package io.lampyr.glasses.agent

import io.lampyr.glasses.agent.ops.*
import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.debug.Trace
import io.lampyr.glasses.input.InputEngine
import io.lampyr.glasses.ui.Ui
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Agent control ("anything the remote can do, the assistant can do"). See android/AGENT_OPS.md.
 *
 * Every app registers named operations (`app.verb`) in its own `ops/XxxOps.kt`. The registry publishes them to
 * the gateway over the agent link; the gateway turns them into MCP tools for the assistant and relays calls back here.
 */

/** What an operation can do to the world. SEND/CALL/SPEND/PHYSICAL are "sensitive" and fall under the confirm policy. */
enum class Risk(val wire: String, val sensitive: Boolean) {
    /** Reads state only. */
    READ("read", false),
    /** Moves around the UI (open, back, select, press). */
    NAVIGATE("navigate", false),
    /** Changes local state on the phone/glasses (lock, mark read, pause, new conversation). */
    ACT("act", false),
    /** Sends text to another person or agent (message, reply, T3 prompt, typed text). */
    SEND("send", true),
    /** Places or starts a call. */
    CALL("call", true),
    /** Spends money, credits or quota (purchases, usage resets). */
    SPEND("spend", true),
    /** Acts on the physical world (Home Assistant scenes). */
    PHYSICAL("physical", true),
}

/** Per-op override of the confirm policy. POLICY = sensitive ops follow the wearer's setting. */
enum class Confirm(val wire: String) { POLICY("policy"), ALWAYS("always"), NEVER("never") }

/** The wearer's setting (AppSettings.agentConfirm). Default: confirm only when the request did not come from the glasses. */
enum class ConfirmPolicy(val wire: String) {
    OUTSIDE_GLASSES("outside_glasses"), ALWAYS("always"), NEVER("never");
    companion object { fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: OUTSIDE_GLASSES }
}

/** Where a call came from. `channel == "glasses"` while a glasses conversation turn is in flight. */
data class Origin(val channel: String = "other") {
    val fromGlasses get() = channel == GLASSES
    companion object { const val GLASSES = "glasses"; val OTHER = Origin(); val GLASSES_TURN = Origin(GLASSES) }
}

/** Error codes shared by the phone, the gateway, MCP and REST. */
object Codes {
    const val OFFLINE = "offline"
    const val TIMEOUT = "timeout"
    const val BUSY = "busy"
    const val UNKNOWN_OP = "unknown_op"
    const val INVALID_ARGS = "invalid_args"
    const val UNAVAILABLE = "unavailable"
    const val AMBIGUOUS = "ambiguous"
    const val DENIED = "denied"
    const val PERMISSION = "permission"
    const val FAILED = "failed"
}

/** One JSON-schema parameter. Build with the companion helpers: `Param.string("chat", "Chat id or name", required = true)`. */
class Param(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val maxLength: Int? = null,
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
        enum?.let { values -> put("enum", JsonArray(values.map(::JsonPrimitive))) }
        min?.let { put("minimum", if (type == "integer") JsonPrimitive(it.toLong()) else JsonPrimitive(it)) }
        max?.let { put("maximum", if (type == "integer") JsonPrimitive(it.toLong()) else JsonPrimitive(it)) }
        maxLength?.let { put("maxLength", it) }
    }

    companion object {
        fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null, maxLength: Int? = null) =
            Param(name, "string", description, required, enum, maxLength = maxLength)
        fun integer(name: String, description: String, required: Boolean = false, min: Int? = null, max: Int? = null) =
            Param(name, "integer", description, required, min = min?.toDouble(), max = max?.toDouble())
        fun number(name: String, description: String, required: Boolean = false, min: Double? = null, max: Double? = null) =
            Param(name, "number", description, required, min = min, max = max)
        fun boolean(name: String, description: String, required: Boolean = false) = Param(name, "boolean", description, required)
        fun obj(name: String, description: String, required: Boolean = false) = Param(name, "object", description, required)
    }
}

/**
 * One agent operation.
 * - [name] `app.verb_noun`, lower snake case, e.g. `beeper.send_message`. The MCP tool name is the same with `.` -> `_`.
 * - [title] <= 40 chars, shown on the glasses confirm card ("Send Beeper message").
 * - [description] <= 300 chars, written for the model. The gateway prefixes "Controls the user's RayNeo glasses".
 * - [available] returns null when the op can run now, or a short reason ("No call is ringing").
 * - [summary] one line for the confirm card ("to Mom: running late").
 * - [tool] false keeps it off the MCP tool list (still callable through `glasses.run` and listed by `glasses.list_operations`).
 * - [run] executes on the main thread inside the UI scope. Return [OpResult]; throw [OpError] (or call [AgentCall.fail]) to fail.
 */
class OpSpec(
    val name: String,
    val title: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val risk: Risk = Risk.ACT,
    val confirm: Confirm = Confirm.POLICY,
    val tool: Boolean = true,
    val timeoutMs: Long = 10_000,
    val available: (Ui) -> String? = { null },
    val summary: (AgentCall) -> String = { "" },
    val run: suspend AgentCall.() -> OpResult,
) {
    val app get() = name.substringBefore('.')
    /** The same op kept off the MCP tool list (see [ActionRegistry.LONG_TAIL]). */
    fun hidden() = OpSpec(name, title, description, params, risk, confirm, false, timeoutMs, available, summary, run)

    /** [confirm] as sent in the manifest. */
    val effectiveConfirm: Confirm get() = confirm

    fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { params.forEach { put(it.name, it.schema()) } })
        params.filter { it.required }.takeIf { it.isNotEmpty() }?.let { req -> put("required", JsonArray(req.map { JsonPrimitive(it.name) })) }
        put("additionalProperties", false)
    }

    fun manifestEntry(): JsonObject = buildJsonObject {
        put("name", name); put("app", app); put("title", title); put("description", description)
        put("risk", risk.wire); put("confirm", effectiveConfirm.wire); put("tool", tool); put("timeoutMs", timeoutMs)
        put("inputSchema", inputSchema())
    }

    companion object {
        val NAME = Regex("^[a-z][a-z0-9]*\\.[a-z][a-z0-9_]*$")
        const val MAX_TIMEOUT_MS = 45_000L
    }
}

/** The result every op returns. Read ops put compact JSON in [data]; action ops say what happened in [message]. */
data class OpResult(val ok: Boolean = true, val message: String? = null, val data: JsonElement? = null, val code: String? = null) {
    fun toJson(id: String): JsonObject = buildJsonObject {
        put("t", "result"); put("id", id); put("ok", ok)
        message?.let { put("message", it) }
        data?.let { put("data", it) }
        if (!ok) put("code", code ?: Codes.FAILED)
    }

    companion object {
        fun ok(message: String? = null, data: JsonElement? = null) = OpResult(true, message, data)
        fun fail(code: String, message: String, data: JsonElement? = null) = OpResult(false, message, data, code)
    }
}

class OpError(val code: String, message: String, val data: JsonElement? = null) : Exception(message)

/** The receiver of [OpSpec.run]: typed argument accessors plus result helpers. */
class AgentCall(val ui: Ui, val args: JsonObject, val id: String, val origin: Origin, val registry: ActionRegistry) {
    private fun prim(name: String) = (args[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    fun has(name: String) = prim(name) != null || args[name] is JsonObject || args[name] is JsonArray
    fun strOrNull(name: String): String? = prim(name)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    fun str(name: String): String = strOrNull(name) ?: fail(Codes.INVALID_ARGS, "Missing \"$name\"")
    fun intOrNull(name: String): Int? = prim(name)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
    fun int(name: String, default: Int? = null): Int = intOrNull(name) ?: default ?: fail(Codes.INVALID_ARGS, "Missing integer \"$name\"")
    fun num(name: String, default: Double? = null): Double = prim(name)?.takeUnless { it.isString }?.doubleOrNull ?: default ?: fail(Codes.INVALID_ARGS, "Missing number \"$name\"")
    fun bool(name: String, default: Boolean = false): Boolean = prim(name)?.takeUnless { it.isString }?.booleanOrNull ?: default
    fun obj(name: String): JsonObject = args[name] as? JsonObject ?: JsonObject(emptyMap())
    fun fail(code: String, message: String, data: JsonElement? = null): Nothing = throw OpError(code, message, data)
    fun ok(message: String? = null, data: JsonElement? = null) = OpResult.ok(message, data)
    /** Maps a repository [ActionResult]: failures become `failed` with the repository's own message. */
    fun done(result: ActionResult, okMessage: String? = null, data: JsonElement? = null): OpResult =
        if (result.ok) OpResult.ok(result.message ?: okMessage, data) else OpResult.fail(Codes.FAILED, result.message ?: "Action failed")
}

/** Each app's `object XxxOps : OpProvider` returns its ops. Build them once; read live state inside `available`/`run`. */
fun interface OpProvider { fun ops(): List<OpSpec> }

/** Providers owned by agent control: generic system/navigation ops plus the Home and assistant worked examples. */
val BUILT_IN_PROVIDERS: List<OpProvider> = listOf(GlassesOps, HomeOps, JoyOps)

/**
 * Per-app providers, found by name so adding one never edits this file: app `X` lives in
 * `agent/ops/XOps.kt` as `object XOps : OpProvider` (package `io.lampyr.glasses.agent.ops`). A missing file is
 * simply skipped. An app not on this list appends its name here (one line).
 */
val PLANNED_PROVIDERS: List<String> = listOf(
    "Beeper", "T3", "Media", "Notification", "Call", "Clock", "People", "Health", "Calendar", "Report",
    "HomeAssistant", "Docs", "Memo", "Teleprompter", "Status", "Settings", "Shortcuts", "Lifelog", "Nav",
)

/** Loads `io.lampyr.glasses.agent.ops.<name>Ops`; null when absent. A present class that is not an OpProvider object fails loudly. */
fun loadProvider(name: String): OpProvider? {
    val cls = try { Class.forName("io.lampyr.glasses.agent.ops.${name}Ops") } catch (_: ClassNotFoundException) { return null }
    // A Kotlin `object` exposes its singleton as the static INSTANCE field (no kotlin-reflect needed).
    return runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() as? OpProvider
        ?: throw IllegalStateException("${cls.name} must be declared as `object ${name}Ops : OpProvider`")
}

/** Every provider: built-ins first, then each planned app that has its file. */
val OP_PROVIDERS: List<OpProvider> by lazy { BUILT_IN_PROVIDERS + PLANNED_PROVIDERS.mapNotNull(::loadProvider) }

/** Names the gateway serves itself (they work with the phone offline); the phone must not register them. */
val RESERVED_OPS = setOf("glasses.notify", "glasses.push_doc", "glasses.link_status")

class ActionRegistry(
    val ui: Ui,
    providers: List<OpProvider> = OP_PROVIDERS,
    private val policy: () -> ConfirmPolicy = { ConfirmPolicy.OUTSIDE_GLASSES },
    val confirmTimeoutMs: Long = 20_000,
) {
    /** Late-bound: `glasses.press` drives the same InputEngine the remote does. */
    var input: InputEngine? = null
    /** Wakes the glasses display for a confirm card (the service reclaims the transport). */
    var onWake: () -> Unit = {}

    val ops: Map<String, OpSpec> = linkedMapOf<String, OpSpec>().also { map ->
        // distinct(): a provider that is discovered by name and also passed explicitly (tests) registers once.
        providers.distinct().flatMap { it.ops() }.map { if (it.name in LONG_TAIL && it.tool) it.hidden() else it }.forEach { spec ->
            require(OpSpec.NAME.matches(spec.name)) { "Bad op name ${spec.name}" }
            require(spec.name !in RESERVED_OPS) { "${spec.name} is served by the gateway" }
            require(map.put(spec.name, spec) == null) { "Duplicate op ${spec.name}" }
        }
    }

    fun manifest(): JsonObject {
        val entries = JsonArray(ops.values.map { it.manifestEntry() })
        val hash = MessageDigest.getInstance("SHA-256").digest(entries.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return buildJsonObject { put("v", 1); put("hash", hash); put("ops", entries) }
    }

    /** Null when available; otherwise the reason. Never throws. */
    fun unavailable(spec: OpSpec): String? = try { spec.available(ui) } catch (e: Exception) { e.message ?: "Unavailable" }

    /** Live context (the phone pushes it without screen text; get_context adds the text). */
    fun context(includeText: Boolean = false): JsonObject = AgentScreen.context(this, includeText)

    /** Whether this call needs the confirm card, given the op, where it came from and the wearer's setting. */
    fun needsConfirm(spec: OpSpec, origin: Origin): Boolean {
        return when (spec.effectiveConfirm) {
            Confirm.NEVER -> false
            Confirm.ALWAYS -> true
            Confirm.POLICY -> spec.risk.sensitive && when (policy()) {
                ConfirmPolicy.ALWAYS -> true
                ConfirmPolicy.NEVER -> false
                ConfirmPolicy.OUTSIDE_GLASSES -> !origin.fromGlasses
            }
        }
    }

    /** Validates against the declared schema (the gateway validates too; the phone never trusts it). */
    fun validate(spec: OpSpec, args: JsonObject): String? {
        for ((key, _) in args) if (spec.params.none { it.name == key }) return "Unknown argument \"$key\""
        for (p in spec.params) {
            val value = args[p.name]?.takeUnless { it is JsonNull }
            if (value == null) { if (p.required) return "Missing required argument \"${p.name}\""; continue }
            val prim = value as? JsonPrimitive
            val ok = when (p.type) {
                "string" -> prim?.isString == true
                "integer" -> prim != null && !prim.isString && prim.doubleOrNull?.let { it % 1.0 == 0.0 } == true
                "number" -> prim != null && !prim.isString && prim.doubleOrNull?.isFinite() == true
                "boolean" -> prim != null && !prim.isString && prim.booleanOrNull != null
                "object" -> value is JsonObject
                else -> true
            }
            if (!ok) return "Argument \"${p.name}\" must be ${if (p.type == "integer") "an integer" else "a ${p.type}"}"
            if (p.enum != null && prim?.content !in p.enum) return "Argument \"${p.name}\" must be one of ${p.enum.joinToString()}"
            val n = prim?.takeUnless { it.isString }?.doubleOrNull
            if (n != null && p.min != null && n < p.min) return "Argument \"${p.name}\" must be >= ${p.min.fmt()}"
            if (n != null && p.max != null && n > p.max) return "Argument \"${p.name}\" must be <= ${p.max.fmt()}"
            if (p.maxLength != null && prim?.isString == true && prim.content.length > p.maxLength) return "Argument \"${p.name}\" is longer than ${p.maxLength} characters"
        }
        return null
    }

    private var confirming: AgentConfirm? = null

    /** Runs one op. Never throws (except cancellation); every failure is an [OpResult] with a code. */
    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap()), id: String = "local", origin: Origin = Origin.OTHER): OpResult {
        val started = System.currentTimeMillis()
        val result = try { execute(name, args, id, origin) }
            catch (e: CancellationException) { throw e }
            catch (e: OpError) { OpResult.fail(e.code, e.message ?: "Failed", e.data) }
            catch (e: Exception) { OpResult.fail(Codes.FAILED, e.message ?: "Action failed") }
        // Never log arguments: they can carry message text.
        Trace.log("Agent", "call id=$id op=$name origin=${origin.channel} ok=${result.ok} code=${result.code ?: "-"} ms=${System.currentTimeMillis() - started}")
        return result
    }

    private suspend fun execute(name: String, args: JsonObject, id: String, origin: Origin): OpResult {
        val spec = ops[name] ?: return OpResult.fail(Codes.UNKNOWN_OP, "No glasses operation named $name")
        validate(spec, args)?.let { return OpResult.fail(Codes.INVALID_ARGS, it) }
        // Only the user answers a confirm card: while one is up, the agent may read but not act (no pressing Y on it).
        if (confirming != null && spec.risk != Risk.READ) return OpResult.fail(Codes.BUSY, "A confirmation is waiting for the user on the glasses")
        unavailable(spec)?.let { return OpResult.fail(Codes.UNAVAILABLE, it) }
        val call = AgentCall(ui, args, id, origin, this)
        if (needsConfirm(spec, origin)) {
            when (confirm(spec, call)) {
                true -> {}
                false -> return OpResult.fail(Codes.DENIED, "The user cancelled \"${spec.title}\" on the glasses. Nothing was done.")
                null -> return OpResult.fail(Codes.TIMEOUT, "The user did not confirm \"${spec.title}\" within ${confirmTimeoutMs / 1000} s, so nothing was done.")
            }
            // The state may have changed while the card was up.
            unavailable(spec)?.let { return OpResult.fail(Codes.UNAVAILABLE, it) }
        } else if (spec.risk.sensitive) ui.flash("${io.lampyr.glasses.settings.Names.assistant}: ${spec.title}")
        // glasses.run wraps another op, which may itself wait for a confirm card before its own timeout starts.
        val limit = spec.timeoutMs.coerceIn(500, OpSpec.MAX_TIMEOUT_MS) + if (spec.name == RUN_OP) confirmTimeoutMs else 0
        val result = withTimeoutOrNull(limit) { spec.run(call) }
            ?: OpResult.fail(Codes.TIMEOUT, "${spec.title} did not finish in time; it may still complete")
        // The assistant acting on the glasses must be visible: anything beyond a read lights the lens for a moment,
        // then the normal idle fade takes over.
        if (spec.risk != Risk.READ && result.ok) ui.wake("agent:${spec.name}", AGENT_RESULT_WAKE_MS)
        return result
    }

    /** Shows the confirm card. true = confirmed (Y), false = cancelled (B or closed), null = timed out. */
    private suspend fun confirm(spec: OpSpec, call: AgentCall): Boolean? {
        if (confirming != null) throw OpError(Codes.BUSY, "Another confirmation is already on the glasses")
        val decision = CompletableDeferred<Boolean>()
        val summary = runCatching { spec.summary(call) }.getOrDefault("")
        val card = AgentConfirm(ui, spec.title, summary, (confirmTimeoutMs / 1000).toInt()) { decision.complete(it) }
        val wasLocked = ui.locked
        ui.locked = false
        onWake()
        ui.wake("agent-confirm", confirmTimeoutMs)
        ui.push(card)
        if (ui.top !== card) {
            ui.locked = wasLocked
            throw OpError(Codes.UNAVAILABLE, "The glasses are showing a ringing call; try again after it")
        }
        confirming = card
        try {
            return withTimeoutOrNull(confirmTimeoutMs) { decision.await() }
        } finally {
            confirming = null
            card.dismiss()
            if (wasLocked) ui.locked = true
            ui.requestRender()
        }
    }

    companion object {
        const val AGENT_RESULT_WAKE_MS = 5_000L
        /**
         * Tool budget: every MCP tool schema rides in every assistant prompt, so the assistant sees about 80 tools (85 is the ceiling). These long-tail
         * ops stay reachable through glasses_list_operations + glasses_run (same validation, confirm rules and results).
         */
        val LONG_TAIL = setOf(
            "glasses.lock", "glasses.unlock", "glasses.show_toast",
            "notifications.dismiss",
            "call.mute", "call.routes", "call.state",
            "timer.pause", "timer.resume", "timer.add_time",
            "stopwatch.lap", "stopwatch.reset", "stopwatch.state",
            "alarm.enable", "alarm.dismiss", "alarm.snooze",
            "settings.set_head_gestures", "settings.set_display_position",
        )
        const val RUN_OP = "glasses.run"
        private fun Double.fmt() = if (this % 1.0 == 0.0) toLong().toString() else toString()
    }
}
