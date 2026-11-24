package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.Chat
import io.lampyr.glasses.data.Mention
import io.lampyr.glasses.data.Message
import io.lampyr.glasses.ui.screens.BeeperThread
import kotlinx.serialization.json.*

/** Beeper chats: list (with unread/muted/spam flags), read, open on the glasses, send (with mentions), react, mark read/spam. */
object BeeperOps : OpProvider {
    private fun AgentCall.chat(): Chat =
        resolve(str("chat"), ui.data.beeper.chats, "chat", "beeper_list_chats", { it.id }, { it.name })

    private fun noChats(ui: io.lampyr.glasses.ui.Ui) = if (ui.data.beeper.chats.isEmpty()) "No Beeper chats are loaded yet" else null

    private fun chatJson(c: Chat) = buildJsonObject {
        put("id", c.id); put("name", c.name); put("network", c.network)
        put("unread", c.unread)
        // counts = what the glasses' unread badge counts (not muted unless @mentioned, not spam, not low priority).
        put("counts", c.countsTowardUnread)
        if (c.markedUnread) put("markedUnread", true)
        if (c.muted) put("muted", true)
        if (c.mentions > 0) put("mentions", c.mentions)
        if (c.spam) put("spam", true)
        if (c.lowPriority) put("lowPriority", true)
        if (c.isGroup || c.type == "group") put("group", true)
        c.last?.let { m -> put("last", buildJsonObject { put("sender", m.sender); put("text", clip(m.text, 200)); putText("at", m.at) }) }
    }

    private fun messageJson(m: Message) = buildJsonObject {
        put("id", m.id); put("sender", m.sender); put("text", clip(m.text, 1_500))
        if (m.mine) put("mine", true)
        putText("at", m.at)
        if (m.reactions.isNotEmpty()) put("reactions", JsonArray(m.reactions.map { r ->
            JsonPrimitive(r.key + (if (r.count > 1) " x${r.count}" else "") + (if (r.mine) " (mine)" else ""))
        }))
        m.replyTo?.let { put("replyTo", "${it.sender}: ${clip(it.text, 120)}") }
        if (m.approval) put("approval", true)
    }

    override fun ops() = listOf(
        OpSpec(
            "beeper.list_chats", "List Beeper chats",
            "The user's Beeper chats, unread first: id, name, network, unread count, counts (on the unread badge), muted, mentions, spam and the last message.",
            params = listOf(
                Param.boolean("unread_only", "Only chats on the unread badge (unread, not muted unless @mentioned, not spam)"),
                Param.boolean("include_spam", "Also list chats marked spam (default false)"),
                Param.integer("limit", "How many (default 15)", min = 1, max = MAX_ITEMS),
            ),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.beeper.refreshChats()
            val chats = ui.data.beeper.chats
                .filter { bool("include_spam") || !it.spam }
                .filter { !bool("unread_only") || it.countsTowardUnread }
                // Unread first (as described), otherwise the repository's most-recent-first order.
                .sortedByDescending { it.countsTowardUnread }
            ok(data = buildJsonObject {
                put("unreadChats", ui.data.beeper.unreadTotal)
                put("chats", JsonArray(chats.take(int("limit", 15)).map(::chatJson)))
                if (chats.size > int("limit", 15)) put("more", chats.size - int("limit", 15))
            })
        },

        OpSpec(
            "beeper.read_chat", "Read a Beeper chat",
            "The latest messages of one chat (id or name), oldest first, with message ids for reply_to and beeper_react. Does not mark it read.",
            params = listOf(
                Param.string("chat", "Chat id or name (see beeper_list_chats)", required = true, maxLength = 200),
                Param.integer("limit", "How many recent messages (default 10)", min = 1, max = MAX_ITEMS),
            ),
            risk = Risk.READ, timeoutMs = 15_000, available = ::noChats,
        ) {
            val chat = chat()
            ui.data.beeper.refreshMessages(chat.id).takeIf { !it.ok && ui.data.beeper.messages(chat.id).isEmpty() }
                ?.let { fail(Codes.FAILED, it.message ?: "Could not load ${chat.name}") }
            val messages = ui.data.beeper.messages(chat.id).takeLast(int("limit", 10))
            ok(data = buildJsonObject {
                put("chat", chatJson(ui.data.beeper.chats.find { it.id == chat.id } ?: chat))
                put("messages", JsonArray(messages.map(::messageJson)))
            })
        },

        OpSpec(
            "beeper.open_chat", "Open a Beeper chat",
            "Show one chat's thread on the glasses (newest message focused), e.g. so the user can read or answer it himself.",
            params = listOf(Param.string("chat", "Chat id or name", required = true, maxLength = 200)),
            risk = Risk.NAVIGATE,
            available = { ui -> lockedReason(ui) ?: noChats(ui) },
        ) {
            val chat = chat()
            ui.openTarget("beeper", chat.id)
            expectTop("the ${chat.name} chat") { it is BeeperThread && it.chatId == chat.id }
            ok("Opened ${chat.name}", registry.context())
        },

        OpSpec(
            "beeper.send_message", "Send Beeper message",
            "Send a text message to one of the user's chats (id or name). reply_to quotes a message id from beeper_read_chat. " +
                "mentions maps an @token in the text to a participant id (from dictation); without ids @Name goes as plain text.",
            params = listOf(
                Param.string("chat", "Chat id or name (see beeper_list_chats)", required = true, maxLength = 200),
                Param.string("text", "Message text, exactly as it should be sent", required = true, maxLength = 4000),
                Param.string("reply_to", "Message id to reply to (optional)", maxLength = 300),
                Param.obj("mentions", "Optional {\"@Token\": participant id} for each @mention in the text"),
            ),
            risk = Risk.SEND, timeoutMs = 15_000, available = ::noChats,
            summary = { call -> "to ${call.strOrNull("chat")}: ${call.strOrNull("text").orEmpty().take(60)}" },
        ) {
            val chat = chat()
            val text = str("text")
            val replyTo = strOrNull("reply_to")?.also { id ->
                if (ui.data.beeper.messages(chat.id).none { it.id == id }) fail(Codes.INVALID_ARGS, "No message $id in ${chat.name}; use beeper_read_chat")
            }
            val mentions = obj("mentions").mapNotNull { (token, id) ->
                val pid = (id as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: fail(Codes.INVALID_ARGS, "mentions[\"$token\"] must be a participant id string")
                if (!token.startsWith("@") || token.length < 2) fail(Codes.INVALID_ARGS, "Mention tokens start with @, e.g. \"@Sam\"")
                if (token !in text) fail(Codes.INVALID_ARGS, "The mention $token is not in the text")
                Mention(token, pid, token.drop(1))
            }
            val result = if (mentions.isEmpty()) ui.data.beeper.send(chat.id, text, replyTo) else ui.data.beeper.send(chat.id, text, replyTo, mentions)
            // A thread already open for this chat follows the new message, as after a dictated send.
            if (result.ok) ui.stack.filterIsInstance<BeeperThread>().find { it.chatId == chat.id }?.let { it.cur = ui.data.beeper.messages(chat.id).lastIndex }
            done(result, "Sent to ${chat.name}")
        },

        OpSpec(
            "beeper.react", "React to a Beeper message",
            "Add (or remove) an emoji reaction on a message (id from beeper_read_chat). On the assistant's approval prompts, ✅ approves and ❌ cancels.",
            params = listOf(
                Param.string("chat", "Chat id or name", required = true, maxLength = 200),
                Param.string("message_id", "Message id from beeper_read_chat", required = true, maxLength = 300),
                Param.string("emoji", "The emoji, e.g. 👍 ❤️ 😂 ✅", required = true, maxLength = 16),
                Param.boolean("remove", "Remove the user's reaction instead"),
            ),
            risk = Risk.SEND, available = ::noChats,
            summary = { call -> "${call.strOrNull("emoji").orEmpty()} in ${call.strOrNull("chat").orEmpty()}" },
        ) {
            val chat = chat()
            val id = str("message_id")
            if (ui.data.beeper.messages(chat.id).none { it.id == id }) {
                ui.data.beeper.refreshMessages(chat.id)
                if (ui.data.beeper.messages(chat.id).none { it.id == id }) fail(Codes.FAILED, "No message $id in ${chat.name}; use beeper_read_chat")
            }
            done(ui.data.beeper.react(chat.id, id, str("emoji"), bool("remove")), if (bool("remove")) "Reaction removed" else "Reacted")
        },

        OpSpec(
            "beeper.mark_read", "Mark a Beeper chat read",
            "Mark one chat read (it leaves the unread badge), as opening it on the glasses does.",
            params = listOf(Param.string("chat", "Chat id or name", required = true, maxLength = 200)),
            risk = Risk.ACT, available = ::noChats,
        ) {
            val chat = chat()
            done(ui.data.beeper.markRead(chat.id), "Marked ${chat.name} read")
        },

        OpSpec(
            "beeper.mark_spam", "Mark a Beeper chat spam",
            "Mark one chat as spam: it stops counting as unread and its notifications stay quiet. Stored in the gateway only; Beeper is not told.",
            params = listOf(Param.string("chat", "Chat id or name", required = true, maxLength = 200)),
            risk = Risk.ACT, tool = false, available = ::noChats,
        ) {
            val chat = chat()
            done(ui.data.beeper.setSpam(chat.id, true), "Marked ${chat.name} spam")
        },

        OpSpec(
            "beeper.mark_not_spam", "Mark a Beeper chat not spam",
            "Undo spam for one chat (also overrides the automatic spam filter for it).",
            params = listOf(Param.string("chat", "Chat id or name", required = true, maxLength = 200)),
            risk = Risk.ACT, tool = false, available = ::noChats,
        ) {
            val chat = chat()
            done(ui.data.beeper.setSpam(chat.id, false), "Marked ${chat.name} not spam")
        },
    )
}
