package com.flexhealth.heartmonitor.engine

import com.flexhealth.heartmonitor.protocol.HeartRatePacket
import com.flexhealth.heartmonitor.protocol.PacketValidator
import com.flexhealth.heartmonitor.protocol.ReadingQuality
import com.flexhealth.heartmonitor.protocol.SensorStatus
import com.flexhealth.heartmonitor.protocol.ValidationResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Fuente de tiempo inyectable (pruebas deterministas). */
interface EngineClock {
    /** Reloj de pared (epoch ms). */
    fun wallMs(): Long
    /** Reloj monotónico (ms), p. ej. SystemClock.elapsedRealtime(). */
    fun elapsedMs(): Long

    object System : EngineClock {
        override fun wallMs() = java.lang.System.currentTimeMillis()
        override fun elapsedMs() = java.lang.System.nanoTime() / 1_000_000
    }
}

data class EngineStats(
    val received: Long = 0,
    val samples: Long = 0,
    val statusPackets: Long = 0,
    val invalid: Long = 0,
    val duplicates: Long = 0,
    val outOfOrder: Long = 0,
    val tooOld: Long = 0,
    val late: Long = 0,
    val lost: Long = 0,
    val sessions: Long = 0,
)

data class MonitorSnapshot(
    val sessionId: String? = null,
    val device: String? = null,
    val source: String? = null,
    /** Último BPM medido (por tiempo de medición). `null` si no hay datos o se perdió la señal. */
    val bpm: Int? = null,
    val quality: ReadingQuality = ReadingQuality.UNKNOWN,
    val sensorStatus: SensorStatus = SensorStatus.UNKNOWN,
    val freshness: Freshness = Freshness.WAITING,
    /** Antigüedad estimada de la última medición en el momento del snapshot. */
    val sampleAgeMs: Long? = null,
    val lastLatenessMs: Long? = null,
    val clockOffsetMs: Long? = null,
    val windowMin: Int? = null,
    val windowMax: Int? = null,
    val windowAvg: Int? = null,
    val stats: EngineStats = EngineStats(),
    /** Se incrementa cada vez que cambia el historial (para redibujar la gráfica). */
    val historyVersion: Long = 0,
) {
    /** BPM que la UI puede mostrar: oculto cuando la señal está perdida. */
    val displayBpm: Int? get() = if (freshness == Freshness.LIVE || freshness == Freshness.STALE) bpm else null
}

enum class IngestResult { SAMPLE, STATUS, INVALID, DUPLICATE, TOO_OLD }

/** Ventana del historial lista para dibujar: edades (ms antes de "ahora") y BPM. */
class HistoryWindow(capacity: Int) {
    val ageMs = LongArray(capacity)
    val bpm = IntArray(capacity)
    val late = BooleanArray(capacity)
    var count = 0
}

/**
 * Motor de datos compartido por el teléfono y la tablet. Valida paquetes, controla secuencias,
 * estima el retraso, guarda el historial en un búfer circular y calcula la actualidad
 * (LIVE/STALE/LOST). Nunca interpola ni genera mediciones: solo almacena lo recibido.
 *
 * Hilos: los métodos públicos son seguros entre hilos (sincronizados); el estado se publica
 * por [snapshot].
 */
class HeartRateEngine(
    private val clock: EngineClock = EngineClock.System,
    val policy: FreshnessPolicy = FreshnessPolicy(),
    bufferCapacity: Int = 1024,
    /** Ventana máxima de historial que se conserva (la gráfica permite hasta 120 s). */
    val maxHistoryMs: Long = 120_000,
    private val statsWindowMs: Long = 60_000,
) {
    private val buffer = SampleRingBuffer(bufferCapacity)
    private val sequences = SequenceTracker()
    private val clockSync = ClockSync()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var newestElapsed: Long? = null
    private var stats = EngineStats()
    private var historyVersion = 0L
    private var latestStatusSeq = -1L

    private val _snapshot = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun onPacket(
        packet: HeartRatePacket,
        receivedWallMs: Long = clock.wallMs(),
        receivedElapsedMs: Long = clock.elapsedMs(),
    ): IngestResult {
        stats = stats.copy(received = stats.received + 1)
        if (PacketValidator.validate(packet) !is ValidationResult.Valid) {
            stats = stats.copy(invalid = stats.invalid + 1)
            publish(); return IngestResult.INVALID
        }
        when (sequences.check(packet.sessionId, packet.sequence)) {
            SequenceTracker.Decision.NEW_SESSION -> startSession()
            SequenceTracker.Decision.IN_ORDER -> Unit
            SequenceTracker.Decision.OUT_OF_ORDER -> stats = stats.copy(outOfOrder = stats.outOfOrder + 1)
            SequenceTracker.Decision.DUPLICATE -> {
                stats = stats.copy(duplicates = stats.duplicates + 1); publish(); return IngestResult.DUPLICATE
            }
            SequenceTracker.Decision.TOO_OLD -> {
                stats = stats.copy(tooOld = stats.tooOld + 1); publish(); return IngestResult.TOO_OLD
            }
        }
        val cur = _snapshot.value
        // El estado del sensor lo marca el paquete más reciente por secuencia.
        val isLatestStatus = packet.sequence >= latestStatusSeq
        if (isLatestStatus) latestStatusSeq = packet.sequence
        var base = cur.copy(
            sessionId = packet.sessionId,
            device = packet.device,
            source = packet.source ?: cur.source,
            sensorStatus = if (isLatestStatus) packet.sensorStatus else cur.sensorStatus,
        )
        val bpm = packet.bpm
        if (bpm == null) {
            stats = stats.copy(statusPackets = stats.statusPackets + 1)
            _snapshot.value = base
            publish(); return IngestResult.STATUS
        }

        val lateness = clockSync.observe(packet.timestampMs, receivedWallMs)
        val measuredElapsed = receivedElapsedMs - lateness
        if (receivedElapsedMs - measuredElapsed > maxHistoryMs) {
            stats = stats.copy(tooOld = stats.tooOld + 1); publish(); return IngestResult.TOO_OLD
        }
        val isLate = lateness >= policy.lateAfterMs
        val added = buffer.add(measuredElapsed, bpm, if (isLate) FLAG_LATE else 0)
        if (!added) {
            stats = stats.copy(duplicates = stats.duplicates + 1); publish(); return IngestResult.DUPLICATE
        }
        buffer.trimBefore(receivedElapsedMs - maxHistoryMs)
        stats = stats.copy(samples = stats.samples + 1, late = stats.late + if (isLate) 1 else 0)
        historyVersion++
        val newest = newestElapsed
        if (newest == null || measuredElapsed >= newest) {
            newestElapsed = measuredElapsed
            base = base.copy(bpm = bpm, quality = packet.quality)
        }
        base = base.copy(lastLatenessMs = lateness, clockOffsetMs = clockSync.offsetMs)
        _snapshot.value = base
        publish()
        wake.trySend(Unit)
        return IngestResult.SAMPLE
    }

    private fun startSession() {
        buffer.clear(); clockSync.reset()
        newestElapsed = null; latestStatusSeq = -1
        historyVersion++
        stats = stats.copy(sessions = stats.sessions + 1)
        _snapshot.value = _snapshot.value.copy(bpm = null, quality = ReadingQuality.UNKNOWN, lastLatenessMs = null)
    }

    /** Recalcula la actualidad. Devuelve ms hasta la siguiente transición (o `null`). */
    @Synchronized
    fun tick(): Long? {
        publish()
        return policy.untilNextTransition(_snapshot.value.sampleAgeMs)
    }

    private fun publish() {
        val now = clock.elapsedMs()
        val age = newestElapsed?.let { (now - it).coerceAtLeast(0) }
        var min = Int.MAX_VALUE; var max = Int.MIN_VALUE; var sum = 0L; var n = 0
        val from = now - statsWindowMs
        for (i in buffer.size - 1 downTo 0) {
            if (buffer.timeAt(i) < from) break
            val v = buffer.bpmAt(i)
            if (v < min) min = v
            if (v > max) max = v
            sum += v; n++
        }
        _snapshot.value = _snapshot.value.copy(
            freshness = policy.classify(age),
            sampleAgeMs = age,
            windowMin = if (n > 0) min else null,
            windowMax = if (n > 0) max else null,
            windowAvg = if (n > 0) (sum / n).toInt() else null,
            stats = stats.copy(lost = sequences.lost),
            historyVersion = historyVersion,
        )
    }

    /** Bucle que actualiza la actualidad solo cuando hace falta (sin sondeo continuo). */
    suspend fun runFreshnessTicker() {
        while (true) {
            val wait = tick()
            withTimeoutOrNull(wait ?: IDLE_WAIT_MS) { wake.receive() }
        }
    }

    /** Copia las muestras de los últimos [windowMs] ms en [out]. Devuelve el número copiado. */
    @Synchronized
    fun copyHistory(windowMs: Long, out: HistoryWindow, nowElapsedMs: Long = clock.elapsedMs()): Int {
        val from = nowElapsedMs - windowMs
        var first = buffer.size
        while (first > 0 && buffer.timeAt(first - 1) >= from) first--
        var n = 0
        val cap = out.ageMs.size
        val skip = maxOf(0, (buffer.size - first) - cap)
        for (i in first + skip until buffer.size) {
            out.ageMs[n] = (nowElapsedMs - buffer.timeAt(i)).coerceAtLeast(0)
            out.bpm[n] = buffer.bpmAt(i)
            out.late[n] = buffer.flagsAt(i) and FLAG_LATE != 0
            n++
        }
        out.count = n
        return n
    }

    /** Borra todo (al detener la monitorización): no se guardan datos de salud. */
    @Synchronized
    fun reset() {
        buffer.clear(); sequences.reset(); clockSync.reset()
        newestElapsed = null; latestStatusSeq = -1
        stats = EngineStats(); historyVersion++
        _snapshot.value = MonitorSnapshot(historyVersion = historyVersion)
    }

    companion object {
        const val FLAG_LATE = 1
        private const val IDLE_WAIT_MS = 60_000L
    }
}
