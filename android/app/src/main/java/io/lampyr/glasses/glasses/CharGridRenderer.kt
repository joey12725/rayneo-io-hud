// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Full-width layout/prefix workaround adapted from OpenRayneo; see android/NOTICE.
package io.lampyr.glasses.glasses

import io.lampyr.glasses.render.TextGrid

class CharGridRenderer {
    var columns = 26
        private set
    var rows = 7
        private set
    private var first = true
    fun reset() { first = true }
    fun configure(font: Int, width: Int, lines: Int) {
        require(font in 1..2 && width == 100 && lines in 1..7) { "Unsupported effective caption configuration" }
        columns = if (font == 1) 26 else 20
        rows = lines
        reset()
    }
    fun content(grid: TextGrid) = content(grid.lines())
    fun content(lines: List<String>): String = (0 until rows).joinToString("\n") { row ->
        fullWidth(lines.getOrNull(row).orEmpty()).take(columns).padEnd(columns, '\u3000')
    }
    // Upstream measured this workaround at font 2. Applying it at font 1 is unverified on hardware.
    fun wireText(content: String): String = (if (first) "" else "\u3000") + content
    fun written() { first = false }

    companion object {
        /** Collapse complete emoji sequences (ZWJ, modifier, flags, keycaps) into one visible cell. */
        fun fullWidth(text: String): String {
            val points = text.codePoints().toArray()
            val out = StringBuilder()
            var i = 0
            while (i < points.size) {
                val cp = points[i++]
                val emoji = cp in 0x1f000..0x1ffff || cp in 0x2600..0x27ff || cp in setOf(0xa9, 0xae, 0x203c, 0x2049, 0x2122, 0x2139)
                val keycap = cp in 0x30..0x39 || cp == 0x23 || cp == 0x2a
                if (emoji || (keycap && points.getOrNull(i) in setOf(0xfe0f, 0x20e3))) {
                    if (cp in 0x1f1e6..0x1f1ff && points.getOrNull(i) in 0x1f1e6..0x1f1ff) i++
                    while (i < points.size) {
                        val next = points[i]
                        if (next in setOf(0xfe0e, 0xfe0f, 0x20e3) || next in 0x1f3fb..0x1f3ff || next in 0xe0020..0xe007f) i++
                        else if (next == 0x200d && i + 1 < points.size) i += 2
                        else break
                    }
                    out.append('\uff1f')
                } else out.append(when (cp) {
                    0x20, 0x3000 -> '\u3000'
                    in 0x21..0x7e -> (cp + 0xfee0).toChar()
                    in 0xff01..0xff5e -> cp.toChar()
                    // Solid block was confirmed upstream; half blocks remain experimental.
                    0x2580, 0x2584, 0x2588 -> cp.toChar()
                    else -> '\uff1f'
                })
            }
            return out.toString()
        }
    }
}

/** One pending frame, based on successful writes. No clock/sleep/socket dependency in the reducer. */
class FramePacer(private val intervalMs: Long = 125) {
    private var pending: String? = null
    private var last: String? = null
    private var lastAt: Long? = null
    fun offer(text: String) { pending = text }
    fun next(nowMs: Long): String? {
        val text = pending ?: return null
        if (text == last) { pending = null; return null }
        if (lastAt?.let { nowMs - it < intervalMs } == true) return null
        pending = null
        return text
    }
    fun written(text: String, nowMs: Long) { last = text; lastAt = nowMs }
    fun reset() { last = null; lastAt = null }
}
