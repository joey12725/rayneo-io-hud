package io.lampyr.glasses.input

import io.lampyr.glasses.debug.Trace

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*

interface Scheduled {
    fun cancel()
}

fun interface Scheduler {
    fun after(delayMs: Long, block: () -> Unit): Scheduled
}

class HandlerScheduler : Scheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun after(delayMs: Long, block: () -> Unit): Scheduled {
        val task = Runnable(block)
        handler.postDelayed(task, delayMs)
        return object : Scheduled {
            override fun cancel() {
                handler.removeCallbacks(task)
            }
        }
    }
}

interface MediaControl {
    fun toggle()

    fun direction(direction: String)
}

class StoreMediaControl(private val ui: Ui) : MediaControl {
    override fun toggle() {
        val playing = ui.data.media.nowPlaying?.playing == true
        ui.perform(if (playing) "Paused" else "Playing") { ui.data.media.control("toggle") }
    }

    override fun direction(direction: String) {
        val m = ui.data.media.nowPlaying ?: return
        when (direction) {
            "up",
            "down" ->
                ui.perform {
                    ui.data.media.control(
                        "volume",
                        (m.volume + if (direction == "up") 1 else -1).coerceIn(0, 15).toDouble(),
                    )
                }
            "left",
            "right" -> {
                val forward = direction == "right"
                ui.perform(if (m.source != "spotify") if (forward) "+30 s" else "−30 s" else null) {
                    ui.data.media.control(
                        if (m.source == "spotify") if (forward) "next" else "previous" else "seek",
                        if (forward) 30.0 else -30.0,
                    )
                }
            }
        }
    }
}

class InputEngine(
    private val ui: Ui,
    private val dictation: Dictation,
    private val media: MediaControl = StoreMediaControl(ui),
    private val scheduler: Scheduler = HandlerScheduler(),
) {
    companion object {
        val BIND =
            mapOf(
                "y" to "sel",
                "b" to "back",
                "x" to "act",
                "a" to "np",
                "l" to "talk",
                "l2" to "quiet",
                "r" to "notif",
                "r2" to "media",
                "minus" to "lock",
                "plus" to "plus",
                "star" to "apps",
                "heart" to "people",
                "up" to "up",
                "down" to "down",
                "left" to "left",
                "right" to "right",
            )
        val HOLDABLE = setOf("back", "media", "plus", "lock")
        const val HOLD_MS = 450L
        const val STALE_MS = 15_000L
        const val TALK_CAP_MS = 120_000L
        /** Actions that act even when their press wakes a dark lens with power.firstPressActs off. */
        val WAKE_ACTS = setOf("talk", "lock", "media")
    }

    var source = "touch"
    var lastKeyEvent: String? = null; private set
    var orientation = "cw"

    private data class Press(
        var held: Boolean = false,
        /** The screen override that owns this press (requirement 15.3); a momentary one gets down/up/cancel. */
        var override: ButtonOverride? = null,
        var ignored: Boolean = false,
        var holdable: Boolean = false,
        var shortcutTap: Boolean = false,
        var shortcutHold: String? = null,
        var timer: Scheduled? = null,
        var stale: Scheduled? = null,
    )

    private val Press.momentary get() = override?.claim?.momentary == true

    private val down = mutableMapOf<String, Press>()
    private val physical = mutableMapOf<String, String>()
    private var layerUsed = false
    /** The screen of the current hold-to-talk session (live capture or its pending transcription); null for the assistant screen. */
    private var talkScreen: DictationScreen? = null
    /** The L press that started the live capture. Its release always ends the capture, whatever screen is up. */
    private var talkPress: Press? = null
    private var joyTalk = false
    private var talkActive = false
    private var talkQualified = false
    private var talkMinimum: Scheduled? = null
    private var talkCap: Scheduled? = null
    var onTalkCap: () -> Unit = {}
    /** A the assistant call owns the microphone: one starting during a capture ends the capture cleanly. */
    private val unsubscribeData = ui.data.addChangeListener {
        if (talkActive && ui.data.joy.call.active) {
            talkScreen?.takeIf { it.phase == "listen" }?.event(DictationEvent.Error("Dictation stopped: ${io.lampyr.glasses.settings.Names.assistant} call"))
            if (joyTalk) ui.flash("Dictation stopped: ${io.lampyr.glasses.settings.Names.assistant} call")
            cancelAudio()
        }
    }
    /** Change-only GATT reports have an explicit disconnect release instead of a stale key timer. */
    var expireHeldKeys = true

    fun logical(direction: String) =
        if (orientation == "cw")
            mapOf("up" to "right", "right" to "down", "down" to "left", "left" to "up")[direction]
                ?: direction
        else direction

    /**
     * Touch/keyboard test callers supply logical arrows; controller callers pass logicalDir=false.
     */
    /** Called on every accepted press (not while locked); the glasses transport uses it to reclaim the display. */
    var onAnyPress: (() -> Unit)? = null
    var onInputState: ((Boolean) -> Unit)? = null
    val hasKeysDown get() = down.isNotEmpty()

    /** A report or key event confirms the source is alive. L (the mic) is ended only by release/lifecycle. */
    fun confirmDown() {
        down.forEach { (letter, state) ->
            state.stale?.cancel()
            if (expireHeldKeys && letter != "l" && !state.momentary) state.stale = scheduler.after(STALE_MS) {
                if (down[letter] === state) abandon(letter)
            }
        }
    }

    private fun abandon(letter: String) {
        val state = down.remove(letter) ?: return
        if (state.momentary) state.override?.onCancel?.invoke()
        state.timer?.cancel(); state.stale?.cancel()
        physical.entries.removeAll { it.value == letter }
        if (letter == "r2") { ui.mediaLayer = false; ui.requestRender() }
        onInputState?.invoke(hasKeysDown)
    }

    /** Lost releases must stop/submit PTT, but must never manufacture back/plus/media taps. */
    fun releaseAll() {
        down.keys.toList().forEach(::abandon)
        physical.clear()
        talkUp()
    }

    fun press(id: String, logicalDir: Boolean = true) {
        if (id !in BIND) return
        confirmDown()
        val letter = if (logicalDir) id else logical(id)
        lastKeyEvent = "press letter=$letter source=$source"
        Trace.log("Input", lastKeyEvent!!)
        if (!logicalDir) physical[id] = letter
        if (down[letter]?.momentary == true) {
            // GATT cannot detect a lost key-up. A fresh down begins a fresh hold of a momentary override.
            abandon(letter)
            if (!logicalDir) physical[id] = letter
        }
        if (letter == "l" && letter in down) {
            // A fresh L down while L still looks held: its release was lost (GATT cannot report one, a grab can drop
            // one). A live capture must never become unstoppable, so this press is the stop; its own release is inert.
            val stuck = down.remove(letter)!!
            stuck.timer?.cancel(); stuck.stale?.cancel()
            if (talkActive && stuck === talkPress) {
                talkQualified = true
                talkUp()
                down[letter] = Press(ignored = true)
                onInputState?.invoke(true)
                return
            }
        }
        if (letter in down) return
        val state = Press()
        down[letter] = state
        confirmDown()
        onInputState?.invoke(true)
        val action = BIND[letter] ?: return
        if (ui.ignoreCallInput(action) && !(shortcutsAllowed() &&
                (ui.shortcuts.action(letter,true)!=null || ui.shortcuts.action(letter,false)!=null))) { state.ignored = true; return }
        if (ui.locked && action != "lock" && ui.data.joy.call.active && !ui.popupVisible) {
            // the assistant call on a locked (dark) lens: any button unlocks and lights it; that press does nothing else,
            // so a stray B never hangs up. The call itself kept running while locked.
            state.ignored = true
            ui.unlock()
            return
        }
        if (ui.locked && action != "lock") {
            state.ignored = true
            if (action == "back" && ui.popupVisible) ui.dismissPopup() // B clears a popup shown on a locked lens; the lock stays.
            return
        }
        onAnyPress?.invoke()
        if (!ui.locked && !ui.awake && action != "lock") { // Locking a dark lens just locks; it does not light it first.
            // Any button wakes a dark lens. Talk, lock and the media layer always act; others act unless
            // power.firstPressActs is off, in which case this press only wakes.
            ui.wake("button:$letter")
            // On the assistant call screen the first press only wakes (B there is hang-up).
            if ((!ui.powerStore.prefs.firstPressActs || ui.top is JoyCall) && action !in WAKE_ACTS) { state.ignored = true; return }
        } else if (!ui.locked) ui.poke()
        if (!ui.locked && ui.popupVisible && (action == "sel" || action == "back" || action == "act")) {
            state.ignored = true
            if (action == "sel") ui.selectPopup() else if (action == "act") ui.dismissPopupNotification() else ui.dismissPopup()
            return
        }
        val claimed = ui.top.overrides().firstOrNull { it.letter == letter }
        if (claimed != null && claimed.claim.momentary) {
            state.override = claimed
            state.stale?.cancel();state.stale=null
            claimed.onDown()
            state.ignored = true // A momentary override owns the button before any global action or shortcut.
            ui.requestRender()
            return
        }
        state.override = claimed
        if (action == "talk") {
            if (ui.data.calls.snapshot.calls.isNotEmpty()) { state.ignored = true; ui.flash("Microphone reserved for phone call"); return }
            talkDown()
            if (talkActive) {
                talkPress = state
                talkQualified = false
                talkMinimum = scheduler.after(HOLD_MS) { talkQualified = true }
                talkCap = scheduler.after(TALK_CAP_MS) {
                    talkQualified = true
                    talkUp()
                    abandon("l")
                    onTalkCap()
                }
            }
            return
        }
        if (action == "media") {
            ui.mediaLayer = true
            layerUsed = false
            ui.requestRender()
        } else if (ui.mediaLayer && action in listOf("up", "down", "left", "right")) {
            media.direction(action)
            layerUsed = true
            state.ignored = true
            ui.requestRender()
            return
        }
        if (shortcutsAllowed()) {
            state.shortcutTap = ui.shortcuts.action(letter,false) != null
            state.shortcutHold = ui.shortcuts.action(letter,true)?.id
        }
        state.holdable = action in HOLDABLE || state.shortcutHold != null || claimed?.onHold != null
        if (state.holdable)
            state.timer =
                scheduler.after(HOLD_MS) {
                    if (!ui.locked && down[letter] === state) {
                        if (state.shortcutHold != null) {
                            // Only a hold that really runs its shortcut consumes the press; otherwise the release still taps.
                            if(shortcutsAllowed() && ui.shortcuts.action(letter,true)?.id==state.shortcutHold) { state.held = true; ui.shortcuts.run(letter,true,::shortcutsAllowed) }
                        } else if (state.override?.onHold != null) { state.held = true; overrideHold(state.override!!) }
                        else { state.held = true; hold(action) }
                    }
                }
        else {
            if (!state.shortcutTap || !ui.shortcuts.run(letter,false,::shortcutsAllowed)) tap(action, state.override)
        }
    }

    fun release(id: String, logicalDir: Boolean = true) {
        confirmDown()
        val letter = if (logicalDir) id else physical.remove(id) ?: logical(id)
        lastKeyEvent = "release letter=$letter source=$source"
        Trace.log("Input", lastKeyEvent!!)
        val state = down.remove(letter) ?: return
        state.timer?.cancel()
        state.stale?.cancel()
        onInputState?.invoke(hasKeysDown)
        if (letter == "l" && state === talkPress) {
            // Wherever the UI is now (another screen, a popup, locked), the release of the talk press ends its capture.
            talkUp()
            return
        }
        if (state.momentary) {
            state.override?.onUp?.invoke()
            ui.requestRender()
            return
        }
        if (state.ignored) return
        val action = BIND[letter] ?: return
        if (ui.locked && action != "lock") return
        if (!ui.locked) ui.poke() // A long hold counts as activity from its release.
        if (action == "talk") {
            talkUp()
            return
        }
        if (action == "media") {
            ui.mediaLayer = false
            if (!state.held && !layerUsed) media.toggle()
            ui.requestRender()
            return
        }
        // While locked no hold fires, so − always just unlocks: a pocket press never flips recording unseen.
        if (state.holdable && !state.held) {
            if (!state.shortcutTap || !shortcutsAllowed() || !ui.shortcuts.run(letter,false,::shortcutsAllowed)) tap(action, state.override)
        }
    }

    private fun shortcutsAllowed() = !ui.locked && !talkActive && ui.top !is DictationScreen && !ui.top.consumesShortcuts

    private fun tap(action: String, override: ButtonOverride? = null) {
        if (ui.ignoreCallInput(action)) return
        io.lampyr.glasses.debug.Latency.input(action) // Press-to-COMMIT instrumentation (requirement 1.4).
        // A screen that claimed this button's tap (the assistant's L2 quiet wait) gets it instead of the global action.
        if (override != null && override.claim.tap && action != "back") { override.onTap(); ui.requestRender(); return }
        when (action) {
            "np" -> ui.push(NowPlayingScreen(ui))
            "notif" -> ui.push(Notifications(ui))
            "apps" -> ui.push(Apps(ui))
            "people" -> ui.push(QuickPeople(ui))
            "lock" -> {
                if (ui.locked) ui.unlock() else ui.locked = true
                if (ui.locked) {
                    ui.top.overrides().filter { it.claim.momentary }.forEach { it.onCancel() }
                    down
                        .filterKeys { it != "minus" }
                        .values
                        .forEach {
                            it.timer?.cancel()
                            it.ignored = true
                        }
                    ui.mediaLayer = false
                    // Locking ends a live capture (its screen is resolved wherever it is); a pending transcription
                    // has no microphone open and finishes normally.
                    if (talkActive) cancelAudio()
                } else ui.flash("Unlocked")
            }
            "quiet" -> Unit // L2 has no global tap; the assistant screen claims it for quiet wait (Joy.overrides), shortcuts may take it.
            "back" -> {
                if (!ui.top.key("back")) ui.pop()
            }
            else -> ui.top.key(action)
        }
        ui.requestRender()
    }

    private fun overrideHold(override: ButtonOverride) {
        if (ui.ignoreCallInput(BIND[override.letter] ?: return)) return
        override.onHold?.invoke()
        ui.requestRender()
    }

    private fun hold(action: String) {
        if (ui.ignoreCallInput(action)) return
        when (action) {
            "back" -> ui.goHome()
            "plus" -> ui.top.key("plusHold")
            // − tap locks on release; − hold (unlocked only) mutes passive listening (AMB-14).
            "lock" -> ui.toggleListening()
        }
        ui.requestRender()
    }

    private fun talkDown() {
        if (ui.data.joy.call.active) { ui.flash("${io.lampyr.glasses.settings.Names.assistant} call active"); return }
        val view = ui.top
        if (view.talkTap()) return
        if (view is DictationScreen) {
            // No session is live here (a live one ends on any L press or release), so a listening screen, or one
            // waiting for a transcription that is not the current one, is orphaned: recover it instead of ignoring L.
            if (view.phase == "listen" || (view.phase != "preview" && view !== talkScreen)) view.discardRecording()
            if (view.phase != "preview") return
            begin(view)
            view.listen()
            dictation.start(view.target.target, view.target.context, view::event)
            return
        }
        val target = view.talk
        if (target == null || target.direct) {
            if (view.kind != "joy") ui.push(Joy(ui))
            begin(null)
            joyTalk = true
            ui.refresh { ui.data.joy.setListening(true) }
            dictation.start("joy") { event ->
                if (event is DictationEvent.Done) {
                    ui.joyPage = 0
                    ui.perform { ui.data.joy.send(event.text, ui.quiet) }
                } else if (event is DictationEvent.Error) {
                    ui.refresh { ui.data.joy.setListening(false) }
                    ui.flash(event.message)
                }
            }
            return
        }
        val screen = DictationScreen(ui, target)
        ui.push(screen)
        begin(screen)
        screen.listen()
        dictation.start(target.target, target.context, screen::event)
    }

    /**
     * One global capture session, owned here rather than by a screen: [screen] shows it (null = the assistant). A new
     * session replaces the dictation, so an earlier screen still waiting for its transcription is told it was
     * cancelled instead of spinning forever. Closing [screen] (B, Home, a cleared stack) cancels its session.
     */
    private fun begin(screen: DictationScreen?) {
        talkScreen?.takeIf { it !== screen && it.phase in setOf("trans", "enh") }?.event(DictationEvent.Error("Transcription cancelled"))
        talkScreen = screen
        talkActive = true
        screen?.onCancel = { if (talkScreen === screen) { talkScreen = null; cancelAudio() } }
        ui.talkCapture = TalkCapture(ui.nowS(), screen)
    }

    private fun talkUp() {
        if (!talkActive) return
        talkMinimum?.cancel(); talkMinimum = null
        talkCap?.cancel(); talkCap = null
        if (!talkQualified) {
            cancelAudio()
            return
        }
        talkActive = false
        talkPress = null
        joyTalk = false
        ui.talkCapture = null
        // The microphone is closed from here on: never leave the timer running while the upload starts.
        talkScreen?.takeIf { it.phase == "listen" }?.event(DictationEvent.Transcribing)
        dictation.stop()
    }

    /** Ends the live capture or pending transcription without a result; a still-listening screen is resolved. */
    private fun cancelAudio() {
        val screen = talkScreen
        talkMinimum?.cancel(); talkMinimum = null
        talkCap?.cancel(); talkCap = null
        dictation.cancel()
        if (joyTalk) ui.refresh { ui.data.joy.setListening(false) }
        joyTalk = false
        talkActive = false
        talkPress = null
        talkScreen = null
        ui.talkCapture = null
        // Wherever it is in the stack: keep earlier text as a preview, otherwise remove the empty listening screen.
        if (screen != null && screen.phase == "listen") {
            if (screen.text.isNotBlank()) screen.discardRecording() else ui.remove(screen)
        }
    }

    fun callInterrupted() {
        down.values.forEach { if (it.momentary) it.override?.onCancel?.invoke() }
        down.values.forEach { it.timer?.cancel(); it.ignored = true }
        ui.mediaLayer = false
        talkScreen?.takeIf { it.phase != "preview" }?.event(DictationEvent.Error("Dictation interrupted by phone call"))
        cancelAudio()
    }

    fun close() {
        unsubscribeData()
        releaseAll()
    }

    /** Agent control: lock or unlock exactly as a − tap does (same side effects); no-op when already in that state. */
    fun setLocked(locked: Boolean) {
        if (ui.locked != locked) tap("lock")
    }
}
