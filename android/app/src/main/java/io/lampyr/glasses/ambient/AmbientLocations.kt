@file:Suppress("MissingPermission", "DEPRECATION")
package io.lampyr.glasses.ambient

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import java.io.Closeable

/** One bounded fix at start, then every five minutes. No continuous GPS or movement polling. */
class AmbientLocations(private val context: Context, private val eligible: () -> Boolean,
    private val emit: (GeoFix) -> Unit) : Closeable {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var conversation: String? = null
    private var running = false
    private var closed = false
    private var pending: CancellationSignal? = null
    private var single: LocationListener? = null
    private var generation = 0L
    private val periodic = object : Runnable {
        override fun run() {
            if (!running || closed || conversation == null) return
            request()
            handler.postDelayed(this, 300_000)
        }
    }
    private fun permitted() = eligible() && listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun start() { if (!closed) running = true }
    fun conversationStart(id: String) {
        cancelRequest(); handler.removeCallbacks(periodic); conversation = id
        if (running) { request(); handler.postDelayed(periodic, 300_000) }
    }
    fun conversationEnd(id: String) {
        if (conversation != id) return
        conversation = null; cancelRequest(); handler.removeCallbacks(periodic)
    }
    private fun request() {
        val id = conversation ?: return
        if (!running || closed || !permitted()) return
        cancelRequest()
        val token = generation
        val cached = runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos } }.getOrNull()
        if (cached != null) {
            val fix = convert(id, cached)
            if (LocationPolicy.reusable(fix, System.currentTimeMillis())) { emit(fix); return }
        }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal(); pending = signal
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (pending !== signal) return@getCurrentLocation
                    pending = null
                    if (conversation == id && running && permitted() && location != null) emit(convert(id, location))
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (single !== this) return
                        cancelRequest()
                        if (conversation == id && running && permitted()) emit(convert(id, location))
                    }
                }
                single = listener; manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            handler.postDelayed({ if (token == generation) cancelRequest() }, 15_000)
        } catch (_: SecurityException) { cancelRequest() }
        catch (_: IllegalArgumentException) { cancelRequest() }
    }
    private fun convert(id: String, location: Location): GeoFix {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000
        return GeoFix(id, System.currentTimeMillis() - age, location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else null, source = location.provider ?: "android.location", confidence = "fix")
    }
    private fun cancelRequest() { generation++; pending?.cancel(); pending = null; single?.let { manager.removeUpdates(it) }; single = null }
    fun stop() { running = false; conversation = null; cancelRequest(); handler.removeCallbacksAndMessages(null) }
    override fun close() { closed = true; stop() }
}
