package io.lampyr.glasses.render.avatar

import android.graphics.Bitmap
import io.lampyr.glasses.render.BLACK
import io.lampyr.glasses.render.GREEN
import io.lampyr.glasses.render.Gfx
import kotlin.math.ceil

/**
 * A bring-your-own avatar: the character drawn for the assistant on the glasses. A pack is a schema-v1
 * `manifest.json` plus one atlas PNG (see docs/avatar-packs.md). The app asks for canonical state names
 * ([AvatarStates]); missing states resolve through the manifest's fallbacks.
 */
interface AvatarPack {
    val manifest: AvatarManifest

    /** Draws [state] (started at [since]) at [now] into the box; false when nothing could be drawn. */
    fun draw(g: Gfx, state: String, since: Double, now: Double, x: Int, y: Int, w: Int, h: Int): Boolean

    /** Draws the idle frame's `portraitCrop` as a [size] x [size] square (rail icons, menus). */
    fun drawPortrait(g: Gfx, x: Int, y: Int, size: Int): Boolean
}

/** OR-pooling to one bit: a target pixel is lit when any source pixel it covers has green >= threshold. */
object AvatarRaster {
    fun pool(source: IntArray, sw: Int, sh: Int, w: Int, h: Int, threshold: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (dy in 0 until h) for (dx in 0 until w) {
            var on = false
            for (sy in dy * sh / h until ceil((dy + 1.0) * sh / h).toInt()) {
                for (sx in dx * sw / w until ceil((dx + 1.0) * sw / w).toInt()) {
                    if ((source[sy * sw + sx] ushr 8 and 255) >= threshold) { on = true; break }
                }
                if (on) break
            }
            out[dy * w + dx] = on
        }
        return out
    }
}

/** Small access-ordered LRU (scaled frames are reused every render). */
class LruCache<K, V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(capacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }
    val size: Int get() = map.size
    fun getOrPut(key: K, make: () -> V): V = map[key] ?: make().also { map[key] = it }
    operator fun contains(key: K) = key in map
    fun clear() = map.clear()
}

/** The standard pack: cells of a single atlas bitmap, scaled to each box by [AvatarRaster.pool]. */
class AtlasAvatarPack(override val manifest: AvatarManifest, private val atlas: Bitmap) : AvatarPack {
    init {
        val m = manifest
        if (atlas.width < m.cols * m.cellW || atlas.height < m.rows * m.cellH)
            throw AvatarPackException("atlas is ${atlas.width}x${atlas.height}; the manifest needs at least ${m.cols * m.cellW}x${m.rows * m.cellH}")
    }

    /** Scaled one-bit frames keyed by cell, box and crop. */
    internal val scaled = LruCache<String, Bitmap>(SCALED_CACHE)

    private fun scaled(cell: Int, w: Int, h: Int, sx: Int, sy: Int, sw: Int, sh: Int): Bitmap = scaled.getOrPut("$cell:$w:$h:$sx:$sy:$sw:$sh") {
        val m = manifest
        val source = IntArray(sw * sh)
        atlas.getPixels(source, 0, sw, cell % m.cols * m.cellW + sx, cell / m.cols * m.cellH + sy, sw, sh)
        val on = AvatarRaster.pool(source, sw, sh, w, h, m.threshold)
        Bitmap.createBitmap(IntArray(w * h) { if (on[it]) GREEN else BLACK }, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun draw(g: Gfx, state: String, since: Double, now: Double, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val cell = manifest.frameCell(state, since, now)
        g.drawImage(scaled(cell, w, h, 0, 0, manifest.cellW, manifest.cellH), 0, 0, w, h, x, y, w, h)
        return true
    }

    override fun drawPortrait(g: Gfx, x: Int, y: Int, size: Int): Boolean {
        if (size <= 0) return false
        val (cx, cy, cw, ch) = manifest.portraitCrop
        val cell = manifest.clip(AvatarStates.IDLE).frames.first().cell
        g.drawImage(scaled(cell, size, size, cx, cy, cw, ch), 0, 0, size, size, x, y, size, size)
        return true
    }

    companion object { const val SCALED_CACHE = 32 }
}

/** Draws the assistant avatar for [state]; a simple orb outline stands in when no pack is loaded. */
fun Gfx.drawAvatar(state: String, since: Double, now: Double, x: Int = 8, y: Int = 10, w: Int = 128, h: Int = 192) {
    imageSmoothingEnabled = false
    val ok = avatar?.let { runCatching { it.draw(this, state, since, now, x, y, w, h) }.getOrDefault(false) } == true
    imageSmoothingEnabled = false
    if (!ok) drawAvatarPlaceholder(x, y, w, h)
}

/** Pack-free stand-in: a ring with two eyes, so a missing or broken pack never blanks the screen. */
fun Gfx.drawAvatarPlaceholder(x: Number, y: Number, w: Number, h: Number) {
    val cx = x.toDouble() + w.toDouble() / 2
    val r = minOf(w.toDouble(), h.toDouble()) * .36
    val cy = y.toDouble() + h.toDouble() * .45
    val l = maxOf(1.0, r / 10)
    p.circ(cx, cy, r, l)
    p.circ(cx - r * .38, cy - r * .15, maxOf(1.0, r * .12), 1, true)
    p.circ(cx + r * .38, cy - r * .15, maxOf(1.0, r * .12), 1, true)
}
