@file:Suppress("DEPRECATION")
package io.lampyr.glasses.calls

import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.*
import io.lampyr.glasses.data.ActionResult
import kotlinx.coroutines.*
import java.util.UUID

/** A non-UI InCallService: the default phone app retains ringing and its phone UI. */
class CompanionCallsService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = linkedMapOf<Call, String>()
    var hasDeliveredCalls = false; private set
    private val identities = mutableMapOf<Call, CallerLookup>()
    private val connected = mutableMapOf<Call, Double>()
    private val identityKeys = mutableMapOf<Call, String>()
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private var audio: CallAudioState? = null
    private var endpoints = emptyList<CallEndpoint>()
    private var endpoint: CallEndpoint? = null
    private var muted = false
    override fun onCreate() {
        super.onCreate(); instance = this
        io.lampyr.glasses.service.ServiceAccess.start(this)
        updates.toList().forEach { it() }
    }
    override fun onCallAdded(call: Call) {
        hasDeliveredCalls = true
        ids[call] = UUID.randomUUID().toString()
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = update()
            override fun onDetailsChanged(call: Call, details: Call.Details) = update()
        }
        callbacks[call] = cb; call.registerCallback(cb)
        update()
    }
    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        ids.remove(call); identities.remove(call); connected.remove(call); identityKeys.remove(call)
        update()
    }
    override fun onCallAudioStateChanged(state: CallAudioState) { audio = state; muted = state.isMuted; update() }
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) { endpoints = availableEndpoints.toList(); update() }
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) { endpoint = callEndpoint; update() }
    override fun onMuteStateChanged(isMuted: Boolean) { muted = isMuted; update() }
    private fun update() {
        ids.keys.toList().forEach { call ->
            val d = call.details
            val raw = if (d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) d.handle?.schemeSpecificPart.orEmpty() else ""
            val key = "$raw:${d.callerDisplayName.orEmpty()}:${d.handlePresentation}:${d.callerDisplayNamePresentation}"
            if (identityKeys[call] != key) {
                identityKeys[call] = key
                identities.remove(call)
                scope.launch {
                    val display = d.callerDisplayName.takeIf { d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED }
                    // A withheld/restricted number is "not a contact" for the call rule.
                    val identity = withContext(Dispatchers.IO) { lookupCaller(this@CompanionCallsService, raw, display, ContactStatus.Unknown) }
                    if (call in ids && identityKeys[call] == key) { identities[call] = identity; updates.toList().forEach { it() } }
                }
            }
            if (d.connectTimeMillis > 0 && call !in connected)
                connected[call] = android.os.SystemClock.elapsedRealtime() / 1000.0 - ((System.currentTimeMillis() - d.connectTimeMillis).coerceAtLeast(0) / 1000.0)
        }
        applyPendingRoute()
        updates.toList().forEach { it() }
    }
    /** After placeCall: once the outgoing call and its endpoints exist, switch to the requested route (once). */
    private fun applyPendingRoute() {
        val want = pendingRoute ?: return
        if (System.currentTimeMillis() > pendingUntil) { pendingRoute = null; return }
        val outgoing = ids.keys.any { telecomPhase(it.state) == CallPhase.Dialing || outgoing(it) }
        if (!outgoing) return
        val s = snapshot()
        if (s.routes.isEmpty()) return
        pendingRoute = null
        val target = if (want == DEFAULT_ROUTE) s.routes.firstOrNull { it.kind == RouteKind.Bluetooth } else pickRoute(s.routes, want)
        if (target == null || target.id == s.currentRoute) return
        if (want == DEFAULT_ROUTE && s.routes.find { it.id == s.currentRoute }?.kind == RouteKind.Bluetooth) return
        scope.launch {
            val result = runCatching { changeRoute(target.id) }.getOrElse { ActionResult.failure(it.message ?: "failed") }
            io.lampyr.glasses.debug.Trace.log("Calls", "Placed-call route kind=${target.kind} ok=${result.ok}")
        }
    }
    private fun outgoing(call: Call) = Build.VERSION.SDK_INT >= 29 && call.details.callDirection == Call.Details.DIRECTION_OUTGOING
    fun snapshot(): CallsSnapshot {
        val rows = ids.map { (call, id) ->
            val d = call.details
            val raw = if (d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) d.handle?.schemeSpecificPart.orEmpty() else ""
            val identity = identities[call] ?: CallerLookup(callerName(null, raw), raw, if (raw.isBlank()) ContactStatus.Unknown else ContactStatus.Pending)
            val via = runCatching { getSystemService(TelecomManager::class.java).getPhoneAccount(d.accountHandle)?.label?.toString() }.getOrNull().orEmpty()
            val direction = if (Build.VERSION.SDK_INT >= 29) when (d.callDirection) {
                Call.Details.DIRECTION_OUTGOING -> CallDirection.Outgoing
                Call.Details.DIRECTION_INCOMING -> CallDirection.Incoming
                else -> CallDirection.Unknown
            } else CallDirection.Unknown
            val cnam = d.callerDisplayName.takeIf { d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED }
            val failed = Build.VERSION.SDK_INT >= 30 && d.callerNumberVerificationStatus == android.telecom.Connection.VERIFICATION_STATUS_FAILED
            PhoneCall(id, telecomPhase(call.state), identity.name, identity.number, via, connected[call],
                answer = telecomPhase(call.state) == CallPhase.Ringing, end = call.state !in listOf(Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING),
                rejected = d.disconnectCause?.code in listOf(DisconnectCause.REJECTED, DisconnectCause.LOCAL),
                direction = direction, contact = identity.contact, label = identity.label,
                spam = identity.contact != ContactStatus.Known && identity.contact != ContactStatus.Starred && spamCallerLabel(cnam),
                callerId = cnam.orEmpty(), priorAnsweredCalls = identity.history.priorAnswered, ownerCalled = identity.history.ownerCalled,
                privateNumber = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED, verificationFailed = failed)
        }
        val routes = if (Build.VERSION.SDK_INT >= 34) endpoints.map { AudioRoute(it.identifier.toString(), it.endpointName.toString(), when (it.endpointType) {
            CallEndpoint.TYPE_BLUETOOTH -> RouteKind.Bluetooth
            CallEndpoint.TYPE_EARPIECE -> RouteKind.Earpiece
            CallEndpoint.TYPE_SPEAKER -> RouteKind.Speaker
            CallEndpoint.TYPE_WIRED_HEADSET -> RouteKind.Wired
            else -> RouteKind.Other
        }) } else legacyRoutes()
        return CallsSnapshot(rows, muted, routes,
            if (Build.VERSION.SDK_INT >= 34) endpoint?.identifier?.toString() else legacyCurrent(),
            audioControl = true, limitation = "")
    }
    private fun legacyRoutes(): List<AudioRoute> {
        val state = audio ?: return emptyList()
        val routes = mutableListOf<AudioRoute>()
        listOf(Triple(CallAudioState.ROUTE_EARPIECE, "Phone earpiece", RouteKind.Earpiece), Triple(CallAudioState.ROUTE_SPEAKER, "Speaker", RouteKind.Speaker), Triple(CallAudioState.ROUTE_WIRED_HEADSET, "Wired headset", RouteKind.Wired)).forEach { (mask, name, kind) ->
            if (state.supportedRouteMask and mask != 0) routes += AudioRoute(mask.toString(), name, kind)
        }
        runCatching { state.supportedBluetoothDevices.forEach { routes += AudioRoute(it.address, it.name ?: "Bluetooth headset", RouteKind.Bluetooth) } }
        return routes
    }
    private fun legacyCurrent(): String? = audio?.let { s ->
        if (s.route == CallAudioState.ROUTE_BLUETOOTH) runCatching { s.activeBluetoothDevice?.address }.getOrNull() else s.route.toString()
    }
    fun call(id: String): Call? = ids.entries.find { it.value == id }?.key
    suspend fun changeRoute(id: String): ActionResult {
        if (Build.VERSION.SDK_INT >= 34) {
            val target = endpoints.find { it.identifier.toString() == id } ?: return ActionResult.failure("Device disconnected")
            return suspendCancellableCoroutine { continuation ->
                requestCallEndpointChange(target, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) { if (continuation.isActive) continuation.resume(ActionResult(), null) }
                    override fun onError(error: CallEndpointException) { if (continuation.isActive) continuation.resume(ActionResult.failure("Audio switch failed · use phone"), null) }
                })
            }
        }
        val route = legacyRoutes().find { it.id == id } ?: return ActionResult.failure("Device disconnected")
        if (route.kind == RouteKind.Bluetooth) {
            val device: BluetoothDevice = audio?.supportedBluetoothDevices?.find { it.address == id } ?: return ActionResult.failure("Device disconnected")
            requestBluetoothAudio(device)
        } else setAudioRoute(id.toInt())
        return ActionResult()
    }
    override fun onDestroy() {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }; callbacks.clear()
        ids.clear(); identities.clear(); connected.clear(); identityKeys.clear()
        audio = null; endpoints = emptyList(); endpoint = null
        scope.cancel(); instance = null; updates.toList().forEach { it() }; super.onDestroy()
    }
    companion object {
        var instance: CompanionCallsService? = null; private set
        val updates = linkedSetOf<() -> Unit>()
        /** Route marker for "the current Bluetooth earbuds if connected" (calls placed from the glasses or the assistant). */
        const val DEFAULT_ROUTE = "bluetooth-default"
        private var pendingRoute: String? = null
        private var pendingUntil = 0L
        /** Called just before placeCall: the route is applied when the new call's endpoints arrive (within 30 s). */
        fun requestRoute(route: String?) {
            pendingRoute = route?.takeIf { it.isNotBlank() } ?: DEFAULT_ROUTE
            pendingUntil = System.currentTimeMillis() + 30_000
        }
    }
}

internal fun telecomPhase(state: Int) = when (state) {
    Call.STATE_AUDIO_PROCESSING -> CallPhase.Screening
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallPhase.Ringing
    Call.STATE_ACTIVE -> CallPhase.Active
    Call.STATE_HOLDING -> CallPhase.Holding
    Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallPhase.Ending
    else -> CallPhase.Dialing
}
