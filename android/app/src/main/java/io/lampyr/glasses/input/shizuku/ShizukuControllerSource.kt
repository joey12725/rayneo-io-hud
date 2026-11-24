package io.lampyr.glasses.input.shizuku

import io.lampyr.glasses.debug.Trace

import io.lampyr.glasses.input.ControllerSink
import io.lampyr.glasses.input.ControllerSource

data class ShizukuInputState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val granted: Boolean = false,
    val bound: Boolean = false,
    val controller: String = "",
    val nodes: List<String> = emptyList(),
    val exclusive: Boolean = false,
    val detail: String = "Shizuku unavailable",
) {
    val ready get() = running && granted && bound && nodes.isNotEmpty()
}

/** All Shizuku/Android IPC stays behind this boundary; source logic needs only a JVM. */
interface ShizukuInputFacade : AutoCloseable {
    interface Listener {
        fun state(state: ShizukuInputState)
        fun event(node: String, event: RawInputEvent)
        fun lost(node: String)
    }
    fun start(identity: EvdevIdentity?, listener: Listener)
    fun requestPermission()
    fun refresh()
    override fun close()
}

class ShizukuControllerSource(
    private val facade: ShizukuInputFacade,
    private val identity: () -> EvdevIdentity?,
    private val letter: (Int) -> String?,
    private val sink: ControllerSink,
    private val route: (Boolean) -> Unit,
    private val learn: (Int, Int) -> Boolean = { _, _ -> false },
) : ControllerSource, ShizukuInputFacade.Listener {
    override val name = "shizuku"
    var state = ShizukuInputState(); private set
    var lastEvent = "Press a button"; private set
    var testing = false
        set(value) { field = value; reset() }
    private var running = false
    private val merger = EvdevKeyMerger()
    private val dedupe = KeyDeliveryDedupe()
    private val down = mutableMapOf<Int, String>()
    override fun start() { running = true; facade.start(identity(), this) }
    override fun stop() {
        running = false
        facade.close()
        state = state.copy(bound = false, nodes = emptyList(), exclusive = false)
        reset(); route(false)
    }
    fun reset() { merger.clear(); dedupe.clear(); down.clear(); sink.releaseAll() }
    fun requestPermission() = facade.requestPermission()
    fun refresh() = facade.refresh()
    override fun state(state: ShizukuInputState) {
        if (!running) return
        if (this.state.ready != state.ready || this.state.nodes != state.nodes) reset()
        if (this.state != state) Trace.log("Shizuku", "Reader/grab state=$state")
        this.state = state
        route(state.ready)
        if (state.ready) sink.status(true, "Shizuku ${if (state.exclusive) "exclusive" else "shared: screen will still wake"} (${state.nodes.size} nodes)")
    }
    override fun lost(node: String) {
        if (!running) return
        // Loss is abandonment, never synthesize a back/media/plus tap or leave PTT running.
        reset()
    }
    override fun event(node: String, event: RawInputEvent) {
        if (!running || !state.ready) return
        lastEvent = "$node: ${if (event.type == 1) LinuxKeyMap.label(LinuxKeyMap.bindingCode(event.code)) else "hat ${event.code}"} = ${event.value}"
        merger.event(node, event.type, event.code, event.value).forEach { transition ->
            deliver("raw", transition.code, transition.value, event.timeMs)
        }
    }
    /** Exclusively grabbed devices never enter this path. In shared mode either delivery order works. */
    fun accessibility(key: Int, value: Int, timeMs: Long) {
        if (running && state.ready && !state.exclusive) deliver("accessibility", key, value, timeMs)
    }
    private fun deliver(source: String, key: Int, value: Int, timeMs: Long) {
        if (!dedupe.accept(source, key, value, timeMs)) return
        if (learn(key, value) || testing) return
        sink.confirmDown()
        if (value == 1 && key !in down) letter(key)?.let { down[key] = it; sink.press(it) }
        else if (value == 0) down.remove(key)?.let { released -> if (released !in down.values) sink.release(released) }
        // value 2 only confirms a hold; InputEngine owns repeats and hold timers.
    }
}
