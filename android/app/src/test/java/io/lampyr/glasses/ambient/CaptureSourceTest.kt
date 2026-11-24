package io.lampyr.glasses.ambient

import io.lampyr.glasses.glasses.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CaptureSourceTest {
    @Test fun lifeLogPerPacketVadSurvivesTheOrderedDecodeBoundary() = runTest {
        val commands=mutableListOf<Command>();val packets=mutableListOf<AmbientPacket>()
        val source=LifeLogCaptureSource({ commands+=it },{ packets+=it },{},{})
        source.start();source.event(GlassesEvent.LifeLog(161,obj(),emptyList()),1000)
        val task=Json.parseToJsonElement(commands.last { it.envelope.type==162 }.envelope.json).jsonObject.string("taskId")!!
        source.event(GlassesEvent.LifeLog(163,obj("taskId" to task),listOf(
            LifeLogPacket(byteArrayOf(0x18),true,false),LifeLogPacket(byteArrayOf(0x18),false,false))),1120)
        assertEquals(listOf(true,false),packets.map { it.speech })
    }
    @Test fun sampleClockDoesNotAccumulateRoundingErrorForTwoPointFiveMillisecondPackets() {
        val clock = ArrivalSampleClock()
        repeat(10_000) { packet -> assertNull(clock.batch((packet + 1) * 5L / 2, 120)) }
    }
    @Test fun uncertainSequencesUseClockDriftAndMarkOnlyFirstPacketOfLifeLogBatch() = runTest {
        val commands = mutableListOf<Command>(); val packets = mutableListOf<AmbientPacket>()
        val source = LifeLogCaptureSource({ commands += it }, { packets += it }, {}, {})
        source.start(); source.event(GlassesEvent.LifeLog(161, obj(), emptyList()), 1000)
        val task = Json.parseToJsonElement(commands.last { it.envelope.type == 162 }.envelope.json).jsonObject.string("taskId")!!
        val body = obj("taskId" to task)
        val batch = List(2) { LifeLogPacket(byteArrayOf(0x18), true, false) }
        source.event(GlassesEvent.LifeLog(163, body, batch, 65535), 1000)
        source.event(GlassesEvent.LifeLog(163, body, batch, 0), 1120) // wrap: no assumed loss
        source.event(GlassesEvent.LifeLog(163, body, batch, 7), 1240) // shared frame sequence, no assumed loss
        source.event(GlassesEvent.LifeLog(163, body, batch, 8), 5000) // late: within burst tolerance, not a gap
        source.event(GlassesEvent.LifeLog(163, body, batch, 8), 5000) // duplicate cannot advance clock
        source.event(GlassesEvent.LifeLog(163, body, batch, 9), 65_000) // a minute with no audio: a real gap
        assertEquals(10, packets.size)
        assertEquals(1, packets.count { it.discontinuityBefore != null })
        assertNotNull(packets[8].discontinuityBefore); assertNull(packets[9].discontinuityBefore)
        assertEquals(listOf(880L, 940, 1000, 1060, 1120, 1180, 1240, 1300), packets.take(8).map { it.utc })
        assertEquals(64_880L, packets[8].utc)
        val root = java.nio.file.Files.createTempDirectory("source-conversations").toFile()
        val store = ConversationStore(root)
        ConversationRecorder(store).use { recorder ->
            packets.forEach { p ->
                p.discontinuityBefore?.let { recorder.drop(p.utc, it) }
                recorder.packet(p.opus, p.utc, p.source, false, p.arrivalUtc)
            }
        }
        // A gap adds a time anchor; it no longer ends the conversation.
        assertEquals(1, store.all().size)
        assertEquals(28800, store.all().sumOf { it.samples })
        assertEquals(2, store.all().single().manifest.segments.size)
        store.all().single().manifest.validate()
        root.deleteRecursively()
    }

    @Test fun continuousClockDetectsMissingAudioEvenWithoutSequenceAndIgnoresBatchJitter() = runTest {
        val commands = mutableListOf<Command>(); val packets = mutableListOf<AmbientPacket>()
        val source = ContinuousCaptureSource({ commands += it }, { packets += it }, {})
        source.start()
        val sid = Json.parseToJsonElement(commands.single().envelope.json).jsonObject.string("sid")!!
        val bytes = byteArrayOf(0xfc.toByte(), 0xff.toByte(), 0xfe.toByte())
        listOf(1000L, 1020, 1020, 1040, 5000, 5020).forEach {
            source.event(GlassesEvent.Audio(0x17, sid, null, bytes), it)
        }
        assertEquals(6, packets.size)
        assertEquals(1, packets.count { it.discontinuityBefore != null })
        assertNotNull(packets[4].discontinuityBefore)
        assertEquals(5000, packets[4].arrivalUtc)
    }

    @Test fun lifeLogHandshakeTaskValidationBatchOffsetsMasksAndDiagnosticOnlyCache() = runTest {
        val commands = mutableListOf<Command>(); val packets = mutableListOf<AmbientPacket>(); val logs = mutableListOf<String>(); var boundaries = 0
        val source = LifeLogCaptureSource({ commands += it }, { packets += it }, { boundaries++ }, { logs += it }, true)
        source.start()
        assertEquals(listOf(20, 167), commands.map { it.envelope.type })
        source.event(GlassesEvent.LifeLog(161, obj(), emptyList()), 1000)
        assertEquals(listOf(162, 168), commands.takeLast(2).map { it.envelope.type })
        val task = Json.parseToJsonElement(commands[2].envelope.json).jsonObject.string("taskId")!!
        val bytes = ByteArray(480).apply { this[0] = 0x18; this[240] = 0x18 }
        val body = obj("frameCount" to 2, "vpuMask" to 1, "vadMask" to 3, "taskId" to task)
        val split = EventParser.splitLifeLog(body, bytes)
        source.event(GlassesEvent.LifeLog(163, obj("taskId" to "wrong"), split, 8), 1000)
        assertTrue(packets.isEmpty())
        source.event(GlassesEvent.LifeLog(163, body, split, 9), 1000)
        source.event(GlassesEvent.LifeLog(163, body, split, 9), 1000)
        assertEquals(2, packets.size); assertEquals(880, packets[0].utc); assertEquals(940, packets[1].utc)
        assertEquals(1000, packets[1].arrivalUtc); assertTrue(packets[0].vpu); assertFalse(packets[1].vpu)
        assertArrayEquals(bytes.copyOfRange(0, 240), packets.first().opus)
        source.event(GlassesEvent.LifeLog(164, body, split), 1100)
        assertEquals(2, packets.size); assertTrue(logs.any { "A4" in it })
        source.event(GlassesEvent.LifeLog(165, obj(), emptyList()), 1200)
        assertEquals(1, boundaries); source.stop()
    }
    @Test fun continuousValidatesSidBusinessAndSequence() = runTest {
        val commands = mutableListOf<Command>(); val packets = mutableListOf<AmbientPacket>()
        val source = ContinuousCaptureSource({ commands += it }, { packets += it }, {})
        source.start(); assertEquals(0x17, commands.single().business)
        val sid = Json.parseToJsonElement(commands.single().envelope.json).jsonObject.string("sid")!!
        val packet = byteArrayOf(0xfc.toByte(), 0xff.toByte(), 0xfe.toByte())
        source.event(GlassesEvent.Audio(0x13, sid, 1, packet), 1000)
        source.event(GlassesEvent.Audio(0x17, "other", 1, packet), 1000)
        source.event(GlassesEvent.Audio(0x17, sid, 1, packet), 1000)
        source.event(GlassesEvent.Audio(0x17, sid, 1, packet), 1000)
        assertEquals(1, packets.size); assertArrayEquals(packet, packets[0].opus)
        source.stop(); assertEquals(3, commands.last().envelope.type)
    }
    @Test fun autoNeedsAudioEvidenceAndNeverTreatsSilenceAsFailedWake() {
        val policy = AutoFallbackPolicy()
        policy.reset(1000)
        assertFalse(policy.fallback(5000, true)); assertFalse(policy.fallback(11000, false)); assertTrue(policy.fallback(11000, true))
        policy.wake(); assertFalse(policy.fallback(100000, true))
    }
}
