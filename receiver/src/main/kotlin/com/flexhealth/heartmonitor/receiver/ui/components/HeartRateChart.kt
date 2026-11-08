package com.flexhealth.heartmonitor.receiver.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.flexhealth.heartmonitor.engine.HeartRateEngine
import com.flexhealth.heartmonitor.engine.HistoryWindow
import com.flexhealth.heartmonitor.engine.SparklineLayout
import com.flexhealth.heartmonitor.receiver.ui.theme.Flex
import kotlin.math.min

/**
 * Gráfica histórica. Dibuja exclusivamente muestras recibidas (sin interpolar entre huecos) y
 * solo se recompone cuando cambia el historial; la animación de desplazamiento dura 450 ms.
 */
@Composable
fun HeartRateChart(
    engine: HeartRateEngine,
    historyVersion: Long,
    windowSec: Int,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val history = remember { HistoryWindow(1024) }
    val layout = remember { SparklineLayout(1024) }
    val shift = remember { Animatable(0f) }
    val lastAt = remember { longArrayOf(0L) }
    val windowMs = windowSec * 1000L
    LaunchedEffect(historyVersion) {
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (lastAt[0] == 0L) 0f else min(now - lastAt[0], 2_000L).toFloat()
        lastAt[0] = now
        shift.snapTo(-dt)
        shift.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
    }
    val labelColor = Flex.TextLow
    val labelStyle = MaterialTheme.typography.labelMedium
    Box(modifier) {
        val count = engine.copyHistory(windowMs, history)
        if (count == 0) {
            Text("Sin datos del sensor", style = MaterialTheme.typography.bodyMedium, color = Flex.TextLow, modifier = Modifier.align(Alignment.Center))
        }
        Canvas(Modifier.matchParentSize()) {
            val left = 34.dp.toPx(); val top = 8.dp.toPx(); val bottom = 18.dp.toPx()
            val w = size.width - left; val h = size.height - top - bottom
            val grid = Flex.TextLow.copy(alpha = 0.18f)
            for (i in 0..2) {
                val y = top + h * i / 2f
                drawLine(grid, Offset(left, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.argb((labelColor.alpha * 255).toInt(), 245, 246, 250)
                textSize = labelStyle.fontSize.toPx()
            }
            // Eje temporal: "-Ns" … "ahora".
            drawContext.canvas.nativeCanvas.drawText("-${windowSec}s", left, size.height - 2.dp.toPx(), paint)
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("ahora", size.width, size.height - 2.dp.toPx(), paint)
            if (count == 0) return@Canvas
            val n = layout.compute(history, windowMs, w, h, timeShiftMs = shift.value)
            paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText("${layout.yMax}", 0f, top + paint.textSize, paint)
            drawContext.canvas.nativeCanvas.drawText("${layout.yMin}", 0f, top + h, paint)
            val line = Path(); val fill = Path()
            var started = false; var sx = 0f; var lx = 0f; var ly = 0f
            fun close() { fill.lineTo(lx, top + h); fill.lineTo(sx, top + h); fill.close() }
            val p = layout.points
            for (i in 0 until n) {
                val x = p[i * 2]; val y = p[i * 2 + 1]
                if (x.isNaN()) { if (started) close(); started = false; continue }
                val gx = left + x; val gy = top + y
                if (!started) { line.moveTo(gx, gy); fill.moveTo(gx, gy); sx = gx; started = true } else { line.lineTo(gx, gy); fill.lineTo(gx, gy) }
                lx = gx; ly = gy
            }
            if (started) close()
            val a = if (dimmed) 0.45f else 1f
            clipRect(left, 0f, size.width, size.height) {
                drawPath(fill, Brush.verticalGradient(listOf(Flex.Heart.copy(alpha = 0.35f * a), Flex.Heart.copy(alpha = 0f)), startY = top, endY = top + h))
                drawPath(line, Brush.horizontalGradient(listOf(Flex.HeartSoft.copy(alpha = a), Flex.Heart.copy(alpha = a))),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (!dimmed) {
                    drawCircle(Flex.Heart.copy(alpha = 0.35f), 7.dp.toPx(), Offset(lx, ly))
                    drawCircle(androidx.compose.ui.graphics.Color.White, 3.dp.toPx(), Offset(lx, ly))
                }
            }
        }
    }
}
