package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import kotlin.math.*

class Report(ui: Ui) : Screen(ui, "report", "cards") {
    var i = 0
    private var cardPage = 0

    override fun onOpen() {
        ui.refresh { ui.data.report.refresh() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val report = ui.data.report.snapshot
            val cards = report.cards
            if (cards.isEmpty()) {
                frame(ui, "Morning report", "news", t)
                tx("No report yet", CX, TOP)
                return@with
            }
            i = i.coerceIn(0, cards.lastIndex)
            val c = cards[i]
            val (title, badge) = reportHeader(report, ui)
            frame(ui, title, c.icon, t, badge, listOf(c.heading))
            var y = TOP + 6
            val ps = pages(c.body, CW - 12, 4, "l")
            cardPage = cardPage.coerceIn(0, ps.lastIndex)
            ps[cardPage].forEach {
                drawLine(it, CX, y, "l")
                y += lh("l")
            }
            if (ps.size > 1) txR("▲▼ ${cardPage + 1}/${ps.size}", W - 12, BOTTOM - 40, "s", tone = SECONDARY)
            repeat(cards.size) {
                val cx = CX + CW / 2.0 + (it - (cards.size - 1) / 2.0) * 16
                p.circ(cx, H - 14, if (it == i) 5.0 else 3.5, if (it == i) 1 else 2, it == i, tone = if (it == i) PRIMARY else TERTIARY)
            }
        }

    private var textPage = 0
    private data class CardPage(val card: Int, val page: Int, val count: Int, val lines: List<String>)
    private fun textCards(): List<CardPage> = ui.data.report.snapshot.cards.flatMapIndexed { index, card ->
        val ps = ui.textPages(card.body)
        ps.mapIndexed { page, lines -> CardPage(index, page, ps.size, lines) }
    }.ifEmpty { listOf(CardPage(0, 0, 1, listOf("No report yet"))) }

    override fun key(k: String): Boolean {
        if (ui.isText && k in listOf("up", "down", "left", "right")) {
            val pages = textCards()
            if (k in listOf("up", "down")) textPage = (textPage + if (k == "down") 1 else -1).coerceIn(0, pages.lastIndex)
            else {
                val current = pages[textPage.coerceIn(0, pages.lastIndex)].card
                val card = (current + if (k == "right") 1 else -1)
                    .coerceIn(0, (ui.data.report.snapshot.cards.size - 1).coerceAtLeast(0))
                if (card == current) return true
                textPage = pages.indexOfFirst { it.card == card }.coerceAtLeast(0)
            }
            return true
        }
        val cards = ui.data.report.snapshot.cards
        if (cards.isEmpty()) return false
        when (k) {
            "right", "left" -> {
                val next = (i + if (k == "right") 1 else -1).coerceIn(0, cards.lastIndex)
                if (next == i) return true
                i = next; cardPage = 0
            }
            "down", "up" -> cardPage = (cardPage + if (k == "down") 1 else -1)
                .coerceIn(0, pages(cards[i].body, CW - 12, 4, "l").lastIndex)
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "left" to "Prev card",
            "right" to "Next card",
            "up" to "Prev page",
            "down" to "Next page",
        )

    override fun drawText(tg: TextGrid) {
        val cards = textCards()
        textPage = textPage.coerceIn(0, cards.lastIndex)
        val current = cards[textPage]
        TextChrome.header(tg, ui.data.report.snapshot.cards.getOrNull(current.card)?.heading ?: "Morning report", reportTextBadge(ui.data.report.snapshot, ui))
        current.lines.forEachIndexed { index, line -> tg.put(0, index + 1, line) }
        val badge = "c${current.card + 1}/${maxOf(1, ui.data.report.snapshot.cards.size)}" + if (current.count > 1) " p${current.page + 1}/${current.count}" else ""
        tg.put(0, tg.rows - 1, if (tg.cols - badge.length - 1 >= 14) "^v read <> card" else "^v read")
        tg.putR(tg.rows - 1, badge)
    }
}

/** The report's own date, or null for fixtures whose date is already a display string. */
private fun reportDay(report: io.lampyr.glasses.data.ReportSnapshot) =
    runCatching { java.time.LocalDate.parse(report.reportDate.ifBlank { report.date }) }.getOrNull()

/** Days the report is behind today, recomputed locally so a cached snapshot goes stale at midnight. */
internal fun reportAge(report: io.lampyr.glasses.data.ReportSnapshot, ui: Ui): Long? = reportDay(report)?.let {
    java.time.temporal.ChronoUnit.DAYS.between(it, java.time.Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate()).coerceAtLeast(0)
}

/** Title and header badge: today's report reads "Morning report · Tue Nov 17"; an older one says so in the title. */
internal fun reportHeader(report: io.lampyr.glasses.data.ReportSnapshot, ui: Ui): Pair<String, String> {
    val day = reportDay(report) ?: return "Morning report" to report.date
    val age = reportAge(report, ui) ?: 0
    return if (age == 0L) "Morning report" to day.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US))
    else "Report from " + day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)) to
        (if (age == 1L) "yesterday" else "$age days old")
}

internal fun reportTextBadge(report: io.lampyr.glasses.data.ReportSnapshot, ui: Ui): String {
    val day = reportDay(report) ?: return report.date
    val age = reportAge(report, ui) ?: 0
    return day.format(java.time.format.DateTimeFormatter.ofPattern(if (age == 0L) "EEE MMM d" else "'from' MMM d", java.util.Locale.US))
}
