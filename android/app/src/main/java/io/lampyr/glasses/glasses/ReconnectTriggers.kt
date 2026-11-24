@file:Suppress("DEPRECATION")
package io.lampyr.glasses.glasses

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.lampyr.glasses.debug.Trace

/**
 * System events that mean the glasses may be reachable right now, so the link should try at once rather than wait out
 * its backoff: the selected glasses' Bluetooth link coming up or pairing completing, the adapter switching on, and the
 * phone screen coming on or being unlocked. The controller key and the Connect button reach the link directly.
 */
object ReconnectTriggers {
    data class Trigger(val reason: String, val userAction: Boolean = false)

    val ACTIONS = listOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothAdapter.ACTION_STATE_CHANGED, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT)

    /** Pure mapping from one broadcast to a retry trigger; null when it is not about the selected glasses. */
    fun classify(action: String?, device: String?, bondState: Int, adapterState: Int, selected: Collection<String>): Trigger? {
        val ours = device != null && selected.any { it.equals(device, true) }
        if (selected.isEmpty()) return null // Link off or no glasses chosen: nothing to retry, nothing to trace.
        return when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (ours) Trigger("glasses Bluetooth link up") else null
            // Pairing finished: this can also fix a link that stopped because the glasses were unpaired.
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (ours && bondState == BluetoothDevice.BOND_BONDED) Trigger("glasses paired", userAction = true) else null
            BluetoothAdapter.ACTION_STATE_CHANGED -> if (adapterState == BluetoothAdapter.STATE_ON) Trigger("Bluetooth on") else null
            Intent.ACTION_SCREEN_ON -> Trigger("phone screen on")
            Intent.ACTION_USER_PRESENT -> Trigger("phone unlocked")
            else -> null
        }
    }

    /** Registers the broadcasts until [close]; [selected] lists the chosen glasses' classic and BLE addresses. */
    class Receiver(private val context: Context, private val selected: () -> Collection<String>,
                   private val onTrigger: (Trigger) -> Unit) : AutoCloseable {
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val trigger = classify(intent.action, device?.address, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1),
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1), selected()) ?: return
                Trace.log("Link", "Reconnect trigger ${trigger.reason} action=${intent.action}")
                onTrigger(trigger)
            }
        }
        init {
            val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
            // Same registration as the bond receiver in BleAuthenticator; every action here is a protected system broadcast.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
        override fun close() { runCatching { context.unregisterReceiver(receiver) } }
    }
}
