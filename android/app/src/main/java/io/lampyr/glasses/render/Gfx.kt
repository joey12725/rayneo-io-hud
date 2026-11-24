package io.lampyr.glasses.render

import android.graphics.*
import kotlin.math.*

const val GREEN: Int = -16711936
const val BLACK: Int = -16777216
object DisplaySize {
    const val W = 540
    const val H = 220
    const val PIXELS = W * H
    const val PACKED_BYTES = (PIXELS + 7) / 8
}

const val W = DisplaySize.W
const val H = DisplaySize.H

/** The simulator's Canvas subset. Geometry remains in display pixels. */
class Gfx(val canvas: Canvas, val avatar: io.lampyr.glasses.render.avatar.AvatarPack? = null) {
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private var path = Path()

    private data class State(
        val fill: Int,
        val stroke: Int,
        val width: Double,
        val smoothing: Boolean,
    )

    private val states = ArrayDeque<State>()
    var tone = PRIMARY
    fun <T> withTone(value: Int, draw: () -> T): T {
        val previous = tone
        tone = value
        return try { draw() } finally { tone = previous }
    }
    var fillStyle = GREEN
        set(value) { field = if (value == GREEN) toneInk(tone) else value }
    var strokeStyle = GREEN
        set(value) { field = if (value == GREEN) toneInk(tone) else value }
    var lineWidth = 2.0
    var imageSmoothingEnabled = false
    val p = Primitives(this)

    fun beginPath() {
        path = Path()
    }

    fun moveTo(x: Number, y: Number) {
        path.moveTo(x.toFloat(), y.toFloat())
    }

    fun lineTo(x: Number, y: Number) {
        path.lineTo(x.toFloat(), y.toFloat())
    }

    fun bezierCurveTo(a: Number, b: Number, c: Number, d: Number, e: Number, f: Number) {
        path.cubicTo(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(), f.toFloat())
    }

    fun quadraticCurveTo(a: Number, b: Number, c: Number, d: Number) {
        path.quadTo(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat())
    }

    fun closePath() {
        path.close()
    }

    fun rect(x: Number, y: Number, w: Number, h: Number) {
        path.addRect(
            x.toFloat(),
            y.toFloat(),
            x.toFloat() + w.toFloat(),
            y.toFloat() + h.toFloat(),
            Path.Direction.CW,
        )
    }

    fun roundRect(x: Number, y: Number, w: Number, h: Number, r: Number) {
        path.addRoundRect(
            x.toFloat(),
            y.toFloat(),
            x.toFloat() + w.toFloat(),
            y.toFloat() + h.toFloat(),
            r.toFloat(),
            r.toFloat(),
            Path.Direction.CW,
        )
    }

    fun arc(x: Number, y: Number, r: Number, a0: Number, a1: Number) {
        val radius = r.toFloat()
        val cx = x.toFloat()
        val cy = y.toFloat()
        var sweep = a1.toDouble() - a0.toDouble()
        if (sweep < 0) sweep = (sweep % (2 * PI) + 2 * PI) % (2 * PI)
        sweep = min(2 * PI, sweep)
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val start = (a0.toDouble() * 180 / PI).toFloat()
        // Path.arcTo normalizes a 360-degree sweep to zero on Android. Canvas arcs do not.
        if (sweep >= 2 * PI) {
            path.arcTo(bounds, start, 180f, false)
            path.arcTo(bounds, start + 180f, 180f, false)
        } else path.arcTo(bounds, start, (sweep * 180 / PI).toFloat(), false)
    }

    fun fill() {
        paint.color = fillStyle
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    fun stroke() {
        paint.color = strokeStyle
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineWidth.toFloat()
        canvas.drawPath(path, paint)
    }

    fun fillRect(x: Number, y: Number, w: Number, h: Number) {
        paint.color = fillStyle
        paint.style = Paint.Style.FILL
        canvas.drawRect(
            x.toFloat(),
            y.toFloat(),
            x.toFloat() + w.toFloat(),
            y.toFloat() + h.toFloat(),
            paint,
        )
    }

    fun strokeRect(x: Number, y: Number, w: Number, h: Number) {
        paint.color = strokeStyle
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineWidth.toFloat()
        canvas.drawRect(
            x.toFloat(),
            y.toFloat(),
            x.toFloat() + w.toFloat(),
            y.toFloat() + h.toFloat(),
            paint,
        )
    }

    fun save() {
        states.addLast(State(fillStyle, strokeStyle, lineWidth, imageSmoothingEnabled))
        canvas.save()
    }

    fun restore() {
        val s = states.removeLast()
        fillStyle = s.fill
        strokeStyle = s.stroke
        lineWidth = s.width
        imageSmoothingEnabled = s.smoothing
        canvas.restore()
    }

    fun translate(x: Number, y: Number) {
        canvas.translate(x.toFloat(), y.toFloat())
    }

    fun rotate(radians: Number) {
        canvas.rotate((radians.toDouble() * 180 / PI).toFloat())
    }

    fun scale(x: Number, y: Number) {
        canvas.scale(x.toFloat(), y.toFloat())
    }

    fun clip() {
        canvas.clipPath(path)
    }

    fun clipRect(x: Number, y: Number, w: Number, h: Number) {
        canvas.clipRect(
            x.toFloat(),
            y.toFloat(),
            x.toFloat() + w.toFloat(),
            y.toFloat() + h.toFloat(),
        )
    }

    fun drawImage(
        bitmap: Bitmap,
        sx: Int,
        sy: Int,
        sw: Int,
        sh: Int,
        x: Number,
        y: Number,
        w: Number,
        h: Number,
    ) {
        paint.isFilterBitmap = imageSmoothingEnabled
        paint.style = Paint.Style.FILL
        paint.color = GREEN
        canvas.drawBitmap(
            bitmap,
            Rect(sx, sy, sx + sw, sy + sh),
            RectF(x.toFloat(), y.toFloat(), x.toFloat() + w.toFloat(), y.toFloat() + h.toFloat()),
            paint,
        )
    }

    fun clear() {
        canvas.drawColor(BLACK)
    }
}

class Primitives(private val g: Gfx) {
    fun lw(w: Number, tone: Int = g.tone) {
        g.lineWidth = w.toDouble()
        g.withTone(tone) {
            g.strokeStyle = GREEN
            g.fillStyle = GREEN
        }
    }

    fun fr(x: Number, y: Number, w: Number, h: Number, c: Int = GREEN, tone: Int = g.tone) {
        g.withTone(tone) { g.fillStyle = c }
        g.fillRect(x, y, w, h)
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, l: Number = 2, tone: Int = g.tone) {
        lw(l, tone)
        val v = l.toDouble()
        g.strokeRect(x.toDouble() + v / 2, y.toDouble() + v / 2, w.toDouble() - v, h.toDouble() - v)
    }

    fun rr(
        x: Number,
        y: Number,
        w: Number,
        h: Number,
        r: Number,
        l: Number = 2,
        fill: Boolean = false,
        tone: Int = g.tone,
    ) {
        lw(l, tone)
        val v = l.toDouble()
        g.beginPath()
        g.roundRect(
            x.toDouble() + v / 2,
            y.toDouble() + v / 2,
            w.toDouble() - v,
            h.toDouble() - v,
            min(r.toDouble(), min((w.toDouble() - v) / 2, (h.toDouble() - v) / 2)),
        )
        if (fill) g.fill() else g.stroke()
    }

    fun ln(x1: Number, y1: Number, x2: Number, y2: Number, l: Number = 2, tone: Int = g.tone) {
        lw(l, tone)
        g.beginPath()
        g.moveTo(x1, y1)
        g.lineTo(x2, y2)
        g.stroke()
    }

    fun circ(x: Number, y: Number, r: Number, l: Number = 2, fill: Boolean = false, tone: Int = g.tone) {
        lw(l, tone)
        g.beginPath()
        g.arc(x, y, r, 0, 2 * PI)
        if (fill) g.fill() else g.stroke()
    }

    fun arc(x: Number, y: Number, r: Number, a0: Number, a1: Number, l: Number = 2, tone: Int = g.tone) {
        lw(l, tone)
        g.beginPath()
        g.arc(x, y, r, a0, a1)
        g.stroke()
    }

    fun poly(pts: List<Pair<Double, Double>>, fill: Boolean = true, l: Number = 2, tone: Int = g.tone) {
        lw(l, tone)
        g.beginPath()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) g.moveTo(x, y) else g.lineTo(x, y) }
        g.closePath()
        if (fill) g.fill() else g.stroke()
    }

    fun path(pts: List<Pair<Double, Double>>, l: Number = 2, tone: Int = g.tone) {
        lw(l, tone)
        g.beginPath()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) g.moveTo(x, y) else g.lineTo(x, y) }
        g.stroke()
    }

    fun heart(cx: Number, cy: Number, size: Number, fill: Boolean = true, l: Number = 2, tone: Int = g.tone) {
        val x = cx.toDouble()
        val y = cy.toDouble()
        val s = size.toDouble()
        lw(l, tone)
        g.beginPath()
        g.moveTo(x, y + s * .35)
        g.bezierCurveTo(x - s * .55, y - s * .05, x - s * .3, y - s * .5, x, y - s * .18)
        g.bezierCurveTo(x + s * .3, y - s * .5, x + s * .55, y - s * .05, x, y + s * .35)
        if (fill) g.fill() else g.stroke()
    }
}

fun Gfx.bar(x: Number, y: Number, w: Number, h: Number, progress: Double, tick: Double? = null) {
    val xx = x.toDouble()
    val yy = y.toDouble()
    val ww = w.toDouble()
    val hh = h.toDouble()
    p.rect(xx, yy, ww, hh, 2, tone = TERTIARY)
    p.fr(xx + 3, yy + 3, max(0.0, floor((ww - 6) * min(1.0, progress) + .5)), hh - 6)
    tick?.let {
        val tx = xx + 3 + floor((ww - 6) * it + .5)
        p.fr(tx - 1, yy - 4, 2, hh + 8, BLACK)
        p.fr(tx - 1, yy - 4, 2, 3, tone = TERTIARY)
        p.fr(tx - 1, yy + hh + 1, 2, 3, tone = TERTIARY)
    }
}
