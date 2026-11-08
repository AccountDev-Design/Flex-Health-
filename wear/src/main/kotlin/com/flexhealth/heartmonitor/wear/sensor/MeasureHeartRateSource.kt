package com.flexhealth.heartmonitor.wear.sensor

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.flexhealth.heartmonitor.protocol.SensorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await

/**
 * Fuente basada en Health Services MeasureClient. Entrega datos rápidos mientras la app está en
 * primer plano; está pensada para lecturas puntuales, no para sesiones largas con la pantalla
 * apagada. No crea un "ejercicio", por lo que no interfiere con Samsung Health.
 */
class MeasureHeartRateSource(private val context: Context) : HeartRateSource {
    override val id = HeartRateSourceIds.MEASURE

    private val client get() = HealthServices.getClient(context).measureClient

    override suspend fun isSupported(): Boolean = try {
        DataType.HEART_RATE_BPM in client.getCapabilitiesAsync().await().supportedDataTypesMeasure
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "MeasureClient no disponible", e); false
    }

    override fun events(): Flow<SensorEvent> = callbackFlow {
        val measure = client
        val boot = HealthServicesMapping.bootInstant()
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                if (dataType == DataType.HEART_RATE_BPM) HealthServicesMapping.availability(availability)?.let { trySend(SensorEvent.Status(it)) }
            }
            override fun onDataReceived(data: DataPointContainer) {
                for (p in data.getData(DataType.HEART_RATE_BPM)) trySend(HealthServicesMapping.toEvent(p, boot))
            }
            override fun onRegistrationFailed(throwable: Throwable) {
                trySend(SensorEvent.Status(SensorStatus.ERROR, throwable.message)); close()
            }
        }
        try {
            if (!isSupported()) {
                send(SensorEvent.Status(SensorStatus.UNAVAILABLE, "heart_rate_not_supported"))
            } else {
                measure.registerMeasureCallback(DataType.HEART_RATE_BPM, ContextCompat.getMainExecutor(context), callback)
                send(SensorEvent.Status(SensorStatus.ACQUIRING))
            }
        } catch (e: SecurityException) {
            send(SensorEvent.Status(SensorStatus.PERMISSION_DENIED, e.message))
        }
        awaitClose { runCatching { measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) } }
    }

    private companion object { const val TAG = "MeasureHrSource" }
}
