package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.DEFAULT_APP_ORDER
import io.lampyr.glasses.data.PREVIOUS_DEFAULT_APP_ORDERS
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*

data class AppEntry(val id: String, private val name: String, val icon: String) {
    /** The assistant app (id "joy") shows the configured assistant name. */
    val label: String get() = if (id == "joy") io.lampyr.glasses.settings.Names.assistant else name
}

val APP_ENTRIES =
    listOf(
        AppEntry("joy", "Hermes", "joy"),
        AppEntry("beeper", "Beeper", "beeper"),
        AppEntry("media", "Media", "media"),
        AppEntry("t3", "T3 Code", "t3"),
        AppEntry("calendar", "Calendar", "calendar"),
        AppEntry("notifications", "Notifs", "bell"),
        AppEntry("people", "People", "people"),
        AppEntry("report", "Report", "news"),
        AppEntry("health", "Health", "health"),
        AppEntry("ha", "Home", "home"),
        AppEntry("docs", "Docs", "docs"),
        AppEntry("teleprompter", "Teleprompter", "prompter"),
        AppEntry("memo", "Voice memo", "mic"),
        AppEntry("status", "Status", "status"),
        AppEntry("settings", "Settings", "settings"),
        AppEntry("phone", "Phone", "phone"),
        AppEntry("clock", "Clock", "clock"),
    )

fun appEntries(ui: Ui): List<AppEntry> =
    mergeAppOrder(ui.data.settings.snapshot.appOrder).mapNotNull { id -> APP_ENTRIES.find { it.id == id } }.filter { integrationOn(it.id) }

/** An app whose integrations are all switched off in the companion config (phone Settings > Integrations) is hidden. */
fun integrationOn(appId: String): Boolean {
    val config = io.lampyr.glasses.settings.CompanionConfig.current
    return io.lampyr.glasses.settings.CompanionConfig.APP_INTEGRATIONS[appId]?.any(config::integrationOn) ?: true
}

/**
 * The launcher order for a saved [saved] order (requirement 16.2). A saved order that is exactly an earlier default
 * was never customised, so it follows the current default. A custom order is kept as saved; apps that are newer than
 * every earlier default (Phone, Clock, later apps) and missing from it are inserted after their nearest
 * preceding app in the default order (before the following one when none precedes). An app that was in the earlier
 * defaults but is missing stays out. Ids without an AppEntry (placeholders, removed apps) never take part.
 */
fun mergeAppOrder(saved: List<String>, entries: Collection<String> = APP_ENTRIES.map { it.id }): List<String> {
    val known = entries.toSet()
    // distinct(): an app appended to DEFAULT_APP_ORDER by its own branch keeps its first (page-1) position here.
    val defaults = (DEFAULT_APP_ORDER.filter { it in known } + entries.filter { it !in DEFAULT_APP_ORDER }).distinct()
    val order = saved.distinct().filter { it in known }.toMutableList()
    if (order.isEmpty() || PREVIOUS_DEFAULT_APP_ORDERS.any { old -> old.filter { it in known } == order } || order == defaults) return defaults
    // Apps in every earlier default; any other app (Phone, Clock, ...) counts as new to an old saved order.
    val established = PREVIOUS_DEFAULT_APP_ORDERS.map { it.toSet() }.reduce { a, b -> a intersect b }
    defaults.forEachIndexed { index, id ->
        if (id in order || id in established) return@forEachIndexed
        val before = defaults.subList(0, index).lastOrNull { it in order }
        val after = defaults.subList(index + 1, defaults.size).firstOrNull { it in order }
        order.add(when { before != null -> order.indexOf(before) + 1; after != null -> order.indexOf(after); else -> order.size }, id)
    }
    return order
}

class Apps(ui: Ui) : Screen(ui, "apps") {
    var i = 0

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val apps = appEntries(ui)
            i = i.coerceIn(0, max(0, apps.lastIndex))
            val pg = i / 8
            val cw = 130
            val ch = 88
            val x0 = (W - 4 * cw) / 2
            val y0 = 8
            for (j in 0 until 8) {
                val k = pg * 8 + j
                val a = apps.getOrNull(k) ?: break
                val x = x0 + j % 4 * cw
                val y = y0 + j / 4 * (ch + 8)
                val on = k == i
                if (on) p.rr(x + 2, y, cw - 4, ch, 10, 3)
                val size = 48
                icon(a.icon, x + (cw - size) / 2, y + 6, size, if (on) t else 0.0)
                val name = fit(if (a.id == "teleprompter") "Prompter" else a.label, cw - 8, "s")
                if (on) {
                    val ww = tw(name, "s") + 4
                    p.rr(x + cw / 2.0 - ww / 2.0, y + ch - 30, ww, 22, 4, 2, true)
                    txC(name, x + cw / 2, y + ch - 28, "s", BLACK)
                } else txC(name, x + cw / 2, y + ch - 28, "s")
            }
            val np = ceil(apps.size / 8.0).toInt()
            repeat(np) {
                val cx = W / 2.0 + (it - (np - 1) / 2.0) * 18
                p.circ(cx, H - 14, if (it == pg) 5 else 4, if (it == pg) 1 else 2, it == pg, tone = if (it == pg) PRIMARY else TERTIARY)
            }
        }

    override fun key(k: String): Boolean {
        val apps = appEntries(ui)
        if (ui.isText && k != "sel") {
            val delta = when (k) { "right" -> 1; "left" -> -1; "down" -> 1; "up" -> -1; else -> return false }
            i = (i + delta).coerceIn(0, max(0, apps.lastIndex))
            return true
        }
        var n = i
        when (k) {
            "right" -> n++
            "left" -> n--
            "down" -> n = if (i % 8 < 4) i + 4 else (i / 8 + 1) * 8 + i % 4
            "up" -> n = if (i % 8 >= 4) i - 4 else if (i >= 8) (i / 8 - 1) * 8 + 4 + i % 4 else i
            "sel" -> {
                apps.getOrNull(i)?.let { ui.push(ui.app(it.id)) }
                return true
            }
            else -> return false
        }
        i = n.coerceIn(0, max(0, apps.lastIndex))
        return true
    }

    override fun legend() =
        mapOf(
            "up" to "Move",
            "down" to "Move",
            "left" to "Move",
            "right" to "Move",
            "sel" to "Open " + appEntries(ui).getOrNull(i)?.label,
        )

    override fun drawText(tg: TextGrid) {
        val apps = appEntries(ui)
        i = i.coerceIn(0, max(0, apps.lastIndex))
        val size = tg.rows - 2
        val page = i / size
        TextChrome.header(tg, "Apps", ui.clockString())
        TextChrome.list(tg, apps.drop(page * size).take(size).map { Row(it.label) }, i % size, scrollHint = false)
        TextChrome.footer(tg, page, (apps.size + size - 1) / size, "Y open B back")
    }
}
