package io.lampyr.glasses.clock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneAlarmsTest {
    private fun many() = (0 until 12).map { i ->
        PhoneAlarm(5 + i / 2, if (i % 2 == 0) 0 else 30, if (i == 7) "Gym" else "", if (i % 3 == 0) "Mon, Tue, Wed, Thu, Fri" else "Not scheduled", i % 2 == 0)
    }.toMutableList()

    private fun auto(f: FakeGoogleClock) = PhoneAlarmAutomation(f)

    @Test fun parsesTheViewLayout() {
        val f = FakeGoogleClock(mutableListOf(
            PhoneAlarm(6, 30, "Wake up", "Mon, Tue, Wed, Thu, Fri", true),
            PhoneAlarm(13, 5, "", "Not scheduled", false),
            PhoneAlarm(0, 0, "Monday meds", "Every day", true),
        ), viewport = 5)
        f.foregroundPkg = PhoneAlarmAutomation.CLOCK_PACKAGE
        val rows = PhoneAlarmParser.rows(f.clockRoot()!!).map { it.alarm }
        assertEquals(f.alarms, rows)
    }

    @Test fun parsesTheComposeLayoutWithAnExpandedRow() {
        val f = FakeGoogleClock(mutableListOf(
            PhoneAlarm(7, 0, "Wake up", "Mon, Tue, Wed, Thu, Fri", true),
            PhoneAlarm(7, 15, "", "Tomorrow", true),
            PhoneAlarm(21, 45, "Pills", "Every day", false),
        ), style = "compose", viewport = 5)
        f.foregroundPkg = PhoneAlarmAutomation.CLOCK_PACKAGE
        assertEquals(f.alarms, PhoneAlarmParser.rows(f.clockRoot()!!).map { it.alarm })
        // An expanded row without a label: "Add label", the chips and Vibrate are not taken for one.
        f.alarms[0] = f.alarms[0].copy(label = "")
        assertEquals("", PhoneAlarmParser.rows(f.clockRoot()!!).first().alarm.label)
    }

    @Test fun timeParsing() {
        assertEquals(6 to 30, PhoneAlarmParser.time("6:30 AM"))
        assertEquals(0 to 5, PhoneAlarmParser.time("12:05 a.m."))
        assertEquals(12 to 0, PhoneAlarmParser.time("12:00", "PM"))
        assertEquals(19 to 45, PhoneAlarmParser.time("19:45"))
        assertNull(PhoneAlarmParser.time("25:00"))
        assertNull(PhoneAlarmParser.time("13:00 PM"))
        assertNull(PhoneAlarmParser.time("Alarm in 8 hours"))
    }

    @Test fun readScrollsThroughEveryAlarmAndReturnsToThePreviousApp() = runBlocking {
        for (style in listOf("views", "compose")) {
            val f = FakeGoogleClock(many(), style = style)
            f.offset = 5 // Google Clock remembers where it was scrolled.
            val out = auto(f).read()
            assertTrue(out.message, out.ok)
            assertEquals(style, f.alarms, out.alarms)
            assertEquals("Read 12 phone alarms", out.message)
            assertEquals(listOf<String?>("io.lampyr.glasses"), f.returned)
        }
    }

    @Test fun toggleFindsTheAlarmFarDownAndVerifies() = runBlocking {
        val f = FakeGoogleClock(many())
        f.clickLagMs = 500
        val target = f.alarms[7] // 8:30 AM "Gym", off
        val out = auto(f).toggle(target, true)
        assertTrue(out.message, out.ok)
        assertEquals("Phone alarm 8:30 AM \"Gym\" on", out.message)
        assertTrue(f.alarms[7].enabled)
        assertEquals(target.copy(enabled = true), out.changed)
        assertEquals(1, f.alarms.zip(many()).count { (a, b) -> a != b })
        assertEquals(listOf<String?>("io.lampyr.glasses"), f.returned)
    }

    @Test fun toggleMatchesByLabelWhenTimesRepeat() = runBlocking {
        val f = FakeGoogleClock(mutableListOf(
            PhoneAlarm(7, 0, "Work", "Mon, Tue", true), PhoneAlarm(7, 0, "Weekend", "Sat, Sun", true), PhoneAlarm(8, 0, "", "", true)))
        val out = auto(f).toggle(PhoneAlarm(7, 0, "weekend"), false)
        assertTrue(out.message, out.ok)
        assertEquals(listOf(true, false, true), f.alarms.map { it.enabled })
    }

    @Test fun alreadyInThatState() = runBlocking {
        val f = FakeGoogleClock(many())
        val out = auto(f).toggle(f.alarms[0], true)
        assertTrue(out.ok)
        assertTrue(out.message, out.message.contains("already on"))
        assertEquals(many(), f.alarms)
    }

    @Test fun failures() = runBlocking {
        // Clock ignores the click: verification times out with a clear message, and we still go back.
        val f = FakeGoogleClock(many()).apply { ignoreClicks = true }
        val out = auto(f).toggle(f.alarms[1], true)
        assertFalse(out.ok)
        assertTrue(out.message, out.message.contains("still shows it off"))
        assertEquals(1, f.returned.size)

        val gone = auto(FakeGoogleClock(many())).toggle(PhoneAlarm(4, 10, "Nope"), true)
        assertFalse(gone.ok)
        assertTrue(gone.message, gone.message.startsWith("No phone alarm 4:10 AM"))
        assertEquals(12, gone.alarms!!.size)

        val locked = FakeGoogleClock(many()).apply { this.locked = true }
        val l = auto(locked).read()
        assertEquals("Phone is locked; unlock it to use phone alarms", l.message)
        assertEquals(0, locked.opens)
        assertTrue(locked.returned.isEmpty())

        val stuck = FakeGoogleClock(many()).apply { clockOpens = false }
        val s = auto(stuck).read()
        assertFalse(s.ok)
        assertTrue(s.message, s.message.contains("unlock the phone"))
        assertTrue(stuck.now >= 7_000)
        assertEquals(listOf<String?>("io.lampyr.glasses"), stuck.returned)
    }

    @Test fun neverScrollsTheTabPagerAroundTheList() = runBlocking {
        val f = FakeGoogleClock(many()).apply { pager = true; offset = 5 }
        val out = auto(f).read()
        assertEquals(f.alarms, out.alarms)
        assertEquals("Alarm", f.tab)
        val far = auto(f).toggle(f.alarms[11], !f.alarms[11].enabled)
        assertTrue(far.message, far.ok)
        assertEquals("Alarm", f.tab)
        // No alarm rows at all: nothing is scrolled (the pager would swipe to the Clock tab).
        val empty = FakeGoogleClock(mutableListOf()).apply { pager = true }
        assertTrue(auto(empty).read().ok)
        assertEquals("Alarm", empty.tab)
    }

    @Test fun waitsForTheAlarmsTabNotJustAnyFab() = runBlocking {
        // Clock reopens on its Timer tab (it has a FAB too) and only switches to Alarms a moment later.
        val f = FakeGoogleClock(many()).apply { tab = "Timer"; alarmTabAfterMs = 1_500 }
        val out = auto(f).read()
        assertTrue(out.message, out.ok)
        assertEquals(12, out.alarms!!.size)
        val stuck = FakeGoogleClock(many()).apply { tab = "Timer"; alarmTabAfterMs = 60_000 }
        val s = auto(stuck).read()
        assertFalse(s.ok)
        assertNull(s.alarms)
        assertEquals("Google Clock's alarm list did not appear in time", s.message)
    }

    @Test fun onlyEverTapsTheSwitchNeverTheRow() = runBlocking {
        val f = FakeGoogleClock(many()).apply { switchClickable = false }
        val out = auto(f).toggle(f.alarms[1], true)
        assertFalse(out.ok)
        assertTrue(out.message, out.message.contains("did not accept the switch"))
        assertEquals(0, f.rowTaps)
        assertEquals(many(), f.alarms)
        assertEquals(1, f.returned.size)
    }

    @Test fun clockAlreadyInFrontStaysInFront() = runBlocking {
        val f = FakeGoogleClock(many()).apply { foregroundPkg = PhoneAlarmAutomation.CLOCK_PACKAGE }
        assertTrue(auto(f).read().ok)
        assertTrue(f.returned.isEmpty())
    }

    @Test fun emptyList() = runBlocking {
        val out = auto(FakeGoogleClock(mutableListOf())).read()
        assertTrue(out.ok)
        assertEquals("No alarms in Google Clock", out.message)
        assertEquals(emptyList<PhoneAlarm>(), out.alarms)
    }

    @Test fun matching() {
        val list = listOf(PhoneAlarm(7, 0, "Work"), PhoneAlarm(7, 0, "Weekend"), PhoneAlarm(8, 0, ""), PhoneAlarm(9, 0, "Gym"), PhoneAlarm(9, 0, "Gym"))
        assertEquals(2, PhoneAlarmMatch.find(list, 7, 0, null).size)
        assertEquals("Weekend", PhoneAlarmMatch.find(list, 7, 0, "WEEKEND").single().label)
        assertEquals("Work", PhoneAlarmMatch.find(list, 7, 0, "work alarm").single().label)
        assertEquals(1, PhoneAlarmMatch.find(list, 8, 0, null).size)
        assertEquals(1, PhoneAlarmMatch.find(list, 9, 0, null).size) // identical twins: either one
        assertTrue(PhoneAlarmMatch.find(list, 10, 0, null).isEmpty())
    }

    @Test fun bookCachesPersistsAndRefusesOverlap() = runBlocking {
        val store = MemoryClockPersistence()
        val f = FakeGoogleClock(many())
        var changes = 0
        val book = PhoneAlarmBook({ 1_000L }, Dispatchers.Unconfined).apply { onChange = { changes++ }; attach(f, store) }
        assertTrue(book.alarms.isEmpty())
        assertTrue(book.refresh().ok)
        assertEquals(12, book.alarms.size)
        assertEquals(1_000L, book.cache.readAtMs)
        assertTrue(changes >= 3)
        assertTrue(book.setEnabled(book.alarms[7], true).ok)
        assertTrue(book.alarms[7].enabled)
        // Reloads from the store (a service restart).
        val again = PhoneAlarmBook({ 0L }, Dispatchers.Unconfined).apply { attach(PhoneAlarmHost.NONE, store) }
        assertEquals(book.cache, again.cache)
        val none = again.refresh()
        assertFalse(none.ok)
        assertEquals(none.message, again.lastError)
        assertEquals(12, again.alarms.size) // A failed read keeps the cached list.

        // Busy: a second request while one runs is refused.
        val slow = object : PhoneAlarmHost by f {
            override suspend fun pause(ms: Long) { f.pause(ms); if (book.busy != null && inner == null) inner = book.refresh() }
            var inner: PhoneAlarmOutcome? = null
        }
        book.attach(slow, store)
        assertTrue(book.refresh().ok)
        assertEquals("Busy: Reading phone alarms", slow.inner?.message)
    }
}
