package io.lampyr.glasses.ambient

import io.lampyr.glasses.debug.Trace

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Recognition-only codec work has its own bounded queue. Stalls never delay Opus recording.
 * LifeLog hands over about 20 s of audio at once; the queue holds a whole burst, and the worker waits
 * (up to [PACE_LIMIT_MS] per packet) while an inference runs so each due window is scored, not skipped. */
class RecognitionAudioWorker(scope: CoroutineScope, private val recognizer: () -> RecentSpeakerRecognizer,
    private val allowed: () -> Boolean, private val diagnostic: (String) -> Unit = {},
    private val decoderFactory: ((ShortArray, Int, Long) -> Unit) -> PacketPcmDecoder = { OpusPcmDecoder(it) }) : Closeable {
    private data class Queued(val packet: AmbientPacket, val version: Long)
    private val traffic = io.lampyr.glasses.debug.StreamCounter()
    private val generation = AtomicLong()
    private val queue = Channel<Queued>(QUEUE_PACKETS)
    private val job = scope.launch(Dispatchers.IO) {
        var decoder: PacketPcmDecoder? = null
        val pending = PcmPacketAssembler()
        var version = -1L
        var samples = 0L
        try {
            for (item in queue) {
                if (item.version != generation.get() || !allowed() || !recognizer().ready) continue
                try {
                    if (version != item.version) {
                        decoder?.close(); decoder = null; pending.reset(); samples = 0
                        version = item.version
                    }
                    if (decoder == null) decoder = decoderFactory { pcm, channels, pts ->
                        if (version == generation.get() && allowed() && recognizer().ready) {
                            pending.decoded(pcm, channels, pts).forEach {
                                recognizer().recent(it.pcm, it.channels, it.packet.utc)
                            }
                        }
                    }
                    pending.enqueue(item.packet, samples)
                    if (pending.size > 64) { reset(); continue }
                    decoder.packet(item.packet.opus, samples * 1_000_000 / 48000)
                    samples += OpusPacket.samples(item.packet.opus)
                    var waited = 0L
                    while (recognizer().busy && version == generation.get() && waited < PACE_LIMIT_MS) { delay(PACE_STEP_MS); waited += PACE_STEP_MS }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Trace.error("Opus", "Recognition decoder reset", e)
                    runCatching { decoder?.close() }; decoder = null; pending.reset(); reset()
                    diagnostic("Recognition decoder reset")
                }
            }
        } finally { runCatching { decoder?.close() } }
    }
    fun offer(packet: AmbientPacket) {
        if (!packet.speech || !allowed() || !recognizer().ready) return
        val dropped = queue.trySend(Queued(packet,generation.get())).isFailure
        traffic.add("recognition-queue", packet.opus.size, dropped)?.let { Trace.log("Mic", it) }
        if (dropped) reset()
    }
    fun reset() { generation.incrementAndGet(); recognizer().reset() }
    override fun close() { reset(); queue.close(); job.cancel() }
    companion object { const val QUEUE_PACKETS = 1024; const val PACE_STEP_MS = 20L; const val PACE_LIMIT_MS = 3000L }
}
