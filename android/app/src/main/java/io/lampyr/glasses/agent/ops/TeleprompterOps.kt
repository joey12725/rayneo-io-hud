package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.screens.Teleprompter
import kotlinx.serialization.json.*

/**
 * Teleprompter: read the script, open it, start/stop auto-scroll, scroll by lines. The screen's own key handler
 * moves the script (Y toggles auto-scroll, up/down a line), so text and image modes stay consistent.
 */
object TeleprompterOps : OpProvider {
    private fun open(ui: Ui) = ui.top as? Teleprompter

    /** The Teleprompter on top, opening it first when [openIt]. */
    private fun AgentCall.prompter(openIt: Boolean): Teleprompter =
        open(ui) ?: if (openIt) show(Teleprompter(ui)) as Teleprompter else fail(Codes.UNAVAILABLE, "The teleprompter is not open; call teleprompter_start or glasses_open_app")

    private fun AgentCall.state(t: Teleprompter, message: String) = ok(message, buildJsonObject {
        put("autoScroll", t.auto); put("line", t.off + 1)
    })

    override fun ops() = listOf(
        OpSpec(
            "teleprompter.read_script", "Read the teleprompter script",
            "The teleprompter script text, and whether the teleprompter is open and auto-scrolling.",
            risk = Risk.READ, tool = false,
        ) {
            ui.data.memos.refresh()
            val t = open(ui)
            ok(data = buildJsonObject {
                put("script", clip(ui.data.memos.script)); put("open", t != null)
                t?.let { put("autoScroll", it.auto); put("line", it.off + 1) }
            })
        },

        OpSpec(
            "teleprompter.open", "Open the teleprompter", "Open the teleprompter at the top of the script, paused.",
            risk = Risk.NAVIGATE, tool = false, available = ::lockedReason,
        ) { state(prompter(openIt = true), "Teleprompter open") },

        OpSpec(
            "teleprompter.start", "Start the teleprompter",
            "Open the teleprompter (if needed) and start auto-scrolling the script; the display stays lit while it scrolls.",
            risk = Risk.ACT, available = ::lockedReason,
        ) {
            val t = prompter(openIt = true)
            if (!t.auto) t.key("sel")
            ui.requestRender()
            state(t, "Auto-scroll on")
        },

        OpSpec(
            "teleprompter.stop", "Pause the teleprompter", "Pause teleprompter auto-scrolling (the script stays where it is).",
            risk = Risk.ACT, confirm = Confirm.NEVER,
            available = { ui -> if (open(ui) == null) "The teleprompter is not open" else null },
        ) {
            val t = prompter(openIt = false)
            if (t.auto) t.key("sel")
            ui.requestRender()
            state(t, "Auto-scroll paused")
        },

        OpSpec(
            "teleprompter.scroll", "Scroll the teleprompter", "Move the open teleprompter script by lines (negative = back).",
            params = listOf(Param.integer("lines", "Lines to move, e.g. 3 or -3", required = true, min = -200, max = 200)),
            risk = Risk.NAVIGATE, tool = false,
            available = { ui -> lockedReason(ui) ?: if (open(ui) == null) "The teleprompter is not open" else null },
        ) {
            val t = prompter(openIt = false)
            val n = int("lines")
            repeat(kotlin.math.abs(n)) { t.key(if (n > 0) "down" else "up") }
            ui.requestRender()
            state(t, "Line ${t.off + 1}")
        },
    )
}
