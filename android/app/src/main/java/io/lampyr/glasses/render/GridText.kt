package io.lampyr.glasses.render

import java.text.Normalizer

/** Appendix A names. Text surfaces cannot display the bitmap emoji palette. */
val EMO_NAMES = listOf(
    "tears of joy", "eyes", "loudly crying", "heart", "exclamation", "pointing up",
    "sweat smile", "smiling with hearts", "winking, tongue out", "grinning",
    "middle finger", "skull", "check (approve)", "cross (cancel)", "thumbs up",
    "thumbs down", "fire", "folded hands", "open mouth", "crying", "party popper",
    "hundred", "thinking", "heart eyes", "eye roll",
)

fun emojiName(emoji: String): String = EMO_NAMES.getOrNull(EMO_KEYS.indexOf(emoKey(emoji))) ?: "emoji"

/** ASCII spellings for letters and marks NFKD leaves alone; scripts with no ASCII equivalent still show '?'. */
private val GRID_FALLBACK: Map<Int, String> = buildMap {
    fun put(chars: String, to: String) = chars.codePoints().forEach { put(it, to) }
    put("ß", "ss"); put("æ", "ae"); put("Æ", "AE"); put("œ", "oe"); put("Œ", "OE")
    put("ø", "o"); put("Ø", "O"); put("ł", "l"); put("Ł", "L"); put("đð", "d")
    put("ĐÐ", "D"); put("þ", "th"); put("Þ", "TH"); put("ı", "i"); put("μ", "u")
    put("‰", "%"); put("¡", "!"); put("¿", "?"); put("§", "S"); put("«", "<<")
    put("‹", "<"); put("›", ">"); put("‐‑‒―", "-"); put("‚", ","); put("„", "\"")
    put("′‛", "'"); put("″", "\""); put("◦∙⋅‣", "."); put("≤", "<="); put("≥", ">=")
    put("       ", " "); put("⁄∕", "/")
    put("▸►▹", ">"); put("◂◄◃", "<"); put("▴▵△", "^"); put("▾▿▽", "v")
    put("◆◇▪▫■□○◯", "*")
}

/** Only emit cells accepted by CharGridRenderer; never silently lose unknown content. */
fun gridText(text: String): String = buildString {
    val clusters = graphemes(text)
    var index = 0
    while (index < clusters.size) {
        var cluster = clusters[index++]
        if (cluster.codePointAt(0) in 0x1f1e6..0x1f1ff && index < clusters.size &&
            clusters[index].codePointAt(0) in 0x1f1e6..0x1f1ff) cluster += clusters[index++]
        when (cluster) {
            "\uE001" -> {} // the link summary already reads "link to <site>"
            "\uE002" -> append(">") // play-box icon before "Instagram reel"
            "\u2026" -> append("...")
            "\u00bb" -> append(">>")
            "\u00b7", "\u2022", "\u25cf" -> append(".")
            "\u25b2", "\u2191" -> append("^")
            "\u25bc", "\u2193" -> append("v")
            "\u25c0", "\u2190" -> append("<")
            "\u25b6", "\u2192" -> append(">")
            "\u2018", "\u2019" -> append("'")
            "\u201c", "\u201d" -> append('"')
            "\u2013", "\u2014", "\u2212" -> append('-')
            "\u00d7" -> append('x')
            "\u00b0" -> append(" deg")
            // Money and math symbols outside ASCII spelled out, not '?' ("$" is plain ASCII and passes through).
            "\u20ac" -> append("EUR")
            "\u00a3" -> append("GBP")
            "\u00a5" -> append("JPY")
            "\u00a2" -> append('c')
            "\u00b1" -> append("+/-")
            "\u00f7" -> append('/')
            "\u2260" -> append("!=")
            "\u2264" -> append("<=")
            "\u2265" -> append(">=")
            "\u2248" -> append('~')
            "\u2713", "\u2714" -> append("[check]")
            "\u00a9" -> append("(C)")
            "\u00ae" -> append("(R)")
            "\u21d2" -> append("=>")
            "\u2194" -> append("<->")
            else -> if (isPict(cluster) || cluster.contains('\u20e3')) append("[${emojiName(cluster)}]")
            else {
                val normalized = Normalizer.normalize(cluster, Normalizer.Form.NFKD)
                    .replace(Regex("[\\u0300-\\u036f]"), "")
                normalized.codePoints().toArray().forEach { cp ->
                    val ascii = GRID_FALLBACK[cp]
                    if (ascii != null) append(ascii) else append(when (cp) {
                        10 -> '\n'
                        9, 13, 32, 0x3000 -> ' '
                        in 33..126 -> cp.toChar()
                        else -> '?'
                    })
                }
            }
        }
    }
}

/** Character-count wrapping, independent of the bitmap font, including unbroken words. */
fun gridLines(text: String, cols: Int): List<String> {
    require(cols > 0)
    val lines = mutableListOf<String>()
    gridText(text).split('\n').forEach { paragraph ->
        var line = ""
        paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { original ->
            var word = original
            if (line.isNotEmpty() && line.length + 1 + word.length > cols) {
                lines += line
                line = ""
            }
            while (word.length > cols) {
                lines += word.take(cols)
                word = word.drop(cols)
            }
            if (word.isNotEmpty()) line += (if (line.isEmpty()) "" else " ") + word
        }
        lines += line
    }
    return lines.ifEmpty { listOf("") }
}

fun gridPages(text: String, cols: Int, bodyRows: Int): List<List<String>> =
    gridLines(text, cols).chunked(bodyRows.coerceAtLeast(1))
