package io.lampyr.glasses.ambient

import io.lampyr.glasses.glasses.protocol.*
import java.util.UUID

/** utc is estimated frame start; arrivalUtc is the unmodified phone clock at Bluetooth ingress. */
data class AmbientPacket(val opus: ByteArray, val utc: Long, val source: String, val vpu: Boolean = false,
    val arrivalUtc: Long = utc, val discontinuityBefore: String? = null, val speech: Boolean = true)

/** The spec (§3 sequence notes, §7.1 and §7.4) only promises de-duplication, not
 * contiguous per-audio sequence numbers or a wrap width. LifeLog uses a shared frame
 * counter. Infer discontinuities from arrival vs exact received-sample clocks instead.
 * A two-second tolerance absorbs Bluetooth batching; smaller losses remain uncertain.
 */
class ArrivalSampleClock(private val thresholdMs: Long = 2000) {
    private var expectedEndUs: Long? = null
    fun reset() { expectedEndUs = null }
    fun batch(arrival: Long, samples: Long): String? {
        val durationUs = samples * 1000 / 48 // preserve 2.5ms TOC frames, no rounding drift
        val drift = expectedEndUs?.let { arrival * 1000 - durationUs - it }
        val discontinuity = drift != null && (drift > thresholdMs * 1000 || drift < -thresholdMs * 1000)
        expectedEndUs = if (expectedEndUs == null || discontinuity) arrival * 1000 else expectedEndUs!! + durationUs
        return if (discontinuity) "arrival/sample clock discontinuity (${drift!! / 1000}ms; loss/stall/clock change)" else null
    }
}

/** Maps a position in received LifeLog audio to UTC. On current firmware LifeLog is not real time: the
 * glasses buffer about 20 s and deliver it in about one second, every 20 s. Arrival time therefore only
 * bounds the END of what has been received. The sample count is trusted; arrival only anchors it.
 *
 * Every batch gives a candidate origin (where the run would start if this batch ended exactly at
 * arrival). The true origin is never later than any candidate, so the origin is the minimum candidate
 * over a sliding window long enough to include the end of at least two bursts. A real gap (wall time
 * not covered by audio by more than [gapMs], longer than one burst period) starts a new run.
 */
class StreamClock(private val gapMs: Long = 30_000, private val windowMs: Long = 60_000) {
    private val candidates = ArrayDeque<Pair<Long, Long>>() // arrival ms to candidate origin us, increasing candidates
    private var samples = 0L
    var lastArrival: Long? = null; private set
    fun reset() { candidates.clear(); samples = 0; lastArrival = null }
    /** Returns the UTC start (ms) of this batch, and whether a real gap preceded it. */
    fun batch(arrivalMs: Long, count: Long): Pair<Long, Boolean> {
        var candidate = arrivalMs * 1000 - (samples + count) * 1000 / 48
        val previous = candidates.firstOrNull()?.second
        val gap = previous != null && candidate - previous > gapMs * 1000
        if (gap) { candidates.clear(); samples = 0; candidate = arrivalMs * 1000 - count * 1000 / 48 }
        while (candidates.isNotEmpty() && arrivalMs - candidates.first().first > windowMs) candidates.removeFirst()
        while (candidates.isNotEmpty() && candidates.last().second >= candidate) candidates.removeLast()
        candidates.addLast(arrivalMs to candidate)
        val start = candidates.first().second + samples * 1000 / 48
        samples += count; lastArrival = arrivalMs
        return Math.floorDiv(start, 1000L) to gap
    }
}

interface AmbientCaptureSource {
    suspend fun start()
    suspend fun event(event: GlassesEvent, utc: Long)
    suspend fun stop()
}

/** A1 -> A2/A8 -> A3 (split by EventParser, preserving VAD/VPU) -> A5.
 * A3 is documented MONO; do not label it as independent wearer/room stereo.
 * The optional A7 probe is diagnostic only: A4 never enters the master automatically.
 */
class LifeLogCaptureSource(private val send: suspend (Command) -> Unit,
    private val packet: (AmbientPacket) -> Unit, private val boundary: () -> Unit,
    private val diagnostic: (String) -> Unit, private val probeCache: Boolean = false) : AmbientCaptureSource {
    private var task: String? = null
    private val sequences = AudioSequenceWindow()
    private val clock = StreamClock()
    override suspend fun start() {
        send(Commands.lifeLogSwitch(true))
        if (probeCache) { send(Commands.lifeLogCache()); diagnostic("A7 cached-audio availability probe sent; wire schema remains unverified") }
    }
    override suspend fun event(event: GlassesEvent, utc: Long) {
        if (event is GlassesEvent.Message && event.business == 0x0f && event.body.string("cmd") == "life_log_switch_result") {
            val result = (event.body["payload"] as? kotlinx.serialization.json.JsonObject)?.int("value")
            diagnostic("LifeLog switch result=$result (1 mic permission, 2 temples, 3 battery)")
        }
        if (event !is GlassesEvent.LifeLog) return
        when (event.type) {
            161 -> if (task == null) {
                task = UUID.randomUUID().toString(); sequences.clear()
                // Consecutive LifeLog rounds are one stream; only a long silence starts a new clock run.
                if (clock.lastArrival?.let { utc - it > 30_000 } != false) clock.reset()
                send(Commands.lifeLogStart(task!!)); send(Commands.lifeLogPage())
            }
            163 -> if (task != null && task == event.body.string("taskId") && sequences.accept(event.sequence?.toLong())) {
                // Audio arrives in bursts: stamp it by the stream clock (exact frame counts anchored to
                // arrival), and mark a discontinuity only for a real gap in the audio.
                val samples = event.packets.sumOf { OpusPacket.samples(it.opus) }
                val (startUtc, gap) = if (samples > 0) clock.batch(utc, samples) else (utc to false)
                val drop = if (gap) "LifeLog audio gap (missing audio)" else null
                var offset = 0L
                event.packets.forEach {
                    packet(AmbientPacket(it.opus, startUtc + offset / 48, "lifelog-mono", it.vpu, utc, if (offset == 0L) drop else null, speech = it.vad))
                    offset += OpusPacket.samples(it.opus)
                }
            }
            164 -> if (probeCache) diagnostic("A4 cache reply: frames=${event.packets.size}, finished=${event.body.string("isFinish")}; not imported")
            165 -> if (event.body.string("taskId") == null || event.body.string("taskId") == task) { task = null; boundary() }
        }
    }
    override suspend fun stop() { if (task != null) send(Commands.lifeLogStop()); task = null; clock.reset(); send(Commands.lifeLogSwitch(false)); boundary() }
}

/** Continuous prompt stereo uplink; decoded PCM is gated by the dependency-free phone VAD. */
class ContinuousCaptureSource(private val send: suspend (Command) -> Unit,
    private val packet: (AmbientPacket) -> Unit, private val diagnostic: (String) -> Unit) : AmbientCaptureSource {
    private var sid: String? = null
    private val sequences = AudioSequenceWindow()
    private val clock = ArrivalSampleClock()
    override suspend fun start() {
        sid = UUID.randomUUID().toString().replace("-", ""); sequences.clear(); clock.reset()
        send(Commands.pageStart(sid!!, prompt = true))
    }
    override suspend fun event(event: GlassesEvent, utc: Long) {
        when (event) {
            is GlassesEvent.Audio -> if (event.business == 0x17 && event.sid == sid && sequences.accept(event.sequence)) {
                val samples = OpusPacket.samples(event.packet)
                packet(AmbientPacket(event.packet, utc - samples / 48, "phone-vad-stereo", arrivalUtc = utc, discontinuityBefore = clock.batch(utc, samples)))
            }
            is GlassesEvent.PageReply -> if (event.business == 0x17 && event.sid == sid && event.type == 2 && event.body.int("code") != 1) diagnostic("Continuous prompt audio rejected")
            is GlassesEvent.PageExit -> if (event.business == 0x17 && event.sid == sid) { sid = null; diagnostic("Continuous prompt page exited; resume manually") }
            else -> Unit
        }
    }
    override suspend fun stop() { sid?.let { send(Commands.pageClose(it, prompt = true)) }; sid = null }
}

/** Auto switches only on evidence of audio without wake. Silence alone never enables streaming. */
class AutoFallbackPolicy(private val windowMs: Long = 10_000) {
    private var since = 0L
    private var wake = false
    fun reset(now: Long) { since = now; wake = false }
    fun wake() { wake = true }
    fun fallback(now: Long, hasAudio: Boolean) = !wake && hasAudio && now - since >= windowMs
}
