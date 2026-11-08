package com.flexhealth.heartmonitor.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.core.android.DiscoveredReceiver
import com.flexhealth.heartmonitor.core.android.NsdBrowser
import com.flexhealth.heartmonitor.lan.LanClients
import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.lan.pairWithReceiver
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.wear.transport.WifiNetworkRequester
import com.flexhealth.heartmonitor.wear.wearApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface PairingUi {
    data object Idle : PairingUi
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
