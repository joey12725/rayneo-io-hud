package io.lampyr.glasses.clock

/**
 * Google Clock (com.google.android.deskclock) integration through its notifications: what it shows is imported
 * into the glasses Clock app, and its own notification actions control it (pause, +1 min, stop, snooze).
 *
 * Pure functions over [Notice] so they are unit-tested with fixtures; the listener builds the notices.
 * Action titles are localized and change between Clock versions, so every pattern lives here. When no action
 * matches, the item is still listed (read-only) and the glasses say "use the phone".
 */
object ClockNotifications {
    const val PACKAGE = "com.google.android.deskclock"

    /** What the listener extracts from one StatusBarNotification. */
    data class Notice(
        val key: String,
        val channel: String,
        val category: String? = null,
        val title: String = "",
        val text: String = "",
        val whenMs: Long = 0,
        val chronometer: Boolean = false,
        val countDown: Boolean = false,
        val actions: List<String> = emptyList(),
        val groupSummary: Boolean = false,
    )

    sealed interface Item {
        data class Timer(val timer: TimerItem) : Item
        data class Firing(val ringing: Ringing) : Item
        data class Upcoming(val atMs: Long?, val state: String, val key: String, val actions: Set<String>) : Item
    }

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "pause" to Regex("^\\s*pause", RegexOption.IGNORE_CASE),
        "resume" to Regex("^\\s*(resume|start|continue)", RegexOption.IGNORE_CASE),
        "add" to Regex("(\\+\\s*1|add\\s*1|1\\s*min)", RegexOption.IGNORE_CASE),
        "snooze" to Regex("^\\s*snooze", RegexOption.IGNORE_CASE),
        "dismiss" to Regex("^\\s*(stop|dismiss|turn off|skip|done)", RegexOption.IGNORE_CASE),
        "cancel" to Regex("^\\s*(reset|cancel|delete|stop|remove)", RegexOption.IGNORE_CASE),
    )

    /** Index of the notification action for a logical [action], or null. */
    fun actionIndex(titles: List<String>, action: String): Int? {
        val pattern = PATTERNS.firstOrNull { it.first == action }?.second ?: return null
        return titles.indexOfFirst { pattern.containsMatchIn(it) }.takeIf { it >= 0 }
    }

    fun logicalActions(titles: List<String>): Set<String> = PATTERNS.map { it.first }.filter { actionIndex(titles, it) != null }.toSet()

    private val CLOCK_TEXT = Regex("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b")

    /** "4:32" / "1:04:32" in a paused timer's text, in ms. */
    fun parseRemaining(text: String): Long? = CLOCK_TEXT.find(text)?.let { m ->
        val h = m.groupValues[1].toLongOrNull() ?: 0
        (h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000
    }

    fun classify(n: Notice, wallNow: Long, elapsedNow: Long): Item? {
        if (n.groupSummary) return null
        val ch = n.channel.lowercase()
        val actions = logicalActions(n.actions)
        val words = (n.title + " " + n.text).lowercase()
        val label = n.title.takeUnless { Regex("^\\s*(timer|alarm|time's up|times up)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty().trim()
        // AOSP DeskClock also gives running timers CATEGORY_ALARM, so the category only counts on a channel that is
        // not one of Clock's known non-ringing ones (a running timer must never pop the ringing card).
        val known = listOf("timer", "stopwatch", "upcoming", "snoozed", "missed", "bedtime", "workflow").any { it in ch }
        val firing = "firing" in ch || (n.category == "alarm" && !known)
        if (firing) {
            val timer = "add" in actions || "timer" in words || "time's up" in words || "times up" in words
            return Item.Firing(Ringing(if (timer) "timer" else "alarm", "g:" + n.key, label,
                sinceWallMs = if (n.whenMs in 1..wallNow) n.whenMs else wallNow,
                sinceElapsedMs = elapsedNow - (wallNow - (if (n.whenMs in 1..wallNow) n.whenMs else wallNow)),
                source = SOURCE_PHONE, external = true, actions = actions))
        }
        if ("stopwatch" in ch || "missed" in ch || "bedtime" in ch) return null
        if ("timer" in ch) {
            val running = n.chronometer && n.countDown && n.whenMs > 0 && "resume" !in actions
            val remaining = if (running) (n.whenMs - wallNow).coerceAtLeast(0) else parseRemaining(n.text) ?: parseRemaining(n.title) ?: 0
            return Item.Timer(TimerItem("g:" + n.key, label, durationMs = remaining, running = running,
                endsAtWallMs = if (running) n.whenMs else 0, remainingMs = if (running) 0 else remaining,
                source = SOURCE_PHONE, actions = actions))
        }
        if ("upcoming" in ch || "snoozed" in ch) {
            return Item.Upcoming(n.whenMs.takeIf { it > 0 }, if ("snoozed" in ch) "snoozed" else "upcoming", n.key, actions)
        }
        return null
    }

    /** Folds the current Clock notifications into one [PhoneClockState]. */
    fun state(notices: Collection<Notice>, wallNow: Long, elapsedNow: Long, nextAlarmWallMs: Long?): PhoneClockState {
        val items = notices.mapNotNull { classify(it, wallNow, elapsedNow) }
        val upcoming = items.filterIsInstance<Item.Upcoming>().firstOrNull()
        return PhoneClockState(
            timers = items.filterIsInstance<Item.Timer>().map { it.timer },
            ringing = items.filterIsInstance<Item.Firing>().map { it.ringing },
            nextAlarmWallMs = nextAlarmWallMs ?: upcoming?.atMs,
            nextAlarmState = upcoming?.state.orEmpty(),
            nextAlarmKey = upcoming?.key,
            nextAlarmActions = upcoming?.actions.orEmpty(),
        )
    }
}
