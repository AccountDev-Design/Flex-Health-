package com.flexhealth.heartmonitor.wear.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.flexhealth.heartmonitor.core.android.DeviceIdentity
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.wear.R
import com.flexhealth.heartmonitor.wear.sensor.ExerciseHeartRateSource
import com.flexhealth.heartmonitor.wear.sensor.HeartRateSource
import com.flexhealth.heartmonitor.wear.sensor.HeartRateSourceIds
import com.flexhealth.heartmonitor.wear.sensor.MeasureHeartRateSource
import com.flexhealth.heartmonitor.wear.sensor.SensorEvent
import com.flexhealth.heartmonitor.wear.transport.DataLayerSender
import com.flexhealth.heartmonitor.wear.transport.LanSender
import com.flexhealth.heartmonitor.wear.ui.WearMainActivity
import com.flexhealth.heartmonitor.wear.wearApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Servicio en primer plano (tipo "health") que mantiene la sesión de monitorización: activa la
 * fuente de FC elegida, construye los paquetes y los reparte entre los transportes activos.
 * Un solo servicio, una sola sesión: llamadas repetidas a START se ignoran.
 */
class MonitoringService : LifecycleService() {
    private var session: Job? = null
    private var phone: DataLayerSender? = null
    private var tablet: LanSender? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastSentElapsed = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSession(); return START_NOT_STICKY }
            ACTION_START -> startSession(intent.getBooleanExtra(EXTRA_TAKEOVER, false))
        }
        return START_NOT_STICKY // tras un cierre del sistema no se reanuda sin que el usuario lo pida
    }

    private fun startSession(allowTakeover: Boolean) {
        if (session?.isActive == true) return
        if (!goForeground()) return
        session = lifecycleScope.launch {
            val cfg = wearApp.settings.current()
            val source: HeartRateSource = when (cfg.sourceId) {
                HeartRateSourceIds.MEASURE -> MeasureHeartRateSource(this@MonitoringService)
                else -> ExerciseHeartRateSource(this@MonitoringService, allowTakeover)
            }
            val factory = PacketFactory(DeviceIdentity.model, source.id)
            MonitoringRepository.update {
                WatchMonitorState(running = true, sourceId = source.id, sessionId = factory.sessionId,
                    sensorStatus = SensorStatus.ACQUIRING, lowLatency = cfg.lowLatency)
            }
            if (cfg.lowLatency) acquireWakeLock()

            if (cfg.sendToPhone) {
                val p = DataLayerSender(this@MonitoringService, this).also { phone = it }
                p.start()
                launch { p.status.collect { s -> MonitoringRepository.update { it.copy(phone = s) } } }
            }
            val record = cfg.tabletPairingId?.let { wearApp.pairings.find(it) }
            if (cfg.sendToTablet && record != null) {
                val t = LanSender(this@MonitoringService, this, record, cfg.tabletLastEndpoint) { ep ->
                    wearApp.settings.setLastEndpoint(ep)
                }.also { tablet = it }
                t.start()
                launch { t.status.collect { s -> MonitoringRepository.update { it.copy(tablet = s) } } }
            }

            fun dispatch(packet: HeartRatePacket) {
                phone?.send(packet)
                tablet?.send(packet)
                lastSentElapsed = SystemClock.elapsedRealtime()
                MonitoringRepository.update { it.copy(packetsSent = it.packetsSent + 1) }
            }

            // Latido de estado: si no hay lecturas, informa del estado (nunca de un BPM).
            launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    if (SystemClock.elapsedRealtime() - lastSentElapsed >= HEARTBEAT_MS) dispatch(factory.status())
                }
            }

            source.events().collect { ev ->
                when (ev) {
                    is SensorEvent.Reading -> {
                        dispatch(factory.sample(ev.bpm, ev.timestampMs, ev.quality))
                        MonitoringRepository.update {
                            it.copy(sensorStatus = SensorStatus.ACTIVE, sensorDetail = null, lastBpm = ev.bpm, lastQuality = ev.quality,
                                lastReadingElapsed = SystemClock.elapsedRealtime(), readings = it.readings + 1)
                        }
                    }
                    is SensorEvent.Status -> {
                        dispatch(factory.status(ev.status))
                        MonitoringRepository.update { it.copy(sensorStatus = ev.status, sensorDetail = ev.detail) }
                    }
                }
            }
            // La fuente terminó (p. ej. otra app tomó el ejercicio): se informa y se cierra la sesión.
            dispatch(factory.status(MonitoringRepository.state.value.sensorStatus.takeIf { it != SensorStatus.ACTIVE } ?: SensorStatus.STOPPED))
            stopSession(keepStatus = true)
        }
    }

    private fun stopSession(keepStatus: Boolean = false) {
        val job = session
        session = null
        lifecycleScope.launch {
            job?.cancel(); job?.join()
            withContext(NonCancellable) { releaseTransports() }
            releaseWakeLock()
            val last = MonitoringRepository.state.value
            if (keepStatus) MonitoringRepository.update { WatchMonitorState(sensorStatus = last.sensorStatus, sensorDetail = last.sensorDetail) }
            else MonitoringRepository.reset()
            ServiceCompat.stopForeground(this@MonitoringService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun releaseTransports() {
        phone?.stop(); phone = null
        tablet?.stop(); tablet = null
    }

    private fun goForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            // SecurityException si falta el permiso de FC (requisito del tipo "health").
            Log.e(TAG, "No se pudo iniciar el servicio en primer plano", e)
            MonitoringRepository.update { it.copy(running = false, sensorStatus = SensorStatus.PERMISSION_DENIED, sensorDetail = e.message) }
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_monitoring))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
        // Ongoing Activity: acceso rápido desde la esfera/recientes en Wear OS.
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_heart)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.notif_monitoring)).build())
            .build()
            .apply(this)
        return builder.build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlexHeart:lowLatency").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS) // límite de seguridad: nunca indefinido
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        // Sin runBlocking: las corrutinas de los transportes usan el hilo principal y bloquearlo aquí
        // provocaría un interbloqueo. Se usa el cierre síncrono.
        session?.cancel()
        phone?.stop(); phone = null
        tablet?.close(); tablet = null
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL = "monitoring"
        private const val NOTIFICATION_ID = 42
        private const val HEARTBEAT_MS = 2_000L
        private const val MAX_WAKELOCK_MS = 4 * 60 * 60 * 1000L
        const val ACTION_START = "com.flexhealth.heartmonitor.wear.START"
        const val ACTION_STOP = "com.flexhealth.heartmonitor.wear.STOP"
        const val EXTRA_TAKEOVER = "takeover"

        fun intent(context: Context, action: String) = Intent(context, MonitoringService::class.java).setAction(action)

        fun start(context: Context, allowTakeover: Boolean = false) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_START).putExtra(EXTRA_TAKEOVER, allowTakeover))

        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))
    }
}
