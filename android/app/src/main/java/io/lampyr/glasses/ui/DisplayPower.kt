package io.lampyr.glasses.ui

import android.content.SharedPreferences
import io.lampyr.glasses.render.TextCase

/**
 * Display power preferences (requirement 1.1, look-up wake, lock return). They live in the `glasses`
 * SharedPreferences file under `power.*`, so `glassesctl pref power.idleMs 5000 --type long` works.
 * Keys deliberately avoid the `display.*` prefix: RasterOutput reopens its session on any `display.*` change.
 */
data class PowerPrefs(
    /** Idle time before the lens goes dark. 0 means never (the display stays lit, the old behaviour). */
    val idleMs: Long = 15_000,
    /** One dim frame before black. A full frame takes about 760 ms, so a smooth fade is impossible. */
    val dimStep: Boolean = false,
    /** A press that wakes a dark lens also performs its action. Off: the first press only wakes. */
    val firstPressActs: Boolean = true,
    /** Unlocking after more than this long returns to Home. 0 = always resume the previous screen. */
    val lockHomeMs: Long = 60_000,
    /** Important popups light a locked ("visually off") lens; the lock itself is kept. */
    val popupWhenLocked: Boolean = true,
    /**
     * What looking up does (stock head-up signal): turn the lens on, turn it off, toggle it, or nothing.
     * Anything but NOTHING enables stock head-up wake. Replaces the old boolean power.lookUpWake.
     */
    val lookUp: LookUp = LookUp.ON,
    /** Looking up may act on a locked ("visually off") headset. Off: a locked headset ignores look-up in every mode. */
    val lookUpWhenLocked: Boolean = false,
    /** Bitmap text case (display.textCase): CAPS is the original all-caps look, NORMAL shows real lowercase. */
    val textCase: TextCase = TextCase.CAPS,
    /**
     * Dark-lens backend asked of the transport: auto, black or park. Auto means black until experiment E1 proves
     * that parking does not expose the stock home (requirement 1.3) and that stock head-up wake reaches the phone;
     * park is the explicit opt-in. Flip [sleepBackend]'s auto branch once E1 passes.
     */
    val backend: String = "auto",
    /** Notification pop-ups ("popup" routes) at all. Off: they only join the Notifications list. */
    val popups: Boolean = true,
    /** How long a notification pop-up stays up (and holds the lens lit). */
    val popupMs: Long = 6_000,
    /** A recording voice memo holds the lens lit. Off: the REC mark shows whenever the lens is lit (any press lights it). */
    val memoKeepAwake: Boolean = false,
    /** the assistant call screen: "compact" (a small corner card, the rest of the lens stays dark/see-through) or "full". */
    val joyCallLayout: String = "compact",
) {
    val autoSleep get() = idleMs > 0
    /** Stock head-up detection is wanted: any look-up mode except NOTHING. */
    val lookUpWake get() = lookUp != LookUp.NOTHING

    /** "black" keeps the raster page open showing an all-black frame; "park" closes it so stock sleep and head-up wake run. */
    val sleepBackend get() = when (backend) { "park" -> "park"; else -> "black" } // auto = black until E1 (see [backend]).

    companion object {
        const val PREFIX = "power."
        const val IDLE = "power.idleMs"
        const val DIM = "power.dimStep"
        const val FIRST_PRESS = "power.firstPressActs"
        const val LOCK_HOME = "power.lockHomeMs"
        const val POPUP_LOCKED = "power.popupWhenLocked"
        const val LOOK_UP = "power.lookUp"
        const val LOOK_UP_WHEN_LOCKED = "power.lookUpWhenLocked"
        /** Old boolean (wave 1): true migrates to ON, false to NOTHING, when power.lookUp is unset. */
        const val LEGACY_LOOK_UP_WAKE = "power.lookUpWake"
        /** A display.* key so it sits with the other display prefs; RasterOutput re-renders on it instead of reopening. */
        const val TEXT_CASE = "display.textCase"
        const val BACKEND = "power.backend"
        const val POPUPS = "power.popups"
        const val POPUP_MS = "power.popupMs"
        val POPUP_MS_OPTIONS = listOf(4_000L, 6_000L, 8_000L, 10_000L, 15_000L)
        fun popupLabel(ms: Long) = "${ms / 1000} s"
        const val MEMO_AWAKE = "power.memoKeepAwake"
        const val JOY_CALL_LAYOUT = "power.joyCallLayout"
        val JOY_CALL_LAYOUTS = listOf("compact", "full")
        /** How long the optional dim frame stays before black. */
        const val DIM_MS = 1_500L
        const val DIM_LEVEL = 0.4
        /** While a screen keeps the lens awake, re-check this often. */
        const val KEEP_AWAKE_POLL_MS = 1_000L
        val IDLE_OPTIONS = listOf(5_000L, 10_000L, 12_000L, 15_000L, 20_000L, 30_000L, 60_000L, 0L)
        const val MAX_IDLE_MS = 60_000L
        val LOCK_HOME_OPTIONS = listOf(0L, 30_000L, 60_000L, 300_000L)
        val BACKENDS = listOf("auto", "black", "park")
        /** Tests and the simulator parity harness: power management off, display always lit. */
        val ALWAYS_ON = PowerPrefs(idleMs = 0)

        fun idleLabel(ms: Long) = if (ms <= 0) "Never" else if (ms % 1000 == 0L) "${ms / 1000} s" else "${ms / 1000.0} s"
        fun lookUpLabel(mode: LookUp) = when (mode) { LookUp.ON -> "On"; LookUp.OFF -> "Off"; LookUp.TOGGLE -> "Toggle"; LookUp.NOTHING -> "Nothing" }
        fun lookUpSub(mode: LookUp) = when (mode) {
            LookUp.ON -> "Looking up lights the lens"
            LookUp.OFF -> "Looking up darkens the lens"
            LookUp.TOGGLE -> "Looking up toggles the lens"
            LookUp.NOTHING -> "Looking up does nothing"
        }
        fun textCaseLabel(case: TextCase) = when (case) { TextCase.CAPS -> "Caps"; TextCase.NORMAL -> "Normal" }
        fun lockHomeLabel(ms: Long) = when {
            ms <= 0 -> "Off"
            ms < 60_000 -> "${ms / 1000} s"
            else -> "${ms / 60_000} min"
        }
        fun backendLabel(value: String) = when (value) { "black" -> "Black"; "park" -> "Park"; else -> "Auto" }

        /** Tolerates the int/long/string types `glassesctl pref` may store. Missing or invalid values keep the default. */
        fun read(values: Map<String, *>): PowerPrefs {
            val d = PowerPrefs()
            fun long(key: String, fallback: Long) = when (val v = values[key]) {
                is Number -> v.toLong(); is String -> v.trim().toLongOrNull() ?: fallback; else -> fallback
            }
            fun bool(key: String, fallback: Boolean) = when (val v = values[key]) {
                is Boolean -> v; is Number -> v.toInt() != 0
                is String -> when (v.trim().lowercase()) { "true", "1", "on" -> true; "false", "0", "off" -> false; else -> fallback }
                else -> fallback
            }
            fun <E : Enum<E>> enum(key: String, entries: List<E>): E? =
                (values[key] as? String)?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } }
            val lookUp = enum(LOOK_UP, LookUp.entries)
                ?: if (values.containsKey(LEGACY_LOOK_UP_WAKE)) { if (bool(LEGACY_LOOK_UP_WAKE, true)) LookUp.ON else LookUp.NOTHING } else d.lookUp
            return PowerPrefs(
                idleMs = long(IDLE, d.idleMs).coerceIn(0, MAX_IDLE_MS).let { if (it in 1..999) 1_000 else it },
                dimStep = bool(DIM, d.dimStep),
                firstPressActs = bool(FIRST_PRESS, d.firstPressActs),
                lockHomeMs = long(LOCK_HOME, d.lockHomeMs).coerceAtLeast(0),
                popupWhenLocked = bool(POPUP_LOCKED, d.popupWhenLocked),
                lookUp = lookUp,
                lookUpWhenLocked = bool(LOOK_UP_WHEN_LOCKED, d.lookUpWhenLocked),
                textCase = enum(TEXT_CASE, TextCase.entries) ?: d.textCase,
                backend = (values[BACKEND] as? String)?.trim()?.lowercase()?.takeIf { it in BACKENDS } ?: d.backend,
                popups = bool(POPUPS, d.popups),
                popupMs = long(POPUP_MS, d.popupMs).coerceIn(2_000, 30_000),
                memoKeepAwake = bool(MEMO_AWAKE, d.memoKeepAwake),
                joyCallLayout = (values[JOY_CALL_LAYOUT] as? String)?.trim()?.lowercase()?.takeIf { it in JOY_CALL_LAYOUTS } ?: d.joyCallLayout,
            )
        }
    }
}

/** What looking up does. Stock head-up only reports while the lens is dark or parked, so ON and TOGGLE usually match. */
enum class LookUp { ON, OFF, TOGGLE, NOTHING }

/** Wake reasons that come from the wearer looking up (stock head-up while parked arrives as screen_status). */
val LOOK_UP_REASONS = setOf("lookup", "screen_status")

/**
 * Apply power.lookUp / power.lookUpWhenLocked to a look-up signal. Returns what happened: "on", "off" or "ignored".
 * A locked headset ignores look-up unless lookUpWhenLocked; then ON and TOGGLE unlock it (it is dark) and OFF keeps it.
 */
fun Ui.onLookUp(reason: String = "lookup"): String {
    val p = powerStore.prefs
    val result = when {
        p.lookUp == LookUp.NOTHING -> "ignored"
        locked && !p.lookUpWhenLocked -> "ignored"
        locked -> if (p.lookUp == LookUp.OFF) "ignored" else { unlock(); "on" }
        p.lookUp == LookUp.ON || (p.lookUp == LookUp.TOGGLE && !awake) -> { wake(reason); "on" }
        else -> { sleep(reason); "off" }
    }
    io.lampyr.glasses.debug.Trace.log("Power", "Look-up source=$reason mode=${p.lookUp} locked=$locked -> $result")
    return result
}

/**
 * Route a wearer signal from the transport (GlassesTransport.onWearerWake). Look-up signals follow [onLookUp];
 * the crown (and screen_status while stock head-up is disabled, when it can only be the crown) is a plain wake.
 */
fun Ui.onWearerSignal(reason: String): String = when {
    reason == "screen_status" && !powerStore.prefs.lookUpWake -> { wake(reason); "on" }
    reason in LOOK_UP_REASONS -> onLookUp(reason)
    else -> { wake(reason); "on" }
}

/** Where the UI reads and writes power preferences. */
interface PowerStore {
    val prefs: PowerPrefs
    fun update(next: PowerPrefs)
    fun listen(listener: () -> Unit): () -> Unit
}

class MemoryPowerStore(initial: PowerPrefs = PowerPrefs.ALWAYS_ON) : PowerStore {
    private val listeners = mutableListOf<() -> Unit>()
    override var prefs = initial
        private set
    override fun update(next: PowerPrefs) { prefs = next; listeners.toList().forEach { it() } }
    override fun listen(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
}

/** The `glasses` SharedPreferences file, shared with the phone settings page and the debug bridge. */
class SharedPowerStore(private val shared: SharedPreferences) : PowerStore {
    override val prefs get() = PowerPrefs.read(shared.all)
    override fun update(next: PowerPrefs) {
        shared.edit()
            .putLong(PowerPrefs.IDLE, next.idleMs)
            .putBoolean(PowerPrefs.DIM, next.dimStep)
            .putBoolean(PowerPrefs.FIRST_PRESS, next.firstPressActs)
            .putLong(PowerPrefs.LOCK_HOME, next.lockHomeMs)
            .putBoolean(PowerPrefs.POPUP_LOCKED, next.popupWhenLocked)
            .putString(PowerPrefs.LOOK_UP, next.lookUp.name)
            .remove(PowerPrefs.LEGACY_LOOK_UP_WAKE)
            .putBoolean(PowerPrefs.LOOK_UP_WHEN_LOCKED, next.lookUpWhenLocked)
            .putString(PowerPrefs.TEXT_CASE, next.textCase.name)
            .putString(PowerPrefs.BACKEND, next.backend)
            .putBoolean(PowerPrefs.POPUPS, next.popups)
            .putLong(PowerPrefs.POPUP_MS, next.popupMs)
            .putBoolean(PowerPrefs.MEMO_AWAKE, next.memoKeepAwake)
            .putString(PowerPrefs.JOY_CALL_LAYOUT, next.joyCallLayout)
            .apply()
    }
    override fun listen(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || key.startsWith(PowerPrefs.PREFIX) || key == PowerPrefs.TEXT_CASE) listener() }
        shared.registerOnSharedPreferenceChangeListener(callback)
        return { shared.unregisterOnSharedPreferenceChangeListener(callback) }
    }
}

/**
 * The display power state machine. Pure Kotlin on a monotonic millisecond clock; [Ui] owns one and the
 * service drives [tick] with a Handler. Nothing here wakes the display except an explicit [wake]:
 * background data, flashes and gateway navigation only re-render, and a dark lens renders black.
 */
class DisplayPower(private val clock: () -> Long, private val prefs: () -> PowerPrefs) {
    enum class State { AWAKE, DIM, ASLEEP }

    /** Asleep by default whenever auto-sleep is enabled. */
    var state = if (prefs().autoSleep) State.ASLEEP else State.AWAKE
        private set
    var lastWakeReason: String? = null
        private set
    var lastWakeAt = 0L
        private set
    var lastChangeAt = clock()
        private set
    private var idleUntil = 0L
    private var holdUntil = 0L
    private var dimUntil = 0L

    val awake get() = state != State.ASLEEP
    val dim get() = state == State.DIM

    /** Light the lens and keep it lit for at least [ms] (then the normal idle fade). Returns true when it was dark. */
    fun wake(reason: String, ms: Long = 0): Boolean {
        val now = clock()
        val wasAsleep = state == State.ASLEEP
        lastWakeReason = reason; lastWakeAt = now
        idleUntil = now + prefs().idleMs
        holdUntil = maxOf(if (wasAsleep) 0 else holdUntil, now + ms.coerceAtLeast(0))
        set(State.AWAKE, now)
        return wasAsleep
    }

    /** Accepted input while lit: restart the idle timer (and undo a dim step). */
    fun poke() {
        if (state == State.ASLEEP) return
        val now = clock()
        idleUntil = now + prefs().idleMs
        if (state == State.DIM) set(State.AWAKE, now)
    }

    /** Drop a [wake] minimum (a dismissed popup), keeping the normal idle time from now. */
    fun release() {
        holdUntil = 0
        poke()
    }

    fun sleep() {
        holdUntil = 0
        set(State.ASLEEP, clock())
    }

    /**
     * Advance AWAKE -> (DIM) -> ASLEEP. [keepAwake] screens (live dictation, calls, teleprompter, the assistant call,
     * running toys) hold the lens lit and restart the idle time when they stop. Returns the milliseconds
     * until the next possible change, or null when no timer is needed.
     */
    fun tick(keepAwake: Boolean): Long? {
        val p = prefs()
        val now = clock()
        if (!p.autoSleep) {
            if (state == State.DIM) set(State.AWAKE, now)
            return null
        }
        if (state == State.ASLEEP) return null
        if (keepAwake) {
            idleUntil = maxOf(idleUntil, now + p.idleMs)
            if (state == State.DIM) set(State.AWAKE, now)
            return PowerPrefs.KEEP_AWAKE_POLL_MS
        }
        val until = maxOf(idleUntil, holdUntil)
        if (now < until) return until - now
        if (state == State.AWAKE && p.dimStep) {
            dimUntil = now + PowerPrefs.DIM_MS
            set(State.DIM, now)
            return PowerPrefs.DIM_MS
        }
        if (state == State.DIM && now < dimUntil) return dimUntil - now
        set(State.ASLEEP, now)
        return null
    }

    private fun set(next: State, now: Long) {
        if (state == next) return
        io.lampyr.glasses.debug.Trace.log("Power", "$state -> $next reason=${lastWakeReason.takeIf { next == State.AWAKE }}")
        state = next
        lastChangeAt = now
    }
}

/**
 * A transient card over whatever screen is up. It wakes a dark lens without changing the screen stack
 * or the lock; Y opens [app]/[target] through [Ui.openTarget], B dismisses, and it disappears after [ms].
 */
data class PopupCard(
    val title: String,
    val body: String,
    val icon: String? = null,
    val app: String? = null,
    val target: String? = null,
    val ms: Long = 6000,
)

/** Stock auto-sleep (seconds) asked for while the park backend may hand the screen back to the stock launcher. */
const val PARK_AUTO_LOCK_S = 5

/**
 * What the power preferences ask of the stock glasses settings: stock look-up wake is on unless power.lookUp is NOTHING
 * (only once the glasses reported their wake settings, because wakeup_config replaces the whole structure),
 * and the park backend wants the shortest stock auto-sleep so the stock home goes dark quickly after a park.
 * Returns null when nothing has to change.
 */
fun stockSyncFor(p: PowerPrefs, s: io.lampyr.glasses.glasses.StockSettings): io.lampyr.glasses.glasses.StockSettings? {
    var next = s
    if (s.wakeKnown && s.headUp != p.lookUpWake) next = next.copy(headUp = p.lookUpWake)
    if (p.sleepBackend == "park" && s.autoLockTime != PARK_AUTO_LOCK_S) next = next.copy(autoLockTime = PARK_AUTO_LOCK_S)
    return next.takeIf { it != s }
}
