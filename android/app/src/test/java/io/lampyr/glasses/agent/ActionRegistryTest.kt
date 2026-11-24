package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.GlassesOps
import io.lampyr.glasses.agent.ops.HomeOps
import io.lampyr.glasses.audio.FakeDictation
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.ui.*
import io.lampyr.glasses.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

/**
 * The pattern for every app's ops: build a Ui on FakeDataStore, an ActionRegistry over it, then `h.call(...)`.
 * Copy `Harness` and the manifest test when you add `XxxOps` (see android/AGENT_OPS.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionRegistryTest {
    private class Timers : Scheduler {
        override fun after(delayMs: Long, block: () -> Unit) = object : Scheduled { override fun cancel() {} }
    }

    class Harness(
        extra: List<OpProvider> = emptyList(),
        var policy: ConfirmPolicy = ConfirmPolicy.OUTSIDE_GLASSES,
        confirmTimeoutMs: Long = 20_000,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ui = Ui(FakeDataStore(RuntimeEnvironment.getApplication(), scope = scope), scope = scope, nowS = { 0.0 },
            nowMs = { java.time.Instant.parse("2026-11-12T01:45:00Z").toEpochMilli() }, zone = java.time.ZoneId.of("America/Chicago"))
        val input = InputEngine(ui, FakeDictation(ui.data, scope), scheduler = Timers())
        val registry = ActionRegistry(ui, OP_PROVIDERS + extra, { policy }, confirmTimeoutMs).also { it.input = input }
        fun call(op: String, args: Map<String, Any?> = emptyMap(), origin: Origin = Origin.OTHER) =
            runBlocking { registry.call(op, json(args), "t", origin) }
        fun callAsync(op: String, args: Map<String, Any?> = emptyMap(), origin: Origin = Origin.OTHER) =
            scope.async { registry.call(op, json(args), "t", origin) }
        override fun close() { input.close(); ui.close() }
    }

    companion object {
        fun json(args: Map<String, Any?>): JsonObject = JsonObject(args.mapValues { (_, v) ->
            when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> json(v.entries.associate { it.key.toString() to it.value })
                is JsonElement -> v
                else -> error("unsupported $v")
            }
        })
        fun JsonElement?.screenId() = ((this as JsonObject)["screen"] as JsonObject)["id"]!!.jsonPrimitive.content
    }

    private var sends = 0
    private val sensitive = OpProvider {
        listOf(
            OpSpec("test.send", "Send test", "Sends a test message.", risk = Risk.SEND, summary = { "to Test: hi" }) { sends++; ok("Sent") },
            OpSpec("test.never", "Never ask", "Sensitive but never confirms.", risk = Risk.SPEND, confirm = Confirm.NEVER) { sends++; ok("Spent") },
            OpSpec("test.always", "Always ask", "Not sensitive but always confirms.", risk = Risk.ACT, confirm = Confirm.ALWAYS) { sends++; ok("Done") },
            OpSpec("test.scene", "Run scene", "Acts on the house.", risk = Risk.PHYSICAL) { sends++; ok("Started") },
            OpSpec("test.stop_all", "Stop all", "Stops everything.", risk = Risk.PHYSICAL, confirm = Confirm.NEVER) { sends++; ok("Stopped") },
            OpSpec("test.slow", "Slow", "Never finishes.", timeoutMs = 500) { awaitCancellation() },
            OpSpec("test.crash", "Crash", "Throws.") { error("boom") },
        )
    }

    @Test fun everyRegisteredOpHasAValidManifestEntry(): Unit = Harness().use { h ->
        val manifest = h.registry.manifest()
        val ops = manifest["ops"]!!.jsonArray.map { it.jsonObject }
        assertEquals(h.registry.ops.size, ops.size)
        assertEquals(64, manifest["hash"]!!.jsonPrimitive.content.length)
        h.registry.ops.values.forEach { spec ->
            assertTrue(spec.name, OpSpec.NAME.matches(spec.name))
            assertTrue("${spec.name} title too long", spec.title.isNotBlank() && spec.title.length <= 40)
            assertTrue("${spec.name} description", spec.description.isNotBlank() && spec.description.length <= 300)
            assertTrue(spec.name, spec.timeoutMs in 500..OpSpec.MAX_TIMEOUT_MS)
            assertFalse(spec.name, spec.name in RESERVED_OPS)
            val schema = spec.inputSchema()
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertEquals(spec.params.map { it.name }.toSet(), schema["properties"]!!.jsonObject.keys)
            spec.params.forEach { p ->
                assertTrue("${spec.name}.${p.name}", Regex("^[a-z][a-z0-9_]*$").matches(p.name))
                assertTrue(p.type in setOf("string", "integer", "number", "boolean", "object"))
                assertTrue("${spec.name}.${p.name} needs a description", p.description.isNotBlank())
            }
        }
        // The generic set the gateway, skill and docs rely on.
        listOf("glasses.get_context", "glasses.read_screen", "glasses.press", "glasses.open_app", "glasses.select_row", "glasses.type_text",
            "glasses.back", "glasses.home", "glasses.lock", "glasses.unlock", "glasses.list_apps", "glasses.list_operations", "glasses.run")
            .forEach { assertTrue(it, it in h.registry.ops) }
        assertFalse("the talk button opens the microphone", "l" in GlassesOps.BUTTONS)
    }

    @Test fun duplicateAndBadNamesAreRejectedAtConstruction(): Unit = Harness().use { h ->
        val dup = OpProvider { listOf(OpSpec("glasses.back", "x", "x") { ok() }) }
        assertThrows(IllegalArgumentException::class.java) { ActionRegistry(h.ui, OP_PROVIDERS + dup) }
        assertThrows(IllegalArgumentException::class.java) { ActionRegistry(h.ui, listOf(OpProvider { listOf(OpSpec("Bad.Name", "x", "x") { ok() }) })) }
        assertThrows(IllegalArgumentException::class.java) { ActionRegistry(h.ui, listOf(OpProvider { listOf(OpSpec("glasses.notify", "x", "x") { ok() }) })) }
    }

    @Test fun pressDrivesTheSameInputEngineAsTheRemote(): Unit = Harness().use { h ->
        val r = h.call("glasses.press", mapOf("button" to "star"))
        assertTrue(r.message, r.ok)
        assertTrue(h.ui.top is Apps)
        assertEquals("apps", r.data.screenId())
        h.call("glasses.press", mapOf("button" to "b"))
        assertTrue(h.ui.top is Home)
        assertEquals("agent", h.input.lastKeyEvent!!.substringAfter("source="))
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.press", mapOf("button" to "l")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.press", mapOf("button" to "y", "times" to 11)).code)
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.press", mapOf("button" to "y", "bogus" to 1)).code)
        h.call("glasses.press", mapOf("button" to "r", "times" to 2))
        assertTrue(h.ui.top is Notifications)
    }

    @Test fun openAppOpensEveryAppAndRejectsUnknownScreens(): Unit = Harness().use { h ->
        APP_ENTRIES.forEach { app ->
            h.ui.goHome()
            val r = h.call("glasses.open_app", mapOf("app" to app.id))
            assertTrue("${app.id}: ${r.message}", r.ok)
            assertEquals(app.id, AgentScreen.currentApp(h.ui))
        }
        h.ui.goHome(); assertTrue(h.call("glasses.open_app", mapOf("app" to "prompter")).ok); assertTrue(h.ui.top is Teleprompter)
        h.ui.goHome(); h.call("glasses.open_app", mapOf("app" to "apps")); assertTrue(h.ui.top is Apps)
        h.call("glasses.open_app", mapOf("app" to "home")); assertTrue(h.ui.top is Home)
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.open_app", mapOf("app" to "nowhere")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.open_app").code)
        val apps = h.call("glasses.list_apps").data!!.jsonObject["apps"]!!.jsonArray
        assertEquals(appEntries(h.ui).map { it.id }, apps.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun readScreenReturnsTextAndRowsInBitmapMode(): Unit = Harness().use { h ->
        assertFalse(h.ui.isText)
        val home = h.call("glasses.read_screen").data!!.jsonObject
        assertEquals("home", home["id"]!!.jsonPrimitive.content)
        assertTrue(home["text"]!!.jsonPrimitive.content.isNotBlank())
        h.ui.push(Notifications(h.ui))
        val list = h.call("glasses.read_screen", mapOf("grid" to true)).data!!.jsonObject
        assertEquals("notifications", list["app"]!!.jsonPrimitive.content)
        assertTrue(list["rows"]!!.jsonArray.isNotEmpty())
        assertEquals(h.ui.textDisplay.rows, list["grid"]!!.jsonArray.size)
        h.ui.push(ReaderView(h.ui, "r", { "Long reader text ".repeat(40) }, "Reader"))
        val reader = h.call("glasses.get_context").data!!.jsonObject["screen"]!!.jsonObject
        assertEquals("Reader", reader["title"]!!.jsonPrimitive.content)
        assertTrue(reader["text"]!!.jsonPrimitive.content.length > 500)
        assertEquals("notifications", reader["app"]!!.jsonPrimitive.content)
    }

    @Test fun selectRowOpensByIndexOrLabel(): Unit = Harness().use { h ->
        h.call("glasses.open_app", mapOf("app" to "beeper"))
        val chat = h.ui.data.beeper.chats.first()
        val rows = (h.ui.top as ListView).rows()
        val index = rows.indexOfFirst { it.label == chat.name }
        assertTrue(index >= 0)
        assertTrue(h.call("glasses.select_row", mapOf("index" to index)).ok)
        assertEquals("thread-${chat.id}", h.ui.top.id)
        h.ui.pop()
        assertTrue(h.call("glasses.select_row", mapOf("label" to chat.name.uppercase())).ok)
        assertEquals("thread-${chat.id}", h.ui.top.id)
        assertEquals(Codes.UNAVAILABLE, h.call("glasses.select_row", mapOf("index" to 0)).code) // a thread is not a list
        h.ui.goHome(); h.ui.push(Notifications(h.ui))
        assertEquals(Codes.FAILED, h.call("glasses.select_row", mapOf("label" to "zzz-no-such-row")).code)
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.select_row", mapOf("index" to 400)).code)
    }

    @Test fun typeTextSendsToTheScreensTargetAndNeverToJoy(): Unit = Harness().use { h ->
        val chat = h.ui.data.beeper.chats.first()
        h.ui.open(OpenTarget("beeper-thread", mapOf("chatId" to chat.id)))
        val r = h.call("glasses.type_text", mapOf("text" to "On my way"), Origin.GLASSES_TURN)
        assertTrue(r.message, r.ok)
        assertEquals("On my way", h.ui.data.beeper.messages(chat.id).last().text)
        assertTrue(AgentScreen.canType(h.ui.top) != null)
        h.ui.push(Joy(h.ui))
        assertEquals(Codes.UNAVAILABLE, h.call("glasses.type_text", mapOf("text" to "x"), Origin.GLASSES_TURN).code)
        h.ui.goHome()
        assertEquals("This screen has no text input", h.call("glasses.type_text", mapOf("text" to "x"), Origin.GLASSES_TURN).message)
    }

    @Test fun sensitiveOpsOutsideTheGlassesNeedTheConfirmCard(): Unit = Harness(listOf(sensitive)).use { h ->
        // From the glasses conversation: no card, runs straight away (with a toast).
        assertTrue(h.call("test.send", origin = Origin.GLASSES_TURN).ok); assertEquals(1, sends)
        assertEquals("Hermes: Send test", h.ui.currentToast)
        // From elsewhere: Y confirms.
        val confirmed = h.callAsync("test.send")
        val card = h.ui.top as AgentConfirm
        assertEquals("Send test", card.title); assertEquals("to Test: hi", card.summary)
        assertTrue(AgentScreen.grid(h.ui).joinToString("\n").contains("Send test"))
        h.input.press("y"); h.input.release("y")
        assertTrue(runBlocking { confirmed.await() }.ok); assertEquals(2, sends)
        assertTrue(h.ui.top is Home)
        // B cancels.
        val cancelled = h.callAsync("test.send")
        assertTrue(h.ui.top is AgentConfirm)
        h.input.press("b"); h.input.release("b")
        assertEquals(Codes.DENIED, runBlocking { cancelled.await() }.code); assertEquals(2, sends)
        assertTrue(h.ui.top is Home)
        // Leaving the card any other way (B hold -> Home) cancels too.
        val left = h.callAsync("test.send")
        h.ui.goHome()
        assertEquals(Codes.DENIED, runBlocking { left.await() }.code)
        // Screen keys are swallowed, never reaching the screen underneath; a global button may cover the card,
        // and B brings it back still waiting.
        val swallowed = h.callAsync("test.send")
        h.input.press("down"); h.input.release("down"); h.input.press("x"); h.input.release("x")
        assertTrue(h.ui.top is AgentConfirm)
        h.input.press("star"); h.input.release("star")
        assertTrue(h.ui.top is Apps)
        h.input.press("b"); h.input.release("b")
        assertTrue(h.ui.top is AgentConfirm)
        h.input.press("y"); h.input.release("y")
        assertTrue(runBlocking { swallowed.await() }.ok)
        assertEquals(3, sends)
    }

    @Test fun confirmCardTimesOutAsCancelAndWakesALockedDisplay(): Unit = Harness(listOf(sensitive), confirmTimeoutMs = 100).use { h ->
        var woke = 0
        h.registry.onWake = { woke++ }
        h.ui.locked = true
        val r = h.call("test.send")
        assertEquals(Codes.TIMEOUT, r.code)
        assertTrue(r.message!!.contains("nothing was done"))
        assertEquals(0, sends); assertEquals(1, woke)
        assertTrue(h.ui.top is Home); assertTrue(h.ui.locked)
    }

    @Test fun confirmPolicySettingAndPerOpOverrides(): Unit = Harness(listOf(sensitive)).use { h ->
        h.policy = ConfirmPolicy.ALWAYS
        assertTrue(h.registry.needsConfirm(h.registry.ops["test.send"]!!, Origin.GLASSES_TURN))
        h.policy = ConfirmPolicy.NEVER
        assertFalse(h.registry.needsConfirm(h.registry.ops["test.send"]!!, Origin.OTHER))
        assertTrue(h.call("test.send").ok)
        h.policy = ConfirmPolicy.OUTSIDE_GLASSES
        assertFalse(h.registry.needsConfirm(h.registry.ops["test.never"]!!, Origin.OTHER))
        assertTrue(h.registry.needsConfirm(h.registry.ops["test.always"]!!, Origin.GLASSES_TURN))
        // A physical op follows the setting; one declared NEVER (a stop) never waits for a card.
        assertTrue(h.registry.needsConfirm(h.registry.ops["test.scene"]!!, Origin.OTHER))
        assertFalse(h.registry.needsConfirm(h.registry.ops["test.scene"]!!, Origin.GLASSES_TURN))
        h.policy = ConfirmPolicy.NEVER
        assertFalse(h.registry.needsConfirm(h.registry.ops["test.stop_all"]!!, Origin.OTHER))
        assertTrue(h.call("test.stop_all").ok)
        // Navigation and reads never ask.
        assertFalse(h.registry.needsConfirm(h.registry.ops["glasses.press"]!!, Origin.OTHER))
        assertEquals(ConfirmPolicy.OUTSIDE_GLASSES, ConfirmPolicy.parse(null))
        assertEquals(ConfirmPolicy.ALWAYS, ConfirmPolicy.parse("always"))
    }

    @Test fun failuresAreResultsWithCodes(): Unit = Harness(listOf(sensitive)).use { h ->
        assertEquals(Codes.UNKNOWN_OP, h.call("nope.op").code)
        assertEquals(Codes.TIMEOUT, h.call("test.slow").code)
        val crash = h.call("test.crash")
        assertEquals(Codes.FAILED, crash.code); assertEquals("boom", crash.message)
        assertEquals(Codes.UNAVAILABLE, h.call("joy.end_voice_call").code)
        val json = OpResult.fail(Codes.DENIED, "no").toJson("c1")
        assertEquals("result", json["t"]!!.jsonPrimitive.content); assertEquals("denied", json["code"]!!.jsonPrimitive.content)
    }

    @Test fun lockUnlockAndLockedPresses(): Unit = Harness().use { h ->
        assertTrue(h.call("glasses.lock").ok); assertTrue(h.ui.locked)
        assertEquals(Codes.UNAVAILABLE, h.call("glasses.press", mapOf("button" to "star")).code)
        assertEquals(Codes.UNAVAILABLE, h.call("glasses.open_app", mapOf("app" to "beeper")).code)
        assertTrue(h.ui.top is Home)
        assertTrue(h.call("glasses.unlock").ok); assertFalse(h.ui.locked)
        assertTrue(h.call("glasses.press", mapOf("button" to "minus")).ok); assertTrue(h.ui.locked)
    }

    @Test fun listOperationsAndRunReachNonToolOps(): Unit = Harness().use { h ->
        val all = h.call("glasses.list_operations").data!!.jsonArray.map { it.jsonObject }
        assertEquals(h.registry.ops.size, all.size)
        assertEquals("No assistant voice call is active", all.first { it["name"]!!.jsonPrimitive.content == "joy.end_voice_call" }["unavailable"]!!.jsonPrimitive.content)
        assertEquals(listOf("joy"), h.call("glasses.list_operations", mapOf("app" to "joy")).data!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content.substringBefore('.') }.distinct())
        val reply = h.call("glasses.run", mapOf("op" to "joy.read_reply"))
        assertTrue(reply.ok); assertTrue(reply.data!!.jsonObject.containsKey("status"))
        assertEquals(Codes.INVALID_ARGS, h.call("glasses.run", mapOf("op" to "glasses.run")).code)
        assertEquals(Codes.UNKNOWN_OP, h.call("glasses.run", mapOf("op" to "x.y")).code)
    }

    @Test fun joyAndHomeExampleOps(): Unit = Harness().use { h ->
        val home = h.call("home.summary").data!!.jsonObject
        assertTrue(home.containsKey("weather")); assertTrue(home.containsKey("beeperUnread"))
        assertTrue(h.call("joy.set_quiet", mapOf("on" to true)).ok); assertTrue(h.ui.quiet)
        assertTrue(h.call("joy.new_conversation").ok)
        val context = h.call("glasses.get_context").data!!.jsonObject
        assertEquals(false, context["locked"]!!.jsonPrimitive.boolean)
        assertEquals("bitmap", context["display"]!!.jsonPrimitive.content)
        assertTrue(context["screenOps"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("home.summary"))
    }

    @Test fun providersAreFoundByNameAndOnlyBuiltInsShipInThisStage() {
        Harness().use { h ->
            val probe = loadProvider("ZzProbe")!!.ops().single()
            assertEquals("pong", runBlocking { probe.run(AgentCall(h.ui, JsonObject(emptyMap()), "t", Origin.OTHER, h.registry)) }.message)
        }
        assertNull(loadProvider("NoSuchApp"))
        assertThrows(IllegalStateException::class.java) { loadProvider("ZzBad") }
        assertTrue(OP_PROVIDERS.containsAll(BUILT_IN_PROVIDERS))
        assertTrue(PLANNED_PROVIDERS.all { Regex("^[A-Z][A-Za-z0-9]*$").matches(it) })
    }

    @Test fun whileAConfirmCardIsUpTheAgentCanReadButNotAct(): Unit = Harness(listOf(sensitive)).use { h ->
        val pending = h.callAsync("test.send")
        assertTrue(h.ui.top is AgentConfirm)
        // The agent cannot press Y on its own card, or navigate away from it.
        assertEquals(Codes.BUSY, h.call("glasses.press", mapOf("button" to "y")).code)
        assertEquals(Codes.BUSY, h.call("glasses.back").code)
        assertTrue(h.call("glasses.read_screen").ok)
        assertTrue(h.ui.top is AgentConfirm); assertEquals(0, sends)
        h.input.press("b"); h.input.release("b")
        assertEquals(Codes.DENIED, runBlocking { pending.await() }.code)
        assertTrue(h.call("glasses.back").ok)
    }

    @Test fun aProviderFoundByNameAndPassedToTheHarnessRegistersOnce(): Unit = Harness(listOf(io.lampyr.glasses.agent.ops.JoyOps, HomeOps)).use { h ->
        // AGENT_OPS.md tells owners to write Harness(listOf(XxxOps)) although XxxOps is also discovered by name.
        assertEquals(1, h.registry.ops.keys.count { it == "joy.read_reply" })
    }

    @Test fun pressIsRefusedWhileAPhoneCallRings(): Unit = Harness().use { h ->
        (h.ui.data.calls as io.lampyr.glasses.calls.FakeCalls).incoming()
        val r = h.call("glasses.press", mapOf("button" to "y"))
        assertEquals(Codes.UNAVAILABLE, r.code)
        assertTrue(h.ui.data.calls.snapshot.ringing != null)
    }

    @Test fun manifestCarriesTheEffectiveConfirmSoTheGatewayWaitsForTheCard(): Unit = Harness(listOf(sensitive)).use { h ->
        val byName = h.registry.manifest()["ops"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        fun confirm(op: String) = byName[op]!!.jsonObject["confirm"]!!.jsonPrimitive.content
        assertEquals("always", confirm("test.always"))
        assertEquals("never", confirm("test.stop_all"))
        assertEquals("policy", confirm("test.send"))
        assertEquals("never", confirm("joy.end_voice_call"))
    }
}
