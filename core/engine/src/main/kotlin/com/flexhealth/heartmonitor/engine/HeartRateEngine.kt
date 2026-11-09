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
    /** Muestras con calidad «no fiable» informada por el sensor (se muestran marcadas, no entran en estadísticas). */
    val unreliable: Long = 0,
    /** Picos aislados descartados por inverosímiles (no confirmados por la muestra siguiente). */
    val suspicious: Long = 0,
    /** Saltos grandes confirmados por la muestra siguiente (cambios reales). */
    val confirmedJumps: Long = 0,
)

data class MonitorSnapshot(
    val sessionId: String? = null,
    val device: String? = null,
    val source: String? = null,
    /** Último BPM medido (por tiempo de medición). `null` si no hay datos o se perdió la señal. */
    val bpm: Int? = null,
    val quality: ReadingQuality = ReadingQuality.UNKNOWN,
    /** `false` si el sensor marcó la última lectura como no fiable. */
    val reliable: Boolean = true,
    /** `true` mientras una lectura con un salto inverosímil espera confirmación (no se muestra). */
    val verifying: Boolean = false,
    /**
     * Estado del sensor posterior a la última muestra que invalida el valor mostrado (fuera de la muñeca,
     * buscando pulso, sin permiso…). `null` si la última información del sensor es una lectura válida.
     */
    val signalIssue: SensorStatus? = null,
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
    /**
     * BPM que la UI puede mostrar: oculto si la señal está perdida o si el sensor informó después de la
     * última lectura que ya no mide (p. ej. fuera de la muñeca). Nunca se muestra un dato antiguo como actual.
     */
    val displayBpm: Int? get() = if ((freshness == Freshness.LIVE || freshness == Freshness.STALE) && signalIssue == null) bpm else null
}

enum class IngestResult {
    SAMPLE, STATUS, INVALID, DUPLICATE, TOO_OLD,
    /** Salto inverosímil retenido a la espera de confirmación (no se muestra ni se guarda todavía). */
    SUSPICIOUS,
}

/** Ventana del historial lista para dibujar: edades (ms antes de "ahora") y BPM. */
class HistoryWindow(capacity: Int) {
    val ageMs = LongArray(capacity)
    val bpm = IntArray(capacity)
    val late = BooleanArray(capacity)
    /** Marcadas como no fiables por el sensor: la gráfica no las une a la línea. */
    val unreliable = BooleanArray(capacity)
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
    private var latestSampleSeq = -1L

    // Filtro de artefactos: referencia = última lectura fiable aceptada; pending = salto por confirmar.
    private var refElapsed = 0L
    private var refBpm = -1
    private var pending: Pending? = null

    private class Pending(val elapsed: Long, val bpm: Int, val seq: Long, val quality: ReadingQuality, val late: Boolean, val receivedElapsed: Long)

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
            if (isLatestStatus) {
                // Un estado más reciente que la última lectura que indica que el sensor no mide invalida el valor.
                val issue = packet.sensorStatus.takeIf { it != SensorStatus.ACTIVE && it != SensorStatus.UNKNOWN }
                base = if (issue != null && packet.sequence > latestSampleSeq) base.copy(signalIssue = issue)
                else if (issue == null) base.copy(signalIssue = null) else base
            }
            _snapshot.value = base
            publish(); return IngestResult.STATUS
        }

        val lateness = clockSync.observe(packet.timestampMs, receivedWallMs)
        val measuredElapsed = receivedElapsedMs - lateness
        if (receivedElapsedMs - measuredElapsed > maxHistoryMs) {
            stats = stats.copy(tooOld = stats.tooOld + 1); publish(); return IngestResult.TOO_OLD
        }
        val isLate = lateness >= policy.lateAfterMs
        val unreliable = packet.quality == ReadingQuality.UNRELIABLE
        val newest = newestElapsed
        val isNewest = newest == null || measuredElapsed >= newest
        base = base.copy(lastLatenessMs = lateness, clockOffsetMs = clockSync.offsetMs)

        if (isNewest && !unreliable) {
            // 1) Resolver un salto pendiente con esta muestra.
            val p = pending
            if (p != null) {
                pending = null
                val confirms = measuredElapsed - p.elapsed <= CONFIRM_WINDOW_MS && kotlin.math.abs(bpm - p.bpm) <= CONFIRM_TOLERANCE_BPM
                if (confirms) {
                    store(p.elapsed, p.bpm, p.late, false, p.seq)
                    stats = stats.copy(confirmedJumps = stats.confirmedJumps + 1)
                    base = base.copy(bpm = p.bpm, quality = p.quality, reliable = true)
                } else {
                    stats = stats.copy(suspicious = stats.suspicious + 1)
                }
            }
            // 2) ¿Es esta muestra un salto inverosímil respecto de la última lectura fiable reciente?
            if (isImplausibleJump(measuredElapsed, bpm)) {
                pending = Pending(measuredElapsed, bpm, packet.sequence, packet.quality, isLate, receivedElapsedMs)
                _snapshot.value = base
                publish(); return IngestResult.SUSPICIOUS
            }
        }

        if (!store(measuredElapsed, bpm, isLate, unreliable, packet.sequence)) {
            stats = stats.copy(duplicates = stats.duplicates + 1); _snapshot.value = base; publish(); return IngestResult.DUPLICATE
        }
        if (unreliable) stats = stats.copy(unreliable = stats.unreliable + 1)
        if (isNewest) {
            base = base.copy(bpm = bpm, quality = packet.quality, reliable = !unreliable, signalIssue = null)
        }
        _snapshot.value = base
        publish()
        wake.trySend(Unit)
        return IngestResult.SAMPLE
    }

    /** Guarda una muestra real en el historial. Devuelve `false` si ya existía (duplicado por tiempo). */
    private fun store(elapsed: Long, bpm: Int, late: Boolean, unreliable: Boolean, seq: Long): Boolean {
        val flags = (if (late) FLAG_LATE else 0) or (if (unreliable) FLAG_UNRELIABLE else 0)
        if (!buffer.add(elapsed, bpm, flags)) return false
        buffer.trimBefore(clock.elapsedMs() - maxHistoryMs)
        stats = stats.copy(samples = stats.samples + 1, late = stats.late + if (late) 1 else 0)
        historyVersion++
        val newest = newestElapsed
        if (newest == null || elapsed >= newest) {
            newestElapsed = elapsed
            if (seq > latestSampleSeq) latestSampleSeq = seq
        }
        if (!unreliable && elapsed >= refElapsed) { refElapsed = elapsed; refBpm = bpm }
        return true
    }

    /**
     * Salto inverosímil: más de max(30 BPM, 12 BPM/s × Δt) respecto de la última lectura fiable de los
     * últimos 10 s. Los cambios fisiológicos rápidos se confirman con la muestra siguiente; los artefactos
     * ópticos aislados (p. ej. 70 → 190 → 71) se descartan. Sin referencia reciente no se juzga.
     */
    private fun isImplausibleJump(elapsed: Long, bpm: Int): Boolean {
        if (refBpm < 0) return false
        val dtMs = elapsed - refElapsed
        if (dtMs < 0 || dtMs > REFERENCE_WINDOW_MS) return false
        val allowed = maxOf(MIN_JUMP_BPM.toDouble(), MAX_RATE_BPM_PER_S * maxOf(1.0, dtMs / 1000.0))
        return kotlin.math.abs(bpm - refBpm) > allowed
    }

    private fun startSession() {
        buffer.clear(); clockSync.reset()
        newestElapsed = null; latestStatusSeq = -1; latestSampleSeq = -1
        refBpm = -1; refElapsed = 0; pending = null
        historyVersion++
        stats = stats.copy(sessions = stats.sessions + 1)
        _snapshot.value = _snapshot.value.copy(bpm = null, quality = ReadingQuality.UNKNOWN, reliable = true, signalIssue = null, lastLatenessMs = null)
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
            if (buffer.flagsAt(i) and FLAG_UNRELIABLE != 0) continue // no fiables: fuera de las estadísticas
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
            verifying = pending?.let { now - it.receivedElapsed < CONFIRM_WINDOW_MS } ?: false,
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
            out.unreliable[n] = buffer.flagsAt(i) and FLAG_UNRELIABLE != 0
            n++
        }
        out.count = n
        return n
    }

    /** Borra todo (al detener la monitorización): no se guardan datos de salud. */
    @Synchronized
    fun reset() {
        buffer.clear(); sequences.reset(); clockSync.reset()
        newestElapsed = null; latestStatusSeq = -1; latestSampleSeq = -1
        refBpm = -1; refElapsed = 0; pending = null
        stats = EngineStats(); historyVersion++
        _snapshot.value = MonitorSnapshot(historyVersion = historyVersion)
    }

    companion object {
        const val FLAG_LATE = 1
        const val FLAG_UNRELIABLE = 2
        private const val MIN_JUMP_BPM = 30
        private const val MAX_RATE_BPM_PER_S = 12.0
        private const val REFERENCE_WINDOW_MS = 10_000L
        private const val CONFIRM_WINDOW_MS = 5_000L
        private const val CONFIRM_TOLERANCE_BPM = 15
        private const val IDLE_WAIT_MS = 60_000L
    }
}
