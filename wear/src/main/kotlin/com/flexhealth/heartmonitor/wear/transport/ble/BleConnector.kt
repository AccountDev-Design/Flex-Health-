package com.flexhealth.heartmonitor.wear.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.flexhealth.heartmonitor.core.android.ble.BleIds
import com.flexhealth.heartmonitor.lan.LanException
import com.flexhealth.heartmonitor.lan.ble.FramedClientPipe
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cliente GATT del reloj. Abre UNA conexión con la tablet, negocia MTU, activa notificaciones y devuelve una
 * [FramedClientPipe] sobre la que corre el protocolo seguro. Las operaciones GATT se serializan (Android
 * solo admite una operación pendiente por conexión). Los callbacks llegan en hilos Binder, nunca en el de UI.
 */
@SuppressLint("MissingPermission")
class BleConnector(private val context: Context) {

    suspend fun open(device: BluetoothDevice, label: String, scope: CoroutineScope, connectTimeoutMs: Long = 12_000): FramedClientPipe {
        val session = GattSession(device, label, scope)
        try {
            return session.establish(connectTimeoutMs)
        } catch (e: Throwable) {
            session.close()
            throw if (e is LanException) e else LanException("Bluetooth: ${e.message ?: e}", cause = e)
        }
    }

    private inner class GattSession(private val device: BluetoothDevice, private val label: String, private val scope: CoroutineScope) : BluetoothGattCallback() {
        private val connected = CompletableDeferred<Unit>()
        private val discovered = CompletableDeferred<Unit>()
        private val mtuNegotiated = CompletableDeferred<Unit>()
        private val notificationsEnabled = CompletableDeferred<Boolean>()
        @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
        private val writeLock = Mutex()
        @Volatile private var mtu = ProtocolConstants.BLE_DEFAULT_MTU
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var rx: BluetoothGattCharacteristic? = null
        @Volatile private var pipe: FramedClientPipe? = null
        @Volatile private var closed = false

        suspend fun establish(timeoutMs: Long): FramedClientPipe {
            val g = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) ?: throw LanException("No se pudo iniciar la conexión Bluetooth")
            gatt = g
            withTimeout(timeoutMs) { connected.await() }
            if (!g.discoverServices()) throw LanException("No se pudieron descubrir los servicios")
            withTimeout(10_000) { discovered.await() }
            val service = g.getService(BleIds.SERVICE) ?: throw LanException("La tablet no ofrece el servicio Flex Heart (¿modo BLE activo?)")
            val rxChar = service.getCharacteristic(BleIds.RX) ?: throw LanException("Servicio Flex Heart incompleto")
            val txChar = service.getCharacteristic(BleIds.TX) ?: throw LanException("Servicio Flex Heart incompleto")
            rx = rxChar
            // MTU grande = menos fragmentos y menos radio. Si la pila no responde se mantiene 23.
            if (g.requestMtu(ProtocolConstants.BLE_REQUESTED_MTU)) withTimeoutOrNull(3_000) { mtuNegotiated.await() }
            val p = FramedClientPipe(label, scope, { mtu }, ::writeChunk, release = { close() })
            pipe = p
            if (!g.setCharacteristicNotification(txChar, true)) throw LanException("No se pudieron activar las notificaciones")
            val cccd = txChar.getDescriptor(BleIds.CCCD) ?: throw LanException("Falta el descriptor de notificaciones")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started || withTimeout(5_000) { notificationsEnabled.await() } != true) throw LanException("La tablet rechazó las notificaciones")
            return p
        }

        /** Una escritura con respuesta cada vez; espera la confirmación de la tablet. */
        private suspend fun writeChunk(chunk: ByteArray): Boolean = writeLock.withLock {
            val g = gatt; val c = rx
            if (closed || g == null || c == null) return@withLock false
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!started) { pendingWrite = null; return@withLock false }
            withTimeoutOrNull(5_000) { done.await() } ?: false
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else {
                val e = LanException("Bluetooth desconectado (estado $status)")
                connected.completeExceptionally(e); discovered.completeExceptionally(e)
                notificationsEnabled.complete(false); mtuNegotiated.complete(Unit)
                pendingWrite?.complete(false)
                pipe?.onDisconnected(e.message!!)
                close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(LanException("Descubrimiento de servicios fallido ($status)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            mtuNegotiated.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == BleIds.CCCD) notificationsEnabled.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BleIds.TX) pipe?.onChunk(value)
        }

        @Deprecated("Android 12 y anteriores")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == BleIds.TX) {
                characteristic.value?.copyOf()?.let { pipe?.onChunk(it) }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            pendingWrite?.complete(false)
            gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
            gatt = null
        }
    }
}
