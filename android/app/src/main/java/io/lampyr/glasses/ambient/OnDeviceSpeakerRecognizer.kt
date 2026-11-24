package io.lampyr.glasses.ambient

import kotlinx.coroutines.*
import java.io.Closeable

/** Audio-thread work is bounded to a mono copy into a 6-second ring. A busy inference drops ticks.
 * No unknown embeddings or PCM are persisted. Generation invalidates in-flight work on mute/drop. */
class OnDeviceSpeakerRecognizer(private val scope: CoroutineScope, private val model: SpeakerModel,
    private val people: PeopleEngine, private val listening: () -> Boolean, private val threshold: () -> Float,
    private val embedOverride: ((FloatArray) -> FloatArray)? = null,
    private val modelReady: () -> Boolean = { model.ready }) : RecentSpeakerRecognizer, Closeable {
    override val ready get() = !closed && listening() && modelReady()
    private val ring = FloatArray(SpeakerPolicy.WINDOW_SAMPLES)
    private var position = 0; private var size = 0; private var lastTick = Long.MIN_VALUE
    private var lastAudio = 0L; private var generation = 0L
    @Volatile private var inference = false; @Volatile private var closed = false
    override val busy get() = inference
    private var embedding: SpeakerEmbedding? = null
    private val decision = SpeakerDecision()
    @Synchronized override fun reset() { size = 0; position = 0; lastTick = Long.MIN_VALUE; generation++; decision.reset() }
    @Synchronized override fun recent(pcm: ShortArray, channels: Int, utc: Long): SpeakerMatch {
        if (closed || !listening() || !modelReady()) { reset(); return SpeakerMatch() }
        if (lastAudio > 0 && utc - lastAudio > 1000) reset()
        lastAudio = utc
        val mono = SpeakerPcm.mono(pcm, channels)
        for (s in mono) { ring[position] = s; position = (position + 1) % ring.size; size = minOf(size + 1, ring.size) }
        if (size < SpeakerPolicy.MIN_SAMPLES || inference || (lastTick != Long.MIN_VALUE && utc - lastTick < SpeakerPolicy.TICK_MS)) return SpeakerMatch()
        val window = FloatArray(size) { ring[(position - size + ring.size + it) % ring.size] }
        lastTick = utc; inference = true; val version = generation
        scope.launch(Dispatchers.Default) {
            try {
                val signal = SpeakerPcm.resample(window, 48000)
                val vector = embedOverride?.invoke(signal) ?: (embedding ?: SpeakerEmbedding(model.file).also { embedding = it }).embed(signal)
                val match = synchronized(this@OnDeviceSpeakerRecognizer) {
                    if (closed || generation != version || !listening()) null else decision.decide(vector, people.voices, threshold())
                }
                if (match != null && listening()) people.heard(match, utc) {
                    synchronized(this@OnDeviceSpeakerRecognizer) { !closed && generation == version && listening() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(this@OnDeviceSpeakerRecognizer) { decision.reset() } }
            finally { synchronized(this@OnDeviceSpeakerRecognizer) { inference = false; if (closed) { embedding?.close(); embedding = null } } }
        }
        return SpeakerMatch()
    }
    @Synchronized override fun close() { closed = true; reset(); if (!inference) { embedding?.close(); embedding = null } }
}
