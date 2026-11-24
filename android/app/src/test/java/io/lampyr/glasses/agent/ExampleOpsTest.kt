package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.ExampleOps
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The AGENT_OPS.md test pattern: Harness(listOf(YourOps)) on FakeDataStore, then h.call(...). Copy this file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleOpsTest {
    @Test fun listAndSendWithNameResolution(): Unit = ActionRegistryTest.Harness(listOf(ExampleOps)).use { h ->
        val chats = h.call("example.list_chats").data!!.jsonArray
        assertTrue(chats.isNotEmpty())
        val chat = h.ui.data.beeper.chats.first()
        // From the glasses conversation a send runs without a card; the fake repository records it.
        val sent = h.call("example.send_message", mapOf("chat" to chat.name.uppercase(), "text" to "On my way"), Origin.GLASSES_TURN)
        assertTrue(sent.message, sent.ok)
        assertTrue(sent.message!!.startsWith("Sent")) // done() keeps the repository's own message when it has one
        assertEquals("On my way", h.ui.data.beeper.messages(chat.id).last().text)
        // Errors are results with codes, never exceptions.
        assertEquals(Codes.FAILED, h.call("example.send_message", mapOf("chat" to "zzz nobody", "text" to "x"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("example.send_message", mapOf("chat" to chat.id)).code)
        // Outside the glasses conversation the confirm card appears first.
        val pending = h.callAsync("example.send_message", mapOf("chat" to chat.id, "text" to "hi"))
        assertTrue(h.ui.top is AgentConfirm)
        h.input.press("b"); h.input.release("b")
        assertEquals(Codes.DENIED, kotlinx.coroutines.runBlocking { pending.await() }.code)
    }
}
