package com.flexhealth.heartmonitor.receiver.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.core.android.ble.BleIds
import com.flexhealth.heartmonitor.core.android.ble.BlePermissions
import com.flexhealth.heartmonitor.lan.PairingHost
import com.flexhealth.heartmonitor.lan.SessionConfig
import com.flexhealth.heartmonitor.lan.SessionHost
import com.flexhealth.heartmonitor.lan.ble.BleFraming
import com.flexhealth.heartmonitor.lan.ble.FramedServerPipe
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth LE directo reloj → tablet, sin router, Internet ni intermediarios. La tablet es servidor GATT
 * y anunciante; el reloj se conecta como cliente. Sobre GATT viaja el MISMO protocolo seguro que por Wi-Fi
 * (emparejamiento ECDH P-256 con código, autenticación mutua, AES-256-GCM con anti-replay), gestionado por
 * [SessionHost]. No se usa el emparejamiento/bonding del sistema.
 *
 * Todos los métodos que usan Bluetooth comprueban antes los permisos ([BlePermissions.problem]).
 */
@SuppressLint("MissingPermission")
class BleReceiver(private val context: Context, store: PairingStore, paired: PairedDevices) : HostedReceiver(store, paired) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    @Volatile private var host: SessionHost? = null
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var advertising = false
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val pipes = ConcurrentHashMap<String, FramedServerPipe>()
    private val mtus = ConcurrentHashMap<String, Int>()

    override val pairingHost: PairingHost? get() = host
    override val ready: Boolean get() = advertising

    override fun waitingDetail(base: String) = "$base · anunciando por Bluetooth LE"

    override fun start(sink: (HeartRatePacket) -> Unit) {
        this.sink = sink
        if (host != null) return
        BlePermissions.problem(context, BlePermissions.peripheral())?.let {
            _status.value = LinkStatus(LinkState.UNAVAILABLE, detail = it); return
        }
        val adapter = manager?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported) {
            _status.value = LinkStatus(LinkState.UNAVAILABLE, detail = "Este dispositivo no puede anunciarse por Bluetooth LE"); return
        }
        val h = SessionHost(SessionConfig(DeviceIdentity.id(context), DeviceIdentity.displayName()), store, listener, threadName = "flexheart-ble-timer")
        host = h
        h.start()
        _status.value = LinkStatus(LinkState.SEARCHING, detail = "Iniciando servidor Bluetooth LE")
        val gatt = manager.openGattServer(context, callback)
        if (gatt == null) { fail("No se pudo abrir el servidor GATT"); return }
        server = gatt
        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BleIds.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(BleIds.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        tx.addDescriptor(BluetoothGattDescriptor(BleIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx); service.addCharacteristic(tx)
        txCharacteristic = tx
        if (!gatt.addService(service)) fail("No se pudo registrar el servicio GATT")
        // El anuncio empieza en onServiceAdded.
    }

    private fun startAdvertising() {
        val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return fail("Anunciante Bluetooth no disponible")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Anuncio (≤ 31 bytes): flags + UUID de 128 bits del servicio.
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(BleIds.SERVICE_PARCEL).build()
        // Respuesta al escaneo: hash corto del id del receptor para que el reloj encuentre SU tablet.
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(BleIds.SERVICE_PARCEL, BleFraming.receiverIdHash(DeviceIdentity.id(context))).build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true; waiting("Esperando al reloj") }
        override fun onStartFailure(errorCode: Int) { fail("No se pudo anunciar por Bluetooth LE (código $errorCode)") }
    }

    private fun fail(detail: String) {
        Log.w(TAG, detail)
        _status.value = LinkStatus(LinkState.ERROR, detail = detail)
    }

    /** Envía un fragmento por notificación. Una sola notificación en vuelo por dispositivo (lo garantiza FramedServerPipe). */
    private fun notify(device: BluetoothDevice, chunk: ByteArray): Boolean {
        val gatt = server ?: return false
        val tx = txCharacteristic ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothStatusCodes.SUCCESS
        } else synchronized(tx) {
            @Suppress("DEPRECATION")
            tx.value = chunk
            @Suppress("DEPRECATION")
            gatt.notifyCharacteristicChanged(device, tx, false)
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == BleIds.SERVICE) startAdvertising()
            else fail("Servicio GATT rechazado (estado $status)")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Una reconexión del mismo dispositivo sustituye cualquier tubería anterior (sin duplicados).
                pipes.remove(address)?.onDisconnected("reconnected")
                val h = host ?: return
                val pipe = FramedServerPipe(
                    address = address,
                    mtu = { mtus[address] ?: ProtocolConstants.BLE_DEFAULT_MTU },
                    transmit = { chunk -> notify(device, chunk) },
                    disconnect = { server?.cancelConnection(device) },
                )
                pipes[address] = pipe
                pipe.session = h.attach(pipe)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(address)
                pipes.remove(address)?.onDisconnected("Bluetooth desconectado (estado $status)")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { mtus[device.address] = mtu }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = characteristic.uuid == BleIds.RX && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
            if (ok) pipes[device.address]?.onChunk(value!!)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = descriptor.uuid == BleIds.CCCD
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pipes[device.address]?.onChunkSent(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    override fun stop() {
        closePairingWindow()
        if (BlePermissions.granted(context, BlePermissions.peripheral())) {
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            pipes.keys.toList().forEach { addr -> runCatching { server?.cancelConnection(manager.adapter.getRemoteDevice(addr)) } }
            runCatching { server?.close() }
        }
        pipes.values.forEach { it.onDisconnected("stopped") }
        pipes.clear(); mtus.clear()
        server = null
        advertising = false
        host?.close(); host = null
        sink = null
        _status.value = LinkStatus(LinkState.IDLE)
    }

    private companion object { const val TAG = "BleReceiver" }
}
