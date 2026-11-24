package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Breakdown
import io.lampyr.glasses.data.Metric
import io.lampyr.glasses.data.Sample
import io.lampyr.glasses.ui.screens.Health
import io.lampyr.glasses.ui.screens.HealthDetail
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Health (Oura and friends): today's metrics, one metric's daily history, open a metric's chart. */
object HealthOps : OpProvider {
    private fun metrics(ui: io.lampyr.glasses.ui.Ui) = ui.data.health.metrics.filter { it.value != "—" || it.series.isNotEmpty() }

    /** A metric (key or name), or one of its breakdown rows ("deep sleep", "sleep.deep"). */
    private fun AgentCall.metric(): Pair<Metric, Breakdown?> {
        val wanted = str("metric")
        val all = metrics(ui)
        all.firstOrNull { it.key.equals(wanted, true) || it.name.equals(wanted, true) }?.let { return it to null }
        val subs = all.flatMap { m -> m.breakdown.filter { it.key.isNotEmpty() || it.series.isNotEmpty() }.map { m to it } }
        subs.firstOrNull { (_, b) -> b.key.equals(wanted, true) || b.label.equals(wanted, true) }?.let { return it }
        val loose = all.filter { it.name.contains(wanted, true) || it.key.contains(wanted, true) }.map { it to null as Breakdown? } +
            subs.filter { (_, b) -> b.label.contains(wanted, true) }
        return when (loose.size) {
            1 -> loose.single()
            0 -> fail(Codes.FAILED, "No health metric matching \"$wanted\"; use health_overview")
            else -> fail(Codes.AMBIGUOUS, "Several metrics match \"$wanted\"",
                JsonArray(loose.take(8).map { (m, b) -> buildJsonObject { put("id", b?.key?.ifEmpty { null } ?: m.key); put("name", b?.label ?: m.name) } }))
        }
    }

    /** The last [days] days of samples (calendar days when dated, else the last N samples, as the chart does). */
    private fun AgentCall.window(samples: List<Sample>, days: Int): List<Sample> {
        val dates = samples.map { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        if (dates.any { it == null }) return samples.takeLast(days)
        val first = Instant.ofEpochMilli(ui.nowMs()).atZone(ui.zone).toLocalDate().minusDays(days - 1L)
        return samples.filterIndexed { i, _ -> !dates[i]!!.isBefore(first) }
    }

    private fun number(v: Double) = if (v % 1.0 == 0.0) JsonPrimitive(v.toLong()) else JsonPrimitive(Math.round(v * 100) / 100.0)

    override fun ops() = listOf(
        OpSpec(
            "health.overview", "Read health overview",
            "The user's latest health metrics (readiness, sleep, activity, HRV, resting heart rate, weight...): value, when measured and the breakdown rows.",
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.health.refresh()
            ok(data = buildJsonObject {
                putText("updated", ui.data.health.updatedLabel)
                put("metrics", JsonArray(metrics(ui).map { m ->
                    buildJsonObject {
                        put("key", m.key); put("name", m.name); put("value", m.value); putText("unit", m.unit); putText("measuredAt", m.measuredAt)
                        if (m.breakdown.isNotEmpty()) put("breakdown", JsonArray(m.breakdown.take(12).map { b ->
                            JsonPrimitive(b.label + ": " + (b.value ?: b.score?.let { s -> number(s).content } ?: "—"))
                        }))
                    }
                }))
            })
        },

        OpSpec(
            "health.metric_history", "Read a metric's history",
            "Daily values of one metric (key or name, or a breakdown row such as deep sleep) over the last N days, oldest first, with min, max and average.",
            params = listOf(
                Param.string("metric", "Metric key or name (see health_overview)", required = true, maxLength = 100),
                Param.integer("days", "Days (default 7)", min = 1, max = 90),
            ),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            if (ui.data.health.metrics.isEmpty()) ui.data.health.refresh()
            val (m, b) = metric()
            val days = int("days", 7)
            val samples = window(b?.series?.takeIf { it.isNotEmpty() } ?: if (b == null) m.series else emptyList(), days)
            val values = samples.map { it.value }
            ok(if (samples.isEmpty()) "No data in the last $days days" else null, buildJsonObject {
                put("metric", b?.label ?: m.name); putText("unit", b?.unit ?: m.unit); put("days", days)
                put("samples", JsonArray(samples.map { s -> buildJsonObject { put("date", s.date); put("value", number(s.value)) } }))
                if (values.isNotEmpty()) { put("min", number(values.min())); put("max", number(values.max())); put("avg", number(values.average())) }
            })
        },

        OpSpec(
            "health.open_metric", "Show a health chart", "Show one metric's chart on the glasses, 7 or 30 days.",
            params = listOf(
                Param.string("metric", "Metric key or name", required = true, maxLength = 100),
                Param.integer("days", "7 (default) or 30", min = 7, max = 30),
            ),
            risk = Risk.NAVIGATE, tool = false, available = ::lockedReason,
        ) {
            val (m, b) = metric()
            val range = if (int("days", 7) >= 30) 30 else 7
            if (ui.top !is Health) show(Health(ui))
            show(HealthDetail(ui, m.key, b?.key.orEmpty(), range))
            ok("Showing ${b?.label ?: m.name}, $range days", registry.context())
        },
    )
}
