package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.CalendarDay
import io.lampyr.glasses.ui.screens.CalendarScreen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.*

/** Calendar: the agenda for the next N days, and open the glasses agenda at a given day. */
object CalendarOps : OpProvider {
    private fun AgentCall.today(): LocalDate = Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate()

    private fun date(d: CalendarDay) = runCatching { LocalDate.parse(d.date) }.getOrNull()

    /** "today", "tomorrow", a weekday ("friday", the next one), or YYYY-MM-DD. */
    private fun AgentCall.day(): LocalDate {
        val wanted = str("day").trim().lowercase()
        val today = today()
        return when (wanted) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> runCatching { LocalDate.parse(wanted) }.getOrNull()
                ?: DayOfWeek.values().firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase().startsWith(wanted.take(3)) && wanted.length >= 3 }
                    ?.let { dow -> (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == dow } }
                ?: fail(Codes.INVALID_ARGS, "day must be today, tomorrow, a weekday or YYYY-MM-DD")
        }
    }

    override fun ops() = listOf(
        OpSpec(
            "calendar.agenda", "Read the agenda",
            "The user's calendar for the next N days (today first): each day's date and events with time, title, location and whether all-day. Multi-day events list on each day.",
            params = listOf(Param.integer("days", "Days including today (default 3)", min = 1, max = 14)),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.calendar.refresh()
            val days = int("days", 3)
            val today = today()
            val agenda = ui.data.calendar.agenda.filter { d -> date(d)?.let { !it.isBefore(today) && it.isBefore(today.plusDays(days.toLong())) } ?: true }
                .take(days)
            ok(data = JsonArray(agenda.map { d ->
                buildJsonObject {
                    put("day", d.day); put("date", d.date)
                    put("events", JsonArray(d.events.take(MAX_ITEMS).map { e ->
                        buildJsonObject {
                            put("id", e.id); put("title", e.title)
                            if (e.allDay) put("allDay", true) else put("time", e.timeLabel.ifBlank { e.start })
                            putText("end", e.end.takeUnless { e.allDay }); putText("location", e.location)
                            putText("notes", e.notes.takeIf { it.isNotBlank() }?.let { clip(it, 300) })
                        }
                    }))
                }
            }))
        },

        OpSpec(
            "calendar.open_day", "Show a calendar day", "Open the glasses calendar with the cursor on one day's first event (today, tomorrow, a weekday or YYYY-MM-DD).",
            params = listOf(Param.string("day", "today, tomorrow, a weekday (friday) or YYYY-MM-DD", required = true, maxLength = 20)),
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            val wanted = day()
            val list = (ui.top as? CalendarScreen) ?: show(CalendarScreen(ui)) as CalendarScreen
            val days = ui.data.calendar.agenda
            val target = days.firstOrNull { date(it) == wanted }
                ?: fail(Codes.UNAVAILABLE, "${wanted} is not in the glasses agenda (it covers ${days.firstOrNull()?.date ?: "nothing"} to ${days.lastOrNull()?.date ?: "nothing"})")
            // Days without events have no header row (except today); then the cursor lands on the next day shown.
            val rows = list.rows()
            val header = rows.indexOfFirst { it.sep && it.label == target.day }
            if (header >= 0) list.cur = (header + 1).coerceAtMost(rows.lastIndex)
            else days.indexOf(target).let { i -> days.drop(i + 1).firstNotNullOfOrNull { d -> rows.indexOfFirst { it.sep && it.label == d.day }.takeIf { it >= 0 } } }
                ?.let { list.cur = it + 1 }
            list.fix()
            ui.requestRender()
            ok(if (target.events.isEmpty()) "No events on ${target.day.lowercase()}" else "Showing ${target.day.lowercase()}", registry.context())
        },
    )
}
