package com.flexhealth.heartmonitor.engine

/**
 * Geometría de la gráfica, independiente del framework de dibujo (Compose o View).
 *
 * - El eje X es tiempo real: x = ancho · (1 − edad/ventana). Sin remuestreo.
 * - Si entre dos muestras consecutivas hay más de [gapMs], la línea se corta: nunca se dibuja
 *   un tramo inventado sobre un periodo sin mediciones.
 * - El rango Y se ajusta a los datos con un margen y un tramo mínimo, para que pequeñas
 *   variaciones no parezcan picos enormes.
 */
class SparklineLayout(capacity: Int) {
    /** Pares (x, y) consecutivos; NaN en x marca el inicio de un nuevo segmento. */
    val points = FloatArray(capacity * 2)
    var pointCount = 0
        private set
    var yMin = 0
        private set
    var yMax = 0
        private set

    fun compute(
        history: HistoryWindow,
        windowMs: Long,
        width: Float,
        height: Float,
        gapMs: Long = 3_000,
        minSpanBpm: Int = 20,
        paddingFraction: Float = 0.12f,
        /** Desplazamiento temporal extra (ms) para animar el avance entre muestras. */
        timeShiftMs: Float = 0f,
    ): Int {
        pointCount = 0
        val n = history.count
        if (n == 0 || width <= 0f || height <= 0f) return 0
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        for (i in 0 until n) { if (history.unreliable[i]) continue; val v = history.bpm[i]; if (v < lo) lo = v; if (v > hi) hi = v }
        if (lo == Int.MAX_VALUE) return 0 // solo lecturas no fiables: no se dibuja línea
        val span = maxOf(hi - lo, minSpanBpm)
        val mid = (hi + lo) / 2f
        val padded = span * (1 + 2 * paddingFraction)
        yMin = (mid - padded / 2).toInt()
        yMax = (mid + padded / 2).toInt() + 1
        val yRange = (yMax - yMin).toFloat()
        var prevAge = Long.MIN_VALUE
        var forceBreak = false
        for (i in 0 until n) {
            // Las lecturas no fiables no se unen a la línea: cortan el segmento.
            if (history.unreliable[i]) { forceBreak = prevAge != Long.MIN_VALUE; continue }
            val age = history.ageMs[i]
            val x = width * (1f - (age + timeShiftMs) / windowMs)
            val y = height * (1f - (history.bpm[i] - yMin) / yRange)
            val breakSegment = prevAge != Long.MIN_VALUE && (forceBreak || prevAge - age > gapMs)
            forceBreak = false
            if (breakSegment && pointCount < points.size / 2) {
                points[pointCount * 2] = Float.NaN; points[pointCount * 2 + 1] = Float.NaN; pointCount++
            }
            if (pointCount < points.size / 2) {
                points[pointCount * 2] = x; points[pointCount * 2 + 1] = y; pointCount++
            }
            prevAge = age
        }
        return pointCount
    }
}
