package com.flexhealth.heartmonitor.wear.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.flexhealth.heartmonitor.core.android.ble.BlePermissions
import com.flexhealth.heartmonitor.lan.ClientPipe
import com.flexhealth.heartmonitor.lan.LanException
import com.flexhealth.heartmonitor.lan.LanLink
import com.flexhealth.heartmonitor.lan.ble.BleFraming
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.wear.transport.ble.BleConnector
import com.flexhealth.heartmonitor.wear.transport.ble.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Envío directo reloj → tablet por Bluetooth LE (sin router, Internet ni teléfono). Reutiliza [LanLink]:
 * mismo protocolo seguro, ping cifrado, detección de pérdida y reconexión con backoff. Primero intenta la
 * última dirección Bluetooth conocida (sin escanear) y, si falla, escanea buscando el hash de SU tablet;
 * alternar limita los escaneos (Android permite ~5 inicios cada 30 s). Un único enlace GATT activo.
 */
@SuppressLint("MissingPermission")
class BleSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val record: PairingRecord,
    lastAddress: String?,
    private val onAddressResolved: suspend (String) -> Unit,
) : TabletSender {
    private val scanner = BleScanner(context)
    private val connector = BleConnector(context)
    private val ridHash = BleFraming.receiverIdHash(record.peerId)
    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE, record.peerName))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    @Volatile private var cached: String? = lastAddress
    @Volatile private var preferCached = true
    private var link: LanLink? = null
    private var mirror: Job? = null

    /** Al volver a encender Bluetooth se reintenta al momento. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) link?.reconnectNow()
        }
    }

    override fun start() {
        if (link != null) return
        val l = LanLink(scope, record, connect = ::connect,
            notFoundMessage = "Tablet no encontrada por Bluetooth (¿fuera de alcance o modo BLE no activo?)")
        link = l
        ContextCompat.registerReceiver(context, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        mirror = scope.launch {
            l.status.collect { s ->
                if (s.state == LinkState.CONNECTED) preferCached = true
                _status.value = s
            }
        }
        l.start()
    }

    private suspend fun connect(): ClientPipe? {
        BlePermissions.problem(context, BlePermissions.central())?.let { throw LanException(it) }
        val adapter = BlePermissions.adapter(context) ?: throw LanException("Bluetooth no disponible")
        val known = cached
        val device = if (preferCached && known != null && BluetoothAdapter.checkBluetoothAddress(known)) {
            preferCached = false
            adapter.getRemoteDevice(known)
        } else {
            preferCached = true
            val found = scanner.find(ridHash, SCAN_TIMEOUT_MS) ?: return null
            cached = found.address
            onAddressResolved(found.address)
            found.device
        }
        return connector.open(device, "Bluetooth LE", scope)
    }

    override fun send(packet: HeartRatePacket): Boolean = link?.send(packet) ?: false

    override fun close() {
        mirror?.cancel(); mirror = null
        link?.close(); link = null
        runCatching { context.unregisterReceiver(stateReceiver) }
    }

    override suspend fun stop() {
        mirror?.cancel(); mirror = null
        link?.stop(); link = null
        runCatching { context.unregisterReceiver(stateReceiver) }
        _status.value = LinkStatus(LinkState.IDLE, record.peerName)
    }

    private companion object { const val SCAN_TIMEOUT_MS = 8_000L }
}
