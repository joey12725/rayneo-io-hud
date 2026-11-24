@file:Suppress("MissingPermission", "DEPRECATION")
package io.lampyr.glasses.debug

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.bluetooth.BluetoothManager
import android.provider.Settings
import io.lampyr.glasses.BuildConfig
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.input.shizuku.AndroidShizukuFacade
import io.lampyr.glasses.service.*
import io.lampyr.glasses.settings.AppSettings
import io.lampyr.glasses.ui.Display
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** adb exec-out content read --uri content://io.lampyr.glasses.debug/ping
 * Caller identity is checked on the Binder thread, before crossing to the pipe worker.
 */
class DebugProvider : ContentProvider() {
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "debug-bridge").apply { isDaemon = true } }
    private lateinit var bridge: DebugBridge
    override fun onCreate(): Boolean {
        val app = checkNotNull(context)
        DebugTraceFiles.install(app)
        Trace.protect(BuildConfig.GATEWAY_TOKEN)
        bridge = DebugBridge(app)
        return true
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller(Binder.getCallingUid())
        val pipe = ParcelFileDescriptor.createPipe()
        workers.execute {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                val response = if (mode == "r") runBlocking { bridge.execute(uri) }
                    else DebugBridge.failure(IllegalArgumentException("Only mode r is supported"))
                runCatching { output.write(response.bytes) }
            }
        }
        return pipe[0]
    }
    override fun getType(uri: Uri): String {
        checkCaller(Binder.getCallingUid())
        return when (uri.path) { "/frame.png" -> "image/png"; "/text" -> "text/plain"; else -> "application/json" }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { checkCaller(Binder.getCallingUid()); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { checkCaller(Binder.getCallingUid()); return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { checkCaller(Binder.getCallingUid()); return 0 }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { checkCaller(Binder.getCallingUid()); return 0 }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? { checkCaller(Binder.getCallingUid()); return null }
    override fun canonicalize(uri: Uri): Uri? { checkCaller(Binder.getCallingUid()); return null }
    override fun uncanonicalize(uri: Uri): Uri? { checkCaller(Binder.getCallingUid()); return null }
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? { checkCaller(Binder.getCallingUid()); return null }
    override fun refresh(uri: Uri, args: Bundle?, cancellationSignal: CancellationSignal?): Boolean { checkCaller(Binder.getCallingUid()); return false }
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int { checkCaller(Binder.getCallingUid()); return 0 }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> { checkCaller(Binder.getCallingUid()); return emptyArray() }
    companion object {
        fun checkCaller(uid: Int) { if (uid != 0 && uid != 2000) throw SecurityException("Debug bridge is shell/root only (uid=$uid)") }
    }
}

internal data class DebugResponse(val bytes: ByteArray)

/** Separated from Binder plumbing to exercise exactly the production router in Robolectric. */
internal class DebugBridge(private val context: Context) {
    private val experiments = DisplayExperimentController(context)
    private fun json(value: JSONObject) = DebugResponse(Trace.safeText(value.toString()).toByteArray(Charsets.UTF_8))
    private fun ok(vararg fields: Pair<String, Any?>) = objectJson("ok" to true, *fields)
    private fun Uri.q(key: String, fallback: String? = null) = getQueryParameter(key) ?: fallback
    private fun Uri.required(key: String) = requireNotNull(q(key)) { "Missing query parameter $key" }
    private fun Uri.long(key: String, default: Long) = q(key)?.toLong() ?: default

    suspend fun execute(uri: Uri): DebugResponse = try {
        val timeout = uri.long("timeoutMs", 15_000).also { require(it in 1..600_000) { "timeoutMs must be 1..600000" } }
        // Scans have an explicit duration; wait uses timeoutMs as its total limit.
        val budget = if (uri.path == "/glasses/wait") timeout + 6000 else if (uri.path == "/glasses/scan" && uri.q("timeoutMs") == null) maxOf(timeout, uri.long("ms", 12_000) + 2000) else timeout
        withTimeout(budget) { withContext(Dispatchers.Main.immediate) { route(uri) } }
    } catch (e: Exception) { failure(e) }

    private suspend fun service(): GlassesService {
        GlassesService.instance?.let { return it }
        ServiceAccess.start(context)
        withTimeoutOrNull(5000) { while (GlassesService.instance == null) delay(50) }
        return GlassesService.instance ?: error("Glasses service is not running; Android may refuse a background foreground-service start. Open the phone app or adb shell am start -n io.lampyr.glasses/.MainActivity")
    }
    private suspend fun real(): RayNeoTransport { service(); return checkNotNull(GlassesTransports.real) { "Glasses transport is not attached" } }
    private fun frame(service: GlassesService, uri: Uri): Frame {
        val which = uri.q("which", "master")
        require(which in setOf("master", "sent")) { "which must be master or sent" }
        return checkNotNull(if (which == "sent") service.preview.frames.value else service.preview.masterFrame) { "No $which frame rendered yet" }
    }
    private fun screen(service: GlassesService) = service.preview.masterFrame?.text?.lines().orEmpty()
    private fun status(value: GlassesStatus) = objectJson("battery" to value.battery, "firmware" to value.firmware, "brightness" to value.brightness)
    private suspend fun route(uri: Uri): DebugResponse {
        val path = uri.path.orEmpty()
        when (path) {
            "/ping" -> return json(ping())
            "/state" -> return json(state())
            "/help" -> return json(ok("endpoints" to help))
            "/trace" -> {
                val entries = Trace.since(uri.long("since", 0), 5000).filter { it.cat.startsWith(uri.q("cat", "")!!) }
                    .take(uri.long("max", 500).coerceIn(0, 5000).toInt())
                return json(ok("latest" to Trace.latest, "entries" to entries.map { it.json() }))
            }
            "/text" -> return DebugResponse(Trace.safeText(frame(service(), uri).text.lines().joinToString("\n")).toByteArray(Charsets.UTF_8))
            "/frame.png" -> {
                val scaleValue = uri.long("scale", 1); require(scaleValue in 1..4) { "scale must be 1..4" }; val scale = scaleValue.toInt()
                val source = frame(service(), uri).bitmap
                val image = if (scale == 1) source else Bitmap.createScaledBitmap(source, source.width * scale, source.height * scale, false)
                val bytes = ByteArrayOutputStream().use { out -> check(image.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
                if (image !== source) image.recycle()
                return DebugResponse(bytes)
            }
            "/buttons" -> return json(ok("ids" to InputEngine.BIND.keys.toList(), "bindings" to InputEngine.BIND))
            "/key", "/keys" -> {
                val service = service()
                if (path == "/key") key(service.input, uri.required("b"), uri.q("a", "tap")!!, uri.long("ms", 600))
                else {
                    val gap = uri.long("gap", 150).also { require(it in 0..60_000) { "gap must be 0..60000" } }
                    val steps = uri.required("seq").split(','); require(steps.size <= 100) { "At most 100 keys per request" }
                    for ((index, value) in steps.withIndex()) {
                        val parts = value.split(':')
                        key(service.input, parts[0], parts.getOrNull(1) ?: "tap", parts.getOrNull(2)?.toLong() ?: 600)
                        if (index != steps.lastIndex) delay(gap)
                    }
                }
                service.ui.requestRender(); delay(300)
                return json(ok("screen" to screen(service), "stack" to service.ui.stack.map { it.javaClass.simpleName }))
            }
            "/pref" -> return json(pref(uri))
            "/call/state" -> return json(ok("calls" to callState(service())))
            "/call/op" -> {
                // Runs a CallOps op exactly as the assistant would from outside the glasses conversation (call.place shows the card).
                val service = service()
                val registry = checkNotNull(service.agentRegistry) { "Agent registry not started" }
                val name = uri.required("name"); require(name.startsWith("call.") || name.startsWith("contacts.")) { "name must be a call.* or contacts.* op" }
                val args = Json.parseToJsonElement(uri.q("args", "{}")!!) as? JsonObject ?: error("args must be a JSON object")
                val result = registry.call(name, args, "bridge", io.lampyr.glasses.agent.Origin.OTHER)
                return json(JSONObject(result.toJson("bridge").toString()))
            }
            "/power" -> {
                val ui = service().ui
                when (uri.q("do", "state")) {
                    "wake" -> ui.wake("debug", uri.long("ms", 0).also { require(it in 0..600_000) { "ms must be 0..600000" } })
                    "sleep" -> ui.sleep("debug")
                    "popup" -> ui.popup(io.lampyr.glasses.ui.PopupCard(uri.q("title", "Popup")!!, uri.q("body", "")!!, uri.q("icon"), uri.q("app"), uri.q("target"), uri.long("ms", 6000)))
                    "state" -> Unit
                    else -> error("do must be wake, sleep, popup or state")
                }
                delay(300)
                return json(ok("power" to power(ui), "screen" to screen(service())))
            }
            "/notify/active" -> {
                // Read-only: how the rules would route each currently posted allow-listed phone notification (e.g. a real Tinder
                // match). No text leaves the phone: package, channel, category and decision only. Nothing is routed, posted or shown.
                val listener = io.lampyr.glasses.data.PhoneNotifications.instance ?: error("Notification access is not connected")
                val store = (GlassesService.instance?.ui?.data as? io.lampyr.glasses.data.LiveDataStore)
                val pkg = uri.q("pkg")
                val rows = listener.activeNotifications.orEmpty()
                    .filter { io.lampyr.glasses.data.NotificationRules.allowedPackage(it.packageName) && (pkg == null || it.packageName == pkg) }
                    .map { sbn ->
                        val n = listener.routingInput(sbn)
                        val row = if (n == null) mapOf("pkg" to sbn.packageName, "channel" to sbn.notification.channelId.orEmpty(), "category" to sbn.notification.category.orEmpty(), "decidedBy" to "dropped-on-phone")
                            else store?.checkPhoneNotification(n) ?: io.lampyr.glasses.data.NotificationRules.check(n)
                        row + mapOf("postedAt" to sbn.postTime)
                    }
                return json(ok("count" to rows.size, "notifications" to rows))
            }
            "/notify/inject" -> {
                // A synthetic phone notification through the real routing path (rules, /notify/route, popups); no Android notification is posted.
                val service = service(); val start = Trace.latest
                val store = service.ui.data as? io.lampyr.glasses.data.LiveDataStore ?: error("Live data is off")
                val pkg = uri.q("pkg", "com.beeper.android")!!
                val input = io.lampyr.glasses.data.NoticeInput(kind = uri.q("kind") ?: io.lampyr.glasses.data.NotificationRules.kindFor(pkg), source = pkg, app = uri.q("app", pkg)!!,
                    title = uri.q("title", "Test")!!, body = uri.q("body", "")!!, conversationTitle = uri.q("conversation", "")!!, sender = uri.q("sender", "")!!,
                    network = uri.q("network", "")!!, isGroup = uri.q("group") == "1", chatId = uri.q("chatId", "")!!, channelId = uri.q("channel", "")!!, category = uri.q("category", "")!!)
                store.phoneNotification("debug:${System.currentTimeMillis()}", input)
                delay(300)
                return json(ok("trace" to Trace.since(start, 50).filter { it.cat == "Notify" }.map { it.json() }, "power" to power(service.ui), "screen" to screen(service)))
            }
            "/clock" -> {
                // Clock app (requirement 7): runs one ClockOps op exactly as the assistant would, e.g. /clock?op=timer.start&duration=10s
                val service = service()
                val args = kotlinx.serialization.json.buildJsonObject {
                    uri.queryParameterNames.filter { it != "op" && it != "timeoutMs" }.forEach { k ->
                        val v = uri.getQueryParameter(k).orEmpty()
                        when (k) {
                            "all", "enabled" -> put(k, kotlinx.serialization.json.JsonPrimitive(v == "1" || v == "true"))
                            "minutes" -> put(k, v.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonPrimitive(v))
                            else -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                        }
                    }
                }
                val result = service.agentRegistry!!.call(uri.required("op"), args, "debug")
                return json(ok("result" to org.json.JSONObject(result.toJson("debug").toString()), "summary" to service.clock.homeSummary()))
            }
            "/service/start" -> { service(); return json(ok("serviceRunning" to true)) }
            "/service/reload" -> { service().reloadData(); return json(ok()) }
            "/open" -> { val service = service(); service.ui.open(io.lampyr.glasses.data.OpenTarget(uri.required("screen"))); delay(300); return json(ok("screen" to screen(service))) }
            "/glasses/scan" -> return json(scan(uri))
            "/glasses/select" -> {
                val ble = uri.required("ble"); val classic = uri.q("classic")?.takeIf { it.isNotBlank() }
                require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ble)) { "Invalid BLE address" }
                require(classic == null || android.bluetooth.BluetoothAdapter.checkBluetoothAddress(classic)) { "Invalid classic address" }
                GlassesSetupActions.select(context, RayNeoDevice(uri.q("name", "RayNeo iO")!!, ble, classic))
                return json(ok("bleAddress" to ble, "classicAddress" to classic))
            }
            "/glasses/enable" -> { service(); val on = uri.required("on"); require(on in setOf("0", "1")); check(context.getSharedPreferences("glasses", 0).edit().putBoolean("glasses.enabled", on == "1").commit()); return json(ok("enabled" to (on == "1"))) }
            "/glasses/reconnect" -> { service(); GlassesTransports.reconnect(); return json(ok()) }
            "/glasses/wait" -> {
                val start = Trace.latest; val real = real()
                val wanted = ConnectionState.valueOf(uri.q("state", "CONNECTED")!!.uppercase())
                val matched = withTimeoutOrNull(uri.long("timeoutMs", 15_000)) { real.connectionState.first { it == wanted }; true } ?: false
                return json(objectJson("ok" to matched, "state" to real.connectionState.value.name, "lastError" to real.lastError.value,
                    "error" to if (matched) null else "Timed out waiting for $wanted", "trace" to Trace.since(start, 5000).map { it.json() }))
            }
            "/glasses/status" -> { val real = real(); real.status(); delay(1000); return json(ok("battery" to real.deviceStatusFlow.value.battery, "firmware" to real.deviceStatusFlow.value.firmware, "brightness" to real.deviceStatusFlow.value.brightness)) }
            "/glasses/testdisplay" -> { val real = real(); check(real.connected) { "Connect glasses first" }; GlassesSetupActions.testPattern(real); return json(ok()) }
            "/glasses/notify" -> { real().notify(uri.q("title", "Probe")!!, uri.q("body", "")!!, uri.q("appId", "io.lampyr.glasses")!!, uri.long("category", 0).toInt()); return json(ok()) }
            "/glasses/reclaim" -> { real().reclaim(); return json(ok()) }
            "/glasses/mic" -> { real(); val on = uri.required("on"); require(on in setOf("0", "1")); GlassesSetupActions.micTest(on == "1"); return json(ok("capturing" to (GlassesSetupActions.testingMic != null))) }
            "/glasses/send" -> {
                // Constructing a Command and calling frame enforces the stock OTA allowlist even if no service/link exists.
                val command = probeCommand(uri)
                val channel = uri.q("channel", "rfcomm")!!; require(channel in setOf("rfcomm", "ble")) { "channel must be rfcomm or ble" }
                real().sendProbe(command, channel == "ble")
                return json(ok())
            }
            else -> if (path.startsWith("/experiment/")) {
                val step = path.removePrefix("/experiment/")
                if (step !in setOf("log", "record", "stop")) real()
                val result = experiments.execute(step, uri.queryParameterNames.associateWith { uri.q(it).orEmpty() })
                return json(ok(if (step == "log") "log" to result else "result" to result))
            }
        }
        return json(objectJson("ok" to false, "error" to "Unknown endpoint $path", "detail" to "Use /help", "endpoints" to help))
    }
    internal suspend fun key(input: InputEngine, id: String, action: String, ms: Long) {
        require(id in InputEngine.BIND) { "Unknown button $id; use /buttons" }
        require(action in setOf("tap", "down", "up", "hold")) { "a must be tap, down, up or hold" }
        require(ms in 0..120_000) { "ms must be 0..120000" }
        input.source = "adb"
        when (action) {
            "down" -> input.press(id)
            "up" -> input.release(id)
            else -> { input.press(id); try { delay(if (action == "tap") 60 else ms) } finally { input.release(id) } }
        }
    }
    private fun pref(uri: Uri): JSONObject {
        val name = when (uri.required("file")) { "glasses" -> "glasses"; "app" -> "live-settings"; else -> error("file must be glasses or app") }
        val key = uri.required("key"); require(!Trace.credentialKey(key) && key != "glasses.phoneId") { "Credential/identity preferences cannot be written" }
        val prefs = context.getSharedPreferences(name, 0); val edit = prefs.edit()
        when (uri.q("type", "string")) {
            "remove" -> edit.remove(key)
            "string" -> edit.putString(key, uri.required("value"))
            "bool" -> edit.putBoolean(key, when (uri.required("value")) { "true", "1" -> true; "false", "0" -> false; else -> error("Invalid bool") })
            "int" -> edit.putInt(key, uri.required("value").toInt())
            "long" -> edit.putLong(key, uri.required("value").toLong())
            "float" -> edit.putFloat(key, uri.required("value").toFloat())
            else -> error("type must be string, bool, int, long, float or remove")
        }
        check(edit.commit()) { "Preference commit failed" }
        return ok("file" to uri.q("file"), "key" to key, "value" to prefs.all[key])
    }
    private suspend fun scan(uri: Uri): JSONObject {
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        check(missing.isEmpty()) { "Missing permissions: ${missing.joinToString()}. Fix: ${missing.joinToString("; ") { "adb shell pm grant ${context.packageName} $it" }}" }
        if (Build.VERSION.SDK_INT < 31) check(context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) { "Enable Location services: adb shell cmd location set-location-enabled true" }
        check(context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) { "Enable Bluetooth: adb shell svc bluetooth enable" }
        val ms = uri.long("ms", 12_000); require(ms in 1..120_000) { "ms must be 1..120000" }
        val found = linkedMapOf<String, RayNeoDevice>(); var failure: String? = null
        val handle = BleAuthenticator(context).scan({ found[it.bleAddress] = it }, { failure = it }, ms)
        try { delay(ms) } finally { handle.close() }
        check(failure == null) { failure.orEmpty() }
        return ok("devices" to found.values.map { objectJson("name" to it.name, "bleAddress" to it.bleAddress, "classicAddress" to it.classicAddress, "rssi" to it.rssi) })
    }
    private fun power(ui: io.lampyr.glasses.ui.Ui): JSONObject {
        val p = ui.powerStore.prefs
        return objectJson("state" to ui.power.state.name, "lit" to ui.awake, "locked" to ui.locked, "idleMs" to p.idleMs,
            "lastWakeReason" to ui.power.lastWakeReason, "lastChangeAt" to ui.power.lastChangeAt, "keepAwake" to ui.keepAwake,
            "popup" to ui.popupCard?.takeIf { ui.popupVisible }?.title, "backend" to p.sleepBackend, "lookUpWake" to p.lookUpWake,
            "lookUp" to p.lookUp.name, "lookUpWhenLocked" to p.lookUpWhenLocked, "textCase" to p.textCase.name,
            "firstPressActs" to p.firstPressActs, "lockHomeMs" to p.lockHomeMs, "popupWhenLocked" to p.popupWhenLocked, "dimStep" to p.dimStep,
            "stockSettings" to ui.glassesSettings.current?.toString())
    }
    private fun ping() = ok("pid" to Process.myPid(), "versionName" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
        "buildTime" to BuildConfig.BUILD_TIME, "gitSha" to BuildConfig.GIT_SHA, "debug" to true, "serviceRunning" to (GlassesService.instance != null))

    /** Call state for diagnostics: phases, routes and access only; caller names and numbers are left out. */
    private fun callState(service: GlassesService?): JSONObject? {
        val ui = service?.ui ?: return null
        val state = JSONObject(io.lampyr.glasses.agent.ops.CallOps.stateJson(ui).toString())
        val calls = state.optJSONArray("calls") ?: JSONArray()
        for (i in 0 until calls.length()) calls.getJSONObject(i).apply { remove("name"); remove("number") }
        state.put("companionBound", io.lampyr.glasses.calls.CompanionCallsService.instance != null)
        state.put("manageOngoingCalls", runCatching { Build.VERSION.SDK_INT >= 31 && context.getSystemService(android.telecom.TelecomManager::class.java).hasManageOngoingCallsPermission() }.getOrDefault(false))
        return state
    }

    private fun state(): JSONObject {
        val service = GlassesService.instance
        val real = GlassesTransports.real
        val controller = service?.controller
        val shizuku = controller?.shizuku?.state ?: AndroidShizukuFacade.availability(context)
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toMutableSet()
        // PackageManager omits declarations excluded by maxSdkVersion on the installed OS.
        // Inspect the packaged manifest as well, so permission diagnostics do not hide them.
        runCatching {
            context.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
                while (manifest.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (manifest.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && manifest.name.startsWith("uses-permission"))
                        manifest.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let(requested::add)
                    manifest.next()
                }
            }
        }
        val permissions = JSONObject()
        requested.forEach { permissions.put(it, context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) }
        // MANAGE_ONGOING_CALLS is signature|appop: checkSelfPermission is always false, Telecom's own check is what counts.
        if (Build.VERSION.SDK_INT >= 31) permissions.put(Manifest.permission.MANAGE_ONGOING_CALLS,
            runCatching { context.getSystemService(android.telecom.TelecomManager::class.java).hasManageOngoingCallsPermission() }.getOrDefault(false))
        val accessibility = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == ControllerAccessibilityService::class.java.name }
        permissions.put("accessibilityEnabled", accessibility).put("accessibilityBound", ControllerAccessibilityService.bound)
            .put("notificationListenerEnabled", Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName })
            .put("batteryOptimizationExempt", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
            .put("shizuku", objectJson("installed" to shizuku.installed, "running" to shizuku.running, "permission" to shizuku.granted, "bound" to shizuku.bound))
        val bluetooth = runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            objectJson("enabled" to (adapter?.isEnabled == true), "bondedDevices" to adapter?.bondedDevices?.map { objectJson("name" to it.name, "address" to it.address, "type" to it.type) }.orEmpty())
        }.getOrElse { objectJson("enabled" to null, "bondedDevices" to emptyList<Any>(), "error" to it.message) }
        fun prefs(name: String) = context.getSharedPreferences(name, 0).all.mapValues { (key, value) ->
            if (Trace.credentialKey(key)) objectJson("present" to (value != null && value.toString().isNotEmpty())) else value
        }
        val caps = real?.raster?.capabilities?.value
        val glasses = objectJson("connectionState" to real?.connectionState?.value?.name, "lastError" to real?.lastError?.value,
            "deviceStatus" to real?.deviceStatusFlow?.value?.let(::status), "textLayout" to real?.textLayout?.value?.let { objectJson("columns" to it.first, "rows" to it.second) },
            "usesBitmap" to real?.usesBitmap, "raster" to objectJson("backend" to real?.raster?.selected?.value?.name, "mode" to real?.raster?.mode?.name,
                "probing" to real?.raster?.probing?.value, "ready" to real?.raster?.ready?.value, "note" to real?.raster?.note?.value,
                "tvp2Hello" to caps?.turbo, "awake" to real?.raster?.awake, "parked" to real?.raster?.parked,
                "powerBackend" to real?.raster?.powerBackend?.name, "pipeline" to real?.raster?.pipelineEnabled, "expectsRaster" to real?.raster?.expectsRaster, "glasslyIdent" to caps?.glassly?.let { Json.parseToJsonElement(kotlinx.serialization.json.Json.encodeToString(io.lampyr.glasses.glasses.raster.GlasslyIdentity.serializer(), it)).toString().let(::JSONObject) }),
            "pageOwner" to real?.pageOwner, "mic" to objectJson("capturing" to real?.mic?.isCapturing(), "mode" to real?.audioMode?.value,
                "lastError" to real?.mic?.lastError?.value, "meter" to real?.mic?.levels?.value, "packets" to real?.mic?.packets?.get(), "drops" to real?.mic?.drops?.get()),
            "reconnectAttempt" to real?.reconnectAttempt, "reconnectDelayMs" to real?.reconnectDelayMs,
            "retry" to real?.retry?.value?.let { objectJson("phase" to it.phase.name, "attempt" to it.attempt, "reason" to it.reason,
                "background" to it.background, "detail" to io.lampyr.glasses.glasses.ReconnectPolicy.detail(it)) },
            "officialApp" to io.lampyr.glasses.glasses.OfficialApp.health(context).let { objectJson("level" to it.level.name, "text" to it.text) })
        val appPrefs = JSONObject(prefs("live-settings")).put("tokenPresent", context.getSharedPreferences("live-settings", 0).contains("token") || BuildConfig.GATEWAY_TOKEN.isNotBlank())
        val display = service?.ui?.display
        return ok("app" to ping(), "android" to objectJson("sdkInt" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL),
            "permissions" to permissions, "bluetooth" to bluetooth, "prefs" to objectJson("glasses" to prefs("glasses"), "app" to appPrefs), "glasses" to glasses,
            "ui" to objectJson("displayMode" to when (display) { is Display.Text -> "Text ${display.cols}×${display.rows}"; null -> null; else -> "Bitmap" }, "locked" to service?.ui?.locked,
                "stack" to service?.ui?.stack?.map { it.javaClass.simpleName }.orEmpty(), "flash" to service?.ui?.currentToast,
                "power" to service?.ui?.let(::power)),
            "screen" to objectJson("master" to service?.preview?.masterFrame?.text?.lines().orEmpty(), "sent" to service?.preview?.frames?.value?.text?.lines().orEmpty()),
            "controller" to objectJson("activeSource" to controller?.sourceName, "healthRows" to ControllerSetupSection.status(context).lines(), "detail" to controller?.detail, "lastKeyEvent" to service?.controllerInput()?.lastKeyEvent),
            "ambient" to objectJson("enabled" to service?.ambient?.preferences?.enabled, "statusLine" to service?.ambient?.status?.value?.line()), "trace" to objectJson("latest" to Trace.latest),
            "latency" to Latency.summary(),
            "calls" to callState(service),
            "agent" to objectJson("linked" to service?.agentLink?.connected, "ops" to service?.agentRegistry?.ops?.size, "callsHandled" to service?.agentLink?.callsHandled, "lastError" to service?.agentLink?.lastError))
    }
    companion object {
        internal fun probeCommand(uri: Uri): Command {
            val business = requireNotNull(uri.getQueryParameter("business")) { "Missing business" }.toInt()
            val type = requireNotNull(uri.getQueryParameter("type")) { "Missing type" }.toInt()
            require(business in 0..255 && business != 0x10) { "Invalid stock business (pairing is not a probe endpoint)" }
            val body = runCatching { Json.parseToJsonElement(uri.getQueryParameter("json") ?: "{}") as? JsonObject }.getOrNull() ?: error("json must be a valid object")
            require(Trace.redact(body) == body) { "Credential fields are not accepted by protocol probes" }
            return Command(business, Envelope(type, body.toString())).also { it.frame(0) }
        }
        internal fun failure(error: Exception) = DebugResponse(objectJson("ok" to false, "error" to Trace.safeText(error.message ?: error.javaClass.simpleName), "detail" to Trace.safeText(error.stackTraceToString())).toString().toByteArray(Charsets.UTF_8))
        val help = linkedMapOf(
            "/ping" to "Build identity and service liveness", "/state" to "Full diagnostic snapshot", "/text?which=master|sent" to "Plain text grid",
            "/frame.png?which=master|sent&scale=1..4" to "PNG frame", "/trace?since=0&max=500&cat=PREFIX" to "Structured trace ring",
            "/key?b=ID&a=tap|down|up|hold&ms=600" to "Logical controller input", "/keys?seq=down,down,a,b:hold:600&gap=150" to "Key sequence", "/buttons" to "Valid ids and bindings",
            "/pref?file=glasses|app&key=K&value=V&type=string|bool|int|long|float|remove" to "Synchronous preference edit",
            "/glasses/scan?ms=12000" to "BLE discovery", "/glasses/select?ble=ADDRESS&classic=ADDRESS&name=NAME" to "Save selection",
            "/glasses/enable?on=1|0" to "Enable or disable transport", "/glasses/reconnect" to "Restart reconnect budget",
            "/glasses/wait?state=CONNECTED&timeoutMs=60000" to "Wait for connection and capture trace", "/glasses/status" to "Query battery/firmware/brightness",
            "/glasses/testdisplay" to "Setup test grid", "/glasses/notify?title=TEXT&body=TEXT&appId=PKG&category=0" to "Stock notification",
            "/glasses/reclaim" to "Reclaim display", "/glasses/mic?on=1|0" to "Meter-only mic test",
            "/glasses/send?business=N&type=N&json=OBJECT&channel=rfcomm|ble" to "Stock probe through guarded authenticated write boundary",
            "/experiment/firmware" to "Firmware query", "/experiment/glyph?page=0" to "Glyph ruler", "/experiment/notif?app=PKG|category=N" to "Icon probe",
            "/experiment/weather?code=100" to "Weather icon", "/experiment/widget?install=1|0" to "Minimal widget", "/experiment/pattern?kind=tvp2|glassly&force=0|1" to "Capability-gated raster pattern",
            "/experiment/stop" to "Restore app display", "/experiment/record?result=PASS|FAIL|UNKNOWN&note=TEXT" to "Record observation", "/experiment/log" to "Experiment log",
            "/clock?op=timer.start&duration=10s&label=Test" to "Run a Clock op (timer.*, stopwatch.*, alarm.*) like the assistant", "/service/start" to "Start service", "/service/reload" to "Reload data session", "/open?screen=NAME" to "Open app screen", "/help" to "Endpoint list; timeoutMs overrides default 15-second command budget",
            "/power?do=wake|sleep|popup|state&ms=0&title=T&body=B&app=ID&target=T" to "Display power: wake (for at least ms), sleep, show a popup card, or report state",
            "/notify/active?pkg=PKG" to "Read-only, text-free routing check of the currently posted allow-listed phone notifications",
            "/notify/inject?pkg=PKG&kind=K&channel=C&category=C&title=T&body=B&group=0|1&conversation=C&sender=S&network=N&chatId=ID" to "Route a synthetic phone notification (rules, gateway, popup)",
            "/call/state" to "Phone call phases, routes and call access (no names or numbers)",
            "/call/op?name=call.place&args={json}" to "Run a call.* / contacts.* agent op as the assistant would from outside the glasses (call.place shows the confirm card)")
    }
}

internal fun objectJson(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { (k, v) -> put(k, wrapped(v)) } }
private fun wrapped(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), wrapped(v)) } }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(wrapped(it)) } }
    else -> value
}
