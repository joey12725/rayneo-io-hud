package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Chat
import io.lampyr.glasses.ui.Ui
import kotlinx.serialization.json.*

/**
 * The worked example from android/AGENT_OPS.md, compiled and tested (ExampleOpsTest) so the guide never rots.
 * It is the Beeper pattern under the test-only app name `example` (the real BeeperOps belongs to the Beeper owner).
 */
object ExampleOps : OpProvider {
    /** Chat id or case-insensitive name; ambiguous names fail with the candidates so the model can ask. */
    private fun AgentCall.chat(): Chat {
        val wanted = str("chat")
        val chats = ui.data.beeper.chats
        chats.firstOrNull { it.id == wanted }?.let { return it }
        val exact = chats.filter { it.name.equals(wanted, ignoreCase = true) }
        val matches = exact.ifEmpty { chats.filter { it.name.contains(wanted, ignoreCase = true) } }
        return when (matches.size) {
            1 -> matches.single()
            0 -> fail(Codes.FAILED, "No chat named \"$wanted\"; use example_list_chats")
            else -> fail(Codes.AMBIGUOUS, "Several chats match \"$wanted\"",
                JsonArray(matches.take(8).map { buildJsonObject { put("id", it.id); put("name", it.name) } }))
        }
    }

    override fun ops() = listOf(
        OpSpec(
            "example.list_chats", "List chats",
            "the user's chats, most recent first: id, name, network and unread count.",
            params = listOf(Param.boolean("unread_only", "Only chats with unread messages")),
            risk = Risk.READ,
        ) {
            ui.data.beeper.refreshChats()
            val chats = ui.data.beeper.chats.filter { !bool("unread_only") || it.unread > 0 }
            ok(data = JsonArray(chats.take(25).map {
                buildJsonObject { put("id", it.id); put("name", it.name); put("network", it.network); put("unread", it.unread) }
            }))
        },

        OpSpec(
            "example.send_message", "Send a message",
            "Send a text message to one of the user's chats (chat id or name).",
            params = listOf(
                Param.string("chat", "Chat id or name (see example_list_chats)", required = true, maxLength = 200),
                Param.string("text", "Message text, exactly as it should be sent", required = true, maxLength = 4000),
            ),
            risk = Risk.SEND, timeoutMs = 15_000,
            available = { ui: Ui -> if (ui.data.beeper.chats.isEmpty()) "No chats are loaded yet" else null },
            summary = { call -> "to ${call.strOrNull("chat")}: ${call.strOrNull("text").orEmpty().take(60)}" },
        ) {
            val chat = chat()
            done(ui.data.beeper.send(chat.id, str("text")), "Sent to ${chat.name}")
        },
    )
}
