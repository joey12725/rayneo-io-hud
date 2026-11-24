package io.lampyr.glasses.phone

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.lampyr.glasses.BuildConfig
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.calls.FakeCalls
import io.lampyr.glasses.data.GatewayClient
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.service.GlassesService
import io.lampyr.glasses.settings.AppSettings
import io.lampyr.glasses.settings.OnboardingActivity
import io.lampyr.glasses.settings.OnboardingChecks
import kotlinx.coroutines.*

/** One-line readings of the hardware, shared by every screen that reports them. */
object Summary {
    class Line(val text: String, val tone: Tone, val detail: String = "")
    fun glasses(context: Context): Line {
        val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val chosen = !prefs.getString("glasses.address", null).isNullOrBlank()
        val real = GlassesTransports.real
        val status = real?.deviceStatusFlow?.value
        val facts = listOfNotNull(status?.battery?.let { "Battery $it%" }, status?.firmware?.let { "Firmware $it" }).joinToString(" · ")
        return when {
            !chosen -> Line("Not set up", Tone.IDLE, "Pair your glasses to get started")
            !prefs.getBoolean("glasses.enabled", false) -> Line("Turned off", Tone.IDLE, "Saved, but not in use")
            real?.connectionState?.value == ConnectionState.CONNECTED -> Line("Connected", Tone.GOOD, facts.ifBlank { "Reading battery…" })
            // Retrying by itself (it never gives up): say so, rather than "Not connected" with a stale error.
            real?.retry?.value?.searching == true -> Line("Searching", Tone.WARN, ReconnectPolicy.detail(real.retry.value))
            real?.connectionState?.value == ConnectionState.CONNECTING -> Line("Connecting", Tone.WARN, "Looking for your glasses…")
            else -> Line("Not connected", Tone.BAD, real?.lastError?.value?.toString()?.take(90) ?: "Out of range or switched off")
        }
    }
    /** RayNeo's own app competes for the glasses link; warn while it can use Bluetooth (we never block it ourselves). */
    fun officialApp(context: Context): Line {
        val health = OfficialApp.health(context)
        val tone = when (health.level) { OfficialApp.Level.WARN -> Tone.WARN; OfficialApp.Level.INFO -> Tone.IDLE; OfficialApp.Level.NONE -> Tone.GOOD }
        return Line(health.text, tone, health.detail)
    }
    fun controller(context: Context, service: GlassesService?, keys: KeyMap): Line {
        val name = OnboardingChecks.externalKeyboard()
        return when {
            service?.controller?.connected == true -> Line("Connected", Tone.GOOD, name ?: "Ready")
            keys.hasLearnedController -> Line("Not connected", Tone.WARN, "Buttons learned. Switch the controller on")
            name != null -> Line("Needs teaching", Tone.WARN, "$name is paired. Teach the app its buttons")
            else -> Line("Not paired", Tone.IDLE, "Pair the 8BitDo Micro in keyboard mode")
        }
    }
}

class HomePage(activity: MainActivity) : Page(activity) {
    private val lens = LensView(activity)
    private val keys = KeyMap(activity)
    private val settings = AppSettings(activity)
    private val headline = activity.text("", Type.DISPLAY)
    private val state = Pill(activity)
    private val setupCount = activity.text("", Type.SMALL, Ink.TEXT)
    private val setupMeter = Meter(activity)
    private lateinit var setup: Stack
    private val glasses = activity.navRow("glasses", "Glasses", "") { open(GlassesSetupActivity::class.java) }
    private val controller = activity.navRow("controller", "Controller", "") { open(ControllerActivity::class.java) }
    private val data = activity.navRow("data", "Data", "") { activity.show(3) }
    private val mic = activity.fact("Microphone")
    private val inBackground = activity.fact("Controller in background")
    private val notifications = activity.fact("Notifications on the lens")
    private val battery = activity.fact("Runs with screen off")
    private val official = activity.fact("RayNeo's own app")
    private val officialNote = activity.note()
    private var live: Boolean? = null
    private var liveRead = 0L

    private fun open(target: Class<*>) = activity.startActivity(Intent(activity, target))

    override val view: View = activity.column(14) {
        setPadding(dp(18), dp(22), dp(18), dp(28))
        add(context.row {
            add(context.column(6) { add(context.text("RayNeo iO", Type.LABEL)); add(headline) }, 1f)
            add(state)
        })
        add(context.card(12) {
            add(lens)
            lens.setOnClickListener { activity.show(1) }
            add(context.row {
                add(context.text("What the lens is showing", Type.SMALL), 1f)
                val modes = context.segments(listOf("Picture", "Text"), if (settings.previewText) 1 else 0) { index ->
                    settings.previewText = index == 1; activity.service?.updateDisplay()
                }
                addView(modes, android.widget.LinearLayout.LayoutParams(dp(150), -2))
            })
        })
        setup = add(context.card(12, Ink.GREEN_DIM) {
            add(context.text("Finish setting up", Type.HEADING))
            add(setupCount, space = dp(6))
            add(setupMeter)
            add(context.button("Continue setup", Btn.PRIMARY) { open(OnboardingActivity::class.java) })
        })
        add(context.card(4) { add(glasses); add(context.divider()); add(controller); add(context.divider()); add(data) })
        add(context.row(12) {
            add(context.button("Pocket mode", Btn.TONAL) { activity.startActivity(PocketActivity.intent(activity)) }, 1f)
            add(context.button("Test the lens", Btn.TONAL) {
                val real = GlassesTransports.real
                if (real?.connectionState?.value == ConnectionState.CONNECTED) { GlassesSetupActions.testPattern(real); Toast.makeText(activity, "Seven test rows sent. Check the lens", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(activity, "Connect your glasses first", Toast.LENGTH_SHORT).show()
            }, 1f)
        })
        section("This phone")
        add(context.card(14) { add(mic); add(inBackground); add(notifications); add(battery); add(official); add(officialNote) })
    }

    override fun frame(frame: Frame, locked: Boolean) = lens.show(frame, locked)

    override fun refresh() {
        keys.refresh()
        val service = activity.service
        val g = Summary.glasses(activity); val c = Summary.controller(activity, service, keys)
        headline.text = when (g.tone) { Tone.GOOD -> "Glasses on"; Tone.WARN -> if (g.text == "Searching") "Searching for glasses" else "Connecting…"; Tone.BAD -> "Glasses offline"; Tone.IDLE -> if (g.text == "Not set up") "Welcome" else "Glasses off" }
        state.show(g.text, g.tone)
        glasses.detail.text = g.detail; glasses.tone(g.tone); glasses.trailing.text = ""
        controller.detail.text = c.detail; controller.tone(c.tone)
        val now = android.os.SystemClock.elapsedRealtime()
        if (live == null || now - liveRead > 5_000) { live = runCatching { settings.live }.getOrDefault(false); liveRead = now }
        data.detail.text = if (live == true) "Live from your gateway" else "Demo data. Add your gateway in Settings"
        data.tone(if (live == true) Tone.GOOD else Tone.IDLE)
        val (done, total) = OnboardingChecks.progress(activity, keys)
        setup.visibility = if (done == total) View.GONE else View.VISIBLE
        setupCount.text = "$done of $total steps done"; setupMeter.fraction = done.toFloat() / total
        fun FactRow.yes(ok: Boolean, good: String, bad: String) = show(if (ok) good else bad, if (ok) Ink.GREEN else Ink.AMBER)
        mic.yes(service?.microphoneReady == true, "Ready", "Not allowed")
        inBackground.yes(OnboardingChecks.accessibility(activity), "On", "Off")
        notifications.yes(OnboardingChecks.notificationAccess(activity), "On", "Off")
        battery.yes(OnboardingChecks.battery(activity), "Yes", "May be stopped")
        val o = Summary.officialApp(activity)
        official.show(o.text, when (o.tone) { Tone.WARN -> Ink.AMBER; Tone.GOOD -> Ink.GREEN; else -> Ink.TEXT })
        officialNote.show(o.detail.takeIf { o.tone == Tone.WARN }, Tone.WARN)
    }
}

class RemotePage(activity: MainActivity) : Page(activity) {
    private val lens = LensView(activity)
    private val cells = linkedMapOf<String, Pair<TextView, TextView>>()
    private val names = linkedMapOf(
        "up" to "▲", "down" to "▼", "left" to "◀", "right" to "▶", "y" to "Y", "b" to "B", "x" to "X", "a" to "A",
        "l" to "L", "l2" to "L2", "r" to "R", "r2" to "R2", "minus" to "−", "plus" to "+", "star" to "★", "heart" to "♥")
    private val defaults = mapOf(
        "up" to "Move", "down" to "Move", "left" to "Move / page", "right" to "Move / page", "y" to "Select", "b" to "Back · hold for Home · on Home opens Apps",
        "x" to "App action", "l" to "Hold to talk to ${io.lampyr.glasses.settings.Names.assistant}", "l2" to "Shortcuts (Settings → Shortcuts) · quiet wait in ${io.lampyr.glasses.settings.Names.assistant}", "a" to "Now playing", "r" to "Notifications",
        "r2" to "Play / pause · hold for media", "minus" to "Lock · hold for listening", "plus" to "Screen action", "star" to "Apps (shortcut; B on Home does the same)", "heart" to "People heard recently")
    private var shown = ""

    override val view: View = activity.column(14) {
        setPadding(dp(18), dp(22), dp(18), dp(28))
        add(context.text("Remote", Type.DISPLAY))
        add(context.text("Works like the controller in your hand. Press and hold behave the same.", Type.SMALL), space = dp(8))
        add(context.card { add(lens) }.apply { setPadding(dp(10), dp(10), dp(10), dp(10)) })
        add(context.card {
            setPadding(dp(14), dp(18), dp(14), dp(14))
            add(RemotePad(context,
                { id -> activity.service?.input?.let { it.source = "touch"; it.press(id) } },
                { id -> activity.service?.input?.let { it.source = "touch"; it.release(id) } }))
        })
        section("What each button does here")
        add(context.card(2) {
            names.forEach { (id, symbol) ->
                val key = context.text(symbol, Type.HEADING).apply { gravity = android.view.Gravity.CENTER; textSize = 13f; background = context.shape(Ink.RAISED, 9) }
                val action = context.text("", Type.BODY)
                cells[id] = key to action
                add(context.row(14) {
                    minimumHeight = dp(40)
                    addView(key, android.widget.LinearLayout.LayoutParams(dp(40), dp(28)))
                    add(action, 1f)
                })
            }
        })
        add(context.text("Highlighted actions are specific to the screen that is showing.", Type.SMALL))
    }

    override fun frame(frame: Frame, locked: Boolean) { lens.show(frame, locked); legend() }
    override fun refresh() = legend()

    private fun legend() {
        val ui = activity.service?.ui
        val context = ui?.top?.legend() ?: emptyMap()
        // A screen override (15.3) names the button first, then the screen legend, then an assigned global shortcut.
        val overrides = ui?.top?.overrides().orEmpty().associate { it.letter to it.claim.label }
        val shortcuts = FREE_GESTURES.mapNotNull { g -> ui?.shortcuts?.action(g.letter, g.hold)?.let { g.letter to "${g.label}: ${it.label}" } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.joinToString(" · ") }
        val signature = context.toString() + overrides + shortcuts
        if (signature == shown && cells.values.first().second.text.isNotEmpty()) return
        shown = signature
        cells.forEach { (id, pair) ->
            val special = overrides[id] ?: if (id == "l") context["talkT"] ?: context["talkH"] else context[InputEngine.BIND[id]]
            pair.second.text = special ?: shortcuts[id]?.let { if (id == "l2") it else "${defaults[id]} · $it" } ?: defaults[id]
            pair.second.setTextColor(if (special != null) Ink.GREEN else Ink.MUTED)
            pair.first.setTextColor(if (special != null) Ink.GREEN else Ink.TEXT)
        }
    }
}

class ToolsPage(activity: MainActivity) : Page(activity) {
    private fun open(target: Class<*>) = activity.startActivity(Intent(activity, target))
    private lateinit var demo: Stack
    private val keys = KeyMap(activity)
    private val glasses = activity.navRow("glasses", "Pair and connect", "Find your glasses, test the lens and microphone") { open(GlassesSetupActivity::class.java) }
    private val controller = activity.navRow("controller", "Controller", "Teach buttons, choose how presses are read") { open(ControllerActivity::class.java) }

    override val view: View = activity.column(14) {
        setPadding(dp(18), dp(22), dp(18), dp(28))
        add(context.text("Tools", Type.DISPLAY))
        section("Devices")
        add(context.card(4) {
            add(glasses); add(context.divider())
            add(controller); add(context.divider())
            add(context.navRow("phone", "Pocket mode", "Black screen that keeps the controller working in your pocket") { activity.startActivity(PocketActivity.intent(activity)) })
        })
        section("Features")
        add(context.card(4) {
            add(context.navRow("wave", "Passive listening", "Conversation capture, voice matching and what is kept") { open(io.lampyr.glasses.ambient.AmbientActivity::class.java) })
            add(context.divider())
            add(context.navRow("call", "Phone calls", "Answer, end and mute calls from the glasses") { open(CallsActivity::class.java) })
        })
        section("Testing")
        add(context.card(4) {
            add(context.navRow("display", "Display lab", "Display mode, grey levels and the firmware experiments") { open(DisplayLabActivity::class.java) })
            add(context.divider())
            add(context.navRow("log", "Activity log", "What the Bluetooth, display and input layers just did") { open(LogActivity::class.java) })
            add(context.divider())
            add(context.navRow("guide", "Setup guide", "Every permission and pairing step, with its current state") { open(OnboardingActivity::class.java) })
        })
        demo = add(context.card {
            add(context.text("Try a call", Type.HEADING))
            add(context.text("Demo data is on, so you can rehearse the call screens without a real call.", Type.SMALL), space = dp(6))
            add(context.row(12) {
                add(context.button("Incoming call") { calls()?.incoming() }, 1f)
                add(context.button("Caller hangs up") { calls()?.let { calls -> calls.snapshot.calls.lastOrNull()?.let { calls.remoteEnd(it.id) } } }, 1f)
            })
        })
    }
    private fun calls() = activity.service?.ui?.data?.calls as? FakeCalls
    override fun refresh() {
        keys.refresh()
        demo.visibility = if (calls() != null) View.VISIBLE else View.GONE
        Summary.glasses(activity).let { glasses.tone(it.tone); glasses.trailing.text = it.text }
        Summary.controller(activity, activity.service, keys).let { controller.tone(it.tone); controller.trailing.text = it.text }
    }
}

class SettingsPage(activity: MainActivity) : Page(activity) {
    private val config = AppSettings(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val url = activity.field("Gateway address", runCatching { config.url }.getOrDefault(""), hint = "https://…")
    private val token = activity.field("Access token", runCatching { config.token }.getOrDefault(""), secret = true)
    private val gatewayNote = activity.note()
    // Companion config: names, integrations and the notification policy (seeded from GET /glasses/v1/config).
    private val companion = io.lampyr.glasses.settings.CompanionConfigStore(activity)
    private val initial = companion.load()
    private val assistantName = activity.field("Assistant name", initial.assistantName, hint = io.lampyr.glasses.settings.CompanionConfig.DEFAULT_ASSISTANT)
    private val workerName = activity.field("Main agent label on calls (blank: Main <assistant>)", initial.workerName)
    private val ownerName = activity.field("Your name (used in agent text)", initial.ownerName, hint = io.lampyr.glasses.settings.CompanionConfig.DEFAULT_OWNER)
    private val ownerAliases = activity.field("Other names and handles (comma separated)", initial.ownerAliases.joinToString(", "))
    private val integrationToggles = linkedMapOf<String, ToggleRow>()
    private val policyField = activity.field("Notification policy JSON (merged over the default; blank = default)", initial.notifyPolicy, lines = 4)
    private val companionNote = activity.note()
    private lateinit var order: Stack
    private var orderShown = ""
    private lateinit var live: ToggleRow
    private val power = io.lampyr.glasses.ui.SharedPowerStore(activity.getSharedPreferences("glasses", android.content.Context.MODE_PRIVATE))
    private lateinit var stockCard: Stack
    private var stockShown: String? = null
    private lateinit var devicesCard: Stack
    private var devicesShown: String? = null
    // Avatar pack: bundled placeholder, a zip picked through the Storage Access Framework, or an https URL.
    private val avatars = io.lampyr.glasses.render.avatar.AvatarPackStore(activity)
    private val avatarFact = activity.fact("Avatar pack")
    private val avatarUrl = activity.field("Pack URL (https, .zip or manifest.json)", "", hint = "https://…")
    private val avatarNote = activity.note()
    private val pickAvatar = activity.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAvatar("Importing…") { avatars.importPackFromZip(uri) }
    }

    override val view: View = activity.column(14) {
        setPadding(dp(18), dp(22), dp(18), dp(28))
        add(context.text("Settings", Type.DISPLAY))
        section("Your data")
        add(context.card(14) {
            live = add(context.toggle("Live data", "Off shows built-in demo content", runCatching { config.live }.getOrDefault(false)) { on ->
                if (on && !valid()) { live.isChecked = false; gatewayNote.show("Enter an https:// address and a token first", Tone.WARN) }
                else { config.live = on; activity.service?.reloadData(); gatewayNote.show(if (on) "Live data on" else "Showing demo data", Tone.IDLE) }
            })
            add(url); add(token); add(gatewayNote)
            add(context.row(12) {
                add(context.button("Test") { test() }, 1f)
                add(context.button("Save", Btn.PRIMARY) { save() }, 1f)
            })
        })
        section("Behaviour")
        add(context.card(8) {
            add(context.toggle("Start when the phone starts", "Reconnects to the glasses after a restart", config.boot) { config.boot = it })
            add(context.divider())
            add(context.toggle("Hold the controller upright", "Turns the D-pad to match a portrait grip", config.grip == "cw") { upright ->
                config.grip = if (upright) "cw" else "land"; activity.service?.input?.orientation = config.grip
            })
        })
        section("Glasses display")
        add(context.card(8) { powerRows(this) })
        stockCard = add(context.card(8))
        section("Assistant and you")
        add(context.card(14) {
            add(assistantName); add(workerName); add(ownerName); add(ownerAliases)
        })
        section("Integrations")
        add(context.card(8) {
            io.lampyr.glasses.settings.CompanionConfig.INTEGRATIONS.entries.forEachIndexed { i, (id, label) ->
                if (i > 0) add(context.divider())
                integrationToggles[id] = add(context.toggle(label, null, initial.integrationOn(id)) {})
            }
        })
        section("Notifications")
        add(context.card(14) {
            add(context.text("Beeper, T3 Code and the assistant reach the glasses by default; other apps stay on the phone. " +
                "Add apps with \"apps\": {\"com.example.app\": \"popup\"}, or turn on the dating preset with \"dating\": {\"enabled\": true}.", Type.SMALL))
            add(policyField); add(companionNote)
            add(context.row(12) {
                add(context.button("Load from gateway") { seedFromGateway() }, 1f)
                add(context.button("Save", Btn.PRIMARY) { saveCompanion() }, 1f)
            })
        })
        section("Avatar")
        add(context.card(14) {
            add(avatarFact); add(avatarUrl); add(avatarNote)
            add(context.row(12) {
                add(context.button("Import zip") { pickAvatar.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, 1f)
                add(context.button("Load URL") { val u = avatarUrl.value.trim(); importAvatar("Downloading…") { avatars.importPackFromUrl(u) } }, 1f)
                add(context.button("Reset") { avatars.resetToDefault(); activity.service?.reloadAvatar(); showAvatar(); avatarNote.show("Using the built-in placeholder", Tone.IDLE) }, 1f)
            })
        })
        section("Controller")
        add(context.card(4) {
            add(context.navRow("remote", "Controller shortcuts", "Give unused button holds an app action") { activity.startActivity(Intent(activity, ShortcutsActivity::class.java)) })
        })
        section("Order of apps on the glasses")
        order = add(context.card(4))
        section("Devices on the Status screen")
        devicesCard = add(context.card(8))
        section("About")
        add(context.card(14) {
            add(context.fact("Version", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug build)" else ""))
            add(context.fact("Android", android.os.Build.VERSION.RELEASE))
            add(context.fact("Phone", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
        })
    }

    private fun showAvatar() {
        val info = avatars.currentInfo()
        avatarFact.show(info?.let { "${it.name} · ${it.license}" } ?: "Drawn placeholder")
    }
    /** Settings hook: runs an AvatarPackStore import off the main thread, then swaps the glasses avatar. */
    private fun importAvatar(progress: String, block: () -> io.lampyr.glasses.render.avatar.AvatarManifest) {
        avatarNote.show(progress)
        scope.launch {
            try {
                val m = withContext(Dispatchers.IO) { block() }
                activity.service?.reloadAvatar(); showAvatar()
                avatarNote.show("Loaded ${m.name} (${m.states.size} states)", Tone.GOOD)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { avatarNote.show("Could not load the pack: ${e.message ?: e.javaClass.simpleName}", Tone.BAD) }
            catch (e: OutOfMemoryError) { avatarNote.show("Could not load the pack: its atlas is too large", Tone.BAD) }
        }
    }

    private fun showCompanion(c: io.lampyr.glasses.settings.CompanionConfig) {
        assistantName.value = c.assistantName; workerName.value = c.workerName; ownerName.value = c.ownerName
        ownerAliases.value = c.ownerAliases.joinToString(", "); policyField.value = c.notifyPolicy
        integrationToggles.forEach { (id, toggle) -> toggle.isChecked = c.integrationOn(id) }
    }
    private fun saveCompanion() {
        val next = companion.load().copy(
            assistantName = assistantName.value.trim().ifBlank { io.lampyr.glasses.settings.CompanionConfig.DEFAULT_ASSISTANT },
            workerName = workerName.value.trim(),
            ownerName = ownerName.value.trim().ifBlank { io.lampyr.glasses.settings.CompanionConfig.DEFAULT_OWNER },
            ownerAliases = ownerAliases.value.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            integrations = integrationToggles.mapValues { it.value.isChecked },
            notifyPolicy = policyField.value.trim(),
        )
        runCatching { companion.save(next) }
            .onSuccess { activity.service?.reloadData(); companionNote.show("Saved", Tone.GOOD) }
            .onFailure { companionNote.show(it.message ?: "Invalid notification policy", Tone.BAD) }
    }
    /** GET /glasses/v1/config: assistant and owner names, integrations and the notification policy. */
    private fun seedFromGateway() {
        companionNote.show("Asking the gateway…")
        scope.launch {
            try {
                val body = GatewayClient(url.value.trim(), token.value.trim()).get("/config") as? kotlinx.serialization.json.JsonObject
                    ?: throw io.lampyr.glasses.data.GatewayFailure("The gateway sent no config")
                val seeded = companion.seed(body)
                showCompanion(seeded); activity.service?.reloadData()
                companionNote.show("Loaded from the gateway", Tone.GOOD)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { companionNote.show((e as? io.lampyr.glasses.data.GatewayFailure)?.display ?: "Could not load the gateway config", Tone.BAD) }
        }
    }

    private fun valid() = url.value.trim().startsWith("https://") && token.value.isNotBlank()
    private fun save() {
        if (live.isChecked && !valid()) { gatewayNote.show("Enter an https:// address and a token", Tone.WARN); return }
        runCatching { config.url = url.value; config.token = token.value; activity.service?.reloadData() }
            .onSuccess { gatewayNote.show("Saved. The token is encrypted on this phone", Tone.GOOD) }
            .onFailure { gatewayNote.show("Could not save the token to secure storage", Tone.BAD) }
    }
    private fun test() {
        gatewayNote.show("Checking…")
        scope.launch {
            try {
                GatewayClient(url.value.trim(), token.value.trim()).get("/home")
                gatewayNote.show("Connected. The gateway answered", Tone.GOOD)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { gatewayNote.show((e as? io.lampyr.glasses.data.GatewayFailure)?.display ?: "Could not reach that address", Tone.BAD) }
        }
    }

    /** Display power rows (requirement 1.1); the same `power.*` preferences as the glasses Settings screen. */
    private fun powerRows(card: Stack) = with(card) {
        val p = power.prefs
        fun set(next: io.lampyr.glasses.ui.PowerPrefs) = power.update(next)
        val idle = io.lampyr.glasses.ui.PowerPrefs.IDLE_OPTIONS
        add(context.choice("Display timeout (lens goes dark after this idle time)", idle.map { io.lampyr.glasses.ui.PowerPrefs.idleLabel(it) }, idle.indexOf(p.idleMs).coerceAtLeast(0)) {
            set(power.prefs.copy(idleMs = idle[it])) })
        add(context.divider())
        add(context.toggle("Dim before going dark", "One dim frame, then black", p.dimStep) { set(power.prefs.copy(dimStep = it)) })
        add(context.divider())
        add(context.toggle("First press also acts", "Off: a press on a dark lens only wakes it", p.firstPressActs) { set(power.prefs.copy(firstPressActs = it)) })
        add(context.divider())
        val lock = io.lampyr.glasses.ui.PowerPrefs.LOCK_HOME_OPTIONS
        add(context.choice("After a lock longer than this, unlock opens Home", lock.map { io.lampyr.glasses.ui.PowerPrefs.lockHomeLabel(it) }, lock.indexOf(p.lockHomeMs).coerceAtLeast(0)) {
            set(power.prefs.copy(lockHomeMs = lock[it])) })
        add(context.divider())
        add(context.toggle("Popups light a locked lens", "Important notifications still show while locked", p.popupWhenLocked) { set(power.prefs.copy(popupWhenLocked = it)) })
        add(context.divider())
        add(context.toggle("Notification popups", "Off: important notifications only join the list", p.popups) { set(power.prefs.copy(popups = it)) })
        add(context.divider())
        val popupTimes = io.lampyr.glasses.ui.PowerPrefs.POPUP_MS_OPTIONS
        add(context.choice("Popup stays up for", popupTimes.map { io.lampyr.glasses.ui.PowerPrefs.popupLabel(it) }, popupTimes.indexOf(p.popupMs).coerceAtLeast(0)) {
            set(power.prefs.copy(popupMs = popupTimes[it])) })
        add(context.divider())
        val modes = io.lampyr.glasses.ui.LookUp.entries.toList()
        add(context.choice("Looking up (stock head-up; Nothing turns stock head-up off)", modes.map { io.lampyr.glasses.ui.PowerPrefs.lookUpLabel(it) }, modes.indexOf(p.lookUp).coerceAtLeast(0)) {
            val ui = activity.service?.ui
            if (ui != null) io.lampyr.glasses.ui.screens.setLookUp(ui, modes[it]) else set(power.prefs.copy(lookUp = modes[it]))
        })
        add(context.divider())
        add(context.toggle("Look up when locked", "Off: looking up never wakes a locked headset", p.lookUpWhenLocked) { set(power.prefs.copy(lookUpWhenLocked = it)) })
        add(context.divider())
        val cases = io.lampyr.glasses.render.TextCase.entries.toList()
        add(context.choice("Glasses text case", cases.map { io.lampyr.glasses.ui.PowerPrefs.textCaseLabel(it) }, cases.indexOf(p.textCase).coerceAtLeast(0)) {
            set(power.prefs.copy(textCase = cases[it])) })
        add(context.divider())
        val backends = io.lampyr.glasses.ui.PowerPrefs.BACKENDS
        add(context.choice("Dark lens mode (Park closes the page so stock look-up wake can run; untested, may show the stock home)", backends.map { io.lampyr.glasses.ui.PowerPrefs.backendLabel(it) }, backends.indexOf(p.backend).coerceAtLeast(0)) {
            set(power.prefs.copy(backend = backends[it])) })
    }

    /** Stock settings kept on the glasses (15.1 head gestures, 15.2 display position); rebuilt when they change. */
    private fun refreshStock() {
        val ui = activity.service?.ui
        val current = ui?.glassesSettings?.current
        val signature = current?.toString() ?: "none"
        if (signature == stockShown) return
        stockShown = signature
        stockCard.removeAllViews()
        if (ui == null || current == null) {
            stockCard.add(activity.text("Head gestures and display position appear here once the glasses are connected.", Type.SMALL))
            ui?.glassesSettings?.refresh()
            return
        }
        fun write(change: (io.lampyr.glasses.glasses.StockSettings) -> io.lampyr.glasses.glasses.StockSettings) = scope.launch {
            val result = ui.glassesSettings.update(change)
            if (!result.ok) Toast.makeText(activity, result.message ?: "Could not change the glasses setting", Toast.LENGTH_SHORT).show()
            refresh()
        }
        if (current.wakeKnown) {
            val degrees = (io.lampyr.glasses.glasses.StockSettings.MIN_DEGREES..io.lampyr.glasses.glasses.StockSettings.MAX_DEGREES step io.lampyr.glasses.glasses.StockSettings.DEGREE_STEP).toList()
            val nearest = degrees.minBy { kotlin.math.abs(it - current.headUpDegrees) }
            stockCard.add(activity.choice("Look-up angle", degrees.map { "$it°" }, degrees.indexOf(nearest)) { i -> write { it.copy(headUpDegrees = degrees[i]) } })
            stockCard.add(activity.divider())
        }
        if (current.gesturesKnown) {
            stockCard.add(activity.toggle("Nod / shake", "Answer stock cards with head gestures", current.headGestures) { on -> write { it.copy(headGestures = on) } })
            stockCard.add(activity.choice("Confirm with", listOf("Nod", "Shake"), current.headGestureMode) { i -> write { it.copy(headGestureMode = i) } })
            stockCard.add(activity.divider())
        }
        if (current.displayKnown) {
            val heights = io.lampyr.glasses.glasses.protocol.Commands.DISPLAY_HEIGHTS
            val distances = io.lampyr.glasses.glasses.protocol.Commands.DISPLAY_DISTANCES
            stockCard.add(activity.choice("Display height", heights.map { io.lampyr.glasses.glasses.StockSettings.heightLabel(it) }, heights.indexOf(current.displayHeight).coerceAtLeast(0)) { i -> write { it.copy(displayHeight = heights[i]) } })
            stockCard.add(activity.choice("Display distance", distances.map { io.lampyr.glasses.glasses.StockSettings.distanceLabel(it) }, distances.indexOf(current.displayDistance).coerceAtLeast(0)) { i -> write { it.copy(displayDistance = distances[i]) } })
        }
        if (!current.wakeKnown && !current.gesturesKnown && !current.displayKnown)
            stockCard.add(activity.text("The glasses did not report head gesture or display settings.", Type.SMALL))
    }

    /** Requirement 16.4: pick which paired Bluetooth devices get a battery row on the glasses' Status screen. */
    private fun refreshDevices() {
        val glasses = activity.getSharedPreferences("glasses", android.content.Context.MODE_PRIVATE).getString("glasses.address", null)
        val devices = io.lampyr.glasses.data.AndroidBluetoothPlatform(activity).bonded()
            .filterNot { glasses != null && it.address.equals(glasses, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        val prefs = activity.getSharedPreferences(io.lampyr.glasses.data.StatusDevices.PREFS, android.content.Context.MODE_PRIVATE)
        val signature = devices.joinToString { it.address + it.name + prefs.getString(it.address, "") }
        if (signature == devicesShown) return
        devicesShown = signature
        devicesCard.removeAllViews()
        devicesCard.add(activity.text("Auto shows earbuds and headphones while they are connected. Show adds any other device; Hide removes one.", Type.SMALL))
        if (devices.isEmpty()) { devicesCard.add(activity.text("No paired Bluetooth devices (or Bluetooth permission is off).", Type.SMALL)); return }
        val values = listOf(null, io.lampyr.glasses.data.StatusDevices.SHOW, io.lampyr.glasses.data.StatusDevices.HIDE)
        devices.forEach { d ->
            val auto = if (io.lampyr.glasses.data.StatusDevices.autoShows(d.kind)) "Auto (shown)" else "Auto (hidden)"
            devicesCard.add(activity.choice(d.name.ifBlank { d.address }, listOf(auto, "Show", "Hide"), values.indexOf(prefs.getString(d.address, null)).coerceAtLeast(0)) { i ->
                prefs.edit().apply { if (values[i] == null) remove(d.address) else putString(d.address, values[i]) }.apply()
                activity.service?.ui?.let { ui -> ui.refresh { ui.data.status.refresh() } }
            })
        }
    }

    override fun refresh() {
        refreshDevices()
        refreshStock()
        showAvatar()
        val service = activity.service ?: return
        val entries = io.lampyr.glasses.ui.screens.appEntries(service.ui)
        val signature = entries.joinToString { it.id }
        if (signature == orderShown) return
        orderShown = signature
        order.removeAllViews()
        entries.forEachIndexed { index, entry ->
            if (index > 0) order.add(activity.divider())
            order.add(activity.row(8) {
                minimumHeight = dp(48)
                add(context.text("${index + 1}", Type.SMALL).apply { minWidth = dp(22) })
                add(context.text(entry.label), 1f)
                for ((glyph, delta) in listOf("up" to -1, "down" to 1)) {
                    val move = context.icon(glyph, Ink.TEXT, 18).apply {
                        background = context.pressable(Ink.RAISED, 12); scaleType = android.widget.ImageView.ScaleType.CENTER
                        contentDescription = "Move ${entry.label} ${if (delta < 0) "up" else "down"}"
                        available(index + delta in entries.indices)
                        setOnClickListener {
                            scope.launch {
                                val ids = entries.map { it.id }.toMutableList()
                                java.util.Collections.swap(ids, index, index + delta)
                                val result = service.ui.data.settings.update(service.ui.data.settings.snapshot.copy(appOrder = ids))
                                if (!result.ok) Toast.makeText(activity, result.message ?: "Could not save the order", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }
                    }
                    addView(move, android.widget.LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginStart = dp(8) })
                }
            })
        }
    }
}
