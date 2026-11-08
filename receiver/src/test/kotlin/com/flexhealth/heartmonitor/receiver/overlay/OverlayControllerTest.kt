package com.flexhealth.heartmonitor.receiver.overlay

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.receiver.AndroidEngineClock
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import com.flexhealth.heartmonitor.receiver.data.ReceiverSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

/**
 * Pruebas del overlay real (WindowManager simulado por Robolectric en la JVM). Verifican la lógica
 * de arrastre, límites, redimensionado, rotación, persistencia y flags; NO sustituyen la prueba
 * sobre Geometry Dash en el hardware real (ver TESTING.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class OverlayControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val wm = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var settings: ReceiverSettings
    private lateinit var engine: HeartRateEngine
    private lateinit var controller: OverlayController
    private val density get() = context.resources.displayMetrics.density

    @Before fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        settings = ReceiverSettings(context)
        engine = HeartRateEngine(AndroidEngineClock)
        controller = OverlayController(context, scope, settings, engine)
    }

    @After fun tearDown() { controller.release(); scope.cancel() }

    private fun views(): List<android.view.View> = Shadow.extract<ShadowWindowManagerImpl>(wm).views
    private fun widget(): OverlayWidgetView = views().filterIsInstance<OverlayWidgetView>().single()
    private fun params(): WindowManager.LayoutParams = widget().layoutParams as WindowManager.LayoutParams
    private fun screen() = wm.currentWindowMetrics.bounds

    private fun touch(v: OverlayWidgetView, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    private fun drag(v: OverlayWidgetView, fromX: Float, fromY: Float, dx: Float, dy: Float) {
        touch(v, MotionEvent.ACTION_DOWN, fromX, fromY)
        touch(v, MotionEvent.ACTION_MOVE, fromX + dx / 2, fromY + dy / 2)
        touch(v, MotionEvent.ACTION_MOVE, fromX + dx, fromY + dy)
        touch(v, MotionEvent.ACTION_UP, fromX + dx, fromY + dy)
    }

    @Test fun compactDefaultSizeAndTopRightPosition() {
        controller.applyPrefs(OverlayPrefs())
        val p = params()
        assertEquals((190 * density).toInt(), p.width)
        assertEquals((65 * density).toInt(), p.height)
        assertEquals(screen().width() - p.width, p.x)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, p.type)
    }

    @Test fun dragMovesAndNeverLeavesScreenAndIsPersisted() = runBlocking {
        controller.applyPrefs(OverlayPrefs(portraitX = 0.5f, portraitY = 0.5f))
        val v = widget()
        val startX = params().x
        drag(v, 50f, 50f, -100f, 0f)
        assertEquals(startX - 100, params().x)
        // Arrastre enorme hacia la esquina inferior izquierda: queda pegado al borde.
        drag(v, 50f, 50f, -5000f, 5000f)
        assertEquals(0, params().x)
        assertEquals(screen().height() - params().height, params().y)
        val saved = withTimeout(5_000) { settings.config.first { it.overlay.portraitY > 0.99f } }
        assertEquals(0f, saved.overlay.portraitX)
    }

    @Test fun lockedWidgetDoesNotMove() {
        controller.applyPrefs(OverlayPrefs(locked = true, portraitX = 0.5f, portraitY = 0.5f))
        val before = params().x to params().y
        drag(widget(), 50f, 50f, -200f, 120f)
        assertEquals(before, params().x to params().y)
    }

    @Test fun longPressOpensMenuAndResizeFromCornerBecomesCustomSize() = runBlocking {
        controller.applyPrefs(OverlayPrefs(portraitX = 0.3f, portraitY = 0.3f))
        val v = widget()
        touch(v, MotionEvent.ACTION_DOWN, 60f, 40f)
        ShadowLooper.idleMainLooper(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        touch(v, MotionEvent.ACTION_UP, 60f, 40f)
        val menu = views().filterIsInstance<OverlayMenuView>().singleOrNull()
        assertNotNull("la pulsación larga abre el menú", menu)
        // Entra en modo edición desde el menú y arrastra la esquina inferior derecha.
        val m = menu!!
        val editButton = (0 until m.childCount).asSequence().map { m.getChildAt(it) as android.view.ViewGroup }
            .flatMap { row -> (0 until row.childCount).asSequence().map { row.getChildAt(it) } }
            .filterIsInstance<android.widget.TextView>().first { it.text == "Redimensionar" }
        editButton.performClick()
        assertTrue(widget().editMode)
        val w0 = params().width; val h0 = params().height
        drag(widget(), w0 - 2f, h0 - 2f, 60f * density, 20f * density)
        assertEquals(w0 + (60 * density).toInt(), params().width)
        assertEquals(h0 + (20 * density).toInt(), params().height)
        val saved = withTimeout(5_000) { settings.config.first { it.overlay.size == OverlaySize.CUSTOM } }
        assertEquals(250, saved.overlay.customWidthDp)
        assertEquals(85, saved.overlay.customHeightDp)
    }

    @Test fun rotationKeepsRelativePositionAndStaysOnScreen() {
        controller.applyPrefs(OverlayPrefs(portraitX = 1f, portraitY = 0f))
        RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-xxhdpi")
        controller.onConfigurationChanged()
        val p = params()
        assertEquals(screen().width() - p.width, p.x)
        assertEquals(0, p.y)
        assertTrue(p.x + p.width <= screen().width())
    }

    @Test fun gameModeMakesTouchesPassThroughWithAllowedOpacity() {
        controller.applyPrefs(OverlayPrefs(gameMode = true, clickThrough = true))
        val p = params()
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue("Android 12+: opacidad ≤ máximo para toques a través (0,8)", p.alpha <= 0.8f + 1e-6f)
        controller.applyPrefs(OverlayPrefs(gameMode = false, clickThrough = false))
        assertEquals(0, params().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(1f, params().alpha)
    }

    @Test fun minimizedAndPresetsAndHide() {
        controller.applyPrefs(OverlayPrefs(minimized = true))
        assertEquals((OverlayLimits.MINIMIZED_W_DP * density).toInt(), params().width)
        controller.applyPrefs(OverlayPrefs(size = OverlaySize.LARGE))
        assertEquals((280 * density).toInt(), params().width)
        assertEquals((100 * density).toInt(), params().height)
        controller.hideTemporarily(30_000)
        assertTrue(views().none { it is OverlayWidgetView })
        ShadowLooper.idleMainLooper(31_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, views().count { it is OverlayWidgetView })
        controller.applyPrefs(OverlayPrefs(enabled = false))
        assertTrue(views().isEmpty())
    }

    @Test fun noOverlayWithoutPermission() {
        ShadowSettings.setCanDrawOverlays(false)
        controller.applyPrefs(OverlayPrefs())
        assertTrue(views().isEmpty())
    }

    @Test fun widgetShowsRealDataOnly() {
        controller.applyPrefs(OverlayPrefs())
        val now = System.currentTimeMillis()
        engine.onPacket(HeartRatePacket(sessionId = "session-test-1", sequence = 0, device = "SM-R950", bpm = 91, timestampMs = now, sensorStatus = SensorStatus.ACTIVE))
        controller.update(engine.snapshot.value, LinkStatus(LinkState.CONNECTED))
        assertEquals(91, engine.snapshot.value.displayBpm)
        engine.reset()
        controller.update(engine.snapshot.value, LinkStatus(LinkState.IDLE))
        assertEquals(null, engine.snapshot.value.displayBpm)
    }
}
