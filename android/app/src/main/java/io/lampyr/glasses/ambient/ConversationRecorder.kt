package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable data class ManifestSegment(val offsetSec: Double, val at: String, val durationSec: Double)
@Serializable data class ManifestFix(val at: String, val lat: Double, val lon: Double, val accuracyM: Float? = null)
@Serializable data class ConversationManifest(val clientId: String, val device: String = "glasses",
    val startedAt: String, val endedAt: String, val speechSec: Double, val tz: String,
    val segments: List<ManifestSegment>, val fixes: List<ManifestFix> = emptyList()) {
    fun validate() {
        require(clientId.matches(Regex("[A-Za-z0-9_-]{8,80}")) && device == "glasses")
        val span = java.time.Duration.between(Instant.parse(startedAt), Instant.parse(endedAt))
        require(!span.isNegative && span <= java.time.Duration.ofDays(2))
        ZoneId.of(tz)
        require(speechSec.isFinite() && speechSec >= 0 && segments.size in 1..5000 && fixes.size <= 2000)
        require(segments.first().offsetSec == 0.0)
        var end = 0.0
        segments.forEachIndexed { i, s ->
            Instant.parse(s.at)
            require(s.offsetSec.isFinite() && s.durationSec.isFinite() && s.durationSec > 0)
            require(kotlin.math.abs(s.offsetSec - end) < 1e-8)
            if (i > 0) require(s.offsetSec > segments[i - 1].offsetSec)
            end = s.offsetSec + s.durationSec
        }
        require(kotlin.math.abs(end - speechSec) < 1e-8)
        fixes.forEach { f -> Instant.parse(f.at); require(f.lat.isFinite() && f.lat in -90.0..90.0 && f.lon.isFinite() && f.lon in -180.0..180.0) }
    }
}
@Serializable data class ConversationRecord(val manifest: ConversationManifest, val samples: Long = 0,
    val committedBytes: Long = 0, val state: String = "recording", val serverId: String? = null,
    val audioSent: Boolean = false, val restarts: Int = 0, val interruptedPuts: Int = 0, val attempts: Int = 0, val nextAttemptAt: Long = 0, val error: String? = null)

/** Each conversation is its own transaction: synced audio prefix then atomic metadata rename.
 * The upload worker and capture worker share this repository's monitor, never mutable records. */
class ConversationStore(val root: File, private val diagnostic: (String) -> Unit = {}) {
    private val records = linkedMapOf<String, ConversationRecord>()
    private val active = mutableSetOf<String>()
    private val skippedCleared = mutableSetOf<String>()
    companion object { const val MIGRATION_MARKER = "migrated-v2" }
    @Synchronized fun recording(id: String) { active += id }
    @Synchronized fun released(id: String) { active -= id }
    var blockedUntil: Long = runCatching { File(root, "backoff").readText().toLong() }.getOrDefault(0); private set
    init {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            try {
                val record = privateJson.decodeFromString<ConversationRecord>(File(dir, "record.json").readText())
                require(record.manifest.clientId == dir.name && dir.name.matches(Regex("[A-Za-z0-9_-]{8,80}")))
                records[dir.name] = record
            } catch (_: Exception) { diagnostic("Unreadable conversation metadata; files preserved") }
        }
    }
    fun audio(id: String): File { require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))); return File(File(root, id), "audio.ogg") }
    @Synchronized fun all() = records.values.toList().sortedByDescending { runCatching { Instant.parse(it.manifest.startedAt) }.getOrDefault(Instant.EPOCH) }
    @Synchronized fun get(id: String) = records[id]
    @Synchronized fun save(record: ConversationRecord) {
        val id = record.manifest.clientId
        audio(id) // validate before constructing paths
        atomicJson(File(File(root, id), "record.json"), privateJson.encodeToString(record))
        records[id] = record
    }
    /** Late HTTP replies cannot recreate retained data or undo a storage-limit eviction. */
    @Synchronized fun saveIfPresent(record: ConversationRecord) {
        val current = records[record.manifest.clientId] ?: return
        if (current.state !in listOf("failed", "skipped")) save(record)
    }
    @Synchronized fun blockUntil(at: Long) { atomicJson(File(root, "backoff"), at.toString()); blockedUntil = at }
    @Synchronized fun recover() {
        all().filter { it.state == "recording" && it.manifest.clientId !in active }.forEach { r ->
            try {
                val file = audio(r.manifest.clientId)
                var samples = 0L
                require(r.committedBytes <= file.length())
                OggReader.audio(file, limit = r.committedBytes) { packet, _ -> samples += OpusPacket.samples(packet) }
                require(samples == r.samples)
                OggOpusWriter(file, r.committedBytes).use { require(it.samples == samples) }
                save(r.copy(committedBytes = file.length(), state = if (samples < 30 * 48000) "skipped" else "waiting"))
            } catch (_: Exception) { save(r.copy(state = "failed", error = "Recovery failed; original audio preserved")) }
        }
    }
    @Synchronized fun checkWritable() {
        check(root.isDirectory || root.mkdirs()) { "Conversation storage unavailable" }
        check(root.canWrite()) { "Conversation storage is read-only" }
    }
    @Synchronized fun fail(record: ConversationRecord, reason: String) {
        if (records.containsKey(record.manifest.clientId)) {
            save(record.copy(state = "failed", error = reason))
            audio(record.manifest.clientId).delete()
        }
    }
    @Synchronized fun enforceLimits(maxBytes: Long = 2_000_000_000L, maxCount: Int = 500) {
        require(maxBytes >= 0 && maxCount >= 0)
        // Skipped (too short) records are never uploaded: their audio is deleted (once the one-time fragment
        // rescue has had its chance) and they never use the pending budget or evict a waiting conversation.
        // This runs every second: each skipped record's audio is deleted once, not re-checked on every pass.
        if (File(root, MIGRATION_MARKER).exists())
            all().filter { it.state == "skipped" && it.manifest.clientId !in active && skippedCleared.add(it.manifest.clientId) }
                .forEach { audio(it.manifest.clientId).delete() }
        val pending = all().filter { !it.audioSent && it.state != "skipped" && (it.state != "failed" || audio(it.manifest.clientId).exists()) }
        var bytes = pending.sumOf { audio(it.manifest.clientId).length() }
        var count = pending.size
        for (r in pending.asReversed().filter { it.state != "recording" }) {
            if (bytes <= maxBytes && count <= maxCount) break
            bytes -= audio(r.manifest.clientId).length(); count--
            fail(r, "storage limit")
        }
    }
    /** One-time repair of conversations that the burst-unaware capture (before the stream clock) cut into
     * fragments of a few seconds, all skipped as too short. Fragments that still have their audio and lie
     * within [gapMs] of each other are joined, in order, into one conversation; a joined conversation with
     * at least 30 s of speech is queued for upload. Audio dropped between fragments at the time is gone, so
     * a rescued conversation has holes; its segments keep the fragments' own time anchors.
     * Afterwards the audio of the remaining skipped records is deleted and skipped or storage-limit records
     * older than a day are removed. Runs once per store (marker file). */
    @Synchronized fun migrateOnce(now: Long, gapMs: Long = 600_000L): Int {
        val marker = File(root, MIGRATION_MARKER)
        if (marker.exists()) return 0
        var rescued = 0
        try { rescued = rescueFragments(gapMs) } catch (e: Exception) { diagnostic("Fragment rescue failed: ${e.message}") }
        all().filter { it.state == "skipped" && it.manifest.clientId !in active }.forEach { audio(it.manifest.clientId).delete() }
        all().filter { (it.state == "skipped" || (it.state == "failed" && it.error == "storage limit")) && it.manifest.clientId !in active &&
            now - runCatching { Instant.parse(it.manifest.endedAt).toEpochMilli() }.getOrDefault(0L) > 86_400_000L }.forEach { r ->
            if (audio(r.manifest.clientId).parentFile!!.deleteRecursively()) records.remove(r.manifest.clientId)
        }
        root.mkdirs(); atomicJson(marker, rescued.toString())
        return rescued
    }
    private fun rescueFragments(gapMs: Long): Int {
        fun start(r: ConversationRecord) = Instant.parse(r.manifest.startedAt).toEpochMilli()
        fun end(r: ConversationRecord) = Instant.parse(r.manifest.endedAt).toEpochMilli()
        val fragments = all().filter { it.state == "skipped" && it.samples > 0 && it.manifest.clientId !in active &&
            audio(it.manifest.clientId).isFile && runCatching { start(it); end(it) }.isSuccess }.sortedBy { start(it) }
        val groups = mutableListOf<MutableList<ConversationRecord>>()
        for (f in fragments) {
            val group = groups.lastOrNull()
            val joins = group != null && start(f) - group.maxOf { end(it) } < gapMs && start(f) - start(group.first()) < 86_400_000L &&
                group.sumOf { it.manifest.segments.size } + f.manifest.segments.size <= 5000 && group.sumOf { it.committedBytes } + f.committedBytes < 80_000_000L
            if (joins) group!!.add(f) else groups.add(mutableListOf(f))
        }
        var rescued = 0
        for (group in groups) {
            if (group.sumOf { it.samples } < 30 * 48000) continue
            val id = UUID.randomUUID().toString()
            val segments = mutableListOf<ManifestSegment>(); val fixes = mutableListOf<ManifestFix>()
            val used = mutableListOf<ConversationRecord>()
            var base = 0L
            try {
                val writer = OggOpusWriter(audio(id))
                try {
                    for (f in group) {
                        val packets = mutableListOf<ByteArray>()
                        try { OggReader.audio(audio(f.manifest.clientId), limit = f.committedBytes) { packet, _ -> packets += packet } } catch (_: Exception) { continue }
                        if (packets.sumOf { OpusPacket.samples(it) } != f.samples) continue
                        packets.forEach { writer.append(it) }
                        val own = f.manifest.segments
                        own.forEachIndexed { i, s ->
                            val a = kotlin.math.round(s.offsetSec * 48000).toLong()
                            val b = if (i == own.lastIndex) f.samples else kotlin.math.round(own[i + 1].offsetSec * 48000).toLong()
                            if (b > a) segments += ManifestSegment((base + a) / 48000.0, s.at, (base + b) / 48000.0 - (base + a) / 48000.0)
                        }
                        fixes += f.manifest.fixes
                        base += f.samples; used += f
                    }
                } finally { writer.close() }
                if (base < 30 * 48000 || segments.isEmpty()) { audio(id).parentFile!!.deleteRecursively(); continue }
                val first = used.first()
                val manifest = ConversationManifest(id, startedAt = first.manifest.startedAt,
                    endedAt = used.maxOf { Instant.parse(it.manifest.endedAt) }.toString(), speechSec = base / 48000.0, tz = first.manifest.tz,
                    segments = segments, fixes = fixes.take(2000))
                manifest.validate()
                save(ConversationRecord(manifest, samples = base, committedBytes = audio(id).length(), state = "waiting"))
                used.forEach { f -> if (audio(f.manifest.clientId).parentFile!!.deleteRecursively()) records.remove(f.manifest.clientId) }
                rescued++
            } catch (e: Exception) {
                runCatching { audio(id).parentFile!!.deleteRecursively() }; records.remove(id)
                diagnostic("Fragment group not rescued: ${e.message}")
            }
        }
        return rescued
    }
    @Synchronized fun retain(now: Long, days: Int) {
        all().filter { (it.audioSent || it.state in listOf("skipped", "failed")) &&
            now - runCatching { Instant.parse(it.manifest.endedAt).toEpochMilli() }.getOrElse { _ -> audio(it.manifest.clientId).parentFile!!.lastModified() } >
                (if (it.state == "skipped") 1 else days) * 86400_000L }.forEach { r ->
            // No record remains to enqueue again. The server retains the idempotency receipt.
            val directory = audio(r.manifest.clientId).parentFile!!
            if (directory.deleteRecursively()) records.remove(r.manifest.clientId)
        }
    }
}

class ConversationRecorder(private val store: ConversationStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 }, private val sizeCap: Long = 89_000_000) : Closeable {
    val exclusions = ExclusionRanges()
    var gapMs = 600_000L
    var onConversation: (String) -> Unit = {}
    var onConversationEnd: (String) -> Unit = {}
    private var record: ConversationRecord? = null
    private var writer: OggOpusWriter? = null
    private var checkpointAt = 0L
    private var lastSpeechElapsed = 0L
    private var forceAnchor = false
    init { store.recover() }
    fun packet(packet: ByteArray, utc: Long, source: String, wearer: Boolean, arrivalUtc: Long = utc): Boolean {
        val count = OpusPacket.samples(packet)
        if (exclusions.overlaps(utc, utc + count / 48)) return false
        tick(utc)
        val candidateStart = record?.let { maxOf(Instant.ofEpochMilli(utc), Instant.parse(it.manifest.endedAt)) } ?: Instant.ofEpochMilli(utc)
        val newSegment = forceAnchor || (record?.manifest?.segments?.lastOrNull()?.let { needsAnchor(it, candidateStart) } ?: true)
        val old = record
        if (old != null && (writer!!.length + packet.size + 512 >= sizeCap ||
            (old.manifest.segments.size >= 5000 && newSegment) ||
            candidateStart.plusNanos(count * 1_000_000_000 / 48000) > Instant.parse(old.manifest.startedAt).plusSeconds(2 * 86400L))) close()
        if (record == null) {
            val id = UUID.randomUUID().toString()
            val instant = Instant.ofEpochMilli(utc).toString()
            writer = OggOpusWriter(store.audio(id))
            record = ConversationRecord(ConversationManifest(id, startedAt = instant, endedAt = instant, speechSec = 0.0, tz = zone().id, segments = emptyList()))
            store.recording(id)
            onConversation(id)
        }
        val r = record!!; val m = r.manifest
        val start = maxOf(Instant.ofEpochMilli(utc), Instant.parse(m.endedAt))
        val offset = writer!!.append(packet)
        check(offset == r.samples)
        val segments = m.segments.toMutableList()
        val last = segments.lastOrNull()
        if (last != null && !forceAnchor && !needsAnchor(last, start)) {
            segments[segments.lastIndex] = last.copy(durationSec = (offset + count) / 48000.0 - last.offsetSec)
        } else segments += ManifestSegment(offset / 48000.0, start.toString(), count / 48000.0)
        record = r.copy(samples = offset + count, manifest = m.copy(endedAt = start.plusNanos(count * 1_000_000_000 / 48000).toString(),
            speechSec = (offset + count) / 48000.0, segments = segments))
        forceAnchor = false
        lastSpeechElapsed = monotonic()
        if (r.samples == 0L || monotonic() - checkpointAt >= 1000) checkpoint()
        exclusions.prune(start.toEpochMilli() - 5000)
        return true
    }
    private fun needsAnchor(segment: ManifestSegment, start: Instant): Boolean {
        val expectedEnd = Instant.parse(segment.at).plusNanos(kotlin.math.round(segment.durationSec * 1e9).toLong())
        return java.time.Duration.between(expectedEnd, start).toNanos() > 300_000_000L
    }
    fun tick(utc: Long) { if (record != null && monotonic() - lastSpeechElapsed >= gapMs) close() }
    fun boundary() {} // Packet UTC and accumulated segment drift determine when a new anchor is needed.
    /** Lost or discontinuous audio (queue overflow, clock gap, Bluetooth blip) adds a time anchor; it does not
     * end the conversation. Conversations end only by the gap timer, mute/disable, stop, or the size/segment/span caps. */
    fun drop(utc: Long, reason: String) { forceAnchor = true; checkpoint() }
    fun beginExclusion(id: String, kind: String, utc: Long) { exclusions.begin(id, utc) }
    fun endExclusion(id: String, utc: Long) { exclusions.end(id, utc) }
    fun fix(fix: GeoFix) {
        if (!validFix(fix)) return
        val r = record?.takeIf { it.manifest.clientId == fix.conversationId } ?: return
        if (r.manifest.fixes.size >= 2000) return
        val f = ManifestFix(Instant.ofEpochMilli(fix.utc).toString(), fix.lat!!, fix.lon!!, fix.accuracy?.takeIf { it.isFinite() && it >= 0 })
        record = r.copy(manifest = r.manifest.copy(fixes = r.manifest.fixes + f)); checkpoint()
    }
    fun checkpoint() {
        val r = record ?: return
        writer!!.sync(); record = r.copy(committedBytes = writer!!.length); store.save(record!!); checkpointAt = monotonic()
    }
    override fun close() {
        val r = record ?: return
        writer!!.close()
        val final = r.copy(committedBytes = writer!!.length, state = if (r.samples < 30 * 48000) "skipped" else "waiting")
        final.manifest.validate(); store.save(final)
        record = null; writer = null; forceAnchor = false; store.released(r.manifest.clientId); onConversationEnd(r.manifest.clientId)
    }
    fun abort() { writer?.abort(); record?.let { store.released(it.manifest.clientId) }; writer = null; record = null }
}

/** Durable stage receipts survive retries; remote expiry restarts the idempotent POST. */
class ConversationUploader(private val store: ConversationStore, private val gateway: GatewayClient,
    private val network: (Boolean) -> Boolean, private val wifiOnly: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxBytes: () -> Long = { 2_000_000_000L }, private val maxCount: () -> Int = { 500 }) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    suspend fun tick() {
        if (!lock.tryLock()) return
        try {
            store.recover()
            store.enforceLimits(maxBytes(), maxCount())
            if (now() < store.blockedUntil || !network(wifiOnly())) return
            var current = store.all().filter { it.state in listOf("waiting", "uploading", "uploaded") && it.nextAttemptAt <= now() }
                .minByOrNull { runCatching { Instant.parse(it.manifest.startedAt) }.getOrDefault(Instant.EPOCH) } ?: return
            try {
                try { current.manifest.validate() } catch (_: Exception) {
                    store.fail(current, "Invalid conversation manifest"); return
                }
                if (!current.audioSent) {
                    val file = store.audio(current.manifest.clientId)
                    if (!file.isFile) { store.fail(current, "Audio file missing"); return }
                    if (file.length() >= 90_000_000) { store.fail(current, "Audio exceeds size cap"); return }
                }
                if (current.serverId == null) {
                    if (current.manifest.speechSec < 30) { store.saveIfPresent(current.copy(state = "skipped")); return }
                    val response = gateway.exchange("/ambient/conversations", "POST", privateJson.encodeToJsonElement(current.manifest))
                    if (terminal(response, current)) return
                    val id = response.requireSuccess().json().s("id"); require(id.isNotBlank())
                    current = current.copy(serverId = id, state = "uploading"); store.saveIfPresent(current)
                }
                val path = "/ambient/conversations/${segment(current.serverId!!)}"
                if (!current.audioSent) {
                    store.saveIfPresent(current.copy(state = "uploading"))
                    val response = gateway.exchange("$path/audio", "PUT", audio = store.audio(current.manifest.clientId))
                    when (response.code) {
                        404 -> { restart(current); return }
                        409 -> { store.fail(current, "server has different audio"); return }
                        400 -> {
                            current = current.copy(interruptedPuts = current.interruptedPuts + 1)
                            if (current.interruptedPuts > 3) { store.fail(current, "Audio PUT interrupted after three retries (400)"); return }
                            response.requireSuccess()
                        }
                    }
                    if (terminal(response, current)) return
                    response.requireSuccess()
                    current = current.copy(audioSent = true, state = "uploaded", attempts = 0, nextAttemptAt = now() + 60_000, error = null)
                    store.saveIfPresent(current); return
                }
                val response = gateway.exchange(path)
                if (response.code == 404) { restart(current); return }
                if (terminal(response, current)) return
                val data = response.requireSuccess().json()
                val state = when (data.s("state")) { "timed" -> "transcribed"; "failed" -> "failed"; else -> "uploaded" }
                store.saveIfPresent(current.copy(state = state, nextAttemptAt = now() + 5 * 60_000, attempts = 0,
                    error = if (state == "failed") data.s("error", "Server processing failed").take(200) else null))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                store.saveIfPresent(current.copy(state = if (current.audioSent) "uploaded" else "waiting", attempts = current.attempts + 1,
                    nextAttemptAt = now() + minOf(6 * 3600_000L, 5000L shl minOf(current.attempts, 12)), error = "Upload waiting; retry scheduled"))
            }
        } finally { lock.unlock() }
    }
    private fun restart(item: ConversationRecord) {
        if (item.restarts >= 3) store.fail(item, "Server conversation missing after three restarts")
        else store.saveIfPresent(item.copy(serverId = null, audioSent = false, state = "waiting", restarts = item.restarts + 1, nextAttemptAt = 0))
    }
    private fun terminal(r: GatewayResponse, item: ConversationRecord): Boolean = when (r.code) {
        429 -> { val at = now() + 30 * 60_000; store.blockUntil(at); store.saveIfPresent(item.copy(state = if (item.audioSent) "uploaded" else "waiting", nextAttemptAt = at, error = "Server busy; waiting 30 minutes")); true }
        400, 413, 422, 404 -> { store.fail(item, "Gateway rejected conversation (${r.code})"); true }
        else -> false
    }
}

/** Reconfiguration never cancels an in-flight upload. People data reloads have their own job. */
class ConversationUploadWorker(scope: CoroutineScope, private val create: (GatewayClient) -> ConversationUploader) {
    @Volatile private var gateway: GatewayClient? = null
    fun configure(value: GatewayClient?) { gateway = value }
    init {
        scope.launch {
            var previous: GatewayClient? = null
            var uploader: ConversationUploader? = null
            while (isActive) {
                val current = gateway
                if (current != null) {
                    if (current !== previous) { uploader = create(current); previous = current }
                    uploader!!.tick()
                }
                delay(5000)
            }
        }
    }
}

/** Old daily files are never enqueued, but still obey the user's retention setting. */
internal fun retainLegacyRecordings(root: File, today: java.time.LocalDate, days: Int) {
    root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }.forEach {
        val date = runCatching { java.time.LocalDate.parse(it.name) }.getOrNull()
        if (date != null && date.isBefore(today.minusDays(days.toLong()))) it.deleteRecursively()
    }
}
