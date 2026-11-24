package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.GatewayClient
import io.lampyr.glasses.data.GatewayResponse
import io.lampyr.glasses.glasses.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** LifeLog on current firmware delivers ~20 s of audio in ~1 s every ~20 s (31 x 60 ms packets per message,
 * 11 messages 100 ms apart, as traced on 2026-11-17). These tests replay that shape through the capture pipeline. */
class BurstCaptureTest {
    private lateinit var root: File
    private val batchSamples = 31 * 2880L // 1.86 s
    private val t0 = Instant.parse("2026-11-18T01:43:09Z").toEpochMilli()
    @Before fun before() { root = Files.createTempDirectory("burst-capture").toFile() }
    @After fun after() { root.deleteRecursively() }
    private val period = 11 * 1860L // a burst carries exactly one period of audio
    private fun arrivals(bursts: Int) = (0 until bursts).flatMap { b -> (0 until 11).map { i -> t0 + b * period + i * 100L } }

    @Test fun streamClockTrustsSampleCountsAcrossBurstsAndOnlyAMinuteOfMissingAudioIsAGap() {
        val clock = StreamClock()
        val starts = arrivals(3).map { at -> clock.batch(at, batchSamples).also { assertFalse("no gap at $at", it.second) }.first }
        assertEquals(33, starts.size)
        starts.zipWithNext().forEach { (a, b) -> assertTrue("strictly increasing", b > a) }
        // Once a burst has ended the origin is known: from then on batches are exactly 1.86 s apart.
        starts.drop(10).zipWithNext().forEach { (a, b) -> assertEquals(1860L, b - a) }
        // The last sample of a burst is stamped exactly at its arrival (audio is never placed in the future).
        val lastArrival = arrivals(3).last()
        assertEquals(lastArrival, starts.last() + 1860)
        val late = clock.batch(lastArrival + 60_000, batchSamples)
        assertTrue("a minute without audio is a real gap", late.second)
        assertEquals(lastArrival + 60_000 - 1860, late.first)
    }

    @Test fun threeMinutesOfBurstDeliveredSpeechMakeOneUploadableConversation() = runTest {
        val commands = mutableListOf<Command>(); val packets = mutableListOf<AmbientPacket>()
        val source = LifeLogCaptureSource({ commands += it }, { packets += it }, {}, {})
        source.start(); source.event(GlassesEvent.LifeLog(161, obj(), emptyList()), t0 - 20_000)
        val task = Json.parseToJsonElement(commands.last { it.envelope.type == 162 }.envelope.json).jsonObject.string("taskId")!!
        val body = obj("taskId" to task)
        var sequence = 0
        arrivals(9).forEachIndexed { n, at ->
            // A later LifeLog wake (161) inside the stream must not restart the clock.
            if (n == 50) source.event(GlassesEvent.LifeLog(161, obj(), emptyList()), at)
            source.event(GlassesEvent.LifeLog(163, body, List(31) { LifeLogPacket(byteArrayOf(0x18), true, false) }, sequence++), at)
        }
        assertEquals(9 * 11 * 31, packets.size)
        assertTrue(packets.none { it.discontinuityBefore != null })
        val store = ConversationStore(root)
        var mono = 0L
        val recorder = ConversationRecorder(store, { ZoneId.of("America/Chicago") }, monotonic = { mono })
        packets.forEach { p ->
            p.discontinuityBefore?.let { recorder.drop(p.utc, it) }
            assertTrue(recorder.packet(p.opus, p.utc, p.source, p.vpu, p.arrivalUtc))
        }
        mono += 600_000; recorder.tick(0)
        val record = store.all().single()
        assertEquals("waiting", record.state)
        assertEquals(9 * 11 * 31 * 0.06, record.manifest.speechSec, 1e-6)
        record.manifest.validate()
        assertTrue("few anchors, not one per burst", record.manifest.segments.size <= 2)
        val end = Instant.parse(record.manifest.endedAt).toEpochMilli()
        assertTrue("audio is never stamped more than one burst after it arrived", end <= arrivals(9).last() + period)
    }

    @Test fun skippedFragmentsDoNotUseThePendingBudget() {
        val store = ConversationStore(root)
        repeat(600) { i ->
            val at = Instant.ofEpochMilli(t0 + i * 10_000L).toString()
            store.save(ConversationRecord(ConversationManifest("skip-%05d".format(i), startedAt = at, endedAt = at, speechSec = 0.02, tz = "UTC",
                segments = listOf(ManifestSegment(0.0, at, 0.02))), samples = 960, state = "skipped"))
        }
        val at = Instant.ofEpochMilli(t0).toString()
        store.save(ConversationRecord(ConversationManifest("wait-00001", startedAt = at, endedAt = at, speechSec = 31.0, tz = "UTC",
            segments = listOf(ManifestSegment(0.0, at, 31.0))), samples = 31 * 48000L, state = "waiting"))
        store.audio("wait-00001").writeBytes(ByteArray(10))
        store.enforceLimits(maxBytes = 2_000_000_000L, maxCount = 500)
        assertEquals("waiting", store.get("wait-00001")!!.state)
        assertTrue(store.audio("wait-00001").exists())
        // Skipped records are removed after a day, independent of the retention setting.
        store.retain(t0 + 600 * 10_000L + 2 * 86_400_000L, 30)
        assertNull(store.get("skip-00000")); assertNotNull(store.get("wait-00001"))
    }

    @Test fun shreddedFragmentsAreRestitchedOnceIntoAConversationThatCanUpload() {
        val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()) // 20 ms
        val store = ConversationStore(root)
        var mono = 0L
        val recorder = ConversationRecorder(store, { ZoneId.of("America/Chicago") }, monotonic = { mono })
        fun fragment(at: Long, seconds: Int) { repeat(seconds * 50) { recorder.packet(packet, at + it * 20L, "lifelog-mono", false) }; recorder.close() }
        repeat(20) { fragment(t0 + it * 5_000L, 2) }   // 20 fragments of 2 s, seconds apart: one talk
        fragment(t0 + 3 * 3600_000L, 2)                // a lone fragment hours later
        fragment(t0 - 3 * 86_400_000L, 2)              // an old fragment
        assertEquals(22, store.all().size); assertTrue(store.all().all { it.state == "skipped" })
        val now = t0 + 4 * 3600_000L
        assertEquals(1, store.migrateOnce(now))
        val rescued = store.all().single { it.state == "waiting" }
        assertEquals(40.0, rescued.manifest.speechSec, 1e-9)
        assertEquals(40L * 48000, rescued.samples)
        assertEquals(Instant.ofEpochMilli(t0).toString(), rescued.manifest.startedAt)
        rescued.manifest.validate()
        var samples = 0L
        OggReader.audio(store.audio(rescued.manifest.clientId)) { p, _ -> samples += OpusPacket.samples(p) }
        assertEquals(40L * 48000, samples)
        // The joined fragments are gone; the lone recent one keeps its list entry without audio; the old one is removed.
        assertEquals(2, store.all().size)
        val lone = store.all().single { it.state == "skipped" }
        assertFalse(store.audio(lone.manifest.clientId).exists())
        assertEquals("runs once", 0, store.migrateOnce(now))
        // The uploader accepts it (>= 30 s).
        assertTrue(rescued.manifest.speechSec >= 30)
    }

    @Test fun burstOfferedRecognitionAudioIsQueuedNotResetAndPacedSoEveryWindowIsScored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = object : GatewayClient("https://fake.invalid", "synthetic") {
            override suspend fun exchange(path: String, method: String, body: JsonElement?, etag: String?, audio: File?) = GatewayResponse(304)
        }
        val embeds = AtomicInteger()
        val recognizer = OnDeviceSpeakerRecognizer(scope, SpeakerModel(root.resolve("model"), { false }), PeopleEngine(root.resolve("people.json"), gateway),
            { true }, { .55f }, embedOverride = { java.lang.Thread.sleep(40); embeds.incrementAndGet(); FloatArray(256) { if (it == 0) 1f else 0f } }, modelReady = { true })
        val resets = AtomicInteger()
        val counting = object : RecentSpeakerRecognizer by recognizer {
            override val ready get() = recognizer.ready
            override val busy get() = recognizer.busy
            override fun reset() { resets.incrementAndGet(); recognizer.reset() }
        }
        val decoded = AtomicInteger()
        val worker = RecognitionAudioWorker(scope, { counting }, { true }, decoderFactory = { output ->
            object : PacketPcmDecoder {
                override fun packet(bytes: ByteArray, timestampUs: Long) { decoded.incrementAndGet(); output(ShortArray(2880) { 1000 }, 1, timestampUs) }
                override fun close() {}
            }
        })
        try {
            val clock = StreamClock()
            // Two bursts as the capture source stamps them; offered in a tight loop like the ingress worker does.
            val stamped = arrivals(2).flatMap { at ->
                val (start, _) = clock.batch(at, batchSamples)
                List(31) { i -> AmbientPacket(byteArrayOf(0x18), start + i * 60L, "lifelog-mono") }
            }
            stamped.take(341).forEach(worker::offer)
            withTimeout(30_000) { while (decoded.get() < 341) delay(10) }
            val afterFirst = embeds.get()
            stamped.drop(341).forEach(worker::offer)
            withTimeout(30_000) { while (decoded.get() < 682) delay(10) }
            delay(200)
            assertEquals("a whole burst fits the queue: no overflow reset", 0, resets.get())
            assertTrue("a 20 s burst is scored every 3 s, not once (got ${embeds.get() - afterFirst})", embeds.get() - afterFirst >= 6)
        } finally { worker.close(); recognizer.close(); scope.cancel() }
    }

    @Test fun ingressQueueHoldsMoreThanTwoBursts() {
        assertTrue(AmbientRuntime.QUEUE_SAMPLES >= 2 * 11 * batchSamples)
        assertTrue(RecognitionAudioWorker.QUEUE_PACKETS >= 2 * 341)
    }

    @Test fun theAutomaticModelDownloadBacksOffAfterFailedRounds() {
        assertEquals(0L, AmbientRuntime.modelRetryMs(0))
        assertEquals(10 * 60_000L, AmbientRuntime.modelRetryMs(1))
        assertEquals(20 * 60_000L, AmbientRuntime.modelRetryMs(2))
        assertEquals(6 * 3600_000L, AmbientRuntime.modelRetryMs(50))
    }

    @Test fun peopleSaysWhyRecognitionIsNotRunning() {
        assertEquals("Voice model not downloaded (Wi-Fi needed)", AmbientRuntime.modelNotice("missing", true))
        assertEquals("Voice model not downloaded", AmbientRuntime.modelNotice("missing", false))
        assertEquals("Downloading voice model 42%", AmbientRuntime.modelNotice("downloading 42%", true))
        assertEquals("Voice model download failed; retrying", AmbientRuntime.modelNotice("failed: Connect to Wi-Fi", true))
    }
}
