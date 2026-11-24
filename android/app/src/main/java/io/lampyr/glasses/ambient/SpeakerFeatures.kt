package io.lampyr.glasses.ambient

import ai.onnxruntime.*
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

/** WeSpeaker frontend. Input is 16 kHz mono PCM in int16 units, not [-1,1]. */
object SpeakerFeatures {
    private const val N = 512
    private fun mel(hz: Double) = 1127.0 * ln(1 + hz / 700)
    private val filters = Array(80) { bin ->
        val low = mel(20.0); val step = (mel(8000.0) - low) / 81
        DoubleArray(N / 2) { i ->
            val m = mel(i * 16000.0 / N)
            max(0.0, min((m - low - bin * step) / step, (low + (bin + 2) * step - m) / step))
        }
    }
    fun fbank(samples: FloatArray): Array<FloatArray> {
        require(samples.all { it.isFinite() })
        val count = max(0, 1 + (samples.size - 400) / 160).let { if (samples.size < 400) 0 else it }
        val features = Array(count) { frame ->
            val x = DoubleArray(N); val im = DoubleArray(N)
            val mean = (0 until 400).sumOf { samples[frame * 160 + it].toDouble() } / 400
            for (i in 0 until 400) x[i] = samples[frame * 160 + i] - mean
            for (i in 399 downTo 1) x[i] -= .97 * x[i - 1]
            x[0] *= .03
            for (i in 0 until 400) x[i] *= .54 - .46 * cos(2 * PI * i / 399)
            fft(x, im)
            val power = DoubleArray(N / 2) { x[it] * x[it] + im[it] * im[it] }
            FloatArray(80) { bin -> ln(max(1.1920928955078125e-7, power.indices.sumOf { power[it] * filters[bin][it] })).toFloat() }
        }
        // Match numpy float32 mean and subtraction in the reference pipeline.
        if (count > 0) for (bin in 0 until 80) {
            var mean = 0f
            for (frame in features) mean += frame[bin]
            mean /= count
            for (frame in features) frame[bin] -= mean
        }
        return features
    }
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= N) {
            for (start in 0 until N step len) for (k in 0 until len / 2) {
                val a = -2 * PI * k / len; val c = cos(a); val s = sin(a)
                val l = start + k; val r = l + len / 2
                val tr = re[r] * c - im[r] * s; val ti = re[r] * s + im[r] * c
                re[r] = re[l] - tr; im[r] = im[l] - ti; re[l] += tr; im[l] += ti
            }
            len *= 2
        }
    }
}

/** Windowed-sinc anti-alias filter. Stateless conversion is applied to complete windows so packet
 * boundaries cannot reset filter phase. The runtime's decoded Opus clock is always 48 kHz. */
object SpeakerPcm {
    // 48k -> 16k has one output phase: every position is an exact multiple of three.
    private const val cutoff48 = (16000.0 / 48000) * .94
    private val radius48 = ceil(24 / cutoff48).toInt()
    private val kernel48 = DoubleArray(2 * radius48 + 1) { i ->
        val d = (i - radius48).toDouble(); val z = PI * cutoff48 * d
        cutoff48 * (if (abs(z) < 1e-12) 1.0 else sin(z) / z) * (.5 + .5 * cos(PI * d / (radius48 + 1)))
    }
    private fun resample48(input: FloatArray) = FloatArray(input.size / 3) { i ->
        val center = i * 3
        var value = 0.0; var total = 0.0
        for (j in max(0, center - radius48)..min(input.lastIndex, center + radius48)) {
            val weight = kernel48[j - center + radius48]
            value += input[j] * weight; total += weight
        }
        (if (total == 0.0) 0.0 else value / total).toFloat()
    }
    fun mono(pcm: ShortArray, channels: Int): FloatArray {
        require(channels in 1..2 && pcm.size % channels == 0)
        return FloatArray(pcm.size / channels) { i -> (0 until channels).sumOf { pcm[i * channels + it].toDouble() }.toFloat() / channels }
    }
    fun resample(input: FloatArray, rate: Int, target: Int = 16000): FloatArray {
        require(rate > 0 && target > 0)
        if (rate == target) return input.copyOf()
        if (rate == 48000 && target == 16000) return resample48(input)
        val cutoff = min(1.0, target.toDouble() / rate) * .94
        val radius = ceil(24 / cutoff).toInt()
        return FloatArray((input.size.toLong() * target / rate).toInt()) { i ->
            val pos = i.toDouble() * rate / target
            var value = 0.0; var total = 0.0
            for (j in max(0, floor(pos).toInt() - radius)..min(input.lastIndex, ceil(pos).toInt() + radius)) {
                val d = j - pos; val z = PI * cutoff * d
                val weight = cutoff * (if (abs(z) < 1e-12) 1.0 else sin(z) / z) * (.5 + .5 * cos(PI * d / (radius + 1)))
                value += input[j] * weight; total += weight
            }
            (if (total == 0.0) 0.0 else value / total).toFloat()
        }
    }
}

class SpeakerEmbedding(model: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        env.createSession(model.absolutePath, options)
    }
    fun embed(pcm16k: FloatArray): FloatArray {
        val features = SpeakerFeatures.fbank(pcm16k)
        require(features.isNotEmpty())
        val flat = FloatArray(features.size * 80) { features[it / 80][it % 80] }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), 80)).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val vector = ((result[0].value as Array<FloatArray>)[0])
                SpeakerDecision.unit(vector) ?: error("Invalid model embedding")
            }
        }
    }
    override fun close() = session.close()
}
