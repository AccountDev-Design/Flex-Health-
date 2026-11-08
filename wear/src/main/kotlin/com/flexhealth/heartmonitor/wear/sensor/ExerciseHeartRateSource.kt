package com.flexhealth.heartmonitor.wear.sensor

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseInfo
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.flexhealth.heartmonitor.protocol.SensorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await

/**
 * Fuente basada en Health Services ExerciseClient: la vía oficial para monitorización continua
 * en Wear OS, también con la pantalla apagada (debe ejecutarse en un servicio en primer plano).
 *
 * Restricciones verificadas en la documentación oficial (ver DEVICE_COMPATIBILITY.md):
 * - Solo puede haber un ejercicio activo en todo el dispositivo. Si otra app (p. ej. Samsung
 *   Health) tiene uno en curso, no se interrumpe sin permiso explícito ([allowTakeover]).
 * - Con la pantalla apagada Health Services agrupa las entregas. Si el dispositivo lo soporta se
 *   solicita BatchingMode.HEART_RATE_5_SECONDS (pensado para transmitir FC a otro dispositivo).
 *   No se garantiza una entrega fija de 1 Hz.
 */
class ExerciseHeartRateSource(
    private val context: Context,
    private val allowTakeover: Boolean = false,
) : HeartRateSource {
    override val id = HeartRateSourceIds.EXERCISE

    private val client get() = HealthServices.getClient(context).exerciseClient

    override suspend fun isSupported(): Boolean = try {
        chooseType(client.getCapabilitiesAsync().await()) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "ExerciseClient no disponible", e); false
    }

    override fun events(): Flow<SensorEvent> = callbackFlow {
        val exercise = client
        val boot = HealthServicesMapping.bootInstant()
        val callback = object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) {
                trySend(SensorEvent.Status(SensorStatus.ERROR, throwable.message)); close()
            }
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                for (p in update.latestMetrics.getData(DataType.HEART_RATE_BPM)) trySend(HealthServicesMapping.toEvent(p, boot))
                val state = update.exerciseStateInfo
                if (state.state.isEnded) {
                    trySend(SensorEvent.Status(HealthServicesMapping.endReason(state.endReason), "exercise_ended"))
                    close()
                }
            }
            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                if (dataType == DataType.HEART_RATE_BPM) HealthServicesMapping.availability(availability)?.let { trySend(SensorEvent.Status(it)) }
            }
        }
        try {
            val caps = exercise.getCapabilitiesAsync().await()
            val type = chooseType(caps)
            if (type == null) {
                send(SensorEvent.Status(SensorStatus.UNAVAILABLE, "heart_rate_not_supported"))
                awaitClose { }
                return@callbackFlow
            }
            val info: ExerciseInfo = exercise.getCurrentExerciseInfoAsync().await()
            if (HealthServicesMapping.isOtherAppInProgress(info) && !allowTakeover) {
                send(SensorEvent.Status(SensorStatus.BUSY, "other_app_in_progress"))
                awaitClose { }
                return@callbackFlow
            }
            // Ejercicio propio huérfano (p. ej. el proceso murió): se cierra para empezar limpio.
            if (HealthServicesMapping.isOwnExerciseInProgress(info)) runCatching { exercise.endExerciseAsync().await() }
            exercise.setUpdateCallback(ContextCompat.getMainExecutor(context), callback)
            val batching = caps.supportedBatchingModeOverrides.filter { it == BatchingMode.HEART_RATE_5_SECONDS }.toSet()
            val config = ExerciseConfig.Builder(type)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
                .setBatchingModeOverrides(batching)
                .build()
            exercise.startExerciseAsync(config).await()
            send(SensorEvent.Status(SensorStatus.ACQUIRING, if (batching.isEmpty()) "batching_default" else "batching_hr_5s"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            send(SensorEvent.Status(SensorStatus.PERMISSION_DENIED, e.message))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el ejercicio", e)
            send(SensorEvent.Status(SensorStatus.ERROR, e.message))
        }
        awaitClose {
            // Libera el sensor: termina el ejercicio y quita el callback (operaciones asíncronas).
            runCatching { exercise.endExerciseAsync() }
            runCatching { exercise.clearUpdateCallbackAsync(callback) }
        }
    }

    private fun chooseType(caps: ExerciseCapabilities): ExerciseType? {
        val candidates = listOf(ExerciseType.WORKOUT) + caps.supportedExerciseTypes
        return candidates.firstOrNull { t ->
            t in caps.supportedExerciseTypes && DataType.HEART_RATE_BPM in caps.getExerciseTypeCapabilities(t).supportedDataTypes
        }
    }

    private companion object { const val TAG = "ExerciseHrSource" }
}
