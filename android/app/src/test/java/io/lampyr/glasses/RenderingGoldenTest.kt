package io.lampyr.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingGoldenTest {
    private fun opened(ui: Ui, screen: io.lampyr.glasses.ui.View): io.lampyr.glasses.ui.View {
        ui.replaceTop(screen); screen.key("sel"); return ui.top
    }
    private fun bitmapDict(ui: Ui) = DictationScreen(ui, TalkTarget("Message to Sam", send = { ActionResult() }))
    /** The pre-compact the assistant call goldens keep the full layout (still available as a setting). */
    private fun fullCallLayout(ui: Ui) = ui.powerStore.update(ui.powerStore.prefs.copy(joyCallLayout = "full"))

    private fun demoCall(ui: Ui, active: Boolean = false): io.lampyr.glasses.ui.View {
        val calls = ui.data.calls as io.lampyr.glasses.calls.FakeCalls
        calls.incoming()
        if (active) runBlocking { calls.answer(calls.snapshot.ringing!!.id) }
        return ui.top
    }
    @Test
    fun packedFrameUsesGreenThresholdAndMsbFirstRows() {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(BLACK)
        bitmap.setPixel(0, 0, 0xff006400.toInt())
        bitmap.setPixel(1, 0, 0xff006300.toInt())
        bitmap.setPixel(7, 0, GREEN)
        bitmap.setPixel(W - 1, 0, GREEN)
        bitmap.setPixel(0, 1, GREEN)
        val packed = OneBit.convert(bitmap)
        assertEquals(14850, packed.bits.size)
        assertEquals(0x81, packed.bits[0].toInt() and 255)
        assertEquals(0x18, packed.bits[67].toInt() and 255)
        assertEquals(0, packed.bits[68].toInt() and 255)
        assertEquals(GREEN, packed.bitmap.getPixel(0, 0))
        assertEquals(BLACK, packed.bitmap.getPixel(1, 0))
    }

    @Test
    fun screensMatchApprovedSimulator() {
        val context = RuntimeEnvironment.getApplication() as Context
        val now = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
        val factories =
            linkedMapOf<String, (Ui) -> io.lampyr.glasses.ui.View>(
                "home" to { Home(it) },
                "doc-reader" to { opened(it, Docs(it)) },
                "calendar-event" to { opened(it, CalendarScreen(it).apply { cur = 1 }) },
                "scene-confirm" to { opened(it, HomeAssistant(it).apply { cur = 1 }) },
                "dictation-listening" to { bitmapDict(it).apply { listen() } },
                "dictation-transcribing" to { bitmapDict(it).apply { event(io.lampyr.glasses.audio.DictationEvent.Transcribing) } },
                "dictation-enhancing" to { bitmapDict(it).apply { event(io.lampyr.glasses.audio.DictationEvent.Enhancing) } },
                "dictation-preview" to { bitmapDict(it).apply { event(io.lampyr.glasses.audio.DictationEvent.Done("I'll bring the speaker. Meet you at the park at seven thirty.")) } },
                "dictation-pages" to { bitmapDict(it).apply { event(io.lampyr.glasses.audio.DictationEvent.Done("A long dictated message. ".repeat(20))) } },

                "apps-page-2" to { Apps(it).apply { i = 8 } },
                "joy-menu" to { JoyMenu(it) },
                "beeper-actions" to { MessageActions(it, "chat-0", "chat-0-2") },
                "beeper-message" to { MessageReader(it, "chat-0", "chat-0-2") },
                "beeper-reply" to { ReplyScreen(it, "chat-0", "chat-0-2") },
                "t3-choice" to { T3Thread(it, ThreadKey("desktop", "1")) },
                "t3-multi" to { T3Thread(it, ThreadKey("desktop", "3")) },
                "t3-reader" to { T3Thread(it, ThreadKey("desktop", "5")) },
                "t3-actions" to { T3ThreadActions(it, ThreadKey("desktop", "5")) },
                "t3-environments" to { T3Environments(it) },
                "t3-new" to { NewThread(it) },
                "t3-usage-more" to { T3Usage(it).apply { key("down") } },
                "audiobooks" to { Audiobooks(it) },
                "spotify-playing" to { Spotify(it) },
                "media-menu" to { MediaMenu(it) },
                "media-speed" to { MediaSpeed(it) },
                "media-devices" to { MediaDevices(it) },
                "people" to { People(it) },
                "health-more" to { Health(it).apply { cur = 3 } },
                "health-breakdown" to { HealthDetail(it, "readiness").apply { key("down") } },
                "health-breakdown-more" to { HealthDetail(it, "readiness").apply { key("down"); key("down") } },
                "home-assistant" to { HomeAssistant(it) },
                "docs" to { Docs(it) },
                "teleprompter" to { Teleprompter(it) },
                "voice-memo" to { VoiceMemo(it) },
                "status" to { Status(it) },
                "settings" to { Settings(it) },
                "shortcuts" to { Shortcuts(it) },
                "shortcuts-last" to { Shortcuts(it).apply {cur=5} },
                "shortcut-shoulder" to { ShortcutActions(it, io.lampyr.glasses.input.FREE_GESTURES[1]) },
                "shortcut-actions" to { ShortcutActions(it, io.lampyr.glasses.input.FREE_GESTURES.first()) },
                "app-order" to { AppOrder(it) },

                "call-incoming" to { demoCall(it) },
                "call-active" to { demoCall(it, active = true) },
                "call-held" to { demoCall(it, active = true); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).let { c -> c.publish(c.snapshot.copy(calls = c.snapshot.calls.map { call -> call.copy(phase = io.lampyr.glasses.calls.CallPhase.Holding) })) }; it.top },
                "call-audio" to { demoCall(it, active = true); CallAudio(it) },
                "call-waiting" to { demoCall(it, active = true); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).incoming("Alex Chen", "(773) 555-0188"); it.top },
                "call-history" to { demoCall(it); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).let { c -> c.remoteEnd(c.snapshot.ringing!!.id) }; CallsHistory(it) },
                "call-dialing" to { runBlocking { it.data.calls.place("(630) 555-0101") }; PhoneCallScreen(it, it.data.calls.snapshot.ongoing!!.id) },
                "phone-favorites" to { PhoneRoot(it) },
                "phone-recents" to { PhoneRoot(it, PhoneTab.Recents) },
                "phone-contacts" to { PhoneRoot(it, PhoneTab.Contacts) },
                "phone-numbers" to { PhoneNumbers(it, it.data.calls.book.contacts.first { c -> c.name == "Ben Ortiz" }) },
                "phone-search" to { PhoneSearch(it, "da", (io.lampyr.glasses.calls.resolveCallee(io.lampyr.glasses.calls.parseCallQuery("da"), it.data.calls.book.contacts) as io.lampyr.glasses.calls.Resolution.Ambiguous).candidates) },
                "phone-confirm" to { PhoneConfirm(it, io.lampyr.glasses.calls.Callee("Mom", "(630) 555-0101", "Mobile")) },
                "apps" to { Apps(it) },
                "joy" to { Joy(it) },
                "joy-thinking-reply" to { runBlocking { it.data.joy.send("New question") }; Joy(it) },
                "joy-call-listening" to { fullCallLayout(it); JoyCall(it) },
                "joy-call-thinking" to { fullCallLayout(it); JoyCall(it) },
                "joy-call-speaking" to { fullCallLayout(it); JoyCall(it) },
                "joy-call-muted" to { fullCallLayout(it); JoyCall(it) },
                "joy-call-turns" to { JoyCall(it) },
                "joy-call-worker" to { fullCallLayout(it); JoyCall(it) },
                // Compact is the default the assistant call layout.
                "joy-call-compact-listening" to { JoyCall(it) },
                "joy-call-compact-speaking" to { JoyCall(it) },
                "joy-call-compact-muted" to { JoyCall(it) },
                "joy-call-compact-worker" to { JoyCall(it) },
                "joy-call-compact-reconnecting" to { JoyCall(it) },
                "youtube" to { YouTube(it) },
                "beeper-list" to { Beeper(it) },
                "beeper-thread" to { BeeperThread(it, "chat-0") },
                "beeper-priority" to { Beeper(it) },
                "beeper-quiet" to { Beeper(it).apply { cur = 5; key("sel"); key("down") } },
                "beeper-menu" to { Beeper(it).also { b -> it.replaceTop(b); b.cur = 1; b.key("act") }; it.top },
                "react-picker" to { ReactionPicker(it, "chat-0", "chat-0-2") },
                "notifications" to { Notifications(it) },
                "reminders-empty" to { ArmedReminders(it) },
                "reminders" to {
                    runBlocking { it.data.people.remind("person-0", "Ask how the game went") }
                    ArmedReminders(it)
                },
                "t3-root" to { T3Root(it) },
                "t3-working" to { T3Thread(it, ThreadKey("desktop", "7")) },
                "t3-approval" to { T3Thread(it, ThreadKey("vps", "2")) },
                "t3-approval-deny" to { T3Approval(it, ThreadKey("vps", "2")).apply { key("right"); key("right"); cur = 2 } },
                "t3-usage" to { T3Usage(it) },
                "now-playing" to { NowPlayingScreen(it) },
                "health" to { Health(it) },
                "health-detail" to { HealthDetail(it, "weight") },
                "morning-report" to { Report(it) },
                "report-continuation" to { Report(it).apply { key("down") } },
                "people-card" to { QuickPeople(it) },
                "calendar" to { CalendarScreen(it) },
                "clock" to { ClockHub(ClockFixtures.demo(it)) },
                "clock-timer" to { TimerScreen(ClockFixtures.demo(it)) },
                "clock-timer-new" to { TimerScreen(it) },
                "clock-stopwatch" to { StopwatchScreen(ClockFixtures.demo(it)) },
                "clock-alarms" to { AlarmsScreen(ClockFixtures.demo(it)) },
                "clock-alarm-set" to { AlarmSet(ClockFixtures.demo(it), "a2") },
                "clock-ringing" to { ClockRinging(ClockFixtures.ringingTimer(it)) },
                "clock-ringing-alarm" to { ClockRinging(ClockFixtures.ringingAlarm(it)) },
                "clock-settings" to { ClockSettingsScreen(it) },
                "memo-recording-hud" to { Home(it) },
            )
        val output = File("build/outputs/golden").apply { mkdirs() }
        val report = mutableListOf("| Screen | Mismatch |", "|---|---:|")
        val failures = mutableListOf<String>()
        factories.forEach { (name, factory) ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val data = FakeDataStore(context, { 0.0 }, { now }, scope)
            val ui =
                Ui(callGoldenData(data, name), io.lampyr.glasses.render.avatar.AvatarPacks.fromAssets(context), scope, { 0.0 }, { now }, ZoneId.of("America/Chicago"))
            ui.replaceTop(factory(ui))
            val frame = ui.render()
            // Explicit opt-in only; ordinary runs never rewrite references.
            // UPDATE_BITMAP_GOLDENS: "1" or "all" for every screen, or a comma list of screen names
            // (for example the avatar screens after an avatar pack change; see docs/avatar-packs.md).
            val requested = System.getenv("UPDATE_BITMAP_GOLDENS").orEmpty()
            val update = requested == "1" || requested == "all" || name in requested.split(',').map { it.trim() }
            if (update) {
                File("src/test/resources/golden/$name.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                val one=OneBit.convert(frame.bitmap,threshold=1).bitmap
                File("src/test/resources/golden-1bit/$name.png").outputStream().use { one.compress(Bitmap.CompressFormat.PNG,100,it) }
                one.recycle()
            }
            val expected =
                (if (update) File("src/test/resources/golden/$name.png").inputStream() else javaClass.getResourceAsStream("/golden/$name.png")!!).use {
                    BitmapFactory.decodeStream(it)
                }
            assertEquals(W, expected.width)
            assertEquals(W, frame.bitmap.width)
            assertEquals(H, frame.bitmap.height)
            assertEquals(DisplaySize.PACKED_BYTES, frame.bits.size)
            assertEquals(H, expected.height)
            val actual = IntArray(DisplaySize.PIXELS)
            val reference = IntArray(DisplaySize.PIXELS)
            frame.bitmap.getPixels(actual, 0, W, 0, 0, W, H)
            assertTrue("$name violates the 8 px right margin", actual.indices.none {
                it % W >= W - 8 && (actual[it] ushr 8 and 255) > 0
            })
            expected.getPixels(reference, 0, W, 0, 0, W, H)
            val previous = (if (update) File("src/test/resources/golden-1bit/$name.png").inputStream() else javaClass.getResourceAsStream("/golden-1bit/$name.png")!!).use { BitmapFactory.decodeStream(it) }
            val before = IntArray(DisplaySize.PIXELS)
            previous.getPixels(before, 0, W, 0, 0, W, H)
            assertTrue("$name changed the simulator's 1-bit geometry", reference.indices.all {
                ((reference[it] ushr 8 and 255) > 0) == ((before[it] ushr 8 and 255) >= 100)
            })
            assertTrue("$name has intermediate or non-green master pixels", actual.all {
                (it and 0xff00ff) == 0 && (it ushr 8 and 255) in listOf(0, PRIMARY * 17, SECONDARY * 17, TERTIARY * 17)
            })
            assertArrayEquals("$name packed fallback", OneBit.convert(frame.bitmap, threshold = 1).bits, frame.bits)
            if (name.startsWith("call-") && name != "call-audio" && name != "call-history" || name.startsWith("joy-call-")) {
                assertTrue("$name dimmed safety-critical call controls", actual.slice(196 * W until 212 * W).all {
                    (it ushr 8 and 255) in listOf(0, PRIMARY * 17)
                })
            }
            if (name.startsWith("t3-approval")) {
                assertTrue("$name dimmed approval command", actual.slice(TOP * W until (TOP + 48) * W).all {
                    (it ushr 8 and 255) in listOf(0, PRIMARY * 17)
                })
            }
            previous.recycle()
            var mismatch = 0
            val diff = IntArray(actual.size)
            actual.indices.forEach { i ->
                val a = (actual[i] ushr 8 and 255) / 17
                val b = (reference[i] ushr 8 and 255) / 17
                if (a != b) mismatch++
                diff[i] = if (a != b) 0xffff0000.toInt() else if (a > 0) BLACK or (a * 17 shl 8) else BLACK
            }
            val pct = mismatch * 100.0 / actual.size
            val line = "| $name | ${String.format(Locale.US,"%.4f",pct)}% |"
            report += line
            println(line)
            File(output, "$name.png").outputStream().use {
                frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(output, "$name-diff.png").outputStream().use {
                Bitmap.createBitmap(diff, W, H, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (pct >= 0.5) failures += "$name: $pct%"
            ui.close()
        }
        File(output, "mismatch.md").writeText(report.joinToString("\n") + "\n")
        assertTrue(
            "Golden mismatches exceed 0.5%:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
