package io.lampyr.glasses.agent

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's real manifest, checked in as server/gateway/test/fixtures/agent-manifest.json so the gateway test
 * (agent-ops.test.mjs) proves the MCP tools/list the assistant sees. When ops change, this test fails and writes the new
 * manifest to app/build/agent-manifest.json: copy it over the fixture (and update hermes-skill/glasses/SKILL.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentManifestFixtureTest {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    @Test fun gatewayFixtureMatchesThePhoneManifest(): Unit = ActionRegistryTest.Harness().use { h ->
        val manifest = h.registry.manifest()
        val text = pretty.encodeToString(JsonObject.serializer(), manifest) + "\n"
        val fixture = File("../../server/gateway/test/fixtures/agent-manifest.json")
        val current = fixture.takeIf { it.exists() }?.readText()?.replace("\r\n", "\n")
        if (current != text) {
            val out = File("build/agent-manifest.json").apply { parentFile?.mkdirs(); writeText(text) }
            fail("The agent manifest changed. Copy ${out.absolutePath} to ${fixture.canonicalPath}, then run node --test server/gateway/test/")
        }
    }

    @Test fun perAppOpsHaveTheRightRiskAndConfirm(): Unit = ActionRegistryTest.Harness().use { h ->
        val ops = h.registry.ops
        fun risk(name: String) = ops[name]?.risk ?: error("missing $name")
        // Text leaving the device is SEND; the microphone is CALL; money is SPEND; the house is PHYSICAL.
        listOf("beeper.send_message", "beeper.react", "t3.send_message", "t3.respond", "t3.new_thread").forEach { assertEquals(it, Risk.SEND, risk(it)) }
        listOf("memo.start", "memo.toggle", "lifelog.resume").forEach { assertEquals(it, Risk.CALL, risk(it)) }
        assertEquals(Risk.SPEND, risk("t3.use_reset"))
        listOf("ha.run_scene", "ha.set_alarm").forEach { assertEquals(it, Risk.PHYSICAL, risk(it)) }
        // Stop-type ops never wait for a card.
        listOf("media.pause", "memo.stop", "teleprompter.stop", "lifelog.pause").forEach { assertEquals(it, Confirm.NEVER, ops[it]!!.confirm) }
        // Every read op is READ, and every per-app read has a sibling the model can act with.
        ops.values.filter { it.name.substringAfter('.').startsWith("list_") || it.name.endsWith(".read") || it.name.endsWith("_status") }
            .forEach { assertEquals(it.name, Risk.READ, it.risk) }
        // The other engineers' apps are not defined here.
        val mine = setOf("beeper", "t3", "media", "memo", "docs", "teleprompter", "people", "health", "calendar", "report", "ha", "lifelog")
        assertTrue(mine.all { app -> ops.values.any { it.app == app } })
        // Tool budget: every tool schema is in every the assistant prompt, so the long tail is tool = false (glasses_run).
        val tools = ops.values.count { it.tool && it.app in mine }
        assertTrue("$tools per-app tools", tools <= 42)
    }
}
