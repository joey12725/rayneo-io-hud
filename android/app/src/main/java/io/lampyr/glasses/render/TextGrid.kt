package io.lampyr.glasses.render

class TextGrid(val cols: Int = 26, val rows: Int = 7, private val uppercase: Boolean = true,
               private val normalizeInput: Boolean = true) {
    /** Diagnostics for layout tests; clipping remains defensive on the device. */
    var overflowed = false
        private set
    private val cells = Array(rows) { CharArray(cols) { ' ' } }

    // Preserve the original uppercase buffer contract for existing callers. The glasses UI
    // explicitly opts into mixed case; cell conversion and alignment are otherwise identical.
    private fun normalize(text: String) = (if (normalizeInput) gridText(text) else text).let {
        if (uppercase) it.uppercase(java.util.Locale.US) else it
    }

    fun clear() {
        cells.forEach { it.fill(' ') }
        overflowed = false
    }

    fun put(x: Int, y: Int, text: String) {
        val value = normalize(text)
        if (y !in 0 until rows) { if (value.isNotBlank()) overflowed = true; return }
        value.forEachIndexed { i, c ->
            if (x + i in 0 until cols) cells[y][x + i] = c else if (c != ' ') overflowed = true
        }
    }

    fun putR(y: Int, text: String) = normalize(text).let { put(cols - it.length, y, it) }

    fun center(y: Int, text: String) = normalize(text).let { put((cols - it.length) / 2, y, it) }

    fun lines(): List<String> = cells.map { String(it) }
}
