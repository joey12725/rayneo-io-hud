package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.ui.screens.T3Thread
import kotlinx.serialization.json.*

/** T3 Code: machines, threads (list/read/open), send or steer, answer approvals and questions, new threads, usage. */
object T3Ops : OpProvider {
    private val THREAD = Param.string("thread", "Thread id or title (see t3_list_threads)", required = true, maxLength = 300)
    private val MACHINE = Param.string("machine", "Machine id, when the thread id is not unique", maxLength = 100)

    /** Thread by id ("machine/id" also works) or title, among active and settled threads, optionally on one machine. */
    private fun AgentCall.thread(): Thread {
        var wanted = str("thread")
        var machine = strOrNull("machine")
        val all = (ui.data.t3.threads + ui.data.t3.settled).distinctBy { it.key }
        // "machine/id" only when the prefix is a real machine: a title such as "Fix a/b test" stays a title.
        if (machine == null && '/' in wanted) wanted.split('/', limit = 2).let { (m, id) ->
            if (all.any { it.machine == m } || ui.data.t3.machines.any { it.machine == m }) { machine = m; wanted = id }
        }
        val pool = all.filter { machine == null || it.machine == machine }
        val byId = pool.filter { it.id == wanted }
        if (byId.size == 1) return byId.single()
        if (byId.size > 1) fail(Codes.AMBIGUOUS, "Thread $wanted exists on several machines; pass machine",
            JsonArray(byId.map { buildJsonObject { put("machine", it.machine); put("id", it.id); put("title", it.title) } }))
        return resolve(wanted, pool, "T3 thread", "t3_list_threads", { it.id }, { it.title })
    }

    private suspend fun AgentCall.detail(th: Thread): ThreadDetail {
        val refreshed = ui.data.t3.refreshThread(th.machine, th.id)
        return ui.data.t3.detail(th.machine, th.id)
            ?: fail(Codes.FAILED, refreshed.message ?: "Could not load \"${th.title}\"")
    }

    private fun threadJson(th: Thread) = buildJsonObject {
        put("machine", th.machine); put("id", th.id); put("title", th.title)
        put("project", th.project); put("provider", th.provider); put("status", th.status)
        if (th.status in T3_NEEDS_YOU) put("needsYou", true)
        put("ageSec", th.ageSec)
        th.step?.let { put("step", "${it.label}: ${it.what}") }
        if (!th.machineOnline) put("machineOffline", true)
    }

    private fun requestJson(r: Request) = buildJsonObject {
        put("id", r.id); put("kind", r.kind)
        r.approval?.let { a -> put("approval", buildJsonObject { put("kind", a.kind); put("command", clip(a.command, 1_000)); put("reason", clip(a.reason, 500)) }) }
        if (r.questions.isNotEmpty()) put("questions", JsonArray(r.questions.map { q ->
            buildJsonObject {
                put("id", q.id); putText("header", q.header); put("question", clip(q.question, 600))
                put("options", JsonArray(q.options.map { JsonPrimitive(it.label) }))
                if (q.multi) put("multi", true)
            }
        }))
        if (r.messages.isNotEmpty()) put("messages", JsonArray(r.messages.takeLast(3).map { JsonPrimitive("${it.who}: ${clip(it.text, 400)}") }))
    }

    /** One answer value: an option label (exact, number "2", or a unique partial label), else the user's own free text. */
    private fun option(q: Question, value: String): String {
        val v = value.trim()
        q.options.firstOrNull { it.label.equals(v, ignoreCase = true) }?.let { return it.label }
        v.toIntOrNull()?.let { n -> q.options.getOrNull(n - 1)?.let { return it.label } }
        q.options.filter { it.label.contains(v, ignoreCase = true) }.singleOrNull()?.let { return it.label }
        return v
    }

    private fun values(value: JsonElement?): List<String>? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }?.let(::listOf)
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun AgentCall.answers(request: Request): Map<String, List<String>> {
        val given = obj("answers")
        val choice = strOrNull("choice")
        // A free-text request ("text") has no questions: the answer is keyed by the request id, as T3Question sends it.
        val questions = request.questions.ifEmpty { if (request.kind == "text") listOf(Question(request.id, question = "")) else emptyList() }
        if (questions.isEmpty()) fail(Codes.UNAVAILABLE, "This request has no questions to answer")
        return questions.mapIndexed { i, q ->
            val raw = values(given[q.id]) ?: values(given[q.header]) ?: values(given[(i + 1).toString()])
                ?: (if (questions.size == 1 || (i == 0 && given.isEmpty())) choice?.let(::listOf) else null)
                ?: fail(Codes.INVALID_ARGS, "Missing an answer for question ${q.id}: ${q.question.take(120)}")
            q.id to raw.map { option(q, it) }.let { if (q.multi) it.distinct() else it.take(1) }
        }.toMap()
    }

    override fun ops() = listOf(
        OpSpec(
            "t3.list_threads", "List T3 threads",
            "The user's T3 Code threads across machines: machine, id, title, project, provider, status (input/approval/request/limited/failed need the user; working; done), age and current step.",
            params = listOf(
                Param.string("status", "needs_you, working, done or all (default)", enum = listOf("needs_you", "working", "done", "all")),
                Param.boolean("include_settled", "Also list settled (archived) threads"),
            ),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.t3.refreshThreads()
            if (bool("include_settled")) ui.data.t3.refreshSettled()
            val filter = strOrNull("status") ?: "all"
            val threads = (ui.data.t3.threads + if (bool("include_settled")) ui.data.t3.settled else emptyList()).filter {
                when (filter) {
                    "needs_you" -> it.status in T3_NEEDS_YOU
                    "working" -> it.status in listOf("working", "waiting")
                    "done" -> it.status == "done"
                    else -> true
                }
            }
            ok(data = buildJsonObject {
                put("needYou", ui.data.t3.needYouCount)
                if (ui.data.t3.offlineCount > 0) put("machinesOffline", ui.data.t3.offlineCount)
                put("threads", JsonArray(threads.take(MAX_ITEMS).map(::threadJson)))
            })
        },

        OpSpec(
            "t3.read_thread", "Read a T3 thread",
            "One thread in full: status, the agent's latest message, a pending request (approval command, or questions with option labels), the working step and recent history.",
            params = listOf(THREAD, MACHINE, Param.integer("history", "How many recent history entries (default 4)", min = 0, max = 20)),
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            val d = detail(thread())
            ok(data = buildJsonObject {
                put("thread", threadJson(d.thread))
                d.latest?.let { put("latest", clip(it.text, 2_500)) }
                d.request?.let { put("request", requestJson(it)) }
                d.working?.now?.let { put("working", "${it.label}: ${it.what}") }
                d.working?.recent?.takeIf { it.isNotEmpty() }?.let { r -> put("recentSteps", JsonArray(r.takeLast(5).map { JsonPrimitive(it.label) })) }
                val n = int("history", 4)
                if (n > 0) put("history", JsonArray(d.history.takeLast(n).map { JsonPrimitive("${it.who}: ${clip(it.text, 600)}") }))
            })
        },

        OpSpec(
            "t3.open_thread", "Open a T3 thread",
            "Show one T3 thread on the glasses (its current view: working, approval, question or latest message).",
            params = listOf(THREAD, MACHINE),
            risk = Risk.NAVIGATE, available = ::lockedReason,
        ) {
            val th = thread()
            ui.open(OpenTarget("t3-thread", mapOf("machine" to th.machine, "id" to th.id)))
            expectTop("\"${th.title}\"") { it is T3Thread && it.threadKey == th.key }
            ok("Opened ${th.title}", registry.context())
        },

        OpSpec(
            "t3.send_message", "Send to a T3 thread",
            "Send a prompt to a T3 thread. A running thread queues it for after the turn unless mode is steer (redirects the running turn). /glasses is added so replies come back short.",
            params = listOf(
                THREAD, MACHINE,
                Param.string("text", "The prompt, exactly as it should be sent", required = true, maxLength = 4000),
                Param.string("mode", "send (default; queues while running), queue or steer", enum = listOf("send", "queue", "steer")),
            ),
            risk = Risk.SEND, timeoutMs = 20_000,
            summary = { call -> "${call.strOrNull("thread").orEmpty().take(30)}: ${call.strOrNull("text").orEmpty().take(60)}" },
        ) {
            val th = thread()
            val status = ui.data.t3.detail(th.machine, th.id)?.thread?.status ?: th.status
            val mode = GatewayMappings.sendMode(status, strOrNull("mode") ?: "send")
            done(ui.data.t3.send(th.machine, th.id, str("text"), mode), when (mode) { "steer" -> "Steered ${th.title}"; "queue" -> "Queued for ${th.title}"; else -> "Sent to ${th.title}" })
        },

        OpSpec(
            "t3.respond", "Answer a T3 request",
            "Answer a thread's pending request. Approval: decision approve, approve_for_thread or deny. Questions: choice (one question) or answers {question id: option label, number, list or free text}.",
            params = listOf(
                THREAD, MACHINE,
                Param.string("decision", "For an approval: approve (once), approve_for_thread or deny", enum = listOf("approve", "approve_for_thread", "deny")),
                Param.string("choice", "For a single question: the option label or number, or a free-text answer", maxLength = 2000),
                Param.obj("answers", "For several questions: question id -> option label, option number, list of labels (multi) or free text"),
            ),
            risk = Risk.SEND, timeoutMs = 20_000,
            summary = { call -> "${call.strOrNull("thread").orEmpty().take(30)}: ${call.strOrNull("decision") ?: call.strOrNull("choice")?.take(50) ?: "answers"}" },
        ) {
            val th = thread()
            val request = detail(th).request ?: fail(Codes.UNAVAILABLE, "\"${th.title}\" has no pending request")
            if (request.approval != null || request.kind == "approval") {
                val decision = when (strOrNull("decision")) {
                    "approve" -> "accept"
                    "approve_for_thread" -> "acceptForSession"
                    "deny" -> "decline"
                    else -> fail(Codes.INVALID_ARGS, "This is an approval: pass decision approve, approve_for_thread or deny")
                }
                return@OpSpec done(ui.data.t3.respond(th.machine, th.id, request.id, decision), if (decision == "decline") "Denied" else "Approved")
            }
            if (has("decision")) fail(Codes.INVALID_ARGS, "This request asks questions, not for approval: pass choice or answers")
            done(ui.data.t3.respond(th.machine, th.id, request.id, answers = answers(request)), "Answer sent")
        },

        OpSpec(
            "t3.new_thread", "Start a T3 thread",
            "Start a new T3 Code thread in a project (name or id; t3.list_projects via glasses_run lists them) with a first prompt. It starts a real agent run, then opens on the glasses.",
            params = listOf(
                Param.string("project", "Project name or id", required = true, maxLength = 200),
                Param.string("prompt", "The first prompt", required = true, maxLength = 4000),
                Param.string("machine", "Machine id or label, when the project exists on several", maxLength = 100),
                Param.boolean("open", "Open the new thread on the glasses (default true)"),
            ),
            risk = Risk.SEND, timeoutMs = 30_000,
            summary = { call -> "${call.strOrNull("project")}: ${call.strOrNull("prompt").orEmpty().take(60)}" },
        ) {
            if (ui.data.t3.projects.isEmpty()) ui.data.t3.refreshProjects()
            val machine = strOrNull("machine")
            val projects = ui.data.t3.projects.filter { machine == null || it.machine.equals(machine, true) || it.machineLabel.equals(machine, true) }
            val wanted = str("project")
            val byId = projects.filter { it.id == wanted }
            val project = byId.singleOrNull() ?: run {
                val named = projects.filter { it.title.equals(wanted, true) }.ifEmpty { projects.filter { it.title.contains(wanted, true) } }
                when (named.size) {
                    1 -> named.single()
                    0 -> fail(Codes.FAILED, "No T3 project matching \"$wanted\"; run t3.list_projects")
                    else -> fail(Codes.AMBIGUOUS, "Several projects match \"$wanted\"; pass machine",
                        JsonArray(named.take(8).map { buildJsonObject { put("id", it.id); put("name", it.title); put("machine", it.machineLabel.ifBlank { it.machine }) } }))
                }
            }
            val result = ui.data.t3.newThread(project.machine, project.id, str("prompt"))
            if (result.ok && bool("open", true) && !ui.locked) result.id?.let { ui.open(OpenTarget("t3-thread", mapOf("machine" to project.machine, "id" to it))) }
            done(result, "Thread started in ${project.title}", result.id?.let { buildJsonObject { put("machine", project.machine); put("id", it) } })
        },

        OpSpec(
            "t3.usage", "Read T3 usage",
            "Coding-agent usage per provider and machine: each limit window's used percent, reset time and pace, plus available usage-reset credits.",
            risk = Risk.READ, timeoutMs = 15_000,
        ) {
            ui.data.t3.refreshUsage()
            ok(data = JsonArray(ui.data.t3.usage.take(MAX_ITEMS).map { u ->
                buildJsonObject {
                    put("provider", u.provider); put("machine", u.machine); put("instanceId", u.instanceId)
                    put("windows", JsonArray(u.windows.map { w ->
                        buildJsonObject { put("label", w.label); put("usedPercent", w.usedPercent); putText("resetsAt", w.resetsAt); put("pace", w.pace) }
                    }))
                    if (u.credits.isNotEmpty()) put("credits", JsonArray(u.credits.map { c ->
                        buildJsonObject { put("id", c.creditId); put("label", c.label); put("available", c.availableCount) }
                    }))
                }
            }))
        },

        OpSpec(
            "t3.list_machines", "List T3 machines",
            "The user's T3 machines (environments): id, label, online, OS, last seen, whether the assistant can write there, and pairing problems.",
            risk = Risk.READ, tool = false, timeoutMs = 15_000,
        ) {
            ui.data.t3.refreshEnvironments()
            ok(data = JsonArray(ui.data.t3.machines.take(MAX_ITEMS).map { e ->
                buildJsonObject {
                    put("machine", e.machine); put("label", e.label); put("online", e.online)
                    putText("os", e.os); putText("lastSeen", e.lastSeenLabel.ifBlank { e.lastSeen })
                    if (!e.canWrite) put("readOnly", true)
                    if (!e.paired || e.needsPairing) put("needsPairing", true)
                    if (e.needsRepair) put("needsRepair", true)
                    putText("cause", e.cause)
                }
            }))
        },

        OpSpec(
            "t3.list_projects", "List T3 projects",
            "Projects a new T3 thread can start in: id, name and machine.",
            risk = Risk.READ, tool = false, timeoutMs = 15_000,
        ) {
            ui.data.t3.refreshProjects()
            ok(data = JsonArray(ui.data.t3.projects.take(60).map {
                buildJsonObject { put("id", it.id); put("name", it.title); put("machine", it.machine); putText("machineLabel", it.machineLabel) }
            }))
        },

        OpSpec(
            "t3.use_reset", "Use a T3 usage reset",
            "Spend one usage-reset credit (ids from t3_usage) to reset a provider's limits now.",
            params = listOf(
                Param.string("machine", "Machine from t3_usage", required = true, maxLength = 100),
                Param.string("instance_id", "instanceId from t3_usage", required = true, maxLength = 200),
                Param.string("credit_id", "Credit id from t3_usage", required = true, maxLength = 200),
            ),
            risk = Risk.SPEND, tool = false, timeoutMs = 20_000,
            summary = { call -> "${call.strOrNull("credit_id")} on ${call.strOrNull("machine")}" },
        ) {
            if (ui.data.t3.usage.isEmpty()) ui.data.t3.refreshUsage()
            val usage = ui.data.t3.usage.find { it.machine == str("machine") && it.instanceId == str("instance_id") }
                ?: fail(Codes.FAILED, "No usage entry for that machine and instance; use t3_usage")
            val credit = usage.credits.find { it.creditId == str("credit_id") } ?: fail(Codes.FAILED, "No such credit; use t3_usage")
            if (credit.availableCount <= 0) fail(Codes.UNAVAILABLE, "No ${credit.label} credits left")
            done(ui.data.t3.resetUsage(usage.machine, usage.instanceId, credit.creditId), "Used ${credit.label}")
        },
    )
}
