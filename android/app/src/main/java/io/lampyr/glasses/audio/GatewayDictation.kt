package io.lampyr.glasses.audio

import android.content.Context
import io.lampyr.glasses.data.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.*

/** Context keys the gateway reads for dictation cleanup; anything else stays on the phone. */
private val DICTATION_CONTEXT = setOf("chatId", "machine", "thread", "project")
fun dictatePath(target: String, context: Map<String, String> = emptyMap()) = "/voice/dictate?target=${segment(target)}" +
    context.filter { (k, v) -> k in DICTATION_CONTEXT && v.isNotBlank() }.entries.joinToString("") { (k, v) -> "&$k=${segment(v)}" }

/** At least 200 ms of actual PCM, independent of how long the key was held. */
fun usableDictation(pcmBytes: Int, sampleRate: Int = 16000) = pcmBytes >= sampleRate * 2 / 5

object PcmAudio {
    fun level(pcm: ShortArray): Float = if (pcm.isEmpty()) 0f else
        sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size).div(32768).toFloat().coerceIn(0f, 1f)
    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}

/** Bounded PCM capture shared by dictation and durable voice memos. */
class PcmCapture(private val context: Context, private val exclusionKind: String = "dictation", private val maxBytes: Int = 24 * 1024 * 1024) {
    private val buffer = ByteArrayOutputStream()
    private var mic: MicSource? = null
    private var exclusion: String? = null
    var sampleRate: Int = 16000; private set
    @Volatile var full = false; private set
    fun start(onFull: () -> Unit = {}, level: (Float) -> Unit = {}): Boolean {
        stop(); synchronized(buffer) { buffer.reset() }; full = false
        exclusion = io.lampyr.glasses.ambient.CaptureExclusions.begin(exclusionKind)
        fun begin(source: MicSource): Boolean {
            sampleRate = source.sampleRate
            if (!source.start { samples ->
                val reached = synchronized(buffer) {
                    val count = minOf(samples.size, (maxBytes - buffer.size()).coerceAtLeast(0) / 2)
                    repeat(count) { i -> buffer.write(samples[i].toInt() and 255); buffer.write((samples[i].toInt() shr 8) and 255) }
                    if (!full && buffer.size() >= maxBytes - 1) { full = true; true } else false
                }
                level(PcmAudio.level(samples))
                if (reached) onFull()
            }) return false
            mic = source; return true
        }
        val glasses = MicRegistry.glasses?.takeIf { it.isAvailable() }
        val started = try { (glasses != null && begin(glasses)) || begin(PhoneMic(context)) }
            catch (e: Exception) { stop(); throw e }
        if (!started) stop()
        return started
    }
    fun stop(): ByteArray {
        mic?.stop(); mic = null
        exclusion?.let { io.lampyr.glasses.ambient.CaptureExclusions.end(it) }; exclusion = null
        return synchronized(buffer) { PcmAudio.wav(buffer.toByteArray(), sampleRate) }
    }
}

class GatewayDictation(private val context: Context, private val gateway: GatewayClient, private val scope: CoroutineScope,
    private val joyPhase: suspend (String) -> Unit = { phase -> io.lampyr.glasses.service.GlassesService.instance?.ui?.data?.joy?.dictationPhase(phase); Unit }) : Dictation {
    private var capture: PcmCapture? = null
    private var listener: ((DictationEvent) -> Unit)? = null
    private var target = "memo"
    private var destination: Map<String, String> = emptyMap()
    private var job: Job? = null
    private var generation = 0L
    override fun start(target: String, onEvent: (DictationEvent) -> Unit) = start(target, emptyMap(), onEvent)
    override fun start(target: String, context: Map<String, String>, onEvent: (DictationEvent) -> Unit) {
        cancel(); listener = onEvent; destination = context
        val turn = generation
        // Navigation preserves its raw-transcript target; thread aliases share the t3 target.
        this.target = when (target) { "new-thread", "other" -> "t3"; else -> target }
        capture = PcmCapture(this.context)
        if (capture?.start(onFull = { scope.launch { if (turn == generation) stop() } }, level = { level -> scope.launch { if (turn == generation) listener?.invoke(DictationEvent.Level(level)) } }) != true) {
            onEvent(DictationEvent.Error("Mic unavailable - open the phone app")); capture = null; listener = null
            if (this.target == "joy") scope.launch { if (turn == generation) joyPhase("idle") }
        }
    }
    override fun stop() {
        val source = capture ?: return
        val audio = source.stop()
        val rate = source.sampleRate
        capture = null
        val emit = listener ?: return
        val turn = generation
        job = scope.launch {
            try {
                if (target == "joy") joyPhase("thinking")
                if (!usableDictation(audio.size - 44, rate)) throw GatewayFailure("Recording too short")
                var terminal = false
                gateway.stream(dictatePath(target, destination), audio, "audio/wav") { row ->
                    val event = when (row.s("phase")) {
                        "transcribing" -> DictationEvent.Transcribing
                        "enhancing" -> DictationEvent.Enhancing
                        "done" -> DictationEvent.Done(row.s("text"), Mentions.parse(row))
                        "error" -> DictationEvent.Error(row.s("message", "Dictation failed"))
                        else -> null
                    }
                    if (event != null) withContext(scope.coroutineContext.minusKey(Job)) { if (turn == generation) {
                        if (event is DictationEvent.Done || event is DictationEvent.Error) terminal = true
                        if (target == "joy" && (event is DictationEvent.Done || event is DictationEvent.Error)) joyPhase("idle")
                        emit(event)
                    } }
                }
                if (!terminal) throw GatewayFailure("Dictation interrupted")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (turn == generation) { if (target == "joy") joyPhase("idle"); emit(DictationEvent.Error((e as? GatewayFailure)?.display ?: "Dictation failed")) } }
            finally { if (turn == generation) listener = null }
        }
    }
    override fun cancel() {
        val wasJoy = target == "joy" && (capture != null || listener != null)
        generation++; job?.cancel(); job = null; listener = null; capture?.stop(); capture = null
        val turn = generation
        if (wasJoy) scope.launch { if (turn == generation && capture == null && listener == null) joyPhase("idle") }
    }
}
