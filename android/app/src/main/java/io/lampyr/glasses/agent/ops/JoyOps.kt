package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.ui.screens.JoyCall
import kotlinx.serialization.json.*

/** The assistant app ops (worked example for AGENT_OPS.md). The assistant's own screen has no type_text: it would message the assistant. */
object JoyOps : OpProvider {
    override fun ops() = listOf(
        OpSpec(
            "joy.read_reply", "Read the assistant's glasses reply",
            "The conversation currently on the glasses assistant screen: status, the last prompt and the assistant's last reply text.",
            risk = Risk.READ, tool = false,
        ) {
            val s = ui.data.joy.snapshot
            ok(data = buildJsonObject {
                put("status", s.status)
                s.prompt?.let { put("prompt", it) }
                put("text", s.text.take(AgentScreen.MAX_TEXT))
                put("sessionId", s.sessionId?.let(::JsonPrimitive) ?: JsonNull)
            })
        },

        OpSpec(
            "joy.new_conversation", "Start a new assistant conversation",
            "Start a fresh glasses conversation with the assistant (the next glasses message begins a new session).",
            risk = Risk.ACT,
        ) {
            val result = ui.data.joy.newConversation()
            if (result.ok) ui.joyPage = 0
            done(result, "New conversation")
        },

        OpSpec(
            "joy.set_quiet", "Set assistant quiet wait",
            "Quiet wait on: the assistant's glasses replies arrive as a notification instead of taking over the screen (the L2 toggle).",
            params = listOf(Param.boolean("on", "true = quiet wait on", required = true)),
            risk = Risk.ACT, tool = false,
        ) {
            ui.quiet = bool("on")
            ui.requestRender()
            ok(if (ui.quiet) "Quiet wait on" else "Quiet wait off")
        },

        OpSpec(
            "joy.start_voice_call", "Start an assistant voice call",
            "Start a live voice call with the assistant on the glasses (uses the microphone).",
            risk = Risk.CALL, timeoutMs = 15_000,
            available = { ui ->
                when {
                    ui.data.joy.call.active -> "An assistant voice call is already active"
                    ui.data.calls.snapshot.calls.isNotEmpty() -> "The user is on a phone call"
                    ui.locked -> "The glasses are locked; call glasses_unlock first"
                    else -> null
                }
            },
        ) {
            ui.push(JoyCall(ui))
            ok("Voice call starting on the glasses")
        },

        OpSpec(
            "joy.end_voice_call", "End the assistant voice call", "Hang up the live assistant voice call.",
            risk = Risk.ACT, confirm = Confirm.NEVER,
            available = { ui -> if (!ui.data.joy.call.active) "No assistant voice call is active" else null },
        ) { done(ui.data.joy.endVoiceCall(), "Voice call ended") },
    )
}
