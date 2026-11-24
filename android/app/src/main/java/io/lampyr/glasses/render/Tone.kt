package io.lampyr.glasses.render

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

// Firmware brightness codes, shared with the simulator. Tune only these three values.
const val PRIMARY = 15
const val SECONDARY = 10
const val TERTIARY = 6

/** Internal drawing ink: green preserves the original coverage, blue carries the tone.
 * The coverage threshold is independent of brightness, so dim strokes cannot disappear.
 * These colours never leave the drawing surface; the master and mirror are green only.
 */
internal fun toneInk(tone: Int) = GREEN or ((15 - tone) * 17)

object GreyFrame {
    private val tones = intArrayOf(PRIMARY, SECONDARY, TERTIARY)
    data class Result(val levels: ByteArray, val bitmap: Bitmap)

    /** The exact per-pixel rule, kept for the table and for tests. */
    internal fun level(green: Int, blue: Int): Byte = if (green < 100) 0 else {
        val tone = 15.0 - blue * 15.0 / green
        tones.minBy { abs(it - tone) }.toByte()
    }
    /** [level] for every (green >= 100, blue) pair, precomputed: convert runs on the main thread for every frame. */
    private val table: ByteArray by lazy { ByteArray(156 * 256) { level(100 + it / 256, it % 256) } }

    fun convert(source: Bitmap): Result {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val lookup = table
        val levels = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val green = p ushr 8 and 255
            val level = if (green < 100) 0 else lookup[(green - 100) * 256 + (p and 255)].toInt()
            levels[i] = level.toByte()
            // Brightness codes are already encoded. DisplayMirror uses the same gamma-1 mapping.
            pixels[i] = BLACK or (level * 17 shl 8)
        }
        return Result(levels, Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888))
    }

    fun levels(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(pixels.size) { ((pixels[it] ushr 8 and 255) / 17.0).roundToInt().toByte() }
    }
}
