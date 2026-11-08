package com.flexhealth.heartmonitor.receiver.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import com.flexhealth.heartmonitor.receiver.data.ReceiverSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Gestiona la ventana flotante (WindowManager + TYPE_APPLICATION_OVERLAY): mostrar/ocultar,
 * posición y tamaño persistentes, adaptación a rotaciones, menú y modo "toques a través".
 *
 * Limitaciones de Android respetadas (ver KNOWN_LIMITATIONS.md):
 * - Requiere el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW).
 * - Android puede ocultar overlays sobre pantallas protegidas (ajustes del sistema, instaladores,
 *   pantallas de bloqueo, contenido FLAG_SECURE) y la barra de estado se dibuja encima.
 * - Desde Android 12, para que los toques atraviesen el overlay hasta el juego, la opacidad de la
 *   ventana debe ser ≤ InputManager.getMaximumObscuringOpacityForTouch() (0,8 por defecto).
 * - Debe llamarse siempre desde el hilo principal.
 */
class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: ReceiverSettings,
    private val engine: HeartRateEngine,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val geometry = OverlayGeometry(context.resources.displayMetrics.density)
    private val main = Handler(Looper.getMainLooper())
    private var prefs = OverlayPrefs()
    private var widget: OverlayWidgetView? = null
    private var params: WindowManager.LayoutParams? = null
    private var menu: OverlayMenuView? = null
    private var hiddenTemporarily = false
    private var editMode = false
    private var gestureStart = PxRect(0, 0, 0, 0)
    private var lastSnapshot = MonitorSnapshot()
    private var lastLink = LinkStatus()
    private var windowMs = 60_000L

    val isShowing: Boolean get() = widget?.isAttachedToWindow == true

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** Aplica preferencias (llamado al cambiar en la app o desde el propio overlay). */
    fun applyPrefs(p: OverlayPrefs) {
        prefs = p
        if (!p.enabled || !canDraw()) { removeWidget(); dismissMenu(); return }
        if (hiddenTemporarily) return
        if (widget == null) addWidget() else layoutWidget()
        widget?.applyPrefs(p)
    }

    fun setGraphWindow(seconds: Int) { windowMs = seconds * 1000L; widget?.windowMs = windowMs }

    fun update(s: MonitorSnapshot, l: LinkStatus) {
        lastSnapshot = s; lastLink = l
        widget?.update(s, l)
    }

    fun showNow() { hiddenTemporarily = false; main.removeCallbacks(reshow); applyPrefs(prefs) }

    private val reshow = Runnable { showNow() }

    fun hideTemporarily(ms: Long = 30_000) {
        dismissMenu(); removeWidget(); hiddenTemporarily = true
        main.postDelayed(reshow, ms)
    }

    fun onConfigurationChanged() { if (widget != null) layoutWidget() }

    fun release() {
        main.removeCallbacks(reshow)
        dismissMenu(); removeWidget()
    }

    // -------------------------------------------------------------------------------------

    private fun screen(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun isLandscape(): Boolean = screen().let { it.first > it.second }

    private fun targetSize(sw: Int, sh: Int): Pair<Int, Int> = if (prefs.minimized) {
        geometry.dp(OverlayLimits.MINIMIZED_W_DP) to geometry.dp(OverlayLimits.MINIMIZED_H_DP)
    } else geometry.clampSize(geometry.dp(prefs.widthDp()), geometry.dp(prefs.heightDp()), sw, sh)

    private fun buildParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "FlexHeartOverlay"
    }

    /** Opacidad de ventana permitida para que los toques atraviesen el overlay (Android 12+). */
    private fun maxPassThroughAlpha(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 1f

    private fun applyFlags(p: WindowManager.LayoutParams) {
        val clickThrough = prefs.clickThrough && !editMode
        p.flags = if (clickThrough) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        p.alpha = if (clickThrough) minOf(1f, maxPassThroughAlpha()) else 1f
    }

    private fun computeLayout(p: WindowManager.LayoutParams) {
        val (sw, sh) = screen()
        val (w, h) = targetSize(sw, sh)
        val land = isLandscape()
        val fx = if (land) prefs.landscapeX ?: prefs.portraitX else prefs.portraitX
        val fy = if (land) prefs.landscapeY ?: prefs.portraitY else prefs.portraitY
        val (x, y) = geometry.clampPosition(geometry.fromFraction(fx, w, sw), geometry.fromFraction(fy, h, sh), w, h, sw, sh)
        p.width = w; p.height = h; p.x = x; p.y = y
        applyFlags(p)
    }

    private fun addWidget() {
        val p = buildParams().also { computeLayout(it) }
        val v = OverlayWidgetView(context, engine, geometry, gestures).apply {
            applyPrefs(prefs); windowMs = this@OverlayController.windowMs; editMode = this@OverlayController.editMode
            update(lastSnapshot, lastLink)
        }
        try {
            wm.addView(v, p)
            widget = v; params = p
        } catch (e: RuntimeException) {
            // Permiso revocado o ventana rechazada por el sistema.
            Log.w(TAG, "No se pudo añadir el overlay", e)
        }
    }

    private fun layoutWidget() {
        val v = widget ?: return
        val p = params ?: return
        computeLayout(p)
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun removeWidget() {
        widget?.let { runCatching { wm.removeViewImmediate(it) } }
        widget = null; params = null
    }

    private fun persistPosition() {
        val p = params ?: return
        val (sw, sh) = screen()
        val fx = geometry.toFraction(p.x, p.width, sw)
        val fy = geometry.toFraction(p.y, p.height, sh)
        val land = isLandscape()
        scope.launch { settings.updateOverlay { if (land) it.copy(landscapeX = fx, landscapeY = fy) else it.copy(portraitX = fx, portraitY = fy) } }
    }

    private fun update(f: (OverlayPrefs) -> OverlayPrefs) {
        prefs = f(prefs)
        applyPrefs(prefs) // respuesta inmediata; DataStore confirmará el mismo valor
        scope.launch { settings.updateOverlay(f) }
    }

    private val gestures = object : OverlayWidgetView.Gestures {
        override fun onGestureStart() {
            val p = params ?: return
            gestureStart = PxRect(p.x, p.y, p.width, p.height)
        }

        override fun onDrag(dx: Int, dy: Int) {
            val p = params ?: return; val v = widget ?: return
            val (sw, sh) = screen()
            val (x, y) = geometry.clampPosition(gestureStart.x + dx, gestureStart.y + dy, p.width, p.height, sw, sh)
            p.x = x; p.y = y
            runCatching { wm.updateViewLayout(v, p) }
        }

        override fun onResize(handle: ResizeHandle, dx: Int, dy: Int) {
            val p = params ?: return; val v = widget ?: return
            val (sw, sh) = screen()
            val r = geometry.resize(gestureStart, handle, dx, dy, sw, sh)
            p.x = r.x; p.y = r.y; p.width = r.w; p.height = r.h
            runCatching { wm.updateViewLayout(v, p) }
        }

        override fun onGestureEnd(resized: Boolean) {
            val p = params ?: return
            if (resized) {
                val wDp = (p.width / context.resources.displayMetrics.density).toInt()
                val hDp = (p.height / context.resources.displayMetrics.density).toInt()
                prefs = prefs.copy(size = OverlaySize.CUSTOM, customWidthDp = wDp, customHeightDp = hDp, minimized = false)
                scope.launch { settings.updateOverlay { it.copy(size = OverlaySize.CUSTOM, customWidthDp = wDp, customHeightDp = hDp, minimized = false) } }
            }
            persistPosition()
        }

        override fun onLongPress() { showMenu() }

        override fun onTap() { if (prefs.minimized) update { it.copy(minimized = false) } }
    }

    private fun showMenu() {
        dismissMenu()
        val anchor = params ?: return
        val m = OverlayMenuView(context, prefs, editMode, ::onMenuAction, ::dismissMenu)
        val (sw, sh) = screen()
        m.measure(View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST))
        val mw = m.measuredWidth; val mh = m.measuredHeight
        val gap = geometry.dp(8)
        val below = anchor.y + anchor.height + gap
        val y = if (below + mh <= sh) below else maxOf(0, anchor.y - gap - mh)
        val (x, yy) = geometry.clampPosition(anchor.x, y, mw, mh, sw, sh)
        val p = WindowManager.LayoutParams(
            mw, mh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = yy; title = "FlexHeartOverlayMenu" }
        try { wm.addView(m, p); menu = m } catch (e: RuntimeException) { Log.w(TAG, "No se pudo abrir el menú", e) }
    }

    private fun dismissMenu() {
        menu?.let { runCatching { wm.removeViewImmediate(it) } }
        menu = null
    }

    private fun onMenuAction(a: MenuAction) {
        dismissMenu()
        when (a) {
            MenuAction.TOGGLE_LOCK -> update { it.copy(locked = !it.locked) }
            MenuAction.TOGGLE_GRAPH -> update { it.copy(showGraph = !it.showGraph) }
            MenuAction.CYCLE_SIZE -> update {
                val next = when (it.size) { OverlaySize.COMPACT -> OverlaySize.NORMAL; OverlaySize.NORMAL -> OverlaySize.LARGE; else -> OverlaySize.COMPACT }
                it.copy(size = next, minimized = false)
            }
            MenuAction.TOGGLE_EDIT -> {
                editMode = !editMode
                widget?.editMode = editMode
                if (editMode && prefs.minimized) update { it.copy(minimized = false) } else layoutWidget()
            }
            MenuAction.ALPHA_DOWN -> update { it.copy(alpha = (it.alpha - 0.1f).coerceAtLeast(OverlayLimits.MIN_ALPHA)) }
            MenuAction.ALPHA_UP -> update { it.copy(alpha = (it.alpha + 0.1f).coerceAtMost(OverlayLimits.MAX_ALPHA)) }
            MenuAction.TOGGLE_MINIMIZE -> update { it.copy(minimized = !it.minimized) }
            MenuAction.EXPAND -> update { it.copy(size = OverlaySize.LARGE, minimized = false) }
            MenuAction.TOGGLE_GAME_MODE -> {
                editMode = false; widget?.editMode = false
                // Modo juego: vista mínima y toques que atraviesan el widget hasta el juego.
                update { it.copy(gameMode = !it.gameMode, clickThrough = !it.gameMode, showGraph = true) }
            }
            MenuAction.HIDE_TEMPORARILY -> hideTemporarily()
            MenuAction.CLOSE -> update { it.copy(enabled = false) }
        }
    }

    private companion object { const val TAG = "OverlayController" }
}
