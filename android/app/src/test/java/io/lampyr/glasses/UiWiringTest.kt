package io.lampyr.glasses

import io.lampyr.glasses.data.*
import io.lampyr.glasses.data.Thread
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.time.Instant
import java.time.ZoneId
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
class UiWiringTest {
    private class Fixture {
        val now = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { now }, scope)
        fun ui(store: DataStore = data, mode: Display = Display.Text()) =
            Ui(store, scope = scope, nowS = { 0.0 }, nowMs = { now }, zone = ZoneId.of("America/Chicago"))
                .apply { display = mode }
    }

    private fun text(view: View, cols: Int = 26, rows: Int = 7): String = TextGrid(cols, rows, false).also {
        view.drawText(it)
        assertFalse("Overflow in ${view.id}: ${it.lines()}", it.overflowed)
    }.lines().joinToString("\n")

    @Test fun beeperColdCacheWaitsForLoadedAndPrependsWithoutLosingSelectionOrRepeatingPages() {
        for (mode in listOf(Display.Bitmap, Display.Text())) {
            val f = Fixture()
            var messages = emptyList<Message>()
            var state = MessagePageState()
            val older = CompletableDeferred<Unit>()
            var calls = 0
            val repository = object : BeeperRepository by f.data.beeper {
                override val unreadTotal = 123
                override val unreadChats = 42
                override fun messages(chatId: String) = messages
                override fun pageState(chatId: String) = state
                override suspend fun refreshMessages(chatId: String) = ActionResult()
                override suspend fun markRead(chatId: String) = ActionResult()
                override suspend fun loadOlder(chatId: String): ActionResult {
                    calls++
                    state = state.copy(loadingOlder = true)
                    older.await()
                    messages = listOf(Message("old", "Alex", text = "Earlier")) + messages
                    state = MessagePageState(loaded = true)
                    return ActionResult()
                }
            }
            val ui = f.ui(object : DataStore by f.data { override val beeper = repository }, mode)
            try {
                val view = BeeperThread(ui, "chat-0")
                ui.push(view); ui.render()
                messages = listOf(Message("a", "Alex", text = "First"), Message("b", "Alex", text = "Newest"))
                // Partial cache hydration is not a completed initial page.
                ui.render()
                state = MessagePageState(loaded = true, nextBefore = "opaque")
                ui.render(); assertEquals("b", messages[view.cur].id)
                view.key("up"); assertEquals("a", messages[view.cur].id)
                view.key("up"); view.key("up")
                assertEquals(1, calls)
                ui.display = Display.Text()
                assertTrue(ui.render().text.lines().joinToString().contains("Loading older"))
                older.complete(Unit)
                ui.render(); assertEquals("a", messages[view.cur].id)
                view.key("up"); assertEquals("old", messages[view.cur].id)
                repeat(4) { view.key("up") }
                assertEquals(1, calls)
                assertFalse(text(view).contains("Newest"))
            } finally { ui.close() }
        }
    }

    @Test fun beeperRailReadsIndependentUnreadSummary() {
        val f = Fixture()
        var unread = 123
        val repository = object : BeeperRepository by f.data.beeper {
            override val unreadTotal get() = unread
        }
        val ui = f.ui(object : DataStore by f.data { override val beeper = repository }, Display.Bitmap)
        try {
            ui.push(Beeper(ui)); val first = ui.render().bits
            unread = 456
            assertFalse(first.contentEquals(ui.render().bits))
            assertTrue(text(Beeper(ui)).contains("456 unread"))
            assertTrue(text(Home(ui)).contains("456 unread"))
        } finally { ui.close() }
    }

    @Test fun t3UsesHumanStepSharedCountsAndOfflineLabels() {
        val f = Fixture()
        val step = Step("command", "pnpm test", label = "Running command", elapsedSec = 727.0)
        val thread = Thread("desktop", "live", "Run tests", "repo", "Codex", "working", step = step)
        val repository = object : T3Repository by f.data.t3 {
            override val threads = listOf(thread)
            override val needYouCount = 42
            override val environments = listOf(Environment("cold", "Cold", false), Environment("seen", "Seen", false, "Linux", lastSeenLabel = "Yesterday"))
            override val offlineCount = 2
            override fun detail(machine: String, id: String) = ThreadDetail(thread, working = Working(step))
        }
        val ui = f.ui(object : DataStore by f.data { override val t3 = repository })
        try {
            val root = T3Root(ui)
            val sub = root.rows().single { it.label == "Run tests" }.sub
            assertEquals("Running command: pnpm test · 12:07", sub)
            assertTrue(text(root).contains("2 offline"))
            assertTrue(text(root).contains("NEEDS YOU (42)"))
            assertTrue(text(Home(ui)).contains("42 need you"))
            assertTrue(text(T3Working(ui, thread.key)).contains("-NOW- Running command"))
            val envs = T3Environments(ui)
            assertEquals("offline", TextChrome.lineText(envs.rows()[0].subLines!!))
            assertTrue(envs.rows()[1].sub!!.contains("last seen Yesterday"))
            assertFalse(text(envs).contains("last seen")) // Never-seen machine has no empty last-seen clause.
            ui.display = Display.Bitmap; ui.replaceTop(root); val offline = ui.render().bits
            assertTrue(offline.any { it.toInt() != 0 })
            ui.replaceTop(T3Working(ui, thread.key)); ui.render()
            ui.replaceTop(envs); ui.render()
        } finally { ui.close() }
    }

    @Test fun guestRequestsReadMessagesAndReplyNormallyIncludingLateArrival() = runBlocking {
        val f = Fixture()
        val th = Thread("machine-b", "same-id", "Guest note", "repo", "Codex", "request")
        var guest = emptyList<HistoryEntry>()
        var sent: Triple<String, String, String>? = null
        val repository = object : T3Repository by f.data.t3 {
            override fun detail(machine: String, id: String) = ThreadDetail(th, request = Request("r", "guest", messages = guest))
            override suspend fun refreshThread(machine: String, id: String) = ActionResult()
            override suspend fun visit(machine: String, id: String) = ActionResult()
            override suspend fun send(machine: String, id: String, text: String, mode: String): ActionResult {
                sent = Triple(machine, id, mode); return ActionResult()
            }
        }
        val ui = f.ui(object : DataStore by f.data { override val t3 = repository })
        try {
            val view = T3Thread(ui, th.key)
            assertTrue(text(view).contains("Guest message unavailable"))
            guest = listOf(HistoryEntry("guest", "Please check the release"))
            assertTrue(text(view).contains("Please check the release"))
            assertTrue(view.legend().containsKey("talkH"))
            view.talk!!.send("On it")
            assertEquals(Triple("machine-b", "same-id", "send"), sent)
            ui.display = Display.Bitmap; ui.replaceTop(view); ui.render()
            view.key("act"); assertTrue(ui.top is T3ThreadActions)
            (ui.top as T3ThreadActions).rows().first().open!!.invoke(); assertTrue(ui.top is T3History)
        } finally { ui.close() }
    }

    @Test fun transitionNotificationOpensExactMachineAndThread() {
        val f = Fixture()
        val notice = Notification("transition", "T3", "t3", "Needs you", "Input", .9, f.now,
            OpenTarget("t3thread", mapOf("machine" to "machine-b", "id" to "same-id")))
        val repository = object : NotificationsRepository by f.data.notifications {
            override val items = listOf(notice)
            override suspend fun dismiss(id: String) = ActionResult()
        }
        val ui = f.ui(object : DataStore by f.data { override val notifications = repository })
        try {
            ui.push(Notifications(ui)); ui.top.key("sel")
            assertEquals(ThreadKey("machine-b", "same-id"), (ui.top as T3Thread).threadKey)
        } finally { ui.close() }
    }

    @Test fun remindersArmWithoutNotificationOpenWithPlusAndCancelWithFlash() {
        val f = Fixture(); val ui = f.ui()
        try {
            val before = ui.data.notifications.items
            val card = PersonCard(ui, "person-0")
            ui.perform { card.talk!!.send("Ask about the game") }
            assertTrue(ui.render().text.lines().joinToString().contains("Reminder armed"))
            assertEquals(before, ui.data.notifications.items)
            val notices = Notifications(ui)
            ui.push(notices); notices.key("plus")
            assertTrue(ui.top is ArmedReminders)
            val view = ui.top as ArmedReminders
            val grid = text(view)
            assertTrue(grid.contains("Ask about the game")); assertTrue(grid.contains("person:")); assertTrue(grid.contains("Expires Dec 11"))
            text(view, 20, 5)
            ui.display = Display.Bitmap; ui.render()
            view.key("act")
            assertTrue(ui.data.reminders.armed.isEmpty())
            assertTrue(text(view).contains("No armed reminders"))
            ui.display = Display.Text()
            assertTrue(ui.render().text.lines().joinToString().contains("Reminder cancelled"))
            ui.push(notices); notices.key("plusHold")
            assertFalse(ui.data.notifications.items.isEmpty())
            notices.key("plusHold")
            assertTrue(ui.data.notifications.items.isEmpty()); assertTrue(ui.top is Notifications)
        } finally { ui.close() }
    }

    @Test fun spotifyCurrentFollowsNowPlayingAndSeparatorsFollowRows() {
        val f = Fixture()
        var currentTrack: NowPlaying? = NowPlaying(source = "spotify", title = "Remote song", artist = "Artist")
        var playlistItems = listOf(Playlist("spotify:collection", "Liked Songs"), Playlist("recent", "Mix"))
        val actions = mutableListOf<String>()
        val repository = object : MediaRepository by f.data.media {
            override val nowPlaying get() = currentTrack
            override val playlists get() = playlistItems
            override suspend fun control(action: String, value: Double?): ActionResult { actions += action; return ActionResult() }
        }
        val ui = f.ui(object : DataStore by f.data { override val media = repository })
        try {
            val view = Spotify(ui)
            assertEquals(listOf("Currently playing", "Liked Songs", "RECENT PLAYLISTS", "Mix"), view.rows().map { it.label })
            assertTrue(text(view).contains("Remote song"))
            view.key("sel"); assertEquals(listOf("play"), actions)
            currentTrack = null
            assertEquals(listOf("Liked Songs", "RECENT PLAYLISTS", "Mix"), view.rows().map { it.label })
            playlistItems = emptyList(); assertTrue(view.rows().isEmpty())
            playlistItems = listOf(Playlist("discover", "Discover Weekly"))
            assertEquals(listOf("Discover Weekly"), view.rows().map { it.label })
            ui.display = Display.Bitmap; ui.replaceTop(view); ui.render()
        } finally { ui.close() }
    }

    @Test fun mappedHealthWeatherCalendarStatusAndMemoValuesReachBothDisplays() {
        val f = Fixture()
        val metric = Metric("hrv", "HRV", "52 ms", "6:41 AM today", listOf(Sample("today", 52.0)), unit = "ms")
        val health = object : HealthRepository by f.data.health {
            override val metrics = listOf(metric)
            override val updatedLabel = "Updated 6:41 AM today"
        }
        val status = object : StatusRepository by f.data.status {
            override val snapshot = StatusSnapshot(listOf(Battery("phone", "Phone", .75), Battery("health", "Oura", -1.0), Battery("note", "Earbuds", .9, level = null)))
        }
        val settings = object : SettingsRepository by f.data.settings {
            override val snapshot = SettingsSnapshot(brightness = 1, glassesBrightness = 7)
        }
        val memos = object : MemosRepository by f.data.memos {
            override val snapshot = MemoSnapshot(stoppedReason = "Stopped at size limit")
        }
        val calendar = object : CalendarRepository by f.data.calendar {
            override val agenda = listOf(CalendarDay("TODAY", "", listOf(CalendarEvent("all-day", "Holiday", "2026-11-12", allDay = true, timeLabel = "All day", displaySubtitle = "All day"))))
        }
        val store = object : DataStore by f.data {
            override val health = health; override val status = status; override val settings = settings
            override val memos = memos; override val calendar = calendar
            override val home = HomeSnapshot(Weather(null, "Cloudy", "cloud"), NextEvent("Holiday", "2026-11-12", allDay = true, timeLabel = "All day"))
        }
        val ui = f.ui(store)
        try {
            assertTrue(text(Health(ui)).contains("52 ms")); assertTrue(text(Health(ui)).contains("6:41 AM today"))
            assertTrue(text(Health(ui)).contains("Updated 6:41 AM today"))
            val detail = HealthDetail(ui, "hrv")
            detail.key("down"); assertEquals(0, detail.page)
            assertFalse(detail.legend().containsKey("down")); assertFalse(text(detail).lines().last().contains("detail"))
            assertTrue(text(Home(ui)).contains("- Cloudy")); assertTrue(text(Home(ui)).contains("All day Holiday"))
            val cal = CalendarScreen(ui)
            assertEquals("All day", TextChrome.lineText(cal.rows()[1].subLines!!))
            cal.cur = 1; cal.key("sel"); assertTrue(text(ui.top).contains("All day"))
            val statusText = text(Status(ui))
            assertFalse(statusText.contains("Oura")); assertFalse(statusText.contains("Earbuds")); assertTrue(statusText.contains("7/10"))
            assertTrue(text(VoiceMemo(ui)).contains("Stopped at size limit"))
            text(VoiceMemo(ui), 20, 5)
            assertEquals("7/10", Settings(ui).rows()[0].right)
            ui.display = Display.Bitmap
            listOf(Health(ui), detail, Home(ui), cal, Status(ui), VoiceMemo(ui), Settings(ui)).forEach {
                ui.replaceTop(it); assertTrue(ui.render().bits.any { byte -> byte.toInt() != 0 })
            }
            detail.key("down"); assertEquals(0, detail.page)
        } finally { ui.close() }
    }
}
