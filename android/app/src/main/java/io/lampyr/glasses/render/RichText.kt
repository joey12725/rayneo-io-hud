package io.lampyr.glasses.render

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

data class Token(
    val w: String? = null,
    val e: String? = null,
    val gap: Boolean = false,
    val nl: Boolean = false,
)

typealias TextLine = List<Token>

fun graphemes(s: String): List<String> {
    val bi = BreakIterator.getCharacterInstance(Locale.ROOT)
    bi.setText(s)
    val out = mutableListOf<String>()
    var start = bi.first()
    var end = bi.next()
    while (end != BreakIterator.DONE) {
        val c = s.substring(start, end)
        // BreakIterator on older Android/JVM versions separates VS16 and ZWJ chains.
        val cp = c.codePointAt(0)
        if (
            out.isNotEmpty() &&
                (cp == 0xFE0F ||
                    cp == 0x200D ||
                    cp in 0x1F3FB..0x1F3FF ||
                    out.last().endsWith("\u200d"))
        )
            out[out.lastIndex] += c
        else out += c
        start = end
        end = bi.next()
    }
    return out
}

fun isPict(c: String) =
    c.codePoints().anyMatch {
        it in 0xE000..0xF8FF ||
            it in 0x1F000..0x1FAFF ||
            it in 0x2600..0x27BF ||
            it == 0x00A9 ||
            it == 0x00AE ||
            it == 0x203C ||
            it == 0x2049 ||
            it == 0x2122 ||
            it == 0x2139 ||
            it in 0x2194..0x21FF ||
            it in 0x2300..0x23FF ||
            it in 0x2B00..0x2BFF
    }

fun es(sz: String = "m") = floor(lh(sz) * 1.05 + .5).toInt()

fun parse2(text: String): List<Token> {
    val out = mutableListOf<Token>()
    var cur = ""
    var sp = false
    fun flush() {
        if (cur.isNotEmpty()) {
            out += Token(w = cur, gap = sp)
            cur = ""
            sp = false
        }
    }
    for (c in graphemes(text)) {
        if (c == "\n") {
            flush()
            sp = false
            out += Token(nl = true)
            continue
        }
        if (c.all { it.isWhitespace() }) {
            flush()
            sp = true
            continue
        }
        if (isPict(c)) {
            flush()
            out += Token(e = emoKey(c) ?: "?", gap = sp)
            sp = false
            continue
        }
        cur += c
    }
    flush()
    return out
}

fun layout(text: String, maxW: Number, sz: String = "m"): List<TextLine> {
    val lines = mutableListOf<TextLine>()
    var line = mutableListOf<Token>()
    var w = 0
    val sp = tw(" ", sz)
    val ew = es(sz)
    fun push() {
        lines += line
        line = mutableListOf()
        w = 0
    }
    for (original in parse2(text)) {
        var token = original
        if (token.nl) {
            push()
            continue
        }
        val width = if (token.e != null) ew else tw(token.w.orEmpty(), sz)
        val add = (if (line.isNotEmpty() && token.gap) sp else 0) + width
        if (line.isNotEmpty() && w + add > maxW.toDouble()) push()
        if (token.e == null && width > maxW.toDouble()) {
            var chunk = ""
            token.w.orEmpty().codePoints().toArray().forEach { cp ->
                val ch = String(Character.toChars(cp))
                if (tw(chunk + ch, sz) > maxW.toDouble() - w) {
                    line += Token(w = chunk, gap = line.isNotEmpty() && token.gap)
                    push()
                    chunk = ""
                }
                chunk += ch
            }
            token = token.copy(w = chunk)
        }
        line += token.copy(gap = line.isNotEmpty() && token.gap)
        w +=
            (if (line.size > 1 && token.gap) sp else 0) +
                (if (token.e != null) ew else tw(token.w.orEmpty(), sz))
    }
    lines += line
    while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
    return lines
}

fun lineWidth(line: TextLine, sz: String = "m") =
    line.sumOf {
        (if (it.e != null) es(sz) else tw(it.w.orEmpty(), sz)) + (if (it.gap) tw(" ", sz) else 0)
    }

fun Gfx.drawLine(line: TextLine, x: Number, y: Number, sz: String = "m", col: Int = GREEN, tone: Int = this.tone) = withTone(tone) {
    var xx = x.toDouble()
    val ew = es(sz)
    for (token in line) {
        if (token.gap) xx += tw(" ", sz)
        if (token.e != null) {
            if (col == BLACK) p.fr(xx - 2, y, ew + 4, lh(sz), BLACK)
            emo(token.e, xx, y.toDouble() + floor((lh(sz) - ew) / 2.0 + .5), ew)
            xx += ew
        } else {
            tx(token.w.orEmpty(), xx, y, sz, col)
            xx += tw(token.w.orEmpty(), sz)
        }
    }
}

fun pages(text: String, maxW: Number, rows: Int, sz: String = "m"): List<List<TextLine>> =
    layout(text, maxW, sz).chunked(max(1, rows))

fun Gfx.pageFooter(i: Int, n: Int, xc: Number, y: Number, sz: String = "s") {
    if (n <= 1) return
    txC((if (i > 0) "< " else "  ") + "${i+1}/$n" + (if (i < n - 1) " >" else "  "), xc, y, sz, tone = SECONDARY)
}

fun clampLines(text: String, w: Number, n: Int, sz: String = "m"): List<TextLine> {
    val ls = layout(text, w, sz)
    if (ls.size <= n) return ls
    val out = ls.take(n).toMutableList()
    val last = out[n - 1].toMutableList()
    while (last.isNotEmpty() && lineWidth(last, sz) + tw(" …", sz) > w.toDouble()) last.removeAt(
        last.lastIndex
    )
    if (last.lastOrNull()?.w == "·") last.removeAt(last.lastIndex)
    else last += Token(w = "…", gap = true)
    out[n - 1] = last
    return out
}

fun Gfx.marq(
    text: String,
    x: Number,
    y: Number,
    maxW: Number,
    sz: String = "m",
    col: Int = GREEN,
    t: Double = 0.0,
) {
    val w = tw(text, sz)
    if (w <= maxW.toDouble()) {
        tx(text, x, y, sz, col)
        return
    }
    save()
    clipRect(x, y.toDouble() - 2, maxW, lh(sz) + 4)
    val cycle = w + 70
    val offset = max(0.0, ((t * 40) % (cycle + 50)) - 50)
    tx(text, x.toDouble() - offset, y, sz, col)
    tx(text, x.toDouble() - offset + cycle, y, sz, col)
    restore()
}

val ALIAS =
    mapOf(
        "youtu" to "YouTube",
        "youtube" to "YouTube",
        "x" to "X",
        "twitter" to "X",
        "instagram" to "Instagram",
        "google" to "Google",
        "nytimes" to "nytimes",
        "reddit" to "Reddit",
    )

fun summarizeLinks(text: String): String {
    val url = Regex("https?://\\S+")
    val matches = url.findAll(text).toList()
    val reel = Regex("instagram\\.com/reels?/", RegexOption.IGNORE_CASE)
    if (
        matches.size == 1 &&
            reel.containsMatchIn(matches[0].value) &&
            text.replace(matches[0].value, "").trim().isEmpty()
    )
        return "\uE002 sent an Instagram reel"
    return url.replace(text) { m ->
        val rest = m.value.substringAfter("://")
        if (reel.containsMatchIn(rest)) "\uE002 Instagram reel"
        else {
            val host = rest.split(Regex("[/ ?#]"))[0].removePrefix("www.")
            val parts = host.split('.')
            val name = if (parts.size >= 2) parts[parts.size - 2] else parts[0]
            "\uE001 link to " + (ALIAS[name] ?: name)
        }
    }
}

/** Keep list previews inside the space remaining after icon/tag/right metadata. */
fun fitLine(line: TextLine, width: Number, sz: String = "m"): TextLine {
    if (lineWidth(line, sz) <= width.toDouble()) return line
    return clampLines(line.joinToString("") { (if (it.gap) " " else "") + (it.e ?: it.w.orEmpty()) }, width, 1, sz).first()
}
