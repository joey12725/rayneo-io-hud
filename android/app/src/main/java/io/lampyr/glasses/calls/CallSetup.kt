@file:Suppress("DEPRECATION")
package io.lampyr.glasses.calls

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.telecom.TelecomManager
import java.util.regex.Pattern

object CallSetup {
    fun status(activity: Activity): String {
        val telecom = activity.getSystemService(TelecomManager::class.java)
        return if (Build.VERSION.SDK_INT >= 31 && runCatching { telecom.hasManageOngoingCallsPermission() }.getOrDefault(false))
            "Companion call access enabled. Controls connect when a call starts."
        else "Basic call mode. Mute and audio routing require companion association on Android 12+."
    }
    fun associate(activity: Activity, report: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) { report("Android 10/11: basic call controls only; mute and audio stay on phone."); return }
        val manager = activity.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) { report("Companion setup unavailable on this phone"); return }
        val name = Pattern.compile(".*(rayneo|8bitdo|micro).*", Pattern.CASE_INSENSITIVE)
        val request = AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder()
            .setNamePattern(name).build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(name).build())
            .setSingleDevice(false).build()
        runCatching {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    runCatching { activity.startIntentSenderForResult(chooserLauncher, 91, null, 0, 0, 0) }
                        .onFailure { report("Could not open companion association") }
                }
                override fun onFailure(error: CharSequence?) { report(error?.toString() ?: "Association cancelled") }
            }, android.os.Handler(activity.mainLooper))
        }.onFailure { report("Companion association unavailable: ${it.message}") }
    }
}
