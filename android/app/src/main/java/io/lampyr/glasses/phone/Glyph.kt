package io.lampyr.glasses.phone

import android.graphics.*
import android.graphics.drawable.Drawable

/** Line icons drawn on a 24-unit grid, so the app needs no image resources and every icon shares one stroke. */
class Glyph(private val kind: String, private var color: Int, private val size: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    init { setBounds(0, 0, size, size) }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    fun tint(value: Int) { color = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        stroke.color = color; fill.color = color
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        canvas.save()
        canvas.translate(bounds.left + (bounds.width() - 24 * scale) / 2, bounds.top + (bounds.height() - 24 * scale) / 2)
        canvas.scale(scale, scale)
        val p = Path()
        fun line(vararg points: Float) { p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]) }
        fun box(l: Float, t: Float, r: Float, b: Float, radius: Float) = p.addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW)
        fun ring(x: Float, y: Float, r: Float) = p.addCircle(x, y, r, Path.Direction.CW)
        fun dot(x: Float, y: Float, r: Float = 1.1f) = canvas.drawCircle(x, y, r, fill)
        when (kind) {
            "home" -> { line(3.5f, 11.5f, 12f, 4f, 20.5f, 11.5f); line(6f, 10f, 6f, 20f, 18f, 20f, 18f, 10f); line(10f, 20f, 10f, 14.5f, 14f, 14.5f, 14f, 20f) }
            "remote", "controller" -> { box(2.5f, 7f, 21.5f, 17f, 5f); line(7.5f, 10.2f, 7.5f, 13.8f); line(5.7f, 12f, 9.3f, 12f); dot(15.2f, 13.2f); dot(18f, 10.8f) }
            "tools" -> { box(4f, 4f, 10.5f, 10.5f, 2f); box(13.5f, 4f, 20f, 10.5f, 2f); box(4f, 13.5f, 10.5f, 20f, 2f); box(13.5f, 13.5f, 20f, 20f, 2f) }
            "settings" -> {
                line(4f, 7f, 7f, 7f); line(11f, 7f, 20f, 7f); ring(9f, 7f, 2f)
                line(4f, 12f, 13f, 12f); line(17f, 12f, 20f, 12f); ring(15f, 12f, 2f)
                line(4f, 17f, 6f, 17f); line(10f, 17f, 20f, 17f); ring(8f, 17f, 2f)
            }
            "glasses" -> {
                box(2.5f, 9.5f, 10.5f, 16f, 2.6f); box(13.5f, 9.5f, 21.5f, 16f, 2.6f)
                p.moveTo(10.5f, 12f); p.quadTo(12f, 10.6f, 13.5f, 12f); line(2.5f, 11.5f, 3.5f, 7.5f); line(21.5f, 11.5f, 20.5f, 7.5f)
            }
            "back" -> line(14.5f, 5f, 7.5f, 12f, 14.5f, 19f)
            "chevron" -> line(9f, 6f, 15f, 12f, 9f, 18f)
            "check" -> line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            "alert" -> { line(12f, 4f, 21f, 19.5f, 3f, 19.5f); p.close(); line(12f, 10f, 12f, 14f); dot(12f, 16.8f) }
            "mic" -> { box(9f, 3.5f, 15f, 13.5f, 3f); p.addArc(RectF(6f, 5.5f, 18f, 17.5f), 0f, 180f); line(12f, 17.5f, 12f, 20.5f); line(9f, 20.5f, 15f, 20.5f) }
            "bell" -> { p.moveTo(6f, 17f); p.lineTo(6f, 11f); p.arcTo(RectF(6f, 5f, 18f, 17f), 180f, 180f); p.lineTo(18f, 17f); line(4.5f, 17f, 19.5f, 17f); line(10.5f, 20f, 13.5f, 20f) }
            "phone" -> { box(7f, 3f, 17f, 21f, 2.5f); line(11f, 18f, 13f, 18f) }
            "wave" -> { line(5f, 10f, 5f, 14f); line(8.5f, 7f, 8.5f, 17f); line(12f, 4.5f, 12f, 19.5f); line(15.5f, 8f, 15.5f, 16f); line(19f, 10.5f, 19f, 13.5f) }
            "log" -> { line(8.5f, 7f, 20f, 7f); line(8.5f, 12f, 20f, 12f); line(8.5f, 17f, 20f, 17f); dot(4.5f, 7f); dot(4.5f, 12f); dot(4.5f, 17f) }
            "display" -> { box(3.5f, 5.5f, 20.5f, 16.5f, 2f); line(9f, 20f, 15f, 20f); line(12f, 16.5f, 12f, 20f) }
            "call" -> {
                p.moveTo(6.5f, 4.5f); p.lineTo(9.5f, 4.5f); p.lineTo(11f, 8.5f); p.lineTo(9f, 10f); p.quadTo(10.5f, 13.5f, 14f, 15f); p.lineTo(15.5f, 13f)
                p.lineTo(19.5f, 14.5f); p.lineTo(19.5f, 17.5f); p.quadTo(19.5f, 19.5f, 17.5f, 19.5f); p.quadTo(5f, 19f, 4.5f, 6.5f); p.quadTo(4.5f, 4.5f, 6.5f, 4.5f)
            }
            "heart" -> { p.moveTo(12f, 20f); p.cubicTo(4f, 14f, 4f, 8.5f, 7.5f, 6.5f); p.cubicTo(10f, 5.2f, 12f, 7f, 12f, 8.5f); p.cubicTo(12f, 7f, 14f, 5.2f, 16.5f, 6.5f); p.cubicTo(20f, 8.5f, 20f, 14f, 12f, 20f) }
            "data" -> { p.addOval(RectF(5f, 4f, 19f, 9f), Path.Direction.CW); line(5f, 6.5f, 5f, 17.5f); line(19f, 6.5f, 19f, 17.5f); p.addArc(RectF(5f, 15f, 19f, 20f), 0f, 180f); p.addArc(RectF(5f, 9.5f, 19f, 14.5f), 0f, 180f) }
            "guide" -> { line(4f, 7f, 6f, 9f, 9f, 5.5f); line(12f, 7.5f, 20f, 7.5f); line(4f, 15f, 6f, 17f, 9f, 13.5f); line(12f, 15.5f, 20f, 15.5f) }
            "bolt" -> { line(13f, 3f, 5f, 13.5f, 11.5f, 13.5f, 10.5f, 21f, 19f, 10f, 12.5f, 10f); p.close() }
            "battery" -> { box(3f, 8f, 19f, 16f, 2f); line(21.2f, 10.5f, 21.2f, 13.5f) }
            "share" -> { line(12f, 15f, 12f, 4f); line(8f, 8f, 12f, 4f, 16f, 8f); line(5f, 13f, 5f, 19.5f, 19f, 19.5f, 19f, 13f) }
            "info" -> { ring(12f, 12f, 8.5f); line(12f, 11f, 12f, 16.5f); dot(12f, 8f) }
            "bluetooth" -> line(7f, 8f, 17f, 16f, 12f, 20.5f, 12f, 3.5f, 17f, 8f, 7f, 16f)
            "refresh" -> { p.addArc(RectF(5f, 5f, 19f, 19f), -50f, 300f); line(19.5f, 4.5f, 19.5f, 9.5f, 14.5f, 9.5f) }
            "apps" -> { for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) dot(x, y, 1.5f) }
            "lock" -> { box(5.5f, 10.5f, 18.5f, 20f, 2.5f); p.moveTo(8.5f, 10.5f); p.lineTo(8.5f, 8f); p.arcTo(RectF(8.5f, 4.5f, 15.5f, 11.5f), 180f, 180f); p.lineTo(15.5f, 10.5f) }
            "up" -> { p.moveTo(12f, 6.5f); p.lineTo(18f, 16.5f); p.lineTo(6f, 16.5f); p.close(); canvas.drawPath(p, fill) }
            "down" -> { p.moveTo(12f, 17.5f); p.lineTo(18f, 7.5f); p.lineTo(6f, 7.5f); p.close(); canvas.drawPath(p, fill) }
            "left" -> { p.moveTo(6.5f, 12f); p.lineTo(16.5f, 6f); p.lineTo(16.5f, 18f); p.close(); canvas.drawPath(p, fill) }
            "right" -> { p.moveTo(17.5f, 12f); p.lineTo(7.5f, 6f); p.lineTo(7.5f, 18f); p.close(); canvas.drawPath(p, fill) }
            "star" -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 8.5f else 3.6f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 12f + r * Math.cos(a).toFloat(); val y = 12.5f + r * Math.sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "minus" -> line(6f, 12f, 18f, 12f)
            "plus" -> { line(6f, 12f, 18f, 12f); line(12f, 6f, 12f, 18f) }
        }
        canvas.drawPath(p, stroke)
        canvas.restore()
    }
}
