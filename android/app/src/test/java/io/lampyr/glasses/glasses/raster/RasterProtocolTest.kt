package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.glasses.DisplayExperiments
import io.lampyr.glasses.glasses.protocol.*
import io.lampyr.glasses.render.RasterFit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Decoders in this source set intentionally do not call production packing, RLE, or CRC routines. */
class RasterProtocolTest {
    private fun u(b: Byte) = b.toInt() and 255
    private fun crc32(b: ByteArray) = CRC32().apply { update(b) }.value
    private fun short(b: ByteArray, p: Int) = u(b[p]) + 256 * u(b[p + 1])
    private fun packbits(bytes: ByteArray, count: Int): ByteArray {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < bytes.size) {
            val control = u(bytes[i++]); require(control != 128)
            if (control <= 127) { repeat(control + 1) { out.write(u(bytes[i++])) } }
            else { val value = u(bytes[i++]); repeat(257 - control) { out.write(value) } }
        }
        return out.toByteArray().also { require(it.size == count) }
    }
    private fun rle(bytes: ByteArray, count: Int): ByteArray {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < bytes.size) {
            val token = u(bytes[i++]); var n = token / 16
            if (n == 0) { n = u(bytes[i++]); if (n == 0) { n = short(bytes, i); i += 2 } }
            require(n > 0 && out.size() + n <= count)
            repeat(n) { out.write((token % 16) * 17) }
        }
        return out.toByteArray().also { require(it.size == count) }
    }
    private fun crcFalse(data: ByteArray): Int {
        // Polynomial long division over each bit, rather than the production byte algorithm.
        var remainder = 65535
        for (b in data) for (bit in 7 downTo 0) {
            val carry = ((remainder ushr 15) xor (u(b) ushr bit)) and 1
            remainder = (remainder shl 1) and 65535
            if (carry == 1) remainder = remainder xor 0x1021
        }
        return remainder
    }
    private data class Tvp(val op: Int, val sid: Int, val seq: Int, val a: Int, val b: Int, val c: Int, val data: ByteArray)
    private fun tvp(bytes: ByteArray): Tvp {
        assertEquals("TVP1", bytes.copyOf(4).toString(Charsets.US_ASCII)); assertEquals(1, u(bytes[4]))
        val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(crc32(bytes.copyOfRange(0, 28) + bytes.copyOfRange(32, bytes.size)), fields.getInt(28).toLong() and 0xffffffffL)
        return Tvp(u(bytes[5]), fields.getInt(8), fields.getInt(12), fields.getInt(16), fields.getInt(20), fields.getInt(24), bytes.copyOfRange(32, bytes.size))
    }
    private fun reply(q: Tvp, a: Int = 0, b: Int = 0, c: Int = 0, result: Int = 0): ByteArray {
        val bytes = ByteArray(32); "VPA1".toByteArray().copyInto(bytes); bytes[4] = 1; bytes[5] = q.op.toByte(); bytes[6] = result.toByte()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8, q.sid); buffer.putInt(12, q.seq); buffer.putInt(16, a); buffer.putInt(20, b); buffer.putInt(24, c)
        buffer.putInt(28, crc32(bytes.copyOf(28)).toInt())
        return bytes
    }
    private abstract class Wire : RasterWire {
        var next = 0x40fe
        val replies = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
        override fun reserveSequences(count: Int) = next.also { next += count } and 65535
        override suspend fun awaitReply(business: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray = withTimeout(timeoutMs) {
            var found: ByteArray? = null
            while (found == null) { val r = replies.receive(); if (r.first == business && matches(r.second)) found = r.second }
            found
        }
    }
    @Test fun standardCrcVectorsAndMalformedPackets() {
        assertEquals(0xcbf43926L, Tvp2Codec.crc("123456789".toByteArray()))
        assertEquals(0x29b1, GlasslyCodec.crc("123456789".toByteArray()))
        val q = Tvp(1, 123, 1, 0, 0, 0, byteArrayOf())
        val bytes = reply(q, 540, 220 or 65536, 100)
        assertEquals(540L, Tvp2Codec.decode(bytes)!!.a)
        for (i in bytes.indices) { val corrupt = bytes.copyOf(); corrupt[i] = (u(corrupt[i]) xor 1).toByte(); assertNull(Tvp2Codec.decode(corrupt)) }
        assertNull(Tvp2Codec.decode(bytes.copyOf(31)))
    }
    @Test fun compressionLimitsAndNoisyTilesDecodeIndependently() {
        for (size in listOf(1, 2, 3, 127, 128, 129, 255, 65535, 70000)) {
            for (source in listOf(ByteArray(size) { 255.toByte() }, ByteArray(size) { (it % 251).toByte() })) {
                assertArrayEquals(source, packbits(Tvp2Codec.compress(source), size))
            }
            val pixels = ByteArray(size) { if (it < size / 2) 255.toByte() else 0 }
            assertArrayEquals(pixels, rle(GlasslyCodec.rle(pixels), size))
        }
        for (levels in listOf(2, 4, 8, 16)) {
            val pixels = ByteArray(118800) { (it % levels * 255 / (levels - 1)).toByte() }
            val encoded = Tvp2Codec.frame(RasterFit.Image(540, 220, levels, pixels))
            val decoded = ByteArrayOutputStream()
            for (strip in encoded.strips) {
                assertTrue(strip.size <= 14967)
                val packed = packbits(strip, 540 * 55 * encoded.bits / 8)
                for (i in 0 until 29700) { val v = (u(packed[i * encoded.bits / 8]) ushr (8 - encoded.bits - i % (8 / encoded.bits) * encoded.bits)) and ((1 shl encoded.bits) - 1); decoded.write(v * 255 / ((1 shl encoded.bits) - 1)) }
            }
            assertArrayEquals(encoded.image.pixels, decoded.toByteArray())
        }
    }
    @Test fun tvpSessionWaitsForAcksChecksCrcCommitsAllStripsAndDeduplicates() = runTest {
        val ops = mutableListOf<Int>(); var frameBytes = 0; var total = 0; var frames = 0; var expectedCrc = 0
        val canvas = ByteArrayOutputStream()
        val wire = object : Wire() {
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                assertEquals(15, business); assertFalse(ble)
                val q = tvp(payload); ops += q.op
                val ack = when (q.op) {
                    1 -> { assertEquals(0x32445056, q.a); reply(q, 540, 220 or 65536, 1234) }
                    2 -> { assertEquals(1234, q.a); reply(q) }
                    6 -> { expectedCrc = q.c; frameBytes = 0; canvas.reset(); reply(q, q.a) }
                    7 -> {
                        assertEquals(crc32(q.data).toInt(), q.c); frameBytes += q.data.size
                        val packed = packbits(q.data, 29700 * q.b / 8)
                        repeat(29700) { i -> val v = (u(packed[i * q.b / 8]) ushr (8 - q.b - i % (8 / q.b) * q.b)) and ((1 shl q.b) - 1); canvas.write(v * 255 / ((1 shl q.b) - 1)) }
                        reply(q, (q.a and 65535) + (q.a ushr 16), 2, frameBytes)
                    }
                    8 -> { assertEquals(expectedCrc, q.c); assertEquals(crc32(canvas.toByteArray()).toInt(), q.c); total += frameBytes; reply(q, ++frames, 3, total) }
                    4 -> reply(q, 0, frames, total)
                    else -> reply(q)
                }
                replies.send(business to ack)
                return reserveSequences(1)
            }
        }
        val sender = Tvp2Transport(wire, 123)
        assertTrue(sender.probe()); sender.open()
        val image = RasterFit.Image(540, 220, 16, ByteArray(118800) { if (it % 17 == 0) 255.toByte() else 0 })
        val actual = sender.show(image)
        assertArrayEquals(image.pixels, actual.pixels)
        val count = ops.size; sender.show(image); assertEquals(count, ops.size)
        val changed = image.copy(pixels = image.pixels.copyOf().apply { this[200] = 255.toByte() })
        sender.show(changed)
        assertEquals(8, ops.count { it == 7 }) // Even a one-pixel change needs all four strips.
        sender.keepAlive(); sender.close()
        assertEquals(5, ops.last())
    }
    private fun identity(revision: Int = 14, flags: Int = 0x7f): ByteArray {
        val bytes = ByteArray(13); bytes[0] = 0x11; bytes[1] = revision.toByte()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(2, 540); b.putShort(4, 220); b.putShort(6, 240); b.putShort(8, 256); b.putShort(10, revision.toShort()); bytes[12] = flags.toByte()
        return bytes + "GLASSLYCFW-IO/$revision\u0000".toByteArray() + byteArrayOf(25)
    }
    @Test fun glasslyIdentityAndVersionQueriesNeverGuessSupportFromOsVersion() {
        val id = GlasslyIdentity.parse(identity())!!
        assertTrue(id.raster); assertTrue(id.stereo); assertEquals(14, id.revision)
        assertFalse(GlasslyIdentity.parse(identity(6))!!.raster)
        assertFalse(GlasslyIdentity.parse(identity(flags = 3))!!.raster)
        assertNull(GlasslyIdentity.parse(identity().copyOf(12)))
        val status = EventParser.parse(Commands.firmwareVersion().copy(envelope = Envelope(1, "{\"OsVersion\":\"Strix OS 1.0.6.11\"}")).frame(1)) as GlassesEvent.Status
        assertEquals("Strix OS 1.0.6.11", status.firmware)
        for (mode in DisplayMode.entries) assertNull(RasterCapabilities().select(mode))
        assertEquals(RasterKind.GLASSLY, RasterCapabilities(glassly = id).select(DisplayMode.AUTO))
        assertEquals(RasterKind.TVP2, RasterCapabilities(true, id).select(DisplayMode.RASTER))
        assertNull(RasterCapabilities(true, id).select(DisplayMode.TEXT))
    }
    @Test fun glasslySessionUsesBleFullFrameThenAlignedBoxAndAcksBothEyes() = runTest {
        val canvas = ByteArray(118800); val modes = mutableListOf<Int>(); var stream = 0
        val record = ByteArrayOutputStream(); var firstSequence = 0; var packets = 0
        val wire = object : Wire() {
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                assertEquals(0xf0, business); assertTrue(ble)
                if (u(payload[0]) == 16) {
                    replies.send(business to when (u(payload[2])) { 1 -> identity(); 3 -> byteArrayOf(0x13, 0, 0); else -> byteArrayOf(0x13, 1, 0) })
                    return reserveSequences(1)
                }
                assertTrue(payload.size <= 242)
                if (u(payload[0]) and 128 != 0) { stream = u(payload[1]); firstSequence = sequence!!; packets = 0; record.reset() }
                assertEquals(stream, u(payload[1])); assertEquals((firstSequence + packets++) and 65535, sequence)
                assertEquals(firstSequence and 255, stream)
                record.write(payload, 2, payload.size - 2)
                if (u(payload[0]) and 64 != 0) {
                    val bytes = record.toByteArray(); assertEquals(11, u(bytes[0])); assertEquals(short(bytes, 1), bytes.size - 5)
                    val message = bytes.copyOfRange(5, bytes.size); assertEquals(crcFalse(message), short(bytes, 3)); modes += u(message[0])
                    if (message[0] == 6.toByte()) rle(message.copyOfRange(1, message.size), canvas.size).copyInto(canvas)
                    else {
                        assertEquals(3, u(message[0])); val l = u(message[1]) * 4; val t = u(message[2]) * 2; val w = u(message[3]) * 4; val h = u(message[4]) * 2
                        val pixels = rle(message.copyOfRange(7, message.size), w * h)
                        pixels.indices.forEach { i -> canvas[(t + i / w) * 540 + l + i % w] = pixels[i] }
                    }
                    for (lens in listOf(2, 1)) { // Intentionally out of order; must collect both.
                        val ack = ByteArray(9); ack[0] = 1; ack[1] = stream.toByte(); ack[4] = lens.toByte()
                        val b = ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN); b.putShort(5, message.size.toShort()); b.putShort(7, crcFalse(message).toShort())
                        replies.send(business to ack)
                    }
                }
                return sequence!!
            }
        }
        val id = GlasslyTransport.probe(wire)!!; val sender = GlasslyTransport(wire, id)
        sender.open()
        val image = RasterFit.Image(540, 220, 16, ByteArray(118800))
        sender.show(image); assertEquals(listOf(6), modes)
        val changed = image.copy(pixels = image.pixels.copyOf().apply { this[539 + 219 * 540] = 255.toByte() })
        sender.show(changed); assertEquals(listOf(6, 3), modes); assertArrayEquals(changed.pixels, canvas)
        assertEquals(GlasslyCodec.Box(536, 218, 4, 2), GlasslyCodec.changed(image.pixels, changed))
        sender.show(changed); assertEquals(2, modes.size)
        sender.keepAlive(); sender.close()
        sender.open()
        val noise = RasterFit.Image(540, 220, 16, ByteArray(118800) { if (it % 2 == 0) 255.toByte() else 0 })
        assertTrue(GlasslyCodec.rle(noise.pixels).size > 65535)
        val before = modes.size
        sender.show(noise)
        assertEquals(7, modes.size - before)
        assertTrue(modes.drop(before).all { it == 3 })
        assertArrayEquals(noise.pixels, canvas)
        sender.close()
    }
    @Test fun busyHelloCountsAsPresentAndOpenWaitsForThePageToClear() = runTest {
        var hellos = 0; val ops = mutableListOf<Int>()
        val wire = object : Wire() {
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                val q = tvp(payload); ops += q.op
                replies.send(business to when {
                    q.op == 1 && ++hellos <= 3 -> reply(q, result = 2) // Text page still owns the display.
                    q.op == 1 -> reply(q, 540, 220 or 65536, 77)
                    else -> reply(q)
                })
                return reserveSequences(1)
            }
        }
        assertTrue(Tvp2Transport(wire, 5).probe())
        Tvp2Transport(wire, 6).open()
        assertEquals(listOf(1, 1, 1, 1, 2), ops)
        // Any other refusal is still "no receiver", and a display that never clears still fails.
        val refuses = object : Wire() {
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                val q = tvp(payload); replies.send(business to reply(q, result = if (q.sid == 7) 1 else 2)); return reserveSequences(1)
            }
        }
        assertFalse(Tvp2Transport(refuses, 7).probe())
        try { Tvp2Transport(refuses, 8).open(); fail("Still busy") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("(2)")) }
    }
    @Test fun probesTimeOutAndSenderNeverAdvancesWithoutAValidAck() = runTest {
        val wire = object : Wire() {
            var sends = 0
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int { sends++; return reserveSequences(1) }
        }
        assertFalse(Tvp2Transport(wire, 1).probe())
        assertNull(GlasslyTransport.probe(wire))
        assertEquals(2, wire.sends)
        try { Tvp2Transport(wire, 1).open(); fail("No ACK") } catch (_: TimeoutCancellationException) { }
        assertEquals(3, wire.sends) // No OPEN after the unanswered HELLO.
    }
    @Test fun stockExperimentsPreserveRawGlyphsAndHaveBoundedFrames() {
        for (install in listOf(true, false)) {
            val command = DisplayExperiments.widget(install)
            assertTrue(FrameCodec.encode(command.frame(1)).size <= 509)
            assertEquals(18, command.envelope.type)
        }
        for (line in DisplayExperiments.glyphPage(0)) assertEquals(26, line.codePointCount(0, line.length))
        assertTrue(DisplayExperiments.glyphPage(0).last().startsWith("😀\uE000"))
        assertEquals(12, DisplayExperiments.weather.size)
        val notification = kotlinx.serialization.json.Json.parseToJsonElement(Commands.notification("t", "b", appId = "com.spotify.music", category = 33).envelope.json) as kotlinx.serialization.json.JsonObject
        assertEquals(33, notification.int("category"))
        assertEquals("com.spotify.music", notification.string("appId"))
    }
    @Test fun tvpRejectsBusyAndCounterMismatchBeforeSendingFurtherStrips() = runTest {
        for (busy in listOf(true, false)) {
            val ops = mutableListOf<Int>()
            val wire = object : Wire() {
                override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                    val q = tvp(payload); ops += q.op
                    replies.send(business to when(q.op) {
                        1 -> reply(q, 540, 220 or 65536, 100)
                        6 -> reply(q, q.a)
                        7 -> if (busy) reply(q, result = 2) else reply(q, 54, 0, q.data.size)
                        else -> reply(q)
                    })
                    return reserveSequences(1)
                }
            }
            val sender = Tvp2Transport(wire, 1); sender.open()
            try { sender.show(RasterFit.Image(540, 220, 16, ByteArray(118800))); fail("Bad strip ACK") }
            catch (_: IllegalStateException) { }
            assertEquals(listOf(1, 2, 6, 7), ops)
        }
    }
    @Test fun tvpHonorsPauseAndExitEventsWithSeparateMonotonicSequence() = runTest {
        var sid = 0
        val wire = object : Wire() {
            override suspend fun send(business: Int, payload: ByteArray, ble: Boolean, sequence: Int?): Int {
                val q = tvp(payload); sid = q.sid
                replies.send(business to if (q.op == 1) reply(q, 540, 220 or 65536, 100) else reply(q))
                return reserveSequences(1)
            }
        }
        val sender = Tvp2Transport(wire, 12); sender.open()
        sender.event(reply(Tvp(32, sid, 1, 0, 0, 0, byteArrayOf()), a = 1)); assertFalse(sender.acceptingFrames)
        sender.event(reply(Tvp(32, sid, 1, 0, 0, 0, byteArrayOf()), a = 0)); assertFalse(sender.acceptingFrames)
        sender.event(reply(Tvp(32, sid, 2, 0, 0, 0, byteArrayOf()), a = 0)); assertTrue(sender.acceptingFrames)
        sender.event(reply(Tvp(33, sid, 3, 0, 0, 0, byteArrayOf()))); assertFalse(sender.acceptingFrames)
    }
}
