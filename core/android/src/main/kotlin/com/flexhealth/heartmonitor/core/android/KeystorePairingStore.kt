package com.flexhealth.heartmonitor.core.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.flexhealth.heartmonitor.protocol.ProtocolJson
import com.flexhealth.heartmonitor.protocol.crypto.PairingRecord
import com.flexhealth.heartmonitor.protocol.crypto.PairingStore
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persiste los emparejamientos cifrados con AES-256-GCM usando una clave no exportable del
 * Android Keystore. El fichero vive en noBackupFilesDir: no se incluye en copias de seguridad
 * en la nube ni se transfiere a otros dispositivos.
 */
class KeystorePairingStore(context: Context, fileName: String = "pairings.bin") : PairingStore {
    private val file = File(context.noBackupFilesDir, fileName)
    private var cache: MutableMap<String, PairingRecord>? = null

    @Synchronized override fun find(pairingId: String): PairingRecord? = load()[pairingId]
    @Synchronized override fun all(): List<PairingRecord> = load().values.sortedByDescending { it.createdAtMs }
    @Synchronized override fun save(record: PairingRecord) { load()[record.pairingId] = record; persist() }
    @Synchronized override fun delete(pairingId: String) { if (load().remove(pairingId) != null) persist() }

    private fun load(): MutableMap<String, PairingRecord> {
        cache?.let { return it }
        val map = LinkedHashMap<String, PairingRecord>()
        if (file.exists()) {
            try {
                val bytes = file.readBytes()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
                val json = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
                ProtocolJson.decodeFromString(ListSerializer(PairingRecord.serializer()), json).forEach { map[it.pairingId] = it }
            } catch (e: Exception) {
                // Clave del Keystore invalidada (p. ej. tras restaurar el dispositivo): se descarta y
                // el usuario debe volver a emparejar. Nunca se intenta "recuperar" la clave.
                Log.w(TAG, "No se pudieron leer los emparejamientos; se descartan", e)
                file.delete()
            }
        }
        cache = map
        return map
    }

    private fun persist() {
        val json = ProtocolJson.encodeToString(ListSerializer(PairingRecord.serializer()), load().values.toList())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(json.encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TAG = "KeystorePairingStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "flexheart_pairing_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
