package io.lampyr.glasses.render

import java.text.Normalizer
import java.util.Locale
import kotlin.math.floor

data class BitmapFont(
    val glyphs: Map<String, List<String>>,
    val scales: Map<String, Int>,
    val lineHeights: Map<String, Int>,
)

object Fonts {
    val table =
        mutableMapOf(
            "repo" to
                BitmapFont(
                    Font5.glyphs,
                    mapOf("s" to 2, "m" to 2, "l" to 3, "xl" to 7),
                    mapOf("s" to 18, "m" to 18, "l" to 27, "xl" to 56),
                )
        )
    var selected = "repo"
    val current
        get() = table.getValue(selected)
    /** display.textCase for the bitmap renderer; the Ui keeps it in step with its preferences. CAPS is the original look. */
    @Volatile var textCase = TextCase.CAPS
}

/** Bitmap text case. The 26x7 / 20x5 stock text page always shows mixed case (its firmware font has lowercase). */
enum class TextCase { CAPS, NORMAL }

/** Labels that were explicitly uppercased (dates, senders, places) follow the text-case setting too. */
fun caseText(s: String): String = if (Fonts.textCase == TextCase.CAPS) s.uppercase(Locale.US) else s

private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")
private val SINGLE_QUOTES = Regex("[‘’]")
private val DOUBLE_QUOTES = Regex("[“”]")
private val DASHES = Regex("[–—]")

/**
 * Small LRU of pure text results, keyed by the inputs plus the text case and font they depend on. Every label is
 * normalised several times per frame (tw, tx, fit); recomputing NFKD and regexes each time cost Home tens of ms.
 */
private class TextMemo<V>(private val max: Int) {
    private class Key(val text: String, val extra: Any?, val case: TextCase, val font: BitmapFont) {
        override fun equals(other: Any?) = other is Key && other.text == text && other.extra == extra && other.case == case && other.font === font
        override fun hashCode() = ((text.hashCode() * 31 + (extra?.hashCode() ?: 0)) * 31 + case.hashCode()) * 31 + System.identityHashCode(font)
    }
    private val map = object : LinkedHashMap<Key, V>(max, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, V>?) = size > max
    }
    fun get(text: String, extra: Any?, compute: () -> V): V {
        val key = Key(text, extra, Fonts.textCase, Fonts.current)
        synchronized(map) { map[key]?.let { return it } }
        return compute().also { synchronized(map) { map[key] = it } }
    }
}
private val norm5Memo = TextMemo<String>(2048)
private val fitMemo = TextMemo<String>(512)

fun norm5(s: String): String = norm5Memo.get(s, null) { norm5Uncached(s) }

private fun norm5Uncached(s: String): String =
    Normalizer.normalize(s.replace("≠", "!="), Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .replace(SINGLE_QUOTES, "'")
        .replace(DOUBLE_QUOTES, "\"")
        .replace(DASHES, "-")
        .replace("…", "...")
        .let { if (Fonts.textCase == TextCase.CAPS) it.uppercase(Locale.US) else withGlyphs(it) }
        .let(::withFallbacks)

/** Look-alikes for characters the bitmap font has no glyph for. Every value is drawn from existing glyphs. */
private val GLYPH_FALLBACK: Map<Int, String> = buildMap {
    fun put(chars: String, to: String) = chars.codePoints().forEach { put(it, to) }
    put("‣◦∙⋅", "·"); put("−‐‑‒―", "-"); put("′‛", "'"); put("″‟„", "\""); put("‚", ",")
    put("‹", "<"); put("›", ">"); put("«", "<<"); put("»", ">>")
    put("≈∼", "~"); put("≤", "<="); put("≥", ">="); put("⁄∕", "/"); put("‰", "%")
    put("✔☑✅", "✓"); put("✗✘✕✖❌❎", "×"); put("©", "(C)"); put("®", "(R)"); put("¡", "!"); put("¿", "?"); put("§", "S")
    put("⇒", "=>"); put("⇐", "<="); put("↔", "<->"); put("⇔", "<=>")
    put("⬆↗↖⇑", "↑"); put("⬇↘↙⇓", "↓"); put("⬅", "←"); put("➡", "→")
    put("▸►▹", "▶"); put("◂◄◃", "◀"); put("△▵▴", "▲"); put("▽▾▿", "▼"); put("◆◇", "●"); put("○◯", "o")
    put("★☆", "*"); put("▪▫", "■")
    put("æ", "ae"); put("Æ", "AE"); put("œ", "oe"); put("Œ", "OE"); put("ø", "o"); put("Ø", "O")
    put("ł", "l"); put("Ł", "L"); put("đð", "d"); put("ĐÐ", "D"); put("þ", "th"); put("Þ", "TH"); put("ı", "i")
    put("μ", "u"); put("Μ", "M")
}

/** Zero-width marks: variation selectors, skin tones, keycap, tags, soft hyphen, invisible spaces. */
private fun zeroWidth(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
    cp == 0x00AD || cp == 0x200B || cp == 0x200C || cp == 0x200E || cp == 0x200F || cp == 0x2060 || cp == 0x20E3 || cp == 0xFEFF

/**
 * The last step of [norm5]: every character comes out as something the font draws, never the "?" glyph. Missing
 * symbols use [GLYPH_FALLBACK], an emoji (one ZWJ sequence or flag counts once) shows as ☺, controls and other
 * spaces as a space, and anything else as an empty box □.
 */
private fun withFallbacks(s: String): String {
    val glyphs = Fonts.current.glyphs
    if (s.all { !it.isSurrogate() && glyphs.containsKey(it.toString()) }) return s
    val cps = s.codePoints().toArray()
    return buildString {
        var i = 0
        while (i < cps.size) {
            val cp = cps[i++]
            val c = String(Character.toChars(cp))
            val fallback = GLYPH_FALLBACK[cp]?.takeIf { f -> f.all { glyphs.containsKey(it.toString()) } }
            when {
                glyphs.containsKey(c) -> append(c)
                zeroWidth(cp) -> {}
                cp == 0x200D -> if (i < cps.size) i++ // the joined emoji belongs to the one already drawn
                fallback != null -> append(fallback)
                cp < 0x20 || cp in 0x7F..0x9F || Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                cp in 0x1F1E6..0x1F1FF -> { append('☺'); if (i < cps.size && cps[i] in 0x1F1E6..0x1F1FF) i++ }
                isPict(c) -> append('☺')
                else -> append('□')
            }
        }
    }
}

/** NORMAL case: keep characters the font draws; anything else (e.g. lowercase accents left after NFKD) falls back to uppercase. */
private fun withGlyphs(s: String): String {
    val glyphs = Fonts.current.glyphs
    if (s.all { it.code < 128 && (glyphs.containsKey(it.toString()) || !it.isLowerCase()) }) return s
    return buildString {
        s.codePoints().forEach { cp ->
            val c = String(Character.toChars(cp))
            append(if (glyphs.containsKey(c)) c else c.uppercase(Locale.US))
        }
    }
}

fun lh(sz: String = "m") = Fonts.current.lineHeights.getValue(sz)

fun tw(s: String, sz: String = "m") =
    norm5(s).let { it.codePointCount(0, it.length) } * 6 * Fonts.current.scales.getValue(sz)

fun Gfx.tx(text: String, x: Number, y: Number, sz: String = "m", col: Int = GREEN, tone: Int = this.tone, plainZero: Boolean = false) {
    val sc = Fonts.current.scales.getValue(sz)
    val yo = floor((lh(sz) - 7 * sc) / 2.0 + .5).toInt() - (if (sz == "xl") 0 else 1)
    var xx = x.toDouble()
    val top = y.toDouble() + yo
    withTone(tone) { fillStyle = col }
    // On whole device pixels an anti-aliased rect covers exactly its pixels, so one rect per run of lit glyph
    // pixels draws the same bytes as one rect per pixel, with a fraction of the draw calls (main-thread render cost).
    @Suppress("DEPRECATION") val wholePixels = top == floor(top) && canvas.matrix.let { m ->
        val v = FloatArray(9).also(m::getValues)
        v[0] == 1f && v[1] == 0f && v[3] == 0f && v[4] == 1f && v[6] == 0f && v[7] == 0f && v[8] == 1f && v[2] == floor(v[2]) && v[5] == floor(v[5])
    }
    norm5(text).codePoints().toArray().forEach { cp ->
        val rows =
            Fonts.current.glyphs[if (plainZero && cp == 48) "O" else String(Character.toChars(cp))]
                ?: Fonts.current.glyphs.getValue("□")
        if (wholePixels && xx == floor(xx)) {
            for (yy in 0..6) {
                val row = rows[yy]
                var gx = 0
                while (gx < 5) {
                    if (row[gx] != '1') { gx++; continue }
                    val start = gx
                    while (gx < 5 && row[gx] == '1') gx++
                    fillRect(xx + start * sc, top + yy * sc, (gx - start) * sc, sc)
                }
            }
        } else for (yy in 0..6) for (gx in 0..4) if (rows[yy][gx] == '1')
            fillRect(xx + gx * sc, top + yy * sc, sc, sc)
        xx += 6 * sc
    }
}

fun Gfx.txR(s: String, xr: Number, y: Number, sz: String = "m", col: Int = GREEN, tone: Int = this.tone) =
    tx(s, xr.toDouble() - tw(s, sz), y, sz, col, tone)

fun Gfx.txC(s: String, xc: Number, y: Number, sz: String = "m", col: Int = GREEN, tone: Int = this.tone) =
    tx(s, floor(xc.toDouble() - tw(s, sz) / 2.0 + .5), y, sz, col, tone)

fun fit(s: String, maxW: Number, sz: String = "m"): String {
    if (tw(s, sz) <= maxW.toDouble()) return s
    return fitMemo.get(s, maxW.toDouble() to sz) { fitUncached(s, maxW, sz) }
}

private fun fitUncached(s: String, maxW: Number, sz: String): String {
    val chars = s.codePoints().toArray().map { String(Character.toChars(it)) }.toMutableList()
    while (chars.size > 1 && tw(chars.joinToString("") + "…", sz) > maxW.toDouble()) chars.removeAt(
        chars.lastIndex
    )
    val prefix = chars.joinToString("").trimEnd()
    // A separator with no following field is noise, not a useful preview.
    return if (prefix.endsWith("·")) prefix.dropLast(1).trimEnd() else prefix + "…"
}
