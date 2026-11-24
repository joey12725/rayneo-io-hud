package io.lampyr.glasses

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Requirements 11-13: day-aware calendar labels, date-window health charts and metric picker, stale reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarHealthReportTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val tuesdayEvening = Instant.parse("2026-11-18T01:46:00Z").toEpochMilli() // Tue Nov 17, 7:46 PM CST

    private inner class Fixture(var now: Long = tuesdayEvening) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { now }, scope)
        fun ui(store: DataStore, text: Boolean = false) = Ui(store, scope = scope, nowS = { 0.0 }, nowMs = { now }, zone = chicago)
            .apply { if (text) display = Display.Text() }
    }
    private fun grid(view: View): List<String> = TextGrid(26, 7, false).also { view.drawText(it) }.lines()

    @Test fun eventLabelsNameTheDayWhenNotToday() {
        val today = LocalDate.of(2026, 11, 17)
        fun label(start: String, allDay: Boolean, from: String = "", to: String = "", zone: ZoneId = chicago, on: LocalDate = today) =
            GatewayMappings.eventWhen(start, allDay, from, to, on, zone)
        assertEquals("All day", label("2026-11-17T06:00:00.000Z", true, "2026-11-17", "2026-11-17"))
        assertEquals("Tomorrow · All day", label("2026-11-18T06:00:00.000Z", true, "2026-11-18", "2026-11-18"))
        assertEquals("Thu · All day", label("2026-11-19T06:00:00.000Z", true, "2026-11-19", "2026-11-19"))
        assertEquals("All day", label("2026-11-16T06:00:00.000Z", true, "2026-11-16", "2026-11-18"), )
        assertEquals("Nov 30 · All day", label("2026-11-30T06:00:00.000Z", true, "2026-11-30", "2026-11-30"))
        assertEquals("7:00 PM", label("2026-11-18T01:00:00Z", false))
        assertEquals("Tomorrow 3:00 PM", label("2026-11-18T21:00:00Z", false))
        assertEquals("Thu 7:00 PM", label("2026-11-20T01:00:00Z", false))
        assertEquals("Yesterday 11:00 PM", label("2026-11-17T05:00:00Z", false))
        // Midnight: 11:59 PM Wednesday Friendsgiving is tomorrow; a minute later it is today.
        assertEquals("Tomorrow · All day", label("2026-11-19T06:00:00.000Z", true, "2026-11-19", "2026-11-19", on = LocalDate.of(2026, 11, 18)))
        assertEquals("All day", label("2026-11-19T06:00:00.000Z", true, "2026-11-19", "2026-11-19", on = LocalDate.of(2026, 11, 19)))
        // Phone in Los Angeles: Chicago midnight is 10 PM the evening before, but the all-day date does not move.
        assertEquals("Thu · All day", label("2026-11-19T06:00:00.000Z", true, "2026-11-19", "2026-11-19", zone = ZoneId.of("America/Los_Angeles")))
        assertNull(label("8:00 PM", false)); assertNull(label("8:00 PM", true))
    }

    private fun homeWith(f: Fixture, event: NextEvent) = object : DataStore by f.data { override val home = f.data.home.copy(nextEvent = event) }
    private val friendsgiving = NextEvent("Friendsgiving", "2026-11-19T06:00:00.000Z", allDay = true, timeLabel = "All day",
        end = "2026-11-20T06:00:00.000Z", startDate = "2026-11-19", endDate = "2026-11-19")

    @Test fun homeBadgeSaysTheDayAndRollsOverAtMidnight() {
        val f = Fixture()
        val ui = f.ui(homeWith(f, friendsgiving), text = true)
        try {
            val home = Home(ui)
            assertTrue(grid(home).joinToString("\n"), grid(home).any { it.trim().startsWith("Next Thu") })
            f.now = Instant.parse("2026-11-19T05:59:00Z").toEpochMilli()
            assertTrue(grid(home).any { it.trim().startsWith("Next Tomorrow") })
            f.now = Instant.parse("2026-11-19T06:01:00Z").toEpochMilli()
            assertTrue(grid(home).joinToString("|"), grid(home).any { it.trim().startsWith("Next All day Friends") })
            // Fixtures with display strings keep their label.
            assertEquals("8:00 PM", eventWhen("8:00 PM", false, "", "", "", ui))
        } finally { ui.close() }
    }

    @Test fun homeBadgeTruncatesTheTitleNotTheDay() {
        val label = "A very long dinner party title that never fits on one row · Tomorrow 7:00 PM"
        val fitted = fitEvent(label, 300)
        assertTrue(fitted, fitted.endsWith(" · Tomorrow 7:00 PM")); assertTrue(fitted, fitted.contains("…"))
        assertTrue(tw(fitted) <= 300)
        assertEquals("Lunch · Thu · All day", fitEvent("Lunch · Thu · All day", 300))
        assertEquals("No upcoming events", fitEvent("No upcoming events", 300))
    }

    @Test fun calendarSkipsEmptyDaysKeepsTodayAndLabelsEachDay() {
        val f = Fixture()
        fun allDay(id: String, from: String, to: String) = CalendarEvent(id, "Trip", "${from}T06:00:00.000Z", allDay = true, timeLabel = "All day", displaySubtitle = "All day", startDate = from, endDate = to)
        val agenda = listOf(
            CalendarDay("TODAY", "2026-11-17", emptyList()), CalendarDay("TOMORROW", "2026-11-18", emptyList()),
            CalendarDay("THU NOV 19", "2026-11-19", listOf(allDay("trip", "2026-11-19", "2026-11-21"),
                CalendarEvent("diy", "DIY night", "2026-11-20T01:00:00.000Z", timeLabel = "7:00 PM", displaySubtitle = "7:00 PM · Garage", location = "Garage"))),
            CalendarDay("FRI NOV 20", "2026-11-20", listOf(allDay("trip", "2026-11-19", "2026-11-21"))),
        )
        val store = object : DataStore by f.data {
            override val calendar = object : CalendarRepository by f.data.calendar { override val agenda = agenda }
        }
        val ui = f.ui(store)
        try {
            val screen = CalendarScreen(ui)
            val rows = screen.rows()
            assertEquals(listOf("TODAY", "No more events today", "THU NOV 19", "Trip", "DIY night", "FRI NOV 20", "Trip"), rows.map { it.label })
            assertFalse(rows[1].selectable)
            screen.fix(); assertEquals(3, screen.cur)
            assertEquals(listOf("Thu · All day", "Thu 7:00 PM · Garage", "Fri · All day").map { layout(it, CW - 60, "s")[0] },
                rows.filter { it.subLines != null }.map { it.subLines })
            assertEquals(listOf("TODAY", "No more events today", "THU NOV 19", "Thu · All day Trip", "Thu 7:00 PM DIY night", "FRI NOV 20", "Fri · All day Trip"),
                screen.textItems().map { it.label })
            // The same cached agenda after midnight (Wed 12:30 AM, offline): yesterday's TODAY is gone and headers move on.
            f.now = Instant.parse("2026-11-18T06:30:00Z").toEpochMilli()
            assertEquals(listOf("TODAY", "No more events today", "TOMORROW", "Trip", "DIY night", "FRI NOV 20", "Trip"), screen.rows().map { it.label })
            assertEquals(listOf("TODAY", "No more events today", "TOMORROW", "Tomorrow · All day Trip", "Tomorrow 7:00 PM DIY night", "FRI NOV 20", "Fri · All day Trip"),
                screen.textItems().map { it.label })
        } finally { ui.close() }
    }

    private fun days(n: Int, last: String) = (n - 1 downTo 0).map { LocalDate.parse(last).minusDays(it.toLong()).toString() }
    private fun healthStore(f: Fixture, metrics: List<Metric>) = object : DataStore by f.data {
        override val health = object : HealthRepository by f.data.health { override val metrics = metrics }
    }
    private val weight = Metric("weight", "Weight", "174.6 lb", "Nov 10", listOf("2026-10-23" to 172.8, "2026-10-27" to 173.4, "2026-11-04" to 174.0, "2026-11-08" to 174.1, "2026-11-10" to 174.6).map { Sample(it.first, it.second) },
        breakdown = listOf(Breakdown("30-day change", "+1.8 lb", key = "change30", unit = "lb", trend = "up")), icon = "status")
    // 15 nightly scores Oct 31..Nov 8 and Nov 12..17: a gap of three nights.
    private val sleep = Metric("sleep", "Sleep", "68", "Woke 7:01 AM", (days(9, "2026-11-08") + days(6, "2026-11-17")).mapIndexed { i, d -> Sample(d, 60.0 + i) }, measuredKind = "wake",
        breakdown = listOf(
            Breakdown("Total sleep", "5h 2m", key = "total_min", unit = "min", series = days(5, "2026-11-17").map { Sample(it, 302.0) }),
            Breakdown("Deep sleep", score = 62.0, key = "deep_sleep", unit = "score", series = days(5, "2026-11-17").mapIndexed { i, d -> Sample(d, 50.0 + i * 3) }),
            Breakdown("Deep time", "1h 12m", key = "deep_min", unit = "min", series = days(5, "2026-11-17").mapIndexed { i, d -> Sample(d, 60.0 + i * 3) }),
            Breakdown("Source", "Oura"),
        ), icon = "moon")

    @Test fun chartUsesTheDateWindowSoSevenAndThirtyDaysDiffer() {
        val f = Fixture()
        val ui = f.ui(healthStore(f, listOf(weight, sleep)))
        try {
            val w = HealthDetail(ui, "weight"); ui.push(w)
            assertTrue("no weigh-in in the last 7 days", w.window().all { it == null }); assertEquals(7, w.window().size)
            val seven = ui.render().bits.copyOf()
            w.key("right"); assertEquals(30, w.range)
            assertEquals(5, w.window().count { it != null }); assertEquals(30, w.window().size)
            assertFalse("weight 7D and 30D frames differ", seven.contentEquals(ui.render().bits))
            ui.pop()
            val s = HealthDetail(ui, "sleep"); ui.push(s)
            // Sleep has 15 samples with a gap Nov 9-11: 7D is exactly Nov 11..17 (Nov 11 missing), not the last 7 samples.
            assertEquals(listOf(null, 69.0, 70.0, 71.0, 72.0, 73.0, 74.0), s.window())
            val sleep7 = ui.render().bits.copyOf()
            s.key("left"); assertEquals(30, s.range); assertEquals(15, s.window().count { it != null })
            assertFalse(sleep7.contentEquals(ui.render().bits))
            s.key("right"); assertEquals(7, s.range)
            assertTrue("7D/30D switching returns the same 7D frame", sleep7.contentEquals(ui.render().bits))
        } finally { ui.close() }
    }

    @Test fun fixtureSeriesWithSyntheticDatesKeepLastSamples() {
        val f = Fixture()
        val ui = f.ui(f.data)
        try {
            val m = f.data.health.metrics.first()
            val detail = HealthDetail(ui, m.key)
            assertEquals(m.series.takeLast(7).map { it.value }, detail.window())
        } finally { ui.close() }
    }

    @Test fun metricPickerChartsAnySleepMetricAndKeepsTheWindow() {
        val f = Fixture()
        val ui = f.ui(healthStore(f, listOf(weight, sleep)), text = true)
        try {
            ui.push(Health(ui))
            val main = HealthDetail(ui, "sleep"); ui.push(main)
            main.key("right"); assertEquals(30, main.range)
            assertEquals(listOf("total_min", "deep_sleep", "deep_min"), main.pickable.map { it.key })
            assertTrue(main.key("sel"))
            val picker = ui.top as HealthPicker
            assertEquals(listOf("Sleep", "Total sleep", "Deep sleep", "Deep time"), picker.rows().map { it.label })
            picker.key("down"); picker.key("down"); picker.key("down"); picker.key("sel")
            val deep = ui.top as HealthDetail
            assertEquals("deep_min", deep.sub); assertEquals(30, deep.range)
            val lines = grid(deep)
            assertTrue(lines.joinToString("\n"), lines[0].startsWith("Deep time"))
            assertTrue(lines.joinToString("\n"), lines.any { it.contains("1h 12m") })
            assertEquals(5, deep.window().count { it != null })
            // Picking again replaces the sub chart; B then returns to the metric's own chart.
            deep.key("sel"); (ui.top as HealthPicker).let { assertEquals(3, it.cur); it.key("up"); it.key("sel") }
            assertEquals("deep_sleep", (ui.top as HealthDetail).sub)
            ui.pop(); assertSame(main, ui.top)
            assertTrue(grid(ui.top).last().contains("Y pick"))
        } finally { ui.close() }
    }

    @Test fun breakdownShowsSignedChangeWithTrendMark() {
        val f = Fixture()
        val ui = f.ui(healthStore(f, listOf(weight)), text = true)
        try {
            val w = HealthDetail(ui, "weight"); ui.push(w)
            w.key("down")
            assertTrue(grid(w).joinToString("\n"), grid(w).any { it.contains("30-day change") && it.endsWith("^+1.8 lb") })
            ui.display = Display.Bitmap
            val up = ui.render().bits.copyOf()
            val other = f.ui(healthStore(f, listOf(weight.copy(breakdown = listOf(weight.breakdown[0].copy(trend = null))))))
            try {
                other.push(HealthDetail(other, "weight").also { it.page = 1 })
                assertFalse("the ▲ mark is drawn", up.contentEquals(other.render().bits))
            } finally { other.close() }
        } finally { ui.close() }
    }

    @Test fun healthHidesMetricsWithoutValueOrSamples() {
        val f = Fixture()
        val empty = Metric("readiness", "Readiness", "—", "", emptyList())
        val ui = f.ui(healthStore(f, listOf(weight, empty, sleep)), text = true)
        try {
            val health = Health(ui); ui.push(health)
            val lines = grid(health).joinToString("\n")
            assertFalse(lines, lines.contains("Readiness")); assertTrue(lines.contains("Sleep"))
            health.key("down"); health.key("sel")
            assertEquals("Sleep", grid(ui.top)[0].take(5))
        } finally { ui.close() }
    }

    @Test fun sleepLabelsUseWakeTimeOrDayNeverMidnightUtc() {
        val now = Instant.parse("2026-11-17T19:00:00Z").toEpochMilli()
        assertEquals("Woke 7:01 AM", GatewayMappings.measuredLabel("2026-11-17T13:01:59Z", "wake", "2026-11-17", now, chicago))
        assertEquals("Woke yesterday 6:40 AM", GatewayMappings.measuredLabel("2026-11-16T12:40:00Z", "wake", "2026-11-16", now, chicago))
        assertEquals("Night of Nov 14", GatewayMappings.measuredLabel("2026-11-15T13:00:00Z", "wake", "2026-11-15", now, chicago))
        assertEquals("Today", GatewayMappings.measuredLabel("2026-11-17T18:00:00Z", "day", "2026-11-17", now, chicago))
        assertEquals("Nov 15", GatewayMappings.measuredLabel("2026-11-15T18:00:00Z", "day", "2026-11-15", now, chicago))
        // Old payloads (no kind) keep the previous clock label.
        assertEquals("Yesterday 6:00 PM", GatewayMappings.measuredLabel("2026-11-17T00:00:00Z", "", "", now, chicago))
    }

    private fun reportStore(f: Fixture, date: String) = object : DataStore by f.data {
        override val report = object : ReportRepository by f.data.report {
            override val snapshot = ReportSnapshot(date, "2026-11-17T11:50:00Z", (1..3).map { ReportCard("note", "news", "Card $it", "Body $it") }, reportDate = date)
        }
    }

    @Test fun staleReportSaysWhichDayInBothDisplays() {
        val f = Fixture()
        val stale = f.ui(reportStore(f, "2026-11-12"))
        val fresh = f.ui(reportStore(f, "2026-11-17"))
        try {
            assertEquals("Report from Nov 12" to "5 days old", reportHeader(stale.data.report.snapshot, stale))
            assertEquals("Morning report" to "Tue Nov 17", reportHeader(fresh.data.report.snapshot, fresh))
            stale.push(Report(stale)); fresh.push(Report(fresh))
            assertFalse(stale.render().bits.contentEquals(fresh.render().bits))
            stale.display = Display.Text()
            assertTrue(grid(stale.top)[0], grid(stale.top)[0].endsWith("from Nov 12"))
            // A cached fresh report goes stale after midnight without a refetch.
            f.now = Instant.parse("2026-11-18T06:01:00Z").toEpochMilli()
            assertEquals("Report from Nov 17" to "yesterday", reportHeader(fresh.data.report.snapshot, fresh))
            // Fixture dates that are already display strings are shown as before.
            assertEquals("Morning report" to "Wed Nov 11", reportHeader(f.data.report.snapshot, fresh))
        } finally { stale.close(); fresh.close() }
    }

    @Test fun gatewayPayloadsDecodeTheNewFields() {
        val fixtures = gatewayJson.parseToJsonElement(javaClass.getResource("/gateway/responses.json")!!.readText()).jsonObject
        val home = GatewayMappings.home(fixtures.getValue("GET /home"))
        assertEquals("2026-11-12" to "2026-11-12", home.nextEvent!!.startDate to home.nextEvent!!.endDate)
        assertEquals("2026-11-12T21:00:00Z", home.nextEvent!!.end)
        assertEquals("2026-11-12", GatewayMappings.agenda(fixtures.getValue("GET /calendar/agenda")).single().events.single().endDate)
        val health = GatewayMappings.metrics(fixtures.getValue("GET /health/overview"), Instant.parse("2026-11-12T20:00:00Z").toEpochMilli())
        val readiness = health.first { it.key == "readiness" }
        assertEquals("wake", readiness.measuredKind); assertTrue(readiness.measuredAt.startsWith("Woke "))
        assertEquals(listOf(60.0, 62.0), readiness.breakdown.first().series.map { it.value }); assertEquals("score", readiness.breakdown.first().unit)
        assertEquals("up", readiness.breakdown[1].trend); assertEquals("+1.8 lb", readiness.breakdown[1].value)
        val report = GatewayMappings.decode<ReportSnapshot>(fixtures.getValue("GET /report/latest"))
        assertTrue(report.stale); assertFalse(report.isToday); assertEquals(5, report.ageDays); assertTrue(report.fallback); assertEquals("2026-11-12", report.reportDate)
    }
}
