package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.input.FREE_GESTURES
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStatusShortcutsOpsTest {
    @Test fun providersAreFoundByName() {
        assertTrue(OP_PROVIDERS.contains(StatusOps)); assertTrue(OP_PROVIDERS.contains(SettingsOps)); assertTrue(OP_PROVIDERS.contains(ShortcutsOps))
    }

    @Test fun statusReadsBatteriesAndBluetoothNeedsLiveData(): Unit = ActionRegistryTest.Harness().use { h ->
        val r = h.call("status.read"); assertTrue(r.message, r.ok)
        val labels = r.data!!.jsonObject["batteries"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertTrue(labels.toString(), "Life P2" in labels && "Glasses" in labels)
        assertEquals(Codes.UNAVAILABLE, h.call("status.list_bluetooth_devices").code)
        assertEquals(Codes.INVALID_ARGS, h.call("status.set_bluetooth_device", mapOf("device" to "x", "status_screen" to "maybe")).code)
    }

    @Test fun settingsReadAndChangeDisplayPowerPopupAndLockHome(): Unit = ActionRegistryTest.Harness().use { h ->
        val read = h.call("settings.read").data!!.jsonObject
        assertEquals(h.ui.powerStore.prefs.popupWhenLocked, read["popups_when_locked"]!!.jsonPrimitive.boolean)
        assertEquals("joy", read["app_order"]!!.jsonArray.first().jsonPrimitive.content)
        val r = h.call("settings.set_display_power", mapOf("sleep_after_s" to 5, "home_after_lock_s" to 300, "popups_when_locked" to false,
            "dim_first" to true, "first_press_acts" to false, "dark_mode" to "black", "wake_on_look_up" to false))
        assertTrue(r.message, r.ok)
        val p = h.ui.powerStore.prefs
        assertEquals(5_000L, p.idleMs); assertEquals(300_000L, p.lockHomeMs); assertFalse(p.popupWhenLocked)
        assertTrue(p.dimStep); assertFalse(p.firstPressActs); assertEquals("black", p.backend); assertFalse(p.lookUpWake)
        assertFalse("look-up wake is mirrored to the glasses", h.ui.glassesSettings.current!!.headUp)
        assertEquals(Codes.INVALID_ARGS, h.call("settings.set_display_power", mapOf("sleep_after_s" to 7)).code)
        assertEquals(Codes.INVALID_ARGS, h.call("settings.set_display_power").code)
    }

    @Test fun settingsChangeHeadGesturesAndDisplayPositionOnTheGlasses(): Unit = ActionRegistryTest.Harness().use { h ->
        assertTrue(h.call("settings.set_head_gestures", mapOf("look_up_angle" to 45, "nod_shake" to true, "confirm_with" to "shake")).ok)
        val s = h.ui.glassesSettings.current!!
        assertEquals(45, s.headUpDegrees); assertTrue(s.headGestures); assertEquals(1, s.headGestureMode)
        assertEquals(Codes.INVALID_ARGS, h.call("settings.set_head_gestures", mapOf("look_up_angle" to 42)).code)
        assertTrue(h.call("settings.set_display_position", mapOf("height" to "low", "distance" to "near")).ok)
        assertEquals(5, h.ui.glassesSettings.current!!.displayHeight); assertEquals(1, h.ui.glassesSettings.current!!.displayDistance)
        val glasses = h.call("settings.read").data!!.jsonObject["glasses"]!!.jsonObject
        assertEquals("low", glasses["display_height"]!!.jsonPrimitive.content)
    }

    @Test fun shortcutsListAndAssignWithSafetyAndOverrides(): Unit = ActionRegistryTest.Harness().use { h ->
        val list = h.call("shortcuts.list").data!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(FREE_GESTURES.map { it.id }.toSet(), list.keys)
        assertEquals(listOf("joy"), list["l2.tap"]!!.jsonObject["not_in"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(h.call("shortcuts.assign", mapOf("gesture" to "Hold L2", "action" to "memo.toggle")).ok)
        assertEquals("memo.toggle", h.ui.shortcuts.assigned(FREE_GESTURES.first { it.id == "l2.hold" })?.id)
        // An unknown action is refused; an action can be named by its label.
        assertEquals(Codes.INVALID_ARGS, h.call("shortcuts.assign", mapOf("gesture" to "l2.hold", "action" to "removed.action")).code)
        assertTrue(h.call("shortcuts.assign", mapOf("gesture" to "a.hold", "action" to "Memo: start/stop")).ok)
        assertTrue(h.call("shortcuts.assign", mapOf("gesture" to "l2.hold", "action" to "none")).ok)
        assertNull(h.ui.shortcuts.assigned(FREE_GESTURES.first { it.id == "l2.hold" }))
        assertEquals(Codes.INVALID_ARGS, h.call("shortcuts.assign", mapOf("gesture" to "b.hold", "action" to "none")).code)
    }
}
