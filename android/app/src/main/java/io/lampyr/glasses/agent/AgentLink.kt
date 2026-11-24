package io.lampyr.glasses.agent

import io.lampyr.glasses.debug.Trace
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** A text WebSocket to the gateway's /agent/link. The production one is GatewayClient.agentSocketFactory(). */
interface AgentSocket {
    fun send(text: String): Boolean
    fun close()
}

interface AgentSocketListener {
    fun opened()
    fun text(value: String)
    fun ended(reason: String)
}

fun interface AgentSocketFactory { fun open(listener: AgentSocketListener): AgentSocket }

/**
 * Phone side of the agent link: announces the registry's manifest, pushes the screen context when it changes,
 * and executes `call` frames one at a time. Calls are never queued across a reconnect or replayed later.
 */
class AgentLink(
    private val factory: AgentSocketFactory,
    private val scope: CoroutineScope,
    private val app: String = "",
    private val pingMs: Long = 20_000,
    private val contextPollMs: Long = 1_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var registry: ActionRegistry? = null
    @Volatile var connected = false; private set
    var lastError: String? = null; private set
    var callsHandled = 0; private set

    private class Pending(val id: String, val op: String, val args: JsonObject, val origin: Origin, val deadlineMs: Long, val receivedAt: Long) {
        @Volatile var cancelled = false
        var job: Job? = null
    }

    private var job: Job? = null
    private var socket: AgentSocket? = null
    private var queue = Channel<Pending>(QUEUE_CAP)
    private var current: Pending? = null
    private val waiting = mutableListOf<Pending>()
    /** Last frame from the gateway (it answers every ping), so a silently dead connection is noticed. */
    private var lastInboundMs = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun loop() {
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            val opened = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<String>()
            val inbox = Channel<String>(Channel.UNLIMITED)
            val s = try {
                factory.open(object : AgentSocketListener {
                    override fun opened() { opened.complete(Unit) }
                    override fun text(value: String) { inbox.trySend(value) }
                    override fun ended(reason: String) { ended.complete(reason); inbox.close() }
                })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e.message; null }
            if (s != null) {
                socket = s
                try {
                    val ok = withTimeoutOrNull(15_000) { awaitOpen(opened, ended) } == true
                    if (ok) {
                        val openedAt = nowMs()
                        session(s, inbox, ended)
                        // Only a link that stayed up resets the backoff; one the gateway keeps closing right away
                        // (rejected, replaced) must not reconnect every second.
                        if (nowMs() - openedAt >= STABLE_MS) backoff = 1000L
                    }
                } finally {
                    connected = false
                    socket = null
                    dropAll()
                    s.close()
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private suspend fun awaitOpen(opened: CompletableDeferred<Unit>, ended: CompletableDeferred<String>): Boolean = coroutineScope {
        val a = async { opened.await(); true }
        val b = async { lastError = ended.await(); false }
        val first = kotlinx.coroutines.selects.select { a.onAwait { it }; b.onAwait { it } }
        a.cancel(); b.cancel(); first
    }

    private suspend fun session(s: AgentSocket, inbox: Channel<String>, ended: CompletableDeferred<String>) = coroutineScope {
        val reg = registry
        send(buildJsonObject { put("t", "hello"); put("v", 1); put("app", app); reg?.let { put("manifest", it.manifest()) } })
        connected = true
        lastInboundMs = nowMs()
        Trace.log("Agent", "link open ops=${reg?.ops?.size ?: 0}")
        queue = Channel(QUEUE_CAP)
        val worker = launch { for (p in queue) execute(p) }
        val ping = launch {
            while (isActive) {
                delay(pingMs)
                if (nowMs() - lastInboundMs > pingMs * 3) {
                    // No pong for three pings: the connection is dead even if the socket has not said so. Reconnect.
                    lastError = "no answer from the gateway"; inbox.close(); break
                }
                send(buildJsonObject { put("t", "ping") })
            }
        }
        val pusher = launch {
            var last: String? = null
            while (isActive) {
                val ctx = runCatching { registry?.context(includeText = false) }.getOrNull()
                val text = ctx?.toString()
                if (text != null && text != last) { last = text; send(buildJsonObject { put("t", "context"); put("ctx", ctx) }) }
                delay(contextPollMs)
            }
        }
        try {
            for (raw in inbox) handle(raw)
        } finally {
            Trace.log("Agent", "link closed ${if (ended.isCompleted) ended.getCompleted() else ""}")
            worker.cancel(); ping.cancel(); pusher.cancel(); queue.close()
        }
    }

    private fun send(value: JsonObject) = socket?.send(value.toString()) == true

    private fun handle(raw: String) {
        lastInboundMs = nowMs()
        val msg = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        when ((msg["t"] as? JsonPrimitive)?.contentOrNull) {
            "call" -> {
                val id = (msg["id"] as? JsonPrimitive)?.contentOrNull ?: return
                val op = (msg["op"] as? JsonPrimitive)?.contentOrNull ?: ""
                val args = msg["args"] as? JsonObject ?: JsonObject(emptyMap())
                val channel = ((msg["origin"] as? JsonObject)?.get("channel") as? JsonPrimitive)?.contentOrNull ?: "other"
                val deadline = (msg["deadlineMs"] as? JsonPrimitive)?.longOrNull ?: 10_000L
                val p = Pending(id, op, args, Origin(channel), deadline, nowMs())
                if (queue.trySend(p).isSuccess) waiting += p
                else send(OpResult.fail(Codes.BUSY, "The glasses are busy with other actions; try again shortly").toJson(id))
            }
            "cancel" -> {
                val id = (msg["id"] as? JsonPrimitive)?.contentOrNull ?: return
                waiting.firstOrNull { it.id == id }?.cancelled = true
                current?.takeIf { it.id == id }?.let { it.cancelled = true; it.job?.cancel() }
            }
        }
    }

    private suspend fun execute(p: Pending) {
        waiting -= p
        if (p.cancelled) return
        // The gateway has already answered "timeout" for a call that waited past its deadline: never run it late.
        if (nowMs() - p.receivedAt > p.deadlineMs) { send(OpResult.fail(Codes.TIMEOUT, "Dropped: waited too long in the queue").toJson(p.id)); return }
        val reg = registry ?: run { send(OpResult.fail(Codes.UNAVAILABLE, "The glasses app is restarting").toJson(p.id)); return }
        current = p
        try {
            val result = coroutineScope {
                val work = async { reg.call(p.op, p.args, p.id, p.origin) }
                p.job = work
                try { work.await() } catch (e: CancellationException) { if (p.cancelled) null else throw e }
            }
            callsHandled++
            if (result != null && !p.cancelled) send(result.toJson(p.id))
        } finally { current = null }
    }

    private fun dropAll() {
        waiting.forEach { it.cancelled = true }; waiting.clear()
        current?.let { it.cancelled = true; it.job?.cancel() }
    }

    companion object {
        const val QUEUE_CAP = 8
        /** A session at least this long counts as a working link (the reconnect backoff starts over). */
        const val STABLE_MS = 10_000L
    }
}
