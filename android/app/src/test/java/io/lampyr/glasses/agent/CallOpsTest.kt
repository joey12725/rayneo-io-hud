package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ActionRegistryTest.Harness
import io.lampyr.glasses.agent.ops.CallOps
import io.lampyr.glasses.calls.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** the assistant's phone ops: the "call mom" walkthrough, confirm policy, refusals and in-call control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallOpsTest {
    private fun Harness.fake() = ui.data.calls as FakeCalls
    private fun JsonElement?.obj() = this!!.jsonObject

    @Test fun opsAreRegisteredWithValidManifestEntries(): Unit = Harness(listOf(CallOps)).use { h ->
        listOf("contacts.search", "call.place", "call.answer", "call.decline", "call.hangup", "call.mute", "call.route", "call.routes", "call.recents", "call.state")
            .forEach { assertTrue(it, it in h.registry.ops) }
        assertEquals(Risk.CALL, h.registry.ops["call.place"]!!.risk)
        assertEquals(Confirm.NEVER, h.registry.ops["call.hangup"]!!.confirm)
        assertEquals(Confirm.NEVER, h.registry.ops["call.decline"]!!.confirm)
        assertTrue(h.registry.ops.values.filter { it.app == "call" || it.app == "contacts" }.all { it.description.length <= 300 && it.title.length <= 40 })
    }

    @Test fun contactsSearchMasksNumbersUnlessAsked(): Unit = Harness(listOf(CallOps)).use { h ->
        val items = h.call("contacts.search", mapOf("query" to "mom")).data.obj()["items"]!!.jsonArray
        val mom = items.first().jsonObject
        assertEquals("Mom", mom["name"]!!.jsonPrimitive.content)
        assertEquals("***0101", mom["numbers"]!!.jsonArray.first().jsonObject["number"]!!.jsonPrimitive.content)
        val full = h.call("contacts.search", mapOf("query" to "mom", "reveal_numbers" to true)).data.obj()["items"]!!.jsonArray.first().jsonObject
        assertEquals("(630) 555-0101", full["numbers"]!!.jsonArray.first().jsonObject["number"]!!.jsonPrimitive.content)
        assertEquals(Codes.INVALID_ARGS, h.call("contacts.search").code)
    }

    @Test fun callMomFromTheGlassesConversationDialsAndShowsTheCallScreen(): Unit = Harness(listOf(CallOps)).use { h ->
        h.ui.locked = true
        val r = h.call("call.place", mapOf("contact" to "mom"), Origin.GLASSES_TURN)
        assertTrue(r.message, r.ok)
        assertEquals("Calling Mom (mobile)", r.message)
        assertEquals(listOf("6305550101"), h.fake().placed)
        val screen = h.ui.top as PhoneCallScreen
        assertEquals(CallPhase.Dialing, h.ui.data.calls.snapshot.calls.single { it.id == screen.callId }.phase)
        assertFalse(h.ui.locked); assertTrue(h.ui.awake)
        assertEquals("bt", h.ui.data.calls.snapshot.currentRoute) // current Bluetooth earbuds by default
        assertEquals("***0101", r.data.obj()["number"]!!.jsonPrimitive.content)
        // Busy now: a second call is refused before anything is dialled.
        assertEquals(Codes.UNAVAILABLE, h.call("call.place", mapOf("contact" to "dad"), Origin.GLASSES_TURN).code)
        assertEquals(1, h.fake().placed.size)
    }

    @Test fun outsideTheGlassesJoyGetsTheCallMomCardAndYPlaces(): Unit = Harness(listOf(CallOps)).use { h ->
        val pending = h.callAsync("call.place", mapOf("contact" to "mom"))
        val card = h.ui.top as PhoneConfirm
        assertTrue(card.fromJoy); assertEquals("Mom", card.callee.name)
        assertTrue(h.fake().placed.isEmpty())
        h.input.press("y"); h.input.release("y")
        val r = runBlocking { pending.await() }
        assertTrue(r.message, r.ok)
        assertEquals(listOf("6305550101"), h.fake().placed)
        assertTrue(h.ui.top is PhoneCallScreen)
    }

    @Test fun bOnTheCardIsDeniedAndAlwaysPolicyAsksEvenFromTheGlasses(): Unit = Harness(listOf(CallOps)).use { h ->
        val pending = h.callAsync("call.place", mapOf("contact" to "mom", "label" to "home"))
        assertEquals("(630) 555-0100", (h.ui.top as PhoneConfirm).callee.number)
        h.input.press("b"); h.input.release("b")
        assertEquals(Codes.DENIED, runBlocking { pending.await() }.code)
        assertTrue(h.fake().placed.isEmpty()); assertFalse(h.ui.top is PhoneConfirm)
        h.policy = ConfirmPolicy.ALWAYS
        val again = h.callAsync("call.place", mapOf("contact" to "mom"), Origin.GLASSES_TURN)
        assertTrue(h.ui.top is PhoneConfirm)
        h.input.press("b"); h.input.release("b")
        assertEquals(Codes.DENIED, runBlocking { again.await() }.code)
        h.policy = ConfirmPolicy.NEVER
        assertTrue(h.call("call.place", mapOf("contact" to "c-sam")).ok)
        assertEquals(listOf("3125550142"), h.fake().placed)
    }

    @Test fun joyCannotPressYOnACallCard(): Unit = Harness(listOf(CallOps)).use { h ->
        // Navigating the Phone app and pressing Y would bypass the CALL confirm policy.
        h.call("glasses.open_app", mapOf("app" to "phone"))
        val r = h.call("glasses.press", mapOf("button" to "y", "times" to 3))
        assertTrue(h.ui.top is PhoneConfirm)
        assertTrue(r.message, h.fake().placed.isEmpty())
        assertEquals(Codes.UNAVAILABLE, h.call("glasses.press", mapOf("button" to "y")).code)
        assertTrue(h.fake().placed.isEmpty())
    }

    @Test fun confirmCardTimesOutAsNothingDialled(): Unit = Harness(listOf(CallOps), confirmTimeoutMs = 100).use { h ->
        val r = h.call("call.place", mapOf("contact" to "mom"))
        assertEquals(Codes.TIMEOUT, r.code)
        assertTrue(h.fake().placed.isEmpty()); assertFalse(h.ui.stack.any { it is PhoneConfirm })
    }

    @Test fun aJoyCardSavedBehindAnIncomingCallCannotDialAfterItsWaitEnded(): Unit = Harness(listOf(CallOps), confirmTimeoutMs = 150).use { h ->
        val pending = h.callAsync("call.place", mapOf("contact" to "mom"))
        assertTrue(h.ui.top is PhoneConfirm)
        h.fake().incoming("Alex Chen", "(773) 555-0188")      // a contact rings: takeover saves the card behind it
        assertTrue(h.ui.top is PhoneCallScreen)
        assertEquals(Codes.TIMEOUT, runBlocking { pending.await() }.code)
        runBlocking { h.ui.data.calls.end(h.ui.data.calls.snapshot.ringing!!.id) }
        // The call ended: the saved stack comes back with the card on top, but Y on it only closes it.
        val stale = h.ui.top as PhoneConfirm
        assertTrue(stale.expired); assertFalse(stale.keepAwake)
        h.input.press("y"); h.input.release("y")
        assertTrue("The assistant was told nothing was dialled", h.fake().placed.isEmpty())
        assertFalse(h.ui.top is PhoneConfirm)
    }

    @Test fun ambiguousUnknownEmergencyAndPermissionNeverDial(): Unit = Harness(listOf(CallOps)).use { h ->
        val ambiguous = h.call("call.place", mapOf("contact" to "da"), Origin.GLASSES_TURN)
        assertEquals(Codes.AMBIGUOUS, ambiguous.code)
        assertEquals(listOf("Dad", "Dana Park"), ambiguous.data!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(Codes.FAILED, h.call("call.place", mapOf("contact" to "zed"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.FAILED, h.call("call.place", mapOf("number" to "911"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.FAILED, h.call("call.place", mapOf("number" to "*#06#"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("call.place", emptyMap(), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("call.place", mapOf("contact" to "mom", "label" to "pager"), Origin.GLASSES_TURN).code)
        h.fake().placeAllowed = false
        assertEquals(Codes.PERMISSION, h.call("call.place", mapOf("contact" to "mom"), Origin.GLASSES_TURN).code)
        assertTrue(h.fake().placed.isEmpty())
    }

    @Test fun answerMuteRouteHangUpAndState(): Unit = Harness(listOf(CallOps)).use { h ->
        assertEquals(Codes.UNAVAILABLE, h.call("call.answer").code)
        assertEquals(Codes.UNAVAILABLE, h.call("call.hangup").code)
        h.fake().incoming()
        val state = h.call("call.state").data.obj()
        val ringing = state["calls"]!!.jsonArray.single().jsonObject
        assertEquals("ringing", ringing["phase"]!!.jsonPrimitive.content)
        assertEquals("***0142", ringing["number"]!!.jsonPrimitive.content)
        assertTrue(ringing["canAnswer"]!!.jsonPrimitive.boolean)
        assertTrue(h.call("call.answer").ok)
        assertEquals(CallPhase.Active, h.ui.data.calls.snapshot.ongoing!!.phase)
        assertTrue(h.call("call.mute", mapOf("on" to true)).ok); assertTrue(h.ui.data.calls.snapshot.muted)
        val routes = h.call("call.routes").data.obj()["routes"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("bluetooth", "handset", "speaker"), routes)
        assertTrue(h.call("call.route", mapOf("endpoint" to "speaker")).ok); assertEquals("speaker", h.ui.data.calls.snapshot.currentRoute)
        assertTrue(h.call("call.route", mapOf("endpoint" to "handset")).ok); assertEquals("ear", h.ui.data.calls.snapshot.currentRoute)
        assertEquals(Codes.FAILED, h.call("call.route", mapOf("endpoint" to "wired")).code)
        assertTrue(h.call("call.hangup").ok); assertTrue(h.ui.data.calls.snapshot.calls.isEmpty())
        h.fake().incoming("Alex Chen", "(773) 555-0188")
        assertTrue(h.call("call.decline").ok); assertTrue(h.ui.data.calls.missed.isEmpty())
    }

    @Test fun recentsListTheCallLogNewestFirst(): Unit = Harness(listOf(CallOps)).use { h ->
        val items = h.call("call.recents", mapOf("limit" to 3)).data.obj()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Sam Rivera", "Mom", "Unknown"), items.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("missed", items.first()["kind"]!!.jsonPrimitive.content)
        assertTrue(items[2]["screened"]!!.jsonPrimitive.boolean)
        val missed = h.call("call.recents", mapOf("missed_only" to true)).data.obj()["items"]!!.jsonArray
        assertEquals(2, missed.size)
    }
}
