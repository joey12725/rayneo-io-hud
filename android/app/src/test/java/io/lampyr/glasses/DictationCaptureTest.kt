package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Hold-to-talk capture is one global session (2026-11-17 stuck-recording bug): an L release ends it on any
 * screen, leaving or locking cancels it cleanly, the dictation screen never shows a running timer without a
 * live microphone, and the microphone plus the LifeLog pause (capture exclusion) always close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationCaptureTest {
    private class Timers : Scheduler {
        data class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun after(delayMs: Long, block: () -> Unit): Scheduled {
            val task = Task(now + delayMs, block); tasks += task
            return object : Scheduled { override fun cancel() { task.cancelled = true } }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                now = task.at; task.cancelled = true; task.block()
            }
            now = end
        }
        val pending get() = tasks.count { !it.cancelled }
    }

    private class Audio : Dictation {
        var starts = 0; var stops = 0; var cancels = 0
        var listener: ((DictationEvent) -> Unit)? = null
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) { starts++; listener = onEvent }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; listener = null }
    }

    /** A screen with a dictation target, like a Beeper thread. */
    private class Chat(ui: Ui, id: String = "thread") : Screen(ui, id) {
        val sent = mutableListOf<String>()
        override val talk = TalkTarget("Message to Sam", send = { sent += it; ActionResult() })
        override fun draw(g: Gfx, t: Double) = with(g) { frame(ui, "Thread", "beeper", t) }
        override fun drawText(tg: TextGrid) { TextChrome.header(tg, "Thread"); TextChrome.footer(tg, hint = "L talk") }
    }

    /** Any other screen to navigate to while L is held. */
    private class Elsewhere(ui: Ui) : Screen(ui, "elsewhere") {
        override fun draw(g: Gfx, t: Double) = with(g) { frame(ui, "Elsewhere", "bell", t) }
        override fun drawText(tg: TextGrid) { TextChrome.header(tg, "Elsewhere"); TextChrome.footer(tg, hint = "B back") }
    }

    private class Rig(prefs: PowerPrefs = PowerPrefs.ALWAYS_ON) : AutoCloseable {
        var seconds = 0.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { seconds }, { 100_000_000L }, scope)
        val power = MemoryPowerStore(prefs)
        val ui = Ui(data, scope = scope, nowS = { seconds }, nowMs = { 100_000_000L }, powerStore = power)
        val timers = Timers()
        val audio = Audio()
        val input = InputEngine(ui, audio, scheduler = timers)
        val thread = Chat(ui).also { ui.push(it) }
        fun tap(letter: String) { input.press(letter); input.release(letter) }
        /** Hold L long enough to qualify as dictation; returns the dictation screen that opened. */
        fun holdTalk(): DictationScreen { input.press("l"); timers.advance(InputEngine.HOLD_MS); return ui.top as DictationScreen }
        fun text(cols: Int = 26, rows: Int = 7): List<String> { ui.display = Display.Text(cols, rows); return ui.render().text.lines() }
        override fun close() { input.close(); ui.close(); scope.cancel() }
    }

    @Test fun releasingLOnAnotherScreenStopsTheCaptureAndReturningShowsTheTranscript() = Rig().use { r ->
        val dict = r.holdTalk()
        assertEquals("listen", dict.phase)
        r.ui.push(Elsewhere(r.ui))
        r.input.release("l")
        assertEquals(1, r.audio.stops); assertEquals(0, r.audio.cancels)
        // The timer stops the moment the mic closes, even before the gateway answers.
        assertEquals("trans", dict.phase)
        assertNull(r.ui.talkCapture)
        r.audio.listener!!(DictationEvent.Done("On my way"))
        r.tap("b") // Back to the dictation screen.
        assertSame(dict, r.ui.top)
        assertEquals("preview", dict.phase); assertEquals("On my way", dict.text)
        r.input.press("l") // L add works: a fresh capture on the same screen.
        assertEquals(2, r.audio.starts); assertEquals("listen", dict.phase)
    }

    @Test fun aLostReleaseNeverLeavesAnUnstoppableTimerTheNextLPressStops() = Rig().use { r ->
        val dict = r.holdTalk()
        r.ui.push(Elsewhere(r.ui))
        r.tap("b") // Return to the dictation screen; the L release never arrived.
        assertSame(dict, r.ui.top); assertEquals("listen", dict.phase)
        r.input.press("l")
        assertEquals(1, r.audio.stops); assertEquals("trans", dict.phase); assertNull(r.ui.talkCapture)
        r.input.release("l")
        assertEquals(1, r.audio.starts); assertEquals(1, r.audio.stops); assertEquals(0, r.audio.cancels)
        assertFalse(r.input.hasKeysDown)
        r.audio.listener!!(DictationEvent.Done("Hello"))
        r.input.press("l") // And the screen still takes a new hold afterwards.
        assertEquals(2, r.audio.starts)
    }

    @Test fun bOnTheListeningScreenCancelsTheCaptureCleanly() = Rig().use { r ->
        r.holdTalk()
        r.tap("b")
        assertSame(r.thread, r.ui.top)
        assertEquals(1, r.audio.cancels); assertNull(r.ui.talkCapture)
        r.input.release("l")
        assertEquals(0, r.audio.stops)
        r.timers.advance(InputEngine.TALK_CAP_MS)
        assertEquals(0, r.audio.stops); assertEquals(0, r.timers.pending)
    }

    @Test fun holdingBHomeDuringCaptureCancelsAndLIsUsableAgain() = Rig().use { r ->
        r.holdTalk()
        r.input.press("b"); r.timers.advance(InputEngine.HOLD_MS)
        assertEquals("home", r.ui.top.id)
        assertEquals(1, r.audio.cancels); assertNull(r.ui.talkCapture)
        r.input.release("b"); r.input.release("l")
        assertEquals(0, r.audio.stops)
        r.input.press("l") // Home talks to the assistant.
        assertEquals(2, r.audio.starts); assertEquals("joy", r.ui.top.kind)
    }

    @Test fun bOnATranscribingScreenCancelsTheTranscription() = Rig().use { r ->
        r.holdTalk(); r.input.release("l")
        assertEquals("trans", (r.ui.top as DictationScreen).phase)
        r.tap("b")
        assertSame(r.thread, r.ui.top); assertEquals(1, r.audio.cancels)
    }

    @Test fun lockDuringCaptureOnAnotherScreenCancelsAndRemovesTheBuriedScreen() = Rig().use { r ->
        r.holdTalk()
        val other = Elsewhere(r.ui); r.ui.push(other)
        r.tap("minus")
        assertTrue(r.ui.locked)
        assertEquals(1, r.audio.cancels); assertNull(r.ui.talkCapture)
        assertTrue(r.ui.stack.none { it is DictationScreen })
        r.input.release("l")
        r.timers.advance(InputEngine.TALK_CAP_MS)
        assertEquals(0, r.audio.stops); assertEquals(0, r.timers.pending)
        r.tap("minus")
        assertFalse(r.ui.locked); assertSame(other, r.ui.top)
    }

    @Test fun lockKeepsEarlierTextAsAPreviewAndLetsAPendingTranscriptionFinish() = Rig().use { r ->
        val dict = r.holdTalk(); r.input.release("l")
        r.audio.listener!!(DictationEvent.Done("First part"))
        r.input.press("l"); r.timers.advance(InputEngine.HOLD_MS) // L add
        r.ui.push(Elsewhere(r.ui))
        r.tap("minus")
        assertEquals("preview", dict.phase); assertEquals("First part", dict.text)
        assertTrue(dict in r.ui.stack)
        r.tap("minus"); r.input.release("l")
        r.tap("b"); assertSame(dict, r.ui.top)
        r.holdTalk(); r.input.release("l") // A pending transcription survives a lock: no mic is open.
        r.tap("minus"); r.tap("minus")
        assertEquals("trans", dict.phase)
        r.audio.listener!!(DictationEvent.Done("second part"))
        assertEquals("First part second part", dict.text)
    }

    @Test fun aJoyCallStartingDuringCaptureStopsTheCaptureCleanly() = Rig().use { r ->
        val dict = r.holdTalk()
        r.ui.push(Elsewhere(r.ui))
        runBlocking { r.ui.data.joy.startVoiceCall() }
        assertEquals(1, r.audio.cancels); assertNull(r.ui.talkCapture)
        assertEquals("preview", dict.phase)
        r.input.release("l")
        assertEquals(0, r.audio.stops)
        runBlocking { r.ui.data.joy.endVoiceCall() }
        r.tap("b"); assertSame(dict, r.ui.top)
        r.input.press("l") // Retry works once the call is over.
        assertEquals(2, r.audio.starts)
    }

    @Test fun theTimeCapSubmitsTheCaptureWhereverTheUiIs() = Rig().use { r ->
        var capped = 0; r.input.onTalkCap = { capped++ }
        val dict = r.holdTalk()
        r.ui.push(Elsewhere(r.ui))
        r.timers.advance(InputEngine.TALK_CAP_MS)
        assertEquals(1, r.audio.stops); assertEquals(1, capped)
        assertEquals("trans", dict.phase); assertNull(r.ui.talkCapture); assertFalse(r.input.hasKeysDown)
        r.input.release("l")
        assertEquals(1, r.audio.stops); assertEquals(0, r.timers.pending)
    }

    @Test fun aNewSessionResolvesAnEarlierScreenStillWaitingForItsTranscript() = Rig().use { r ->
        val first = r.holdTalk(); r.input.release("l")
        assertEquals("trans", first.phase)
        r.ui.push(Chat(r.ui, "thread-2"))
        val second = r.holdTalk()
        assertNotSame(first, second)
        assertEquals("preview", first.phase)
        r.input.release("l")
        r.tap("b"); r.tap("b") // Back past the second thread to the first dictation.
        assertSame(first, r.ui.top)
        r.input.press("l") // It is not stuck: L records again.
        assertEquals(3, r.audio.starts); assertEquals("listen", first.phase)
    }

    @Test fun anOrphanedListeningScreenRecoversOnL() = Rig().use { r ->
        val orphan = DictationScreen(r.ui, r.thread.talk); r.ui.push(orphan); orphan.listen()
        r.input.press("l")
        assertEquals(1, r.audio.starts); assertSame(orphan, r.ui.top); assertEquals("listen", orphan.phase)
        r.timers.advance(InputEngine.HOLD_MS); r.input.release("l")
        assertEquals(1, r.audio.stops)
    }

    @Test fun micMarkShowsOnOtherScreensWhileDictationCapturesAndKeepsTheLensLit() = Rig(PowerPrefs(idleMs = 2_000)).use { r ->
        r.ui.wake("test")
        r.holdTalk()
        assertNull(r.ui.recordingBadge()) // Its own screen shows the timer.
        r.ui.push(Elsewhere(r.ui))
        r.seconds = 3.0
        assertEquals("MIC" to 0.0, r.ui.recordingBadge())
        assertTrue(r.text().first().endsWith(" MIC 0:03"))
        r.ui.powerTick(); r.seconds = 9.0; r.ui.powerTick()
        assertTrue(r.ui.awake) // Live capture holds the lens lit on any screen.
        r.input.release("l")
        assertNull(r.ui.recordingBadge())
        assertFalse(r.text().first().contains("MIC"))
    }

    @Test fun joyTalkReleaseOnAnotherScreenStillSendsToJoy() = Rig().use { r ->
        r.ui.goHome()
        r.input.press("l"); r.timers.advance(InputEngine.HOLD_MS)
        assertEquals("joy", r.ui.top.kind)
        r.ui.push(Elsewhere(r.ui))
        assertEquals("MIC", r.ui.recordingBadge()?.first)
        r.input.release("l")
        assertEquals(1, r.audio.stops)
        r.audio.listener!!(DictationEvent.Done("What time is it"))
        assertEquals("What time is it", r.ui.data.joy.snapshot.prompt)
    }

    // ---------- Voice memo recording mark ----------

    @Test fun memoRecordingMarkIsOnEveryScreenBlinksAndSaysSavedWithTheDuration() = Rig().use { r ->
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("dictation-capture-memo", 0)
        prefs.edit().clear().putString("l2.hold", "memo.toggle").commit()
        val ui = Ui(r.data, scope = r.scope, nowS = { r.seconds }, nowMs = { 100_000_000L }, shortcutAssignments = ShortcutAssignments(prefs))
        val input = InputEngine(ui, r.audio, scheduler = r.timers)
        try {
            ui.goHome()
            input.press("l2"); r.timers.advance(InputEngine.HOLD_MS); input.release("l2")
            assertTrue(ui.data.memos.snapshot.recording)
            ui.display = Display.Text()
            r.seconds = 42.0
            assertEquals("*REC 0:42", ui.render().text.lines().first().takeLast(9))
            r.seconds = 43.0
            assertEquals(" REC 0:43", ui.render().text.lines().first().takeLast(9))
            ui.push(Apps(ui))
            assertTrue(ui.render().text.lines().first().endsWith("REC 0:43"))
            ui.display = Display.Text(20, 5)
            assertTrue(ui.render().text.lines().first().endsWith("REC 0:43"))
            ui.display = Display.Bitmap
            /** Leftmost lit pixel of the top-right mark: the dot when it is on, else the "REC" text. */
            fun leftEdge(seconds: Double): Int {
                r.seconds = seconds
                val frame = ui.render()
                return (300 until W).first { x -> (10..20).any { y -> (frame.bitmap.getPixel(x, y) ushr 8 and 255) > 0 } }
            }
            assertTrue(leftEdge(44.0) <= leftEdge(45.0) - 10) // The dot blinks once a second.
            assertTrue(leftEdge(46.0) <= leftEdge(47.0) - 10)
            assertTrue(ui.needsAnimation) // The elapsed time keeps counting.
            r.seconds = 47.0
            input.press("l2"); r.timers.advance(InputEngine.HOLD_MS); input.release("l2")
            assertFalse(ui.data.memos.snapshot.recording)
            assertEquals("Memo saved 0:47", ui.currentToast)
            assertNull(ui.recordingBadge())
        } finally { input.close(); ui.close() }
    }

    /** The same Home as the memo-recording-hud bitmap golden, through the whole Ui text path (26x7 and 20x5). */
    @Test fun memoRecordingMarkMatchesItsTextFixtures() {
        val now = java.time.Instant.parse("2026-11-12T01:45:00Z").toEpochMilli()
        for ((suffix, mode) in listOf("" to Display.Text(), "-20x5" to Display.Text(20, 5))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { now }, scope)
            val ui = Ui(callGoldenData(data, "memo-recording-hud"), scope = scope, nowS = { 0.0 }, nowMs = { now },
                zone = java.time.ZoneId.of("America/Chicago")).apply { display = mode }
            try {
                val actual = ui.render().text.lines().joinToString("\n") + "\n"
                val file = java.io.File("src/test/resources/textgolden/memo-recording-hud$suffix.txt")
                if (System.getenv("UPDATE_TEXT_GOLDENS") == "1") file.writeText(actual)
                assertTrue("Missing golden: ${file.name}", file.exists())
                assertEquals(file.name, file.readText().replace("\r\n", "\n"), actual)
                assertTrue(actual.lines().first().endsWith("*REC 0:42"))
            } finally { ui.close() }
        }
    }

    @Test fun memoRecordingKeepsTheLensLitOnlyWhenTheSettingIsOn() {
        for (keep in listOf(false, true)) Rig(PowerPrefs(idleMs = 2_000, memoKeepAwake = keep)).use { r ->
            runBlocking { r.ui.data.memos.toggle() }
            r.ui.wake("button:l2")
            r.seconds = 1.0; r.ui.powerTick(); r.seconds = 5.0; r.ui.powerTick()
            assertEquals(keep, r.ui.awake)
            r.ui.wake("button:a") // Any press lights the lens and the mark is there.
            assertEquals("REC", r.ui.recordingBadge()?.first)
            r.ui.display = Display.Text()
            assertTrue(r.ui.render().text.lines().first().contains("REC"))
        }
    }

    @Test fun memoKeepAwakePreferenceIsReadWrittenAndShownInSettings() {
        assertFalse(PowerPrefs().memoKeepAwake)
        assertTrue(PowerPrefs.read(mapOf(PowerPrefs.MEMO_AWAKE to "on")).memoKeepAwake)
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("dictation-capture-power", 0)
        shared.edit().clear().commit()
        val store = SharedPowerStore(shared)
        store.update(store.prefs.copy(memoKeepAwake = true))
        assertTrue(shared.getBoolean(PowerPrefs.MEMO_AWAKE, false))
        Rig().use { r ->
            val view = PowerSettings(r.ui); r.ui.push(view)
            view.cur = view.rows().indexOfFirst { it.label == "Memo keeps lit" }
            assertTrue(view.cur >= 0)
            view.key("sel")
            assertTrue(r.power.prefs.memoKeepAwake)
        }
    }

    // ---------- LifeLog pause (capture exclusions) and the real capture pipeline ----------

    private val exclusions: Map<String, Pair<String, Long>> get() {
        val field = io.lampyr.glasses.ambient.CaptureExclusions::class.java.getDeclaredField("active").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return HashMap(field.get(io.lampyr.glasses.ambient.CaptureExclusions) as Map<String, Pair<String, Long>>)
    }

    private class Mic : MicSource {
        override val name = "test glasses"
        var live = false; var starts = 0; var stops = 0
        var pcm: ((ShortArray) -> Unit)? = null
        override fun isAvailable() = true
        override fun start(onPcm: (ShortArray) -> Unit): Boolean { live = true; starts++; pcm = onPcm; return true }
        override fun stop() { if (live) stops++; live = false; pcm = null }
    }

    private class Gateway : GatewayClient("https://fixture.example", "test") {
        var streaming: suspend (suspend (JsonElement) -> Unit) -> Unit = { emit ->
            emit(buildJsonObject { put("phase", "transcribing") })
            emit(buildJsonObject { put("phase", "done"); put("text", "Running late") })
        }
        override suspend fun get(path: String): JsonElement = JsonArray(emptyList())
        override suspend fun write(path: String, body: JsonElement, method: String): JsonElement = buildJsonObject { put("ok", true) }
        override suspend fun stream(path: String, body: ByteArray, contentType: String, accept: suspend (JsonElement) -> Unit) = streaming(accept)
    }

    private val mic = Mic()
    @After fun clearMic() { MicRegistry.glasses = null }

    private fun realRig(block: (Rig, InputEngine) -> Unit) = Rig().use { r ->
        MicRegistry.glasses = mic
        val dictation = GatewayDictation(RuntimeEnvironment.getApplication(), Gateway(), r.scope) { }
        r.input.close()
        val input = InputEngine(r.ui, dictation, scheduler = r.timers)
        try { block(r, input) } finally { input.close() }
    }

    @Test fun lifeLogIsPausedForTheCaptureAndResumedWhenLIsReleasedOnAnotherScreen() = realRig { r, input ->
        val before = exclusions.keys
        input.press("l"); r.timers.advance(InputEngine.HOLD_MS)
        val dict = r.ui.top as DictationScreen
        assertTrue(mic.live)
        assertEquals(listOf("dictation"), (exclusions.keys - before).map { exclusions[it]!!.first })
        mic.pcm!!(ShortArray(8000) { 500 })
        r.ui.push(Elsewhere(r.ui))
        input.release("l")
        assertFalse(mic.live); assertEquals(1, mic.stops)
        assertEquals(before, exclusions.keys)
        assertEquals("preview", dict.phase); assertEquals("Running late", dict.text)
    }

    @Test fun lifeLogResumesWhenTheCaptureIsCancelledByLockBackOrAJoyCall() = realRig { r, input ->
        val before = exclusions.keys
        fun tap(letter: String) { input.press(letter); input.release(letter) }
        // Lock on another screen.
        input.press("l"); r.timers.advance(InputEngine.HOLD_MS); r.ui.push(Elsewhere(r.ui))
        assertTrue(mic.live)
        tap("minus"); assertTrue(r.ui.locked); assertFalse(mic.live); assertEquals(before, exclusions.keys)
        tap("minus"); input.release("l")
        // B on the listening screen.
        r.ui.goHome(); r.ui.push(Chat(r.ui))
        input.press("l"); r.timers.advance(InputEngine.HOLD_MS)
        assertTrue(mic.live)
        tap("b")
        assertFalse(mic.live); assertEquals(before, exclusions.keys)
        input.release("l")
        // A the assistant call.
        input.press("l"); r.timers.advance(InputEngine.HOLD_MS)
        assertTrue(mic.live)
        runBlocking { r.ui.data.joy.startVoiceCall() }
        assertFalse(mic.live); assertEquals(before, exclusions.keys)
        input.release("l")
        runBlocking { r.ui.data.joy.endVoiceCall() }
        assertEquals(3, mic.starts); assertEquals(3, mic.stops)
    }

    @Test fun lifeLogIsPausedForAVoiceMemoAndResumedWhenItStops() {
        MicRegistry.glasses = mic
        val capture = PcmCapture(RuntimeEnvironment.getApplication(), "memo")
        val before = exclusions.keys
        assertTrue(capture.start())
        assertEquals(listOf("memo"), (exclusions.keys - before).map { exclusions[it]!!.first })
        capture.stop()
        assertEquals(before, exclusions.keys); assertFalse(mic.live)
    }
}
