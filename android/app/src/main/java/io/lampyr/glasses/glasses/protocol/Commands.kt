// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Protocol adapted from OpenRayneo Android/Swift and docs/rayneo-protocol.md. Modified for RayNeo iO HUD.
package io.lampyr.glasses.glasses.protocol

import kotlinx.serialization.json.*
import java.security.MessageDigest

internal fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> error("Unsupported JSON value")
}
fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*fields.map { it.first to jsonValue(it.second) }.toTypedArray()))

data class Command(val business: Int, val envelope: Envelope) {
    fun frame(sequence: Int): RayneoFrame {
        // Hard guard at the wire boundary. No firmware flashing is implemented.
        require(business != 9 || envelope.type in setOf(1, 2, 11)) { "Only read-only OTA queries are permitted" }
        return RayneoFrame(business, sequence and 65535, envelope.encode())
    }
}

object Commands {
    private fun command(business: Int, type: Int, body: JsonObject, emptyAudio: Boolean = false, action: Int = 1) =
        Command(business, Envelope(type, body.toString(), if (emptyAudio) byteArrayOf() else null, action))
    fun notification(title: String, body: String, now: Long = System.currentTimeMillis(), appId: String = "io.lampyr.glasses", category: Int = 0) = command(0x15, 2, obj(
        "notificationUID" to now.toString(), "appId" to appId, "appName" to "RayNeo iO HUD",
        "title" to title, "subtitle" to null, "content" to body, "timestamp" to now.toString(),
        "category" to category, "reply" to false, "type" to 1), true)
    fun notificationSettings(enabled: Boolean, blockedApps: List<String> = emptyList(), seconds: Int = 5): Command {
        require(seconds in setOf(5, 10, 15, 20, 30))
        return command(0x15, 17, obj("notification" to enabled, "callNotification" to enabled, "avoidDuplicate" to false,
            "displayTime" to seconds, "intervalTime" to 2, "filterUID" to blockedApps), true)
    }
    fun notificationSwitch(enabled: Boolean) = command(0x15, 18, obj("notification" to enabled), true)
    fun notificationAvailability() = command(0x15, 20, obj(), true)
    fun suggestion(uid: String, title: String, source: String, content: String, todo: Boolean = true, remove: Boolean = false, timeRange: String? = null) =
        command(0x15, 33, obj("type" to if (remove) 2 else 1, "title" to title, "source" to source, "content" to content,
            "suggestUID" to uid, "suggestType" to if (todo) 2 else 1, "timeRange" to timeRange), true)
    fun suggestionAck(uid: String, type: Int) = command(0x15, 35, obj("suggestUID" to uid, "suggestType" to type, "code" to 0), true)

    fun pageStart(sid: String, prompt: Boolean = false) = command(if (prompt) 0x17 else 0x13, 1, obj(
        "sid" to sid, "trigger" to 1, "force" to false, "code" to 0, "settings" to obj(
            "mode" to if (prompt) "conversation" else "translation", "source_language" to "zh-CN", "target_language" to "zh-CN",
            "save_audio" to false, "direction" to "ahead")))
    fun captionLayout(sid: String) = command(0x13, 7, obj("sid" to sid, "force" to false, "scope" to "temporary", "config" to obj(
        "font_size" to 1, "content_width" to 100, "max_lines" to 7, "position" to "center", "is_display" to true, "straight_view" to "original")))
    fun captionText(sid: String, text: String) = command(0x13, 5, obj("sid" to sid, "mode" to 3, "status" to 1, "content" to obj("source_transcript" to text)))
    fun promptText(sid: String, question: String, answer: String, final: Boolean = true) = command(0x17, 5, obj(
        "sid" to sid, "mode" to 4, "status" to if (final) 1 else 0, "content" to obj("source_transcript" to question,
            "target_translation" to answer, "keyword_info" to null, "label" to 0)))
    fun pageClose(sid: String, prompt: Boolean = false) = command(if (prompt) 0x17 else 0x13, 3, obj("sid" to sid, "reason_code" to if (prompt) 2 else 10, "text" to ""))

    fun teleprompterSettings(did: String, script: String) = obj("action" to 1, "countdown" to 3, "scroll" to 1,
        "speed" to 120, "gear" to 1, "depth" to 1, "size" to 18, "width" to 492, "leading" to 4,
        "total" to script.length, "did" to did, "pageOffset" to 0, "highLightOffset" to 0)
    fun teleprompterPrepare(did: String, script: String) = command(0x14, 2, teleprompterSettings(did, script), true)
    fun teleprompterList(did: String, title: String, script: String, timestampSeconds: Long, timezoneSeconds: Int, scripts: Int = 1) = command(0x14, 1, obj(
        "action" to 2, "type" to 3, "total" to scripts + 2,
        "chunkinfo" to obj("chunkid" to 1, "total_items" to 1, "islast" to true), "items" to listOf(obj(
            "did" to did, "pin" to false, "title" to title, "offset" to 0, "scroll" to 1, "total" to script.length,
            "timestamp" to timestampSeconds, "preview" to script.take(80), "timezone" to timezoneSeconds))), true, 2)
    fun teleprompterStart(did: String, script: String) = command(0x14, 3,
        JsonObject(teleprompterSettings(did, script) + obj("code" to 1, "checksum" to fnv1a(script.toByteArray()))), true)
    fun teleprompterControl(type: Int, did: String, offset: Int = 0, speed: Int = 120, highlight: Int = 0, autoSync: Boolean? = null): Command {
        require(type in setOf(4, 5, 6, 7, 8))
        val extra = when (type) {
            4 -> obj("offset" to offset, "code" to 1, "isCompleted" to false)
            7 -> { require(speed in 60..240); obj("scroll" to 2, "speed" to speed) }
            8 -> obj("pageOffset" to offset, "highLightOffset" to highlight, "autoSync" to autoSync)
            else -> obj()
        }
        return command(0x14, type, JsonObject(obj("action" to 1, "did" to did) + extra), true)
    }
    fun teleprompterAck(type: Int, did: String, current: Boolean = true): Command {
        require(type in setOf(4, 5, 6, 8))
        return command(0x14, type, obj("action" to 2, "did" to did, "code" to if (current) 1 else 2), true, 2)
    }
    fun fnv1a(bytes: ByteArray): String {
        var hash = 0x811c9dc5u
        bytes.forEach { hash = (hash xor (it.toUInt() and 255u)) * 0x01000193u }
        return hash.toString(16).padStart(8, '0')
    }

    fun todoQuery() = command(0x16, 15, obj("queryType" to 0, "eventType" to 1, "lastSyncTime" to 0, "eventIDList" to emptyList<String>(), "needFullData" to true), true)
    /** Caller must query and preserve existing items; this command replaces the entire list. */
    fun todoReplace(items: List<JsonObject>) = command(0x16, 6, obj("total" to items.size, "isLastBatch" to true, "eventList" to items), true)
    fun todoItem(id: Long, title: String, createdMs: Long, modifiedSeconds: Long, important: Boolean = false, done: Boolean = false) = obj(
        "eventType" to 1, "eventID" to id, "createTime" to createdMs, "title" to title, "isImportant" to important,
        "status" to if (done) 1 else 0, "lastModifiedTime" to modifiedSeconds)
    fun todoUpdate(item: JsonObject) = command(0x16, 2, item, true)
    fun launcher(cmd: String, type: Int, value: Int = 0, mode: Int = 0, data: String = "", timestamp: String? = null) = command(0x0f, type,
        obj("cmd" to cmd, "payload" to JsonObject(obj("value" to value, "mode" to mode, "data" to data) +
            (timestamp?.let { obj("ts" to it) } ?: obj()))), true)
    fun currentWeather(location: String, icon: Int, temperature: Int, seconds: Long) = launcher("current_weather_update", 18,
        data = obj("location" to location, "icon" to icon, "temp" to temperature).toString(), timestamp = seconds.toString())
    /** Ordered pairs deliberately preserve hourly display order, per §4.6. */
    fun weatherCity(location: String, id: String, temperature: Int, icon: Int, description: String, range: String, hourly: List<Pair<String, Pair<Int, Int>>>) = obj(
        "location" to location, "location_id" to id, "temp" to temperature, "icon" to icon, "des" to description,
        "temp_range" to range, "hourly" to JsonObject(linkedMapOf(*hourly.map { (label, values) -> label to jsonValue(listOf(values.first, values.second)) }.toTypedArray())))
    fun weatherCities(cities: List<JsonObject>, seconds: Long) = launcher("weather_update", 18, data = JsonArray(cities).toString(), timestamp = seconds.toString())
    fun dashboard() = launcher("dashboard_config", 18, timestamp = "0")
    fun generalStatus() = launcher("request_general_status", 1)
    fun generalSettings() = launcher("request_general_settings", 4)
    fun firmwareVersion() = Command(9, Envelope(1))
    fun firmwareBattery() = Command(9, Envelope(2))
    fun firmwareValidation() = Command(9, Envelope(11))
    fun lifeLogSwitch(enabled: Boolean) = launcher("life_log_switch", 20, value = if (enabled) 1 else 0)
    fun lifeLogStart(task: String) = command(0x0d, 162, obj("taskId" to task, "idleTimeoutSec" to 30))
    fun lifeLogPage(inPage: Boolean = false) = command(0x0d, 168, obj("inRealtimePage" to inPage))
    fun lifeLogStop() = command(0x0d, 166, obj("rc" to 1))
    /** Phone-started glasses microphone: 0x0D type 3 Opus frames flow until rc 2. No page is opened. */
    fun assistantRecorder(on: Boolean) = command(0x0d, 2, obj("rc" to if (on) 1 else 2))
    /** A7 schema is not verified on hardware. Only used by the explicit diagnostic flag. */
    fun lifeLogCache() = command(0x0d, 167, obj())

    // ---- Stock settings writes (0x0F type 5). Shapes from aixleo GlassesSettingWrite / Turbo CompanionDeviceFeatures. ----
    // Each replaces a whole stock structure: callers must read request_general_settings first and resend every field.
    val AUTO_LOCK_SECONDS = listOf(5, 10, 15, 25, 40, 60, 120)
    val DISPLAY_HEIGHTS = listOf(1, 3, 5)
    val DISPLAY_DISTANCES = listOf(1, 2)
    /** Stock look-up wake ("抬头亮屏"): switch, angle 0..90 and the crown screen-off mode 0..3, as a JSON *string* in data. */
    fun wakeupConfig(headUp: Boolean, degrees: Int, crownSwitch: Int): Command {
        require(degrees in 0..90) { "Head-up angle must be 0..90" }
        require(crownSwitch in 0..3) { "crown_switch must be 0..3" }
        return launcher("wakeup_config", 5, data = obj("headup_switch" to if (headUp) 1 else 0, "headup_degree" to degrees, "crown_switch" to crownSwitch).toString())
    }
    /** Stock auto sleep. The official values only; mode and data are explicit JSON nulls, not omitted. */
    fun autoLockTime(seconds: Int): Command {
        require(seconds in AUTO_LOCK_SECONDS) { "auto_lock_time must be one of $AUTO_LOCK_SECONDS" }
        return command(0x0f, 5, obj("cmd" to "auto_lock_time", "payload" to obj("value" to seconds, "mode" to null, "data" to null)), true)
    }
    /** Stock nod/shake: enabled rides in value and the direction in mode; data stays empty. */
    fun headGestures(enabled: Boolean, mode: Int): Command {
        require(mode in 0..1) { "head_gestures mode must be 0 or 1" }
        return launcher("head_gestures", 5, value = if (enabled) 1 else 0, mode = mode)
    }
    /** Stock display position: height 1/3/5 and distance 1/2 (unit and direction unverified on hardware). */
    fun displayConfig(height: Int, distance: Int): Command {
        require(height in DISPLAY_HEIGHTS) { "display height must be one of $DISPLAY_HEIGHTS" }
        require(distance in DISPLAY_DISTANCES) { "display distance must be one of $DISPLAY_DISTANCES" }
        return launcher("display_config", 5, data = obj("height" to height, "distance" to distance).toString())
    }
}

/** Pure request-driven teleprompter transfer. UTF-8 bytes, offsets; metadata total remains UTF-16. */
class ScriptTransfer(val taskId: String, val did: String, script: String) {
    private val bytes = script.toByteArray(Charsets.UTF_8)
    fun descriptor(): RayneoFrame {
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val body = Proto.Writer().text(1, taskId).text(2, did).int(3, bytes.size.toLong()).text(4, md5).int(5, 17).build()
        return RayneoFrame(0x11, 0, Proto.Writer().bytes(1, body).build())
    }
    sealed interface Reply {
        data class Chunk(val frame: RayneoFrame) : Reply
        data class Complete(val result: Long) : Reply
    }
    fun receive(payload: ByteArray): Reply? {
        for (field in Proto.read(payload)) {
            if (field.number !in setOf(3, 5) || field.bytes == null) continue
            val inner = Proto.read(field.bytes).associateBy { it.number }
            if (inner[1]?.bytes?.toString(Charsets.UTF_8) != taskId) continue
            if (field.number == 5) return Reply.Complete(inner[2]?.integer ?: 0)
            val number = inner[2]?.integer ?: error("Missing chunk number")
            val offset = inner[3]?.integer ?: 0
            val requested = inner[4]?.integer ?: error("Missing chunk size")
            require(offset in 0..bytes.size.toLong() && requested in 1..65500L)
            // Response metadata shares the frame's 65524-byte payload budget.
            val count = minOf(requested.toInt(), bytes.size - offset.toInt(), (65400 - taskId.toByteArray().size).coerceAtLeast(0))
            val chunk = Proto.Writer().text(1, taskId).int(2, number).int(3, offset).int(4, count.toLong())
                .bytes(5, bytes.copyOfRange(offset.toInt(), offset.toInt() + count)).build()
            return Reply.Chunk(RayneoFrame(0x11, 0, Proto.Writer().bytes(4, chunk).build()))
        }
        return null
    }
}
