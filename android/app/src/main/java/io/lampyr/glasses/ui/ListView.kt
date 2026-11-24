package io.lampyr.glasses.ui

import io.lampyr.glasses.render.*
import kotlin.math.*

data class Row(
    val label: String,
    val sub: String? = null,
    val subLines: TextLine? = null,
    val lines: TextLine? = null,
    val icon: String? = null,
    val emo: String? = null,
    val tag: String? = null,
    val right: String = "",
    val sep: Boolean = false,
    val selectable: Boolean = true,
    val open: (() -> Unit)? = null,
    val act: (() -> Unit)? = null,
    val selLabel: String? = null,
    val actLabel: String? = null,
    val bitmapSubFallback: String? = null,
)

open class ListView(
    ui: Ui,
    id: String,
    val items: () -> List<Row>,
    val chrome: Gfx.(Double) -> Unit = {},
    val x0: Int = CX,
    val y0: Int = TOP,
    val w0: Int = CW - 8,
    var hint: String? = null,
    val footer: String? = null,
    val reserveBottom: Boolean = false,
    kind: String = "list",
) : Screen(ui, id, kind) {
    var cur = 0
    var top = 0
    var onKey: ((String, Row?) -> Boolean)? = null
    var act: ((Row?) -> Unit)? = null
    var actLabel: String? = null
    var leg: Map<String, String> = emptyMap()
    var target: TalkTarget? = null
    var animateChrome = true
    override val needsAnimation
        get() = animateChrome || rows().any { it.icon in ANIMATED_ICONS }

    var refreshHook: (suspend () -> io.lampyr.glasses.data.ActionResult)? = null

    override fun onOpen() {
        refreshHook?.let { ui.refresh(it) }
    }

    override val talk
        get() = target

    fun rows() = items()

    private fun rh(row: Row) =
        if (row.sep) 24 else if (row.sub != null || row.subLines != null) 50 else 34

    fun fix() {
        val r = rows()
        if (r.isEmpty()) return
        cur = cur.coerceIn(0, r.lastIndex)
        if (r[cur].sep || !r[cur].selectable) {
            val next = (cur..r.lastIndex).find { !r[it].sep && r[it].selectable }
            cur = next ?: (cur downTo 0).find { !r[it].sep && r[it].selectable } ?: cur
        }
        val avail = BOTTOM - y0 - (if (footer != null || hint != null || reserveBottom) 24 else 0)
        if (cur < top) top = cur
        fun height(a: Int, b: Int): Int {
            return if (a > b) 0 else r.subList(a, b + 1).sumOf(::rh)
        }
        while (top < cur && height(top, cur) > avail) top++
        if (top > 0 && r[top - 1].sep && height(top - 1, cur) <= avail) top--
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            chrome(t)
            fix()
            val r = rows()
            var y = y0
            val x = x0
            val w = w0
            val avail = BOTTOM - y - (if (footer != null || hint != null || reserveBottom) 24 else 0)
            var shown = 0
            for (i in top until r.size) {
                val row = r[i]
                val h = rh(row)
                if (y - y0 + h > avail) break
                shown++
                if (row.sep) {
                    tx(row.label, x, y + 3, "s", tone = SECONDARY)
                    val lx = x + tw(row.label, "s") + 8
                    p.fr(lx, y + 12, w - (lx - x), 2, tone = TERTIARY)
                    y += h
                    continue
                }
                val on = i == cur
                val col = if (on) BLACK else GREEN
                val rowTone = if (!on && !row.selectable) TERTIARY else PRIMARY
                val supportTone = if (on) PRIMARY else if (!row.selectable) TERTIARY else SECONDARY
                if (on) p.rr(x - 4, y, w + 4, h - 4, 5, 2, true)
                var ix = x + 4
                val iy = y + (h - 4 - 24) / 2.0
                if (row.icon != null || row.emo != null) {
                    if (on) p.fr(ix - 3, iy - 3, 30, 30, BLACK)
                    if (row.icon != null) icon(row.icon, ix, iy, 24, t, tone = rowTone)
                    else emo(row.emo!!, ix, iy, 24, tone = rowTone)
                    ix += 34
                }
                row.tag?.let {
                    val width = tw(it, "s") + 10
                    p.fr(ix, y + 6, width, 20, if (on) BLACK else GREEN, tone = if (on) PRIMARY else SECONDARY)
                    tx(it, ix + 5, y + 7, "s", if (on) GREEN else BLACK)
                    ix += width + 8
                }
                // Keep a useful label; omit oversized metadata instead of ellipsizing it.
                val right = row.right.takeIf { !it.contains('…') && tw(it, "s") <= (x + w - ix - 6) / 2 }.orEmpty()
                val rw = if (right.isNotEmpty()) tw(right, "s") + 10 else 0
                val sub = row.sub != null || row.subLines != null
                if (row.lines != null) drawLine(fitLine(row.lines, x + w - ix - rw - 6), ix, y + (if (sub) 2 else 6), "m", col)
                else listLabel(fit(row.label, x + w - ix - rw - 6), ix, y + (if (sub) 3 else 7), "m", col, rowTone)
                if (sub) {
                    if (row.bitmapSubFallback != null) {
                        val width = x + w - ix - 8
                        tx(fit(if (tw(row.sub.orEmpty(), "s") <= width) row.sub.orEmpty() else row.bitmapSubFallback, width, "s"), ix, y + 24, "s", col, tone = supportTone)
                    } else if (row.subLines != null) drawLine(fitLine(row.subLines, x + w - ix - 8, "s"), ix, y + 24, "s", col, tone = supportTone)
                    else tx(fit(row.sub.orEmpty(), x + w - ix - 8, "s"), ix, y + 24, "s", col, tone = supportTone)
                }
                if (right.isNotEmpty())
                    txR(right, x + w - 6, y + (if (sub) 4 else 8), "s", col, tone = if (on) PRIMARY else SECONDARY)
                y += h
            }
            if (r.size > shown) {
                p.fr(W - 11, y0, 2, avail, BLACK)
                for (k in 0 until avail step 6) p.fr(W - 11, y0 + k, 2, 2, tone = TERTIARY)
                val th = max(14.0, avail.toDouble() * shown / r.size)
                val ty = y0 + (avail - th) * (top.toDouble() / max(1, r.size - shown))
                p.fr(W - 12, ty, 4, th)
            }
            footer?.let { txR(it, W - 14, BOTTOM - 16, "s", tone = SECONDARY) }
            hint?.let {
                p.fr(x, BOTTOM - 22, w, 1, tone = TERTIARY)
                hintText(fit(it, w, "s"), x, BOTTOM - 16)
            }
            Unit
        }

    override fun key(k: String): Boolean {
        fix()
        val r = rows()
        if (k == "up" || k == "down") {
            val d = if (k == "up") -1 else 1
            var i = cur + d
            while (i in r.indices && (r[i].sep || !r[i].selectable)) i += d
            if (i in r.indices) cur = i
            return true
        }
        val row = r.getOrNull(cur)
        if (k == "sel" && row?.selectable == true && row.open != null) {
            row.open.invoke()
            return true
        }
        if (k == "act") {
            if (row?.act != null) {
                row.act.invoke()
                return true
            }
            act?.let {
                it(row)
                return true
            }
        }
        return onKey?.invoke(k, row) ?: false
    }

    override fun legend(): Map<String, String> {
        val r = rows().getOrNull(cur)
        val out =
            mutableMapOf(
                "up" to "Move up",
                "down" to "Move down",
                "sel" to (r?.selLabel ?: if (r?.open != null) "Open" else "—"),
            )
        (r?.actLabel ?: actLabel)?.let { out["act"] = it }
        out.putAll(leg)
        return out
    }

    var textTitle: () -> String = { textScreenTitle(id) }
    var textContext: () -> String = { ui.clockString() }
    var textHint: () -> String = {
        when (id) {
            "notif" -> "Y open X clear +hold all"
            "speed", "dev", "confirm", "resets", "joymenu", "msgact" -> "Y pick B back"
            "abs", "spot", "yt" -> "Y play B back"
            else -> if (rows().getOrNull(cur)?.open != null) "Y open B back" else "B back"
        }
    }
    var textItems: () -> List<Row> = { rows() }
    var textWrapFocus = false
    var textTop = 0
        private set

    override fun drawText(tg: TextGrid) {
        val r = textItems()
        if (r.isNotEmpty()) {
            cur = cur.coerceIn(0, r.lastIndex)
            if (r[cur].sep || !r[cur].selectable) cur = (cur..r.lastIndex).firstOrNull { !r[it].sep && r[it].selectable }
                ?: (cur downTo 0).firstOrNull { !r[it].sep && r[it].selectable } ?: cur
        }
        TextChrome.header(tg, textTitle(), textContext())
        TextChrome.footer(tg, hint = textHint())
        textTop = TextChrome.list(tg, r, cur, wrapFocus = textWrapFocus)
    }
}

private fun textScreenTitle(id: String): String = when (id) {
    "beeper" -> "Beeper"
    "joymenu" -> "${io.lampyr.glasses.settings.Names.assistant} menu"
    "msgact" -> "Message actions"
    "t3" -> "T3 Code"
    "t3hist" -> "Thread history"
    "t3a" -> "Approval"
    "resets" -> "Use a reset"
    "env" -> "Environments"
    "settled" -> "Settled"
    "newt" -> "New thread"
    "mmenu" -> "Media"
    "abs" -> "Audiobooks"
    "spot" -> "Spotify"
    "dev" -> "Output device"
    "speed" -> "Playback speed"
    "yt" -> "YouTube"
    "ytl" -> "YouTube videos"
    "cal" -> "Calendar"
    "notif" -> "Notifs"
    "people" -> "People"
    "ha" -> "Home Assistant"
    "confirm" -> "Run scene?"
    "docs" -> "Docs"
    "set" -> "Settings"
    "order" -> "App order"
    else -> "Choose"
}
