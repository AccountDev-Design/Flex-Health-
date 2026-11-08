package com.flexhealth.heartmonitor.receiver.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.flexhealth.heartmonitor.receiver.data.OverlayPrefs

/** Acciones del menú del widget (pulsación larga). */
enum class MenuAction {
    TOGGLE_LOCK, TOGGLE_GRAPH, CYCLE_SIZE, TOGGLE_EDIT, ALPHA_DOWN, ALPHA_UP,
    TOGGLE_MINIMIZE, EXPAND, TOGGLE_GAME_MODE, HIDE_TEMPORARILY, CLOSE,
}

/** Menú compacto Liquid Glass construido con vistas nativas (ligero, sin Compose). */
@SuppressLint("ViewConstructor")
class OverlayMenuView(
    context: Context,
    prefs: OverlayPrefs,
    editMode: Boolean,
    private val onAction: (MenuAction) -> Unit,
    private val onDismiss: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        val p = (10 * d).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = 20 * d
            colors = intArrayOf(0xF01A1C26.toInt(), 0xF00B0C12.toInt())
            setStroke((1 * d).toInt(), 0x40FFFFFF)
        }
        elevation = 8 * d
        row(item(if (prefs.locked) "Desbloquear" else "Bloquear", MenuAction.TOGGLE_LOCK), item(if (prefs.showGraph) "Ocultar gráfica" else "Mostrar gráfica", MenuAction.TOGGLE_GRAPH))
        row(item("Tamaño: ${sizeLabel(prefs)}", MenuAction.CYCLE_SIZE), item(if (editMode) "Terminar edición" else "Redimensionar", MenuAction.TOGGLE_EDIT))
        row(item("Opacidad −", MenuAction.ALPHA_DOWN), label("${(prefs.alpha * 100).toInt()} %"), item("Opacidad +", MenuAction.ALPHA_UP))
        row(item(if (prefs.minimized) "Restaurar" else "Minimizar", MenuAction.TOGGLE_MINIMIZE), item("Expandir", MenuAction.EXPAND))
        row(item(if (prefs.gameMode) "Salir de modo juego" else "Modo juego", MenuAction.TOGGLE_GAME_MODE))
        row(item("Ocultar 30 s", MenuAction.HIDE_TEMPORARILY), item("Cerrar overlay", MenuAction.CLOSE, danger = true))
    }

    private fun sizeLabel(p: OverlayPrefs) = when (p.size) {
        OverlaySize.COMPACT -> "compacto"; OverlaySize.NORMAL -> "normal"; OverlaySize.LARGE -> "grande"; OverlaySize.CUSTOM -> "personal."
    }

    private fun row(vararg views: TextView) {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        views.forEach { v -> r.addView(v, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt()) }) }
        addView(r, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER
        minWidth = (52 * d).toInt(); setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
    }

    private fun item(text: String, action: MenuAction, danger: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextColor(if (danger) 0xFFFF6B81.toInt() else Color.WHITE)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minWidth = (104 * d).toInt()
        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        val shape = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0x1FFFFFFF); setStroke((1 * d).toInt(), 0x1AFFFFFF) }
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null)
        isClickable = true
        setOnClickListener { onAction(action) }
    }

    /** Toque fuera del menú (FLAG_WATCH_OUTSIDE_TOUCH) → cerrar. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { onDismiss(); return true }
        return super.onTouchEvent(event)
    }
}
