package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.BeeperOps
import io.lampyr.glasses.ui.screens.BeeperThread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BeeperOps on FakeDataStore (chats Sam, the assistant, Family, Alex). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BeeperOpsTest {
    private fun ActionRegistryTest.Harness.chat(name: String) = ui.data.beeper.chats.first { it.name == name }

    @Test fun listsChatsWithBadgeFlags(): Unit = ActionRegistryTest.Harness(listOf(BeeperOps)).use { h ->
        val all = h.call("beeper.list_chats").data!!.jsonObject
        val chats = all["chats"]!!.jsonArray.map { it.jsonObject }
        assertEquals(h.ui.data.beeper.chats.size, chats.size)
        assertEquals(h.ui.data.beeper.unreadTotal, all["unreadChats"]!!.jsonPrimitive.int)
        val sam = chats.first { it["name"]!!.jsonPrimitive.content == "Sam" }
        assertEquals(3, sam["unread"]!!.jsonPrimitive.int)
        assertTrue(sam["counts"]!!.jsonPrimitive.boolean)
        assertNotNull(sam["last"]!!.jsonObject["text"])
        val unread = h.call("beeper.list_chats", mapOf("unread_only" to true)).data!!.jsonObject["chats"]!!.jsonArray
        assertEquals(h.ui.data.beeper.chats.count { it.countsTowardUnread }, unread.size)
        assertEquals(1, h.call("beeper.list_chats", mapOf("limit" to 1)).data!!.jsonObject["chats"]!!.jsonArray.size)
        // Spam is hidden unless asked for, and the flag shows.
        assertTrue(h.call("beeper.mark_spam", mapOf("chat" to "Alex")).ok)
        assertTrue(h.call("beeper.list_chats").data!!.jsonObject["chats"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "Alex" })
        val withSpam = h.call("beeper.list_chats", mapOf("include_spam" to true)).data!!.jsonObject["chats"]!!.jsonArray
        assertTrue(withSpam.first { it.jsonObject["name"]!!.jsonPrimitive.content == "Alex" }.jsonObject["spam"]!!.jsonPrimitive.boolean)
        assertTrue(h.call("beeper.mark_not_spam", mapOf("chat" to "alex")).ok)
        assertFalse(h.chat("Alex").spam)
    }

    @Test fun readsAndOpensAChatByName(): Unit = ActionRegistryTest.Harness(listOf(BeeperOps)).use { h ->
        val read = h.call("beeper.read_chat", mapOf("chat" to "family", "limit" to 2)).data!!.jsonObject
        val messages = read["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals(h.ui.data.beeper.messages(h.chat("Family").id).last().id, messages.last().jsonObject["id"]!!.jsonPrimitive.content)
        // Reading never marks read; mark_read does.
        assertEquals(3, h.chat("Sam").unread)
        h.call("beeper.read_chat", mapOf("chat" to "Sam"))
        assertEquals(3, h.chat("Sam").unread)
        assertTrue(h.call("beeper.mark_read", mapOf("chat" to "Sam")).ok)
        assertEquals(0, h.chat("Sam").unread)

        val opened = h.call("beeper.open_chat", mapOf("chat" to "Family"))
        assertTrue(opened.message, opened.ok)
        assertEquals(h.chat("Family").id, (h.ui.top as BeeperThread).chatId)
        assertEquals("beeper", AgentScreen.currentApp(h.ui))

        // "a" matches Sam, Family and Alex: ambiguous, with candidates.
        val ambiguous = h.call("beeper.open_chat", mapOf("chat" to "a"))
        assertEquals(Codes.AMBIGUOUS, ambiguous.code)
        assertTrue(ambiguous.data!!.jsonArray.size >= 3)
        assertEquals(Codes.FAILED, h.call("beeper.read_chat", mapOf("chat" to "nobody-here")).code)
        h.ui.locked = true
        assertEquals(Codes.UNAVAILABLE, h.call("beeper.open_chat", mapOf("chat" to "Sam")).code)
    }

    @Test fun sendsRepliesAndMentionsAndConfirmsOutsideTheGlasses(): Unit = ActionRegistryTest.Harness(listOf(BeeperOps)).use { h ->
        val sam = h.chat("Sam")
        val replyTo = h.ui.data.beeper.messages(sam.id).first()
        val sent = h.call("beeper.send_message", mapOf("chat" to "sam", "text" to "@Sam see you at 6", "reply_to" to replyTo.id,
            "mentions" to mapOf("@Sam" to "@sam:beeper.local")), Origin.GLASSES_TURN)
        assertTrue(sent.message, sent.ok)
        val last = h.ui.data.beeper.messages(sam.id).last()
        assertEquals("@Sam see you at 6", last.text)
        assertTrue(last.mine)
        assertEquals(replyTo.text, last.replyTo!!.text)
        // Bad arguments never send.
        val count = h.ui.data.beeper.messages(sam.id).size
        assertEquals(Codes.INVALID_ARGS, h.call("beeper.send_message", mapOf("chat" to "Sam", "text" to "hi", "reply_to" to "nope"), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("beeper.send_message", mapOf("chat" to "Sam", "text" to "hi", "mentions" to mapOf("@Dana" to "x")), Origin.GLASSES_TURN).code)
        assertEquals(Codes.INVALID_ARGS, h.call("beeper.send_message", mapOf("chat" to "Sam", "text" to "hi Sam", "mentions" to mapOf("Sam" to "x")), Origin.GLASSES_TURN).code)
        assertEquals(count, h.ui.data.beeper.messages(sam.id).size)
        // From anywhere else (Discord, schedules) the user confirms on the glasses first.
        val pending = h.callAsync("beeper.send_message", mapOf("chat" to "Sam", "text" to "from discord"))
        val card = h.ui.top as AgentConfirm
        assertTrue(card.summary, card.summary.contains("from discord"))
        h.input.press("y"); h.input.release("y")
        assertTrue(runBlocking { pending.await() }.ok)
        assertEquals("from discord", h.ui.data.beeper.messages(sam.id).last().text)
        // An open thread follows the agent's send.
        h.call("beeper.open_chat", mapOf("chat" to "Sam"))
        h.call("beeper.send_message", mapOf("chat" to "Sam", "text" to "again"), Origin.GLASSES_TURN)
        assertEquals(h.ui.data.beeper.messages(sam.id).lastIndex, (h.ui.top as BeeperThread).cur)
    }

    @Test fun reactsToAMessage(): Unit = ActionRegistryTest.Harness(listOf(BeeperOps)).use { h ->
        val joy = h.chat("Hermes")
        val message = h.ui.data.beeper.messages(joy.id).last()
        val r = h.call("beeper.react", mapOf("chat" to "Hermes", "message_id" to message.id, "emoji" to "👍"), Origin.GLASSES_TURN)
        assertTrue(r.message, r.ok)
        assertTrue(h.ui.data.beeper.messages(joy.id).last().reactions.any { it.key == "👍" && it.mine })
        assertTrue(h.call("beeper.react", mapOf("chat" to "Hermes", "message_id" to message.id, "emoji" to "👍", "remove" to true), Origin.GLASSES_TURN).ok)
        assertTrue(h.ui.data.beeper.messages(joy.id).last().reactions.none { it.mine })
        assertEquals(Codes.FAILED, h.call("beeper.react", mapOf("chat" to "Hermes", "message_id" to "missing", "emoji" to "👍"), Origin.GLASSES_TURN).code)
        assertEquals(Risk.SEND, h.registry.ops["beeper.react"]!!.risk)
    }
}
