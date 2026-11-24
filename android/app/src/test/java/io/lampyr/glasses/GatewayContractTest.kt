package io.lampyr.glasses

import io.lampyr.glasses.audio.PcmAudio
import io.lampyr.glasses.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

internal class FixtureGateway {
    val fixtures = gatewayJson.parseToJsonElement(javaClass.getResource("/gateway/responses.json")!!.readText()).jsonObject.toMutableMap()
    data class Sent(val method: String, val path: String, val query: String?, val body: String, val authorization: String?)
    val sent = java.util.Collections.synchronizedList(mutableListOf<Sent>())
    var status = 200
    var offline = false
    val client = GatewayClient("https://fixture.example", "synthetic-token", OkHttpClient.Builder().addInterceptor { chain ->
        if (offline) throw java.net.UnknownHostException("synthetic offline")
        val request = chain.request()
        val buffer = Buffer(); request.body?.writeTo(buffer)
        sent += Sent(request.method, request.url.encodedPath, request.url.encodedQuery, buffer.readUtf8(), request.header("Authorization"))
        val key = "${request.method} ${request.url.encodedPath}"
        val fixture = fixtures[key] ?: if (request.method == "GET") error("Missing synthetic fixture: $key") else obj("ok" to JsonPrimitive(true))
        val body = if (fixture is JsonPrimitive && fixture.isString) fixture.content else fixture.toString()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    fun row(path: String) = fixtures.getValue("GET $path")
}

class GatewayContractTest {
    @Test fun callSocketUsesConfiguredApiBaseAndHeaderOnlyBearer() {
        var captured: okhttp3.Request? = null
        val done = java.util.concurrent.CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("".toResponseBody()).build()
        }.build()
        val gateway = GatewayClient("https://fixture.example/custom/glasses/v1/", "synthetic-token", http)
        val socket = gateway.callSocketFactory().connect(object : io.lampyr.glasses.audio.JoyCallSocket.Listener {
            override fun opened() { done.countDown() }
            override fun text(value: String) {}
            override fun binary(value: ByteArray) {}
            override fun ended(reason: String) { done.countDown() }
        })
        try {
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val request = captured!!
            assertEquals("/custom/glasses/v1/assistant/call", request.url.encodedPath)
            assertEquals("https", request.url.scheme) // OkHttp normalizes wss to https for its handshake.
            assertNull(request.url.query); assertEquals("", request.url.username)
            assertEquals("Bearer synthetic-token", request.header("Authorization"))
        } finally { socket.close(); http.dispatcher.executorService.shutdown() }
    }
    private val f = FixtureGateway()
    @Test fun homeAndBeeperShapes() {
        val home = GatewayMappings.home(f.row("/home"))
        assertEquals(3, home.beeper!!.unread); assertEquals(1, home.t3!!.needYou); assertEquals(66, home.weather.tempF)
        val chats = GatewayMappings.chats(f.row("/beeper/chats"))
        assertEquals("Please approve ✅ ❌", chats.single().last!!.text)
        val messages = GatewayMappings.decode<List<Message>>(f.row("/beeper/chats/chat%2Fa/messages"))
        assertTrue(messages.single().approval); assertTrue(messages.single().reactions.first().mine)
        val unavailable = GatewayMappings.home(gatewayJson.parseToJsonElement("""{"weather":null,"nextEvent":null}"""))
        assertEquals("Weather unavailable", unavailable.weather.condition)
    }
    @Test fun t3ListDetailSettledUsageProjectsAndEnvironmentsShapes() {
        val now = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()
        val threads = f.row("/t3/threads").a("threads").map { GatewayMappings.thread(it, now, 100.0) }
        assertEquals(12L, threads.first().ageSec); assertEquals(30.0, threads.first().step!!.elapsedSec, .001)
        assertNotEquals(threads.first().key, threads.last().key)
        val detail = GatewayMappings.detail(f.row("/t3/threads/desktop/same"), now, 100.0)
        assertEquals(5.0, detail.working!!.now!!.quietSec!!, .001); assertEquals(3, detail.working!!.recent.single().durSec)
        assertEquals("old", GatewayMappings.decode<List<io.lampyr.glasses.data.Thread>>(f.row("/t3/settled")).single().id)
        assertEquals(42, GatewayMappings.decode<List<Usage>>(f.row("/t3/usage")).single().windows.first().usedPercent)
        assertEquals("project1", GatewayMappings.decode<List<Project>>(f.row("/t3/projects")).single().id)
        assertEquals("", GatewayMappings.decode<List<Environment>>(f.row("/t3/environments")).last().lastSeen)
    }
    @Test fun requestVariants() {
        for (kind in listOf("choice", "multi", "text", "approval")) {
            val row = obj("id" to str("req"), "kind" to str(kind), "questions" to JsonArray(listOf(obj("id" to str("q1"), "question" to str("Example?"), "multi" to JsonPrimitive(kind == "multi"), "options" to JsonArray(listOf(obj("label" to str("Option"), "description" to str("Description"))))))), "approval" to if (kind == "approval") obj("kind" to str("command"), "command" to str("echo synthetic"), "reason" to str("Example reason")) else JsonNull)
            val request = GatewayMappings.decode<io.lampyr.glasses.data.Request>(row)
            assertEquals(kind, request.kind)
            assertEquals(kind == "multi", request.questions.single().multi)
        }
    }
    @Test fun spotifyAbsAndYoutubeShapes() {
        assertEquals(60, GatewayMappings.decode<NowPlaying>(f.row("/spotify/now")).volume)
        assertEquals("spotify:collection", GatewayMappings.decode<Playlist>(f.row("/spotify/home").jsonObject.getValue("liked")).uri)
        assertTrue(GatewayMappings.decode<List<Device>>(f.row("/spotify/devices")).single().active)
        assertEquals(1800.0, GatewayMappings.decode<List<Book>>(f.row("/abs/recent")).single().currentTimeSec, .001)
        assertEquals(2, f.fixtures.getValue("POST /abs/play/book1").a("tracks").size)
        assertEquals("picks", f.row("/youtube/lists").jsonArray.single().s("key"))
        assertEquals(180, GatewayMappings.decode<List<Video>>(f.row("/youtube/list/picks")).single().durationSec)
    }
    @Test fun calendarHealthHaReportDocsPeopleShapes() {
        assertEquals("Office", GatewayMappings.decode<List<CalendarDay>>(f.row("/calendar/agenda")).single().events.single().location)
        val health = GatewayMappings.decode<List<Metric>>(f.row("/health/overview"))
        assertEquals("180.5", health.first().value); assertEquals("80.5", health.first().breakdown.single().value); assertEquals("—", health.last().value)
        assertEquals(3, GatewayMappings.decode<HomeAssistantPanel>(f.row("/ha/panel")).printers.single().layer)
        assertEquals("Synthetic report", GatewayMappings.decode<ReportSnapshot>(f.row("/report/latest")).cards.single().body)
        assertEquals("doc1", GatewayMappings.decode<List<Document>>(f.row("/docs")).single().id)
        assertEquals("Example document", GatewayMappings.decode<Document>(f.row("/docs/doc1")).text)
        assertEquals("", GatewayMappings.decode<PeopleSnapshot>(f.row("/people/recent")).recent.single().seenAt)
    }
    @Test fun bearerAndAuthErrorsAndOfflineMapping() = runBlocking {
        f.client.get("/home")
        assertEquals("Bearer synthetic-token", f.sent.single().authorization)
        for (code in listOf(401, 403)) {
            f.status = code
            try { f.client.get("/home"); fail("Expected auth failure") } catch (e: GatewayFailure) { assertEquals("Check token", e.display) }
        }
        f.offline = true
        try { f.client.get("/home"); fail("Expected offline failure") } catch (e: GatewayFailure) { assertEquals("Offline · cached data shown", e.display) }
    }
    @Test fun ndjsonSplitLinesCrLfEmptyAndFinalLine() {
        val rows = mutableListOf<JsonElement>()
        val parser = NdjsonParser(rows::add)
        val wire = "\n{\"type\":\"status\",\"status\":\"thinking\"}\r\n{\"type\":\"result\",\"text\":\"✅ synthetic\"}"
        for (chunk in wire.chunked(3)) parser.feed(chunk)
        parser.finish()
        assertEquals(listOf("status", "result"), rows.map { it.s("type") }); assertEquals("✅ synthetic", rows.last().s("text"))
    }
    @Test fun actualStreamingReaderHandlesBothEndpoints() = runBlocking {
        val joy = mutableListOf<String>()
        f.client.stream("/assistant/chat", "{}".toByteArray(), "application/json") { joy += it.s("type") }
        assertEquals(listOf("session", "status", "status", "session", "delta", "result"), joy)
        val phases = mutableListOf<String>()
        f.client.stream("/voice/dictate?target=memo", PcmAudio.wav(byteArrayOf(0, 0)), "audio/wav") { phases += it.s("phase") }
        assertEquals(listOf("transcribing", "enhancing", "done"), phases)
    }
    @Test fun eventCursorAdvancesAfterAcceptanceAndUsesSequenceNotEntityId() = runBlocking {
        val cursor = EventCursor("7")
        val seq = mutableListOf<Int>()
        cursor.consume(f.row("/events")) { seq += it.n("seq").toInt(); assertEquals("7", cursor.value) }
        assertEquals(listOf(8, 9), seq); assertEquals("9", cursor.value)
        try { cursor.consume(obj("cursor" to str("10"), "events" to JsonArray(listOf(obj())))) { error("simulate failed action") } } catch (_: IllegalStateException) { }
        assertEquals("9", cursor.value)
    }
    @Test fun eventsLoopCarriesReturnedCursorIntoNextRequest() = runBlocking {
        val cursor = EventCursor("7")
        val worker = launch {
            f.client.events(cursor, {}, {})
        }
        withTimeout(5000) { while (f.sent.size < 2) delay(10) }
        worker.cancelAndJoin()
        assertTrue(f.sent[0].query!!.contains("since=7")); assertTrue(f.sent[1].query!!.contains("since=9")); assertTrue(f.sent[1].query!!.contains("wait=25"))
    }
    @Test fun joyStatusAndAwayRules() {
        val statuses = listOf("idle", "listening", "thinking", "working", "searching", "remembering", "saving", "speaking", "waiting", "delegating", "error")
        statuses.forEach { assertEquals(it, GatewayMappings.joyStatus(it)) }
        assertEquals("thinking", GatewayMappings.joyStatus("unknown"))
        assertFalse(GatewayMappings.notifyJoy("joy")); assertFalse(GatewayMappings.notifyJoy("call")); assertTrue(GatewayMappings.notifyJoy("home"))
    }
    @Test fun wavHeaderAndRms() {
        val wav = PcmAudio.wav(byteArrayOf(0, 0, -1, 127))
        val h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4))); assertEquals(40, h.getInt(4)); assertEquals(16000, h.getInt(24)); assertEquals(32000, h.getInt(28)); assertEquals(4, h.getInt(40))
        assertEquals(0f, PcmAudio.level(shortArrayOf()), 0f)
        assertEquals(.5f, PcmAudio.level(shortArrayOf(16384, -16384)), .0001f)
        assertEquals(1f, PcmAudio.level(shortArrayOf(-32768)), .0001f)
    }
    @Test fun sendModesAndApprovalToast() {
        assertEquals("queue", GatewayMappings.sendMode("working", "send")); assertEquals("queue", GatewayMappings.sendMode("waiting", "queue")); assertEquals("steer", GatewayMappings.sendMode("working", "steer"))
        assertEquals("send", GatewayMappings.sendMode("settled", "queue")); assertEquals("send", GatewayMappings.sendMode("done", "steer"))
        assertEquals("Approved · Hermes notified", GatewayMappings.approvalToast(true, "✅", false))
        assertEquals("Cancelled · Hermes notified", GatewayMappings.approvalToast(true, "❌", false))
        assertEquals("Reaction removed", GatewayMappings.approvalToast(true, "✅", true)); assertEquals("Reacted", GatewayMappings.approvalToast(false, "✅", false))
    }
}
