package com.flexhealth.heartmonitor.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flexhealth.heartmonitor.receiver.R
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.graph
import com.flexhealth.heartmonitor.receiver.overlay.OverlayController
import com.flexhealth.heartmonitor.receiver.transport.ReceiverTransport
import com.flexhealth.heartmonitor.receiver.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (tipo "connectedDevice") que recibe la FC del reloj y alimenta el
 * motor y el overlay. Permite cerrar la app principal: la sesión continúa mientras el servicio
 * esté activo. Una sola instancia y un solo transporte activo a la vez.
 */
class MonitorService : LifecycleService() {
    private var job: Job? = null
    private var transport: ReceiverTransport? = null
    private var overlay: OverlayController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_SHOW_OVERLAY -> {
                lifecycleScope.launch { graph.settings.updateOverlay { it.copy(enabled = true) } }
                overlay?.showNow()
            }
            ACTION_UNLOCK_OVERLAY -> lifecycleScope.launch { graph.settings.updateOverlay { it.copy(clickThrough = false, locked = false) } }
            else -> startMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (job?.isActive == true) return
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar en primer plano", e); stopSelf(); return
        }
        val g = graph
        val ov = OverlayController(this, lifecycleScope, g.settings, g.engine).also { overlay = it }
        job = lifecycleScope.launch {
            launch { g.engine.runFreshnessTicker() }
            // Cambiar de modo reinicia el transporte y la sesión (sin mezclar datos).
            launch {
                g.settings.config.map { it.mode }.distinctUntilChanged().collectLatest { mode -> switchTransport(mode) }
            }
            launch { g.settings.config.map { it.overlay }.distinctUntilChanged().collect { ov.applyPrefs(it) } }
            launch { g.settings.config.map { it.graphWindowSec }.distinctUntilChanged().collect { ov.setGraphWindow(it) } }
            launch { combine(g.engine.snapshot, g.linkStatus) { s, l -> s to l }.collect { (s, l) -> ov.update(s, l) } }
        }
    }

    private fun switchTransport(mode: MonitorMode) {
        val g = graph
        transport?.stop()
        g.engine.reset()
        val t = g.transportFor(mode)
        transport = t
        g.activeMode.value = mode
        t.start { packet -> g.engine.onPacket(packet) }
    }

    private fun stopMonitoring() {
        job?.cancel(); job = null
        transport?.stop(); transport = null
        overlay?.release(); overlay = null
        graph.activeMode.value = null
        graph.engine.reset() // no se conservan datos de FC al detener
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        job?.cancel()
        transport?.stop()
        overlay?.release()
        graph.activeMode.value = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, MonitorService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.notif_stop), action(ACTION_STOP, 1))
            .addAction(0, getString(R.string.notif_show_overlay), action(ACTION_SHOW_OVERLAY, 2))
            .addAction(0, getString(R.string.notif_unlock_overlay), action(ACTION_UNLOCK_OVERLAY, 3))
            .build()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 7
        const val ACTION_START = "com.flexhealth.heartmonitor.START"
        const val ACTION_STOP = "com.flexhealth.heartmonitor.STOP"
        const val ACTION_SHOW_OVERLAY = "com.flexhealth.heartmonitor.SHOW_OVERLAY"
        const val ACTION_UNLOCK_OVERLAY = "com.flexhealth.heartmonitor.UNLOCK_OVERLAY"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(ACTION_START))

        fun stop(context: Context) = context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
    }
}
