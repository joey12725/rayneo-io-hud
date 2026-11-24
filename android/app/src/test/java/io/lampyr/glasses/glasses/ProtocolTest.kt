package io.lampyr.glasses.glasses

import io.lampyr.glasses.glasses.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun crcVectors() {
        assertEquals(0, Crc16Xmodem.compute(byteArrayOf()))
        assertEquals(0x31c3, Crc16Xmodem.compute("123456789".toByteArray()))
        assertEquals(0x1021, Crc16Xmodem.compute(byteArrayOf(1)))
    }
    @Test fun segmentedStreamRoundTripAndAck() {
        val payload = ByteArray(16_319) { (it * 17).toByte() }
        val original = RayneoFrame(0x13, 0xfeda, payload, flags = 1, address = 6, slices = byteArrayOf(9, 8, 7))
        val wire = FrameCodec.encode(original)
        val decoder = FrameCodec.StreamDecoder()
        val decoded = FrameCodec.segment(wire + FrameCodec.encode(FrameCodec.acknowledgement(original)), 37).flatMap(decoder::append)
        assertEquals(2, decoded.size)
        assertEquals(0x13, decoded[0].business)
        assertEquals(0xfeda, decoded[0].sequence)
        assertEquals(6, decoded[0].address)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded[0].slices)
        assertArrayEquals(payload, decoded[0].payload)
        assertEquals(0x63, decoded[0].flags)
        assertTrue(decoded[1].acknowledgement)
        assertEquals(0, decoded[1].flags)
        assertEquals(original.sequence, decoded[1].sequence)
        assertEquals(original.business, decoded[1].business)
        assertTrue(decoded[1].payload.isEmpty())
        assertTrue(FrameCodec.segment(wire, 37).all { it.size <= 37 })
    }
    @Test fun splitMagicCorruptCrcAndConcatenation() {
        val wire = FrameCodec.encode(RayneoFrame(0x15, 42, byteArrayOf(1, 2, 3)))
        val corrupt = wire.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val decoder = FrameCodec.StreamDecoder()
        assertTrue(decoder.append(byteArrayOf(7, 8, wire[0])).isEmpty())
        assertEquals(42, decoder.append(wire.copyOfRange(1, wire.size)).single().sequence)
        assertEquals(2, decoder.append(corrupt + wire + wire).size)
    }
    @Test fun payloadCapAndOpaqueMetadataValidation() {
        val max = FrameCodec.encode(RayneoFrame(1, 0, ByteArray(65524)))
        assertEquals(65524, FrameCodec.StreamDecoder().append(max).single().payload.size)
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(RayneoFrame(1, 0, ByteArray(65525))) }
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(RayneoFrame(1, 0, byteArrayOf(), slices = ByteArray(8))) }
    }
    @Test fun protobufEnvelopeRoundTripWithLongLengthsAndUnknownFields() {
        val message = Envelope(163, "{\"text\":\"${"é".repeat(512)}\"}", ByteArray(240) { it.toByte() }, 2)
        val decoded = Envelope.decode(message.encode() + byteArrayOf(0x29) + ByteArray(8))
        assertEquals(message.type, decoded.type); assertEquals(message.json, decoded.json); assertEquals(2, decoded.version)
        assertArrayEquals(message.audio, decoded.audio)
        val id = Long.MAX_VALUE - 5
        assertEquals(id, Proto.read(Proto.Writer().int(17, id).build()).single().integer)
        assertNull(Envelope.decode(Envelope(5).encode()).audio)
        assertArrayEquals(byteArrayOf(), Envelope.decode(Envelope(2, audio = byteArrayOf()).encode()).audio)
        assertThrows(IllegalArgumentException::class.java) { Proto.read(byteArrayOf(0x1a, 0x7f)) }
        assertThrows(IllegalArgumentException::class.java) { Proto.read(byteArrayOf(0x10, 0x80.toByte())) }
    }
    @Test fun authProofFixedIndependentVector() {
        // Independently calculated with Python hashlib from §2.4's concatenation and public K.
        val proof = Pairing.proof(hex("00010203"), hex("112233445566"), hex("aabbccddeeff"))
        assertEquals("3bff93640ed2c573e59a8e2368f15931a982393d44d139dce5d0fe8982f3aab1", proof.joinToString("") { "%02x".format(it.toInt() and 255) })
        assertArrayEquals(hex("112233445566"), Pairing.phoneId("abcd112233445566"))
        val payload = Pairing.encode(24, listOf(16 to hex("00010203"), 17 to proof))
        assertArrayEquals(proof, Pairing.fields(payload)[17])
        assertThrows(IllegalArgumentException::class.java) { Pairing.fields(payload.copyOf(payload.size - 1)) }
    }
    @Test fun mtuCallbackGateAndAdvertAddress() {
        val gate = BleIdentityGate()
        assertFalse(gate.mtuReady()); assertTrue(gate.servicesReady()); assertFalse(gate.servicesReady())
        assertTrue(gate.mtuReady()); assertFalse(gate.mtuReady())
        assertEquals("AA:BB:CC:DD:EE:FF", BleAuthenticator.manufacturerAddress(hex("06aabbccddeeff00")))
        assertNull(BleAuthenticator.manufacturerAddress(hex("07aabbccddeeff")))
    }
    @Test fun commandUnitsWeatherOrderingAndOtaGuard() {
        val script = "A🙂B"
        val settings = Json.parseToJsonElement(Commands.teleprompterPrepare("id", script).envelope.json).jsonObject
        assertEquals(4, settings.int("total"))
        assertEquals(2, Commands.teleprompterList("id", "title", script, 0, 0).envelope.version)
        val notification = Commands.notification("title", "body", 1).envelope
        assertEquals("io.lampyr.glasses", Json.parseToJsonElement(notification.json).jsonObject.string("appId"))
        assertNotNull(notification.audio)
        assertNull(Commands.captionText("sid", "grid").envelope.audio)
        assertEquals("4f9f2cab", Commands.fnv1a("hello".toByteArray()))
        val city = Commands.weatherCity("Town", "existing", 42, 100, "Clear", "40°/20°", listOf("10am" to (22 to 100), "9pm" to (20 to 150)))
        val hourly = city["hourly"]!!.jsonObject.keys.toList()
        assertEquals(listOf("10am", "9pm"), hourly)
        assertEquals(Long.MAX_VALUE, Commands.todoItem(Long.MAX_VALUE, "item", 0, 0).long("eventID"))
        listOf(Commands.firmwareVersion(), Commands.firmwareBattery(), Commands.firmwareValidation()).forEach { it.frame(0) }
        assertThrows(IllegalArgumentException::class.java) { Command(9, Envelope(3)).frame(0) }
    }
    @Test fun scriptTransferRequestedChunksAndCompletion() {
        val transfer = ScriptTransfer("task", "did", "A🙂B")
        val descriptor = Proto.read(Proto.read(transfer.descriptor().payload).single().bytes!!).associateBy { it.number }
        assertEquals(6L, descriptor[3]?.integer)
        val request = Proto.Writer().bytes(3, Proto.Writer().text(1, "task").int(2, 1).int(4, 4).build()).build()
        val chunk = (transfer.receive(request) as ScriptTransfer.Reply.Chunk).frame
        val fields = Proto.read(Proto.read(chunk.payload).single().bytes!!).associateBy { it.number }
        assertEquals(0L, fields[3]?.integer)
        assertEquals(4L, fields[4]?.integer)
        assertArrayEquals("A🙂B".toByteArray().copyOf(4), fields[5]?.bytes)
        val complete = Proto.Writer().bytes(5, Proto.Writer().text(1, "task").build()).build()
        assertEquals(0L, (transfer.receive(complete) as ScriptTransfer.Reply.Complete).result)
    }
    @Test fun eventsAndLifeLogBatchMasks() {
        fun parse(business: Int, type: Int, json: String, audio: ByteArray? = null) = EventParser.parse(RayneoFrame(business, 0, Envelope(type, json, audio).encode()))
        assertEquals("session", (parse(0x13, 3, "{\"sid\":\"session\",\"reason_code\":10}") as GlassesEvent.PageExit).sid)
        assertTrue((parse(0x15, 34, "{\"suggestUID\":\"id\",\"suggestType\":2,\"cmd\":1}") as GlassesEvent.HeadGesture).accepted)
        assertTrue(parse(0x0d, 161, "{}") is GlassesEvent.LifeLog)
        assertTrue(parse(0x0d, 1, "{}") is GlassesEvent.Wake)
        assertEquals(82, (parse(0x0f, 1, "{\"generalStatus\":{\"battery\":82,\"brightness\":7}}") as GlassesEvent.Status).battery)
        val page = obj("cmd" to "glass_preview", "payload" to obj("data" to obj("scence" to 2, "index" to 1, "app" to obj("name" to "com.rayneo.liteos.aiSubtitle", "action" to 2)).toString()))
        assertTrue((parse(0x0f, 24, page.toString()) as GlassesEvent.PageShown).exited)
        val packets = EventParser.splitLifeLog(obj("frameCount" to 99, "vadMask" to 5, "vpuMask" to 2), ByteArray(720))
        assertEquals(3, packets.size); assertEquals(240, packets[0].opus.size)
        assertTrue(packets[0].vad); assertFalse(packets[0].vpu); assertTrue(packets[1].vpu)
        assertEquals(31, EventParser.splitLifeLog(obj("frameCount" to 100), ByteArray(240 * 40)).size)
    }
    @Test fun audioDedupWindow() {
        val window = AudioSequenceWindow()
        assertTrue(window.accept(1)); assertFalse(window.accept(1))
        for (i in 2L..65L) assertTrue(window.accept(i))
        assertTrue(window.accept(1)); assertTrue(window.accept(null)); window.clear(); assertTrue(window.accept(1))
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
