package io.lampyr.glasses

import android.content.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.ambient.*
import io.lampyr.glasses.data.*
import java.io.File
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveDataAuditTest {
    @get:Rule val temp = TemporaryFolder()
    private val epoch = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private class Gateway : GatewayClient("https://fixture.example", "test") {
        val rows = mutableMapOf<String, JsonElement>()
        val writes = mutableListOf<Pair<String, JsonElement>>()
        val gets = mutableListOf<String>()
        var offline = false
        var streaming: suspend (String, suspend (JsonElement) -> Unit) -> Unit = { _, emit -> emit(obj("phase" to str("done"), "text" to str(""))) }
        override suspend fun get(path: String): JsonElement { gets += path; if (offline) throw GatewayFailure("Offline"); return rows[path] ?: JsonArray(emptyList()) }
        override suspend fun write(path: String, body: JsonElement, method: String): JsonElement { if (offline) throw GatewayFailure("Offline"); writes += path to body; return rows[path] ?: obj("ok" to JsonPrimitive(true)) }
        override suspend fun stream(path: String, body: ByteArray, contentType: String, accept: suspend (JsonElement) -> Unit) = streaming(path, accept)
    }
    private fun TestScope.store(gateway: Gateway, background: Boolean = false) = LiveDataStore(context(), backgroundScope, gateway, { epoch }, { testScheduler.currentTime / 1000.0 }, background)
    private fun row(text: String) = gatewayJson.parseToJsonElement(text)

    @Test fun totalsPaginationLoadedSignalMergeAndIdDefense() = runTest {
        val g = Gateway(); val data = store(g)
        g.rows["/beeper/chats?limit=100"] = row("""[{"id":"c","name":"12345","network":"Instagram","unread":2,"last":{"sender":"@raw:server.tld","text":"hi @raw:server.tld"}}]""")
        g.rows["/beeper/summary"] = row("""{"unread":70,"unreadChats":35,"names":["Friends"]}""")
        g.rows["/beeper/chats/c/messages?page=true"] = row("""{"messages":[{"id":"2","sender":"12345","text":"hi @raw:server.tld","at":"2026-11-12T19:00:02Z"}],"nextBefore":"opaque+/="}""")
        g.rows["/beeper/chats/c/messages?page=true&before=opaque%2B%2F%3D"] = row("""{"messages":[{"id":"1","sender":"Alex","text":"old","at":"2026-11-12T19:00:01Z"},{"id":"2","sender":"Alex","text":"edited","at":"2026-11-12T19:00:02Z"}],"nextBefore":null}""")
        try {
            assertFalse(data.beeper.pageState("c").loaded)
            g.offline = true; assertFalse(data.beeper.refreshMessages("c").ok); assertFalse(data.beeper.pageState("c").loaded); g.offline = false
            assertTrue(data.beeper.refreshChats().ok); assertEquals(70, data.beeper.unreadTotal); assertEquals(35, data.beeper.unreadChats); assertEquals(70, data.home.beeper!!.unread)
            assertEquals("Chat", data.beeper.chats.single().name); assertEquals("Someone", data.beeper.chats.single().last!!.sender)
            data.beeper.refreshMessages("c"); assertTrue(data.beeper.pageState("c").loaded)
            assertEquals("hi @someone", data.beeper.messages("c").single().text)
            assertTrue(data.beeper.loadOlder("c").ok); assertEquals(listOf("1", "2"), data.beeper.messages("c").map { it.id })
            assertEquals("edited", data.beeper.messages("c").last().text); assertNull(data.beeper.pageState("c").nextBefore)
            data.beeper.refreshMessages("c"); assertEquals(listOf("1", "2"), data.beeper.messages("c").map { it.id }); assertNull(data.beeper.pageState("c").nextBefore)
        } finally { data.close() }
    }

    @Test fun phoneNotificationsOfflineFollowThePolicyNeverPopUpAndScrubRawTitles() = runTest {
        val g = Gateway(); val data = store(g); g.offline = true
        val popups = mutableListOf<Notification>(); data.notifications.listenPopups { popups += it }
        fun beeper(title: String, body: String, group: Boolean = false, conversation: String = "", sender: String = "", network: String = "") =
            NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = title, body = body, isGroup = group, conversationTitle = conversation, sender = sender, network = network)
        try {
            assertTrue(data.phoneNotification("a", beeper("123456", "hi @raw:server.tld")).ok)
            assertEquals("Message", data.notifications.items.single().title); assertEquals("hi @someone", data.notifications.items.single().body)
            assertEquals(.4, data.notifications.items.single().importance, .001); assertEquals("stream", data.notifications.items.single().route)
            data.phoneNotification("b", beeper("Friends", "lol", group = true, network = "Instagram"))
            data.phoneNotification("c", NoticeInput(source = "com.google.android.gm", app = "Gmail", title = "Sale", body = "newsletter 30% off"))
            data.phoneNotification("ha", NoticeInput(source = "io.homeassistant.companion.android", app = "Home Assistant", title = "Integration stale"))
            assertEquals(1, data.notifications.items.size)
            data.phoneNotification("personal", beeper("Friends", "Robin, can you bring the speaker?", group = true, conversation = "Friends", sender = "Alex"))
            val personal = data.notifications.items.first { it.id == "phone:personal" }
            assertEquals("Friends", personal.title); assertEquals("Alex: Robin, can you bring the speaker?", personal.body); assertEquals("stream", personal.route)
            assertTrue("offline never pops up", popups.isEmpty())
            // Only Beeper and semantic cases reach the gateway; a denied app never leaves the phone.
            g.offline = false; g.rows["/notify/route"] = row("""{"route":"popup","importance":0.9,"reason":"asks the owner","by":"model","open":{"app":"beeper","target":"!c:beeper.local"}}""")
            data.phoneNotification("e", beeper("Friends", "Robin, dinner?", group = true, conversation = "Friends", sender = "Alex", network = "Instagram"))
            val sent = g.writes.last(); assertEquals("/notify/route", sent.first)
            assertTrue(sent.second.b("isGroup")); assertEquals("Instagram", sent.second.s("network")); assertEquals("beeper", sent.second.s("kind"))
            val e = data.notifications.items.first { it.id == "phone:e" }
            assertEquals("popup", e.route); assertEquals(OpenTarget("beeper", mapOf("target" to "!c:beeper.local")), e.open); assertEquals(listOf("phone:e"), popups.map { it.id })
            val writes = g.writes.size
            data.phoneNotification("w", NoticeInput(source = "com.whatsapp", app = "WhatsApp", title = "Sam", body = "hi"))
            assertEquals(writes, g.writes.size)
        } finally { data.close() }
    }

    // T3 notifications come from the gateway notifier (events); the poll counts requests and offline machines only.
    @Test fun t3PollCountsRequestsAndOfflineMachinesWithoutMakingNotifications() = runTest {
        val g = Gateway(); val data = store(g)
        fun threads(status: String, revision: Int) { g.rows["/t3/threads"] = row("""{"machines":[{"machine":"desktop","label":"Desktop","online":false}],"threads":[{"machine":"desktop","id":"t","title":"Test thread","project":"Test","provider":"Codex","status":"$status","updatedAt":"2026-11-12T20:00:0${revision}Z"}]}""") }
        try {
            g.rows["/t3/watch"] = row("""[{"key":"desktop:t","machine":"desktop","id":"t","title":"Test thread"}]""")
            data.t3.watch("desktop", "t", "Test thread", true)
            assertTrue(data.t3.isWatched("desktop", "t"))
            threads("working", 0); data.t3.refreshThreads(); assertTrue(data.notifications.items.isEmpty())
            threads("approval", 1); data.t3.refreshThreads(); data.t3.refreshThreads()
            assertEquals(1, data.t3.needYouCount)
            threads("request", 2); data.t3.refreshThreads(); assertEquals(1, data.t3.needYouCount); assertEquals(1, data.home.t3!!.needYou)
            assertEquals(1, data.t3.offlineCount); assertFalse(data.t3.threads.single().machineOnline)
            threads("done", 3); data.t3.refreshThreads(); threads("working", 4); data.t3.refreshThreads(); threads("done", 3); data.t3.refreshThreads()
            assertTrue(data.notifications.items.isEmpty())
        } finally { data.close() }
    }

    @Test fun reminderArmingIsNotFiringAndCanBeListedAndCancelled() = runTest {
        val g = Gateway(); val data = store(g)
        g.rows["/reminders"] = row("""[{"id":"r","text":"Ask Alex","condition":{"type":"person","value":"alex"},"expiresAt":"2026-12-12T20:00:00Z"}]""")
        try {
            data.handleEvent(row("""{"type":"reminder","id":"r","text":"Ask Alex","state":"armed"}"""))
            assertTrue(data.notifications.items.isEmpty()); assertEquals("r", data.reminders.armed.single().id)
            g.rows["/reminders"] = JsonArray(emptyList()); assertTrue(data.reminders.cancel("r").ok); assertTrue(data.reminders.armed.isEmpty())
            assertTrue(g.writes.any { it.first == "/reminders/r" })
        } finally { data.close() }
    }

    @Test fun joyErrorsResetAndDictationThinksThenClearsListeningOnFailure() = runTest {
        val g = Gateway(); val data = store(g)
        g.streaming = { _, emit -> emit(row("""{"type":"error","message":"Offline"}""")) }
        try {
            data.joy.send("test"); runCurrent(); assertEquals("error", data.joy.snapshot.status)
            advanceTimeBy(1701); runCurrent(); assertEquals("idle", data.joy.snapshot.status)
            var pcm: ((ShortArray) -> Unit)? = null
            MicRegistry.glasses = object : MicSource {
                override val name = "test"
                override fun isAvailable() = true
                override fun start(onPcm: (ShortArray) -> Unit): Boolean { pcm = onPcm; return true }
                override fun stop() {}
            }
            g.streaming = { _, _ -> assertEquals("thinking", data.joy.snapshot.status); throw GatewayFailure("Offline") }
            val events = mutableListOf<DictationEvent>()
            val dictation = GatewayDictation(context(), g, backgroundScope) { data.joy.dictationPhase(it); Unit }
            data.joy.setListening(true); dictation.start("joy", events::add); dictation.start("joy", events::add); runCurrent()
            assertEquals("listening", data.joy.snapshot.status)
            pcm!!(ShortArray(4000)); dictation.stop(); runCurrent()
            assertEquals("idle", data.joy.snapshot.status); assertTrue(events.last() is DictationEvent.Error)
            dictation.cancel()
        } finally { MicRegistry.glasses = null; data.close() }
    }

    @Test fun turnBasedCallSurvivesSilenceWithoutSendingAnEmptyPrompt() = runTest {
        val g = Gateway(); var turns = 0
        g.rows["/assistant/call/status"] = obj("available" to JsonPrimitive(false))
        g.streaming = { path, emit -> if (path.startsWith("/voice")) { turns++; emit(row("""{"phase":"done","text":"","silence":true}""")) } }
        MicRegistry.glasses = object : MicSource {
            override val name = "test"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { onPcm(ShortArray(4000)); return true }
            override fun stop() {}
        }
        val data = LiveDataStore(context(), backgroundScope, g, { epoch }, { testScheduler.currentTime / 1000.0 }, false, { error("unavailable must skip socket") })
        try {
            data.joy.startVoiceCall(); runCurrent(); advanceTimeBy(12001); runCurrent()
            assertTrue(data.joy.call.active); assertEquals("turns", data.joy.call.mode); assertEquals(2, turns); assertTrue(g.writes.isEmpty())
        } finally { data.close(); MicRegistry.glasses = null }
    }

    @Test fun boundedCaptureSignalsCapOnceKeepsPcmAndMemosJoinWaveDaysAndShare() = runTest {
        var pcm: ((ShortArray) -> Unit)? = null; var stopped = 0; var capped = 0
        MicRegistry.glasses = object : MicSource {
            override val name = "test"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { pcm = onPcm; return true }
            override fun stop() { stopped++ }
        }
        try {
            val capture = PcmCapture(context(), "memo", maxBytes = 8)
            assertTrue(capture.start(onFull = { capped++ })); pcm!!(ShortArray(6) { 1000 }); pcm!!(ShortArray(6))
            val wav = capture.stop(); assertEquals(52, wav.size); assertEquals(1, capped); assertTrue(capture.full); assertTrue(stopped > 0)
            val directory = File(temp.root, "memos").apply { mkdirs() }; val file = File(directory, "memo.wav").apply { writeBytes(wav) }
            val memo = Memo("memo", "Test memo", "2 PM", 10, filePath = file.absolutePath, createdAt = "2026-11-12T14:00:00")
            SnapshotCache(File(temp.root, "gateway-cache")).put("memos", gatewayJson.encodeToJsonElement(listOf(memo)))
            assertEquals(wav.toList(),file.readBytes().toList())
            val cached = SnapshotCache(File(temp.root,"gateway-cache"))["memos"]!!
            assertEquals(file.absolutePath,GatewayMappings.decode<List<Memo>>(cached).single().filePath)
        } finally { MicRegistry.glasses = null }
    }

    @Test fun displayFormattingWeatherCalendarHealthAndReadOnlySettings() = runTest {
        val g = Gateway(); val data = store(g)
        try {
            val zone = ZoneId.of("America/Chicago")
            assertEquals("6:41 AM today", GatewayMappings.measuredAt("2026-11-12T12:41:00Z", epoch, zone))
            assertEquals("Yesterday 10:12 PM", GatewayMappings.measuredAt("2026-11-12T04:12:00Z", epoch, zone))
            assertEquals("Nov 9", GatewayMappings.measuredAt("2026-11-09T12:00:00Z", epoch, zone))
            val metrics = GatewayMappings.metrics(row("""[{"key":"hrv","name":"HRV","value":52,"unit":"ms","measuredAt":"2026-11-12T12:00:00Z","series":[]}]"""), epoch)
            assertEquals("52 ms", metrics.single().value); assertFalse(metrics.single().hasBreakdown)
            val home = GatewayMappings.home(row("""{"weather":null,"nextEvent":{"title":"Trip","start":"2026-11-12T06:00:00Z","allDay":true}}"""))
            assertNull(home.weather.tempF); assertEquals("—", home.weather.temperatureLabel); assertEquals("All day", home.nextEvent!!.displaySubtitle)
            val agenda = GatewayMappings.agenda(row("""[{"day":"TODAY","date":"2026-11-12","events":[{"id":"e","title":"Trip","start":"2026-11-12T06:00:00Z","allDay":true}]}]"""))
            assertEquals("All day", agenda.single().events.single().displaySubtitle)
            assertTrue(data.settings.snapshot.brightnessReadOnly); assertNull(data.settings.snapshot.glassesBrightness)
            assertEquals("Set brightness on the glasses", data.settings.update(SettingsSnapshot(brightness = 5)).message)
            assertTrue(g.writes.isEmpty())
        } finally { data.close() }
    }

    @Test fun memoCapStopsAutomaticallySavesFullRecordingAndNotifiesTheReason() = runTest {
        val g = Gateway(); val data = store(g)
        var pcm: ((ShortArray) -> Unit)? = null
        MicRegistry.glasses = object : MicSource {
            override val name = "test"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { pcm = onPcm; return true }
            override fun stop() {}
        }
        try {
            data.memos.toggle(); pcm!!(ShortArray(12 * 1024 * 1024)); runCurrent()
            withContext(Dispatchers.Default) {
                withTimeout(10000) { while (data.notifications.items.none { it.id.startsWith("memo-cap:") }) delay(1) }
            }
            assertFalse(data.memos.snapshot.recording)
            assertEquals("Recording limit reached · memo saved", data.memos.snapshot.stoppedReason)
            val memo = data.memos.snapshot.list.single()
            assertEquals(24L * 1024 * 1024 + 44, File(memo.filePath).length())
            assertTrue(data.notifications.items.any { it.body == data.memos.snapshot.stoppedReason })
        } finally { data.close(); MicRegistry.glasses = null }
    }

    @Test fun remoteSpotifyCommandsAndCurrentContextDoNotDependOnAListIndexAndProgressInterpolates() = runTest {
        val g = Gateway(); var clock = 0.0
        val data = LiveDataStore(context(), backgroundScope, g, { epoch }, { clock }, false)
        g.rows["/spotify/now"] = row("""{"source":"spotify","title":"Track","positionSec":50,"durationSec":60,"playing":true,"volume":60,"device":"Speaker"}""")
        g.rows["/spotify/home"] = row("""{"liked":{"uri":"spotify:collection","name":"Liked"},"current":{"source":"spotify","title":"Track"},"recent":[]}""")
        try {
            data.media.refresh(); assertEquals("Track", data.media.current!!.title)
            clock = 3.0; assertEquals(53.0, data.media.nowPlaying!!.positionSec, .001)
            for (action in listOf("toggle", "next", "previous", "volume")) assertTrue(data.media.control(action, 15.0).ok)
            assertEquals(listOf("pause", "next", "previous", "volume"), g.writes.map { it.second.s("action") })
            assertEquals(100.0, g.writes.last().second.n("value"), .001)
        } finally { data.close() }
    }

    @Test fun bluetoothBatteriesTrackBroadcastsPerDeviceAndForgetOnDisconnect() {
        // Requirement 16.4: the level broadcast is kept per device with no audio-route gate; -1 means unknown.
        val ctx = context(); val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")
        val battery = BluetoothBatteries(ctx, {})
        fun send(intent: Intent) { ctx.sendBroadcast(intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)); shadowOf(android.os.Looper.getMainLooper()).idle() }
        fun level(value: Int) = send(Intent(BluetoothBatteries.ACTION_BATTERY_LEVEL_CHANGED).putExtra(BluetoothBatteries.EXTRA_BATTERY_LEVEL, value))
        try {
            assertTrue(battery.levels.isEmpty()); level(73); assertEquals(.73, battery.levels.single().level!!, .001)
            level(-1); assertNull(battery.levels.single().level); level(45); assertEquals(.45, battery.levels.single().level!!, .001)
            send(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)); assertTrue(battery.levels.isEmpty())
        } finally { battery.close() }
    }

    @Test fun r2UsesGatewayForRemoteSpotifyAndRefreshesAtTrackEnd() = runTest {
        val g = Gateway(); var clock = 0.0
        g.rows["/spotify/now"] = row("""{"source":"spotify","title":"Track","positionSec":50,"durationSec":60,"playing":true,"volume":60,"device":"Speaker"}""")
        g.rows["/spotify/home"] = row("""{"liked":{"uri":"spotify:collection","name":"Liked"},"recent":[]}""")
        val data = LiveDataStore(context(), backgroundScope, g, { epoch }, { clock }, true)
        val ui = io.lampyr.glasses.ui.Ui(data, scope = backgroundScope, nowS = { clock })
        val controls = io.lampyr.glasses.input.StoreMediaControl(ui)
        try {
            data.media.refresh()
            controls.toggle(); runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5000) { while (g.writes.none { it.second.s("action") == "pause" }) delay(1) } }
            controls.direction("right"); runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5000) { while (g.writes.none { it.second.s("action") == "next" }) delay(1) } }
            controls.direction("up"); runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5000) { while (g.writes.none { it.second.s("action") == "volume" }) delay(1) } }
            assertEquals(10.0 / 15 * 100, g.writes.last { it.second.s("action") == "volume" }.second.n("value"), 1.0)
            val before = g.gets.count { it == "/spotify/now" }
            clock = 20.0; advanceTimeBy(1001); runCurrent()
            // The refresh crosses real Dispatchers.IO; wait on the real clock instead of asserting immediately.
            withContext(Dispatchers.Default) { withTimeout(5000) { while (g.gets.count { it == "/spotify/now" } <= before) delay(1) } }
        } finally { ui.close() }
    }
}
