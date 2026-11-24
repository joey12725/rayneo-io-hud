package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.screens.PhoneConfirm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Phone calls and contacts for the assistant (requirement 6 and the "call mom" example of requirement 2).
 *
 * The owner decided the assistant may see contact names, numbers and recents (docs/requirements.md CALL-09). Numbers are still
 * masked to the last four digits unless the assistant says the user asked for them (`reveal_numbers`), so they do not end up in
 * chat logs by default. `call.place` resolves "mom" on the phone (name, nickname, relationship, starred first) and
 * returns candidates instead of calling when it is not sure. Placing follows the agent confirm policy: from the
 * glasses conversation it dials at once; otherwise the glasses show the "Call Mom?  mobile" card and wait for Y.
 */
object CallOps : OpProvider {
    /** Stand-in for the confirm-policy question "would a CALL op need the card for this origin?". */
    private val PLACE_POLICY = OpSpec("call.place", "Place a phone call", "policy probe", risk = Risk.CALL) { ok() }

    private fun number(n: String, reveal: Boolean) = if (reveal) n else maskNumber(n)

    fun callJson(ui: Ui, c: PhoneCall, reveal: Boolean = false) = buildJsonObject {
        put("id", c.id)
        put("phase", c.phase.name.lowercase())
        put("direction", c.direction.name.lowercase())
        put("name", c.name)
        if (c.number.isNotBlank()) put("number", number(c.number, reveal))
        if (c.label.isNotBlank()) put("label", c.label)
        put("contact", c.contact in setOf(ContactStatus.Known, ContactStatus.Starred))
        if (c.spam) put("spam", true)
        c.connectedAt?.let { put("durationS", (ui.nowS() - it).toLong().coerceAtLeast(0)) }
        put("canAnswer", c.phase == CallPhase.Ringing && c.answer)
        put("canEnd", c.end)
    }

    fun routesJson(s: CallsSnapshot) = JsonArray(s.routes.map { r ->
        buildJsonObject { put("id", r.id); put("name", r.name); put("kind", routeKindLabel(r.kind).lowercase()); if (r.id == s.currentRoute) put("current", true) }
    })

    /** Same object the debug bridge's /state "calls" block shows (names and numbers redacted there). */
    fun stateJson(ui: Ui, reveal: Boolean = false): JsonObject {
        val calls = ui.data.calls
        val s = calls.snapshot
        return buildJsonObject {
            put("calls", JsonArray(s.calls.map { callJson(ui, it, reveal) }))
            put("muted", s.muted)
            put("route", s.routes.find { it.id == s.currentRoute }?.let { buildJsonObject { put("id", it.id); put("name", it.name); put("kind", routeKindLabel(it.kind).lowercase()) } } ?: JsonNull)
            put("routes", routesJson(s))
            put("access", buildJsonObject {
                put("audioControl", s.audioControl); put("place", calls.canPlace)
                put("contacts", calls.book.access.contacts); put("callLog", calls.book.access.callLog)
            })
            if (s.limitation.isNotBlank()) put("limitation", s.limitation)
        }
    }

    private fun calleeJson(c: Callee, reveal: Boolean) = buildJsonObject {
        put("name", c.name); c.contact?.let { put("contact_id", it.id) }
        if (c.label.isNotBlank()) put("label", shortLabel(c.label))
        put("number", number(c.number, reveal))
    }

    private fun AgentCall.ringing(): PhoneCall {
        val s = ui.data.calls.snapshot
        strOrNull("id")?.let { id -> return s.calls.find { it.id == id } ?: fail(Codes.FAILED, "No call with id $id; see call_state") }
        return s.ringing ?: fail(Codes.UNAVAILABLE, "No call is ringing")
    }

    private fun AgentCall.current(): PhoneCall {
        val s = ui.data.calls.snapshot
        strOrNull("id")?.let { id -> return s.calls.find { it.id == id } ?: fail(Codes.FAILED, "No call with id $id; see call_state") }
        return s.ongoing ?: s.calls.firstOrNull { it.phase != CallPhase.Ending } ?: fail(Codes.UNAVAILABLE, "No call in progress")
    }

    private val noCall: (Ui) -> String? = { ui -> if (ui.data.calls.snapshot.calls.none { it.phase != CallPhase.Ending }) "No call in progress" else null }

    /** An assistant-requested call that needs the user's Y: the same "Call Mom?" card the Phone app uses. */
    private suspend fun AgentCall.confirmOnGlasses(callee: Callee, route: String?): ActionResult? {
        class Answer(val result: ActionResult?)
        val decision = CompletableDeferred<ActionResult?>()
        val card = PhoneConfirm(ui, callee, route, fromJoy = true) { decision.complete(it) }
        val wasLocked = ui.locked
        ui.locked = false
        registry.onWake()
        ui.wake("agent-confirm", registry.confirmTimeoutMs)
        ui.push(card)
        if (ui.top !== card) { ui.locked = wasLocked; fail(Codes.UNAVAILABLE, "The glasses are showing a ringing call or another call card; try again after it") }
        val answer = withTimeoutOrNull(registry.confirmTimeoutMs) { Answer(decision.await()) }
        if (answer == null || answer.result == null) {
            card.expire()
            if (wasLocked) ui.locked = true
            ui.requestRender()
            if (answer == null) fail(Codes.TIMEOUT, "The user did not confirm the call to ${callee.name} within ${registry.confirmTimeoutMs / 1000} s, so nothing was dialled.")
            fail(Codes.DENIED, "The user cancelled the call to ${callee.name} on the glasses. Nothing was dialled.")
        }
        return answer.result
    }

    override fun ops() = listOf(
        OpSpec("contacts.search", "Search contacts",
            "Find people in the user's phone contacts by name, nickname or relationship (\"mom\"). Returns names, number labels and numbers (masked unless reveal_numbers).",
            params = listOf(
                Param.string("query", "Name, nickname or relationship", required = true, maxLength = 100),
                Param.integer("limit", "How many (default 8)", min = 1, max = 25),
                Param.boolean("reveal_numbers", "Full numbers; only when the user asked for a number"),
            ),
            risk = Risk.READ,
            available = { ui -> if (!ui.data.calls.book.access.contacts) "Contacts permission is off on the phone (READ_CONTACTS)" else null },
        ) {
            val reveal = bool("reveal_numbers")
            val matches = rankContacts(parseCallQuery(str("query")).name.ifBlank { str("query") }, ui.data.calls.book.contacts, int("limit", 8))
            ok(data = buildJsonObject {
                put("items", JsonArray(matches.map { m -> buildJsonObject {
                    put("contact_id", m.contact.id); put("name", m.contact.name); put("starred", m.contact.starred)
                    put("score", Math.round(m.score * 100) / 100.0)
                    put("numbers", JsonArray(m.contact.numbers.map { n -> buildJsonObject { put("label", shortLabel(n.label)); put("number", number(n.number, reveal)); if (n.primary) put("primary", true) } }))
                } }))
            })
        },

        OpSpec("call.place", "Place a phone call",
            "Call a contact (name, nickname like \"mom\", or contact_id) or a number from the user's phone. Ambiguous names return candidates instead of calling. The user may have to confirm on the glasses.",
            params = listOf(
                Param.string("contact", "Contact name, nickname/relationship, or contact_id from contacts_search", maxLength = 100),
                Param.string("number", "Phone number to dial (instead of contact)", maxLength = 40),
                Param.string("label", "Which number: mobile, home, work, main, other", enum = listOf("mobile", "home", "work", "main", "other")),
                Param.string("route", "Audio: bluetooth (default when connected), earpiece, speaker, wired, or a device name", maxLength = 60),
            ),
            risk = Risk.CALL, confirm = Confirm.NEVER, timeoutMs = 35_000,
            available = { ui -> if (ui.data.calls.snapshot.calls.any { it.phase != CallPhase.Ending }) "The user is already on a call" else null },
            summary = { call -> "Call ${call.strOrNull("contact") ?: call.strOrNull("number").orEmpty()}" },
        ) {
            val book = ui.data.calls.book
            val label = strOrNull("label")
            val wanted = strOrNull("contact")
            val resolution = when {
                has("number") -> resolveCallee(CallQuery("", number = str("number")), book.contacts)
                wanted != null -> book.contacts.find { it.id == wanted }?.let { c ->
                    chooseNumber(c, label)?.let { Resolution.Found(Callee(c.name, it.number, it.label, c)) }
                        ?: Resolution.Ambiguous(c.numbers.map { Callee(c.name, it.number, it.label, c) })
                } ?: resolveCallee(parseCallQuery(wanted).let { if (label != null) it.copy(label = label) else it }, book.contacts)
                else -> fail(Codes.INVALID_ARGS, "Give a contact or a number")
            }
            val callee = when (resolution) {
                is Resolution.Found -> resolution.callee
                is Resolution.Ambiguous -> fail(Codes.AMBIGUOUS, "Several matches for \"${wanted ?: ""}\"; ask the user which one and call again with contact_id and label",
                    JsonArray(resolution.candidates.map { calleeJson(it, false) }))
                is Resolution.NotFound -> fail(if (!book.access.contacts && wanted != null) Codes.PERMISSION else Codes.FAILED,
                    if (!book.access.contacts && wanted != null) "Contacts permission is off on the phone (READ_CONTACTS)" else resolution.message)
            }
            if (!ui.data.calls.canPlace) fail(Codes.PERMISSION, "Placing calls is off on the phone (CALL_PHONE): allow Place calls in the phone app")
            val route = strOrNull("route")
            val result = if (registry.needsConfirm(PLACE_POLICY, origin)) confirmOnGlasses(callee, route)
            else { ui.expectOutgoing(); ui.data.calls.place(callee.number, route).also { if (!it.ok) ui.cancelExpectOutgoing() } }
            if (result == null || !result.ok) fail(Codes.FAILED, result?.message ?: "The call did not start")
            val what = callee.name + if (callee.label.isNotBlank()) " (${shortLabel(callee.label)})" else ""
            ok("Calling $what", calleeJson(callee, false))
        },

        OpSpec("call.answer", "Answer the ringing call", "Answer the phone call that is ringing now (see call_state).",
            params = listOf(Param.string("id", "Call id (default: the ringing call)", maxLength = 80)),
            risk = Risk.ACT,
            available = { ui -> if (ui.data.calls.snapshot.ringing == null) "No call is ringing" else null },
        ) { val c = ringing(); done(ui.data.calls.answer(c.id), "Answered ${c.name}") },

        OpSpec("call.decline", "Decline the ringing call", "Decline (reject) the phone call that is ringing now.",
            params = listOf(Param.string("id", "Call id (default: the ringing call)", maxLength = 80)),
            risk = Risk.ACT, confirm = Confirm.NEVER,
            available = { ui -> if (ui.data.calls.snapshot.ringing == null) "No call is ringing" else null },
        ) { val c = ringing(); done(ui.data.calls.end(c.id), "Declined ${c.name}") },

        OpSpec("call.hangup", "Hang up the call", "End the current phone call (or a call by id).",
            params = listOf(Param.string("id", "Call id (default: the current call)", maxLength = 80)),
            risk = Risk.ACT, confirm = Confirm.NEVER, available = noCall,
        ) { val c = current(); done(ui.data.calls.end(c.id), "Hung up ${c.name}") },

        OpSpec("call.mute", "Mute or unmute the call", "Mute (on=true) or unmute the user's microphone on the current phone call.",
            params = listOf(Param.boolean("on", "true = muted", required = true)),
            risk = Risk.ACT, available = noCall,
        ) {
            if (!ui.data.calls.snapshot.audioControl) fail(Codes.UNAVAILABLE, "Mute needs companion call access on the phone (MANAGE_ONGOING_CALLS)")
            val on = bool("on")
            done(ui.data.calls.mute(on), if (on) "Muted" else "Unmuted")
        },

        OpSpec("call.routes", "List call audio routes", "Audio devices the current call can use (earbuds, handset, speaker, car, wired) and which is current.",
            risk = Risk.READ, available = noCall,
        ) { val s = ui.data.calls.snapshot; ok(data = buildJsonObject { put("routes", routesJson(s)); put("audioControl", s.audioControl) }) },

        OpSpec("call.route", "Switch call audio", "Move the current call's audio: bluetooth, earpiece/handset, speaker, wired, a device name or a route id from call_routes.",
            params = listOf(Param.string("endpoint", "Route kind, device name or id", required = true, maxLength = 80)),
            risk = Risk.ACT, available = noCall,
        ) {
            val s = ui.data.calls.snapshot
            if (!s.audioControl) fail(Codes.UNAVAILABLE, "Audio routing needs companion call access on the phone (MANAGE_ONGOING_CALLS)")
            val target = pickRoute(s.routes, str("endpoint"))
                ?: fail(Codes.FAILED, "No audio route \"${str("endpoint")}\" right now", routesJson(s))
            done(ui.data.calls.route(target.id), "Audio: ${target.name}")
        },

        OpSpec("call.recents", "Read recent calls", "The user's recent calls from the phone's call log, newest first: name, kind (missed, in, out...), when, duration.",
            params = listOf(
                Param.integer("limit", "How many (default 10)", min = 1, max = 25),
                Param.boolean("missed_only", "Only missed calls"),
                Param.boolean("reveal_numbers", "Full numbers; only when the user asked for a number"),
            ),
            risk = Risk.READ,
            available = { ui -> if (!ui.data.calls.book.access.callLog) "Call log permission is off on the phone (READ_CALL_LOG)" else null },
        ) {
            val reveal = bool("reveal_numbers")
            val rows = ui.data.calls.book.recents.filter { !bool("missed_only") || it.kind == RecentKind.Missed }.take(int("limit", 10))
            ok(data = buildJsonObject { put("items", JsonArray(rows.map { r -> buildJsonObject {
                put("name", r.name.ifBlank { "Unknown" }); put("known", r.known); put("kind", r.kind.name.lowercase())
                if (r.number.isNotBlank()) put("number", number(r.number, reveal))
                if (r.label.isNotBlank()) put("label", shortLabel(r.label))
                put("at", java.time.Instant.ofEpochMilli(r.atMs).toString()); put("durationS", r.durationS)
                if (r.count > 1) put("count", r.count)
                if (r.screened) put("screened", true)
            } })) })
        },

        OpSpec("call.state", "Read phone call state", "Current phone calls (ringing, dialing, active, held), mute, audio route and which call permissions the phone has.",
            params = listOf(Param.boolean("reveal_numbers", "Full numbers; only when the user asked for a number")),
            risk = Risk.READ,
        ) { ok(data = stateJson(ui, bool("reveal_numbers"))) },
    )
}
