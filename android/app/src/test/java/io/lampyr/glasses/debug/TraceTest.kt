package io.lampyr.glasses.debug

import io.lampyr.glasses.glasses.protocol.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import kotlin.concurrent.thread

class TraceTest {
    @After fun cleanup() { Trace.verbose = false; Trace.sink = null }
    @Test fun ringIsBoundedAndSinceIsExclusiveAndLimited() {
        val start = Trace.latest
        repeat(5100) { Trace.log("Test", "$it") }
        val all = Trace.since(start, 99999)
        assertEquals(5000, all.size)
        assertEquals("100", all.first().msg)
        assertEquals("5099", all.last().msg)
        assertEquals(all.drop(1).take(3), Trace.since(all.first().seq, 3))
        assertTrue(Trace.since(start, -1).isEmpty())
    }
    @Test fun sequencesAndSinkDeliveryAreOrderedAcrossThreads() {
        val entries = mutableListOf<Trace.Entry>()
        Trace.sink = { entries += it }
        val jobs = (0..3).map { n -> thread { repeat(100) { Trace.log("Concurrent", "$n/$it") } } }
        jobs.forEach { it.join() }
        assertEquals(400, entries.size)
        assertTrue(entries.zipWithNext().all { (a, b) -> b.seq == a.seq + 1 })
        assertTrue(entries.all { it.t > 0 })
    }
    @Test fun lazyLogsAndSinkFailureWorkWithoutAnAndroidRuntime() {
        Trace.sink = { error("sink unavailable") }
        val seq = Trace.latest
        Trace.log("JVM") { "lazy message" }
        Trace.error("JVM", "failed", IllegalStateException("context"))
        assertEquals("lazy message", Trace.since(seq).first().msg)
        assertTrue(Trace.since(seq).last().msg.contains("IllegalStateException"))
    }
    @Test fun verboseGatesContentAndHex() {
        Trace.verbose = false
        assertFalse(Trace.content("private text").contains("private text"))
        assertEquals("", Trace.hex(byteArrayOf(0x12)))
        Trace.verbose = true
        assertEquals("private text", Trace.content("private text"))
        assertEquals("12", Trace.hex(byteArrayOf(0x12)))
        assertEquals(192, Trace.hex(ByteArray(200)).length)
    }
    @Test fun audioAndPairingNeverDumpPayloadEvenWhenVerbose() {
        Trace.verbose = true
        val seq = Trace.latest
        Trace.frame("RX", "BLE", RayneoFrame(0x13, 1, Envelope(4, "{\"sid\":\"test\"}", ByteArray(240) { 0x67 }).encode()))
        Trace.frame("TX", "BLE", RayneoFrame(0x10, 1, "SECRET_PROOF".toByteArray()))
        assertTrue(Trace.since(seq).none { it.msg.contains("676767") || it.msg.contains("SECRET") })
    }
    @Test fun frameJsonCredentialsAreRedactedBeforeHex() {
        Trace.verbose = true
        val start = Trace.latest
        Trace.frame("TX", "RFCOMM", RayneoFrame(15, 1, Envelope(20, "{\"token\":\"never-reveal-me\",\"message\":\"hello\"}").encode()))
        val line = Trace.since(start).single().msg
        assertFalse(line.contains(Trace.hex("never-reveal-me".toByteArray())))
        assertTrue(line.contains("type=20"))
    }
    @Test fun registeredGatewayCredentialCannotLeakThroughTextHexOrErrors() {
        val secret = "trace-test-gateway-secret-abcdefghijk"
        Trace.protect(secret)
        Trace.verbose = true
        val seq = Trace.latest
        Trace.error("Test", "failed $secret", IllegalStateException(secret))
        assertFalse(Trace.since(seq).single().msg.contains(secret))
        assertEquals("[redacted]", Trace.content(secret))
        assertEquals("5b72656461637465645d", Trace.hex(secret.toByteArray()))
    }
    @Test fun perStreamRateLimiterCountsPacketsBytesAndDrops() {
        var now = 0L
        val counter = StreamCounter { now }
        assertNull(counter.add("a", 10))
        now = 999; assertNull(counter.add("a", 20, true))
        now = 1000
        assertEquals("stream=a packets=3 bytes=60 drops=1", counter.add("a", 30))
        assertNull(counter.add("a", 50))
        assertNull(counter.add("b", 5))
        now = 2000
        assertEquals("stream=a packets=2 bytes=60 drops=0", counter.add("a", 10))
        assertEquals("stream=b packets=2 bytes=10 drops=0", counter.add("b", 5))
    }
}
