package io.lampyr.glasses.input

import io.lampyr.glasses.debug.Trace

import android.content.*
import android.hardware.input.InputManager
import android.os.*
import android.view.InputDevice
import android.view.KeyEvent
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.service.GlassesService
import io.lampyr.glasses.input.shizuku.*

/** Service-owned input lifecycle. All engine mutations happen on the main thread. */
class ControllerRuntime(private val service: GlassesService) : AutoCloseable {
    private val prefs = service.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val keys = KeyMap(service)
    private val manager = service.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val selection = ControllerSourceSelection { engine()?.releaseAll() }
    private var source: ControllerSource? = null
    private var probing = false
    private var down = false
    private val dictations = mutableSetOf<Any>()
    @Volatile private var closed = false
    private val wake = service.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glasses:controller").apply { setReferenceCounted(false) }
    private val idle = Runnable { if (!down && dictations.isEmpty() && wake.isHeld) wake.release() }
    private val refreshWake = Runnable { if (!closed && (down || dictations.isNotEmpty())) activity() }
    private val connection = RemoteConnection(HandlerScheduler()) { engine()?.releaseAll() }
    val connected get() = connection.connected
    private var generation = 0
    private val accessibilityManager = service.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    private val accessibilityState = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener { inputPathChanged() }
    fun inputPathChanged() {
        if (!closed && selection.accepts("accessibility")) accessibility.refresh()
    }
    var detail = "Controller unavailable"; private set
    val sourceName get() = selection.selected
    val shizuku get() = source as? ShizukuControllerSource
    private fun engine() = service.controllerInput()
    private fun activity() {
        if (closed) return
        handler.removeCallbacks(idle)
        handler.removeCallbacks(refreshWake)
        wake.acquire(125_000L)
        if (!down && dictations.isEmpty()) handler.postDelayed(idle, 5_000)
        else handler.postDelayed(refreshWake, 60_000)
    }
    fun attach(input: InputEngine) {
        dictations.clear() // reloadData closes/cancels the previous UI session.
        input.onInputState = { down = it; activity() }
        input.onTalkCap = { activity() }
        input.expireHeldKeys = selection.accepts("accessibility")
        down = input.hasKeysDown
        activity()
    }
    /** Includes capture and pending transcription; terminal events release the dictation hold. */
    fun dictation(delegate: Dictation): Dictation = object : Dictation {
        private var token: Any? = null
        private var deadline: Runnable? = null
        private var stopped = false
        override fun start(target: String, onEvent: (DictationEvent) -> Unit) = start(target, emptyMap(), onEvent)
        override fun start(target: String, context: Map<String, String>, onEvent: (DictationEvent) -> Unit) {
            deadline?.let(handler::removeCallbacks)
            token?.let { dictations.remove(it) }
            val turn = Any(); token = turn; dictations.add(turn); activity()
            stopped = false
            deadline = Runnable {
                if (token === turn) {
                    // Send the captured audio; transcription retains its hold until a terminal event.
                    stop()
                }
            }.also { handler.postDelayed(it, 300_000L) }
            try {
                delegate.start(target, context) event@ { event ->
                    if (closed || token !== turn) return@event
                    if (event is DictationEvent.Done || event is DictationEvent.Error) { deadline?.let(handler::removeCallbacks); dictations.remove(turn); token = null; activity() }
                    onEvent(event)
                }
            } catch (e: Exception) { deadline?.let(handler::removeCallbacks); token = null; dictations.remove(turn); activity(); throw e }
        }
        override fun stop() { if (!stopped && token != null) { stopped = true; delegate.stop() } }
        override fun cancel() { deadline?.let(handler::removeCallbacks); delegate.cancel(); token?.let { dictations.remove(it) }; token = null; activity() }
    }
    private val sink = object : ControllerSink {
        override fun press(letter: String) { activity(); if (!probing) engine()?.let { it.source = sourceName; it.press(letter, false) } }
        override fun release(letter: String) { engine()?.let { it.source = sourceName; it.release(letter, false) } }
        override fun releaseAll() { engine()?.releaseAll() }
        override fun confirmDown() { engine()?.confirmDown(); activity() }
        override fun status(connected: Boolean, detail: String) {
            connection.report(connected)
            if (this@ControllerRuntime.detail != detail) Trace.log("Controller", "source=$sourceName connected=$connected detail=$detail")
            this@ControllerRuntime.detail = detail
        }
    }
    private var accessibility = AccessibilityControllerSource(keys, sink) { ControllerAccessibilityService.available(service) }
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("source", "gattProfile", "shizukuEnabled", "controllerDescriptor", "controllerVendor", "controllerProduct", "controllerName")) select()
        else if (key == "keyMap") { keys.refresh(); accessibility.refresh() }
    }
    private val devices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId)
            if (prefs.getString("controllerName", null) == null && keys.isController(device) && device != null)
                keys.rememberController(device) // Upgrade older saved identities when the learned pad reconnects.
            accessibility.refresh()
        }
        override fun onInputDeviceChanged(deviceId: Int) { accessibility.refresh() }
        override fun onInputDeviceRemoved(deviceId: Int) { accessibility.refresh() }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            engine()?.releaseAll()
            shizuku?.reset()
            (source as? GattControllerSource)?.reset()
        }
    }
    private val availability = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            (source as? GattControllerSource)?.environmentChanged()
        }
    }
    init {
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityState)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        manager.registerInputDeviceListener(devices, handler)
        service.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
        service.registerReceiver(availability, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(Intent.ACTION_USER_PRESENT) })
        select()
    }
    private fun select() {
        connection.switching()
        val epoch = ++generation
        val selectedSink = object : ControllerSink by sink {
            override fun status(connected: Boolean, detail: String) {
                if (!closed && generation == epoch) sink.status(connected, detail)
            }
        }
        keys.refresh() // Preference listeners have no ordering guarantee.
        source?.stop(); source = null
        engine()?.releaseAll()
        accessibility.stop()
        accessibility = AccessibilityControllerSource(keys, selectedSink) { ControllerAccessibilityService.available(service) }
        selection.shizukuReady(false)
        selection.select(prefs.getString("source", "accessibility").takeIf { it in setOf("accessibility", "gatt", "shizuku") } ?: "accessibility")
        engine()?.expireHeldKeys = selection.accepts("accessibility")
        if (probing) { detail = "BLE probe owns the controller"; return }
        source = when (selection.preferred) {
            "gatt" -> ControllerProfile.load(service)?.let { GattControllerSource(service, it, selectedSink) }
            "shizuku" -> if (prefs.getBoolean("shizukuEnabled", true)) ShizukuControllerSource(
                AndroidShizukuFacade(service) {
                    // Binder-thread ingress: cover the queue before any translation or engine work.
                    synchronized(wake) { if (!closed) wake.acquire(125_000L) }
                    handler.post { activity() }
                }, ::evdevIdentity, keys::letter, selectedSink,
                route = route@ { ready ->
                    if (closed || generation != epoch) return@route
                    selection.shizukuReady(ready)
                    engine()?.expireHeldKeys = selection.accepts("accessibility")
                    if (ready) accessibility.stop() else accessibility.start()
                }, learn = { key, value -> io.lampyr.glasses.settings.KeyLearning.raw?.invoke(key, value) == true }
            ) else accessibility
            else -> accessibility
        }
        if (source == null) detail = "No BLE profile; run probe + learn" else source?.start()
    }
    fun probing(active: Boolean) { probing = active; select() }
    private fun evdevIdentity(): EvdevIdentity? {
        if (!keys.hasLearnedController) return null
        val learnedDevice = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).firstOrNull(keys::isController)
        return EvdevIdentity(prefs.getString("controllerName", null) ?: learnedDevice?.name ?: "",
            prefs.getInt("controllerVendor", 0), prefs.getInt("controllerProduct", 0))
    }
    fun key(event: KeyEvent) {
        if (probing) return
        if (selection.accepts("accessibility")) accessibility.key(event)
        else if (keys.isController(event.device) && event.action in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            shizuku?.accessibility(if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode > 0) LinuxKeyMap.bindingCode(event.scanCode) else event.keyCode,
                if (event.action == KeyEvent.ACTION_UP) 0 else if (event.repeatCount > 0) 2 else 1, event.eventTime)
    }
    fun appeared() { accessibility.refresh(); shizuku?.refresh(); (source as? GattControllerSource)?.retryAfterUserAction() }
    fun disconnected() {
        connection.report(false)
        detail = "Controller disconnected"; engine()?.releaseAll()
        shizuku?.reset()
        (source as? GattControllerSource)?.reset()
    }
    override fun close() {
        synchronized(wake) { closed = true }
        source?.stop(); source = null
        accessibility.stop()
        engine()?.releaseAll()
        closed = true; dictations.clear()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        manager.unregisterInputDeviceListener(devices)
        service.unregisterReceiver(screen)
        service.unregisterReceiver(availability)
        accessibilityManager.removeAccessibilityStateChangeListener(accessibilityState)
        connection.close()
        keys.close(); handler.removeCallbacksAndMessages(null)
        if (wake.isHeld) wake.release()
    }
}

class AccessibilityControllerSource(
    private val keys: KeyMap,
    private val sink: ControllerSink,
    private val available: () -> Boolean = { true },
) : ControllerSource {
    override val name = "accessibility"
    private var running = false
    private var deviceIds = emptySet<Int>()
    override fun start() { running = true; refresh() }
    override fun stop() { if (!running) return; running = false; deviceIds = emptySet(); sink.releaseAll() }
    fun refresh() {
        if (!running) return
        val next = InputDevice.getDeviceIds().filter { keys.isController(InputDevice.getDevice(it)) }.toSet()
        if ((deviceIds - next).isNotEmpty()) sink.releaseAll()
        deviceIds = next
        val usable = available()
        sink.status(next.isNotEmpty() && usable, if (!usable) "Accessibility disabled or unbound" else if (next.isNotEmpty()) "Keyboard controller connected" else "Learn/connect controller keyboard")
    }
    fun key(event: KeyEvent) {
        if (!running || !keys.isController(event.device)) return
        sink.confirmDown()
        val letter = keys.letter(event.keyCode) ?: return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sink.press(letter)
        else if (event.action == KeyEvent.ACTION_UP) sink.release(letter)
    }
}
