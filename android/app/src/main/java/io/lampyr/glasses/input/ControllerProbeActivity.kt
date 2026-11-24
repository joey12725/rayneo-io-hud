package io.lampyr.glasses.input

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService

/** Diagnostic central: never reads HID, writes vendor commands, or changes pairing/bonds. */
@Suppress("MissingPermission")
class ControllerProbeActivity : Activity() {
    private lateinit var status: Note
    private lateinit var live: TextView
    private lateinit var devices: Stack
    private lateinit var characteristics: Stack
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var session: NonHidGattSession? = null
    private var address = ""
    private val seen = mutableSetOf<String>()
    private val reports = mutableMapOf<String, ByteArray>()
    private val rows = mutableMapOf<String, TextView>()
    private val ambiguous = mutableSetOf<Pair<java.util.UUID, java.util.UUID>>()
    private var chosen: BluetoothGattCharacteristic? = null
    private var ready = false
    private var neutral: ByteArray? = null
    private var pending: ByteArray? = null
    private val samples = mutableListOf<ByteArray>()
    private val learned = mutableListOf<ButtonField>()
    private var learning = false
    private val letters = listOf("up", "down", "left", "right", "y", "b", "x", "a", "l", "l2", "r", "r2", "minus", "plus", "star", "heart")
    private fun id(c: BluetoothGattCharacteristic) = "${c.service.uuid}/${c.uuid}/${c.instanceId}"
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Bluetooth probe", "Looks for a direct button channel on the controller, so presses can skip Android's keyboard handling. It only listens: nothing is written and pairing is untouched.") {
            add(context.card(12) {
                add(context.text("Try this in each controller mode. Choose your controller, then press and release buttons to find a channel whose bytes change.", Type.SMALL))
                add(context.button("Scan for 15 seconds", Btn.PRIMARY) { permissionsThenScan() })
                status = add(context.note())
            })
            section("Controllers found")
            devices = add(context.card(8))
            section("Channels")
            characteristics = add(context.card(8))
            live = add(context.text("", Type.MONO))
            section("Learn from the chosen channel")
            add(context.card(12) {
                add(context.text("Let go of every button first. Then press and release each one as it is named.", Type.SMALL))
                add(context.button("Start learning") { beginLearn() })
                add(context.button("Stop the probe", Btn.DANGER) { stop(); status.text = "Probe stopped" })
            })
        }
    }
    private fun permissionsThenScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) scan() else requestPermissions(missing.toTypedArray(), 72)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 72) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scan()
            else status.text = "Nearby devices permission required (Android 10/11: Location and system Location enabled)"
        }
    }
    private fun scan() {
        stop(); devices.removeAllViews(); characteristics.removeAllViews(); seen.clear()
        runCatching {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
            check(adapter.isEnabled) { "Turn on Bluetooth" }
            scanner = adapter.bluetoothLeScanner ?: error("BLE scanning unavailable")
            scanner!!.startScan(callback)
            status.text = "Scanning BLE for 15 seconds. Keyboard-only/classic modes may advertise no BLE device."
            handler.postDelayed({ stopScan(); status.text = if (seen.isEmpty()) "No BLE devices found. Check controller mode and Android 10/11 Location. A classic HID-only mode cannot supply vendor BLE reports." else "Scan finished. Select your controller." }, 15_000)
        }.onFailure { status.text = it.message }
    }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { runOnUiThread {
            val device = result.device
            if (seen.add(device.address)) devices.add(button("${result.scanRecord?.deviceName ?: "Unnamed"} · ${device.address}") { connect(device.address) })
        } }
        override fun onScanFailed(errorCode: Int) { runOnUiThread { stopScan(); status.text = "BLE scan failed ($errorCode)" } }
    }
    private fun stopScan() { handler.removeCallbacksAndMessages(null); runCatching { scanner?.stopScan(callback) }; scanner = null }
    private fun connect(selectedAddress: String) {
        stop(); address = selectedAddress; reports.clear(); rows.clear(); chosen = null; characteristics.removeAllViews()
        session = NonHidGattSession(this, services = { services ->
            val candidates = mutableListOf<BluetoothGattCharacteristic>()
            services.forEach { service ->
                characteristics.add(text("Service ${service.uuid}${if (service.uuid == NonHidGattSession.HID) " (HID: restricted, skipped)" else ""}", Type.MONO, Ink.TEXT))
                service.characteristics.forEach { c ->
                    val usable = NonHidGattSession.allowed(service.uuid, c.uuid) && NonHidGattSession.notifying(c)
                    if (usable) {
                        candidates += c
                        val row = button("${c.uuid} · awaiting notifications") { chosen = c; learning = false; status.text = "Selected ${c.uuid}. Release every button, then start learning. Press and release once first if no resting report is shown." }
                        row.typeface = android.graphics.Typeface.MONOSPACE; row.textSize = 12f
                        rows[id(c)] = row; characteristics.add(row)
                    } else characteristics.add(text("  ${c.uuid} · properties 0x${c.properties.toString(16)} · skipped", Type.MONO))
                }
            }
            ambiguous.clear()
            candidates.groupBy { it.service.uuid to it.uuid }.filterValues { it.size > 1 }.keys.forEach { ambiguous.add(it) }
            candidates
        }, report = ::report, state = { connected, detail -> ready = connected; status.text = detail; if (!connected) learning = false })
        session?.connect(address, false)
    }
    private fun report(c: BluetoothGattCharacteristic, bytes: ByteArray) {
        val previous = reports.put(id(c), bytes)
        val changes = if (previous?.size == bytes.size) bytes.indices.filter { previous[it] != bytes[it] }.joinToString() else "length ${bytes.size}"
        rows[id(c)]?.text = "${c.uuid}\n${hex(bytes)}\nChanged byte offsets: $changes"
        if (chosen?.let(::id) != id(c)) return
        live.text = "Selected report (${bytes.size} bytes): ${hex(bytes)}"
        if (!learning) return
        val baseline = neutral ?: return
        if (bytes.size != baseline.size) { learning = false; status.text = "Variable report length: unsupported. Choose a fixed-length button report."; return }
        if (bytes.contentEquals(baseline)) {
            val press = pending ?: return
            val field = ControllerReportLearning.derive(baseline, press, letters[learned.size])
            pending = null
            if (field == null || learned.any { it.offset == field.offset && it.mask == field.mask && it.value == field.value }) {
                status.text = "Ambiguous / reused / multi-byte change. Retry ${letters[learned.size].uppercase()} alone; counters and encrypted reports cannot be learned."; return
            }
            learned += field; samples += press
            if (learned.size == letters.size) finishLearn() else status.text = "Press and release ${letters[learned.size].uppercase()}"
        } else if (pending == null) {
            pending = bytes.clone()
            status.text = "Release ${letters[learned.size].uppercase()} completely"
        } else if (!pending!!.contentEquals(bytes)) {
            pending = null; learning = false
            status.text = "Report changed while held. Counters/axes/repeating payloads are not a stable button report; restart with a different characteristic."
        }
    }
    private fun beginLearn() {
        val c = chosen
        val baseline = c?.let { reports[id(it)] }
        if (!ready || c == null || baseline == null || baseline.isEmpty() || baseline.size > 512) { status.text = "First select a subscribed characteristic with live fixed-length reports. Press/release a button to get a neutral report."; return }
        if (c.service.uuid to c.uuid in ambiguous) { status.text = "Duplicate service/characteristic UUIDs: this profile format cannot identify an instance. Choose another characteristic/mode."; return }
        neutral = baseline.clone(); pending = null; learned.clear(); samples.clear(); learning = true
        status.text = "Press and release ${letters[0].uppercase()}. Keep other buttons released."
    }
    private fun finishLearn() {
        learning = false
        val c = chosen ?: return
        val baseline = neutral ?: return
        // Whole-byte alternatives disambiguate enumerated hats that initially looked like bits.
        val fields = learned.map { field ->
            if (samples.indices.any { index -> field.active(samples[index]) != (letters[index] == field.letter) })
                field.copy(mask = 255, value = samples[letters.indexOf(field.letter)][field.offset].toInt() and 255)
            else field
        }
        val valid = fields.none { it.active(baseline) } && samples.indices.all { index -> fields.filter { it.active(samples[index]) }.map { it.letter } == listOf(letters[index]) }
        if (!valid) { status.text = "No unambiguous 16-button profile. Choose another characteristic/mode; this report cannot be learned safely."; return }
        runCatching {
            ControllerProfile(address, c.service.uuid, c.uuid, baseline.size, fields).save(this)
            getSharedPreferences("controller", Context.MODE_PRIVATE).edit().putString("source", "gatt").apply()
        }.onSuccess { status.text = "Saved all 16 buttons; vendor BLE source selected. Close probe to connect it. Test simultaneous buttons and locked screen on hardware." }
            .onFailure { status.text = "Profile rejected: ${it.message}" }
    }
    private fun stop() { stopScan(); session?.close(); session = null; ready = false; learning = false }
    override fun onStart() { super.onStart(); GlassesService.instance?.controller?.probing(true) }
    override fun onStop() { stop(); GlassesService.instance?.controller?.probing(false); super.onStop() }
}
