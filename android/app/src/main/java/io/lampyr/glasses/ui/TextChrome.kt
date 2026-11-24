package io.lampyr.glasses.ui

import io.lampyr.glasses.render.*
import kotlin.math.roundToInt

sealed interface Display {
    data object Bitmap : Display
    data class Text(val cols: Int = 26, val rows: Int = 7) : Display {
        init { require(cols >= 12 && rows >= 3) }
    }
}

/** ASCII fallbacks deliberately match the hardware's full-width character repertoire. */
object TextChrome {
    fun fit(text: String, width: Int): String {
        val s = gridText(text).replace('\n', ' ')
        return if (s.length <= width) s else if (width <= 3) s.take(width.coerceAtLeast(0))
        else {
            val head = s.take(width - 3).trimEnd()
            val boundary = head.lastIndexOf(' ')
            // Back up to a word boundary only when that costs a few characters; otherwise a long
            // word would throw away most of the row ("Deploy..." instead of "Deploy event-scra...").
            val cut = if (s.getOrNull(width - 3) != ' ' && boundary > 0 && head.length - boundary <= 2) head.take(boundary) else head
            cut.trimEnd(' ', '.', ',', ':', ';', '-', '/') + "..."
        }
    }

    fun header(tg: TextGrid, title: String, context: String = "") {
        val label = fit(title, tg.cols)
        val available = (tg.cols - label.length - 1).coerceAtLeast(0)
        val badge = gridText(context).let { value ->
            if (value.length <= available) value else when (value.uppercase()) {
                "INPUT", "REQUEST" -> "ASK"
                "APPROVAL" -> "OK?"
                "WORKING", "WAITING" -> "RUN"
                "FAILED" -> "FAIL"
                "LIMITED" -> "LIMIT"
                else -> value.replace(" PM", "p").replace(" AM", "a")
            }.takeIf { it.length <= available }.orEmpty()
        }
        tg.put(0, 0, label)
        if (badge.isNotEmpty()) tg.putR(0, badge)
    }

    fun pair(tg: TextGrid, row: Int, left: String, right: String = "", prefix: String = "", cols: Int = tg.cols) {
        val badge = fit(right, (cols / 2).coerceAtLeast(1))
        val width = cols - prefix.length - if (badge.isEmpty()) 0 else badge.length + 1
        tg.put(0, row, prefix + fit(left, width))
        if (badge.isNotEmpty()) tg.put(cols - badge.length, row, badge)
    }

    fun footer(tg: TextGrid, page: Int = 0, count: Int = 1, hint: String = "", state: String = "") {
        val badge = if (count > 1) "${page + 1}/$count" else ""
        val width = tg.cols - if (badge.isEmpty()) 0 else badge.length + 1
        // Keep complete key/verb pairs when a smaller grid or page badge needs space.
        val pairs = gridText(hint).split(' ').filter { it.isNotBlank() }.chunked(2)
        var label = state
        for (pair in pairs) {
            val next = (label + " " + pair.joinToString(" ")).trim()
            if (next.length > width) break
            label = next
        }
        tg.put(0, tg.rows - 1, label)
        if (badge.isNotEmpty()) tg.putR(tg.rows - 1, badge)
    }

    fun reader(tg: TextGrid, title: String, context: String, pages: List<List<String>>, page: Int,
               hint: String = "^v page B back", state: String = ""): Int {
        val p = page.coerceIn(0, pages.lastIndex)
        header(tg, title, context)
        pages[p].take(tg.rows - 2).forEachIndexed { i, s -> tg.put(0, i + 1, s) }
        footer(tg, p, pages.size, hint, state)
        return p
    }

    fun section(tg: TextGrid, row: Int, label: String) = tg.put(0, row, fit("-$label-", tg.cols))

    /** One row per item; the focused item's subtitle gets a dedicated, consistent detail row. */
    fun list(tg: TextGrid, rows: List<Row>, focus: Int, start: Int = 1, end: Int = tg.rows - 1, scrollHint: Boolean = true, wrapFocus: Boolean = false): Int {
        if (rows.isEmpty()) { tg.put(0, start, "Nothing here"); return 0 }
        val cur = focus.coerceIn(0, rows.lastIndex)
        val selected = rows[cur]
        val sub = selected.sub?.takeIf { it.isNotBlank() }
            ?: selected.subLines?.let(::lineText)?.takeIf { it.isNotBlank() }
        val scrolling = scrollHint && rows.size + (if (sub != null) 1 else 0) + (if (wrapFocus) 1 else 0) > end - start
        val cols = tg.cols - if (scrolling) 2 else 0 // keep a blank column before the scroll arrow
        val focusLines = if (wrapFocus && selected.tag.isNullOrEmpty())
            gridLines(selected.label, cols - 1 - if (selected.right.isEmpty()) 0 else selected.right.length + 1).take(minOf(2, (end - start).coerceAtLeast(1))) else emptyList()
        val extra = (focusLines.size - 1).coerceAtLeast(0)
        val capacity = (end - start - extra - if (sub != null && end - start > extra + 1) 1 else 0).coerceAtLeast(1)
        var top = (cur - capacity + 1).coerceAtLeast(0)
        if (top > 0 && rows[top - 1].sep && cur - top + 2 <= capacity) top--
        var y = start
        for (i in top until minOf(rows.size, top + capacity)) {
            val r = rows[i]
            if (r.sep) {
                if (y == end - 1 && i < rows.lastIndex) break
                section(tg, y++, r.label)
            }
            else {
                val label = r.lines?.let(::lineText) ?: r.label
                if (i == cur && focusLines.isNotEmpty()) focusLines.forEachIndexed { j, line ->
                    if (j == 0) pair(tg, y++, line, selected.right, ">", cols)
                    else tg.put(0, y++, " " + line)
                } else pair(tg, y++, label, r.tag?.takeIf { it.isNotEmpty() } ?: r.right,
                    if (i == cur) ">" else " ", cols)
                if (i == cur && sub != null && y < end) tg.put(0, y++, fit(" $sub", cols))
            }
        }
        if (scrollHint) {
            if (top > 0) tg.putR(start, "^")
            if (top + capacity < rows.size) tg.putR((end - 1).coerceAtMost(tg.rows - 2), "v")
        }
        return top
    }

    fun lineText(line: TextLine): String = line.joinToString("") {
        (if (it.gap) " " else "") + (it.w ?: it.e?.let { e -> if (e == "?") "[emoji]" else gridText(e) }.orEmpty())
    }

    fun bar(fraction: Double, width: Int = 10): String {
        val filled = (fraction.coerceIn(0.0, 1.0) * width).roundToInt()
        return "[" + "#".repeat(filled) + ".".repeat(width - filled) + "]"
    }

    fun toast(tg: TextGrid, text: String, reserved: Int = 0) {
        // Recovery instructions must survive the stock display's narrow columns.
        val lines = gridLines(">> $text", tg.cols).take(tg.rows - reserved)
        lines.forEachIndexed { i, line ->
            val row = tg.rows - reserved - lines.size + i
            tg.put(0, row, " ".repeat(tg.cols))
            tg.put(0, row, line)
        }
    }

    fun media(tg: TextGrid, reserved: Int = 0) = toast(tg, "MEDIA ^v vol <> skip", reserved)

    fun state(tg: TextGrid, label: String, hint: String = "", elapsed: String = "") {
        tg.center(2.coerceAtMost(tg.rows - 2), fit(label, tg.cols))
        if (elapsed.isNotEmpty()) tg.center(3.coerceAtMost(tg.rows - 2), elapsed)
        footer(tg, hint = hint)
    }
}
