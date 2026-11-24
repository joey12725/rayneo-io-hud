// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// MediaCodec configuration/draining adapted from OpenRayneo PromptAudio.kt. Modified for RayNeo iO HUD.
package io.lampyr.glasses.glasses

import io.lampyr.glasses.debug.Trace

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import io.lampyr.glasses.audio.MicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class GlassesMic(
    private val connected: () -> Boolean,
    private val capture: (Boolean) -> Unit,
) : MicSource {
    override val name = "RayNeo glasses (caption mono; wearer mapping unverified)"
    private val thread = HandlerThread("rayneo-opus").apply { start() }
    private val worker = Handler(thread.looper)
    private val queued = AtomicInteger()
    private val traffic = io.lampyr.glasses.debug.StreamCounter()
    val packets = java.util.concurrent.atomic.AtomicLong()
    val drops = java.util.concurrent.atomic.AtomicLong()
    private val level = MutableStateFlow(0f)
    val levels = level.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val lastError = error.asStateFlow()
    @Volatile private var active = false
    @Volatile private var generation = 0
    @Volatile private var consumer: ((ShortArray) -> Unit)? = null
    private var codec: MediaCodec? = null
    private var channels = 0
    private var outputChannels = 0
    private var timestamp = 0L
    private val resampler = WearerDownsampler()
    private val info = MediaCodec.BufferInfo()
    override fun isAvailable() = connected()
    fun isCapturing() = active

    @Synchronized override fun start(onPcm: (ShortArray) -> Unit): Boolean {
        if (!isAvailable() || active) { Trace.log("Mic", "Start rejected available=${isAvailable()} active=$active"); return false }
        Trace.log("Mic", "Capture start connected=${connected()}")
        generation++
        active = true
        consumer = onPcm
        error.value = null
        capture(true)
        return true
    }

    /** One packet per call. The TOC handles occasional mono packets even in a stereo session. */
    @Synchronized fun accept(packet: ByteArray) {
        if (!active || packet.isEmpty()) return
        val token = generation
        packets.incrementAndGet()
        if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); drops.incrementAndGet(); traffic.add("mic-queue", packet.size, true)?.let { Trace.log("Mic", it) }; return }
        traffic.add("mic-queue", packet.size)?.let { Trace.log("Mic", it) }
        worker.post {
            try {
                if (token != generation || !active) return@post
                val packetChannels = if (packet[0].toInt() and 4 != 0) 2 else 1
                if (codec == null || channels != packetChannels) { Trace.log("Opus", "Format TOC=${packet[0].toInt() and 255} channels=$packetChannels rate=48000"); configure(packetChannels) }
                val decoder = checkNotNull(codec)
                drain(decoder, token)
                val index = decoder.dequeueInputBuffer(5_000)
                if (index < 0) { drops.incrementAndGet(); return@post } // Bounded real-time stream: drop rather than queue stale audio.
                decoder.getInputBuffer(index)!!.apply { clear(); put(packet) }
                val duration = OpusTiming.durationUs(packet)
                decoder.queueInputBuffer(index, 0, packet.size, timestamp, 0)
                timestamp += duration
                drain(decoder, token)
            } catch (e: Exception) {
                Trace.error("Opus", "Decoder failure", e)
                release()
                if (token == generation) error.value = "Opus decode: ${e.message}"
            } finally { queued.decrementAndGet() }
        }
    }

    private fun configure(count: Int) {
        release()
        channels = count; outputChannels = count; timestamp = 0
        resampler.reset()
        val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(count.toByte()).putShort(0)
            .putInt(48_000).putShort(0).put(0).array()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, count).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(header))
            setByteBuffer("csd-1", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).array()))
            setByteBuffer("csd-2", ByteBuffer.wrap(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).array()))
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { it.configure(format, null, null, 0); it.start() }
        // Platform MediaCodec implementations and this app's CSD path still need a real-device test.
    }
    private fun drain(decoder: MediaCodec, token: Int) {
        while (token == generation && active) {
            val index = decoder.dequeueOutputBuffer(info, 1_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48_000 && outputChannels in 1..2)
                    check(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                }
                index >= 0 -> try {
                    val buffer = decoder.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val pcm = ShortArray(shorts.remaining()).also { shorts.get(it) }
                    val mono = resampler.process(pcm, outputChannels)
                    if (mono.isNotEmpty()) deliver(token, mono)
                } finally { decoder.releaseOutputBuffer(index, false) }
            }
        }
    }
    @Synchronized private fun deliver(token: Int, mono: ShortArray) {
        if (token != generation || !active) return
        level.value = (sqrt(mono.sumOf { it.toDouble() * it } / mono.size) / 32768).toFloat()
        error.value = null
        consumer?.invoke(mono)
    }
    @Synchronized override fun stop() {
        val wasActive = active
        if (wasActive) Trace.log("Mic", "Stop packets=${packets.get()} drops=${drops.get()}")
        generation++; active = false; consumer = null; level.value = 0f
        worker.post { release() }
        if (wasActive) capture(false)
    }
    fun close() { stop(); thread.quitSafely() }
    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null; channels = 0
    }
}
