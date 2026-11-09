package com.flexhealth.heartmonitor.receiver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flexhealth.heartmonitor.protocol.ProtocolJson
import com.flexhealth.heartmonitor.receiver.BuildConfig
import com.flexhealth.heartmonitor.receiver.overlay.OverlaySize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Modo de conexión del receptor. */
enum class MonitorMode {
    /** Galaxy Watch6 Classic → Wear Data Layer (Bluetooth cuando está cerca) → teléfono. */
    PHONE_BLUETOOTH,
    /** Galaxy Watch6 Classic → Wi-Fi local (WebSocket cifrado) → tablet, sin teléfono. */
    TABLET_WIFI,
    /** Galaxy Watch6 Classic → Bluetooth LE directo (GATT, mismo cifrado) → tablet, sin router ni teléfono. */
    TABLET_BLE,
    ;

    /** Modos en los que este dispositivo actúa como servidor del protocolo seguro (emparejamiento propio). */
    val isHosted: Boolean get() = this == TABLET_WIFI || this == TABLET_BLE
}

/** Preferencias del widget flotante. Las posiciones se guardan como fracciones por orientación. */
@Serializable
data class OverlayPrefs(
    val enabled: Boolean = true,
    val size: OverlaySize = OverlaySize.COMPACT,
    val customWidthDp: Int = 230,
    val customHeightDp: Int = 76,
    val alpha: Float = 0.85f,
    val showGraph: Boolean = true,
    val locked: Boolean = false,
    val gameMode: Boolean = false,
    /** Los toques atraviesan el widget y llegan al juego (FLAG_NOT_TOUCHABLE). */
    val clickThrough: Boolean = false,
    val minimized: Boolean = false,
    val animations: Boolean = true,
    val portraitX: Float = 1f,
    val portraitY: Float = 0.06f,
    val landscapeX: Float? = null,
    val landscapeY: Float? = null,
) {
    fun widthDp() = if (size == OverlaySize.CUSTOM) customWidthDp else size.widthDp
    fun heightDp() = if (size == OverlaySize.CUSTOM) customHeightDp else size.heightDp
}

data class ReceiverConfig(
    val mode: MonitorMode,
    val graphWindowSec: Int = 60,
    val overlay: OverlayPrefs = OverlayPrefs(),
)

private val Context.receiverStore by preferencesDataStore("receiver_settings")

class ReceiverSettings(private val context: Context) {
    private object K {
        val mode = stringPreferencesKey("mode")
        val window = intPreferencesKey("graph_window")
        val overlay = stringPreferencesKey("overlay_json")
    }

    private val defaultMode = runCatching { MonitorMode.valueOf(BuildConfig.DEFAULT_MODE) }.getOrDefault(MonitorMode.PHONE_BLUETOOTH)

    val config: Flow<ReceiverConfig> = context.receiverStore.data.map { p ->
        ReceiverConfig(
            mode = p[K.mode]?.let { runCatching { MonitorMode.valueOf(it) }.getOrNull() } ?: defaultMode,
            graphWindowSec = p[K.window]?.takeIf { it in GRAPH_WINDOWS } ?: 60,
            overlay = p[K.overlay]?.let { runCatching { ProtocolJson.decodeFromString(OverlayPrefs.serializer(), it) }.getOrNull() } ?: OverlayPrefs(),
        )
    }

    suspend fun current() = config.first()

    suspend fun setMode(mode: MonitorMode) = context.receiverStore.edit { it[K.mode] = mode.name }
    suspend fun setGraphWindow(sec: Int) = context.receiverStore.edit { it[K.window] = sec }

    suspend fun updateOverlay(f: (OverlayPrefs) -> OverlayPrefs) = context.receiverStore.edit { p ->
        val cur = p[K.overlay]?.let { runCatching { ProtocolJson.decodeFromString(OverlayPrefs.serializer(), it) }.getOrNull() } ?: OverlayPrefs()
        p[K.overlay] = ProtocolJson.encodeToString(OverlayPrefs.serializer(), f(cur))
    }

    companion object { val GRAPH_WINDOWS = listOf(15, 30, 60, 120) }
}
