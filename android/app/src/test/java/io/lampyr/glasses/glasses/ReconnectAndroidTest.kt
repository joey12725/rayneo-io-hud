package io.lampyr.glasses.glasses

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReconnectAndroidTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun receiverDeliversScreenAndGlassesBroadcastsUntilClosed() {
        val got = mutableListOf<ReconnectTriggers.Trigger>()
        val receiver = ReconnectTriggers.Receiver(context, { listOf("AA:BB:CC:DD:EE:FF") }) { got += it }
        val glasses = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val other = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("11:22:33:44:55:66")
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, other))
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, glasses))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("phone screen on", "glasses Bluetooth link up"), got.map { it.reason })
        receiver.close()
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, got.size)
        receiver.close() // Idempotent.
    }

    @Test fun probeReadsInstalledStoppedAndBluetoothGrant() {
        assertEquals(OfficialApp.State(installed = false), OfficialApp.probe(context))
        val pm = shadowOf(context.packageManager)
        fun install(stopped: Boolean, granted: Boolean) {
            pm.installPackage(PackageInfo().apply {
                packageName = OfficialApp.PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    packageName = OfficialApp.PACKAGE; enabled = true; flags = if (stopped) ApplicationInfo.FLAG_STOPPED else 0
                }
                requestedPermissions = arrayOf("android.permission.BLUETOOTH_CONNECT")
                requestedPermissionsFlags = intArrayOf(if (granted) PackageInfo.REQUESTED_PERMISSION_GRANTED else 0)
            })
        }
        install(stopped = false, granted = false)
        assertEquals(OfficialApp.State(true, enabled = true, stopped = false, bluetooth = false), OfficialApp.probe(context))
        assertEquals(OfficialApp.Level.INFO, OfficialApp.health(context, now = 1_000_000).level)
        install(stopped = false, granted = true)
        // Cached for a few seconds (phone pages refresh several times a second), then probed again.
        assertEquals(OfficialApp.Level.INFO, OfficialApp.health(context, now = 1_002_000).level)
        assertEquals(OfficialApp.Level.WARN, OfficialApp.health(context, now = 1_006_000).level)
        OfficialApp.traceIfChanged(context) // Never throws; traces once per change.
    }
}
