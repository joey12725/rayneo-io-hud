package io.lampyr.glasses.phone

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.lampyr.glasses.MainActivity
import io.lampyr.glasses.glasses.Frame
import io.lampyr.glasses.render.DisplaySize

/** One tab of the main screen. */
abstract class Page(val activity: MainActivity) {
    abstract val view: View
    /** Called about once a second while the tab is showing, and whenever it is switched to. */
    open fun refresh() {}
    /** Every frame the glasses would show. */
    open fun frame(frame: Frame, locked: Boolean) {}
}

/** What the lens is showing right now, pixel for pixel, at the display's own 540:220 shape. */
class LensView(context: Context) : View(context) {
    var frame: Frame? = null
    var locked = false
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1).toFloat(); color = Ink.LINE }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.FAINT; textSize = context.dp(13).toFloat(); textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val box = RectF()
    private val radius = context.dp(14).toFloat()
    fun show(next: Frame, isLocked: Boolean) { frame = next; locked = isLocked; invalidate() }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(width, width * DisplaySize.H / DisplaySize.W)
    }
    override fun onDraw(canvas: Canvas) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.rewind(); clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); canvas.drawColor(Color.BLACK)
        val bitmap = frame?.bitmap
        when {
            bitmap == null -> canvas.drawText("Starting…", width / 2f, height / 2f + caption.textSize / 3, caption)
            locked -> canvas.drawText("Lens locked", width / 2f, height / 2f + caption.textSize / 3, caption)
            io.lampyr.glasses.service.GlassesService.instance?.let { runCatching { it.ui.locked || it.ui.awake }.getOrDefault(true) } == false ->
                canvas.drawText("Lens asleep · press any button", width / 2f, height / 2f + caption.textSize / 3, caption)
            else -> {
                val inset = context.dp(8).toFloat()
                val scale = minOf((width - 2 * inset) / bitmap.width, (height - 2 * inset) / bitmap.height)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
            }
        }
        canvas.restore()
        box.inset(edge.strokeWidth / 2, edge.strokeWidth / 2); canvas.drawRoundRect(box, radius, radius, edge)
    }
}

/**
 * An on-screen copy of the 16-button controller, laid out like the real one: shoulder buttons along
 * the top, D-pad on the left, face buttons on the right, the four small buttons between them.
 * Buttons report press and release separately, so holds work exactly as they do on the hardware.
 */
class RemotePad(context: Context, private val press: (String) -> Unit, private val release: (String) -> Unit) : ViewGroup(context) {
    /** Centre x, centre y, width and height, all as fractions of the pad's width. */
    private class Spot(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val label: String?, val round: Boolean)
    private val spots = listOf(
        Spot("l2", 0.115f, 0.07f, 0.19f, 0.105f, "L2", false), Spot("l", 0.325f, 0.07f, 0.19f, 0.105f, "L", false),
        Spot("r", 0.675f, 0.07f, 0.19f, 0.105f, "R", false), Spot("r2", 0.885f, 0.07f, 0.19f, 0.105f, "R2", false),
        Spot("up", 0.215f, 0.305f, 0.13f, 0.13f, null, false), Spot("down", 0.215f, 0.565f, 0.13f, 0.13f, null, false),
        Spot("left", 0.085f, 0.435f, 0.13f, 0.13f, null, false), Spot("right", 0.345f, 0.435f, 0.13f, 0.13f, null, false),
        Spot("x", 0.785f, 0.295f, 0.14f, 0.14f, "X", true), Spot("b", 0.785f, 0.575f, 0.14f, 0.14f, "B", true),
        Spot("y", 0.645f, 0.435f, 0.14f, 0.14f, "Y", true), Spot("a", 0.925f, 0.435f, 0.14f, 0.14f, "A", true),
        Spot("minus", 0.445f, 0.235f, 0.085f, 0.085f, null, true), Spot("plus", 0.555f, 0.235f, 0.085f, 0.085f, null, true),
        Spot("star", 0.445f, 0.62f, 0.085f, 0.085f, null, true), Spot("heart", 0.555f, 0.62f, 0.085f, 0.085f, null, true),
    )
    private val ratio = 0.69f
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.RAISED }
    init {
        setWillNotDraw(false); isMotionEventSplittingEnabled = true
        spots.forEach { addView(Key(it)) }
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        spots.forEachIndexed { index, spot ->
            getChildAt(index).measure(MeasureSpec.makeMeasureSpec((spot.w * width).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((spot.h * width).toInt(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, (width * ratio).toInt())
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        spots.forEachIndexed { index, spot ->
            val w = (spot.w * width).toInt(); val h = (spot.h * width).toInt()
            val left = (spot.x * width - w / 2f).toInt(); val top = (spot.y * width - h / 2f).toInt()
            getChildAt(index).layout(left, top, left + w, top + h)
        }
    }
    /** The plus-shaped plate behind the four direction keys. */
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val arm = 0.13f * w / 2; val reach = 0.195f * w; val cx = 0.215f * w; val cy = 0.435f * w; val r = context.dp(12).toFloat()
        canvas.drawRoundRect(cx - arm, cy - reach, cx + arm, cy + reach, r, r, cross)
        canvas.drawRoundRect(cx - reach, cy - arm, cx + reach, cy + arm, r, r, cross)
    }

    private inner class Key(private val spot: Spot) : View(context) {
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        private val glyph = if (spot.label == null) Glyph(spot.id, Ink.TEXT, 0) else null
        private val direction = spot.id in setOf("up", "down", "left", "right")
        private var down = false
        init { contentDescription = spot.label ?: spot.id; isClickable = true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (!down) { down = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); press(spot.id); invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (down) { down = false; release(spot.id); invalidate() }
            }
            return true
        }
        override fun onDetachedFromWindow() { if (down) { down = false; release(spot.id) }; super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val color = if (down) Ink.GREEN else Ink.TEXT
            body.color = when { down && !direction -> Ink.GREEN_DIM; direction -> Color.TRANSPARENT; else -> Ink.RAISED }
            if (spot.round) canvas.drawCircle(w / 2, h / 2, minOf(w, h) / 2, body) else canvas.drawRoundRect(0f, 0f, w, h, context.dp(14).toFloat(), context.dp(14).toFloat(), body)
            if (glyph != null) {
                val size = (minOf(w, h) * if (direction) 0.5f else 0.56f).toInt()
                glyph.tint(if (down) Ink.GREEN else if (spot.id == "heart") 0xFFFF8FA3.toInt() else Ink.TEXT)
                glyph.setBounds(((w - size) / 2).toInt(), ((h - size) / 2).toInt(), ((w + size) / 2).toInt(), ((h + size) / 2).toInt())
                glyph.draw(canvas)
            } else {
                ink.color = color; ink.textSize = minOf(w, h) * if (spot.round) 0.38f else 0.4f
                canvas.drawText(spot.label!!, w / 2, h / 2 - (ink.descent() + ink.ascent()) / 2, ink)
            }
        }
    }
}
