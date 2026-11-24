package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.glasses.MemoryStockSettings
import io.lampyr.glasses.glasses.StockSettings
import io.lampyr.glasses.glasses.protocol.Commands
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.TextGrid
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Requirement 1.1 (asleep by default, idle fade), popups, lock return and the power settings screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayPowerTest {
    // ---------- Pure state machine ----------
    private class Clock(var ms: Long = 1_000_000) { fun get() = ms }

    @Test fun asleepByDefaultAndFadesAfterIdle() {
        val clock = Clock(); var prefs = PowerPrefs()
        val power = DisplayPower(clock::get) { prefs }
        assertFalse(power.awake)
        assertNull(power.tick(false))
        assertTrue(power.wake("button"))
        assertEquals(15_000L, power.tick(false)) // the user: 12-15 s, not 2 s (2026-11-18).
        clock.ms += 1_500; power.poke() // Activity restarts the idle time.
        clock.ms += 14_999; assertEquals(1L, power.tick(false)); assertTrue(power.awake)
        clock.ms += 1; assertNull(power.tick(false)); assertFalse(power.awake)
        assertEquals("button", power.lastWakeReason)
        prefs = PowerPrefs.ALWAYS_ON
        assertTrue(DisplayPower(clock::get) { prefs }.awake)
    }

    @Test fun keepAwakeHoldsAndWakeMinimumOutlastsIdle() {
        val clock = Clock(); val power = DisplayPower(clock::get) { PowerPrefs(idleMs = 1_000) }
        power.wake("call")
        clock.ms += 60_000
        assertEquals(PowerPrefs.KEEP_AWAKE_POLL_MS, power.tick(true)); assertTrue(power.awake)
        clock.ms += 999; assertTrue(power.tick(false)!! > 0) // Idle restarts when the keep-awake screen ends.
        clock.ms += 1; power.tick(false); assertFalse(power.awake)
        power.wake("popup", 6_000)
        clock.ms += 5_999; power.tick(false); assertTrue(power.awake)
        clock.ms += 1; power.tick(false); assertFalse(power.awake)
        power.wake("popup", 6_000); power.release() // A dismissed popup keeps only the normal idle time.
        clock.ms += 1_000; power.tick(false); assertFalse(power.awake)
    }

    @Test fun optionalDimStepThenBlackAndNeverMeansAlwaysLit() {
        val clock = Clock(); var prefs = PowerPrefs(idleMs = 1_000, dimStep = true)
        val power = DisplayPower(clock::get) { prefs }
        power.wake("button")
        clock.ms += 1_000; assertEquals(PowerPrefs.DIM_MS, power.tick(false)); assertTrue(power.dim); assertTrue(power.awake)
        power.poke(); assertFalse(power.dim)
        clock.ms += 1_000; power.tick(false); clock.ms += PowerPrefs.DIM_MS; power.tick(false)
        assertFalse(power.awake)
        prefs = PowerPrefs(idleMs = 0); power.wake("x"); clock.ms += 3_600_000
        assertNull(power.tick(false)); assertTrue(power.awake)
    }

    @Test fun preferencesTolerateStoredTypesAndClamp() {
        assertEquals(PowerPrefs(), PowerPrefs.read(emptyMap<String, Any>()))
        val p = PowerPrefs.read(mapOf(PowerPrefs.IDLE to 5000, PowerPrefs.DIM to "on", PowerPrefs.FIRST_PRESS to "false",
            PowerPrefs.LOCK_HOME to "300000", PowerPrefs.LEGACY_LOOK_UP_WAKE to 0, PowerPrefs.BACKEND to "PARK"))
        assertEquals(PowerPrefs(idleMs = 5000, dimStep = true, firstPressActs = false, lockHomeMs = 300_000, lookUp = LookUp.NOTHING, backend = "park"), p)
        assertEquals(60_000L, PowerPrefs.read(mapOf(PowerPrefs.IDLE to 90_000L)).idleMs)
        assertEquals(1_000L, PowerPrefs.read(mapOf(PowerPrefs.IDLE to 10)).idleMs)
        assertEquals("auto", PowerPrefs.read(mapOf(PowerPrefs.BACKEND to "glow")).backend)
        assertEquals("Auto stays on the safe black backend until experiment E1", "black", PowerPrefs().sleepBackend)
        assertEquals("park", PowerPrefs(backend = "park").sleepBackend)
        assertEquals("black", PowerPrefs(lookUp = LookUp.NOTHING).sleepBackend)
        assertEquals("black", PowerPrefs(backend = "black").sleepBackend)
        assertEquals(listOf("5 s", "10 s", "12 s", "15 s", "20 s", "30 s", "60 s", "Never"), PowerPrefs.IDLE_OPTIONS.map(PowerPrefs::idleLabel))
        assertEquals(listOf("Off", "30 s", "1 min", "5 min"), PowerPrefs.LOCK_HOME_OPTIONS.map(PowerPrefs::lockHomeLabel))
    }

    @Test fun stockSyncMirrorsLookUpWakeOnlyWhenKnownAndShortensStockSleepForPark() {
        val known = StockSettings(autoLockTime = 15, headUp = false, headUpDegrees = 40, wakeKnown = true)
        val park = PowerPrefs(backend = "park")
        assertEquals("Default (black) never touches stock auto-sleep", known.copy(headUp = true), stockSyncFor(PowerPrefs(), known))
        assertNull(stockSyncFor(PowerPrefs(), StockSettings(autoLockTime = 15)))
        assertEquals(known.copy(headUp = true, autoLockTime = PARK_AUTO_LOCK_S), stockSyncFor(park, known))
        assertEquals(StockSettings(autoLockTime = PARK_AUTO_LOCK_S), stockSyncFor(park, StockSettings(autoLockTime = 15)))
        assertNull(stockSyncFor(PowerPrefs(lookUp = LookUp.NOTHING), known.copy(headUp = false)))
        for (mode in listOf(LookUp.ON, LookUp.OFF, LookUp.TOGGLE))
            assertEquals("Stock head-up stays on for $mode", known.copy(headUp = true), stockSyncFor(PowerPrefs(lookUp = mode), known))
        assertEquals(known, stockSyncFor(PowerPrefs(lookUp = LookUp.NOTHING), known.copy(headUp = true)))
        assertNull(stockSyncFor(park, known.copy(headUp = true, autoLockTime = PARK_AUTO_LOCK_S)))
    }

    // ---------- Ui integration ----------
    private class Fixture(prefs: PowerPrefs = PowerPrefs(idleMs = 2_000)) {
        var seconds = 100.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_763_000_000_000L }, scope)
        val power = MemoryPowerStore(prefs)
        val stock = MemoryStockSettings()
        val ui = Ui(data, scope = scope, nowS = { seconds }, nowMs = { 1_763_000_000_000L }, powerStore = power, glassesSettings = stock)
        var dirties = 0
        val awakeCalls = mutableListOf<Boolean>()
        init {
            ui.onDirty = { dirties++ }
            ui.onDisplayAwake = { awakeCalls += it }
        }
        fun textFrame(): String { ui.display = Display.Text(); return ui.render().text.lines().joinToString("\n") }
        fun advance(ms: Long) { seconds += ms / 1000.0; ui.powerTick() }
        fun close() { ui.close(); scope.cancel() }
    }

    private fun fixture(prefs: PowerPrefs = PowerPrefs(idleMs = 2_000), block: Fixture.() -> Unit) { val f = Fixture(prefs); try { f.block() } finally { f.close() } }

    @Test fun backgroundWorkNeverLightsADarkLens() = fixture {
        assertFalse(ui.awake)
        ui.render() // The dark frame is on the glasses.
        dirties = 0
        ui.flash("Message sent")
        ui.open(OpenTarget("beeper"))
        ui.requestRender()
        assertFalse(ui.awake)
        assertEquals(0, dirties)
        assertEquals("beeper", ui.top.id) // Gateway navigation happens, unseen.
        assertFalse(ui.needsAnimation)
    }

    @Test fun displayModeChangeWhileDarkSendsOneDarkFrameInTheNewMode() = fixture {
        ui.render(); dirties = 0
        ui.requestRender(); assertEquals(0, dirties)
        ui.display = Display.Text()
        assertEquals("The new output mode needs its own black frame", 1, dirties)
        assertTrue(ui.render().text.lines().all { it.isBlank() })
        ui.requestRender(); assertEquals(1, dirties)
    }

    @Test fun bDismissesAPopupOnALockedLensAndKeepsTheLock() = fixture {
        val input = input()
        try {
            ui.locked = true
            ui.popup(PopupCard("Call from Mom", "Important", app = "beeper"))
            assertTrue(ui.awake)
            input.press("y"); input.release("y")
            assertTrue("Y does not act through the lock", ui.popupVisible)
            input.press("b"); input.release("b")
            assertFalse(ui.popupVisible); assertFalse(ui.awake); assertTrue(ui.locked)
        } finally { input.close() }
    }

    @Test fun wakeLightsThenFadesToABlackFrame() = fixture {
        ui.render()
        ui.wake("button")
        assertTrue(ui.awake); assertEquals(listOf(true), awakeCalls.takeLast(1))
        assertTrue("A lit Home frame has pixels", ui.render().bits.any { it != 0.toByte() })
        advance(1_999); assertTrue(ui.awake)
        advance(1); assertFalse(ui.awake)
        assertEquals(false, awakeCalls.last())
        val frame = ui.render()
        assertTrue("Dark frame must be all black", frame.bits.all { it == 0.toByte() })
    }

    @Test fun keepAwakeScreensHoldTheLens() = fixture {
        val prompter = Teleprompter(ui); ui.push(prompter); ui.wake("button")
        prompter.auto = true
        advance(10_000); assertTrue(ui.awake)
        prompter.auto = false
        advance(1_000); assertTrue(ui.awake)
        advance(1_000); assertFalse(ui.awake)
    }

    @Test fun popupWakesWithoutChangingStackAndYOpensTarget() = fixture {
        ui.push(Apps(ui)); val stack = ui.stack.toList()
        var opened: String? = null
        ui.targetOpeners["beeper"] = { target -> opened = target; true }
        ui.popup(PopupCard("Ana", "Lunch at noon?", icon = "beeper", app = "beeper", target = "chat-1"))
        assertTrue(ui.awake); assertTrue(ui.popupVisible); assertEquals(stack, ui.stack.toList())
        assertTrue(textFrame().contains("Ana"))
        assertTrue(ui.selectPopup())
        assertEquals("chat-1", opened); assertFalse(ui.popupVisible)
    }

    @Test fun popupExpiresAndBDismisses() = fixture {
        ui.popup(PopupCard("Timer", "Tea is ready", ms = 3_000))
        advance(2_999); assertTrue(ui.popupVisible)
        advance(1); assertFalse(ui.popupVisible); assertFalse(ui.awake) // Card gone and the idle time already passed: dark.
        ui.popup(PopupCard("Timer", "Again"))
        ui.dismissPopup(); assertFalse(ui.popupVisible)
        assertFalse(ui.openTarget(null, null))
        assertFalse(ui.openTarget("nonexistent-app", null))
        assertTrue(ui.openTarget("docs", null)); assertEquals("docs", ui.top.id)
    }

    @Test fun lockedLensShowsPopupsOnlyWhenAllowed() {
        fixture {
            ui.wake("button"); ui.locked = true
            assertFalse(ui.awake)
            ui.popup(PopupCard("Call from Mom", "Important"))
            assertTrue(ui.awake); assertTrue(ui.locked)
            assertTrue(textFrame().contains("Call from Mom"))
            advance(6_000); assertFalse(ui.awake); assertTrue(ui.locked)
        }
        fixture(PowerPrefs(popupWhenLocked = false)) {
            ui.locked = true
            ui.popup(PopupCard("Quiet", "Not shown"))
            assertFalse(ui.awake); assertFalse(ui.popupVisible)
        }
    }

    @Test fun unlockAfterMoreThanAMinuteReturnsHome() {
        fixture {
            ui.push(Apps(ui)); ui.push(Docs(ui))
            ui.locked = true; advance(30_000)
            ui.unlock()
            assertEquals("docs", ui.top.id); assertTrue(ui.awake) // Short lock: resume.
            ui.locked = true; advance(61_000)
            ui.unlock()
            assertEquals("home", ui.top.id); assertEquals(1, ui.stack.size)
        }
        fixture(PowerPrefs(lockHomeMs = 0)) {
            ui.push(Docs(ui)); ui.locked = true; advance(600_000); ui.unlock()
            assertEquals("docs", ui.top.id)
        }
    }

    // ---------- Buttons ----------
    private class Timers : Scheduler {
        override fun after(delayMs: Long, block: () -> Unit): Scheduled = object : Scheduled { override fun cancel() {} }
    }
    private class NoAudio : Dictation {
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) {}
        override fun stop() {}
        override fun cancel() {}
    }
    private class NoMedia : MediaControl { override fun toggle() {}; override fun direction(direction: String) {} }

    private fun Fixture.input() = InputEngine(ui, NoAudio(), NoMedia(), Timers())

    @Test fun firstPressWakesAndActsByDefault() = fixture {
        ui.push(Apps(ui)); val input = input()
        try {
            input.press("b"); input.release("b")
            assertTrue(ui.awake); assertEquals("home", ui.top.id)
            assertEquals("button:b", ui.power.lastWakeReason)
        } finally { input.close() }
    }

    @Test fun firstPressOnlyWakesWhenConfigured() = fixture(PowerPrefs(firstPressActs = false)) {
        ui.push(Apps(ui)); val input = input()
        try {
            input.press("b"); input.release("b")
            assertTrue(ui.awake); assertEquals("apps", ui.top.id)
            input.press("b"); input.release("b")
            assertEquals("home", ui.top.id)
        } finally { input.close() }
    }

    @Test fun lockOnADarkLensLocksWithoutLighting() = fixture {
        val input = input()
        try {
            input.press("minus"); input.release("minus")
            assertTrue(ui.locked); assertFalse(ui.awake); assertFalse(awakeCalls.contains(true))
        } finally { input.close() }
    }

    @Test fun popupButtonsSelectAndDismiss() = fixture {
        val input = input(); var opened = 0
        ui.targetOpeners["beeper"] = { opened++; true }
        try {
            ui.popup(PopupCard("Ana", "Hi", app = "beeper"))
            input.press("y"); input.release("y")
            assertEquals(1, opened); assertFalse(ui.popupVisible)
            ui.popup(PopupCard("Ana", "Hi", app = "beeper"))
            input.press("b"); input.release("b")
            assertEquals(1, opened); assertFalse(ui.popupVisible); assertEquals("home", ui.top.id)
        } finally { input.close() }
    }

    // ---------- Settings screens ----------
    private fun lines(view: View, cols: Int = 26, rows: Int = 7) = TextGrid(cols, rows, false).also { view.drawText(it); assertFalse(it.overflowed) }.lines()

    @Test fun powerSettingsCycleAndPersist() = fixture(PowerPrefs()) {
        val view = PowerSettings(ui); ui.push(view)
        view.cur = 0; view.key("sel")
        assertEquals(20_000L, power.prefs.idleMs)
        view.cur = view.rows().indexOfFirst { it.label == "Home after lock" }; view.key("sel")
        assertEquals(300_000L, power.prefs.lockHomeMs)
        view.cur = view.rows().indexOfFirst { it.label == "First press acts" }; view.key("sel")
        assertFalse(power.prefs.firstPressActs)
        assertTrue(lines(view).joinToString("\n").contains("Display & sleep"))
    }

    @Test fun headGestureAndPositionSettingsWriteStockCommands() = fixture {
        val gestures = HeadGestureSettings(ui); ui.push(gestures)
        gestures.cur = gestures.rows().indexOfFirst { it.label == "Look-up angle" }; gestures.key("sel")
        assertEquals(35, stock.current!!.headUpDegrees)
        gestures.cur = gestures.rows().indexOfFirst { it.label == "Look up" }
        gestures.key("sel"); assertEquals(LookUp.OFF, power.prefs.lookUp); assertTrue("OFF still needs stock head-up", stock.current!!.headUp)
        gestures.key("sel"); assertEquals(LookUp.TOGGLE, power.prefs.lookUp); assertTrue(stock.current!!.headUp)
        gestures.key("sel"); assertEquals(LookUp.NOTHING, power.prefs.lookUp); assertFalse(stock.current!!.headUp)
        gestures.cur = gestures.rows().indexOfFirst { it.label == "Nod / shake" }; gestures.key("sel")
        assertTrue(stock.current!!.headGestures)
        val position = DisplayPositionSettings(ui); ui.push(position)
        position.cur = 0; position.key("sel")
        assertEquals(5, stock.current!!.displayHeight)
        assertEquals(listOf("wakeup_config", "wakeup_config", "head_gestures", "display_config"),
            stock.written.map { kotlinx.serialization.json.Json.parseToJsonElement(it.envelope.json).let { j -> (j as kotlinx.serialization.json.JsonObject)["cmd"].toString().trim('"') } })
    }

    @Test fun stockRowsAreInertUntilTheGlassesReportThem() {
        val f = Fixture()
        try {
            val ui = Ui(f.data, scope = f.scope, nowS = { f.seconds }, glassesSettings = MemoryStockSettings(null))
            try {
                val gestures = HeadGestureSettings(ui)
                assertEquals(listOf(true, false, false, false), gestures.rows().map { it.selectable })
                assertEquals("Glasses not connected", gestures.rows()[1].sub)
                val position = DisplayPositionSettings(ui)
                assertTrue(position.rows().none { it.selectable })
                assertEquals("Glasses not connected", headGestureSummary(ui))
                assertEquals("B back", position.textHint())
            } finally { ui.close() }
        } finally { f.close() }
    }

    @Test fun stockWritesRefuseUnreadStructures() = runBlocking {
        val store = MemoryStockSettings(StockSettings(autoLockTime = 15))
        assertFalse(store.update { it.copy(headUp = true) }.ok)
        assertFalse(store.update { it.copy(displayHeight = 1, displayDistance = 1) }.ok)
        assertTrue(store.update { it.copy(autoLockTime = 5) }.ok)
        assertEquals(listOf(Commands.autoLockTime(5).envelope.json), store.written.map { it.envelope.json })
    }
}
