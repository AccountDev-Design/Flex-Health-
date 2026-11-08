package com.flexhealth.heartmonitor.engine

/**
 * Búfer circular de capacidad fija, ordenado por tiempo, sin asignaciones en el camino caliente.
 * Los tiempos están en el reloj monotónico del receptor (ms). Cuando se llena, descarta la
 * muestra más antigua: la memoria nunca crece.
 */
class SampleRingBuffer(val capacity: Int) {
    init { require(capacity > 1) }

    private val times = LongArray(capacity)
    private val values = IntArray(capacity)
    private val flags = ByteArray(capacity)
    private var start = 0

    var size: Int = 0
        private set

    private fun phys(i: Int) = (start + i) % capacity

    fun timeAt(i: Int): Long = times[phys(i)]
    fun bpmAt(i: Int): Int = values[phys(i)]
    fun flagsAt(i: Int): Int = flags[phys(i)].toInt()

    /** Inserta respetando el orden temporal. Devuelve `false` si ya existe una muestra con ese tiempo. */
    fun add(timeMs: Long, bpm: Int, flag: Int = 0): Boolean {
        // Posición de inserción (normalmente al final: las muestras llegan casi siempre en orden).
        var pos = size
        while (pos > 0 && timeAt(pos - 1) > timeMs) pos--
        if (pos > 0 && timeAt(pos - 1) == timeMs) return false
        if (size == capacity) {
            if (pos == 0) return false // más antigua que todo lo almacenado con el búfer lleno
            start = (start + 1) % capacity
            size--
            pos--
        }
        // Desplaza [pos, size) una posición hacia la derecha.
        var i = size
        while (i > pos) {
            val dst = phys(i); val src = phys(i - 1)
            times[dst] = times[src]; values[dst] = values[src]; flags[dst] = flags[src]
            i--
        }
        val p = phys(pos)
        times[p] = timeMs; values[p] = bpm; flags[p] = flag.toByte()
        size++
        return true
    }

    /** Elimina muestras con tiempo < [minTimeMs]. */
    fun trimBefore(minTimeMs: Long) {
        while (size > 0 && times[start] < minTimeMs) {
            start = (start + 1) % capacity
            size--
        }
    }

    fun clear() { start = 0; size = 0 }

    fun latestTime(): Long? = if (size == 0) null else timeAt(size - 1)
    fun latestBpm(): Int? = if (size == 0) null else bpmAt(size - 1)
}
