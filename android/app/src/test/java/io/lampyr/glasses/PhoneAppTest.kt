package io.lampyr.glasses

import io.lampyr.glasses.audio.DictationEvent
import io.lampyr.glasses.audio.FakeDictation
import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pure phone-book rules: query parsing, ranking, "call mom" resolution, favourites, refusals and the call rule. */
class PhoneBookTest {
    private val book = FakePhoneBook { Instant.parse("2026-11-12T01:45:00Z").toEpochMilli() }
    private val contacts get() = book.contacts

    @Test fun parsesSpokenRequests() {
        assertEquals(CallQuery("mom"), parseCallQuery("call mom"))
        assertEquals(CallQuery("Mom", "mobile"), parseCallQuery("Call Mom on her cell."))
        assertEquals(CallQuery("Sam Rivera", "work"), parseCallQuery("please call Sam Rivera at work"))
        assertEquals(CallQuery("mom"), parseCallQuery("Hey ${io.lampyr.glasses.settings.Names.assistant}, call my mom"))
        assertEquals(CallQuery("mom"), parseCallQuery("ok assistant call my mom"))
        assertEquals("312 555 0142", parseCallQuery("dial 312 555 0142").number)
        assertEquals(CallQuery("Dana Park"), parseCallQuery("Dana Park"))
        assertEquals(CallQuery("home", null), parseCallQuery("home")) // a bare label word stays a name
    }

    @Test fun ranksNamesNicknamesRelationshipsAndMisspellings() {
        assertEquals("Sam Rivera", rankContacts("Sam Riviera", contacts).first().contact.name)
        assertEquals("Mom", rankContacts("mother", contacts).first().contact.name)
        assertEquals("Mom", rankContacts("mum", contacts).first().contact.name)
        assertEquals("Dad", rankContacts("father", contacts).first().contact.name)
        assertEquals("Marcus Lee", rankContacts("marcus", contacts).first().contact.name)
        assertEquals("Tess Nguyen", rankContacts("nguyen", contacts).first().contact.name)
        assertTrue(rankContacts("zebra", contacts).isEmpty())
        val withNickname = contacts + Contact("c-x", "Robert Smith", listOf(ContactNumber("(555) 555-0100", "Mobile")), nicknames = listOf("Bobby"))
        assertEquals("Robert Smith", rankContacts("bobby", withNickname).first().contact.name)
        val relationOnly = listOf(Contact("c-j", "Jane Doe", listOf(ContactNumber("(555) 555-0109", "Mobile")), relations = listOf("mother")))
        assertEquals("Jane Doe", (resolveCallee(parseCallQuery("call mom"), relationOnly) as Resolution.Found).callee.name)
    }

    @Test fun callMomResolvesOrAsksInsteadOfGuessing() {
        val mom = resolveCallee(parseCallQuery("call mom"), contacts) as Resolution.Found
        assertEquals("(630) 555-0101", mom.callee.number); assertEquals("Mobile", mom.callee.label)
        assertEquals("(630) 555-0100", (resolveCallee(parseCallQuery("call mom at home"), contacts) as Resolution.Found).callee.number)
        assertEquals("(312) 555-0110", (resolveCallee(parseCallQuery("ben"), contacts) as Resolution.Found).callee.number)
        assertEquals("(312) 555-0111", (resolveCallee(CallQuery("ben", "work"), contacts) as Resolution.Found).callee.number)
        val ambiguous = resolveCallee(parseCallQuery("da"), contacts) as Resolution.Ambiguous
        assertEquals(listOf("Dad", "Dana Park"), ambiguous.candidates.map { it.name })
        assertTrue(resolveCallee(CallQuery("zed"), contacts) is Resolution.NotFound)
        assertTrue(resolveCallee(CallQuery("Priya", "mobile"), contacts) is Resolution.NotFound)
        // Two numbers, no primary, no single mobile: ask which.
        val twoHomes = listOf(Contact("c-y", "Yan Li", listOf(ContactNumber("(555) 555-0111", "Home"), ContactNumber("(555) 555-0112", "Work"))))
        assertEquals(2, (resolveCallee(CallQuery("yan"), twoHomes) as Resolution.Ambiguous).candidates.size)
        val number = resolveCallee(parseCallQuery("call 312-555-0142"), contacts) as Resolution.Found
        assertEquals("Sam Rivera", number.callee.name); assertEquals("3125550142", number.callee.number)
    }

    @Test fun emergencyAndCodesAreNeverDialled() {
        assertEquals("Emergency numbers: use the phone", dialable("911").second)
        assertNull(dialable(" 1 1 2 ").first)
        assertNull(dialable("*#06#").first)
        assertNull(dialable("#31#5551234").first)
        assertNull(dialable("call me").first)
        assertNull(dialable("").first)
        assertEquals("+13125550142", dialable("+1 (312) 555-0142").first)
        assertTrue(resolveCallee(CallQuery("", number = "911"), contacts) is Resolution.NotFound)
    }

    @Test fun favoritesAreStarredThenFrequentCallees() {
        val favs = favorites(book)
        assertEquals(listOf("Dad", "Mom", "Sam Rivera", "Alex Chen", "Marcus Lee"), favs.map { it.first.name })
        assertEquals(listOf(true, true, true, false, false), favs.map { it.second })
        assertEquals(2, favorites(book, 2).size)
    }

    @Test fun recentsCollapseConsecutiveRowsForTheSameNumberAndKind() {
        val rows = listOf(
            RecentCall("1", "(312) 555-0166", "Marcus", true, RecentKind.Outgoing, 3),
            RecentCall("2", "+1 312-555-0166", "Marcus", true, RecentKind.Outgoing, 2),
            RecentCall("3", "(312) 555-0166", "Marcus", true, RecentKind.Missed, 1),
        )
        val out = collapseRecents(rows)
        assertEquals(2, out.size); assertEquals(2, out.first().count); assertEquals(RecentKind.Missed, out[1].kind)
        assertEquals("missed 33m", recentRight(book.recents.first(), Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()))
        assertEquals("screened 5h", recentRight(book.recents[2], Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()))
    }

    @Test fun callRuleTakesOverOnlyForContacts() {
        val base = PhoneCall("1", CallPhase.Ringing)
        assertEquals(CallAlert.Takeover, callAlert(base.copy(contact = ContactStatus.Known)))
        assertEquals(CallAlert.Takeover, callAlert(base.copy(contact = ContactStatus.Starred)))
        assertEquals(CallAlert.Takeover, callAlert(base.copy(contact = ContactStatus.Unavailable)))
        assertEquals(CallAlert.Quiet, callAlert(base.copy(contact = ContactStatus.Unknown)))
        assertEquals(CallAlert.Silent, callAlert(base.copy(contact = ContactStatus.Unknown, spam = true)))
        assertEquals(CallAlert.Wait, callAlert(base.copy(contact = ContactStatus.Pending)))
        assertTrue(spamCallerLabel("Scam Likely")); assertTrue(spamCallerLabel("SPAM RISK")); assertFalse(spamCallerLabel("Sam Rivera"))
        // the user's call policy: prior real calls take over (even over a spam label), except a withheld number.
        val unknown = base.copy(name = "(872) 555-0199", contact = ContactStatus.Unknown)
        assertEquals(CallAlert.Takeover, callAlert(unknown.copy(priorAnsweredCalls = 2)))
        assertEquals(CallAlert.Takeover, callAlert(unknown.copy(ownerCalled = true, spam = true)))
        assertEquals(CallAlert.Quiet, callAlert(unknown.copy(privateNumber = true, priorAnsweredCalls = 3)))
        assertEquals(CallAlert.Silent, callAlert(unknown.copy(verificationFailed = true)))
        assertEquals(CallAlert.Silent, callAlert(base.copy(contact = ContactStatus.Unavailable, spam = true)))
        assertEquals(CallAlert.Quiet, callAlert(base.copy(contact = ContactStatus.Unavailable, privateNumber = true, spam = true)))
        // A local business by caller ID takes over; a survey company does not.
        assertEquals(CallAlert.Takeover, callAlert(unknown.copy(callerId = "DOMINOS PIZZA")))
        assertEquals(CallAlert.Quiet, callAlert(unknown.copy(callerId = "OPINION SURVEY")))
        assertEquals(CallerHistory(1, true), callerHistory(listOf(Triple("+1 (872) 555-0199", RecentKind.Incoming, 40L), Triple("8725550199", RecentKind.Outgoing, 0L),
            Triple("8725550199", RecentKind.Incoming, 0L), Triple("3125550142", RecentKind.Incoming, 90L)), "(872) 555-0199"))
        assertEquals(CallerHistory(), callerHistory(listOf(Triple("911", RecentKind.Outgoing, 1L)), ""))
        // Unknown callers ring quietly and leave a missed-call notice; spam never does.
        val calls = FakeCalls()
        calls.incoming("(872) 555-0199", "(872) 555-0199", ContactStatus.Unknown); calls.remoteEnd(calls.snapshot.ringing!!.id)
        assertEquals(1, calls.missed.size)
        calls.publish(calls.snapshot.copy(calls = listOf(PhoneCall("spam-1", CallPhase.Ringing, "Scam Likely", contact = ContactStatus.Unknown, spam = true))))
        calls.publish(calls.snapshot.copy(calls = emptyList()))
        assertEquals(1, calls.missed.size)
        calls.incoming(); calls.remoteEnd(calls.snapshot.ringing!!.id)
        assertEquals(2, calls.missed.size)
    }

    @Test fun routesAreChosenByKindNameOrIdAndNumbersMasked() {
        val routes = listOf(AudioRoute("7", "Pixel Buds Pro", RouteKind.Bluetooth), AudioRoute("8", "Earpiece", RouteKind.Earpiece),
            AudioRoute("9", "Speaker", RouteKind.Speaker), AudioRoute("10", "Honda HFL", RouteKind.Bluetooth))
        assertEquals("7", pickRoute(routes, "bluetooth")!!.id)
        assertEquals("8", pickRoute(routes, "handset")!!.id)
        assertEquals("9", pickRoute(routes, "Speaker")!!.id)
        assertEquals("10", pickRoute(routes, "honda")!!.id)
        assertEquals("10", pickRoute(routes, "10")!!.id)
        assertNull(pickRoute(routes, "wired")); assertNull(pickRoute(routes, null))
        assertEquals("***0142", maskNumber("(312) 555-0142"))
        assertEquals("Handset", routeKindLabel(RouteKind.Earpiece))
    }

    @Test fun fakePlacementDialsThenConnectsAndRefusesBadNumbers() = runBlocking {
        val calls = FakeCalls({ 10.0 })
        assertFalse(calls.place("911").ok); assertFalse(calls.place("*#06#").ok); assertTrue(calls.placed.isEmpty())
        assertTrue(calls.place("(630) 555-0101", "speaker").ok)
        val c = calls.snapshot.ongoing!!
        assertEquals(CallPhase.Dialing, c.phase); assertEquals("Mom", c.name); assertEquals("mobile", c.label)
        assertEquals(CallDirection.Outgoing, c.direction); assertEquals("speaker", calls.snapshot.currentRoute)
        assertFalse(calls.place("(312) 555-0142").ok) // already on a call
        calls.connect(c.id); assertEquals(CallPhase.Active, calls.snapshot.ongoing!!.phase)
        assertTrue(calls.end(c.id).ok); assertTrue(calls.missed.isEmpty())
        calls.placeAllowed = false; assertFalse(calls.place("(312) 555-0142").ok)
        assertEquals(listOf("6305550101"), calls.placed)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneAppTest {
    private var now = 100.0
    private val fixedMs = Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
    private fun ui(display: Display = Display.Bitmap): Ui {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Ui(FakeDataStore(RuntimeEnvironment.getApplication(), { 100.0 }, { fixedMs }, scope), scope = scope, nowS = { now },
            nowMs = { fixedMs }, zone = ZoneId.of("America/Chicago")).apply { this.display = display }
    }
    private fun Ui.fake() = data.calls as FakeCalls
    private fun text(v: View, cols: Int = 26, rows: Int = 7) = TextGrid(cols, rows, uppercase = false).also { v.drawText(it) }.lines()

    @Test fun phoneIsTheLastAppAndSavedOrdersGainIt() {
        val ui = ui()
        try {
            appEntries(ui).map { it.id }.let { ids -> assertEquals(ids.indexOf("notifications") + 1, ids.indexOf("phone")) } // launcher order: Phone follows Notifications on page 1
            assertEquals(5, DEFAULT_APP_ORDER.indexOf("phone"))
            runBlocking { ui.data.settings.update(ui.data.settings.snapshot.copy(appOrder = listOf("joy", "status"))) }
            assertTrue("a saved order gains Phone", appEntries(ui).any { it.id == "phone" })
            val apps = Apps(ui).apply { i = appEntries(ui).indexOfFirst { it.id == "phone" } }; ui.push(apps); apps.key("sel")
            assertTrue(ui.top is PhoneRoot)
            ui.goHome(); ui.open(OpenTarget("phone")); assertTrue(ui.top is PhoneRoot)
            assertTrue(ui.openTarget("phone", null)); assertTrue(ui.top is PhoneRoot)
        } finally { ui.close() }
    }

    @Test fun tabsSwitchWithArrowsAndContactsJumpByLetterWithX() {
        val ui = ui()
        try {
            val root = PhoneRoot(ui); ui.push(root)
            assertEquals(PhoneTab.Favorites, root.tab)
            assertEquals(listOf("Dad", "Mom", "Sam Rivera", "Frequent", "Alex Chen", "Marcus Lee"), root.rows().map { it.label })
            root.key("act"); assertEquals(PhoneTab.Recents, root.tab)
            assertEquals("Sam Rivera", root.rows().first().label)
            root.key("right"); assertEquals(PhoneTab.Contacts, root.tab)
            assertEquals("Alex Chen", root.rows()[root.cur].label)
            root.key("act"); assertEquals("Ben Ortiz", root.rows()[root.cur].label)
            root.key("act"); assertEquals("Dad", root.rows()[root.cur].label)
            root.key("act"); assertEquals("Marcus Lee", root.rows()[root.cur].label)
            var presses = 0
            while (root.rows()[root.cur].label != "Alex Chen" && presses++ < root.rows().count { it.sep }) root.key("act")
            assertEquals("Alex Chen", root.rows()[root.cur].label) // after the last letter X wraps to A
            assertEquals(0, root.top)
            assertEquals(PhoneTab.Contacts, root.tab)
            root.key("right"); assertEquals(PhoneTab.Favorites, root.tab)
            root.key("left"); assertEquals(PhoneTab.Contacts, root.tab)
            assertTrue(text(root).first().startsWith("Contacts"))
            assertEquals("phone", io.lampyr.glasses.agent.AgentScreen.currentApp(ui))
        } finally { ui.close() }
    }

    /** Left/right switch tab from every tab, even mid-list on Contacts, and B always leaves the Phone app. */
    @Test fun arrowsNeverTrapATabAndBAlwaysLeaves() {
        val ui = ui()
        try {
            for (start in PhoneTab.entries) {
                val root = PhoneRoot(ui, start); ui.push(root)
                repeat(3) { root.key("down") }
                for (k in listOf("right", "left")) {
                    val order = mutableListOf(root.tab)
                    repeat(PhoneTab.entries.size) { assertTrue(root.key(k)); order += root.tab }
                    assertEquals("$k from $start visits every tab", PhoneTab.entries.toSet(), order.toSet())
                    assertEquals(order.first(), order.last())
                    assertTrue(order.zipWithNext().all { (a, b) -> a != b })
                }
                assertEquals("Previous tab", root.legend()["left"]); assertEquals("Next tab", root.legend()["right"])
                repeat(3) { root.key("down") }
                assertFalse(root.key("back")) // B is never consumed: the input engine pops the screen
                ui.pop(); assertFalse(ui.stack.contains(root))
            }
        } finally { ui.close() }
    }

    @Test fun everyPlacementNeedsOneYAndOpensTheDialingScreen() {
        val ui = ui()
        try {
            val root = PhoneRoot(ui); ui.push(root)
            root.cur = 1; root.key("sel")                       // Mom
            val confirm = ui.top as PhoneConfirm
            assertEquals("(630) 555-0101", confirm.callee.number)
            assertTrue(ui.fake().placed.isEmpty())             // nothing dialled before Y
            assertTrue(text(confirm).any { it.contains("Call Mom?") || it.contains("Mom") })
            confirm.key("up"); confirm.key("act"); assertTrue(ui.fake().placed.isEmpty())
            confirm.key("sel")
            assertEquals(listOf("6305550101"), ui.fake().placed)
            val screen = ui.top as PhoneCallScreen
            assertEquals(CallPhase.Dialing, ui.data.calls.snapshot.calls.single { it.id == screen.callId }.phase)
            assertTrue(screen.keepAwake)
            assertTrue(text(screen).first().startsWith("Calling"))
            assertTrue(text(screen).any { it.contains("mobile") })
            ui.fake().connect(screen.callId)
            assertFalse("a connected call may fade", screen.keepAwake)
            screen.key("plus"); assertTrue(ui.data.calls.snapshot.calls.isEmpty())
            assertTrue(ui.top is PhoneRoot)
        } finally { ui.close() }
    }

    @Test fun bCancelsAndFailuresStayOnThePhone() {
        val ui = ui()
        try {
            val root = PhoneRoot(ui, PhoneTab.Recents); ui.push(root)
            root.key("sel")                                     // call back Sam
            assertEquals("(312) 555-0142", (ui.top as PhoneConfirm).callee.number)
            ui.top.key("back"); assertTrue(ui.top is PhoneRoot); assertTrue(ui.fake().placed.isEmpty())
            ui.fake().placeAllowed = false
            root.key("sel"); ui.top.key("sel")
            assertTrue(ui.top is PhoneRoot); assertEquals("Allow Place calls in phone setup", ui.currentToast)
            assertTrue(ui.data.calls.snapshot.calls.isEmpty())
        } finally { ui.close() }
    }

    @Test fun confirmCardNeverHoldsTheLensForeverAndAFailedPlaceForgetsTheExpectation() {
        val ui = ui()
        try {
            val root = PhoneRoot(ui); ui.push(root)
            root.cur = 1; root.key("sel")
            val confirm = ui.top as PhoneConfirm
            assertTrue(confirm.keepAwake)
            now += PhoneConfirm.AWAKE_S + 1
            assertFalse("an unanswered call card falls back to the idle fade", confirm.keepAwake)
            // A refused placement must not make a call the user later dials on the phone jump onto the glasses.
            ui.fake().placeAllowed = false
            confirm.key("sel"); assertTrue(ui.top is PhoneRoot)
            ui.fake().placeAllowed = true
            runBlocking { ui.data.calls.place("(312) 555-0142") }   // as if dialled on the phone itself
            assertTrue(ui.top is PhoneRoot)
        } finally { ui.close() }
    }

    @Test fun contactsWithSeveralNumbersAskWhichOne() {
        val ui = ui()
        try {
            // Ben has one mobile number: it is the default, straight to the confirm card.
            callContact(ui, ui.data.calls.book.contacts.first { it.name == "Ben Ortiz" })
            assertEquals("(312) 555-0110", (ui.top as PhoneConfirm).callee.number)
            ui.pop()
            callContact(ui, Contact("c-y", "Yan Li", listOf(ContactNumber("(555) 555-0111", "Home"), ContactNumber("(555) 555-0112", "Work"))))
            val numbers = ui.top as PhoneNumbers
            assertEquals(listOf("Home", "Work"), numbers.rows().map { it.label })
            numbers.cur = 1; numbers.key("sel")
            assertEquals("(555) 555-0112", (ui.top as PhoneConfirm).callee.number)
        } finally { ui.close() }
    }

    @Test fun sayANameSearchesOnThePhoneAndActsOnTheTranscript() {
        val ui = ui()
        try {
            val root = PhoneRoot(ui); ui.push(root)
            val target = root.talk!!
            assertTrue(target.instant); assertEquals("raw", target.target)
            assertTrue(runBlocking { target.send("call mom") }.ok); assertEquals("Mom", (ui.top as PhoneConfirm).callee.name)
            ui.pop()
            assertTrue(runBlocking { target.send("call da") }.ok); assertEquals(listOf("Dad", "Dana Park"), (ui.top as PhoneSearch).rows().map { it.label })
            ui.top.key("sel"); assertEquals("Dad", (ui.top as PhoneConfirm).callee.name)
            ui.pop()
            assertEquals("No contact named \"nobody\"", runBlocking { target.send("call nobody") }.message)
            // The dictation preview is skipped: the transcript goes straight to the search.
            val dict = DictationScreen(ui, target); ui.push(dict); dict.listen()
            dict.event(DictationEvent.Done("call mom"))
            assertTrue(ui.top is PhoneConfirm); assertFalse(dict in ui.stack)
            // During a call the microphone belongs to the call.
            ui.goHome(); val again = PhoneRoot(ui); ui.push(again)
            ui.fake().incoming(contact = ContactStatus.Unknown)
            assertTrue(again.talkTap()); assertEquals("Microphone reserved for phone call", ui.currentToast)
        } finally { ui.close() }
    }

    @Test fun unknownNumbersRingQuietlyAndContactsTakeOver() {
        val ui = ui()
        try {
            val calls = ui.fake()
            val previous = ui.stack.toList(); ui.locked = true
            calls.incoming("(872) 555-0199", "(872) 555-0199", ContactStatus.Unknown)
            assertEquals(previous, ui.stack); assertTrue(ui.locked)
            assertTrue(callNotifications(ui).none { it.id == "call-current" })
            ui.locked = false
            assertTrue(text(Home(ui)).any { it.contains("Phone: (872) 555-0199") })
            calls.remoteEnd(calls.snapshot.ringing!!.id); assertEquals(1, calls.missed.size) // in the list, never a takeover
            // A pending lookup waits, then a contact takes over.
            calls.incoming("Sam Rivera", "(312) 555-0142", ContactStatus.Pending)
            assertEquals(previous, ui.stack)
            calls.publish(calls.snapshot.copy(calls = calls.snapshot.calls.map { it.copy(contact = ContactStatus.Known) }))
            assertTrue(ui.top is PhoneCallScreen); assertTrue(ui.top.keepAwake)
        } finally { ui.close() }
    }

    @Test fun homeRowNeverSaysNullForScreeningOrEndingCalls() {
        val ui = ui()
        try {
            ui.fake().publish(CallsSnapshot(listOf(PhoneCall("s", CallPhase.Screening, "(872) 555-0199", "(872) 555-0199"))))
            val home = Home(ui)
            assertTrue(text(home).none { it.contains("null") })
            assertTrue(text(home).any { it.contains("Phone: (872)") })
        } finally { ui.close() }
    }

    @Test fun idleCallsOpenRecentsInsteadOfACallEndedCard() {
        val ui = ui()
        try {
            ui.openCalls(); assertEquals(PhoneTab.Recents, (ui.top as PhoneRoot).tab)
            ui.goHome(); ui.push(ui.app("calls")); assertTrue(ui.top is PhoneRoot)
        } finally { ui.close() }
    }

    @Test fun phoneScreensFitBothTextGridsWithFixturesAt20x5() {
        val names = mapOf<String, (Ui) -> View>(
            "phone-favorites-20" to { PhoneRoot(it) },
            "phone-recents-20" to { PhoneRoot(it, PhoneTab.Recents) },
            "phone-contacts-20" to { PhoneRoot(it, PhoneTab.Contacts) },
            "phone-confirm-20" to { PhoneConfirm(it, Callee("Mom", "(630) 555-0101", "Mobile")) },
            "call-dialing-20" to { runBlocking { it.data.calls.place("(630) 555-0101") }; PhoneCallScreen(it, it.data.calls.snapshot.ongoing!!.id) },
        )
        names.forEach { (name, factory) ->
            val ui = ui(Display.Text(20, 5)).also { now = 0.0 }
            try {
                val actual = text(factory(ui), 20, 5).joinToString("\n") + "\n"
                val file = File("src/test/resources/textgolden/$name.txt")
                if (System.getenv("UPDATE_TEXT_GOLDENS") == "1") file.writeText(actual)
                assertTrue("Missing golden: $name", file.exists())
                assertEquals(name, file.readText().replace("\r\n", "\n"), actual)
            } finally { ui.close(); now = 100.0 }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPhonePlacementTest {
    @Test fun placingNeedsCallPhoneAndRefusesEmergencyNumbers() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AndroidCalls(app, scope)
        try {
            assertFalse(calls.canPlace)
            assertEquals("Allow Place calls in phone setup", calls.place("(312) 555-0142").message)
            shadowOf(app).grantPermissions(android.Manifest.permission.CALL_PHONE)
            assertTrue(calls.canPlace)
            assertEquals("Emergency numbers: use the phone", calls.place("911").message)
            assertFalse(calls.place("*#06#").ok)
            assertTrue(calls.place("(312) 555-0142").ok)
            assertFalse(calls.book.access.contacts)
            assertTrue(calls.book.contacts.isEmpty())
        } finally { calls.close(); scope.cancel() }
    }
}
