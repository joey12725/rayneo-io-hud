package io.lampyr.glasses.input.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RawInputEvent(val timeMs: Long, val type: Int, val code: Int, val value: Int)

/** Linux compat evdev uses the reader's ABI: timeval is 2 x 32 or 64 bits. */
class InputEventParser(val recordSize: Int, private val order: ByteOrder = ByteOrder.nativeOrder()) {
    init { require(recordSize == 16 || recordSize == 24) }
    private var pending = byteArrayOf()
    fun feed(bytes: ByteArray, count: Int = bytes.size): List<RawInputEvent> {
        require(count in 0..bytes.size)
        val all = pending + bytes.copyOf(count)
        val result = mutableListOf<RawInputEvent>()
        var offset = 0
        while (offset + recordSize <= all.size) {
            val b = ByteBuffer.wrap(all, offset, recordSize).order(order)
            val seconds = if (recordSize == 24) b.long else b.int.toLong() and 0xffffffffL
            val micros = if (recordSize == 24) b.long else b.int.toLong() and 0xffffffffL
            result += RawInputEvent(seconds * 1000 + micros / 1000, b.short.toInt() and 0xffff, b.short.toInt() and 0xffff, b.int)
            offset += recordSize
        }
        pending = all.copyOfRange(offset, all.size)
        return result
    }
}

data class EvdevIdentity(val name: String, val vendor: Int, val product: Int)
data class EvdevDevice(val name: String, val bus: Int, val vendor: Int, val product: Int, val nodes: List<String>,
    val physical: String = "", val unique: String = "") {
    // Never capture a phone button/touch node or a name-only wildcard. Android's descriptor is
    // a framework hash, not the kernel name; persist the learned InputDevice.name separately.
    fun matches(identity: EvdevIdentity) = bus in setOf(3, 5) && identity.name.isNotBlank() &&
        identity.vendor != 0 && identity.product != 0 && name == identity.name &&
        vendor == identity.vendor && product == identity.product
}

object EvdevDevices {
    /** Additional HID applications can have a Consumer Control suffix; join only proven siblings. */
    fun matching(devices: List<EvdevDevice>, identity: EvdevIdentity): List<EvdevDevice> {
        val anchors = devices.filter { it.matches(identity) }
        fun physicalDevice(value: String) = value.replace(Regex("/input[0-9]+$"), "")
        return devices.filter { candidate -> anchors.any { anchor ->
            candidate == anchor || candidate.bus == anchor.bus && candidate.vendor == anchor.vendor && candidate.product == anchor.product &&
                (candidate.unique.isNotBlank() && candidate.unique == anchor.unique ||
                    candidate.physical.isNotBlank() && anchor.physical.isNotBlank() && physicalDevice(candidate.physical) == physicalDevice(anchor.physical))
        } }
    }
    /** AOSP toolbox getevent -i uses EVIOCGNAME/EVIOCGID and exits without reading events. */
    fun parseGetevent(text: String): List<EvdevDevice> = text.split(Regex("(?m)^add device [0-9]+: ")).drop(1).mapNotNull { block ->
        val path = block.lineSequence().first().trim()
        if (!Regex("/dev/input/event[0-9]+").matches(path)) return@mapNotNull null
        fun hex(key: String) = Regex("(?m)^\\s*$key:?\\s+([0-9a-fA-F]+)\\s*$").find(block)?.groupValues?.get(1)?.toIntOrNull(16)
        val name = Regex("(?m)^\\s*name:\\s+\"(.*)\"\\s*$").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        fun quoted(key: String) = Regex("(?m)^\\s*$key:\\s+\"(.*)\"\\s*$").find(block)?.groupValues?.get(1).orEmpty()
        EvdevDevice(name, hex("bus") ?: return@mapNotNull null, hex("vendor") ?: return@mapNotNull null,
            hex("product") ?: return@mapNotNull null, listOf(path), quoted("location"), quoted("id"))
    }
    fun parse(text: String): List<EvdevDevice> = text.split(Regex("\\r?\\n\\s*\\r?\\n")).mapNotNull { block ->
        val id = Regex("I: Bus=([0-9a-fA-F]+) Vendor=([0-9a-fA-F]+) Product=([0-9a-fA-F]+)").find(block) ?: return@mapNotNull null
        val name = Regex("N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val handlers = Regex("H: Handlers=([^\\r\\n]*)").find(block)?.groupValues?.get(1).orEmpty()
        fun field(key: String) = Regex("(?m)^$key: [^=]+=([^\\r\\n]*)").find(block)?.groupValues?.get(1).orEmpty()
        EvdevDevice(name, id.groupValues[1].toInt(16), id.groupValues[2].toInt(16), id.groupValues[3].toInt(16),
            Regex("\\bevent[0-9]+\\b").findAll(handlers).map { "/dev/input/${it.value}" }.toList(), field("P"), field("U"))
    }
}

/** The subset of AOSP android15-release Generic.kl used by Micro keyboard/gamepad modes.
 * Values are Android keycodes, kept numeric so these helpers run on a plain JVM. */
object LinuxKeyMap {
    private val table = buildMap {
        val letters = mapOf(16 to 'Q',17 to 'W',18 to 'E',19 to 'R',20 to 'T',21 to 'Y',22 to 'U',23 to 'I',24 to 'O',25 to 'P',
            30 to 'A',31 to 'S',32 to 'D',33 to 'F',34 to 'G',35 to 'H',36 to 'J',37 to 'K',38 to 'L',
            44 to 'Z',45 to 'X',46 to 'C',47 to 'V',48 to 'B',49 to 'N',50 to 'M')
        letters.forEach { (linux, letter) -> put(linux, 29 + (letter - 'A')) }
        (2..10).forEach { put(it, it + 6) }; put(11, 7)
        putAll(mapOf(1 to 111,12 to 69,13 to 70,14 to 67,15 to 61,26 to 71,27 to 72,28 to 66,
            29 to 113,39 to 74,40 to 75,41 to 68,42 to 59,43 to 73,51 to 55,52 to 56,53 to 76,54 to 60,
            56 to 57,57 to 62,58 to 115,96 to 160,97 to 114,100 to 58,
            103 to 19,105 to 21,106 to 22,108 to 20,111 to 112,
            113 to 164,114 to 25,115 to 24,119 to 121,125 to 117,126 to 118,127 to 82,
            158 to 4,163 to 87,164 to 85,165 to 88,166 to 86,172 to 3,
            304 to 96,305 to 97,306 to 98,307 to 99,308 to 100,309 to 101,
            310 to 102,311 to 103,312 to 104,313 to 105,314 to 109,315 to 108,316 to 110,317 to 106,318 to 107,
            544 to 19,545 to 20,546 to 21,547 to 22))
    }
    fun androidCode(code: Int): Int? = table[code]
    // A separate negative namespace preserves unmapped evdev keys in the existing JSON KeyMap.
    fun bindingCode(code: Int) = androidCode(code) ?: (-1 - code)
    fun label(code: Int) = if (code < 0) "raw ${-1 - code}" else "Android key $code"
}

data class KeyTransition(val code: Int, val value: Int)

/** Per-node/per-origin state with aggregate edges, so a duplicate interface cannot release a chord. */
class EvdevKeyMerger {
    private val held = mutableMapOf<String, MutableSet<Int>>()
    private val hats = mutableMapOf<Pair<String, Int>, Int>()
    private fun key(origin: String, code: Int, value: Int): List<KeyTransition> {
        val keys = held.getOrPut(origin) { mutableSetOf() }
        val was = held.values.any { code in it }
        if (value == 2) return if (code in keys) listOf(KeyTransition(code, 2)) else emptyList()
        if (value == 1) keys.add(code) else keys.remove(code)
        val now = held.values.any { code in it }
        return if (was != now) listOf(KeyTransition(code, if (now) 1 else 0)) else emptyList()
    }
    fun event(node: String, type: Int, code: Int, value: Int): List<KeyTransition> {
        if (type == 1 && value in 0..2) return key("$node:key:$code", LinuxKeyMap.bindingCode(code), value)
        if (type != 3 || code !in 16..17) return emptyList()
        val axis = node to code
        val next = value.compareTo(0)
        val previous = hats.put(axis, next) ?: 0
        if (next == previous) return emptyList()
        fun direction(v: Int) = if (code == 16) { if (v < 0) 21 else 22 } else { if (v < 0) 19 else 20 }
        return buildList {
            if (previous != 0) addAll(key("$node:hat:$code", direction(previous), 0))
            if (next != 0) addAll(key("$node:hat:$code", direction(next), 1))
        }
    }
    fun remove(node: String): List<KeyTransition> {
        val before = held.values.flatten().toSet()
        held.keys.removeAll { it.startsWith("$node:") }
        hats.keys.removeAll { it.first == node }
        return (before - held.values.flatten().toSet()).map { KeyTransition(it, 0) }
    }
    fun clear() { held.clear(); hats.clear() }
}

/** Cross-source only: two fast taps from one source must never be suppressed. */
class KeyDeliveryDedupe(private val windowMs: Long = 100) {
    private data class Seen(val source: String, val key: Int, val value: Int, val time: Long)
    private val recent = ArrayDeque<Seen>()
    fun accept(source: String, key: Int, value: Int, time: Long): Boolean {
        recent.removeAll { time - it.time > windowMs * 4 }
        val duplicate = recent.firstOrNull { it.source != source && it.key == key && it.value == value && kotlin.math.abs(time - it.time) <= windowMs }
        // Consume pairs one-to-one; a repeated tap cannot match the same old event twice.
        if (duplicate != null) { recent.remove(duplicate); return false }
        recent.addLast(Seen(source, key, value, time))
        while (recent.size > 256) recent.removeFirst()
        return true
    }
    fun clear() = recent.clear()
}
