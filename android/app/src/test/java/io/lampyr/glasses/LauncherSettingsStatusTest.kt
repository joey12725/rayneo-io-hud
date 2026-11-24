package io.lampyr.glasses

import android.content.Intent
import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Requirements 16.2 (launcher order and saved-order migration) and 16.4 (Status earbuds). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherSettingsStatusTest {
    private val page1 = listOf("joy", "beeper", "media", "t3", "notifications", "phone", "calendar", "ha")

    @Test fun defaultOrderPutsHomeOnPageOneInTheDefaultOrder() {
        assertEquals(page1, DEFAULT_APP_ORDER.take(8))
        assertEquals(listOf("people", "report", "health", "status", "clock", "settings", "docs", "teleprompter"), DEFAULT_APP_ORDER.subList(8, 16))
        assertEquals(DEFAULT_APP_ORDER.distinct(), DEFAULT_APP_ORDER)
        assertTrue("every app is in the default order", APP_ENTRIES.all { it.id in DEFAULT_APP_ORDER })
        // With the phone and clock entries merged in, page 1 is exactly the requested eight.
        val withNewApps = APP_ENTRIES.map { it.id } + listOf("phone", "clock")
        assertEquals(page1, mergeAppOrder(DEFAULT_APP_ORDER, withNewApps).take(8))
        // Without them (this branch alone) Home is still on page 1 and placeholders are skipped.
        val ui = Ui(FakeDataStore(RuntimeEnvironment.getApplication()), scope = CoroutineScope(Dispatchers.Unconfined))
        try {
            val ids = appEntries(ui).map { it.id }
            assertTrue(ids.indexOf("ha") in 0..7)
            assertTrue("phone" in ids && "clock" in ids)
            assertEquals(APP_ENTRIES.size, ids.size)
        } finally { ui.close() }
    }

    @Test fun aSavedOldDefaultFollowsTheNewDefault() {
        // Settings saves encode defaults, so an untouched order is stored as the old default; it is not a choice.
        val entries = APP_ENTRIES.map { it.id } + listOf("phone", "clock")
        PREVIOUS_DEFAULT_APP_ORDERS.forEach { old -> assertEquals(page1, mergeAppOrder(old, entries).take(8)) }
        assertEquals(mergeAppOrder(DEFAULT_APP_ORDER, entries), mergeAppOrder(emptyList(), entries))
    }

    @Test fun aCustomOrderIsKeptAndOnlyNewAppsAreInsertedAtTheirDefaultPositions() {
        val entries = APP_ENTRIES.map { it.id } + listOf("phone", "clock")
        val custom = listOf("settings", "joy", "beeper", "media", "t3", "notifications", "calendar", "ha", "people", "report",
            "health", "docs", "teleprompter", "memo", "status")
        val merged = mergeAppOrder(custom, entries)
        assertEquals(custom, merged.filter { it in custom })
        // Phone follows Notifications (its default predecessor); Clock follows Status.
        assertEquals(merged.indexOf("notifications") + 1, merged.indexOf("phone"))
        assertEquals(merged.indexOf("status") + 1, merged.indexOf("clock"))
        // An established app the user's saved order lacks stays out; ids without an entry never appear.
        val trimmed = mergeAppOrder(listOf("status", "joy"), entries)
        assertEquals(listOf("status", "clock", "joy", "phone"), trimmed)
        assertEquals(listOf("status", "clock", "joy", "phone"), mergeAppOrder(listOf("status", "joy"), APP_ENTRIES.map { it.id }))
        // A new app whose default predecessors are all missing goes before its nearest following app.
        assertEquals(listOf("phone", "calendar"), mergeAppOrder(listOf("calendar"), listOf("calendar", "phone")))
        assertEquals("merging is idempotent", merged, mergeAppOrder(merged, entries))
    }

    @Test fun reorderingInSettingsStillSavesAndRoundTrips() = runBlocking {
        val ui = Ui(FakeDataStore(RuntimeEnvironment.getApplication()), scope = CoroutineScope(Dispatchers.Unconfined))
        try {
            val before = appEntries(ui).map { it.id }
            val order = AppOrder(ui); ui.push(order); order.cur = 7; order.key("sel"); order.key("up")
            val after = appEntries(ui).map { it.id }
            assertEquals(before[7], after[6]); assertEquals(before[6], after[7])
            assertEquals(after, mergeAppOrder(ui.data.settings.snapshot.appOrder))
        } finally { ui.close() }
    }

    private class FakePlatform : BluetoothPlatform {
        val connected = mutableListOf<BtDevice>()
        val levels = mutableMapOf<String, Int>()
        var reflectionBlocked = false
        override fun connected(onResult: (List<BtDevice>) -> Unit) = onResult(connected.toList())
        override fun level(address: String): Int? { if (reflectionBlocked) throw SecurityException("blocked"); return levels[address] }
        override fun bonded() = connected.toList() + BtDevice("AA:00:00:00:00:09", "JBL Flip 5", "speaker")
        override fun device(intent: Intent): BtDevice? = null
    }

    @Test fun earbudsAlreadyConnectedAtStartAreReadWithoutWaitingForABroadcast() {
        val ctx = RuntimeEnvironment.getApplication()
        val platform = FakePlatform().apply {
            connected += BtDevice("AA:00:00:00:00:01", "Soundcore Life P2 Mini", "buds")
            connected += BtDevice("AA:00:00:00:00:02", "WH-CH720N", "headphones")
            levels["AA:00:00:00:00:01"] = 80
        }
        var changes = 0
        val bt = BluetoothBatteries(ctx, { changes++ }, platform)
        try {
            assertTrue(bt.levels.isEmpty()); bt.start()
            assertEquals(listOf("Soundcore Life P2 Mini" to .8, "WH-CH720N" to null), bt.levels.map { it.name to it.level })
            val before = changes; bt.refresh(); assertEquals("an unchanged re-read does not loop", before, changes)
            platform.levels["AA:00:00:00:00:02"] = 55; bt.refresh(); assertEquals(.55, bt.levels[1].level!!, .001)
            // Blocked reflection falls back to what the broadcasts said.
            platform.reflectionBlocked = true; bt.level(platform.connected[0], 64); assertEquals(.64, bt.levels[0].level!!, .001)
            bt.level(platform.connected[0], -1); assertNull(bt.levels[0].level)
            bt.disconnected("AA:00:00:00:00:01"); assertEquals(listOf("WH-CH720N"), bt.levels.map { it.name })
        } finally { bt.close() }
    }

    @Test fun aLevelResetAfterDisconnectOrBluetoothOffLeavesNoGhostRow() {
        val ctx = RuntimeEnvironment.getApplication()
        val buds = BtDevice("AA:00:00:00:00:01", "Soundcore Life P2 Mini", "buds")
        val platform = FakePlatform().apply { connected += buds; levels[buds.address] = 70 }
        var changes = 0
        val bt = BluetoothBatteries(ctx, { changes++ }, platform)
        try {
            bt.start(); assertEquals(1, bt.levels.size)
            // Android may send the -1 / -100 reset after ACL_DISCONNECTED; it must not bring the row back.
            bt.disconnected(buds.address); platform.connected.clear()
            bt.level(buds, -1); bt.level(buds, -100)
            assertTrue(bt.levels.isEmpty())
            // A real level broadcast still adds a device (it is connected).
            bt.level(buds, 55); assertEquals(.55, bt.levels.single().level!!, .001)
            // Bluetooth turned off forgets every device even without per-device ACL_DISCONNECTED.
            val before = changes
            ctx.sendBroadcast(Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(bt.levels.isEmpty()); assertEquals(before + 1, changes)
        } finally { bt.close() }
    }

    @Test fun statusShowsEarbudsByNameAndThePhoneSettingPicksDevices() {
        val buds = DeviceBattery("AA:01", "Soundcore Life P2 Mini", .7, "buds")
        val qc = DeviceBattery("AA:02", "sam's qc35", null, "headphones")
        val car = DeviceBattery("AA:03", "Honda", .5, "car")
        val speaker = DeviceBattery("AA:04", "JBL Flip 5", .9, "speaker")
        val glasses = DeviceBattery("AA:05", "RayNeo X3", .4, "buds")
        val all = listOf(buds, qc, car, speaker, glasses)
        assertEquals(listOf("Life P2", "sam's qc35"), StatusDevices.rows(all, { null }, "aa:05").map { it.label })
        assertNull("a device with no level keeps its row", StatusDevices.rows(all, { null }, "aa:05")[1].level)
        val choices = mapOf("AA:01" to StatusDevices.HIDE, "AA:04" to StatusDevices.SHOW)
        assertEquals(listOf("sam's qc35", "JBL Flip 5"), StatusDevices.rows(all, { choices[it] }, "AA:05").map { it.label })
        assertEquals(StatusDevices.MAX_ROWS, StatusDevices.rows(all, { StatusDevices.SHOW }, null).size)
        assertEquals("WH-CH720N", StatusDevices.shortName("WH-CH720N"))
        assertEquals("V40i", StatusDevices.shortName("soundcore V40i"))
        assertTrue(StatusDevices.shortName("A very long device name here").length <= StatusDevices.NAME_CHARS)
    }

    @Test fun statusScreenKeepsItsDesignAndDropsBrightnessOnlyWhenFull() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun ui(rows: List<Battery>): Ui {
            val base = FakeDataStore(RuntimeEnvironment.getApplication(), scope = scope)
            val data = object : DataStore by base {
                override val status = object : StatusRepository by base.status {
                    override val snapshot = StatusSnapshot(rows, "Home", "4/4", "Glasses connected")
                }
            }
            return Ui(data, scope = scope)
        }
        val full = listOf(Battery("glasses", "Glasses", .8), Battery("buds", "Life P2", .7), Battery("buds", "qc35", -1.0),
            Battery("ring", "Oura", .4), Battery("phone", "Phone", .6))
        ui(full).also { u ->
            try {
                u.display = Display.Text(); u.push(Status(u))
                val text = u.render().text.lines().joinToString("\n")
                assertTrue(text, "Life P2" in text && "qc35 -" in text)
                val grid = TextGrid(26, 7, false); u.top.drawText(grid); assertFalse(grid.overflowed)
                u.display = Display.Bitmap; u.render()
                assertTrue(Status.brightnessFits(4)); assertFalse(Status.brightnessFits(full.size))
            } finally { u.close() }
        }
        scope.cancel()
    }
}
