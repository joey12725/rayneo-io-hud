package io.lampyr.glasses.input

import io.lampyr.glasses.debug.Trace

interface ControllerSource {
    val name: String
    fun start()
    fun stop()
}

interface ControllerSink {
    fun press(letter: String)
    fun release(letter: String)
    fun releaseAll()
    fun confirmDown()
    fun status(connected: Boolean, detail: String)
}

/** A single BLE report can contain both transitions; establish the layer before its directions. */
fun ControllerReportDecoder.Changes.deliver(sink: ControllerSink) {
    released.sortedBy { if (it == "r2") 1 else 0 }.forEach(sink::release)
    pressed.sortedBy { if (it == "r2") 0 else 1 }.forEach(sink::press)
}

data class ControllerIdentity(val descriptor: String, val vendor: Int, val product: Int) {
    fun matches(other: ControllerIdentity) = descriptor.isNotBlank() && this == other
}

/** Consumption depends only on identity, never on mapping, engine availability, or source. */
object ControllerKeyPolicy {
    fun consume(learned: ControllerIdentity?, incoming: ControllerIdentity?) =
        incoming != null && learned?.matches(incoming) == true
}

/** GATT stays explicit. Shizuku falls back until its raw reader owns usable nodes. */
class ControllerSourceSelection(private val releaseAll: () -> Unit) {
    var preferred = "accessibility"
        private set
    var selected = "accessibility"
        private set
    private var rawReady = false
    fun select(value: String) {
        require(value in setOf("accessibility", "gatt", "shizuku"))
        preferred = value
        update()
    }
    fun shizukuReady(ready: Boolean) { rawReady = ready; update() }
    private fun update() {
        val next = if (preferred == "shizuku" && !rawReady) "accessibility" else preferred
        if (next != selected) { Trace.log("Controller", "Source $selected -> $next preferred=$preferred"); releaseAll(); selected = next }
    }
    fun accepts(source: String) = source == selected
}
