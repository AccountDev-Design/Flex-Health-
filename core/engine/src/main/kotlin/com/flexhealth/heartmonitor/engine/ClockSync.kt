package com.flexhealth.heartmonitor.engine

/**
 * Estima el desfase entre el reloj de pared del reloj (watch) y el del receptor sin requerir
 * relojes sincronizados: offset = mín(recepción − medición) en una ventana deslizante. Ese mínimo
 * incluye la latencia mínima del enlace, de modo que el "retraso" de cada muestra se mide respecto
 * de la mejor entrega observada recientemente.
 */
class ClockSync(private val windowSize: Int = 30) {
    private val deltas = LongArray(windowSize)
    private var count = 0
    private var next = 0

    var offsetMs: Long? = null
        private set

    /** Registra una muestra y devuelve su retraso estimado (≥ 0 ms). */
    fun observe(sampleWallMs: Long, receivedWallMs: Long): Long {
        val delta = receivedWallMs - sampleWallMs
        deltas[next] = delta
        next = (next + 1) % windowSize
        if (count < windowSize) count++
        var min = Long.MAX_VALUE
        for (i in 0 until count) if (deltas[i] < min) min = deltas[i]
        offsetMs = min
        return (delta - min).coerceAtLeast(0)
    }

    fun reset() { count = 0; next = 0; offsetMs = null }
}
