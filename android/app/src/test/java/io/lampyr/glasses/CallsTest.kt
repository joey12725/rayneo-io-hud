package io.lampyr.glasses

import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.audio.FakeDictation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class CallModelTest {
    @Test fun identityPrefersContactsThenNumberThenNotificationAndNeverBlank() {
        assertEquals("Sam", callerName(" Sam ", "123", "Someone"))
        assertEquals("123", callerName(" ", "123", "Someone"))
        assertEquals("Someone", callerName(null, null, "Someone"))
        assertEquals("Unknown caller", callerName(null, " ", " "))
    }
    @Test fun durationUsesConnectionBaselineAndSupportsHours() {
        val c = PhoneCall("1", CallPhase.Active, connectedAt = 100.0)
        assertEquals("0:00", callDuration(c, 99.0))
        assertEquals("1:05", callDuration(c, 165.9))
        assertEquals("1:01:05", callDuration(c, 3765.0))
        assertEquals("0:00", callDuration(c.copy(connectedAt = null), 3765.0))
    }
    @Test fun missedIsExactlyOnceAndDeclinedOrAnsweredCallsAreNotMissed() = runBlocking {
        val calls = FakeCalls({ 50.0 }, { 1234L })
        calls.incoming(); calls.remoteEnd(calls.snapshot.ringing!!.id)
        calls.publish(calls.snapshot)
        assertEquals(1, calls.missed.size)
        assertEquals("Phone", calls.missed.single().source)
        calls.incoming(); calls.end(calls.snapshot.ringing!!.id)
        calls.incoming(); val id = calls.snapshot.ringing!!.id
        calls.answer(id); calls.remoteEnd(id)
        assertEquals(1, calls.missed.size)
        calls.dismissMissed(calls.missed.single().id)
        assertTrue(calls.missed.isEmpty())
    }
    @Test fun waitingCanBeDeclinedWithoutEndingFirstCallAndRoutesValidateAvailability() = runBlocking {
        val calls = FakeCalls({ 10.0 })
        calls.incoming(); val first = calls.snapshot.ringing!!.id; calls.answer(first)
        calls.incoming("Alex"); calls.end(calls.snapshot.ringing!!.id)
        assertEquals(first, calls.snapshot.ongoing!!.id)
        assertTrue(calls.missed.isEmpty())
        assertTrue(calls.mute(true).ok); assertTrue(calls.snapshot.muted)
        assertFalse(calls.route("gone").ok)
        assertTrue(calls.route("speaker").ok); assertEquals("Speaker", calls.snapshot.routeName)
        calls.incoming("Alex"); calls.answer(calls.snapshot.ringing!!.id)
        assertEquals(CallPhase.Holding, calls.snapshot.calls.first().phase)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallsUiTest {
    private var now = 100.0
    private fun ui(): Ui {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return Ui(FakeDataStore(RuntimeEnvironment.getApplication(), { 100.0 }, { 1234L }, scope), scope = scope, nowS = { now })
    }
    @Test fun ringWakesLockHidingRestoresExactStackAndNeverRepops() {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            val list = Beeper(ui).apply { cur = 2 }; ui.push(list)
            val previous = ui.stack.toList(); ui.locked = true
            calls.incoming()
            assertFalse(ui.locked); assertTrue(ui.top is PhoneCallScreen)
            val id = calls.snapshot.ringing!!.id
            ui.top.key("act")
            assertEquals(previous, ui.stack); assertTrue(ui.locked)
            calls.publish(calls.snapshot.copy(calls = calls.snapshot.calls.map { it.copy(name = "Sam updated") }))
            assertEquals(previous, ui.stack); assertTrue(ui.locked)
            ui.locked = false; ui.openCalls(); assertTrue(ui.top is PhoneCallScreen)
            calls.remoteEnd(id)
            assertEquals(previous, ui.stack); assertEquals(1, calls.missed.size)
        } finally { ui.close() }
    }
    @Test fun controllerAnswersMutesSelectsAudioLeavesAndTapPlusEnds() {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        val scheduler = Scheduler { _, _ -> object : Scheduled { override fun cancel() {} } }
        val input = InputEngine(ui, FakeDictation(ui.data, ui.scope), scheduler = scheduler)
        fun tap(key: String) { input.press(key); input.release(key) }
        try {
            val previous = ui.stack.toList(); calls.incoming(); now += 0.5
            tap("y"); assertEquals(CallPhase.Active, calls.snapshot.ongoing!!.phase)
            tap("y"); assertTrue(calls.snapshot.muted)
            tap("x"); assertTrue(ui.top is CallAudio)
            tap("down"); tap("down"); tap("y"); assertEquals("Speaker", calls.snapshot.routeName)
            tap("b"); assertEquals(previous, ui.stack); assertNotNull(calls.snapshot.ongoing)
            ui.openCalls(); tap("plus")
            assertTrue(calls.snapshot.calls.isEmpty()); assertEquals(previous, ui.stack)
            assertTrue(calls.missed.isEmpty())
        } finally { input.close(); ui.close() }
    }
    @Test fun remoteEndRestoresPreviousScreenAndCallWaitingDeclineKeepsFirst() = runBlocking {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            val previous = ui.stack.toList(); calls.incoming(); val first = calls.snapshot.ringing!!.id
            calls.answer(first); calls.incoming("Alex"); now += 0.5
            ui.top.key("back")
            assertEquals(first, (ui.top as PhoneCallScreen).callId)
            calls.remoteEnd(first); assertEquals(previous, ui.stack)
            assertTrue(calls.missed.isEmpty())
        } finally { ui.close() }
    }
    @Test fun longIdentityAndFallbackExplanationsRemainPageableInBothTextSizes() {
        for (display in listOf(Display.Text(), Display.Text(20, 5))) {
            val ui = ui(); ui.display = display
            try {
                val calls = ui.data.calls as FakeCalls
                val name = (1..20).joinToString(" ") { "Person$it" }
                calls.incoming(name)
                calls.publish(calls.snapshot.copy(audioControl = false, limitation = "Mute / audio on phone"))
                val view = ui.top
                val pages = mutableListOf<String>()
                repeat(30) { val grid = io.lampyr.glasses.render.TextGrid(display.cols, display.rows, false); view.drawText(grid); pages += grid.lines().drop(1).dropLast(1).joinToString(" "); view.key("down") }
                val content = pages.joinToString(" ")
                (1..20).forEach { assertTrue("Missing Person$it", content.contains("Person$it")) }
                assertTrue(content.contains("Mute / audio on"))
            } finally { ui.close() }
        }
    }
    @Test @Config(sdk = [29, 31, 35]) fun missingPermissionsKeepBackendOptionalAndRejectAudioControls() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AndroidCalls(RuntimeEnvironment.getApplication(), scope)
        try {
            assertTrue(calls.snapshot.calls.isEmpty())
            assertFalse(calls.answer("missing").ok)
            assertFalse(calls.end("missing").ok)
            assertFalse(calls.mute(true).ok)
            assertFalse(calls.route("speaker").ok)
        } finally { calls.close(); scope.cancel() }
    }
    @Test @Config(sdk = [29, 31, 35]) fun basicTelephonyTransitionsDisableAmbiguousWaitingAndPersistMissed() {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AndroidCalls(context, scope)
        val phone = shadowOf(context.getSystemService(android.telephony.TelephonyManager::class.java))
        try {
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
            assertNotNull(calls.snapshot.ringing); assertTrue(calls.snapshot.ringing!!.answer)
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
            assertNotNull(calls.snapshot.ongoing); assertFalse(calls.snapshot.audioControl)
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
            assertFalse(calls.snapshot.ringing!!.answer); assertFalse(calls.snapshot.ringing!!.end)
            assertTrue(calls.snapshot.limitation.contains("waiting"))
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_IDLE)
            assertTrue(calls.missed.isEmpty()) // Aggregate state cannot classify the second caller.
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_IDLE)
            assertEquals(1, calls.missed.size)
            calls.close()
            val restored = AndroidCalls(context, scope)
            try { assertEquals(calls.missed, restored.missed); restored.dismissMissed(restored.missed.single().id) }
            finally { restored.close() }
        } finally { calls.close(); scope.cancel() }
    }
    @Test fun openingMissedNoticeReadsCallerAfterDismissingAndLongAudioNamesPage() {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            calls.incoming("Sam Rivera"); calls.remoteEnd(calls.snapshot.ringing!!.id)
            val notice = calls.missed.single()
            ui.push(Notifications(ui)); ui.top.key("sel")
            // Y on a missed call opens the phone app's recents (requirement 3.8), which still lists it.
            assertEquals("calls-history", ui.top.id); assertFalse(ui.stack.any { it.id == "notif" })
            assertEquals(notice, calls.missed.single())
            ui.top.key("sel")
            assertEquals("missed-call", ui.top.id)
            val grid = io.lampyr.glasses.render.TextGrid(26, 7, false)
            ui.top.drawText(grid)
            assertTrue(grid.lines().joinToString("\n").contains(notice.body))
            calls.incoming(); runBlocking { calls.answer(calls.snapshot.ringing!!.id) }
            val name = (1..35).joinToString(" ") { "Device$it" }
            calls.publish(calls.snapshot.copy(routes = listOf(AudioRoute("long", name, RouteKind.Bluetooth)), currentRoute = "long"))
            ui.display = Display.Text(20, 5)
            val picker = CallAudio(ui)
            val content = mutableListOf<String>()
            repeat(40) { val tg = io.lampyr.glasses.render.TextGrid(20, 5, false); picker.drawText(tg); content += tg.lines().joinToString(" "); picker.key("right") }
            (1..35).forEach { assertTrue("Missing Device$it", content.joinToString(" ").contains("Device$it")) }
        } finally { ui.close() }
    }

    @Test fun takeoverRejectsStrayAnswerBackAndGlobalScreensUntilExplicitHide() = runBlocking {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        val input = InputEngine(ui, FakeDictation(ui.data, ui.scope))
        ui.onCallWake = input::callInterrupted
        fun tap(k: String) { input.press(k); input.release(k) }
        try {
            input.press("b"); calls.incoming(); val id = calls.snapshot.ringing!!.id
            input.release("b"); tap("y"); tap("b")
            assertEquals(id, calls.snapshot.ringing!!.id)
            now += .5
            listOf("r", "star", "heart", "a").forEach(::tap)
            assertEquals(id, (ui.top as PhoneCallScreen).callId)
            ui.push(Apps(ui)); assertTrue(ui.top is PhoneCallScreen)
            tap("x"); assertFalse(ui.top is PhoneCallScreen)
            calls.publish(calls.snapshot); assertFalse(ui.top is PhoneCallScreen)
        } finally { input.close(); ui.close() }
    }

    @Test fun answeredLockedCallStaysUnlockedAfterLeavingAndRestoresLockAtEnd() = runBlocking {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            ui.locked = true; val previous = ui.stack.toList()
            calls.incoming(); val id = calls.snapshot.ringing!!.id
            now += .5; ui.top.key("sel"); ui.top.key("back")
            assertEquals(previous, ui.stack); assertFalse(ui.locked)
            ui.locked = true; assertFalse(ui.locked)
            calls.remoteEnd(id); assertTrue(ui.locked); assertEquals(previous, ui.stack)
        } finally { ui.close() }
    }

    @Test fun survivingHeldCallSelectResumesInsteadOfMuting() = runBlocking {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            calls.incoming(); val first = calls.snapshot.ringing!!.id; calls.answer(first)
            calls.incoming("Second"); val second = calls.snapshot.ringing!!.id; calls.answer(second)
            calls.remoteEnd(second)
            assertEquals(CallPhase.Holding, calls.snapshot.ongoing!!.phase)
            assertEquals(first, (ui.top as PhoneCallScreen).callId)
            ui.top.key("sel")
            assertEquals(CallPhase.Active, calls.snapshot.ongoing!!.phase); assertFalse(calls.snapshot.muted)
        } finally { ui.close() }
    }

    @Test @Config(sdk = [29, 31, 35]) fun grantsOutsideAppRegisterOnUserPresentAndWaitingControlsRecover() {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        shadowOf(context).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AndroidCalls(context, scope)
        try {
            assertTrue(calls.snapshot.limitation.contains("disabled"))
            shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val phone = shadowOf(context.getSystemService(android.telephony.TelephonyManager::class.java))
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
            assertFalse(calls.snapshot.ringing!!.end)
            phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
            assertTrue(calls.snapshot.ongoing!!.end)
            assertFalse(calls.snapshot.limitation.contains("waiting"))
        } finally { calls.close(); scope.cancel() }
    }

    @Test fun answerAndDeclineUseBasicFallbackDuringCompanionBindingWindow() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        shadowOf(context).grantPermissions(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = AndroidCalls(context, scope)
        val phone = shadowOf(context.getSystemService(android.telephony.TelephonyManager::class.java))
        phone.setCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING)
        val id = calls.snapshot.ringing!!.id
        val owner = org.robolectric.Robolectric.buildService(CompanionCallsService::class.java).create()
        try {
            assertEquals(id, calls.snapshot.ringing!!.id)
            assertTrue(calls.answer(id).ok)
            // The selected A disappeared while B arrived. A stale card cannot answer B.
            org.robolectric.util.ReflectionHelpers.setField(owner.get(), "hasDeliveredCalls", true)
            assertEquals("Call ended", calls.answer(id).message)
            // A platform refusal is actionable; a missing per-call object must not say Call ended.
            assertNotEquals("Call ended", calls.end(id).message)
        } finally { owner.destroy(); calls.close(); scope.cancel() }
    }

    @Test fun screeningIsNotAnIncomingTakeoverOrAnOngoingCall() {
        val ui = ui(); val calls = ui.data.calls as FakeCalls
        try {
            ui.locked = true; val previous = ui.stack.toList()
            calls.publish(CallsSnapshot(calls = listOf(PhoneCall("screen", CallPhase.Screening))))
            assertTrue(ui.locked); assertEquals(previous, ui.stack)
            assertNull(calls.snapshot.ringing); assertNull(calls.snapshot.ongoing)
        } finally { ui.close() }
    }
}
