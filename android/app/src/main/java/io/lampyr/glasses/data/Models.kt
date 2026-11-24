package io.lampyr.glasses.data

import kotlin.math.exp
import kotlinx.serialization.Serializable

@Serializable
data class ActionResult(
    val ok: Boolean = true,
    val message: String? = null,
    val id: String? = null,
) {
    companion object {
        fun failure(message: String) = ActionResult(false, message)
    }
}

@Serializable
data class Weather(
    val tempF: Int? = 72,
    val condition: String = "rain after 4 PM",
    val icon: String = "sun",
    /** Temperature in [unit] (the gateway's `units` setting: F or C); older gateways send only [tempF]. */
    val temp: Int? = null,
    val unit: String = "F",
) { val temperatureLabel get() = (if (temp != null) "$temp°$unit" else tempF?.let { "$it°F" }) ?: "—" }

@Serializable
data class HomeSnapshot(val weather: Weather = Weather(), val nextEvent: NextEvent? = null,
    val beeper: HomeBeeper? = null, val t3: HomeT3? = null, val nowPlaying: NowPlaying? = null)

@Serializable data class HomeBeeper(val unread: Int = 0, val names: List<String> = emptyList())
@Serializable data class HomeT3(val needYou: Int = 0, val done: Int = 0)

@Serializable data class NextEvent(val title: String, val start: String, val location: String = "", val allDay: Boolean = false, val timeLabel: String = "", val displaySubtitle: String = "",
    val end: String = "", val startDate: String = "", val endDate: String = "")

data class JoyCallState(val active: Boolean = false, val mode: String = "live", val muted: Boolean = false,
    val endedReason: String? = null,
    /** What the working agent (the main agent on Hermes) is doing for a handoff from this call; null when idle. */
    val worker: JoyWorker? = null,
    /** Monotonic seconds when [worker] last changed (animation start). */
    val workerSince: Double = 0.0)

/**
 * One `agent` event from a Hermes voice bridge that emits agent events (see docs/architecture.md): [state] is
 * started, working, tool, approval, done, error or late; [tool] is the Hermes tool name for `tool`; [task] is a
 * short form of the user's request.
 */
data class JoyWorker(val state: String, val tool: String? = null, val task: String? = null)

@Serializable
data class JoyState(
    val sessionId: String? = null,
    val status: String = "idle",
    /** Avatar state name ([io.lampyr.glasses.render.avatar.AvatarStates]). */
    val anim: String = "waving",
    val since: Double = 0.0,
    val text: String = "",
    val prompt: String? = null,
)

@Serializable data class Reaction(val key: String, val count: Int = 1, val mine: Boolean = false)

@Serializable data class ReplyTo(val sender: String, val text: String)

@Serializable
data class Message(
    val id: String,
    val sender: String,
    val mine: Boolean = false,
    val text: String,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: ReplyTo? = null,
    val approval: Boolean = false,
    val at: String = "",
)

@Serializable
data class Chat(
    val id: String,
    val name: String,
    val network: String,
    val unread: Int = 0,
    val last: Message? = null,
    // Gateway BEEP-01/02 fields (all additive; older gateways omit them).
    val type: String = "single",
    val isGroup: Boolean = false,
    val muted: Boolean = false,
    val mentions: Int = 0,
    val markedUnread: Boolean = false,
    val lowPriority: Boolean = false,
    val spam: Boolean = false,
    val spamCategory: String = "",
    val spamSource: String = "",
    val counts: Boolean? = null,
    val lastActivity: String = "",
    // Gateway BEEP-PRI fields (additive): list tier, importance score and reasons, Beeper pin/favourite, glasses pin.
    val priority: String = "",
    val importance: Double = 0.0,
    val why: List<String> = emptyList(),
    val pinned: Boolean = false,
    val glassesPinned: Boolean = false,
    val participants: ChatPeople = ChatPeople(),
) {
    /** Contributes to the unread badge: unread, not muted (unless @mentioned), not spam, not low priority. */
    val countsTowardUnread: Boolean
        get() = counts ?: ((unread > 0 || markedUnread) && (!muted || mentions > 0) && !spam && !lowPriority)
}

/** The gateway's `participants: {count, names}` summary of a chat (names of up to three other people). */
@Serializable data class ChatPeople(val count: Int = 0, val names: List<String> = emptyList())

@Serializable data class ThreadKey(val machine: String, val id: String)

@Serializable
data class Step(
    val kind: String,
    val what: String,
    val startedAt: String = "",
    val lastOutputAt: String? = null,
    val elapsedSec: Double = 0.0,
    val quietSec: Double? = null,
    val since: Double = 0.0,
    val label: String = kind,
)

@Serializable data class RecentStep(val label: String, val durSec: Int)

@Serializable data class HistoryEntry(val who: String, val text: String, val at: String = "")

@Serializable data class Option(val label: String, val description: String = "")

@Serializable
data class Question(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<Option> = emptyList(),
    val multi: Boolean = false,
    val selected: List<Int> = emptyList(),
)

@Serializable data class Approval(val kind: String, val command: String, val reason: String)

@Serializable
data class Request(
    val id: String,
    val kind: String,
    val questions: List<Question> = emptyList(),
    val approval: Approval? = null,
    val messages: List<HistoryEntry> = emptyList(),
)

@Serializable data class Working(val now: Step? = null, val recent: List<RecentStep> = emptyList())

@Serializable
data class Thread(
    val machine: String,
    val id: String,
    val title: String,
    val project: String,
    val provider: String,
    val status: String,
    val ageSec: Long = 0,
    val updatedAt: String = "",
    val step: Step? = null,
    val machineOnline: Boolean = true,
) {
    val key
        get() = ThreadKey(machine, id)
}

@Serializable data class Latest(val text: String, val at: String = "")

@Serializable
data class ThreadDetail(
    val thread: Thread,
    val latest: Latest? = null,
    val history: List<HistoryEntry> = emptyList(),
    val working: Working? = null,
    val request: Request? = null,
    val runId: String? = null,
)

@Serializable
data class UsageWindow(
    val label: String,
    val usedPercent: Int,
    val resetsAt: String = "",
    val windowDurationMins: Int = 0,
    val pace: String = "on",
    val elapsedFraction: Double = 0.0,
)

@Serializable
data class Credit(
    val creditId: String,
    val label: String,
    val description: String,
    val availableCount: Int,
)

@Serializable
data class Usage(
    val provider: String,
    val machine: String,
    val instanceId: String,
    val windows: List<UsageWindow>,
    val credits: List<Credit> = emptyList(),
)

@Serializable
data class Environment(
    val machine: String,
    val label: String,
    val online: Boolean,
    val os: String = "",
    val lastSeen: String = "",
    val lastSeenLabel: String = "",
    /** False when the assistant's token for the machine lacks orchestration:operate (read-only). */
    val canWrite: Boolean = true,
    /** False when machines.json lists the machine but the gateway has no token for it. */
    val paired: Boolean = true,
    val needsPairing: Boolean = false,
    val needsRepair: Boolean = false,
    /** Why the gateway last failed to reach it ("network error", "timed out", ...). */
    val cause: String = "",
)

@Serializable data class Project(val machine: String, val id: String, val title: String, val machineLabel: String = "")

@Serializable
data class NowPlaying(
    val source: String = "abs",
    val title: String = "",
    val artist: String = "",
    val subtitle: String = "",
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val playing: Boolean = false,
    val device: String = "",
    val volume: Int = 6,
    val speed: Double = 1.5,
    val elapsedLabel: String? = null,
    val durationLabel: String? = null,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val chapter: String,
    val progress: Double,
    val durationSec: Double = 58200.0,
    val currentTimeSec: Double = 0.0,
)

@Serializable data class Playlist(val uri: String, val name: String, val subtitle: String? = null)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val type: String = "",
    val active: Boolean = false,
)

@Serializable
data class Video(val videoId: String, val title: String, val channel: String, val durationSec: Int)

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String,
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val timeLabel: String = "",
    val displaySubtitle: String = "",
    /** Inclusive local dates (YYYY-MM-DD) from the gateway; blank in older payloads and fixtures. */
    val startDate: String = "",
    val endDate: String = "",
)

@Serializable
data class CalendarDay(val day: String, val date: String, val events: List<CalendarEvent>)

@Serializable
data class Person(
    val id: String,
    val name: String,
    val where: String,
    val seenAt: String = "",
    val notes: String,
    val muted: Boolean = false,
)

@Serializable
data class PeopleSnapshot(
    val heard: List<Person> = emptyList(),
    val recent: List<Person> = emptyList(),
)

@Serializable data class Sample(val date: String, val value: Double)

@Serializable
data class Breakdown(val label: String, val value: String? = null, val score: Double? = null,
    val key: String = "", val unit: String = "", val trend: String? = null, val series: List<Sample> = emptyList())

@Serializable
data class Metric(
    val key: String,
    val name: String,
    val value: String = "—",
    val measuredAt: String = "",
    val series: List<Sample>,
    val unit: String = "",
    val breakdown: List<Breakdown> = emptyList(),
    val icon: String = "health",
    val hasBreakdown: Boolean = breakdown.isNotEmpty(),
    val measuredAtRaw: String = "",
    /** wake | day | reading: what measuredAt means, so the label never guesses. */
    val measuredKind: String = "",
    val measuredDay: String = "",
)

@Serializable data class Scene(val id: String, val name: String, val icon: String)

@Serializable data class Alarm(val time: String = "7:00 AM", val style: String = "Gentle")

@Serializable
data class Printer(
    val id: String,
    val name: String,
    val state: String,
    val progress: Double = 0.0,
    val timeLeftSec: Int = 0,
    val layer: Int = 0,
    val layers: Int = 0,
    val detail: String = "",
)

@Serializable
data class HomeAssistantPanel(
    val scenes: List<Scene> = emptyList(),
    val alarm: Alarm = Alarm(),
    val printers: List<Printer> = emptyList(),
)

@Serializable
data class Document(
    val id: String,
    val title: String,
    val text: String = "",
    val createdAt: String = "",
)

@Serializable
data class ReportCard(val type: String, val icon: String, val heading: String, val body: String)

@Serializable
data class ReportSnapshot(
    val date: String = "",
    val generatedAt: String = "",
    val cards: List<ReportCard> = emptyList(),
    val reportDate: String = "",
    val today: String = "",
    val isToday: Boolean = true,
    val stale: Boolean = false,
    val ageDays: Int = 0,
    val fallback: Boolean = false,
)

@Serializable data class OpenTarget(val screen: String, val args: Map<String, String> = emptyMap())

@Serializable
data class Notification(
    val id: String,
    val source: String,
    val icon: String,
    val title: String,
    val body: String,
    val importance: Double,
    val at: Long,
    val open: OpenTarget? = null,
    /** Routing contract: "stream" (list only) or "popup" (list and a card that lights the lens). */
    val route: String = "stream",
    /** Short reason from the policy or the gateway classifier (for agents and traces; not shown on the glasses). */
    val reason: String = "",
) {
    fun score(nowMs: Long) = importance * exp(-((nowMs - at) / 60000.0) / 120)
}

fun sortNotifications(items: List<Notification>, nowMs: Long) =
    items.sortedByDescending { it.score(nowMs) }

@Serializable
data class Memo(val id: String, val title: String, val at: String, val durationSec: Int,
    val transcript: String = "", val filePath: String = "", val createdAt: String = "", val pending: Boolean = false,
    // Upload to the gateway, which transcribes it and delivers it to its configured targets (see data/MemoUploads.kt).
    // upload: "" saved on the phone only, "uploading" with the gateway, "uploaded" (the gateway is delivering it),
    // "delivered" (every delivery settled, at least one succeeded), "failed" given up.
    val startedAt: String = "", val serverId: String? = null, val upload: String = "",
    val audioSent: Boolean = false, val uploadPath: String = "", val attempts: Int = 0, val nextAttemptAt: Long = 0, val restarts: Int = 0,
    /** At least one delivery target has the memo. */
    val delivered: Boolean = false,
    /** Why the upload or a delivery failed (retrying or final), shown in the memo row. */
    val deliveryError: String? = null,
    /** When the last delivery succeeded (gateway time, ISO-8601). */
    val deliveredAt: String? = null,
    /** Names of the targets that have the memo (gateway `deliveries[].name`), for "sent to <name>". */
    val deliveredTo: List<String> = emptyList())

@Serializable
data class MemoSnapshot(
    val recording: Boolean = false,
    val since: Double = 0.0,
    val list: List<Memo> = emptyList(),
    val stoppedReason: String? = null,
)

@Serializable data class Battery(val icon: String, val label: String, val fraction: Double, val level: Double? = fraction.takeIf { it >= 0 })

@Serializable
data class StatusSnapshot(
    val batteries: List<Battery> = emptyList(),
    val wifi: String = "",
    val cell: String = "",
    val link: String = "Glasses link OK",
)

@Serializable
data class SettingsSnapshot(
    val brightness: Int = 8,
    val appOrder: List<String> = DEFAULT_APP_ORDER,
    val headGestures: Boolean = true,
    val displayPosition: String = "Upper",
    val brightnessReadOnly: Boolean = false,
    val glassesBrightness: Int? = null,
)

@Serializable data class ReminderCondition(val type: String, val value: String)
@Serializable data class Reminder(val id: String, val text: String, val condition: ReminderCondition, val expiresAt: String = "")
data class MessagePageState(val loaded: Boolean = false, val loadingOlder: Boolean = false, val nextBefore: String? = null)

/**
 * Launcher order (requirement 16.2): page 1 is the assistant, Beeper, Media, T3 Code, Notifications, Phone, Calendar, Home
 * (Home Assistant). "phone" and "clock" are placeholders until their AppEntry exists; ids without an entry are
 * skipped. Saved custom orders are kept; see appEntries() for how new apps are slotted into them.
 */
val DEFAULT_APP_ORDER =
    listOf(
        "joy",
        "beeper",
        "media",
        "t3",
        "notifications",
        "phone",
        "calendar",
        "ha",
        "people",
        "report",
        "health",
        "status",
        "clock",
        "settings",
        "docs",
        "teleprompter",
        "memo",
    )

/** Earlier defaults. A saved order equal to one of them was never customised (settings saves encode defaults). */
val PREVIOUS_DEFAULT_APP_ORDERS =
    listOf(
        listOf("joy", "beeper", "media", "t3", "calendar", "notifications", "people", "report", "health", "ha",
            "docs", "teleprompter", "memo", "status", "settings"),
    )
