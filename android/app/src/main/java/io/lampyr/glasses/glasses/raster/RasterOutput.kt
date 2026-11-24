package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.debug.Latency
import io.lampyr.glasses.debug.Trace

import android.content.Context
import android.os.SystemClock
import io.lampyr.glasses.glasses.*
import io.lampyr.glasses.glasses.protocol.RayneoFrame
import io.lampyr.glasses.render.RasterFit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How the lens is kept dark while the display is asleep (pref power.backend, see docs/raster-wire.md). */
enum class PowerBackend { BLACK, PARK }

/**
 * Pure frame-pacing policy. Input and data-change frames go out at once; animation-only (IDLE) frames
 * wait [INPUT_HOLD_MS] after the last key press and then run at one per [idleMs], so a press never
 * queues behind an idle animation frame and the link is not kept busy by spinners.
 */
object FramePolicy {
    const val INPUT_HOLD_MS = 1_500L
    const val IDLE_FRAME_MS = 1_000L
    fun readyAt(kind: Latency.Kind?, now: Long, lastInputAt: Long, lastIdleSentAt: Long, idleMs: Long = IDLE_FRAME_MS): Long =
        if (kind != Latency.Kind.IDLE) now else maxOf(now, lastInputAt + INPUT_HOLD_MS, lastIdleSentAt + idleMs)
    /** Coalescing keeps the oldest unshown key press, so a newer frame that replaces it still carries (and measures) it. */
    fun merge(old: Latency.Meta?, new: Latency.Meta?): Latency.Meta? {
        if (old?.kind != Latency.Kind.INPUT) return new
        val base = new ?: old
        return base.copy(kind = Latency.Kind.INPUT, key = old.key, inputAt = old.inputAt)
    }
    /** Keep-alive cadence: below Android's ~1 s RFCOMM sniff timer while the wearer is interacting. */
    const val KEEPALIVE_IDLE_MS = 2_000L
    const val KEEPALIVE_ASLEEP_MS = 4_000L
    const val KEEPALIVE_ACTIVE_MS = 600L
    const val INTERACTIVE_WINDOW_MS = 10_000L
    /** Firmware caps a TVP session at 600 s; renew at 540 s awake, or after 300 s while nobody can see it. */
    const val RENEW_AWAKE_MS = 540_000L
    const val RENEW_ASLEEP_MS = 300_000L
    const val PARK_DELAY_MS = 1_500L
    val RETRY_BACKOFF_MS = longArrayOf(1_000, 3_000, 10_000)
}

/** One active frame plus one replaceable pending frame; ACK waits never block the audio event reducer. */
class RasterOutput(context: Context, private val scope: CoroutineScope, private val link: TransportLink,
                   private val sequence: AtomicInteger, private val releaseText: suspend (RasterKind) -> Boolean,
                   private val mirror: (RasterFit.Image) -> Unit, private val report: (String) -> Unit,
                   private val wearerExit: () -> Unit = {}, private val lifeLogArmed: () -> Boolean = { false },
                   private val wearerWake: (String) -> Unit = {},
                   /** A long outage ended (shown briefly on the glasses so a stall is explained). */
                   private val recovered: (String) -> Unit = {}, private val releaseDelay: () -> Long = { 500 },
                   private val clock: () -> Long = SystemClock::elapsedRealtime,
                   /** Fit and encode run here (tests pass their own dispatcher to keep virtual time deterministic). */
                   private val compute: CoroutineDispatcher = Dispatchers.Default) : RasterWire {
    private val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
    private val log = DisplayExperimentLog(context)
    private val inbox = Channel<RayneoFrame>(128)
    /** Latest-frame-wins slot; [wake] nudges the worker (new frame, power change, input). */
    /** Fit and TVP2 encoding done ahead, as soon as a frame is offered, so a press never waits for that CPU work. */
    private class Prepared(val levels: Int, val image: RasterFit.Image, val encoded: Tvp2Codec.Encoded?)
    private class Pending(val frame: Frame, val meta: Latency.Meta?, val prepared: Deferred<Prepared>? = null)
    private fun fit(frame: Frame, levels: Int) = RasterFit.fromMaster(frame.levels ?: io.lampyr.glasses.render.GreyFrame.levels(frame.bitmap),
        width = frame.bitmap.width, height = frame.bitmap.height, levels = levels)
    private fun prepare(frame: Frame): Deferred<Prepared>? {
        if (!awake || selectedState.value == null) return null
        val tvp2 = selectedState.value == RasterKind.TVP2
        val levels = prefs.getInt("display.levels", 16)
        return scope.async(compute) {
            val image = fit(frame, levels)
            Prepared(levels, image, if (tvp2 && image.width == 540 && image.height == 220) Tvp2Codec.frame(image) else null)
        }
    }
    private val slotLock = Any()
    private var slot: Pending? = null
    /** A press frame already written behind the frame in flight (Tvp2 look-ahead); it is the next one shown. */
    private var ahead: Pending? = null
    /** Hands the transport a ready press frame while it collects the previous frame's replies. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun takeUrgent(levels: Int): Tvp2Transport.Ahead? = synchronized(slotLock) {
        if (!awake || ahead != null) return null
        val pending = slot ?: return null
        if (pending.meta?.kind != Latency.Kind.INPUT) return null
        val job = pending.prepared ?: return null
        if (!job.isCompleted || job.isCancelled) return null
        val prepared = runCatching { job.getCompleted() }.getOrNull() ?: return null
        val encoded = prepared.encoded ?: return null
        if (prepared.levels != levels) return null
        slot = null; ahead = pending
        Tvp2Transport.Ahead(prepared.image, encoded)
    }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    @Volatile private var sender: RasterTransport? = null
    @Volatile private var forced: RasterKind? = null
    @Volatile private var lastFrame: Frame? = null
    private val capabilityState = MutableStateFlow(RasterCapabilities())
    val capabilities = capabilityState.asStateFlow()
    private val selectedState = MutableStateFlow<RasterKind?>(null)
    val selected = selectedState.asStateFlow()
    private val readyState = MutableStateFlow(false)
    val ready = readyState.asStateFlow()
    private val firmware = MutableStateFlow<String?>(null)
    fun firmwareVersion(value: String) { firmware.value = value }
    private var cacheDevice = "unknown"
    private val cache get() = "$cacheDevice/${firmware.value}"
    private val probeState = MutableStateFlow(false)
    val probing = probeState.asStateFlow()
    private val noteState = MutableStateFlow("Not connected")
    val note = noteState.asStateFlow()
    @Volatile private var generation = 0
    @Volatile private var failureGeneration: Int? = null
    /** Same-backend reopen (renewal, PAUSE, lost session, unpark): no text release, no delay, selection kept. */
    @Volatile private var fastReopen = false
    @Volatile var suspended = false
    // Display power (GlassesTransport.displayAwake). Awake until the power owner says otherwise.
    @Volatile var awake = true; private set
    @Volatile private var sleptAt = 0L
    @Volatile private var wokeAt = 0L
    @Volatile private var blackShown = false
    @Volatile private var needsFrame = false
    @Volatile var parked = false; private set
    @Volatile private var parkedAt = 0L
    @Volatile private var openDarkUntil = 0L
    // Recovery: retry before falling back to the stock text page.
    @Volatile private var retries = 0
    @Volatile private var outageSince = 0L
    @Volatile private var retryAt = 0L
    @Volatile private var sessionLosses = 0
    @Volatile private var openedThisConnection = false
    // Sniff avoidance.
    @Volatile private var interactiveUntil = 0L
    @Volatile private var nudgeWanted = false
    @Volatile private var lastInputAt = Long.MIN_VALUE / 2
    val mode get() = runCatching { DisplayMode.valueOf(prefs.getString("display.mode", "AUTO")!!) }.getOrDefault(DisplayMode.AUTO)
    // glassesctl pref can store any type under a key; a wrong type must never kill the raster worker.
    // Typed read first: prefs.all copies the whole map, and these run in the worker loop.
    private fun raw(key: String): Any? = runCatching { prefs.all[key] }.getOrNull()
    private fun flag(key: String, default: Boolean): Boolean = try { prefs.getBoolean(key, default) } catch (_: ClassCastException) {
        when (val it = raw(key)) { is String -> it.toBooleanStrictOrNull() ?: default; is Number -> it.toInt() != 0; else -> default }
    }
    private fun number(key: String, default: Long): Long = try { prefs.getLong(key, default) } catch (_: ClassCastException) {
        when (val it = raw(key)) { is Number -> it.toLong(); is String -> it.toLongOrNull() ?: default; else -> default }
    }
    /** Cached; the pref listener clears it on any power.* change. Read in the worker loop while dark. */
    @Volatile private var powerBackendCache: PowerBackend? = null
    // One reader for the whole app: DisplayPower's PowerPrefs decides (auto = black until the look-up experiment passes).
    val powerBackend: PowerBackend get() = powerBackendCache ?: (runCatching { io.lampyr.glasses.ui.PowerPrefs.read(prefs.all).sleepBackend }.getOrDefault("black")
        .let { if (it == "park") PowerBackend.PARK else PowerBackend.BLACK }).also { powerBackendCache = it }
    /**
     * A raster open was refused while LifeLog was armed (the R1 gate patch that lets TVP2 open over an armed LifeLog
     * is unverified on hardware). For the rest of this connection acquires disarm LifeLog first and renewals take the
     * acquire path, as before this pass, so the display keeps working either way.
     */
    @Volatile var lifeLogBlocksOpen = false; private set
    /** A pipelined frame failed: stop-and-wait until then (or a new connection), then pipelining is tried again. */
    @Volatile private var pipelineOffUntil = 0L
    /**
     * raster.pipeline (default on) unless pipelined frames failed on several connections in a row (persisted as
     * raster.pipeline.fallback), or a pipelined frame failed on this connection. Setting raster.pipeline again clears the fallback.
     */
    val pipelineEnabled get() = flag("raster.pipeline", true) && !prefs.contains(PIPELINE_FALLBACK) && clock() >= pipelineOffUntil
    private val idleFrameMs get() = number("raster.idleFrameMs", FramePolicy.IDLE_FRAME_MS).coerceAtLeast(100)
    private val provenKey get() = "raster.proven.$cacheDevice"
    /**
     * Raster is expected on this connection: forced by mode, or these glasses (by address, any firmware string)
     * have opened a raster session before. While expected, connect and failures never show the stock caption page.
     */
    val expectsRaster get() = mode == DisplayMode.RASTER || (mode == DisplayMode.AUTO && prefs.getString(provenKey, null) != null)
    /** The text page must stay closed: probing for expected raster, or waiting to retry a failed open. */
    val holdingDisplay get() = (probeState.value && expectsRaster) || (retryAt != 0L && failureGeneration != generation && !suspended)
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("power.")) { powerBackendCache = null; wake.trySend(Unit) }
        if (key == "raster.pipeline") { pipelineOffUntil = 0; prefs.edit().remove(PIPELINE_FALLBACK).remove(PIPELINE_TIMEOUTS).apply() }
        if (key?.startsWith("display.") == true) scope.launch {
            if (key == "display.mode" && worker?.isActive == true) {
                val version = firmware.value
                stop(); begin(version)
            } else if (key == "display.levels" || key == io.lampyr.glasses.ui.PowerPrefs.TEXT_CASE) requeue() // Same session, new pixels.
            // Other display experiments: reopen, fast when the backend stays the same (no stock text page in between).
            else { forced = null; requestReopen("pref $key"); requeue() }
        }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    /** Safe on the Bluetooth reader thread: only volatile writes and channel trySend. */
    fun incoming(frame: RayneoFrame): Boolean {
        val raster = frame.business == 0xf0 || (frame.business == 15 && frame.payload.take(4).toByteArray().toString(Charsets.US_ASCII) == "VPA1")
        if (!raster) return false
        val active = sender
        (active as? Tvp2Transport)?.event(frame.payload)
        (active as? GlasslyTransport)?.event(frame.payload)
        if ((active as? Tvp2Transport)?.wearerExited == true || (active as? GlasslyTransport)?.wearerExited == true) {
            suspended = true
            wearerExit()
            return true // Unsolicited exit is not a response to a future PAGE_OPEN.
        }
        if (active is Tvp2Transport && Tvp2Codec.decode(frame.payload)?.op == 32) {
            // Events are never replies. Only the PAUSE event itself starts recovery, so the CLOSE reply of the
            // paused session still reaches the worker.
            if (active.isPaused) pauseRecover(active)
            return true
        }
        if (!inbox.trySend(frame).isSuccess) report("Raster reply queue overflow; acknowledgement may time out")
        return true
    }
    /**
     * Crown short-press toggles PAUSE in the firmware, which freezes our page and can only be undone by closing
     * and reopening it. Reopen at once (fast path), and while dark treat the crown as a wearer wake.
     */
    private fun pauseRecover(active: RasterTransport) {
        if (sender !== active) return
        Trace.log("Raster", "PAUSE (crown) recovery: fast reopen awake=$awake")
        requestReopen("pause")
        if (!awake) wearerWake("crown")
    }
    private fun requestReopen(reason: String) {
        Trace.log("Raster", "Reopen fast reason=$reason")
        fastReopen = true; bump(); wake.trySend(Unit)
    }
    private fun bump() { synchronized(slotLock) { generation++ } }
    override fun reserveSequences(count: Int): Int = sequence.getAndAdd(count) and 65535
    override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
        val seq = sequence ?: reserveSequences(1)
        val frame = RayneoFrame(business, seq and 65535, payload)
        if (ble) link.sendBle(frame) else link.send(frame)
        return seq
    }
    override suspend fun awaitReply(business: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray = withTimeout(timeoutMs) {
        while (true) {
            val frame = inbox.receive()
            if (frame.business == business && matches(frame.payload)) return@withTimeout frame.payload
        }
        @Suppress("UNREACHABLE_CODE") byteArrayOf()
    }
    /** Timeout as a select clause, not a cancellation, so a reply that arrives just then stays in the inbox. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun awaitReplyOrNull(business: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray? {
        // A non-matching frame (a stray PING reply) restarts the short wait; callers use this for brief slices.
        while (true) {
            val frame = select<RayneoFrame?> {
                inbox.onReceive { it }
                onTimeout(timeoutMs) { null }
            } ?: return null
            if (frame.business == business && matches(frame.payload)) return frame.payload
        }
    }
    fun offer(frame: Frame) {
        val meta = Latency.metaFor(frame)?.copy(offeredAt = Latency.now())
        if (meta?.kind == Latency.Kind.INPUT) lastInputAt = clock()
        val prepared = runCatching { prepare(frame) }.getOrNull()
        synchronized(slotLock) { slot?.prepared?.cancel(); slot = Pending(frame, FramePolicy.merge(slot?.meta, meta), prepared); lastFrame = frame }
        wake.trySend(Unit)
    }
    private fun requeue() {
        synchronized(slotLock) { if (slot == null) lastFrame?.let { slot = Pending(it, null) } }
        wake.trySend(Unit)
    }
    /** Called on every controller press: keep the link out of sniff for a while and pre-wake it now. */
    fun noteInteraction() {
        val now = clock()
        interactiveUntil = now + FramePolicy.INTERACTIVE_WINDOW_MS; lastInputAt = now; nudgeWanted = true
        wake.trySend(Unit)
    }
    /** GlassesTransport.displayAwake: BLACK sends one black frame and then nothing; PARK also closes the page. */
    fun displayAwake(value: Boolean) {
        if (awake == value) return
        val now = clock()
        awake = value
        if (!value) { sleptAt = now; blackShown = false; openDarkUntil = 0 }
        else {
            // Frames rendered while dark are stale (usually the UI's own black frame): wait briefly for the wake render.
            synchronized(slotLock) { slot = null; ahead = null }
            wokeAt = now; needsFrame = true; openDarkUntil = 0; interactiveUntil = now + FramePolicy.INTERACTIVE_WINDOW_MS
        }
        Trace.log("Raster", "Display awake=$value backend=$powerBackend parked=$parked")
        wake.trySend(Unit)
    }
    /**
     * A stock screen-on signal (screen_status, crown on the stock launcher) while parked: reopen our page at
     * once so a black canvas covers the stock home, and ask the power owner to wake. If it does not wake
     * within 3 s the page is parked again.
     */
    fun stockScreenOn(source: String) {
        val now = clock()
        if (!parked || awake || now - parkedAt < FramePolicy.PARK_DELAY_MS) {
            Trace.log("Raster", "Stock screen signal source=$source ignored parked=$parked awake=$awake"); return
        }
        Trace.log("Raster", "Stock screen signal source=$source while parked: reopen dark and wake")
        openDarkUntil = now + 3_000
        wake.trySend(Unit)
        wearerWake(source)
    }
    /** Explicit setup confirmation permits attempting an unsupported backend, but never fabricates its ACKs. */
    fun forceTry(kind: RasterKind) { scope.launch {
        prefs.edit().remove("raster.cache.$cache").apply()
        forced = kind; bump(); failureGeneration = null; retryAt = 0; retries = 0; requeue()
    } }
    val wantsRaster get() = !suspended && failureGeneration != generation && (forced ?: capabilityState.value.select(mode)) != null
    private suspend fun probeTvp2(expects: Boolean): Boolean {
        if (Tvp2Transport(this).probe()) return true
        if (!expects) return false
        // These glasses drew pictures before: a missed HELLO is far more likely than lost support.
        for (wait in longArrayOf(1_000, 3_000)) {
            noteState.value = "TVP2 HELLO unanswered; retrying in ${wait / 1000}s"
            delay(wait)
            if (Tvp2Transport(this).probe()) return true
        }
        return false
    }
    fun begin(knownFirmware: String? = null) {
        cacheDevice = prefs.getString("glasses.address", "unknown").orEmpty()
        val previousWorker = worker
        previousWorker?.cancel(); firmware.value = knownFirmware; readyState.value = false; capabilityState.value = RasterCapabilities(); selectedState.value = null
        probeState.value = mode != DisplayMode.TEXT; forced = null; failureGeneration = null
        retries = 0; retryAt = 0; sessionLosses = 0; openedThisConnection = false; parked = false; blackShown = false; fastReopen = false
        pipelineOffUntil = 0; pipelineConfirmed = false; lifeLogBlocksOpen = false
        synchronized(slotLock) { ahead = null }
        while (inbox.tryReceive().isSuccess) { /* discard old connection's replies */ }
        worker = scope.launch {
            // The worker runs on the raster thread while begin() is called from the reducer: let the old one finish first.
            previousWorker?.join()
            try {
                if (mode != DisplayMode.TEXT) {
                    val expects = expectsRaster
                    // Wait for this connection's actual OsVersion, never trust a previous firmware string. When raster is
                    // expected, do not hold the probe for it: the capability is re-proven by HELLO either way.
                    val version = withTimeoutOrNull(if (expects) 300 else 1_500) { firmware.filterNotNull().first() }
                    val cached = if (version == null) null else prefs.getString("raster.cache.$cache", null)
                        ?.let { runCatching { Json.decodeFromString<RasterCapabilities>(it) }.getOrNull() }
                    noteState.value = "Probing TVP2 HELLO and Glassly IDENT"
                    // Only affirmative replies survive reconnects. Old negative cache entries
                    // and a missing backend in a partial positive entry must be probed again.
                    val provenTvp2 = expects && prefs.getString(provenKey, RasterKind.TVP2.name) == RasterKind.TVP2.name
                    // Proven TVP2 glasses keep TVP2 even if HELLO goes unanswered: open() then retries forever (see failed()).
                    val turbo = cached?.turbo == true || probeTvp2(provenTvp2) ||
                        (provenTvp2 && mode == DisplayMode.AUTO).also { if (it) report("Glasses display did not answer HELLO; retrying the image display") }
                    // TVP2 wins selection, so a BLE IDENT round trip is only worth it without TVP2.
                    capabilityState.value = RasterCapabilities(turbo, cached?.glassly ?: if (turbo) null else GlasslyTransport.probe(this@RasterOutput))
                    if (version != null) {
                        val editor = prefs.edit()
                        if (capabilityState.value.turbo || capabilityState.value.glassly != null)
                            editor.putString("raster.cache.$cache", Json.encodeToString(capabilityState.value))
                        else editor.remove("raster.cache.$cache")
                        editor.apply()
                    }
                    if (!capabilityState.value.turbo && capabilityState.value.glassly == null) prefs.edit().remove(provenKey).apply()
                }
                val (turbo, glassly) = capabilityState.value
                noteState.value = "TVP2: $turbo; Glassly: ${glassly?.name ?: "no IDENT"}"
                Trace.log("Raster", "Probe ${noteState.value} capabilities=${capabilityState.value}")
                log.add("Raster capability probe", noteState.value)
            } catch (e: TimeoutCancellationException) { report("Raster probe: ${e.message}") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("Raster probe: ${e.message}") }
            finally { probeState.value = false }
            serve()
        }
    }

    private suspend fun CoroutineScope.serve() {
        var currentGeneration = -1
        var lastActivity = 0L
        var openedAt = 0L
        var acknowledged = 0L
        var lastIdleSentAt = Long.MIN_VALUE / 2
        var lastSendStart = Long.MIN_VALUE / 2
        var previousBegin = 0L
        var previousCommit = 0L
        while (isActive) {
            val desired = if (suspended || failureGeneration == generation) null else forced ?: capabilityState.value.select(mode)
            var now = clock()
            val openDark = now < openDarkUntil
            val stayParked = parked && desired == RasterKind.TVP2 && !awake && !openDark && currentGeneration == generation
            if (!stayParked && (desired != selectedState.value || currentGeneration != generation || parked)) {
                val fast = desired != null && desired == selectedState.value && (fastReopen || parked) && !lifeLogBlocksOpen
                val wasParked = parked
                fastReopen = false; parked = false
                if (!fast) readyState.value = false
                try { sender?.close() } catch (_: TimeoutCancellationException) { } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                sender = null; currentGeneration = generation
                if (!fast) selectedState.value = null
                if (desired != null) try {
                    if (!fast) {
                        if (retryAt > now) { currentGeneration = -1; withTimeoutOrNull(minOf(100, retryAt - now)) { wake.receive() }; continue }
                        if (!releaseText(desired) || suspended) { currentGeneration = -1; delay(25); continue }
                        // Let a stock page we really just closed go before custom OPEN (BUSY retries cover the rest).
                        val settle = releaseDelay()
                        if (settle > 0) delay(settle)
                        if (suspended) { currentGeneration = -1; continue }
                    }
                    Trace.log("Raster", "Backend selection=$desired forced=$forced mode=$mode fast=$fast${if (wasParked) " unpark" else ""}")
                    selectedState.value = desired
                    val opened = when (desired) {
                        RasterKind.TVP2 -> Tvp2Transport(this@RasterOutput, clock = Latency::now, compute = compute).also {
                            if (fast) { it.busyRetryMs = 40; it.busyRetries = 25 }
                        }
                        RasterKind.GLASSLY -> GlasslyTransport(this@RasterOutput, capabilityState.value.glassly ?:
                            GlasslyIdentity(540, 220, 240, 7, 0x1a, "forced/unverified"))
                    }
                    sender = opened
                    opened.open(); noteState.value = "$desired session open; render ACKs required"
                    log.add("Raster session", noteState.value)
                    if (!openedThisConnection) prefs.edit().putString(provenKey, desired.name).apply()
                    openedThisConnection = true; retryAt = 0
                    // A fresh page is a black canvas; a dark display shows our black frame, an awake one the cached frame.
                    blackShown = false
                    if (awake) { needsFrame = true; requeue() }
                    lastActivity = clock(); openedAt = lastActivity
                    acknowledged = 0
                } catch (e: TimeoutCancellationException) { failed(e) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
                else if (mode == DisplayMode.RASTER && !suspended) report("Forced Raster unavailable: no supported capability reply; choose Text grid or run a confirmed forced try")
            }
            readyState.value = (failureGeneration != generation || selectedState.value == null) && !(retryAt != 0L && desired != null)
            val active = sender
            if (active == null) { withTimeoutOrNull(if (awake) 100L else 500L) { wake.receive() }; continue }
            try {
                now = clock()
                val tvp = active as? Tvp2Transport
                if (tvp?.isPaused == true) { pauseRecover(active); continue }
                if (tvp != null) {
                    val age = now - openedAt
                    if (age >= FramePolicy.RENEW_AWAKE_MS || (!awake && blackShown && age >= FramePolicy.RENEW_ASLEEP_MS && now - sleptAt >= FramePolicy.PARK_DELAY_MS)) {
                        requestReopen("renewal age=${age / 1000}s awake=$awake"); continue
                    }
                    if (!awake && blackShown && now >= openDarkUntil && powerBackend == PowerBackend.PARK && now - sleptAt >= FramePolicy.PARK_DELAY_MS) {
                        Trace.log("Raster", "Park: closing TVP page while dark so stock sleep/look-up can run")
                        try { active.close() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        sender = null; parked = true; parkedAt = clock()
                        continue
                    }
                }
                // What to send: a black frame once while dark, otherwise the pending frame when the policy allows.
                val black = !awake && !blackShown
                var item: Pending? = null
                var fromAhead = false
                // Dark: nothing but keep-alive, renewal and park are due; power and input changes wake the loop anyway.
                var waitMs = if (awake) 100L else 500L
                if (black) { item = null; synchronized(slotLock) { ahead = null } }
                else if (awake) synchronized(slotLock) {
                    val pending = slot
                    val early = ahead
                    // Already on the wire behind the last frame: show it next, whatever else is pending.
                    if (early != null) { item = early; ahead = null; fromAhead = true }
                    else if (pending == null && needsFrame && now - wokeAt >= 150) lastFrame?.let { item = Pending(it, null) }
                    else if (pending != null) {
                        val due = FramePolicy.readyAt(pending.meta?.kind, now, lastInputAt, lastIdleSentAt, idleFrameMs)
                        if (due <= now) { item = pending; slot = null } else waitMs = minOf(waitMs, due - now)
                    }
                }
                if (!black && item == null) {
                    val keepAlive = if (!awake) FramePolicy.KEEPALIVE_ASLEEP_MS else FramePolicy.KEEPALIVE_IDLE_MS
                    if (now - lastActivity >= keepAlive) {
                        active.keepAlive(); lastActivity = clock()
                        if (tvp?.isPaused == true) { pauseRecover(active); continue }
                    } else if (tvp != null && awake && (nudgeWanted && now - lastActivity >= 800 ||
                            now < interactiveUntil && now - lastActivity >= FramePolicy.KEEPALIVE_ACTIVE_MS)) {
                        tvp.nudge(); lastActivity = clock()
                    }
                    nudgeWanted = false
                    withTimeoutOrNull(waitMs) { wake.receive() }
                    continue
                }
                // Never two BEGINs closer than 50 ms; with real frames taking >200 ms this adds nothing.
                val gap = if (fromAhead) 0 else 50 - (clock() - lastSendStart)
                if (gap > 0) delay(gap)
                if (!black && !fromAhead) synchronized(slotLock) {
                    // Latest frame wins at send time, too, keeping the oldest unshown key press.
                    val newer = slot
                    if (newer != null && item != null && newer.meta?.kind != Latency.Kind.IDLE) {
                        item = Pending(newer.frame, FramePolicy.merge(item!!.meta, newer.meta), newer.prepared); slot = null
                    }
                }
                lastSendStart = clock()
                val levels = prefs.getInt("display.levels", 16)
                val sending = item
                val fitStart = clock()
                val prepared = sending?.prepared?.let { job ->
                    try { job.await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); null }
                    catch (_: Exception) { null }
                }?.takeIf { it.levels == levels }
                val image = if (sending == null) RasterFit.Image(540, 220, levels, ByteArray(540 * 220))
                    else prepared?.image ?: withContext(compute) { fit(sending.frame, levels) }
                if (sending?.meta?.kind == Latency.Kind.INPUT) Trace.log("Raster", "fit ms=${clock() - fitStart} gapWait=${maxOf(0, gap)}")
                var timing: RasterFrameTiming? = null
                tvp?.pipeline = pipelineEnabled
                tvp?.onFrameTiming = { timing = it }
                tvp?.nextUrgent = { takeUrgent(levels) }
                val actual = if (tvp != null) tvp.show(image, prepared?.encoded) else active.show(image)
                lastActivity = clock()
                if (sending == null) blackShown = true else needsFrame = false
                if (sending?.meta?.kind == Latency.Kind.IDLE) lastIdleSentAt = lastActivity
                if (retries > 0) {
                    val downS = (clock() - outageSince) / 1000
                    Trace.log("Raster", "Recovered after $retries retries, ${downS}s")
                    log.add("Raster recovered", "after $retries retries, ${downS}s")
                    if (retries >= FramePolicy.RETRY_BACKOFF_MS.size) recovered("Display link dropped ${downS}s; reconnected")
                }
                retries = 0; sessionLosses = 0
                val t = timing
                if (acknowledged++ == 0L) log.add("Raster first frame", "${active.kind} ${actual.width}x${actual.height} acknowledged; optical appearance unverified")
                Trace.log("Raster", "ACK backend=${active.kind} width=${actual.width} height=${actual.height} frame=$acknowledged sent=${t?.sent ?: true} " +
                    "kind=${if (sending == null) "BLACK" else sending.meta?.kind ?: "CHANGE"} pipelined=${t?.pipelined ?: false}" + (t?.let { " wireMs=${it.commitRx - it.beginTx}" } ?: ""))
                val meta = sending?.meta
                if (t != null && t.sent && meta != null) {
                    Latency.committed(meta, t.beginTx, t.commitRx, t.bytes, t.pipelined, meta.offeredAt in previousBegin until previousCommit)
                }
                if (t != null && t.sent) { previousBegin = t.beginTx; previousCommit = t.commitRx; if (t.pipelined) pipelineSucceeded() }
                mirror(actual)
            } catch (_: RasterFrameBusy) { requeue(); delay(50) }
            catch (e: RasterPipelineRefused) {
                // A crown PAUSE or another reopen racing the burst says nothing about pipelining support.
                if (currentGeneration != generation || (active as? Tvp2Transport)?.acceptingFrames == false)
                    Trace.log("Raster", "Pipelined frame aborted by pause/reopen: ${e.message}")
                else pipelineRefused(e)
                requestReopen("pipeline refused"); requeue()
            }
            catch (e: RasterSessionLost) {
                if (sessionLosses++ < 2) { Trace.log("Raster", "Session lost: ${e.message}"); requestReopen("session lost"); requeue() } else failed(e)
            }
            catch (e: TimeoutCancellationException) { failedUnlessReopening(e, currentGeneration) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { failedUnlessReopening(e, currentGeneration) }
        }
    }
    /**
     * Automatic stop-and-wait fallback. Any failed pipelined frame (a non-OK result, counters, or a timeout, which can
     * also be a dropped link) turns pipelining off for [PIPELINE_RETRY_MS] or until the next connection. It is persisted
     * only after [PIPELINE_FAILURES_TO_PERSIST] failures in a row with no pipelined success in between: on TMIG2-VIDEO-R1
     * a single refusal once disabled pipelining for good, which tripled every press-to-display time (6 round trips of
     * ~100-200 ms each instead of one burst).
     */
    private fun pipelineRefused(e: RasterPipelineRefused) {
        val failures = number(PIPELINE_TIMEOUTS, 0) + 1
        val persist = failures >= PIPELINE_FAILURES_TO_PERSIST
        pipelineOffUntil = clock() + PIPELINE_RETRY_MS
        val editor = prefs.edit().putLong(PIPELINE_TIMEOUTS, failures)
        if (persist) editor.putString(PIPELINE_FALLBACK, e.message ?: "refused")
        editor.apply()
        Trace.error("Raster", "Pipelined frame refused; stop-and-wait ${if (persist) "from now on (pref $PIPELINE_FALLBACK)" else "for this connection ($failures/$PIPELINE_FAILURES_TO_PERSIST)"}", e)
        log.add("Raster pipeline", "refused: ${e.message}; stop-and-wait ${if (persist) "from now on" else "for this connection"}")
    }
    private fun pipelineSucceeded() {
        if (pipelineConfirmed) return
        pipelineConfirmed = true
        if (prefs.contains(PIPELINE_TIMEOUTS)) prefs.edit().remove(PIPELINE_TIMEOUTS).apply()
        Trace.log("Raster", "Pipelined frame acknowledged; TVP2 accepts back-to-back packets on this firmware")
    }
    @Volatile private var pipelineConfirmed = false
    /** A frame that failed after a reopen was already requested (PAUSE, renewal, pref) is not a raster failure. */
    private fun failedUnlessReopening(e: Exception, current: Int) {
        if (current != generation) Trace.log("Raster", "Frame aborted by pending reopen: ${e.message}") else failed(e)
    }
    private fun failed(e: Exception) {
        Trace.error("Raster", "Failure", e)
        if (!lifeLogBlocksOpen && lifeLogArmed() && Regex("refused|rejected", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) {
            lifeLogBlocksOpen = true
            Trace.log("Raster", "Refused while LifeLog armed: later opens disarm LifeLog first (connection-scoped fallback)")
            log.add("Raster LifeLog gate", "refused while LifeLog armed: ${e.message}; disarming LifeLog before opens on this connection")
        }
        // These glasses drew pictures: retry with backoff forever. The stock text UI is never a fallback for them
        // (a deliberate choice): a clear error and a stalled picture beat the text UI. Choose Text grid to opt out.
        if ((expectsRaster || openedThisConnection) && !suspended) {
            val wait = FramePolicy.RETRY_BACKOFF_MS[minOf(retries, FramePolicy.RETRY_BACKOFF_MS.size - 1)]
            if (retries == 0) outageSince = clock()
            retries++
            retryAt = clock() + wait
            noteState.value = "Raster retry ${retries} in ${wait / 1000}s: ${e.message}"
            log.add("Raster retry", noteState.value)
            Trace.log("Raster", noteState.value)
            if (retries == FramePolicy.RETRY_BACKOFF_MS.size) report("Glasses display link lost (${e.message}); still retrying every ${wait / 1000}s")
            bump()
            return
        }
        retryAt = 0
        failureGeneration = generation
        noteState.value = if (lifeLogArmed() && Regex("refused|rejected", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))
            "raster refused while LifeLog armed: ${e.message}" else "Raster stopped: ${e.message}"
        log.add("Raster failure", noteState.value)
        report(noteState.value)
        // The next loop closes the page. Auto then exposes the stock text path; Raster forced stays blank.
    }
    suspend fun suspendAndClose() {
        suspended = true
        wake.trySend(Unit)
        withTimeout(5_000) { selected.first { it == null } }
    }
    suspend fun stop() {
        worker?.cancelAndJoin(); worker = null
        try { withTimeoutOrNull(750) { sender?.close() } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
        finally { sender = null; parked = false; selectedState.value = null; probeState.value = false; readyState.value = false }
    }
    private companion object {
        const val PIPELINE_FALLBACK = "raster.pipeline.fallback"
        /** Consecutive connections whose pipelined frame failed (refusal or timeout); cleared by a pipelined success. */
        const val PIPELINE_TIMEOUTS = "raster.pipeline.timeouts"
        const val PIPELINE_FAILURES_TO_PERSIST = 3
        const val PIPELINE_RETRY_MS = 120_000L
    }
    fun dispose() { prefs.unregisterOnSharedPreferenceChangeListener(listener); worker?.cancel(); inbox.close(); wake.close() }
}
