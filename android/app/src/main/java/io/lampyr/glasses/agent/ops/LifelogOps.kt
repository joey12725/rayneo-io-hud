package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import kotlinx.serialization.json.*

/**
 * Lifelog / ambient capture (AMB-14 passive listening: conversation recording and voice matching for People).
 * Pause and resume flip the same switch as holding minus on the remote. Dictation, memos and calls are unaffected.
 */
object LifelogOps : OpProvider {
    /** The service's capture status line; null in tests and before the service has started capture. */
    private fun captureStatus(): io.lampyr.glasses.ambient.AmbientStatus? =
        runCatching { io.lampyr.glasses.service.GlassesService.instance?.ambient?.status?.value }.getOrNull()

    private fun AgentCall.status(message: String) = ok(message, buildJsonObject {
        put("listening", ui.listening.on)
        captureStatus()?.let { s ->
            put("state", s.state); put("recordedSec", s.recordedMs / 1000); put("conversations", s.conversations)
        }
    })

    override fun ops() = listOf(
        OpSpec(
            "lifelog.status", "Read lifelog capture status",
            "Whether passive listening (ambient conversation capture and voice matching) is on, and today's capture state, recorded time and conversations.",
            risk = Risk.READ, tool = false,
        ) { status(if (ui.listening.on) "Passive listening is on" else "Passive listening is off") },

        OpSpec(
            "lifelog.pause", "Pause passive listening",
            "Stop ambient capture and voice matching now (the minus-hold switch). Dictation, memos and calls still work.",
            risk = Risk.ACT, confirm = Confirm.NEVER,
        ) {
            if (ui.listening.on) { ui.listening.set(false); ui.flash("Passive listening off") }
            status("Passive listening off")
        },

        OpSpec(
            "lifelog.resume", "Resume passive listening",
            "Turn passive listening back on (ambient capture and voice matching).",
            // Turning the microphone back on is confirmed outside the glasses conversation, like memo.start.
            risk = Risk.CALL, tool = false,
        ) {
            if (!ui.listening.on) { ui.listening.set(true); ui.flash("Passive listening on") }
            status("Passive listening on")
        },
    )
}
