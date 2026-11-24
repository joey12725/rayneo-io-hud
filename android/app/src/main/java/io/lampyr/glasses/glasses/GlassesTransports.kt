package io.lampyr.glasses.glasses

import io.lampyr.glasses.debug.Trace

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*

/** Shared by the service and setup UI; a single Bluetooth owner, with preview always available. */
object GlassesTransports {
    var real: RayNeoTransport? = null
        private set
    private var prefs: SharedPreferences? = null
    private var ownerScope: CoroutineScope? = null
    private var transports: MutableList<GlassesTransport>? = null
    private var configuration: Pair<Boolean, String?>? = null
    private var update: Job? = null
    private var linkWatch: Job? = null
    private var triggers: ReconnectTriggers.Receiver? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "glasses.enabled" || key == "glasses.address") refresh()
    }
    fun attach(context: Context, outputs: MutableList<GlassesTransport>, scope: CoroutineScope) {
        Trace.log("Registry", "Attach")
        detach()
        // Raster I/O gets its own thread so main-thread render stalls never delay a frame's round trips.
        real = RayNeoTransport(context.applicationContext, rasterDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "raster").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 } }.asCoroutineDispatcher())
        prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        ownerScope = scope; transports = outputs
        prefs!!.registerOnSharedPreferenceChangeListener(listener)
        // Remember the first real connection, so setup can tell "chosen in a scan" from "actually paired".
        val link = real!!; val store = prefs!!
        linkWatch = scope.launch { link.connectionState.collect { if (it == ConnectionState.CONNECTED && !store.getBoolean(LINKED, false)) store.edit().putBoolean(LINKED, true).apply() } }
        // Bluetooth and screen events cut the reconnect backoff short (the link ignores them while off or connected).
        triggers = runCatching { ReconnectTriggers.Receiver(context.applicationContext, {
            if (!store.getBoolean("glasses.enabled", false)) emptyList()
            else listOfNotNull(store.getString("glasses.address", null), store.getString("glasses.bleAddress", null))
        }) { trigger -> link.wake(trigger.reason, trigger.userAction) } }
            .onFailure { Trace.error("Registry", "Reconnect triggers unavailable", it) }.getOrNull()
        refresh()
    }
    private fun refresh() {
        val settings = prefs ?: return
        val next = settings.getBoolean("glasses.enabled", false) to settings.getString("glasses.address", null)
        if (configuration == next) return
        configuration = next
        update?.cancel()
        val transport = real ?: return
        val enabled = next.first && !next.second.isNullOrBlank()
        Trace.log("Registry", "enabled=$enabled address=${next.second}")
        if (enabled) { if (transport !in transports.orEmpty()) transports?.add(transport) }
        else transports?.remove(transport)
        update = ownerScope?.launch {
            if (enabled) {
                // Seed the current screen even when the UI is idle when Real glasses is enabled.
                transports?.filterIsInstance<PreviewTransport>()?.firstOrNull()?.masterFrame?.let { transport.show(it) }
                transport.connect()
            } else transport.disconnect()
        }
    }
    const val LINKED = "glasses.linked"
    fun reconnect() { Trace.log("Registry", "Reconnect requested"); configuration = null; refresh() }
    fun detach() {
        Trace.log("Registry", "Detach")
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        update?.cancel(); update = null
        linkWatch?.cancel(); linkWatch = null
        triggers?.close(); triggers = null
        GlassesSetupActions.micTest(false)
        real?.let { transports?.remove(it); it.close() }
        real = null; prefs = null; ownerScope = null; transports = null; configuration = null
    }
}
