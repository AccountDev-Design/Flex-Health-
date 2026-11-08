package com.flexhealth.heartmonitor.engine

/**
 * Control de secuencias por sesión con ventana deslizante (al estilo anti-replay de IPsec):
 * detecta duplicados, desorden, pérdidas y cambios de sesión.
 */
class SequenceTracker {
    enum class Decision { NEW_SESSION, IN_ORDER, OUT_OF_ORDER, DUPLICATE, TOO_OLD }

    var sessionId: String? = null
        private set
    var highest: Long = -1
        private set
    /** Huecos de secuencia aún no rellenados dentro de la sesión. */
    var lost: Long = 0
        private set

    private var window = 0L // bit i => (highest - i) recibido

    fun check(session: String, seq: Long): Decision {
        if (session != sessionId) {
            sessionId = session; highest = seq; window = 1L; lost = 0
            return Decision.NEW_SESSION
        }
        if (seq > highest) {
            val shift = seq - highest
            lost += shift - 1
            window = if (shift >= WINDOW) 1L else (window shl shift.toInt()) or 1L
            highest = seq
            return Decision.IN_ORDER
        }
        val diff = highest - seq
        if (diff >= WINDOW) return Decision.TOO_OLD
        val bit = 1L shl diff.toInt()
        if (window and bit != 0L) return Decision.DUPLICATE
        window = window or bit
        if (lost > 0) lost--
        return Decision.OUT_OF_ORDER
    }

    fun reset() { sessionId = null; highest = -1; window = 0; lost = 0 }

    companion object { const val WINDOW = 64 }
}
