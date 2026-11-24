package io.lampyr.glasses

import android.content.Context
import io.lampyr.glasses.ambient.AmbientListening
import io.lampyr.glasses.ambient.AmbientPreferences
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

/** AMB-14: hold − mutes the daily recording and voice matching together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PassiveListeningTest {
    private class Timers : Scheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        private val cancelled = mutableSetOf<() -> Unit>()
        var now = 0L
        override fun after(delayMs: Long, block: () -> Unit): Scheduled {
            tasks += (now + delayMs) to block
            return object : Scheduled { override fun cancel() { cancelled += block } }
        }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.also { tasks.removeAll(it) }
                .forEach { if (it.second !in cancelled) it.second() }
        }
    }

    private class NoAudio : Dictation {
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) {}
        override fun stop() {}
        override fun cancel() {}
    }

    private class NoMedia : MediaControl {
        override fun toggle() {}
        override fun direction(direction: String) {}
    }

    private fun fixture(block: (Ui, InputEngine, Timers) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { 100000000L }, scope)
        val ui = Ui(data, scope = scope, nowS = { 0.0 }, nowMs = { 100000000L })
        val timer = Timers()
        val engine = InputEngine(ui, NoAudio(), NoMedia(), timer)
        try { block(ui, engine, timer) } finally { engine.close(); ui.close() }
    }

    @Test
    fun onByDefault() {
        fixture { ui, _, _ -> assertTrue(ui.listening.on) }
        val prefs = AmbientPreferences(RuntimeEnvironment.getApplication())
        prefs.prefs.edit().clear().commit()
        assertTrue(prefs.enabled)
        assertTrue(prefs.listening)
    }

    @Test
    fun holdMinusTogglesListeningWithoutLocking() {
        fixture { ui, input, timer ->
            input.press("minus")
            timer.advance(449)
            assertTrue(ui.listening.on)
            timer.advance(1)
            assertFalse(ui.listening.on)
            input.release("minus")
            assertFalse("a hold must not also lock", ui.locked)

            input.press("minus"); timer.advance(450); input.release("minus")
            assertTrue(ui.listening.on)
            assertFalse(ui.locked)
        }
    }

    @Test
    fun tapMinusStillLocksAndUnlocksOnRelease() {
        fixture { ui, input, timer ->
            input.press("minus")
            timer.advance(200)
            assertFalse(ui.locked)
            input.release("minus")
            assertTrue(ui.locked)
            assertTrue(ui.listening.on)
            input.press("minus"); input.release("minus")
            assertFalse(ui.locked)
        }
    }

    @Test
    fun holdMinusWhileLockedOnlyUnlocks() {
        fixture { ui, input, timer ->
            input.press("minus"); input.release("minus")
            assertTrue(ui.locked)
            input.press("minus"); timer.advance(600); input.release("minus")
            assertTrue("a pocket press must not flip recording with the screen off", ui.listening.on)
            assertFalse(ui.locked)
        }
    }

    @Test
    fun mutedStateShowsOnHomeAndPeople() {
        fixture { ui, _, _ ->
            ui.display = Display.Text()
            assertFalse(ui.render().text.lines().joinToString("\n").contains(LISTEN_OFF))
            ui.listening.set(false)
            assertTrue(ui.render().text.lines().joinToString("\n").contains(LISTEN_OFF))

            val quick = QuickPeople(ui)
            ui.push(quick)
            val text = ui.render().text.lines().joinToString(" ")
            assertTrue(text, text.contains("Passive"))
            assertNull("no heard person is shown while voice matching is off", quick.talk)
        }
    }

    @Test
    fun phoneDisableOrPauseMutesTheGlassesAndRemoteUnmuteRestoresCapture() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = AmbientPreferences(context)
        prefs.prefs.edit().clear().commit()
        val switch = AmbientListening(prefs)
        var changes = 0
        val stop = switch.listen { changes++ }
        try {
            prefs.enabled = false
            assertFalse(switch.on)
            switch.set(true)
            assertTrue(prefs.enabled && !prefs.paused)
            prefs.paused = true
            assertFalse(switch.on)
            switch.set(true)
            assertTrue(switch.on)
            switch.set(false)
            assertTrue("remote mute pauses, it does not disable", prefs.enabled && prefs.paused)
            assertTrue(changes > 0)
        } finally { stop() }
    }
}
