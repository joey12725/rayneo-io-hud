package io.lampyr.glasses

import android.os.PowerManager
import io.lampyr.glasses.audio.AndroidJoyCallAudio
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JoyCallAudioTest {
    @Test fun focusAndWakeSurviveFallbackUntilTheWholeCallEnds() {
        var pauses = 0; var resumes = 0
        val audio = AndroidJoyCallAudio(RuntimeEnvironment.getApplication(), { pauses++; { resumes++ } }, { true }, releaseOnClose = false)
        try {
            assertTrue(audio.hold {}); assertTrue(audio.hold {})
            assertEquals(1, pauses)
            val wake = ReflectionHelpers.getField<PowerManager.WakeLock>(audio, "wake")
            assertTrue(wake.isHeld)
            audio.close() // Session fell back: stream closes, but the call still owns focus/wake.
            assertTrue(wake.isHeld); assertEquals(0, resumes)
            audio.release(); assertFalse(wake.isHeld); assertEquals(1, resumes)
            audio.release(); assertEquals(1, resumes)
        } finally { audio.release() }
    }
    @Test fun cellularCallPreventsResumingMediaDuringTeardown() {
        var resumes = 0
        val audio = AndroidJoyCallAudio(RuntimeEnvironment.getApplication(), { { resumes++ } }, { false })
        assertTrue(audio.hold {})
        audio.close(); assertEquals(0, resumes)
    }
    @Test fun glassesLiveAudioDoesNotRequirePhonePermissionOrMicrophoneForegroundType() {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        var starts = 0; var stops = 0
        io.lampyr.glasses.audio.MicRegistry.glasses = object : io.lampyr.glasses.audio.MicSource {
            override val name = "Glasses"
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { starts++; return true }
            override fun stop() { stops++ }
        }
        val audio = AndroidJoyCallAudio(context, { {} }, { true })
        try {
            assertNull(io.lampyr.glasses.service.GlassesService.instance)
            assertTrue(audio.open {}); assertTrue(audio.capture {})
            val wake = ReflectionHelpers.getField<PowerManager.WakeLock>(audio, "wake")
            audio.close(); assertEquals(1, starts); assertEquals(1, stops); assertFalse(wake.isHeld)
            assertNull(ReflectionHelpers.getField<Any?>(audio, "focus"))
        } finally { audio.release(); io.lampyr.glasses.audio.MicRegistry.glasses = null }
    }
}
