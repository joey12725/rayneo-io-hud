package io.lampyr.glasses.input

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** One GATT owner, callbacks confined to main, serialized CCCD writes with a bounded wait. */
@Suppress("DEPRECATION", "MissingPermission")
class NonHidGattSession(
    private val context: Context,
    private val services: (List<BluetoothGattService>) -> List<BluetoothGattCharacteristic>,
    private val report: (BluetoothGattCharacteristic, ByteArray) -> Unit,
    private val state: (Boolean, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<BluetoothGattCharacteristic>()
    private var writing: BluetoothGattDescriptor? = null
    private var subscribed = 0
    private var savedAddress: String? = null
    private var automatic = false
    private val timeout = Runnable { fail("GATT operation timed out; check pairing/permissions") }

    fun connect(address: String, autoConnect: Boolean) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Grant Nearby devices permission")
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return userAction("Bluetooth is unavailable")
            if (!adapter.isEnabled) return userAction("Turn on Bluetooth")
            if (gatt != null && automatic && autoConnect && address == savedAddress) {
                check(gatt!!.connect()) { "BLE reconnect could not start" }
                state(false, "Waiting for BLE controller")
                return
            }
            close()
            automatic = autoConnect; savedAddress = address
            gatt = adapter.getRemoteDevice(address).connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            check(gatt != null) { "Could not create GATT connection" }
            state(false, "Waiting for BLE controller")
            if (!autoConnect) armTimeout()
        }.onFailure {
            if (it is SecurityException || it is IllegalArgumentException) userAction(it.message ?: "Check Bluetooth permission/profile")
            else fail(it.message ?: "BLE connection failed")
        }
    }

    private fun armTimeout() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, 15_000) }
    private fun fail(message: String) { close(); state(false, message) }
    private fun userAction(message: String) { close(); state(false, "Needs user action: $message") }
    fun close() {
        handler.removeCallbacks(timeout)
        val old = gatt; gatt = null
        queue.clear(); writing = null; subscribed = 0
        runCatching { old?.disconnect() }; runCatching { old?.close() }
    }

    private fun subscribeNext() {
        try {
            val owner = gatt ?: return
            if (queue.isEmpty()) {
                handler.removeCallbacks(timeout)
                if (subscribed == 0) userAction("No usable non-HID notifying characteristic. Re-learn this mode.")
                else state(true, "Subscribed to $subscribed non-HID characteristic(s)")
                return
            }
            val characteristic = queue.removeFirst()
            val descriptor = characteristic.getDescriptor(CCCD)
            if (descriptor == null || !owner.setCharacteristicNotification(characteristic, true)) { subscribeNext(); return }
            writing = descriptor
            descriptor.value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!owner.writeDescriptor(descriptor)) { writing = null; subscribeNext() } else armTimeout()
        } catch (_: SecurityException) { userAction("Bluetooth permission revoked; grant Nearby devices") }
        catch (_: IllegalStateException) { userAction("Turn on Bluetooth") }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(owner: BluetoothGatt, status: Int, newState: Int) { handler.post {
            if (owner !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(timeout); queue.clear(); writing = null; subscribed = 0
                // Only a clean disconnect can reuse Android's auto-connect handle.
                if (status != BluetoothGatt.GATT_SUCCESS || !automatic) close()
                state(false, "BLE disconnected ($status)")
                return@post
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state(false, "Discovering BLE services")
                armTimeout()
                if (runCatching { owner.discoverServices() }.getOrDefault(false) != true) fail("Service discovery could not start; check Bluetooth permission")
            }
        } }
        override fun onServicesDiscovered(owner: BluetoothGatt, status: Int) { handler.post {
            if (owner !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed ($status)"); return@post }
            queue.addAll(services(owner.services).filter { allowed(it.service.uuid, it.uuid) && notifying(it) })
            subscribeNext()
        } }
        override fun onDescriptorWrite(owner: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { handler.post {
            if (owner !== gatt || descriptor !== writing) return@post
            handler.removeCallbacks(timeout); writing = null
            if (status == BluetoothGatt.GATT_SUCCESS) subscribed++
            subscribeNext()
        } }
        override fun onCharacteristicChanged(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(owner, characteristic, characteristic.value?.clone() ?: return)
        }
        override fun onCharacteristicChanged(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(owner, characteristic, value.clone())
        }
        private fun deliver(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handler.post {
            if (owner === gatt && allowed(characteristic.service.uuid, characteristic.uuid)) report(characteristic, value)
        } }
    }
    companion object {
        private fun bluetoothUuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        val HID = bluetoothUuid("1812")
        val CCCD = bluetoothUuid("2902")
        private val restricted = setOf("2a4a", "2a4b", "2a4c", "2a4d").map(::bluetoothUuid).toSet()
        fun allowed(service: UUID, characteristic: UUID) = service != HID && characteristic !in restricted
        fun notifying(c: BluetoothGattCharacteristic) = c.properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }
}
