package io.lampyr.glasses.glasses

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.lampyr.glasses.debug.Trace

/**
 * RayNeo's own phone app competes for the one glasses link (docs/rayneo-protocol.md §8: glassesctl `official off`
 * force-stops it and revokes its Nearby devices permission). We only detect and warn; never uninstall or block it.
 * Android does not show other apps' processes, so "may run" means the package is not in the force-stopped state.
 */
object OfficialApp {
    const val PACKAGE = "com.rayneo.venus.pub"

    data class State(val installed: Boolean, val enabled: Boolean = true, val stopped: Boolean = true, val bluetooth: Boolean = false)
    enum class Level { NONE, INFO, WARN }
    data class Health(val level: Level, val text: String, val detail: String = "")

    fun assess(state: State): Health = when {
        !state.installed -> Health(Level.NONE, "Not installed")
        !state.enabled -> Health(Level.NONE, "Disabled")
        state.bluetooth && !state.stopped -> Health(Level.WARN, "Running with Bluetooth",
            "RayNeo's app competes for the glasses link. Force-stop it or turn off its Nearby devices permission")
        state.bluetooth -> Health(Level.WARN, "Has Bluetooth access",
            "If it opens it takes the glasses link. Turn off its Nearby devices permission")
        !state.stopped -> Health(Level.INFO, "May run, Bluetooth blocked", "Fine while its Nearby devices permission stays off")
        else -> Health(Level.INFO, "Stopped, Bluetooth blocked")
    }

    fun probe(context: Context): State {
        val pm = context.packageManager
        val info = try { pm.getApplicationInfo(PACKAGE, 0) } catch (_: PackageManager.NameNotFoundException) { return State(false) }
        val permission = if (android.os.Build.VERSION.SDK_INT >= 31) "android.permission.BLUETOOTH_CONNECT" else "android.permission.BLUETOOTH"
        val bluetooth = pm.checkPermission(permission, PACKAGE) == PackageManager.PERMISSION_GRANTED
        return State(true, info.enabled, info.flags and ApplicationInfo.FLAG_STOPPED != 0, bluetooth)
    }

    /** Phone pages refresh several times a second; two package-manager calls each time are not worth it. */
    @Volatile private var cached: Pair<Long, Health>? = null
    fun health(context: Context, now: Long = android.os.SystemClock.elapsedRealtime()): Health {
        cached?.let { (at, health) -> if (now - at in 0 until CACHE_MS) return health }
        return runCatching { assess(probe(context)) }.getOrDefault(Health(Level.NONE, "Unknown")).also { cached = now to it }
    }
    private const val CACHE_MS = 5_000L

    @Volatile private var traced: State? = null
    /** Traces the official app's state on the first attempt and whenever it changes (cheap: one package lookup). */
    fun traceIfChanged(context: Context) {
        val state = runCatching { probe(context) }.getOrNull() ?: return
        if (state == traced) return
        traced = state
        val health = assess(state)
        val line = "Official RayNeo app: ${health.text} installed=${state.installed} enabled=${state.enabled} stopped=${state.stopped} bluetoothConnect=${state.bluetooth}"
        if (health.level == Level.WARN) Trace.error("Link", "$line. ${health.detail}") else Trace.log("Link", line)
    }
}
