package io.lampyr.glasses.service

import io.lampyr.glasses.debug.Trace

import android.app.*
import android.content.Intent
import android.os.*
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.audio.FakeDictation
import io.lampyr.glasses.audio.GatewayDictation
import io.lampyr.glasses.ambient.AmbientRuntime
import io.lampyr.glasses.settings.AppSettings
import io.lampyr.glasses.data.*
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.ui.Ui
import io.lampyr.glasses.ui.Display
import io.lampyr.glasses.ui.onWearerSignal
import kotlinx.coroutines.*

class GlassesService : Service() {
    inner class LocalBinder : Binder() {
        val service
            get() = this@GlassesService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var ui: Ui
        private set
    /** The assistant avatar (phone Settings → Avatar pack); loaded once and swapped on [reloadAvatar]. */
    val avatarPacks by lazy { io.lampyr.glasses.render.avatar.AvatarPackStore(this) }
    private var avatar: io.lampyr.glasses.render.avatar.AvatarPack? = null
    private fun avatar() = avatar ?: avatarPacks.currentPack().also { avatar = it }
    /** Re-reads the avatar setting after an import or reset and redraws. */
    fun reloadAvatar() {
        avatar = try { avatarPacks.currentPack() } catch (_: OutOfMemoryError) { io.lampyr.glasses.render.avatar.AvatarPacks.bundledOrNull(this) }
        if (::ui.isInitialized) { ui.avatar = avatar; ui.requestRender() }
    }

    lateinit var input: InputEngine
        private set

    var controller: ControllerRuntime? = null
        private set
    fun controllerInput(): InputEngine? = if (::input.isInitialized) input else null
    private val controllerNotification by lazy { ControllerNotification(this) { foreground = it } }
    val preview = PreviewTransport()
    val transports = mutableListOf<GlassesTransport>(preview)
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var destroying = false
    private var lastFrame = 0L
    private var foreground: android.app.Notification? = null
    lateinit var ambient: AmbientRuntime
        private set
    /** Clock app engine (requirement 7): owned here so timers and alarms outlive Ui sessions; see clock/ClockEngine.kt. */
    lateinit var clock: io.lampyr.glasses.clock.ClockEngine
        private set
    val clockOrNull get() = if (::clock.isInitialized) clock else null
    private val clockTicker = object : Runnable {
        override fun run() {
            if (destroying || !::clock.isInitialized) return
            clock.tick()
            if (::ui.isInitialized && !ui.locked && (ui.top.id in CLOCK_SCREENS || (ui.top.kind == "home" && ui.clockSummary() != null))) ui.requestRender()
            if (clock.active) handler.postDelayed(this, 1000)
        }
    }
    private fun clockChanged() {
        handler.removeCallbacks(clockTicker)
        if (clock.active) handler.postDelayed(clockTicker, 1000)
        if (::ui.isInitialized) ui.requestRender()
    }
    private val clockTimeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (!::clock.isInitialized) return
            if (intent.action == android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) io.lampyr.glasses.clock.ClockBridge.publish(context)
            else clock.onTimeChanged()
        }
    }
    private val access: ForegroundAccess = ForegroundAccess({ types ->
        startForeground(1, controllerNotification.build(statusLine()), types)
    }, { android.util.Log.e("GlassesService", "Foreground type promotion rejected; open the phone app", it) })
    val microphoneReady: Boolean get() = permission(android.Manifest.permission.RECORD_AUDIO) && access.has(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    val microphoneStatus: String get() = if (microphoneReady) "Phone microphone armed for background recording" else "Mic unavailable - open the phone app"
    val ambientLocationStatus get() = if (locationForeground) "Location ready" else "Location unavailable - open the phone app and allow Location"
    private fun permission(name: String) = checkSelfPermission(name) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun statusLine(): String = listOf(microphoneStatus, if (::ambient.isInitialized && ambient.preferences.enabled) "Ambient: ${ambient.status.value.line()} / $ambientLocationStatus" else "").filter { it.isNotBlank() }.joinToString(" · ")
    private var locationForeground = false
    private val displayPrefs by lazy { getSharedPreferences("glasses", MODE_PRIVATE) }
    private val previewPrefs by lazy { AppSettings(this).prefs }
    private val displayListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateDisplay()
    }
    fun updateDisplay() {
        if (!::ui.isInitialized) return
        val realActive = displayPrefs.getBoolean("glasses.enabled", false) &&
            !displayPrefs.getString("glasses.address", null).isNullOrBlank()
        val (cols, rows) = if (realActive) GlassesTransports.real?.textLayout?.value ?: (26 to 7) else (26 to 7)
        val previous = ui.display
        ui.display = if (realActive) {
            if (GlassesTransports.real?.usesBitmap == true) Display.Bitmap else Display.Text(cols, rows.coerceAtLeast(3))
        } else if (AppSettings(this).previewText) Display.Text(cols, rows.coerceAtLeast(3)) else Display.Bitmap
        if (previous != ui.display) Trace.log("Service", "Display mode ${ui.display} realActive=$realActive")
    }
    private val render = Runnable {
        scheduled = false
        scope.launch {
            if (ui.dirty || ui.needsAnimation) {
                val latency = io.lampyr.glasses.debug.Latency.renderStarted(ui.dirty)
                val frame = ui.render()
                io.lampyr.glasses.debug.Latency.rendered(frame, latency)
                preview.rememberMaster(frame)
                lastFrame = SystemClock.elapsedRealtime()
                transports.toList().forEach { transport ->
                    // With a live link, preview follows successful sends/commits from the real transport.
                    if (transport === preview && GlassesTransports.real?.connected == true && displayPrefs.getBoolean("glasses.enabled", false)) return@forEach
                    try {
                        transport.show(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Trace.error("Service", "Render transport=${transport.javaClass.simpleName}", e)
                        ui.flash("Display disconnected")
                    }
                }
            }
            if (ui.needsAnimation) schedule()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Trace.log("Service", "Create")
        ServiceAccess.observe(application)
        instance = this
        GlassesTransports.attach(this, transports, scope) // RayNeo integration: optional real output alongside preview.
        val channel =
            NotificationChannel("glasses", "RayNeo companion", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        foreground = controllerNotification.build()
        startForeground(1, foreground!!, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        controller = ControllerRuntime(this)
        ambient = AmbientRuntime(this) { locationForeground }
        GlassesTransports.real?.attachAmbient(ambient)
        clock = io.lampyr.glasses.clock.AndroidClock.engine(this)
        clock.listen { clockChanged() }
        clockChanged()
        val clockFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(clockTimeReceiver, clockFilter, RECEIVER_NOT_EXPORTED) else registerReceiver(clockTimeReceiver, clockFilter)
        io.lampyr.glasses.clock.ClockBridge.publish(this)
        refreshForegroundAccess()
        controllerNotification.extra = { statusLine() }
        reloadData()
        controllerNotification.start()
        displayPrefs.registerOnSharedPreferenceChangeListener(displayListener)
        previewPrefs.registerOnSharedPreferenceChangeListener(displayListener)
        GlassesTransports.real?.let { real -> scope.launch {
            real.textLayout.collect { updateDisplay() }
        } }
        GlassesTransports.real?.let { real ->
            scope.launch { real.raster.selected.collect { updateDisplay() } }
            real.onDisplayNotice = { notice -> scope.launch { ui.flash(notice) } }
            scope.launch { real.sentFrames.collect { preview.showSent(it) } }
        }
    }

    /** Agent control (the assistant drives the glasses): the op registry for the current Ui and its gateway link. */
    var agentRegistry: io.lampyr.glasses.agent.ActionRegistry? = null
        private set
    var agentLink: io.lampyr.glasses.agent.AgentLink? = null
        private set

    fun reloadData() {
        agentLink?.stop(); agentLink = null
        if (::input.isInitialized) input.close()
        if (::ui.isInitialized) ui.close()
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val config = AppSettings(this)
        val store = storeFactory?.invoke(this, sessionScope) ?: if (config.live) LiveDataStore(this, sessionScope) else FakeDataStore(this, scope = sessionScope)
        Trace.log("Service", "reloadData store=${store.javaClass.simpleName} live=${store is LiveDataStore}")
        ambient.attachPeople((store as? LiveDataStore)?.peopleEngine, (store as? LiveDataStore)?.gateway)
        ui = Ui(store, avatar(), sessionScope,
            shortcutAssignments = io.lampyr.glasses.input.ShortcutAssignments(getSharedPreferences("app-shortcuts", MODE_PRIVATE)),
            powerStore = io.lampyr.glasses.ui.SharedPowerStore(displayPrefs),
            glassesSettings = GlassesTransports.real?.stockSettings ?: MemoryStockSettings(),
            clock = clock,
            beeperPrefs = io.lampyr.glasses.data.SharedBeeperPrefs(displayPrefs))
        ui.shortcuts.controllerLetters = { io.lampyr.glasses.input.KeyMap.learnedLetters(this) }
        // Real capture runs in demo and live modes alike, so the remote's mute always drives the real switch.
        ui.listening = io.lampyr.glasses.ambient.AmbientListening(ambient.preferences)
        val audio = if (store is LiveDataStore) GatewayDictation(this, store.gateway, sessionScope) else FakeDictation(store, sessionScope)
        input = InputEngine(ui, controller?.dictation(audio) ?: audio)
        controller?.attach(input)
        input.orientation = config.grip
        input.onAnyPress = { GlassesTransports.real?.reclaim() }
        if (store is LiveDataStore) {
            store.onOpen = { ui.open(it) }
            store.glassesBattery = object : GlassesBatterySource {
                override val batteryFraction get() = GlassesTransports.real?.deviceStatusFlow?.value?.battery?.let { it / 100.0 }
                override val connected get() = GlassesTransports.real?.connectionState?.value == ConnectionState.CONNECTED
            }
        }
        agentRegistry = io.lampyr.glasses.agent.ActionRegistry(ui, policy = {
            io.lampyr.glasses.agent.ConfirmPolicy.parse(AppSettings(this).agentConfirm)
        }).also { it.input = input; it.onWake = { GlassesTransports.real?.reclaim("assistant request") } }
        if (store is LiveDataStore && config.agentControl) agentLink = io.lampyr.glasses.agent.AgentLink(store.gateway.agentSocketFactory(), sessionScope,
            "${io.lampyr.glasses.BuildConfig.VERSION_NAME} ${io.lampyr.glasses.BuildConfig.GIT_SHA}").also { it.registry = agentRegistry; it.start() }
        ui.onCallWake = { input.callInterrupted(); GlassesTransports.real?.reclaim("phone call") }
        ui.onClockWake = { GlassesTransports.real?.reclaim("clock alert") }
        ui.onDirty = { schedule() }
        attachPower()
        updateDisplay()
        schedule()
    }

    // ---- Display power (requirement 1.1): one Handler runnable follows Ui.powerTick(). ----
    private val powerTick = Runnable {
        if (destroying || !::ui.isInitialized) return@Runnable
        ui.powerTick()?.let { schedulePowerAt(it) }
    }
    private fun schedulePowerAt(delayMs: Long) {
        handler.removeCallbacks(powerTick)
        handler.postDelayed(powerTick, delayMs.coerceIn(10, 60_000))
    }
    private var unsubscribeStock: () -> Unit = {}
    private fun attachPower() {
        val real = GlassesTransports.real
        ui.onPowerActivity = { if (!destroying) { handler.removeCallbacks(powerTick); handler.post(powerTick) } }
        ui.onDisplayAwake = { awake -> GlassesTransports.real?.displayAwake(awake) }
        real?.displayAwake(ui.awake)
        real?.onWearerWake = { reason ->
            handler.post {
                if (!destroying && ::ui.isInitialized) ui.onWearerSignal(reason)
            }
        }
        // Wake on look-up is our setting; mirror it (and the short stock auto-sleep a parked page needs) into the
        // stock settings once per fresh read and after each power preference change. Never in a loop: a glasses
        // reply that keeps the old value is not rewritten until the next connection or preference change.
        unsubscribeStock()
        var synced = false
        fun sync() {
            val current = ui.glassesSettings.current
            if (current == null) { synced = false; return }
            if (synced) return
            synced = true
            io.lampyr.glasses.ui.stockSyncFor(ui.powerStore.prefs, current)?.let { wanted ->
                Trace.log("Power", "Stock settings sync headUp=${wanted.headUp} autoLock=${wanted.autoLockTime}")
                ui.perform { ui.glassesSettings.update { live -> io.lampyr.glasses.ui.stockSyncFor(ui.powerStore.prefs, live) ?: live } }
            }
        }
        val stopStock = ui.glassesSettings.listen { handler.post { if (!destroying) sync() } }
        val stopPrefs = ui.powerStore.listen { handler.post { if (!destroying) { synced = false; sync() } } }
        unsubscribeStock = { stopStock(); stopPrefs() }
        handler.post(powerTick)
    }

    /** Promotion happens while visible, never at a background recording boundary. */
    fun enableMicrophone(): Boolean = access.enable(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        permission(android.Manifest.permission.RECORD_AUDIO), ServiceAccess.isVisible)

    fun refreshForegroundAccess() {
        enableMicrophone()
        if (::ambient.isInitialized && ambient.preferences.enabled) enableAmbientLocation()
        if (::ui.isInitialized) (ui.data.calls as? io.lampyr.glasses.calls.AndroidCalls)?.refreshPermissions()
        controller?.appeared()
    }

    /** Called from visible phone settings; missing permission/FGS eligibility omits locations only. */
    fun enableAmbientLocation(): Boolean {
        locationForeground = access.enable(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            permission(android.Manifest.permission.ACCESS_FINE_LOCATION) || permission(android.Manifest.permission.ACCESS_COARSE_LOCATION),
            ServiceAccess.isVisible)
        if (::ambient.isInitialized) ambient.refresh()
        return locationForeground
    }

    private var scheduledFor = 0L
    private fun schedule() {
        if (destroying) return
        // A key press skips the render throttle, even when an animation render is already queued.
        val urgent = io.lampyr.glasses.debug.Latency.inputPending
        val now = SystemClock.elapsedRealtime()
        val interval = if (::ui.isInitialized && !ui.dirty) animationIntervalMs() else 100L
        val target = if (urgent) now else maxOf(now, lastFrame + interval)
        // A queued render that is due sooner stands; a real change or a press pulls a slow animation render forward.
        if (scheduled && target >= scheduledFor) return
        handler.removeCallbacks(render)
        scheduled = true; scheduledFor = target
        handler.postDelayed(render, target - now)
    }

    /**
     * Animation-only renders (nothing changed but the clock) run on the main thread. When the raster glasses are the
     * only viewer they send at most one such frame per raster.idleFrameMs, so rendering them at 10 Hz wasted ~90% of
     * the work and kept the main thread busy exactly when a button press needed it. With the phone app visible
     * (its mirror animates) or on the text page, the 100 ms cadence stays.
     */
    private fun animationIntervalMs(): Long {
        val real = GlassesTransports.real ?: return 100L
        if (ServiceAccess.isVisible || !real.connected || real.raster.selected.value == null || !displayPrefs.getBoolean("glasses.enabled", false)) return 100L
        return runCatching { displayPrefs.getLong("raster.idleFrameMs", 1_000L) }.getOrDefault(1_000L).coerceIn(100L, 1_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::clock.isInitialized) when (intent?.action) {
            io.lampyr.glasses.clock.AndroidClock.ACTION_DISMISS -> clock.dismiss(intent.getStringExtra(io.lampyr.glasses.clock.AndroidClock.EXTRA_ID))
            io.lampyr.glasses.clock.AndroidClock.ACTION_SNOOZE -> clock.snooze(intent.getStringExtra(io.lampyr.glasses.clock.AndroidClock.EXTRA_ID))
            io.lampyr.glasses.clock.AndroidClock.ACTION_WAKE -> clock.tick()
        }
        refreshForegroundAccess()
        return START_STICKY
    }

    override fun onBind(intent: Intent) = binder

    override fun onDestroy() {
        Trace.log("Service", "Destroy")
        destroying = true
        displayPrefs.unregisterOnSharedPreferenceChangeListener(displayListener)
        previewPrefs.unregisterOnSharedPreferenceChangeListener(displayListener)
        controllerNotification.close()
        controller?.close(); controller = null
        handler.removeCallbacks(render)
        handler.removeCallbacks(powerTick); unsubscribeStock()
        handler.removeCallbacks(clockTicker)
        runCatching { unregisterReceiver(clockTimeReceiver) }
        if (::clock.isInitialized) (clock.alerter as? io.lampyr.glasses.clock.PhoneAlerter)?.close()
        agentLink?.stop(); agentLink = null
        if (::input.isInitialized) input.close()
        if (::ui.isInitialized) ui.close()
        if (::ambient.isInitialized) ambient.close()
        GlassesTransports.detach() // RayNeo integration: stop reconnects and release Bluetooth/microphone.
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        private val CLOCK_SCREENS = setOf("clock", "timer", "stopwatch", "alarms", "clock-ring")
        @Volatile
        var instance: GlassesService? = null
            private set

        /** Install a live store at application startup without changing any screen. */
        var storeFactory: ((android.content.Context, CoroutineScope) -> DataStore)? = null
    }
}
