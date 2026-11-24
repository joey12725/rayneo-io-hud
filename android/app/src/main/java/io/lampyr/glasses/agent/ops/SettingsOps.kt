package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.glasses.StockSettings
import io.lampyr.glasses.glasses.protocol.Commands
import io.lampyr.glasses.ui.PowerPrefs
import io.lampyr.glasses.ui.screens.appEntries
import io.lampyr.glasses.ui.LookUp
import io.lampyr.glasses.render.TextCase
import io.lampyr.glasses.ui.screens.setLookUp
import kotlinx.serialization.json.*

/**
 * Settings app ops: display power and sleep, popups on a locked lens, Home after lock, look-up wake and head
 * gestures, display position. They change the same preferences and glasses settings as Settings on the glasses.
 */
object SettingsOps : OpProvider {
    private val HEIGHTS = mapOf("high" to 1, "middle" to 3, "low" to 5)
    private val DISTANCES = mapOf("near" to 1, "far" to 2)

    private fun stockJson(s: StockSettings?) = buildJsonObject {
        put("connected", s != null)
        if (s == null) return@buildJsonObject
        if (s.wakeKnown) { put("look_up_wake_on_glasses", s.headUp); put("look_up_angle", s.headUpDegrees) }
        if (s.gesturesKnown) { put("nod_shake", s.headGestures); put("confirm_with", StockSettings.modeLabel(s.headGestureMode).lowercase()) }
        if (s.displayKnown) {
            put("display_height", StockSettings.heightLabel(s.displayHeight).lowercase())
            put("display_distance", StockSettings.distanceLabel(s.displayDistance).lowercase())
        }
    }

    override fun ops() = listOf(
        OpSpec(
            "settings.read", "Read glasses settings",
            "Display & sleep (sleep after, dim, first press, Home after lock, popups when locked, look-up wake, dark mode), head gestures, display position, brightness and app order.",
            risk = Risk.READ,
        ) {
            val p = ui.powerStore.prefs
            ok(data = buildJsonObject {
                put("sleep_after_s", p.idleMs / 1000.0)
                put("dim_first", p.dimStep)
                put("first_press_acts", p.firstPressActs)
                put("home_after_lock_s", p.lockHomeMs / 1000.0)
                put("popups_when_locked", p.popupWhenLocked)
                put("wake_on_look_up", p.lookUpWake)
                put("look_up", p.lookUp.name)
                put("look_up_when_locked", p.lookUpWhenLocked)
                put("text_case", p.textCase.name)
                put("popups", p.popups)
                put("popup_s", p.popupMs / 1000.0)
                put("memo_keeps_lit", p.memoKeepAwake)
                put("joy_call_layout", p.joyCallLayout)
                put("dark_mode", p.backend)
                put("glasses", stockJson(ui.glassesSettings.current))
                put("brightness", ui.data.settings.snapshot.glassesBrightness?.let(::JsonPrimitive) ?: JsonNull)
                put("app_order", JsonArray(appEntries(ui).map { JsonPrimitive(it.id) }))
            })
        },

        OpSpec(
            "settings.set_display_power", "Change display & sleep",
            "Set any of: sleep_after_s (display timeout, 0 = never), dim_first, first_press_acts, home_after_lock_s (0 = off), popups, popup_s, popups_when_locked, look_up (ON/OFF/TOGGLE/NOTHING), look_up_when_locked, text_case, memo_keeps_lit, joy_call_layout, dark_mode.",
            params = listOf(
                Param.integer("sleep_after_s", "Idle seconds before the lens goes dark: 0 (never), 5, 10, 12, 15, 20, 30 or 60", min = 0, max = 60),
                Param.boolean("dim_first", "One dim frame before black"),
                Param.boolean("first_press_acts", "A press that wakes the lens also acts"),
                Param.integer("home_after_lock_s", "Unlocking after this long opens Home: 0 (off), 30, 60 or 300", min = 0, max = 300),
                Param.boolean("popups_when_locked", "Important popups light a locked lens"),
                Param.boolean("wake_on_look_up", "Old switch: true = look_up ON, false = NOTHING"),
                Param.string("look_up", "What looking up does", enum = LookUp.entries.map { it.name }),
                Param.boolean("look_up_when_locked", "Looking up also acts while the glasses are locked"),
                Param.string("text_case", "Glasses text: CAPS (all caps) or NORMAL", enum = TextCase.entries.map { it.name }),
                Param.boolean("popups", "Important notifications pop up and light the lens"),
                Param.integer("popup_s", "Seconds a popup stays up: 4, 6, 8, 10 or 15", min = 4, max = 15),
                Param.boolean("memo_keeps_lit", "A recording memo keeps the lens lit"),
                Param.string("joy_call_layout", "Assistant voice call screen", enum = PowerPrefs.JOY_CALL_LAYOUTS),
                Param.string("dark_mode", "How the lens goes dark", enum = PowerPrefs.BACKENDS),
            ),
            risk = Risk.ACT,
        ) {
            var p = ui.powerStore.prefs
            val changes = mutableListOf<String>()
            intOrNull("sleep_after_s")?.let { s ->
                val ms = s * 1000L
                if (ms !in PowerPrefs.IDLE_OPTIONS) fail(Codes.INVALID_ARGS, "sleep_after_s must be one of ${PowerPrefs.IDLE_OPTIONS.map { it / 1000 }}")
                p = p.copy(idleMs = ms); changes += "sleep after ${PowerPrefs.idleLabel(ms)}"
            }
            intOrNull("home_after_lock_s")?.let { s ->
                val ms = s * 1000L
                if (ms !in PowerPrefs.LOCK_HOME_OPTIONS) fail(Codes.INVALID_ARGS, "home_after_lock_s must be one of ${PowerPrefs.LOCK_HOME_OPTIONS.map { it / 1000 }}")
                p = p.copy(lockHomeMs = ms); changes += "Home after lock ${PowerPrefs.lockHomeLabel(ms)}"
            }
            if (has("dim_first")) { p = p.copy(dimStep = bool("dim_first")); changes += "dim first ${onOff(p.dimStep)}" }
            if (has("first_press_acts")) { p = p.copy(firstPressActs = bool("first_press_acts")); changes += "first press acts ${onOff(p.firstPressActs)}" }
            if (has("popups_when_locked")) { p = p.copy(popupWhenLocked = bool("popups_when_locked")); changes += "popups when locked ${onOff(p.popupWhenLocked)}" }
            strOrNull("dark_mode")?.let { p = p.copy(backend = it); changes += "dark mode ${PowerPrefs.backendLabel(it)}" }
            if (has("look_up_when_locked")) { p = p.copy(lookUpWhenLocked = bool("look_up_when_locked")); changes += "look up when locked ${onOff(p.lookUpWhenLocked)}" }
            strOrNull("text_case")?.let { p = p.copy(textCase = TextCase.valueOf(it)); changes += "text ${PowerPrefs.textCaseLabel(p.textCase)}" }
            if (has("popups")) { p = p.copy(popups = bool("popups")); changes += "popups ${onOff(p.popups)}" }
            intOrNull("popup_s")?.let { s ->
                val ms = s * 1000L
                if (ms !in PowerPrefs.POPUP_MS_OPTIONS) fail(Codes.INVALID_ARGS, "popup_s must be one of ${PowerPrefs.POPUP_MS_OPTIONS.map { it / 1000 }}")
                p = p.copy(popupMs = ms); changes += "popups stay ${PowerPrefs.popupLabel(ms)}"
            }
            if (has("memo_keeps_lit")) { p = p.copy(memoKeepAwake = bool("memo_keeps_lit")); changes += "memo keeps lit ${onOff(p.memoKeepAwake)}" }
            strOrNull("joy_call_layout")?.let { p = p.copy(joyCallLayout = it); changes += "Assistant call layout $it" }
            val lookUp = strOrNull("look_up")?.let { LookUp.valueOf(it) }
                ?: if (has("wake_on_look_up")) (if (bool("wake_on_look_up")) LookUp.ON else LookUp.NOTHING) else null
            if (changes.isEmpty() && lookUp == null) fail(Codes.INVALID_ARGS, "Nothing to change")
            ui.powerStore.update(p)
            // Look-up wake is also mirrored into the glasses' own wake setting, exactly as the Settings row does.
            lookUp?.let { setLookUp(ui, it); changes += "look up ${PowerPrefs.lookUpLabel(it)}" }
            ui.requestRender()
            ok("Set " + changes.joinToString(", "))
        },

        OpSpec(
            "settings.set_head_gestures", "Change head gestures",
            "Set the glasses' look-up wake angle (10 to 70 degrees), nod/shake answers, and which gesture confirms.",
            params = listOf(
                Param.integer("look_up_angle", "Look-up angle in degrees, 10 to 70 in steps of 5", min = StockSettings.MIN_DEGREES, max = StockSettings.MAX_DEGREES),
                Param.boolean("nod_shake", "Answer stock cards with nod/shake"),
                Param.string("confirm_with", "Which gesture means yes", enum = listOf("nod", "shake")),
            ),
            risk = Risk.ACT, timeoutMs = 15_000,
            available = { ui -> if (ui.glassesSettings.current == null) "The glasses are not connected" else null },
        ) {
            val s = ui.glassesSettings.current ?: fail(Codes.UNAVAILABLE, "The glasses are not connected")
            val angle = intOrNull("look_up_angle")
            if (angle != null && (!s.wakeKnown || angle % StockSettings.DEGREE_STEP != 0))
                fail(if (!s.wakeKnown) Codes.UNAVAILABLE else Codes.INVALID_ARGS, if (!s.wakeKnown) "The glasses did not report their look-up settings" else "look_up_angle must be a multiple of 5")
            val gestures = has("nod_shake") || has("confirm_with")
            if (gestures && !s.gesturesKnown) fail(Codes.UNAVAILABLE, "The glasses did not report their head gesture settings")
            if (angle == null && !gestures) fail(Codes.INVALID_ARGS, "Nothing to change")
            val result = ui.glassesSettings.update { cur ->
                cur.copy(
                    headUpDegrees = angle ?: cur.headUpDegrees,
                    headGestures = if (has("nod_shake")) bool("nod_shake") else cur.headGestures,
                    headGestureMode = when (strOrNull("confirm_with")) { "shake" -> 1; "nod" -> 0; else -> cur.headGestureMode },
                )
            }
            ui.requestRender()
            done(result, "Head gestures updated")
        },

        OpSpec(
            "settings.set_display_position", "Change display position",
            "Set where the image sits (height high, middle or low) and its depth (distance near or far) on the glasses.",
            params = listOf(
                Param.string("height", "Image height", enum = HEIGHTS.keys.toList()),
                Param.string("distance", "Image depth", enum = DISTANCES.keys.toList()),
            ),
            risk = Risk.ACT, timeoutMs = 15_000,
            available = { ui -> if (ui.glassesSettings.current == null) "The glasses are not connected" else null },
        ) {
            val s = ui.glassesSettings.current ?: fail(Codes.UNAVAILABLE, "The glasses are not connected")
            if (!s.displayKnown) fail(Codes.UNAVAILABLE, "The glasses did not report their display position")
            val height = strOrNull("height")?.let { HEIGHTS.getValue(it) }
            val distance = strOrNull("distance")?.let { DISTANCES.getValue(it) }
            if (height == null && distance == null) fail(Codes.INVALID_ARGS, "Nothing to change")
            check(height == null || height in Commands.DISPLAY_HEIGHTS) { "height" }
            val result = ui.glassesSettings.update { it.copy(displayHeight = height ?: it.displayHeight, displayDistance = distance ?: it.displayDistance) }
            ui.requestRender()
            done(result, "Display position updated")
        },
    )

    private fun onOff(v: Boolean) = if (v) "on" else "off"
}
