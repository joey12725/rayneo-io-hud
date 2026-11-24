package io.lampyr.glasses.ambient

import io.lampyr.glasses.glasses.OpusTiming
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object OpusPacket {
    fun samples(bytes: ByteArray): Long = OpusTiming.durationUs(bytes) * 48 / 1000
    // RFC 7845 §4.2: pre-skip describes known encoder padding (or intentional cropping),
    // not a universal libopus constant. These packets can start mid-encoder-stream; there
    // is no lookahead/header metadata. Retain every received sample rather than guess 312.
    // Decoder convergence at stream boundaries remains imperfect. Keep old append math.
    fun head(channels: Int = 2): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray()).put(1).put(channels.toByte()).putShort(0)
        .putInt(48000).putShort(0).put(0).array()
}

/** Ogg uses the non-reflected CRC-32 polynomial, NOT java.util.zip.CRC32. */
object OggCrc {
    private val table = IntArray(256) { value ->
        var crc = value shl 24
        repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        crc
    }
    fun calculate(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) crc = (crc shl 8) xor table[((crc ushr 24) xor (byte.toInt() and 255)) and 255]
        return crc
    }
}

data class OggPage(val offset: Long, val end: Long, val flags: Int, val granule: Long,
                   val serial: Int, val sequence: Int, val laces: ByteArray, val body: ByteArray)

object OggReader {
    /** Streaming, CRC-checked page reader. Throws on a torn page; never silently skips corruption. */
    fun page(input: RandomAccessFile): OggPage? {
        if (input.filePointer == input.length()) return null
        val offset = input.filePointer
        val header = ByteArray(27).also { input.readFully(it) }
        require(header.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) && header[4] == 0.toByte())
        val laces = ByteArray(header[26].toInt() and 255).also { input.readFully(it) }
        val body = ByteArray(laces.sumOf { it.toInt() and 255 }).also { input.readFully(it) }
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val crc = h.getInt(22)
        h.putInt(22, 0)
        require(OggCrc.calculate(header + laces + body) == crc) { "Ogg CRC at $offset" }
        return OggPage(offset, input.filePointer, header[5].toInt() and 255, h.getLong(6), h.getInt(14), h.getInt(18), laces, body)
    }

    /** Master files use one complete packet per page; headers/empty EOS pages have no audio. */
    fun audio(file: File, stream: () -> Unit = {}, limit: Long = file.length(),
              warning: (String) -> Unit = { java.util.logging.Logger.getLogger("OggReader").warning(it) },
              consume: (ByteArray, Long) -> Unit) {
        var sample = 0L
        var streamSamples = 0L
        RandomAccessFile(file, "r").use { input ->
            while (true) {
                if (input.filePointer >= limit) break
                val page = page(input) ?: break
                require(page.end <= limit) { "Snapshot ends inside an Ogg page" }
                if (page.body.startsWithAscii("OpusHead")) { streamSamples = 0; stream(); continue }
                if (page.body.isEmpty() || page.body.startsWithAscii("OpusTags")) continue
                require(page.flags and 1 == 0 && page.laces.isNotEmpty() &&
                    (page.laces.last().toInt() and 255) < 255 && page.laces.count { (it.toInt() and 255) < 255 } == 1)
                streamSamples += OpusPacket.samples(page.body)
                if (page.granule >= 0 && page.granule != streamSamples) {
                    warning("Master granule/sample mismatch at ${page.offset}: expected $streamSamples, got ${page.granule}; resynchronizing")
                    streamSamples = page.granule
                }
                consume(page.body, sample)
                sample += OpusPacket.samples(page.body)
            }
        }
    }
}

private fun ByteArray.startsWithAscii(value: String) = size >= value.length && copyOfRange(0, value.length).contentEquals(value.toByteArray())

/** One packet per page, sync at checkpoints (at most one second apart in ConversationRecorder).
 * Resume seals the preceding logical stream then chains a fresh OpusHead/OpusTags stream.
 * Mono Opus packets are legal in a two-channel mapping-family-0 stream (dual mono output).
 */
class OggOpusWriter(file: File, committedBytes: Long? = null,
    openFile: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") }) : Closeable {
    private val out = openFile(file.apply { parentFile?.mkdirs() })
    private var serial = UUID.randomUUID().hashCode()
    private var sequence = 0
    private var granule = 0L
    var samples = 0L; private set
    private var finalLength = 0L
    val length get() = if (closed) finalLength else out.length()
    private var closed = false
    init {
        try {
            committedBytes?.let { require(it in 0..out.length()); out.setLength(it) }
            var last: OggPage? = null
            var valid = 0L
            val serials = mutableSetOf<Int>()
            out.seek(0)
            while (out.filePointer < out.length()) {
                val page = try { OggReader.page(out) } catch (_: Exception) { break } ?: break
                serials += page.serial
                // Timeline offsets count retained packet samples, including legacy streams
                // whose otherwise valid granules differ from the decoded duration.
                if (page.body.isNotEmpty() && !page.body.startsWithAscii("OpusHead") && !page.body.startsWithAscii("OpusTags"))
                    samples += OpusPacket.samples(page.body)
                valid = page.end; last = page
            }
            out.setLength(valid); out.seek(valid)
            while (serial in serials) serial = UUID.randomUUID().hashCode()
            last?.takeIf { it.flags and 4 == 0 }?.let {
                val fresh = serial
                serial = it.serial; sequence = it.sequence + 1; granule = it.granule.coerceAtLeast(0)
                page(byteArrayOf(), 4, granule)
                serial = fresh
            }
            sequence = 0; granule = 0
            page(OpusPacket.head(), 2, 0)
            val vendor = "RayNeo iO HUD ambient".toByteArray()
            val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusTags".toByteArray()).putInt(vendor.size).put(vendor).putInt(0).array()
            page(tags, 0, 0)
        } catch (e: Throwable) { runCatching { out.close() }; throw e }
    }
    fun append(packet: ByteArray): Long {
        check(!closed)
        val count = OpusPacket.samples(packet)
        require(packet.size <= 65024) { "Opus packet exceeds a complete Ogg page" }
        val start = samples
        page(packet, 0, granule + count)
        granule += count; samples += count
        return start
    }
    private fun page(packet: ByteArray, flags: Int, position: Long) {
        val laces = if (packet.isEmpty()) byteArrayOf() else ByteArray(packet.size / 255 + 1) { i ->
            if (i < packet.size / 255) 255.toByte() else (packet.size % 255).toByte()
        }
        val header = ByteBuffer.allocate(27 + laces.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(flags.toByte()).putLong(position).putInt(serial)
            .putInt(sequence++).putInt(0).put(laces.size.toByte()).put(laces).array()
        val bytes = header + packet
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, OggCrc.calculate(bytes))
        out.write(bytes)
    }
    fun sync() { out.fd.sync() }
    fun abort() { if (!closed) { finalLength = out.length(); closed = true; out.close() } }
    override fun close() {
        if (closed) return
        try { page(byteArrayOf(), 4, granule); sync() }
        finally { finalLength = out.length(); closed = true; out.close() }
    }
}
