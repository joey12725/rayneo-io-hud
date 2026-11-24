package io.lampyr.glasses.render

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ToneTest {
    @Test fun packedFallbackTreatsEveryTierAsOn() {
        assertArrayEquals(byteArrayOf(0x70), OneBit.pack(byteArrayOf(0, TERTIARY.toByte(), SECONDARY.toByte(), PRIMARY.toByte())))
        val levels = ByteArray(DisplaySize.PIXELS)
        levels[W - 1] = TERTIARY.toByte()
        levels[W] = SECONDARY.toByte()
        val packed = OneBit.pack(levels)
        assertEquals(DisplaySize.PACKED_BYTES, packed.size)
        assertEquals(0x18, packed[67].toInt() and 255)
    }

    @Test fun nativeMasterQuantizesWithoutLosingAnyTier() {
        val master = byteArrayOf(0, TERTIARY.toByte(), SECONDARY.toByte(), PRIMARY.toByte())
        val expected = mapOf(
            2 to listOf(0, 255, 255, 255),
            4 to listOf(0, 85, 170, 255),
            8 to listOf(0, 109, 182, 255),
            16 to listOf(0, 102, 170, 255),
        )
        for ((count, values) in expected) {
            val image = RasterFit.fromMaster(master, 4, 1, count)
            assertEquals(values, image.pixels.map { it.toInt() and 255 })
            assertEquals(count, image.levels)
            assertEquals(if (count == 2) 2 else 4, image.pixels.toSet().size)
        }
        assertTrue(TERTIARY >= 5 && SECONDARY - TERTIARY >= 4 && PRIMARY - SECONDARY >= 4)
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fromMaster(byteArrayOf(16), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fromMaster(master, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fromMaster(master, 4, 1, 3) }
    }

    @Test fun allMasterCodesRemainOnInTwoLevelOutput() {
        val master = ByteArray(16) { it.toByte() }
        assertEquals(listOf(0) + List(15) { 255 }, RasterFit.fromMaster(master, 16, 1, 2).pixels.map { it.toInt() and 255 })
        for (count in listOf(4, 8, 16)) {
            val output = RasterFit.fromMaster(master, 16, 1, count).pixels.map { it.toInt() and 255 }
            assertEquals(0, output.first()); assertEquals(255, output.last())
            assertTrue(output.zipWithNext().all { (a, b) -> a <= b })
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToneDrawingTest {
    @Test fun explicitToneOverridesTheScopedToneAndThenRestoresIt() {
        val source = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val g = Gfx(Canvas(source))
        g.clear()
        g.withTone(TERTIARY) {
            g.p.fr(12, 12, 10, 10, tone = PRIMARY)
            g.tx("A", 12, 40, tone = PRIMARY)
            g.p.rect(40, 12, 10, 10, tone = PRIMARY)
            g.p.fr(60, 12, 10, 10)
        }
        val grey = GreyFrame.convert(source)
        assertEquals(PRIMARY * 17, grey.bitmap.getPixel(12, 12) ushr 8 and 255)
        assertEquals(PRIMARY * 17, grey.bitmap.getPixel(14, 41) ushr 8 and 255)
        assertEquals(PRIMARY * 17, grey.bitmap.getPixel(40, 16) ushr 8 and 255)
        assertEquals(TERTIARY * 17, grey.bitmap.getPixel(60, 12) ushr 8 and 255)
        assertEquals(PRIMARY, g.tone)
        grey.bitmap.recycle(); source.recycle()
    }

    @Test fun focusedListSubtitlesStayBlackOnFullBrightness() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val ui = io.lampyr.glasses.ui.Ui(io.lampyr.glasses.data.FakeDataStore(context), scope = scope)
        ui.replaceTop(io.lampyr.glasses.ui.ListView(ui, "tones", {
            listOf(io.lampyr.glasses.ui.Row("Focus", sub = "Selected subtitle"),
                io.lampyr.glasses.ui.Row("Next", sub = "Supporting subtitle"))
        }))
        val frame = ui.render()
        val master = GreyFrame.levels(frame.bitmap)
        assertTrue(master.slice(42 * W until 88 * W).all { it.toInt() in listOf(0, PRIMARY) })
        val subtitle = master.slice(116 * W until 134 * W)
        assertTrue(subtitle.any { it.toInt() == SECONDARY })
        assertTrue(subtitle.all { it.toInt() in listOf(0, SECONDARY) })
        ui.close()
    }

    @Test fun toneChangesBrightnessWithoutChangingCoverageOrInvertedText() {
        fun draw(tone: Int): GreyFrame.Result {
            val source = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val g = Gfx(Canvas(source))
            g.clear()
            g.p.rr(12, 8, 90, 24, 5, 2, true, tone)
            g.tx("Focus", 16, 10, col = BLACK)
            g.tx("Support", 12, 44, tone = tone)
            g.icon("health", 12, 80, 48, tone = tone)
            val result = GreyFrame.convert(source)
            source.recycle()
            return result
        }
        val primary = draw(PRIMARY)
        for (tone in listOf(SECONDARY, TERTIARY)) {
            val grey = draw(tone)
            assertArrayEquals(OneBit.convert(primary.bitmap, 1).bits, OneBit.convert(grey.bitmap, 1).bits)
            assertTrue(grey.levels.all { it.toInt() in listOf(0, tone) })
            assertTrue(grey.levels.any { it.toInt() == tone })
            assertArrayEquals(grey.levels, GreyFrame.levels(grey.bitmap))
            grey.bitmap.recycle()
        }
        primary.bitmap.recycle()
    }
}
