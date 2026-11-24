package io.lampyr.glasses

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import io.lampyr.glasses.input.*
import io.lampyr.glasses.audio.*
import io.lampyr.glasses.service.GlassesService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @org.junit.Before fun grantReceiverSignaturePermission() {
        // Android grants this merged-manifest signature permission on install; API 29 Robolectric does not.
        Shadows.shadowOf(context as android.app.Application).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private fun device(id: Int, descriptor: String) = InputDeviceBuilder.newBuilder().setId(id).setName("8BitDo Micro")
        .setDescriptor(descriptor).setVendorId(123).setProductId(456).setExternal(true).setSources(InputDevice.SOURCE_KEYBOARD).build()
    private fun event(device: InputDevice, code: Int, action: Int = KeyEvent.ACTION_DOWN) =
        KeyEvent(0, 0, action, code, 0, 0, device.id, 0, InputDevice.SOURCE_KEYBOARD).also { Shadows.shadowOf(it).setDevice(device) }

    @Test
    fun accessibilityConsumesUnmappedControllerKeysWithServiceStoppedAndLeavesOtherKeyboardsAlone() {
        val pad = device(10, "controller"); val keyboard = device(11, "keyboard")
        KeyMap(context).apply { rememberController(pad); close() }
        assertNull(GlassesService.instance)
        val owner = Robolectric.buildService(ControllerAccessibilityService::class.java).create()
        val service = owner.get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        fun key(event: KeyEvent): Boolean = ReflectionHelpers.callInstanceMethod(service, "onKeyEvent", ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))
        try {
            assertTrue(key(event(pad, KeyEvent.KEYCODE_Z))) // Unmapped
            assertTrue(key(event(pad, KeyEvent.KEYCODE_Z, KeyEvent.ACTION_UP)))
            assertTrue(key(event(pad, KeyEvent.KEYCODE_ENTER)))
            assertFalse(key(event(keyboard, KeyEvent.KEYCODE_ENTER)))
            assertFalse(key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            assertNotNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
        } finally { owner.destroy() }
    }

    @Test fun existingKeyMapInstanceRefreshesWhenLearningSavesAndIdentityIsStrict() {
        val pad = device(10, "controller")
        val reader = KeyMap(context)
        val writer = KeyMap(context)
        try {
            writer.rememberController(pad)
            writer.replace(InputEngine.BIND.keys.mapIndexed { i, letter -> (100 + i) to letter }.toMap())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(reader.isController(pad))
            assertFalse(reader.isController(device(11, "other")))
            assertEquals("y", reader.letter(100))
            assertNull(reader.letter(KeyEvent.KEYCODE_ENTER)) // Complete maps replace defaults.
        } finally { reader.close(); writer.close() }
    }

    @Test fun starIsOptionalButEveryOtherButtonIsRequiredOnce() {
        val keys = KeyMap(context)
        try {
            val withoutStar = (InputEngine.BIND.keys - "star").mapIndexed { i, letter -> (200 + i) to letter }.toMap()
            keys.replace(withoutStar)
            assertEquals("y", keys.letter(200 + (InputEngine.BIND.keys - "star").indexOf("y")))
            assertNull(keys.letter(KeyEvent.KEYCODE_S)) // A learned map without ★ does not fall back to the default S key.
            assertThrows(IllegalArgumentException::class.java) { keys.replace(withoutStar - 200) }
            assertThrows(IllegalArgumentException::class.java) { keys.replace(withoutStar + (999 to "y")) }
        } finally { keys.close() }
    }

    @Test fun vendorProfileRoundTripsAndRejectsHidProfiles() {
        val vendor = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val profile = ControllerProfile("AA:BB:CC:DD:EE:FF", vendor, vendor, 2, listOf(ButtonField(1, 4, 4, "l2")))
        profile.save(context)
        assertEquals(profile, ControllerProfile.load(context))
        assertThrows(IllegalArgumentException::class.java) { profile.copy(service = NonHidGattSession.HID) }
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().putString("gattProfile", "corrupt").commit()
        assertNull(ControllerProfile.load(context))
    }

    @Test @Config(sdk = [29, 35])
    fun selectedShizukuWithoutInstalledBackendKeepsAccessibilityWorkingAndStopsCleanly() {
        val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
        prefs.edit().putString("source", "shizuku").commit()
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        try {
            val runtime = owner.get().controller!!
            assertEquals("accessibility", runtime.sourceName)
            assertNotNull(runtime.shizuku)
            assertFalse(runtime.shizuku!!.state.ready)
            assertFalse(runtime.shizuku!!.state.running)
            prefs.edit().putBoolean("shizukuEnabled", false).commit()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("accessibility", runtime.sourceName)
            assertNull(runtime.shizuku)
        } finally { owner.destroy() }
    }

    @Test @Config(sdk = [29, 35])
    fun screenOffReleasesInputAndWakeLockCoversDictationUntilTerminalResult() {
        val owner = Robolectric.buildService(GlassesService::class.java).create()
        val service = owner.get()
        val runtime = service.controller!!
        val wake = ReflectionHelpers.getField<android.os.PowerManager.WakeLock>(runtime, "wake")
        val clock = Shadows.shadowOf(Looper.getMainLooper())
        var terminal: ((DictationEvent) -> Unit)? = null
        val audio = runtime.dictation(object : Dictation {
            override fun start(target: String, onEvent: (DictationEvent) -> Unit) { terminal = onEvent }
            override fun stop() {}
            override fun cancel() {}
        })
        try {
            service.input.press("r2")
            assertTrue(wake.isHeld)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            clock.idle()
            assertFalse(service.input.hasKeysDown)
            assertFalse(service.ui.mediaLayer)
            service.ui.locked = true // Do not animate during the virtual idle period.
            clock.idleFor(Duration.ofSeconds(6))
            assertFalse(wake.isHeld)
            audio.start("joy") {}
            audio.stop()
            service.input.onTalkCap()
            clock.idleFor(Duration.ofSeconds(180))
            assertTrue(wake.isHeld) // Processing can outlive key-up.
            terminal!!.invoke(DictationEvent.Done("done"))
            clock.idleFor(Duration.ofSeconds(6))
            assertFalse(wake.isHeld)
        } finally { owner.destroy() }
        terminal!!.invoke(DictationEvent.Error("late result"))
        assertFalse(wake.isHeld)
    }
}
