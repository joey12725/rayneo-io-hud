package io.lampyr.glasses.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

/** Wire adaptation stays here; models remain the immutable renderer's contract. */
object GatewayMappings {
    inline fun <reified T> decode(value: JsonElement): T = gatewayJson.decodeFromJsonElement(normalize(value))
    fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::normalize))
        is JsonObject -> JsonObject(value.filter { (key, v) -> v != JsonNull || key == "tempF" }.mapValues { (key, v) ->
            when {
                key == "value" && v is JsonPrimitive -> JsonPrimitive(v.content)
                key in setOf("ageSec", "durSec", "usedPercent", "tempF", "temp", "windowDurationMins", "timeLeftSec") && v is JsonPrimitive && v.doubleOrNull != null -> JsonPrimitive(v.double.toLong())
                else -> normalize(v)
            }
        })
        else -> value
    }
    fun home(value: JsonElement): HomeSnapshot = decode<HomeSnapshot>(if (value.jsonObject["weather"] == null || value.jsonObject["weather"] == JsonNull)
        JsonObject(value.jsonObject + ("weather" to obj("tempF" to JsonNull, "condition" to str("Weather unavailable"), "icon" to str("cloud")))) else value).let { home ->
            home.copy(nextEvent = home.nextEvent?.let { e -> val time = calendarTime(e.start, e.allDay); e.copy(timeLabel = time, displaySubtitle = listOf(time, e.location).filter { it.isNotBlank() }.joinToString(" · ")) })
        }
    private val matrixId = Regex("@[^:\\s<>]+:(?:[\\w-]+\\.)+[\\w-]+")
    // Compiled once: safeName runs for every name on every Home render.
    private val digitsOnly = Regex("^\\d+$")
    private val roomId = Regex("^!?[\\w-]{12,}:(?:[\\w-]+\\.)+[\\w-]+$")
    fun idLike(name: String) = name.isBlank() || digitsOnly.matches(name.trim()) || matrixId.containsMatchIn(name) || roomId.matches(name)
    fun safeName(name: String, fallback: String = "Someone", allowDigits: Boolean = false) =
        if (allowDigits && digitsOnly.matches(name.trim())) name else if (idLike(name)) fallback else name
    fun phoneNumberNetwork(network: String) = network.lowercase(Locale.ROOT).replace(" ", "") in
        setOf("sms", "googlemessages", "imessage", "whatsapp", "signal", "telegram")
    fun scrubIds(text: String) = text.replace(matrixId, "@someone")
    fun message(m: Message) = m.copy(sender = safeName(m.sender), text = scrubIds(m.text), replyTo = m.replyTo?.let { it.copy(sender = safeName(it.sender), text = scrubIds(it.text)) })
    // One malformed row is skipped instead of blanking the whole list.
    fun chats(value: JsonElement) = (value as JsonArray).mapNotNull { c -> runCatching {
        val preview = c.jsonObject["last"] as? JsonObject
        // Optional BEEP-PRI shapes: a malformed participants summary or reasons list must not drop the whole row.
        decode<Chat>(JsonObject(c.jsonObject + ("last" to (preview?.let { JsonObject(it + ("id" to JsonPrimitive("preview-${c.s("id")}"))) } ?: JsonNull)) +
            ("participants" to ((c.jsonObject["participants"] as? JsonObject)?.let { p -> JsonObject(p.filterKeys { it == "count" || it == "names" }.filter { (k, v) -> if (k == "names") v is JsonArray && v.all { (it as? JsonPrimitive)?.isString == true } else v is JsonPrimitive }) } ?: JsonNull)) +
            ("why" to ((c.jsonObject["why"] as? JsonArray)?.let { a -> JsonArray(a.filter { (it as? JsonPrimitive)?.isString == true }) } ?: JsonNull)))).let {
            val phoneName = phoneNumberNetwork(it.network) && !c.b("isGroup") && !c.b("isGroupConversation") && c.s("type") != "group"
            it.copy(name = safeName(it.name, "Chat", phoneName), last = it.last?.let(::message))
        }
    }.getOrNull() }
    fun step(s: Step?, epochMs: Long, monotonic: Double): Step? = s?.copy(
        elapsedSec = elapsed(s.startedAt, epochMs), quietSec = s.lastOutputAt?.let { elapsed(it, epochMs) }, since = monotonic,
    )
    fun elapsed(at: String, nowMs: Long) = runCatching { ((nowMs - Instant.parse(at).toEpochMilli()) / 1000.0).coerceAtLeast(0.0) }.getOrDefault(0.0)
    fun thread(value: JsonElement, epochMs: Long, monotonic: Double): Thread = decode<Thread>(value).let { it.copy(step = step(it.step, epochMs, monotonic)) }
    fun detail(value: JsonElement, epochMs: Long, monotonic: Double): ThreadDetail = decode<ThreadDetail>(value).let {
        it.copy(thread = it.thread.copy(step = step(it.thread.step, epochMs, monotonic)), working = it.working?.copy(now = step(it.working.now, epochMs, monotonic)))
    }
    fun joyStatus(status: String) = status.takeIf { it in setOf("idle", "listening", "thinking", "working", "searching", "remembering", "saving", "speaking", "waiting", "delegating", "error") } ?: "thinking"
    fun notifyJoy(screen: String) = screen !in setOf("joy", "call")
    fun sendMode(status: String?, requested: String) = if (status in setOf("working", "waiting")) { if (requested == "steer") "steer" else "queue" } else "send"
    fun approvalToast(approval: Boolean, key: String, removed: Boolean) =
        if (approval && !removed && key in setOf("✅", "❌")) "${if (key == "✅") "Approved" else "Cancelled"} · ${io.lampyr.glasses.settings.Names.assistant} notified" else if (removed) "Reaction removed" else "Reacted"

    fun measuredAt(at: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        val time = Instant.parse(at).atZone(zone); val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        when (time.toLocalDate()) { today -> "$clock today"; today.minusDays(1) -> "Yesterday $clock"; else -> time.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) }
    }.getOrDefault("")
    fun metrics(value: JsonElement, nowMs: Long): List<Metric> = decode<List<Metric>>(value).map { m ->
        m.copy(value = if (m.value == "—" || m.value.isBlank()) "—" else listOf(m.value, m.unit).filter { it.isNotBlank() }.joinToString(" "),
            measuredAtRaw = m.measuredAt, measuredAt = measuredLabel(m.measuredAt, m.measuredKind, m.measuredDay, nowMs), hasBreakdown = m.breakdown.isNotEmpty())
    }
    fun calendarTime(start: String, allDay: Boolean): String = if (allDay) "All day" else runCatching {
        Instant.parse(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }.getOrDefault("")
    fun agenda(value: JsonElement) = decode<List<CalendarDay>>(value).map { day -> day.copy(events = day.events.map { e ->
        val time = calendarTime(e.start, e.allDay)
        e.copy(timeLabel = time, displaySubtitle = listOf(time, e.location).filter { it.isNotBlank() }.joinToString(" · "))
    }) }
    fun interpolate(now: NowPlaying, elapsedSec: Double): NowPlaying {
        val position = now.positionSec + if (now.playing) elapsedSec.coerceAtLeast(0.0) else 0.0
        return now.copy(positionSec = position.coerceIn(0.0, now.durationSec.takeIf { it > 0 } ?: Double.MAX_VALUE),
            playing = now.playing && (now.durationSec <= 0 || position < now.durationSec))
    }

    /** Health labels by meaning: Oura's daily documents are dated, not timed, so they never show a clock time they do not have. */
    fun measuredLabel(at: String, kind: String, day: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (kind) {
            "wake" -> runCatching {
                val time = Instant.parse(at).atZone(zone)
                val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                when (time.toLocalDate()) {
                    today -> "Woke $clock"
                    today.minusDays(1) -> "Woke yesterday $clock"
                    else -> "Night of " + time.toLocalDate().minusDays(1).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
                }
            }.getOrDefault("")
            "day" -> when (val date = runCatching { LocalDate.parse(day) }.getOrNull()) {
                null -> ""
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
            }
            else -> measuredAt(at, nowMs, zone)
        }
    }

    /** A day relative to today: Today, Tomorrow, Yesterday, a weekday within the week, else Nov 26. */
    fun dayName(date: LocalDate, today: LocalDate): String = when (val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (days in 2..6) "EEE" else "MMM d", Locale.US))
    }

    /**
     * Day-aware event label, computed at draw time so a cached payload stays right after midnight:
     * "7:00 PM" (today), "Tomorrow 7:00 PM", "Thu 7:00 PM", "All day" (covers today), "Thu · All day".
     * All-day events use the gateway's local dates, never an instant, so another phone zone cannot shift them.
     * Null when the payload has no parseable time (fixtures carry display strings).
     */
    fun eventWhen(start: String, allDay: Boolean, startDate: String, endDate: String, today: LocalDate, zone: ZoneId): String? {
        val instant = runCatching { Instant.parse(start) }.getOrNull()
        if (allDay) {
            val from = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: instant?.atZone(zone)?.toLocalDate() ?: return null
            val to = runCatching { LocalDate.parse(endDate) }.getOrNull()?.takeIf { !it.isBefore(from) } ?: from
            return if (!from.isAfter(today) && !to.isBefore(today)) "All day" else dayName(from, today) + " · All day"
        }
        val time = instant?.atZone(zone) ?: return null
        val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return if (time.toLocalDate() == today) clock else dayName(time.toLocalDate(), today) + " " + clock
    }
}
