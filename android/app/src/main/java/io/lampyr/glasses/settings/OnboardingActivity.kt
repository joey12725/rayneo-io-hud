package io.lampyr.glasses.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.InputDevice
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.lampyr.glasses.BuildConfig
import io.lampyr.glasses.glasses.ConnectionState
import io.lampyr.glasses.glasses.GlassesSetupActivity
import io.lampyr.glasses.glasses.GlassesTransports
import io.lampyr.glasses.input.ControllerAccessibilityService
import io.lampyr.glasses.input.ControllerActivity
import io.lampyr.glasses.input.KeyMap
import io.lampyr.glasses.phone.*

/** What the setup checklist reads from the phone. Kept free of views so it can be tested and reused. */
object OnboardingChecks {
    const val OFFICIAL_APP = "com.rayneo.venus.pub"
    fun granted(context: Context, vararg permissions: String) =
        permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun bluetoothPermissions() =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun notifications(context: Context) = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    /** Colon-separated component list, as Android stores enabled accessibility services and notification listeners. */
    fun listed(setting: String?, packageName: String, className: String? = null) =
        setting.orEmpty().split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it) }
            .any { it.packageName == packageName && (className == null || it.className == className) }
    fun accessibility(context: Context) = listed(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        context.packageName, ControllerAccessibilityService::class.java.name)
    fun notificationAccess(context: Context) =
        listed(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"), context.packageName)
    fun battery(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    /** Name of an attached external keyboard-class device (the 8BitDo Micro in keyboard mode), if any. */
    fun externalKeyboard(): String? = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
        .firstOrNull { it.isExternal && !it.isVirtual && it.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD }?.name
    fun officialAppInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(OFFICIAL_APP, 0) }.isSuccess
    /** USB or wireless debugging; either lets the computer install builds and read logs. */
    fun usbDebugging(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 ||
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    fun glassesChosen(context: Context) = !context.getSharedPreferences("glasses", Context.MODE_PRIVATE).getString("glasses.address", null).isNullOrBlank()
    fun glassesConnected() = GlassesTransports.real?.connectionState?.value == ConnectionState.CONNECTED
    /** True once these glasses have completed a real connection, not merely been chosen from a scan. */
    fun glassesLinked(context: Context) = glassesConnected() ||
        context.getSharedPreferences("glasses", Context.MODE_PRIVATE).getBoolean(GlassesTransports.LINKED, false)

    /** The steps that count towards "set up": every one the phone can verify by itself. */
    fun required(context: Context, keys: KeyMap): List<Boolean> = listOf(
        notifications(context), granted(context, *bluetoothPermissions()), granted(context, Manifest.permission.RECORD_AUDIO),
        accessibility(context), notificationAccess(context), battery(context),
        externalKeyboard() != null || keys.hasLearnedController, keys.hasLearnedController, glassesLinked(context))
    /** Steps done and steps in total. */
    fun progress(context: Context, keys: KeyMap): Pair<Int, Int> = required(context, keys).let { it.count { done -> done } to it.size }
}

/** The setup checklist. Each step shows its live state; unfinished steps offer the one button that fixes them. */
class OnboardingActivity : Activity() {
    private class Step(val pill: Pill, val action: View?, val counts: Boolean, val check: () -> Triple<Boolean?, String, Tone>)
    private val steps = mutableListOf<Step>()
    private lateinit var count: TextView
    private lateinit var meter: Meter
    private lateinit var keys: KeyMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = KeyMap(this)
        getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("shown", true).apply()
        val me = this
        fun open(intent: Intent) { runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show() } }
        fun appInfo() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        fun yesNo(done: Boolean, yes: String, no: String) = Triple<Boolean?, String, Tone>(done, if (done) yes else no, if (done) Tone.GOOD else Tone.WARN)

        screen("Set up", "Work down the list and come back here after each step. None of it needs RayNeo's own app.") {
            add(context.card(10, Ink.GREEN_DIM) {
                count = add(context.text("", Type.HEADING))
                meter = add(Meter(context))
            })
            /** [check] returns done (null when the phone cannot tell), the state word and its colour. */
            fun step(title: String, detail: String, action: String?, counts: Boolean = true, run: () -> Unit = {}, check: () -> Triple<Boolean?, String, Tone>) {
                add(context.card(10) {
                    val pill = Pill(context)
                    add(context.row(10) { add(context.text(title, Type.HEADING), 1f); add(pill) })
                    add(context.text(detail, Type.SMALL))
                    val button = action?.let { add(context.button(it, Btn.TONAL) { run() }, space = dp(14)) }
                    steps += Step(pill, button, counts, check)
                })
            }

            section("Permissions")
            step("Notifications", "Keeps the companion running in the background.", "Allow notifications",
                run = { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }) {
                yesNo(OnboardingChecks.notifications(me), "Allowed", "Needed")
            }
            step("Bluetooth", "Finds and connects to the glasses.", "Allow Bluetooth",
                run = { requestPermissions(OnboardingChecks.bluetoothPermissions(), 2) }) {
                yesNo(OnboardingChecks.granted(me, *OnboardingChecks.bluetoothPermissions()), "Allowed", "Needed")
            }
            step("Microphone", "For talking to ${io.lampyr.glasses.settings.Names.assistant} and dictating through the glasses.", "Allow microphone",
                run = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3) }) {
                yesNo(OnboardingChecks.granted(me, Manifest.permission.RECORD_AUDIO), "Allowed", "Needed")
            }
            step("If Android says \"Restricted setting\"",
                "This app was installed from a file, so Android may block the next two switches. If it does: press OK, open app info below, " +
                    "tap ⋮ at the top right, then \"Allow restricted settings\". That menu item only appears after you have seen the message once.",
                "Open app info", counts = false, run = { open(appInfo()) }) { Triple(null, "Only if asked", Tone.IDLE) }
            step("Controller in the background",
                "Lets the controller work while the phone is locked or another app is open. Choose RayNeo Companion in the list and switch it on.",
                "Open accessibility settings", run = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                yesNo(OnboardingChecks.accessibility(me), "On", "Off")
            }
            step("Notifications on the lens", "Shows your notifications and what is playing. Switch on RayNeo Companion.",
                "Open notification access", run = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                yesNo(OnboardingChecks.notificationAccess(me), "On", "Off")
            }
            step("Keep running with the screen off", "Stops Android closing the companion to save battery.", "Allow background use",
                run = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }) {
                yesNo(OnboardingChecks.battery(me), "Allowed", "May be stopped")
            }

            section("Controller")
            step("Pair the 8BitDo Micro",
                "Slide its mode switch to K (keyboard). Hold the pair button until the light blinks quickly, then choose it in Bluetooth settings.",
                "Open Bluetooth settings", run = { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                yesNo(OnboardingChecks.externalKeyboard() != null || keys.hasLearnedController, "Paired", "Not paired")
            }
            step("Teach the app its buttons", "Press each of the 16 buttons once as the app names it. Takes about 20 seconds.",
                "Teach buttons", run = { open(Intent(me, ControllerActivity::class.java)) }) {
                yesNo(keys.hasLearnedController, "Learned", "Not yet")
            }

            section("Glasses")
            step("Leave RayNeo's own app out",
                "Only one app can hold the glasses. Don't install or open the official app, and decline any firmware update. " +
                    "If you already used it, unbind the glasses there and forget them in Bluetooth settings.",
                null, counts = false) {
                if (OnboardingChecks.officialAppInstalled(me)) Triple(false, "Installed. Keep it closed", Tone.WARN) else Triple(true, "Not installed", Tone.GOOD)
            }
            step("Pair and connect",
                "Hold the button on the glasses for about 5 seconds until the blue light blinks, then scan, choose them and accept Android's pairing prompt.",
                "Find my glasses", run = { open(Intent(me, GlassesSetupActivity::class.java)) }) {
                when {
                    OnboardingChecks.glassesConnected() -> Triple(true, "Connected", Tone.GOOD)
                    OnboardingChecks.glassesLinked(me) -> Triple(true, "Paired", Tone.GOOD)
                    OnboardingChecks.glassesChosen(me) -> Triple(false, "Chosen, not connected yet", Tone.WARN)
                    else -> Triple(false, "Not paired", Tone.WARN)
                }
            }

            if (BuildConfig.DEBUG) {
                section("Remote help (optional)")
                step("Let the computer update the app",
                    "Settings → About phone → tap Build number seven times. Then in Developer options switch on Wireless debugging, tap " +
                        "\"Pair device with pairing code\" and give the code to the computer. Phone and computer must be on the same Wi-Fi.",
                    "Open developer options", counts = false, run = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) {
                    OnboardingChecks.usbDebugging(me).let { Triple(it, if (it) "On" else "Off", if (it) Tone.GOOD else Tone.IDLE) }
                }
            }
            add(context.button("Done", Btn.PRIMARY) { finish() }, space = dp(26))
        }
    }

    private fun refresh() {
        var done = 0; var total = 0
        steps.forEach { step ->
            val (ok, word, tone) = runCatching { step.check() }.getOrElse { Triple(null, "Unknown", Tone.IDLE) }
            step.pill.show(word, tone)
            // A finished step folds down to its title and state, so what is left to do stands out.
            if (ok != null) step.action?.visibility = if (ok) View.GONE else View.VISIBLE
            if (step.counts) { total++; if (ok == true) done++ }
        }
        count.text = if (done == total) "All set" else "$done of $total steps done"
        meter.fraction = if (total == 0) 0f else done.toFloat() / total
    }

    override fun onResume() { super.onResume(); keys.refresh(); refresh() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refresh() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Android stops showing the dialog after two refusals; send the owner to the app's permission page instead.
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED } && permissions.none { shouldShowRequestPermissionRationale(it) })
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        io.lampyr.glasses.service.GlassesService.instance?.refreshForegroundAccess()
        refresh()
    }
    override fun onDestroy() { keys.close(); super.onDestroy() }

    companion object {
        /** Opens the checklist once, the first time the app is launched. */
        fun showOnFirstRun(activity: Activity) {
            if (!activity.getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("shown", false))
                activity.startActivity(Intent(activity, OnboardingActivity::class.java))
        }
    }
}
