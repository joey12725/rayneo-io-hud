package io.lampyr.glasses.settings

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingTest {
    private fun texts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    @Test fun enabledComponentListsMatchOnlyThisApp() {
        val service = "io.lampyr.glasses.input.ControllerAccessibilityService"
        assertTrue(OnboardingChecks.listed("com.other/.A:io.lampyr.glasses/$service", "io.lampyr.glasses", service))
        assertTrue(OnboardingChecks.listed("io.lampyr.glasses/.input.ControllerAccessibilityService", "io.lampyr.glasses", service))
        assertFalse(OnboardingChecks.listed("io.lampyr.glasses.other/$service", "io.lampyr.glasses", service))
        assertFalse(OnboardingChecks.listed("io.lampyr.glasses/.data.PhoneNotifications", "io.lampyr.glasses", service))
        assertTrue(OnboardingChecks.listed("io.lampyr.glasses/.data.PhoneNotifications", "io.lampyr.glasses"))
        assertFalse(OnboardingChecks.listed(null, "io.lampyr.glasses"))
        assertFalse(OnboardingChecks.listed("", "io.lampyr.glasses"))
    }

    @Test fun checklistShowsEveryStepWithItsStateOnAFreshPhone() {
        val activity = Robolectric.buildActivity(OnboardingActivity::class.java).setup().get()
        val shown = texts(activity.findViewById(android.R.id.content))
        listOf("Notifications", "Bluetooth", "Microphone", "Controller in the background", "Notifications on the lens",
            "Keep running with the screen off", "Pair the 8BitDo Micro", "Teach the app its buttons", "Leave RayNeo's own app out",
            "Pair and connect").forEach { assertTrue("missing step $it", it in shown) }
        assertTrue("Not paired" in shown)
        assertTrue("Not yet" in shown)
        assertTrue("Not installed" in shown)
        assertTrue(shown.any { Regex("[0-9]+ of 9 steps done").matches(it) })
    }

    @Test fun progressCountsOnlyWhatThePhoneCanVerify() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val keys = io.lampyr.glasses.input.KeyMap(context)
        try {
            val (done, total) = OnboardingChecks.progress(context, keys)
            assertEquals(9, total)
            assertTrue(done in 0..total)
            assertFalse(OnboardingChecks.glassesChosen(context))
            context.getSharedPreferences("glasses", 0).edit().putString("glasses.address", "AA:BB:CC:DD:EE:FF").commit()
            assertTrue(OnboardingChecks.glassesChosen(context))
            assertEquals("choosing glasses in a scan is not pairing", done, OnboardingChecks.progress(context, keys).first)
            context.getSharedPreferences("glasses", 0).edit().putBoolean(io.lampyr.glasses.glasses.GlassesTransports.LINKED, true).commit()
            assertEquals(done + 1, OnboardingChecks.progress(context, keys).first)
        } finally { keys.close() }
    }

    @Test fun checklistOpensItselfOnlyOnFirstLaunch() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        OnboardingActivity.showOnFirstRun(first)
        assertEquals(OnboardingActivity::class.java.name, shadowOf(first).nextStartedActivity?.component?.className)
        Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup().get()
        OnboardingActivity.showOnFirstRun(second)
        assertNull(shadowOf(second).nextStartedActivity)
    }
}
