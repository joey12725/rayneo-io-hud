package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.data.*
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

/** BEEP-PRI: list grouping (pins, important, unread, rest, collapsed Quiet), the order setting, glasses pins, spoken find. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BeeperPriorityTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
    private fun chat(id: String, unread: Int = 0, vararg text: String, group: Boolean = false, mentions: Int = 0, muted: Boolean = false, spam: Boolean = false, names: List<String> = emptyList()) =
        Chat(id, id, "Instagram", unread, Message("m-$id", if (group) "Ann" else id, false, text.firstOrNull() ?: "hello"), type = if (group) "group" else "single", isGroup = group,
            mentions = mentions, muted = muted, spam = spam, participants = ChatPeople(names.size + 1, names))

    private class Fixture(chats: List<Chat>? = null, prefs: BeeperPrefStore = MemoryBeeperPrefs()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { Instant.parse("2026-11-12T01:45:00Z").toEpochMilli() }, scope)
        val data: DataStore = if (chats == null) fake else object : DataStore by fake {
            var list: List<Chat> = chats.orEmpty()
            override val beeper = object : BeeperRepository by fake.beeper {
                override val chats get() = list
                override val unreadTotal get() = list.count { it.countsTowardUnread }
                override fun chat(chatId: String) = list.find { it.id == chatId }
                override suspend fun find(query: String) = BeeperPriority.find(query, list).let { BeeperFind(it, BeeperPriority.confident(it)) }
                override suspend fun setPin(chatId: String, pinned: Boolean): ActionResult {
                    list = list.map { if (it.id == chatId) it.copy(glassesPinned = pinned) else it }
                    return ActionResult(message = if (pinned) "Pinned on glasses" else "Unpinned")
                }
            }
        }
        val ui = Ui(data, scope = scope, nowS = { 0.0 }, nowMs = { Instant.parse("2026-11-12T01:45:00Z").toEpochMilli() }, zone = ZoneId.of("America/Chicago"), beeperPrefs = prefs)
    }

    private fun labels(view: ListView) = view.rows().map { it.label }

    @Test fun priorityGroupsPinsImportantUnreadThenTheRestWithQuietCollapsed() {
        val groups = BeeperPriority.group(PRIORITY_CHATS, BeeperOrder.PRIORITY)
        assertEquals(listOf("Maya", "Sam", "Climbing crew", "Jordan", "Family"), groups.top.map { it.name })
        assertEquals(listOf("Neighborhood", "Deals"), groups.quiet.map { it.name })
        // Recent: the pins, then the list order as the gateway sent it; nothing is collapsed.
        val recent = BeeperPriority.group(PRIORITY_CHATS, BeeperOrder.RECENT)
        assertEquals(listOf("Maya", "Jordan", "Neighborhood", "Sam", "Deals", "Climbing crew", "Family"), recent.top.map { it.name })
        assertTrue(recent.quiet.isEmpty())
        assertEquals(PRIORITY_CHATS.size, groups.all.size)
    }

    @Test fun gatewayTiersAndScoresWinOverTheLocalRules() {
        val a = Chat("a", "A", "Discord", 1, priority = "unread", importance = 0.2, counts = true)
        val b = Chat("b", "B", "Discord", 1, priority = "important", importance = 0.9, counts = true, why = listOf("mention"))
        val c = Chat("c", "C", "Discord", 1, priority = "important", importance = 0.6, counts = true)
        val pin = Chat("p", "P", "Discord", 0, priority = "pinned", pinned = true)
        val quiet = Chat("q", "Q", "Discord", 4, priority = "quiet", counts = false, muted = true)
        val read = Chat("r", "R", "Discord", 0, priority = "read")
        val g = BeeperPriority.group(listOf(a, read, c, quiet, b, pin), BeeperOrder.PRIORITY)
        assertEquals(listOf("p", "b", "c", "a", "r"), g.top.map { it.id })
        assertEquals(listOf("q"), g.quiet.map { it.id })
        assertEquals(ChatTier.PINNED, BeeperPriority.tier(pin)); assertEquals(ChatTier.QUIET, BeeperPriority.tier(quiet))
    }

    @Test fun localScoreMatchesTheGatewayRules() {
        assertEquals(0.5, BeeperPriority.localScore(chat("dm", 1, "are you free tonight?")), 1e-9)
        assertEquals(0.2, BeeperPriority.localScore(chat("dm", 1, "see you")), 1e-9)
        assertEquals(0.5, BeeperPriority.localScore(chat("g", 3, "hey", group = true, mentions = 1)), 1e-9)
        assertEquals(0.1, BeeperPriority.localScore(chat("g", 3, "who is in?", group = true)), 1e-9)
        assertEquals(0.3, BeeperPriority.localScore(chat("62881", 1, "Reply YES?")), 1e-9)
        assertTrue(BeeperPriority.asksQuestion("can you grab the speaker"))
        assertFalse(BeeperPriority.asksQuestion("see you soon"))
    }

    @Test fun classifiedNotificationsLiftAChatOnlyWhenImportantAndRecent() {
        val c = chat("Riley", 1, "ok")
        fun note(importance: Double, ageMs: Long, title: String = "Riley") = Notification("n", "beeper", "beeper", title, "x", importance, now - ageMs)
        assertEquals(ChatTier.UNREAD, BeeperPriority.tier(c, emptyList(), now))
        assertEquals(ChatTier.IMPORTANT, BeeperPriority.tier(c, listOf(note(0.8, 60_000)), now))
        assertEquals(ChatTier.UNREAD, BeeperPriority.tier(c, listOf(note(0.6, 60_000)), now))
        assertEquals(ChatTier.UNREAD, BeeperPriority.tier(c, listOf(note(0.9, 7 * 3600_000L)), now))
        val byId = Notification("n", "beeper", "beeper", "Someone else", "x", 0.9, now, OpenTarget("beeper-thread", mapOf("chatId" to "Riley")))
        assertEquals(ChatTier.IMPORTANT, BeeperPriority.tier(c, listOf(byId), now))
        val other = Notification("n", "t3", "t3", "Riley", "x", 0.9, now)
        assertEquals(ChatTier.UNREAD, BeeperPriority.tier(c, listOf(other), now))
    }

    @Test fun listShowsQuietRowAtTheBottomAndYExpandsItWhileTheBadgeIgnoresIt() {
        val f = Fixture(PRIORITY_CHATS); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        assertEquals(listOf("Maya", "Sam", "Climbing crew", "Jordan", "Family", "Quiet (2)"), labels(list))
        assertEquals("pin", list.rows()[0].right); assertEquals("2 new", list.rows()[1].right)
        assertEquals(3, f.ui.data.beeper.unreadTotal) // Sam, Climbing crew, Jordan: muted and spam never count
        list.cur = 5; list.key("sel")
        assertTrue(list.quietOpen)
        assertEquals(listOf("Maya", "Sam", "Climbing crew", "Jordan", "Family", "Quiet (2)", "Neighborhood", "Deals"), labels(list))
        assertEquals(listOf("hide", "muted", "spam"), list.rows().drop(5).map { it.right })
        list.key("sel")
        assertFalse(list.quietOpen); assertEquals(6, list.rows().size)
        list.cur = 1; list.key("sel")
        assertEquals("thread-p-sam", f.ui.top.id)
    }

    @Test fun xMenuPinsTheSelectedChatOnTheGlassesAndItMovesToTheTop() {
        val f = Fixture(PRIORITY_CHATS); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        list.cur = 3; list.key("act") // Jordan
        val menu = f.ui.top as BeeperListMenu
        assertEquals(listOf("Find chat", "Pin on glasses", "Mark spam", "Order"), labels(menu))
        menu.cur = 1; menu.key("sel")
        assertSame(list, f.ui.top)
        assertEquals(listOf("Maya", "Jordan"), labels(list).take(2))
        assertEquals("pin · 1 new", list.rows()[1].right)
        list.cur = 1; list.key("act")
        assertEquals("Unpin from glasses", labels(f.ui.top as ListView)[1])
        // X on the Quiet row has no chat: only Find and Order.
        f.ui.pop(); list.cur = 5; list.key("act")
        assertEquals(listOf("Find chat", "Order"), labels(f.ui.top as ListView))
    }

    @Test fun orderSettingSwitchesToRecentFromTheMenuAndPersistsInPrefs() {
        val prefs = MemoryBeeperPrefs()
        val f = Fixture(PRIORITY_CHATS, prefs); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        list.key("act")
        val menu = f.ui.top as ListView
        assertEquals("Priority", menu.rows().last().right)
        menu.cur = menu.rows().lastIndex; menu.key("sel")
        assertEquals(BeeperOrder.RECENT, prefs.prefs.order)
        assertEquals(listOf("Maya", "Jordan", "Neighborhood", "Sam", "Deals", "Climbing crew", "Family"), labels(list))
        assertEquals(BeeperOrder.RECENT, BeeperPrefs.read(mapOf("beeper.order" to "RECENT")).order)
        assertEquals(BeeperOrder.RECENT, BeeperPrefs.read(mapOf("beeper.order" to "recent")).order)
        assertEquals(BeeperOrder.PRIORITY, BeeperPrefs.read(mapOf("beeper.order" to "loud")).order)
        assertEquals(BeeperOrder.PRIORITY, BeeperPrefs.read(emptyMap<String, Any>()).order)
    }

    @Test fun sharedPrefsStoreTheOrderUnderBeeperDotOrder() {
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("beeper-pri-test", Context.MODE_PRIVATE)
        val store = SharedBeeperPrefs(shared)
        var heard = 0
        val stop = store.listen { heard++ }
        store.update(BeeperPrefs(BeeperOrder.RECENT))
        assertEquals("RECENT", shared.getString("beeper.order", null))
        assertEquals(BeeperOrder.RECENT, store.prefs.order)
        assertTrue(heard >= 1)
        stop()
    }

    @Test fun findMatchesTitlesPeopleNicknamesAndMisheardNames() {
        assertEquals("sam", BeeperPriority.cleanQuery("Find the chat with Sam."))
        assertEquals("avery stone", BeeperPriority.cleanQuery("open my conversation with Avery Stone"))
        assertEquals("boys", BeeperPriority.cleanQuery("go to the boys group chat"))
        assertEquals(1.0, BeeperPriority.nameScore("sam", "Sam"), 1e-9)
        assertEquals(0.9, BeeperPriority.nameScore("sam", "Samantha Reyes"), 1e-9)
        assertEquals(0.86, BeeperPriority.nameScore("reyes", "Samantha Reyes"), 1e-9)
        assertTrue(BeeperPriority.nameScore("jon", "John") >= 0.5)
        assertTrue(BeeperPriority.nameScore("caitlin", "Kaitlyn") >= 0.5)
        assertEquals(0.0, BeeperPriority.nameScore("xavier", "Sam"), 1e-9)
        val chats = listOf(
            chat("Weekend crew", group = true, names = listOf("Avery Stone", "Blake Moss")),
            chat("Avery Stone"), chat("Blake Moss"), chat("Blair Moss"), chat("Maureen Ortiz"),
        )
        val avery = BeeperPriority.find("avery", chats)
        assertEquals(listOf("Avery Stone", "Weekend crew"), avery.map { it.chat.id })
        assertTrue(BeeperPriority.confident(avery))
        assertFalse(BeeperPriority.confident(BeeperPriority.find("moss", chats)))
        assertTrue(BeeperPriority.find("nobody here", chats).isEmpty())
        // A contact nickname finds the contact's chat.
        val mo = BeeperPriority.find("Mo", chats, mapOf("Maureen Ortiz" to listOf("Mo")))
        assertEquals("Maureen Ortiz", mo.first().chat.id)
        assertTrue(BeeperPriority.find("Mo", chats).none { it.chat.id == "Maureen Ortiz" })
    }

    @Test fun holdLOnTheListFindsAChatAndOpensAClearMatch() {
        val f = Fixture(); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        val target = list.talk!!
        assertEquals("raw", target.target); assertTrue(target.autoSend)
        // The dictation preview acts at once for a lookup target.
        val dict = DictationScreen(f.ui, target); f.ui.push(dict); dict.listen()
        dict.event(DictationEvent.Done("Sam"))
        assertEquals("thread-chat-0", f.ui.top.id)
        assertFalse(f.ui.stack.any { it is DictationScreen })
        assertSame(list, f.ui.stack[f.ui.stack.size - 2])
    }

    @Test fun aFindDiscardedWithBDuringTranscriptionNeverJumpsWhenTheWordsArriveLate() {
        val f = Fixture(); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        val dict = DictationScreen(f.ui, list.talk!!); f.ui.push(dict); dict.listen()
        dict.event(DictationEvent.Transcribing)
        dict.key("back")
        assertSame(list, f.ui.top)
        // Transcription is not cancelled by B; its late result lands on the popped preview and must do nothing.
        dict.event(DictationEvent.Done("Sam"))
        assertSame(list, f.ui.top)
        assertFalse(f.ui.stack.any { it is BeeperThread })
    }

    @Test fun anUnclearNameListsCandidatesAndNoMatchSaysSo() {
        val chats = listOf(chat("Blake Moss", 1), chat("Blair Moss"), chat("Sam"))
        val f = Fixture(chats); val list = Beeper(f.ui)
        f.ui.replaceTop(list)
        runBlocking { assertTrue(findChat(f.ui, "moss").ok) }
        val results = f.ui.top as BeeperFindScreen
        assertEquals(listOf("Blake Moss", "Blair Moss"), labels(results))
        assertEquals("moss", results.state.query)
        // Holding L again on the results replaces them in place.
        runBlocking { findChat(f.ui, "Sam", results) }
        assertEquals("thread-Sam", f.ui.top.id)
        assertFalse(f.ui.stack.any { it is BeeperFindScreen })
        val none = runBlocking { findChat(f.ui, "Xavier") }
        assertFalse(none.ok); assertEquals("No chat matches “xavier”", none.message)
        // From the X menu: Find opens the empty Find screen with its own talk target.
        f.ui.goHome(); f.ui.push(list); list.key("act")
        (f.ui.top as ListView).key("sel")
        val find = f.ui.top as BeeperFindScreen
        assertEquals(listOf("Hold L and say a name"), labels(find))
        assertTrue(find.talk!!.autoSend)
    }

    @Test fun fakePinsToggleAndTheThreadTitleUsesChatLookup() {
        val f = Fixture()
        runBlocking { assertEquals("Pinned on glasses", f.ui.data.beeper.setPin("chat-2", true).message) }
        assertTrue(f.ui.data.beeper.chat("chat-2")!!.glassesPinned)
        val list = Beeper(f.ui); f.ui.replaceTop(list)
        assertEquals("Family", list.rows()[0].label)
        runBlocking { f.ui.data.beeper.setPin("chat-2", false) }
        assertEquals("Sam", Beeper(f.ui).rows()[0].label)
    }

    // ---- Live data store: order query, pin route, find merging ----
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private fun TestScope.store(f: FixtureGateway) = LiveDataStore(context(), backgroundScope, f.client, { now }, { testScheduler.currentTime / 1000.0 }, startBackground = false)
    private fun row(id: String, name: String, vararg extra: Pair<String, JsonElement>) = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "network" to JsonPrimitive("Instagram"), "unread" to JsonPrimitive(0),
        "last" to obj("sender" to str("Ann"), "text" to str("hi"), "mine" to JsonPrimitive(false))) + extra)

    @Test fun liveStoreAsksForRecentOnlyWhenSetAndDecodesPriorityFields() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            f.fixtures["GET /beeper/chats"] = JsonArray(listOf(row("a", "Ann", "priority" to str("important")!!, "importance" to JsonPrimitive(0.7), "why" to JsonArray(listOf(str("mention")!!, JsonPrimitive(3))),
                "pinned" to JsonPrimitive(true), "glassesPinned" to JsonPrimitive(true), "participants" to obj("count" to JsonPrimitive(3), "names" to JsonArray(listOf(str("Ann")!!, str("Bo")!!)))),
                row("b", "Bo", "participants" to JsonPrimitive("garbage"))))
            data.beeper.refreshChats()
            assertEquals("limit=100", f.sent.last { it.path == "/beeper/chats" }.query)
            val a = data.beeper.chats.first()
            assertEquals(listOf("important", "0.7", "mention"), listOf(a.priority, a.importance.toString(), a.why.single()))
            assertTrue(a.pinned && a.glassesPinned); assertEquals(listOf("Ann", "Bo"), a.participants.names)
            assertEquals("b", data.beeper.chats[1].id) // a malformed summary does not drop the row
            data.beeper.useOrder(BeeperOrder.RECENT)
            advanceUntilIdle(); data.beeper.refreshChats()
            assertEquals("limit=100&order=recent", f.sent.last { it.path == "/beeper/chats" }.query)
        } finally { data.close() }
    }

    @Test fun liveStorePinsThroughTheGatewayAndFindMergesGatewayMatches() = runTest {
        val f = FixtureGateway(); val data = store(f)
        try {
            f.fixtures["GET /beeper/chats"] = JsonArray(listOf(row("a", "Avery Stone"), row("b", "Blake")))
            data.beeper.refreshChats()
            val pinned = data.beeper.setPin("a", true)
            assertTrue(pinned.ok); assertEquals("Pinned on glasses", pinned.message)
            val sent = f.sent.first { it.method == "POST" }
            assertEquals("/beeper/chats/a/pin", sent.path)
            assertEquals(true, gatewayJson.parseToJsonElement(sent.body).jsonObject["pinned"]!!.jsonPrimitive.boolean)
            // The gateway knows a chat beyond the loaded page: it is merged in and has a name for its thread.
            f.fixtures["GET /beeper/find"] = obj("query" to str("riley"), "confident" to JsonPrimitive(true),
                "matches" to JsonArray(listOf(JsonObject(row("far", "Riley Park") + ("match" to obj("score" to JsonPrimitive(0.9)))))))
            val found = data.beeper.find("Riley")
            assertEquals(listOf("far"), found.matches.map { it.chat.id }); assertTrue(found.confident)
            assertEquals("q=Riley&limit=5", f.sent.last { it.path == "/beeper/find" }.query)
            assertEquals("Riley Park", data.beeper.chat("far")!!.name)
            assertNull(data.beeper.chats.find { it.id == "far" })
            // A gateway without /beeper/find still finds loaded chats.
            f.fixtures["GET /beeper/find"] = JsonPrimitive("<html>not found</html>")
            assertEquals("a", data.beeper.find("avery").matches.first().chat.id)
        } finally { data.close() }
    }
}
