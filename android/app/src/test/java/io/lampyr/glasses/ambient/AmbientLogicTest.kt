package io.lampyr.glasses.ambient

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*
import kotlin.math.sin

class AmbientLogicTest {
    private val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    @Test fun decoderOutputCanSplitAndCoalesceOriginalPackets() {
        val assembler = PcmPacketAssembler()
        val first = AmbientPacket(packet, 1000, "test")
        val second = AmbientPacket(packet, 1020, "test")
        assembler.enqueue(first, 0); assembler.enqueue(second, 960)
        assertTrue(assembler.decoded(ShortArray(480 * 2) { 11 }, 2, 0).isEmpty())
        val decoded = assembler.decoded(ShortArray(1440 * 2) { 22 }, 2, 10000)
        assertEquals(listOf(first, second), decoded.map { it.packet })
        assertArrayEquals(ShortArray(960 * 2) { if (it < 480 * 2) 11 else 22 }, decoded[0].pcm)
        assertArrayEquals(ShortArray(960 * 2) { 22 }, decoded[1].pcm)
        assertEquals(0, assembler.size)
    }

    @Test fun halfOpenExclusionsHandleOverlappingClientsAndEntirePackets() {
        val ranges = ExclusionRanges()
        ranges.begin("dictation", 1000); ranges.begin("memo", 1100)
        assertFalse(ranges.overlaps(980, 1000)); assertTrue(ranges.overlaps(990, 1010))
        ranges.end("dictation", 1200)
        assertTrue(ranges.overlaps(1200, 1210))
        ranges.end("memo", 1300)
        assertFalse(ranges.overlaps(1300, 1320)); assertTrue(ranges.overlaps(1250, 1320))
        ranges.prune(1400); assertFalse(ranges.overlaps(1100, 1120))
    }
    @Test fun fallbackRejectsSilenceDcAndHighCrossingNoiseAndHoldsSpeech() {
        fun pcm(hz: Double, amplitude: Int) = ShortArray(960) { (sin(it * 2 * Math.PI * hz / 48000) * amplitude).toInt().toShort() }
        val vad = EnergyVad()
        assertFalse(vad.speech(ShortArray(960), channels = 1))
        assertFalse(vad.speech(ShortArray(960) { 5000 }, channels = 1))
        assertFalse(vad.speech(ShortArray(960) { if (it % 2 == 0) 5000 else -5000 }, channels = 1))
        assertTrue(vad.speech(pcm(300.0, 4000), channels = 1))
        repeat(29) { assertTrue(vad.speech(ShortArray(960), channels = 1)) }
        assertFalse(vad.speech(ShortArray(960), channels = 1))
        vad.reset(); assertFalse(vad.speech(pcm(300.0, 50), channels = 1))
        assertTrue(vad.wearer(ShortArray(1920) { if (it % 2 == 0) 5000 else 1000 }, 2))
        assertFalse(vad.wearer(ShortArray(1920) { 5000 }, 2))
        assertFalse(vad.wearer(pcm(300.0, 4000), 1))
        vad.reset()
        val voice = pcm(300.0, 4000)
        assertTrue(vad.speech(ShortArray(1920) { if (it % 2 == 0) voice[it / 2] else (-voice[it / 2]).toShort() }))
    }
    @Test fun locationReuseDistanceMotionAndHeuristic() {
        val fix = GeoFix("c", 1000, 41.0, -87.0, 50f, motion = "still", source = "test", confidence = "test")
        assertTrue(LocationPolicy.reusable(fix, 121000)); assertFalse(LocationPolicy.reusable(fix, 121001))
        assertFalse(LocationPolicy.reusable(fix.copy(accuracy = 51f), 1000)); assertFalse(LocationPolicy.reusable(fix, 999))
        assertFalse(LocationPolicy.during(fix, fix.copy(lat = 41.001)))
        assertTrue(LocationPolicy.during(fix, fix.copy(lat = 41.002)))
        assertTrue(LocationPolicy.during(fix, fix.copy(motion = "walking")))
        assertFalse(LocationPolicy.during(fix, fix.copy(motion = "unknown")))
        assertEquals("walking", LocationPolicy.motion(null, true))
        listOf(0f to "still", 1f to "walking", 4f to "bicycle", 10f to "vehicle").forEach { (speed, label) -> assertEquals(label, LocationPolicy.motion(speed, false)) }
        assertEquals("unknown", LocationPolicy.motion(null, false))
    }

}
