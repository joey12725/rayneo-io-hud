package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.data.ActionResult
import io.lampyr.glasses.glasses.StockSettings
import io.lampyr.glasses.glasses.protocol.Commands
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

// Display power, head gestures and display position settings (requirements 1.1, 15.1, 15.2).

/** The stock text page is ASCII only: degrees become "deg" there (the bitmap font has the degree sign). */
internal fun Row.asciiText() = copy(sub = sub?.replace("°", " deg"), right = right.replace("°", " deg"))

private fun onOff(value: Boolean) = if (value) "On" else "Off"
private fun <T> nextOf(options: List<T>, value: T): T = options[(options.indexOf(value) + 1).mod(options.size)]

internal fun headGestureSummary(ui: Ui): String {
    val s = ui.glassesSettings.current ?: return "Glasses not connected"
    val look = if (!s.wakeKnown) "Look-up wake ?" else if (s.headUp) "Look-up wake ${s.headUpDegrees}°" else "Look-up wake off"
    return look + if (s.gesturesKnown && s.headGestures) ", nod/shake on" else ""
}

internal fun displayPositionSummary(ui: Ui): String {
    val s = ui.glassesSettings.current ?: return "Glasses not connected"
    if (!s.displayKnown) return "Not reported by the glasses"
    return StockSettings.heightLabel(s.displayHeight) + ", " + StockSettings.distanceLabel(s.displayDistance).lowercase()
}

/** Look-up is our preference; stock head-up (wakeup_config) is on for every mode but NOTHING once the glasses reported it. */
internal fun setLookUp(ui: Ui, mode: LookUp) {
    ui.powerStore.update(ui.powerStore.prefs.copy(lookUp = mode))
    val on = mode != LookUp.NOTHING
    val stock = ui.glassesSettings.current
    if (stock?.wakeKnown == true && stock.headUp != on) ui.perform { ui.glassesSettings.update { it.copy(headUp = on) } }
    ui.requestRender()
}

private val LOOK_UP_OPTIONS = LookUp.entries.toList()
private val TEXT_CASES = io.lampyr.glasses.render.TextCase.entries.toList()

/** The look-up row shared by Display & sleep and Head gestures: Y cycles On, Off, Toggle, Nothing. */
private fun lookUpRow(ui: Ui, p: PowerPrefs) = Row("Look up", icon = "glasses", right = PowerPrefs.lookUpLabel(p.lookUp), sub = PowerPrefs.lookUpSub(p.lookUp),
    selLabel = "Change", open = { setLookUp(ui, nextOf(LOOK_UP_OPTIONS, p.lookUp)) })

private fun Ui.stock(change: (StockSettings) -> StockSettings) = perform { glassesSettings.update(change) }

class PowerSettings(ui: Ui) :
    ListView(
        ui,
        "power",
        items = {
            val p = ui.powerStore.prefs
            fun set(next: PowerPrefs) { ui.powerStore.update(next); ui.requestRender() }
            listOf(
                Row("Display timeout", icon = "moon", right = PowerPrefs.idleLabel(p.idleMs), sub = "Idle time before the lens goes dark",
                    selLabel = "Change", open = { set(p.copy(idleMs = nextOf(PowerPrefs.IDLE_OPTIONS, p.idleMs))) }),
                Row("Dim first", icon = "bright", right = onOff(p.dimStep), sub = "One dim frame before black",
                    selLabel = "Toggle", open = { set(p.copy(dimStep = !p.dimStep)) }),
                Row("First press acts", icon = "check", right = onOff(p.firstPressActs),
                    sub = if (p.firstPressActs) "A press wakes and acts" else "First press only wakes",
                    selLabel = "Toggle", open = { set(p.copy(firstPressActs = !p.firstPressActs)) }),
                Row("Home after lock", icon = "home", right = PowerPrefs.lockHomeLabel(p.lockHomeMs), sub = "Locked longer than this opens Home",
                    selLabel = "Change", open = { set(p.copy(lockHomeMs = nextOf(PowerPrefs.LOCK_HOME_OPTIONS, p.lockHomeMs))) }),
                Row("Popups when locked", icon = "bell", right = onOff(p.popupWhenLocked), sub = "Important cards light a locked lens",
                    selLabel = "Toggle", open = { set(p.copy(popupWhenLocked = !p.popupWhenLocked)) }),
                lookUpRow(ui, p),
                Row("Look up when locked", icon = "glasses", right = onOff(p.lookUpWhenLocked),
                    sub = if (p.lookUpWhenLocked) "Looking up acts while locked" else "A locked headset ignores it",
                    selLabel = "Toggle", open = { set(p.copy(lookUpWhenLocked = !p.lookUpWhenLocked)) }),
                Row("Text case", icon = "docs", right = PowerPrefs.textCaseLabel(p.textCase),
                    sub = if (p.textCase == io.lampyr.glasses.render.TextCase.CAPS) "All caps text" else "Normal upper and lower case",
                    selLabel = "Change", open = { set(p.copy(textCase = nextOf(TEXT_CASES, p.textCase))) }),
                Row("Dark mode", icon = "settings", right = PowerPrefs.backendLabel(p.backend),
                    sub = if (p.sleepBackend == "park") "Park: page closed while dark (look-up wake, untested)" else "Black: page kept, black frame",
                    selLabel = "Change", open = { set(p.copy(backend = nextOf(PowerPrefs.BACKENDS, p.backend))) }),
                Row("Notification popups", icon = "bell", right = onOff(p.popups), sub = if (p.popups) "Important ones light the lens" else "List only, never pop up",
                    selLabel = "Toggle", open = { set(p.copy(popups = !p.popups)) }),
                Row("Popup time", icon = "clock", right = PowerPrefs.popupLabel(p.popupMs), sub = "How long a popup stays up",
                    selLabel = "Change", open = { set(p.copy(popupMs = nextOf(PowerPrefs.POPUP_MS_OPTIONS, p.popupMs))) }),
                Row("Memo keeps lit", icon = "mic", right = onOff(p.memoKeepAwake),
                    sub = if (p.memoKeepAwake) "Lens stays lit while a memo records" else "REC mark shows when the lens is lit",
                    selLabel = "Toggle", open = { set(p.copy(memoKeepAwake = !p.memoKeepAwake)) }),
            )
        },
        chrome = { t -> frame(ui, "Display & sleep", "moon", t) },
    ) {
    init {
        textTitle = { "Display & sleep" }
        textItems = { rows().map { it.asciiText() } }
        textHint = { "Y change B back" }
    }
}

class HeadGestureSettings(ui: Ui) :
    ListView(
        ui,
        "headgest",
        items = {
            val p = ui.powerStore.prefs
            val s = ui.glassesSettings.current
            val wake = s?.wakeKnown == true
            val gestures = s?.gesturesKnown == true
            val missing = if (s == null) "Glasses not connected" else "Not reported by the glasses"
            listOf(
                lookUpRow(ui, p),
                Row("Look-up angle", icon = "gauge", right = if (wake) "${s!!.headUpDegrees}°" else "-",
                    sub = if (wake) "Y adds ${StockSettings.DEGREE_STEP}° (${StockSettings.MIN_DEGREES} to ${StockSettings.MAX_DEGREES})" else missing,
                    selectable = wake, selLabel = "Steeper", open = { ui.stock { it.copy(headUpDegrees = StockSettings.nextDegrees(it.headUpDegrees)) } }),
                Row("Nod / shake", icon = "people", right = if (gestures) onOff(s!!.headGestures) else "-",
                    sub = if (gestures) "Answer stock cards with the head" else missing,
                    selectable = gestures, selLabel = "Toggle", open = { ui.stock { it.copy(headGestures = !it.headGestures) } }),
                Row("Confirm with", icon = "check", right = if (gestures) StockSettings.modeLabel(s!!.headGestureMode) else "-",
                    sub = if (gestures) "Which gesture means yes" else missing,
                    selectable = gestures, selLabel = "Change", open = { ui.stock { it.copy(headGestureMode = 1 - it.headGestureMode) } }),
            )
        },
        chrome = { t -> frame(ui, "Head gestures", "glasses", t) },
    ) {
    init {
        refreshHook = { ui.glassesSettings.refresh(); ActionResult() }
        textTitle = { "Head gestures" }
        textItems = { rows().map { it.asciiText() } }
        textHint = { "Y change B back" }
    }
}

class DisplayPositionSettings(ui: Ui) :
    ListView(
        ui,
        "displaypos",
        items = {
            val s = ui.glassesSettings.current
            val known = s?.displayKnown == true
            val missing = if (s == null) "Glasses not connected" else "Not reported by the glasses"
            listOf(
                Row("Height", icon = "glasses", right = if (known) StockSettings.heightLabel(s!!.displayHeight) else "-",
                    sub = if (known) "Where the image sits in view" else missing, selectable = known, selLabel = "Change",
                    open = { ui.stock { it.copy(displayHeight = nextOf(Commands.DISPLAY_HEIGHTS, it.displayHeight ?: 3)) } }),
                Row("Distance", icon = "glasses", right = if (known) StockSettings.distanceLabel(s!!.displayDistance) else "-",
                    sub = if (known) "Near or far image depth" else missing, selectable = known, selLabel = "Change",
                    open = { ui.stock { it.copy(displayDistance = nextOf(Commands.DISPLAY_DISTANCES, it.displayDistance ?: 2)) } }),
            )
        },
        chrome = { t -> frame(ui, "Display position", "glasses", t) },
    ) {
    init {
        refreshHook = { ui.glassesSettings.refresh(); ActionResult() }
        textTitle = { "Display position" }
        textItems = { rows().map { it.asciiText() } }
        textHint = { if (ui.glassesSettings.current?.displayKnown == true) "Y change B back" else "B back" }
    }
}
