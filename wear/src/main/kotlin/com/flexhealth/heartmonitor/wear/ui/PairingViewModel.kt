package com.flexhealth.heartmonitor.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.core.android.DiscoveredReceiver
import com.flexhealth.heartmonitor.core.android.NsdBrowser
import com.flexhealth.heartmonitor.lan.LanClients
import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.lan.LocalAddressAlias
import com.flexhealth.heartmonitor.lan.pairOverPipe
import com.flexhealth.heartmonitor.lan.pairWithReceiver
import com.flexhealth.heartmonitor.wear.transport.ble.BleConnector
import com.flexhealth.heartmonitor.wear.transport.ble.BleScanner
import com.flexhealth.heartmonitor.wear.transport.ble.FoundTablet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.wear.transport.WifiNetworkRequester
import com.flexhealth.heartmonitor.wear.wearApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface PairingUi {
    data object Idle : PairingUi
    data class BleSearching(val found: List<FoundTablet>) : PairingUi
    data object NoWifi : PairingUi
    data class Searching(val found: List<DiscoveredReceiver>) : PairingUi
    data class Connecting(val target: String) : PairingUi
    data class Code(val code: String, val receiverName: String) : PairingUi
    data object WaitingTablet : PairingUi
    data class Success(val receiverName: String) : PairingUi
    data class Failed(val message: String) : PairingUi
}

/** Emparejamiento del reloj con una tablet en la misma red local. */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = WifiNetworkRequester(app)
    private val nsd = NsdBrowser(app)
    private val client = LanClients.okHttp(OkHttpClient.Builder().socketFactory(wifi.socketFactory))
    private val bleScanner = BleScanner(app)
    private val bleConnector = BleConnector(app)
    /** E/S Bluetooth fuera del hilo de UI, ligada al ciclo de vida del ViewModel. */
    private val ioScope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.IO)
    private val _ui = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val ui: StateFlow<PairingUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var confirm: CompletableDeferred<Boolean>? = null

    fun startDiscovery() {
        job?.cancel()
        wifi.request()
        _ui.value = PairingUi.Searching(emptyList())
        job = viewModelScope.launch {
            val network = wifi.await(20_000)
            if (network == null) { _ui.value = PairingUi.NoWifi; return@launch }
            try {
                nsd.discover(network).collect { _ui.value = PairingUi.Searching(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = PairingUi.Failed("mDNS: ${e.message}")
            }
        }
    }

    /** Acepta "192.168.1.20" o "192.168.1.20:47820". */
    fun pairManual(text: String) {
        val t = text.trim()
        val host = t.substringBeforeLast(':').takeIf { t.count { it == ':' } == 1 } ?: t
        val port = t.substringAfterLast(':', "").toIntOrNull()?.takeIf { t.count { it == ':' } == 1 } ?: ProtocolConstants.LAN_DEFAULT_PORT
        if (host.isBlank() || port !in 1..65535) { _ui.value = PairingUi.Failed("Dirección inválida"); return }
        if (LocalAddressAlias.aliasFor(host) == null) { _ui.value = PairingUi.Failed("Introduce la IP local de la tablet (p. ej. 192.168.1.20)"); return }
        pair(LanEndpoint(host, port), host)
    }

    fun pair(receiver: DiscoveredReceiver) = pair(receiver.endpoint, receiver.name)

    private fun pair(endpoint: LanEndpoint, label: String) {
        job?.cancel()
        _ui.value = PairingUi.Connecting(label)
        val app = getApplication<Application>()
        job = viewModelScope.launch {
            if (wifi.await(20_000) == null) { _ui.value = PairingUi.NoWifi; return@launch }
            try {
                // ECDH, HKDF y Android Keystore fuera del hilo principal (causa P2).
                withContext(Dispatchers.IO) {
                val record = pairWithReceiver(
                    client, endpoint, DeviceIdentity.id(app), DeviceIdentity.displayName(),
                    onCode = { code, name -> _ui.value = PairingUi.Code(code, name) },
                    awaitUserConfirm = {
                        val d = CompletableDeferred<Boolean>().also { confirm = it }
                        d.await().also { ok -> if (ok) _ui.value = PairingUi.WaitingTablet }
                    },
                )
                app.wearApp.pairings.all().filter { it.pairingId != record.pairingId }.forEach { app.wearApp.pairings.delete(it.pairingId) }
                app.wearApp.pairings.save(record)
                app.wearApp.settings.setTablet(record.pairingId, record.peerName, endpoint)
                _ui.value = PairingUi.Success(record.peerName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = PairingUi.Failed(e.message ?: e.toString())
            }
        }
    }

    fun fail(message: String) { job?.cancel(); _ui.value = PairingUi.Failed(message) }

    fun chooseMethod() { job?.cancel(); _ui.value = PairingUi.Idle }

    /** Busca tablets Flex Heart por Bluetooth LE (los permisos se piden antes desde la pantalla). */
    fun startBleDiscovery() {
        job?.cancel()
        _ui.value = PairingUi.BleSearching(emptyList())
        job = ioScope.launch {
            try {
                bleScanner.scan().collect { _ui.value = PairingUi.BleSearching(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = PairingUi.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Emparejamiento por Bluetooth LE: mismo protocolo seguro (ECDH + código) que por Wi-Fi. */
    fun pairBle(tablet: FoundTablet) {
        job?.cancel()
        _ui.value = PairingUi.Connecting(tablet.name)
        val app = getApplication<Application>()
        job = ioScope.launch {
            try {
                val pipe = bleConnector.open(tablet.device, tablet.name, ioScope)
                val record = pairOverPipe(
                    pipe, DeviceIdentity.id(app), DeviceIdentity.displayName(),
                    onCode = { code, name -> _ui.value = PairingUi.Code(code, name) },
                    awaitUserConfirm = {
                        val d = CompletableDeferred<Boolean>().also { confirm = it }
                        d.await().also { ok -> if (ok) _ui.value = PairingUi.WaitingTablet }
                    },
                )
                app.wearApp.pairings.all().filter { it.pairingId != record.pairingId }.forEach { app.wearApp.pairings.delete(it.pairingId) }
                app.wearApp.pairings.save(record)
                app.wearApp.settings.setTabletBle(record.pairingId, record.peerName, tablet.address)
                _ui.value = PairingUi.Success(record.peerName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = PairingUi.Failed(e.message ?: e.toString())
            }
        }
    }

    fun answerCode(matches: Boolean) { confirm?.complete(matches); confirm = null }

    override fun onCleared() {
        confirm?.complete(false)
        job?.cancel()
        wifi.release()
        nsd.close()
    }
}
