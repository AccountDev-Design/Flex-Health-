package com.flexhealth.heartmonitor.receiver.overlay

import kotlin.math.roundToInt

/** Tamaños predefinidos del widget en dp (ancho × alto). */
enum class OverlaySize(val widthDp: Int, val heightDp: Int) {
    COMPACT(190, 65),
    NORMAL(230, 76),
    LARGE(280, 100),
    CUSTOM(0, 0),
}

object OverlayLimits {
    const val MIN_W_DP = 120
    const val MIN_H_DP = 44
    const val MAX_W_DP = 420
    const val MAX_H_DP = 180
    const val MINIMIZED_W_DP = 92
    const val MINIMIZED_H_DP = 40
    const val MIN_ALPHA = 0.30f
    const val MAX_ALPHA = 1.00f
}

/** Zona de agarre para redimensionar (bordes y esquinas). */
enum class ResizeHandle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    LEFT(left = true), RIGHT(right = true), TOP(top = true), BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true), TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true),
}

data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Geometría pura del overlay (sin dependencias de Android, probada en JVM): límites de tamaño,
 * redimensionado por bordes/esquinas, posición dentro de la pantalla y conversión a fracciones
 * para adaptarse a rotaciones y cambios de resolución.
 */
class OverlayGeometry(private val density: Float) {
    fun dp(v: Int): Int = (v * density).roundToInt()

    val minW get() = dp(OverlayLimits.MIN_W_DP)
    val minH get() = dp(OverlayLimits.MIN_H_DP)
    val maxW get() = dp(OverlayLimits.MAX_W_DP)
    val maxH get() = dp(OverlayLimits.MAX_H_DP)

    /** Tamaño (px) limitado a mín/máx y a la pantalla disponible. */
    fun clampSize(w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        w.coerceIn(minW, maxOf(minW, minOf(maxW, screenW))) to h.coerceIn(minH, maxOf(minH, minOf(maxH, screenH)))

    /** Posición limitada para que el widget nunca salga de la pantalla. */
    fun clampPosition(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, screenW - w)) to y.coerceIn(0, maxOf(0, screenH - h))

    fun toFraction(pos: Int, size: Int, screen: Int): Float {
        val free = screen - size
        return if (free <= 0) 0f else (pos.toFloat() / free).coerceIn(0f, 1f)
    }

    fun fromFraction(fraction: Float, size: Int, screen: Int): Int = (fraction.coerceIn(0f, 1f) * maxOf(0, screen - size)).roundToInt()

    /** Aplica un arrastre (dx, dy en px) sobre [handle] partiendo de [start]. El borde opuesto queda fijo. */
    fun resize(start: PxRect, handle: ResizeHandle, dx: Int, dy: Int, screenW: Int, screenH: Int): PxRect {
        var left = start.x; var top = start.y
        var right = start.x + start.w; var bottom = start.y + start.h
        if (handle.left) left = (left + dx).coerceIn(maxOf(0, right - maxW), right - minW)
        if (handle.right) right = (right + dx).coerceIn(left + minW, minOf(screenW, left + maxW))
        if (handle.top) top = (top + dy).coerceIn(maxOf(0, bottom - maxH), bottom - minH)
        if (handle.bottom) bottom = (bottom + dy).coerceIn(top + minH, minOf(screenH, top + maxH))
        return PxRect(left, top, right - left, bottom - top)
    }

    /** Detecta qué zona de redimensionado se toca (coordenadas locales del widget). */
    fun hitHandle(localX: Float, localY: Float, w: Int, h: Int, touchPx: Int): ResizeHandle? {
        val l = localX < touchPx; val r = localX > w - touchPx
        val t = localY < touchPx; val b = localY > h - touchPx
        return when {
            t && l -> ResizeHandle.TOP_LEFT
            t && r -> ResizeHandle.TOP_RIGHT
            b && l -> ResizeHandle.BOTTOM_LEFT
            b && r -> ResizeHandle.BOTTOM_RIGHT
            l -> ResizeHandle.LEFT
            r -> ResizeHandle.RIGHT
            t -> ResizeHandle.TOP
            b -> ResizeHandle.BOTTOM
            else -> null
        }
    }
}
