package io.lampyr.glasses

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.calls.*
import io.lampyr.glasses.data.*
import io.lampyr.glasses.input.*
import io.lampyr.glasses.service.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReviewTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private val clock get() = Shadows.shadowOf(Looper.getMainLooper())

    @Test @Config(sdk = [29, 31, 35]) fun bluetoothCaptureWorksWithoutPhoneMicPermissionOrForegroundService() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertNull(GlassesService.instance)
        var stops = 0
        MicRegistry.glasses = object : MicSource {
            override val name = "Bluetooth wearer"
            override val sampleRate = 48000
            override fun isAvailable() = true
            override fun start(onPcm: (ShortArray) -> Unit): Boolean { onPcm(ShortArray(12000) { 123 }); return true }
            override fun stop() { stops++ }
        }
        val capture = PcmCapture(context, "memo")
        try {
            assertFalse(PhoneMic(context).start {})
            assertTrue(capture.start())
            assertEquals(48000, capture.sampleRate)
            assertEquals(24044, capture.stop().size); assertEquals(1, stops)
        } finally { capture.stop(); MicRegistry.glasses = null }
    }

    @Test fun visiblePhoneArmsMicrophoneAndBackgroundRecordingsNeverDropIt() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val service = owner.get()
        try {
            assertFalse(service.microphoneReady)
            ServiceAccess.resumed(activity)
            assertTrue(service.microphoneReady)
            ServiceAccess.paused(activity)
            assertTrue(service.enableMicrophone()); assertTrue(service.microphoneReady)
            val notification = ControllerNotification(service) {}.build(service.microphoneStatus)
            assertTrue(notification.extras.getString(android.app.Notification.EXTRA_TEXT)!!.contains("armed"))
            assertFalse(notification.actions.single().isAuthenticationRequired)
        } finally { ServiceAccess.paused(activity); activity.finish(); owner.destroy() }
    }
    @Test fun phonePermissionAloneCannotStartAudioRecordWithoutArmedService() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertNull(GlassesService.instance)
        val phone = PhoneMic(context)
        assertTrue(phone.isAvailable()); assertFalse(phone.start {})
    }

    @Test fun resumedSetupRetriesLocationThroughExistingAmbientRefreshHook() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        context.getSharedPreferences("ambient", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        val service = owner.get()
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            assertFalse(service.enableAmbientLocation())
            assertTrue(service.ambientLocationStatus.contains("open the phone app"))
            activity.start().resume()
            assertTrue(service.enableAmbientLocation()); assertEquals("Location ready", service.ambientLocationStatus)
        } finally { activity.pause().stop().destroy(); owner.destroy() }
    }


    @Test fun rejectedColdStartsPostActionableRecoveryAndPlaybackMessages() {
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? { throw android.app.ForegroundServiceStartNotAllowedException("background start rejected") }
        }
        assertFalse(ServiceAccess.start(blocked))
        val notification = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(23)
        assertEquals("Tap to start the glasses service", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertNotNull(notification.contentIntent)
        val result = AbsPlayer.play(blocked, "book", obj())
        assertFalse(result.ok); assertEquals("Open the phone app to start playback", result.message)
    }

    @Test @Config(sdk = [31, 35]) fun missingBluetoothPermissionStopsRetriesUntilUserReturns() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val statuses = mutableListOf<String>()
        val profile = ControllerProfile("AA:BB:CC:DD:EE:FF", java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1, listOf(ButtonField(0, 1, 1, "y")))
        val sink = object : ControllerSink {
            override fun press(letter: String) {}
            override fun release(letter: String) {}
            override fun releaseAll() {}
            override fun confirmDown() {}
            override fun status(connected: Boolean, detail: String) { statuses += detail }
        }
        val source = GattControllerSource(context, profile, sink)
        try {
            source.start(); clock.idleFor(Duration.ofMinutes(6))
            assertEquals(1, statuses.size); assertTrue(statuses.single().startsWith("Needs user action:"))
            Shadows.shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            Shadows.shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
            source.environmentChanged()
            assertTrue(statuses.last().startsWith("Waiting"))
        } finally { source.stop() }
    }

    @Test fun autoConnectRetainsGattOnDisconnectAndReconnectsTheSameOwner() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val session = NonHidGattSession(context, { emptyList() }, { _, _ -> }, { _, _ -> })
        try {
            session.connect("AA:BB:CC:DD:EE:FF", true)
            val gatt = ReflectionHelpers.getField<BluetoothGatt>(session, "gatt")
            assertNotNull(gatt)
            val callback = ReflectionHelpers.getField<BluetoothGattCallback>(session, "callback")
            callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTED); clock.idle()
            assertSame(gatt, ReflectionHelpers.getField<BluetoothGatt>(session, "gatt"))
            session.connect("AA:BB:CC:DD:EE:FF", true)
            assertSame(gatt, ReflectionHelpers.getField<BluetoothGatt>(session, "gatt"))
            callback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED); clock.idle()
            assertNull(ReflectionHelpers.getField<BluetoothGatt?>(session, "gatt"))
            session.connect("AA:BB:CC:DD:EE:FF", true)
            val replacement = ReflectionHelpers.getField<BluetoothGatt>(session, "gatt")
            assertNotSame(gatt, replacement)
            // A late callback from the closed owner cannot invalidate its replacement.
            callback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED); clock.idle()
            assertSame(replacement, ReflectionHelpers.getField<BluetoothGatt>(session, "gatt"))
        } finally { session.close() }
    }

    @Test fun palmCannotExitPocketButVolumeExitShowsConfirmation() {
        val owner = Robolectric.buildActivity(PocketActivity::class.java).create().resume()
        val activity = owner.get()
        val view = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        try {
            val touch = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            view.dispatchTouchEvent(touch); touch.recycle()
            clock.idleFor(Duration.ofSeconds(3)); assertFalse(activity.isFinishing)
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
            assertTrue(activity.isFinishing); assertEquals("Pocket mode off", ShadowToast.getTextOfLatestToast())
            val notice = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(24)
            assertEquals("Pocket mode off", notice.extras.getString(android.app.Notification.EXTRA_TITLE))
        } finally { owner.pause().destroy() }
    }

    @Test fun pocketExitNeedsThreeFingersHeldContinuouslyForTwoSeconds() {
        val owner = Robolectric.buildActivity(PocketActivity::class.java).create().start().resume().visible()
        val activity = owner.get()
        val view = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        fun touch(count: Int, action: Int) {
            val properties = Array(count) { i -> android.view.MotionEvent.PointerProperties().apply { id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val points = Array(count) { i -> android.view.MotionEvent.PointerCoords().apply { x = 20f + 10 * i; y = 20f; pressure = 1f; size = 1f } }
            val event = android.view.MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, count, properties, points, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.dispatchTouchEvent(event); event.recycle()
        }
        try {
            touch(1, android.view.MotionEvent.ACTION_DOWN)
            touch(2, android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8))
            clock.idleFor(Duration.ofSeconds(3)); assertFalse(activity.isFinishing)
            touch(3, android.view.MotionEvent.ACTION_POINTER_DOWN or (2 shl 8))
            clock.idleFor(Duration.ofMillis(1999)); assertFalse(activity.isFinishing)
            touch(3, android.view.MotionEvent.ACTION_POINTER_UP or (2 shl 8))
            clock.idleFor(Duration.ofSeconds(1)); assertFalse(activity.isFinishing)
            touch(3, android.view.MotionEvent.ACTION_POINTER_DOWN or (2 shl 8))
            clock.idleFor(Duration.ofSeconds(2)); assertTrue(activity.isFinishing)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun mappingEditsKeepHeldLayerAndOnlySourceChangesReleaseIt() {
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        val service = owner.get()
        try {
            service.input.press("r2")
            KeyMap(context).apply { bind(200, "y"); close() }; clock.idle()
            assertTrue(service.input.hasKeysDown); assertTrue(service.ui.mediaLayer)
            context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().putString("source", "gatt").commit(); clock.idle()
            assertFalse(service.input.hasKeysDown); assertFalse(service.ui.mediaLayer)
        } finally { owner.destroy() }
    }

    @Test fun dictationDeadlineSendsRecordingAndRetainsWakeUntilTerminalEvent() {
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        val service = owner.get(); service.ui.locked = true
        val runtime = service.controller!!
        var cancel = 0; var stops = 0; var late: ((DictationEvent) -> Unit)? = null
        val events = mutableListOf<DictationEvent>()
        val audio = runtime.dictation(object : Dictation {
            override fun start(target: String, onEvent: (DictationEvent) -> Unit) { late = onEvent }
            override fun stop() { stops++ }
            override fun cancel() { cancel++ }
        })
        try {
            audio.start("joy", events::add)
            clock.idleFor(Duration.ofSeconds(306))
            assertEquals(0, cancel); assertEquals(1, stops); assertTrue(events.isEmpty())
            val wake = ReflectionHelpers.getField<PowerManager.WakeLock>(runtime, "wake")
            assertTrue(wake.isHeld)
            audio.stop(); assertEquals(1, stops)
            late!!.invoke(DictationEvent.Done("sent")); assertEquals(1, events.size)
            clock.idleFor(Duration.ofSeconds(6)); assertFalse(wake.isHeld)
            late!!.invoke(DictationEvent.Done("late")); assertEquals(1, events.size)
        } finally { owner.destroy() }
    }

    @Test fun telecomBindingColdStartsCompanionAndDestroyClearsAllCallMaps() {
        val owner = Robolectric.buildService(CompanionCallsService::class.java).create()
        val service = owner.get()
        val started = Shadows.shadowOf(context).nextStartedService
        assertEquals(GlassesService::class.java.name, started.component!!.className)
        for (name in listOf("identities", "connected", "identityKeys")) {
            val map = ReflectionHelpers.getField<MutableMap<Any, Any>>(service, name)
            map[Any()] = "stale"
        }
        owner.destroy()
        for (name in listOf("ids", "identities", "connected", "identityKeys", "callbacks"))
            assertTrue(ReflectionHelpers.getField<Map<*, *>>(service, name).isEmpty())
        assertNull(CompanionCallsService.instance)
    }

    @Test fun screenedTelecomStatesRingOnlyAfterScreeningFinishes() {
        assertEquals(CallPhase.Ringing, telecomPhase(android.telecom.Call.STATE_SIMULATED_RINGING))
        assertEquals(CallPhase.Screening, telecomPhase(android.telecom.Call.STATE_AUDIO_PROCESSING))
    }

    @Test fun stockTextDisplaysKeepTheEntireVoiceAndPlaybackRecoveryInstruction() {
        for ((cols, rows) in listOf(26 to 7, 20 to 5)) {
            for (message in listOf("Mic unavailable - open the phone app", "Open the phone app to start playback")) {
                val grid = io.lampyr.glasses.render.TextGrid(cols, rows, false)
                io.lampyr.glasses.ui.TextChrome.toast(grid, message)
                val displayed = grid.lines().joinToString(" ").replace(Regex("\\s+"), " ")
                assertTrue(displayed, displayed.contains(message))
            }
        }
    }
}
