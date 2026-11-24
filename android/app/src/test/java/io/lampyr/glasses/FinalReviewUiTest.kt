package io.lampyr.glasses

import io.lampyr.glasses.calls.*
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
class FinalReviewUiTest {
    private class Fixture {
        var seconds = 0.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_000_000L }, scope)
        fun ui(store: DataStore = data) = Ui(store, scope = scope, nowS = { seconds }).apply { display = Display.Text() }
    }
    private fun frame(view: View): List<String> = TextGrid(26, 7, false).also {
        view.drawText(it)
        assertFalse("Overflow: ${it.lines()}", it.overflowed)
        assertEquals(7, it.lines().size)
        assertTrue(it.lines().all { line -> line.length == 26 })
    }.lines()

    @Test fun backLeavesInactiveJoyCallEvenWhenEndFailsOrThrows() {
        for (mode in listOf("success", "failure", "throw")) {
            val f = Fixture()
            var ends = 0
            val joy = object : JoyRepository by f.data.joy {
                override val call = JoyCallState(active = false)
                override suspend fun startVoiceCall() = ActionResult()
                override suspend fun endVoiceCall(): ActionResult {
                    ends++
                    return when (mode) {
                        "throw" -> throw IllegalStateException("Already disconnected")
                        "failure" -> ActionResult.failure("Already disconnected")
                        else -> ActionResult()
                    }
                }
            }
            val ui = f.ui(object : DataStore by f.data { override val joy = joy })
            try {
                val previous = ui.top
                ui.push(JoyCall(ui)); assertTrue(ui.top is JoyCall)
                ui.top.key("back")
                assertSame(mode, previous, ui.top); assertEquals(1, ends)
            } finally { ui.close() }
        }
    }

    @Test fun multiSelectShowsThreeOptionsArrowsSubmitAndOtherAndSubmitsCheckedValues() {
        val f = Fixture(); val key = ThreadKey("desktop", "3")
        var submitted: Map<String, List<String>> = emptyMap()
        val t3 = object : T3Repository by f.data.t3 {
            override fun detail(machine: String, id: String) = f.data.t3.detail(machine, id)!!.copy(
                request = Request("r", "question", listOf(Question("q", question = "Pick a few",
                    options = listOf("One", "Two", "Three").map { Option(it) }, multi = true))))
            override suspend fun respond(machine: String, id: String, requestId: String, decision: String?, answers: Map<String, List<String>>): ActionResult {
                submitted = answers; return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val t3 = t3 })
        try {
            val view = T3Question(ui, key)
            val first = frame(view)
            assertTrue(first[3].contains("One")); assertTrue(first[4].contains("Two")); assertTrue(first[5].contains("Three"))
            assertTrue(first[5].endsWith("v")); assertTrue(first.last().contains("submit"))
            view.key("sel"); repeat(3) { view.key("down") }
            val submit = frame(view)
            assertTrue(submit.any { it.startsWith(">Submit 1 selected") })
            assertTrue(submit.last().contains("Y submit"))
            assertTrue(submit[3].endsWith("^"))
            view.key("sel"); assertEquals(listOf("One"), submitted["q"])
            view.key("down"); assertTrue(frame(view).any { it.startsWith(">Other") })
        } finally { ui.close() }
    }

    @Test fun approvalNeedsLastPageDrawnDenyIsAlwaysAllowedAndNewRequestRelocks() {
        val f = Fixture(); val key = ThreadKey("vps", "2")
        var request = Request("r", "approval", approval = Approval("command", "echo " + "part ".repeat(55), "A reason"))
        val decisions = mutableListOf<String?>()
        val t3 = object : T3Repository by f.data.t3 {
            override fun detail(machine: String, id: String) = f.data.t3.detail(machine, id)!!.copy(request = request)
            override suspend fun respond(machine: String, id: String, requestId: String, decision: String?, answers: Map<String, List<String>>): ActionResult {
                decisions += decision; return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val t3 = t3 })
        try {
            val view = T3Approval(ui, key)
            val first = frame(view)
            assertTrue(first[3].isNotBlank()) // Three body lines before the choices.
            assertTrue(first.last().contains("<> read all"))
            assertEquals(2, view.cur)
            view.cur = 0; view.key("sel"); assertTrue(decisions.isEmpty())
            view.cur = 2; view.key("sel"); assertEquals(listOf("decline"), decisions)
            repeat(50) { view.key("right") } // Navigation alone is insufficient until rendered.
            view.cur = 0; view.key("sel"); assertEquals(1, decisions.size)
            assertFalse(frame(view).last().contains("read all"))
            view.key("left"); frame(view) // Reading stays acknowledged after paging back.
            view.cur = 0; view.key("sel"); assertEquals("accept", decisions.last())
            request = request.copy(id = "new", approval = request.approval!!.copy(command = "danger " + "more ".repeat(60)))
            view.cur = 0; view.key("sel"); assertEquals(2, decisions.size)
            assertTrue(frame(view).last().contains("read all"))
        } finally { ui.close() }
    }

    @Test fun everyOwnReactionIsLabeledAndSentAsRemoval() {
        val f = Fixture()
        val removals = mutableListOf<Pair<String, Boolean>>()
        val own = listOf(EMO_KEYS[0], EMO_KEYS[1])
        val beeper = object : BeeperRepository by f.data.beeper {
            override fun messages(chatId: String) = f.data.beeper.messages(chatId).map {
                if (it.id == "chat-0-2") it.copy(reactions = own.map { key -> Reaction(key, mine = true) }) else it
            }
            override suspend fun react(chatId: String, messageId: String, key: String, remove: Boolean): ActionResult {
                removals += key to remove; return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val beeper = beeper })
        try {
            for (i in own.indices) {
                val picker = ReactionPicker(ui, "chat-0", "chat-0-2").apply { this.i = i }
                assertTrue(frame(picker)[5].contains("Your reaction"))
                assertTrue(frame(picker).last().contains("Y remove"))
                assertEquals("Remove reaction", picker.legend()["sel"])
                picker.key("sel")
            }
            assertEquals(own.map { it to true }, removals)
        } finally { ui.close() }
    }

    @Test fun spotifyCurrentRowUsesTheTrackThatNowPlayingWillOpen() {
        val f = Fixture()
        var current = NowPlaying(source = "youtube", title = "Video", playing = true)
        val media = object : MediaRepository by f.data.media { override val nowPlaying get() = current }
        val ui = f.ui(object : DataStore by f.data { override val media = media })
        try {
            val spotify = Spotify(ui)
            assertFalse(spotify.rows().any { it.label == "Currently playing" })
            current = current.copy(source = "spotify", title = "New Spotify track")
            val row = spotify.rows().single { it.label == "Currently playing" }
            assertTrue(TextChrome.lineText(row.subLines!!).contains(current.title))
        } finally { ui.close() }
    }

    @Test fun ringingBackLeavesWhenEitherAnswerOrEndCapabilityIsMissing() {
        for ((answer, end) in listOf(false to false, true to false, false to true)) {
            val f = Fixture(); val calls = CallState()
            val ui = f.ui(object : DataStore by f.data { override val calls = calls })
            try {
                val previous = ui.top
                calls.publish(CallsSnapshot(calls = listOf(PhoneCall("c", CallPhase.Ringing, answer = answer, end = end))))
                assertTrue(ui.top is PhoneCallScreen)
                f.seconds = 1.0 // Wait out takeover input guard.
                assertTrue(frame(ui.top).last().contains("B back"))
                ui.top.key("back")
                assertSame(previous, ui.top)
                assertEquals(1, calls.snapshot.calls.size)
            } finally { ui.close() }
        }
    }

    @Test fun notificationClearNeedsTwoHoldsWithinThreeSecondsAndTapDisarms() {
        val f = Fixture(); var clears = 0
        val notifications = object : NotificationsRepository by f.data.notifications {
            override suspend fun clear(): ActionResult { clears++; return ActionResult() }
        }
        val ui = f.ui(object : DataStore by f.data { override val notifications = notifications })
        try {
            val view = Notifications(ui); ui.push(view)
            assertTrue(frame(view)[5].contains("Hold + clears all"))
            view.key("plusHold"); assertEquals(0, clears)
            assertTrue(frame(view)[5].contains("Hold + again to clear all"))
            f.seconds = 3.01; view.key("plusHold"); assertEquals(0, clears)
            f.seconds = 5.0; view.key("plusHold"); assertEquals(1, clears)
            view.key("plusHold"); assertEquals(1, clears) // Clear consumes confirmation.
            view.key("plus"); assertTrue(ui.top is ArmedReminders); ui.pop()
            view.key("plusHold"); assertEquals(1, clears)
            view.onClose(); view.key("plusHold"); assertEquals(1, clears)
            f.seconds += 3.0; view.key("plusHold"); assertEquals(2, clears)
        } finally { ui.close() }
    }

    @Test fun reportBoundaryKeysKeepTheCurrentPageInBothDisplays() {
        for (display in listOf(Display.Text(), Display.Bitmap)) {
            val f = Fixture()
            val report = object : ReportRepository by f.data.report {
                override val snapshot = ReportSnapshot(cards = (1..2).map {
                    ReportCard("text", "news", "Card $it", "word ".repeat(250))
                })
            }
            val ui = f.ui(object : DataStore by f.data { override val report = report }).apply { this.display = display }
            try {
                val view = Report(ui)
                fun page(): Any = if (ui.isText) frame(view) else Report::class.java.getDeclaredField("cardPage").apply { isAccessible = true }.getInt(view)
                view.key("down"); val first = page()
                view.key("left"); assertEquals(first, page())
                view.key("right"); view.key("down"); val last = page()
                view.key("right"); assertEquals(last, page())
            } finally { ui.close() }
        }
    }

    @Test fun settingsDisplayRowsOpenTheirScreensInsteadOfPlaceholders() {
        val f = Fixture(); val ui = f.ui()
        try {
            val view = Settings(ui)
            val labels = view.rows().map { it.label }
            assertTrue(labels.containsAll(listOf("Display & sleep", "Head gestures", "Display position")))
            assertTrue(view.rows().none { it.sub == "(not available yet)" })
            assertEquals("Look-up wake 30°", view.rows().first { it.label == "Head gestures" }.sub)
            assertEquals("Middle, far", view.rows().first { it.label == "Display position" }.sub)
            for ((label, kind) in listOf("Display & sleep" to "power", "Head gestures" to "headgest", "Display position" to "displaypos")) {
                ui.push(view); view.cur = view.rows().indexOfFirst { it.label == label }; view.key("sel")
                assertEquals(kind, ui.top.id); ui.goHome()
            }
            val grid = frame(view)
            assertTrue(grid.none { it.contains("n/a") })
        } finally { ui.close() }
    }
}
