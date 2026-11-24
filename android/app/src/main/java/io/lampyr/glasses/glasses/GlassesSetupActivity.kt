package io.lampyr.glasses.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*
import java.io.Closeable

/** Pairing, connection and the two quick hardware checks. Has no dependency on the gateway or the UI renderer. */
class GlassesSetupActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scan: Closeable? = null
    private val devices = linkedMapOf<String, RayNeoDevice>()
    private var selected: RayNeoDevice? = null
    private lateinit var state: Pill
    private lateinit var headline: TextView
    private lateinit var battery: FactRow
    private lateinit var firmware: FactRow
    private lateinit var saved: FactRow
    private lateinit var official: FactRow
    private lateinit var problem: Note
    private lateinit var scanNote: Note
    private lateinit var choices: Stack
    private lateinit var level: Meter
    private lateinit var micButton: TextView
    private lateinit var enabled: ToggleRow
    private lateinit var technical: TextView
    private val testingMic get() = GlassesSetupActions.testingMic
    private var pendingAction: (() -> Unit)? = null
    private val prefs by lazy { getSharedPreferences("glasses", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Glasses") {
            add(context.card(14) {
                add(context.row(10) {
                    headline = add(context.text("", Type.TITLE), 1f)
                    state = add(Pill(context))
                })
                battery = add(context.fact("Battery")); firmware = add(context.fact("Firmware")); saved = add(context.fact("Saved glasses"))
                official = add(context.fact("RayNeo's own app"))
                problem = add(context.note())
                add(context.divider())
                enabled = add(context.toggle("Use these glasses", "Off keeps them paired but sends nothing to the lens", prefs.getBoolean("glasses.enabled", false)) { on ->
                    if (on) withPermissions { prefs.edit().putBoolean("glasses.enabled", true).apply(); ensureService(); GlassesTransports.reconnect() }
                    else { prefs.edit().putBoolean("glasses.enabled", false).apply(); GlassesSetupActions.micTest(false) }
                })
            })

            section("Pair")
            add(context.card(14) {
                add(context.text("Hold the button on the glasses for about 5 seconds, until the blue light blinks. Then scan.", Type.SMALL))
                add(context.button("Scan for glasses", Btn.PRIMARY) { withPermissions { startScan() } })
                scanNote = add(context.note())
                choices = add(context.column(8))
                add(context.button("Connect") { withPermissions { connect() } })
            })

            section("Check they work")
            add(context.card(14) {
                add(context.button("Show the test pattern") {
                    val real = GlassesTransports.real
                    if (real?.connectionState?.value != ConnectionState.CONNECTED) scanNote.show("Connect your glasses first", Tone.WARN)
                    else { GlassesSetupActions.testPattern(real); scanNote.show("Sent. You should see seven rows of text, ending in #", Tone.GOOD) }
                })
                add(context.divider())
                micButton = add(context.button("Test the microphone") {
                    try { GlassesSetupActions.micTest(testingMic == null) }
                    catch (e: Exception) { scanNote.show(e.message, Tone.BAD) }
                })
                level = add(Meter(context))
                add(context.text("Speak and the bar should move. Nothing is saved. Stop the test before dictating.", Type.SMALL))
            })

            section("More")
            add(context.card(8) {
                add(context.navRow("display", "Display lab", "Display mode, grey levels and firmware experiments") {
                    startActivity(Intent(this@GlassesSetupActivity, DisplayLabActivity::class.java))
                })
            })
            technical = add(context.text("", Type.MONO), space = dp(18))
            add(context.button("Disconnect and stop using these glasses", Btn.DANGER) {
                prefs.edit().putBoolean("glasses.enabled", false).apply(); enabled.isChecked = false; GlassesSetupActions.micTest(false)
            }, space = dp(22))
        }
        scope.launch {
            while (isActive) {
                val summary = Summary.glasses(this@GlassesSetupActivity)
                val real = GlassesTransports.real
                val status = real?.deviceStatusFlow?.value
                headline.text = "RayNeo iO"; state.show(summary.text, summary.tone)
                battery.show(status?.battery?.let { "$it%" } ?: "—")
                firmware.show(status?.firmware ?: "—")
                saved.show(prefs.getString("glasses.address", null) ?: "None yet")
                val o = Summary.officialApp(this@GlassesSetupActivity)
                official.show(o.text, when (o.tone) { Tone.WARN -> Ink.AMBER; Tone.GOOD -> Ink.GREEN; else -> Ink.TEXT })
                val error = real?.lastError?.value?.toString()
                // While searching, the summary line already carries the last error; a red box would claim it gave up.
                val shown = if (summary.text == "Searching") summary.detail else error?.takeIf { summary.tone != Tone.GOOD && it != "null" }
                problem.show(listOfNotNull(shown, o.detail.takeIf { o.tone == Tone.WARN }).joinToString("\n").ifBlank { null },
                    if (summary.text == "Searching") Tone.WARN else Tone.BAD)
                technical.text = listOf(real?.raster?.note?.value.orEmpty(), real?.audioMode?.value.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
                technical.visibility = if (technical.text.isBlank()) View.GONE else View.VISIBLE
                level.fraction = (testingMic?.levels?.value ?: 0f) * 5f
                if (testingMic?.isCapturing() == false) GlassesSetupActions.micTest(false)
                micButton.text = if (testingMic == null) "Test the microphone" else "Stop the microphone test"
                enabled.isChecked = prefs.getBoolean("glasses.enabled", false)
                delay(250)
            }
        }
    }

    private fun connect() {
        selected?.let { GlassesSetupActions.select(this, it) }
        if (prefs.getString("glasses.address", null).isNullOrBlank()) { scanNote.show("Scan and choose your glasses first", Tone.WARN); return }
        scan?.close(); scan = null
        prefs.edit().putBoolean("glasses.enabled", true).apply()
        enabled.isChecked = true
        ensureService(); GlassesTransports.reconnect()
        scanNote.show("Connecting. Accept Android's pairing prompt if it appears", Tone.IDLE)
    }
    private fun ensureService() { startForegroundService(Intent(this, GlassesService::class.java)) }

    private fun paintChoices() {
        choices.removeAllViews()
        devices.values.forEach { device ->
            val on = device == selected
            choices.add(row(12) {
                background = if (on) shape(Ink.GREEN_DIM, 14, Ink.GREEN) else pressable(Ink.RAISED, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12)); isClickable = true
                add(context.icon("glasses", if (on) Ink.GREEN else Ink.MUTED))
                add(context.column(3) { add(context.text(device.name, Type.HEADING)); add(context.text(device.classicAddress ?: device.bleAddress, Type.MONO)) }, 1f)
                if (on) add(context.icon("check", Ink.GREEN, 20))
                setOnClickListener { selected = device; paintChoices() }
            })
        }
    }
    private fun startScan() {
        scan?.close(); devices.clear(); selected = null; paintChoices()
        scanNote.show("Scanning for 12 seconds…")
        try {
            scan = BleAuthenticator(this).scan({ device -> runOnUiThread {
                if (devices.put(device.bleAddress, device) == null) {
                    if (devices.size == 1) selected = device
                    paintChoices()
                    scanNote.show(if (devices.size == 1) "Found your glasses. Tap Connect" else "Found ${devices.size}. Choose one, then Connect", Tone.GOOD)
                }
            } }, { message -> runOnUiThread { scanNote.show(message) } })
        } catch (e: Exception) { scanNote.show(e.message, Tone.BAD) }
    }
    private fun withPermissions(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingAction = action; requestPermissions(missing.toTypedArray(), 42) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            val action = pendingAction; pendingAction = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
            else { enabled.isChecked = prefs.getBoolean("glasses.enabled", false); scanNote.show("Bluetooth permission is needed to scan and connect (Location on Android 10 and 11)", Tone.WARN) }
        }
    }
    override fun onStop() { scan?.close(); scan = null; GlassesSetupActions.micTest(false); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
