package com.flexhealth.heartmonitor.wear.service

/**
 * Descarta en origen lecturas repetidas o desordenadas del sensor (misma marca temporal o anterior a la
 * última enviada), para no transmitir duplicados.
 */
class ReadingGate {
    private var lastTimestampMs = Long.MIN_VALUE

    @Synchronized
    fun accept(timestampMs: Long): Boolean {
        if (timestampMs <= lastTimestampMs) return false
        lastTimestampMs = timestampMs
        return true
    }
}
