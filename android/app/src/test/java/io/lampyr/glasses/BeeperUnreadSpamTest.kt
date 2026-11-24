package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
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
import org.robolectric.annotation.GraphicsMode

/** BEEP-01/02 big pass: unread conversations, mute/spam marks, paging, push/poll refresh, spam override, deep links. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BeeperUnreadSpamTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private val epoch = Instant.parse("2026-11-17T18:00:00Z").toEpochMilli()
    private fun TestScope.store(f: FixtureGateway) = LiveDataStore(context(), backgroundScope, f.client, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
    private fun row(id: String, vararg extra: Pair<String, JsonElement>) = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive("Chat $id"), "network" to JsonPrimitive("Instagram"), "unread" to JsonPrimitive(0),
        "last" to obj("sender" to str("Ann"), "text" to str("hi $id"), "mine" to JsonPrimitive(false))) + extra)

    @Test fun chatRowsDecodeNewFieldsAndOneMalformedRowCannotBlankTheList() {
        val list = GatewayMappings.chats(JsonArray(listOf(
            row("a", "unread" to JsonPrimitive(39), "muted" to JsonPrimitive(true), "type" to JsonPrimitive("group"), "isGroup" to JsonPrimitive(true), "counts" to JsonPrimitive(false)),
            JsonPrimitive("garbage"),
            obj("id" to str("broken")), // missing name/network
            row("b", "unread" to JsonPrimitive(2), "spam" to JsonPrimitive(true), "spamCategory" to JsonPrimitive("marketing")),
            row("c", "unread" to JsonPrimitive(1)),
        )))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertTrue(list[0].muted && list[0].isGroup); assertFalse(list[0].countsTowardUnread)
        assertFalse(list[1].countsTowardUnread); assertTrue(list[2].countsTowardUnread)
        // Without the gateway's `counts` the client applies the same rule.
        assertFalse(Chat("x", "X", "SMS", 4, muted = true).countsTowardUnread)
        assertTrue(Chat("x", "X", "SMS", 4, muted = true, mentions = 1).countsTowardUnread)
        assertTrue(Chat("x", "X", "SMS", 0, markedUnread = true).countsTowardUnread)
        assertEquals(listOf("muted", "spam", "1 new", "new", ""), listOf(list[0], list[1], list[2], Chat("x", "X", "SMS", 0, markedUnread = true), Chat("y", "Y", "SMS")).map(::chatMark))
    }

    @Test fun listRequestsAHundredLoadsMorePagesAndBadgeCountsConversations() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            f.fixtures["GET /beeper/chats"] = JsonArray((0 until 100).map { row("c$it", "unread" to JsonPrimitive(if (it < 3) 5 else 0)) })
            f.fixtures["GET /beeper/summary"] = obj("unread" to JsonPrimitive(3), "unreadChats" to JsonPrimitive(3), "unreadMessages" to JsonPrimitive(15), "names" to JsonArray(emptyList()), "total" to JsonPrimitive(130))
            assertTrue(data.beeper.refreshChats().ok)
            assertEquals("limit=100", f.sent.first { it.path == "/beeper/chats" }.query)
            assertEquals(3, data.beeper.unreadTotal); assertTrue(data.beeper.hasMoreChats)
            assertSame(data.beeper.chats, data.beeper.chats) // decoded once per snapshot
            f.fixtures["GET /beeper/chats"] = JsonArray((95 until 130).map { row("c$it") })
            assertTrue(data.beeper.loadMoreChats().ok)
            assertEquals("limit=100&offset=100", f.sent.last().query)
            assertEquals(130, data.beeper.chats.size); assertEquals(130, data.beeper.chats.map { it.id }.toSet().size)
            assertFalse(data.beeper.hasMoreChats)
            data.beeper.refreshChats()
            assertEquals("limit=130", f.sent.last { it.path == "/beeper/chats" }.query) // a refresh keeps what Load more showed
        } finally { data.close() }
    }

    @Test fun notificationDismissalNeverHidesArchivesOrMarksAChatRead() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            f.fixtures["GET /beeper/chats"] = JsonArray(listOf(row("chat/a", "unread" to JsonPrimitive(3))))
            data.beeper.refreshChats()
            data.phoneNotification("0|com.beeper.android|7|null|10", NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = "Chat chat/a", body = "hi chat/a"))
            data.phoneNotification("0|com.beeper.android|8|null|10", NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = "Chat chat/a", body = "another"))
            assertEquals(2, data.notifications.items.size)
            val before = f.sent.size
            data.notifications.dismiss(data.notifications.items.first().id)
            data.notifications.clear()
            assertTrue(data.notifications.items.isEmpty())
            val after = f.sent.drop(before)
            assertTrue("dismissal must not reach the gateway: $after", after.isEmpty())
            assertFalse(f.sent.any { it.method != "GET" && (it.path.endsWith("/read") || it.path.contains("archive") || it.path.endsWith("/spam")) })
            assertFalse(f.sent.any { it.method == "PATCH" || it.method == "PUT" || it.method == "DELETE" })
            assertEquals(listOf("chat/a"), data.beeper.chats.map { it.id }); assertEquals(3, data.beeper.chats.single().unread)
        } finally { data.close() }
    }

    /** Answers in the caller's coroutine (no IO hop), so virtual-time tests see every request; records paths without queries. */
    private class SyncGateway : GatewayClient("https://fixture.example", "test") {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun get(path: String): JsonElement {
            sent += path.substringBefore('?')
            return when {
                path.contains("/messages") -> obj("messages" to JsonArray(emptyList()), "nextBefore" to JsonNull)
                path.startsWith("/beeper/summary") -> obj("unread" to JsonPrimitive(0), "names" to JsonArray(emptyList()))
                else -> JsonArray(emptyList())
            }
        }
        override suspend fun write(path: String, body: JsonElement, method: String): JsonElement { sent += "$method $path"; return obj("ok" to JsonPrimitive(true)) }
    }

    /** Runs queued work and lets the snapshot cache's real IO writes finish (it hops to Dispatchers.IO) without advancing virtual time. */
    private fun TestScope.settle() = repeat(100) { runCurrent(); java.lang.Thread.sleep(5) }

    @Test fun beeperEventRefreshesListAndOnlyOpenThreads() = runTest {
        val f = SyncGateway(); val data = LiveDataStore(context(), backgroundScope, f, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
        try {
            data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(listOf(JsonPrimitive("chat/a")))))
            settle()
            assertTrue(f.sent.any { it == "/beeper/chats" }); assertFalse(f.sent.any { it.endsWith("/messages") })
            data.beeper.threadVisible("chat/a", true); f.sent.clear()
            data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(listOf(JsonPrimitive("chat/a"), JsonPrimitive("chat/b"))))); settle()
            assertEquals(listOf("/beeper/chats/chat%2Fa/messages"), f.sent.filter { it.endsWith("/messages") })
            f.sent.clear()
            data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(emptyList()))); settle() // reconcile: every open thread
            assertEquals(1, f.sent.count { it.endsWith("/messages") })
            data.beeper.threadVisible("chat/a", false); f.sent.clear()
            data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(listOf(JsonPrimitive("chat/a"))))); settle()
            assertFalse(f.sent.any { it.endsWith("/messages") })
        } finally { data.close() }
    }

    @Test fun aBurstOfBeeperEventsIsConflatedIntoOneOrTwoRefreshes() = runTest {
        val f = SyncGateway(); val data = LiveDataStore(context(), backgroundScope, f, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
        try {
            data.beeper.threadVisible("chat/a", true)
            // A reconnect can deliver a backlog of hints at once: never one refresh per event.
            repeat(40) { data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(listOf(JsonPrimitive(if (it % 2 == 0) "chat/a" else "chat/b"))))) }
            settle()
            assertTrue(f.sent.count { it == "/beeper/chats" } in 1..2)
            assertTrue(f.sent.count { it == "/beeper/chats/chat%2Fa/messages" } in 1..2)
            f.sent.clear()
            data.handleEvent(obj("type" to str("beeper"), "chatIds" to JsonArray(emptyList()))); settle()
            assertEquals(1, f.sent.count { it == "/beeper/chats" }); assertEquals(1, f.sent.count { it.endsWith("/messages") })
        } finally { data.close() }
    }

    @Test fun pollIsTwentySecondsOnBeeperScreensSixtyOtherwiseAndIgnoresDisplaySleep() = runTest {
        val f = SyncGateway(); val data = LiveDataStore(context(), backgroundScope, f, { epoch }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
        try {
            data.setDisplayAwake(false) // the display sleeps after 2 s: polling must not depend on it
            data.onScreenChanged("home")
            val job = backgroundScope.launch { data.beeperPoll() }
            fun lists() = f.sent.count { it == "/beeper/chats" }
            settle(); assertEquals(1, lists())
            advanceTimeBy(55_000); settle(); assertEquals(1, lists())
            advanceTimeBy(6_000); settle(); assertEquals(2, lists())
            data.onScreenChanged("beeper")
            advanceTimeBy(21_000); settle(); assertEquals(3, lists())
            advanceTimeBy(20_000); settle(); assertEquals(4, lists())
            data.onScreenChanged("thread"); data.beeper.threadVisible("chat/a", true)
            advanceTimeBy(20_000); settle(); assertEquals(5, lists())
            assertTrue(f.sent.any { it == "/beeper/chats/chat%2Fa/messages" })
            job.cancel()
        } finally { data.close() }
    }

    @Test fun spamOverridePostsToTheGatewayOnly() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            val result = data.beeper.setSpam("chat/a", true)
            assertTrue(result.ok); assertEquals("Marked spam", result.message)
            val sent = f.sent.first { it.method == "POST" }
            assertEquals("/beeper/chats/chat%2Fa/spam", sent.path)
            assertEquals(true, gatewayJson.parseToJsonElement(sent.body).jsonObject["spam"]!!.jsonPrimitive.boolean)
            assertTrue(f.sent.any { it.path == "/beeper/chats" }) // refetched so the mark appears
            assertFalse(f.sent.any { it.method == "POST" && it.path.endsWith("/read") })
        } finally { data.close() }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { Instant.parse("2026-11-12T01:45:00Z").toEpochMilli() }, scope)
        fun ui(store: DataStore = data) = Ui(store, scope = scope, nowS = { 0.0 }, nowMs = { 0L }, zone = ZoneId.of("America/Chicago"))
    }

    @Test fun openTargetOpensTheThreadForAChatId() {
        val f = Fixture(); val ui = f.ui()
        ui.goHome()
        assertTrue(ui.openTarget("beeper", "chat-1"))
        assertEquals("thread-chat-1", ui.top.id); assertEquals("beeper", ui.stack[ui.stack.size - 2].kind)
        ui.goHome()
        assertTrue(ui.openTarget("beeper", null)); assertEquals("beeper", ui.top.kind)
        assertFalse(ui.openTarget("nope", "x")); assertFalse(ui.openTarget(null, null))
    }

    @Test fun badgeCountsConversationsMutedAndSpamShowMarksAndThreadMenuTogglesSpam() {
        val f = Fixture()
        var chats = listOf(Chat("a", "Group", "Instagram", 39, muted = true, isGroup = true), Chat("b", "62881", "SMS", 1, spam = true), Chat("c", "Sam", "iMessage", 3), Chat("d", "Riley", "Discord", 1))
        val spamCalls = mutableListOf<Pair<String, Boolean>>()
        val repository = object : BeeperRepository by f.data.beeper {
            override val chats get() = chats
            override val hasMoreChats = true
            override fun chat(chatId: String) = chats.find { it.id == chatId }
            override suspend fun setSpam(chatId: String, spam: Boolean): ActionResult { spamCalls += chatId to spam; chats = chats.map { if (it.id == chatId) it.copy(spam = spam) else it }; return ActionResult() }
        }
        val ui = f.ui(object : DataStore by f.data { override val beeper = repository })
        assertEquals(2, repository.unreadTotal) // conversations, not 44 messages
        ui.goHome(); ui.push(Beeper(ui))
        val list = ui.top as ListView
        // BEEP-PRI default order: unread muted and spam chats fold into the Quiet row; Y shows their marks.
        assertEquals(listOf("3 new", "1 new", "show", "4 shown"), list.rows().map { it.right })
        list.cur = 2; list.key("sel")
        assertEquals(listOf("3 new", "1 new", "hide", "muted", "spam", "4 shown"), list.rows().map { it.right })
        assertEquals("Load more", list.rows().last().label)
        assertSame(list.rows(), list.rows()) // rows are memoized per chat snapshot
        ui.display = Display.Text()
        assertTrue(TextGrid(26, 7, false).also { ui.top.drawText(it) }.lines().first().contains("2 unread"))
        ui.display = Display.Bitmap
        val actions = MessageActions(ui, "c", "m")
        assertEquals("Mark spam", actions.rows().last().label)
        ui.push(actions); actions.cur = actions.rows().lastIndex; actions.key("sel")
        assertEquals(listOf("c" to true), spamCalls)
        assertEquals("Not spam", MessageActions(ui, "c", "m").rows().last().label)
        assertEquals("beeper-actions", MessageActions(ui, "c", "m").kind)
    }
}
