package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.ClockOps
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClockOpsTest {
    @Test fun registersEveryClockOp(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        val names = listOf("timer.start", "timer.list", "timer.pause", "timer.resume", "timer.cancel", "timer.add_time",
            "stopwatch.start", "stopwatch.stop", "stopwatch.lap", "stopwatch.reset", "stopwatch.state",
            "alarm.set", "alarm.list", "alarm.delete", "alarm.enable", "alarm.dismiss", "alarm.snooze")
        names.forEach { assertTrue(it, it in h.registry.ops) }
        val manifest = h.registry.manifest()["ops"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(manifest.containsAll(names))
        assertEquals("never", h.registry.ops["alarm.dismiss"]!!.effectiveConfirm.wire)
    }

    @Test fun timers(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        val started = h.call("timer.start", mapOf("duration" to "10 minutes", "label" to "Pasta"))
        assertTrue(started.message, started.ok)
        val id = started.data!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(Codes.INVALID_ARGS, h.call("timer.start", mapOf("duration" to "soon")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("timer.start", emptyMap()).code)
        assertEquals(Codes.UNAVAILABLE, h.call("timer.start", mapOf("duration" to "30 hours")).code)
        val list = h.call("timer.list").data!!.jsonObject["timers"]!!.jsonArray
        assertEquals("Pasta", list.single().jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals(600, list.single().jsonObject["remainingSec"]!!.jsonPrimitive.long)
        assertTrue(h.call("timer.pause", mapOf("id" to "pasta")).ok)
        assertTrue(h.call("timer.resume").ok)
        assertTrue(h.call("timer.add_time", mapOf("duration" to "5m", "id" to id)).ok)
        assertTrue(h.call("timer.add_time", mapOf("duration" to "-1m")).ok)
        assertEquals(840, h.call("timer.list").data!!.jsonObject["timers"]!!.jsonArray.single().jsonObject["remainingSec"]!!.jsonPrimitive.long)
        assertEquals(Codes.FAILED, h.call("timer.cancel", mapOf("id" to "nope")).code)
        assertTrue(h.call("timer.cancel", mapOf("all" to true)).ok)
        assertEquals(Codes.UNAVAILABLE, h.call("timer.pause").code)
    }

    @Test fun stopwatch(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        assertTrue(h.call("stopwatch.start").ok)
        assertTrue(h.call("stopwatch.lap").ok)
        assertTrue(h.call("stopwatch.stop").ok)
        val state = h.call("stopwatch.state").data!!.jsonObject
        assertFalse(state["running"]!!.jsonPrimitive.boolean)
        assertEquals(1, state["laps"]!!.jsonArray.size)
        assertEquals(Codes.UNAVAILABLE, h.call("stopwatch.lap").code)
        assertTrue(h.call("stopwatch.reset").ok)
    }

    @Test fun alarms(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        val set = h.call("alarm.set", mapOf("time" to "7:30 am", "days" to "weekdays", "label" to "Gym"))
        assertTrue(set.message, set.ok)
        assertTrue(set.message!!.contains("tomorrow"))
        assertEquals(Codes.INVALID_ARGS, h.call("alarm.set", mapOf("time" to "later")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("alarm.set", mapOf("time" to "7", "days" to "someday")).code)
        val list = h.call("alarm.list").data!!.jsonObject
        val alarm = list["alarms"]!!.jsonArray.single().jsonObject
        assertEquals("7:30 AM", alarm["time"]!!.jsonPrimitive.content)
        assertEquals("Weekdays", alarm["days"]!!.jsonPrimitive.content)
        assertEquals("7:00 AM", list["homeAssistantAlarm"]!!.jsonPrimitive.content)
        // Edit keeps the days and label unless given.
        val id = alarm["id"]!!.jsonPrimitive.content
        assertTrue(h.call("alarm.set", mapOf("time" to "6:45", "id" to id)).ok)
        assertEquals("Gym", h.ui.clock.alarm(id)!!.label)
        assertEquals((1..5).toSet(), h.ui.clock.alarm(id)!!.days)
        assertTrue(h.call("alarm.enable", mapOf("id" to "gym", "enabled" to false)).ok)
        assertFalse(h.ui.clock.alarm(id)!!.enabled)
        assertEquals(Codes.UNAVAILABLE, h.call("alarm.snooze").code)
        assertEquals(Codes.UNAVAILABLE, h.call("alarm.dismiss").code)
        assertTrue(h.call("alarm.delete", mapOf("id" to "6:45 am")).ok)
        assertEquals(Codes.FAILED, h.call("alarm.delete", mapOf("id" to id)).code)
    }

    @Test fun phoneAlarmsAreHiddenExperimentalOps(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        val fake = io.lampyr.glasses.clock.FakeGoogleClock(mutableListOf(
            io.lampyr.glasses.clock.PhoneAlarm(6, 30, "Wake up", "Mon, Tue, Wed, Thu, Fri", true),
            io.lampyr.glasses.clock.PhoneAlarm(7, 0, "Work", "Mon, Tue", false),
            io.lampyr.glasses.clock.PhoneAlarm(7, 0, "Weekend", "Sat, Sun", true),
        ))
        val book = h.ui.clock.phoneAlarms
        book.worker = kotlinx.coroutines.Dispatchers.Unconfined
        book.attach(fake, io.lampyr.glasses.clock.MemoryClockPersistence())
        assertFalse(h.registry.ops["alarm.phone_list"]!!.tool)
        assertFalse(h.registry.ops["alarm.phone_toggle"]!!.tool)
        val cached = h.call("alarm.phone_list")
        assertTrue(cached.ok)
        assertTrue(cached.message!!.contains("refresh=true"))
        assertEquals(0, fake.opens)
        val read = h.call("alarm.phone_list", mapOf("refresh" to true))
        assertTrue(read.message, read.ok)
        val alarms = read.data!!.jsonObject["alarms"]!!.jsonArray
        assertEquals(3, alarms.size)
        assertEquals("6:30 AM", alarms[0].jsonObject["time"]!!.jsonPrimitive.content)
        assertTrue(read.data!!.jsonObject["experimental"]!!.jsonPrimitive.boolean)
        // Two alarms at 7:00: the label decides.
        assertEquals(Codes.AMBIGUOUS, h.call("alarm.phone_toggle", mapOf("time" to "7:00 am")).code)
        val on = h.call("alarm.phone_toggle", mapOf("time" to "7 am", "label" to "work"))
        assertTrue(on.message, on.ok)
        assertTrue(fake.alarms[1].enabled)
        assertTrue(book.alarms[1].enabled)
        assertTrue(h.call("alarm.phone_toggle", mapOf("time" to "6:30", "enabled" to false)).ok)
        assertFalse(fake.alarms[0].enabled)
        assertEquals(Codes.FAILED, h.call("alarm.phone_toggle", mapOf("time" to "4:00")).code)
        fake.locked = true
        val locked = h.call("alarm.phone_list", mapOf("refresh" to true))
        assertEquals(Codes.UNAVAILABLE, locked.code)
        assertEquals("Phone is locked; unlock it to use phone alarms", locked.message)
    }

    @Test fun dismissAndSnoozeWhatIsRinging(): Unit = ActionRegistryTest.Harness(listOf(ClockOps)).use { h ->
        val c = h.ui.clock
        c.restore(io.lampyr.glasses.clock.ClockSnapshot(ringing = listOf(io.lampyr.glasses.clock.Ringing("timer", "t1", "Tea", sinceWallMs = h.ui.nowMs(), sinceElapsedMs = 0))))
        assertTrue(h.call("alarm.snooze").ok)
        assertEquals(1, c.timers.size)
        c.restore(io.lampyr.glasses.clock.ClockSnapshot(ringing = listOf(io.lampyr.glasses.clock.Ringing("timer", "t1", "Tea", sinceWallMs = h.ui.nowMs(), sinceElapsedMs = 0))))
        assertTrue(h.call("alarm.dismiss").ok)
        assertTrue(c.ringing.isEmpty())
        // An upcoming repeating alarm that is not ringing: dismiss skips its next occurrence.
        val id = c.alarmSet(7, 0, (1..7).toSet()).id!!
        val before = c.nextAlarmAt(c.alarm(id)!!)!!
        assertTrue(h.call("alarm.dismiss", mapOf("id" to id)).ok)
        assertEquals(before + 86_400_000L, c.nextAlarmAt(c.alarm(id)!!))
    }
}
