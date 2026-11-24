package io.lampyr.glasses.input

import android.content.Context
import android.os.Handler
import android.os.Looper

class GattControllerSource(private val context: Context, private val profile: ControllerProfile, private val sink: ControllerSink) : ControllerSource {
    override val name = "gatt"
    private val decoder = ControllerReportDecoder(profile.reportLength, profile.fields)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val retry = GattRetry()
    private var needsAction = false
    private val reconnect: Runnable = Runnable { if (running) session.connect(profile.address, true) }
    private val session: NonHidGattSession = NonHidGattSession(context,
        services = { services -> services.filter { it.uuid == profile.service }.flatMap { it.characteristics }
            .filter { it.uuid == profile.characteristic }.singleOrNull()?.let { listOf(it) } ?: emptyList() },
        report = { _, value ->
            val changes = decoder.decode(value)
            if (changes != null) {
                sink.status(true, "Vendor BLE controller connected")
                sink.confirmDown()
                changes.deliver(sink)
            } else sink.status(true, "BLE report length mismatch; re-learn this mode")
        },
        state = { connected, detail ->
            sink.status(connected, detail)
            needsAction = detail.startsWith("Needs user action:")
            if (!connected) { decoder.reset(); sink.releaseAll() }
            handler.removeCallbacks(reconnect)
            val delay = retry.next(connected, detail)
            if (running && delay != null) handler.postDelayed(reconnect, delay)
        })
    override fun start() { running = true; session.connect(profile.address, true) }
    override fun stop() { running = false; handler.removeCallbacks(reconnect); session.close(); decoder.reset(); sink.releaseAll() }
    fun reset() { decoder.reset() }
    fun retryAfterUserAction() { if (running && needsAction) session.connect(profile.address, true) }
    fun environmentChanged() {
        if (!running) return
        val available = runCatching {
            (android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        if (!available || needsAction) session.connect(profile.address, true)
    }
}

class GattRetry {
    private var failures = 0
    fun next(connected: Boolean, detail: String): Long? {
        if (connected) { failures = 0; return null }
        if (detail.startsWith("Needs user action:") || detail.startsWith("Waiting") || detail.startsWith("Discovering")) return null
        return (5_000L * (1L shl failures.coerceAtMost(6))).coerceAtMost(300_000L).also { failures++ }
    }
}
