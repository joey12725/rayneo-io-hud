package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.*
import java.io.File
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleEngineTest {
    private lateinit var root: File
    private var now = 1_800_000_000_000L
    private val calls = mutableListOf<Triple<String, JsonElement?, String?>>()
    private var code = 200
    private var voiceReply = ""
    private var offline = false
    private var respond: (suspend (String, JsonElement?) -> GatewayResponse)? = null
    private val fake = object : GatewayClient("https://fake.invalid", "synthetic") {
        override suspend fun exchange(path: String, method: String, body: JsonElement?, etag: String?, audio: File?): GatewayResponse {
            calls += Triple(path, body, etag)
            if (offline) error("synthetic offline")
            respond?.let { return it(path,body) }
            return if (path == "/people/voices") GatewayResponse(code, voiceReply, "\"v1\"")
                else GatewayResponse(code, "{\"ok\":true,\"muted\":true}")
        }
    }
    @Before fun before() { root = Files.createTempDirectory("people-test").toFile(); Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain(); root.deleteRecursively() }
    private fun voice(id: String = "person-one", muted: Boolean = false) = VoicePerson(id, "Synthetic $id", muted = muted,
        blurb = "Synthetic notes", profiles = listOf(VoiceProfile("profile-$id", List(256) { if (it == 0) 1f else 0f })))
    private fun wire(people: List<VoicePerson>) = obj("model" to str("wespeaker-voxceleb-resnet34-LM"), "dim" to JsonPrimitive(256),
        "updatedAt" to str("2026-11-01T00:00:00Z"), "people" to JsonArray(people.map { p -> obj(
            "id" to str(p.id), "name" to str(p.name), "me" to JsonPrimitive(p.me), "muted" to JsonPrimitive(p.muted),
            "status" to str(p.status), "blurb" to str(p.blurb), "profiles" to JsonArray(p.profiles.map { profile ->
                val bytes = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply { profile.embedding.forEach { putFloat(it) } }.array()
                obj("id" to str(profile.id), "embedding" to str(Base64.getEncoder().encodeToString(bytes)))
            })) })) .toString()
    private fun engine() = PeopleEngine(root.resolve("people.json"), fake, { now })
    @Test fun voiceParsingEtag304AndPersistenceRetainNotesOffline() = runTest {
        voiceReply = wire(listOf(voice(), voice("unlinked").copy(status = "unlinked", handle = null)))
        val e = engine(); e.sync()
        assertEquals(256, e.voices.first().profiles.single().embedding.size)
        assertEquals(1f, e.voices.first().profiles.single().embedding[0])
        assertEquals("", e.voices.last().blurb)
        assertEquals(e.voices, engine().voices)
        e.sync(); assertEquals(1, calls.size)
        now += 1800_000; code = 304; e.sync()
        assertEquals("\"v1\"", calls.last().third)
        assertEquals(2, e.voices.size)
        offline = true
        assertEquals(e.voices, engine().voices)
    }
    @Test fun malformedAndNonfiniteProfilesAreIgnored() {
        val bad = obj("model" to str("wespeaker-voxceleb-resnet34-LM"), "dim" to JsonPrimitive(256), "people" to JsonArray(listOf(obj(
            "id" to str("p"), "name" to str("Synthetic"), "profiles" to JsonArray(listOf(obj("id" to str("bad"), "embedding" to str("nonsense"))))))))
        assertTrue(PeopleEngine.parse(bad).people.single().profiles.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PeopleEngine.parse(obj("dim" to JsonPrimitive(128))) }
        val nonfinite = voice().copy(profiles = listOf(VoiceProfile("nan", List(256) { Float.NaN })))
        assertTrue(PeopleEngine.parse(gatewayJson.parseToJsonElement(wire(listOf(nonfinite)))).people.single().profiles.isEmpty())
    }
    @Test fun heardDeduplicatesLocallyAndSurvivesRestartWithFreshLocation() = runTest {
        voiceReply = wire(listOf(voice())); val e = engine(); e.sync()
        e.lastFix = GeoFix("conversation", now - 599999, 42.0, -88.0, 8f, source = "fake", confidence = "fake")
        val m = RecognizedVoice(voice(), "profile-person-one", .8f)
        assertTrue(e.heard(m, now)); assertFalse(e.heard(m, now + 59_999))
        val snapshot = e.merge(PeopleSnapshot())
        assertEquals(1, snapshot.heard.size); assertEquals("Synthetic notes", snapshot.recent.single().notes)
        assertEquals("42.0,-88.0", snapshot.heard.single().where)
        val restored = engine(); assertEquals(snapshot, restored.merge(PeopleSnapshot()))
        now += 60_000; assertTrue(restored.heard(m, now))
        restored.flush()
        val events = calls.last().second!!.a("events")
        assertEquals(2, events.size)
        assertTrue(events.first().s("id").matches(Regex("[A-Za-z0-9_-]{8,80}")))
        assertFalse(events.last().jsonObject.containsKey("lat"))
        val count = calls.size; engine().flush(); assertEquals(count, calls.size)
        now += 600001; assertTrue(engine().merge(PeopleSnapshot()).heard.isEmpty())
    }
    @Test fun batchesAreBounded400DroppedAndRetryIsDurable() = runTest {
        val people = (0..204).map { voice("synthetic-$it") }
        voiceReply = wire(people); val e = engine(); e.sync()
        people.forEach { assertTrue(e.heard(RecognizedVoice(it, it.profiles.single().id, .75f), now)) }
        offline = true; e.flush(); val count = calls.size
        e.flush(); assertEquals(count, calls.size)
        offline = false; code = 400
        now += 5000; e.flush()
        assertEquals(1, calls.last().second!!.a("events").size)
        assertEquals(200, calls.filter { it.first == "/people/heard" }[1].second!!.a("events").size)
        code = 200; engine().flush(); assertEquals(5, calls.last().second!!.a("events").size)
        val after = calls.size; engine().flush(); assertEquals(after, calls.size)
    }
    @Test fun invalidHeardNeverSendsAndMutePersistsOptimisticallyUntilOnline() = runTest {
        voiceReply = wire(listOf(voice())); val e = engine(); e.sync()
        for (m in listOf(RecognizedVoice(voice().copy(me = true), "p", .9f), RecognizedVoice(voice().copy(muted = true), "p", .9f), RecognizedVoice(voice(), "p", Float.NaN))) {
            assertFalse(e.heard(m, now))
        }
        assertFalse(e.heard(RecognizedVoice(voice(), "p", .7f), now + 300001))
        offline = true
        assertTrue(e.mute("person-one", true).ok)
        assertTrue(e.voices.single().muted); assertTrue(engine().voices.single().muted)
        assertFalse(e.heard(RecognizedVoice(voice(), "p", .7f), now))
        offline = false
        e.flush()
        assertTrue(e.voices.single().muted)
        assertEquals(true, calls.last().second!!.b("muted"))
        e.mute("person-one", false)
        assertFalse(e.voices.single().muted)
    }
    @Test fun rejectedMutesRevertWithNoticeAndNeverBlockHeardUploads() = runTest {
        for (rejection in listOf(400,404,409,422,500)) {
            val file = root.resolve("people-$rejection.json")
            val e = PeopleEngine(file,fake,{now})
            voiceReply = wire(listOf(voice(),voice("mute-target"))); e.sync()
            offline = true; e.mute("mute-target",true); offline = false
            assertTrue(e.voices.last().muted)
            assertTrue(e.heard(RecognizedVoice(voice(),"profile-person-one",.8f),now))
            var uploaded = false
            respond = { path,_ ->
                if(path.endsWith("/mute")) GatewayResponse(rejection)
                else { uploaded = true; GatewayResponse(200) }
            }
            e.flush(); assertTrue("Mute $rejection blocked heard",uploaded)
            if(rejection < 500) {
                assertFalse(e.voices.last().muted); assertTrue(e.lastError!!.contains("restored"))
                val saved = privateJson.parseToJsonElement(file.readText())
                assertTrue(saved.jsonObject["mutes"]!!.jsonObject.isEmpty())
            } else assertTrue(e.voices.last().muted)
            respond = null
        }
    }
    @Test fun directRejectedMuteReturnsVisibleFailure() = runTest {
        voiceReply = wire(listOf(voice())); val e = engine(); e.sync()
        code = 422
        val result = e.mute("person-one",true)
        assertFalse(result.ok); assertTrue(result.message!!.contains("restored")); assertFalse(e.voices.single().muted)
    }
    @Test fun blockedSyncMuteAndHeardHttpDoNotHoldStateLockOrRestampMatches() = runTest {
        voiceReply = wire(listOf(voice(),voice("other"))); val e = engine(); e.sync()
        for (blocked in listOf("/people/voices","/people/other/mute","/people/heard")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            respond = { path,_ ->
                if(path==blocked) { entered.complete(Unit); release.await() }
                if(path=="/people/voices") GatewayResponse(200,voiceReply) else GatewayResponse(200)
            }
            val operation = launch {
                when(blocked) {
                    "/people/voices" -> e.sync(true)
                    "/people/other/mute" -> e.mute("other",true)
                    else -> e.flush()
                }
            }
            entered.await()
            val at = now; now += 60_000
            // Completes while HTTP is still waiting. The event retains its supplied match timestamp.
            withContext(Dispatchers.Default) { withTimeout(5000) { assertTrue(e.heard(RecognizedVoice(voice(),"profile-person-one",.8f),at)) } }
            val latest = privateJson.parseToJsonElement(root.resolve("people.json").readText()).a("events").last()
            assertEquals(Instant.ofEpochMilli(at).toString(),latest.s("at"))
            release.complete(Unit); operation.join(); now += 60_000
        }
    }
    @Test fun hangingMuteDoesNotDelayHeardBatch() = runTest {
        voiceReply=wire(listOf(voice(),voice("other"))); val e=engine(); e.sync()
        offline=true; e.mute("other",true); offline=false
        e.heard(RecognizedVoice(voice(),"profile-person-one",.8f),now)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val uploaded=CompletableDeferred<Unit>()
        respond = { path,_ ->
            if(path.endsWith("/mute")) { entered.complete(Unit); release.await() }
            else uploaded.complete(Unit)
            GatewayResponse(200)
        }
        val flushing=launch { e.flush() }; entered.await()
        withContext(Dispatchers.Default) { withTimeout(5000) { uploaded.await() } }
        assertFalse(flushing.isCompleted)
        release.complete(Unit); flushing.join()
    }
    @Test fun oneBadHeardEventLosesOnlyItselfAndGoodHalvesStayAcknowledged() = runTest {
        val people=(0..4).map { voice("person-$it") }; voiceReply=wire(people); val e=engine(); e.sync()
        people.forEach { e.heard(RecognizedVoice(it,it.profiles.single().id,.8f),now) }
        val accepted=mutableSetOf<String>(); val sizes=mutableListOf<Int>()
        respond = { _,body ->
            val events=body!!.a("events"); sizes += events.size
            if(events.any { it.s("personId")=="person-2" }) GatewayResponse(400)
            else { accepted += events.map { it.s("personId") }; GatewayResponse(200) }
        }
        e.flush()
        assertEquals(setOf("person-0","person-1","person-3","person-4"),accepted)
        assertEquals(5,sizes.first()); assertTrue(1 in sizes)
        val count=calls.size; engine().flush(); assertEquals(count,calls.size)
    }
    @Test fun unreadableOutboxIsQuarantinedAndLoggedOnce() {
        val file=root.resolve("people.json"); val original="{\"mutes\":{\"person-one\":true},\"pending\":[\"unsynced-event\"],broken"
        file.writeText(original); val logs=mutableListOf<String>()
        repeat(2) { PeopleEngine(file,fake,{now},diagnostic={ logs+=it }) }
        assertEquals(1,logs.size)
        val aside=root.listFiles()!!.single { it.name.startsWith("people.json.unreadable-") }
        assertEquals(original,aside.readText())
    }
    @Test fun retentionPrunesEventsAndDanglingPendingIdsEvenWithoutNewMatches() = runTest {
        voiceReply=wire(listOf(voice()));val e=engine();e.sync()
        e.heard(RecognizedVoice(voice(),"profile-person-one",.8f),now)
        val file=root.resolve("people.json"); val json=privateJson.parseToJsonElement(file.readText()).jsonObject
        file.writeText(JsonObject(json + ("pending" to JsonArray(json.a("pending") + JsonPrimitive("missing-event")))).toString())
        now+=31L*86400_000; val restored=engine(); val count=calls.size; restored.flush()
        val saved=privateJson.parseToJsonElement(file.readText())
        assertTrue(saved.a("events").isEmpty());assertTrue(saved.a("pending").isEmpty());assertEquals(count,calls.size)
    }
    @Test fun mutedProfilesWithoutHeardEventsStillHaveUndoCards()=runTest {
        voiceReply=wire(listOf(voice("muted",true)));val e=engine();e.sync()
        val snapshot=e.merge(PeopleSnapshot())
        assertTrue(snapshot.heard.isEmpty());assertEquals("muted",snapshot.recent.single().id);assertTrue(snapshot.recent.single().muted)
    }

    @Test fun staleVoiceRefreshCannotUndoAnAcknowledgedMute()=runTest {
        voiceReply=wire(listOf(voice()));val e=engine();e.sync()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        respond = { path,_ ->
            if(path=="/people/voices") { entered.complete(Unit);release.await();GatewayResponse(200,voiceReply) }
            else GatewayResponse(200)
        }
        val syncing=launch { e.sync(true) };entered.await()
        assertTrue(e.mute("person-one",true).ok)
        release.complete(Unit);syncing.join();assertTrue(e.voices.single().muted)
    }

}
