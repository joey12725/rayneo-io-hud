package io.lampyr.glasses.input

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.lampyr.glasses.input.shizuku.AndroidShizukuFacade
import io.lampyr.glasses.input.shizuku.LinuxKeyMap
import io.lampyr.glasses.phone.*
import io.lampyr.glasses.service.GlassesService
import io.lampyr.glasses.service.ServiceAccess
import io.lampyr.glasses.settings.KeyLearning
import io.lampyr.glasses.settings.OnboardingChecks
import kotlinx.coroutines.*

/** Everything about the hand controller: its state, teaching the 16 buttons, and how presses reach the app. */
class ControllerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("controller", Context.MODE_PRIVATE) }
    private lateinit var keys: KeyMap
    private lateinit var headline: TextView
    private lateinit var state: Pill
    private lateinit var reading: FactRow
    private lateinit var inBackground: FactRow

    // Teaching
    private val buttons = listOf("up", "down", "left", "right", "y", "b", "x", "a", "l", "l2", "r", "r2", "minus", "plus", "star", "heart")
    private val spoken = mapOf("up" to "D-pad up", "down" to "D-pad down", "left" to "D-pad left", "right" to "D-pad right", "y" to "Y", "b" to "B", "x" to "X", "a" to "A",
        "l" to "L (front left)", "l2" to "L2 (back left)", "r" to "R (front right)", "r2" to "R2 (back right)", "minus" to "Minus (−)", "plus" to "Plus (+)", "star" to "Star (★)", "heart" to "Heart (♥)")
    private val learned = linkedMapOf<Int, String>()
    private var learnIndex = -1
    private var learningDevice: android.view.InputDevice? = null
    private lateinit var prompt: TextView
    private lateinit var promptDetail: TextView
    private lateinit var progress: Meter
    private lateinit var teach: TextView
    private lateinit var skip: TextView

    // Sources
    private val sourceValues = listOf("accessibility", "gatt", "shizuku")
    private val sourceRows = mutableListOf<Stack>()
    private lateinit var shizuku: Stack
    private lateinit var shizukuFacts: List<FactRow>
    private lateinit var shizukuNote: Note
    private var requestPermissionWhenReady = false
    private val sourceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "source") paintSources() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = KeyMap(this)
        screen("Controller") {
            add(context.card(14) {
                add(context.row(10) { headline = add(context.text("", Type.TITLE), 1f); state = add(Pill(context)) })
                reading = add(context.fact("Presses read by")); inBackground = add(context.fact("Works in the background"))
            })

            section("Teach the buttons")
            add(context.card(12) {
                prompt = add(context.text("", Type.TITLE))
                promptDetail = add(context.text("", Type.SMALL))
                progress = add(Meter(context), space = dp(14))
                teach = add(context.button("Start", Btn.PRIMARY) { beginLearning() }, space = dp(14))
                skip = add(context.button("Skip this button", Btn.GHOST) { skipButton() })
            })

            section("How presses reach the app")
            add(context.card(10) {
                listOf(
                    "Keyboard" to "The simple way. With the screen off, the first press only wakes the phone and is lost.",
                    "Controller's own Bluetooth channel" to "Only after the Bluetooth probe below has found and learned a usable channel.",
                    "Shizuku" to "Works with the screen off and keeps presses away from other apps. Needs the free Shizuku app.",
                ).forEachIndexed { index, (title, detail) ->
                    sourceRows += add(context.row(12) {
                        setPadding(dp(14), dp(13), dp(14), dp(13)); isClickable = true
                        add(context.column(4) { add(context.text(title, Type.HEADING)); add(context.text(detail, Type.SMALL)) }, 1f)
                        add(context.icon("check", Ink.GREEN, 20))
                        setOnClickListener { prefs.edit().putString("source", sourceValues[index]).apply() }
                    })
                }
            })

            shizuku = add(context.card(12) {
                add(context.text("Shizuku", Type.HEADING))
                shizukuFacts = listOf("Installed", "Running", "Permission", "Controller", "Capture", "Last press").map { add(context.fact(it)) }
                shizukuNote = add(context.note())
                add(context.row(10) {
                    add(context.button("Open Shizuku") {
                        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                        runCatching { startActivity(intent) }
                    }, 1f)
                    add(context.button("Allow and start", Btn.PRIMARY) { requestPermissionWhenReady = true; startShizuku() }, 1f)
                })
                add(context.row(10) {
                    add(context.button("Test presses") {
                        GlassesService.instance?.controller?.shizuku?.let { source ->
                            source.testing = !source.testing
                            Toast.makeText(context, if (source.testing) "Testing. Presses do nothing until you tap Test again" else "Test finished", Toast.LENGTH_SHORT).show()
                        }
                    }, 1f)
                    add(context.button("Stop", Btn.DANGER) { prefs.edit().putBoolean("shizukuEnabled", false).apply() }, 1f)
                })
                val steps = context.text(
                    "1. Install Shizuku from Google Play or its website.\n" +
                        "2. In Developer options, switch on USB debugging and Wireless debugging. Pair once following Shizuku's instructions, then tap Start inside Shizuku. Android 10 needs a computer.\n" +
                        "3. Teach the buttons above first, then tap Allow and start here.\n\n" +
                        "After every phone restart, start Shizuku again and then open this app. Some phones block this entirely; the app then falls back to Keyboard.", Type.SMALL).apply { visibility = View.GONE }
                add(context.button("How to set up Shizuku", Btn.GHOST) { steps.visibility = if (steps.visibility == View.GONE) View.VISIBLE else View.GONE })
                add(steps)
            })

            section("In your pocket")
            add(context.card(12) {
                add(context.text("Pocket mode shows a black screen at lowest brightness so the controller keeps working with the phone put away. Hold three fingers for 2 seconds, or press a volume key, to leave it.", Type.SMALL))
                add(context.button("Start pocket mode") { startActivity(PocketActivity.intent(this@ControllerActivity)) })
                add(context.divider())
                add(context.toggle("Start it by itself", "When the locked phone wakes with the controller connected", prefs.getBoolean("pocketOnWake", false)) {
                    prefs.edit().putBoolean("pocketOnWake", it).apply()
                })
            })

            section("Advanced")
            add(context.card(4) {
                add(context.navRow("bluetooth", "Bluetooth probe", "Look for a direct button channel on the controller") { startActivity(Intent(this@ControllerActivity, ControllerProbeActivity::class.java)) })
                add(context.divider())
                add(context.navRow("refresh", "Restart when the controller appears", "Lets Android wake the app for the controller (Android 12+)") { startActivity(Intent(this@ControllerActivity, ControllerAssociationActivity::class.java)) })
            })
        }
        paintSources(); paintLearning(null)
        prefs.registerOnSharedPreferenceChangeListener(sourceListener)
        scope.launch { while (isActive) { refresh(); delay(1_000) } }
    }

    private fun startShizuku() {
        ServiceAccess.start(this)
        prefs.edit().putBoolean("shizukuEnabled", true).putString("source", "shizuku").apply()
    }

    private fun paintSources() {
        val current = sourceValues.indexOf(prefs.getString("source", "accessibility")).coerceAtLeast(0)
        sourceRows.forEachIndexed { index, row ->
            val on = index == current
            row.background = if (on) shape(Ink.GREEN_DIM, 14, Ink.GREEN) else pressable(Ink.RAISED, 14)
            row.getChildAt(1).visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        shizuku.visibility = if (current == 2) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        keys.refresh()
        val service = GlassesService.instance
        val summary = Summary.controller(this, service, keys)
        headline.text = OnboardingChecks.externalKeyboard() ?: "8BitDo Micro"; state.show(summary.text, summary.tone)
        reading.show(when (service?.controller?.sourceName) { "shizuku" -> "Shizuku"; "gatt" -> "Its own Bluetooth channel"; null -> "Starting…"; else -> "Keyboard" })
        val bound = ControllerAccessibilityService.bound
        inBackground.show(if (bound) "Yes" else if (OnboardingChecks.accessibility(this)) "Starting…" else "No. Turn on in Setup guide", if (bound) Ink.GREEN else Ink.AMBER)
        if (learnIndex < 0) paintLearning(null)

        val source = service?.controller?.shizuku
        if (source != null && requestPermissionWhenReady) { requestPermissionWhenReady = false; source.requestPermission() }
        source?.refresh()
        val s = source?.state ?: AndroidShizukuFacade.availability(this)
        fun FactRow.yes(ok: Boolean) = show(if (ok) "Yes" else "No", if (ok) Ink.GREEN else Ink.AMBER)
        shizukuFacts[0].yes(s.installed); shizukuFacts[1].yes(s.running); shizukuFacts[2].yes(s.granted)
        shizukuFacts[3].show(s.controller.ifBlank { "Not found" })
        shizukuFacts[4].show(if (s.nodes.isEmpty()) "Not available" else if (s.exclusive) "Exclusive" else "Shared. Screen will still wake", if (s.exclusive) Ink.GREEN else Ink.AMBER)
        shizukuFacts[5].show(source?.lastEvent?.toString() ?: "—")
        shizukuNote.show(if (source != null) s.detail.takeIf { it.isNotBlank() } else null, if (source?.testing == true) Tone.WARN else Tone.IDLE)
    }

    // ---- Teaching the 16 buttons ----

    private fun beginLearning() {
        learned.clear(); learningDevice = null; learnIndex = 0
        GlassesService.instance?.controller?.shizuku?.testing = false
        if (GlassesService.instance?.controller?.shizuku?.state?.ready == true) { KeyLearning.consume = null; KeyLearning.raw = ::learnRaw }
        else { KeyLearning.raw = null; KeyLearning.consume = ::learn }
        paintLearning(null)
    }
    private fun paintLearning(message: String?) {
        if (::skip.isInitialized) paintSkip()
        when {
            learnIndex in buttons.indices -> {
                prompt.text = "Press ${spoken[buttons[learnIndex]]}"; prompt.setTextColor(Ink.GREEN)
                promptDetail.text = message ?: ("Button ${learnIndex + 1} of ${buttons.size}" +
                    if (buttons[learnIndex] in KeyMap.OPTIONAL) ". If pressing it does nothing, skip it; B on Home opens Apps instead." else "")
                progress.fraction = learnIndex.toFloat() / buttons.size; teach.text = "Start again"
            }
            keys.hasLearnedController || message != null -> {
                prompt.text = "Buttons learned"; prompt.setTextColor(Ink.TEXT)
                promptDetail.text = message ?: "Teach them again if you change controller or its mode."
                progress.fraction = 1f; teach.text = "Teach again"
            }
            else -> {
                prompt.text = "Not taught yet"; prompt.setTextColor(Ink.TEXT)
                promptDetail.text = "Connect the controller, tap Start, then press each button as it is named."
                progress.fraction = 0f; teach.text = "Start"
            }
        }
    }
    private fun paintSkip() { skip.visibility = if (learnIndex in buttons.indices && buttons[learnIndex] in KeyMap.OPTIONAL) View.VISIBLE else View.GONE }
    /** Only optional buttons can be skipped; the rest of the app needs every other button. */
    private fun skipButton() {
        if (learnIndex !in buttons.indices || buttons[learnIndex] !in KeyMap.OPTIONAL) return
        learnIndex++
        if (learnIndex == buttons.size) completeLearning() else paintLearning(null)
    }
    /** Saves what was learned. Keyboard learning also remembers which device it came from; Shizuku learning reads raw codes. */
    private fun completeLearning() {
        if (KeyLearning.raw != null) { keys.replace(learned); finishLearning("Saved for Shizuku. The controller is ready to use."); return }
        val device = learningDevice ?: run { finishLearning("No button was pressed, so nothing was saved."); return }
        keys.rememberController(device); keys.replace(learned)
        if (prefs.getString("source", "accessibility") != "shizuku") prefs.edit().putString("source", "accessibility").apply()
        finishLearning("Saved. The controller is ready to use.")
    }
    private fun finishLearning(message: String) { learnIndex = -1; KeyLearning.consume = null; KeyLearning.raw = null; keys.refresh(); paintLearning(message) }

    private fun learn(event: KeyEvent): Boolean {
        if (learnIndex !in buttons.indices) return false
        val device = event.device ?: return false
        if (!device.isExternal || device.isVirtual) return false
        if (learningDevice == null && (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)) return false
        learningDevice?.let { if (it.descriptor != device.descriptor || it.vendorId != device.vendorId || it.productId != device.productId) return false }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        val code = if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode > 0) LinuxKeyMap.bindingCode(event.scanCode) else event.keyCode
        if (learned.containsKey(code)) { paintLearning("That button is already used. Try again"); return true }
        learningDevice = device
        learned[code] = buttons[learnIndex++]
        if (learnIndex == buttons.size) completeLearning() else paintLearning(null)
        return true
    }
    private fun learnRaw(code: Int, value: Int): Boolean {
        if (learnIndex !in buttons.indices) return false
        if (value != 1) return true
        if (code in learned) { runOnUiThread { paintLearning("${LinuxKeyMap.label(code)} is already used. Try again") }; return true }
        learned[code] = buttons[learnIndex++]
        runOnUiThread { if (learnIndex == buttons.size) completeLearning() else paintLearning(null) }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (KeyLearning.consume?.invoke(event) == true) true else super.dispatchKeyEvent(event)
    override fun onResume() { super.onResume(); ServiceAccess.resumed(this) }
    override fun onPause() {
        ServiceAccess.paused(this)
        KeyLearning.consume = null; KeyLearning.raw = null
        if (learnIndex >= 0) { learnIndex = -1; paintLearning(null) }
        super.onPause()
    }
    override fun onDestroy() {
        GlassesService.instance?.controller?.shizuku?.testing = false
        prefs.unregisterOnSharedPreferenceChangeListener(sourceListener)
        keys.close(); KeyLearning.consume = null; KeyLearning.raw = null; scope.cancel(); super.onDestroy()
    }
}
