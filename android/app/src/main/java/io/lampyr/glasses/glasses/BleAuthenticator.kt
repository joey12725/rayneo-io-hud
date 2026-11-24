// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Adapted from OpenRayneo RayneoConnection.kt and RUCH BleIdentityGate.kt. See android/NOTICE.
@file:Suppress("MissingPermission", "DEPRECATION")
package io.lampyr.glasses.glasses

import io.lampyr.glasses.debug.Trace

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import android.provider.Settings
import io.lampyr.glasses.glasses.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RayNeoDevice(val name: String, val bleAddress: String, val classicAddress: String?, val rssi: Int? = null)

class BleAuthenticator(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")

    /** Returns a handle that must be closed; defaults to a 12-second scan. */
    fun scan(onFound: (RayNeoDevice) -> Unit, onError: (String) -> Unit, durationMs: Long = 12_000): Closeable {
        Trace.log("BLE", "Scan start durationMs=$durationMs")
        check(adapter.isEnabled) { "Enable Bluetooth first" }
        val scanner = adapter.bluetoothLeScanner ?: error("BLE scanner unavailable")
        val active = AtomicBoolean(true)
        val handler = Handler(Looper.getMainLooper())
        val seen = mutableSetOf<String>()
        val logged = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!active.get()) return
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name.orEmpty()
                val data = record?.getManufacturerSpecificData(0x5716)
                val classic = manufacturerAddress(data)
                val matches = name.contains("RayNeo iO", true) || record?.serviceUuids?.any { it.uuid == SERVICE } == true || classic != null
                if (logged.add(result.device.address)) Trace.log("BLE", "Scan result name=$name ble=${result.device.address} classic=$classic rssi=${result.rssi} matches=$matches")
                if (matches && seen.add(result.device.address)) onFound(RayNeoDevice(name.ifEmpty { "RayNeo iO" }, result.device.address, classic, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) { Trace.error("BLE", "Scan failed code=$errorCode"); onError("BLE scan failed ($errorCode)") }
        }
        val stop = Runnable { if (active.getAndSet(false)) runCatching { Trace.log("BLE", "Scan stop results=${seen.size}"); scanner.stopScan(callback) } }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        handler.postDelayed(stop, durationMs)
        return Closeable { handler.removeCallbacks(stop); stop.run() }
    }

    suspend fun find(address: String, savedBleAddress: String? = null, scanMs: Long = 12_000): RayNeoDevice =
        withTimeoutOrNull(scanMs + 1_000) {
            val found = CompletableDeferred<RayNeoDevice>()
            val handle = scan({ device ->
                if (device.classicAddress.equals(address, true) || device.bleAddress.equals(address, true) || device.bleAddress.equals(savedBleAddress, true)) found.complete(device)
            }, { found.completeExceptionally(IllegalStateException(it)) }, scanMs)
            try { found.await() } finally { handle.close() }
        } ?: error("Glasses not found nearby (no advert in a ${scanMs / 1000} s scan)")

    /**
     * Picks how [attempt] reaches the glasses (see [ConnectPlan]): straight to a bonded or already-connected unit with
     * no scan, else a scan (short in the background). A sleeping or already-linked unit may not advertise at all.
     */
    suspend fun locate(address: String, savedBleAddress: String?, attempt: LinkAttempt): Pair<RayNeoDevice, ConnectPlan> {
        check(adapter.isEnabled) { "Bluetooth is off. Waiting for it to come back on" }
        val candidates = listOfNotNull(savedBleAddress, address).map { it.uppercase() }.distinct().filter(BluetoothAdapter::checkBluetoothAddress)
        val manager = context.getSystemService(BluetoothManager::class.java)
        val held = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty().firstOrNull { d -> candidates.any { it.equals(d.address, true) } }
        val bonded = candidates.map { adapter.getRemoteDevice(it) }.firstOrNull {
            it.bondState == BluetoothDevice.BOND_BONDED && it.type in setOf(BluetoothDevice.DEVICE_TYPE_LE, BluetoothDevice.DEVICE_TYPE_DUAL)
        }
        val plan = ConnectPlan.choose(attempt, bondedLe = bonded != null, heldElsewhere = held != null)
        val target = held ?: bonded
        Trace.log("BLE", "Locate attempt=${attempt.number} plan=$plan bonded=${bonded?.address} gattConnected=${held?.address}")
        if (held != null) Trace.error("BLE", "Glasses already have an LE link from another app (the official RayNeo app competes for it)")
        if (plan.direct && target != null) {
            val classic = address.takeIf { !it.equals(target.address, true) || target.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            return RayNeoDevice(target.name ?: "RayNeo iO", target.address, classic) to plan
        }
        return find(address, savedBleAddress, plan.scanMs) to plan
    }

    private fun identity(): ByteArray {
        val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        val saved = prefs.getString("glasses.phoneId", null)
        if (saved != null) return Pairing.phoneId(saved)
        val id = Pairing.phoneId(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty())
        check(prefs.edit().putString("glasses.phoneId", id.joinToString("") { "%02x".format(it.toInt() and 255) }).commit()) { "Cannot persist phone identity" }
        // Never impersonate pairing-info's bound phone ID. User must unbind that phone (§2.5).
        return id
    }

    /**
     * [mayBond] false (background retries) refuses to open Android's pairing dialog for an unpaired unit; that is a
     * terminal error instead. [autoConnect] waits passively for up to [connectWindowMs] for the first LE contact;
     * [hurry] completing (a wake: controller key, screen on, glasses link up) ends that wait with an active connect.
     */
    suspend fun authenticate(device: RayNeoDevice, expectedAddress: String?, onFrame: (RayneoFrame) -> Unit, mayBond: Boolean = true,
                             autoConnect: Boolean = false, connectWindowMs: Long = ConnectPlan.ACTIVE_WINDOW_MS,
                             hurry: Deferred<String>? = null): Session {
        device.classicAddress?.let { ensureBond(adapter.getRemoteDevice(it), mayBond) }
        val session = Session(onFrame)
        try {
            val budget = 25_000 + connectWindowMs + if (autoConnect) ConnectPlan.ACTIVE_WINDOW_MS else 0
            withTimeout(budget) { session.authenticate(adapter.getRemoteDevice(device.bleAddress), expectedAddress ?: device.classicAddress, identity(), autoConnect, connectWindowMs, hurry) }
            ensureBond(adapter.getRemoteDevice(session.classicAddress), mayBond)
            Trace.log("Auth", "Authentication success classic=${session.classicAddress}")
            return session
        } catch (e: Throwable) { Trace.error("Auth", "Authentication failed: ${e.javaClass.simpleName}: ${e.message}", e); session.close(); throw e }
    }

    private suspend fun ensureBond(device: BluetoothDevice, mayBond: Boolean = true) {
        Trace.log("BLE", "Bond address=${device.address} state=${device.bondState} mayBond=$mayBond")
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        check(mayBond) { ReconnectPolicy.UNPAIRED }
        withTimeout(45_000) {
            val done = CompletableDeferred<Unit>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val target: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (target?.address != device.address) return
                    Trace.log("BLE", "Bond transition address=${device.address} previous=${intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)} state=${intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)}")
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> done.complete(Unit)
                        BluetoothDevice.BOND_NONE -> done.completeExceptionally(IllegalStateException("Pairing declined or failed; re-enter pairing mode"))
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) done.complete(Unit)
                else if (device.bondState == BluetoothDevice.BOND_NONE) check(device.createBond()) { "System rejected bond request" }
                done.await()
            } catch (e: Exception) { Trace.error("BLE", "Bond failed/timeout address=${device.address}", e); throw e }
            finally { context.unregisterReceiver(receiver) }
        }
    }

    inner class Session(private val onFrame: (RayneoFrame) -> Unit) : Closeable {
        lateinit var classicAddress: String
            private set
        val lost = CompletableDeferred<Unit>()
        @Volatile private var gatt: BluetoothGatt? = null
        private val closed = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val gate = BleIdentityGate()
        private val events = Channel<GattEvent>(16)
        private val pairing = Channel<RayneoFrame>(8)
        private val writes = Channel<BleWrite>(32)
        private val writeDone = Channel<Int>(1)
        private var payloadSize = 20
        private var sequence = 0
        private var send: BluetoothGattCharacteristic? = null
        private val decoder = FrameCodec.StreamDecoder()
        private fun fail(error: Throwable) {
            Trace.error("BLE", "Session failed", error)
            events.close(error); pairing.close(error); writeDone.close(error)
            lost.completeExceptionally(error)
        }
        private fun event(event: GattEvent) {
            if (!closed.get() && !events.trySend(event).isSuccess) fail(IllegalStateException("BLE callback queue overflow"))
        }
        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                Trace.log("BLE", "GATT state=$state status=$status")
                if (closed.get() || (gatt != null && g !== gatt)) return // A passive connect we already replaced.
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) fail(IllegalStateException("BLE disconnected (status $status)"))
                else if (state == BluetoothProfile.STATE_CONNECTED) event(GattEvent("connected", status))
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Trace.log("BLE", "Services status=$status UUIDs=${g.services.map { it.uuid }} expected=${g.getService(SERVICE) != null} characteristics=${g.getService(SERVICE)?.let { s -> listOf(SEND, RECEIVE, PAIR_INFO).associateWith { s.getCharacteristic(it) != null } }}")
                event(GattEvent("services", status)) }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Trace.log("BLE", "MTU result mtu=$mtu status=$status")
                if (gate.mtuReady()) event(GattEvent("mtu", status, number = mtu))
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (c.uuid == PAIR_INFO) event(GattEvent("info", status, value))
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { onCharacteristicRead(g, c, c.value ?: byteArrayOf(), status) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { Trace.log("BLE", "Descriptor write uuid=${d.uuid} status=$status"); if (d.uuid == CCCD) event(GattEvent("descriptor", status)) }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (c.uuid == SEND && !closed.get()) writeDone.trySend(status)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.uuid != RECEIVE || closed.get()) return
                try {
                    synchronized(decoder) {
                        for (frame in decoder.append(value)) {
                            if (frame.acknowledgement) continue
                            if (frame.flags and 1 != 0) check(writes.trySend(BleWrite(FrameCodec.encode(FrameCodec.acknowledgement(frame)))).isSuccess)
                            if (frame.business == 0x10) {
                                Trace.log("Auth", "Pairing reply command=${frame.payload.firstOrNull()?.toInt()?.and(255)} tags=${runCatching { Pairing.fields(frame.payload).mapValues { it.value.size } }.getOrNull()}")
                                // Status 22/23 are raw bodies, not TLVs, and may arrive after auth.
                                if (frame.payload.firstOrNull()?.toInt()?.and(255) in setOf(17, 25)) check(pairing.trySend(frame).isSuccess)
                            } else onFrame(frame)
                        }
                    }
                } catch (e: Exception) { fail(e) }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { onCharacteristicChanged(g, c, c.value ?: byteArrayOf()) }
        }
        private suspend fun await(kind: String): GattEvent {
            val result = events.receive()
            check(result.kind == kind && result.status == BluetoothGatt.GATT_SUCCESS) { "BLE $kind failed (received ${result.kind}, status ${result.status})" }
            return result
        }
        private fun open(device: BluetoothDevice, autoConnect: Boolean, windowMs: Long): BluetoothGatt {
            Trace.log("BLE", "GATT connect address=${device.address} autoConnect=$autoConnect windowMs=$windowMs")
            return (device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
                ?: error("Cannot open GATT")).also { gatt = it }
        }
        private fun connected(event: GattEvent) =
            check(event.kind == "connected" && event.status == BluetoothGatt.GATT_SUCCESS) { "BLE connected failed (received ${event.kind}, status ${event.status})" }
        internal suspend fun authenticate(device: BluetoothDevice, expected: String?, phone: ByteArray, autoConnect: Boolean = false,
                                          connectWindowMs: Long = ConnectPlan.ACTIVE_WINDOW_MS, hurry: Deferred<String>? = null) {
            var g = open(device, autoConnect, connectWindowMs)
            val notFound = { ms: Long -> IllegalStateException("Glasses not found nearby (no Bluetooth LE contact in ${ms / 1000} s)") }
            // A passive connect waits cheaply; a wake ends the wait with an active connect, which finds an awake unit faster.
            val first: Any = withTimeoutOrNull(connectWindowMs) {
                if (autoConnect && hurry != null) select<Any> { events.onReceive { it }; hurry.onAwait { it } } else events.receive()
            } ?: throw notFound(connectWindowMs)
            if (first is String) {
                Trace.log("BLE", "Passive connect interrupted reason=$first: connecting actively")
                runCatching { g.disconnect() }; runCatching { g.close() }
                g = open(device, false, ConnectPlan.ACTIVE_WINDOW_MS)
                withTimeoutOrNull(ConnectPlan.ACTIVE_WINDOW_MS) { connected(events.receive()) } ?: throw notFound(ConnectPlan.ACTIVE_WINDOW_MS)
            } else connected(first as GattEvent)
            check(g.discoverServices())
            await("services")
            val service = checkNotNull(g.getService(SERVICE)) { "Missing B81D service" }
            send = checkNotNull(service.getCharacteristic(SEND))
            val receive = checkNotNull(service.getCharacteristic(RECEIVE))
            val info = checkNotNull(service.getCharacteristic(PAIR_INFO))
            check(gate.servicesReady() && g.requestMtu(512))
            val mtu = await("mtu").number
            check(mtu >= 160) { "BLE MTU too small ($mtu)" }
            payloadSize = mtu - 3
            // The reference firmware exposes a readable pairing-info value (glasses identity, binding). Some firmware marks it
            // write-only (properties 0x0c); the identity then comes from the device-info reply, or the BLE address.
            var glasses: ByteArray? = null
            if (info.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                // Android 14+ negotiates the MTU by itself, so the first MTU callback can be that one while our own
                // request is still in flight. The stack then refuses the next operation as busy; retry until it accepts.
                retryBusyGatt("pairing info read") { g.readCharacteristic(info) }
                val value = await("info").bytes
                check(value.size >= 13) { "Incomplete pairing info" }
                Trace.log("Auth", "Pairing info state=${value[6].toInt() and 255} bound=${value.copyOfRange(7, 13).any { it.toInt() != 0 }}")
                glasses = value.copyOfRange(0, 6)
            } else Trace.log("Auth", "Pairing info not readable (properties 0x${info.properties.toString(16)}); identity from device-info reply")
            check(g.setCharacteristicNotification(receive, true))
            val cccd = checkNotNull(receive.getDescriptor(CCCD))
            retryBusyGatt("notification enable") {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
            await("descriptor")
            scope.launch {
                runBleWriteQueue(writes, { bytes ->
                    for (chunk in FrameCodec.segment(bytes, payloadSize)) {
                        val characteristic = checkNotNull(send)
                        retryBusyBleWrite { if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                            else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; characteristic.value = chunk; g.writeCharacteristic(characteristic) } }
                        check(withTimeout(5_000) { writeDone.receive() } == BluetoothGatt.GATT_SUCCESS) { "BLE write failed" }
                    }
                }, ::fail)
            }
            writePairing(17, listOf(16 to byteArrayOf(1), 17 to phone, 18 to (adapter.name ?: "RayNeo iO HUD").toByteArray(),
                19 to byteArrayOf(2), 22 to Build.MANUFACTURER.toByteArray(), 23 to Build.MODEL.toByteArray(), 27 to byteArrayOf(1)))
            val version = pairing.receive()
            check(version.payload.firstOrNull()?.toInt() == 17) { "Expected command 17" }
            val fields = Pairing.fields(version.payload)
            Trace.log("Auth", "Account-bound=${25 in fields || fields[26]?.isNotEmpty() == true}")
            check(25 !in fields && fields[26]?.isNotEmpty() != true) { "Account-bound auth unsupported: unbind in the official RayNeo app first" }
            val reported = fields[17]?.takeIf { it.size == 6 }
            if (glasses != null) reported?.let { check(it.contentEquals(glasses)) { "Device-info identity mismatch" } }
            else glasses = reported ?: device.address.split(':').map { it.toInt(16).toByte() }.toByteArray()
            Trace.log("Auth", "Glasses identity source=${if (reported != null) "device-info reply" else "pairing info or BLE address"}")
            val identity = checkNotNull(glasses)
            classicAddress = Pairing.address(identity)
            check(expected == null || expected.equals(classicAddress, true)) { "BLE identity does not match selected classic address" }
            val random = ByteArray(4).also { SecureRandom().nextBytes(it) }
            writePairing(24, listOf(16 to random, 17 to Pairing.proof(random, phone, identity)))
            val proofFrame = pairing.receive()
            check(proofFrame.payload.firstOrNull()?.toInt() == 25) { "Expected command 25" }
            val proof = Pairing.fields(proofFrame.payload)
            val challenge = checkNotNull(proof[16]) { "Missing challenge" }
            check(challenge.size == 4 && MessageDigest.isEqual(proof[17] ?: byteArrayOf(), Pairing.proof(challenge, phone, identity))) { "Glasses authentication proof rejected" }
            // Guest auth is verified by upstream; this Kotlin integration remains unverified on hardware.
        }
        /** Shares the authenticated GATT write queue; no second connection or companion app required. */
        suspend fun sendBusiness(bytes: ByteArray) {
            check(!closed.get()) { "BLE link closed" }
            val done = CompletableDeferred<Unit>()
            writes.send(BleWrite(bytes, done))
            withTimeout(10_000) { done.await() }
        }
        private suspend fun writePairing(command: Int, fields: List<Pair<Int, ByteArray>>) {
            Trace.log("Auth", "Pairing write command=$command tags=${fields.map { it.first to it.second.size }} values=withheld (identity/proof inputs)")
            writes.send(BleWrite(FrameCodec.encode(RayneoFrame(0x10, sequence++ and 65535, Pairing.encode(command, fields)))))
        }
        override fun close() {
            if (closed.getAndSet(true)) return
            Trace.log("BLE", "Session close")
            scope.cancel(); writes.close(); events.close(); pairing.close(); writeDone.close()
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }; gatt = null
        }
    }
    private data class GattEvent(val kind: String, val status: Int, val bytes: ByteArray = byteArrayOf(), val number: Int = 0)
    companion object {
        val SERVICE: UUID = UUID.fromString("0000B81D-0000-1000-8000-00805F9B34FB")
        private val SEND = UUID.fromString("EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9")
        private val RECEIVE = UUID.fromString("7DB3E235-3608-41F3-A03C-955FCBD2EA4B")
        private val PAIR_INFO = UUID.fromString("EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        fun manufacturerAddress(data: ByteArray?): String? = if (data != null && data.size >= 7 && data[0].toInt() == 6) Pairing.address(data.copyOfRange(1, 7)) else null
    }
}

/** A rejected submission has no completion callback; retry only before Android accepts the chunk. */
/** Retries a GATT operation the stack refused because another one is still in flight (about 1.5 seconds in total). */
internal suspend fun retryBusyGatt(what: String, submit: () -> Boolean) {
    repeat(15) { attempt ->
        if (submit()) { if (attempt > 0) Trace.log("BLE", "$what accepted after ${attempt + 1} tries"); return }
        delay(100)
    }
    error("Android refused the $what: Bluetooth stayed busy")
}

internal suspend fun retryBusyBleWrite(submit: () -> Boolean) {
    repeat(4) { attempt ->
        if (submit()) return
        if (attempt < 3) delay(30L * (attempt + 1))
    }
    error("BLE write busy after retries")
}

internal data class BleWrite(val bytes: ByteArray, val done: CompletableDeferred<Unit>? = null)

/** A writer failure ends this connection and wakes its supervisor, including callback timeouts. */
internal suspend fun runBleWriteQueue(writes: ReceiveChannel<BleWrite>, transmit: suspend (ByteArray) -> Unit,
                                      failed: (Throwable) -> Unit) {
    try {
        for (write in writes) {
            try { transmit(write.bytes); write.done?.complete(Unit) }
            catch (e: Throwable) { write.done?.completeExceptionally(e); throw e }
        }
    } catch (e: TimeoutCancellationException) { failed(e) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { failed(e) }
}
