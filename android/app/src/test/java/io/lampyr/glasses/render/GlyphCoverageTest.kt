package io.lampyr.glasses.render

import io.lampyr.glasses.glasses.CharGridRenderer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Every character the user's data can carry draws as a real glyph or a sensible look-alike, never the "?" glyph. */
class GlyphCoverageTest {
    @After fun reset() { Fonts.textCase = TextCase.CAPS; Fonts.selected = "repo" }

    private val printableAscii = (0x20..0x7E).map { it.toChar().toString() }

    /** Symbols seen in reports, messages and calendars, plus typographic punctuation and emoji. */
    private val dataSymbols = listOf(
        "€", "£", "¢", "¥", "%", "&", "@", "#", "°", "±", "×", "÷", "•", "·", "–", "—", "…", "‘", "’", "“", "”",
        "←", "→", "↑", "↓", "✓", "✔", "✗", "✅", "❌", "−", "≠", "≤", "≥", "≈", "½", "²", "™", "©", "®", "«", "»",
        " ", " ", "\t", "\n", "é", "ñ", "ü", "ß", "æ", "ø", "ł", "µ", "★", "▸", "◆", "⇒", "↔", "➡", "⬆",
        "😀", "👍🏽", "❤️", "👨‍👩‍👧", "🇺🇸", "1️⃣", "", "漢", "Ж",
    )

    private fun unmapped(input: String): List<String> {
        val out = norm5(input)
        val cps = out.codePoints().toArray().map { String(Character.toChars(it)) }
        return cps.filter { !Fonts.current.glyphs.containsKey(it) || (it == "?" && !input.contains('?') && input != "¿") }
    }

    @Test fun everyPrintableAsciiCharacterHasItsOwnGlyphInBothCases() {
        for (font in Fonts.table.keys) {
            Fonts.selected = font
            for (case in TextCase.entries) {
                Fonts.textCase = case
                for (c in printableAscii) {
                    val shown = norm5(c)
                    val expected = if (case == TextCase.CAPS) c.uppercase() else c
                    assertEquals("$font $case '$c' draws as itself", expected, shown)
                    assertTrue("$font $case '$c' has no glyph", Fonts.current.glyphs.containsKey(shown))
                }
            }
        }
    }

    @Test fun dataSymbolsAndEmojiNeverFallBackToTheQuestionMark() {
        for (font in Fonts.table.keys) {
            Fonts.selected = font
            for (case in TextCase.entries) {
                Fonts.textCase = case
                for (s in dataSymbols) assertEquals("$font $case '$s' -> '${norm5(s)}'", emptyList<String>(), unmapped(s))
                for (sz in Fonts.current.scales.keys) {
                    val text = "\$43,192 · \$219-164 € £ ✓ 👍🏽"
                    assertEquals("$sz width counts drawn cells", norm5(text).codePointCount(0, norm5(text).length) * 6 * Fonts.current.scales.getValue(sz), tw(text, sz))
                }
            }
        }
    }

    @Test fun glyphRowsAreWellFormed() {
        for ((c, rows) in Font5.glyphs) {
            assertEquals("glyph $c", 7, rows.size)
            assertTrue("glyph $c", rows.all { it.length == 5 && it.all { b -> b == '0' || b == '1' } })
        }
        assertNotEquals("\$ is not the ? glyph", Font5.glyphs["?"], Font5.glyphs["$"])
    }

    @Test fun morningReportMoneyAndFallbacksReadCorrectly() {
        assertEquals("\$43,192", norm5("\$43,192"))
        assertEquals("\$219-164", norm5("\$219–164"))
        assertEquals("1/2 != 2", norm5("½ ≠ 2"))
        assertEquals("one emoji per sequence", "☺ ☺ ☺", norm5("👨‍👩‍👧 🇺🇸 👍🏽"))
        assertEquals("□", norm5("漢"))
        Fonts.textCase = TextCase.NORMAL
        assertEquals("Paid \$12.50 ~ €11", norm5("Paid \$12.50 ≈ €11"))
    }

    @Test fun stockTextGridKeepsDollarsAndSpellsOutOtherMoney() {
        assertEquals("\$43,192 \$219-164", gridText("\$43,192 \$219–164"))
        assertEquals("EUR5 GBP3 +/-2", gridText("€5 £3 ±2"))
        val grid = TextGrid(uppercase = false).apply { put(0, 0, "\$43,192") }
        assertEquals("\$43,192", grid.lines()[0].trimEnd())
        // The firmware text page receives full-width ASCII: '$' becomes U+FF04, not the unknown-character mark.
        assertEquals("＄４３", CharGridRenderer.fullWidth("\$43"))
        for (c in printableAscii.drop(1)) assertEquals(c, gridText(c))
        // Data symbols and Latin letters reach the stock text page as ASCII, never '?' (only other scripts do).
        assertEquals(emptyList<String>(), dataSymbols.filter { it != "漢" && it != "Ж" && gridText(it).contains('?') }.map { "'$it' -> '${gridText(it)}'" })
        assertEquals("Strasse aero l (C) 5% [check]", gridText("Straße æro ł © 5‰ ✓"))
    }
}
