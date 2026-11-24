package io.lampyr.glasses

import io.lampyr.glasses.audio.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreFlowTest {
    @Test
    fun joyStreamsStatusAndAddsNotificationWhenReplyArrivesElsewhere() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val data =
            FakeDataStore(
                RuntimeEnvironment.getApplication(),
                { testScheduler.currentTime / 1000.0 },
                { 100000000L + testScheduler.currentTime },
                scope,
            )
        val observed = mutableListOf<String>()
        data.addChangeListener { observed += data.joy.snapshot.status }
        try {
            data.onScreenChanged("home")
            data.joy.send("Put on something interesting")
            assertEquals("thinking", data.joy.snapshot.status)
            advanceTimeBy(900)
            runCurrent()
            assertEquals("searching", data.joy.snapshot.status)
            advanceTimeBy(1400)
            runCurrent()
            assertEquals("speaking", data.joy.snapshot.status)
            assertEquals("Hermes replied", data.notifications.items.first().title)
            advanceTimeBy(1150)
            runCurrent()
            assertEquals("idle", data.joy.snapshot.status)
            assertTrue(observed.containsAll(listOf("thinking", "searching", "speaking", "idle")))
        } finally {
            data.close()
        }
    }

    @Test
    fun fakeDictationPhasesAppendMoreAndReplyReturnsToThread() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val data =
            FakeDataStore(
                RuntimeEnvironment.getApplication(),
                { testScheduler.currentTime / 1000.0 },
                { 100000000L },
                scope,
            )
        val ui = Ui(data, scope = scope, nowS = { testScheduler.currentTime / 1000.0 })
        try {
            ui.push(BeeperThread(ui, "chat-0"))
            ui.push(ReplyScreen(ui, "chat-0", "chat-0-2"))
            runCurrent()
            val audio = FakeDictation(data, scope)
            val input =
                io.lampyr.glasses.input.InputEngine(
                    ui,
                    audio,
                    scheduler =
                        io.lampyr.glasses.input.Scheduler { ms, action ->
                            val task = scope.launch { delay(ms); action() }
                            object : io.lampyr.glasses.input.Scheduled {
                                override fun cancel() { task.cancel() }
                            }
                        },
                )
            input.press("l")
            val screen = ui.top as DictationScreen
            assertEquals("listen", screen.phase)
            advanceTimeBy(450); runCurrent()
            input.release("l")
            runCurrent()
            assertEquals("trans", screen.phase)
            advanceTimeBy(700)
            runCurrent()
            assertEquals("enh", screen.phase)
            advanceTimeBy(700)
            runCurrent()
            assertEquals("preview", screen.phase)
            val first = screen.text
            input.press("l")
            advanceTimeBy(450); runCurrent()
            input.release("l")
            advanceTimeBy(1400)
            runCurrent()
            assertTrue(screen.text.startsWith(first + " "))
            screen.key("sel")
            runCurrent()
            assertTrue(ui.top is BeeperThread)
            assertEquals(
                "chat-0-2",
                data.beeper.messages("chat-0").first { it.id == "chat-0-2" }.id,
            )
            assertEquals(
                "https://www.instagram.com/reel/EXAMPLE123/",
                data.beeper.messages("chat-0").last().replyTo?.text,
            )
            input.close()
        } finally {
            ui.close()
        }
    }

    @Test
    fun threadKeysIncludeMachineAndApprovalUpdatesWorkingSnapshot() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val data =
            FakeDataStore(RuntimeEnvironment.getApplication(), { 0.0 }, { 100000000L }, scope)
        try {
            assertNull(data.t3.detail("desktop", "2"))
            assertFalse(data.t3.send("desktop", "2", "wrong machine").ok)
            val before = data.t3.detail("vps", "2")!!
            assertTrue(data.t3.respond("vps", "2", before.request!!.id, "accept").ok)
            val after = data.t3.detail("vps", "2")!!
            assertEquals("working", after.thread.status)
            assertEquals("Running command", after.working?.now?.kind)
            assertNull(after.request)
            assertFalse(data.t3.respond("vps", "2", before.request.id, "accept").ok)
        } finally {
            data.close()
        }
    }
}
