// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Caption session handshake adapted from OpenRayneo GlassesDisplay.kt; see android/NOTICE.
package io.lampyr.glasses.glasses

import io.lampyr.glasses.debug.Trace

import android.content.Context
import android.os.SystemClock
import io.lampyr.glasses.audio.MicRegistry
import io.lampyr.glasses.ambient.*
import io.lampyr.glasses.data.GlassesBatterySource
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.glasses.raster.*
import io.lampyr.glasses.render.BlockGraphics
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class GlassesStatus(val battery: Int? = null, val firmware: String? = null, val brightness: Int? = null)

/** Source-based stock-firmware port; the full BLE + caption + mic lifecycle is unverified on hardware. */
class RayNeoTransport(context: Context, private val link: TransportLink = RayNeoLink(context),
                      private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                      /** Raster I/O, encode and reply handling run here; null keeps them on [scope] (tests). */
                      private val rasterDispatcher: CoroutineDispatcher? = null,
                      /** Raster fit/encode CPU work; tests pass their scheduler's dispatcher. */
                      rasterCompute: CoroutineDispatcher = Dispatchers.Default,
                      private val clock: () -> Long = SystemClock::elapsedRealtime) : GlassesTransport, GlassesBatterySource {
    private val app = context.applicationContext
    override val connectionState = link.connectionState
    override val connected get() = connectionState.value == ConnectionState.CONNECTED
    override val batteryFraction get() = if (connected) deviceStatus.value.battery?.div(100.0) else null
    private val error = MutableStateFlow<String?>(null)
    val lastError = error.asStateFlow()
    private val deviceStatus = MutableStateFlow(GlassesStatus())
    val deviceStatusFlow = deviceStatus.asStateFlow()
    private val mode = MutableStateFlow("Display only; microphone off")
    val audioMode = mode.asStateFlow()
    private val signals = Channel<Signal>(128)
    private val tickQueued = AtomicBoolean()
    private val latest = AtomicReference<List<String>?>(null)
    private val renderer = CharGridRenderer()
    private val layout = MutableStateFlow(26 to 7)
    val textLayout = layout.asStateFlow()
    private val pacer = FramePacer()
    private val sequence = AtomicInteger(0x4000)
    private val sent = MutableSharedFlow<Frame>(replay = 1, extraBufferCapacity = 1)
    val sentFrames = sent.asSharedFlow()
    /** Stock glasses settings (head gestures, look-up wake, display position); see StockSettings.kt. */
    val stockSettings = StockSettingsClient { command -> sendProbe(command) }
    private enum class PageOwner { NONE, TEXT, CAPTION, RASTER, PROMPT, EXPERIMENT }
    private var owner = PageOwner.NONE // All page transitions belong to the signal reducer.
        set(value) { if (field != value) Trace.log("Page", "Owner $field -> $value"); field = value }
    val pageOwner get() = owner.name
    val reconnectAttempt get() = (link as? RayNeoLink)?.reconnectAttempt
    val reconnectDelayMs get() = (link as? RayNeoLink)?.reconnectDelayMs
    /** The reconnect supervisor's status: searching in the background, waiting, or stopped on a real error. */
    val retry get() = link.retry
    /** A Bluetooth or phone event says the glasses may be reachable: retry now (see [ReconnectTriggers]). */
    fun wake(reason: String, userAction: Boolean = false) = link.wake(reason, userAction)
    private var sessionStartedAt = 0L
    @Volatile private var lifeLogArmed = false
    /** Raster work never shares the main thread with rendering and the reducer (latency report 4.5). */
    private val rasterScope = if (rasterDispatcher == null) scope else CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + rasterDispatcher)
    val raster = RasterOutput(app, rasterScope, link, sequence, { kind ->
        val accepted = CompletableDeferred<Boolean>(currentCoroutineContext()[Job])
        signals.send(Signal.RasterAcquire(accepted, kind))
        accepted.await()
    }, { sent.tryEmit(DisplayMirror.raster(it)) }, { error.value = it },
        { signals.trySend(Signal.WearerExit) }, { lifeLogArmed },
        wearerWake = { reason -> scope.launch { Trace.log("Power", "Wearer wake signal source=$reason"); onWearerWake?.invoke(reason) } },
        recovered = { notice -> onDisplayNotice?.invoke(notice) },
        // Only a stock caption page we really just closed needs time to let go before OPEN.
        releaseDelay = { if (clock() - selfClosedAt < 1_500) 500 else 0 }, clock = clock, compute = rasterCompute)
    init { link.rasterTap = raster::incoming }
    @Volatile override var onWearerWake: ((String) -> Unit)? = null
    /** Short display-health notices for the wearer (a long raster outage just ended). */
    @Volatile var onDisplayNotice: ((String) -> Unit)? = null
    /** Display power contract (GlassesTransport): BLACK or PARK backend per pref power.backend / power.lookUpWake. */
    override fun displayAwake(awake: Boolean) { raster.displayAwake(awake) }
    private var glyphExperiment = false
    private var surfaceExperiment = false
    @Volatile private var experimentActive = false
    val usesBitmap get() = raster.selected.value != null || raster.holdingDisplay || blockMode != BlockGraphics.Mode.OFF
    private val blockMode get() = runCatching { BlockGraphics.Mode.valueOf(app.getSharedPreferences("glasses", Context.MODE_PRIVATE).getString("display.blocks", "OFF")!!) }.getOrDefault(BlockGraphics.Mode.OFF)
    private val audioSequences = AudioSequenceWindow()
    private var sid: String? = null
    private var audioPage = false
    private var promptSid: String? = null
    private var readyAt = Long.MAX_VALUE
    private var deadline = 0L
    private var reopenAt = 0L
    private var micWanted = false
    private var reducedState = ConnectionState.DISCONNECTED
    private var fallback = false
    /**
     * Hardware, Strix OS 1.0.6.11 + TMIG2-VIDEO-R1: the caption page accepts its microphone start but
     * never sends audio, and opening it throws the raster UI off the display. The assistant recorder
     * (0x0D type 2) streams within 300 ms and leaves the raster page alone, so it is the microphone
     * whenever a TVP raster session owns the display.
     */
    private var recorder = false
    private var lifeTask: String? = null
    private var micStartedAt = 0L
    private var lastAudioAt = 0L
    private var nextStatusAt = 0L
    private var ambient: AmbientRuntime? = null
    private var ambientSource: AmbientCaptureSource? = null
    private var ambientContinuous = false
    private var ambientFallback = false
    private var ambientDisplayPriority = false
    private var ambientRetryAt = 0L
    private var ambientRetryMs = 1_000L
    private val autoPolicy = AutoFallbackPolicy()
    fun attachAmbient(runtime: AmbientRuntime) { signals.trySend(Signal.Action({ ambient = runtime; configureAmbient() })) }
    fun ambientChanged() { if (!closing) signals.trySend(Signal.AmbientChanged) }
    @Volatile private var closing = false
    private var layoutPending = false
    @Volatile private var connectionSerial = 0
    val mic = GlassesMic({ !closing && connected }) { start ->
        if (!closing) {
            val signal = Signal.Capture(start, connectionSerial)
            scope.launch { signals.send(signal) }
        }
    }

    private sealed interface Signal {
        data class State(val value: ConnectionState) : Signal
        data class Incoming(val event: GlassesEvent, val utc: Long) : Signal
        data class Capture(val start: Boolean, val serial: Int) : Signal
        data class Action(val run: suspend () -> Unit, val done: CompletableDeferred<Unit>? = null) : Signal
        data class RasterAcquire(val done: CompletableDeferred<Boolean>, val kind: RasterKind = RasterKind.TVP2) : Signal
        data object DisplayChanged : Signal
        data object WearerExit : Signal
        data object AmbientChanged : Signal
        data object Tick : Signal
    }
    init {
        scope.launch {
            for (signal in signals) try {
                when (signal) {
                    is Signal.State -> stateChanged(signal.value)
                    is Signal.Incoming -> receive(signal.event, signal.utc)
                    is Signal.Capture -> if (signal.serial == connectionSerial) captureChanged(signal.start)
                    is Signal.Action -> try { signal.run(); signal.done?.complete(Unit) }
                        catch (e: Exception) { signal.done?.completeExceptionally(e); throw e }
                    is Signal.RasterAcquire -> try {
                        val allowed = signal.done.isActive && connected && !micWanted && reopenAt != Long.MAX_VALUE && !surfaceExperiment && owner != PageOwner.PROMPT
                        if (allowed) {
                            // TVP2's patched gate ignores an armed LifeLog, so a raster (re)open never cycles LifeLog
                            // (that stop/re-arm bracketed every 9-minute flash). Glassly's PAGE_OPEN uses the stock busy gate.
                            // If an open was refused while armed, fall back to disarming first (RasterOutput.lifeLogBlocksOpen).
                            if (signal.kind == RasterKind.GLASSLY || raster.lifeLogBlocksOpen) stopAmbient()
                            closeText()
                            owner = PageOwner.RASTER
                        }
                        signal.done.complete(allowed)
                    } catch (e: Exception) { signal.done.completeExceptionally(e); throw e }
                    Signal.DisplayChanged -> {
                        if (raster.ready.value && raster.selected.value == null && owner == PageOwner.RASTER) owner = PageOwner.NONE
                        if (raster.ready.value && !raster.probing.value) configureAmbient()
                    }
                    Signal.WearerExit -> wearerExited()
                    Signal.AmbientChanged -> {
                        ambientDisplayPriority = false; ambientFallback = false; ambientRetryAt = 0; ambientRetryMs = 1_000
                        configureAmbient()
                    }
                    Signal.Tick -> { tickQueued.set(false); tick() }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (signal is Signal.Capture) {
                    micWanted = false; recorder = false; raster.suspended = reopenAt == Long.MAX_VALUE
                    mic.stop()
                    mode.value = "Display only; microphone off"
                }
                Trace.error("Transport", "Signal ${signal.javaClass.simpleName} failed", e)
                error.value = e.message ?: e.javaClass.simpleName
            }
        }
        scope.launch { lastError.collect { Trace.log("Transport", "lastError=$it") } }
        scope.launch { connectionState.collect { signals.send(Signal.State(it)) } }
        scope.launch { link.lastError.collect { if (it != null) error.value = it } }
        scope.launch { mic.lastError.collect { if (it != null) error.value = it } }
        scope.launch { combine(raster.ready, raster.selected, raster.probing) { _, _, _ -> Unit }.collect { signals.send(Signal.DisplayChanged) } }
        scope.launch { link.frames.collect { frame ->
            // Raw ACKs must bypass the reducer: it may be awaiting raster close. They are not audio envelopes.
            if (raster.incoming(frame)) return@collect
            try { EventParser.parse(frame).let { event ->
                if (event == null || event is GlassesEvent.Message || event is GlassesEvent.Teleprompter || event is GlassesEvent.HeadGesture || event is GlassesEvent.Wake || event is GlassesEvent.AssistantExit ||
                    (event is GlassesEvent.PageReply && (event.business != 0x13 || event.sid != sid || event.type !in setOf(2, 8))) ||
                    (event is GlassesEvent.PageExit && event.sid != sid && event.sid != promptSid) ||
                    (event is GlassesEvent.PageShown && (!event.exited || event.app?.endsWith(".aiSubtitle") != true)) ||
                    (event is GlassesEvent.LifeLog && ambientSource == null && event.type !in setOf(161, 163, 165))) {
                    Trace.log("Unhandled", "Incoming business=${frame.business} type=${runCatching { Envelope.decode(frame.payload).type }.getOrNull()} len=${frame.payload.size}")
                }
                if (event != null) signals.send(Signal.Incoming(event, frame.receivedAt ?: System.currentTimeMillis()))
            } }
            catch (e: IllegalArgumentException) { Trace.error("Parser", "Malformed business=${frame.business} len=${frame.payload.size}", if (Trace.verbose) e else null); error.value = "Malformed glasses message ignored" }
            catch (e: IllegalStateException) { Trace.error("Parser", "Unsupported business=${frame.business} len=${frame.payload.size}", if (Trace.verbose) e else null); error.value = "Unsupported glasses message ignored" }
        } }
        scope.launch { while (isActive) {
            // A suspended handler needs one future tick, not hundreds ahead of mic/controller commands.
            if (tickQueued.compareAndSet(false, true) && !signals.trySend(Signal.Tick).isSuccess) tickQueued.set(false)
            delay(25)
        } }
    }

    override suspend fun connect() {
        val prefs = app.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val address = prefs.getString("glasses.address", null)
        check(!address.isNullOrBlank()) { "Scan and select glasses first" }
        link.connect(address)
    }
    override suspend fun disconnect() {
        // Best effort while the socket is still usable; unexpected loss cannot deliver stop commands.
        try { withTimeoutOrNull(1_000) { mutate { releaseSurface() } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Native socket loss is handled by the link supervisor. */ }
        finally { link.disconnect(); clearMic() }
    }
    fun close() {
        if (closing) return
        closing = true
        scope.coroutineContext.cancelChildren()
        clearMic(); mic.close()
        scope.launch {
            try { withTimeoutOrNull(1_000) { releaseSurface() } }
            catch (_: Exception) { /* Cannot disarm an already lost Bluetooth link. */ }
            finally { raster.dispose(); link.rasterTap = null; link.close(); signals.close(); scope.cancel(); (rasterDispatcher as? ExecutorCoroutineDispatcher)?.close() }
        }
    }
    private suspend fun releaseSurface() {
        if (!connected) return
        stopAmbient()
        raster.stop()
        if (fallback) { send(Commands.lifeLogStop()); send(Commands.lifeLogSwitch(false)) }
        if (recorder) { recorder = false; send(Commands.assistantRecorder(false)) }
        sid?.let { send(Commands.pageClose(it)) }
    }
    private suspend fun mutate(action: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        signals.send(Signal.Action(action, done)); done.await()
    }
    override suspend fun show(frame: Frame) = mutate {
        if (experimentActive) return@mutate
        raster.offer(frame)
        glyphExperiment = blockMode != BlockGraphics.Mode.OFF
        val lines = if (glyphExperiment) BlockGraphics.convert(frame.bits, frame.bitmap.width, frame.bitmap.height, renderer.columns, renderer.rows, blockMode) else frame.text.lines()
        // While a test pattern is up, keep the newest app frame aside instead of overwriting the pattern.
        if (clock() < testUntil) heldDuringTest = lines else latest.set(lines)
    }
    @Volatile private var testUntil = 0L
    @Volatile private var heldDuringTest: List<String>? = null
    /** Setup's test pattern uses the same session/conversion/pacing path as application frames, and stays up for [holdMs]. */
    fun showGrid(lines: List<String>, holdMs: Long = 10_000) {
        testUntil = clock() + holdMs
        latest.set(lines.toList())
    }
    fun showGlyphExperiment(lines: List<String>) { signals.trySend(Signal.Action({ beginGlyphExperiment(lines) })) }
    suspend fun showGlyphExperimentAndWait(lines: List<String>) = mutate { beginGlyphExperiment(lines) }
    private suspend fun beginGlyphExperiment(lines: List<String>) {
        check(!micWanted) { "Stop caption capture before display experiments" }
        raster.suspendAndClose(); stopAmbient(); closeText(); owner = PageOwner.NONE
        experimentActive = true; glyphExperiment = true; surfaceExperiment = false; raster.suspended = true
        latest.set(lines); renderer.reset(); pacer.reset()
    }
    suspend fun experiment(command: Command) = mutate {
        check(!micWanted) { "Stop caption capture before display experiments" }
        experimentActive = true; surfaceExperiment = true; raster.suspended = true
        raster.suspendAndClose(); stopAmbient(); closeText()
        owner = PageOwner.EXPERIMENT
        send(command)
    }
    suspend fun experimentRaster(kind: RasterKind, frame: Frame) = mutate {
        check(!micWanted) { "Stop caption capture before display experiments" }
        stopAmbient()
        experimentActive = true; surfaceExperiment = false; glyphExperiment = false
        raster.suspended = false; raster.offer(frame); raster.forceTry(kind)
    }
    fun endDisplayExperiment() { signals.trySend(Signal.Action({
        if (!experimentActive) return@Action
        experimentActive = false; surfaceExperiment = false; glyphExperiment = false; raster.suspended = micWanted
        app.getSharedPreferences("glasses", Context.MODE_PRIVATE).edit().putLong("display.refresh", System.currentTimeMillis()).apply()
        if (owner == PageOwner.EXPERIMENT) owner = PageOwner.NONE
        GlassesService.instance?.let { it.updateDisplay(); it.ui.requestRender() }
    })) }
    suspend fun notify(title: String, body: String, appId: String = "io.lampyr.glasses", category: Int = 0) {
        send(Commands.notificationSwitch(true))
        send(Commands.notification(title, body, appId = appId, category = category))
    }
    suspend fun status(): GlassesStatus {
        send(Commands.generalStatus()); send(Commands.firmwareVersion())
        // Return latest observed values. A write is not a firmware response or a render acknowledgement.
        return deviceStatus.value
    }
    /** Uses the same link write boundary and OTA allowlist as every stock command. */
    suspend fun sendProbe(command: Command, ble: Boolean = false) {
        if (command.business == 9) require(command.envelope.type in setOf(1, 2, 11)) { "Unsafe OTA type" }
        if (ble) link.sendStockBle(command.frame(sequence.getAndIncrement() and 65535)) else send(command)
    }
    private suspend fun send(command: Command) {
        if (command.business == 0x17 && command.envelope.type == 1) {
            promptSid = (kotlinx.serialization.json.Json.parseToJsonElement(command.envelope.json) as JsonObject).string("sid")
        }
        link.send(command.frame(sequence.getAndIncrement()))
        if (command.business == 15 && command.envelope.type == 20) {
            val body = kotlinx.serialization.json.Json.parseToJsonElement(command.envelope.json) as JsonObject
            if (body.string("cmd") == "life_log_switch") lifeLogArmed = (body["payload"] as? JsonObject)?.int("value") == 1
        }
    }
    private fun clearMic() {
        if (MicRegistry.glasses === mic) MicRegistry.glasses = null
        mic.stop()
    }
    private suspend fun stateChanged(state: ConnectionState) {
        Trace.log("Transport", "connectionState=$state")
        reducedState = state
        connectionSerial++
        if (state == ConnectionState.CONNECTED) {
            error.value = null
            deviceStatus.value = GlassesStatus()
            MicRegistry.glasses = mic
            nextStatusAt = 0; reopenAt = 0; owner = PageOwner.NONE
            ambientDisplayPriority = false; ambientFallback = false; ambientRetryAt = 0; ambientRetryMs = 1_000
            stopAmbient() // A lost link cannot disarm a previously enabled LifeLog switch.
            raster.suspended = false
            raster.begin()
            if (mic.isCapturing()) captureChanged(true) else tick()
        } else {
            ambientSource = null; promptSid = null; owner = PageOwner.NONE; ambient?.drop("Bluetooth ${state.name.lowercase()}")
            clearMic(); sid = null; deadline = 0; readyAt = Long.MAX_VALUE; layoutPending = false
            micWanted = false; fallback = false; lifeTask = null; recorder = false
            experimentActive = false; surfaceExperiment = false; glyphExperiment = false; raster.suspended = false
            mode.value = "Display only; microphone off"
            pacer.reset(); audioSequences.clear()
            raster.stop()
        }
    }
    /** When we last closed our own caption page. The glasses announce that close as an app exit a moment later. */
    private var selfClosedAt = Long.MIN_VALUE / 2
    private suspend fun closeText() {
        val id = sid
        if (id != null) { send(Commands.pageClose(id)); selfClosedAt = clock() }
        sid = null; deadline = 0; readyAt = Long.MAX_VALUE; layoutPending = false
        if (owner == PageOwner.TEXT || owner == PageOwner.CAPTION) owner = PageOwner.NONE
    }
    private suspend fun stopAmbient() {
        val source = ambientSource; ambientSource = null
        try {
            source?.stop()
            if (lifeLogArmed && connected) send(Commands.lifeLogSwitch(false))
            lifeLogArmed = false
        } finally {
            promptSid = null
            if (owner == PageOwner.PROMPT) {
                owner = PageOwner.NONE
                raster.suspended = micWanted || reopenAt == Long.MAX_VALUE
            }
        }
    }
    private suspend fun openPage(withAudio: Boolean) {
        sessionStartedAt = clock()
        Trace.log("Page", "Caption session start audio=$withAudio")
        check(owner !in setOf(PageOwner.RASTER, PageOwner.PROMPT, PageOwner.EXPERIMENT)) { "Another display page is owned" }
        closeText()
        owner = if (micWanted) PageOwner.CAPTION else PageOwner.TEXT
        val id = UUID.randomUUID().toString().replace("-", "")
        sid = id; audioPage = withAudio; readyAt = Long.MAX_VALUE
        layoutPending = !withAudio
        renderer.reset(); pacer.reset(); audioSequences.clear()
        (latest.get() ?: lastLines)?.let { pacer.offer(gridContent(it)) }
        deadline = clock() + 8_000
        if (withAudio) send(Commands.pageStart(id)) else send(Commands.captionLayout(id))
    }
    private suspend fun captureChanged(start: Boolean) {
        if (!connected || reducedState != ConnectionState.CONNECTED || start != mic.isCapturing() || (!start && !micWanted && !recorder)) return
        Trace.log("Mic", "Capture start=$start")
        stopAmbient()
        if (recorder) {
            recorder = false
            send(Commands.assistantRecorder(false))
            if (!start) { mode.value = "Display only; microphone off"; configureAmbient(); return }
        }
        if (start && owner == PageOwner.RASTER && raster.selected.value == RasterKind.TVP2) {
            recorder = true
            mode.value = "Glasses microphone (assistant recorder); raster stays up"
            Trace.log("Mic", "Assistant recorder start")
            send(Commands.assistantRecorder(true))
            return
        }
        micWanted = start
        raster.suspended = micWanted
        if (micWanted) {
            raster.suspendAndClose()
            if (owner == PageOwner.RASTER) owner = PageOwner.NONE
        } else {
            closeText()
            if (raster.wantsRaster) owner = PageOwner.RASTER
        }
        if (fallback) {
            send(Commands.lifeLogStop()); send(Commands.lifeLogSwitch(false))
        }
        fallback = false; lifeTask = null
        micStartedAt = clock(); lastAudioAt = 0
        mode.value = if (micWanted) "Caption mono (wearer mapping unverified)" else "Display only; microphone off"
        if (reopenAt != Long.MAX_VALUE && (micWanted || (textChosen() && !raster.holdingDisplay))) openPage(micWanted)
        if (!micWanted) configureAmbient()
    }
    private suspend fun configureAmbient() {
        val runtime = ambient
        if (!connected || micWanted || runtime?.isActive() != true || reopenAt == Long.MAX_VALUE || experimentActive) {
            stopAmbient(); return
        }
        if (!raster.ready.value || raster.probing.value || (owner == PageOwner.RASTER && raster.selected.value == null)) return
        val continuous = runtime.preferences.mode == "continuous" || ambientFallback
        // Stock prompt, caption and raster pages are mutually exclusive. A controller
        // request gives the UI priority until capture is explicitly reconfigured.
        if (continuous && ambientDisplayPriority) {
            stopAmbient()
            runtime.sourceState("Continuous paused: UI display requested; reapply capture settings to resume")
            return
        }
        if (ambientSource != null && continuous == ambientContinuous) return
        if (clock() < ambientRetryAt) return
        stopAmbient()
        startAmbient(runtime, continuous)
    }
    private suspend fun startAmbient(runtime: AmbientRuntime, continuous: Boolean) {
        ambientContinuous = continuous
        autoPolicy.reset(clock())
        var source: AmbientCaptureSource? = null
        try {
            if (continuous) {
                // 0x17 is a display page too: continuous capture takes exclusive ownership.
                raster.suspendAndClose(); closeText(); owner = PageOwner.PROMPT
                source = ContinuousCaptureSource(::send, runtime::packet, runtime::diagnostic)
            } else {
                if (raster.selected.value == RasterKind.GLASSLY) {
                    runtime.sourceState("LifeLog paused: Glassly owns display; select Continuous to test streaming")
                    runtime.diagnostic("LifeLog paused: Glassly PAGE_OPEN uses the stock busy gate")
                    return
                }
                source = LifeLogCaptureSource(::send, runtime::packet, { runtime.boundary() }, runtime::diagnostic, runtime.preferences.probeCache)
                lifeLogArmed = true // TVP2's patched gate permits this; sustained audio still needs hardware testing.
            }
            source.start()
            ambientSource = source
            ambientRetryAt = 0; ambientRetryMs = 1_000
            runtime.sourceState(if (ambientContinuous) "Continuous stream; phone VAD" else "LifeLog armed; audio arrives in 20 s batches")
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            // The start write may have reached the glasses before timing out.
            try { source?.stop() } catch (cleanup: Exception) {
                if (cleanup is CancellationException && cleanup !is TimeoutCancellationException) throw cleanup
            }
            try { stopAmbient() } catch (cleanup: Exception) {
                if (cleanup is CancellationException && cleanup !is TimeoutCancellationException) throw cleanup
            }
            owner = PageOwner.NONE
            raster.suspended = micWanted || reopenAt == Long.MAX_VALUE
            ambientRetryAt = clock() + ambientRetryMs
            runtime.sourceState("Capture start failed; retry in ${ambientRetryMs / 1000}s: ${e.message}")
            runtime.diagnostic("Capture start failed: ${e.message}")
            ambientRetryMs = (ambientRetryMs * 2).coerceAtMost(60_000)
            error.value = "Ambient capture start failed: ${e.message}"
        }
    }
    private suspend fun useLifeLog(reason: String) {
        if (!micWanted || fallback) return
        Trace.log("Mic", "Fallback to LifeLog reason=$reason")
        fallback = true; lifeTask = null
        mode.value = "LifeLog mono fallback; speech-triggered"
        error.value = "$reason; trying LifeLog mono while retaining the text grid"
        // Not armed until a caller explicitly starts MicSource. Never background-record by default.
        // Coexistence of LifeLog + caption layout is unverified on hardware.
        openPage(false)
        send(Commands.lifeLogSwitch(true))
    }
    /** Called on controller input: take the display back if the wearer had exited our page. */
    fun reclaim(reason: String = "controller key") { raster.noteInteraction(); Trace.log("Page", "Reclaim requested owner=$owner"); signals.trySend(Signal.Action({
        val continuousWanted = ambient?.isActive() == true && (ambient?.preferences?.mode == "continuous" || ambientFallback)
        if (reopenAt == Long.MAX_VALUE || owner == PageOwner.PROMPT || continuousWanted) {
            ambientDisplayPriority = true
            ambientRetryAt = 0
            reopenAt = 0
            stopAmbient(); raster.suspended = micWanted
            configureAmbient()
        }
        // A person (or the assistant) is using the glasses now: retry at once, even after a terminal error.
        link.wake(reason, userAction = true)
    })) }
    private suspend fun wearerExited() {
        Trace.log("Page", "Crown/page exit owner=$owner")
        sid = null; deadline = 0; readyAt = Long.MAX_VALUE; layoutPending = false
        reopenAt = Long.MAX_VALUE; owner = PageOwner.NONE; raster.suspended = true
        stopAmbient()
    }
    private fun textChosen() = owner !in setOf(PageOwner.RASTER, PageOwner.PROMPT, PageOwner.EXPERIMENT) &&
        raster.selected.value == null && (micWanted || !raster.wantsRaster || raster.probing.value) && (raster.mode != DisplayMode.RASTER || glyphExperiment || micWanted)


    private suspend fun receive(event: GlassesEvent, utc: Long) {
        if (experimentActive && event is GlassesEvent.Message) {
            runCatching {
                DisplayExperimentLog(app).add("Reply business=${event.business} type=${event.type}",
                    "cmd=${event.body.string("cmd")} code=${event.body.int("code")} value=${(event.body["payload"] as? JsonObject)?.int("value")}")
            }.onFailure { error.value = "Experiment log: ${it.message}" }
        }
        if (!connected) return
        if (event is GlassesEvent.LifeLog && event.type == 161) autoPolicy.wake()
        ambientSource?.let { source ->
            val runtime = ambient
            // Deliver the triggering event to its current owner before changing capture sources.
            source.event(event, utc)
            if (runtime?.preferences?.mode == "auto" && !ambientContinuous &&
                autoPolicy.fallback(clock(), event is GlassesEvent.Audio || event is GlassesEvent.LifeLog && event.type == 163 && event.packets.isNotEmpty())) {
                stopAmbient()
                runtime.diagnostic("Audio without LifeLog wake: switching to continuous phone VAD")
                ambientFallback = true
                configureAmbient()
            }
        }
        receiveStock(event)
        when (event) {
            is GlassesEvent.Status -> {
                Trace.log("Status", "battery=${event.battery} OsVersion=${event.firmware} brightness=${event.brightness}")
                event.firmware?.let { raster.firmwareVersion(it) }
                deviceStatus.value = deviceStatus.value.let { current -> current.copy(
                    battery = event.battery ?: current.battery, firmware = event.firmware ?: current.firmware, brightness = event.brightness ?: current.brightness) }
            }
            is GlassesEvent.PageExit -> if ((event.business == 0x13 && event.sid == sid) || (event.business == 0x17 && event.sid == promptSid && owner == PageOwner.PROMPT)) {
                // Protocol §4.3: an inbound exit releases the display. Stay off it until the wearer
                // asks for it again with the controller (see reclaim()).
                wearerExited()
            }
            is GlassesEvent.PageShown -> { Trace.log("Glasses", "Showing scene=${event.scene} index=${event.index} app=${event.app} exited=${event.exited}") }
            else -> {}
        }
        when (event) {
            is GlassesEvent.PageShown -> if (event.exited && event.app?.endsWith(".aiSubtitle") == true && sid != null) {
                // Swapping pages (text grid -> microphone page) closes ours first; the glasses then report the caption app
                // exiting, which is our own close, not the wearer leaving. Only treat it as the wearer after a quiet period.
                if (clock() - selfClosedAt < 2_000) Trace.log("Page", "Ignoring caption-app exit caused by our own page swap")
                else wearerExited()
            }
            is GlassesEvent.PageReply -> if (event.business == 0x13 && event.sid == sid) {
                Trace.log("Page", "Caption reply type=${event.type} elapsedMs=${clock() - sessionStartedAt} code=${event.body.int("code")}")
                when (event.type) {
                    2 -> if (audioPage) {
                        if (event.body.int("code") != 1) useLifeLog("Caption microphone startup rejected")
                        else if (!layoutPending) { layoutPending = true; deadline = clock() + 8_000; Trace.log("Page", "Caption layout send elapsedMs=${clock() - sessionStartedAt}"); send(Commands.captionLayout(event.sid)) }
                    }
                    8 -> {
                        if (!layoutPending) return
                        check(event.body.int("code") == 1) { "Caption layout rejected" }
                        val effective = event.body["effective_config"] as? JsonObject ?: error("Missing effective caption configuration")
                        renderer.configure(effective.int("font_size") ?: 0, effective.int("content_width") ?: 0, effective.int("max_lines") ?: 0)
                        Trace.log("Page", "Effective layout font=${effective.int("font_size")} columns=${renderer.columns} rows=${renderer.rows} elapsedMs=${clock() - sessionStartedAt}")
                        layout.value = renderer.columns to renderer.rows
                        layoutPending = false
                        (latest.get() ?: lastLines)?.let { pacer.offer(gridContent(it)) }
                        readyAt = clock() + 500; deadline = 0
                    }
                }
            }
            is GlassesEvent.Audio -> if (event.business == 0x0d) { if (recorder) mic.accept(event.packet) }
                else if (micWanted && !fallback && event.business == 0x13 && event.sid == sid && audioSequences.accept(event.sequence)) {
                lastAudioAt = clock(); mic.accept(event.packet)
            }
            is GlassesEvent.LifeLog -> if (micWanted && fallback) {
                when (event.type) {
                    161 -> {
                        if (lifeTask != null) return
                        lifeTask = UUID.randomUUID().toString()
                        send(Commands.lifeLogStart(lifeTask!!)); send(Commands.lifeLogPage())
                    }
                    163 -> if (lifeTask != null && event.body.string("taskId") == lifeTask) {
                        event.packets.forEach { mic.accept(it.opus) }; lastAudioAt = clock()
                    }
                    165 -> lifeTask = null
                }
            }
            is GlassesEvent.Message -> if (event.business == 0x0f && event.body.string("cmd") == "life_log_switch_result") {
                val value = (event.body["payload"] as? JsonObject)?.int("value")
                if (fallback && value != null && value != 0) error.value = "LifeLog refused ($value): check microphone permission, unfolded temples and battery"
            }
            else -> Unit // Other typed events are parsed for future page-specific input integration.
        }
    }
    private suspend fun tick() {
        if (connectionState.value != ConnectionState.CONNECTED) return
        val now = clock()
        if (now >= nextStatusAt) {
            nextStatusAt = now + 60_000
            status()
        }
        if (ambientSource == null && ambientRetryAt > 0 && now >= ambientRetryAt) configureAmbient()
        if (surfaceExperiment || !textChosen() || (raster.probing.value && raster.mode == DisplayMode.RASTER && !micWanted)) return
        // Raster expected (or retrying after a failure): never flash the stock caption page meanwhile.
        if (raster.holdingDisplay && !micWanted && !glyphExperiment) return
        if (raster.mode == DisplayMode.RASTER && !glyphExperiment && !micWanted) return // Caption mic explicitly owns its stock page while capturing.
        if (sid == null && now >= reopenAt) openPage(micWanted && !fallback)
        if (deadline != 0L && now >= deadline) {
            if (micWanted && !fallback) useLifeLog("Caption audio/layout handshake timed out")
            else {
                error.value = "Caption layout not confirmed; retrying in five seconds"
                closeText(); reopenAt = now + 5_000
            }
        }
        if (micWanted && !fallback && now - maxOf(lastAudioAt, micStartedAt) > 10_000) useLifeLog("No caption audio for ten seconds")
        if (testUntil != 0L && now >= testUntil) { testUntil = 0; heldDuringTest?.let { latest.set(it) }; heldDuringTest = null }
        if (now < readyAt || sid == null) return
        latest.getAndSet(null)?.let { lines -> lastLines = lines; pacer.offer(gridContent(lines)) }
        val text = pacer.next(now) ?: return
        Trace.log("Display", "Text write hash=${text.hashCode().toUInt().toString(16)} columns=${renderer.columns} rows=${renderer.rows} ${Trace.content(text)}")
        send(Commands.captionText(sid!!, renderer.wireText(text)))
        renderer.written(); pacer.written(text, clock())
        sent.tryEmit(DisplayMirror.text(text, renderer.columns, renderer.rows))
    }
    private fun gridContent(lines: List<String>) = if (glyphExperiment) (0 until renderer.rows).joinToString("\n") { row ->
        val points = lines.getOrNull(row).orEmpty().codePoints().toArray().take(renderer.columns)
        points.joinToString("") { String(Character.toChars(it)) } + "\u3000".repeat(renderer.columns - points.size)
    } else renderer.content(lines)
    private var lastLines: List<String>? = null

    // ---- Stock settings (requirements 15.1 / 15.2): read on connect, written through the guarded probe boundary. ----
    init {
        stockSettings.requestRead = { if (connected && !closing) scope.launch { runCatching { stockSettings.read() } } }
        scope.launch { connectionState.collectLatest { state ->
            stockSettings.connectionChanged(state == ConnectionState.CONNECTED)
            if (state == ConnectionState.CONNECTED) {
                delay(2_000) // After the raster/caption handshake has had its turn on the link.
                runCatching { stockSettings.read() }.onFailure { Trace.error("Settings", "Settings read failed", it) }
            }
        } }
    }
    private fun receiveStock(event: GlassesEvent) {
        when (event) {
            is GlassesEvent.Settings -> stockSettings.received(event)
            is GlassesEvent.Screen -> {
                // Stock screen woke (head-up or crown) while our page may be parked: reopen and wake the lens.
                Trace.log("Glasses", "screen_status value=${event.value} owner=$owner parked=${raster.parked}")
                if (event.value != 0) raster.stockScreenOn("screen_status")
            }
            is GlassesEvent.Hall -> Trace.log("Glasses", "hall_state value=${event.value}")
            else -> Unit
        }
    }
}
