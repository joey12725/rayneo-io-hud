package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*
import io.lampyr.glasses.data.BluetoothBatteries
import io.lampyr.glasses.data.LiveDataStore
import io.lampyr.glasses.data.StatusDevices
import kotlinx.serialization.json.*

/** Status app ops (requirement 16.4): batteries and links, and which Bluetooth devices get a Status row. */
object StatusOps : OpProvider {
    private fun AgentCall.bluetooth(): BluetoothBatteries =
        (ui.data as? LiveDataStore)?.bluetoothBatteries ?: fail(Codes.UNAVAILABLE, "Bluetooth devices are tracked only with live data on")

    override fun ops() = listOf(
        OpSpec(
            "status.read", "Read glasses status",
            "Battery levels (glasses, earbuds by name, Oura, phone), Wi-Fi, cell signal, glasses link and brightness, as on the Status screen.",
            risk = Risk.READ,
        ) {
            ui.data.status.refresh()
            val s = ui.data.status.snapshot
            ok(data = buildJsonObject {
                put("link", s.link)
                put("batteries", JsonArray(s.batteries.map { b ->
                    buildJsonObject {
                        put("kind", b.icon); put("label", b.label)
                        put("percent", b.level?.let { JsonPrimitive(Math.round(it * 100)) } ?: JsonNull)
                    }
                }))
                put("wifi", s.wifi); put("cell", s.cell)
                put("brightness", ui.data.settings.snapshot.glassesBrightness?.let(::JsonPrimitive) ?: JsonNull)
            })
        },

        OpSpec(
            "status.list_bluetooth_devices", "List Bluetooth devices",
            "Paired Bluetooth devices with kind, whether connected, battery, and the Status screen setting (auto, show, hide).",
            risk = Risk.READ, tool = false,
        ) {
            val bt = bluetooth()
            val connected = bt.levels.associateBy { it.address }
            val glasses = bt.glassesAddress()
            val bonded = bt.bonded().filterNot { glasses != null && it.address.equals(glasses, ignoreCase = true) }
            val all = bonded.map { it.address to it } + connected.values.filter { c -> bonded.none { it.address == c.address } }
                .map { it.address to io.lampyr.glasses.data.BtDevice(it.address, it.name, it.kind) }
            ok(data = JsonArray(all.take(25).map { (address, d) ->
                val live = connected[address]
                buildJsonObject {
                    put("address", address); put("name", d.name); put("kind", d.kind)
                    put("connected", live != null)
                    put("percent", live?.level?.let { JsonPrimitive(Math.round(it * 100)) } ?: JsonNull)
                    put("status_screen", bt.choice(address) ?: "auto")
                    put("shown", live != null && StatusDevices.shows(live, bt.choice(address), glasses))
                }
            }))
        },

        OpSpec(
            "status.set_bluetooth_device", "Choose a Status device",
            "Show or hide one Bluetooth device's battery on the Status screen; auto shows earbuds and headphones only.",
            params = listOf(
                Param.string("device", "Device name or address (see status_list_bluetooth_devices)", required = true, maxLength = 100),
                Param.string("status_screen", "auto, show or hide", required = true, enum = listOf("auto", "show", "hide")),
            ),
            risk = Risk.ACT, tool = false,
        ) {
            val bt = bluetooth()
            val wanted = str("device")
            val known = bt.bonded().map { it.address to it.name } + bt.levels.map { it.address to it.name }
            val matches = known.filter { it.first.equals(wanted, true) }.ifEmpty {
                known.filter { it.second.equals(wanted, true) }.ifEmpty { known.filter { it.second.contains(wanted, true) } }
            }.distinctBy { it.first }
            val (address, name) = when (matches.size) {
                1 -> matches.single()
                0 -> fail(Codes.FAILED, "No paired device named \"$wanted\"; use status_list_bluetooth_devices")
                else -> fail(Codes.AMBIGUOUS, "Several devices match \"$wanted\"",
                    JsonArray(matches.take(8).map { buildJsonObject { put("address", it.first); put("name", it.second) } }))
            }
            val value = when (str("status_screen")) { "show" -> StatusDevices.SHOW; "hide" -> StatusDevices.HIDE; else -> null }
            bt.setChoice(address, value)
            ok("$name: ${str("status_screen")} on Status")
        },
    )
}
