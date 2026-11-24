package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReviewTest {
    private lateinit var root: File
    private var now = 1_800_000_000_000L
    private val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
    @Before fun before() { root = Files.createTempDirectory("conversation-review").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun row(store: ConversationStore, id: String = "conversation-001", start: Long = now): ConversationRecord {
        val at = Instant.ofEpochMilli(start).toString()
        val r = ConversationRecord(ConversationManifest(id, startedAt = at, endedAt = Instant.ofEpochMilli(start + 30_000).toString(),
            speechSec = 30.0, tz = "UTC", segments = listOf(ManifestSegment(0.0, at, 30.0))), state = "waiting")
        store.save(r); store.audio(id).writeBytes(ByteArray(16)); return r
    }
    private fun gateway(reply: suspend (String, String) -> Int) = object : GatewayClient("https://fake.invalid", "synthetic") {
        override suspend fun exchange(path: String, method: String, body: JsonElement?, etag: String?, audio: File?) =
            GatewayResponse(reply(path, method), "{\"id\":\"server-id\",\"state\":\"timed\"}")
    }
    @Test fun accumulatedShortPausesKeepEveryPacketWithinThreeHundredMsAndExactOffsets() {
        val store = ConversationStore(root)
        var at = now
        val times = mutableListOf<Long>()
        ConversationRecorder(store, monotonic = { 0 }).use { r ->
            repeat(1600) { i -> times += at; r.packet(packet, at, "fake", false); at += 20 + 100 + (i * 37 % 191) }
        }
        assertTrue(at - now > 300_000)
        val m = store.all().single().manifest
        m.validate(); assertEquals(0.0, m.segments.first().offsetSec, 0.0)
        assertTrue(m.segments.zipWithNext().all { (a,b) -> b.offsetSec > a.offsetSec })
        times.forEachIndexed { i, real ->
            val position = i * .02
            val anchor = m.segments.last { it.offsetSec <= position + 1e-9 }
            val mapped = Instant.parse(anchor.at).toEpochMilli() + ((position - anchor.offsetSec) * 1000).toLong()
            assertTrue("packet $i slipped by ${real - mapped}", kotlin.math.abs(real - mapped) <= 300)
        }
        assertTrue(m.segments.all { kotlin.math.abs(it.offsetSec * 48000 - kotlin.math.round(it.offsetSec * 48000)) < 1e-6 })
    }
    @Test fun segmentCapRotatesEvenWhenEachIndividualPauseWouldMerge() {
        val store = ConversationStore(root)
        ConversationRecorder(store, monotonic = { 0 }).use { r ->
            // 290ms pauses need an anchor every second packet as accumulated drift exceeds 300ms.
            repeat(10001) { r.packet(packet, now + it * 310L, "fake", false) }
        }
        assertEquals(2, store.all().size)
        assertEquals(5000, store.all().last().manifest.segments.size)
        store.all().forEach { it.manifest.validate(); assertEquals(0.0, it.manifest.segments.first().offsetSec, 0.0) }
        assertEquals(10001 * 960L, store.all().sumOf { it.samples })
    }
    @Test fun backwardWallClockCannotExtendGap() {
        val store = ConversationStore(root); var mono = 10L
        val r = ConversationRecorder(store, monotonic = { mono })
        r.packet(packet, now, "fake", false)
        mono += 599999; r.tick(now - 3600_000); assertEquals("recording", store.all().single().state)
        mono++; r.tick(now - 3600_000); assertEquals("skipped", store.all().single().state)
    }
    @Test fun audioPutHasNoOverallTimeoutButKeepsIdleTimeouts() = runTest {
        val seen = mutableListOf<Long>()
        val http = OkHttpClient.Builder().callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
                seen += chain.call().timeout().timeoutNanos()
                assertEquals(12000, chain.connectTimeoutMillis()); assertEquals(60000, chain.writeTimeoutMillis()); assertEquals(90000, chain.readTimeoutMillis())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }.build()
        val file = root.resolve("audio").apply { writeText("synthetic") }
        val client = GatewayClient("https://fake.invalid", "synthetic", http)
        client.exchange("/audio", "PUT", audio = file); client.exchange("/status")
        assertEquals(listOf(0L, 180_000_000_000L), seen)
    }
    @Test fun invalidMissingAndOversizedAudioFailLocallyAndFreeAudio() = runTest {
        for (kind in listOf("manifest", "invalid-start", "missing", "size")) {
            val store = ConversationStore(root.resolve(kind)); var r = row(store)
            when (kind) {
                "manifest" -> { r = r.copy(manifest = r.manifest.copy(endedAt = Instant.ofEpochMilli(now + 2 * 86400_000L + 1).toString())); store.save(r) }
                "invalid-start" -> { r = r.copy(manifest = r.manifest.copy(startedAt = "invalid")); store.save(r) }
                "missing" -> store.audio(r.manifest.clientId).delete()
                "size" -> java.io.RandomAccessFile(store.audio(r.manifest.clientId), "rw").use { it.setLength(90_000_000) }
            }
            var calls = 0
            ConversationUploader(store, gateway { _,_ -> calls++; 200 }, {true}, {false}, {now}).tick()
            val failed = store.all().single(); assertEquals("failed", failed.state); assertFalse(failed.error.isNullOrBlank())
            assertEquals(0, calls); assertFalse(store.audio(r.manifest.clientId).exists())
        }
    }
    @Test fun put400RetriesThreeTimesAcrossRestartsAnd408AlwaysRetries() = runTest {
        for (code in listOf(400,408)) {
            val store = ConversationStore(root.resolve("$code")); row(store)
            val g = gateway { _,method -> if (method == "PUT") code else 200 }
            repeat(4) { ConversationUploader(store,g,{true},{false},{now}).tick(); now += 7 * 3600_000L }
            assertEquals(if (code == 400) "failed" else "waiting", store.all().single().state)
            if (code == 400) assertEquals(4, store.all().single().interruptedPuts)
        }
    }
    @Test fun missingRemoteRestartsPostFromBothStagesAndIsBounded() = runTest {
        for (sent in listOf(false,true)) {
            val store = ConversationStore(root.resolve("$sent")); val r = row(store)
            store.save(r.copy(serverId = "expired", audioSent = sent, state = if (sent) "uploaded" else "uploading"))
            var posts = 0
            val g = gateway { _,method -> if (method == "POST") { posts++; 200 } else 404 }
            val uploader = ConversationUploader(store,g,{true},{false},{now})
            uploader.tick(); assertNull(store.all().single().serverId); assertFalse(store.all().single().audioSent)
            repeat(3) { uploader.tick() }
            assertEquals(3, posts); assertEquals("failed", store.all().single().state)
        }
    }
    @Test fun conflictingAudioIsFailedAndFreed() = runTest {
        val store = ConversationStore(root); val r = row(store)
        ConversationUploader(store,gateway { _,method -> if(method == "PUT")409 else 200 },{true},{false},{now}).tick()
        assertEquals("server has different audio", store.all().single().error)
        assertEquals("failed", store.all().single().state); assertFalse(store.all().single().audioSent)
        assertFalse(store.audio(r.manifest.clientId).exists())
    }
    @Test fun storageLimitsEvictOldestClosedAndFailedAudioIsRetained() {
        for (bytes in listOf(false,true)) {
            val store = ConversationStore(root.resolve("$bytes"))
            row(store,"conversation-old",now-2000); row(store,"conversation-new",now-1000)
            val recorder = ConversationRecorder(store, monotonic = {0})
            recorder.packet(packet,now,"fake",false)
            val active = store.all().first()
            store.enforceLimits(if(bytes) store.audio(active.manifest.clientId).length()+16 else Long.MAX_VALUE, if(bytes)500 else 2)
            assertEquals("failed",store.get("conversation-old")!!.state); assertEquals("storage limit",store.get("conversation-old")!!.error)
            assertEquals("waiting",store.get("conversation-new")!!.state); assertEquals("recording",store.get(active.manifest.clientId)!!.state)
            assertFalse(store.audio("conversation-old").exists())
            recorder.close()
        }
        val store = ConversationStore(root.resolve("retention")); val r = row(store)
        store.save(r.copy(state="failed")) // recovery/processing failures may preserve their audio
        store.retain(now+32L*86400_000,30); assertTrue(store.all().isEmpty()); assertFalse(store.audio(r.manifest.clientId).exists())
    }
    @Test fun workerRecoversAbortedButNeverActiveRecorderEvenOffline() = runTest {
        val store = ConversationStore(root); val r = ConversationRecorder(store)
        r.packet(packet,now,"fake",false)
        val uploader = ConversationUploader(store,gateway { _,_ -> error("offline") },{false},{false},{now})
        uploader.tick(); assertEquals("recording",store.all().single().state)
        r.abort(); uploader.tick(); assertEquals("skipped",store.all().single().state)
    }
    @Test fun dailyDirectoriesStillExpireWithoutUploading() {
        val old = root.resolve("2026-01-01").apply { mkdirs(); resolve("audio.ogg").writeText("old") }
        val recent = root.resolve("2026-11-15").apply { mkdirs() }
        val other = root.resolve("conversations").apply { mkdirs() }
        retainLegacyRecordings(root, LocalDate.parse("2026-11-16"),30)
        assertFalse(old.exists()); assertTrue(recent.exists()); assertTrue(other.exists())
    }
    @Test fun uploadRunsWithoutPeopleAndReloadDoesNotCancelPut() = runTest {
        val store = ConversationStore(root); row(store)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var cancelled = false
        val g = gateway { _,method ->
            if(method == "PUT") { entered.complete(Unit); try { release.await() } catch(e:CancellationException) { cancelled=true; throw e } }; 200
        }
        val worker = ConversationUploadWorker(backgroundScope) { ConversationUploader(store,it,{true},{false},{now}) }
        worker.configure(g); runCurrent(); entered.await()
        worker.configure(gateway { _,_ -> 200 }); runCurrent(); assertFalse(cancelled)
        release.complete(Unit); runCurrent(); assertTrue(store.all().single().audioSent)
    }
    @Test fun storageCapAlsoBoundsSkippedAndPreservedFailedAudio() {
        val store=ConversationStore(root)
        val skipped=row(store,"conversation-skipped",now-2000);store.save(skipped.copy(state="skipped"))
        val failed=row(store,"conversation-failed",now-1000);store.save(failed.copy(state="failed"))
        row(store,"conversation-current")
        store.migrateOnce(now) // skipped audio is kept only until the one-time fragment rescue has run
        store.enforceLimits(maxCount=1)
        // Skipped audio is deleted outright and never counts against (or evicts) waiting conversations.
        assertEquals("skipped",store.get(skipped.manifest.clientId)!!.state);assertNull(store.get(skipped.manifest.clientId)!!.error)
        assertFalse(store.audio(skipped.manifest.clientId).exists());assertFalse(store.audio(failed.manifest.clientId).exists())
        assertEquals("waiting",store.get("conversation-current")!!.state)
    }

    @Test fun latePutReplyCannotUndoStorageEviction() = runTest {
        val store=ConversationStore(root);val r=row(store)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val uploader=ConversationUploader(store,gateway { _,method ->
            if(method=="PUT") { entered.complete(Unit);release.await() };200
        },{true},{false},{now})
        val uploading=launch { uploader.tick() };entered.await()
        store.enforceLimits(maxCount=0);release.complete(Unit);uploading.join()
        assertEquals("failed",store.get(r.manifest.clientId)!!.state)
        assertEquals("storage limit",store.get(r.manifest.clientId)!!.error)
    }

}
