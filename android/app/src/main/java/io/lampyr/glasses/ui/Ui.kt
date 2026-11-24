package io.lampyr.glasses.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import io.lampyr.glasses.data.*
import io.lampyr.glasses.glasses.Frame
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.screens.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*

class Ui(
    val data: DataStore,
    /** The assistant avatar pack; the service swaps it when the phone setting changes. */
    var avatar: io.lampyr.glasses.render.avatar.AvatarPack? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val nowS: () -> Double = { android.os.SystemClock.elapsedRealtime() / 1000.0 },
    val nowMs: () -> Long = { System.currentTimeMillis() },
    val zone: ZoneId = ZoneId.systemDefault(),
    val shortcutAssignments: io.lampyr.glasses.input.ShortcutAssignments = io.lampyr.glasses.input.ShortcutAssignments(),
    /** Display power preferences. The default keeps the lens lit (tests, simulator parity); the service passes the real store. */
    val powerStore: PowerStore = MemoryPowerStore(),
    /** Stock glasses settings (head gestures, display position). The service passes the live transport's copy. */
    val glassesSettings: io.lampyr.glasses.glasses.StockSettingsStore = io.lampyr.glasses.glasses.MemoryStockSettings(),
    /** Clock app state (timers, stopwatch, alarms). The service passes its long-lived engine; the default follows this Ui's clocks. */
    val clock: io.lampyr.glasses.clock.ClockEngine = io.lampyr.glasses.clock.ClockEngine.memory(nowMs, { (nowS() * 1000).toLong() }, { zone }),
    /** Beeper list preferences (beeper.order). The service passes the `glasses` SharedPreferences copy. */
    val beeperPrefs: BeeperPrefStore = MemoryBeeperPrefs(),
) {
    var display: Display = Display.Bitmap
        set(value) {
            if (field == value) return
            field = value
            joyPage = 0
            renderedDark = false // The new output mode still needs its own dark frame.
            requestRender()
        }
    val textDisplay get() = display as? Display.Text ?: Display.Text()
    val isText get() = display is Display.Text
    fun textPages(text: String, bodyRows: Int = textDisplay.rows - 2) =
        gridPages(text, textDisplay.cols, bodyRows)

    val stack = mutableListOf<View>()
    val top
        get() = stack.last()

    var dirty = true
        private set

    var onDirty: () -> Unit = {}
    var locked = false
        set(value) {
            val next = value && data.calls.snapshot.ongoing == null
            if (next && !field) { lockedAtMs = powerNow(); power.sleep() }
            if (!next && field) power.wake("unlock")
            field = next
        }

    // ---- Display power (requirement 1.1): asleep by default, idle fade, popups, lock return. ----
    /** Monotonic power clock; follows [nowS] so tests can drive it. */
    fun powerNow(): Long = (nowS() * 1000).toLong()
    private var lockedAtMs: Long? = null
    val power = DisplayPower(::powerNow) { powerStore.prefs }
    /** The lens is lit: power is awake and either unlocked or showing a popup that may light a locked lens. */
    val awake: Boolean
        get() = power.awake && (!locked || popupVisible)
    /** Called with every lit/dark transition; the service forwards it to GlassesTransport.displayAwake. */
    var onDisplayAwake: (Boolean) -> Unit = {}
    /** Called whenever the power timer may need rescheduling; the service posts [powerTick]. */
    var onPowerActivity: () -> Unit = {}
    private var lastLit: Boolean? = null
    private var renderedDark = false
    var popupCard: PopupCard? = null
        private set
    private var popupUntil = 0L
    val popupVisible get() = popupCard != null && powerNow() < popupUntil
    /** Apps register how to open a popup's target: targetOpeners["beeper"] = { chatId -> ... }. */
    val targetOpeners = mutableMapOf<String, (String?) -> Boolean>()
    private val unsubscribePower = powerStore.listen { Fonts.textCase = powerStore.prefs.textCase; if (!powerStore.prefs.autoSleep) wake("settings") else requestRender() }
    init { Fonts.textCase = powerStore.prefs.textCase } // display.textCase: bitmap glyph case.
    private val unsubscribeStock = glassesSettings.listen { requestRender() }
    var mediaLayer = false
    var quiet = false
    private var unsubscribeListening: () -> Unit = {}
    /** AMB-14 passive listening (conversation recording + voice matching). The service swaps in the persisted switch. */
    var listening: PassiveListening = MemoryPassiveListening()
        set(value) {
            unsubscribeListening()
            field = value
            unsubscribeListening = value.listen { requestRender() }
            requestRender()
        }

    /** Hold − while unlocked; the toast confirms the new state (a locked − always just unlocks). */
    fun toggleListening() {
        val on = !listening.on
        listening.set(on)
        flash(if (on) "Passive listening on" else "Passive listening off")
    }
    var joyPage = 0
    /** The flash message currently on screen, for the debug bridge. */
    val currentToast get() = toastText?.takeIf { nowS() < toastUntil }
    val shortcuts by lazy { io.lampyr.glasses.input.AppShortcuts(this, shortcutAssignments) }
    private var toastText: String? = null
    private var toastUntil = 0.0
    private val unsubscribe = data.addChangeListener {
        val ended = data.joy.call.endedReason
        if (!data.joy.call.active && ended != null) {
            val removed = stack.filterIsInstance<JoyCall>()
            val saved = callReturn?.filterIsInstance<JoyCall>().orEmpty()
            stack.removeAll(removed.toSet())
            callReturn = callReturn?.filterNot { it is JoyCall }
            (removed + saved).forEach { it.onClose() }
            if (removed.isNotEmpty() || saved.isNotEmpty()) {
                if (stack.isEmpty()) goHome() else announce()
                flash("Call ended ($ended)")
            }
        }
        requestRender()
    }
    val needsAnimation
        get() = awake && !locked && (top.needsAnimation || mediaLayer || nowS() < toastUntil || recordingBadge() != null)

    // ---- Recording indicator: a memo recording, or a live dictation shown away from its own screen. ----
    /** The live hold-to-talk capture, set by the input engine for its whole session (cleared when the mic closes). */
    var talkCapture: TalkCapture? = null
        set(value) { field = value; requestRender() }

    /** The persistent mark drawn over every screen's status chrome: label and monotonic start, or null. */
    fun recordingBadge(): Pair<String, Double>? {
        val memo = data.memos.snapshot
        if (memo.recording) return "REC" to memo.since
        val talk = talkCapture ?: return null
        if (stack.isEmpty()) return null
        val here = talk.owner?.let { top === it } ?: (top.kind == "joy")
        return if (here) null else "MIC" to talk.since
    }

    /** Text form of [recordingBadge]: a dot that blinks each second, the label and the elapsed time ("*REC 0:42"). */
    fun recordingBadgeText(): String? = recordingBadge()?.let { (label, since) ->
        val s = kotlin.math.floor(nowS() - since).toInt().coerceAtLeast(0)
        (if (s % 2 == 0) "*" else " ") + label + " " + fmtS(s)
    }

    private fun Gfx.recordingMark() {
        val (label, since) = recordingBadge() ?: return
        val s = kotlin.math.floor(nowS() - since).toInt().coerceAtLeast(0)
        val text = label + " " + fmtS(s)
        val right = W - 12
        val x = right - tw(text, "s")
        val dot = x - 16
        p.fr(dot - 6, 0, right - dot + 6, 31, BLACK) // Over the clock; the header rule at y=32 stays.
        if (s % 2 == 0) { p.fr(dot + 2, 10, 6, 10); p.fr(dot, 12, 10, 6); p.fr(dot + 1, 11, 8, 8) }
        tx(text, x, 8, "s")
    }

    init {
        goHome()
    }

    private var callReturn: List<View>? = null
    private var callLocked = false
    private var ongoingLock: Boolean? = null
    private var callTakeoverUntil = 0.0
    private val ringingOnTop get() = (top as? PhoneCallScreen)?.let { v -> data.calls.snapshot.calls.any { it.id == v.callId && it.phase == io.lampyr.glasses.calls.CallPhase.Ringing } } == true
    fun ignoreCallInput(action: String) = ringingOnTop &&
        (action in setOf("np", "notif", "apps", "people") || (action in setOf("back", "sel") && nowS() < callTakeoverUntil))
    private val hiddenCalls = mutableSetOf<String>()
    private var expectOutgoingUntil = 0.0
    private val shownOutgoing = mutableSetOf<String>()
    var onCallWake: () -> Unit = {}
    private val unsubscribeCalls = data.calls.listen { reconcileCalls(); reconcileClock(); requestRender() }
    init { reconcileCalls() }

    // ---- Clock app (requirement 7): a ringing timer or alarm takes over the display like a call (after one). ----
    /** Ringing entries already shown, so navigating away from the card does not bring it back every second. */
    private val clockShown = mutableSetOf<String>()
    private var clockLockedBefore: Boolean? = null
    /** Called when a ringing timer/alarm takes the display; the service reclaims the glasses transport. */
    var onClockWake: () -> Unit = {}
    private val unsubscribeClock = clock.listen { reconcileClock(); requestRender() }
    init { reconcileClock() }

    /** "Timer 4:32" for Home's weather row while a timer or the stopwatch runs. */
    fun clockSummary(): String? = clock.homeSummary()

    fun reconcileClock() {
        val ringing = clock.ringing
        // Glasses items are keyed by their elapsed anchor, which a wall-clock or time-zone change does not move
        // (onTimeChanged rewrites sinceWallMs); Google Clock items by their notification time.
        val keys = ringing.map { it.kind + ":" + it.id + ":" + (if (it.source == io.lampyr.glasses.clock.SOURCE_PHONE) it.sinceWallMs else it.sinceElapsedMs) }
        clockShown.retainAll(keys.toSet())
        val fresh = ringing.filterIndexed { i, _ -> keys[i] !in clockShown }
        if (fresh.isNotEmpty() && data.calls.snapshot.ringing == null && stack.isNotEmpty()) {
            clockShown += keys
            if (top !is ClockRinging) {
                if (clockLockedBefore == null) clockLockedBefore = locked
                locked = false; mediaLayer = false
                push(ClockRinging(this))
            }
            if (fresh.any(clock::ringingLoud)) power.wake("clock")
            onClockWake()
            requestRender()
        }
        if (ringing.isEmpty()) {
            val removed = stack.filterIsInstance<ClockRinging>()
            if (removed.isNotEmpty()) {
                removed.forEach { it.onClose() }; stack.removeAll(removed.toSet())
                if (stack.isEmpty()) goHome() else announce()
            }
            val relock = clockLockedBefore
            clockLockedBefore = null
            if (relock == true && removed.isNotEmpty()) locked = true
        }
    }

    fun openCalls() {
        val s = data.calls.snapshot
        val call = s.ringing ?: s.ongoing
        if (call != null) push(PhoneCallScreen(this, call.id))
        else push(PhoneRoot(this, PhoneTab.Recents))
    }

    /** Set by the Phone confirm card and the assistant's call.place: the next outgoing call opens its call screen here. */
    fun expectOutgoing(seconds: Double = 15.0) { expectOutgoingUntil = nowS() + seconds }
    /** The placement failed: a call the user later dials on the phone itself must not jump onto the glasses. */
    fun cancelExpectOutgoing() { expectOutgoingUntil = 0.0 }

    fun leaveCall() {
        val saved = callReturn
        if (saved != null) {
            stack.forEach { it.onClose() }; stack.clear(); stack.addAll(saved)
            callReturn = null; locked = if (data.calls.snapshot.ongoing != null) false else callLocked; announce()
        } else pop()
    }

    /** X silences only the glasses card; the default dialer's ringtone continues. */
    fun hideCall(id: String) { hiddenCalls += id; leaveCall() }

    private fun reconcileCalls() {
        val s = data.calls.snapshot
        if (s.ongoing != null) {
            if (ongoingLock == null) ongoingLock = if (callReturn != null) callLocked else locked
            locked = false
        }
        hiddenCalls.retainAll(s.calls.map { it.id }.toSet())
        shownOutgoing.retainAll(s.calls.map { it.id }.toSet())
        // The user's rule (callAlert): prior real calls, saved contacts and local businesses take over; other unknown and
        // private numbers ring quietly (Home row, a missed-call notice); spam stays in Recents only.
        val ring = s.calls.firstOrNull { it.phase == io.lampyr.glasses.calls.CallPhase.Ringing && it.id !in hiddenCalls &&
            io.lampyr.glasses.calls.callAlert(it) == io.lampyr.glasses.calls.CallAlert.Takeover }
        if (ring != null) {
            hiddenCalls += ring.id // Wake/take over once per ringing call, including call waiting.
            if (callReturn == null) { callReturn = stack.toList(); callLocked = locked; stack.clear() }
            else stack.forEach { it.onClose() }.also { stack.clear() }
            locked = false; mediaLayer = false
            callTakeoverUntil = nowS() + 0.5
            stack += PhoneCallScreen(this, ring.id)
            power.wake("call") // CALL-01: a ringing call is the one interruption that lights a dark lens.
            onCallWake(); announce()
        } else if (s.calls.none { it.phase != io.lampyr.glasses.calls.CallPhase.Ending }) {
            if (callReturn != null) leaveCall()
            else {
                val removed = stack.filter { it.kind == "phone-call" || it.id == "call-audio" }
                removed.forEach { it.onClose() }; stack.removeAll(removed.toSet())
                if (stack.isEmpty()) goHome() else announce()
            }
            ongoingLock?.let { locked = it }; ongoingLock = null
        } else {
            val placed = s.calls.firstOrNull { it.id !in shownOutgoing && (it.phase == io.lampyr.glasses.calls.CallPhase.Dialing ||
                it.direction == io.lampyr.glasses.calls.CallDirection.Outgoing && it.phase == io.lampyr.glasses.calls.CallPhase.Active) }
            if (placed != null && nowS() < expectOutgoingUntil) {
                // A call placed from the glasses or by the assistant: show its call screen (replacing the confirm card).
                shownOutgoing += placed.id; expectOutgoingUntil = 0.0
                locked = false
                if (top is PhoneConfirm) replaceTop(PhoneCallScreen(this, placed.id)) else push(PhoneCallScreen(this, placed.id))
                power.wake("call")
                return
            }
            val v = top as? PhoneCallScreen
            if (v != null && s.calls.none { it.id == v.callId && it.phase != io.lampyr.glasses.calls.CallPhase.Ending }) {
                val current = s.ringing ?: s.ongoing
                if (current != null) replaceTop(PhoneCallScreen(this, current.id))
            }
        }
    }

    fun requestRender() {
        (data as? LiveDataStore)?.setDisplayAwake(!locked)
        dirty = true
        syncPower()
        // A dark lens that already shows its black frame needs no new frame (and no Bluetooth traffic).
        if (!awake && renderedDark) return
        onDirty()
    }

    private fun announce() {
        data.onScreenChanged(top.kind)
        requestRender()
    }

    fun push(v: View) {
        if (stack.isNotEmpty() && ringingOnTop && v.kind != "phone-call") { v.onClose(); return }
        if (stack.lastOrNull()?.id == v.id) return
        stack += v
        v.onOpen()
        announce()
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex).onClose()
        announce()
    }

    /** Remove [v] wherever it is in the stack (top or buried), closing it. */
    fun remove(v: View) {
        if (stack.lastOrNull() === v) { pop(); return }
        if (stack.remove(v)) { v.onClose(); announce() }
    }

    fun replaceTop(v: View) {
        stack.removeLastOrNull()?.onClose()
        stack += v
        v.onOpen()
        announce()
    }

    fun goHome() {
        // Leaving an interruption explicitly makes Home the new destination.
        callReturn?.forEach { it.onClose() }
        callReturn = null
        stack.forEach { it.onClose() }
        stack.clear()
        stack += Home(this)
        top.onOpen()
        announce()
    }

    fun flash(text: String) {
        toastText = text
        toastUntil = nowS() + 1.7
        requestRender()
    }

    fun perform(
        fallback: String? = null,
        after: ((ActionResult) -> Unit)? = null,
        action: suspend () -> ActionResult,
    ) {
        scope.launch {
            val result =
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ActionResult.failure(e.message ?: "Action failed")
                }
            if (!result.ok) flash(result.message ?: "Action failed")
            else {
                (result.message ?: fallback)?.let(::flash)
                after?.invoke(result)
            }
            requestRender()
        }
    }

    fun refresh(action: suspend () -> ActionResult) = perform(action = action)

    fun clockString(): String =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            .withZone(zone)
            .format(Instant.ofEpochMilli(nowMs()))

    fun dateString(pattern: String): String =
        DateTimeFormatter.ofPattern(pattern, Locale.US)
            .withZone(zone)
            .format(Instant.ofEpochMilli(nowMs()))

    fun render(): Frame {
        val source = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val g = Gfx(Canvas(source), avatar)
        g.clear()
        val grid = TextGrid(textDisplay.cols, textDisplay.rows, uppercase = false)
        val lit = awake
        if (!locked && lit) {
            if (isText) {
                top.drawText(grid)
                val call = data.calls.snapshot.ongoing != null && top.kind != "phone-call"
                if (call) grid.putR(0, " CALL")
                recordingBadgeText()?.let { grid.putR(0, " $it" + if (call) " CALL" else "") }
            } else {
                top.draw(g, nowS())
                g.recordingMark()
            }
            with(g) {
                if (mediaLayer) {
                    val text = "MEDIA: UP/DOWN VOLUME, LEFT/RIGHT SKIP"
                    val w = tw(text, "s") + 24
                    p.rr((W - w) / 2.0, H - 40, w, 30, 6, 2, true)
                    txC(text, W / 2, H - 34, "s", BLACK)
                }
                if (nowS() < toastUntil)
                    toastText?.let {
                        val message = fit(it, W - 52)
                        val w = tw(message) + 28
                        val x = kotlin.math.floor((W - w) / 2.0 + .5)
                        p.rr(x, H - 44, w, 34, 6, 2, true)
                        p.rr(x + 3, H - 41, w - 6, 28, 4, 2, false)
                        tx(message, x + 14, H - 38, "m", BLACK)
                    }
            }
        }
        if (!locked && lit) {
            if (mediaLayer) TextChrome.media(grid)
            if (nowS() < toastUntil) toastText?.let { TextChrome.toast(grid, it) }
        }
        if (lit) drawPopup(g, grid)
        if (isText) {
            // Phone mirror of exactly the same cells sent through CharGridRenderer.
            val canvas = Canvas(source)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint = android.graphics.Paint().apply {
                color = GREEN
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = minOf(W.toFloat() / grid.cols / .61f, H.toFloat() / grid.rows * .8f)
            }
            val cellW = W.toFloat() / grid.cols
            val cellH = H.toFloat() / grid.rows
            grid.lines().forEachIndexed { y, line ->
                line.forEachIndexed { x, c ->
                    canvas.drawText(c.toString(), x * cellW, y * cellH + (cellH - paint.fontSpacing) / 2 - paint.ascent(), paint)
                }
            }
        }
        var greyLevels: ByteArray? = null
        val converted = if (isText) OneBit.convert(source) else GreyFrame.convert(source).let {
            if (lit && power.dim) dimmed(it) else it
        }.let {
            greyLevels = it.levels
            OneBit.Result(OneBit.pack(it.levels), it.bitmap)
        }
        source.recycle()
        dirty = false
        renderedDark = !lit
        return Frame(converted.bits, converted.bitmap, grid, greyLevels)
    }

    fun open(target: OpenTarget) {
        when (target.screen) {
            "home" -> goHome()
            "calls" -> openCalls()
            "call-missed" -> push(ReaderView(this, "missed-call", {
                listOf(target.args["name"].orEmpty(), target.args["number"].orEmpty()).filter { it.isNotBlank() }.distinct().joinToString("\n")
            }, "Missed call", "phone", right = "Phone"))
            "t3-thread", "t3thread" -> {
                push(T3Root(this))
                val machine = target.args["machine"]
                val id = target.args["id"] ?: target.args["threadId"]
                if (machine != null && id != null) push(T3Thread(this, ThreadKey(machine, id)))
            }
            "beeper-thread" -> {
                push(Beeper(this))
                target.args["chatId"]?.let { push(BeeperThread(this, it)) }
            }
            else -> push(app(target.screen))
        }
    }

    fun app(id: String): View =
        when (id) {
            "joy" -> Joy(this)
            "calls" -> (data.calls.snapshot.ringing ?: data.calls.snapshot.ongoing)?.let { PhoneCallScreen(this, it.id) } ?: PhoneRoot(this, PhoneTab.Recents)
            "phone" -> PhoneRoot(this)
            "beeper" -> Beeper(this)
            "media",
            "np" -> NowPlayingScreen(this)
            "t3" -> T3Root(this)
            "calendar" -> CalendarScreen(this)
            "notifications",
            "notif" -> Notifications(this)
            "people" -> People(this)
            "report" -> Report(this)
            "health" -> Health(this)
            "ha" -> HomeAssistant(this)
            "docs" -> Docs(this)
            "teleprompter" -> Teleprompter(this)
            "memo" -> VoiceMemo(this)
            "status" -> Status(this)
            "settings" -> Settings(this)
            "clock" -> ClockHub(this)
            "timer" -> TimerScreen(this)
            "stopwatch" -> StopwatchScreen(this)
            "alarms" -> AlarmsScreen(this)
            else -> Home(this)
        }

    init { registerBeeperTarget(this) }
    private val unsubscribeNotifications = registerNotificationTargets(this)

    fun close() {
        unsubscribePower(); unsubscribeStock()
        unsubscribe()
        unsubscribeCalls()
        unsubscribeNotifications()
        unsubscribeClock()
        unsubscribeListening()
        callReturn?.forEach { it.onClose() }
        stack.forEach { it.onClose() }
        scope.cancel()
        data.close()
    }

    /** Light the lens for at least [ms] (then the normal idle fade). A locked lens stays dark (only popups may light it). */
    fun wake(reason: String, ms: Long = 0) {
        power.wake(reason, ms)
        requestRender()
    }

    /** Darken the lens now (debug bridge, explicit sleep). */
    fun sleep(reason: String = "sleep") {
        io.lampyr.glasses.debug.Trace.log("Power", "Sleep requested reason=$reason")
        power.sleep()
        requestRender()
    }

    /** Accepted input while lit restarts the idle timer. */
    fun poke() {
        val wasDim = power.dim
        power.poke()
        if (wasDim) requestRender() else onPowerActivity()
    }

    /** What a screen needs: live dictation, calls, teleprompter, the assistant call hold the lens lit. */
    val keepAwake get() = !locked && stack.isNotEmpty() && (top.keepAwake || talkCapture != null ||
        (powerStore.prefs.memoKeepAwake && data.memos.snapshot.recording))

    /** Advance the idle fade and popup expiry. Returns ms until the next check, or null when nothing is pending. */
    fun powerTick(): Long? {
        val wasAwake = awake
        val wasDim = power.dim
        var next = power.tick(keepAwake)
        val card = popupCard
        if (card != null) {
            val left = popupUntil - powerNow()
            if (left <= 0) popupCard = null
            else next = minOf(next ?: left, left)
        }
        if (wasAwake != awake || wasDim != power.dim || (card != null && popupCard == null)) requestRender()
        return next
    }

    /** Unlock (the − button). Locked longer than power.lockHomeMs returns to Home instead of the previous screen. */
    fun unlock() {
        val since = lockedAtMs
        locked = false
        lockedAtMs = null
        val limit = powerStore.prefs.lockHomeMs
        // A the assistant call survives a lock of any length: going Home would close its screen and hang it up.
        if (limit > 0 && since != null && powerNow() - since >= limit && callReturn == null && data.calls.snapshot.calls.isEmpty() && !data.joy.call.active) {
            io.lampyr.glasses.debug.Trace.log("Power", "Unlock after ${powerNow() - since} ms: back to Home")
            goHome()
        }
        wake("unlock")
    }

    /** Show a transient card over the current screen. A locked lens stays dark unless power.popupWhenLocked. */
    fun popup(card: PopupCard) {
        if (locked && !powerStore.prefs.popupWhenLocked) {
            io.lampyr.glasses.debug.Trace.log("Power", "Popup suppressed while locked")
            return
        }
        popupCard = card
        popupUntil = powerNow() + card.ms.coerceAtLeast(500)
        wake("popup", card.ms)
    }

    fun dismissPopup() {
        if (popupCard == null) return
        popupCard = null
        power.release()
        requestRender()
    }

    /** Y on a visible popup. */
    fun selectPopup(): Boolean {
        val card = popupCard ?: return false
        popupCard = null
        power.release()
        val opened = openTarget(card.app, card.target)
        requestRender()
        return opened
    }

    /** Open [app] (and an app-specific [target]) through [targetOpeners], falling back to the app's root screen. */
    fun openTarget(app: String?, target: String?): Boolean {
        if (app.isNullOrBlank()) return false
        targetOpeners[app]?.let { return it(target) }
        if (app !in DEFAULT_APP_ORDER && app !in setOf("home", "calls", "notif", "np")) return false
        open(OpenTarget(app))
        return true
    }

    private fun syncPower() {
        val lit = awake
        if (lastLit != lit) {
            lastLit = lit
            io.lampyr.glasses.debug.Trace.log("Power", "Lens ${if (lit) "lit" else "dark"} reason=${power.lastWakeReason}")
            onDisplayAwake(lit)
        }
        onPowerActivity()
    }

    private fun dimmed(frame: GreyFrame.Result): GreyFrame.Result {
        val levels = ByteArray(frame.levels.size) { kotlin.math.round(frame.levels[it] * PowerPrefs.DIM_LEVEL).toInt().toByte() }
        val pixels = IntArray(levels.size) { BLACK or (levels[it].toInt() * 17 shl 8) }
        return GreyFrame.Result(levels, Bitmap.createBitmap(pixels, frame.bitmap.width, frame.bitmap.height, Bitmap.Config.ARGB_8888))
    }

    private fun drawPopup(g: Gfx, grid: TextGrid) {
        val card = popupCard?.takeIf { popupVisible } ?: return
        val hint = if (locked) "B dismiss" else if (card.app != null) "Y open  B dismiss" else "B dismiss"
        if (isText) {
            val rows = grid.rows
            val body = gridLines(card.body, grid.cols - 1).take((rows - 3).coerceAtLeast(1))
            val top = ((rows - body.size - 2) / 2).coerceAtLeast(0)
            for (r in top until (top + body.size + 2).coerceAtMost(rows)) grid.put(0, r, " ".repeat(grid.cols))
            grid.put(0, top, TextChrome.fit("[" + card.title + "]", grid.cols))
            body.forEachIndexed { i, line -> if (top + 1 + i < rows) grid.put(1, top + 1 + i, line) }
            if (hint.isNotEmpty() && top + body.size + 1 < rows) grid.put(0, top + body.size + 1, TextChrome.fit(hint, grid.cols))
            return
        }
        with(g) {
            val x = 40.0; val y = 30.0; val w = W - 80.0; val h = H - 60.0
            p.fr(x, y, w, h, BLACK)
            p.rr(x, y, w, h, 10, 2, false)
            var tx0 = x + 16
            card.icon?.let { icon(it, tx0, y + 14, 26); tx0 += 36 }
            tx(fit(card.title, x + w - 16 - tx0, "m"), tx0, y + 14, "m")
            var line = y + 14 + lh("m") + 6
            layout(card.body, w - 32, "s").take(3).forEach { drawLine(it, x + 16, line, "s"); line += lh("s") }
            if (hint.isNotEmpty()) tx(hint, x + 16, y + h - 14 - lh("s"), "s", tone = SECONDARY)
        }
    }
}

/** A live hold-to-talk capture: when it started ([Ui.nowS]) and the dictation screen that shows it (null = the assistant). */
data class TalkCapture(val since: Double, val owner: View?)
