@file:Suppress("DEPRECATION")
package io.lampyr.glasses.calls

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.*
import io.lampyr.glasses.data.ActionResult
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.lampyr.glasses.data.gatewayJson
import java.util.UUID

internal fun resolveCaller(context: Context, raw: String, display: String? = null): Pair<String, String> =
    lookupCaller(context, raw, display).let { it.name to it.number }

/**
 * Caller identity plus what the call-alert rule needs: the contact status, the network's caller name and the call-log
 * history of the number. [blank] is the status of a call with no number.
 */
internal data class CallerLookup(val name: String, val number: String, val contact: ContactStatus, val label: String = "",
    val callerId: String = "", val history: CallerHistory = CallerHistory())
/** Answered calls from [raw] and calls the user placed to it, from the newest 500 call-log rows (READ_CALL_LOG; the ringing call is not logged yet). */
internal fun lookupHistory(context: Context, raw: String): CallerHistory {
    if (raw.isBlank() || context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return CallerHistory()
    return runCatching {
        val uri = android.provider.CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(android.provider.CallLog.Calls.LIMIT_PARAM_KEY, "500").build()
        val rows = mutableListOf<Triple<String, RecentKind, Long>>()
        context.contentResolver.query(uri, arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DURATION),
            null, null, android.provider.CallLog.Calls.DATE + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val kind = when (c.getInt(1)) {
                    android.provider.CallLog.Calls.INCOMING_TYPE, android.provider.CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> RecentKind.Incoming
                    android.provider.CallLog.Calls.OUTGOING_TYPE -> RecentKind.Outgoing
                    else -> RecentKind.Other
                }
                rows += Triple(c.getString(0).orEmpty(), kind, c.getLong(2))
            }
        }
        callerHistory(rows, raw)
    }.getOrDefault(CallerHistory())
}
internal fun lookupCaller(context: Context, raw: String, display: String? = null, blank: ContactStatus = ContactStatus.Unavailable): CallerLookup {
    val number = if (raw.isBlank()) "" else PhoneNumberUtils.formatNumber(raw, java.util.Locale.getDefault().country) ?: raw
    val callerId = display?.trim().orEmpty()
    if (raw.isBlank()) return CallerLookup(callerName(null, null, display), "", blank, callerId = callerId)
    val history = lookupHistory(context, raw)
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
        return CallerLookup(callerName(null, number, display), number, ContactStatus.Unavailable, callerId = callerId, history = history)
    val row = runCatching {
        context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(raw)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED, ContactsContract.PhoneLookup.TYPE, ContactsContract.PhoneLookup.LABEL),
            null, null, null)?.use {
            if (!it.moveToFirst()) null
            else Triple(it.getString(0), it.getInt(1) == 1,
                ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, it.getInt(2), it.getString(3)).toString())
        }
    }
    val found = row.getOrNull()
    val status = when { row.isFailure -> ContactStatus.Unavailable; found == null -> ContactStatus.Unknown; found.second -> ContactStatus.Starred; else -> ContactStatus.Known }
    return CallerLookup(callerName(found?.first, number, display), number, status, found?.third?.let(::shortLabel).orEmpty(), callerId, history)
}

/** READ_PHONE_STATE is aggregate only: waiting calls cannot safely be individually controlled. */
class AndroidCalls(private val context: Context, private val scope: CoroutineScope) : CallState() {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private var id = UUID.randomUUID().toString()
    private var state = TelephonyManager.CALL_STATE_IDLE
    private var connectedAt: Double? = null
    private var identity = "Unknown caller" to ""
    private var contactStatus = ContactStatus.Unavailable
    private var numberLabel = ""
    private var caller: CallerLookup? = null
    private var direction = CallDirection.Unknown
    private var identityVersion = 0
    private val phoneBook = lazy { AndroidPhoneBook(context, scope) }
    override val book: PhoneBook by phoneBook
    override val canPlace get() = granted(Manifest.permission.CALL_PHONE)
    private var knownNumber = ""
    private var waiting = false
    private var registered = false
    private var closed = false
    private var receiving = false
    private val companionIds = mutableMapOf<String, String>()
    private var modern: TelephonyCallback? = null
    private val legacy = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) { observed(state, phoneNumber) }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val raw = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!raw.isNullOrBlank() && intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING)
                observed(TelephonyManager.CALL_STATE_RINGING, raw)
        }
    }
    private val serviceUpdate: () -> Unit = { refresh() }
    private val unlock = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshPermissions() }
    }
    private val preferences = context.getSharedPreferences("phone-calls", Context.MODE_PRIVATE)
    private var savedMissed = ""
    private val stopPersistence: () -> Unit
    init {
        restoreMissed(runCatching { gatewayJson.decodeFromString<List<io.lampyr.glasses.data.Notification>>(preferences.getString("missed", "[]")!!) }.getOrDefault(emptyList()))
        stopPersistence = listen {
            val encoded = gatewayJson.encodeToString(missed)
            if (savedMissed != encoded) { savedMissed = encoded; preferences.edit().putString("missed", encoded).apply() }
        }
        CompanionCallsService.updates += serviceUpdate
        context.registerReceiver(unlock, IntentFilter(Intent.ACTION_USER_PRESENT))
        refreshPermissions()
    }
    fun refreshPermissions() {
        if (registered && !granted(Manifest.permission.READ_PHONE_STATE)) {
            runCatching { if (Build.VERSION.SDK_INT >= 31) modern?.let { telephony.unregisterTelephonyCallback(it) } else telephony.listen(legacy, PhoneStateListener.LISTEN_NONE) }
            registered = false; state = TelephonyManager.CALL_STATE_IDLE
        }
        if (!registered && granted(Manifest.permission.READ_PHONE_STATE)) runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { observed(state, null) }
                }
                modern = callback; telephony.registerTelephonyCallback(context.mainExecutor, callback)
            } else telephony.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE)
            registered = true
        }
        refresh()
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun resolve(number: String, display: String? = null) {
        if (number.isBlank() && knownNumber.isNotBlank()) return
        if (number.isNotBlank()) knownNumber = number
        val generation = id
        val version = ++identityVersion
        if (number.isNotBlank()) contactStatus = ContactStatus.Pending
        scope.launch {
            val found = withContext(Dispatchers.IO) { lookupCaller(context, number, display) }
            if (generation == id && version == identityVersion && state != TelephonyManager.CALL_STATE_IDLE) {
                identity = found.name to found.number; contactStatus = found.contact; numberLabel = found.label; caller = found; refresh()
            }
        }
    }
    private fun observed(next: Int, number: String?) {
        if (state == TelephonyManager.CALL_STATE_IDLE && next != state) {
            id = UUID.randomUUID().toString(); identity = "Unknown caller" to ""; knownNumber = ""; connectedAt = null; waiting = false
            numberLabel = ""; caller = null
            direction = if (next == TelephonyManager.CALL_STATE_RINGING) CallDirection.Incoming else CallDirection.Outgoing
            // The number arrives a moment later through the phone-state broadcast when READ_CALL_LOG is granted:
            // hold the takeover decision briefly, then fall back to the old always-take-over behaviour.
            contactStatus = if (next == TelephonyManager.CALL_STATE_RINGING && receiving && number.isNullOrBlank()) ContactStatus.Pending else ContactStatus.Unavailable
            if (contactStatus == ContactStatus.Pending) {
                val generation = id
                scope.launch {
                    delay(1500)
                    if (generation == id && contactStatus == ContactStatus.Pending && knownNumber.isBlank()) { contactStatus = ContactStatus.Unavailable; refresh() }
                }
            }
        }
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && next == TelephonyManager.CALL_STATE_RINGING) waiting = true
        if (next == TelephonyManager.CALL_STATE_OFFHOOK && state == TelephonyManager.CALL_STATE_RINGING) waiting = false
        if (next == TelephonyManager.CALL_STATE_OFFHOOK && connectedAt == null) connectedAt = android.os.SystemClock.elapsedRealtime() / 1000.0
        state = next
        if (!number.isNullOrBlank()) resolve(number)
        refresh()
    }
    fun notificationCaller(name: String) {
        if (state == TelephonyManager.CALL_STATE_RINGING && identity.first == "Unknown caller") resolve("", name)
    }
    private fun refresh() {
        if (!receiving && granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.READ_CALL_LOG)) {
            receiving = runCatching {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
                true
            }.getOrDefault(false)
        }
        val service = CompanionCallsService.instance
        if (service != null) {
            val observed = service.snapshot()
            // onCreate precedes onCallAdded. Keep the ringing card until real calls arrive.
            val basic = snapshot.calls.find { it.id == id }
            if (observed.calls.isEmpty() && basic != null && companionIds.isEmpty()) return
            if (basic != null && companionIds.isEmpty() && observed.calls.isNotEmpty()) {
                val matching = observed.calls.find { it.phase == basic.phase } ?: observed.calls.first()
                companionIds[matching.id] = basic.id
            }
            val next = observed.copy(calls = observed.calls.map { it.copy(id = companionIds[it.id] ?: it.id) })
            // A basic-to-companion handover retains the card identity and its hidden state.
            publish(next)
            if (observed.calls.isEmpty()) { state = TelephonyManager.CALL_STATE_IDLE; connectedAt = null }
            companionIds.keys.retainAll(observed.calls.map { it.id }.toSet())
            return
        }
        val control = granted(Manifest.permission.ANSWER_PHONE_CALLS) && !waiting
        val calls = if (state == TelephonyManager.CALL_STATE_IDLE) emptyList() else listOf(PhoneCall(id,
            if (state == TelephonyManager.CALL_STATE_RINGING) CallPhase.Ringing else CallPhase.Active,
            identity.first, identity.second, if (waiting) "Call waiting · use phone" else "", connectedAt,
            answer = control && state == TelephonyManager.CALL_STATE_RINGING, end = control,
            direction = direction, contact = contactStatus, label = numberLabel, callerId = caller?.callerId.orEmpty(),
            priorAnsweredCalls = caller?.history?.priorAnswered ?: 0, ownerCalled = caller?.history?.ownerCalled == true,
            spam = contactStatus != ContactStatus.Known && contactStatus != ContactStatus.Starred && spamCallerLabel(caller?.callerId)))
        publish(CallsSnapshot(calls, limitation = if (!registered) "Calls disabled: allow phone state in setup" else if (waiting) "Call waiting: controls on phone" else "Mute / audio on phone"))
    }
    private inline fun safely(action: () -> ActionResult): ActionResult = try { action() } catch (_: SecurityException) { refreshPermissions(); ActionResult.failure("Call permission unavailable · use phone") } catch (_: RuntimeException) { ActionResult.failure("Call control unavailable · use phone") }
    override suspend fun answer(id: String): ActionResult = safely {
        val c = snapshot.calls.find { it.id == id && it.phase == CallPhase.Ringing && it.answer } ?: return@safely ActionResult.failure("Answer on phone")
        val s = CompanionCallsService.instance
        val call = s?.call(platformId(c.id))
        if (call != null) call.answer(VideoProfile.STATE_AUDIO_ONLY)
        else if (s == null || (!s.hasDeliveredCalls && companionIds.isEmpty() &&
                c.id == this.id && state == TelephonyManager.CALL_STATE_RINGING)) telecom.acceptRingingCall()
        else return@safely ActionResult.failure("Call ended")
        ActionResult()
    }
    override suspend fun end(id: String): ActionResult = safely {
        val c = snapshot.calls.find { it.id == id && it.end } ?: return@safely ActionResult.failure("End on phone")
        val s = CompanionCallsService.instance
        val call = s?.call(platformId(id))
        if (call != null) {
            if (c.phase == CallPhase.Ringing) call.reject(false, null) else call.disconnect()
        } else if (s == null || c.phase == CallPhase.Ringing) {
            if (!telecom.endCall()) return@safely ActionResult.failure("End call refused · use phone")
        } else return@safely ActionResult.failure("Call ended")
        if (c.phase == CallPhase.Ringing) markDeclined(id)
        ActionResult()
    }
    override suspend fun mute(value: Boolean): ActionResult = safely {
        val s = CompanionCallsService.instance ?: return@safely ActionResult.failure("Mute on phone · companion access required")
        s.setMuted(value); ActionResult()
    }
    override suspend fun resume(id: String): ActionResult = safely {
        val call = CompanionCallsService.instance?.call(platformId(id)) ?: return@safely ActionResult.failure("Resume on phone")
        call.unhold(); ActionResult()
    }
    override suspend fun route(id: String): ActionResult = try {
        withTimeout(8000) { CompanionCallsService.instance?.changeRoute(id) ?: ActionResult.failure("Audio on phone · companion access required") }
    } catch (_: TimeoutCancellationException) { ActionResult.failure("Audio switch timed out · use phone") }
      catch (e: CancellationException) { throw e }
      catch (_: RuntimeException) { ActionResult.failure("Audio switch failed · use phone") }
    /**
     * TelecomManager.placeCall needs only CALL_PHONE (no dialer role). It is a binder call, so it works from the
     * background service. Emergency numbers and MMI codes are refused here; Google Phone shows its own call UI on the
     * phone and the companion service then reports the Dialing call. The audio route is applied by the companion
     * service once Telecom lists endpoints for the new call (endpoints only exist while a call exists).
     */
    override suspend fun place(number: String, route: String?): ActionResult = safely {
        val (digits, reason) = dialable(number)
        if (digits == null) return@safely ActionResult.failure(reason ?: "Not a phone number")
        if (runCatching { telephony.isEmergencyNumber(digits) }.getOrDefault(false)) return@safely ActionResult.failure("Emergency numbers: use the phone")
        if (!granted(Manifest.permission.CALL_PHONE)) return@safely ActionResult.failure("Allow Place calls in phone setup")
        if (snapshot.calls.any { it.phase != CallPhase.Ending }) return@safely ActionResult.failure("Already on a call")
        CompanionCallsService.requestRoute(route)
        telecom.placeCall(Uri.fromParts("tel", digits, null), android.os.Bundle())
        io.lampyr.glasses.debug.Trace.log("Calls", "Place call requested route=${route ?: "default"}")
        ActionResult()
    }
    private fun platformId(id: String) = companionIds.entries.find { it.value == id }?.key ?: id
    override fun close() {
        if (closed) return
        closed = true
        if (phoneBook.isInitialized()) phoneBook.value.close()
        CompanionCallsService.updates -= serviceUpdate
        stopPersistence()
        if (registered) runCatching { if (Build.VERSION.SDK_INT >= 31) modern?.let { telephony.unregisterTelephonyCallback(it) } else telephony.listen(legacy, PhoneStateListener.LISTEN_NONE) }
        if (receiving) runCatching { context.unregisterReceiver(receiver) }
        context.unregisterReceiver(unlock)
    }
}
