package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InputEngineTest {
    private class Timers : Scheduler {
        data class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)

        var now = 0L
        val tasks = mutableListOf<Task>()

        override fun after(delayMs: Long, block: () -> Unit): Scheduled {
            val task = Task(now + delayMs, block)
            tasks += task
            return object : Scheduled {
                override fun cancel() {
                    task.cancelled = true
                }
            }
        }

        fun advance(ms: Long) {
            now += ms
            tasks
                .filter { !it.cancelled && it.at <= now }
                .toList()
                .forEach {
                    it.cancelled = true
                    it.block()
                }
        }
    }

    private class Audio : Dictation {
        var starts = 0
        var stops = 0
        var cancels = 0
        var listener: ((DictationEvent) -> Unit)? = null

        override fun start(target: String, onEvent: (DictationEvent) -> Unit) {
            starts++
            listener = onEvent
        }

        override fun stop() {
            stops++
        }

        override fun cancel() { cancels++ }
    }

    private class Media : MediaControl {
        var toggles = 0
        val directions = mutableListOf<String>()

        override fun toggle() {
            toggles++
        }

        override fun direction(direction: String) {
            directions += direction
        }
    }

    private fun fixture(block: (Ui, InputEngine, Timers, Media, Audio) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data =
            FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { 100000000L }, scope)
        val ui = Ui(data, scope = scope, nowS = { 0.0 }, nowMs = { 100000000L })
        val timer = Timers()
        val media = Media()
        val audio = Audio()
        val engine = InputEngine(ui, audio, media, timer)
        try {
            block(ui, engine, timer, media, audio)
        } finally {
            engine.close()
            ui.close()
        }
    }

    @Test
    fun backHoldGoesHomeWithoutAlsoPopping() {
        fixture { ui, input, timer, _, _ ->
            ui.push(Apps(ui))
            ui.push(Beeper(ui))
            input.press("b")
            timer.advance(449)
            assertEquals(3, ui.stack.size)
            timer.advance(1)
            assertEquals("home", ui.top.id)
            input.release("b")
            assertEquals(1, ui.stack.size)
        }
    }

    @Test
    fun backOnHomeOpensAppsAndBackInAppsReturns() {
        fixture { ui, input, _, _, _ ->
            assertEquals("home", ui.top.id)
            input.press("b"); input.release("b")
            assertEquals("apps", ui.top.id)
            input.press("b"); input.release("b")
            assertEquals("home", ui.top.id)
        }
    }

    @Test
    fun tapBackWaitsForRelease() {
        fixture { ui, input, timer, _, _ ->
            ui.push(Apps(ui))
            input.press("b")
            timer.advance(200)
            assertEquals("apps", ui.top.id)
            input.release("b")
            assertEquals("home", ui.top.id)
        }
    }

    @Test
    fun mediaLayerDoesNotLeakDpadOrToggleOnRelease() {
        fixture { ui, input, timer, media, _ ->
            val home = ui.top as Home
            input.press("r2")
            input.press("down")
            input.release("down")
            input.release("r2")
            assertEquals(listOf("down"), media.directions)
            assertEquals(0, home.cur)
            assertEquals(0, media.toggles)
            input.press("r2")
            input.release("r2")
            assertEquals(1, media.toggles)
            input.press("r2")
            timer.advance(450)
            input.release("r2")
            assertEquals(1, media.toggles)
            assertFalse(ui.mediaLayer)
        }
    }

    @Test
    fun lockIgnoresEveryOtherButtonAndCancelsPendingHolds() {
        fixture { ui, input, timer, media, audio ->
            input.press("b")
            input.press("minus")
            input.release("minus")
            assertTrue(ui.locked)
            for (letter in InputEngine.BIND.keys.filter { it != "minus" && it != "b" }) {
                input.press(letter)
                timer.advance(500)
                input.release(letter)
            }
            assertEquals("home", ui.top.id)
            assertEquals(0, media.toggles)
            assertEquals(0, audio.starts)
            input.release("b")
            input.press("minus")
            input.release("minus")
            assertFalse(ui.locked)
        }
    }

    @Test
    fun physicalPortraitDpadRotatesButKeyboardArrowsStayLogical() {
        fixture { ui, input, _, _, _ ->
            input.press("right", false)
            input.release("right", false)
            assertEquals(1, (ui.top as Home).cur)
            input.press("up")
            input.release("up")
            assertEquals(0, (ui.top as Home).cur)
            input.orientation = "land"
            input.press("down", false)
            input.release("down", false)
            assertEquals(1, (ui.top as Home).cur)
        }
    }

    @Test
    fun memoTapNeverStartsDictation() {
        fixture { ui, input, _, _, audio ->
            ui.push(VoiceMemo(ui))
            input.press("l")
            input.release("l")
            assertTrue(ui.data.memos.snapshot.recording)
            assertEquals(0, audio.starts)
            assertEquals(0, audio.stops)
        }
    }

    @Test
    fun releaseAllStopsAndSubmitsJoyDictationWithoutCancellingIt() {
        fixture { ui, input, timer, _, audio ->
            input.press("l")
            timer.advance(60_000)
            assertEquals(0, audio.stops) // Long PTT must not expire.
            input.releaseAll()
            input.releaseAll()
            assertEquals(1, audio.stops)
            assertEquals(0, audio.cancels)
            assertFalse(input.hasKeysDown)
            audio.listener!!.invoke(DictationEvent.Done("Keep this recording"))
            assertEquals("Keep this recording", ui.data.joy.snapshot.prompt)
            input.press("l")
            assertEquals(2, audio.starts)
        }
    }

    @Test
    fun releaseAllCancelsTimersAndLayersWithoutSyntheticTaps() {
        fixture { ui, input, timer, media, _ ->
            ui.push(Apps(ui))
            input.press("b"); input.press("plus"); input.press("r2")
            input.releaseAll()
            timer.advance(60_000)
            assertEquals("apps", ui.top.id)
            assertFalse(ui.mediaLayer)
            assertEquals(0, media.toggles)
            input.release("r2")
            assertEquals(0, media.toggles)
            input.press("r2"); input.release("r2")
            assertEquals(1, media.toggles)
        }
    }

    @Test
    fun staleMediaPressIsReleasedWithoutToggleAndCanBePressedAgain() {
        fixture { ui, input, timer, media, _ ->
            input.press("r2")
            timer.advance(InputEngine.STALE_MS)
            assertFalse(input.hasKeysDown)
            assertFalse(ui.mediaLayer)
            assertEquals(0, media.toggles)
            input.press("r2"); input.release("r2")
            assertEquals(1, media.toggles)
        }
    }

    @Test
    fun sourceConfirmationExtendsStaleDeadlineAndPhysicalStateIsCleared() {
        fixture { ui, input, timer, _, _ ->
            input.press("right", false)
            timer.advance(InputEngine.STALE_MS - 1)
            input.confirmDown()
            timer.advance(1)
            assertTrue(input.hasKeysDown)
            timer.advance(InputEngine.STALE_MS)
            assertFalse(input.hasKeysDown)
            input.orientation = "land"
            input.press("right", false)
            input.releaseAll()
            assertFalse(input.hasKeysDown)
        }
    }

    @Test
    fun lostPttReleasePreservesDictationPreviewSemantics() {
        fixture { ui, input, timer, _, audio ->
            val screen = DictationScreen(ui, TalkTarget("beeper", "Reply", send = { ActionResult() }))
            ui.push(screen)
            screen.event(DictationEvent.Done("Earlier"))
            input.press("l")
            timer.advance(450)
            input.releaseAll()
            audio.listener!!.invoke(DictationEvent.Done(" and more"))
            assertEquals("preview", screen.phase)
            assertTrue(screen.text.contains("and more"))
            assertEquals(1, audio.stops)
            assertEquals(0, audio.cancels)
        }
    }

    @Test
    fun simultaneousBleMediaChordEntersLayerBeforeDirectionAndDoesNotNavigate() {
        fixture { ui, input, _, media, _ ->
            val sink = object : ControllerSink {
                override fun press(letter: String) { input.press(letter) }
                override fun release(letter: String) { input.release(letter) }
                override fun releaseAll() { input.releaseAll() }
                override fun confirmDown() { input.confirmDown() }
                override fun status(connected: Boolean, detail: String) {}
            }
            val decoder = ControllerReportDecoder(1, listOf(ButtonField(0, 1, 1, "down"), ButtonField(0, 2, 2, "r2")))
            decoder.decode(byteArrayOf(3))!!.deliver(sink)
            decoder.decode(byteArrayOf(0))!!.deliver(sink)
            assertEquals(listOf("down"), media.directions)
            assertEquals(0, (ui.top as Home).cur)
            assertEquals(0, media.toggles)
            assertFalse(ui.mediaLayer)
        }
    }

    @Test fun brushPrearmsMicButDiscardsAndHoldSubmitsAt450ms() {
        fixture { ui, input, timer, _, audio ->
            input.press("l"); assertEquals(1, audio.starts)
            timer.advance(449); input.release("l")
            assertEquals(1, audio.cancels); assertEquals(0, audio.stops)
            input.press("l"); timer.advance(450); input.release("l")
            assertEquals(1, audio.stops)
        }
    }

    @Test fun accidentalAppendKeepsEarlierPreview() {
        fixture { ui, input, _, _, audio ->
            val screen = DictationScreen(ui, TalkTarget("beeper", "Reply", send = { ActionResult() }))
            ui.push(screen); screen.event(DictationEvent.Done("Earlier text"))
            input.press("l"); input.release("l")
            assertSame(screen, ui.top); assertEquals("preview", screen.phase)
            assertEquals("Earlier text", screen.text); assertEquals(0, audio.stops)
        }
    }

    @Test fun lostPttReleaseForceSubmitsAtTwoMinutesExactlyOnceAndClearsKeys() {
        fixture { _, input, timer, _, audio ->
            var capped = 0; input.onTalkCap = { capped++ }
            input.press("l"); timer.advance(119_999)
            assertEquals(0, audio.stops)
            timer.advance(1)
            assertEquals(1, audio.stops); assertEquals(1, capped); assertFalse(input.hasKeysDown)
            input.release("l"); input.releaseAll()
            assertEquals(1, audio.stops)
        }
    }

    @Test fun changeOnlyGattKeepsMediaAndDirectionsHeldUntilDisconnect() {
        fixture { ui, input, timer, media, _ ->
            input.expireHeldKeys = false
            input.press("r2"); input.press("down")
            timer.advance(90_000)
            assertTrue(input.hasKeysDown); assertTrue(ui.mediaLayer)
            input.releaseAll()
            assertFalse(input.hasKeysDown); assertFalse(ui.mediaLayer); assertEquals(0, media.toggles)
        }
    }

    @Test fun bothHandsUseSamePortraitAndLandscapeBindingsForEveryDirection() {
        fixture { _, input, _, _, _ ->
            // INP-01 specifies either hand, with no hand-dependent mirror or upside-down grip.
            repeat(2) {
                input.orientation = "cw"
                assertEquals(listOf("right", "down", "left", "up"), listOf("up", "right", "down", "left").map(input::logical))
                input.orientation = "land"
                listOf("up", "right", "down", "left").forEach { assertEquals(it, input.logical(it)) }
            }
        }
    }
}
