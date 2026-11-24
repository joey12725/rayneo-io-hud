// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// RFCOMM UUID/read/write behaviour adapted from OpenRayneo; lifecycle/queue implementation modified for RayNeo iO HUD.
@file:Suppress("MissingPermission")
package io.lampyr.glasses.glasses

import io.lampyr.glasses.debug.Trace

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.lampyr.glasses.glasses.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The socket seam permits unit tests without Bluetooth or Android runtime. */
interface RayNeoSocket : Closeable {
    val input: InputStream
    val output: OutputStream
    val mtu: Int
}
interface RayNeoSession : Closeable {
    val socket: RayNeoSocket
    val bleLost: Deferred<Unit>?
    suspend fun sendBle(bytes: ByteArray) { error("BLE business writes unavailable in this session") }
}
fun interface RayNeoConnector {
    suspend fun open(address: String, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession
    /** The link calls this one; [attempt] says why and how cheaply to look for the glasses. */
    suspend fun open(address: String, attempt: LinkAttempt, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession = open(address, onBleFrame)
}
private val noRetry = MutableStateFlow(RetryStatus()).asStateFlow()

interface TransportLink {
    val connectionState: StateFlow<ConnectionState>
    val lastError: StateFlow<String?>
    val frames: SharedFlow<RayneoFrame>
    fun connect(address: String)
    fun disconnect()
    fun retryIfIdle()
    /** Reconnect supervisor status (searching, waiting, stopped on a real error). */
    val retry: StateFlow<RetryStatus> get() = noRetry
    /**
     * Something suggests the glasses may be reachable now (Bluetooth event, screen on, controller key): try at once
     * instead of waiting out the backoff. [userAction] also restarts a link stopped on a terminal error.
     */
    fun wake(reason: String, userAction: Boolean = false) { if (userAction) retryIfIdle() }
    fun close()
    suspend fun send(frame: RayneoFrame)
    suspend fun sendBle(frame: RayneoFrame)
    suspend fun sendStockBle(frame: RayneoFrame) { sendBle(frame) }
    /**
     * Called on the Bluetooth reader thread for every inbound RFCOMM frame before it is published; returning true
     * consumes it. Raster replies use this so their round trips never wait for the main thread.
     */
    var rasterTap: ((RayneoFrame) -> Boolean)? get() = null; set(_) {}
}

class RayNeoLink(private val connector: RayNeoConnector,
                 private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                 private val clock: () -> Long = ReconnectPolicy::linkNow) : TransportLink {
    constructor(context: Context) : this(AndroidConnector(context.applicationContext))
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = state.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    override val lastError = error.asStateFlow()
    private val incoming = MutableSharedFlow<RayneoFrame>(extraBufferCapacity = 128)
    override val frames = incoming.asSharedFlow()
    private var supervisor: Job? = null
    @Volatile private var session: RayNeoSession? = null
    @Volatile private var queue: Channel<Write>? = null
    @Volatile override var rasterTap: ((RayneoFrame) -> Boolean)? = null
    private data class Write(val bytes: ByteArray, val done: CompletableDeferred<Unit> = CompletableDeferred())

    @Volatile var reconnectAttempt = 0; private set
    @Volatile var reconnectDelayMs = 0L; private set
    private fun setState(value: ConnectionState) { if (state.value != value) Trace.log("Link", "connectionState ${state.value} -> $value"); state.value = value }
    private fun setError(value: String?) { if (error.value != value) Trace.log("Link", "lastError=$value"); error.value = value }
    private var lastAddress: String? = null
    private val retryState = MutableStateFlow(RetryStatus())
    override val retry = retryState.asStateFlow()
    private data class Wake(val reason: String, val userAction: Boolean)
    private val wakes = Channel<Wake>(Channel.CONFLATED)
    private fun setRetry(value: RetryStatus) { retryState.value = value }
    /** The attempt in flight, so a wake can hurry a passive background one. */
    @Volatile private var current: LinkAttempt? = null

    /** A controller press: try now, and restart a link that stopped on a terminal error. */
    override fun retryIfIdle() = wake("controller key", userAction = true)

    @Synchronized override fun wake(reason: String, userAction: Boolean) {
        val address = lastAddress
        if (address == null) { Trace.log("Link", "Wake ignored reason=$reason: no glasses selected or link off"); return }
        val status = retryState.value
        when {
            supervisor?.isActive != true || status.phase == RetryPhase.STOPPED -> if (userAction) { Trace.log("Link", "Wake restarts stopped link reason=$reason"); connect(address, reason) }
                else Trace.log("Link", "Wake ignored reason=$reason: stopped on ${status.error}")
            status.phase == RetryPhase.WAITING -> { Trace.log("Link", "Wake reason=$reason userAction=$userAction: retrying now"); wakes.trySend(Wake(reason, userAction)) }
            status.phase == RetryPhase.CONNECTED -> Unit // Every controller press lands here while connected.
            // A passive background attempt can wait minutes; switch it to an active connect now instead of ignoring the wake.
            status.phase == RetryPhase.ATTEMPTING && current?.passive == true && current?.hurry?.complete(reason) == true ->
                Trace.log("Link", "Wake reason=$reason userAction=$userAction: hurrying background attempt=${status.attempt}")
            else -> Trace.log("Link", "Wake ignored reason=$reason: ${status.phase.name.lowercase()}")
        }
    }

    override fun connect(address: String) = connect(address, "connect")

    @Synchronized fun connect(address: String, reason: String) {
        disconnect()
        Trace.log("Link", "Connect address=$address reason=$reason")
        reconnectAttempt = 0; reconnectDelayMs = 0
        lastAddress = address
        while (wakes.tryReceive().isSuccess) Unit
        supervisor = scope.launch {
            var failures = 0
            var connectedAt: Long? = null
            var why = reason
            var urgent = true
            var user = true
            while (isActive) {
                connectedAt = null
                setState(ConnectionState.CONNECTING)
                val background = failures >= ReconnectPolicy.FAST_ATTEMPTS
                val attempt = LinkAttempt(failures + 1, why, background, urgent, mayBond = !background || user)
                current = attempt
                setRetry(RetryStatus(RetryPhase.ATTEMPTING, attempt.number, why, background, error = error.value))
                try {
                    Trace.log("Link", "Connection attempt=${attempt.number} reason=$why mode=${if (background) "background" else "fast"} urgent=$urgent mayBond=${attempt.mayBond}")
                    val active = connector.open(address, attempt) { frame ->
                        Trace.frame("RX", "BLE", frame)
                        if (!incoming.tryEmit(frame.copy(receivedAt = System.currentTimeMillis()))) setError("BLE receive queue full; packet dropped")
                    }
                    Trace.log("Link", "RFCOMM opened mtu=${active.socket.mtu}")
                    reconnectDelayMs = 0
                    session = active
                    val writes = Channel<Write>(32)
                    queue = writes
                    try {
                        coroutineScope {
                            setState(ConnectionState.CONNECTED)
                            setError(null)
                            setRetry(RetryStatus(RetryPhase.CONNECTED, attempt.number, why, background))
                            connectedAt = clock()
                            launch {
                                for (write in writes) {
                                    try {
                                        for (chunk in FrameCodec.segment(write.bytes, active.socket.mtu)) active.socket.output.write(chunk)
                                        active.socket.output.flush()
                                        write.done.complete(Unit)
                                    } catch (e: Throwable) { write.done.completeExceptionally(e); throw e }
                                }
                            }
                            launch {
                                val decoder = FrameCodec.StreamDecoder()
                                val buffer = ByteArray(4096)
                                while (isActive) {
                                    val count = active.socket.input.read(buffer)
                                    check(count >= 0) { "RFCOMM stream closed" }
                                    if (count == 0) continue
                                    for (frame in decoder.append(buffer.copyOf(count))) {
                                        Trace.frame("RX", "RFCOMM", frame)
                                        if (frame.acknowledgement) continue
                                        if (frame.flags and 1 != 0) {
                                            check(writes.trySend(Write(FrameCodec.encode(FrameCodec.acknowledgement(frame)))).isSuccess) { "ACK queue full" }
                                        }
                                        val received = frame.copy(receivedAt = System.currentTimeMillis())
                                        // A tap failure must not kill the link; the frame then takes the ordinary path.
                                        if (runCatching { rasterTap?.invoke(received) }.getOrNull() == true) continue
                                        incoming.emit(received)
                                    }
                                }
                            }
                            active.bleLost?.let { lost -> launch {
                                try { lost.await() }
                                catch (e: TimeoutCancellationException) {
                                    // A child ending with CancellationException does not fail coroutineScope.
                                    throw IllegalStateException("BLE operation timed out", e)
                                }
                                error("BLE authentication link closed")
                            } }
                            try { awaitCancellation() } finally { active.close() }
                        }
                    } finally {
                        // Closing the socket unblocks native reads/writes before coroutine cancellation joins.
                        active.close()
                        writes.close()
                        while (true) (writes.tryReceive().getOrNull() ?: break).done.completeExceptionally(IllegalStateException("Link disconnected"))
                        if (queue === writes) queue = null
                        if (session === active) session = null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    Trace.error("Link", "Connection failure ${e.javaClass.simpleName}: ${e.message}", e)
                    currentCoroutineContext().ensureActive() // A deliberate disconnect may unblock a native read with EOF.
                    if (connectedAt?.let { clock() - it >= 30_000 } == true) failures = 0
                    setState(ConnectionState.ERROR)
                    val message = ReconnectPolicy.message(e)
                    // Protocol §9 stops after three attempts (5/10/15 s); a sleeping unit then never came back. We keep the
                    // fast budget, then retry in the background forever. Only failures retrying cannot fix stop the link.
                    if (isTerminal(message)) {
                        val stopped = "$message. Press Connect to retry."
                        setError(stopped)
                        setRetry(RetryStatus(RetryPhase.STOPPED, attempt.number, why, background, error = stopped))
                        Trace.error("Link", "Terminal error: $message attempts=${attempt.number}")
                        break
                    }
                    setError(message)
                    reconnectDelayMs = reconnectDelay(failures++); reconnectAttempt = failures
                    val nextBackground = failures >= ReconnectPolicy.FAST_ATTEMPTS
                    while (wakes.tryReceive().isSuccess) Unit // Wakes that arrived mid-attempt are stale.
                    setRetry(RetryStatus(RetryPhase.WAITING, failures, why, nextBackground, clock() + reconnectDelayMs, message))
                    Trace.log("Link", "Reconnect attempt=${failures + 1} delayMs=$reconnectDelayMs mode=${if (nextBackground) "background" else "fast"}")
                    val wake = withTimeoutOrNull(reconnectDelayMs) { wakes.receive() }
                    why = wake?.reason ?: if (connectedAt != null) "link lost" else "backoff"; urgent = wake != null; user = wake?.userAction == true
                    // A person pressing a key or Connect gets the fast budget (and the pairing dialog) back.
                    if (user) failures = 0
                }
            }
        }
    }

    override suspend fun send(frame: RayneoFrame) {
        if (frame.business == 9) require(Envelope.decode(frame.payload).type in setOf(1, 2, 11)) { "Unsafe OTA type" }
        Trace.frame("TX", "RFCOMM", frame)
        val writes = queue ?: error("Glasses disconnected")
        val item = Write(FrameCodec.encode(frame))
        writes.send(item)
        item.done.await()
    }

    override suspend fun sendBle(frame: RayneoFrame) {
        if (frame.business == 9) require(Envelope.decode(frame.payload).type in setOf(1, 2, 11)) { "Unsafe OTA type" }
        Trace.frame("TX", "BLE", frame)
        require(frame.business == 0xf0) { "Only Glassly raster business is routed through this seam" }
        (session ?: error("Glasses disconnected")).sendBle(FrameCodec.encode(frame))
    }

    /** Stock envelope probes share the authenticated GATT queue and the RFCOMM OTA guard. */
    override suspend fun sendStockBle(frame: RayneoFrame) {
        require(frame.business != 0x10) { "Pairing commands are owned by authentication" }
        val envelope = Envelope.decode(frame.payload)
        if (frame.business == 9) require(envelope.type in setOf(1, 2, 11)) { "Unsafe OTA type" }
        Trace.frame("TX", "BLE", frame)
        (session ?: error("Glasses disconnected")).sendBle(FrameCodec.encode(frame))
    }

    @Synchronized override fun disconnect() {
        Trace.log("Link", "Disconnect")
        reconnectDelayMs = 0
        supervisor?.cancel(); supervisor = null
        lastAddress = null; current = null
        session?.let { runCatching { it.close() } }; session = null
        queue = null
        setState(ConnectionState.DISCONNECTED)
        setRetry(RetryStatus())
    }
    override fun close() { Trace.log("Link", "Close"); disconnect(); scope.cancel() }
    companion object {
        /** Fast attempts before background retries (there is no final attempt any more). */
        const val MAX_ATTEMPTS = ReconnectPolicy.FAST_ATTEMPTS
        fun reconnectDelay(attempt: Int): Long = ReconnectPolicy.delayMs(attempt)
        fun isTerminal(message: String) = ReconnectPolicy.isTerminal(message)
    }
}

private class AndroidConnector(private val context: Context) : RayNeoConnector {
    override suspend fun open(address: String, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession = open(address, LinkAttempt(1, "connect"), onBleFrame)
    override suspend fun open(address: String, attempt: LinkAttempt, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession {
        OfficialApp.traceIfChanged(context)
        val auth = BleAuthenticator(context)
        val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val (device, plan) = auth.locate(address, prefs.getString("glasses.bleAddress", null), attempt)
        val expected = device.classicAddress ?: address.takeUnless { it.equals(device.bleAddress, true) }
        val ble = auth.authenticate(device, expected, onBleFrame, attempt.mayBond, plan.autoConnect, plan.windowMs, attempt.hurry)
        var socket: BluetoothSocket? = null
        try {
            val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java).adapter)
            adapter.cancelDiscovery()
            val pending = adapter.getRemoteDevice(ble.classicAddress).createRfcommSocketToServiceRecord(UUID.fromString("99999999-9999-9999-9999-999999999999"))
            Trace.log("Link", "RFCOMM socket connect classic=${ble.classicAddress}")
            socket = pending
            coroutineScope {
                val timeout = launch { delay(20_000); runCatching { pending.close() } }
                try { withContext(Dispatchers.IO) { pending.connect() }; ensureActive() }
                finally { timeout.cancel(); if (!isActive) pending.close() }
            }
            return object : RayNeoSession {
                override val socket = object : RayNeoSocket {
                    override val input get() = pending.inputStream
                    override val output get() = pending.outputStream
                    // Android's negotiated transmit size is not reliable on every RFCOMM stack.
                    // A whole frame is also supported upstream; conservative chunks preserve stream semantics.
                    override val mtu = runCatching { pending.maxTransmitPacketSize }.getOrDefault(0).takeIf { it > 0 } ?: 1011
                    override fun close() { runCatching { pending.close() } }
                }
                override val bleLost = ble.lost
                override suspend fun sendBle(bytes: ByteArray) = ble.sendBusiness(bytes)
                override fun close() { socket.close(); ble.close() }
            }
        } catch (e: Throwable) { Trace.error("Link", "RFCOMM open failed ${e.javaClass.simpleName}: ${e.message}", e); runCatching { socket?.close() }; ble.close(); throw e }
    }
}
