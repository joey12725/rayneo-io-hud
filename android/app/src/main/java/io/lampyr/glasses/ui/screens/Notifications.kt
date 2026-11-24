package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

class Notifications(ui: Ui) :
    ListView(
        ui,
        "notif",
        hint = "Y open · X clear · + armed",
        items = {
            val items = sortNotifications(callNotifications(ui), ui.nowMs())
            if (items.isEmpty()) listOf(Row("All caught up", icon = "check"))
            else
                items.map { n ->
                    Row(
                        n.title,
                        icon = n.icon,
                        sub = "",
                        subLines = layout(n.body, CW - 70, "s")[0],
                        right =
                            age(((ui.nowMs() - n.at) / 1000).coerceAtLeast(0)) +
                                " " +
                                "●"
                                    .repeat(
                                        if (n.importance > .75) 3
                                        else if (n.importance > .45) 2 else 1
                                    ),
                        selLabel = if (n.open != null) "Open in app" else "Read",
                        actLabel = if (n.id == "call-current") "Call continues" else "Dismiss",
                        // Y closes this list and opens the owning app at the notification's target (requirement 3.8).
                        open = { ui.openNotification(n) },
                        act = {
                            if (n.id == "call-current") ui.flash("Call continues / Y opens")
                            else ui.perform("Dismissed") { dismissNotice(ui, n.id) }
                        },
                    )
                }
        },
        chrome = { t ->
            frame(
                ui,
                "Notifications",
                "bell",
                t,
                railText = listOf("${callNotifications(ui).size} new", "sorted by ${io.lampyr.glasses.settings.Names.classifier}"),
            )
        },
    ) {
    private var clearUntil: Double? = null

    override fun onClose() { clearUntil = null }

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, "Notifications", "${callNotifications(ui).size}")
        TextChrome.list(tg, textItems(), cur, end = tg.rows - 2)
        tg.put(0, tg.rows - 2, TextChrome.fit(
            if (clearUntil?.let { ui.nowS() <= it } == true) "Hold + again to clear all" else "Hold + clears all", tg.cols))
        TextChrome.footer(tg, hint = textHint())
    }

    init {
        refreshHook = { ui.data.notifications.refresh() }
        textItems = { rows().map { it.copy(right = it.right.replace("●", "!")) } }
        textHint = { "Y open X clear + armed" }
        onKey = { k, _ ->
            if (k == "plus") {
                clearUntil = null
                ui.push(ArmedReminders(ui))
                true
            } else if (k == "plusHold") {
                if (clearUntil?.let { ui.nowS() <= it } == true) {
                    clearUntil = null
                    ui.perform("All cleared") { ui.data.calls.missed.toList().forEach { ui.data.calls.dismissMissed(it.id) }; ui.data.notifications.clear() }
                } else {
                    clearUntil = ui.nowS() + 3.0
                    ui.flash("Hold + again to clear all")
                }
                true
            } else false
        }
        leg = mapOf("plus" to "Armed reminders", "plusH" to "Clear all")
    }
}

class ArmedReminders(ui: Ui) : ListView(
    ui, "reminders",
    hint = "X cancel · B back",
    items = {
        ui.data.reminders.armed.map { r ->
            Row(r.text, sub = reminderCondition(ui, r), right = "Exp " + reminderExpiry(ui, r).substringBefore(','),
                actLabel = "Cancel reminder", act = {
                    ui.perform { ui.data.reminders.cancel(r.id).let { if (it.ok) it.copy(message = "Reminder cancelled") else it } }
                })
        }.ifEmpty { listOf(Row("No armed reminders")) }
    },
    chrome = { t -> frame(ui, "Armed reminders", "bell", t) },
) {
    override fun draw(g: Gfx, t: Double) {
        hint = if (ui.data.reminders.armed.isEmpty()) "B back" else "X cancel · B back"
        super.draw(g, t)
    }

    init {
        refreshHook = { ui.data.reminders.refresh() }
        textTitle = { "Armed reminders" }
        textHint = { if (ui.data.reminders.armed.isEmpty()) "B back" else "X cancel B back" }
        animateChrome = false
    }

    override fun drawText(tg: TextGrid) {
        val reminders = ui.data.reminders.armed
        cur = cur.coerceIn(0, reminders.lastIndex.coerceAtLeast(0))
        TextChrome.header(tg, "Armed reminders", "${reminders.size}")
        val r = reminders.getOrNull(cur)
        if (r == null) tg.put(0, 2, ">No armed reminders")
        else {
            val body = gridLines(">" + r.text, tg.cols).take(2) +
                gridLines(reminderCondition(ui, r), tg.cols).take(1) +
                gridLines("Expires " + reminderExpiry(ui, r), tg.cols).take(2)
            body.take(tg.rows - 2).forEachIndexed { i, line -> tg.put(0, i + 1, line) }
        }
        TextChrome.footer(tg, cur, reminders.size.coerceAtLeast(1), textHint())
    }
}

private fun reminderCondition(ui: Ui, r: Reminder): String {
    val name = if (r.condition.type == "person")
        (ui.data.people.snapshot.recent + ui.data.people.snapshot.heard).find { it.id == r.condition.value }?.name
            ?: r.condition.value else r.condition.value
    return "${r.condition.type}: $name"
}

private fun reminderExpiry(ui: Ui, r: Reminder): String = if (r.expiresAt.isBlank()) "—" else try {
    java.time.Instant.parse(r.expiresAt).atZone(ui.zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US))
} catch (_: Exception) { r.expiresAt }

private suspend fun dismissNotice(ui: Ui, id: String): ActionResult = dismissNotificationRow(ui, id)
