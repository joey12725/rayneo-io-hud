package io.lampyr.glasses.render

import kotlin.math.*

/** Linear light coverage of a 1-bit master, quantized onto a firmware surface. No Android APIs. */
object RasterFit {
    /** Native 4-bit master. No gamma re-encoding, resampling or dithering at hand-off.
     * Every nonzero code stays ON for 1-bit firmware. At 4+ levels the UI tiers stay distinct.
     */
    fun fromMaster(master: ByteArray, width: Int = DisplaySize.W, height: Int = DisplaySize.H, levels: Int = 16): Image {
        require(master.size == width * height)
        require(levels in listOf(2, 4, 8, 16))
        // 16 possible inputs: one table instead of floating-point rounding per pixel (this is on the press path).
        val table = ByteArray(16) { value ->
            val q = if (levels == 2) { if (value > 0) 1 else 0 }
                else (value * (levels - 1) / 15.0).roundToInt()
            (q * 255 / (levels - 1)).toByte()
        }
        val out = ByteArray(master.size)
        for (i in master.indices) {
            val value = master[i].toInt()
            require(value in 0..15)
            out[i] = table[value]
        }
        return Image(width, height, levels, out)
    }

    enum class Fit { STRETCH, FIT }
    enum class Mono { THRESHOLD, DITHER }
    data class Image(val width: Int, val height: Int, val levels: Int, val pixels: ByteArray) {
        init {
            require(width in 1..4096 && height in 1..4096 && pixels.size == width * height)
            require(levels in listOf(2, 4, 8, 16))
        }
    }

    /** Input is row-major, MSB first, without row padding (the app's Frame.bits). */
    fun fit(bits: ByteArray, sourceWidth: Int = DisplaySize.W, sourceHeight: Int = DisplaySize.H,
            width: Int = 540, height: Int = 220, levels: Int = 16,
            mode: Fit = Fit.FIT, gamma: Double = 2.2, mono: Mono = Mono.DITHER): Image {
        require(sourceWidth in 1..4096 && sourceHeight in 1..4096)
        require(bits.size == (sourceWidth * sourceHeight + 7) / 8)
        val source = IntArray(sourceWidth * sourceHeight) { i ->
            if ((bits[i / 8].toInt() and (128 ushr (i % 8))) != 0) 1 else 0
        }
        return fit(source, sourceWidth, sourceHeight, width, height, levels, mode, gamma, mono)
    }

    /** 0/1 input. Average coverage before gamma encoding; do not average encoded brightness. */
    fun fit(source: IntArray, sourceWidth: Int, sourceHeight: Int,
            width: Int = 540, height: Int = 220, levels: Int = 16,
            mode: Fit = Fit.FIT, gamma: Double = 2.2, mono: Mono = Mono.DITHER): Image {
        require(sourceWidth in 1..4096 && sourceHeight in 1..4096 && source.size == sourceWidth * sourceHeight)
        require(source.all { it in 0..1 } && gamma.isFinite() && gamma > 0)
        require(width in 1..4096 && height in 1..4096 && levels in listOf(2, 4, 8, 16))
        if (sourceWidth == width && sourceHeight == height)
            return Image(width, height, levels, ByteArray(source.size) { if (source[it] == 1) 255.toByte() else 0 })
        val scale = min(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
        val dw = if (mode == Fit.STRETCH) width else max(1, (sourceWidth * scale).roundToInt()).coerceAtMost(width)
        val dh = if (mode == Fit.STRETCH) height else max(1, (sourceHeight * scale).roundToInt()).coerceAtMost(height)
        val ox = (width - dw) / 2
        val oy = (height - dh) / 2
        val output = ByteArray(width * height)
        for (y in 0 until dh) for (x in 0 until dw) {
            val left = x.toDouble() * sourceWidth / dw
            val right = (x + 1.0) * sourceWidth / dw
            val top = y.toDouble() * sourceHeight / dh
            val bottom = (y + 1.0) * sourceHeight / dh
            var coverage = 0.0
            for (sy in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(sourceHeight)) {
                val wy = min(bottom, sy + 1.0) - max(top, sy.toDouble())
                for (sx in floor(left).toInt() until ceil(right).toInt().coerceAtMost(sourceWidth)) {
                    coverage += source[sy * sourceWidth + sx] * wy * (min(right, sx + 1.0) - max(left, sx.toDouble()))
                }
            }
            val brightness = (coverage / ((right - left) * (bottom - top))).coerceIn(0.0, 1.0).pow(1 / gamma)
            val q = if (levels == 2) {
                val threshold = if (mono == Mono.THRESHOLD) .5 else (bayer[(y + oy) % 4][(x + ox) % 4] + .5) / 16
                if (brightness >= threshold) 1 else 0
            } else (brightness * (levels - 1)).roundToInt()
            output[(y + oy) * width + x + ox] = (q * 255 / (levels - 1)).toByte()
        }
        return Image(width, height, levels, output)
    }
    private val bayer = arrayOf(intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6), intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5))
}
