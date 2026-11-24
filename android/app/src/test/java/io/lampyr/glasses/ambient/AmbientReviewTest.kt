package io.lampyr.glasses.ambient

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AmbientReviewTest {

    private val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
    private val utc = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
    private val zone = { ZoneId.of("America/Chicago") }


    @Test fun recorderRetriesExponentiallyUntilStableAndNeverRearmsEarly() {
        val retry = CaptureRecovery()
        var now = 100L
        assertFalse(retry.ready(now))
        listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L).forEach { delay ->
            assertEquals(delay, retry.failed(now))
            assertFalse(retry.ready(now + delay - 1))
            now += delay
            assertTrue(retry.ready(now))
            retry.rearmed(now); retry.healthy(now)
            assertFalse(retry.ready(now))
        }
        retry.healthy(now + 60_000)
        assertEquals(1000, retry.failed(now + 60_000))
        retry.reset(); assertFalse(retry.ready(Long.MAX_VALUE))
    }


    @Test fun oggConstructorClosesOpenedHandleWhenInitializationThrows() {
        val file = Files.createTempDirectory("ambient-handle").toFile().resolve("master.ogg")
        var closed = false
        assertThrows(IllegalArgumentException::class.java) {
            OggOpusWriter(file, -1) { f -> object : RandomAccessFile(f, "rw") {
                override fun close() { closed = true; super.close() }
            } }
        }
        assertTrue(closed)
        file.parentFile!!.deleteRecursively()
    }


    @Test fun zeroPreskipAndGranulesRemainCompatibleAcrossResume() {
        val file = Files.createTempDirectory("ambient-preskip").toFile().resolve("master.ogg")
        OggOpusWriter(file).use { it.append(packet) }
        OggOpusWriter(file).use { assertEquals(960, it.samples); it.append(packet) }
        var headers = 0
        RandomAccessFile(file, "r").use { input ->
            while (true) {
                val p = OggReader.page(input) ?: break
                if (p.flags and 2 != 0) {
                    headers++
                    assertEquals(0, ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
                }
            }
        }
        assertEquals(2, headers)
        val starts = mutableListOf<Long>()
        OggReader.audio(file) { _, sample -> starts += sample }
        assertEquals(listOf(0L, 960L), starts)
        file.parentFile!!.deleteRecursively()
    }
}
