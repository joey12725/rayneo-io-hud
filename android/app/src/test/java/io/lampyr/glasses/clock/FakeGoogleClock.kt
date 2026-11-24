package io.lampyr.glasses.clock

/** A fake accessibility node. */
class FakeNode(
    override val text: String? = null,
    override val desc: String? = null,
    override val className: String? = "android.widget.TextView",
    override val viewId: String? = null,
    override val packageName: String? = PhoneAlarmAutomation.CLOCK_PACKAGE,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val clickable: Boolean = false,
    override val scrollable: Boolean = false,
    override val collection: Boolean = false,
    override val children: List<UiNode> = emptyList(),
    private val onClick: () -> Boolean = { false },
    private val onForward: () -> Boolean = { false },
    private val onBackward: () -> Boolean = { false },
) : UiNode {
    override fun click() = onClick()
    override fun scrollForward() = onForward()
    override fun scrollBackward() = onBackward()
}

/**
 * Google Clock's Alarms tab as an accessibility tree, plus the phone around it. [style] "views" mimics the
 * classic RecyclerView layout (view ids, "7:00 AM" in one TextView); "compose" has no ids, AM/PM in its own
 * node, an expanded first row (day chips, Vibrate, Delete, "Add label") and an "Alarm in 8 hours" line.
 */
class FakeGoogleClock(
    val alarms: MutableList<PhoneAlarm>,
    var style: String = "views",
    var viewport: Int = 4,
) : PhoneAlarmHost {
    var now = 0L
    var foregroundPkg: String? = "io.lampyr.glasses"
    var locked = false
    var clockOpens = true
    var ignoreClicks = false
    /** Clock shows a click only after this long (verification must poll). */
    var clickLagMs = 0L
    var offset = 0
    /** The tabs sit in a horizontally scrollable pager around the list (as in classic DeskClock). */
    var pager = false
    /** Tab showing: "Alarm", or e.g. "Timer" (its own FAB, no rows). */
    var tab = "Alarm"
    /** Clock first shows [tab] for this long after opening, then switches to Alarm (SHOW_ALARMS arriving late). */
    var alarmTabAfterMs = 0L
    var switchClickable = true
    /** Taps on a row itself (which in Google Clock expands or edits it). */
    var rowTaps = 0
    val returned = mutableListOf<String?>()
    var opens = 0
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()

    override fun blocker() = if (locked) "Phone is locked; unlock it to use phone alarms" else null
    override fun foreground() = foregroundPkg
    override fun openAlarms(): Boolean {
        opens++
        if (clockOpens) foregroundPkg = PhoneAlarmAutomation.CLOCK_PACKAGE
        if (alarmTabAfterMs > 0) pending += (now + alarmTabAfterMs) to { tab = "Alarm" } else tab = "Alarm"
        return true
    }
    override fun returnTo(previous: String?) { returned += previous; foregroundPkg = previous }
    override suspend fun pause(ms: Long) { now += ms; pending.filter { it.first <= now }.forEach { pending -= it; it.second() } }
    override fun elapsedMs() = now

    override fun clockRoot(): UiNode? {
        if (foregroundPkg != PhoneAlarmAutomation.CLOCK_PACKAGE) return null
        val tabs = FakeNode(className = "android.view.ViewGroup", children = listOf("Alarm", "Clock", "Timer", "Stopwatch", "Bedtime").map { FakeNode(text = it, clickable = true) })
        if (tab != "Alarm") return FakeNode(className = "android.widget.FrameLayout", children = listOf(tabs,
            FakeNode(text = "00h 00m 00s"),
            FakeNode(desc = "Start", className = "android.widget.ImageButton", clickable = true, viewId = if (style == "views") "com.google.android.deskclock:id/fab" else null)))
        val visible = alarms.indices.drop(offset).take(viewport)
        val list = FakeNode(className = if (style == "views") "androidx.recyclerview.widget.RecyclerView" else "android.view.View",
            scrollable = alarms.size > viewport, collection = true,
            children = visible.map { row(it) },
            onForward = { if (offset + viewport < alarms.size) { offset = minOf(offset + viewport - 1, alarms.size - viewport); true } else false },
            onBackward = { if (offset > 0) { offset = maxOf(0, offset - viewport); true } else false })
        val page = if (!pager) list else FakeNode(className = "androidx.viewpager.widget.ViewPager", scrollable = true, children = listOf(list),
            onForward = { tab = "Clock"; true }, onBackward = { false })
        return FakeNode(className = "android.widget.FrameLayout", children = listOf(
            tabs,
            page,
            FakeNode(desc = "Add alarm", className = "android.widget.ImageButton", clickable = true, viewId = if (style == "views") "com.google.android.deskclock:id/fab" else null),
        ))
    }

    private fun toggle(i: Int): Boolean {
        if (ignoreClicks) return true
        val flip = { alarms[i] = alarms[i].copy(enabled = !alarms[i].enabled) }
        if (clickLagMs > 0) pending += (now + clickLagMs) to flip else flip()
        return true
    }

    private fun row(i: Int): UiNode {
        val a = alarms[i]
        val h12 = if (a.hour % 12 == 0) 12 else a.hour % 12
        val hm = "$h12:${a.minute.toString().padStart(2, '0')}"
        val ap = if (a.hour < 12) "AM" else "PM"
        val id = { s: String -> if (style == "views") "com.google.android.deskclock:id/$s" else null }
        val switch = FakeNode(className = if (style == "views") "androidx.appcompat.widget.SwitchCompat" else "android.widget.Switch",
            viewId = id("onoff"), checkable = true, checked = a.enabled, clickable = switchClickable, onClick = { switchClickable && toggle(i) })
        val kids = mutableListOf<UiNode>()
        if (style == "views") {
            if (a.label.isNotBlank()) kids += FakeNode(text = a.label, viewId = id("label"))
            kids += FakeNode(text = "$hm $ap", viewId = id("digital_clock"))
            kids += FakeNode(text = a.days, viewId = id("days_of_week"))
            kids += switch
            kids += FakeNode(desc = "Expand alarm", className = "android.widget.ImageButton", clickable = true)
        } else {
            val expanded = i == 0
            kids += FakeNode(className = "android.view.View", children = listOf(FakeNode(text = hm), FakeNode(text = ap)))
            kids += switch
            if (a.enabled) kids += FakeNode(text = "Alarm in 8 hours")
            if (expanded) {
                kids += FakeNode(text = a.label.ifBlank { "Add label" }, clickable = true)
                kids += listOf("S", "M", "T", "W", "T", "F", "S").mapIndexed { d, s ->
                    FakeNode(text = s, desc = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[d],
                        className = "android.widget.ToggleButton", checkable = true, checked = d in 1..5, clickable = true)
                }
                kids += FakeNode(text = a.days)
                kids += FakeNode(text = "Default (Cesium)")
                kids += FakeNode(text = "Vibrate", className = "android.widget.CheckBox", checkable = true, checked = true)
                kids += FakeNode(text = "Delete", clickable = true)
            } else {
                if (a.label.isNotBlank()) kids += FakeNode(text = a.label)
                kids += FakeNode(text = a.days)
            }
        }
        return FakeNode(className = "android.view.ViewGroup", children = kids, clickable = true, onClick = { rowTaps++; true })
    }
}
