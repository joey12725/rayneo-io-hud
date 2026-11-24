package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import java.io.File
import java.time.Instant
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveDataStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private val epoch = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()
    private fun TestScope.store(f: FixtureGateway, ctx: Context = context()): LiveDataStore = LiveDataStore(ctx, backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)

    @Test fun liveCallStatusFallbackCellularPriorityAndServiceTeardown() = runTest {
        val f = FixtureGateway()
        f.fixtures["GET /assistant/call/status"] = obj("available" to JsonPrimitive(true))
        val socket = JoyCallSessionTest.Socket()
        val audio = JoyCallSessionTest.Audio()
        val exclusion = JoyCallSessionTest.Exclusion()
        val data = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, false,
            { update -> JoyCallSession(socket, audio, exclusion, { testScheduler.currentTime }, update) })
        try {
            assertTrue(data.joy.startVoiceCall().ok)
            withTimeout(5000) { while (data.joy.call.mode == "connecting") { delay(10) } }
            assertEquals("/assistant/call/status", f.sent.last().path)
            socket.listener.opened(); socket.event("ready"); runCurrent()
            socket.event("state", "value", "joy_speaking"); runCurrent()
            assertEquals("speaking", data.joy.snapshot.status)
            data.handleEvent(obj("type" to str("joy_reply"), "text" to str("Late chat reply"), "sessionId" to str("old-session")))
            advanceTimeBy(1200); runCurrent()
            assertEquals("speaking", data.joy.snapshot.status) // A chat idle timer cannot replace a live bridge state.
            assertFalse(data.joy.newConversation().ok)
            data.calls.publish(io.lampyr.glasses.calls.CallsSnapshot(calls = listOf(io.lampyr.glasses.calls.PhoneCall("phone", io.lampyr.glasses.calls.CallPhase.Ringing))))
            runCurrent()
            assertFalse(data.joy.call.active); assertEquals("phone call", data.joy.call.endedReason)
            assertTrue(audio.closed); assertTrue(socket.closed)
            assertFalse(data.joy.startVoiceCall().ok)
        } finally { data.close() }
        // Unavailable preflight selects turns before attempting any WebSocket upgrade.
        f.fixtures["GET /assistant/call/status"] = obj("available" to JsonPrimitive(false))
        var connected = false
        val fallback = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, false,
            { _ -> connected = true; error("unavailable must skip socket") })
        try {
            fallback.joy.startVoiceCall()
            withTimeout(5000) { while (fallback.joy.call.mode == "connecting") delay(10) }
            assertEquals("turns", fallback.joy.call.mode); assertFalse(connected)
            fallback.close(); assertEquals("service destroyed", fallback.joy.call.endedReason)
        } finally { fallback.close() }
    }
    @Test fun callScreenMuteAndRemoteEndReturnToJoy() = runTest {
        val f = FixtureGateway(); f.fixtures["GET /assistant/call/status"] = obj("available" to JsonPrimitive(true))
        val socket = JoyCallSessionTest.Socket(); val audio = JoyCallSessionTest.Audio()
        val data = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, false,
            { update -> JoyCallSession(socket, audio, JoyCallSessionTest.Exclusion(), { testScheduler.currentTime }, update) })
        val ui = io.lampyr.glasses.ui.Ui(data, scope = backgroundScope, nowS = { testScheduler.currentTime / 1000.0 })
        try {
            ui.push(io.lampyr.glasses.ui.screens.Joy(ui)); ui.push(io.lampyr.glasses.ui.screens.JoyCall(ui)); runCurrent()
            withTimeout(5000) { while (data.joy.call.mode == "connecting") delay(10) }
            socket.listener.opened(); socket.event("ready"); runCurrent()
            ui.top.key("act"); runCurrent(); assertTrue(data.joy.call.muted)
            socket.event("hangup", "reason", "silence"); runCurrent()
            assertEquals("joy", ui.top.kind); assertEquals("silence", data.joy.call.endedReason)
            assertFalse(data.joy.call.active); assertTrue(audio.closed)
        } finally { ui.close() }
    }
    @Test fun youtubeUsesOneFixedListWithoutFetchingAChooser() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            // Before /youtube/lists has loaded, the gateway's default list ("picks", shown as "Picks") is used.
            assertEquals(listOf("Picks"), data.media.youtubeLists)
            assertTrue(data.media.refreshVideos("Picks").ok)
            val request = f.sent.single()
            assertEquals("/youtube/list/picks", request.path); assertEquals("limit=25", request.query)
            assertEquals("Example channel", data.media.videos("Picks").single().channel)
        } finally { data.close() }
    }
    @Test fun everyRepositoryLoadsRealShapesAndKeepsCacheOfflineAndAcrossRestart() = runTest {
        val f = FixtureGateway(); val ctx = context(); val data = store(f, ctx)
        try {
            val results = listOf(data.refreshHome(), data.beeper.refreshChats(), data.beeper.refreshMessages("chat/a"), data.t3.refreshThreads(), data.t3.refreshSettled(), data.t3.refreshThread("desktop", "same"), data.t3.refreshUsage(), data.t3.refreshProjects(), data.t3.refreshEnvironments(), data.media.refresh(), data.media.refreshVideos("Picks"), data.calendar.refresh(), data.health.refresh(), data.ha.refresh(), data.docs.refresh(), data.docs.refreshDocument("doc1"), data.people.refresh(), data.report.refresh())
            assertTrue(results.toString(), results.all { it.ok })
            assertEquals(3, data.home.beeper!!.unread); assertEquals("preview-chat/a", data.beeper.chats.single().last!!.id)
            assertEquals(2, data.t3.threads.size); assertEquals(30.0, data.t3.detail("desktop", "same")!!.working!!.now!!.elapsedSec, .001)
            assertEquals(9, data.media.nowPlaying!!.volume); assertEquals(2, data.media.playlists.size); assertEquals(1, data.media.books.size); assertEquals(1, data.media.videos("Picks").size)
            assertEquals("180.5 lb", data.health.metrics.first().value); assertEquals("Example document", data.docs.documents.single().text)
            f.offline = true
            assertFalse(data.refreshHome().ok); assertEquals(3, data.home.beeper!!.unread)
            data.close()
            val restored = store(f, ctx)
            try { assertEquals(data.home, restored.home); assertEquals("Example document", restored.docs.documents.single().text); assertEquals(2, restored.t3.threads.size) } finally { restored.close() }
        } finally { data.close() }
    }
    @Test fun beeperWritesReplyTogglesOwnReactionAndApprovalToast() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            data.beeper.refreshMessages("chat/a")
            assertTrue(data.beeper.send("chat/a", "Synthetic reply", "m1").ok)
            val message = f.sent.first { it.method == "POST" && it.path.endsWith("/messages") }
            assertEquals("m1", gatewayJson.parseToJsonElement(message.body).s("replyTo"))
            val removed = data.beeper.react("chat/a", "m1", "✅")
            assertEquals("Reaction removed", removed.message)
            assertTrue(f.sent.any { it.method == "DELETE" && it.query == "key=%E2%9C%85" })
            assertEquals("Cancelled · Hermes notified", data.beeper.react("chat/a", "m1", "❌").message)
            assertTrue(data.beeper.markRead("chat/a").ok)
        } finally { data.close() }
    }
    @Test fun t3WritesAreMachineScopedAndNormalizeModesAndAnswers() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            data.t3.refreshThread("desktop", "same")
            assertTrue(data.t3.send("desktop", "same", "Queued").ok)
            assertEquals("queue", gatewayJson.parseToJsonElement(f.sent.last { it.path.endsWith("/messages") }.body).s("mode"))
            data.t3.send("desktop", "same", "Steered", "steer")
            assertEquals("steer", gatewayJson.parseToJsonElement(f.sent.last { it.path.endsWith("/messages") }.body).s("mode"))
            val detail = f.row("/t3/threads/desktop/same").jsonObject
            val thread = JsonObject(detail.getValue("thread").jsonObject + ("status" to str("settled")!!))
            val request = gatewayJson.parseToJsonElement("""{"id":"req1","kind":"multi","questions":[{"id":"a","question":"Single","options":[],"multi":false},{"id":"b","question":"Multi","options":[],"multi":true}],"approval":null}""")
            f.fixtures["GET /t3/threads/desktop/same"] = JsonObject(detail + mapOf("thread" to thread, "request" to request))
            data.t3.refreshThread("desktop", "same")
            // The gateway dispatches send and returns the newly un-settled projection on refresh.
            f.fixtures["GET /t3/threads/desktop/same"] = JsonObject(detail + ("request" to request))
            data.t3.send("desktop", "same", "Reopen", "queue")
            assertEquals("send", gatewayJson.parseToJsonElement(f.sent.last { it.path.endsWith("/messages") }.body).s("mode"))
            assertEquals("working", data.t3.detail("desktop", "same")!!.thread.status)
            data.t3.respond("desktop", "same", "req1", answers = mapOf("a" to listOf("one"), "b" to listOf("two", "three")))
            val answers = gatewayJson.parseToJsonElement(f.sent.last { it.path.endsWith("/respond") }.body).jsonObject.getValue("answers").jsonObject
            assertEquals(JsonPrimitive("one"), answers["a"]); assertEquals(2, answers.getValue("b").jsonArray.size)
            assertEquals("new1", data.t3.newThread("desktop", "project1", "Synthetic task").id)
            assertTrue(data.t3.resetUsage("desktop", "account1", "credit1").ok)
            data.t3.refreshThreads(); data.t3.visit("vps", "same")
            assertTrue(f.sent.any { it.path == "/t3/threads/vps/same/visit" })
        } finally { data.close() }
    }
    @Test fun joyKeepsPreviousTextUpdatesPromptRetainsFinalSessionAndNotifiesOnlyAway() = runTest {
        val f = FixtureGateway(); val data = store(f)
        val observed = mutableListOf<String>()
        data.addChangeListener { observed += data.joy.snapshot.status }
        try {
            data.onScreenChanged("joy")
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"joy_reply","id":"old","seq":1,"text":"Previous reply","sessionId":"old"}"""))
            data.joy.send("Transcribed prompt")
            assertEquals("Previous reply", data.joy.snapshot.text); assertEquals("Transcribed prompt", data.joy.snapshot.prompt)
            data.onScreenChanged("home")
            // Cache writes cross Dispatchers.IO: virtual time can expire before disk work completes.
            withContext(Dispatchers.Default) {
                withTimeout(5000) { while (data.notifications.items.isEmpty()) { testScheduler.runCurrent(); delay(1) } }
            }
            assertEquals("compressed2", data.joy.snapshot.sessionId)
            assertTrue(observed.containsAll(listOf("thinking", "searching", "speaking")))
            assertEquals(.85, data.notifications.items.single().importance, .001); assertEquals("joy", data.notifications.items.single().open!!.screen)
            // Same turn arrives once over NDJSON and once over the durable events channel.
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"joy_reply","id":"reply1","seq":2,"text":"Synthetic reply","sessionId":"compressed2"}"""))
            assertEquals(1, data.notifications.items.size)
            // A later turn may legitimately repeat the same words in the same session.
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"joy_reply","id":"reply2","seq":3,"text":"Synthetic reply","sessionId":"compressed2"}"""))
            assertEquals(2, data.notifications.items.size)
        } finally { data.close() }
    }
    @Test fun eventActionsDocsRemindersAndNotificationDismissalPersistWithoutInterruptions() = runTest {
        val f = FixtureGateway(); val ctx = context(); val data = store(f, ctx)
        val opened = mutableListOf<String>(); data.onOpen = { opened += it.screen }
        try {
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"doc","id":"doc1","seq":1,"title":"Synthetic doc"}"""))
            assertEquals("Example document", data.docs.documents.single().text)
            assertEquals(.25, data.notifications.items.single().importance, .001)
            assertTrue(opened.isEmpty())
            data.phoneNotification("phone1", NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = "Example", body = "Personal message"))
            assertEquals(2, data.notifications.items.size); assertTrue(opened.isEmpty())
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"open","screen":"joy","args":{}}"""))
            assertEquals(listOf("joy"), opened)
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"reminder","id":"reminder1","seq":2,"text":"Example reminder"}"""))
            data.notifications.dismiss("doc:doc1"); data.close()
            val restored = store(f, ctx)
            try {
                assertEquals(1, restored.notifications.items.size)
                restored.handleEvent(gatewayJson.parseToJsonElement("""{"type":"doc","id":"doc1","seq":3,"title":"Synthetic doc"}"""))
                assertEquals(1, restored.notifications.items.size)
                restored.notifications.clear(); assertTrue(restored.notifications.items.isEmpty())
            } finally { restored.close() }
        } finally { data.close() }
    }
    @Test fun dictationUsesGlassesMicStreamsPhasesAndEncodesActualPcm() = runTest {
        val f = FixtureGateway()
        val fakeMic = object : MicSource {
            override val name = "Synthetic glasses"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { onPcm(ShortArray(4000) { if (it % 2 == 0) 16384 else -16384 }); return true }
            override fun stop() {}
        }
        MicRegistry.glasses = fakeMic
        val events = mutableListOf<DictationEvent>()
        val dictation = GatewayDictation(context(), f.client, backgroundScope)
        try {
            dictation.start("new-thread", events::add); runCurrent(); dictation.stop()
            withTimeout(5000) { while (events.none { it is DictationEvent.Done }) { runCurrent(); delay(1) } }
            assertEquals(.5f, (events.first() as DictationEvent.Level).value, .001f)
            assertTrue(events[1] is DictationEvent.Transcribing); assertTrue(events[2] is DictationEvent.Enhancing)
            assertEquals("Synthetic memo.", (events.last() as DictationEvent.Done).text)
            assertEquals("target=t3", f.sent.single().query)
            assertTrue(f.sent.single().body.startsWith("RIFF"))
        } finally { dictation.cancel(); MicRegistry.glasses = null }
    }
    @Test fun shortPcmNeverReachesGatewayAndMicFailureIsActionableForEveryVoiceEntry() = runTest {
        val f = FixtureGateway(); val ctx = context(); val data = store(f, ctx)
        val events = mutableListOf<DictationEvent>()
        val dictation = GatewayDictation(ctx, f.client, backgroundScope)
        MicRegistry.glasses = object : MicSource {
            override val name = "Synthetic glasses"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { onPcm(shortArrayOf(100)); return true }
            override fun stop() {}
        }
        try {
            dictation.start("joy", events::add); dictation.stop(); runCurrent()
            assertTrue(events.last() is DictationEvent.Error); assertTrue(f.sent.isEmpty())
            MicRegistry.glasses = null
            events.clear(); dictation.start("joy", events::add)
            assertEquals(DictationEvent.Error("Mic unavailable - open the phone app"), events.single())
            assertEquals("Mic unavailable - open the phone app", data.memos.toggle().message)
            assertEquals("Mic unavailable - open the phone app", data.joy.startVoiceCall().message)
            data.joy.setListening(true); data.joy.setListening(false)
            assertEquals("idle", data.joy.snapshot.status)
        } finally { dictation.cancel(); data.close(); MicRegistry.glasses = null }
    }
    @Test fun blockedAudiobookStartReportsPlaybackRecoveryInsteadOfCachedData() = runTest {
        val f = FixtureGateway()
        val blocked = object : ContextWrapper(context()) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw SecurityException("Background playback start rejected")
            }
        }
        val data = store(f, blocked)
        try {
            val result = data.media.play("abs", "book")
            assertFalse(result.ok); assertEquals("Open the phone app to start playback", result.message)
        } finally { data.close() }
    }
    @Test fun settingsPersistAcrossInstances() = runTest {
        val f = FixtureGateway(); val ctx = context(); val data = store(f, ctx)
        val settings = SettingsSnapshot(4, DEFAULT_APP_ORDER.reversed(), false)
        try { data.settings.update(settings); data.close(); val restored = store(f, ctx); try { assertEquals(settings.appOrder, restored.settings.snapshot.appOrder); assertTrue(restored.settings.snapshot.brightnessReadOnly); assertNull(restored.settings.snapshot.glassesBrightness); assertEquals(0, restored.settings.snapshot.brightness) } finally { restored.close() } } finally { data.close() }
    }
    @Test fun mediaHaDocsAndReminderActionsUseGatewayPayloads() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            data.media.refresh()
            assertTrue(data.media.control("volume", 15.0).ok)
            assertEquals(100.0, gatewayJson.parseToJsonElement(f.sent.last { it.path == "/spotify/control" }.body).n("value"), .001)
            assertTrue(data.media.control("toggle").ok)
            assertEquals("pause", gatewayJson.parseToJsonElement(f.sent.last { it.path == "/spotify/control" }.body).s("action"))
            assertTrue(data.media.play("spotify", "spotify:playlist:abc").ok)
            assertTrue(data.media.transfer("device1").ok)
            assertEquals("device1", gatewayJson.parseToJsonElement(f.sent.last { it.path == "/spotify/transfer" }.body).s("deviceId"))
            assertTrue(data.ha.scene("scene.example").ok); assertTrue(data.ha.alarm(style = "Harsh").ok)
            assertEquals("Harsh", gatewayJson.parseToJsonElement(f.sent.last { it.path == "/ha/alarm" }.body).s("style"))
            assertTrue(data.people.remind("person1", "Example reminder").ok)
            assertEquals("person", gatewayJson.parseToJsonElement(f.sent.last { it.method == "POST" && it.path == "/reminders" }.body).jsonObject.getValue("condition").s("type"))
            assertTrue(data.docs.delete("doc1").ok)
            assertTrue(f.sent.any { it.method == "DELETE" && it.path == "/docs/doc1" })
            assertTrue(data.joy.newConversation().ok); assertEquals("joy1", data.joy.snapshot.sessionId)
        } finally { data.close() }
    }
    @Test fun voiceMemoKeepsAudioTranscriptAndTitleOnDisk() = runTest {
        val f = FixtureGateway(); val ctx = context(); val data = store(f, ctx)
        MicRegistry.glasses = object : MicSource {
            override val name = "Synthetic glasses"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { onPcm(ShortArray(1600) { 2000 }); return true }
            override fun stop() {}
        }
        try {
            assertTrue(data.memos.toggle().ok); assertTrue(data.memos.snapshot.recording)
            advanceTimeBy(1000)
            assertTrue(data.memos.toggle().ok)
            // Memo finalization performs real Dispatchers.IO work. A virtual-time timeout can
            // expire before the host gets scheduled, even though the operation is healthy.
            withContext(Dispatchers.Default) {
                withTimeout(5000) { while (data.memos.snapshot.list.first().pending) delay(1) }
            }
            val memo = data.memos.snapshot.list.single()
            assertEquals("Synthetic memo", memo.title); assertEquals("Synthetic memo.", memo.transcript)
            assertEquals("RIFF", File(memo.filePath).inputStream().use { String(it.readNBytes(4)) })
            data.close()
            val restored = store(f, ctx)
            try { assertEquals(memo, restored.memos.snapshot.list.single()) } finally { restored.close() }
        } finally { data.close(); MicRegistry.glasses = null }
    }
    @Test fun legacyOpenEventsAreNotReplayedLate() = runTest {
        val f = FixtureGateway(); val data = store(f)
        val opened = mutableListOf<String>(); data.onOpen = { opened += it.screen }
        try {
            val fresh = Instant.ofEpochMilli(epoch - 10_000).toString(); val stale = Instant.ofEpochMilli(epoch - 31_000).toString()
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"open","seq":1,"screen":"calendar","args":{},"at":"$fresh"}"""))
            data.handleEvent(gatewayJson.parseToJsonElement("""{"type":"open","seq":2,"screen":"beeper","args":{},"at":"$stale"}"""))
            assertEquals(listOf("calendar"), opened)
        } finally { data.close() }
    }
}
