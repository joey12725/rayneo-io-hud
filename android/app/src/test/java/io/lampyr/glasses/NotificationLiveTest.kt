package io.lampyr.glasses

import android.content.*
import io.lampyr.glasses.agent.*
import io.lampyr.glasses.agent.ops.NotificationOps
import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.notificationRows
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

/** LiveDataStore side of the routing contract: what reaches POST /notify/route, delivery, events, withdrawals, deep links. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationLiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val epoch = Instant.parse("2026-11-17T20:00:00Z").toEpochMilli()
    /** The dating preset is off by default; these tests turn it on the way the phone Settings page would. */
    private fun datingPreset() {
        io.lampyr.glasses.settings.CompanionConfig.current = io.lampyr.glasses.settings.CompanionConfig(notifyPolicy = """{"dating":{"enabled":true}}""")
    }
    @org.junit.After fun resetConfig() { io.lampyr.glasses.settings.CompanionConfig.current = io.lampyr.glasses.settings.CompanionConfig() }
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private class Gateway : GatewayClient("https://fixture.example", "test") {
        val rows = mutableMapOf<String, JsonElement>()
        val writes = mutableListOf<Pair<String, JsonElement>>()
        var offline = false
        var slowMs = 0L
        override suspend fun get(path: String): JsonElement { if (offline) throw GatewayFailure("Offline"); return rows[path] ?: JsonArray(emptyList()) }
        override suspend fun write(path: String, body: JsonElement, method: String): JsonElement {
            if (offline) throw GatewayFailure("Offline")
            writes += path to body
            if (slowMs > 0) delay(slowMs)
            return rows[path] ?: obj("ok" to JsonPrimitive(true))
        }
    }
    private fun TestScope.store(g: Gateway) = LiveDataStore(context(), backgroundScope, g, { epoch }, { testScheduler.currentTime / 1000.0 }, false)
    private fun row(text: String) = gatewayJson.parseToJsonElement(text)
    private fun beeper(title: String, body: String) = NoticeInput(kind = "beeper", source = "com.beeper.android", app = "Beeper", title = title, body = body, channelId = "MESSAGES_5")

    @Test fun beeperAlwaysAsksTheGatewayWithTheFullContractAndKeepsItsOpenTarget() = runTest {
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        try {
            g.rows["/notify/route"] = row("""{"route":"stream","importance":0.3,"reason":"beeper.transactional","by":"rule","open":{"app":"beeper","target":"!o:beeper.local"}}""")
            data.phoneNotification("txn", beeper("62881", "Your card was charged $12.40").copy(network = "SMS"), postedAt = epoch - 60_000)
            val body = g.writes.single().second.jsonObject
            assertEquals(setOf("kind", "source", "app", "title", "body", "conversationTitle", "sender", "network", "isGroup", "channelId"), body.keys)
            val n = data.notifications.items.single()
            assertEquals("stream", n.route); assertEquals(epoch - 60_000, n.at); assertEquals(OpenTarget("beeper", mapOf("target" to "!o:beeper.local")), n.open)
            assertTrue(popups.isEmpty())
            // Muted on the gateway: suppressed, nothing listed.
            g.rows["/notify/route"] = row("""{"route":"suppress","importance":0,"reason":"beeper.muted","by":"rule"}""")
            data.phoneNotification("muted", beeper("Muted group", "Anyone around?"))
            assertEquals(1, data.notifications.items.size)
            // Bad answers are ignored in favour of the offline fallback (stream, never popup).
            g.rows["/notify/route"] = row("""{"route":"notify","importance":0.9}""")
            data.phoneNotification("bad", beeper("Sam", "Dinner at 7?"))
            assertEquals("stream", data.notifications.items.first { it.id == "phone:bad" }.route); assertTrue(popups.isEmpty())
            // A Beeper share link is suppressed on the phone without a network call.
            val writes = g.writes.size
            data.phoneNotification("reel", beeper("Sam", "https://www.instagram.com/reel/abc/"))
            assertEquals(writes, g.writes.size); assertFalse(data.notifications.items.any { it.id == "phone:reel" })
        } finally { data.close() }
    }

    @Test fun realTinderAndFeeldChannelsRouteOnThePhoneAndTheDebugCheckAgrees() = runTest {
        datingPreset()
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        fun tinder(ch: String, title: String, body: String, category: String = "") = NoticeInput(kind = "app", source = "com.tinder", app = "Tinder", title = title, body = body, channelId = ch, category = category)
        try {
            // Channel rules decide on the phone: no gateway call for a match, a message, a like or a top-picks promo.
            data.phoneNotification("m", tinder("MATCHES", "It's a Match!", "You and Riley liked each other"))
            data.phoneNotification("msg", tinder("MESSAGES", "Riley", "See you at 8?", "msg"))
            data.phoneNotification("like", tinder("NEW_LIKES", "You have a new like", ""))
            data.phoneNotification("top", tinder("TOP_PICKS", "Your Top Picks are here", ""))
            data.phoneNotification("feeld", NoticeInput(kind = "app", source = "co.feeld", app = "Feeld", title = "Feeld", body = "Someone new is nearby", channelId = "com_appboy_default_notification_channel"))
            assertTrue(g.writes.isEmpty())
            assertEquals(listOf("phone:m", "phone:msg"), popups)
            assertEquals(mapOf("phone:m" to "popup", "phone:msg" to "popup", "phone:like" to "stream"), data.notifications.items.associate { it.id to it.route })
            // An ambiguous text on Tinder's catch-all channel is the one case that asks the gateway (its classifier).
            g.rows["/notify/route"] = row("""{"route":"suppress","importance":0,"reason":"model","by":"model"}""")
            data.phoneNotification("amb", tinder("UNCATEGORIZED", "Tinder", "Riley is active now"))
            assertEquals("/notify/route", g.writes.single().first)
            // The debug check reports the same split, and never any text.
            val m = data.checkPhoneNotification(tinder("MATCHES", "It's a Match!", "You and Riley liked each other"))
            assertEquals("phone-rule", m["decidedBy"]); assertEquals("popup", m["route"])
            val amb = data.checkPhoneNotification(tinder("UNCATEGORIZED", "Tinder", "Riley is active now"))
            assertEquals("gateway", amb["decidedBy"]); assertEquals("suppress", amb["offlineRoute"])
            assertFalse((m.values + amb.values).any { it.toString().contains("Riley") })
            assertEquals(1, g.writes.size) // checking routes nothing
        } finally { data.close() }
    }

    @Test fun aSlowGatewayFallsBackWithoutPoppingUp() = runTest {
        val g = Gateway(); val data = store(g); g.slowMs = NOTIFY_ROUTE_TIMEOUT_MS + 1_000
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        g.rows["/notify/route"] = row("""{"route":"popup","importance":0.9,"reason":"late","by":"model"}""")
        try {
            data.phoneNotification("late", beeper("Sam", "Dinner at 7?"))
            val n = data.notifications.items.single()
            assertEquals("stream", n.route); assertEquals("model unavailable", n.reason); assertEquals(.4, n.importance, .001)
            assertTrue(popups.isEmpty())
            // The row still knows how to find its chat later.
            assertEquals("beeper", n.open!!.screen); assertEquals("Sam", n.open!!.args["name"])
        } finally { data.close() }
    }

    @Test fun datingRulesRunOfflineAndPopUpOnlyForMatchesAndMessages() = runTest {
        datingPreset()
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        try {
            data.phoneNotification("m", NoticeInput(source = "com.tinder", app = "Tinder", title = "You have a new match!", body = "Say hi", channelId = "MATCHES"))
            data.phoneNotification("l", NoticeInput(source = "com.tinder", app = "Tinder", title = "Someone liked you", channelId = "NEW_LIKES"))
            data.phoneNotification("p", NoticeInput(source = "com.tinder", app = "Tinder", title = "Your Top Picks are here", channelId = "TOP_PICKS"))
            assertTrue("deterministic: no gateway call", g.writes.isEmpty())
            assertEquals(listOf("phone:m"), popups)
            assertEquals(setOf("phone:m", "phone:l"), data.notifications.items.map { it.id }.toSet())
            assertNull("no glasses app for dating rows", data.notifications.items.first { it.id == "phone:l" }.open)
            // Ambiguous dating text asks the classifier; offline it is suppressed.
            g.offline = true
            data.phoneNotification("a", NoticeInput(source = "com.takimi.android", app = "Taimi", title = "Taimi", body = "Alex is online now", channelId = "other"))
            assertFalse(data.notifications.items.any { it.id == "phone:a" })
        } finally { data.close() }
    }

    @Test fun joyEventsCarryThePopupHintAndAwayRepliesPopUpUnlessQuiet() = runTest {
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        try {
            data.handleEvent(row("""{"type":"notification","id":"j1","seq":1,"source":"Hermes","title":"Leave now","body":"Traffic","importance":0.9,"route":"popup","at":"2026-11-17T20:00:00Z"}"""))
            data.handleEvent(row("""{"type":"notification","id":"j2","seq":2,"source":"Hermes","title":"FYI","body":"Report","importance":0.5,"at":"2026-11-17T20:00:00Z"}"""))
            assertEquals(listOf("j1"), popups)
            assertEquals("stream", data.notifications.items.first { it.id == "j2" }.route)
            data.onScreenChanged("home")
            data.handleEvent(row("""{"type":"joy_reply","id":"r1","seq":3,"text":"Here you go","sessionId":"s"}"""))
            assertEquals(2, popups.size)
            assertEquals("popup", data.notifications.items.first { it.title == "Hermes replied" }.route)
        } finally { data.close() }
    }

    @Test fun aPopupThatArrivesLateOnlyJoinsTheList() = runTest {
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        try {
            // Replayed after the phone was offline for ten minutes: no stale lens wake-up.
            data.handleEvent(row("""{"type":"notification","id":"old","seq":1,"source":"Hermes","title":"Leave now","body":"Traffic","importance":0.9,"route":"popup","at":"2026-11-17T19:50:00Z"}"""))
            assertTrue(popups.isEmpty()); assertEquals("stream", data.notifications.items.single { it.id == "old" }.route)
            g.rows["/notify/route"] = row("""{"route":"popup","importance":0.9,"reason":"asks the owner","by":"model"}""")
            data.phoneNotification("stale", beeper("Sam", "Dinner at 7?"), postedAt = epoch - POPUP_MAX_AGE_MS - 1)
            assertTrue(popups.isEmpty()); assertEquals("stream", data.notifications.items.single { it.id == "phone:stale" }.route)
            data.phoneNotification("fresh", beeper("Sam", "Dinner at 8?"), postedAt = epoch - 1_000)
            assertEquals(listOf("phone:fresh"), popups)
        } finally { data.close() }
    }

    @Test fun anUnchangedRepostIsRoutedOnceAndNeverPopsUpTwice() = runTest {
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        g.rows["/notify/route"] = row("""{"route":"popup","importance":0.9,"reason":"asks the owner","by":"model"}""")
        try {
            data.phoneNotification("k", beeper("Sam", "Dinner at 7?"))
            data.phoneNotification("k", beeper("Sam", "Dinner at 7?"))
            assertEquals(1, g.writes.count { it.first == "/notify/route" }); assertEquals(listOf("phone:k"), popups)
            // A new message under the same key is routed again.
            data.phoneNotification("k", beeper("Sam", "Or 8?"))
            assertEquals(2, g.writes.count { it.first == "/notify/route" }); assertEquals(2, popups.size)
            // After the phone withdrew it, the same text is a new notification.
            data.withdrawPhoneNotification("k")
            data.phoneNotification("k", beeper("Sam", "Or 8?"))
            assertEquals(3, g.writes.count { it.first == "/notify/route" })
        } finally { data.close() }
    }

    @Test fun t3CodeAppNotificationsAreSuppressedOnThePhoneEvenForAWatchedThread() = runTest {
        val g = Gateway(); val data = store(g)
        try {
            g.rows["/t3/watch"] = row("""[{"key":"mac:a","machine":"mac","id":"a","title":"Fix the login flow"}]""")
            data.t3.watch("mac", "a", "Fix the login flow", true)
            data.phoneNotification("t", NoticeInput(kind = "t3", source = "com.t3tools.t3code", app = "T3 Code", title = "Fix the login flow", body = "Approval required"))
            data.phoneNotification("u", NoticeInput(kind = "app", source = "com.t3tools.t3code", app = "T3 Code", title = "Fix the login flow", body = "Finished"))
            assertTrue(data.notifications.items.isEmpty())
            assertFalse(g.writes.any { it.first == "/notify/route" })
        } finally { data.close() }
    }

    @Test fun t3NotificationsArriveAsGatewayEventsNeverFromThePhonePoll() = runTest {
        val g = Gateway(); val data = store(g)
        val popups = mutableListOf<String>(); data.notifications.listenPopups { popups += it.id }
        fun threads(status: String, revision: Int) { g.rows["/t3/threads"] = row("""{"machines":[{"machine":"mac","label":"Mac","online":true}],"threads":[{"machine":"mac","id":"a","title":"Alpha","project":"P","provider":"Codex","status":"$status","updatedAt":"2026-11-17T20:00:0${revision}Z"}]}""") }
        try {
            threads("working", 0); data.t3.refreshThreads()
            assertTrue(data.t3.watch("mac", "a", "Alpha", true).ok)
            assertEquals("/t3/watch", g.writes.last().first); assertEquals(true, g.writes.last().second.jsonObject["on"]!!.jsonPrimitive.boolean)
            threads("approval", 1); data.t3.refreshThreads(); threads("done", 2); data.t3.refreshThreads()
            assertTrue("the poll adds nothing", data.notifications.items.isEmpty())
            // The gateway notifier's event (server lib/t3-notify.mjs): popup for needs-you, open target the thread.
            data.handleEvent(row("""{"type":"notification","seq":1,"at":"2026-11-17T20:00:00Z","id":"t3:mac:a:approval:2026-11-17T20:00:01Z","source":"T3","icon":"t3","title":"Alpha","body":"Needs you · approve npm test","importance":0.9,"route":"popup","reason":"t3.needs-you","open":{"screen":"t3","args":{"target":"mac:a"},"app":"t3","target":"mac:a"}}"""))
            val n = data.notifications.items.single()
            assertEquals("popup", n.route); assertEquals("t3.needs-you", n.reason); assertEquals("t3", n.icon); assertEquals("Needs you · approve npm test", n.body)
            assertEquals(OpenTarget("t3", mapOf("target" to "mac:a")), n.open); assertEquals(listOf("t3:mac:a:approval:2026-11-17T20:00:01Z"), popups)
            data.handleEvent(row("""{"type":"notification","seq":2,"at":"2026-11-17T20:00:00Z","id":"t3:mac:a:done:2026-11-17T20:00:02Z","source":"T3","icon":"t3","title":"Alpha","body":"Finished · done","importance":0.6,"route":"stream","reason":"t3.done","open":{"screen":"t3","args":{"target":"mac:a"}}}"""))
            assertEquals("stream", data.notifications.items.first { it.reason == "t3.done" }.route); assertEquals(1, popups.size)
            data.t3.watch("mac", "a", "Alpha", false); assertFalse(data.t3.isWatched("mac", "a"))
        } finally { data.close() }
    }

    @Test fun notifyAllNeedsSettingReadsAndWritesTheGatewaySetting() = runTest {
        val g = Gateway(); val data = store(g)
        try {
            assertTrue(data.t3.notifyAllNeeds) // default on before the first read
            g.rows["/t3/notify/settings"] = row("""{"needsAll":false}""")
            assertTrue(data.t3.refreshNotifySettings().ok); assertFalse(data.t3.notifyAllNeeds)
            g.rows["/t3/notify/settings"] = row("""{"needsAll":true}""")
            assertTrue(data.t3.setNotifyAllNeeds(true).ok)
            assertEquals("/t3/notify/settings", g.writes.last().first); assertEquals(true, g.writes.last().second.jsonObject["needsAll"]!!.jsonPrimitive.boolean)
            assertTrue(data.t3.notifyAllNeeds)
        } finally { data.close() }
    }

    @Test fun withdrawnPhoneNotificationsLeaveTheListAndBeeperRowsResolveTheirChatOnOpen() = runTest {
        val g = Gateway(); val data = store(g)
        try {
            g.offline = true
            data.phoneNotification("k1", beeper("Sam", "Dinner at 7?"))
            assertEquals(1, data.notifications.items.size)
            data.withdrawPhoneNotification("k1"); assertTrue(data.notifications.items.isEmpty())
            data.phoneNotification("k2", beeper("Sam", "Dinner at 8?"))
            g.offline = false
            g.rows["/beeper/resolve"] = row("""{"resolved":true,"chatId":"!sam:beeper.local","open":{"app":"beeper","target":"!sam:beeper.local"}}""")
            val open = data.notifications.resolveOpen(data.notifications.items.single())
            assertEquals(OpenTarget("beeper", mapOf("target" to "!sam:beeper.local")), open)
            assertEquals("Sam", g.writes.last().second.jsonObject["title"]!!.jsonPrimitive.content)
            g.rows["/beeper/resolve"] = row("""{"resolved":false,"reason":"ambiguous"}""")
            assertEquals(OpenTarget("beeper"), data.notifications.resolveOpen(data.notifications.items.single()))
        } finally { data.close() }
    }

    @Test fun notificationOpsListOpenDismissAndClear(): Unit = io.lampyr.glasses.agent.ActionRegistryTest.Harness(listOf(NotificationOps)).use { h ->
        val list = h.call("notifications.list").data!!.jsonArray
        assertTrue(list.isNotEmpty()); assertTrue(list.first().jsonObject.containsKey("route"))
        val first = list.first().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(Codes.INVALID_ARGS, h.call("notifications.dismiss").code)
        assertEquals(Codes.FAILED, h.call("notifications.dismiss", mapOf("id" to "no such thing at all")).code)
        assertTrue(h.call("notifications.dismiss", mapOf("id" to first)).ok)
        assertFalse(h.ui.notificationRows().any { it.id == first })
        val sam = h.ui.notificationRows().firstOrNull { it.open != null }
        if (sam != null) {
            val opened = h.call("notifications.open", mapOf("id" to sam.id))
            assertTrue(opened.message, opened.ok)
            assertFalse(h.ui.stack.any { it.id == "notif" })
        }
        assertTrue(h.call("notifications.clear").ok)
        assertTrue(h.ui.data.notifications.items.isEmpty())
    }
}

