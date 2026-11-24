package io.lampyr.glasses.ambient

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import android.app.AlertDialog
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmbientAndroidTest {
    @Test fun storageFailureShowsDiagnosticAndRearmsWithoutPreferenceToggle() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ambient", 0).edit().clear().putBoolean("enabled", true).commit()
        val root = File(context.filesDir, "ambient")
        root.deleteRecursively(); root.writeText("not a directory")
        val runtime = AmbientRuntime(context)
        val worker = AmbientRuntime::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(runtime) as android.os.Handler
        val looper = shadowOf(worker.looper)
        val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        try {
            looper.idle(); runtime.packet(AmbientPacket(packet,System.currentTimeMillis(),"lifelog-mono"));looper.idle()
            assertFalse(runtime.isActive());assertTrue(runtime.status.value.state.contains("retry in 1s"))
            assertTrue(root.delete());looper.idleFor(java.time.Duration.ofSeconds(1))
            assertTrue(runtime.isActive());assertTrue(runtime.status.value.state.contains("Recovered"))
            runtime.packet(AmbientPacket(packet,System.currentTimeMillis(),"lifelog-mono"));looper.idle()
            runtime.packet(AmbientPacket(packet,System.currentTimeMillis(),"lifelog-mono",speech=false));looper.idle()
            runtime.preferences.listening=false;looper.idle()
            assertEquals("skipped",runtime.conversations.all().single().state)
            assertEquals(960,runtime.conversations.all().single().samples)
        } finally { runtime.close();looper.idle();context.getSharedPreferences("ambient",0).edit().clear().commit();root.deleteRecursively() }
    }
    @Test @Config(sdk = [29]) fun locationIsBoundedToOneFixEveryFiveMinutesAndStopsOnMute() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        val location = shadowOf(manager)
        location.setProviderEnabled("gps", true)
        val fixes = mutableListOf<GeoFix>()
        val capture = AmbientLocations(context, { true }, { fixes += it })
        val main = shadowOf(android.os.Looper.getMainLooper())
        try {
            capture.start(); capture.conversationStart("synthetic")
            assertEquals(1, location.getLocationRequests("gps").size)
            main.idleFor(java.time.Duration.ofSeconds(15))
            assertTrue(location.getLocationRequests("gps").isEmpty())
            main.idleFor(java.time.Duration.ofSeconds(284))
            assertTrue(location.getLocationRequests("gps").isEmpty())
            main.idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(1, location.getLocationRequests("gps").size)
            capture.stop(); main.idleFor(java.time.Duration.ofMinutes(10))
            assertTrue(location.getLocationRequests("gps").isEmpty())
            assertTrue(fixes.isEmpty())
        } finally { capture.close() }
    }
    @Test fun legacyGapMigratesAndNewSettingsPersist() {
        val context = RuntimeEnvironment.getApplication()
        val p = AmbientPreferences(context); p.prefs.edit().clear().putInt("gap", 3).commit()
        assertEquals(10,p.conversationGapMinutes)
        p.prefs.edit().putInt("gap", 7).commit();assertEquals(7,p.conversationGapMinutes)
        for (old in listOf(-100, 0, 61, Int.MAX_VALUE)) {
            p.prefs.edit().putInt("gap", old).commit(); assertEquals(10, p.conversationGapMinutes)
        }
        for (old in listOf(1, 60)) {
            p.prefs.edit().putInt("gap", old).commit(); assertEquals(old, p.conversationGapMinutes)
        }
        p.conversationGapMinutes=12;assertFalse(p.prefs.contains("gap"))
        assertTrue(p.modelWifiOnly);assertFalse(p.uploadWifiOnly)
        p.speakerThreshold=.7f;p.uploadWifiOnly=true
        assertEquals(.7f,AmbientPreferences(context).speakerThreshold,0f)
        assertThrows(IllegalArgumentException::class.java) { p.speakerThreshold=.95f }
        p.prefs.edit().clear().commit()
    }

    @Test fun publicMicTestHandleOpensDistinctExclusionAndClosesIdempotently() {
        val field = CaptureExclusions::class.java.getDeclaredField("active").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val active = field.get(CaptureExclusions) as Map<String, Pair<String, Long>>
        val before = active.keys.toSet()
        val first = CaptureExclusions.microphoneTest()
        val second = CaptureExclusions.microphoneTest()
        try {
            assertEquals(2, (active.keys - before).size)
            assertTrue((active.keys - before).all { active[it]!!.first == "mic-test" })
            first.close(); first.close()
            assertEquals(1, (active.keys - before).size)
        } finally { first.close(); second.close() }
        assertEquals(before, active.keys)
    }

    @Test fun captureIsOnByDefaultAndSettingsPersistWithoutGatewaySecrets() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ambient", 0).edit().clear().commit()
        val preferences = AmbientPreferences(context)
        // Owner decision 2026-11-13 (AMB-13/14): passive listening starts on; hold − mutes it.
        assertTrue(preferences.enabled); assertFalse(preferences.paused); assertFalse(preferences.probeCache)
        assertEquals(30, preferences.retention); assertEquals(10, preferences.gapMinutes); assertEquals("auto", preferences.mode)
        preferences.enabled = false; preferences.paused = true; preferences.retention = 7
        val restored = AmbientPreferences(context)
        assertFalse(restored.enabled); assertTrue(restored.paused); assertEquals(7, restored.retention)
        preferences.prefs.edit().clear().commit()
    }
}
