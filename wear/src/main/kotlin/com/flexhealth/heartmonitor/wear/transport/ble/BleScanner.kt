package com.flexhealth.heartmonitor.wear.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.flexhealth.heartmonitor.core.android.ble.BleIds
import com.flexhealth.heartmonitor.core.android.ble.BlePermissions
import com.flexhealth.heartmonitor.lan.LanException
import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/** Tablet Flex Heart detectada por Bluetooth LE. */
class FoundTablet(val device: BluetoothDevice, val name: String, val ridHash: ByteArray?, val rssi: Int) {
    val address: String get() = device.address
}

/**
 * Busca tablets que anuncian el servicio Flex Heart. Solo filtra por el UUID del servicio (no usa la
 * ubicación: BLUETOOTH_SCAN se declara con neverForLocation). Los permisos se comprueban antes de usarlo.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    fun scan(): Flow<List<FoundTablet>> = callbackFlow {
        BlePermissions.problem(context, BlePermissions.central())?.let { close(LanException(it)); awaitClose { }; return@callbackFlow }
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
        if (scanner == null) { close(LanException("Bluetooth no disponible")); awaitClose { }; return@callbackFlow }
        val found = LinkedHashMap<String, FoundTablet>()
        fun add(r: ScanResult) {
            val hash = r.scanRecord?.getServiceData(BleIds.SERVICE_PARCEL)
            val name = r.device.name ?: "Tablet Flex Heart" + (hash?.let { " · " + CryptoBytes.hex(it).take(4) } ?: "")
            synchronized(found) { found[r.device.address] = FoundTablet(r.device, name, hash, r.rssi); trySend(found.values.sortedByDescending { it.rssi }) }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::add)
            override fun onScanFailed(errorCode: Int) { close(LanException("Búsqueda Bluetooth fallida (código $errorCode)")) }
        }
        trySend(emptyList())
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(BleIds.SERVICE_PARCEL).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /** Busca la tablet emparejada por el hash de su id anunciado en la respuesta al escaneo. */
    suspend fun find(ridHash: ByteArray, timeoutMs: Long): FoundTablet? = withTimeoutOrNull(timeoutMs) {
        scan().mapNotNull { list -> list.firstOrNull { it.ridHash?.contentEquals(ridHash) == true } }.first()
    }
}
