package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Notification
import io.lampyr.glasses.ui.*
import kotlinx.serialization.json.*

/** Notifications app ops: the same list, open (Y) and dismiss (X) the glasses show. Dismissing never touches the source app. */
object NotificationOps : OpProvider {
    private fun AgentCall.find(): Notification {
        val wanted = str("id")
        val rows = ui.notificationRows()
        rows.firstOrNull { it.id == wanted }?.let { return it }
        val matches = rows.filter { it.title.contains(wanted, ignoreCase = true) }
        return when (matches.size) {
            1 -> matches.single()
            0 -> fail(Codes.FAILED, "No notification \"$wanted\"; use notifications_list")
            else -> fail(Codes.AMBIGUOUS, "Several notifications match \"$wanted\"",
                JsonArray(matches.take(8).map { buildJsonObject { put("id", it.id); put("title", it.title) } }))
        }
    }

    override fun ops() = listOf(
        OpSpec("notifications.list", "List glasses notifications",
            "The glasses Notifications list, most important first: id, app, title, text, importance, route (stream or popup), age and whether it opens an app.",
            params = listOf(Param.integer("limit", "How many (default 10)", min = 1, max = 25)),
            risk = Risk.READ,
        ) {
            val now = ui.nowMs()
            ok(data = JsonArray(ui.notificationRows().take(int("limit", 10)).map { n ->
                buildJsonObject {
                    put("id", n.id); put("app", n.source); put("title", n.title); put("text", n.body.take(400))
                    put("importance", n.importance); put("route", n.route); put("ageMin", ((now - n.at) / 60_000).coerceAtLeast(0))
                    put("opens", n.open?.screen?.let(::JsonPrimitive) ?: JsonNull)
                }
            }))
        },
        OpSpec("notifications.open", "Open a notification",
            "Open a notification in its app on the glasses (Beeper thread, T3 thread, calendar day...), as Y does; the notification is then cleared.",
            params = listOf(Param.string("id", "Notification id (see notifications_list) or part of its title", required = true, maxLength = 300)),
            risk = Risk.NAVIGATE,
            available = { ui -> if (ui.locked) "The glasses are locked; call glasses_unlock first" else null },
        ) {
            val n = find()
            ui.openNotification(n)
            ok(if (n.open != null) "Opening ${n.title}" else "Showing ${n.title} (no app on the glasses)")
        },
        OpSpec("notifications.dismiss", "Dismiss a notification",
            "Remove one notification from the glasses list (X). The message or chat in the source app is not touched.",
            params = listOf(Param.string("id", "Notification id (see notifications_list) or part of its title", required = true, maxLength = 300)),
            risk = Risk.ACT,
        ) {
            val n = find()
            if (n.id == "call-current") fail(Codes.UNAVAILABLE, "The current call cannot be dismissed")
            done(dismissNotificationRow(ui, n.id), "Dismissed ${n.title}")
        },
        OpSpec("notifications.clear", "Clear all notifications",
            "Clear the whole glasses Notifications list, including missed calls. Source apps are not touched.",
            risk = Risk.ACT, tool = false,
        ) {
            ui.data.calls.missed.toList().forEach { ui.data.calls.dismissMissed(it.id) }
            done(ui.data.notifications.clear(), "All cleared")
        },
    )
}
