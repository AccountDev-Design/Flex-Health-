package com.flexhealth.heartmonitor.wear.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.wear.sensor.HeartRateSourceIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class WatchConfig(
    val sourceId: String = HeartRateSourceIds.EXERCISE,
    val sendToPhone: Boolean = true,
    val sendToTablet: Boolean = false,
    /**
     * Mantiene la CPU despierta y vacía los lotes del sensor cada 2 s, **solo mientras hay un receptor
     * conectado**. Sin ello, con la pantalla apagada Health Services entrega en lotes y el receptor alterna
     * entre «en vivo» y «sin señal» (causa C3). Desactivable para ahorrar batería.
     */
    val lowLatency: Boolean = true,
    val tabletPairingId: String? = null,
    val tabletName: String? = null,
    val tabletLastEndpoint: LanEndpoint? = null,
)

private val Context.watchStore by preferencesDataStore("watch_settings")

/** Preferencias del reloj. No contiene secretos: la clave de emparejamiento va al Keystore. */
class WatchSettings(private val context: Context) {
    private object K {
        val source = stringPreferencesKey("source")
        val phone = booleanPreferencesKey("send_phone")
        val tablet = booleanPreferencesKey("send_tablet")
        val lowLatency = booleanPreferencesKey("low_latency")
        val pairingId = stringPreferencesKey("tablet_pairing_id")
        val tabletName = stringPreferencesKey("tablet_name")
        val host = stringPreferencesKey("tablet_host")
        val port = intPreferencesKey("tablet_port")
    }

    val config: Flow<WatchConfig> = context.watchStore.data.map { p ->
        WatchConfig(
            sourceId = p[K.source] ?: HeartRateSourceIds.EXERCISE,
            sendToPhone = p[K.phone] ?: true,
            sendToTablet = p[K.tablet] ?: false,
            lowLatency = p[K.lowLatency] ?: true,
            tabletPairingId = p[K.pairingId],
            tabletName = p[K.tabletName],
            tabletLastEndpoint = p[K.host]?.let { h -> p[K.port]?.let { LanEndpoint(h, it) } },
        )
    }

    suspend fun current(): WatchConfig = config.first()

    suspend fun setSource(id: String) = context.watchStore.edit { it[K.source] = id }
    suspend fun setSendToPhone(v: Boolean) = context.watchStore.edit { it[K.phone] = v }
    suspend fun setSendToTablet(v: Boolean) = context.watchStore.edit { it[K.tablet] = v }
    suspend fun setLowLatency(v: Boolean) = context.watchStore.edit { it[K.lowLatency] = v }

    suspend fun setTablet(pairingId: String, name: String, endpoint: LanEndpoint) = context.watchStore.edit {
        it[K.pairingId] = pairingId; it[K.tabletName] = name; it[K.host] = endpoint.host; it[K.port] = endpoint.port
        it[K.tablet] = true
    }

    suspend fun setLastEndpoint(endpoint: LanEndpoint) = context.watchStore.edit { it[K.host] = endpoint.host; it[K.port] = endpoint.port }

    suspend fun clearTablet() = context.watchStore.edit {
        it.remove(K.pairingId); it.remove(K.tabletName); it.remove(K.host); it.remove(K.port); it[K.tablet] = false
    }
}
