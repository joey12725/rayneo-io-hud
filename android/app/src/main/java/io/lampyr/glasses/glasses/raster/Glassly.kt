package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.render.RasterFit
import java.io.ByteArrayOutputStream

@kotlinx.serialization.Serializable
data class GlasslyIdentity(val width: Int, val height: Int, val chunk: Int, val revision: Int, val flags: Int, val name: String) {
    val raster get() = revision >= 7 && flags and 0x12 == 0x12 && width == 540 && height == 220
    val stereo get() = flags and 8 != 0
    companion object {
        fun parse(bytes: ByteArray): GlasslyIdentity? {
            if (bytes.size < 14 || bytes.u8(0) != 0x11) return null
            val name = bytes.copyOfRange(13, bytes.size).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            if (!name.startsWith("GLASSLYCFW-IO/") || bytes.le16(6) !in 1..240) return null
            return GlasslyIdentity(bytes.le16(2), bytes.le16(4), bytes.le16(6), bytes.le16(10), bytes.u8(12), name)
        }
    }
}

object GlasslyCodec {
    fun crc(bytes: ByteArray): Int {
        var crc = 0xffff
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }
    /** Nibble RLE. Short token count 1..15, extended count u8 or LE16; no zero runs. */
    fun rle(pixels: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < pixels.size) {
            val color = (pixels.u8(i) * 15 + 127) / 255
            var end = i + 1
            while (end < pixels.size && end - i < 65535 && (pixels.u8(end) * 15 + 127) / 255 == color) end++
            val count = end - i
            if (count <= 15) out.write((count shl 4) or color)
            else { out.write(color); out.write(if (count <= 255) count else 0)
                if (count > 255) { out.write(count and 255); out.write(count ushr 8) } }
            i = end
        }
        return out.toByteArray()
    }
    fun record(message: ByteArray, stereo: Boolean): ByteArray {
        require(message.size in 1..65535)
        return ByteArray(5 + message.size).apply {
            this[0] = (8 or if (stereo) 3 else 1).toByte() // Uncompressed, RESET_CONTEXT.
            put16(1, message.size); put16(3, crc(message)); message.copyInto(this, 5)
        }
    }
    fun packets(record: ByteArray, firstSequence: Int, chunk: Int = 240): List<ByteArray> {
        require(chunk in 1..240)
        val count = (record.size + chunk - 1) / chunk
        return (0 until count).map { i ->
            byteArrayOf((1 or (if (i == 0) 0x80 else 0) or (if (i == count - 1) 0x40 else 0)).toByte(), firstSequence.toByte()) +
                record.copyOfRange(i * chunk, minOf(record.size, (i + 1) * chunk))
        }
    }
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)
    fun changed(previous: ByteArray?, image: RasterFit.Image): Box? {
        val w = image.width; val h = image.height
        if (previous == null || previous.size != image.pixels.size) return Box(0, 0, w, h)
        var left = w; var right = -1; var top = h; var bottom = -1
        image.pixels.indices.forEach { i -> if (previous[i] != image.pixels[i]) {
            val x = i % w; val y = i / w
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        } }
        if (right < 0) return null
        left = left / 4 * 4; top = top / 2 * 2
        return Box(left, top, minOf(w, (right + 4) / 4 * 4) - left, minOf(h, (bottom + 2) / 2 * 2) - top)
    }
    fun box(image: RasterFit.Image, box: Box, frameId: Int): ByteArray {
        require(box.left % 4 == 0 && box.top % 2 == 0 && box.width % 4 == 0 && box.height % 2 == 0)
        require(box.left >= 0 && box.top >= 0 && box.width > 0 && box.height > 0 && box.left + box.width <= image.width && box.top + box.height <= image.height)
        val pixels = ByteArray(box.width * box.height) { i -> image.pixels[(box.top + i / box.width) * image.width + box.left + i % box.width] }
        return byteArrayOf(3, (box.left / 4).toByte(), (box.top / 2).toByte(), (box.width / 4).toByte(), (box.height / 2).toByte(), frameId.toByte(), (frameId ushr 8).toByte()) + rle(pixels)
    }
}

class GlasslyTransport(private val wire: RasterWire, private val identity: GlasslyIdentity) : RasterTransport {
    override val kind = RasterKind.GLASSLY
    private var previous: ByteArray? = null
    private var frame = 0
    @Volatile private var pageOpen = false
    @Volatile var wearerExited = false
        private set
    @Volatile private var closing = false
    fun event(bytes: ByteArray) {
        if (bytes.size < 3 || bytes.u8(0) != 0x13 || bytes.u8(1) != 0) return
        if (pageOpen && !closing && bytes.u8(2) in setOf(0, 4)) wearerExited = true
        pageOpen = false
    }
    companion object {
        suspend fun probe(wire: RasterWire): GlasslyIdentity? {
            wire.send(0xf0, byteArrayOf(0x10, 0, 1), ble = true)
            return try { GlasslyIdentity.parse(wire.awaitReply(0xf0, 2_000) { GlasslyIdentity.parse(it) != null }) }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
        }
    }
    private suspend fun control(op: Int, state: Int) {
        wire.send(0xf0, byteArrayOf(0x10, 0, op.toByte()), ble = true)
        val reply = wire.awaitReply(0xf0) { it.size >= 3 && it.u8(0) == 0x13 }
        check(reply.u8(1) == state && reply.u8(2) == 0) { "Glassly PAGE refused/closed (${reply.u8(2)}); LifeLog may make launcher busy" }
        pageOpen = state == 1
    }
    override suspend fun open() { check(identity.raster) { "Glassly revision lacks direct raster capability" }; control(2, 1); previous = null }
    private suspend fun message(message: ByteArray) {
        check(pageOpen) { "Glassly page closed; reconnect or change display mode to reopen" }
        val record = GlasslyCodec.record(message, identity.stereo)
        val count = (record.size + identity.chunk - 1) / identity.chunk
        val start = wire.reserveSequences(count)
        val packets = GlasslyCodec.packets(record, start, identity.chunk)
        for ((i, packet) in packets.withIndex()) wire.send(0xf0, packet, ble = true, sequence = (start + i) and 65535)
        val lenses = mutableSetOf<Int>()
        val expected = if (identity.stereo) setOf(1, 2) else setOf(1)
        wire.awaitReply(0xf0) { ack ->
            if (ack.size != 9 || ack.u8(1) != (start and 255) || ack.le16(2) != 0 || ack.u8(0) !in setOf(1, 3)) false
            else {
                check(ack.u8(0) == 1 && ack.le16(5) == message.size && ack.le16(7) == GlasslyCodec.crc(message)) { "Glassly NACK or mismatched record acknowledgement" }
                lenses += ack.u8(4)
                lenses.containsAll(expected)
            }
        }
    }
    override suspend fun show(image: RasterFit.Image): RasterFit.Image {
        require(image.width == identity.width && image.height == identity.height)
        val actual = image.copy(pixels = ByteArray(image.pixels.size) { ((image.pixels.u8(it) * 15 + 127) / 255 * 17).toByte() })
        val box = GlasslyCodec.changed(previous, actual) ?: return actual
        try {
            val full = if (previous == null) byteArrayOf(6) + GlasslyCodec.rle(actual.pixels) else null
            if (full != null && full.size <= 65535) message(full)
            else {
                // Bound noisy updates as 32-row boxes to fit the 16-bit record length.
                var y = box.top
                while (y < box.top + box.height) {
                    message(GlasslyCodec.box(actual, box.copy(top = y, height = minOf(32, box.top + box.height - y)), frame++))
                    y += 32
                }
            }
            previous = actual.pixels.copyOf()
        } catch (e: Exception) { previous = null; throw e } // Any partial mutation needs a full keyframe next time.
        return actual
    }
    override suspend fun keepAlive() { control(4, 1) }
    override suspend fun close() {
        closing = true
        try { if (pageOpen) control(3, 0); previous = null }
        finally { closing = false }
    }
}
