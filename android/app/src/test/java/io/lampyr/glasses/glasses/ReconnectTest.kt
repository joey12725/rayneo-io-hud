package io.lampyr.glasses.glasses

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import io.lampyr.glasses.glasses.protocol.RayneoFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Glasses reconnect never gives up on "not found"; real errors stay terminal; events cut the backoff short. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTest {
    /** A fake connector on the test clock: records every attempt and fails it with [failure] (null connects). */
    private class FakeConnector(val now: () -> Long, var failure: (LinkAttempt) -> Throwable?) : RayNeoConnector {
        val attempts = mutableListOf<Pair<Long, LinkAttempt>>()
        override suspend fun open(address: String, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession = error("link must pass its attempt")
        override suspend fun open(address: String, attempt: LinkAttempt, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession {
            attempts += now() to attempt
            failure(attempt)?.let { throw it }
            // Connects, then the glasses drop the link at once (a read on the test thread must never block).
            return object : RayNeoSession {
                override val bleLost: Deferred<Unit>? = null
                override val socket = object : RayNeoSocket {
                    override val mtu = 100
                    override val output = ByteArrayOutputStream()
                    override val input = object : InputStream() { override fun read() = -1 }
                    override fun close() {}
                }
                override fun close() {}
            }
        }
    }

    private fun TestScope.link(failure: (LinkAttempt) -> Throwable?): Pair<RayNeoLink, FakeConnector> {
        val connector = FakeConnector({ testScheduler.currentTime }, failure)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return RayNeoLink(connector, owner) { testScheduler.currentTime } to connector
    }
    private fun TestScope.advance(ms: Long) { testScheduler.advanceTimeBy(ms); testScheduler.runCurrent() }
    private val notFound = { _: LinkAttempt -> IllegalStateException("Glasses not found nearby (no advert in a 12 s scan)") }

    @Test fun notFoundRetriesForeverWithBackoffCappedAtSixtySeconds() = runTest {
        val (link, fake) = link(notFound)
        try {
            link.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
            advance(6 * 60 * 60_000L) // A night with the glasses asleep in a drawer.
            val times = fake.attempts.map { it.first }
            val gaps = times.zipWithNext { a, b -> b - a }
            assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L), gaps.take(7))
            assertTrue("never above the cap", gaps.all { it <= ReconnectPolicy.MAX_DELAY_MS })
            assertTrue("still trying after six hours: ${times.size}", times.size > 300)
            assertEquals(RetryPhase.WAITING, link.retry.value.phase)
            assertTrue(link.retry.value.searching)
            assertEquals(ConnectionState.ERROR, link.connectionState.value)
            assertFalse("not presented as terminal", link.lastError.value!!.contains("Press"))
            // Fast attempts first (may pair, active connect), then cheap background ones that never open a pairing dialog.
            val attempts = fake.attempts.map { it.second }
            assertEquals(listOf(false, false, false), attempts.take(3).map { it.background })
            assertTrue(attempts.drop(3).all { it.background && !it.mayBond && !it.urgent && it.reason == "backoff" })
            assertEquals("connect", attempts.first().reason)
            assertEquals((1..attempts.size).toList(), attempts.map { it.number })
        } finally { link.close() }
    }

    @Test fun authRefusedUnpairedAndPermissionStayTerminalUntilAPersonActs() = runTest {
        for (failure in listOf<Throwable>(IllegalStateException("Glasses authentication proof rejected"),
            IllegalStateException("Account-bound auth unsupported: unbind in the official RayNeo app first"),
            IllegalStateException(ReconnectPolicy.UNPAIRED),
            SecurityException("Need android.permission.BLUETOOTH_CONNECT permission"))) {
            val (link, fake) = link { failure }
            try {
                link.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
                advance(60 * 60_000L)
                assertEquals("${failure.message}", 1, fake.attempts.size)
                assertEquals(RetryPhase.STOPPED, link.retry.value.phase)
                assertFalse(link.retry.value.searching)
                assertTrue(link.lastError.value!!.endsWith("Press Connect to retry."))
                if (failure is SecurityException) assertTrue(link.lastError.value!!.startsWith(ReconnectPolicy.PERMISSION))
                // System events never restart a terminal error.
                link.wake("phone screen on"); link.wake("glasses Bluetooth link up"); link.wake("Bluetooth on"); advance(60_000)
                assertEquals(1, fake.attempts.size)
                // A controller key (or Connect, or pairing finishing) does, with the fast budget.
                link.retryIfIdle(); runCurrent()
                assertEquals(2, fake.attempts.size)
                assertEquals("controller key", fake.attempts.last().second.reason)
                assertFalse(fake.attempts.last().second.background)
                link.wake("glasses paired", userAction = true); runCurrent()
                assertEquals(3, fake.attempts.size)
            } finally { link.close() }
        }
    }

    @Test fun eventsCutTheBackoffShortAndAreTraced() = runTest {
        val (link, fake) = link(notFound)
        try {
            link.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
            advance(5_000 + 10_000 + 15_000 + 20_000 + 1) // Into the background phase: next wait is 30 s.
            assertEquals(5, fake.attempts.size)
            assertEquals(RetryPhase.WAITING, link.retry.value.phase)
            assertEquals(testScheduler.currentTime - 1 + 30_000, link.retry.value.nextAttemptAt)
            for ((index, reason) in listOf("glasses Bluetooth link up", "phone screen on", "phone unlocked", "Bluetooth on").withIndex()) {
                advance(1_000)
                link.wake(reason); runCurrent()
                assertEquals(6 + index, fake.attempts.size)
                val attempt = fake.attempts.last().second
                assertEquals(reason, attempt.reason)
                assertTrue("event attempts connect actively", attempt.urgent)
                assertTrue("system events keep the background budget", attempt.background)
                assertFalse(attempt.mayBond)
            }
            // A controller press gives the fast budget back.
            advance(1_000); link.retryIfIdle(); runCurrent()
            val pressed = fake.attempts.last().second
            assertEquals("controller key", pressed.reason)
            assertFalse(pressed.background); assertTrue(pressed.mayBond)
            assertEquals(5_000L, link.reconnectDelayMs)
        } finally { link.close() }
    }

    @Test fun burstsOfWakesCollapseIntoOneAttemptAndALostLinkIsRetried() = runTest {
        var connect = false
        val (link, fake) = link { if (connect) null else IllegalStateException("Glasses not found nearby") }
        try {
            link.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
            assertEquals(1, fake.attempts.size)
            // Screen on and unlock arrive together: one attempt, not two.
            link.wake("phone screen on"); link.wake("phone unlocked"); runCurrent()
            assertEquals(2, fake.attempts.size)
            assertEquals("phone screen on", fake.attempts.last().second.reason) // The first wins; the rest of the burst is dropped.
            assertEquals(RetryPhase.WAITING, link.retry.value.phase)
            // Glasses wake: the link comes up, then drops; the supervisor goes straight back to searching.
            connect = true
            link.wake("glasses Bluetooth link up"); runCurrent()
            assertEquals(3, fake.attempts.size)
            assertEquals(RetryPhase.WAITING, link.retry.value.phase)
            advance(link.reconnectDelayMs)
            assertEquals(4, fake.attempts.size)
            assertEquals("link lost", fake.attempts.last().second.reason)
        } finally { link.close() }
    }

    @Test fun aWakeDuringALongPassiveAttemptHurriesItInsteadOfBeingDropped() = runTest {
        // Like AndroidConnector: a passive background attempt waits up to 180 s unless hurried; fast ones fail at once.
        val (link, fake) = link { null }
        val hurried = mutableListOf<String>()
        val connector = object : RayNeoConnector {
            override suspend fun open(address: String, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession = error("unused")
            override suspend fun open(address: String, attempt: LinkAttempt, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession {
                fake.attempts += testScheduler.currentTime to attempt
                if (attempt.passive) withTimeoutOrNull(ConnectPlan.PASSIVE_WINDOW_MS) { attempt.hurry.await() }?.let { hurried += it }
                error("Glasses not found nearby")
            }
        }
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val passive = RayNeoLink(connector, owner) { testScheduler.currentTime }
        try {
            passive.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
            // A wake during a fast attempt is not a passive wait: nothing to hurry.
            assertFalse(fake.attempts.last().second.passive)
            advance(5_000 + 10_000 + 15_000) // Fourth attempt: the first background (passive) one, now mid-wait.
            assertEquals(4, fake.attempts.size)
            assertTrue(fake.attempts.last().second.passive)
            assertEquals(RetryPhase.ATTEMPTING, passive.retry.value.phase)
            advance(30_000)
            passive.wake("controller key", userAction = true); runCurrent()
            assertEquals(listOf("controller key"), hurried)
            // The wake ended the wait at once (the attempt then failed and the link is waiting again), not 150 s later.
            assertEquals(RetryPhase.WAITING, passive.retry.value.phase)
            // A burst during one passive attempt hurries it only once.
            advance(passive.reconnectDelayMs)
            passive.wake("phone screen on"); passive.wake("phone unlocked"); runCurrent()
            assertEquals(5, fake.attempts.size)
            assertEquals(listOf("controller key", "phone screen on"), hurried)
            assertEquals(5, fake.attempts.size)
            assertEquals(RetryPhase.WAITING, passive.retry.value.phase)
        } finally { passive.close(); link.close() }
    }

    @Test fun disconnectStopsRetriesAndWakesAreIgnored() = runTest {
        val (link, fake) = link(notFound)
        try {
            link.connect("AA:BB:CC:DD:EE:FF"); runCurrent()
            link.disconnect()
            advance(10 * 60_000L)
            link.wake("phone screen on"); link.retryIfIdle(); runCurrent()
            assertEquals(1, fake.attempts.size)
            assertEquals(RetryPhase.IDLE, link.retry.value.phase)
            assertEquals(ConnectionState.DISCONNECTED, link.connectionState.value)
        } finally { link.close() }
    }

    @Test fun terminalClassification() {
        for (message in listOf("Glasses authentication proof rejected", "Authentication rejected by glasses", ReconnectPolicy.UNPAIRED,
            ReconnectPolicy.PERMISSION, "Pairing declined or failed; re-enter pairing mode", "Account-bound auth unsupported: unbind in the official RayNeo app first"))
            assertTrue(message, ReconnectPolicy.isTerminal(message))
        for (message in listOf("Timed out waiting for 13000 ms", "Glasses not found nearby (no advert in a 4 s scan)", "Bluetooth is off. Waiting for it to come back on",
            "BLE disconnected (status 133)", "RFCOMM stream closed", "System rejected bond request", "BLE write rejected"))
            assertFalse(message, ReconnectPolicy.isTerminal(message))
        assertEquals(ReconnectPolicy.PERMISSION, ReconnectPolicy.message(SecurityException("Need BLUETOOTH_SCAN")))
        assertEquals("x", ReconnectPolicy.message(IllegalStateException("x")))
    }

    @Test fun connectPlanSkipsTheScanForBondedOrHeldGlassesAndStaysCheapInTheBackground() {
        val fast = LinkAttempt(1, "connect")
        val background = LinkAttempt(7, "backoff", background = true, urgent = false, mayBond = false)
        val event = background.copy(reason = "phone screen on", urgent = true)
        // Bonded dual-mode glasses (the phone trace: bonded, no adverts): direct connect, passive in the background.
        assertEquals(ConnectPlan(true, false, ConnectPlan.ACTIVE_WINDOW_MS, 0), ConnectPlan.choose(fast, bondedLe = true, heldElsewhere = false))
        assertEquals(ConnectPlan(true, true, ConnectPlan.PASSIVE_WINDOW_MS, 0), ConnectPlan.choose(background, bondedLe = true, heldElsewhere = false))
        assertEquals(ConnectPlan(true, false, ConnectPlan.ACTIVE_WINDOW_MS, 0), ConnectPlan.choose(event, bondedLe = true, heldElsewhere = false))
        // Already linked by another app: connect straight onto it.
        assertTrue(ConnectPlan.choose(background, bondedLe = false, heldElsewhere = true).direct)
        // Unknown glasses: scan; short scans in the background.
        assertEquals(ConnectPlan.FAST_SCAN_MS, ConnectPlan.choose(fast, false, false).scanMs)
        assertEquals(ConnectPlan.SHORT_SCAN_MS, ConnectPlan.choose(background, false, false).scanMs)
        assertEquals(ConnectPlan.FAST_SCAN_MS, ConnectPlan.choose(event, false, false).scanMs)
        assertFalse(ConnectPlan.choose(background, false, false).direct)
    }

    @Test fun triggersMapOnlyRelevantBroadcasts() {
        val selected = listOf("AA:BB:CC:DD:EE:FF")
        fun classify(action: String, device: String? = null, bond: Int = -1, adapter: Int = -1) = ReconnectTriggers.classify(action, device, bond, adapter, selected)
        assertEquals(ReconnectTriggers.Trigger("glasses Bluetooth link up"), classify(BluetoothDevice.ACTION_ACL_CONNECTED, "aa:bb:cc:dd:ee:ff"))
        assertNull(classify(BluetoothDevice.ACTION_ACL_CONNECTED, "11:22:33:44:55:66")) // The controller or earbuds.
        assertEquals(ReconnectTriggers.Trigger("glasses paired", userAction = true), classify(BluetoothDevice.ACTION_BOND_STATE_CHANGED, "AA:BB:CC:DD:EE:FF", BluetoothDevice.BOND_BONDED))
        assertNull(classify(BluetoothDevice.ACTION_BOND_STATE_CHANGED, "AA:BB:CC:DD:EE:FF", BluetoothDevice.BOND_NONE))
        assertEquals(ReconnectTriggers.Trigger("Bluetooth on"), classify(BluetoothAdapter.ACTION_STATE_CHANGED, adapter = BluetoothAdapter.STATE_ON))
        assertNull(classify(BluetoothAdapter.ACTION_STATE_CHANGED, adapter = BluetoothAdapter.STATE_TURNING_OFF))
        assertEquals("phone screen on", classify(Intent.ACTION_SCREEN_ON)?.reason)
        assertEquals("phone unlocked", classify(Intent.ACTION_USER_PRESENT)?.reason)
        assertNull(classify(Intent.ACTION_SCREEN_OFF))
        // Link off (no glasses selected): Bluetooth events about any device are ignored.
        assertNull(ReconnectTriggers.classify(BluetoothDevice.ACTION_ACL_CONNECTED, "AA:BB:CC:DD:EE:FF", -1, -1, emptyList()))
        assertEquals(5, ReconnectTriggers.ACTIONS.size)
    }

    @Test fun officialAppWarnsOnlyWhileItCanTakeTheLink() {
        fun level(state: OfficialApp.State) = OfficialApp.assess(state).level
        assertEquals(OfficialApp.Level.NONE, level(OfficialApp.State(installed = false)))
        assertEquals(OfficialApp.Level.NONE, level(OfficialApp.State(installed = true, enabled = false, bluetooth = true)))
        assertEquals(OfficialApp.Level.WARN, level(OfficialApp.State(true, stopped = false, bluetooth = true)))
        assertEquals(OfficialApp.Level.WARN, level(OfficialApp.State(true, stopped = true, bluetooth = true)))
        // Tonight's phone: running, but glassesctl 'official off' revoked its Nearby devices permission.
        val tonight = OfficialApp.assess(OfficialApp.State(true, stopped = false, bluetooth = false))
        assertEquals(OfficialApp.Level.INFO, tonight.level)
        assertTrue(tonight.text.contains("Bluetooth blocked"))
        assertTrue(OfficialApp.assess(OfficialApp.State(true, stopped = false, bluetooth = true)).detail.contains("competes"))
    }

    @Test fun phoneTextStaysHonestWhileSearching() {
        val waiting = RetryStatus(RetryPhase.WAITING, 7, "backoff", true, nextAttemptAt = 45_000, error = "Glasses not found nearby")
        assertEquals("Searching for glasses. Next try in 42 s. Last: Glasses not found nearby", ReconnectPolicy.detail(waiting, now = 3_000))
        assertTrue(ReconnectPolicy.detail(waiting.copy(phase = RetryPhase.ATTEMPTING)).startsWith("Searching for glasses in the background"))
        assertEquals("Looking for your glasses…", ReconnectPolicy.detail(RetryStatus(RetryPhase.ATTEMPTING, 1, "connect")))
        val stopped = RetryStatus(RetryPhase.STOPPED, 1, "connect", error = "Glasses authentication proof rejected. Press Connect to retry.")
        assertEquals(stopped.error, ReconnectPolicy.detail(stopped))
        assertFalse(stopped.searching)
        assertEquals("Glasses searching", ReconnectPolicy.notificationLine(waiting))
        assertEquals("Glasses searching", ReconnectPolicy.notificationLine(waiting.copy(phase = RetryPhase.ATTEMPTING)))
        assertEquals("Glasses stopped, open the app", ReconnectPolicy.notificationLine(stopped))
        assertEquals("", ReconnectPolicy.notificationLine(RetryStatus(RetryPhase.CONNECTED)))
    }
}
