package io.lampyr.glasses.agent

import io.lampyr.glasses.ui.screens.Beeper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phone side of the agent link against a fake socket: hello/manifest, context push, calls, cancel, busy, no replay. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentLinkTest {
    private class FakeSockets : AgentSocketFactory {
        val sent = mutableListOf<JsonObject>()
        var listener: AgentSocketListener? = null
        var opens = 0
        var closes = 0
        override fun open(listener: AgentSocketListener): AgentSocket {
            this.listener = listener; opens++
            return object : AgentSocket {
                override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
                override fun close() { closes++ }
            }
        }
        fun frames(t: String) = sent.filter { it["t"]!!.jsonPrimitive.content == t }
        fun result(id: String) = frames("result").single { it["id"]!!.jsonPrimitive.content == id }
        fun call(id: String, op: String, args: String = "{}", deadlineMs: Long = 10_000, channel: String = "other") =
            listener!!.text("""{"t":"call","id":"$id","op":"$op","args":$args,"deadlineMs":$deadlineMs,"origin":{"channel":"$channel"}}""")
    }

    private val confirmOp = OpProvider { listOf(OpSpec("test.send", "Send test", "Sends.", risk = Risk.SEND) { ok("Sent") }) }

    @Test fun helloContextCallsAndNoReplayAfterReconnect() = runTest {
        ActionRegistryTest.Harness(listOf(confirmOp)).use { h ->
            val sockets = FakeSockets()
            val link = AgentLink(sockets, backgroundScope, "test-app", nowMs = { testScheduler.currentTime }).also { it.registry = h.registry }
            link.start(); runCurrent()
            assertEquals(1, sockets.opens)
            assertTrue(sockets.sent.isEmpty()) // nothing before the socket opens
            sockets.listener!!.opened(); runCurrent()
            val hello = sockets.frames("hello").single()
            assertEquals("test-app", hello["app"]!!.jsonPrimitive.content)
            assertEquals(h.registry.ops.size, hello["manifest"]!!.jsonObject["ops"]!!.jsonArray.size)
            assertTrue(link.connected)
            assertEquals("home", sockets.frames("context").single()["ctx"]!!.jsonObject["screen"]!!.jsonObject["id"]!!.jsonPrimitive.content)

            sockets.call("c1", "glasses.open_app", """{"app":"beeper"}"""); runCurrent()
            assertTrue(sockets.result("c1")["ok"]!!.jsonPrimitive.boolean)
            assertTrue(h.ui.top is Beeper)
            advanceTimeBy(1_100); runCurrent()
            assertEquals("beeper", sockets.frames("context").last()["ctx"]!!.jsonObject["screen"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val contexts = sockets.frames("context").size
            advanceTimeBy(3_000); runCurrent()
            assertEquals("unchanged context is not resent", contexts, sockets.frames("context").size)

            sockets.call("c2", "nope.op"); runCurrent()
            assertEquals(Codes.UNKNOWN_OP, sockets.result("c2")["code"]!!.jsonPrimitive.content)

            // A call waiting on the confirm card is cancelled by the gateway: no result is sent, the card goes away.
            sockets.call("c3", "test.send"); runCurrent()
            assertTrue(h.ui.top is AgentConfirm)
            sockets.listener!!.text("""{"t":"cancel","id":"c3"}"""); runCurrent()
            assertTrue(sockets.frames("result").none { it["id"]!!.jsonPrimitive.content == "c3" })
            assertFalse(h.ui.top is AgentConfirm)

            advanceTimeBy(20_000); runCurrent()
            assertTrue(sockets.frames("ping").isNotEmpty())

            // Disconnect while a call waits on the confirm card: it is dropped, and never runs after reconnecting.
            sockets.call("c4", "test.send"); runCurrent()
            assertTrue(h.ui.top is AgentConfirm)
            sockets.listener!!.ended("connection lost"); runCurrent()
            assertFalse(link.connected)
            assertFalse(h.ui.top is AgentConfirm)
            advanceTimeBy(1_100); runCurrent()
            assertEquals(2, sockets.opens)
            sockets.listener!!.opened(); runCurrent()
            assertEquals(2, sockets.frames("hello").size)
            assertTrue(sockets.frames("result").none { it["id"]!!.jsonPrimitive.content == "c4" })
            link.stop()
        }
    }

    @Test fun callsRunOneAtATimeWithABusyCapAndStaleCallsAreDropped() = runTest {
        ActionRegistryTest.Harness(listOf(confirmOp)).use { h ->
            val sockets = FakeSockets()
            val link = AgentLink(sockets, backgroundScope, nowMs = { testScheduler.currentTime }).also { it.registry = h.registry }
            link.start(); runCurrent(); sockets.listener!!.opened(); runCurrent()
            sockets.call("first", "test.send"); runCurrent()          // blocks on the confirm card
            sockets.call("stale", "glasses.get_context", deadlineMs = 100); runCurrent()
            repeat(AgentLink.QUEUE_CAP - 1) { sockets.call("q$it", "glasses.get_context"); runCurrent() }
            sockets.call("overflow", "glasses.get_context"); runCurrent()
            assertEquals(Codes.BUSY, sockets.result("overflow")["code"]!!.jsonPrimitive.content)
            advanceTimeBy(500); runCurrent()
            h.input.press("b"); h.input.release("b"); runCurrent()
            assertEquals(Codes.DENIED, sockets.result("first")["code"]!!.jsonPrimitive.content)
            assertEquals(Codes.TIMEOUT, sockets.result("stale")["code"]!!.jsonPrimitive.content)
            repeat(AgentLink.QUEUE_CAP - 1) { assertTrue(sockets.result("q$it")["ok"]!!.jsonPrimitive.boolean) }
            // A glasses-channel origin reaches the registry: the same op runs without a card.
            sockets.call("g", "test.send", channel = Origin.GLASSES); runCurrent()
            assertTrue(sockets.result("g")["ok"]!!.jsonPrimitive.boolean)
            link.stop()
        }
    }

    @Test fun aSilentlyDeadLinkIsReplacedAndAQuicklyClosedOneBacksOff() = runTest {
        ActionRegistryTest.Harness().use { h ->
            val sockets = FakeSockets()
            val link = AgentLink(sockets, backgroundScope, nowMs = { testScheduler.currentTime }).also { it.registry = h.registry }
            link.start(); runCurrent(); sockets.listener!!.opened(); runCurrent()
            assertTrue(link.connected)
            // The gateway answers every ping; with nothing heard for three pings the phone gives up on the socket.
            advanceTimeBy(40_000); runCurrent()
            sockets.listener!!.text("""{"t":"pong"}""")
            advanceTimeBy(40_000); runCurrent()
            assertTrue("a pong keeps it alive", link.connected); assertEquals(1, sockets.opens)
            advanceTimeBy(45_000); runCurrent()
            assertFalse(link.connected); assertEquals(1, sockets.closes)
            advanceTimeBy(1_100); runCurrent()
            assertEquals("a link that lived reconnects after 1 s", 2, sockets.opens)
            // Closed right after opening: the backoff keeps growing (2 s, then 4 s) instead of retrying every second.
            sockets.listener!!.opened(); runCurrent(); sockets.listener!!.ended("closed 1008"); runCurrent()
            advanceTimeBy(1_500); runCurrent(); assertEquals(2, sockets.opens)
            advanceTimeBy(600); runCurrent(); assertEquals(3, sockets.opens)
            sockets.listener!!.opened(); runCurrent(); sockets.listener!!.ended("closed 1008"); runCurrent()
            advanceTimeBy(3_500); runCurrent(); assertEquals(3, sockets.opens)
            advanceTimeBy(600); runCurrent(); assertEquals(4, sockets.opens)
            link.stop()
        }
    }
}
