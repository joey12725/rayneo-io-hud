package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.ui.screens.Report
import kotlinx.serialization.json.*

/** Morning report: read the latest report's cards, open it on the glasses (optionally at one card). */
object ReportOps : OpProvider {
    override fun ops() = listOf(
        OpSpec(
            "report.latest", "Read the morning report",
            "The latest morning report on the glasses: its date, whether it is today's or stale, and every card (type, heading, body).",
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.report.refresh()
            val r = ui.data.report.snapshot
            ok(if (r.cards.isEmpty()) "No report yet" else null, buildJsonObject {
                putText("date", r.reportDate.ifBlank { r.date })
                put("isToday", r.isToday)
                if (r.stale) { put("stale", true); put("ageDays", r.ageDays) }
                if (r.fallback) put("fallback", true)
                put("cards", JsonArray(r.cards.map { c -> buildJsonObject { put("type", c.type); put("heading", c.heading); put("body", clip(c.body, 600)) } }))
            })
        },

        OpSpec(
            "report.open", "Open the morning report",
            "Open the morning report on the glasses, at the first card or at one card (heading or type, e.g. weather, calendar, news).",
            params = listOf(Param.string("card", "Card heading or type (optional)", maxLength = 100)),
            risk = Risk.NAVIGATE, tool = false, available = ::lockedReason,
        ) {
            val cards = ui.data.report.snapshot.cards
            val wanted = strOrNull("card")
            val index = if (wanted == null) 0 else {
                val i = cards.indexOfFirst { it.heading.equals(wanted, true) || it.type.equals(wanted, true) }
                    .takeIf { it >= 0 } ?: cards.indexOfFirst { it.heading.contains(wanted, true) }
                if (i < 0) fail(Codes.FAILED, "No report card matching \"$wanted\"", JsonArray(cards.map { JsonPrimitive(it.heading) }))
                i
            }
            val screen = (ui.top as? Report) ?: show(Report(ui)) as Report
            screen.i = index
            ui.requestRender()
            ok(cards.getOrNull(index)?.let { "Showing ${it.heading}" } ?: "Opened the report", registry.context())
        },
    )
}
