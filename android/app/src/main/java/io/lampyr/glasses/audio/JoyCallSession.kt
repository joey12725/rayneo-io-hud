package io.lampyr.glasses.audio

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface JoyCallSocket {
    fun text(value: String): Boolean
    fun binary(value: ByteArray): Boolean
    fun close()
    interface Listener {
        fun opened()
        fun text(value: String)
        fun binary(value: ByteArray)
        fun ended(reason: String = "connection lost")
    }
}

fun interface JoyCallSocketFactory {
    fun connect(listener: JoyCallSocket.Listener): JoyCallSocket
    /** A reconnect of a dropped call. [resume] is the bridge's call id when it sent one (null for an unpatched bridge). */
    fun reconnect(listener: JoyCallSocket.Listener, resume: String?): JoyCallSocket = connect(listener)
}

/** Playback head is the number of PCM samples actually played since the last flush. */
interface JoyCallAudio {
    val sampleRate: Int
    val playedSamples: Long
    fun open(interrupted: () -> Unit): Boolean
    fun capture(accept: (ShortArray) -> Unit): Boolean
    fun write(pcm: ByteArray, offset: Int): Int
    fun play()
    fun flush()
    fun close()
}

interface JoyCallExclusion {
    fun begin()
    fun end()
}

data class JoyCallEnd(val reason: String, val fallback: Boolean = false)

/** Streaming linear resampler and bounded 100ms uplink queue. Never catch up by bursting frames. */
class CallPcmFrames(private val rate: Int) {
    init { require(rate > 0) }
    private var index = -1L
    private var next = 0L
    private var previous: Short = 0
    private var used = 0
    private var partial = ByteArray(640)
    private val frames = ArrayDeque<ByteArray>()
    @Synchronized fun accept(samples: ShortArray) {
        samples.forEach { sample ->
            index++
            while (next <= index * 16_000) {
                val fraction = (next - (index - 1) * 16_000).toDouble() / 16_000
                val value = if (index == 0L) sample.toInt() else (previous + (sample - previous) * fraction).toInt()
                partial[used++] = value.toByte(); partial[used++] = (value shr 8).toByte()
                if (used == 640) {
                    if (frames.size == 5) frames.removeFirst()
                    frames.addLast(partial); partial = ByteArray(640); used = 0
                }
                next += rate
            }
            previous = sample
        }
    }
    @Synchronized fun take(muted: Boolean): ByteArray {
        val frame = if (frames.isEmpty()) ByteArray(640) else frames.removeFirst()
        return if (muted) ByteArray(640) else frame
    }
    @Synchronized fun clear() { frames.clear(); used = 0 }
}

/**
 * Single dispatcher owns the protocol and playback queue; socket/mic callbacks never do UI work.
 * Audio is memory-only.
 *
 * Long calls: there is no short cap ([maxCallMs], default 4 h, is a runaway guard) and a call that loses its
 * link after `ready` reconnects by itself for up to [RECONNECT_WINDOW_MS], keeping the microphone, the audio
 * route and the LifeLog exclusion. The reconnect sends `call_start` with the bridge's call id (`resume`), so a
 * voice bridge that supports resume (see docs/architecture.md) continues the same conversation; one that does not
 * starts a fresh assistant session.
 * A failure before the first `ready` still ends the call (fallback is true when turn-based calls may take over).
 * Playback never ends a call: a burst beyond [DOWNLINK_MAX] frames drops the oldest audio instead.
 */
class JoyCallSession(
    private val sockets: JoyCallSocketFactory,
    private val audio: JoyCallAudio,
    private val exclusion: JoyCallExclusion,
    private val nowMs: () -> Long,
    private val state: (String) -> Unit,
    private val pacingContext: CoroutineContext = EmptyCoroutineContext,
    /** The working agent (the main agent) the call handed work to; null when nothing is running. */
    private val worker: (JoyWorker?) -> Unit = {},
    private val maxCallMs: Long = MAX_CALL_MS,
) {
    companion object {
        const val MAX_CALL_MS = 4 * 60 * 60_000L
        const val RECONNECT_WINDOW_MS = 60_000L
        /** Delay before reconnect attempt n (the last value repeats). */
        val RECONNECT_BACKOFF_MS = longArrayOf(250, 1_000, 2_000, 4_000, 8_000)
        /** A reconnect attempt that does not reach `ready` within this is abandoned and retried. */
        const val ATTEMPT_TIMEOUT_MS = 10_000L
        /** 15 s of 20 ms downlink frames; beyond it the oldest audio is dropped down to [DOWNLINK_KEEP]. */
        const val DOWNLINK_MAX = 750
        const val DOWNLINK_KEEP = 150
        /** Sent in `hello`: the bridge only emits `agent`, `call` and `session` messages to clients that ask. */
        val CAPS = listOf("agent", "resume", "session")
        /** Bridge hang-ups after `ready` that mean "the line broke", not "the call is over". */
        val RECOVERABLE = setOf("error", "socket", "busy", "unavailable", "replaced")
    }
    private sealed interface Event {
        /** Socket generation; -1 is a local event that always applies. */
        val gen: Int
        data class Open(override val gen: Int) : Event
        data class Text(override val gen: Int, val value: String) : Event
        data class Binary(override val gen: Int, val value: ByteArray) : Event
        data class End(override val gen: Int, val reason: String) : Event
        data object Tick : Event { override val gen = -1 }
    }
    // Unbounded: a stalled consumer must never turn into a hang-up. Ticks are coalesced, so a stall queues at
    // most one tick; downlink audio is bounded by DOWNLINK_MAX once processed.
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val tickQueued = AtomicBoolean(false)
    @Volatile private var socket: JoyCallSocket? = null
    @Volatile private var gen = 0
    @Volatile private var linkReady = false
    private var linkOpen = false
    private var result: JoyCallEnd? = null
    private var opened = false
    private var ready = false
    private var remoteEnd = false
    private var acquired = false
    private var frames: CallPcmFrames? = null
    private val downlink = ArrayDeque<ByteArray>()
    private var offset = 0
    private var written = 0L
    private var playing = false
    private var pendingDrain = false
    private var firstAudioAt = 0L
    private var nextPingAt = 0L
    private var startedAt = 0L
    /** The bridge's id for this call (`call` message), sent back as `resume` on a reconnect. */
    var callId: String? = null; private set
    var reconnects = 0; private set
    var droppedFrames = 0L; private set
    private var reconnecting = false
    private var droppedAt = 0L
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var attemptStartedAt = 0L
    @Volatile private var closed = false
    @Volatile var muted = false; private set
    fun mute(value: Boolean) { muted = value; frames?.clear() }
    private fun post(event: Event) {
        if (event is Event.Tick) { if (!tickQueued.compareAndSet(false, true)) return }
        events.trySend(event)
    }
    private fun send(type: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement?>) {
        val s = socket ?: return // Reconnecting: control messages are not queued for a dead link.
        if (!s.text(obj("type" to str(type), *fields).toString())) lost("connection lost")
    }
    private fun finish(reason: String, fallback: Boolean = false) {
        if (result == null) result = JoyCallEnd(reason, fallback)
    }
    /** Called on the owning dispatcher, including service destruction. Releases capture immediately. */
    fun close(reason: String = "user") {
        finish(reason)
        release()
        events.trySend(Event.Tick)
    }
    private fun release() {
        closed = true
        if (!remoteEnd) socket?.text(obj("type" to str("hangup"), "reason" to str(result?.reason ?: "user")).toString())
        socket?.close(); socket = null; linkReady = false
        if (acquired) {
            acquired = false
            try { audio.close() } finally { exclusion.end() }
        }
    }
    private fun connect(resume: Boolean) {
        val g = ++gen
        linkOpen = false; linkReady = false
        attemptStartedAt = nowMs()
        val listener = object : JoyCallSocket.Listener {
            override fun opened() = post(Event.Open(g))
            override fun text(value: String) = post(Event.Text(g, value))
            override fun binary(value: ByteArray) = post(Event.Binary(g, value))
            override fun ended(reason: String) = post(Event.End(g, reason))
        }
        socket = if (resume) sockets.reconnect(listener, callId) else sockets.connect(listener)
    }
    /** The link broke. Before the first `ready` this ends the call; afterwards it starts (or continues) reconnecting. */
    private fun lost(reason: String) {
        if (result != null) return
        if (!ready) { finish(reason, !opened); return }
        val now = nowMs()
        if (!reconnecting) {
            reconnecting = true; droppedAt = now; attempts = 0
            state("reconnecting")
            // Audio queued for the old link is stale; the bridge resends nothing, so drop it.
            audio.flush(); downlink.clear(); offset = 0; written = 0; playing = false; pendingDrain = false
        }
        gen++ // Late events from the dead socket are ignored from here on.
        socket?.close(); socket = null; linkOpen = false; linkReady = false
        nextAttemptAt = now + RECONNECT_BACKOFF_MS[attempts.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        attempts++
    }
    suspend fun run(): JoyCallEnd = coroutineScope {
        startedAt = nowMs(); nextPingAt = startedAt + 20_000
        val ticker = launch { while (isActive) { delay(10); post(Event.Tick) } }
        var uplink: Job? = null
        try {
            exclusion.begin(); acquired = true
            if (!audio.open { post(Event.End(-1, "audio interrupted")) }) finish("microphone or audio unavailable")
            if (result == null) connect(resume = false)
            while (result == null) {
                val event = events.receive()
                if (event is Event.Tick) tickQueued.set(false)
                if (event.gen != -1 && event.gen != gen) continue // a superseded socket
                when (event) {
                    is Event.Open -> {
                        opened = true; linkOpen = true
                        send("hello", "fw" to str("rayneo-android"), "caps" to JsonArray(CAPS.map(::JsonPrimitive)))
                        if (reconnecting) send("call_start", "trigger" to str("button"), "preroll_ms" to JsonPrimitive(0), "resume" to (str(callId) ?: JsonPrimitive(true)))
                        else send("call_start", "trigger" to str("button"), "preroll_ms" to JsonPrimitive(0))
                    }
                    is Event.End -> if (event.gen == -1) finish(event.reason) else lost(event.reason)
                    is Event.Text -> {
                        val row = gatewayJson.parseToJsonElement(event.value)
                        when (row.s("type")) {
                            "ready" -> {
                                if (!ready) {
                                    frames = CallPcmFrames(audio.sampleRate)
                                    ready = audio.capture { frames?.accept(it) }
                                    if (!ready) finish("microphone unavailable") else {
                                        // Production uses a background dispatcher: rendering cannot delay mic pacing.
                                        // Tests inherit their virtual dispatcher, exercising the same deadline logic.
                                        uplink = launch(pacingContext) {
                                            var next = nowMs()
                                            var failed = -1
                                            while (isActive && !closed) {
                                                val now = nowMs()
                                                if (now >= next) {
                                                    val frame = frames!!.take(muted)
                                                    val s = socket; val g = gen
                                                    // While reconnecting the microphone keeps running; its audio is dropped.
                                                    if (linkReady && s != null && !s.binary(frame) && failed != g) { failed = g; post(Event.End(g, "connection lost")) }
                                                    next = if (now - next >= 20) now + 20 else next + 20
                                                }
                                                delay((next - nowMs()).coerceAtLeast(1))
                                            }
                                        }
                                    }
                                }
                                if (ready) {
                                    linkReady = true
                                    nextPingAt = nowMs() + 20_000
                                    if (reconnecting) { reconnecting = false; reconnects++ }
                                    state("listening")
                                }
                            }
                            "call" -> row.s("id").takeIf { it.isNotBlank() }?.let { callId = it }
                            "state" -> when (row.s("value")) {
                                "listening", "thinking" -> state(row.s("value"))
                                "joy_speaking", "speaking" -> state("speaking")
                            }
                            "session" -> when (row.s("state")) {
                                "rotating" -> state("compacting")
                                "rotated" -> state("listening")
                            }
                            "agent" -> {
                                val value = row.s("state")
                                worker(if (value.isBlank() || value == "idle") null else
                                    JoyWorker(value, row.s("tool").takeIf { it.isNotBlank() }, row.s("task").takeIf { it.isNotBlank() }))
                            }
                            "clear" -> {
                                audio.flush(); downlink.clear(); offset = 0; written = 0
                                playing = false; pendingDrain = false
                                state("interrupted")
                            }
                            "hangup" -> {
                                val reason = row.s("reason", "ended")
                                if (ready && reason in RECOVERABLE) lost(reason)
                                else { remoteEnd = true; finish(reason, reason == "unavailable") }
                            }
                        }
                    }
                    is Event.Binary -> {
                        if (event.value.size != 960) finish("invalid audio frame")
                        else {
                            if (downlink.size >= DOWNLINK_MAX) {
                                // A network burst or a stalled consumer: keep the newest audio, never hang up.
                                while (downlink.size > DOWNLINK_KEEP) { downlink.removeFirst(); droppedFrames++ }
                                offset = 0
                            }
                            if (!pendingDrain) firstAudioAt = nowMs()
                            pendingDrain = true; downlink.addLast(event.value)
                        }
                    }
                    Event.Tick -> tick()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { finish("connection lost", !opened) }
        finally { ticker.cancel(); uplink?.cancel(); release(); events.close() }
        result ?: JoyCallEnd("ended")
    }
    private fun tick() {
        val now = nowMs()
        if (!ready && now - startedAt >= 12_000) { finish("connection timeout", true); return }
        if (maxCallMs > 0 && now - startedAt >= maxCallMs) { finish("cap"); return }
        if (reconnecting) {
            if (now - droppedAt >= RECONNECT_WINDOW_MS) { finish("connection lost"); return }
            if (socket == null && now >= nextAttemptAt) connect(resume = true)
            else if (socket != null && !linkReady && now - attemptStartedAt >= ATTEMPT_TIMEOUT_MS) lost("reconnect timeout")
        }
        if (linkOpen && now >= nextPingAt) { send("ping"); nextPingAt = now + 20_000 }
        if (pendingDrain && (playing || now - firstAudioAt >= 80 || downlink.size >= 4)) {
            // Nonblocking writes keep clear/hangup responsive even if the hardware stalls.
            repeat(8) {
                val frame = downlink.firstOrNull() ?: return@repeat
                val count = audio.write(frame, offset)
                if (count < 0 || count > frame.size - offset || count % 2 != 0) { finish("playback failed"); return }
                written += count / 2; offset += count
                if (offset == frame.size) { downlink.removeFirst(); offset = 0 }
            }
            if (!playing && written > 0) { audio.play(); playing = true }
            if (playing && downlink.isEmpty() && audio.playedSamples >= written) {
                send("drained"); pendingDrain = false
                audio.flush(); written = 0; playing = false
            }
        }
    }
}
