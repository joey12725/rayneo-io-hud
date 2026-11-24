package io.lampyr.glasses.input

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import io.lampyr.glasses.phone.*

/** Adds only a controller association; never replaces or removes glasses associations. */
@Suppress("DEPRECATION")
class ControllerAssociationActivity : Activity() {
    private lateinit var status: Note
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen("Restart with the controller", "Optional. Lets Android start the companion again when your controller appears (Android 12 and later).") {
            add(context.card(12) {
                add(context.text("Choose the controller in the list Android shows, not your glasses.", Type.SMALL))
                add(context.button("Link a keyboard-mode controller", Btn.PRIMARY) { associate(false) })
                add(context.button("Link a Bluetooth LE controller") { associate(true) })
                status = add(context.note())
            })
        }
    }
    private fun associate(ble: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) { status.text = "Companion device setup unavailable on this phone"; return }
        val request = AssociationRequest.Builder().setSingleDevice(false).apply {
            if (ble) addDeviceFilter(BluetoothLeDeviceFilter.Builder().build()) else addDeviceFilter(BluetoothDeviceFilter.Builder().build())
        }.build()
        runCatching {
            getSystemService(CompanionDeviceManager::class.java).associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) { launch(chooserLauncher) }
                override fun onAssociationPending(intentSender: IntentSender) { launch(intentSender) }
                private fun launch(sender: IntentSender) {
                    runCatching { startIntentSenderForResult(sender, 71, null, 0, 0, 0) }.onFailure { status.text = "Device chooser unavailable" }
                }
                @android.annotation.TargetApi(33)
                override fun onAssociationCreated(associationInfo: AssociationInfo) { associationInfo.deviceMacAddress?.toString()?.let(::remember) }
                override fun onFailure(error: CharSequence?) { status.text = error ?: "Association cancelled" }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { status.text = "Could not associate: ${it.message}" }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == RESULT_OK) {
            val chosen = data?.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
            val device = when (chosen) { is BluetoothDevice -> chosen; is ScanResult -> chosen.device; else -> null }
            device?.address?.let(::remember)
        }
    }
    private fun remember(address: String) {
        val prefs = getSharedPreferences("controller", MODE_PRIVATE)
        val previous = prefs.getString("associationAddress", null)
        val manager = getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && previous != null && previous != address) runCatching { manager.stopObservingDevicePresence(previous) }
        prefs.edit().putString("associationAddress", address).apply()
        status.text = if (Build.VERSION.SDK_INT >= 31) runCatching {
            manager.startObservingDevicePresence(address); "Controller associated; presence observation enabled"
        }.getOrElse { "Associated; presence observation unavailable: ${it.message}" }
        else "Controller associated. Automatic presence needs Android 12+."
    }
}
