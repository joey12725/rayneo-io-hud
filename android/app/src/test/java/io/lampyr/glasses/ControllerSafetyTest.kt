package io.lampyr.glasses

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.util.SparseArray
import android.view.InputDevice
import android.view.accessibility.AccessibilityManager
import io.lampyr.glasses.input.*
import io.lampyr.glasses.service.GlassesService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[ControllerSafetyTest.PadDevices::class])
class ControllerSafetyTest {
    @org.robolectric.annotation.Implements(className="android.hardware.input.InputManagerGlobal",isInAndroidSdk=false)
    class PadDevices : org.robolectric.shadows.ShadowInputManagerGlobal() {
        @org.robolectric.annotation.Implementation
        override fun getInputDeviceIds() = intArrayOf(10)
    }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun enabled(value: Boolean) {
        val info = AccessibilityServiceInfo().apply {
            ReflectionHelpers.setField(this,"mResolveInfo",ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = context.packageName; name = ControllerAccessibilityService::class.java.name
            } })
        }
        Shadows.shadowOf(context.getSystemService(AccessibilityManager::class.java))
            .setEnabledAccessibilityServiceList(if (value) listOf(info) else emptyList())
    }
    private fun pad() {
        // Robolectric does not reset this hidden singleton when switching shadow configurations.
        ReflectionHelpers.setStaticField(Class.forName("android.hardware.input.InputManagerGlobal"),"sInstance",null)
        val pad = InputDeviceBuilder.newBuilder().setId(10).setName("8BitDo Micro").setDescriptor("pad")
            .setVendorId(123).setProductId(456).setExternal(true).setSources(InputDevice.SOURCE_KEYBOARD).build()
        KeyMap(context).apply { rememberController(pad); close() }
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.hardware.input.InputManagerGlobal"), "getInstance")
        ReflectionHelpers.setField(global, "mInputDevices", SparseArray<InputDevice>().apply { put(pad.id, pad) })
    }
    private fun harness(block: (GlassesService) -> Unit) {
        pad(); enabled(true)
        val access = Robolectric.buildService(ControllerAccessibilityService::class.java).create()
        ReflectionHelpers.callInstanceMethod<Unit>(access.get(), "onServiceConnected")
        val owner = Robolectric.buildService(GlassesService::class.java)
        try {
            owner.create()
            owner.get().ui.locked=true
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue(owner.get().controller!!.connected)
            block(owner.get())
        } finally {
            owner.destroy(); access.destroy()
            ReflectionHelpers.setStaticField(Class.forName("android.hardware.input.InputManagerGlobal"),"sInstance",null)
        }
    }
    @Test fun disabledAccessibilityWithPadStillPairedIsAnImmediateDisconnect() = harness { service ->
        enabled(false);service.controller!!.inputPathChanged()
        assertFalse(service.controller!!.connected)
        assertTrue(InputDevice.getDeviceIds().isNotEmpty())
    }
    @Test fun deadShizukuWithoutAccessibilityFallbackDisconnects() = harness { service ->
        context.getSharedPreferences("controller",Context.MODE_PRIVATE).edit().putString("source","shizuku").commit()
        val clock=Shadows.shadowOf(Looper.getMainLooper());clock.idleFor(Duration.ofSeconds(3))
        val raw=service.controller!!.shizuku!!
        raw.state(io.lampyr.glasses.input.shizuku.ShizukuInputState(running=true,granted=true,bound=true,nodes=listOf("event1")))
        enabled(false)
        raw.state(io.lampyr.glasses.input.shizuku.ShizukuInputState())
        assertFalse(service.controller!!.connected)
    }
    @Test fun gattHeldXDoesNotManufactureAnotherPress() {
        val id=java.util.UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val presses=mutableListOf<String>()
        val sink=object : ControllerSink {
            override fun press(letter: String) { presses+=letter }
            override fun release(letter: String) {}
            override fun releaseAll() {}
            override fun confirmDown() {}
            override fun status(connected: Boolean,detail: String) {}
        }
        val source=GattControllerSource(context,ControllerProfile("AA:BB:CC:DD:EE:FF",id,id,1,listOf(ButtonField(0,1,1,"x"))),sink)
        val session=ReflectionHelpers.getField<NonHidGattSession>(source,"session")
        val report=ReflectionHelpers.getField<(android.bluetooth.BluetoothGattCharacteristic,ByteArray)->Unit>(session,"report")
        val characteristic=android.bluetooth.BluetoothGattCharacteristic(id,0,0)
        try {
            report(characteristic,byteArrayOf(1));assertEquals(listOf("x"),presses)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            report(characteristic,byteArrayOf(1));assertEquals(listOf("x"),presses)
            report(characteristic,byteArrayOf(0));report(characteristic,byteArrayOf(1));assertEquals(listOf("x","x"),presses)
        } finally { source.stop() }
    }

}
