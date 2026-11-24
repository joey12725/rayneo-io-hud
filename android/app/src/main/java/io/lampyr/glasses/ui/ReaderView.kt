package io.lampyr.glasses.ui

import io.lampyr.glasses.render.*

open class ReaderView(
    ui: Ui,
    id: String,
    val text: () -> String,
    val title: String = "",
    val icon: String = "docs",
    val right: String? = null,
    val fullWidth: Boolean = false,
    val y0off: Int = 0,
    val rows: Int? = null,
) : Screen(ui, id, "reader") {
    override val needsAnimation
        get() = !fullWidth && icon in ANIMATED_ICONS

    var page = 0

    open fun pg() = if (ui.isText) textPages().map { lines -> lines.map { listOf(Token(w = it)) } } else
        pages(text(), if (fullWidth) XW else CW - 12, rows ?: ((BOTTOM - TOP - y0off - 22) / lh()))

    open fun header(g: Gfx, t: Double) {
        with(g) { if (fullWidth) frameW(ui, title, right) else frame(ui, title, icon, t, right) }
    }

    open fun footer(g: Gfx, n: Int) {
        g.pageFooter(page, n, if (fullWidth) W / 2 else CX + CW / 2, BOTTOM - 16)
    }

    override fun draw(g: Gfx, t: Double) {
        header(g, t)
        val ps = pg()
        page = page.coerceIn(0, ps.lastIndex)
        var y = TOP + y0off
        ps[page].forEach {
            g.drawLine(it, if (fullWidth) X0 else CX, y)
            y += lh()
        }
        footer(g, ps.size)
    }

    override fun key(k: String): Boolean {
        val n = pg().size
        when (k) {
            "down",
            "right" -> page = (page + 1).coerceAtMost(n - 1)
            "up",
            "left" -> page = (page - 1).coerceAtLeast(0)
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "up" to "Prev page",
            "down" to "Next page",
            "left" to "Prev page",
            "right" to "Next page",
        )

    open val textTitle: String get() = title.ifBlank { "Read" }
    open val textContext: String get() = right ?: ui.clockString()
    open val textHint: String get() = "^v page B back"
    open val textFooterState: String get() = ""
    open fun textContent(): String = text()
    fun textPages() = ui.textPages(textContent())

    override fun drawText(tg: TextGrid) {
        page = TextChrome.reader(tg, textTitle, textContext,
            gridPages(textContent(), tg.cols, tg.rows - 2), page, textHint, textFooterState)
    }
}
