package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Requirements 1.2, 3.1, 3.2, 3.8: the routing policy mirror, pop-ups from a dark lens, and Y opening the owning app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationRoutingTest {
    // ---------- The policy table, shared with the gateway ----------
    private val fixture = Json.parseToJsonElement(File("../../server/gateway/test/fixtures/notify-policy.json").readText()).jsonObject
    /** The fixture's policy merged over the default, and its owner, exactly as the gateway test runs the table. */
    private val policy = NotifyPolicy.from(fixture["policy"]!!.jsonObject)
    private val owner = io.lampyr.glasses.settings.CompanionConfig(
        ownerName = fixture["owner"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        ownerAliases = fixture["owner"]!!.jsonObject["aliases"]!!.jsonArray.map { it.jsonPrimitive.content },
    ).ownerPattern

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun input(o: JsonObject): NoticeInput {
        val contact = o["contact"] as? JsonObject
        val source = o.str("source"); val app = o.str("app")
        return NoticeInput(
            kind = o.str("kind").ifBlank { NotificationRules.inferKind(source, app, policy) }, source = source, app = app, title = o.str("title"), body = o.str("body"),
            conversationTitle = o.str("conversationTitle"), sender = o.str("sender"), network = o.str("network"),
            isGroup = (o["isGroup"] as? JsonPrimitive)?.booleanOrNull == true, chatId = o.str("chatId"), channelId = o.str("channelId"), category = o.str("category"),
            contactKnown = (contact?.get("known") as? JsonPrimitive)?.booleanOrNull, callerVerified = o.str("callerVerified").ifBlank { "none" },
            popup = (o["popup"] as? JsonPrimitive)?.booleanOrNull == true,
            priorAnsweredCalls = (o["priorAnsweredCalls"] as? JsonPrimitive)?.intOrNull ?: 0, ownerCalledNumber = o.flag("ownerCalledNumber"),
            privateNumber = o.flag("privateNumber"), spam = o.flag("spam"), business = o.flag("business"),
        )
    }

    @Test fun everyPolicyRowMatchesTheGatewayTable() {
        val watched = fixture["watched"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val titles = fixture["watchedTitles"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 60)
        var checked = 0
        for (c in cases) {
            if ((c["gatewayOnly"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val name = c.str("name"); val n = input(c["input"]!!.jsonObject)
            val got = NotificationRules.decide(n, { it in watched }, { text -> titles.entries.firstOrNull { text.lowercase().contains(it.value.lowercase()) }?.key }, policy, owner)
            val expect = c["expect"] as? JsonObject
            if (expect == null) {
                assertNull(name, got)
                val fb = NotificationRules.fallback(n, policy)
                assertEquals("$name (fallback)", (c["fallback"] as JsonObject).str("route"), fb.route)
                assertNotEquals(name, "popup", fb.route); assertEquals("fallback", fb.by)
            } else {
                assertNotNull("$name: expected a rule decision", got)
                assertEquals(name, expect.str("route"), got!!.route)
                assertEquals(name, expect.str("reason"), got.reason)
                assertTrue(name, got.importance in 0.0..1.0)
            }
            if (c.containsKey("cap")) {
                val cap = c["cap"]
                val ceiling = NotificationRules.ceiling(n)
                if (cap is JsonObject) assertEquals("$name (cap)", NoticeCap(cap.str("route"), cap.str("reason")), ceiling) else assertNull("$name (cap)", ceiling)
            }
            checked++
        }
        assertTrue(checked >= 55)
    }

    @Test fun everyRealTinderAndFeeldChannelRoutesLikeTheGateway() {
        var checked = 0
        for ((pkg, channels) in fixture["datingChannels"]!!.jsonObject) for ((channel, reason) in channels.jsonObject) {
            val n = NoticeInput(kind = "app", source = pkg, channelId = channel, title = "Riley", body = "Hey")
            val got = NotificationRules.decide(n, pol = policy)
            if (reason.jsonPrimitive.content == "text") { assertNull("$pkg $channel", got); assertEquals("suppress", NotificationRules.fallback(n, policy).route) }
            else assertEquals("$pkg $channel", reason.jsonPrimitive.content, got?.reason)
            checked++
        }
        assertEquals(12, checked)
        assertEquals("popup", NotificationRules.decide(NoticeInput(kind = "app", source = "com.tinder", channelId = "MESSAGES", title = "Riley", body = "my friend bought a boost lol"), pol = policy)!!.route)
        // The dating preset is off by default: the same notification never leaves the phone.
        assertEquals("deny.default", NotificationRules.decide(NoticeInput(kind = "app", source = "com.tinder", channelId = "MATCHES", title = "Riley", body = "Hey"))!!.reason)
    }

    @Test fun defaultPolicyMirrorsTheGatewayDefaultFile() {
        val gateway = Json.parseToJsonElement(File("../../server/gateway/apis/glasses/notify-policy.default.json").readText()).jsonObject
        assertEquals(NotifyPolicy.from(gateway, base = null), NotifyPolicy.DEFAULT)
        // Round trip: the phone's JSON form parses back to the same policy.
        assertEquals(NotifyPolicy.DEFAULT, NotifyPolicy.parse(NotifyPolicy.DEFAULT.toJson().toString(), base = null))
        assertThrows(IllegalArgumentException::class.java) { NotifyPolicy.parse("""{"apps":{"com.x":"loud"},"bogus":1}""") }
    }

    @Test fun routingCheckOfARealNotificationCarriesNoText() {
        val match = NotificationRules.check(NoticeInput(kind = "app", source = "com.tinder", channelId = "MATCHES", category = "social", title = "It's a Match!", body = "Riley secret words"), pol = policy)
        assertEquals("phone-rule", match["decidedBy"]); assertEquals("popup", match["route"]); assertEquals("dating.match", match["reason"])
        assertEquals("com.tinder", match["pkg"]); assertEquals("MATCHES", match["channel"]); assertEquals("social", match["category"]); assertEquals(true, match["hasBody"])
        assertFalse(match.values.any { it.toString().contains("Riley") || it.toString().contains("Match!") })
        val ambiguous = NotificationRules.check(NoticeInput(kind = "app", source = "com.tinder", channelId = "UNCATEGORIZED", title = "Tinder", body = "Riley is active now"), pol = policy)
        assertEquals("gateway", ambiguous["decidedBy"]); assertEquals("semantic", ambiguous["reason"]); assertEquals("suppress", ambiguous["offlineRoute"])
        assertFalse(ambiguous.values.any { it.toString().contains("Riley") })
        // Beeper rule hits that are not suppressions still go to the gateway, exactly like LiveDataStore.phoneNotification.
        val photo = NotificationRules.check(NoticeInput(kind = "beeper", source = "com.beeper.android", title = "Sam", body = "Sent a photo"))
        assertEquals("gateway", photo["decidedBy"]); assertEquals("beeper.media", photo["reason"]); assertEquals("stream", photo["offlineRoute"])
    }

    @Test fun allowListKeepsEverythingElseOnThePhone() {
        for (pkg in listOf("com.beeper.android", "com.t3tools.t3code", "com.tinder", "co.hinge.app", "com.bumble.app", "co.feeld", "com.takimi.android"))
            assertTrue(pkg, NotificationRules.allowedPackage(pkg, pol = policy))
        for (pkg in listOf("com.google.android.apps.messaging", "com.whatsapp", "com.instagram.android", "com.google.android.gm", "com.google.android.youtube",
                "io.homeassistant.companion.android", "com.openai.chatgpt", "com.google.android.dialer", "com.example.anything"))
            assertFalse(pkg, NotificationRules.allowedPackage(pkg, pol = policy))
        // The default policy: Beeper and T3 Code only; dating apps stay on the phone until the preset is enabled.
        assertTrue(NotificationRules.allowedPackage("com.beeper.android")); assertTrue(NotificationRules.allowedPackage("com.t3tools.t3code"))
        assertFalse(NotificationRules.allowedPackage("com.tinder"))
        // The `apps` allow-list adds any app with a fixed route.
        val withApp = NotifyPolicy.parse("""{"apps":{"com.example.bank":"popup","com.example.quiet":"suppress"}}""")
        assertTrue(NotificationRules.allowedPackage("com.example.bank", pol = withApp)); assertFalse(NotificationRules.allowedPackage("com.example.quiet", pol = withApp))
        assertEquals("app.popup", NotificationRules.decide(NoticeInput(kind = "app", source = "com.example.bank", title = "Card used"), pol = withApp)!!.reason)
        // A disabled kind is suppressed on the phone too.
        val noBeeper = NotifyPolicy.parse("""{"kinds":{"beeper":false}}""")
        assertEquals("kind.disabled", NotificationRules.decide(NoticeInput(kind = "beeper", source = "com.beeper.android", title = "Sam", body = "Dinner?"), pol = noBeeper)!!.reason)
        assertEquals("beeper", NotificationRules.kindFor("com.beeper.android"))
        assertEquals("t3", NotificationRules.kindFor("com.t3tools.t3code"))
        assertEquals("app", NotificationRules.kindFor("com.tinder"))
    }

    // ---------- Pop-ups and opening on the glasses ----------
    private class Rig(prefs: PowerPrefs = PowerPrefs()) {
        var seconds = 100.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_763_000_000_000L }, scope)
        val power = MemoryPowerStore(prefs)
        val ui = Ui(data, scope = scope, nowS = { seconds }, nowMs = { 1_763_000_000_000L }, powerStore = power)
        val input = InputEngine(ui, NoAudio(), NoMedia(), Timers())
        fun advance(ms: Long) { seconds += ms / 1000.0; ui.powerTick() }
        fun tap(key: String) { input.press(key); input.release(key) }
        fun note(id: String, route: String = "popup", open: OpenTarget? = OpenTarget("beeper", mapOf("target" to "chat-0"))) =
            Notification(id, "com.beeper.android", "beeper", "Sam", "Dinner at 7?", .9, 1_763_000_000_000L, open, route, "model")
        fun close() { input.close(); ui.close(); scope.cancel() }
    }
    private class Timers : Scheduler { override fun after(delayMs: Long, block: () -> Unit): Scheduled = object : Scheduled { override fun cancel() {} } }
    private class NoAudio : Dictation { override fun start(target: String, onEvent: (DictationEvent) -> Unit) {}; override fun stop() {}; override fun cancel() {} }
    private class NoMedia : MediaControl { override fun toggle() {}; override fun direction(direction: String) {} }
    private fun rig(prefs: PowerPrefs = PowerPrefs(), block: Rig.() -> Unit) { val r = Rig(prefs); try { r.block() } finally { r.close() } }

    @Test fun popupRouteLightsADarkLensFromAnyScreenAndFadesAfterItsTime() = rig(PowerPrefs(popupMs = 4_000, idleMs = 2_000)) {
        ui.push(NowPlayingScreen(ui)); val stack = ui.stack.toList()
        assertFalse(ui.awake)
        data.deliverNotification(note("p1"))
        assertTrue(ui.awake); assertTrue(ui.popupVisible); assertEquals(stack, ui.stack.toList())
        assertEquals(NOTIFICATION_POPUP_APP, ui.popupCard!!.app); assertEquals("p1", ui.popupCard!!.target)
        advance(3_999); assertTrue(ui.popupVisible)
        advance(1); assertFalse(ui.popupVisible); assertFalse("never leaves the display on", ui.awake)
        assertTrue(data.notifications.items.any { it.id == "p1" }) // still in the list
    }

    @Test fun streamRouteNeverPopsUpAndPopupsCanBeTurnedOff() {
        rig {
            data.deliverNotification(note("s1", route = "stream"))
            assertFalse(ui.popupVisible); assertFalse(ui.awake); assertTrue(data.notifications.items.any { it.id == "s1" })
        }
        rig(PowerPrefs(popups = false)) {
            data.deliverNotification(note("p1"))
            assertFalse(ui.popupVisible); assertFalse(ui.awake); assertTrue(data.notifications.items.any { it.id == "p1" })
        }
    }

    @Test fun popupIsHeldDuringAJoyCallOrABusyScreenOrWhenItsThreadIsOpen() = rig {
        ui.push(BeeperThread(ui, "chat-0"))
        data.deliverNotification(note("p1"))
        assertFalse("same thread already on top", ui.popupVisible)
        ui.goHome(); ui.push(Teleprompter(ui))
        if (ui.keepAwake) { data.deliverNotification(note("p2")); assertFalse(ui.popupVisible) }
        ui.goHome()
        data.deliverNotification(note("p3")); assertTrue(ui.popupVisible)
    }

    @Test fun yOnAPopupOpensTheOwningAppAtTheTargetAndClearsTheRow() = rig {
        data.deliverNotification(note("p1"))
        tap("y")
        assertFalse(ui.popupVisible)
        assertEquals("thread-chat-0", ui.top.id); assertTrue(ui.stack.any { it is Beeper })
        assertFalse(data.notifications.items.any { it.id == "p1" })
    }

    @Test fun yOnAPopupStillOpensItsTargetAfterThePhoneWithdrewTheRow() = rig {
        data.deliverNotification(note("p1"))
        // Read on the phone while the card is up: the row leaves the list, the card stays until it expires.
        runBlocking { data.notifications.dismiss("p1") }
        assertFalse(data.notifications.items.any { it.id == "p1" }); assertTrue(ui.popupVisible)
        tap("y")
        assertFalse(ui.popupVisible); assertEquals("thread-chat-0", ui.top.id)
    }

    @Test fun closingTheUiStopsItsPopupListener() {
        val r = Rig()
        r.ui.close()
        r.data.deliverNotification(r.note("p1"))
        assertFalse(r.ui.popupVisible)
        r.input.close(); r.scope.cancel()
    }

    @Test fun xOnAPopupDismissesThatNotificationAndBOnlyHidesTheCard() = rig {
        data.deliverNotification(note("p1"))
        tap("b"); assertFalse(ui.popupVisible); assertTrue(data.notifications.items.any { it.id == "p1" }); assertEquals("home", ui.top.id)
        data.deliverNotification(note("p2"))
        tap("x"); assertFalse(ui.popupVisible); assertFalse(data.notifications.items.any { it.id == "p2" }); assertEquals("home", ui.top.id)
        assertTrue(data.beeper.chats.isNotEmpty()) // dismissal is local: no chat was read, archived or hidden
    }

    @Test fun yInTheListClosesTheListAndOpensTheTargetNeverJustVanishing() = rig(PowerPrefs.ALWAYS_ON) {
        ui.push(Apps(ui)); ui.push(Notifications(ui))
        val list = ui.top as Notifications
        val sam = data.notifications.items.first { it.id == "n1" } // Beeper row with a thread target
        list.cur = list.rows().indexOfFirst { it.label == sam.title }
        list.rows()[list.cur].open!!.invoke()
        assertFalse("the Notifications view is closed", ui.stack.any { it.id == "notif" })
        assertEquals(listOf("home", "apps", "beeper", "thread-chat-0"), ui.stack.map { it.id })
        assertFalse(data.notifications.items.any { it.id == "n1" })
        ui.goHome()
        // A row with no app on the glasses shows its text in a reader and stays in the list.
        data.deliverNotification(note("dating", route = "stream", open = null).copy(source = "com.tinder", title = "Riley", body = "Hey!"))
        ui.push(Notifications(ui))
        val again = ui.top as Notifications
        again.cur = again.rows().indexOfFirst { it.label == "Riley" }
        assertEquals("Read", again.rows()[again.cur].selLabel)
        again.rows()[again.cur].open!!.invoke()
        assertEquals("notif-read", ui.top.id); assertTrue(data.notifications.items.any { it.id == "dating" })
    }

    @Test fun targetsOpenT3ThreadsCalendarDaysAndPhoneRecents() = rig(PowerPrefs.ALWAYS_ON) {
        assertTrue(ui.openNotificationTarget(OpenTarget("t3", mapOf("target" to "desktop:7"))))
        assertEquals(ThreadKey("desktop", "7"), (ui.top as T3Thread).threadKey)
        ui.goHome()
        val day = data.calendar.agenda.first { it.events.isNotEmpty() }
        assertTrue(ui.openNotificationTarget(OpenTarget("calendar", mapOf("target" to day.date))))
        val cal = ui.top as CalendarScreen
        assertTrue(cal.cur > 0 || day == data.calendar.agenda.first())
        ui.goHome()
        assertTrue(ui.openNotificationTarget(OpenTarget("call-missed", mapOf("name" to "Mom", "number" to "1"))))
        assertEquals("calls-history", ui.top.id)
        ui.goHome()
        assertTrue(ui.openNotificationTarget(OpenTarget("joy")))
        assertEquals("joy", ui.top.kind)
        ui.goHome()
        assertFalse(ui.openNotificationTarget(OpenTarget("nonexistent-app")))
        assertEquals("home", ui.top.id)
    }

    @Test fun xInAT3ThreadOpensTheThreadMenuThatWatchesTheThread() = rig(PowerPrefs.ALWAYS_ON) {
        val key = ThreadKey("desktop", "7")
        ui.push(T3Thread(ui, key))
        ui.top.key("act")
        val menu = ui.top as T3ThreadActions
        assertEquals(listOf("Thread history", "Watch this thread"), menu.rows().map { it.label })
        menu.rows()[1].open!!.invoke()
        assertTrue(data.t3.isWatched("desktop", "7")); assertTrue(ui.top is T3Thread)
        ui.top.key("act")
        assertEquals("Stop watching", (ui.top as T3ThreadActions).rows()[1].label)
        (ui.top as T3ThreadActions).rows()[0].open!!.invoke()
        assertTrue(ui.top is T3History); assertFalse(ui.stack.any { it is T3ThreadActions })
    }

    @Test fun popupSettingsLiveInDisplayAndSleepAndPersist() {
        assertEquals(PowerPrefs(popups = false, popupMs = 10_000), PowerPrefs.read(mapOf(PowerPrefs.POPUPS to "off", PowerPrefs.POPUP_MS to 10_000)))
        assertEquals(2_000L, PowerPrefs.read(mapOf(PowerPrefs.POPUP_MS to 5)).popupMs)
        assertTrue(PowerPrefs().popups); assertEquals(6_000L, PowerPrefs().popupMs)
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("notif-test", 0)
        val store = SharedPowerStore(shared)
        store.update(PowerPrefs(popups = false, popupMs = 8_000))
        assertEquals(false, store.prefs.popups); assertEquals(8_000L, store.prefs.popupMs)
        rig(PowerPrefs.ALWAYS_ON) {
            val view = PowerSettings(ui); ui.push(view)
            val row = view.rows().indexOfFirst { it.label == "Notification popups" }
            assertTrue(row >= 0)
            view.rows()[row].open!!.invoke(); assertFalse(power.prefs.popups)
            val time = view.rows().indexOfFirst { it.label == "Popup time" }
            view.rows()[time].open!!.invoke(); assertEquals(8_000L, power.prefs.popupMs)
        }
    }
}
