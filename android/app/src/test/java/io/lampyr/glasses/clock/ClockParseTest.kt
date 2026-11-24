package io.lampyr.glasses.clock

import io.lampyr.glasses.clock.ClockParse.Command
import org.junit.Assert.*
import org.junit.Test

class ClockParseTest {
    private val min = 60_000L

    @Test fun durations() {
        assertEquals(10 * min, ClockParse.duration("10 minutes"))
        assertEquals(10 * min, ClockParse.duration("ten minutes"))
        assertEquals(25 * min, ClockParse.duration("twenty five minutes"))
        assertEquals(90 * min, ClockParse.duration("an hour and a half"))
        assertEquals(90 * min, ClockParse.duration("1h30m"))
        assertEquals(90 * min, ClockParse.duration("1h30"))
        assertEquals(90 * min, ClockParse.duration("1 hour 30 minutes"))
        assertEquals(30 * min, ClockParse.duration("half an hour"))
        assertEquals(15 * min, ClockParse.duration("a quarter of an hour"))
        assertEquals(150_000L, ClockParse.duration("2 and a half minutes"))
        assertEquals(150_000L, ClockParse.duration("2.5 min"))
        assertEquals(90_000L, ClockParse.duration("90 s"))
        assertEquals(90_000L, ClockParse.duration("1:30"))
        assertEquals(3_723_000L, ClockParse.duration("1:02:03"))
        assertEquals(5 * min, ClockParse.duration("5"))
        assertEquals(min, ClockParse.duration("a minute"))
        assertEquals(10 * min, ClockParse.duration("Set a timer for 10 minutes."))
        assertNull(ClockParse.duration("pasta"))
        assertNull(ClockParse.duration(""))
    }

    @Test fun times() {
        assertEquals(7 to 30, ClockParse.time("7:30 am"))
        assertEquals(7 to 30, ClockParse.time("7:30 a.m."))
        assertEquals(19 to 30, ClockParse.time("7:30 PM"))
        assertEquals(19 to 45, ClockParse.time("19:45"))
        assertEquals(7 to 30, ClockParse.time("730am"))
        assertEquals(19 to 0, ClockParse.time("7 pm"))
        assertEquals(19 to 30, ClockParse.time("seven thirty pm"))
        assertEquals(6 to 15, ClockParse.time("six fifteen in the morning"))
        assertEquals(0 to 0, ClockParse.time("12 am"))
        assertEquals(12 to 0, ClockParse.time("noon"))
        assertEquals(0 to 0, ClockParse.time("midnight"))
        assertEquals(7 to 30, ClockParse.time("half past 7"))
        assertEquals(7 to 45, ClockParse.time("quarter to 8"))
        assertEquals(6 to 0, ClockParse.time("wake me up at 6"))
        assertEquals(7 to 0, ClockParse.time("7"))
        assertNull(ClockParse.time("25:00"))
        assertNull(ClockParse.time("7:75"))
        assertNull(ClockParse.time("tomorrow"))
    }

    @Test fun days() {
        assertEquals((1..7).toSet(), ClockParse.days("every day"))
        assertEquals((1..5).toSet(), ClockParse.days("weekdays"))
        assertEquals(setOf(6, 7), ClockParse.days("on weekends"))
        assertEquals(setOf(1, 3, 5), ClockParse.days("mon wed fri"))
        assertEquals(setOf(1, 5), ClockParse.days("Monday and Friday"))
        assertEquals(emptySet<Int>(), ClockParse.days("once"))
        assertNull(ClockParse.days("7:30"))
    }

    @Test fun spokenCommands() {
        assertEquals(Command.Timer(10 * min), ClockParse.command("set a timer for ten minutes"))
        assertEquals(Command.Timer(8 * min, "Pasta"), ClockParse.command("pasta eight minutes"))
        assertEquals(Command.Timer(2 * min, "Tea"), ClockParse.command("Tea timer, 2 minutes"))
        assertEquals(Command.Alarm(7, 30), ClockParse.command("7:30 am"))
        assertEquals(Command.Alarm(6, 0, (1..5).toSet()), ClockParse.command("wake me up at 6 on weekdays"))
        assertEquals(Command.Alarm(19, 0, label = "Call mom"), ClockParse.command("alarm 7 pm call mom"))
        // On the Alarms screen a bare time is an alarm; on the timer screen "7:30" is a duration.
        assertEquals(Command.Alarm(7, 30), ClockParse.command("7:30", prefer = "alarm"))
        assertEquals(Command.Timer(450_000), ClockParse.command("7:30"))
        assertNull(ClockParse.command("what's the weather"))
    }
}
