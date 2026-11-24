package io.lampyr.glasses.glasses.raster

import io.lampyr.glasses.render.RasterFit
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** The table-driven encoder (press-path latency) must produce byte-for-byte what the original per-pixel one did. */
class Tvp2EncoderEquivalenceTest {
    private fun referenceCompress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var cursor = 0
        while (cursor < input.size) {
            var run = 1
            while (run < 128 && cursor + run < input.size && input[cursor + run] == input[cursor]) run++
            if (run >= 3) { out.write(257 - run); out.write(input[cursor].toInt()); cursor += run }
            else {
                val start = cursor++
                while (cursor - start < 128 && cursor < input.size) {
                    if (cursor + 2 < input.size && input[cursor] == input[cursor + 1] && input[cursor] == input[cursor + 2]) break
                    cursor++
                }
                out.write(cursor - start - 1); out.write(input, start, cursor - start)
            }
        }
        return out.toByteArray()
    }
    private fun reference(image: RasterFit.Image): Tvp2Codec.Encoded {
        val bits = if (image.levels <= 4) 2 else 4
        val max = (1 shl bits) - 1
        val samples = IntArray(image.pixels.size) { ((image.pixels[it].toInt() and 255) * max + 127) / 255 }
        val actual = image.copy(pixels = ByteArray(samples.size) { (samples[it] * 255 / max).toByte() })
        val strips = (0..3).map { strip ->
            val packed = ByteArray(540 * 55 * bits / 8)
            repeat(540 * 55) { i ->
                val shift = 8 - bits - (i % (8 / bits)) * bits
                packed[i * bits / 8] = (packed[i * bits / 8].toInt() or (samples[strip * 540 * 55 + i] shl shift)).toByte()
            }
            referenceCompress(packed)
        }
        return Tvp2Codec.Encoded(actual, bits, strips)
    }

    @Test fun matchesTheReferenceEncoderOnUiLikeAndNoisyFrames() {
        val random = Random(7)
        for (levels in listOf(2, 4, 8, 16)) for (kind in 0..2) {
            val pixels = ByteArray(540 * 220) { i ->
                when (kind) {
                    0 -> 0
                    1 -> if ((i / 540) % 18 < 7 && (i % 540) % 12 < 5) (if (i % 3 == 0) 255 else 170).toByte() else 0 // text-like rows
                    else -> random.nextInt(256).toByte()
                }
            }
            val image = RasterFit.Image(540, 220, levels, pixels)
            val expected = reference(image)
            val actual = Tvp2Codec.frame(image)
            assertEquals(expected.bits, actual.bits)
            assertArrayEquals("levels=$levels kind=$kind expanded", expected.image.pixels, actual.image.pixels)
            expected.strips.zip(actual.strips).forEachIndexed { n, (e, a) -> assertArrayEquals("levels=$levels kind=$kind strip $n", e, a) }
        }
    }

    @Test fun compressMatchesTheReferenceOnEdgeCases() {
        val random = Random(11)
        val cases = listOf(ByteArray(1), ByteArray(2) { 1 }, ByteArray(3) { 5 }, ByteArray(128) { it.toByte() }, ByteArray(129) { it.toByte() },
            ByteArray(300) { 9 }, ByteArray(1000) { (random.nextInt(3)).toByte() }, ByteArray(14850) { random.nextInt(256).toByte() })
        for (case in cases) assertArrayEquals(referenceCompress(case), Tvp2Codec.compress(case))
    }
}
