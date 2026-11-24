package io.lampyr.glasses.render

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class RasterFitTest {
    @Test fun coverageIsAveragedBeforeGammaAndThinStrokesSurvive() {
        val line = intArrayOf(1, 0, 0, 0)
        assertEquals(68, RasterFit.fit(line, 4, 1, 1, 1, 16, RasterFit.Fit.STRETCH, 1.0).pixels[0].toInt() and 255)
        assertEquals(136, RasterFit.fit(line, 4, 1, 1, 1, 16, RasterFit.Fit.STRETCH).pixels[0].toInt() and 255)
        assertEquals(255, RasterFit.fit(line, 4, 1, 1, 1, 2, RasterFit.Fit.STRETCH, mono = RasterFit.Mono.THRESHOLD).pixels[0].toInt() and 255)
    }
    @Test fun fitPreservesAllEdgesAndCentersIntegerContentWithoutCropping() {
        val image = RasterFit.fit(IntArray(600 * 300) { 1 }, 600, 300)
        for (y in 0 until 220) for (x in 0 until 540) {
            assertEquals(if (x in 50..489) 255 else 0, image.pixels[y * 540 + x].toInt() and 255)
        }
        assertTrue(RasterFit.fit(IntArray(180000) { 1 }, 600, 300, mode = RasterFit.Fit.STRETCH).pixels.all { it == (-1).toByte() })
    }
    @Test fun nativeMasterIsIdentityAtEveryGreyDepthAndFitMode() {
        val source = IntArray(DisplaySize.PIXELS) { if (it % 13 == 0 || it % W == 0 || it % W == W - 1) 1 else 0 }
        val bits = ByteArray(DisplaySize.PACKED_BYTES)
        source.indices.filter { source[it] == 1 }.forEach { bits[it / 8] = (bits[it / 8].toInt() or (128 ushr (it % 8))).toByte() }
        val expected = ByteArray(source.size) { if (source[it] == 1) (-1).toByte() else 0 }
        for (levels in listOf(2, 4, 8, 16)) for (mode in RasterFit.Fit.entries) for (mono in RasterFit.Mono.entries) {
            val actual = RasterFit.fit(bits, levels = levels, mode = mode, mono = mono)
            assertEquals(540, actual.width); assertEquals(220, actual.height)
            assertArrayEquals(expected, actual.pixels)
        }
    }
    @Test fun packedInputIsMsbFirstAndInvalidSizesAreRejected() {
        val result = RasterFit.fit(byteArrayOf(0xa1.toByte()), 4, 2, 4, 2, 2, RasterFit.Fit.STRETCH)
        assertArrayEquals(byteArrayOf(-1, 0, -1, 0, 0, 0, 0, -1), result.pixels)
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fit(byteArrayOf(), 4, 2) }
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fit(intArrayOf(2), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RasterFit.fit(intArrayOf(1), 1, 1, gamma = Double.NaN) }
    }
    @Test fun allLevelsAndMonoDitherAreBoundedAndDeterministic() {
        val source = IntArray(64) { if (it % 4 == 0) 1 else 0 }
        for (levels in listOf(2, 4, 8, 16)) {
            val a = RasterFit.fit(source, 8, 8, 4, 4, levels, RasterFit.Fit.STRETCH)
            val palette = (0 until levels).map { it * 255 / (levels - 1) }.toSet()
            assertTrue(a.pixels.all { (it.toInt() and 255) in palette })
            assertArrayEquals(a.pixels, RasterFit.fit(source, 8, 8, 4, 4, levels, RasterFit.Fit.STRETCH).pixels)
        }
        val dither = RasterFit.fit(source, 8, 8, 4, 4, 2, RasterFit.Fit.STRETCH).pixels
        assertTrue(dither.any { it == 0.toByte() } && dither.any { it == (-1).toByte() })
    }
    @Test fun everyQuadrantAndHalfBlockUsesTheCorrectUnicodeCell() {
        val table = "　▘▝▀▖▌▞▛▗▚▐▜▄▙▟█"
        for (mask in 0..15) {
            val bits = byteArrayOf(((if (mask and 1 != 0) 128 else 0) or (if (mask and 2 != 0) 64 else 0) or
                (if (mask and 4 != 0) 32 else 0) or (if (mask and 8 != 0) 16 else 0)).toByte())
            assertEquals(table[mask].toString(), BlockGraphics.convert(bits, 2, 2, 1, 1).single())
        }
        assertEquals("▀", BlockGraphics.convert(byteArrayOf(128.toByte()), 1, 2, 1, 1, BlockGraphics.Mode.HALF).single())
        assertEquals("▄", BlockGraphics.convert(byteArrayOf(64), 1, 2, 1, 1, BlockGraphics.Mode.HALF).single())
        assertEquals(7, BlockGraphics.convert(ByteArray(DisplaySize.PACKED_BYTES), W, H).size)
        assertTrue(BlockGraphics.convert(ByteArray(DisplaySize.PACKED_BYTES), W, H).all { it.length == 26 && it.all { c -> c == '\u3000' } })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterGoldenTest {
    @Test fun existingScreenGoldensHaveStableFittedPixels() {
        for (name in listOf("home", "joy", "t3-approval")) {
            val png = javaClass.getResourceAsStream("/golden/$name.png")!!.use { BitmapFactory.decodeStream(it) }
            val pixels = GreyFrame.levels(png)
            val hashes = RasterFit.Fit.entries.map {
                val image = RasterFit.fromMaster(pixels, png.width, png.height)
                MessageDigest.getInstance("SHA-256").digest(image.pixels).joinToString("") { "%02x".format(it) }
            }
            // UPDATE_RASTER_GOLDENS=1 rewrites the hashes after an intended golden change (e.g. a new avatar pack).
            if (System.getenv("UPDATE_RASTER_GOLDENS") == "1") java.io.File("src/test/resources/rastergolden/$name.sha256").writeText(hashes.joinToString("\n", postfix = "\n"))
            val expected = if (System.getenv("UPDATE_RASTER_GOLDENS") == "1") hashes else javaClass.getResourceAsStream("/rastergolden/$name.sha256")!!.bufferedReader().use { it.readLines() }
            for ((index, mode) in RasterFit.Fit.entries.withIndex()) assertEquals("$name $mode", expected[index], hashes[index])
            png.recycle()
        }
    }
}
