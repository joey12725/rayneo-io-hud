package io.lampyr.glasses.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.lampyr.glasses.ambient.CaptureExclusions
import io.lampyr.glasses.service.GlassesService

class AmbientJoyCallExclusion : JoyCallExclusion {
    private var id: String? = null
    override fun begin() { id = CaptureExclusions.begin("joy-call") }
    override fun end() { id?.let(CaptureExclusions::end); id = null }
}

/** Use Android's current media output (including A2DP/LE/wired); never request SCO or HFP.
 * VOICE_COMMUNICATION capture enables the phone's platform echo cancellation/noise suppression.
 */
class AndroidJoyCallAudio(
    private val context: Context,
    private val pauseMedia: () -> (() -> Unit),
    private val canResume: () -> Boolean,
    private val releaseOnClose: Boolean = true,
) : JoyCallAudio {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var mic: MicSource? = null
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var resume: (() -> Unit)? = null
    private var interrupted = false
    private var lastHead = 0L
    private var wraps = 0L
    private var consumer: ((ShortArray) -> Unit)? = null
    private var capturing = false
    private var speaker = false
    override val sampleRate get() = mic?.sampleRate ?: 16_000
    override val playedSamples: Long get() {
        val head = (track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL
        if (head < lastHead) wraps += 1L shl 32
        lastHead = head
        return wraps + head
    }
    /** The store holds focus/wake across preflight, duplex audio and any turn-based fallback. */
    fun hold(interrupted: () -> Unit): Boolean {
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) { this.interrupted = true; interrupted() }
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        resume = pauseMedia()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { release(); return false }
        wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lampyr:joy-call").apply {
            setReferenceCounted(false); acquire(JoyCallSession.MAX_CALL_MS + 10 * 60_000L) // Long calls; released on hang-up.
        }
        return true
    }
    override fun open(interrupted: () -> Unit): Boolean {
        if (!hold(interrupted)) return false
        onInterrupted = interrupted
        mic = MicRegistry.glasses?.takeIf { it.isAvailable() } ?: PhoneMic(context, communication = true)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(960 * 4, AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .build()
        track?.addOnRoutingChangedListener({ routing ->
            speaker = routing.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            // Decoded glasses PCM has no Android audio session for AEC. On the phone's speaker,
            // switch to VOICE_COMMUNICATION capture so platform AEC has a real capture session.
            if (speaker && capturing && mic !is PhoneMic) {
                mic?.stop()
                mic = PhoneMic(context, communication = true)
                if (mic?.start(consumer ?: {}) != true) { this.interrupted = true; interrupted() }
            }
        }, Handler(Looper.getMainLooper()))
        return track?.state == AudioTrack.STATE_INITIALIZED
    }
    override fun capture(accept: (ShortArray) -> Unit): Boolean {
        val counted: (ShortArray) -> Unit = { pcm -> lastPcmAt = android.os.SystemClock.elapsedRealtime(); accept(pcm) }
        consumer = counted
        if (speaker) mic = PhoneMic(context, communication = true)
        val selected = mic ?: return false
        lastPcmAt = android.os.SystemClock.elapsedRealtime()
        if (selected.start(counted)) { capturing = true; watchMic(); return true }
        // A connected glasses transport may already be recording; still offer the phone route.
        mic = PhoneMic(context, communication = true)
        capturing = mic!!.start(counted)
        return capturing
    }
    @Volatile private var lastPcmAt = 0L
    private val main = Handler(Looper.getMainLooper())
    /**
     * The glasses microphone can go quiet mid-call (the glasses stop their recorder, a lock or page change on the
     * firmware side, a Bluetooth blip). A the assistant call must not die of that: after [MIC_STALL_MS] without a single
     * PCM chunk the call moves to the phone's microphone for the rest of the call.
     */
    private val micWatch = object : Runnable {
        override fun run() {
            if (!capturing) return
            val current = mic
            if (current != null && current !is PhoneMic && android.os.SystemClock.elapsedRealtime() - lastPcmAt > MIC_STALL_MS) {
                io.lampyr.glasses.debug.Trace.log("JoyCall", "Glasses mic silent for ${MIC_STALL_MS} ms; switching the call to the phone mic")
                runCatching { current.stop() }
                val phone = PhoneMic(context, communication = true)
                mic = phone
                if (!phone.start(consumer ?: {})) { capturing = false; interrupted = true; onInterrupted?.invoke(); return }
            }
            if (mic !is PhoneMic) main.postDelayed(this, 1_000)
        }
    }
    private var onInterrupted: (() -> Unit)? = null
    private fun watchMic() { main.removeCallbacks(micWatch); if (mic !is PhoneMic) main.postDelayed(micWatch, 1_000) }
    companion object { const val MIC_STALL_MS = 3_000L }
    override fun write(pcm: ByteArray, offset: Int) = track?.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING) ?: -1
    override fun play() { track?.play() }
    override fun flush() { track?.pause(); track?.flush(); lastHead = 0; wraps = 0 }
    override fun close() {
        stopStream()
        if (releaseOnClose) release()
    }
    private fun stopStream() {
        main.removeCallbacks(micWatch)
        if (capturing) runCatching { mic?.stop() }
        capturing = false; consumer = null
        mic = null
        track?.let { runCatching { it.pause(); it.flush() }; runCatching { it.release() } }; track = null
    }
    fun release() {
        stopStream()
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }; focus = null
        wake?.let { runCatching { if (it.isHeld) it.release() } }; wake = null
        val restore = resume; resume = null
        if (!interrupted && canResume()) runCatching { restore?.invoke() }
    }
}
