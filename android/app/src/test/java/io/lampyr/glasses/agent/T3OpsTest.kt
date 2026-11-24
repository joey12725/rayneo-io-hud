package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.T3Ops
import io.lampyr.glasses.data.T3_NEEDS_YOU
import io.lampyr.glasses.ui.screens.T3Thread
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T3Ops on FakeDataStore: 1 choice question, 2 approval (on vps), 3 multi question, 4 limited, 5-6 done, 7-9 working. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class T3OpsTest {
    private fun ActionRegistryTest.Harness.detail(id: String) = ui.data.t3.threads.first { it.id == id }.let { ui.data.t3.detail(it.machine, it.id)!! }

    @Test fun listsReadsAndOpensThreads(): Unit = ActionRegistryTest.Harness(listOf(T3Ops)).use { h ->
        val needs = h.call("t3.list_threads", mapOf("status" to "needs_you")).data!!.jsonObject
        assertEquals(h.ui.data.t3.threads.count { it.status in T3_NEEDS_YOU }, needs["threads"]!!.jsonArray.size)
        assertEquals(h.ui.data.t3.needYouCount, needs["needYou"]!!.jsonPrimitive.int)
        val working = h.call("t3.list_threads", mapOf("status" to "working")).data!!.jsonObject["threads"]!!.jsonArray
        assertEquals(3, working.size)
        assertTrue(working.all { it.jsonObject["step"] != null })
        assertTrue(h.call("t3.list_threads", mapOf("include_settled" to true)).data!!.jsonObject["threads"]!!.jsonArray.size > h.ui.data.t3.threads.size)

        val approval = h.call("t3.read_thread", mapOf("thread" to "deploy event-scraper")).data!!.jsonObject
        assertEquals("vps", approval["thread"]!!.jsonObject["machine"]!!.jsonPrimitive.content)
        val request = approval["request"]!!.jsonObject
        assertTrue(request["approval"]!!.jsonObject["command"]!!.jsonPrimitive.content.startsWith("docker compose"))
        val question = h.call("t3.read_thread", mapOf("thread" to "1", "history" to 0)).data!!.jsonObject
        assertEquals(3, question["request"]!!.jsonObject["questions"]!!.jsonArray[0].jsonObject["options"]!!.jsonArray.size)
        assertNull(question["history"])
        // machine/id and an explicit machine both resolve; a wrong machine does not.
        assertTrue(h.call("t3.read_thread", mapOf("thread" to "vps/2")).ok)
        assertFalse(h.call("t3.read_thread", mapOf("thread" to "2", "machine" to "laptop")).ok)

        val opened = h.call("t3.open_thread", mapOf("thread" to "Font test sheets"))
        assertTrue(opened.message, opened.ok)
        assertEquals("3", (h.ui.top as T3Thread).threadKey.id)
        assertEquals("t3", AgentScreen.currentApp(h.ui))
    }

    @Test fun answersApprovalsAndQuestions(): Unit = ActionRegistryTest.Harness(listOf(T3Ops)).use { h ->
        assertEquals(Codes.INVALID_ARGS, h.call("t3.respond", mapOf("thread" to "2"), Origin.GLASSES_TURN).code)
        val approved = h.call("t3.respond", mapOf("thread" to "2", "decision" to "approve"), Origin.GLASSES_TURN)
        assertTrue(approved.message, approved.ok)
        assertEquals("Approved", approved.message)
        assertEquals("working", h.detail("2").thread.status)
        assertEquals(Codes.UNAVAILABLE, h.call("t3.respond", mapOf("thread" to "2", "decision" to "deny"), Origin.GLASSES_TURN).code)

        // A question takes no decision; choice "2" is the second option's label.
        assertEquals(Codes.INVALID_ARGS, h.call("t3.respond", mapOf("thread" to "1", "decision" to "approve"), Origin.GLASSES_TURN).code)
        assertTrue(h.call("t3.respond", mapOf("thread" to "1", "choice" to "2"), Origin.GLASSES_TURN).ok)
        assertEquals("Session cookie copied from Beeper Desktop", h.detail("1").history.last().text)

        // Multi-select by labels (partial labels resolve to the option).
        val multi = h.call("t3.respond", mapOf("thread" to "Font test sheets", "answers" to mapOf("q1" to JsonArray(listOf(JsonPrimitive("vt323"), JsonPrimitive("Terminus"))))), Origin.GLASSES_TURN)
        assertTrue(multi.message, multi.ok)
        assertEquals("VT323, Terminus", h.detail("3").history.last().text)
        // Outside the glasses conversation an approval needs the card.
        assertEquals(Risk.SEND, h.registry.ops["t3.respond"]!!.risk)
    }

    @Test fun sendsQueuesSteersAndStartsThreads(): Unit = ActionRegistryTest.Harness(listOf(T3Ops)).use { h ->
        val done = h.call("t3.send_message", mapOf("thread" to "Root page copy pass", "text" to "Ship it"), Origin.GLASSES_TURN)
        assertTrue(done.message, done.ok)
        assertEquals("Ship it", h.detail("5").history.last().text)
        // A running thread queues by default and steers on request.
        assertEquals("Queued · sends after this turn", h.call("t3.send_message", mapOf("thread" to "7", "text" to "then lint"), Origin.GLASSES_TURN).message)
        assertTrue(h.call("t3.send_message", mapOf("thread" to "7", "text" to "stop that", "mode" to "steer"), Origin.GLASSES_TURN).message!!.startsWith("Steered"))

        val before = h.ui.data.t3.threads.size
        val started = h.call("t3.new_thread", mapOf("project" to "scraper", "prompt" to "Add retries to the fetcher"), Origin.GLASSES_TURN)
        assertTrue(started.message, started.ok)
        assertEquals(before + 1, h.ui.data.t3.threads.size)
        val id = started.data!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(id, (h.ui.top as T3Thread).threadKey.id)
        assertEquals(Codes.AMBIGUOUS, h.call("t3.new_thread", mapOf("project" to "r", "prompt" to "x"), Origin.GLASSES_TURN).code)
    }

    @Test fun aTitleWithASlashIsNotReadAsMachineSlashId(): Unit = ActionRegistryTest.Harness(listOf(T3Ops)).use { h ->
        assertTrue(h.call("t3.new_thread", mapOf("project" to "scraper", "prompt" to "Fix a/b test flakes", "open" to false), Origin.GLASSES_TURN).ok)
        val read = h.call("t3.read_thread", mapOf("thread" to "Fix a/b test flakes"))
        assertTrue(read.message, read.ok)
        assertEquals("Fix a/b test flakes", read.data!!.jsonObject["thread"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        // A real machine prefix still selects the machine.
        assertTrue(h.call("t3.read_thread", mapOf("thread" to "vps/2")).ok)
    }

    @Test fun usageMachinesProjectsAndResets(): Unit = ActionRegistryTest.Harness(listOf(T3Ops)).use { h ->
        val usage = h.call("t3.usage").data!!.jsonArray
        assertEquals(h.ui.data.t3.usage.size, usage.size)
        assertTrue(usage[0].jsonObject["windows"]!!.jsonArray.isNotEmpty())
        val machines = h.call("t3.list_machines").data!!.jsonArray
        assertTrue(machines.any { !it.jsonObject["online"]!!.jsonPrimitive.boolean })
        assertEquals(h.ui.data.t3.projects.size, h.call("t3.list_projects").data!!.jsonArray.size)
        val u = h.ui.data.t3.usage.first { it.credits.isNotEmpty() }
        val credit = u.credits.first()
        val args = mapOf("machine" to u.machine, "instance_id" to u.instanceId, "credit_id" to credit.creditId)
        assertTrue(h.registry.needsConfirm(h.registry.ops["t3.use_reset"]!!, Origin.OTHER))
        val r = h.call("t3.use_reset", args, Origin.GLASSES_TURN)
        assertTrue(r.message, r.ok)
        assertEquals(Codes.FAILED, h.call("t3.use_reset", args + ("credit_id" to "nope"), Origin.GLASSES_TURN).code)
    }
}
