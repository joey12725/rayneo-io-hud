package io.lampyr.glasses.glasses

import org.junit.Assert.*
import org.junit.Test

class RenderingAudioTest {
    @Test fun fullWidthConversionAndEmojiAreSingleCells() {
        assertEquals("Ａｚ０！～　？", CharGridRenderer.fullWidth("Az0!~ é"))
        assertEquals("？Ａ？Ｂ？Ｃ？", CharGridRenderer.fullWidth("👨‍👩‍👧‍👦A👍🏽B🇺🇸C1️⃣"))
        assertEquals("？？", CharGridRenderer.fullWidth("🙂🙂"))
        assertEquals("█▀▄", CharGridRenderer.fullWidth("█▀▄"))
    }
    @Test fun wholeStringPrefixAndGeometryReset() {
        val renderer = CharGridRenderer()
        val text = renderer.content(listOf("A", "B"))
        assertEquals(7, text.split('\n').size)
        assertTrue(text.split('\n').all { it.length == 26 })
        assertEquals('Ａ', renderer.wireText(text).first())
        renderer.written()
        val second = renderer.wireText(text)
        assertEquals("\u3000$text", second)
        assertEquals('Ｂ', second.split('\n')[1].first()) // No prefix on each row.
        renderer.reset(); assertEquals(text, renderer.wireText(text))
        renderer.configure(2, 100, 5)
        assertTrue(renderer.content(listOf("A")).split('\n').all { it.length == 20 })
        assertEquals(5, renderer.content(emptyList()).split('\n').size)
    }
    @Test fun fakeClockAndSinkPacingDropsStaleAndDeduplicates() {
        var now = 0L
        val socket = mutableListOf<String>()
        val pacer = FramePacer()
        fun flush() { pacer.next(now)?.let { socket += it; pacer.written(it, now) } }
        pacer.offer("first"); flush()
        repeat(124) { now++; pacer.offer("stale-$it"); flush() }
        assertEquals(listOf("first"), socket)
        pacer.offer("latest"); now = 125; flush()
        assertEquals(listOf("first", "latest"), socket)
        pacer.offer("latest"); now = 1000; flush(); assertEquals(2, socket.size)
        pacer.offer("changed"); pacer.offer("latest"); flush(); assertEquals(2, socket.size)
        pacer.reset(); pacer.offer("latest"); flush(); assertEquals(3, socket.size)
    }
    @Test fun downsampleSelectsChannelZeroAndCarriesPartialGroups() {
        val downsampler = WearerDownsampler()
        assertArrayEquals(shortArrayOf(), downsampler.process(shortArrayOf(3000, -30000, 6000, -30000), 2))
        assertArrayEquals(shortArrayOf(6000, -6000), downsampler.process(shortArrayOf(9000, -30000, -3000, 30000, -6000, 30000, -9000, 30000), 2))
        downsampler.reset()
        assertArrayEquals(shortArrayOf(32767, -32768), downsampler.process(shortArrayOf(32767, 32767, 32767, -32768, -32768, -32768), 1))
    }
    @Test fun downsamplingConstantAndNyquistSignal() {
        val downsampler = WearerDownsampler()
        assertArrayEquals(ShortArray(160) { 1000 }, downsampler.process(ShortArray(480) { 1000 }, 1))
        downsampler.reset()
        assertArrayEquals(ShortArray(10), downsampler.process(ShortArray(30) { if (it % 3 == 0) 9000 else if (it % 3 == 1) -9000 else 0 }, 1))
    }
    @Test fun opusTocDurationsAndMalformedPackets() {
        assertEquals(20000, OpusTiming.durationUs(byteArrayOf(0x9c.toByte())).toInt())
        assertEquals(60000, OpusTiming.durationUs(byteArrayOf(0x18)).toInt())
        assertEquals(60000, OpusTiming.durationUs(byteArrayOf(0x9f.toByte(), 3)).toInt())
        assertThrows(IllegalArgumentException::class.java) { OpusTiming.durationUs(byteArrayOf(3)) }
        assertThrows(IllegalArgumentException::class.java) { OpusTiming.durationUs(byteArrayOf(0x9f.toByte(), 63)) }
    }
}
