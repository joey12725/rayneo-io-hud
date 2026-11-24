package io.lampyr.glasses.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*

/**
 * The phone app's design kit: one palette, one type scale and a handful of components, built from
 * plain Views so every screen (and Robolectric) can use them without resources or new libraries.
 */
object Ink {
    val BG = 0xFF0A0C0B.toInt()
    val SURFACE = 0xFF141816.toInt()
    val RAISED = 0xFF1D2421.toInt()
    val LINE = 0xFF2A332F.toInt()
    val TEXT = 0xFFEAF0EC.toInt()
    val MUTED = 0xFF98A49E.toInt()
    val FAINT = 0xFF66726C.toInt()
    /** The lens phosphor green, used sparingly for the one thing that matters on a screen. */
    val GREEN = 0xFF7DF06A.toInt()
    val GREEN_DIM = 0xFF16301A.toInt()
    val ON_GREEN = 0xFF06210A.toInt()
    val AMBER = 0xFFFFB84D.toInt()
    val AMBER_DIM = 0xFF33260E.toInt()
    val RED = 0xFFFF7068.toInt()
    val RED_DIM = 0xFF3A1715.toInt()
}

enum class Tone(val color: Int, val dim: Int) {
    GOOD(Ink.GREEN, Ink.GREEN_DIM), WARN(Ink.AMBER, Ink.AMBER_DIM), BAD(Ink.RED, Ink.RED_DIM), IDLE(Ink.MUTED, Ink.RAISED)
}
enum class Type(val size: Float, val color: Int, val bold: Boolean = false) {
    DISPLAY(30f, Ink.TEXT, true), TITLE(21f, Ink.TEXT, true), HEADING(16f, Ink.TEXT, true), BODY(15f, Ink.TEXT),
    SMALL(13.5f, Ink.MUTED), LABEL(12f, Ink.MUTED, true), MONO(12.5f, Ink.MUTED)
}
enum class Btn { PRIMARY, TONAL, GHOST, DANGER }

fun Context.dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(color: Int, radius: Number, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
}
/** A filled, rounded surface that ripples when touched. */
fun Context.pressable(color: Int, radius: Number, stroke: Int? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(color, radius, stroke), shape(Color.WHITE, radius))

fun Context.text(value: CharSequence, type: Type = Type.BODY, color: Int = type.color): TextView = TextView(this).apply {
    text = value; textSize = type.size; setTextColor(color); includeFontPadding = false
    typeface = when { type == Type.MONO -> Typeface.MONOSPACE; type.bold -> Typeface.create("sans-serif-medium", Typeface.BOLD); else -> Typeface.SANS_SERIF }
    setLineSpacing(0f, if (type == Type.BODY || type == Type.SMALL) 1.22f else 1.1f)
    if (type == Type.LABEL) { isAllCaps = true; letterSpacing = 0.09f }
}

fun Context.column(gap: Number = 0, build: Stack.() -> Unit = {}): Stack = Stack(this, true, dp(gap)).apply(build)
fun Context.row(gap: Number = 0, build: Stack.() -> Unit = {}): Stack = Stack(this, false, dp(gap)).apply { gravity = Gravity.CENTER_VERTICAL }.apply(build)

/** A LinearLayout that spaces its children evenly, so screens never hand-tune margins. */
class Stack(context: Context, vertical: Boolean, private val gap: Int) : LinearLayout(context) {
    init { orientation = if (vertical) VERTICAL else HORIZONTAL }
    private val vertical get() = orientation == VERTICAL
    /** [weight] > 0 shares the main axis; [wrap] keeps the child at its own size on the cross axis. */
    fun <T : View> add(view: T, weight: Float = 0f, wrap: Boolean = false, space: Int = gap): T {
        val params = if (vertical) LayoutParams(if (wrap) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT, if (weight > 0) 0 else LayoutParams.WRAP_CONTENT, weight)
            else LayoutParams(if (weight > 0) 0 else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, weight)
        if (childCount > 0) { if (vertical) params.topMargin = space else params.marginStart = space }
        addView(view, params); return view
    }
    fun spacer() = add(View(context), 1f)
}

/** A rounded surface that groups related rows. */
fun Context.card(gap: Number = 12, color: Int = Ink.SURFACE, build: Stack.() -> Unit = {}): Stack =
    column(gap).apply { background = shape(color, 22); setPadding(dp(18), dp(18), dp(18), dp(18)); build() }

fun Context.button(label: CharSequence, style: Btn = Btn.TONAL, icon: String? = null, action: () -> Unit): TextView {
    val (fill, ink) = when (style) {
        Btn.PRIMARY -> Ink.GREEN to Ink.ON_GREEN; Btn.TONAL -> Ink.RAISED to Ink.TEXT
        Btn.GHOST -> Color.TRANSPARENT to Ink.GREEN; Btn.DANGER -> Ink.RED_DIM to Ink.RED
    }
    return text(label, Type.HEADING, ink).apply {
        textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(50); setPadding(dp(18), dp(10), dp(18), dp(10))
        background = pressable(fill, 16, if (style == Btn.GHOST) Ink.LINE else null)
        if (icon != null) { setCompoundDrawablesRelative(Glyph(icon, ink, dp(20)), null, null, null); compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL }
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
}

fun View.dp(value: Number): Int = context.dp(value)

/** Dims a control and stops it responding, without changing its layout. */
fun View.available(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f }

/** A small coloured state marker: a dot and a word. */
class Pill(context: Context) : TextView(context) {
    init {
        textSize = 12.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); includeFontPadding = false
        setPadding(context.dp(10), context.dp(6), context.dp(12), context.dp(6)); compoundDrawablePadding = context.dp(6); maxLines = 1
    }
    fun show(label: CharSequence, tone: Tone) {
        text = label; setTextColor(tone.color); background = context.shape(tone.dim, 99)
        setCompoundDrawablesRelative(Dot(tone.color, context.dp(8)), null, null, null)
    }
}
fun Context.pill(label: CharSequence, tone: Tone) = Pill(this).apply { show(label, tone) }

private class Dot(private val color: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setBounds(0, 0, size, size) }
    override fun draw(canvas: Canvas) { paint.color = color; canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), size / 2f, paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}

fun Context.icon(kind: String, color: Int = Ink.MUTED, size: Number = 24): ImageView =
    ImageView(this).apply { setImageDrawable(Glyph(kind, color, dp(size))) }

/** An icon in a soft rounded tile; the leading element of list rows. */
fun Context.badge(kind: String, tone: Tone = Tone.IDLE): ImageView = ImageView(this).apply {
    setImageDrawable(Glyph(kind, if (tone == Tone.IDLE) Ink.TEXT else tone.color, dp(22)))
    background = shape(tone.dim, 14); scaleType = ImageView.ScaleType.CENTER
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
}

/** Icon, title, supporting line and a trailing chevron or value. The standard way into another screen. */
class NavRow(context: Context, kind: String, title: CharSequence, detail: CharSequence?, action: () -> Unit) : LinearLayout(context) {
    private val badge = context.badge(kind)
    private val kind = kind
    val title = context.text(title, Type.HEADING)
    val detail = context.text(detail ?: "", Type.SMALL)
    val trailing = context.text("", Type.SMALL)
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = context.pressable(Color.TRANSPARENT, 14); isClickable = true; isFocusable = true
        setPadding(0, context.dp(6), 0, context.dp(6)); minimumHeight = context.dp(56)
        addView(badge)
        addView(context.column(3) { add(this@NavRow.title); if (detail != null) add(this@NavRow.detail) },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(14); marginEnd = context.dp(8) })
        addView(trailing)
        addView(context.icon("chevron", Ink.FAINT, 20), LayoutParams(context.dp(20), context.dp(20)).apply { marginStart = context.dp(4) })
        setOnClickListener { action() }
    }
    fun tone(tone: Tone) {
        badge.setImageDrawable(Glyph(kind, if (tone == Tone.IDLE) Ink.TEXT else tone.color, context.dp(22))); badge.background = context.shape(tone.dim, 14)
    }
}
fun Context.navRow(kind: String, title: CharSequence, detail: CharSequence? = null, action: () -> Unit) = NavRow(this, kind, title, detail, action)

/** Title and explanation on the left, a switch on the right; the whole row toggles. */
class ToggleRow(context: Context, title: CharSequence, detail: CharSequence?, checked: Boolean, private val changed: (Boolean) -> Unit) : LinearLayout(context) {
    val switch = Switch(context)
    private var silent = false
    var isChecked: Boolean
        get() = switch.isChecked
        /** Programmatic updates do not call back. */
        set(value) { if (switch.isChecked != value) { silent = true; switch.isChecked = value; silent = false } }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = context.dp(52)
        background = context.pressable(Color.TRANSPARENT, 14)
        addView(context.column(3) { add(context.text(title, Type.BODY)); if (detail != null) add(context.text(detail, Type.SMALL)) },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(12) })
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        switch.thumbTintList = ColorStateList(states, intArrayOf(Ink.GREEN, Ink.MUTED))
        switch.trackTintList = ColorStateList(states, intArrayOf(0xFF2C5A2E.toInt(), Ink.LINE))
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, value -> if (!silent) changed(value) }
        addView(switch)
        setOnClickListener { switch.toggle() }
    }
}
fun Context.toggle(title: CharSequence, detail: CharSequence? = null, checked: Boolean, changed: (Boolean) -> Unit) = ToggleRow(this, title, detail, checked, changed)

/** A label and a value on one line, for read-only facts. */
class FactRow(context: Context, label: CharSequence) : LinearLayout(context) {
    val value = context.text("", Type.BODY).apply { gravity = Gravity.END }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(context.text(label, Type.SMALL), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(16) })
        addView(value, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    fun show(text: CharSequence, color: Int = Ink.TEXT) { value.text = text; value.setTextColor(color) }
}
fun Context.fact(label: CharSequence, value: CharSequence = "") = FactRow(this, label).apply { show(value) }

/** A captioned text box. */
class Field(context: Context, label: CharSequence, value: String, secret: Boolean, lines: Int, number: Boolean, hint: String?) : LinearLayout(context) {
    val input = EditText(context)
    var value: String
        get() = input.text.toString()
        set(text) { input.setText(text) }
    init {
        orientation = VERTICAL
        addView(context.text(label, Type.SMALL))
        input.apply {
            setText(value); textSize = 15f; setTextColor(Ink.TEXT); setHintTextColor(Ink.FAINT); this.hint = hint
            background = context.shape(Ink.RAISED, 14, Ink.LINE); setPadding(context.dp(14), context.dp(13), context.dp(14), context.dp(13))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START }
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(7) })
    }
}
fun Context.field(label: CharSequence, value: String = "", secret: Boolean = false, lines: Int = 1, number: Boolean = false, hint: String? = null) =
    Field(this, label, value, secret, lines, number, hint)

/** Two to four short options side by side; exactly one is selected. */
class Segments(context: Context, private val options: List<String>, selected: Int, private val changed: (Int) -> Unit) : LinearLayout(context) {
    var selected = selected
        set(value) { field = value; paint() }
    private val cells = options.mapIndexed { index, label ->
        context.text(label, Type.HEADING).apply {
            textSize = 13.5f; gravity = Gravity.CENTER; minHeight = context.dp(40); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(6), 0, context.dp(6), 0)
            setOnClickListener { if (this@Segments.selected != index) { this@Segments.selected = index; changed(index) } }
        }
    }
    init {
        orientation = HORIZONTAL; background = context.shape(Ink.RAISED, 14); setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        cells.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        paint()
    }
    private fun paint() = cells.forEachIndexed { index, cell ->
        val on = index == selected
        cell.background = if (on) context.shape(Ink.GREEN, 11) else context.pressable(Color.TRANSPARENT, 11)
        cell.setTextColor(if (on) Ink.ON_GREEN else Ink.MUTED)
    }
}
fun Context.segments(options: List<String>, selected: Int, changed: (Int) -> Unit) = Segments(this, options, selected.coerceIn(0, options.lastIndex), changed)

/** A captioned choice: segments when the options are few and short, otherwise a row that opens a list. */
fun Context.choice(label: CharSequence, options: List<String>, selected: Int, changed: (Int) -> Unit): View = column(8) {
    add(context.text(label, Type.SMALL))
    if (options.size <= 4 && options.all { it.length <= 12 }) add(context.segments(options, selected, changed))
    else {
        var current = selected.coerceIn(0, options.lastIndex)
        lateinit var value: TextView
        val row = context.row {
            background = context.pressable(Ink.RAISED, 14, Ink.LINE); setPadding(context.dp(14), context.dp(13), context.dp(10), context.dp(13))
            value = add(context.text(options[current]), 1f)
            add(context.icon("chevron", Ink.FAINT, 20))
        }
        row.setOnClickListener {
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(label)
                .setSingleChoiceItems(options.toTypedArray(), current) { dialog, index -> current = index; value.text = options[index]; changed(index); dialog.dismiss() }.show()
        }
        add(row)
    }
}

/** A thin progress or level bar; [fraction] is 0..1. */
class Meter(context: Context, private val tone: Tone = Tone.GOOD) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    init { minimumHeight = context.dp(8) }
    override fun onMeasure(width: Int, height: Int) = setMeasuredDimension(getDefaultSize(0, width), context.dp(8))
    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = Ink.RAISED; rect.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) { paint.color = tone.color; rect.right = maxOf(height.toFloat(), width * fraction); canvas.drawRoundRect(rect, r, r, paint) }
    }
}

/** A hairline between rows. Measures itself, so it stays a hairline whatever layout it is put in. */
class Divider(context: Context) : View(context) {
    init { setBackgroundColor(Ink.LINE) }
    override fun onMeasure(width: Int, height: Int) = setMeasuredDimension(getDefaultSize(0, width), maxOf(1, context.dp(1) / 2 + 1))
}
fun Context.divider(): View = Divider(this)

/** Short status feedback inside a card; hidden while empty. */
class Note(context: Context) : TextView(context) {
    init {
        textSize = 13.5f; includeFontPadding = false; setLineSpacing(0f, 1.2f)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12)); visibility = GONE
    }
    fun show(message: CharSequence?, tone: Tone = Tone.IDLE) {
        text = message ?: ""; visibility = if (message.isNullOrBlank()) GONE else VISIBLE
        setTextColor(if (tone == Tone.IDLE) Ink.TEXT else tone.color); background = context.shape(tone.dim, 14)
    }
    /** Plain `text = …` assignments (older screens) still reveal or hide the note. */
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        visibility = if (text.isNullOrBlank()) GONE else VISIBLE
        if (background == null) { background = context.shape(Tone.IDLE.dim, 14); setTextColor(Ink.TEXT) }
    }
}
fun Context.note() = Note(this)

/** Dark system bars and a window that draws behind them, with the content padded clear. */
fun Activity.frameWindow(root: View) {
    window.statusBarColor = Ink.BG; window.navigationBarColor = Ink.BG
    window.decorView.setBackgroundColor(Ink.BG)
    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
    }
}

/**
 * The standard secondary screen: a back arrow, a title, an optional one-line purpose, then scrolling
 * content. Returns the content column so callers can keep adding to it.
 */
fun Activity.screen(title: CharSequence, purpose: CharSequence? = null, build: Stack.() -> Unit): Stack {
    val content = column(14) { setPadding(dp(18), dp(4), dp(18), dp(32)) }
    val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content, ViewGroup.LayoutParams(-1, -2)) }
    val bar = row {
        setPadding(dp(8), dp(6), dp(18), dp(6))
        val back = icon("back", Ink.TEXT, 24).apply {
            background = pressable(Color.TRANSPARENT, 99); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back"
            setOnClickListener { finish() }
        }
        addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    val root = column { add(bar); add(scroll, 1f) }
    content.add(text(title, Type.DISPLAY))
    if (purpose != null) content.add(text(purpose, Type.SMALL).apply { textSize = 15f }, space = dp(8))
    content.build()
    frameWindow(root)
    setContentView(root)
    return content
}

/** A small caps heading that introduces a group of cards. */
fun Stack.section(title: CharSequence) = add(context.text(title, Type.LABEL).apply { setPadding(context.dp(4), 0, 0, 0) }, space = context.dp(26))
