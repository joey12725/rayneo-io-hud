package io.lampyr.glasses.data

import kotlinx.serialization.json.*

/**
 * A spoken Beeper mention from dictation cleanup: [token] ("@Sam") as it appears in the preview text,
 * [id] the chat participant id the gateway turns into the platform's mention form on send.
 */
data class Mention(val token: String, val id: String, val name: String = "")

object Mentions {
    /** The `mentions` array of a dictation `done` row; malformed entries are dropped. */
    fun parse(row: JsonElement): List<Mention> =
        ((row as? JsonObject)?.get("mentions") as? JsonArray).orEmpty().mapNotNull { m ->
            val token = m.s("token"); val id = m.s("id")
            if (token.startsWith("@") && token.length > 1 && id.isNotEmpty()) Mention(token, id, m.s("name")) else null
        }

    fun json(mentions: List<Mention>): JsonArray = JsonArray(mentions.map { obj("token" to str(it.token), "id" to str(it.id)) })
}
