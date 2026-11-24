package io.lampyr.glasses.render

import android.graphics.Bitmap

object OneBit {
    data class Result(val bits: ByteArray, val bitmap: Bitmap)

    /** Compatibility output from the grey master: every positive brightness code is ON. */
    fun pack(levels: ByteArray): ByteArray {
        val bits = ByteArray((levels.size + 7) / 8)
        for (i in levels.indices) if (levels[i] > 0) bits[i ushr 3] = (bits[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
        return bits
    }

    /** Row-major, MSB first; 540 pixels per row, continuously packed without row padding (rows alternate half-bytes). */
    fun convert(source: Bitmap, threshold: Int = 100): Result {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val bits = ByteArray((pixels.size + 7) / 8)
        pixels.indices.forEach { i ->
            val on = (pixels[i] ushr 8 and 255) >= threshold
            if (on) bits[i / 8] = (bits[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            pixels[i] = if (on) GREEN else BLACK
        }
        return Result(
            bits,
            Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888),
        )
    }
}
