package com.flexhealth.heartmonitor.wear.sensor

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointAccuracy
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseInfo
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.SampleDataPoint
import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import java.time.Instant
import kotlin.math.roundToInt

/** Conversión de tipos de Health Services al modelo compartido. */
internal object HealthServicesMapping {

    fun availability(a: Availability): SensorStatus? = when (a) {
        DataTypeAvailability.AVAILABLE -> SensorStatus.ACTIVE
        DataTypeAvailability.ACQUIRING -> SensorStatus.ACQUIRING
        DataTypeAvailability.UNAVAILABLE -> SensorStatus.UNAVAILABLE
        DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> SensorStatus.OFF_BODY
        else -> null
    }

    /*
     * ExerciseEndReason / ExerciseTrackedStatus son anotaciones IntDef marcadas @RestrictTo, pero sus
     * constantes son los valores públicos y documentados de los enteros que devuelven las APIs
     * públicas (ExerciseStateInfo.endReason, ExerciseInfo.exerciseTrackedStatus). Se usan solo aquí.
     */
    @SuppressLint("RestrictedApi")
    fun endReason(reason: Int): SensorStatus = when (reason) {
        ExerciseEndReason.AUTO_END_SUPERSEDED -> SensorStatus.BUSY
        ExerciseEndReason.AUTO_END_PERMISSION_LOST -> SensorStatus.PERMISSION_DENIED
        ExerciseEndReason.USER_END -> SensorStatus.STOPPED
        else -> SensorStatus.STOPPED
    }

    @SuppressLint("RestrictedApi")
    fun isOtherAppInProgress(info: ExerciseInfo) = info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS

    @SuppressLint("RestrictedApi")
    fun isOwnExerciseInProgress(info: ExerciseInfo) = info.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS

    /** Instante de arranque en hora de pared, para convertir "duración desde el arranque" a epoch ms. */
    fun bootInstant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    /**
     * Convierte un punto de datos en un evento. Devuelve un estado (no una lectura) cuando no hay
     * contacto con la piel o el valor no es fisiológicamente plausible: nunca se inventan datos.
     */
    fun toEvent(point: SampleDataPoint<Double>, boot: Instant): SensorEvent {
        val accuracy = point.accuracy
        val quality = quality(accuracy)
        if (accuracy is HeartRateAccuracy && accuracy.sensorStatus == HeartRateAccuracy.SensorStatus.NO_CONTACT) {
            return SensorEvent.Status(SensorStatus.OFF_BODY, "no_contact")
        }
        val bpm = point.value.takeIf { !it.isNaN() }?.roundToInt() ?: return SensorEvent.Status(SensorStatus.ACQUIRING)
        if (bpm !in ProtocolConstants.BPM_MIN..ProtocolConstants.BPM_MAX) return SensorEvent.Status(SensorStatus.ACQUIRING)
        return SensorEvent.Reading(bpm, point.getTimeInstant(boot).toEpochMilli(), quality)
    }

    private fun quality(a: DataPointAccuracy?): ReadingQuality = when ((a as? HeartRateAccuracy)?.sensorStatus) {
        HeartRateAccuracy.SensorStatus.ACCURACY_HIGH -> ReadingQuality.HIGH
        HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> ReadingQuality.MEDIUM
        HeartRateAccuracy.SensorStatus.ACCURACY_LOW -> ReadingQuality.LOW
        HeartRateAccuracy.SensorStatus.UNRELIABLE -> ReadingQuality.UNRELIABLE
        else -> ReadingQuality.UNKNOWN
    }
}
