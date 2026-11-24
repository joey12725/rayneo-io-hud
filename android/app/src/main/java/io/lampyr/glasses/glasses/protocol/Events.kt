// SPDX-License-Identifier: Apache-2.0
package io.lampyr.glasses.glasses.protocol

import kotlinx.serialization.json.*

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

sealed interface GlassesEvent {
    data class PageExit(val business: Int, val sid: String, val reason: Int?) : GlassesEvent
    data class PageReply(val business: Int, val type: Int, val sid: String, val body: JsonObject) : GlassesEvent
    data class Teleprompter(val type: Int, val did: String, val action: Int?, val body: JsonObject, val audio: ByteArray? = null) : GlassesEvent
    data class HeadGesture(val uid: String, val suggestionType: Int, val accepted: Boolean) : GlassesEvent
    data class Wake(val nextRound: Boolean = false) : GlassesEvent
    data object AssistantExit : GlassesEvent
    data class LifeLog(val type: Int, val body: JsonObject, val packets: List<LifeLogPacket>, val sequence: Int? = null) : GlassesEvent
    data class PageShown(val scene: Int?, val index: Int?, val app: String?, val exited: Boolean, val body: JsonObject) : GlassesEvent
    data class Audio(val business: Int, val sid: String, val sequence: Long?, val packet: ByteArray) : GlassesEvent
    data class Status(val battery: Int?, val brightness: Int?, val firmware: String?, val screen: Int? = null) : GlassesEvent
    data class Message(val business: Int, val type: Int, val body: JsonObject) : GlassesEvent
    /** Unsolicited launcher `screen_status` (stock screen woke or slept, e.g. on head-up). Raw value; meanings unverified. */
    data class Screen(val value: Int) : GlassesEvent
    /** Launcher `hall_state` (temple fold sensor). Raw value. */
    data class Hall(val value: Int) : GlassesEvent
    /** 0x0F type 4 reply to request_general_settings: the `generalSettings` object. */
    data class Settings(val body: JsonObject) : GlassesEvent
}
data class LifeLogPacket(val opus: ByteArray, val vad: Boolean, val vpu: Boolean)

object EventParser {
    fun parse(frame: RayneoFrame): GlassesEvent? {
        if (frame.acknowledgement || frame.business in setOf(0x10, 0x11)) return null
        val envelope = Envelope.decode(frame.payload)
        val body = if (envelope.json.isBlank()) obj() else Json.parseToJsonElement(envelope.json) as? JsonObject ?: return null
        val type = envelope.type
        val business = frame.business
        if (business in setOf(0x13, 0x17)) {
            val sid = body.string("sid") ?: return null
            return when (type) {
                3 -> GlassesEvent.PageExit(business, sid, body.int("reason_code"))
                4 -> envelope.audio?.takeIf { it.isNotEmpty() }?.let { GlassesEvent.Audio(business, sid, body.long("seq"), it) }
                else -> GlassesEvent.PageReply(business, type, sid, body)
            }
        }
        if (business == 0x14 && type in setOf(2, 3, 4, 5, 6, 8, 9)) return GlassesEvent.Teleprompter(type, body.string("did").orEmpty(), body.int("action"), body, envelope.audio)
        if (business == 0x15 && type == 34) {
            val cmd = body.int("cmd") ?: return null
            if (cmd in 1..2) return GlassesEvent.HeadGesture(body.string("suggestUID").orEmpty(), body.int("suggestType") ?: 0, cmd == 1)
        }
        if (business == 0x0d) {
            when (type) {
                1, 11 -> return GlassesEvent.Wake(type == 11)
                // Assistant recorder uplink: one 240-byte Opus frame per message, no session id.
                3 -> envelope.audio?.takeIf { it.isNotEmpty() }?.let { return GlassesEvent.Audio(business, "", frame.sequence.toLong(), it) }
                8 -> return GlassesEvent.AssistantExit
                in 161..168 -> {
                    val packets = if (type in setOf(163, 164)) splitLifeLog(body, envelope.audio ?: byteArrayOf()) else emptyList()
                    return GlassesEvent.LifeLog(type, body, packets, frame.sequence)
                }
            }
        }
        if (business == 0x0f) {
            (body["generalStatus"] as? JsonObject)?.let { return GlassesEvent.Status(it.int("battery")?.coerceIn(0, 100), it.int("brightness"), null, it.int("screenStatus")) }
            (body["generalSettings"] as? JsonObject)?.let { return GlassesEvent.Settings(it) }
            when (body.string("cmd")) {
                "screen_status" -> (body["payload"] as? JsonObject)?.int("value")?.let { return GlassesEvent.Screen(it) }
                "hall_state" -> (body["payload"] as? JsonObject)?.int("value")?.let { return GlassesEvent.Hall(it) }
            }
            if (body.string("cmd") == "glass_preview") {
                val data = (body["payload"] as? JsonObject)?.string("data") ?: return null
                val page = Json.parseToJsonElement(data) as? JsonObject ?: return null
                val app = page["app"] as? JsonObject
                return GlassesEvent.PageShown(page.int("scence") ?: page.int("scene"), page.int("index"), app?.string("name"), app?.int("action") == 2, page)
            }
        }
        if (business == 9 && type in setOf(1, 2)) return GlassesEvent.Status(body.int("BatteryLevel")?.coerceIn(0, 100), null, body.string("OsVersion"))
        return GlassesEvent.Message(business, type, body)
    }
    fun splitLifeLog(body: JsonObject, bytes: ByteArray): List<LifeLogPacket> {
        val count = minOf(31, (body.int("frameCount") ?: 0).coerceAtLeast(0), bytes.size / 240)
        val vad = body.long("vadMask") ?: 0
        val vpu = body.long("vpuMask") ?: 0
        return (0 until count).map { LifeLogPacket(bytes.copyOfRange(it * 240, (it + 1) * 240), vad and (1L shl it) != 0L, vpu and (1L shl it) != 0L) }
    }
}

class AudioSequenceWindow {
    private val recent = ArrayDeque<Long>()
    fun accept(sequence: Long?): Boolean {
        // Missing sequence cannot be safely de-duplicated; accept rather than drop real audio.
        if (sequence == null) return true
        if (sequence in recent) return false
        recent.addLast(sequence)
        if (recent.size > 64) recent.removeFirst()
        return true
    }
    fun clear() = recent.clear()
}
