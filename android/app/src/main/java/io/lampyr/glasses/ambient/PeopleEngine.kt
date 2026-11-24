package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable data class HeardEvent(val id: String, val personId: String, val profileId: String, val at: String,
    val confidence: Float, val lat: Double? = null, val lon: Double? = null, val accuracyM: Float? = null)
@Serializable private data class PeopleDisk(val voices: VoiceList = VoiceList(), val etag: String? = null,
    val syncedAt: Long = 0, val events: List<HeardEvent> = emptyList(), val pending: List<String> = emptyList(),
    val mutes: Map<String, Boolean> = emptyMap())

/** Private, durable voice cache and recognition outbox. Disk writes and HTTP run on IO.
 * UI reads only immutable snapshots. Unsynced mute choices survive voice refresh and process death. */
class PeopleEngine(private val file: File, private val gateway: GatewayClient,
    private val now: () -> Long = { System.currentTimeMillis() }, private val changed: () -> Unit = {},
    private val diagnostic: (String) -> Unit = {}) {
    private val lock = Mutex()
    private val syncLock = Mutex()
    private val muteLock = Mutex()
    private val heardLock = Mutex()
    @Volatile private var disk = load()
    private fun load(): PeopleDisk {
        if (!file.exists()) return PeopleDisk()
        return try { privateJson.decodeFromString<PeopleDisk>(file.readText()) }
        catch (e: Exception) {
            val aside = File(file.parentFile, file.name + ".unreadable-" + UUID.randomUUID())
            check(file.renameTo(aside)) { "Unable to preserve unreadable people outbox" }
            diagnostic("Unreadable people outbox preserved for recovery")
            PeopleDisk()
        }
    }
    @Volatile var lastFix: GeoFix? = null
    @Volatile var lastError: String? = null; private set
    /** Why recognition cannot run (voice model missing or downloading); set by AmbientRuntime. */
    @Volatile var modelNotice: () -> String? = { null }
    private var muteAttempts = 0
    private var heardAttempts = 0
    private var muteDue = 0L
    private var heardDue = 0L
    val voices get() = disk.voices.people.map { p -> disk.mutes[p.id]?.let { p.copy(muted = it) } ?: p }
    val lastSync get() = disk.syncedAt
    val muted get() = voices.filter { it.muted && !it.me }
    private suspend fun save(value: PeopleDisk) {
        val events = value.events.filter { Instant.parse(it.at).toEpochMilli() >= now() - 30L * 86400_000 }
        val retained = events.map { it.id }.toSet()
        val pruned = value.copy(events = events, pending = value.pending.filter { it in retained })
        withContext(Dispatchers.IO) { atomicJson(file, privateJson.encodeToString(pruned)) }
        disk = pruned
        withContext(Dispatchers.Main.immediate) { changed() }
    }
    companion object {
        fun parse(raw: JsonElement): VoiceList {
            require(raw.n("dim").toInt() == 256)
            require(raw.s("model") == "wespeaker-voxceleb-resnet34-LM") { "Unsupported voice embedding model" }
            val people = raw.a("people").map { p ->
                VoicePerson(p.s("id"), p.s("name"), p.s("handle").takeUnless { it.isEmpty() || it == "null" },
                    p.b("me"), p.b("muted"), p.s("status", "confirmed"), if (p.s("status") == "unlinked") "" else p.s("blurb").take(500),
                    p.a("profiles").mapNotNull { profile ->
                        runCatching {
                            val bytes = Base64.getDecoder().decode(profile.s("embedding")); require(bytes.size == 1024)
                            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            val v = FloatArray(256) { b.float }
                            val unit = SpeakerDecision.unit(v) ?: error("Invalid vector")
                            VoiceProfile(profile.s("id"), unit.toList())
                        }.getOrNull()
                    })
            }
            return VoiceList(raw.s("model"), 256, raw.s("updatedAt"), people)
        }
    }
    suspend fun sync(force: Boolean = false) = syncLock.withLock {
        val snapshot = lock.withLock { disk }
        if (!force && snapshot.syncedAt > 0 && now() - snapshot.syncedAt in 0 until 30 * 60_000) return@withLock
        val response = gateway.exchange("/people/voices", etag = snapshot.etag)
        val parsed = if (response.code == 200) withContext(Dispatchers.IO) { parse(response.json()) } else null
        lock.withLock {
            when (response.code) {
                304 -> save(disk.copy(syncedAt = now()))
                200 -> {
                    val before = snapshot.voices.people.associateBy { it.id }
                    val latest = disk.voices.people.associateBy { it.id }
                    val merged = parsed!!.copy(people = parsed.people.map { person ->
                        val current = latest[person.id]
                        // Preserve mute acknowledgements that arrived while this GET was in flight.
                        if (current != null && current.muted != before[person.id]?.muted) person.copy(muted = current.muted) else person
                    })
                    save(disk.copy(voices = merged, etag = response.etag, syncedAt = now()))
                }
                else -> response.requireSuccess()
            }
        }
    }
    suspend fun heard(match: RecognizedVoice, at: Long, allowed: () -> Boolean = { true }): Boolean = lock.withLock {
        if (!allowed() || match.person.me || match.person.muted || voices.any { it.id == match.person.id && (it.me || it.muted) } ||
            !match.confidence.isFinite() || match.confidence !in -1f..1f || at > now() + 300_000) return@withLock false
        val previous = disk.events.filter { it.personId == match.person.id }.maxOfOrNull { Instant.parse(it.at).toEpochMilli() }
        if (previous != null && at - previous < 60_000) return@withLock false
        val fix = lastFix?.takeIf { now() - it.utc in 0..599_999 && validFix(it) }
        val event = HeardEvent(UUID.randomUUID().toString(), match.person.id, match.profileId, Instant.ofEpochMilli(at).toString(),
            match.confidence, fix?.lat, fix?.lon, fix?.accuracy?.takeIf { it.isFinite() && it >= 0 })
        save(disk.copy(events = (disk.events + event).filter { Instant.parse(it.at).toEpochMilli() >= now() - 30L * 86400_000 }, pending = disk.pending + event.id))
        true
    }
    fun merge(server: PeopleSnapshot): PeopleSnapshot {
        val current = voices.associateBy { it.id }
        val local = disk.events.groupBy { it.personId }.mapNotNull { (id, events) ->
            val voice = current[id] ?: return@mapNotNull null
            val event = events.maxBy { Instant.parse(it.at) }
            Person(id, voice.name, if (event.lat == null) "" else "${event.lat},${event.lon}", event.at,
                if (voice.status == "unlinked") "" else voice.blurb, voice.muted)
        }
        val mutedCards = voices.filter { it.muted && !it.me }.map { Person(it.id, it.name, "", "", it.blurb, true) }
        val recent = (local + server.recent + server.heard + mutedCards).groupBy { it.id }.map { (id, rows) ->
            val p = rows.maxBy { runCatching { Instant.parse(it.seenAt).toEpochMilli() }.getOrDefault(0) }
            val enriched = current[id]?.let { p.copy(name = it.name, notes = if (it.status == "unlinked") "" else it.blurb, muted = it.muted) } ?: p
            disk.mutes[id]?.let { enriched.copy(muted = it) } ?: enriched
        }.filter { current[it.id]?.me != true }.sortedByDescending { runCatching { Instant.parse(it.seenAt).toEpochMilli() }.getOrDefault(0) }
        return PeopleSnapshot(recent.filter { !it.muted && runCatching { now() - Instant.parse(it.seenAt).toEpochMilli() in 0..600_000 }.getOrDefault(false) }, recent)
    }
    suspend fun mute(id: String, muted: Boolean): ActionResult {
        lock.withLock { save(disk.copy(mutes = disk.mutes + (id to muted))) }
        return try {
            flushMutes()
            when {
                disk.mutes.containsKey(id) -> ActionResult(message = "Saved here; will sync when online")
                voices.find { it.id == id }?.muted == muted -> ActionResult(message = if (muted) "Person no longer counts" else "Person counts again")
                else -> ActionResult.failure(lastError ?: "Mute rejected; previous setting restored")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ActionResult(message = "Saved here; will sync when online") }
    }
    private suspend fun flushMutes() {
        if (!muteLock.tryLock()) return
        try {
            val queued = lock.withLock { disk.mutes.toMap() }
            for ((id, value) in queued) {
                val r = gateway.exchange("/people/${segment(id)}/mute", "POST", obj("muted" to JsonPrimitive(value)))
                val rejected = r.code in listOf(400, 404, 409, 422)
                if (!rejected) r.requireSuccess()
                lock.withLock {
                    // A reply for an older toggle must not remove a newer queued choice.
                    val remaining = if (disk.mutes[id] == value) disk.mutes - id else disk.mutes
                    if (rejected) {
                        lastError = "Mute rejected (${r.code}); previous setting restored"
                        save(disk.copy(mutes = remaining))
                    } else {
                        save(disk.copy(voices = disk.voices.copy(people = disk.voices.people.map { if (it.id == id) it.copy(muted = value) else it }), mutes = remaining))
                    }
                }
            }
        } finally { muteLock.unlock() }
    }
    private suspend fun acknowledge(ids: Set<String>) = lock.withLock { save(disk.copy(pending = disk.pending - ids)) }
    private suspend fun sendHeard(batch: List<HeardEvent>) {
        if (batch.isEmpty()) return
        val response = gateway.exchange("/people/heard", "POST", obj("events" to privateJson.encodeToJsonElement(batch)))
        if (response.code == 400 && batch.size > 1) {
            val half = batch.size / 2
            sendHeard(batch.take(half)); sendHeard(batch.drop(half))
            return
        }
        if (response.code == 400) {
            diagnostic("Dropped rejected heard event (400)")
            lastError = "Heard event rejected (400)"
        } else response.requireSuccess()
        acknowledge(batch.map { it.id }.toSet())
    }
    private suspend fun flushHeard() {
        if (!heardLock.tryLock()) return
        try {
            val batch = lock.withLock {
                save(disk) // Retention also prunes stale pending ids, including after restart.
                disk.events.filter { it.id in disk.pending }.take(200)
            }
            val valid = batch.filter { it.confidence.isFinite() && it.confidence in -1f..1f &&
                it.id.matches(Regex("[A-Za-z0-9_-]{8,80}")) && runCatching { Instant.parse(it.at).toEpochMilli() <= now() + 300_000 }.getOrDefault(false) }
            acknowledge((batch - valid.toSet()).map { it.id }.toSet())
            sendHeard(valid)
        } finally { heardLock.unlock() }
    }
    suspend fun flush() = supervisorScope {
        launch {
            if (now() < muteDue) return@launch
            try { flushMutes(); muteAttempts = 0; muteDue = 0 }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { lastError = "Mute sync waiting for network"; muteDue = now() + minOf(30 * 60_000L, 5000L shl minOf(muteAttempts++, 9)) }
        }
        launch {
            if (now() < heardDue) return@launch
            try { flushHeard(); heardAttempts = 0; heardDue = 0 }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { lastError = "Heard sync waiting for network"; heardDue = now() + minOf(30 * 60_000L, 5000L shl minOf(heardAttempts++, 9)) }
        }
    }

}

internal fun validFix(fix: GeoFix) = fix.lat?.let { it.isFinite() && it in -90.0..90.0 } == true &&
    fix.lon?.let { it.isFinite() && it in -180.0..180.0 } == true
