package io.lampyr.glasses.data

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.*
import android.os.Build

/** One Bluetooth device the phone knows: [kind] is buds, headphones, speaker, car or other (from its class). */
data class BtDevice(val address: String, val name: String, val kind: String = "other")

/** A connected device and its battery, 0..1, or null when it reports none. */
data class DeviceBattery(val address: String, val name: String, val level: Double?, val kind: String)

/** What the phone's Bluetooth stack can tell; the Android version is [AndroidBluetoothPlatform], tests use a fake. */
interface BluetoothPlatform {
    /** Devices connected on the headset, A2DP or LE audio profile right now (needs BLUETOOTH_CONNECT). */
    fun connected(onResult: (List<BtDevice>) -> Unit)
    /** The device's battery 0..100 from the hidden BluetoothDevice.getBatteryLevel(), or null when unknown/blocked. */
    fun level(address: String): Int?
    fun bonded(): List<BtDevice>
    fun device(intent: Intent): BtDevice?
}

/**
 * Battery levels of connected Bluetooth audio devices, tracked per device by address (requirement 16.4).
 * Sources, in order: an initial read of the connected profiles on start; the hidden
 * `BluetoothDevice.getBatteryLevel()` read by reflection (guarded: a blocked call falls through) on connect and on
 * every [refresh]; and the system's `BATTERY_LEVEL_CHANGED` broadcast, kept per device with no audio-route gate,
 * so a level that arrives during the headset connection, before the audio output exists, is not lost.
 * Which devices show on Status is decided by [StatusDevices].
 */
class BluetoothBatteries(
    private val context: Context,
    private val changed: () -> Unit,
    private val platform: BluetoothPlatform = AndroidBluetoothPlatform(context),
) {
    private val devices = linkedMapOf<String, DeviceBattery>()
    /** Connected audio devices, oldest connection first. */
    val levels: List<DeviceBattery> get() = synchronized(devices) { devices.values.toList() }
    fun bonded(): List<BtDevice> = runCatching { platform.bonded() }.getOrDefault(emptyList())

    /** The phone setting per device ([StatusDevices.SHOW] / [StatusDevices.HIDE]; null = auto). */
    fun choice(address: String): String? = runCatching { context.getSharedPreferences(StatusDevices.PREFS, Context.MODE_PRIVATE).getString(address, null) }.getOrNull()
    fun setChoice(address: String, value: String?) {
        require(value == null || value == StatusDevices.SHOW || value == StatusDevices.HIDE)
        context.getSharedPreferences(StatusDevices.PREFS, Context.MODE_PRIVATE).edit().apply { if (value == null) remove(address) else putString(address, value) }.apply()
        changed()
    }
    /** The glasses' own classic address (they are a Bluetooth audio device too); never a Status row. */
    fun glassesAddress(): String? = runCatching { context.getSharedPreferences("glasses", Context.MODE_PRIVATE).getString("glasses.address", null) }.getOrNull()
    /** The Status rows for the connected devices, honouring the phone setting. */
    fun statusRows(): List<Battery> = StatusDevices.rows(levels, ::choice, glassesAddress())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                // Turning Bluetooth off does not reliably send ACL_DISCONNECTED per device: forget them all.
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) clear()
                return
            }
            val device = runCatching { platform.device(intent) }.getOrNull() ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> disconnected(device.address)
                ACTION_BATTERY_LEVEL_CHANGED -> level(device, intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1))
                else -> when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> connected(device)
                    // One profile going down (A2DP while HFP stays) is not a disconnect; ACL_DISCONNECTED is.
                    else -> Unit
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Bluetooth broadcasts originate in a privileged system app outside our UID.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        }
    }

    /** The initial read of already-connected devices; call once the owner can take [changed] callbacks. */
    fun start() = readConnected()

    /** Re-read the connected set and every level (Status opening, a status refresh). */
    fun refresh() {
        readConnected()
        // Update only devices still tracked: a disconnect that lands during the read must not bring a row back.
        levels.forEach { d -> readLevel(d.address)?.let { level -> update(d.address) { it.copy(level = level) } } }
    }

    private fun update(address: String, change: (DeviceBattery) -> DeviceBattery) {
        val differs = synchronized(devices) {
            val old = devices[address] ?: return@synchronized false
            val next = change(old)
            (old != next).also { if (it) devices[address] = next }
        }
        if (differs) changed()
    }

    /** Stores [next]; [changed] runs only on a real change, so a status refresh that re-reads cannot loop. */
    private fun put(next: DeviceBattery) {
        val differs = synchronized(devices) { (devices[next.address] != next).also { if (it) devices[next.address] = next } }
        if (differs) changed()
    }

    private fun readConnected() {
        runCatching { platform.connected { list -> list.forEach(::connected) } }
    }

    private fun readLevel(address: String): Double? =
        runCatching { platform.level(address) }.getOrNull()?.takeIf { it in 0..100 }?.div(100.0)

    private fun merged(device: BtDevice, level: Double?): DeviceBattery {
        val old = synchronized(devices) { devices[device.address] }
        return DeviceBattery(device.address, device.name.ifBlank { old?.name ?: "Bluetooth" }, level,
            if (device.kind == "other" && old != null) old.kind else device.kind)
    }

    internal fun connected(device: BtDevice) {
        val old = synchronized(devices) { devices[device.address] }
        put(merged(device, readLevel(device.address) ?: old?.level))
    }

    /**
     * A level broadcast also means the device is connected. -1 (unknown) falls back to the hidden-API read. Android
     * also sends -1/-100 while a device disconnects, sometimes after ACL_DISCONNECTED: such a reset for a device not
     * tracked is ignored, so it cannot leave a ghost row on Status.
     */
    internal fun level(device: BtDevice, raw: Int) {
        if (raw !in 0..100 && synchronized(devices) { device.address !in devices }) return
        put(merged(device, raw.takeIf { it in 0..100 }?.div(100.0) ?: readLevel(device.address)))
    }

    internal fun disconnected(address: String) {
        if (synchronized(devices) { devices.remove(address) } != null) changed()
    }

    internal fun clear() {
        if (synchronized(devices) { devices.isNotEmpty().also { devices.clear() } }) changed()
    }

    fun close() { runCatching { context.unregisterReceiver(receiver) } }

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED = "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
        /** BluetoothProfile.LE_AUDIO (API 33); the constant is used by value so older SDKs compile and run. */
        const val PROFILE_LE_AUDIO = 22

        /** Device kind from its Bluetooth class (the class constants are public API). */
        fun kind(deviceClass: Int?, majorClass: Int?): String = when {
            majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO -> "other"
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "buds"
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "headphones"
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "car"
            deviceClass in setOf(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO) -> "speaker"
            else -> "other"
        }
    }
}

/** The real Bluetooth stack. Every call is guarded: without BLUETOOTH_CONNECT it reports nothing instead of throwing. */
class AndroidBluetoothPlatform(private val context: Context) : BluetoothPlatform {
    private val adapter: BluetoothAdapter? get() = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    @Suppress("MissingPermission")
    private fun BluetoothDevice.toBt() = BtDevice(address, runCatching { alias ?: name }.getOrNull().orEmpty(),
        runCatching { BluetoothBatteries.kind(bluetoothClass?.deviceClass, bluetoothClass?.majorDeviceClass) }.getOrDefault("other"))

    @Suppress("MissingPermission")
    override fun connected(onResult: (List<BtDevice>) -> Unit) {
        val adapter = adapter ?: return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothBatteries.PROFILE_LE_AUDIO)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val list = runCatching { proxy.connectedDevices.map { it.toBt() } }.getOrDefault(emptyList())
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        onResult(list)
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            }
        }
    }

    /** Hidden API, read by reflection and guarded; Android returns -1 (unknown) or -100 (disconnected) for no level. */
    override fun level(address: String): Int? = runCatching {
        val device = adapter?.getRemoteDevice(address) ?: return null
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int)?.takeIf { it in 0..100 }
    }.getOrNull()

    @Suppress("MissingPermission")
    override fun bonded(): List<BtDevice> = runCatching { adapter?.bondedDevices.orEmpty().map { it.toBt() } }.getOrDefault(emptyList())

    override fun device(intent: Intent): BtDevice? {
        @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return null
        return device.toBt()
    }
}

/**
 * Which Bluetooth devices appear on the Status screen (requirement 16.4). The phone's Settings page stores a choice
 * per device in the `status-devices` prefs: "show" or "hide"; no entry means auto (earbuds and headphones show,
 * speakers, cars and other devices do not). The glasses' own classic address never shows.
 */
object StatusDevices {
    const val PREFS = "status-devices"
    const val SHOW = "show"
    const val HIDE = "hide"
    /** Status has room for two audio rows under Glasses, Oura and Phone. */
    const val MAX_ROWS = 2
    /** The label column before the bar holds about 11 characters. */
    const val NAME_CHARS = 11

    fun autoShows(kind: String) = kind == "buds" || kind == "headphones"

    fun shows(device: DeviceBattery, choice: String?, glassesAddress: String?) = when {
        glassesAddress != null && device.address.equals(glassesAddress, ignoreCase = true) -> false
        choice == SHOW -> true
        choice == HIDE -> false
        else -> autoShows(device.kind)
    }

    /** "Soundcore Life P2 Mini" -> "Life P2 Mini" -> fitted to [NAME_CHARS]; brand words are dropped when the name is long. */
    fun shortName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "Bluetooth"
        val brands = setOf("soundcore", "anker", "sony", "bose", "jbl", "samsung", "galaxy", "apple", "google", "pixel", "beats", "jabra", "my")
        // A leading possessive ("Sam's AirPods") is dropped like a brand word.
        fun lead(w: String) = w.lowercase().let { it in brands || it.endsWith("'s") || it.endsWith("’s") }
        val trimmed = if (name.length > NAME_CHARS && words.size > 1) words.dropWhile { lead(it) }.ifEmpty { words } else words
        var out = ""
        for (w in trimmed) {
            val next = if (out.isEmpty()) w else "$out $w"
            if (next.length > NAME_CHARS) break
            out = next
        }
        return out.ifEmpty { trimmed.first().take(NAME_CHARS) }
    }

    /** Status battery rows for [devices]: at most [MAX_ROWS], connected order. A device without a level shows "—". */
    fun rows(devices: List<DeviceBattery>, choice: (String) -> String?, glassesAddress: String?): List<Battery> =
        devices.filter { shows(it, choice(it.address), glassesAddress) }.take(MAX_ROWS).map { d ->
            Battery("buds", shortName(d.name), d.level ?: -1.0)
        }
}
