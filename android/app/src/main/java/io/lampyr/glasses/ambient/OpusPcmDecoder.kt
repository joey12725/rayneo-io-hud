package io.lampyr.glasses.ambient

import io.lampyr.glasses.debug.Trace

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface PacketPcmDecoder : Closeable {
    fun packet(bytes: ByteArray, timestampUs: Long)
}

/** Worker-confined decoder. Stable two-channel CSD accepts both mono and stereo Opus TOCs. */
class OpusPcmDecoder(private val output: (ShortArray, Int, Long) -> Unit) : PacketPcmDecoder {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var lastToc: Int? = null
    private var channels = 2
    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(OpusPacket.head()))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        try { codec.configure(format, null, null, 0); codec.start() } catch (e: Exception) { Trace.error("Opus", "Ambient decoder configure failed", e); codec.release(); throw e }
    }
    override fun packet(bytes: ByteArray, timestampUs: Long) {
        val toc = bytes.firstOrNull()?.toInt()?.and(255)
        if (lastToc == null || (toc != null && (toc and 4) != (lastToc!! and 4))) { Trace.log("Opus", "Ambient TOC=$toc inputChannels=${if (toc != null && toc and 4 != 0) 2 else 1} decoderChannels=$channels"); lastToc = toc }
        val deadline = System.nanoTime() + 5_000_000_000L
        var index: Int
        while (true) {
            drain(false)
            index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) break
            check(System.nanoTime() < deadline) { "Opus input timed out" }
        }
        codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
        codec.queueInputBuffer(index, 0, bytes.size, timestampUs, 0)
        drain(false)
    }
    fun finish(timestampUs: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var index: Int
        do { drain(false); index = codec.dequeueInputBuffer(10_000); check(System.nanoTime() < deadline) { "Opus EOS input timed out" } } while (index < 0)
        codec.queueInputBuffer(index, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
    }
    private fun drain(eos: Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Trace.log("Opus", "Ambient output format=$format")
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(channels in 1..2 && format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000)
                    check(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0) {
                            val b = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val shorts = b.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
                            output(pcm, channels, info.presentationTimeUs)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
            }
            check(System.nanoTime() < deadline) { "Opus output timed out" }
        }
    }
    override fun close() { try { codec.stop() } finally { codec.release() } }
}
