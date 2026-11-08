package com.flexhealth.heartmonitor.receiver.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import com.flexhealth.heartmonitor.receiver.graph
import com.flexhealth.heartmonitor.receiver.overlay.OverlayGeometry
import com.flexhealth.heartmonitor.receiver.overlay.OverlaySize
import com.flexhealth.heartmonitor.receiver.overlay.OverlayWidgetView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Genera capturas (PNG) de la UI y del overlay con el renderizador nativo de Robolectric para
 * revisión visual. Los datos dibujados son paquetes de PRUEBA inyectados solo en este test; la
 * app nunca genera datos ficticios. El desenfoque RenderEffect no se aplica en este renderizador.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    private val outDir = File(System.getProperty("robolectric.screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun feed(engine: HeartRateEngine, seconds: Int = 60) {
        val now = System.currentTimeMillis()
        for (i in 0 until seconds) {
            val bpm = (92 + 14 * sin(i / 7.0) + (i % 5)).roundToInt()
            engine.onPacket(
                HeartRatePacket(sessionId = "session-screenshot", sequence = i.toLong(), device = "SM-R950", bpm = bpm,
                    timestampMs = now - (seconds - 1 - i) * 1000L, sensorStatus = SensorStatus.ACTIVE, quality = ReadingQuality.HIGH, source = "exercise"),
                receivedWallMs = now - (seconds - 1 - i) * 1000L,
                receivedElapsedMs = android.os.SystemClock.elapsedRealtime() - (seconds - 1 - i) * 1000L,
            )
        }
    }

    private fun save(v: View, w: Int, h: Int, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2A3A55.toInt()) // simula un fondo de juego detrás del overlay
        v.draw(c)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun overlayVariants() {
        val ctx = RuntimeEnvironment.getApplication()
        val engine = HeartRateEngine(com.flexhealth.heartmonitor.receiver.AndroidEngineClock)
        feed(engine)
        val d = ctx.resources.displayMetrics.density
        val geo = OverlayGeometry(d)
        val noop = object : OverlayWidgetView.Gestures {
            override fun onGestureStart() {}; override fun onDrag(dx: Int, dy: Int) {}
            override fun onResize(handle: com.flexhealth.heartmonitor.receiver.overlay.ResizeHandle, dx: Int, dy: Int) {}
            override fun onGestureEnd(resized: Boolean) {}; override fun onLongPress() {}; override fun onTap() {}
        }
        val variants = listOf(
            "overlay_compact" to OverlayPrefs(size = OverlaySize.COMPACT),
            "overlay_normal" to OverlayPrefs(size = OverlaySize.NORMAL),
            "overlay_large" to OverlayPrefs(size = OverlaySize.LARGE),
            "overlay_game" to OverlayPrefs(size = OverlaySize.COMPACT, gameMode = true),
            "overlay_minimized" to OverlayPrefs(minimized = true),
        )
        for ((name, p) in variants) {
            val v = OverlayWidgetView(ctx, engine, geo, noop)
            v.applyPrefs(p.copy(animations = false))
            v.update(engine.snapshot.value, LinkStatus(LinkState.CONNECTED))
            val w = if (p.minimized) 92 else p.widthDp(); val h = if (p.minimized) 40 else p.heightDp()
            save(v, (w * d).toInt(), (h * d).toInt(), name)
        }
        val edit = OverlayWidgetView(ctx, engine, geo, noop).apply { applyPrefs(OverlayPrefs(size = OverlaySize.NORMAL, animations = false)); editMode = true; update(engine.snapshot.value, LinkStatus(LinkState.CONNECTED)) }
        save(edit, (230 * d).toInt(), (76 * d).toInt(), "overlay_edit")
        val empty = HeartRateEngine(com.flexhealth.heartmonitor.receiver.AndroidEngineClock)
        val waiting = OverlayWidgetView(ctx, empty, geo, noop).apply { applyPrefs(OverlayPrefs(animations = false)); update(empty.snapshot.value, LinkStatus(LinkState.SEARCHING)) }
        save(waiting, (190 * d).toInt(), (65 * d).toInt(), "overlay_waiting")
    }

    private fun screen(name: String, wDp: Int, hDp: Int) {
        val ctx = RuntimeEnvironment.getApplication()
        feed(ctx.graph.engine)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        val d = activity.resources.displayMetrics.density
        save(activity.window.decorView, (wDp * d).toInt(), (hDp * d).toInt(), name)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun phoneMonitorScreen() = screen("app_phone_monitor", 411, 891)

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletMonitorScreen() = screen("app_tablet_monitor", 1280, 800)

    /** Android 11 (API 30): sin RenderEffect, solo tinte. */
    @Test @Config(sdk = [30], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun phoneMonitorScreenApi30() = screen("app_phone_monitor_api30", 411, 891)
}
