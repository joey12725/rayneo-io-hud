package io.lampyr.glasses.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import kotlin.concurrent.thread

class PhoneMic(private val context: Context, private val communication: Boolean = false) : MicSource {
    override val name = "Phone microphone"
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()
    override fun isAvailable() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun start(onPcm: (ShortArray) -> Unit): Boolean {
        if (!isAvailable()) return false
        if (io.lampyr.glasses.service.GlassesService.instance?.microphoneReady != true) return false
        stop()
        return runCatching {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(if (communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            record = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            if (communication) {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                    android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)?.let { effects += it; it.enabled = true }
                if (android.media.audiofx.NoiseSuppressor.isAvailable())
                    android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)?.let { effects += it; it.enabled = true }
            }
            recorder.startRecording(); running = true
            worker = thread(name = "phone-mic", isDaemon = true) {
                val buffer = ShortArray(800)
                while (running) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0 && running) onPcm(buffer.copyOf(count))
                    else if (count < 0) break
                }
            }
            true
        }.getOrElse { android.util.Log.e("PhoneMic", "Phone microphone capture failed", it); stop(); false }
    }
    override fun stop() {
        running = false
        runCatching { record?.stop() }
        if (worker != Thread.currentThread()) worker?.join(1000)
        worker = null; effects.forEach { it.release() }; effects.clear(); record?.release(); record = null
    }
}
