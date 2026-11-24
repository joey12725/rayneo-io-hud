package io.lampyr.glasses.glasses

import io.lampyr.glasses.glasses.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class RayNeoLinkTest {
    private class FakeSocket : RayNeoSocket {
        val received = LinkedBlockingQueue<Int>()
        val wire = ByteArrayOutputStream()
        val chunks = mutableListOf<Int>()
        val closed = AtomicBoolean()
        override val mtu = 19
        override val input = object : InputStream() {
            override fun read() = received.take()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val first = read()
                if (first < 0) return -1
                bytes[offset] = first.toByte()
                var count = 1
                while (count < length) {
                    val value = received.poll() ?: break
                    if (value < 0) { received.offer(-1); break }
                    bytes[offset + count++] = value.toByte()
                }
                return count
            }
        }
        override val output = object : OutputStream() {
            override fun write(value: Int) { synchronized(wire) { wire.write(value) } }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                synchronized(wire) { chunks += length; wire.write(bytes, offset, length) }
            }
        }
        override fun close() { closed.set(true); received.offer(-1) }
        fun bytes() = synchronized(wire) { wire.toByteArray() }
    }
    @Test(timeout = 10_000) fun fakeSocketSerializesChunksReceivesAckAndCloses() = runBlocking {
        val socket = FakeSocket()
        val link = RayNeoLink(RayNeoConnector { _, _ -> object : RayNeoSession {
            override val socket = socket
            override val bleLost: Deferred<Unit>? = null
            override fun close() { socket.close() }
        } })
        try {
            link.connect("address")
            withTimeout(3_000) { link.connectionState.first { it == ConnectionState.CONNECTED } }
            val first = RayneoFrame(0x13, 1, ByteArray(100) { it.toByte() })
            val second = RayneoFrame(0x15, 2, byteArrayOf(4, 5))
            link.send(first); link.send(second)
            val decoded = FrameCodec.StreamDecoder().append(socket.bytes())
            assertEquals(listOf(1, 2), decoded.map { it.sequence })
            assertArrayEquals(first.payload, decoded[0].payload)
            assertTrue(socket.chunks.all { it <= socket.mtu })
            val incoming = async(start = CoroutineStart.UNDISPATCHED) { link.frames.first() }
            FrameCodec.encode(RayneoFrame(0x13, 3, byteArrayOf(9), flags = 1)).forEach { socket.received.put(it.toInt() and 255) }
            assertEquals(3, withTimeout(3_000) { incoming.await() }.sequence)
            withTimeout(3_000) {
                while (FrameCodec.StreamDecoder().append(socket.bytes()).size < 3) yield()
            }
            val ack = FrameCodec.StreamDecoder().append(socket.bytes()).last()
            assertTrue(ack.acknowledgement); assertEquals(3, ack.sequence)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { link.send(RayneoFrame(9, 5, Envelope(3).encode())) }
            }
        } finally { link.close() }
        assertTrue(socket.closed.get())
        assertEquals(ConnectionState.DISCONNECTED, link.connectionState.value)
    }
    @Test fun reconnectBackoffMatchesSpec() {
        // The protocol's fast 5/10/15 s budget, then background retries that never stop, capped at 60 s.
        assertEquals(listOf(5000L, 10000L, 15000L, 20000L, 30000L, 45000L, 60000L, 60000L), (0..7).map(RayNeoLink::reconnectDelay))
        assertEquals(60_000L, RayNeoLink.reconnectDelay(10_000))
        assertEquals(3, RayNeoLink.MAX_ATTEMPTS)
        assertTrue(RayNeoLink.isTerminal("Authentication rejected by glasses"))
        assertFalse(RayNeoLink.isTerminal("RFCOMM stream closed"))
    }
    @Test fun busyWriteIsTransientAndSubmissionRetriesAreBounded() = runTest {
        assertFalse(RayNeoLink.isTerminal("BLE write rejected"))
        assertFalse(RayNeoLink.isTerminal("BLE authentication link closed"))
        assertFalse(RayNeoLink.isTerminal("System rejected bond request"))
        assertTrue(RayNeoLink.isTerminal("Glasses authentication proof rejected"))
        assertTrue(RayNeoLink.isTerminal("Account-bound auth unsupported: unbind in the official RayNeo app first"))
        var submissions = 0
        retryBusyBleWrite { ++submissions == 3 }
        assertEquals(3, submissions)
        submissions = 0
        try { retryBusyBleWrite { submissions++; false }; fail("Expected bounded busy failure") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("busy")) }
        assertEquals(4, submissions)
    }
    @Test fun connectionTimeoutNeverKillsSupervisorAndRetriesForever() = runTest {
        var attempts = 0
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val link = RayNeoLink(RayNeoConnector { _, _ ->
            attempts++; withTimeout(1) { delay(2) }; error("unreachable")
        }, owner) { testScheduler.currentTime }
        try {
            // The phone trace: three 13 s scan timeouts used to end in "Terminal error". Now it keeps going.
            link.connect("test"); testScheduler.advanceTimeBy(60 * 60_000L); testScheduler.runCurrent()
            assertTrue("attempts=$attempts", attempts > 50)
            assertEquals(ConnectionState.ERROR, link.connectionState.value)
            assertEquals(RetryPhase.WAITING, link.retry.value.phase)
            assertFalse(link.lastError.value!!.contains("Press"))
        } finally { link.close() }
    }
    @Test fun shortLivedConnectionsSpendFastBudgetButThirtySecondsRestoresIt() = runTest {
        for (stableAttempt in listOf(-1, 3)) {
            var attempts = 0
            var clock = 0L
            val modes = mutableListOf<Boolean>()
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val link = RayNeoLink(object : RayNeoConnector {
                override suspend fun open(address: String, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession = error("unused")
                override suspend fun open(address: String, attempt: LinkAttempt, onBleFrame: (RayneoFrame) -> Unit): RayNeoSession {
                modes += attempt.background
                attempts++
                val uptime = if (attempts == stableAttempt) 31_000 else 1_000
                return object : RayNeoSession {
                    override val bleLost: Deferred<Unit>? = null
                    override val socket = object : RayNeoSocket {
                        override val mtu = 100
                        override val output = ByteArrayOutputStream()
                        override val input = object : InputStream() {
                            override fun read(): Int { clock += uptime; return -1 }
                        }
                        override fun close() {}
                    }
                    override fun close() {}
                }
            } }, owner) { clock }
            try {
                link.connect("test")
                var guard = 0
                while (attempts < 5 && guard++ < 200) { testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent() }
                // Short-lived links spend the fast budget (attempt 4 is background); a 30 s link earns it back.
                assertEquals(if (stableAttempt < 0) listOf(false, false, false, true, true) else List(5) { false }, modes.take(5))
                assertEquals(ConnectionState.ERROR, link.connectionState.value)
                assertTrue(link.retry.value.searching)
            } finally { link.close() }
        }
    }

    @Test fun bleCallbackTimeoutFailsPendingWriteAndNotifiesConnectionSupervisor() = runTest {
        val writes = kotlinx.coroutines.channels.Channel<BleWrite>(1)
        val done = CompletableDeferred<Unit>()
        writes.send(BleWrite(byteArrayOf(1), done))
        var failure: Throwable? = null
        runBleWriteQueue(writes, { withTimeout(5) { delay(10) } }, { failure = it })
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(done.isCompleted)
        try { done.await(); fail("Expected timeout") } catch (_: TimeoutCancellationException) {}
        writes.close()
        var reportedCancellation = false
        val cancelled = launch {
            runBleWriteQueue(kotlinx.coroutines.channels.Channel<BleWrite>(), {}, { reportedCancellation = true })
        }
        runCurrent(); cancelled.cancelAndJoin()
        assertFalse(reportedCancellation)
    }

    @Test(timeout = 10_000) fun bleLossTimeoutPropagatesOutOfChildAndEndsConnection() = runBlocking {
        val socket = FakeSocket()
        val lost = CompletableDeferred<Unit>()
        val link = RayNeoLink(RayNeoConnector { _, _ -> object : RayNeoSession {
            override val socket = socket
            override val bleLost = lost
            override fun close() { socket.close() }
        } })
        try {
            link.connect("test")
            withTimeout(1000) { link.connectionState.first { it == ConnectionState.CONNECTED } }
            val timeout = try { withTimeout(1) { delay(100) }; error("unreachable") }
                catch (e: TimeoutCancellationException) { e }
            lost.completeExceptionally(timeout)
            withTimeout(1000) { link.connectionState.first { it == ConnectionState.ERROR } }
            assertTrue(socket.closed.get())
            assertTrue(link.lastError.first { it != null }!!.contains("timed out"))
        } finally { link.close() }
    }

}
