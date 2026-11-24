package io.lampyr.glasses.audio

import android.media.MediaCodec
import android.media.MediaFormat
import io.lampyr.glasses.ambient.OggOpusWriter
import io.lampyr.glasses.ambient.OpusPacket
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes a memo WAV (16-bit mono PCM, as written by [PcmAudio.wav]) to Ogg/Opus with the platform Opus
 * encoder (Android 10+), one packet per page through the same [OggOpusWriter] ambient conversations use.
 * Returns false (and leaves no output) when the encoder is unavailable or the input is unexpected; the
 * memo is then sent as WAV. */
object MemoOpusEncoder {
    private val RATES = setOf(8000, 12000, 16000, 24000, 48000)
    fun encode(wav: File, ogg: File): Boolean = try { encodeOrThrow(wav, ogg); true }
        catch (_: Throwable) { ogg.delete(); false }

    private fun encodeOrThrow(wav: File, ogg: File) {
        val bytes = wav.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        val h = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = h.getShort(22).toInt(); val rate = h.getInt(24); val bits = h.getShort(34).toInt()
        require(channels == 1 && bits == 16 && rate in RATES && String(bytes, 36, 4) == "data")
        val end = 44 + minOf(h.getInt(40).toLong().coerceAtLeast(0), (bytes.size - 44).toLong()).toInt() / 2 * 2
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var position = 44; var inputDone = false; var outputDone = false
            val deadline = System.nanoTime() + 120_000_000_000L
            OggOpusWriter(ogg).use { writer ->
                while (!outputDone) {
                    check(System.nanoTime() < deadline) { "Opus encoder stalled" }
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val n = minOf(buffer.remaining() / 2 * 2, end - position)
                            buffer.put(bytes, position, n)
                            val pts = (position - 44) / 2 * 1_000_000L / rate
                            position += n
                            inputDone = position >= end
                            codec.queueInputBuffer(index, 0, n, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val packet = ByteArray(info.size)
                            codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size) }.get(packet)
                            OpusPacket.samples(packet) // rejects anything that is not a raw Opus packet
                            writer.append(packet)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                check(writer.samples > 0) { "Opus encoder produced no audio" }
            }
        } finally { runCatching { codec.stop() }; codec.release() }
    }
}
