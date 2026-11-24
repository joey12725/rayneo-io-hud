package io.lampyr.glasses.calls

import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.data.NoticeInput
import io.lampyr.glasses.data.NotificationRules
import io.lampyr.glasses.data.Notification
import io.lampyr.glasses.data.OpenTarget

enum class CallPhase { Screening, Ringing, Dialing, Active, Holding, Ending }
enum class RouteKind { Bluetooth, Earpiece, Speaker, Wired, Other }
data class AudioRoute(val id: String, val name: String, val kind: RouteKind)
data class PhoneCall(
    val id: String,
    val phase: CallPhase,
    val name: String = "Unknown caller",
    val number: String = "",
    val via: String = "",
    val connectedAt: Double? = null,
    val answer: Boolean = true,
    val end: Boolean = true,
    val rejected: Boolean = false,
    /** Outgoing calls (placed from the glasses, the assistant or the phone) vs incoming. */
    val direction: CallDirection = CallDirection.Unknown,
    /** Phone-side contact match for the call-alert rule (see [callAlert]). */
    val contact: ContactStatus = ContactStatus.Unavailable,
    /** The matched contact's number label ("mobile", "work"); blank when unknown. */
    val label: String = "",
    /** Carrier CNAM says spam/scam (never set for a saved contact). */
    val spam: Boolean = false,
    /** The network's caller name (CNAM / business caller ID) when shown; only the business rule reads it. */
    val callerId: String = "",
    /** Answered calls from this number before this one, and whether the user has called it (call log, on the phone). */
    val priorAnsweredCalls: Int = 0,
    val ownerCalled: Boolean = false,
    /** Private / withheld / unknown presentation. */
    val privateNumber: Boolean = false,
    /** STIR/SHAKEN verification failed. */
    val verificationFailed: Boolean = false,
    /** The platform marked a business caller. */
    val business: Boolean = false,
)
enum class CallDirection { Incoming, Outgoing, Unknown }
/**
 * Pending: the contact lookup is still running. Known/Starred: a saved contact. Unknown: not a contact (or withheld).
 * Unavailable: the phone cannot tell (no contacts permission, no number in basic mode).
 */
enum class ContactStatus { Pending, Known, Starred, Unknown, Unavailable }
/** Takeover = popup (wakes the glasses), Quiet = stream (Home row, a missed-call notice), Silent = suppress (Recents only). */
enum class CallAlert { Takeover, Quiet, Silent, Wait }

/** The routing input for a ringing call: what the phone knows, never the number. */
fun callNotice(call: PhoneCall) = NoticeInput(kind = "call", title = call.callerId.ifBlank { call.name }, category = "ringing",
    contactKnown = when (call.contact) { ContactStatus.Known, ContactStatus.Starred -> true; ContactStatus.Unknown -> false; else -> null },
    callerVerified = if (call.verificationFailed) "failed" else "none", priorAnsweredCalls = call.priorAnsweredCalls, ownerCalledNumber = call.ownerCalled,
    privateNumber = call.privateNumber, spam = call.spam, business = call.business)

/**
 * The call rule (deterministic, on the phone, never the gateway classifier; the table is NotificationRules' "call" kind, shared with the
 * gateway): a number he has had real calls with, a saved contact or a local business takes over and wakes the glasses;
 * a private number and other unknown numbers ring quietly (Home row, a missed-call notice); suspected spam and failed
 * verification stay silent (Recents only). When the phone cannot tell (no contacts permission) and knows nothing against
 * the caller, the old CALL-01 takeover applies.
 */
fun callAlert(call: PhoneCall): CallAlert {
    if (call.contact == ContactStatus.Pending) return CallAlert.Wait
    if (call.contact == ContactStatus.Unavailable && !call.spam && !call.privateNumber && !call.verificationFailed) return CallAlert.Takeover
    return when (NotificationRules.decide(callNotice(call))?.route) {
        "popup" -> CallAlert.Takeover
        "stream" -> CallAlert.Quiet
        else -> CallAlert.Silent
    }
}

/** Call-log history of a number before the current call: answered incoming calls and calls the user placed. */
data class CallerHistory(val priorAnswered: Int = 0, val ownerCalled: Boolean = false)
/** Same number: the last 10 digits match (formatting and country prefixes differ between the log and Telecom). */
fun sameNumber(a: String, b: String): Boolean {
    val x = a.filter { it.isDigit() }.takeLast(10); val y = b.filter { it.isDigit() }.takeLast(10)
    return x.length >= 7 && x == y
}
/** [rows]: (number, kind, durationS) from the call log, any order. */
fun callerHistory(rows: List<Triple<String, RecentKind, Long>>, number: String): CallerHistory {
    if (number.isBlank()) return CallerHistory()
    val mine = rows.filter { sameNumber(it.first, number) }
    return CallerHistory(mine.count { it.second == RecentKind.Incoming && it.third > 0 }, mine.any { it.second == RecentKind.Outgoing })
}

/** CNAM text carriers use for suspected spam ("Scam Likely", "Spam Risk"). */
fun spamCallerLabel(display: String?): Boolean =
    display != null && Regex("\\b(spam|scam|fraud|telemarket\\w*|robocall)\\b", RegexOption.IGNORE_CASE).containsMatchIn(display)

/** Pick the audio route a request names: an id, a kind ("bluetooth", "earpiece"/"handset", "speaker", "wired") or a name part. */
fun pickRoute(routes: List<AudioRoute>, want: String?): AudioRoute? {
    val w = want?.trim()?.lowercase().orEmpty()
    if (w.isEmpty()) return null
    routes.firstOrNull { it.id.lowercase() == w }?.let { return it }
    val kind = when (w) {
        "bluetooth", "bt", "earbuds", "buds", "headset", "headphones", "car" -> RouteKind.Bluetooth
        "earpiece", "handset", "phone", "phone earpiece" -> RouteKind.Earpiece
        "speaker", "speakerphone", "loudspeaker" -> RouteKind.Speaker
        "wired", "wired headset" -> RouteKind.Wired
        else -> null
    }
    if (kind != null) routes.firstOrNull { it.kind == kind }?.let { return it }
    return routes.firstOrNull { it.name.lowercase() == w } ?: routes.firstOrNull { it.name.lowercase().contains(w) }
}

/** Human kind for the route picker. Car head units and earbuds both arrive as Bluetooth with their own name. */
fun routeKindLabel(kind: RouteKind) = when (kind) {
    RouteKind.Bluetooth -> "Bluetooth"
    RouteKind.Earpiece -> "Handset"
    RouteKind.Speaker -> "Speaker"
    RouteKind.Wired -> "Wired"
    RouteKind.Other -> "Audio"
}

/** Common emergency numbers: never dialled from the glasses or by the assistant (the phone's own dialer handles them). */
val EMERGENCY_NUMBERS = setOf("911", "112", "999", "000", "110", "118", "119", "100", "101", "102", "108", "933")

/**
 * The digits to dial (first) or why the string must not be dialled (second): blank, emergency numbers and MMI/USSD
 * codes (any * or #) are refused. Formatting characters are stripped; a leading + is kept.
 */
fun dialable(number: String): Pair<String?, String?> {
    val raw = number.trim()
    if (raw.isEmpty()) return null to "No number to call"
    if (raw.any { it == '*' || it == '#' }) return null to "Codes with * or # are not dialled from the glasses"
    if (raw.any { !it.isDigit() && it !in " +-(). " }) return null to "Not a phone number"
    val digits = (if (raw.startsWith("+")) "+" else "") + raw.filter { it.isDigit() }
    if (digits.trimStart('+').length < 3) return null to "Not a phone number"
    if (digits.trimStart('+') in EMERGENCY_NUMBERS) return null to "Emergency numbers: use the phone"
    return digits to null
}

/** "***0142": numbers shown to the assistant are masked except the last four unless the user asked for them. */
fun maskNumber(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length <= 4) digits else "***" + digits.takeLast(4)
}
data class CallsSnapshot(
    val calls: List<PhoneCall> = emptyList(),
    val muted: Boolean = false,
    val routes: List<AudioRoute> = emptyList(),
    val currentRoute: String? = null,
    val audioControl: Boolean = false,
    val limitation: String = "Calls optional: enable in phone setup",
) {
    val ringing get() = calls.firstOrNull { it.phase == CallPhase.Ringing }
    val ongoing get() = calls.firstOrNull { it.phase == CallPhase.Active }
        ?: calls.firstOrNull { it.phase == CallPhase.Dialing }
        ?: calls.firstOrNull { it.phase == CallPhase.Holding }
    val routeName get() = routes.find { it.id == currentRoute }?.name ?: "Phone audio"
}

interface Calls {
    val snapshot: CallsSnapshot
    val missed: List<Notification>
    fun listen(listener: () -> Unit): () -> Unit
    suspend fun answer(id: String): ActionResult
    suspend fun end(id: String): ActionResult
    suspend fun mute(value: Boolean): ActionResult
    suspend fun resume(id: String): ActionResult = ActionResult.failure("Resume on phone")
    suspend fun route(id: String): ActionResult
    fun dismissMissed(id: String)
    fun close() {}
    /** Contacts and call log for the Phone app (cached; [PhoneBook.refresh] reloads). */
    val book: PhoneBook get() = PhoneBook.NONE
    /** CALL_PHONE is granted (placing calls from the glasses and the assistant). */
    val canPlace: Boolean get() = false
    /**
     * Place a call through TelecomManager. [route] picks the audio route once the call exists (see [pickRoute]);
     * null means "the current Bluetooth earbuds if connected". The result only says the request was accepted: the call
     * itself appears through [snapshot] (Dialing, then Active).
     */
    suspend fun place(number: String, route: String? = null): ActionResult = ActionResult.failure("Calling unavailable")
}

/** Main-thread snapshots. Android observations, not button presses, confirm state changes. */
open class CallState(private val nowMs: () -> Long = { System.currentTimeMillis() }) : Calls {
    final override var snapshot = CallsSnapshot(); private set
    final override var missed = emptyList<Notification>(); private set
    private val listeners = linkedSetOf<() -> Unit>()
    private val seenConnected = mutableSetOf<String>()
    private val declined = mutableSetOf<String>()
    private val ringing = linkedMapOf<String, PhoneCall>()
    override fun listen(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    protected fun changed() { listeners.toList().forEach { it() } }
    fun publish(next: CallsSnapshot) {
        next.calls.forEach { c ->
            if (c.rejected) declined += c.id
            if (c.phase == CallPhase.Ringing) ringing[c.id] = c
            if (c.connectedAt != null || c.phase == CallPhase.Active || c.phase == CallPhase.Holding) seenConnected += c.id
        }
        val ended = ringing.keys.filter { id -> next.calls.none { it.id == id && it.phase != CallPhase.Ending } }
        ended.forEach { id ->
            val c = ringing.remove(id)!!
            // Suspected spam and failed verification never become missed-call notices: they stay in Recents only.
            val alert = callAlert(c)
            if (id !in seenConnected && id !in declined && alert != CallAlert.Silent) missed = (listOf(Notification(
                "call-missed:$id", "Phone", "phone", "Missed call", c.name, if (alert == CallAlert.Quiet) .5 else .8, nowMs(),
                OpenTarget("call-missed", mapOf("name" to c.name, "number" to c.number)),
            )) + missed).take(30)
            seenConnected -= id; declined -= id
        }
        snapshot = next
        seenConnected.retainAll(next.calls.map { it.id }.toSet())
        declined.retainAll(next.calls.map { it.id }.toSet())
        changed()
    }
    fun restoreMissed(items: List<Notification>) { missed = items.take(30); changed() }
    protected fun markDeclined(id: String) { declined += id }
    override fun dismissMissed(id: String) { missed = missed.filterNot { it.id == id }; changed() }
    override suspend fun answer(id: String) = ActionResult.failure("Answer on phone")
    override suspend fun end(id: String) = ActionResult.failure("End on phone")
    override suspend fun mute(value: Boolean) = ActionResult.failure("Mute on phone / companion access required")
    override suspend fun route(id: String) = ActionResult.failure("Choose audio on phone / companion access required")
}

fun callerName(contact: String?, number: String?, notification: String? = null): String =
    contact?.trim()?.takeIf { it.isNotEmpty() }
        ?: number?.trim()?.takeIf { it.isNotEmpty() }
        ?: notification?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Unknown caller"

fun callDuration(call: PhoneCall, now: Double): String {
    val seconds = call.connectedAt?.let { (now - it).toLong().coerceAtLeast(0) } ?: 0
    val hours = seconds / 3600
    val clock = "${seconds / 60 % 60}:${(seconds % 60).toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}" else clock
}

class FakeCalls(private val now: () -> Double = { 0.0 }, nowMs: () -> Long = { System.currentTimeMillis() }) : CallState(nowMs) {
    private var sequence = 0
    override val book = FakePhoneBook(nowMs)
    /** Tests switch CALL_PHONE off. */
    var placeAllowed = true
    override val canPlace get() = placeAllowed
    /** Digits of every call this fake placed (tests assert nothing else was dialled). */
    val placed = mutableListOf<String>()
    private val demoRoutes = listOf(AudioRoute("bt", "Pixel Buds Pro", RouteKind.Bluetooth), AudioRoute("ear", "Phone earpiece", RouteKind.Earpiece), AudioRoute("speaker", "Speaker", RouteKind.Speaker))
    fun incoming(name: String = "Sam Rivera", number: String = "(312) 555-0142", contact: ContactStatus = ContactStatus.Known) {
        publish(snapshot.copy(calls = snapshot.calls + PhoneCall("demo-${++sequence}", CallPhase.Ringing, name, number, "Personal SIM",
                direction = CallDirection.Incoming, contact = contact),
            routes = demoRoutes,
            currentRoute = "bt", audioControl = true, limitation = ""))
    }
    override suspend fun place(number: String, route: String?): ActionResult {
        val (digits, reason) = dialable(number)
        if (digits == null) return ActionResult.failure(reason ?: "Not a phone number")
        if (!placeAllowed) return ActionResult.failure("Allow Place calls in phone setup")
        if (snapshot.calls.any { it.phase != CallPhase.Ending }) return ActionResult.failure("Already on a call")
        placed += digits
        val known = findByNumber(book.contacts, digits)
        val chosen = pickRoute(demoRoutes, route ?: "bluetooth") ?: demoRoutes.first()
        publish(snapshot.copy(calls = snapshot.calls + PhoneCall("out-${++sequence}", CallPhase.Dialing, known?.first?.name ?: number.trim(),
                known?.second?.number ?: number.trim(), "Personal SIM", direction = CallDirection.Outgoing,
                contact = if (known != null) ContactStatus.Known else ContactStatus.Unknown, label = known?.second?.label?.let(::shortLabel).orEmpty()),
            routes = demoRoutes, currentRoute = chosen.id, audioControl = true, limitation = ""))
        return ActionResult()
    }
    /** The far end picks up a placed call. */
    fun connect(id: String) {
        publish(snapshot.copy(calls = snapshot.calls.map { if (it.id == id) it.copy(phase = CallPhase.Active, connectedAt = now()) else it }))
    }
    override suspend fun answer(id: String): ActionResult {
        if (snapshot.calls.none { it.id == id && it.phase == CallPhase.Ringing }) return ActionResult.failure("Call ended")
        publish(snapshot.copy(calls = snapshot.calls.map { if (it.id == id) it.copy(phase = CallPhase.Active, connectedAt = now()) else if (it.phase == CallPhase.Active) it.copy(phase = CallPhase.Holding) else it }))
        return ActionResult()
    }
    override suspend fun end(id: String): ActionResult {
        if (snapshot.calls.none { it.id == id }) return ActionResult.failure("Call ended")
        markDeclined(id)
        publish(snapshot.copy(calls = snapshot.calls.filterNot { it.id == id }))
        return ActionResult()
    }
    fun remoteEnd(id: String) { publish(snapshot.copy(calls = snapshot.calls.filterNot { it.id == id })) }
    override suspend fun mute(value: Boolean): ActionResult { publish(snapshot.copy(muted = value)); return ActionResult() }
    override suspend fun resume(id: String): ActionResult {
        if (snapshot.calls.none { it.id == id && it.phase == CallPhase.Holding }) return ActionResult.failure("Call ended")
        publish(snapshot.copy(calls = snapshot.calls.map { if (it.id == id) it.copy(phase = CallPhase.Active) else if (it.phase == CallPhase.Active) it.copy(phase = CallPhase.Holding) else it }))
        return ActionResult()
    }
    override suspend fun route(id: String): ActionResult {
        if (snapshot.routes.none { it.id == id }) return ActionResult.failure("Device disconnected")
        publish(snapshot.copy(currentRoute = id)); return ActionResult()
    }
}
