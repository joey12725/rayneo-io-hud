package io.lampyr.glasses.ui

import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.launch

// Notification routing on the glasses (requirements 1.2, 3.1, 3.2, 3.8): pop-ups for "popup" routes, and Y on a
// notification (in the list or on a pop-up) opens the owning app at its target instead of making it vanish.

/** Pop-up cards for notifications use this app id; the target is the notification id (see [registerNotificationTargets]). */
const val NOTIFICATION_POPUP_APP = "notification"

/** Why a "popup" notification stays in the list only, or null when it may pop up now. */
fun Ui.popupBlockedBy(n: Notification): String? {
    val p = powerStore.prefs
    return when {
        !p.popups -> "popups off"
        data.calls.snapshot.calls.isNotEmpty() -> "phone call"
        data.joy.call.active -> "joy call"
        keepAwake -> "busy screen" // live dictation, teleprompter, a running toy: never cover them
        stack.isNotEmpty() && targetOnTop(n) -> "already open"
        else -> null
    }
}

private fun Ui.targetOnTop(n: Notification): Boolean {
    val open = n.open ?: return false
    val target = open.args["target"] ?: open.args["chatId"]
    return when (open.screen) {
        "beeper", "beeper-thread" -> target != null && top.id == "thread-$target"
        "t3", "t3thread", "t3-thread" -> {
            val key = target ?: "${open.args["machine"]}:${open.args["id"]}"
            (top as? T3Thread)?.threadKey?.let { "${it.machine}:${it.id}" } == key
        }
        "joy" -> top.kind == "joy"
        else -> false
    }
}

/** A "popup" notification: a card that lights a dark lens for power.popupMs, from any screen. */
fun Ui.notificationPopup(n: Notification) {
    val blocked = popupBlockedBy(n)
    io.lampyr.glasses.debug.Trace.log("Notify", "popup id=${n.id.take(40)} ${if (blocked == null) "shown" else "held reason=$blocked"}")
    if (blocked != null) return
    popup(PopupCard(n.title, n.body, n.icon, NOTIFICATION_POPUP_APP, n.id, powerStore.prefs.popupMs))
}

/** X on a notification pop-up: hide the card and dismiss that one notification (never touches the source app). */
fun Ui.dismissPopupNotification() {
    val card = popupCard
    dismissPopup()
    if (card?.app == NOTIFICATION_POPUP_APP && card.target != null) {
        val id = card.target
        perform("Dismissed") { dismissNotificationRow(this, id) }
    }
}

internal suspend fun dismissNotificationRow(ui: Ui, id: String): ActionResult = when {
    id == "call-current" -> ActionResult()
    id.startsWith("call-missed:") -> { ui.data.calls.dismissMissed(id); ActionResult() }
    else -> ui.data.notifications.dismiss(id)
}

/** Every row the Notifications list shows (current call, missed calls, routed notifications). */
fun Ui.notificationRows(): List<Notification> = callNotifications(this)

/**
 * Y on a notification: close the Notifications view, open the owning app at the target (Beeper thread resolved
 * through the gateway, T3 thread, calendar day, phone recents, clock...), then drop the row. When the open fails
 * the row stays; a row with no app on the glasses opens a reader with its full text and stays too.
 */
fun Ui.openNotification(n: Notification) {
    scope.launch {
        val target = try { data.notifications.resolveOpen(n) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { n.open }
        if (target == null) {
            push(ReaderView(this@openNotification, "notif-read", { listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString("\n\n") }, n.source.substringAfterLast('.').replaceFirstChar { it.uppercase() }, n.icon))
            return@launch
        }
        closeNotificationViews()
        val opened = openNotificationTarget(target)
        io.lampyr.glasses.debug.Trace.log("Notify", "open id=${n.id.take(40)} app=${target.screen} ok=$opened")
        if (!opened) { flash("Can't open ${target.screen}"); return@launch }
        // A missed call stays listed: today the phone recents ARE the missed-call list.
        if (n.id != "call-current" && !n.id.startsWith("call-missed:")) dismissNotificationRow(this@openNotification, n.id)
        requestRender()
    }
}

private fun Ui.closeNotificationViews() {
    while (stack.size > 1 && stack.any { it.id == "notif" || it.id == "reminders" || it.id == "notif-read" }) {
        val i = stack.indexOfLast { it.id == "notif" || it.id == "reminders" || it.id == "notif-read" }
        while (stack.lastIndex >= i && stack.size > 1) pop()
    }
}

/** Opens a stored target: contract targets ({app, target}) through [Ui.openTarget], older screen targets through [Ui.open]. */
fun Ui.openNotificationTarget(t: OpenTarget): Boolean {
    if (t.args.isEmpty() || t.args.keys == setOf("target")) {
        if (openTarget(t.screen, t.args["target"])) return true
    }
    return when (t.screen) {
        "call-missed" -> openTarget("calls", "recents")
        "beeper" -> openTarget("beeper", null) // unresolved chat: the Beeper list
        "t3thread", "t3-thread", "beeper-thread", "home", "calls", "docs", "memo", "joy", "calendar" -> { open(t); true }
        else -> false
    }
}

/**
 * Openers for notification targets that no app registered (registered with putIfAbsent, so an app's own opener wins):
 * "notification" (a pop-up card's notification id), "t3" ("machine:threadId"), "calendar" (ISO date), "calls" (recents).
 */
fun registerNotificationTargets(ui: Ui): () -> Unit {
    // The last few popped notifications: Y on a card still opens its target after the phone withdrew the row.
    val popped = object : LinkedHashMap<String, Notification>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification>?) = size > 8
    }
    ui.targetOpeners.putIfAbsent(NOTIFICATION_POPUP_APP) { id ->
        val n = ui.notificationRows().firstOrNull { it.id == id } ?: popped[id]
        if (n != null) ui.openNotification(n)
        n != null
    }
    ui.targetOpeners.putIfAbsent("t3") { target ->
        ui.push(T3Root(ui))
        val machine = target?.substringBefore(':', "").orEmpty()
        val id = target?.substringAfter(':', "").orEmpty()
        if (machine.isNotBlank() && id.isNotBlank()) ui.push(T3Thread(ui, ThreadKey(machine, id)))
        true
    }
    ui.targetOpeners.putIfAbsent("calendar") { date ->
        val screen = CalendarScreen(ui)
        ui.push(screen)
        val day = date?.let { d -> ui.data.calendar.agenda.firstOrNull { it.date == d }?.day }
        if (day != null) screen.rows().indexOfFirst { it.sep && it.label == day }.takeIf { it >= 0 }?.let { screen.cur = (it + 1).coerceAtMost(screen.rows().lastIndex) }
        true
    }
    ui.targetOpeners.putIfAbsent("calls") { _ ->
        val s = ui.data.calls.snapshot
        if (s.ringing != null || s.ongoing != null) ui.openCalls() else ui.push(CallsHistory(ui))
        true
    }
    // Pop-ups arrive from the data store; the card itself is a Ui overlay (it never changes the stack).
    return ui.data.notifications.listenPopups { n -> popped[n.id] = n; ui.notificationPopup(n) }
}
