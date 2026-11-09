package com.flexhealth.heartmonitor.core.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import java.util.UUID

/** Identificadores GATT del servicio Flex Heart (definidos en ProtocolConstants). */
object BleIds {
    val SERVICE: UUID = UUID.fromString(ProtocolConstants.BLE_SERVICE_UUID)
    val RX: UUID = UUID.fromString(ProtocolConstants.BLE_RX_UUID)
    val TX: UUID = UUID.fromString(ProtocolConstants.BLE_TX_UUID)
    val CCCD: UUID = UUID.fromString(ProtocolConstants.BLE_CCCD_UUID)
    val SERVICE_PARCEL: ParcelUuid = ParcelUuid(SERVICE)
}

/** Permisos Bluetooth modernos (Android 12+) con alternativa para Android 11. */
object BlePermissions {
    /** Reloj (cliente/central): buscar y conectar. En Android 11 el escaneo exige ubicación. */
    fun central(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Tablet (servidor/periférico): anunciar y aceptar conexiones. En Android 11 son permisos de instalación. */
    fun peripheral(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

    fun granted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Motivo legible si Bluetooth no puede usarse ahora, o `null` si está listo. */
    fun problem(context: Context, permissions: Array<String>): String? {
        val adapter = adapter(context) ?: return "Este dispositivo no tiene Bluetooth"
        if (!granted(context, permissions)) return "Falta el permiso de Bluetooth cercano"
        @Suppress("MissingPermission")
        if (!adapter.isEnabled) return "Bluetooth desactivado"
        return null
    }
}
