package com.flexhealth.heartmonitor.core.android

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.UUID

/**
 * Identificador aleatorio de instalación (no usa IMEI, MAC ni otros identificadores de hardware).
 */
object DeviceIdentity {
    private const val PREFS = "flexheart_identity"
    private const val KEY_ID = "installation_id"

    @Synchronized
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(24)
        prefs.edit { putString(KEY_ID, id) }
        return id
    }

    /** Modelo real del dispositivo (p. ej. "SM-R950"), detectado en tiempo de ejecución. */
    val model: String get() = Build.MODEL.take(48).ifBlank { "unknown" }

    fun displayName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim().take(48)
}
