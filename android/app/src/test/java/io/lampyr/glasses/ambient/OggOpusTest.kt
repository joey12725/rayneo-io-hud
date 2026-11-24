package io.lampyr.glasses.ambient

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class OggOpusTest {
    private fun file() = Files.createTempDirectory("ambient-ogg").toFile().resolve("master.ogg")
    private val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    /** Deliberately independent page/CRC implementation: do not validate the writer with itself. */
    private fun strict(file: File): List<ByteArray> {
        val bytes = file.readBytes()
        var p = 0
        var serial: Int? = null
        var sequence = 0
        var granule = 0L
        var eos = true
        val packets = mutableListOf<ByteArray>()
        while (p < bytes.size) {
            assertTrue(p + 27 <= bytes.size)
            assertEquals("OggS", String(bytes, p, 4))
            assertEquals(0, bytes[p + 4].toInt())
            val h = ByteBuffer.wrap(bytes, p, bytes.size - p).slice().order(ByteOrder.LITTLE_ENDIAN)
            val count = bytes[p + 26].toInt() and 255
            assertTrue(p + 27 + count <= bytes.size)
            val laces = bytes.copyOfRange(p + 27, p + 27 + count)
            val length = 27 + count + laces.sumOf { it.toInt() and 255 }
            assertTrue(p + length <= bytes.size)
            val page = bytes.copyOfRange(p, p + length)
            val crc = h.getInt(22)
            repeat(4) { page[22 + it] = 0 }
            var remainder = 0L
            for (byte in page) {
                remainder = remainder xor ((byte.toLong() and 255) shl 24)
                repeat(8) { remainder = ((remainder shl 1) xor if (remainder and 0x80000000L != 0L) 0x04c11db7L else 0L) and 0xffffffffL }
            }
            assertEquals(crc.toLong() and 0xffffffffL, remainder)
            val flags = bytes[p + 5].toInt()
            val body = bytes.copyOfRange(p + 27 + count, p + length)
            if (flags and 2 != 0) {
                assertTrue(eos); assertNotEquals(serial, h.getInt(14)); serial = h.getInt(14)
                sequence = 0; granule = 0; eos = false
                assertEquals("OpusHead", String(body, 0, 8)); assertEquals(2, body[9].toInt())
            }
            assertEquals(serial, h.getInt(14))
            assertEquals(sequence++, h.getInt(18))
            assertEquals(0, flags and 1)
            if (body.isNotEmpty()) {
                assertTrue(laces.isNotEmpty()); assertTrue((laces.last().toInt() and 255) < 255)
                assertEquals(1, laces.count { (it.toInt() and 255) < 255 })
                if (body.size < 8 || String(body, 0, 8) !in listOf("OpusHead", "OpusTags")) {
                    packets += body; granule += OpusPacket.samples(body)
                }
            }
            assertEquals(granule, h.getLong(6))
            if (flags and 4 != 0) eos = true
            p += length
        }
        assertEquals(bytes.size, p); assertTrue(eos)
        return packets
    }

    @Test fun crcKnownVector() { assertEquals(0x89a1897f.toInt(), OggCrc.calculate("123456789".toByteArray())) }
    @Test fun everyTocConfigurationAndFrameCount() {
        for (config in 0..31) {
            val one = when {
                config >= 16 -> 120L shl (config and 3)
                config >= 12 -> if (config and 1 == 0) 480L else 960L
                else -> listOf(480L, 960L, 1920L, 2880L)[config and 3]
            }
            for (stereo in listOf(0, 4)) {
                assertEquals(one, OpusPacket.samples(byteArrayOf(((config shl 3) or stereo).toByte())))
                assertEquals(one * 2, OpusPacket.samples(byteArrayOf(((config shl 3) or stereo or 1).toByte())))
                assertEquals(one * 2, OpusPacket.samples(byteArrayOf(((config shl 3) or stereo or 2).toByte())))
                val max = (5760 / one).toInt()
                assertEquals(one * max, OpusPacket.samples(byteArrayOf(((config shl 3) or stereo or 3).toByte(), max.toByte())))
            }
        }
        listOf(byteArrayOf(), byteArrayOf(3), byteArrayOf(3, 0), byteArrayOf(3, 63), byteArrayOf(0x1b, 3)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OpusPacket.samples(it) }
        }
    }
    @Test fun lacingExactMultiplesAndPacketIdentity() {
        val file = file()
        val packets = listOf(3, 254, 255, 256, 510, 65024).map { n -> ByteArray(n).apply { this[0] = 0x9c.toByte() } }
        OggOpusWriter(file).use { writer -> packets.forEachIndexed { index, p -> assertEquals(index * 960L, writer.append(p)) } }
        val read = strict(file)
        assertEquals(packets.size, read.size); packets.zip(read).forEach { (a, b) -> assertArrayEquals(a, b) }
    }
    @Test fun resumeSealsIncompleteStreamAndDiscardsTornPage() {
        val original = file()
        val copy = File(original.parentFile, "crash.ogg")
        OggOpusWriter(original).use { writer ->
            writer.append(silence); writer.sync(); original.copyTo(copy)
        }
        copy.appendBytes("OggSbroken".toByteArray())
        OggOpusWriter(copy).use { writer -> assertEquals(960, writer.samples); writer.append(silence) }
        assertEquals(2, strict(copy).size)
        OggOpusWriter(copy).use { writer -> assertEquals(1920, writer.samples); writer.append(silence) }
        assertEquals(3, strict(copy).size)
    }
    @Test fun checkpointTrimsUncommittedAudio() {
        val file = file(); var length = 0L
        OggOpusWriter(file).use { it.append(silence); it.sync(); length = it.length; it.append(silence) }
        OggOpusWriter(file, length).use { assertEquals(960, it.samples); it.append(silence) }
        assertEquals(2, strict(file).size)
    }
    @Test fun readerRejectsCorruption() {
        val file = file(); OggOpusWriter(file).use { it.append(silence) }
        RandomAccessFile(file, "rw").use { it.seek(30); it.write(99) }
        assertThrows(IllegalArgumentException::class.java) { OggReader.audio(file) { _, _ -> } }
    }
    @Test fun crcValidLegacyGranulesResynchronizeWithoutLosingPackets() {
        val file = file()
        OggOpusWriter(file).use { it.append(silence); it.append(silence) }
        RandomAccessFile(file, "rw").use { input ->
            repeat(2) { OggReader.page(input) }
            repeat(2) { index ->
                val page = OggReader.page(input)!!
                val bytes = ByteArray((page.end - page.offset).toInt())
                input.seek(page.offset); input.readFully(bytes)
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                header.putLong(6, (index + 1) * 960L + 312)
                header.putInt(22, 0); header.putInt(22, OggCrc.calculate(bytes))
                input.seek(page.offset); input.write(bytes)
            }
        }
        val warnings = mutableListOf<String>(); val starts = mutableListOf<Long>()
        OggReader.audio(file, warning = warnings::add) { packet, start ->
            assertArrayEquals(silence, packet); starts += start
        }
        assertEquals(listOf(0L, 960L), starts)
        assertEquals(1, warnings.size) // second page follows the resynchronized granule
        assertTrue(warnings.single().contains("resynchronizing"))
        OggOpusWriter(file).use { assertEquals(1920, it.samples); it.append(silence) }
        val resumed = mutableListOf<Long>()
        OggReader.audio(file) { _, start -> resumed += start }
        assertEquals(listOf(0L, 960L, 1920L), resumed)
    }
    @Test fun ffmpegRoundTripOptIn() {
        assumeTrue("Set AMBIENT_FFMPEG_TEST=1 to run external decoder validation", System.getenv("AMBIENT_FFMPEG_TEST") == "1")
        val file = file(); val source = File(file.parentFile, "source.ogg")
        fun run(vararg args: String): String {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor()); return output
        }
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-ar", "48000", "-ac", "2", "-c:a", "libopus", source.absolutePath)
        // Read independent encoder's potentially multi-packet pages using lacing.
        val packets = mutableListOf<ByteArray>(); var pending = byteArrayOf()
        RandomAccessFile(source, "r").use { input ->
            while (true) {
                val page = OggReader.page(input) ?: break; var offset = 0
                for (lace in page.laces) {
                    val n = lace.toInt() and 255; pending += page.body.copyOfRange(offset, offset + n); offset += n
                    if (n < 255) {
                        if (!(pending.size >= 8 && String(pending, 0, 8) in listOf("OpusHead", "OpusTags"))) packets += pending
                        pending = byteArrayOf()
                    }
                }
            }
        }
        // Simulate process death: the first stream has no EOS until the resume path seals it.
        OggOpusWriter(file).let { writer -> packets.forEach { writer.append(it) }; writer.sync(); writer.abort() }
        OggOpusWriter(file).use { writer -> packets.forEach { writer.append(it) } }
        assertEquals(packets.size * 2, strict(file).size)
        val decoded = File(file.parentFile, "decoded.pcm")
        run("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "s16le", "-acodec", "pcm_s16le", decoded.absolutePath)
        val expected = packets.sumOf { OpusPacket.samples(it) } * 2 * 2 * 2
        assertEquals(expected, decoded.length())
        val probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,channels,sample_rate", "-of", "default=noprint_wrappers=1", file.absolutePath)
        assertTrue(probe.contains("codec_name=opus")); assertTrue(probe.contains("sample_rate=48000")); assertTrue(probe.contains("channels=2"))
        println("Independent FFmpeg validation: chained Ogg, ${packets.size * 2} packets, ${decoded.length()} PCM bytes, stereo 48kHz")
        file.parentFile!!.deleteRecursively()
    }
}
