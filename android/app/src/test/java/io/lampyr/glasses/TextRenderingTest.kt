package io.lampyr.glasses

import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.data.*
import io.lampyr.glasses.glasses.CharGridRenderer
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextRenderingTest {
    private val fixedTime = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
    private fun ui(display: Display = Display.Text(), name: String = ""): Ui {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { fixedTime }, scope)
        return Ui(callGoldenData(data, name), scope = scope, nowS = { 0.0 }, nowMs = { fixedTime },
            zone = ZoneId.of("America/Chicago")).apply { this.display = display }
    }

    private fun demoCall(ui: Ui, active: Boolean = false): View {
        val calls = ui.data.calls as io.lampyr.glasses.calls.FakeCalls
        calls.incoming()
        if (active) runBlocking { calls.answer(calls.snapshot.ringing!!.id) }
        return ui.top
    }
    private fun dict(ui: Ui) = DictationScreen(ui, TalkTarget("Message to Sam", send = { ActionResult() }))
    private fun opened(ui: Ui, view: View, action: String = "sel"): View {
        ui.push(view)
        view.key(action)
        return ui.top
    }

    /** Includes every concrete View, plus the anonymous readers opened from lists. */
    private fun screens(): Map<String, (Ui) -> View> = linkedMapOf<String, (Ui) -> View>(
        "home" to { Home(it) },
        "call-incoming" to { demoCall(it) },
        "call-waiting" to { demoCall(it, true); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).incoming("Alex Chen", "(773) 555-0188"); it.top },
        "call-active" to { demoCall(it, true) },
        "call-held" to { demoCall(it, active = true); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).let { c -> c.publish(c.snapshot.copy(calls = c.snapshot.calls.map { call -> call.copy(phase = io.lampyr.glasses.calls.CallPhase.Holding) })) }; it.top },
        "call-audio" to { demoCall(it, true); CallAudio(it) },
        "call-fallback" to { demoCall(it, true); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).let { c -> c.publish(c.snapshot.copy(audioControl = false, routes = emptyList(), currentRoute = null, limitation = "Mute / audio on phone")) }; it.top },
        "call-history" to { demoCall(it); (it.data.calls as io.lampyr.glasses.calls.FakeCalls).let { c -> c.remoteEnd(c.snapshot.ringing!!.id) }; CallsHistory(it) },
        "call-dialing" to { runBlocking { it.data.calls.place("(630) 555-0101") }; PhoneCallScreen(it, it.data.calls.snapshot.ongoing!!.id) },
        "phone-favorites" to { PhoneRoot(it) },
        "phone-recents" to { PhoneRoot(it, PhoneTab.Recents) },
        "phone-contacts" to { PhoneRoot(it, PhoneTab.Contacts) },
        "phone-numbers" to { PhoneNumbers(it, it.data.calls.book.contacts.first { c -> c.name == "Ben Ortiz" }) },
        "phone-search" to { PhoneSearch(it, "da", (io.lampyr.glasses.calls.resolveCallee(io.lampyr.glasses.calls.parseCallQuery("da"), it.data.calls.book.contacts) as io.lampyr.glasses.calls.Resolution.Ambiguous).candidates) },
        "phone-confirm" to { PhoneConfirm(it, io.lampyr.glasses.calls.Callee("Mom", "(630) 555-0101", "Mobile")) },
        "apps" to { Apps(it) },
        "apps-last" to { Apps(it).apply { i = APP_ENTRIES.lastIndex } },
        "joy-reply" to { Joy(it) },
        "joy-reply-page" to { Joy(it).apply { it.joyPage = 1 } },
        "joy-thinking" to { runBlocking { it.data.joy.send("Can you check tonight's plans?") }; Joy(it) },
        "joy-listening" to { runBlocking { it.data.joy.setListening(true) }; Joy(it) },
        "joy-menu" to { JoyMenu(it) },
        "voice-call" to { JoyCall(it) },
        "voice-call-thinking" to { JoyCall(it) },
        "voice-call-speaking" to { JoyCall(it) },
        "voice-call-turns" to { JoyCall(it) },
        "voice-call-muted" to { JoyCall(it) },
        "voice-call-worker" to { JoyCall(it) },
        "beeper-list" to { Beeper(it) },
        "beeper-thread" to { BeeperThread(it, "chat-0") },
        "beeper-priority" to { Beeper(it) },
        "beeper-quiet" to { Beeper(it).apply { cur = 5; key("sel"); key("down") } },
        "beeper-menu" to { BeeperListMenu(it, PRIORITY_CHATS[3]) },
        "beeper-find" to { BeeperFindScreen(it) },
        "message" to { MessageReader(it, "chat-0", "chat-0-0") },
        "message-actions" to { MessageActions(it, "chat-0", "chat-0-0") },
        "reply-quote" to { ReplyScreen(it, "chat-0", "chat-0-0") },
        "react-picker" to { ReactionPicker(it, "chat-0", "chat-0-2") },
        "notifs" to { Notifications(it) },
        "reminders-empty" to { ArmedReminders(it) },
        "reminders" to {
            runBlocking { it.data.people.remind("person-0", "Ask how the game went") }
            ArmedReminders(it)
        },
        "t3-root" to { T3Root(it) },
        "t3-thread" to { T3Thread(it, ThreadKey("desktop", "5")) },
        "t3-reader" to { T3Reader(it, ThreadKey("desktop", "5")) },
        "t3-working" to { T3Working(it, ThreadKey("desktop", "7")) },
        "t3-working-quiet" to { T3Working(it, ThreadKey("desktop", "8")) },
        "t3-history" to { T3History(it, ThreadKey("desktop", "7")) },
        "t3-actions" to { T3ThreadActions(it, ThreadKey("desktop", "5")) },
        "t3-history-entry" to { opened(it, T3History(it, ThreadKey("desktop", "7"))) },
        "t3-approval" to { T3Approval(it, ThreadKey("vps", "2")) },
        "t3-approval-reason" to { T3Approval(it, ThreadKey("vps", "2")).apply { approvalPage = 1 } },
        "t3-choice" to { T3Question(it, ThreadKey("desktop", "1")) },
        "t3-multi" to { T3Question(it, ThreadKey("desktop", "3")) },
        "t3-usage" to { T3Usage(it) },
        "t3-resets" to { opened(it, T3Usage(it), "act") },
        "t3-envs" to { T3Environments(it) },
        "t3-settled" to { T3Settled(it) },
        "t3-new-thread" to { NewThread(it) },
        "t3-new-prompt" to { opened(it, NewThread(it)) },
        "now-playing" to { NowPlayingScreen(it) },
        "media-menu" to { MediaMenu(it) },
        "audiobooks" to { Audiobooks(it) },
        "spotify" to { Spotify(it) },
        "media-devices" to { MediaDevices(it) },
        "media-speed" to { MediaSpeed(it) },
        "youtube" to { YouTube(it) },
        "youtube-list" to { YouTubeList(it) },
        "calendar" to { CalendarScreen(it) },
        "calendar-event" to { opened(it, CalendarScreen(it).apply { cur = 1 }) },
        "people" to { People(it) },
        "people-muted" to { ui ->
            runBlocking { ui.data.people.mute(ui.data.people.snapshot.recent.first().id,true) }
            MutedPeople(ui)
        },
        "people-card" to { PersonCard(it, it.data.people.snapshot.recent.first().id) },
        "people-quick" to { QuickPeople(it) },
        "health" to { Health(it) },
        "health-detail" to { HealthDetail(it, "weight") },
        "health-breakdown" to { HealthDetail(it, "readiness").apply { page = 1 } },
        "health-picker" to { HealthPicker(it, "sleep", "", 7) },
        "status" to { Status(it) },
        "home-assistant" to { HomeAssistant(it) },
        "scene-confirm" to { opened(it, HomeAssistant(it).apply { cur = 1 }) },
        "docs" to { Docs(it) },
        "doc-reader" to { opened(it, Docs(it)) },
        "teleprompter" to { Teleprompter(it) },
        "voice-memo" to { VoiceMemo(it) },
        "settings" to { Settings(it) },
        "settings-head-gestures" to { Settings(it).apply { cur = 3 } },
        "power-settings" to { PowerSettings(it) },
        "head-gestures" to { HeadGestureSettings(it).apply { cur = 1 } },
        "display-position" to { DisplayPositionSettings(it) },
                "shortcuts" to { Shortcuts(it) },
                "shortcut-actions" to { ShortcutActions(it, io.lampyr.glasses.input.FREE_GESTURES.first()) },
        "app-order" to { AppOrder(it) },
        "report" to { Report(it) },
        "dictation-listening" to { dict(it).apply { listen() } },
        "dictation-transcribing" to { dict(it).apply { event(DictationEvent.Transcribing) } },
        "dictation-enhancing" to { dict(it).apply { event(DictationEvent.Enhancing) } },
        "dictation-preview" to { dict(it).apply { event(DictationEvent.Done("I'll bring the speaker. Meet you at the park at seven thirty.")) } },
        "dictation-error" to { dict(it).apply { event(DictationEvent.Error("Transcription failed")) } },
        "reader" to { ReaderView(it, "internal-document-route", { "A document with a long body. " .repeat(20) }, "Document") },
        "list" to { ListView(it, "internal-list-route", { listOf(Row("First item"), Row("Second item")) }) },
        "clock" to { ClockHub(ClockFixtures.demo(it)) },
        "clock-timer" to { TimerScreen(ClockFixtures.demo(it)) },
        "clock-timer-new" to { TimerScreen(it) },
        "clock-stopwatch" to { StopwatchScreen(ClockFixtures.demo(it)) },
        "clock-alarms" to { AlarmsScreen(ClockFixtures.demo(it)) },
        "clock-alarm-set" to { AlarmSet(ClockFixtures.demo(it), "a2") },
        "clock-ringing" to { ClockRinging(ClockFixtures.ringingTimer(it)) },
        "clock-ringing-alarm" to { ClockRinging(ClockFixtures.ringingAlarm(it)) },
        "clock-settings" to { ClockSettingsScreen(it) },
    )

    private fun render(view: View, mode: Display.Text = Display.Text()): TextGrid =
        TextGrid(mode.cols, mode.rows, uppercase = false).also { view.drawText(it) }

    @Test fun youtubeOpensPicksAndBackReturnsToItsOpeningScreen() {
        val ui = ui()
        val input = io.lampyr.glasses.input.InputEngine(ui, io.lampyr.glasses.audio.FakeDictation(ui.data, ui.scope))
        try {
            ui.push(NowPlayingScreen(ui)); ui.push(MediaMenu(ui)); ui.push(YouTube(ui))
            assertEquals(25, ui.data.media.videos("Picks").size)
            assertTrue(render(ui.top).lines().first().startsWith("Picks"))
            input.press("b"); input.release("b")
            assertEquals("mmenu", ui.top.id)
        } finally { input.close(); ui.close() }
    }
    @Test fun everyScreenMatchesTextGolden() {
        screens().forEach { (name, factory) ->
            val ui = ui(name = name)
            try {
                val actual = render(factory(ui)).lines().joinToString("\n") + "\n"
                val file = File("src/test/resources/textgolden/$name.txt")
                // Explicit opt-in for reviewing new fixtures; ordinary tests never rewrite expectations.
                if (System.getenv("UPDATE_TEXT_GOLDENS") == "1") {
                    file.parentFile!!.mkdirs()
                    file.writeText(actual)
                }
                assertTrue("Missing golden: $name", file.exists())
                assertEquals(name, file.readText().replace("\r\n", "\n"), actual)
            } finally { ui.close() }
        }
    }

    @Test fun everyConcreteViewHasMeaningfulTextAndFitsBothGrids() {
        val covered = mutableSetOf<Class<*>>()
        for (mode in listOf(Display.Text(), Display.Text(20, 5))) screens().forEach { (name, factory) ->
            val ui = ui(mode, name)
            try {
                val view = factory(ui)
                covered += view.javaClass
                val method = view.javaClass.getMethod("drawText", TextGrid::class.java)
                assertNotEquals("$name inherits placeholder", Screen::class.java, method.declaringClass)
                assertNotEquals("$name inherits empty method", View::class.java, method.declaringClass)
                val frame = render(view, mode)
                assertFalse("$name writes outside ${mode.cols}x${mode.rows}", frame.overflowed)
                val lines = frame.lines()
                if (view.textHintRequired) assertTrue("$name declares actions but has no hint", lines.last().isNotBlank())
                assertEquals(mode.rows, lines.size)
                assertTrue(name, lines.all { it.length == mode.cols && it.all { c -> c in ' '..'~' } })
                assertTrue("$name has no header", lines.first().isNotBlank())
                assertFalse("$name leaks route", lines.first().trim().equals(view.id, ignoreCase = false))
                assertTrue("$name has no body", lines.drop(1).any { it.isNotBlank() })
                val source = lines.joinToString("")
                val full = CharGridRenderer.fullWidth(source)
                assertEquals("$name changed cell count on hardware", source.length, full.length)
                assertEquals("$name unsupported on hardware", source.count { it == '?' }, full.count { it == '\uff1f' })
            } finally { ui.close() }
        }
        // Source inventory makes adding a new concrete View without a fixture a test failure.
        File("src/main/java/io/lampyr/glasses/ui").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            Regex("(?m)^(?:open )?class (\\w+)").findAll(file.readText()).forEach { match ->
                val pkg = if (file.parentFile!!.name == "screens") "ui.screens" else "ui"
                val cls = Class.forName("io.lampyr.glasses.$pkg.${match.groupValues[1]}")
                if (View::class.java.isAssignableFrom(cls) && !Modifier.isAbstract(cls.modifiers))
                    assertTrue("Missing fixture for ${cls.simpleName}", cls in covered)
            }
        }
    }

    @Test fun everyListFocusAndReaderPageKeepsTheGridAndHintIntact() {
        for (mode in listOf(Display.Text(), Display.Text(20, 5))) screens().forEach { (name, factory) ->
            val ui = ui(mode, name)
            try {
                val view = factory(ui)
                fun check() {
                    val grid = render(view, mode)
                    assertFalse("$name overflow at $mode: ${grid.lines()}", grid.overflowed)
                    if (view.textHintRequired) assertTrue("$name lost hint", grid.lines().last().isNotBlank())
                }
                if (view is ListView) view.rows().indices.filterNot { view.rows()[it].sep }.forEach {
                    view.cur = it
                    check()
                    assertTrue("$name lost focus $it", render(view, mode).lines().any { it.startsWith(">") })
                }
                if (view is ReaderView) repeat(view.textPages().size) { view.page = it; check() }
            } finally { ui.close() }
        }
    }

    @Test fun longJoyReplyUsesFiveBodyRowsAndAllFourDirections() {
        val base = ui()
        val longText = (1..80).joinToString(" ") { "word$it" }
        val joy = object : JoyRepository by base.data.joy {
            override val snapshot = JoyState(text = longText)
        }
        val store = object : DataStore by base.data { override val joy = joy }
        val ui = Ui(store, scope = base.scope, nowS = { 0.0 }, nowMs = { fixedTime }).apply { display = Display.Text() }
        try {
            val view = Joy(ui)
            assertEquals(6, view.textPages().size)
            val first = render(view).lines()
            assertTrue(first.last().endsWith("1/6"))
            view.key("down")
            val second = render(view).lines()
            assertEquals(1, ui.joyPage)
            assertNotEquals(first[1], second[1])
            assertTrue(second.last().endsWith("2/6"))
            repeat(20) { view.key("right") }
            assertEquals(5, ui.joyPage)
            view.key("left"); assertEquals(4, ui.joyPage)
            repeat(20) { view.key("up") }; assertEquals(0, ui.joyPage)
            ui.display = Display.Text(20, 5)
            assertTrue(view.textPages().size > 6)
        } finally { ui.close() }
    }

    @Test fun readersAndDictationNavigateBeforeFirstRender() {
        for (mode in listOf(Display.Text(), Display.Text(20, 5))) {
            val ui = ui(mode)
            try {
                val text = "abcdefghij ".repeat(50)
                val reader = ReaderView(ui, "reader-route", { text }, "Document")
                assertEquals(ui.textPages(text).size, reader.pg().size)
                repeat(100) { reader.key("down") }
                assertEquals(ui.textPages(text).lastIndex, reader.page)
                assertTrue(render(reader, mode).lines().last().endsWith("${reader.page + 1}/${reader.pg().size}"))
                val dict = dict(ui).apply { event(DictationEvent.Done(text)) }
                repeat(100) { dict.key("right") }
                assertEquals(ui.textPages(text).lastIndex, dict.page)
            } finally { ui.close() }
        }
    }

    @Test fun focusStaysVisibleAcrossSectionsAndAtEndOfLauncher() {
        val ui = ui(Display.Text(20, 5))
        try {
            val list = T3Root(ui)
            repeat(40) {
                list.key("down")
                val grid = render(list, ui.textDisplay)
                assertTrue(grid.lines().joinToString("\n"), grid.lines().any { it.startsWith(">") })
                assertFalse(list.rows()[list.cur].sep)
            }
            val apps = Apps(ui)
            repeat(40) { apps.key("right") }
            assertTrue(render(apps, ui.textDisplay).lines().any { it.contains(">" + appEntries(ui).last().label) })
        } finally { ui.close() }
    }

    @Test fun emojiNamesUnknownSequencesAndLongWordsRemainVisible() {
        assertEquals("[tears of joy] [thumbs up]", gridText("😂 👍"))
        assertEquals("[emoji]", gridText("👨‍👩‍👧‍👦"))
        assertEquals("[emoji]", gridText("1️⃣"))
        assertEquals("[emoji]", gridText("🇺🇸"))
        assertTrue(gridText("中文").contains('?'))
        assertEquals(listOf("abcdefghijklmnopqrst", "uvwxyz"), gridLines("abcdefghijklmnopqrstuvwxyz", 20))
        assertEquals(listOf(""), gridLines("", 20))
        EMO_KEYS.forEach { assertFalse(gridText(it).contains("[emoji]")) }
    }

    @Test fun bitmapRenderingKeepsBitmapPaginationAndDoesNotRunTextLayout() {
        val ui = ui(Display.Bitmap)
        try {
            val reader = ReaderView(ui, "document", { "word ".repeat(200) }, "Document", rows = 1)
            ui.replaceTop(reader)
            assertEquals(pages(reader.text(), CW - 12, 1), reader.pg())
            reader.page = reader.pg().lastIndex
            assertTrue(reader.page > reader.textPages().lastIndex)
            ui.render()
            assertEquals(reader.pg().lastIndex, reader.page)
            ui.display = Display.Text(20, 5)
            ui.render()
            assertEquals(reader.textPages().lastIndex, reader.page)
        } finally { ui.close() }
    }

    @Test fun reportPagesIncludeEveryCardAndPeopleKeepSeparatePersonControls() {
        val ui = ui()
        try {
            val report = Report(ui)
            val expected = ui.data.report.snapshot.cards.flatMap { c -> ui.textPages(c.body).map { c.heading to it } }
            expected.forEachIndexed { i, (heading, body) ->
                val grid = render(report).lines()
                assertTrue(grid[0].startsWith(heading))
                assertEquals(body, grid.drop(1).take(body.size).map { it.trimEnd() })
                val cardIndex = ui.data.report.snapshot.cards.indexOfFirst { it.heading == heading }
                assertTrue(grid.last().contains("c${cardIndex + 1}/${ui.data.report.snapshot.cards.size}"))
                report.key("down")
            }
            val card = PersonCard(ui, ui.data.people.snapshot.recent.first().id)
            assertTrue(card.pg().size > 1)
            card.key("right"); assertEquals(1, card.page)
            card.key("left"); assertEquals(0, card.page)
            ui.replaceTop(QuickPeople(ui))
            val first = render(ui.top).lines().first()
            ui.top.key("right")
            assertNotEquals(first, render(ui.top).lines().first())
        } finally { ui.close() }
    }

    @Test fun approvalAndQuestionPagesKeepOptionsAccessible() {
        for (mode in listOf(Display.Text(), Display.Text(20, 5))) {
            val ui = ui(mode)
            try {
                val approval = T3Approval(ui, ThreadKey("vps", "2"))
                approval.key("right")
                assertEquals(1, approval.approvalPage)
                repeat(2) { approval.key("down") }
                assertTrue(render(approval, mode).lines().any { it.startsWith(">Deny") })
                val question = T3Question(ui, ThreadKey("desktop", "1"))
                val first = render(question, mode).lines()
                question.key("right")
                if (mode.rows < 7) {
                    assertEquals(1, question.qp)
                    assertNotEquals(first[1], render(question, mode).lines()[1])
                } else {
                    assertEquals("Beeper bridge use on", first[2].trim())
                    assertTrue(first[3].startsWith(">Device token"))
                    assertTrue(first.last().contains("1/2"))
                }
                repeat(10) { question.key("down") }
                assertTrue(render(question, mode).lines().any { it.startsWith(">Other") })
            } finally { ui.close() }
        }
    }

    @Test fun teleprompterScrollsLinesAndPagesWithoutMixingSentences() {
        val ui = ui()
        try {
            val view = Teleprompter(ui)
            val first = render(view).lines()
            assertEquals("Thanks for coming out", first[1].trimEnd())
            view.key("down")
            val next = render(view).lines()
            assertEquals("tonight.", next[1].trimEnd())
            assertTrue(next.drop(2).take(4).all { it.isBlank() })
            view.key("right")
            assertTrue(render(view).lines()[1].trimEnd().isNotBlank())
            view.key("left")
            assertEquals(0, view.off)
        } finally { ui.close() }
    }

    @Test fun quietWaitLockToastsAndMediaOverlayUseSameGridAsMirror() {
        var time = 0.0
        val ui = ui()
        val timed = Ui(ui.data, scope = ui.scope, nowS = { time }, nowMs = { fixedTime }).apply { display = Display.Text() }
        try {
            timed.replaceTop(Joy(timed))
            timed.quiet = true
            runBlocking { timed.data.joy.send("Wait quietly") }
            assertTrue(timed.render().text.lines().all { it.isBlank() })
            runBlocking { timed.data.joy.setListening(true) }
            assertTrue(timed.render().text.lines().last().contains("LISTENING"))
            timed.quiet = false
            timed.mediaLayer = true
            assertTrue(timed.render().text.lines().last().startsWith(">> MEDIA"))
            timed.flash("Sent")
            assertTrue(timed.render().text.lines().last().startsWith(">> Sent"))
            time = 1.71
            assertTrue(timed.render().text.lines().last().startsWith(">> MEDIA"))
            timed.locked = true
            val locked = timed.render()
            assertTrue(locked.text.lines().all { it.isBlank() })
            assertTrue(locked.bits.all { it == 0.toByte() })
        } finally { timed.close() }
    }
}
