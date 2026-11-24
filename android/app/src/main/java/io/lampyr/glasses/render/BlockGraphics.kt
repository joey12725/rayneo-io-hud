package io.lampyr.glasses.render

/** Experimental font graphics. Quadrants give 52x14 samples at a 26x7 layout; halves give 26x14. */
object BlockGraphics {
    enum class Mode { OFF, HALF, QUADRANT }
    fun convert(bits: ByteArray, width: Int, height: Int, columns: Int = 26, rows: Int = 7,
                mode: Mode = Mode.QUADRANT): List<String> {
        require(mode != Mode.OFF)
        val horizontal = if (mode == Mode.QUADRANT) 2 else 1
        val image = RasterFit.fit(bits, width, height, columns * horizontal, rows * 2, 2,
            RasterFit.Fit.FIT, mono = RasterFit.Mono.THRESHOLD)
        fun on(x: Int, y: Int) = image.pixels[y * image.width + x].toInt() != 0
        return (0 until rows).map { y -> buildString {
            for (x in 0 until columns) {
                val sx = x * horizontal
                var mask = (if (on(sx, y * 2)) 1 else 0) or (if (on(sx, y * 2 + 1)) 4 else 0)
                if (horizontal == 2) mask = mask or (if (on(sx + 1, y * 2)) 2 else 0) or (if (on(sx + 1, y * 2 + 1)) 8 else 0)
                append(if (horizontal == 1) when (mask) { 1 -> '▀'; 4 -> '▄'; 5 -> '█'; else -> '\u3000' } else quadrants[mask])
            }
        } }
    }
    private val quadrants = charArrayOf('\u3000', '▘', '▝', '▀', '▖', '▌', '▞', '▛', '▗', '▚', '▐', '▜', '▄', '▙', '▟', '█')
}
