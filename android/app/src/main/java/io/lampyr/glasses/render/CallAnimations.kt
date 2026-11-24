package io.lampyr.glasses.render

import io.lampyr.glasses.data.JoyWorker
import io.lampyr.glasses.render.avatar.AvatarStates

/**
 * Avatar states for a voice call, as canonical state names ([AvatarStates]); the avatar pack decides what each
 * looks like and falls back for anything it does not draw. Two avatars are shown during a call: the voice
 * assistant (the realtime call agent, the big portrait) and the worker (the main agent on Hermes doing the
 * handed-off work, the small portrait). Emotion and ambient states add the one-shot moments (done, late result,
 * hello, goodbye).
 *
 * The simulator mirrors these tables in simulator/index.html (CALL_ANIM / WORKER_ANIM / CALL_TOOLS);
 * JoyCallLongCallsTest checks both stay in step and that every state resolves in the bundled pack.
 */
object CallAnimations {
    /** The voice assistant, by call status (JoyState.status during a call). */
    val VOICE: Map<String, String> = linkedMapOf(
        "connecting" to "waking",
        "listening" to "listening",
        "thinking" to "thinking",
        "speaking" to "speaking",
        "delegating" to "delegating",       // it just handed the request to the worker
        "waiting" to "waiting",             // the worker is still busy; the voice stays quiet
        "asking" to "asking",               // the worker needs the user's approval
        "interrupted" to "interrupted",     // the user talked over it (barge-in)
        "compacting" to "compressing",      // the bridge is rolling the realtime context into a fresh session
        "reconnecting" to "checking_watch", // the link dropped and the phone is reconnecting
        "muted" to "looking_around",        // present but cannot hear the user
        "error" to "error",
        "result" to "happy",                // a handed-off result came back
        "late" to "nodding",                // a late result it said was on its way
        "ended" to "waving",                // goodbye
        "idle" to "idle",
        "sleeping" to "sleeping",           // lens dark (locked) while the call continues
    )

    /** The worker, by bridge `agent` state. `tool` is refined by [toolAnim]. */
    val WORKER: Map<String, String> = linkedMapOf(
        "started" to "curious",     // picked the request up
        "working" to "working",
        "tool" to "working",        // unknown tool
        "approval" to "asking",     // waiting for the user's approval
        "done" to "celebrating",
        "error" to "error",
        "late" to "waiting",        // still running after the voice's wait; the result follows
    )

    /** Tool families, matched on the Hermes tool name (first match wins): avatar state and worker label. */
    val TOOLS: List<Triple<Regex, String, String>> = listOf(
        Triple(Regex("memory_(add|save|write)|save|learn|skill|note|remember_"), "saving", "saving"),
        Triple(Regex("memory|recall|session_search|history|remember|vault_read"), "remembering", "remembering"),
        Triple(Regex("search|browse|web|fetch|crawl|lookup|scrape|url"), "searching", "searching"),
        Triple(Regex("calendar|clock|time|timer|alarm|reminder"), "checking_watch", "checking the time"),
        Triple(Regex("delegate|subagent|kanban|handoff|spawn"), "delegating", "delegating"),
        Triple(Regex("terminal|shell|exec|code|patch|file|python|git|edit"), "nerdy", "running code"),
        Triple(Regex("image|draw|generate|video|music|design"), "creative", "creating"),
        Triple(Regex("compress|summar|compact"), "compressing", "summarising"),
        Triple(Regex("ask|clarify|human_input|approval"), "asking", "asking"),
        Triple(Regex("send|message|post|reply|email|beeper|discord|notify"), "speaking", "sending"), // sending on the user's behalf
    )

    private fun tool(tool: String?) = tool?.lowercase()?.takeIf { it.isNotEmpty() }?.let { n -> TOOLS.firstOrNull { it.first.containsMatchIn(n) } }

    fun toolAnim(tool: String?): String = tool(tool)?.second ?: WORKER.getValue("tool")

    fun worker(w: JoyWorker?): String? = when {
        w == null -> null
        w.state == "tool" -> toolAnim(w.tool)
        else -> WORKER[w.state] ?: WORKER.getValue("working")
    }

    /**
     * The voice avatar's state: muted and link states win, a pending handoff turns an idle "listening" into
     * delegating/waiting/asking (late: nodding, "it's on its way"), a finished one shows it happy for the
     * worker's linger, and the bridge's own states pass through.
     */
    fun voice(status: String, muted: Boolean = false, worker: JoyWorker? = null, workerAge: Double = 0.0): String {
        val active = worker != null && worker.state !in setOf("done", "error")
        val key = when {
            status in setOf("reconnecting", "compacting", "error", "connecting", "interrupted", "ended") -> status
            status == "speaking" -> "speaking"
            muted -> "muted"
            worker?.state == "approval" -> "asking"
            worker?.state == "late" -> "late"
            worker?.state == "done" -> "result"
            active -> if (workerAge < 2.0) "delegating" else "waiting"
            else -> status
        }
        return VOICE[key] ?: key.takeIf { it in AvatarStates.STATUS } ?: VOICE.getValue("listening")
    }

    /** Short words for the worker line ("<worker> · searching"). */
    fun workerLabel(w: JoyWorker): String = when (w.state) {
        "started" -> "on it"
        "working" -> "working"
        "tool" -> toolLabel(w.tool)
        "approval" -> "needs your OK"
        "done" -> "done"
        "error" -> "failed"
        "late" -> "still working"
        else -> w.state
    }

    private fun toolLabel(name: String?): String = tool(name)?.third ?: name?.replace('_', ' ')?.take(24) ?: "working"
}
