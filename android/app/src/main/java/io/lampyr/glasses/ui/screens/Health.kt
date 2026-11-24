package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import java.time.Instant
import java.time.LocalDate
import kotlin.math.*

private fun Gfx.spark(d: List<Double>, x: Double, y: Double, w: Double, h: Double) {
    if (d.size < 2) return
    val mn = d.min()
    val range = (d.max() - mn).takeIf { it != 0.0 } ?: 1.0
    p.path(d.mapIndexed { i, v -> x + i * w / (d.size - 1) to y + h - (v - mn) / range * h }, 2)
}

/** Metrics with something real to show; a metric with no value and no samples is hidden rather than shown as 0 or —. */
private fun visibleMetrics(ui: Ui) = ui.data.health.metrics.filter { it.value != "—" || it.series.isNotEmpty() }

class Health(ui: Ui) : Screen(ui, "health") {
    var cur = 0

    override fun onOpen() {
        ui.refresh { ui.data.health.refresh() }
    }

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            frame(ui, "Health", "health", t, railText = listOf(ui.data.health.updatedLabel))
            val metrics = visibleMetrics(ui)
            if (metrics.isEmpty()) return@with
            cur = cur.coerceIn(0, metrics.lastIndex)
            val rh = 50
            val start = cur / 3 * 3
            metrics.drop(start).take(3).forEachIndexed { j, m ->
                val i = start + j
                val y = TOP + j * rh
                val on = i == cur
                if (on) {
                    p.rr(CX - 4, y, CW - 4, rh - 4, 5, 2, true)
                    p.fr(CX - 1, y + 4, 30, rh - 12, BLACK)
                }
                val col = if (on) BLACK else GREEN
                icon(m.icon, CX, y + (rh - 4 - 24) / 2.0, 24, t)
                tx(m.name, CX + 36, y + 3, "s", col)
                tx(m.value, CX + 36, y + 3 + lh("s"), "m", col)
                if (!on)
                    spark(
                        m.series.takeLast(14).map { it.value },
                        W - 150.0,
                        y + 8.0,
                        120.0,
                        rh - 20.0,
                    )
                else {
                    // Wake/day labels ("Woke 7:01 AM", "Yesterday") are already short and lose meaning as a bare time.
                    val short = m.measuredAt.takeIf { m.measuredKind in setOf("wake", "day") && tw(it, "s") <= 150 }
                        ?: Regex("\\d{1,2}:\\d{2} [AP]M", RegexOption.IGNORE_CASE).find(m.measuredAt)?.value
                        ?: m.measuredAt.replace("night average", "night avg", ignoreCase = true)
                            .replace("Oura · this morning", "Morning", ignoreCase = true)
                    if (tw(short, "s") <= 150) txR(short, W - 28, y + 8, "s", col)
                }
            }
            if (metrics.size > 3) txR("${cur / 3 + 1}/${(metrics.size + 2) / 3}", W - 14, BOTTOM - 16, "s", tone = SECONDARY)
        }

    override fun key(k: String): Boolean {
        val metrics = visibleMetrics(ui)
        val n = metrics.size
        if (n == 0) return false
        when (k) {
            "up" -> cur = max(0, cur - 1)
            "down" -> cur = min(n - 1, cur + 1)
            "sel" -> ui.push(HealthDetail(ui, metrics[cur.coerceIn(0, n - 1)].key))
            else -> return false
        }
        return true
    }

    override fun legend() = mapOf("up" to "Move", "down" to "Move", "sel" to
        if (visibleMetrics(ui).getOrNull(cur)?.hasBreakdown == true) "History & breakdown" else "History")

    override fun drawText(tg: TextGrid) {
        TextChrome.header(tg, "Health", ui.clockString())
        tg.put(0, 1, TextChrome.fit(ui.data.health.updatedLabel, tg.cols))
        val metrics = visibleMetrics(ui)
        val rows = metrics.map { m ->
            val points = m.series.takeLast(14)
            val trend = if (points.size < 2) "" else when {
                points.last().value > points.first().value -> " up"
                points.last().value < points.first().value -> " dn"
                else -> " ="
            }
            Row(m.name + " " + m.value + trend, sub = m.measuredAt.takeIf { it.isNotBlank() })
        }
        TextChrome.footer(tg, hint = "Y open B back")
        TextChrome.list(tg, rows, cur, start = 2)
    }
}

/** Formats a breakdown row's own unit (gateway `unit`), e.g. minutes as 1h 12m and clock minutes as 11:42 PM. */
internal fun healthValue(v: Double, unit: String): String = when (unit) {
    "min" -> floor(v + .5).toInt().let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
    "clock" -> floor(v + .5).toInt().mod(1440).let { m -> "${(m / 60).let { h -> if (h % 12 == 0) 12 else h % 12 }}:${(m % 60).toString().padStart(2, '0')} ${if (m < 720) "AM" else "PM"}" }
    "%" -> "${floor(v + .5).toInt()}%"
    "ms", "bpm" -> "${floor(v + .5).toInt()} $unit"
    "brpm" -> number(floor(v * 10 + .5) / 10) + " brpm"
    "°C" -> (if (v > 0) "+" else "") + number(floor(v * 10 + .5) / 10) + " °C"
    "lb", "kg" -> number(floor(v * 10 + .5) / 10) + " $unit"
    else -> floor(v + .5).toInt().toString()
}

/**
 * A metric's history, or one breakdown row's history when [sub] names it (12.5). The chart covers the
 * selected window by calendar date: one slot per day, so 7D and 30D always differ and missing days show.
 */
class HealthDetail(ui: Ui, private val key: String, val sub: String = "", initialRange: Int = 7) : Screen(ui, if (sub.isEmpty()) "hd" else "hds") {
    var range = initialRange
    var page = 0
    private val metric
        get() = ui.data.health.metrics.find { it.key == key }
    private val row
        get() = if (sub.isEmpty()) null else metric?.breakdown?.find { it.key == sub }
    private val samples
        get() = if (sub.isEmpty()) metric?.series.orEmpty() else row?.series.orEmpty()
    private val hasBreakdown
        get() = sub.isEmpty() && metric?.hasBreakdown == true
    /** Rows with their own history: every sleep quantity and contributor, readiness contributors, body composition. */
    internal val pickable
        get() = metric?.breakdown?.filter { it.key.isNotEmpty() && it.series.isNotEmpty() }.orEmpty()

    private fun fmt(v: Double) = row?.let { healthValue(v, it.unit) } ?: when (key) {
            "weight" -> number(v) + " " + (metric?.unit?.takeIf { it.isNotBlank() } ?: "lb")
            "hrv" -> "${floor(v+.5).toInt()} ms"
            "rhr" -> "${floor(v+.5).toInt()} bpm"
            else -> floor(v + .5).toInt().toString()
        }

    /**
     * One entry per day of the window, oldest first, null where there is no sample. Fixture series with
     * synthetic dates (not YYYY-MM-DD) keep the last-N-samples behaviour.
     */
    internal fun window(): List<Double?> {
        val s = samples
        val dates = s.map { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        if (s.isEmpty() || dates.any { it == null }) return s.takeLast(range).map { it.value }
        val today = Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate()
        val first = today.minusDays(range - 1L)
        val byDay = s.indices.associate { dates[it]!! to s[it].value }
        return (0 until range).map { byDay[first.plusDays(it.toLong())] }
    }

    private fun title(m: Metric) = row?.label ?: m.name
    private fun headline(m: Metric) = row?.let { b -> b.value ?: b.score?.let { number(it) } ?: "—" } ?: m.value
    private fun pickHint() = if (pickable.isNotEmpty()) " · Y pick" else ""

    override fun draw(g: Gfx, t: Double) =
        with(g) {
            val m = metric ?: return@with
            frame(ui, title(m), m.icon, t, "${range}D", if (row != null) listOf(headline(m), m.name) else listOf(m.value, m.measuredAt))
            val slots = window()
            val d = slots.filterNotNull()
            if (d.isEmpty() && page == 0) {
                if (samples.isEmpty()) return@with
                tx("No data in the last $range days", CX, TOP + 4, "s", tone = SECONDARY)
                hintTextR("◀▶ 7D/30D" + (if (hasBreakdown) " · ▼ breakdown" else "") + pickHint(), W - 14, BOTTOM - 16, "s")
                return@with
            }
            if (page == 0) {
                val w = CW - 10
                val h = 80
                val y0 = TOP + 4
                val mn = d.min()
                val mx = d.max()
                val rg = (mx - mn).takeIf { it != 0.0 } ?: 1.0
                val bw = w.toDouble() / slots.size
                slots.forEachIndexed { i, v ->
                    if (v == null) {
                        // A missing day is a short baseline tick, so gaps read as gaps rather than closing up.
                        p.fr(floor(CX + i * bw + 1 + .5), (y0 + h - 2).toDouble(), max(2.0, floor(bw - 3)), 2.0, tone = TERTIARY)
                        return@forEachIndexed
                    }
                    val bh = 8 + (v - mn) / rg * (h - 8)
                    p.fr(
                        floor(CX + i * bw + 1 + .5),
                        floor(y0 + h - bh + .5),
                        max(2.0, floor(bw - 3)),
                        floor(bh + .5),
                    )
                }
                tx("low " + fmt(mn), CX, y0 + h + 8, "s", tone = SECONDARY)
                txR("high " + fmt(mx), CX + w, y0 + h + 8, "s", tone = SECONDARY)
                val avg = floor(d.average() * 10 + .5) / 10
                tx("avg " + fmt(avg), CX, y0 + h + 8 + lh("s"), tone = SECONDARY)
                hintTextR("◀▶ 7D/30D" + (if (hasBreakdown) " · ▼ breakdown" else "") + pickHint(), W - 14, BOTTOM - 16, "s")
            } else {
                var y = TOP
                tx("BREAKDOWN", CX, y - 2, "s", tone = SECONDARY)
                y += lh("s") + 2
                for (b in m.breakdown.drop((page - 1) * 4).take(4)) {
                    val labelWidth = m.breakdown.maxOf { tw(it.label) }
                    val shown = breakdownValue(b)
                    tx(b.label, CX, y)
                    if (!shown.isNullOrEmpty()) txR(shown, W - 16, y)
                    b.score?.let {
                        bar(
                            CX + labelWidth + 10,
                            y + 4,
                            CW - labelWidth - 24 - (shown?.takeIf { it.isNotEmpty() }?.let { tw(it) + 10 } ?: 0),
                            12,
                            it / 100,
                        )
                    }
                    y += lh() + 6
                }
                hintTextR("▲ chart" + pickHint() + " · $page/${(m.breakdown.size + 3) / 4}", W - 14, BOTTOM - 16, "s")
            }
        }

    /** ▲/▼ before a signed change (12.2); glyphs exist in the bitmap font. */
    private fun breakdownValue(b: Breakdown) = b.value?.let { v -> when (b.trend) { "up" -> "▲ $v"; "down" -> "▼ $v"; else -> v } }

    private var detailPage = 0
    private fun detailText(): String {
        val m = metric ?: return "No health data"
        if (page > 0) return "-BREAKDOWN-\n" + m.breakdown.joinToString("\n") { b ->
            // ASCII trend marks: the stock text charset has no triangles.
            val value = b.value?.let { v -> when (b.trend) { "up" -> "^$v"; "down" -> "v$v"; else -> v } } ?: b.score?.let { number(it) }.orEmpty()
            TextChrome.fit(b.label, ui.textDisplay.cols - 10).padEnd(ui.textDisplay.cols - 10) + TextChrome.fit(value, 10).padStart(10)
        }
        val slots = window()
        val d = slots.filterNotNull()
        if (d.isEmpty()) return if (samples.isEmpty()) "No samples" else "No data in the last $range days"
        // ASCII heights survive the stock firmware's verified character set; '.' is a day without a sample.
        val span = (d.max() - d.min()).takeIf { it > 0 } ?: 1.0
        val digits = slots.joinToString("") { v -> v?.let { ((it - d.min()) / span * 9).roundToInt().toString() } ?: "." }
            .chunked(ui.textDisplay.cols).joinToString("\n")
        return (if (slots.size <= ui.textDisplay.cols) headline(m) + "\n" else "") + digits + "\nLow " + fmt(d.min()) + "\nHigh " + fmt(d.max()) +
            "\nAvg " + fmt(floor(d.average() * 10 + .5) / 10)
    }
    private fun detailPages() = if (page > 0) detailText().split('\n').chunked(ui.textDisplay.rows - 2)
        else ui.textPages(detailText())

    private fun openPicker() {
        val m = metric ?: return
        if (pickable.isEmpty()) return
        ui.push(HealthPicker(ui, m.key, sub, range))
    }

    override fun key(k: String): Boolean {
        if (k == "sel" && pickable.isNotEmpty()) { openPicker(); return true }
        if (ui.isText && k in listOf("up", "down")) {
            val n = detailPages().size
            if (k == "down") {
                if (detailPage < n - 1) detailPage++ else if (page == 0 && hasBreakdown) { page = 1; detailPage = 0 }
            } else if (detailPage > 0) detailPage-- else page = 0
            return true
        }
        if (ui.isText && k in listOf("left", "right")) detailPage = 0
        when (k) {
            "left",
            "right" -> range = if (range == 7) 30 else 7
            "down" -> if (hasBreakdown) page = minOf((metric!!.breakdown.size + 3) / 4, page + 1)
            "up" -> page = maxOf(0, page - 1)
            else -> return false
        }
        return true
    }

    override fun legend() =
        mapOf(
            "left" to "7 / 30 days",
            "right" to "7 / 30 days",
            "up" to "Chart",
        ) + (if (hasBreakdown) mapOf("down" to "Breakdown") else emptyMap()) +
            (if (pickable.isNotEmpty()) mapOf("sel" to "Pick metric") else emptyMap())

    override fun drawText(tg: TextGrid) {
        val m = metric
        detailPage = TextChrome.reader(tg, m?.let { title(it) } ?: "Health", "${range}D",
            detailPages(), detailPage, if (pickable.isNotEmpty()) (if (page > 0) "<> range ^ chart Y pick" else "<> range ^v more Y pick")
                else if (page > 0) "<> range ^v chart B back" else if (hasBreakdown) "<> range ^v detail B back" else "<> range B back")
    }
}

/**
 * Metric picker (12.5): the metric itself plus every breakdown row with its own history, in the
 * standard list design. Choosing one shows its chart with the same 7D/30D window.
 */
class HealthPicker(ui: Ui, private val key: String, private val current: String, private val range: Int) :
    ListView(
        ui,
        "hpick",
        items = {
            val m = ui.data.health.metrics.find { it.key == key }
            if (m == null) emptyList() else
                listOf(Row(m.name, right = m.value.takeIf { it != "—" }.orEmpty(), open = { pickHealth(ui, key, "", range) })) +
                    m.breakdown.filter { it.key.isNotEmpty() && it.series.isNotEmpty() }.map { b ->
                        Row(b.label, right = b.value ?: b.score?.let { number(it) }.orEmpty(), open = { pickHealth(ui, key, b.key, range) })
                    }
        },
        chrome = { t ->
            val m = ui.data.health.metrics.find { it.key == key }
            frame(ui, (m?.name ?: "Health") + " metrics", m?.icon ?: "health", t, "${range}D")
        },
    ) {
    init {
        val m = ui.data.health.metrics.find { it.key == key }
        cur = if (current.isEmpty()) 0 else (m?.breakdown?.filter { it.key.isNotEmpty() && it.series.isNotEmpty() }?.indexOfFirst { it.key == current } ?: -1) + 1
        textTitle = { (m?.name ?: "Health") + " metrics" }
        textContext = { "${range}D" }
        textHint = { "Y show B back" }
        animateChrome = false
    }
}

/** Replaces the picker (and a previous sub-metric chart) so B always returns to the metric's own chart. */
private fun pickHealth(ui: Ui, key: String, sub: String, range: Int) {
    ui.pop()
    val top = ui.top
    if (top is HealthDetail && top.sub.isNotEmpty()) ui.pop()
    if (sub.isEmpty()) {
        (ui.top as? HealthDetail)?.let { it.range = range; it.page = 0 }
        return
    }
    (ui.top as? HealthDetail)?.range = range
    ui.push(HealthDetail(ui, key, sub, range))
}
