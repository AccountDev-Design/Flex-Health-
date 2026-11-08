package com.flexhealth.heartmonitor.engine

/** Estado de actualidad de los datos mostrados. */
enum class Freshness {
    /** Aún no se ha recibido ninguna muestra en esta sesión. */
    WAITING,
    /** Última muestra con antigüedad < staleAfter. */
    LIVE,
    /** staleAfter ≤ antigüedad < lostAfter: los datos se marcan como desactualizados. */
    STALE,
    /** Antigüedad ≥ lostAfter: pérdida de señal; no se muestra BPM. */
    LOST,
}

data class FreshnessPolicy(
    val staleAfterMs: Long = 3_000,
    val lostAfterMs: Long = 5_000,
    /** Retraso a partir del cual una lectura se considera "atrasada". */
    val lateAfterMs: Long = 2_500,
) {
    init { require(staleAfterMs in 1 until lostAfterMs) }

    fun classify(ageMs: Long?): Freshness = when {
        ageMs == null -> Freshness.WAITING
        ageMs < staleAfterMs -> Freshness.LIVE
        ageMs < lostAfterMs -> Freshness.STALE
        else -> Freshness.LOST
    }

    /** Milisegundos hasta el siguiente cambio de estado, o `null` si no habrá más cambios. */
    fun untilNextTransition(ageMs: Long?): Long? = when {
        ageMs == null -> null
        ageMs < staleAfterMs -> staleAfterMs - ageMs
        ageMs < lostAfterMs -> lostAfterMs - ageMs
        else -> null
    }
}
