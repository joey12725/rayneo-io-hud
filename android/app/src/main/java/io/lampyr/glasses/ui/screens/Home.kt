package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import java.util.Locale
import kotlin.math.*

class Home(ui: Ui) : Screen(ui, "home") {
    override val textHintRequired get() = false // All seven rows carry the dashboard and its five shortcuts.
    var cur = 0

    private fun items(): List<Pair<String, String>> {
        val chats = ui.data.beeper.chats
        val unread = ui.data.home.beeper?.unread ?: ui.data.beeper.unreadTotal
        val threads = ui.data.t3.threads
        val np = ui.data.media.nowPlaying
        val need = ui.data.t3.needYouCount
        val done = ui.data.home.t3?.done ?: threads.count { it.status == "done" }
        return listOf(
            "beeper" to
                if (unread > 0)
                    "$unread unread · " +
                        (ui.data.home.beeper?.names?.joinToString(", ") ?: chats.filter { it.countsTowardUnread }.joinToString(", ") { it.name })
                else "No unread messages",
            "t3" to "$need need you · $done done",
            (if (np?.source == "spotify") "note"
            else if (np?.source == "youtube") "youtube" else "book") to
                ((if (np?.playing == false) "Paused · " else "") +
                    (np?.title ?: "Nothing playing") +
                    (if (np?.source == "spotify") " · " + np.artist else "")),
            (if (ui.data.calls.snapshot.calls.isNotEmpty()) "phone" else "bell") to
                if (ui.data.calls.snapshot.calls.isNotEmpty()) "Phone / " + (ui.data.calls.snapshot.ringing ?: ui.data.calls.snapshot.ongoing ?: ui.data.calls.snapshot.calls.first()).name
                else if (callNotifications(ui).isEmpty()) "No notifications"
                else "${callNotifications(ui).size} notifications",
            "calendar" to
                (ui.data.home.nextEvent?.let { it.title + " · " + eventWhen(it.start, it.allDay, it.startDate, it.endDate, it.timeLabel, ui) }
                    ?: "No upcoming events"),
        )
    }

    override fun onOpen() {
        ui.refresh { ui.data.refreshHome() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val ts = ui.dateString("h:mm")
            tx(ts, 18, 8, "xl")
            val cw = tw(ts, "xl")
            tx(ui.dateString("a"), 18 + cw + 8, 8 + lh("xl") - lh() - 4)
            val rx = max(18 + cw + 70, 210)
            tx(caseText(ui.dateString("EEEE, MMM d")), rx, 8, tone = SECONDARY)
            val weather = ui.data.home.weather
            icon(weather.icon, rx, 32, 24, t)
            // AMB-14: a muted day is easy to forget, so Home says so at full brightness on the weather row.
            val off = if (ui.listening.on) 0 else tw(LISTEN_OFF, "s") + 12
            if (off > 0) txR(LISTEN_OFF, W - 12, 34, "s")
            tx(fit(ui.clockSummary() ?: "${weather.temperatureLabel} · ${weather.condition.replace(" after ", " ")}", W - rx - 38 - off, "s"), rx + 30, 34, "s", tone = SECONDARY)
            var y = 72
            val rh = (BOTTOM - y) / 5
            items().forEachIndexed { i, (ic, label) ->
                val on = i == cur
                if (on) {
                    p.rr(X0, y - 3, XW, rh - 2, 6, 2, true)
                    p.fr(X0 + 5, y, 30, rh - 8, BLACK)
                }
                icon(ic, X0 + 8, y + (rh - 24) / 2.0 - 3, 24, t)
                val col = if (on) BLACK else GREEN
                val yy = y + (rh - 6 - lh()) / 2.0
                if (i == 2) marq(label, X0 + 46, yy, XW - 56, "m", col, t)
                else tx(if (i == 4) fitEvent(label, XW - 56) else fit(label, XW - 56), X0 + 46, yy, "m", col)
                y += rh
            }
        }

    override fun key(k: String): Boolean {
        when (k) {
            "down" -> cur = min(4, cur + 1)
            "up" -> cur = max(0, cur - 1)
            // Home is the root, so B has nothing to go back to: it opens Apps, and B in Apps comes back here.
            "back" -> ui.push(Apps(ui))
            "sel" ->
                if (cur == 3 && ui.data.calls.snapshot.calls.isNotEmpty()) ui.openCalls() else ui.push(ui.app(listOf("beeper", "t3", "media", "notifications", "calendar")[cur]))
            else -> return false
        }
        return true
    }

    override fun legend() = mapOf("up" to "Move", "down" to "Move", "sel" to "Open", "back" to "Apps")

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, ui.clockString(), ui.dateString("EEE MMM d"))
        val w = ui.data.home.weather
        tg.put(0, 1, TextChrome.fit(if (ui.data.calls.snapshot.calls.isNotEmpty()) "PHONE CALL / Notifs opens"
            else (if (ui.listening.on) "" else "$LISTEN_OFF ") + (ui.clockSummary() ?: "${w.temperatureLabel.replace("°", "")} ${w.condition}"), tg.cols))
        val names = listOf("Msgs", "T3", "Play", "Notifs", "Next")
        val rows = items().mapIndexed { i, (_, value) ->
            if (i == 4 && ui.data.home.nextEvent != null) {
                val event = ui.data.home.nextEvent!!
                Row("Next " + eventWhen(event.start, event.allDay, event.startDate, event.endDate, event.timeLabel, ui) + " " + event.title)
            } else if (i == 3) Row(if (ui.data.calls.snapshot.calls.isNotEmpty()) "Phone: " + (ui.data.calls.snapshot.ringing ?: ui.data.calls.snapshot.ongoing ?: ui.data.calls.snapshot.calls.first()).name else "Notifs: ${callNotifications(ui).size} new")
            else Row("${names[i]}: $value")
        }
        // Seven rows fit all five badges; font 2 scrolls the focused badge into view.
        TextChrome.list(tg, rows, cur, 2, tg.rows)
    }
}

const val LISTEN_OFF = "LISTEN OFF"

/** Truncates the event title, never its "when" ("Long title… · Thu · All day"), so the day always shows. */
internal fun fitEvent(label: String, maxW: Int): String {
    val at = label.indexOf(" · ")
    if (at < 0 || tw(label) <= maxW) return fit(label, maxW)
    val tail = label.substring(at)
    val room = maxW - tw(tail)
    return if (room < 40) fit(label, maxW) else fit(label.substring(0, at), room) + tail
}

/** Says the day for any event that is not today ("Thu · All day", "Tomorrow 3:00 PM"); fixtures keep their label. */
fun eventWhen(start: String, allDay: Boolean, startDate: String, endDate: String, label: String, ui: Ui): String =
    io.lampyr.glasses.data.GatewayMappings.eventWhen(start, allDay, startDate, endDate,
        java.time.Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate(), ui.zone) ?: eventTime(start, allDay, label, ui)

fun eventTime(start: String, allDay: Boolean, label: String, ui: Ui): String =
    label.ifBlank { if (allDay) "All day" else displayTime(start, ui) }

fun displayTime(time: String, ui: Ui): String =
    try {
        java.time.Instant.parse(time)
            .atZone(ui.zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    } catch (_: Exception) {
        time
    }
