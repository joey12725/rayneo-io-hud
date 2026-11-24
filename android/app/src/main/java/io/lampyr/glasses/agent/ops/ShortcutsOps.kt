package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.input.FREE_GESTURES
import io.lampyr.glasses.input.ShortcutGesture
import kotlinx.serialization.json.*

/** Controller shortcut ops (Settings > Shortcuts): which app action each free button gesture runs. */
object ShortcutsOps : OpProvider {
    private fun AgentCall.gesture(): ShortcutGesture {
        val wanted = str("gesture").trim()
        return FREE_GESTURES.firstOrNull { it.id.equals(wanted, true) || it.label.equals(wanted, true) }
            ?: fail(Codes.INVALID_ARGS, "Unknown gesture \"$wanted\"; use shortcuts_list")
    }

    override fun ops() = listOf(
        OpSpec(
            "shortcuts.list", "List controller shortcuts",
            "Each assignable controller gesture (id, label), its assigned app action, the actions it can take, and the apps that keep that button for themselves.",
            risk = Risk.READ, tool = false,
        ) {
            val listed = ui.shortcuts.gestures()
            ok(data = JsonArray(FREE_GESTURES.map { g ->
                buildJsonObject {
                    put("id", g.id); put("label", g.label)
                    put("on_controller", g in listed)
                    put("action", ui.shortcuts.assigned(g)?.let { a -> buildJsonObject { put("id", a.id); put("label", a.label) } } ?: JsonNull)
                    put("choices", JsonArray(ui.shortcuts.actions(g).map { a -> buildJsonObject { put("id", a.id); put("label", a.label) } }))
                    put("not_in", JsonArray(ui.shortcuts.overriddenIn(g).map { JsonPrimitive(it.id) }))
                }
            }))
        },

        OpSpec(
            "shortcuts.assign", "Assign a controller shortcut",
            "Give a controller gesture (e.g. l2.hold) an app action id or label from shortcuts_list, or action \"none\" to clear it.",
            params = listOf(
                Param.string("gesture", "Gesture id or label, e.g. l2.hold or Hold L2", required = true, maxLength = 40),
                Param.string("action", "Action id or label (e.g. memo.toggle), or none", required = true, maxLength = 60),
            ),
            risk = Risk.ACT, tool = false,
        ) {
            val g = gesture()
            val wanted = str("action").trim()
            if (wanted.equals("none", true)) {
                ui.shortcuts.assign(g, null)
                return@OpSpec ok("${g.label}: none")
            }
            val choices = ui.shortcuts.actions(g)
            val action = choices.firstOrNull { it.id.equals(wanted, true) || it.label.equals(wanted, true) }
                ?: fail(Codes.INVALID_ARGS, "\"$wanted\" cannot be assigned to ${g.label}; choices: ${choices.joinToString { it.id }.ifEmpty { "none" }}")
            ui.shortcuts.assign(g, action.id)
            ok("${g.label}: ${action.label}")
        },
    )
}
