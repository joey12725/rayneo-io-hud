package io.lampyr.glasses.glasses

import android.graphics.*
import io.lampyr.glasses.render.*

/** Mirror only after a text write / raster commit acknowledgement. Optical font appearance is unverified. */
object DisplayMirror {
    fun raster(image: RasterFit.Image): Frame {
        val pixels = IntArray(image.pixels.size) { i -> BLACK or ((image.pixels[i].toInt() and 255) shl 8) }
        return Frame(byteArrayOf(), Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888), TextGrid())
    }
    fun text(content: String, columns: Int, rows: Int): Frame {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; typeface = Typeface.MONOSPACE; textSize = minOf(W.toFloat() / columns / .61f, H.toFloat() / rows * .8f) }
        content.split('\n').take(rows).forEachIndexed { y, line ->
            line.codePoints().toArray().take(columns).forEachIndexed { x, cp ->
                canvas.drawText(String(Character.toChars(cp)), x * W.toFloat() / columns, y * H.toFloat() / rows + (H.toFloat() / rows - paint.fontSpacing) / 2 - paint.ascent(), paint)
            }
        }
        // Mirror metadata preserves the actual full-width/glyph probe cells, bypassing UI normalization.
        val grid = TextGrid(columns, rows, uppercase = false, normalizeInput = false)
        content.split('\n').take(rows).forEachIndexed { y, line -> grid.put(0, y, line) }
        return Frame(byteArrayOf(), bitmap, grid)
    }
}
