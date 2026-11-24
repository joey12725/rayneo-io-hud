package io.lampyr.glasses.clock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * EXPERIMENTAL: the user's existing Google Clock alarms, read and switched on/off from the glasses.
 *
 * Android has no API for another app's alarms (AlarmClock intents only create, dismiss and snooze; Google Clock's
 * provider answers "No external queries"; AlarmManager only gives the next one). So this drives Google Clock's own
 * screen through the app's accessibility service: open the Alarms tab (ACTION_SHOW_ALARMS), read each row (time,
 * label, days, switch) from the accessibility tree, click the chosen row's switch, re-read to verify, then return to
 * the previous app. Pure Kotlin over [UiNode] and [PhoneAlarmHost] so it is unit-tested with a fake tree.
 */
@Serializable
data class PhoneAlarm(
    /** Local time, 24 h. */
    val hour: Int,
    val minute: Int,
    val label: String = "",
    /** Google Clock's own repeat text ("Mon, Tue, Wed", "Every day", "Tomorrow", "Not scheduled"). */
    val days: String = "",
    val enabled: Boolean = false,
) {
    val time get() = fmtTime(hour, minute)
    /** Identity used for matching: time + label (the days text changes with the on/off state). */
    fun sameAs(other: PhoneAlarm) = hour == other.hour && minute == other.minute && norm(label) == norm(other.label)
    companion object { fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase() }
}

@Serializable
data class PhoneAlarmCache(val alarms: List<PhoneAlarm> = emptyList(), val readAtMs: Long = 0)

/** One accessibility node (AccessibilityNodeInfo on the phone, a fake tree in tests). */
interface UiNode {
    val text: String?
    val desc: String?
    val className: String?
    val viewId: String?
    val packageName: String?
    val checkable: Boolean
    val checked: Boolean
    val clickable: Boolean
    val scrollable: Boolean
    /** Has CollectionInfo (RecyclerView / LazyColumn): the alarm list itself, never part of one row. */
    val collection: Boolean
    val children: List<UiNode>
    fun click(): Boolean
    fun scrollForward(): Boolean
    fun scrollBackward(): Boolean
}

/** What the automation needs from Android. */
interface PhoneAlarmHost {
    /** Null when Google Clock can be driven now; otherwise why not (service off, phone locked, screen off). */
    fun blocker(): String?
    /** Package of the app in front, to return to afterwards. */
    fun foreground(): String?
    /** Starts Google Clock on its Alarms tab. */
    fun openAlarms(): Boolean
    /** Root of Google Clock's window when it is showing, else null. */
    fun clockRoot(): UiNode?
    /** Leaves Google Clock for [previous] (back, then relaunch or home). */
    fun returnTo(previous: String?)
    suspend fun pause(ms: Long)
    fun elapsedMs(): Long

    companion object {
        val NONE = object : PhoneAlarmHost {
            override fun blocker() = "Phone alarms need the RayNeo Companion app on the phone"
            override fun foreground(): String? = null
            override fun openAlarms() = false
            override fun clockRoot(): UiNode? = null
            override fun returnTo(previous: String?) {}
            override suspend fun pause(ms: Long) {}
            override fun elapsedMs() = 0L
        }
    }
}

data class PhoneAlarmOutcome(
    val ok: Boolean,
    val message: String,
    /** The full list (a read, or a toggle that had to scan everything); null when not read. */
    val alarms: List<PhoneAlarm>? = null,
    /** The toggled alarm in its new state. */
    val changed: PhoneAlarm? = null,
)

/** One parsed row: the alarm and the switch to click (only the switch: tapping the row would expand or edit it). */
class PhoneAlarmRow(val alarm: PhoneAlarm, val switch: UiNode)

/** Reads Google Clock's alarm rows out of an accessibility tree. */
object PhoneAlarmParser {
    private val TIME = Regex("^(\\d{1,2})[:.](\\d{2})\\s*(?:([AaPp])\\.?\\s*[Mm]\\.?)?$")
    private val AMPM = Regex("^([AaPp])\\.?\\s*[Mm]\\.?$")
    private val DAYS = Regex("(?i)\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b|every ?day|daily|weekdays|weekends|tomorrow|today|not scheduled|^once$")
    private val CHROME = Regex("(?i)^(alarms?|clock|timers?|stopwatch|bedtime|add label|label|delete|vibrate|default \\(.*|ringtone.*|silent|google assistant.*|schedule.*|expand.*|collapse.*|sunrise.*|done|cancel|ok|repeat|add alarm|more options|settings|alarm in .*|in \\d.*|snooz.*|skip.*|dismiss.*|upcoming.*|.*hours? and .*|.*minutes? from now.*)$")

    fun normalize(s: String?) = s?.replace(' ', ' ')?.replace(' ', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /** Google Clock's on/off switch (not the day chips or the Vibrate checkbox of an expanded row). */
    fun isOnOff(n: UiNode): Boolean {
        if (!n.checkable) return false
        val id = n.viewId.orEmpty()
        if (id.endsWith("/onoff") || id.endsWith(":id/onoff")) return true
        val cls = n.className.orEmpty()
        return cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("SwitchMaterial")
    }

    /** (hour 0..23, minute) from "7:00 AM", "7:00 am", "19:45"; [ampm] is a separate "AM"/"PM" node. */
    fun time(s: String?, ampm: String? = null): Pair<Int, Int>? {
        val m = TIME.matchEntire(normalize(s)) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        val mer = m.groupValues[3].ifEmpty { ampm?.let { AMPM.matchEntire(normalize(it))?.groupValues?.get(1) }.orEmpty() }.lowercase()
        if (min > 59) return null
        when (mer) {
            "a" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            "p" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            else -> if (h > 23) return null
        }
        return h to min
    }

    private fun walk(n: UiNode, out: MutableList<UiNode>) { out += n; n.children.forEach { walk(it, out) } }
    private fun flat(n: UiNode) = mutableListOf<UiNode>().also { walk(n, it) }
    private fun switches(n: UiNode) = flat(n).count(::isOnOff)

    fun rows(root: UiNode): List<PhoneAlarmRow> {
        val rows = mutableListOf<PhoneAlarmRow>()
        collect(root, rows)
        return rows
    }

    /** A row is the highest subtree holding exactly one on/off switch and a time, and not (inside it) the list. */
    private fun collect(n: UiNode, out: MutableList<PhoneAlarmRow>) {
        val count = switches(n)
        if (count == 0) return
        if (count == 1 && flat(n).none { it.collection || it.scrollable } && hasTime(n)) { row(n)?.let { out += it }; return }
        if (count == 1 && n.children.isEmpty()) return
        n.children.forEach { collect(it, out) }
    }

    private fun hasTime(n: UiNode) = flat(n).any { time(it.text) != null || time(it.desc) != null }

    private fun row(n: UiNode): PhoneAlarmRow? {
        val nodes = flat(n)
        val sw = nodes.first(::isOnOff)
        val texts = nodes.filter { !it.checkable }.map { it to normalize(it.text) }.filter { it.second.isNotEmpty() }
        val ampm = texts.firstOrNull { AMPM.matches(it.second) }?.second
        val timeNode = texts.firstOrNull { time(it.second) != null }
        val hm = timeNode?.let { time(it.second, ampm) }
            ?: nodes.firstNotNullOfOrNull { node -> normalize(node.desc).let { d -> Regex("\\d{1,2}[:.]\\d{2}(\\s*[AaPp]\\.?\\s*[Mm]\\.?)?").find(d)?.value?.let { time(it) } } }
            ?: return null
        val rest = texts.filter { it !== timeNode && !AMPM.matches(it.second) && time(it.second) == null }
        val labelById = rest.firstOrNull { (node, t) -> node.viewId.orEmpty().let { id -> id.endsWith("/label") || id.endsWith("/edit_label") } && !CHROME.matches(t) }
        val daysNode = rest.firstOrNull { it.first.viewId.orEmpty().let { id -> "days" in id || "repeat" in id } }
            ?: rest.firstOrNull { it !== labelById && it.second.length > 2 && DAYS.containsMatchIn(it.second) }
        val label = labelById ?: rest.firstOrNull { it !== daysNode && it.second.length <= 60 && !CHROME.matches(it.second) && !DAYS.matches(it.second) }
        return PhoneAlarmRow(PhoneAlarm(hm.first, hm.second, label?.second.orEmpty(), daysNode?.second.orEmpty(), sw.checked), sw)
    }

    /**
     * Google Clock's Alarms tab is showing (a row, or its "Add alarm" button). Not any FAB: the Timer and
     * Stopwatch tabs have one too, and while Clock switches tabs an empty read there would wipe the cached list.
     */
    fun alarmsTabReady(root: UiNode): Boolean = rows(root).isNotEmpty() || flat(root).any { node ->
        listOf(node.desc, node.text).any { normalize(it).equals("Add alarm", ignoreCase = true) }
    }

    /**
     * The scrollable alarm list: the innermost scrollable node holding the most switches. Never an outer
     * scrollable around it (a tab ViewPager: scrolling that would swipe to the Clock / Timer tabs), and none
     * when no alarm switch is showing.
     */
    fun list(root: UiNode): UiNode? = flat(root).filter { it.scrollable }.map { it to switches(it) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<UiNode, Int>> { it.second }.thenByDescending { it.first.collection }.thenBy { flat(it.first).size })
        .firstOrNull()?.first
}

/** Matching a wanted alarm against what Google Clock shows. */
object PhoneAlarmMatch {
    /** Exact time + label; else, when exactly one alarm has that time and the label was not given, that one. */
    fun find(alarms: List<PhoneAlarm>, hour: Int, minute: Int, label: String?): List<PhoneAlarm> {
        val atTime = alarms.filter { it.hour == hour && it.minute == minute }
        if (label.isNullOrBlank()) return atTime.distinctBy { PhoneAlarm.norm(it.label) to it.enabled }.let { d -> if (d.map { PhoneAlarm.norm(it.label) }.distinct().size <= 1) atTime.take(1) else atTime }
        val want = PhoneAlarm.norm(label)
        atTime.filter { PhoneAlarm.norm(it.label) == want }.let { if (it.isNotEmpty()) return it.take(1) }
        return atTime.filter { PhoneAlarm.norm(it.label).contains(want) || want.contains(PhoneAlarm.norm(it.label).ifEmpty { "\u0000" }) }.take(1)
    }
}

/** Drives Google Clock. Every call returns to the previous app, whatever happens. */
class PhoneAlarmAutomation(
    private val host: PhoneAlarmHost,
    private val openTimeoutMs: Long = 7_000,
    private val verifyTimeoutMs: Long = 3_000,
    private val settleMs: Long = 400,
    private val maxScrolls: Int = 40,
) {
    private class Abort(val text: String) : Exception(text)

    suspend fun read(): PhoneAlarmOutcome = session {
        val (all, _) = scan()
        PhoneAlarmOutcome(true, if (all.isEmpty()) "No alarms in Google Clock" else "Read ${all.size} phone alarm${if (all.size == 1) "" else "s"}", all)
    }

    suspend fun toggle(target: PhoneAlarm, enabled: Boolean): PhoneAlarmOutcome = session {
        val what = target.time + target.label.ifBlank { null }?.let { " \"$it\"" }.orEmpty()
        val (all, hit) = scan { it.alarm.sameAs(target) && it.alarm.enabled != enabled }
        if (hit == null) {
            // Scanned everything: either it is already in that state or it is gone.
            return@session if (all.any { it.sameAs(target) }) PhoneAlarmOutcome(true, "Phone alarm $what is already ${onOff(enabled)}", all, target.copy(enabled = enabled))
            else PhoneAlarmOutcome(false, "No phone alarm $what in Google Clock now; read the list again", all)
        }
        val before = countState(target, enabled)
        if (!hit.switch.click()) throw Abort("Google Clock did not accept the switch for $what")
        val deadline = host.elapsedMs() + verifyTimeoutMs
        while (true) {
            host.pause(200)
            if (countState(target, enabled) > before) break
            if (host.elapsedMs() >= deadline) throw Abort("Switched $what but Google Clock still shows it ${onOff(!enabled)}; check the phone")
        }
        PhoneAlarmOutcome(true, "Phone alarm $what ${onOff(enabled)}", null, hit.alarm.copy(enabled = enabled))
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    /** Rows matching [target] with state [enabled] in the current viewport. */
    private fun countState(target: PhoneAlarm, enabled: Boolean): Int =
        host.clockRoot()?.let { r -> PhoneAlarmParser.rows(r).count { it.alarm.sameAs(target) && it.alarm.enabled == enabled } } ?: 0

    /**
     * Scrolls to the top, then down, collecting every row in order. With [stopAt], stops at the first row it
     * accepts and returns it (still on screen, so its switch can be clicked).
     */
    private suspend fun scan(stopAt: ((PhoneAlarmRow) -> Boolean)? = null): Pair<List<PhoneAlarm>, PhoneAlarmRow?> {
        var guard = 0
        while (guard++ < maxScrolls && PhoneAlarmParser.list(root())?.scrollBackward() == true) host.pause(settleMs / 2)
        val seen = mutableListOf<PhoneAlarm>()
        var stale = 0
        for (i in 0..maxScrolls) {
            val rows = PhoneAlarmParser.rows(root())
            rows.firstOrNull { stopAt?.invoke(it) == true }?.let { return seen to it }
            // Viewports overlap after a scroll: add a row only when this viewport shows more copies than seen so far.
            var fresh = 0
            for (a in rows.map { it.alarm }.distinct()) {
                val extra = rows.count { it.alarm == a } - seen.count { it == a }
                repeat(extra.coerceAtLeast(0)) { seen += a; fresh++ }
            }
            if (fresh == 0 && i > 0) { if (++stale >= 2) break } else stale = 0
            val list = PhoneAlarmParser.list(root()) ?: break
            if (!list.scrollForward()) break
            host.pause(settleMs)
        }
        return seen to null
    }

    private fun root(): UiNode = host.clockRoot() ?: throw Abort("Google Clock closed while reading alarms")

    private suspend fun session(body: suspend () -> PhoneAlarmOutcome): PhoneAlarmOutcome {
        host.blocker()?.let { return PhoneAlarmOutcome(false, it) }
        val previous = host.foreground()
        val wasClock = previous == CLOCK_PACKAGE
        return try {
            if (!host.openAlarms()) return PhoneAlarmOutcome(false, "Could not open Google Clock on the phone")
            val deadline = host.elapsedMs() + openTimeoutMs
            while (true) {
                val r = host.clockRoot()
                if (r != null && PhoneAlarmParser.alarmsTabReady(r)) break
                if (host.elapsedMs() >= deadline) return PhoneAlarmOutcome(false,
                    if (r == null) "Google Clock did not come up; unlock the phone and try again" else "Google Clock's alarm list did not appear in time")
                host.pause(150)
            }
            // Rows can arrive just after the add button; give them a moment.
            if (PhoneAlarmParser.rows(root()).isEmpty()) host.pause(settleMs * 2)
            body()
        } catch (e: Abort) {
            PhoneAlarmOutcome(false, e.text)
        } finally {
            if (!wasClock) runCatching { host.returnTo(previous) }
        }
    }

    companion object { const val CLOCK_PACKAGE = "com.google.android.deskclock" }
}

/**
 * The cached phone alarm list plus the one-at-a-time read/toggle runner. Call from the main thread; the
 * automation itself runs on [worker]. [onChange] fires after every state change (the engine re-renders).
 */
class PhoneAlarmBook(
    private val wallMs: () -> Long = System::currentTimeMillis,
    var worker: CoroutineDispatcher = Dispatchers.IO,
) {
    var host: PhoneAlarmHost = PhoneAlarmHost.NONE
    var cacheStore: ClockPersistence = MemoryClockPersistence()
        set(value) { field = value; cache = load(value) }
    var onChange: () -> Unit = {}
    var cache: PhoneAlarmCache = PhoneAlarmCache()
        private set
    /** "Reading" / "Switching 7:00 AM" while Google Clock is being driven. */
    var busy: String? = null
        private set
    var lastError: String? = null
        private set

    val alarms get() = cache.alarms

    fun attach(host: PhoneAlarmHost, store: ClockPersistence) { this.host = host; cacheStore = store; onChange() }

    suspend fun refresh(): PhoneAlarmOutcome = run("Reading") { it.read() }

    /** Switches [target] (a cached alarm or a wanted time + label) to [enabled]. */
    suspend fun setEnabled(target: PhoneAlarm, enabled: Boolean): PhoneAlarmOutcome = run("Switching ${target.time}") { it.toggle(target, enabled) }

    private suspend fun run(what: String, block: suspend (PhoneAlarmAutomation) -> PhoneAlarmOutcome): PhoneAlarmOutcome {
        busy?.let { return PhoneAlarmOutcome(false, "Busy: $it phone alarms") }
        busy = what; lastError = null; onChange()
        val out = try {
            withContext(worker) { block(PhoneAlarmAutomation(host)) }
        } catch (e: kotlinx.coroutines.CancellationException) { busy = null; onChange(); throw e
        } catch (e: Exception) { PhoneAlarmOutcome(false, e.message ?: "Phone alarms failed") }
        busy = null
        if (!out.ok) lastError = out.message
        out.alarms?.let { cache = PhoneAlarmCache(it, wallMs()) }
        out.changed?.let { c ->
            val list = cache.alarms.toMutableList()
            val i = list.indexOfFirst { it.sameAs(c) && it.enabled != c.enabled }.takeIf { it >= 0 } ?: list.indexOfFirst { it.sameAs(c) }
            if (i >= 0) list[i] = list[i].copy(enabled = c.enabled) else list += c
            cache = cache.copy(alarms = list)
        }
        if (out.alarms != null || out.changed != null) runCatching { cacheStore.save(json.encodeToString(PhoneAlarmCache.serializer(), cache)) }
        onChange()
        return out
    }

    private fun load(store: ClockPersistence) = store.load()?.let { runCatching { json.decodeFromString(PhoneAlarmCache.serializer(), it) }.getOrNull() } ?: PhoneAlarmCache()

    companion object { private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true } }
}
