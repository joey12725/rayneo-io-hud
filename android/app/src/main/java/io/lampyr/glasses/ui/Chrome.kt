package io.lampyr.glasses.ui

import io.lampyr.glasses.render.*
import kotlin.math.*

const val RAIL = 104
const val CX = RAIL + 10
const val CW = W - CX - 12
const val TOP = 42
const val BOTTOM = H - 8
const val X0 = 12
const val XW = W - X0 * 2

/** Rail captions use whole words; unknown oversized live labels are omitted. */
fun railCaption(text: String): String = text.split(Regex("\\s+")).mapNotNull { word ->
    val short = when (word.lowercase()) {
        "audiobooks" -> "Books"
        "auto-scroll" -> "Auto scroll"
        "recording" -> "REC"
        "temperature" -> "Temp"
        "readiness" -> "Ready"
        "listening" -> "Listen"
        else -> word
    }
    short.takeIf { it.split(' ').all { part -> tw(part, "s") <= RAIL - 4 } }
}.joinToString(" ")

fun Gfx.frame(
    ui: Ui,
    title: String,
    ic: String,
    t: Double,
    right: String? = null,
    railText: List<String> = emptyList(),
    railIcon: Int = 72,
    railY: Int = 18,
) {
    icon(ic, floor((RAIL - railIcon) / 2.0 + .5), railY, railIcon, t)
    var y = railY + railIcon + 12
    var n = 0
    for (text in railText.filter { it.isNotEmpty() }) for (line in layout(railCaption(text), RAIL - 4, "s")) {
        if (n++ >= 4) break
        drawLine(line, floor((RAIL - lineWidth(line, "s")) / 2.0 + .5), y, "s", tone = SECONDARY)
        y += lh("s")
    }
    val shown = title
    val rt = ui.frameStatus(right, maxWidth = CW - minOf(tw(shown, "s"), 96) - 20)
    tx(fit(shown, CW - tw(rt, "s") - 20), CX, 8)
    txR(rt, W - 12, 8, "s", tone = SECONDARY)
    p.fr(CX, 32, CW, 2, tone = TERTIARY)
}

fun Gfx.frameW(ui: Ui, title: String, right: String? = null, rideBadge: Boolean = true) {
    val shown = title
    val rt = ui.frameStatus(right, rideBadge, XW - minOf(tw(shown, "s"), 96) - 16)
    tx(fit(shown, XW - tw(rt, "s") - 16), X0, 8)
    txR(rt, W - 12, 8, "s", tone = SECONDARY)
    p.fr(X0, 32, XW, 2, tone = TERTIARY)
}

fun Gfx.bottomRow(
    left: String = "",
    right: String = "",
    page: Int = 0,
    n: Int = 1,
    leftIcon: String? = null,
) {
    val y = BOTTOM - 16
    var x = X0
    if (leftIcon != null) {
        icon(leftIcon, x, y - 1, 18)
        x += 22
    }
    if (left.isNotEmpty()) tx(left, x, y, "s", tone = SECONDARY)
    if (right.isNotEmpty()) txR(right, W - 12, y, "s", tone = SECONDARY)
    if (n > 1) pageFooter(page, n, W / 2, y)
}

fun Gfx.hintRow(s: String, safety: Boolean = false) {
    p.fr(X0, BOTTOM - 22, XW, 1, tone = TERTIARY)
    hintText(fit(s, XW, "s"), X0, BOTTOM - 16, safety = safety)
}

fun fmtS(seconds: Number): String {
    val s = max(0, floor(seconds.toDouble()).toInt())
    val h = s / 3600
    val m = s % 3600 / 60
    val x = s % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2,'0')}m"
    else "$m:${x.toString().padStart(2,'0')}"
}

fun age(seconds: Long): String =
    when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds/60}m"
        seconds < 86400 -> "${seconds/3600}h"
        seconds < 86400 * 10 -> "${seconds/86400}d"
        else -> "${seconds/(86400*7)}w"
    }

fun number(v: Double): String = if (v == floor(v)) v.toInt().toString() else v.toString()

/** Hint words support the screen; the physical keys remain easy to find. */
fun Gfx.hintText(s: String, x: Number, y: Number, sz: String = "s", safety: Boolean = false) {
    if (safety) { tx(s, x, y, sz); return }
    tx(s, x, y, sz, tone = SECONDARY)
    Regex("\\b(?:L|L|R2|Y|X|B|A)\\b|[▲▼◀▶+]").findAll(s).forEach { key ->
        tx(key.value, x.toDouble() + tw(s.substring(0, key.range.first), sz), y, sz)
    }
}

fun Gfx.listLabel(s: String, x: Number, y: Number, sz: String, col: Int, tone: Int) {
    tx(s, x, y, sz, col, tone)
    if (col == GREEN && s.startsWith("[ ]")) tx("[ ]", x, y, sz, col, TERTIARY)
}

fun Gfx.hintTextR(s: String, xr: Number, y: Number, sz: String = "s") =
    hintText(s, xr.toDouble() - tw(s, sz), y, sz)

@Suppress("UNUSED_PARAMETER")
fun Ui.frameStatus(context: String? = null, rideBadge: Boolean = true, maxWidth: Int? = null): String {
    val call = if (data.calls.snapshot.ongoing != null) "CALL" else null
    val clock = if (context == null) clockString() else null
    return listOfNotNull(call, context, clock).joinToString(" ")
}
