package io.lampyr.glasses.glasses

import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.glasses.protocol.*
import kotlinx.serialization.json.JsonObject

/**
 * Settings the stock firmware keeps on the glasses (requirements 15.1 and 15.2). Read with
 * request_general_settings; the stock write commands replace whole structures, so a write is only
 * allowed after a successful read and always resends the fields it did not change.
 */
data class StockSettings(
    val autoLockTime: Int? = null,
    val headUp: Boolean = false,
    val headUpDegrees: Int = 30,
    val crownSwitch: Int = 0,
    val headGestures: Boolean = false,
    val headGestureMode: Int = 0,
    val displayHeight: Int? = null,
    val displayDistance: Int? = null,
    /** True when the reply carried a complete wakeupConfig (writing wakeup_config is safe). */
    val wakeKnown: Boolean = false,
    val gesturesKnown: Boolean = false,
    val displayKnown: Boolean = false,
) {
    companion object {
        const val MIN_DEGREES = 10
        const val MAX_DEGREES = 70
        const val DEGREE_STEP = 5

        fun heightLabel(value: Int?) = when (value) { 1 -> "High"; 3 -> "Middle"; 5 -> "Low"; null -> "?"; else -> "Level $value" }
        fun distanceLabel(value: Int?) = when (value) { 1 -> "Near"; 2 -> "Far"; null -> "?"; else -> "Level $value" }
        fun modeLabel(value: Int) = if (value == 1) "Shake" else "Nod"
        /** Look-up angle steps of 5° from 10° to 70°, wrapping (the official UI offers 0 to 70). */
        fun nextDegrees(value: Int): Int {
            val stepped = (value + DEGREE_STEP) / DEGREE_STEP * DEGREE_STEP
            return if (stepped > MAX_DEGREES) MIN_DEGREES else stepped.coerceAtLeast(MIN_DEGREES)
        }

        /** Parses the `generalSettings` object; values outside the documented ranges are treated as unknown. */
        fun parse(body: JsonObject, previous: StockSettings? = null): StockSettings {
            val base = previous ?: StockSettings()
            val wake = body["wakeupConfig"] as? JsonObject
            val head = body["headGestures"] as? JsonObject
            val display = body["displayConfig"] as? JsonObject
            val headSwitch = wake?.int("headupSwitch")?.takeIf { it in 0..1 }
            val degree = wake?.int("headupDegree")?.takeIf { it in 0..90 }
            val crown = wake?.int("crownSwitch")?.takeIf { it in 0..3 }
            val wakeOk = headSwitch != null && degree != null && crown != null
            val enabled = head?.int("enabled")?.takeIf { it in 0..1 }
            val mode = head?.int("mode")?.takeIf { it in 0..1 }
            val gesturesOk = enabled != null && mode != null
            val height = display?.int("height")
            val distance = display?.int("distance")
            val displayOk = height != null && distance != null
            return base.copy(
                autoLockTime = body.int("autoLockTime") ?: base.autoLockTime,
                headUp = if (wakeOk) headSwitch == 1 else base.headUp,
                headUpDegrees = if (wakeOk) degree!! else base.headUpDegrees,
                crownSwitch = if (wakeOk) crown!! else base.crownSwitch,
                wakeKnown = wakeOk || base.wakeKnown,
                headGestures = if (gesturesOk) enabled == 1 else base.headGestures,
                headGestureMode = if (gesturesOk) mode!! else base.headGestureMode,
                gesturesKnown = gesturesOk || base.gesturesKnown,
                displayHeight = if (displayOk) height else base.displayHeight,
                displayDistance = if (displayOk) distance else base.displayDistance,
                displayKnown = displayOk || base.displayKnown,
            )
        }

        /** The commands that turn [from] into [to], one per changed stock structure. */
        fun writes(from: StockSettings, to: StockSettings): List<Command> = buildList {
            if (from.headUp != to.headUp || from.headUpDegrees != to.headUpDegrees || from.crownSwitch != to.crownSwitch) {
                check(from.wakeKnown) { "Look-up settings were not read from the glasses yet" }
                add(Commands.wakeupConfig(to.headUp, to.headUpDegrees, to.crownSwitch))
            }
            if (from.headGestures != to.headGestures || from.headGestureMode != to.headGestureMode) {
                check(from.gesturesKnown) { "Head gesture settings were not read from the glasses yet" }
                add(Commands.headGestures(to.headGestures, to.headGestureMode))
            }
            if (from.displayHeight != to.displayHeight || from.displayDistance != to.displayDistance) {
                check(from.displayKnown) { "Display position was not read from the glasses yet" }
                add(Commands.displayConfig(to.displayHeight ?: error("height"), to.displayDistance ?: error("distance")))
            }
            if (from.autoLockTime != to.autoLockTime && to.autoLockTime != null) add(Commands.autoLockTime(to.autoLockTime))
        }
    }
}

/** What the glasses and phone settings screens read and change. */
interface StockSettingsStore {
    /** The last settings read from the glasses, or null while not connected / not read yet. */
    val current: StockSettings?
    /** Ask the glasses for their settings again. */
    fun refresh() {}
    suspend fun update(change: (StockSettings) -> StockSettings): ActionResult
    fun listen(listener: () -> Unit): () -> Unit
}

/** Simulator, tests and demo: an in-memory copy with plausible stock defaults. */
class MemoryStockSettings(initial: StockSettings? = StockSettings(autoLockTime = 15, headUp = true, headUpDegrees = 30,
    displayHeight = 3, displayDistance = 2, wakeKnown = true, gesturesKnown = true, displayKnown = true)) : StockSettingsStore {
    private val listeners = mutableListOf<() -> Unit>()
    override var current = initial
        private set
    val written = mutableListOf<Command>()
    override suspend fun update(change: (StockSettings) -> StockSettings): ActionResult {
        val from = current ?: return ActionResult.failure("Glasses not connected")
        val to = change(from)
        return try {
            written += StockSettings.writes(from, to)
            current = to
            listeners.toList().forEach { it() }
            ActionResult()
        } catch (e: IllegalStateException) { ActionResult.failure(e.message ?: "Not available") }
    }
    override fun listen(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
}

/** Live copy on the RayNeo link: [send] is the guarded stock write boundary. */
class StockSettingsClient(private val send: suspend (Command) -> Unit) : StockSettingsStore {
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile override var current: StockSettings? = null
        private set
    @Volatile var connected = false
        private set
    /** Set by the transport to schedule a read without suspending the caller. */
    var requestRead: () -> Unit = {}

    override fun refresh() = requestRead()

    suspend fun read() { send(Commands.generalSettings()) }

    fun connectionChanged(up: Boolean) {
        connected = up
        if (!up && current != null) { current = null; notifyListeners() }
    }

    fun received(event: GlassesEvent.Settings) {
        val next = StockSettings.parse(event.body, current)
        Trace.log("Settings", "Glasses settings autoLock=${next.autoLockTime} headUp=${next.headUp}/${next.headUpDegrees} crown=${next.crownSwitch} " +
            "gestures=${next.headGestures}/${next.headGestureMode} display=${next.displayHeight}/${next.displayDistance}")
        current = next
        notifyListeners()
    }

    override suspend fun update(change: (StockSettings) -> StockSettings): ActionResult {
        val from = current ?: return ActionResult.failure("Glasses not connected or settings not read yet")
        val to = change(from)
        val commands = try { StockSettings.writes(from, to) } catch (e: IllegalStateException) { return ActionResult.failure(e.message ?: "Not available") }
        if (commands.isEmpty()) return ActionResult()
        return try {
            commands.forEach { send(it) }
            current = to // Optimistic; the read below confirms or corrects it.
            notifyListeners()
            read()
            ActionResult()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ActionResult.failure(e.message ?: "Could not write glasses settings") }
    }

    override fun listen(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }
    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
}
