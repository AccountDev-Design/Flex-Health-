package com.flexhealth.heartmonitor.receiver.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.withClip
import com.flexhealth.heartmonitor.engine.Freshness
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.engine.HistoryWindow
import com.flexhealth.heartmonitor.engine.MonitorSnapshot
import com.flexhealth.heartmonitor.engine.SparklineLayout
import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.protocol.LinkStatus
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Widget flotante dibujado a mano sobre Canvas (sin Compose ni layouts) para que el coste por
 * fotograma sea mínimo encima de juegos. Solo se redibuja cuando llega una muestra nueva, cambia
 * el estado o durante una animación corta (≈450 ms) tras cada muestra: no hay bucle continuo.
 */
@SuppressLint("ViewConstructor")
class OverlayWidgetView(
    context: Context,
    private val engine: HeartRateEngine,
    private val geometry: OverlayGeometry,
    private val gestures: Gestures,
) : View(context) {

    interface Gestures {
        fun onGestureStart()
        fun onDrag(dx: Int, dy: Int)
        fun onResize(handle: ResizeHandle, dx: Int, dy: Int)
        fun onGestureEnd(resized: Boolean)
        fun onLongPress()
        fun onTap()
    }

    private val d = resources.displayMetrics.density
    private val history = HistoryWindow(512)
    private val layout = SparklineLayout(512)

    private var snapshot = MonitorSnapshot()
    private var link = LinkStatus()
    var prefs = OverlayPrefs(); private set
    var editMode = false
        set(v) { field = v; invalidate() }

    // --- Pinturas reutilizadas (sin asignaciones en onDraw) ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HEART }
    private val bpmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2f * d
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xCCFFFFFF.toInt(); pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val panel = RectF()
    private val graphRect = RectF()
    private val heartPath = Path()
    private val linePath = Path()
    private val fillPath = Path()
    private val margin = 3f * d

    // --- Animación tras cada muestra ---
    private var anim = 1f
    private var animShiftMs = 0f
    private var lastHistoryVersion = -1L
    private var lastSampleDrawElapsed = 0L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim = it.animatedValue as Float; invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        contentDescription = "Frecuencia cardíaca"
    }

    fun applyPrefs(p: OverlayPrefs) {
        prefs = p
        rebuildShaders()
        invalidate()
    }

    fun update(s: MonitorSnapshot, l: LinkStatus) {
        val newSample = s.historyVersion != lastHistoryVersion && s.freshness == Freshness.LIVE && s.stats.samples > 0
        val changed = s.displayBpm != snapshot.displayBpm || s.freshness != snapshot.freshness ||
            s.historyVersion != snapshot.historyVersion || l.state != link.state
        snapshot = s; link = l
        if (s.historyVersion != lastHistoryVersion) {
            val now = android.os.SystemClock.elapsedRealtime()
            animShiftMs = if (lastSampleDrawElapsed == 0L) 0f else min(now - lastSampleDrawElapsed, 2_000L).toFloat()
            lastSampleDrawElapsed = now
            lastHistoryVersion = s.historyVersion
        }
        if (newSample && prefs.animations && isAttachedToWindow) {
            animator.cancel(); anim = 0f; animator.start()
        } else if (changed) {
            anim = 1f; invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        panel.set(margin, margin, w - margin, h - margin)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (panel.height() <= 0f) return
        val a = (prefs.alpha.coerceIn(OverlayLimits.MIN_ALPHA, OverlayLimits.MAX_ALPHA) * 255).toInt()
        bgPaint.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, Color.argb((a * 0.92f).toInt(), 24, 26, 36), Color.argb(a, 8, 9, 14), Shader.TileMode.CLAMP)
        bgPaint.setShadowLayer(3f * d, 0f, 1.2f * d, Color.argb((a * 0.55f).toInt(), 0, 0, 0))
        glossPaint.shader = LinearGradient(0f, panel.top, 0f, panel.top + panel.height() * 0.5f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        borderPaint.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, 0x66FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDetachedFromWindow() { animator.cancel(); removeCallbacks(longPress); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val r = min(panel.height() / 2f, 22f * d)
        canvas.drawRoundRect(panel, r, r, bgPaint)
        canvas.withClip(panel.left, panel.top, panel.right, panel.centerY()) { drawRoundRect(panel, r, r, glossPaint) }
        canvas.drawRoundRect(panel, r, r, borderPaint)

        val h = panel.height()
        val pad = h * 0.18f
        val minimized = prefs.minimized
        val stale = snapshot.freshness == Freshness.STALE
        val lost = snapshot.freshness == Freshness.LOST

        // Corazón (late con cada muestra real; no hay latido sintético).
        val heartSize = h * (if (minimized) 0.46f else 0.38f)
        val pulse = if (prefs.animations && anim < 1f) 1f + 0.18f * (1f - anim) else 1f
        val hx = panel.left + pad + heartSize / 2f
        val hy = panel.centerY()
        heartPaint.alpha = if (lost) 110 else 255
        buildHeart(heartPath, hx, hy, heartSize * pulse)
        canvas.drawPath(heartPath, heartPaint)

        // BPM.
        val bpm = snapshot.displayBpm
        bpmPaint.textSize = h * (if (minimized) 0.52f else 0.46f)
        bpmPaint.alpha = when { lost || bpm == null -> 150; stale -> 140; else -> 255 }
        val text = bpm?.toString() ?: "--"
        val tx = hx + heartSize / 2f + pad * 0.6f
        val baseline = hy + bpmPaint.textSize * 0.36f
        canvas.drawText(text, tx, baseline, bpmPaint)
        var textEnd = tx + bpmPaint.measureText(text)

        if (!minimized) {
            labelPaint.textSize = h * 0.17f
            labelPaint.color = 0xB3FFFFFF.toInt()
            canvas.drawText("BPM", textEnd + 4 * d, baseline, labelPaint)
            val sub = when {
                lost -> "Sin señal"
                stale -> "Desactualizado"
                snapshot.freshness == Freshness.WAITING -> "Esperando"
                else -> null
            }
            if (sub != null && h >= 50 * d) {
                labelPaint.textSize = h * 0.15f
                labelPaint.color = if (lost) 0xFFFF6B81.toInt() else 0xFFFFC145.toInt()
                canvas.drawText(sub, tx, baseline + labelPaint.textSize * 1.3f, labelPaint)
            } else if (!prefs.gameMode && h >= 90 * d && snapshot.windowAvg != null) {
                labelPaint.textSize = h * 0.13f
                canvas.drawText("mín ${snapshot.windowMin} · máx ${snapshot.windowMax} · media ${snapshot.windowAvg}", tx, baseline + labelPaint.textSize * 1.6f, labelPaint)
            }
            labelPaint.textSize = h * 0.17f
            textEnd += labelPaint.measureText("BPM") + 4 * d
        }

        // Mini gráfica: solo datos reales; cortada en los huecos.
        if (prefs.showGraph && !minimized) {
            graphRect.set(textEnd + pad, panel.top + h * 0.22f, panel.right - pad - 6 * d, panel.bottom - h * 0.22f)
            if (graphRect.width() > 24 * d) drawGraph(canvas, lost || stale)
        }

        // Indicador de conexión.
        dotPaint.color = when {
            link.state == LinkState.CONNECTED && snapshot.freshness == Freshness.LIVE -> 0xFF3DDC97.toInt()
            lost || link.state == LinkState.ERROR || link.state == LinkState.UNAVAILABLE -> 0xFFFF4D67.toInt()
            stale || link.state == LinkState.SEARCHING || link.state == LinkState.CONNECTING || link.state == LinkState.AUTHENTICATING -> 0xFFFFC145.toInt()
            else -> 0xFF8A8F98.toInt()
        }
        canvas.drawCircle(panel.right - r * 0.55f, panel.top + r * 0.55f, 3f * d, dotPaint)

        if (editMode) drawEditHandles(canvas, r)
    }

    /** Ventana temporal de la mini gráfica (15/30/60/120 s, configurable en la app). */
    var windowMs: Long = 60_000L
        set(v) { field = v.coerceIn(5_000L, engine.maxHistoryMs); invalidate() }

    private var shaderKey = 0f

    private fun drawGraph(canvas: Canvas, dim: Boolean) {
        engine.copyHistory(windowMs, history)
        val shift = if (anim < 1f) -(1f - anim) * animShiftMs else 0f
        val n = layout.compute(history, windowMs, graphRect.width(), graphRect.height(), timeShiftMs = shift)
        if (n == 0) return
        val alpha = if (dim) 120 else 255
        val key = graphRect.left * 31f + graphRect.right * 17f + graphRect.top * 7f + graphRect.bottom
        if (key != shaderKey) { // los shaders solo se recrean si cambia el tamaño
            shaderKey = key
            linePaint.shader = LinearGradient(graphRect.left, 0f, graphRect.right, 0f, 0xFFFF7A90.toInt(), HEART, Shader.TileMode.CLAMP)
            fillPaint.shader = LinearGradient(0f, graphRect.top, 0f, graphRect.bottom, 0x55FF3B5C, 0x00FF3B5C, Shader.TileMode.CLAMP)
        }
        linePaint.alpha = alpha
        fillPaint.alpha = alpha
        canvas.withClip(graphRect) { drawGraphPaths(this, n, dim) }
    }

    private fun drawGraphPaths(canvas: Canvas, n: Int, dim: Boolean) {
        linePath.rewind(); fillPath.rewind()
        var started = false; var segStartX = 0f; var lastX = 0f; var lastY = 0f
        val p = layout.points
        for (i in 0 until n) {
            val x = p[i * 2]; val y = p[i * 2 + 1]
            if (x.isNaN()) {
                if (started) closeFill(segStartX, lastX)
                started = false; continue
            }
            val gx = graphRect.left + x; val gy = graphRect.top + y
            if (!started) { linePath.moveTo(gx, gy); fillPath.moveTo(gx, gy); segStartX = gx; started = true }
            else { linePath.lineTo(gx, gy); fillPath.lineTo(gx, gy) }
            lastX = gx; lastY = gy
        }
        if (started) closeFill(segStartX, lastX)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        if (!dim) {
            dotPaint.color = Color.WHITE
            canvas.drawCircle(lastX, lastY, 2.4f * d, dotPaint)
        }
    }

    private fun closeFill(startX: Float, endX: Float) {
        fillPath.lineTo(endX, graphRect.bottom); fillPath.lineTo(startX, graphRect.bottom); fillPath.close()
    }

    private fun drawEditHandles(canvas: Canvas, r: Float) {
        canvas.drawRoundRect(panel, r, r, editPaint)
        val pts = floatArrayOf(
            panel.left, panel.top, panel.centerX(), panel.top, panel.right, panel.top,
            panel.left, panel.centerY(), panel.right, panel.centerY(),
            panel.left, panel.bottom, panel.centerX(), panel.bottom, panel.right, panel.bottom,
        )
        for (i in pts.indices step 2) canvas.drawCircle(pts[i], pts[i + 1], 3.5f * d, handlePaint)
    }

    private fun buildHeart(path: Path, cx: Float, cy: Float, size: Float) {
        val s = size / 2f
        path.rewind()
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.25f, cy + s * 0.05f, cx - s * 0.9f, cy - s * 1.05f, cx, cy - s * 0.42f)
        path.cubicTo(cx + s * 0.9f, cy - s * 1.05f, cx + s * 1.25f, cy + s * 0.05f, cx, cy + s * 0.9f)
        path.close()
    }

    // ------------------------------- Gestos ---------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var longPressed = false
    private var activeHandle: ResizeHandle? = null
    private val longPress = Runnable { longPressed = true; performHapticFeedback(HAPTIC_FEEDBACK_ENABLED); gestures.onLongPress() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false; longPressed = false
                activeHandle = if (editMode) geometry.hitHandle(e.x, e.y, width, height, (22 * d).toInt()) else null
                if (activeHandle == null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                gestures.onGestureStart()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > slop) { moved = true; removeCallbacks(longPress) }
                if (moved && !longPressed) {
                    val h = activeHandle
                    if (h != null) gestures.onResize(h, dx.toInt(), dy.toInt())
                    else if (!prefs.locked) gestures.onDrag(dx.toInt(), dy.toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (moved && !longPressed) gestures.onGestureEnd(activeHandle != null)
                else if (!moved && !longPressed && e.actionMasked == MotionEvent.ACTION_UP) { performClick(); gestures.onTap() }
                activeHandle = null
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private companion object { val HEART = 0xFFFF3B5C.toInt() }
}
