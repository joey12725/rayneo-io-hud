package io.lampyr.glasses.glasses

import android.content.Context
import android.graphics.Bitmap
import io.lampyr.glasses.render.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayMirrorTest {
    @Test fun experimentalFramesAndSentMirrorsUseNativeGeometry() {
        val pattern = DisplayExperiments.patternFrame()
        assertEquals(540, pattern.bitmap.width); assertEquals(220, pattern.bitmap.height)
        assertEquals(14850, pattern.bits.size)
        assertArrayEquals(pattern.bits, OneBit.convert(pattern.bitmap).bits)
        val text = DisplayMirror.text("Stock content unchanged", 26, 7)
        assertEquals(540, text.bitmap.width); assertEquals(220, text.bitmap.height)
        assertEquals(26, text.text.cols); assertEquals(7, text.text.rows)
    }

    @Test fun reconnectSeedsTheMasterRatherThanTheFittedOrTextMirror() = runBlocking {
        val preview = PreviewTransport()
        val master = Frame(ByteArray(14850) { 0x55 }, Bitmap.createBitmap(540, 220, Bitmap.Config.ARGB_8888), TextGrid())
        preview.show(master)
        val fitted = DisplayMirror.raster(RasterFit.Image(540, 220, 16, ByteArray(118800) { 119 }))
        preview.showSent(fitted)
        assertSame(fitted, preview.frames.value)
        assertSame(master, preview.masterFrame)
        assertEquals(14850, preview.masterFrame!!.bits.size)
        assertEquals(0xff007700.toInt(), fitted.bitmap.getPixel(0, 0))
        val next = master.copy(bits = ByteArray(14850))
        preview.rememberMaster(next)
        assertSame(next, preview.masterFrame)
        assertSame(fitted, preview.frames.value) // An unsent new frame cannot advance the mirror.
    }
    @Test fun experimentLogKeepsMultipleResultsAndBoundsOldContent() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = File(context.filesDir, "display-experiments.log")
        file.writeText("")
        val firstWriter = DisplayExperimentLog(context)
        firstWriter.add("glyph", "PASS")
        DisplayExperimentLog(context).add("weather", "UNKNOWN")
        assertTrue(firstWriter.read().contains("glyph: PASS"))
        assertTrue(firstWriter.read().contains("weather: UNKNOWN"))
        file.writeText("old\n".repeat(20_000))
        firstWriter.add("new", "FAIL")
        assertTrue(file.readText().length < 49_000)
        assertTrue(firstWriter.read().endsWith("new: FAIL\n"))
        file.delete()
    }
}
