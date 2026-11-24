package io.lampyr.glasses

import android.content.Context
import android.content.ContextWrapper
import io.lampyr.glasses.ambient.CaptureExclusions
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Voice call: lock, compact layout, worker indicator, LifeLog exclusion and the avatar state map. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JoyCallLongCallsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
    private val epoch = Instant.parse("2026-11-12T20:00:00Z").toEpochMilli()
    /** Answers the call preflight at once (no OkHttp thread), so virtual time stays deterministic. */
    private fun callGateway() = object : GatewayClient("https://fixture.example", "synthetic-token") {
        override suspend fun get(path: String): JsonElement =
            if (path == "/assistant/call/status") obj("available" to JsonPrimitive(true)) else JsonObject(emptyMap())
    }

    private class Fixture(prefs: PowerPrefs = PowerPrefs()) {
        var seconds = 100.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 1_763_000_000_000L }, scope)
        val power = MemoryPowerStore(prefs)
        val ui = Ui(data, io.lampyr.glasses.render.avatar.AvatarPacks.fromAssets(RuntimeEnvironment.getApplication()), scope = scope, nowS = { seconds }, nowMs = { 1_763_000_000_000L }, powerStore = power)
        fun advance(ms: Long) { seconds += ms / 1000.0; ui.powerTick() }
        fun close() { ui.close(); scope.cancel() }
    }
    private fun fixture(prefs: PowerPrefs = PowerPrefs(), block: Fixture.() -> Unit) { val f = Fixture(prefs); try { f.block() } finally { f.close() } }
    private class Timers : Scheduler { override fun after(delayMs: Long, block: () -> Unit): Scheduled = object : Scheduled { override fun cancel() {} } }
    private class NoAudio : Dictation {
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) {}
        override fun stop() {}
        override fun cancel() {}
    }
    private class NoMedia : MediaControl { override fun toggle() {}; override fun direction(direction: String) {} }
    private fun Fixture.tap(input: InputEngine, key: String) { input.press(key); input.release(key) }

    // ---------- Requirement 2: lock never ends the call ----------
    @Test fun lockingForMinutesKeepsTheCallAndUnlockReturnsToIt() = fixture {
        ui.push(Joy(ui)); ui.push(JoyCall(ui))
        assertTrue(data.joy.call.active)
        ui.wake("button"); ui.locked = true
        assertFalse(ui.awake) // the lens goes dark
        advance(5 * 60_000) // far beyond power.lockHomeMs (60 s), which used to go Home and hang up
        assertTrue(data.joy.call.active)
        ui.unlock()
        assertTrue(data.joy.call.active); assertEquals("call", ui.top.id); assertTrue(ui.awake)
        // Without a call the same lock still returns Home.
        runBlocking { data.joy.endVoiceCall() }
        ui.locked = true; advance(5 * 60_000); ui.unlock()
        assertEquals("home", ui.top.id)
    }

    @Test fun anyButtonOnALockedCallOnlyWakesAndBNeverHangsUpThere() = fixture {
        ui.push(Joy(ui)); ui.push(JoyCall(ui))
        val input = InputEngine(ui, NoAudio(), NoMedia(), Timers())
        try {
            ui.wake("button"); tap(input, "minus"); assertTrue(ui.locked)
            tap(input, "b")
            assertFalse(ui.locked); assertTrue(ui.awake)
            assertTrue(data.joy.call.active); assertEquals("call", ui.top.id)
            // Lit and unlocked, B is the explicit hang-up.
            tap(input, "b")
            assertFalse(data.joy.call.active)
        } finally { input.close() }
    }

    @Test fun firstPressOnADarkCallScreenOnlyWakes() = fixture {
        ui.push(Joy(ui)); ui.push(JoyCall(ui))
        val input = InputEngine(ui, NoAudio(), NoMedia(), Timers())
        try {
            ui.sleep("test"); assertFalse(ui.awake)
            tap(input, "b")
            assertTrue(ui.awake); assertTrue(data.joy.call.active); assertEquals("call", ui.top.id)
        } finally { input.close() }
    }

    // ---------- Requirement 3: compact layout ----------
    @Test fun compactIsTheDefaultAndLeavesTheLensDarkOutsideTheCornerCard() = fixture(PowerPrefs.ALWAYS_ON) {
        assertEquals("compact", PowerPrefs().joyCallLayout)
        ui.push(Joy(ui)); val call = JoyCall(ui); ui.push(call)
        assertTrue(call.compact)
        val pixels = IntArray(W * H); ui.render().bitmap.getPixels(pixels, 0, W, 0, 0, W, H)
        val lit = pixels.indices.filter { (pixels[it] ushr 8 and 255) > 0 }
        assertTrue(lit.isNotEmpty())
        assertTrue("compact card stays in the bottom-left corner", lit.all { it / W >= BOTTOM - 72 && it % W < JoyCall.CARD_X + JoyCall.CARD_W + 4 })
        assertTrue("uses well under a third of the lens", lit.size < W * H / 12)
        // The JoyMenu row switches to the full layout and back.
        ui.pop(); ui.push(JoyMenu(ui))
        (ui.top as ListView).rows().first { it.label == "Call layout" }.open!!()
        assertEquals("full", power.prefs.joyCallLayout)
        ui.pop(); ui.push(JoyCall(ui)); assertFalse((ui.top as JoyCall).compact)
        val full = IntArray(W * H); ui.render().bitmap.getPixels(full, 0, W, 0, 0, W, H)
        assertTrue(full.indices.any { (full[it] ushr 8 and 255) > 0 && it / W < 60 })
        assertEquals("full", PowerPrefs.read(mapOf(PowerPrefs.JOY_CALL_LAYOUT to "FULL")).joyCallLayout)
        assertEquals("compact", PowerPrefs.read(mapOf(PowerPrefs.JOY_CALL_LAYOUT to "tiny")).joyCallLayout)
    }

    // ---------- Requirement 4/5: worker indicator and animations ----------
    @Test fun workerEventsShowTheSecondIndicatorAndClearAfterDone() = runTest {
        val gateway = callGateway()
        val socket = JoyCallSessionTest.Socket(); val audio = JoyCallSessionTest.Audio()
        val workers = mutableListOf<JoyWorker?>()
        lateinit var data: LiveDataStore
        data = LiveDataStore(context(), backgroundScope, gateway, { epoch }, { testScheduler.currentTime / 1000.0 }, false,
            { update -> JoyCallSession(socket, audio, JoyCallSessionTest.Exclusion(), { testScheduler.currentTime }, update,
                worker = { workers += it; data.callWorker(it) }) })
        try {
            assertTrue(data.joy.startVoiceCall().ok)
            runCurrent(); assertEquals("live", data.joy.call.mode)
            socket.listener.opened(); socket.event("ready"); runCurrent()
            socket.listener.text("""{"type":"agent","state":"tool","tool":"web_search","task":"weather in Springfield"}"""); runCurrent()
            assertEquals(JoyWorker("tool", "web_search", "weather in Springfield"), data.joy.call.worker)
            assertEquals(CallAnimations.VOICE["delegating"], data.joy.snapshot.anim)
            assertEquals("searching", CallAnimations.worker(data.joy.call.worker))
            socket.listener.text("""{"type":"agent","state":"done"}"""); runCurrent()
            assertEquals("done", data.joy.call.worker?.state)
            advanceTimeBy((WORKER_LINGER_S * 1000).toLong() + 10); runCurrent()
            assertNull(data.joy.call.worker)
            assertEquals(2, workers.size)
        } finally { data.close() }
        assertNull(data.joy.call.worker)
    }

    @Test fun everyCallStateIsCanonicalAndResolvesInTheBundledPack() {
        val used = CallAnimations.VOICE.values + CallAnimations.WORKER.values + CallAnimations.TOOLS.map { it.second }
        assertTrue("non-canonical: ${used.filterNot { it in io.lampyr.glasses.render.avatar.AvatarStates.ALL }}",
            used.all { it in io.lampyr.glasses.render.avatar.AvatarStates.ALL })
        // The activity states (former library ids 1..16) are all used by the call machine.
        val activity = io.lampyr.glasses.render.avatar.AvatarStates.ALL.take(16)
        assertTrue("unused activity states: ${activity - used.toSet()}", used.toSet().containsAll(activity))
        // The bundled placeholder draws every state, directly or through a fallback that is not plain idle.
        val manifest = io.lampyr.glasses.render.avatar.AvatarManifest.parse(File("src/main/assets/avatar/default/manifest.json").readText())
        used.forEach { assertTrue(it, manifest.resolve(it) in manifest.states) }
        listOf("listening", "thinking", "speaking", "working", "error", "happy").forEach { assertEquals(it, manifest.resolve(it)) }
        // The simulator ships a byte-identical copy of the bundled pack.
        listOf("manifest.json", "atlas.png").forEach {
            assertArrayEquals(it, File("src/main/assets/avatar/default/$it").readBytes(), File("../../simulator/avatar/default/$it").readBytes())
        }
    }

    @Test fun simulatorMirrorsTheCallAnimationTables() {
        val html = File("../../simulator/index.html").readText()
        fun table(name: String): Map<String, String> {
            val body = Regex("const $name=\\{([^}]*)\\}").find(html)!!.groupValues[1]
            return body.split(",").associate { it.substringBefore(":").trim() to it.substringAfter(":").trim().removeSurrounding("'") }
        }
        assertEquals(CallAnimations.VOICE, table("CALL_ANIM"))
        assertEquals(CallAnimations.WORKER, table("WORKER_ANIM"))
        val tools = Regex("\\[/([^/]+)/,'([a-z_]+)','([^']+)'\\]").findAll(html.substringAfter("const CALL_TOOLS=")).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        assertEquals(CallAnimations.TOOLS.map { Triple(it.first.pattern, it.second, it.third) }, tools)
        // The avatar fallback chain and status set are mirrored too (simulator and hud/hud.js).
        assertEquals(io.lampyr.glasses.render.avatar.AvatarStates.BUILTIN_FALLBACKS, table("AVATAR_FALLBACKS"))
        val status = Regex("const AVATAR_STATUS=\\[([^\\]]*)\\]").find(html)!!.groupValues[1].split(",").map { it.trim().removeSurrounding("'") }.toSet()
        assertEquals(io.lampyr.glasses.render.avatar.AvatarStates.STATUS, status)
        val hud = File("../../hud/hud.js").readText()
        assertEquals(Regex("const AVATAR_FALLBACKS=(\\{[^}]*\\})").find(html)!!.groupValues[1], Regex("const FALLBACKS = (\\{[^}]*\\})").find(hud)!!.groupValues[1])
    }

    @Test fun voiceAndWorkerStatesMapToFittingAnimations() {
        val v = CallAnimations
        assertEquals("listening", v.voice("listening")); assertEquals("thinking", v.voice("thinking")); assertEquals("speaking", v.voice("speaking"))
        assertEquals("looking_around", v.voice("listening", muted = true)); assertEquals("speaking", v.voice("speaking", muted = true))
        assertEquals("checking_watch", v.voice("reconnecting")); assertEquals("compressing", v.voice("compacting")); assertEquals("interrupted", v.voice("interrupted"))
        val working = JoyWorker("working")
        assertEquals("delegating", v.voice("listening", worker = working, workerAge = 0.5))
        assertEquals("waiting", v.voice("thinking", worker = working, workerAge = 5.0))
        assertEquals("asking", v.voice("listening", worker = JoyWorker("approval")))
        assertEquals("happy", v.voice("listening", worker = JoyWorker("done")))
        assertEquals("nodding", v.voice("listening", worker = JoyWorker("late")))
        assertEquals("listening", v.voice("listening", worker = JoyWorker("error")))
        assertEquals("speaking", v.voice("speaking", worker = JoyWorker("done")))
        assertEquals("searching", v.voice("searching")); assertEquals("listening", v.voice("unknown-status"))
        assertEquals("searching", v.toolAnim("web_search")); assertEquals("remembering", v.toolAnim("session_search")); assertEquals("saving", v.toolAnim("memory_add"))
        assertEquals("nerdy", v.toolAnim("terminal")); assertEquals("checking_watch", v.toolAnim("calendar_list")); assertEquals("working", v.toolAnim("mystery"))
        assertEquals("celebrating", v.worker(JoyWorker("done"))); assertEquals("curious", v.worker(JoyWorker("started"))); assertNull(v.worker(null))
        assertEquals("searching", v.workerLabel(JoyWorker("tool", "web_search")))
        assertEquals("running code", v.workerLabel(JoyWorker("tool", "terminal")))
        assertEquals("mystery tool", v.workerLabel(JoyWorker("tool", "mystery_tool")))
        assertEquals("working", v.workerLabel(JoyWorker("tool")))
        assertEquals("needs your OK", v.workerLabel(JoyWorker("approval")))
    }

    // ---------- Requirement 6: LifeLog pauses for the whole call, reconnects included ----------
    @Test fun ambientCaptureIsExcludedForTheWholeCallAndResumesAfter() = runTest {
        val gateway = callGateway()
        val socket = JoyCallSessionTest.Socket(); val audio = JoyCallSessionTest.Audio()
        val before = CaptureExclusions.activeKinds().count { it == "joy-call" }
        val data = LiveDataStore(context(), backgroundScope, gateway, { epoch }, { testScheduler.currentTime / 1000.0 }, false,
            { update -> JoyCallSession(socket, audio, JoyCallSessionTest.Exclusion(), { testScheduler.currentTime }, update) })
        try {
            assertTrue(data.joy.startVoiceCall().ok)
            assertEquals(before + 1, CaptureExclusions.activeKinds().count { it == "joy-call" })
            runCurrent(); assertEquals("live", data.joy.call.mode)
            socket.listener.opened(); socket.event("ready"); runCurrent()
            socket.listener.ended(); runCurrent() // the link drops mid-call
            assertTrue(data.joy.call.active); assertEquals("reconnecting", data.joy.snapshot.status)
            assertEquals(before + 1, CaptureExclusions.activeKinds().count { it == "joy-call" })
            advanceTimeBy(300); runCurrent(); socket.listener.opened(); socket.event("ready"); runCurrent()
            assertEquals("listening", data.joy.snapshot.status)
            assertEquals(before + 1, CaptureExclusions.activeKinds().count { it == "joy-call" })
            assertTrue(data.joy.endVoiceCall().ok)
            assertEquals(before, CaptureExclusions.activeKinds().count { it == "joy-call" })
        } finally { data.close() }
    }
}
