package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.render.RasterFit
import java.util.UUID
import java.util.zip.CRC32

/** Original implementation of the wire facts recorded in docs/raster-wire.md, not a source port. */
object Tvp2Codec {
    const val CAPABILITY = 0x32445056L
    data class Packet(val op: Int, val session: Long, val sequence: Long, val a: Long = 0,
                      val b: Long = 0, val c: Long = 0, val result: Int = 0, val data: ByteArray = byteArrayOf())
    fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    fun encode(p: Packet, reply: Boolean = false): ByteArray {
        require(p.session in 1..0xffffffffL && p.sequence in 1..0xffffffffL && p.data.size <= 14967)
        require(p.op in 1..8 || (reply && p.op in 32..33))
        require(p.result in 0..6 && (reply || p.result == 0) && (!reply || p.data.isEmpty()))
        val bytes = ByteArray(32 + p.data.size)
        (if (reply) "VPA1" else "TVP1").toByteArray().copyInto(bytes)
        bytes[4] = 1; bytes[5] = p.op.toByte(); bytes[6] = p.result.toByte()
        bytes.put32(8, p.session); bytes.put32(12, p.sequence)
        bytes.put32(16, p.a); bytes.put32(20, p.b); bytes.put32(24, p.c)
        p.data.copyInto(bytes, 32)
        bytes.put32(28, crc(bytes.copyOfRange(0, 28) + p.data))
        return bytes
    }
    fun decode(bytes: ByteArray, reply: Boolean = true): Packet? {
        if (bytes.size !in 32..14999 || (reply && bytes.size != 32) ||
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != if (reply) "VPA1" else "TVP1") return null
        if (bytes.u8(4) != 1 || bytes.u8(7) != 0 || bytes.u8(6) > 6 || bytes.le32(8) == 0L || bytes.le32(12) == 0L) return null
        val op = bytes.u8(5)
        if (op !in 1..8 && !(reply && op in 32..33)) return null
        if (crc(bytes.copyOfRange(0, 28) + bytes.copyOfRange(32, bytes.size)) != bytes.le32(28)) return null
        return Packet(op, bytes.le32(8), bytes.le32(12), bytes.le32(16), bytes.le32(20), bytes.le32(24), bytes.u8(6), bytes.copyOfRange(32, bytes.size))
    }
    /** PackBits: 0..127 literal count-1; 129..255 repeat 257-tag; 128 forbidden. */
    fun compress(input: ByteArray): ByteArray {
        // Worst case is one tag byte per 128 literals. A plain array: this runs for every frame on the press path.
        val out = ByteArray(input.size + input.size / 128 + 1)
        var size = 0
        var cursor = 0
        while (cursor < input.size) {
            var run = 1
            while (run < 128 && cursor + run < input.size && input[cursor + run] == input[cursor]) run++
            if (run >= 3) { out[size++] = (257 - run).toByte(); out[size++] = input[cursor]; cursor += run }
            else {
                val start = cursor++
                while (cursor - start < 128 && cursor < input.size) {
                    if (cursor + 2 < input.size && input[cursor] == input[cursor + 1] && input[cursor] == input[cursor + 2]) break
                    cursor++
                }
                out[size++] = (cursor - start - 1).toByte()
                input.copyInto(out, size, start, cursor); size += cursor - start
            }
        }
        return out.copyOf(size)
    }
    data class Encoded(val image: RasterFit.Image, val bits: Int, val strips: List<ByteArray>)
    fun frame(image: RasterFit.Image): Encoded {
        require(image.width == 540 && image.height == 220)
        val bits = if (image.levels <= 4) 2 else 4
        val max = (1 shl bits) - 1
        // Per-intensity tables: wire sample and the intensity the firmware expands it back to.
        val sampleOf = IntArray(256) { (it * max + 127) / 255 }
        val expandedOf = ByteArray(256) { (sampleOf[it] * 255 / max).toByte() }
        val pixels = image.pixels
        val actual = image.copy(pixels = ByteArray(pixels.size) { expandedOf[pixels[it].toInt() and 255] })
        val perByte = 8 / bits
        val stripPixels = 540 * 55
        val strips = (0..3).map { strip ->
            val packed = ByteArray(stripPixels * bits / 8)
            val base = strip * stripPixels
            for (b in packed.indices) {
                var value = 0
                for (k in 0 until perByte) value = (value shl bits) or sampleOf[pixels[base + b * perByte + k].toInt() and 255]
                packed[b] = value.toByte()
            }
            compress(packed).also { require(it.size <= 14967) }
        }
        return Encoded(actual, bits, strips)
    }
}

class Tvp2Transport(private val wire: RasterWire, private val session: Long = (UUID.randomUUID().leastSignificantBits and 0xffffffffL).coerceAtLeast(1),
                    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
                    /** Where packing/PackBits runs; never the caller's (possibly main) thread in production. */
                    private val compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default) : RasterTransport {
    override val kind = RasterKind.TVP2
    private var sequence = 0L
    private var frame = 0L
    private var displayed = 0L
    private var verifiedBytes = 0L
    private var previous: ByteArray? = null
    @Volatile private var eventSequence = 0L
    @Volatile private var paused = false
    @Volatile private var exited = false
    val wearerExited get() = exited
    val isPaused get() = paused
    val acceptingFrames get() = !paused && !exited
    /**
     * Write BEGIN, the four STRIPs and COMMIT back to back and then collect the six replies, instead of six
     * stop-and-wait round trips. The pinned receiver handles each packet synchronously and only requires an
     * increasing sequence, but this is unverified on TMIG2-VIDEO-R1: any refusal or timeout throws
     * [RasterPipelineRefused] so the caller can reopen and fall back to stop-and-wait.
     */
    @Volatile var pipeline = false
    /** BUSY on HELLO: the old page is still being disposed. Fast renewal asks again after 40 ms (firmware ticks every 33 ms). */
    var busyRetryMs = 300L
    var busyRetries = 10
    var onFrameTiming: ((RasterFrameTiming) -> Unit)? = null
    private companion object { const val BUSY = 2; const val SESSION = 5 }

    /** PAUSE/EXIT are asynchronous and have a separate monotonic sequence. */
    fun event(payload: ByteArray) {
        val p = Tvp2Codec.decode(payload) ?: return
        if (p.session != session || p.op !in 32..33 || p.result != 0 || p.sequence <= eventSequence) return
        eventSequence = p.sequence
        if (p.op == 33) exited = true else if (p.a in 0..1) paused = p.a == 1L
    }
    /** One command and its matching reply, whatever the result code. */
    private suspend fun exchange(op: Int, a: Long = 0, b: Long = 0, c: Long = 0, data: ByteArray = byteArrayOf(), timeout: Long = 3_000): Tvp2Codec.Packet {
        check(!exited) { "TVP2 wearer exited; reconnect or change mode to reopen" }
        check(sequence < 0xffffffffL) { "TVP2 sequence exhausted" }
        val q = Tvp2Codec.Packet(op, session, ++sequence, a, b, c, data = data)
        wire.send(15, Tvp2Codec.encode(q)) // Raw launcher business 0x0F over authenticated RFCOMM.
        val bytes = wire.awaitReply(15, timeout) { raw ->
            val p = Tvp2Codec.decode(raw)
            p != null && p.session == session && p.op == op && p.sequence == q.sequence
        }
        return checkNotNull(Tvp2Codec.decode(bytes))
    }
    private fun verify(op: Int, result: Int) {
        if (result == BUSY && op in 6..8) throw RasterFrameBusy()
        if (result == SESSION) throw RasterSessionLost("TVP2 session lost on op $op (5)")
        check(result == 0) { "TVP2 rejected op $op ($result); return glasses home and check LifeLog/guest link" }
    }
    private suspend fun request(op: Int, a: Long = 0, b: Long = 0, c: Long = 0, data: ByteArray = byteArrayOf(), timeout: Long = 3_000): Tvp2Codec.Packet =
        exchange(op, a, b, c, data, timeout).also { verify(op, it.result) }
    /**
     * BUSY proves the receiver exists: another page (usually our own text page, opened first on
     * connect) owns the display. open() runs after that page is released and asks again.
     */
    suspend fun probe(): Boolean = try {
        val p = exchange(1, Tvp2Codec.CAPABILITY, timeout = 1_000)
        p.result == BUSY || (p.result == 0 && p.a == 540L && p.b == (220L or (1L shl 16)))
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { false }
      catch (_: IllegalStateException) { false }

    override suspend fun open() {
        var hello = exchange(1, Tvp2Codec.CAPABILITY, timeout = 1_000)
        // The previous page can take a moment to let go; BUSY here is not a refusal yet.
        var waits = 0
        while (hello.result == BUSY && waits++ < busyRetries) {
            kotlinx.coroutines.delay(busyRetryMs)
            hello = exchange(1, Tvp2Codec.CAPABILITY, timeout = 1_000)
        }
        check(hello.result == 0) { "TVP2 rejected op 1 (${hello.result}); return glasses home and check LifeLog/guest link" }
        check(hello.a == 540L && hello.b == (220L or (1L shl 16))) { "TVP2 native raster capability missing" }
        openWithUnverifiedGuestGate(hello.c)
        previous = null; displayed = 0; verifiedBytes = 0
    }
    /** Firmware also requires nav_bonded + connection(0x80). Our guest proof satisfying it needs hardware. */
    private suspend fun openWithUnverifiedGuestGate(token: Long) {
        request(2, token) // Echo within 1500 ms; no undocumented account handshake is fabricated.
    }
    override suspend fun show(image: RasterFit.Image): RasterFit.Image = show(image, null)
    /** [prepared] is [Tvp2Codec.frame] of [image], already encoded while the frame waited (RasterOutput.offer). */
    suspend fun show(image: RasterFit.Image, prepared: Tvp2Codec.Encoded?): RasterFit.Image {
        check(!paused && !exited) { "TVP2 paused or exited on glasses" }
        // A burst written ahead (look-ahead) for exactly this frame: only its replies are left to collect.
        queued?.let { ahead ->
            queued = null
            if (ahead.source === image) return finish(ahead)
            settle(ahead)
        }
        // Packing and PackBits are CPU work; never run them on the caller's (possibly main) thread.
        val encodeStart = clock()
        val encoded = prepared ?: kotlinx.coroutines.withContext(compute) { Tvp2Codec.frame(image) }
        lastEncodeMs = clock() - encodeStart
        if (previous?.contentEquals(encoded.image.pixels) == true) {
            val at = clock()
            onFrameTiming?.invoke(RasterFrameTiming(at, at, 0, false, sent = false))
            return encoded.image
        }
        val crc = Tvp2Codec.crc(encoded.image.pixels)
        check(frame < 0xffffffffL)
        val beginTx = clock()
        if (pipeline) return finish(write(image, encoded, crc))
        val bytes = stopAndWait(encoded, crc)
        onFrameTiming?.invoke(RasterFrameTiming(beginTx, clock(), bytes.toInt(), false, sent = true))
        previous = encoded.image.pixels.copyOf()
        return encoded.image
    }
    /** Collects [burst], checks it in order, and reports its timing. */
    private suspend fun finish(burst: Burst): RasterFit.Image {
        try { collect(burst); verify(burst) }
        catch (e: Throwable) {
            // A burst written behind this one is already in the firmware: account for it before giving up.
            queued?.let { ahead -> queued = null; settle(ahead) }
            throw e
        }
        onFrameTiming?.invoke(RasterFrameTiming(burst.beginTx, burst.commitAt, burst.bytes.toInt(), true, sent = true))
        previous = burst.encoded.image.pixels.copyOf()
        return burst.encoded.image
    }
    @Volatile var lastEncodeMs = 0L
    private suspend fun stopAndWait(encoded: Tvp2Codec.Encoded, crc: Long): Long {
        val ops = StringBuilder()
        var t0 = clock()
        fun lap(name: String, fw: Long) { val t = clock(); ops.append(" $name=${t - t0}/$fw"); t0 = t }
        val begin = request(6, ++frame, encoded.bits.toLong(), crc)
        lap("B", 0)
        check(begin.a == frame && begin.b == 0L && begin.c == 0L) { "TVP2 BEGIN counters disagree" }
        var bytes = 0L
        encoded.strips.forEachIndexed { i, strip ->
            // TVP2 requires all four sequential strips even when only one changes. No tile delta command.
            val r = request(7, (i * 55L) or (55L shl 16), encoded.bits.toLong(), Tvp2Codec.crc(strip), strip)
            bytes += strip.size
            lap("S$i:${strip.size}", r.b)
            check(r.a == (i + 1) * 55L && r.c == bytes) { "TVP2 strip counters disagree" }
        }
        val commit = request(8, frame, c = crc)
        lap("C", commit.b)
        io.lampyr.glasses.debug.Trace.log("Tvp2", "ops encode=$lastEncodeMs$ops (rtt ms/firmware ms)")
        check(commit.a == displayed + 1 && commit.c == verifiedBytes + bytes) { "TVP2 COMMIT counters disagree" }
        displayed = commit.a; verifiedBytes = commit.c
        return bytes
    }
    /** One pipelined frame on the wire: BEGIN, four STRIPs and COMMIT, and the replies collected so far. */
    private inner class Burst(val source: RasterFit.Image, val encoded: Tvp2Codec.Encoded, val number: Long,
                              val packets: List<Tvp2Codec.Packet>, val beginTx: Long) {
        val expected = packets.associate { it.sequence to it.op }
        val replies = HashMap<Long, Tvp2Codec.Packet>()
        var firstFailure: Tvp2Codec.Packet? = null
        var commitAt = 0L
        val marks = StringBuilder()
        val bytes get() = encoded.strips.sumOf { it.size }.toLong()
        val complete get() = replies.size == packets.size
        fun expects(p: Tvp2Codec.Packet) = p.session == session && expected[p.sequence] == p.op && p.sequence !in replies
        fun accept(p: Tvp2Codec.Packet) {
            replies[p.sequence] = p
            if (p.op == 8) commitAt = clock()
            marks.append(" r${p.op}=${clock() - beginTx}/${p.b}")
            if (p.result != 0 && firstFailure == null) firstFailure = p
        }
    }
    /** A ready press frame to write right behind the frame in flight (offered by RasterOutput), or null. */
    class Ahead(val image: RasterFit.Image, val encoded: Tvp2Codec.Encoded)
    var nextUrgent: (() -> Ahead?)? = null
    /** Written behind the frame being collected; its show() only collects. At most one. */
    private var queued: Burst? = null
    /** Look-ahead poll interval while collecting replies: a ready press frame waits at most this long to be written. */
    private val lookaheadSliceMs = 20L

    private suspend fun write(source: RasterFit.Image, encoded: Tvp2Codec.Encoded, crc: Long): Burst {
        check(!exited) { "TVP2 wearer exited; reconnect or change mode to reopen" }
        check(sequence + 6 < 0xffffffffL) { "TVP2 sequence exhausted" }
        val number = ++frame
        val bits = encoded.bits.toLong()
        val packets = buildList {
            add(Tvp2Codec.Packet(6, session, ++sequence, number, bits, crc))
            encoded.strips.forEachIndexed { i, strip ->
                add(Tvp2Codec.Packet(7, session, ++sequence, (i * 55L) or (55L shl 16), bits, Tvp2Codec.crc(strip), data = strip))
            }
            add(Tvp2Codec.Packet(8, session, ++sequence, number, c = crc))
        }
        val burst = Burst(source, encoded, number, packets, clock())
        for (p in packets) { wire.send(15, Tvp2Codec.encode(p)); burst.marks.append(" w${p.op}=${clock() - burst.beginTx}") }
        return burst
    }
    /**
     * Collects [burst]'s six replies (each with a 3 s budget, 300 ms after a refusal). Meanwhile, if a press frame is
     * ready, it is written straight behind (the firmware handles packets in order), so it does not wait a round trip
     * for this frame's COMMIT reply. Replies are routed to their burst by sequence; none are dropped.
     */
    private suspend fun collect(burst: Burst, lookahead: Boolean = true) {
        while (!burst.complete) {
            val budget = if (burst.firstFailure == null) 3_000L else 300L
            try {
                kotlinx.coroutines.withTimeout(budget) {
                    while (true) {
                        val look = lookahead && pipeline && queued == null && nextUrgent != null && burst.firstFailure == null
                        if (look) nextUrgent?.invoke()?.let { ahead ->
                            queued = write(ahead.image, ahead.encoded, Tvp2Codec.crc(ahead.encoded.image.pixels))
                        }
                        val stillLooking = look && queued == null
                        val matches = { raw: ByteArray ->
                            val p = Tvp2Codec.decode(raw)
                            p != null && (burst.expects(p) || queued?.expects(p) == true)
                        }
                        val raw = (if (stillLooking) wire.awaitReplyOrNull(15, lookaheadSliceMs, matches)
                            else wire.awaitReply(15, budget, matches)) ?: continue
                        val p = checkNotNull(Tvp2Codec.decode(raw))
                        if (burst.expects(p)) { burst.accept(p); break } else queued?.accept(p)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (burst.firstFailure == null) throw RasterPipelineRefused("TVP2 pipelined frame timed out after ${burst.replies.size}/6 replies", e)
                return
            }
        }
    }
    private fun verify(burst: Burst) {
        burst.firstFailure?.let { failure ->
            if (failure.result == BUSY) throw RasterFrameBusy()
            if (failure.result == SESSION) throw RasterSessionLost("TVP2 session lost on op ${failure.op} (5)")
            throw RasterPipelineRefused("TVP2 refused pipelined op ${failure.op} (${failure.result})")
        }
        io.lampyr.glasses.debug.Trace.log("Tvp2", "pipelined encode=$lastEncodeMs bytes=${burst.bytes}${burst.marks}")
        val ordered = burst.packets.map { burst.replies.getValue(it.sequence) }
        val begin = ordered.first()
        if (begin.a != burst.number || begin.b != 0L || begin.c != 0L) throw RasterPipelineRefused("TVP2 BEGIN counters disagree (pipelined)")
        var bytes = 0L
        burst.encoded.strips.forEachIndexed { i, strip ->
            bytes += strip.size
            val r = ordered[i + 1]
            if (r.a != (i + 1) * 55L || r.c != bytes) throw RasterPipelineRefused("TVP2 strip counters disagree (pipelined)")
        }
        val commit = ordered.last()
        if (commit.a != displayed + 1 || commit.c != verifiedBytes + bytes) throw RasterPipelineRefused("TVP2 COMMIT counters disagree (pipelined)")
        displayed = commit.a; verifiedBytes = commit.c
    }
    /**
     * A burst written ahead whose own show() will not come (the frame in front failed, or a different frame is next):
     * collect its replies briefly and keep the counters right. If it was displayed, [previous] says so, so the same
     * pixels are not sent again.
     */
    private suspend fun settle(burst: Burst) {
        try {
            kotlinx.coroutines.withTimeout(1_000) { collect(burst, lookahead = false) }
            verify(burst)
            previous = burst.encoded.image.pixels.copyOf()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        } catch (_: Exception) { }
    }
    override suspend fun keepAlive() {
        queued?.let { queued = null; settle(it) }
        val eventAtSend = eventSequence
        val r = request(4)
        check(r.a in 0..1 && r.b == displayed && r.c == verifiedBytes) { "TVP2 PING counters disagree" }
        if (eventAtSend == eventSequence) paused = r.a == 1L
    }
    /**
     * Fire-and-forget PING: wakes the classic-Bluetooth link out of sniff (and resets the firmware's 15 s
     * idle timer) without waiting for the round trip. The reply is discarded by the next awaitReply.
     */
    suspend fun nudge() {
        if (exited || sequence >= 0xffffffffL - 8) return
        wire.send(15, Tvp2Codec.encode(Tvp2Codec.Packet(4, session, ++sequence)))
    }
    /** CLOSE is answered in ~100-250 ms; a short timeout keeps a renewal on a stuck session from blanking for 3 s. */
    override suspend fun close() { queued = null; if (!exited) request(5, timeout = 1_500); previous = null }
}
