package io.lampyr.glasses

import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiPolishTest {
    private class Fixture {
        var seconds = 0.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_000_000L }, scope)
        fun ui(store: DataStore = data) = Ui(store, scope = scope, nowS = { seconds }).apply { display = Display.Text() }
    }
    private fun frame(view: View, cols: Int = 26, rows: Int = 7) = TextGrid(cols, rows, false).also {
        view.drawText(it)
        assertFalse("Overflow: ${it.lines()}", it.overflowed)
    }.lines()

    @Test fun multiSelectOtherPreservesCheckedOptionsAndSendsPlainText() = runBlocking {
        val f = Fixture()
        var submitted: Map<String, List<String>> = emptyMap()
        val repository = object : T3Repository by f.data.t3 {
            override fun detail(machine: String, id: String) = f.data.t3.detail(machine, id)?.copy(
                request = Request("request", "question", listOf(Question("q", question = "Pick ingredients",
                    options = listOf(Option("Coffee"), Option("Milk")), multi = true))))
            override suspend fun respond(machine: String, id: String, requestId: String, decision: String?, answers: Map<String, List<String>>): ActionResult {
                submitted = answers
                return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val t3 = repository })
        try {
            val question = T3Question(ui, ThreadKey("desktop", "3"))
            question.key("sel"); question.key("down"); question.key("sel")
            question.talk.send("Oat milk")
            assertEquals(listOf("Coffee", "Milk", "Oat milk"), submitted["q"])
        } finally { ui.close() }
    }

    @Test fun emptyOrWhitespaceDictationCannotSendOrSteerAndErrorSurvivesToast() {
        val f = Fixture(); val ui = f.ui(); var sends = 0
        val target = TalkTarget("Message", send = { sends++; ActionResult() }, steer = { sends++; ActionResult() })
        try {
            val dictation = DictationScreen(ui, target)
            dictation.event(DictationEvent.Error("Transcription failed"))
            f.seconds = 20.0
            assertTrue(frame(dictation).joinToString().contains("Transcription failed"))
            assertTrue(frame(dictation).last().contains("L retry"))
            dictation.key("sel"); dictation.key("act")
            dictation.event(DictationEvent.Done("   "))
            dictation.key("sel"); dictation.key("act")
            assertEquals(0, sends)
            dictation.event(DictationEvent.Done("hello"))
            dictation.key("sel")
            assertEquals(1, sends)
        } finally { ui.close() }
    }

    @Test fun lateBeeperCacheStartsAtNewestAndOwnReactionIsFirstAndRemovable() {
        val f = Fixture(); var messages = emptyList<Message>(); var removed = ""
        val repository = object : BeeperRepository by f.data.beeper {
            override fun messages(chatId: String) = messages
            override suspend fun react(chatId: String, messageId: String, key: String, remove: Boolean): ActionResult {
                assertTrue(remove); removed = key; return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val beeper = repository })
        try {
            val thread = BeeperThread(ui, "chat-0")
            frame(thread)
            messages = listOf(Message("old", "Sam", text = "Old message"), Message("new", "Sam", text = "Newest message",
                reactions = listOf(Reaction(EMO_KEYS[0]), Reaction(EMO_KEYS[14], mine = true))))
            assertTrue(frame(thread).joinToString().contains("Newest message"))
            assertEquals(1, thread.cur)
            val picker = ReactionPicker(ui, "chat-0", "new")
            val grid = frame(picker)
            assertTrue(grid.joinToString().contains("thumbs up"))
            assertTrue(grid.joinToString().contains("Your reaction"))
            assertTrue(grid.last().contains("Y remove"))
            picker.key("sel")
            assertEquals(EMO_KEYS[14], removed)
        } finally { ui.close() }
    }

    @Test fun thinkingKeepsPreviousReplyReadableAndHangupEndsOnlyOnce() {
        val f = Fixture(); var state = JoyState(text = "Previous reply stays readable")
        val repository = object : JoyRepository by f.data.joy { override val snapshot get() = state }
        val ui = f.ui(object : DataStore by f.data { override val joy = repository })
        try {
            val joy = Joy(ui)
            state = state.copy(status = "thinking", prompt = "Very long question ".repeat(15))
            val grid = frame(joy)
            assertTrue(grid[1].contains("Thinking"))
            assertTrue(grid.joinToString().contains("Previous reply stays"))
            assertTrue(grid.joinToString().contains("readable"))
        } finally { ui.close() }

        val callFixture = Fixture(); val callUi = callFixture.ui()
        try {
            callUi.push(JoyCall(callUi))
            callUi.top.key("back")
            assertFalse(callFixture.data.joy.call.active)
            assertFalse(callUi.top is JoyCall)
            assertEquals(1, callUi.render().text.lines().joinToString().split("Call ended").size - 1)
            assertEquals("user", callFixture.data.joy.call.endedReason)
        } finally { callUi.close() }
    }

    @Test fun youtubeHandoffWaitsForChangedSessionOrTenSeconds() {
        val f = Fixture(); var playing: NowPlaying? = null
        val repository = object : MediaRepository by f.data.media {
            override val nowPlaying get() = playing
            override suspend fun play(source: String, id: String) = ActionResult()
        }
        val ui = f.ui(object : DataStore by f.data { override val media = repository })
        try {
            fun choose(): NowPlayingScreen {
                ui.goHome(); ui.push(YouTube(ui)); ui.top.key("sel")
                return ui.top as NowPlayingScreen
            }
            var view = choose()
            assertTrue(frame(view).joinToString().contains("Opening on phone"))
            f.seconds = 9.9
            assertTrue(view.needsAnimation)
            playing = NowPlaying(source = "youtube", title = "Selected video")
            assertTrue(frame(view).joinToString().contains("Selected video"))
            view = choose() // An old YouTube session must not prematurely finish the new handoff.
            assertTrue(frame(view).joinToString().contains("Opening on phone"))
            playing = null; f.seconds += 10.1
            assertTrue(frame(view).joinToString().contains("Nothing playing"))
        } finally { ui.close() }
    }

    @Test fun settingsReportsBrightnessAndLeavesUnavailableControlsInert() = runBlocking {
        val f = Fixture(); val ui = f.ui()
        try {
            val settings = Settings(ui)
            val before = f.data.settings.snapshot
            for (index in listOf(0)) {
                settings.cur = index
                assertNull(settings.rows()[index].open)
                listOf("left", "right", "sel", "act").forEach { settings.key(it) }
            }
            assertEquals(before, f.data.settings.snapshot)
            assertEquals("8/10", settings.rows()[0].right)
            assertEquals("set on the glasses", settings.rows()[0].sub)
            assertEquals("Fade, wake and lock", settings.rows()[2].sub)
            assertFalse(settings.textHint().contains("cycle"))
            assertFalse(settings.textHint().contains("adjust"))
            settings.cur = 1; settings.key("down"); assertEquals(2, settings.cur)
            settings.key("up"); assertEquals(1, settings.cur)
            settings.cur = 1; settings.key("sel")
            assertTrue(ui.top is AppOrder)
            val order = ui.top as AppOrder
            order.key("sel"); order.key("down"); order.key("sel")
            assertEquals(before.appOrder[0], f.data.settings.snapshot.appOrder[1])
            assertEquals(before.appOrder[1], f.data.settings.snapshot.appOrder[0])
        } finally { ui.close() }
    }

    @Test fun reportContinuationKeepsCardCountAndEveryWordRemainsReachable() {
        val f = Fixture()
        val original = f.data.report.snapshot
        val longBody = (1..80).joinToString(" ") { "word$it" }
        val repository = object : ReportRepository by f.data.report {
            override val snapshot = original.copy(cards = listOf(original.cards.first().copy(body = longBody), original.cards.last()))
        }
        val ui = f.ui(object : DataStore by f.data { override val report = repository })
        try {
            val report = Report(ui)
            val content = mutableListOf<String>()
            val count = ui.textPages(longBody).size
            repeat(count) { page ->
                val grid = frame(report)
                assertTrue(grid.last().contains("c1/2 p${page + 1}/$count"))
                content += grid.drop(1).dropLast(1).joinToString(" ")
                report.key("down")
            }
            (1..80).forEach { assertTrue("Missing word$it", content.joinToString(" ").contains("word$it")) }
            assertTrue(frame(report).last().contains("c2/2"))
            report.key("left"); assertTrue(frame(report).last().contains("c1/2 p1/"))
        } finally { ui.close() }
    }

    @Test fun textChromePreservesTitleAndAllFramesReportOverflow() {
        val grid = TextGrid(26, 7, false)
        TextChrome.header(grid, "Root page copy pass", "WORKING")
        assertTrue(grid.lines()[0].startsWith("Root page copy pass"))
        assertTrue(grid.lines()[0].endsWith("RUN"))
        assertFalse(grid.overflowed)
        grid.put(25, 2, "xx"); assertTrue(grid.overflowed)
        grid.clear(); grid.put(0, 7, "out"); assertTrue(grid.overflowed)
    }

    @Test fun phoneCallIdentityStateAndActionsFitOnePage() = runBlocking {
        val f = Fixture(); val ui = f.ui()
        try {
            val calls = f.data.calls as io.lampyr.glasses.calls.FakeCalls
            calls.incoming()
            val incoming = frame(ui.top)
            assertEquals("Incoming call", incoming[0].trim())
            assertEquals("Y answer B decline X hide", incoming.last().trim())
            assertEquals(1, incoming.count { it.contains("Incoming call") })
            calls.answer(calls.snapshot.ringing!!.id)
            f.seconds = 42.0
            val active = frame(ui.top)
            assertTrue(active[0].startsWith("Phone call")); assertTrue(active[0].endsWith("0:42"))
            assertTrue(active[2].contains("Personal"))
            assertTrue(active[3].contains("Mic on . Pixel Buds Pro"))
            assertEquals("Y mute X audio + end", active.last().trim())
        } finally { ui.close() }
    }

    @Test fun calendarAllDayWithoutLocationAndThirtyDayAsciiChartRemainComplete() {
        val f = Fixture()
        val calendar = object : CalendarRepository by f.data.calendar {
            override val agenda = listOf(CalendarDay("TODAY", "2026-11-11", listOf(
                CalendarEvent("holiday", "Day off", "2026-11-11", allDay = true))))
        }
        val ui = f.ui(object : DataStore by f.data { override val calendar = calendar })
        try {
            val view = CalendarScreen(ui)
            assertTrue(frame(view).joinToString().contains("All day Day off"))
            view.cur = 1; view.key("sel")
            val event = frame(ui.top)
            assertTrue(event.first().endsWith("All day"))
            assertFalse(event.joinToString().contains(" . "))
            val health = HealthDetail(ui, "weight").apply { range = 30 }
            val chart = frame(health)
            assertEquals(30, chart.drop(1).take(2).joinToString("").count { it.isDigit() })
            assertTrue(chart[3].startsWith("Low "))
            assertTrue(chart[4].startsWith("High "))
            assertTrue(chart[5].startsWith("Avg "))
        } finally { ui.close() }
    }
}
