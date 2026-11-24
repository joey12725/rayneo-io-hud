package io.lampyr.glasses.ambient

import io.lampyr.glasses.debug.Trace

import android.content.Context
import android.os.*
import android.util.Log
import io.lampyr.glasses.glasses.GlassesTransports
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable

class AmbientPreferences(context: Context) {
    val prefs = context.getSharedPreferences("ambient", Context.MODE_PRIVATE)
    /** On by default (owner decision, 2026-11-13). Disable from the phone; pause from the remote (hold âˆ’). */
    var enabled: Boolean get() = prefs.getBoolean("enabled", true); set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) { prefs.edit().putBoolean("paused", v).apply() }
    var mode: String get() = prefs.getString("mode", "auto")!!; set(v) { require(v in listOf("auto", "lifelog", "continuous")); prefs.edit().putString("mode", v).apply() }
    var retention: Int get() = prefs.getInt("retention", 30); set(v) { require(v in 1..3650); prefs.edit().putInt("retention", v).apply() }
    var conversationGapMinutes: Int
        get() = prefs.getInt("conversationGapMinutes", if (prefs.contains("gap") && prefs.getInt("gap", 3) != 3) prefs.getInt("gap", 10).takeIf { it in 1..60 } ?: 10 else 10)
        set(v) { require(v in 1..60); prefs.edit().putInt("conversationGapMinutes", v).remove("gap").apply() }
    var pendingStorageBytes: Long get() = prefs.getLong("pendingStorageBytes", 2_000_000_000L).coerceAtLeast(0)
        set(v) { require(v >= 0); prefs.edit().putLong("pendingStorageBytes", v).apply() }
    var pendingStorageCount: Int get() = prefs.getInt("pendingStorageCount", 500).coerceAtLeast(0)
        set(v) { require(v >= 0); prefs.edit().putInt("pendingStorageCount", v).apply() }
    var gapMinutes: Int get() = conversationGapMinutes; set(v) { conversationGapMinutes = v }
    var speakerThreshold: Float get() = prefs.getFloat("speakerThreshold", SpeakerPolicy.DEFAULT_THRESHOLD)
        set(v) { require(v.isFinite() && v in SpeakerPolicy.MIN_THRESHOLD..SpeakerPolicy.MAX_THRESHOLD); prefs.edit().putFloat("speakerThreshold", v).apply() }
    var modelWifiOnly: Boolean get() = prefs.getBoolean("modelWifiOnly", true); set(v) { prefs.edit().putBoolean("modelWifiOnly", v).apply() }
    var uploadWifiOnly: Boolean get() = prefs.getBoolean("uploadWifiOnly", false); set(v) { prefs.edit().putBoolean("uploadWifiOnly", v).apply() }
    var probeCache: Boolean get() = prefs.getBoolean("probeCache", false); set(v) { prefs.edit().putBoolean("probeCache", v).apply() }
    /** AMB-14: conversation recording and voice matching share this one state. Either phone control (disable or
     * pause) turns both off; turning it back on from the remote also re-enables a phone-disabled capture. */
    var listening: Boolean
        get() = enabled && !paused
        set(v) { prefs.edit().apply { if (v) putBoolean("enabled", true) }.putBoolean("paused", !v).apply() }
}

/** The glasses UI's view of [AmbientPreferences.listening]; persists across restarts. */
class AmbientListening(private val preferences: AmbientPreferences) : io.lampyr.glasses.ui.PassiveListening {
    override val on get() = preferences.listening
    override fun set(on: Boolean) { preferences.listening = on }
    override fun listen(listener: () -> Unit): () -> Unit {
        // SharedPreferences holds listeners weakly; the returned closure keeps this one alive.
        val watcher = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "enabled" || key == "paused") listener()
        }
        preferences.prefs.registerOnSharedPreferenceChangeListener(watcher)
        return { preferences.prefs.unregisterOnSharedPreferenceChangeListener(watcher) }
    }
}

data class AmbientStatus(val state: String = "Off", val recordedMs: Long = 0, val conversations: Int = 0, val bytes: Long = 0) {
    fun line() = "$state · ${recordedMs / 60000}m ${recordedMs / 1000 % 60}s · $conversations conversations · ${bytes / 1024} KiB"
}

/** Synchronous boundary publication before a dictation/memo mic is opened. Audio and ranges share
 * one ordered worker; IDs allow overlapping clients without prematurely reopening ambient capture.
 */
object CaptureExclusions {
    private val active = linkedMapOf<String, Pair<String, Long>>()
    private var runtime: AmbientRuntime? = null
    @Synchronized fun begin(kind: String): String = UUID.randomUUID().toString().also {
        val utc = System.currentTimeMillis(); active[it] = kind to utc; runtime?.begin(it, kind, utc)
    }
    @Synchronized fun end(id: String) { active.remove(id); runtime?.end(id, System.currentTimeMillis()) }
    /** Kinds currently excluding ambient capture (dictation, memo, joy-call, ...), oldest first. */
    @Synchronized fun activeKinds(): List<String> = active.values.map { it.first }
    /** Keep the returned handle until mic stop/failure/onStop; close is idempotent. */
    fun microphoneTest(): Closeable {
        val id = begin("mic-test")
        return Closeable { end(id) }
    }
    @Synchronized fun attach(value: AmbientRuntime?) {
        runtime = value
        if (value != null) active.forEach { (id, range) -> value.begin(id, range.first, range.second) }
    }
}

/** Service-owned serial storage/codec worker. No audio is uploaded here. */
class AmbientRuntime(private val context: Context, @Volatile private var recognizer: RecentSpeakerRecognizer = NoSpeakerRecognition,
                     canLocate: () -> Boolean = { false }) {
    val preferences = AmbientPreferences(context)
    val root = File(context.filesDir, "ambient")
    private val thread = HandlerThread("ambient-capture").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val audioTraffic = io.lampyr.glasses.debug.StreamCounter()
    private val queuedSamples = AtomicLong()
    private val overflow = AtomicBoolean()
    private val state = MutableStateFlow(AmbientStatus())
    val status = state.asStateFlow()
    val conversations = ConversationStore(File(root, "conversations"), diagnostic = { Log.w("AmbientCapture", it) })
    private var recorder = ConversationRecorder(conversations, monotonic = { SystemClock.elapsedRealtime() })
    val model = SpeakerModel(File(context.filesDir, "speaker-model"), network = { allowedNetwork(context, it) })
    private var people: PeopleEngine? = null
    private var peopleJob: Job? = null
    private var decoder: OpusPcmDecoder? = null
    private val pending = PcmPacketAssembler()
    private val vad = EnergyVad()
    private var timestamp = 0L
    @Volatile private var active = false
    @Volatile private var closed = false
    private var error: String? = null
    private val recovery = CaptureRecovery()
    private var phase = "Waiting for glasses"
    private var lastRetainedAt = 0L
    private var migrated = false
    private var retentionDay: LocalDate? = null
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recognitionAudio = RecognitionAudioWorker(exportScope, { recognizer }, { active && preferences.listening }, { Log.w("AmbientCapture", it) })
    private val uploads = ConversationUploadWorker(exportScope) { gateway ->
        ConversationUploader(conversations, gateway, { allowedNetwork(context, it) }, { preferences.uploadWifiOnly },
            maxBytes = { preferences.pendingStorageBytes }, maxCount = { preferences.pendingStorageCount })
    }
    private val locations = AmbientLocations(context, canLocate, emit = { fix -> people?.lastFix = fix; submit { recorder.fix(fix) } })
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    init {
        wireRecorder()
        CaptureExclusions.attach(this)
        preferences.prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
        worker.post(object : Runnable {
            override fun run() {
                if (closed) return
                guarded {
                    val now = System.currentTimeMillis()
                    if (!active && preferences.enabled && !preferences.paused && recovery.ready(SystemClock.elapsedRealtime())) {
                        conversations.checkWritable(); conversations.recover()
                        active = true; recovery.rearmed(SystemClock.elapsedRealtime())
                        phase = "Capture recovered; waiting for speech"
                        error = "Recovered after recorder failure"
                        CaptureExclusions.attach(this@AmbientRuntime)
                        main.post { locations.start(); GlassesTransports.real?.ambientChanged() }
                    }
                    if (!migrated) { migrated = true; conversations.migrateOnce(now, preferences.gapMinutes * 60_000L) }
                    conversations.recover()
                    conversations.enforceLimits(preferences.pendingStorageBytes, preferences.pendingStorageCount)
                    if (active) recorder.tick(System.currentTimeMillis())
                    if (active) recovery.healthy(SystemClock.elapsedRealtime())
                    if (phase.startsWith("Recording") && System.currentTimeMillis() - lastRetainedAt > 2000) phase = "Waiting for speech"
                    val today = LocalDate.now()
                    if (retentionDay != today) {
                        conversations.retain(now, preferences.retention)
                        retainLegacyRecordings(root, LocalDate.now(), preferences.retention)
                        File(context.filesDir, "ambient-share").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                        retentionDay = today
                    }
                    publish()
                }
                worker.postDelayed(this, 1000)
            }
        })
    }
    private fun wireRecorder() {
        recorder.onConversation = { id -> main.post { if (active) locations.conversationStart(id) } }
        recorder.onConversationEnd = { id -> main.post { locations.conversationEnd(id) } }
    }
    fun attachPeople(engine: PeopleEngine?, gateway: io.lampyr.glasses.data.GatewayClient?) {
        recognitionAudio.reset()
        peopleJob?.cancel(); (recognizer as? Closeable)?.close(); recognizer = NoSpeakerRecognition; people = engine
        val configured = gateway ?: io.lampyr.glasses.settings.AppSettings(context).let {
            if (it.url.isNotBlank() && it.token.isNotBlank()) io.lampyr.glasses.data.GatewayClient(it.url, it.token) else null
        }
        uploads.configure(configured)
        if (engine == null) return
        recognizer = OnDeviceSpeakerRecognizer(exportScope, model, engine, { active && preferences.listening }, { preferences.speakerThreshold })
        engine.modelNotice = { modelNotice(model.status.value, preferences.modelWifiOnly).takeIf { preferences.listening && !model.ready } }
        peopleJob = exportScope.launch {
            model.check()
            var lastSyncTry = 0L
            var lastModelTry = Long.MIN_VALUE / 2
            var modelTries = 0
            var starting = true
            while (isActive) {
                val now = System.currentTimeMillis()
                // The voice model downloads by itself (Wi-Fi only unless changed on the phone); the button stays.
                // Each failed round doubles the wait (10 min up to 6 h) so a broken download never pulls ~25 MB every 10 minutes.
                if (!model.ready && now - lastModelTry >= modelRetryMs(modelTries) && allowedNetwork(context, preferences.modelWifiOnly)) {
                    lastModelTry = now; modelTries++; launch { model.download(preferences.modelWifiOnly) }
                }
                if (now - lastSyncTry >= 60_000) {
                    lastSyncTry = now
                    try { engine.sync(force = starting); starting = false } catch (e: CancellationException) { throw e } catch (_: Exception) { /* cached voices remain */ }
                }
                engine.flush(); delay(5000)
            }
        }
    }
    fun downloadModel() { exportScope.launch { model.download(preferences.modelWifiOnly) } }
    fun refresh() {
        if (closed) return
        val wanted = preferences.enabled && !preferences.paused
        active = wanted
        if (!wanted) recognizer.reset()
        if (wanted) locations.start() else locations.stop()
        if (wanted) CaptureExclusions.attach(this)
        submit {
            error = null; retentionDay = null; recovery.reset()
            recorder.gapMs = preferences.gapMinutes * 60_000L
            if (!wanted) { resetDecode(); recorder.close() }
            publish()
        }
        GlassesTransports.real?.ambientChanged()
    }
    fun packet(packet: AmbientPacket) {
        if (!active || !preferences.listening || closed) return
        val count = runCatching { OpusPacket.samples(packet.opus) }.getOrElse { diagnostic("Invalid Opus TOC ignored"); return }
        // LifeLog delivers about 20 s of audio in one burst; 60 s of headroom absorbs it (the worker only appends
        // 240-byte packets for LifeLog). Overflow now costs a time anchor, not the conversation.
        if (queuedSamples.addAndGet(count) > QUEUE_SAMPLES) {
            audioTraffic.add("ambient-queue", packet.opus.size, true)?.let { Trace.log("Mic", it) }
            queuedSamples.addAndGet(-count)
            if (overflow.compareAndSet(false, true)) submit { recorder.drop(packet.utc, "capture queue overflow"); error = "Capture queue overflow"; overflow.set(false) }
            return
        }
        worker.post {
            try { guarded {
                if (!active || !preferences.listening) return@guarded
                packet.discontinuityBefore?.let { resetDecode(); recorder.drop(packet.utc, it); error = it }
                if (packet.source == "lifelog-mono" && packet.speech && recorder.packet(packet.opus, packet.utc, packet.source, packet.vpu, packet.arrivalUtc)) recorded(packet)
                if (packet.source == "lifelog-mono") {
                    if (!recorder.exclusions.overlaps(packet.utc, packet.utc + count / 48)) recognitionAudio.offer(packet)
                    return@guarded
                }
                // Fallback decoding is required for VAD; recognition-only work uses its own queue.
                if (packet.source != "lifelog-mono") {
                    val sample = timestamp; timestamp += OpusPacket.samples(packet.opus)
                    pending.enqueue(packet, sample)
                    if (pending.size > 64) { resetDecode(); recorder.drop(packet.utc, "decoder backlog"); error = "Opus decoder backlog"; return@guarded }
                    if (decoder == null) decoder = OpusPcmDecoder(::decoded)
                    decoder!!.packet(packet.opus, sample * 1000000 / 48000)
                }
            } } finally { queuedSamples.addAndGet(-count) }
        }
    }
    private fun decoded(pcm: ShortArray, channels: Int, pts: Long) {
        // Muted (AMB-14): nothing decoded after the switch flips reaches the recorder or voice matching.
        if (!active || !preferences.listening) return
        pending.decoded(pcm, channels, pts).forEach { decoded ->
            val packet = decoded.packet
            if (recorder.exclusions.overlaps(packet.utc, packet.utc + OpusPacket.samples(packet.opus) / 48)) return@forEach
            val speech = if (packet.source == "lifelog-mono") packet.speech else vad.speech(decoded.pcm, channels = decoded.channels)
            if (speech && active && preferences.listening) recognizer.recent(decoded.pcm, decoded.channels, packet.utc)
            if (packet.source == "lifelog-mono") return@forEach
            if (speech) {
                if (recorder.packet(packet.opus, packet.utc, packet.source, packet.vpu ||
                    (packet.opus[0].toInt() and 4 != 0 && vad.wearer(decoded.pcm, decoded.channels)), packet.arrivalUtc)) recorded(packet)
            } else recorder.boundary()
        }
    }
    private fun recorded(packet: AmbientPacket) { lastRetainedAt = System.currentTimeMillis(); phase = "Recording (${packet.source})" }
    fun boundary() = submit { recorder.boundary(); phase = "Waiting for speech" }
    fun drop(reason: String) = submit { resetDecode(); recorder.drop(System.currentTimeMillis(), reason); phase = reason }
    fun sourceState(value: String) = submit { phase = value; publish() }
    fun diagnostic(message: String) { Log.i("AmbientCapture", message); submit { error = message; publish() } }
    fun begin(id: String, kind: String, utc: Long) = submit { if (active) recorder.beginExclusion(id, kind, utc) }
    fun end(id: String, utc: Long) = submit { recorder.endExclusion(id, utc) }
    private fun submit(action: () -> Unit) { if (!closed) worker.post { guarded(action) } }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            Trace.error("Opus", "Ambient audio pipeline failure", e)
            error = "Capture error: ${e.message}"; Log.e("AmbientCapture", "Capture operation failed", e)
            active = false
            runCatching { resetDecode() }; runCatching { recorder.abort() }
            if (preferences.enabled && !preferences.paused) {
                val delay = recovery.failed(SystemClock.elapsedRealtime())
                error += "; retry in ${delay / 1000}s"
            }
            main.post { locations.stop(); GlassesTransports.real?.ambientChanged() }
            publish()
        }
    }
    private fun resetDecode() { recognitionAudio.reset(); decoder?.close(); decoder = null; pending.reset(); vad.reset(); recognizer.reset(); timestamp = 0 }
    private fun publish() {
        val records = conversations.all()
        val label = when { !preferences.enabled -> "Off"; preferences.paused -> "Paused"; error?.startsWith("Capture error") == true -> error!!; else -> "$phase (${preferences.mode})${error?.let { "; $it" }.orEmpty()}" }
        val bytes = listOf(root, File(context.filesDir, "ambient-share")).sumOf { directory -> directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        state.value = AmbientStatus(label, records.sumOf { it.samples } / 48, records.size, bytes)
    }
    fun close() {
        preferences.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        CaptureExclusions.attach(null); active = false; locations.close(); closed = true; exportScope.cancel()
        recognitionAudio.close(); (recognizer as? Closeable)?.close()
        worker.post { runCatching { resetDecode(); recorder.close() }; thread.quitSafely() }
    }
    fun isActive() = active
    companion object {
        const val QUEUE_SAMPLES = 2_880_000L // 60 s at 48 kHz
        const val MODEL_RETRY_MS = 10 * 60_000L
        fun modelRetryMs(tries: Int): Long = if (tries == 0) 0 else minOf(6 * 3600_000L, MODEL_RETRY_MS shl minOf(tries - 1, 6))
        fun modelNotice(status: String, wifiOnly: Boolean): String = when {
            status == "ready" -> ""
            status.startsWith("downloading") -> "Downloading voice model ${status.removePrefix("downloading").trim()}"
            status.startsWith("failed") -> "Voice model download failed; retrying"
            else -> if (wifiOnly) "Voice model not downloaded (Wi-Fi needed)" else "Voice model not downloaded"
        }
    }
}

/** Retry indefinitely at a bounded rate; only a stable minute clears repeated-failure history. */
class CaptureRecovery {
    private var failures = 0
    private var stableSince: Long? = null
    private var due: Long? = null
    fun failed(now: Long): Long {
        val delay = minOf(60_000L, 1000L shl minOf(failures, 6))
        failures++; stableSince = null; due = now + delay
        return delay
    }
    fun ready(now: Long) = due?.let { now >= it } == true
    fun rearmed(now: Long) { due = null; stableSince = now }
    fun healthy(now: Long) { if (due == null && stableSince?.let { now - it >= 60_000 } == true) failures = 0 }
    fun reset() { failures = 0; due = null; stableSince = null }
}
