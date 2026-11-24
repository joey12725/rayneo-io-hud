package io.lampyr.glasses.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ControllerProfile(
    val address: String,
    val service: UUID,
    val characteristic: UUID,
    val reportLength: Int,
    val fields: List<ButtonField>,
) {
    init {
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address))
        require(NonHidGattSession.allowed(service, characteristic))
        ControllerReportDecoder(reportLength, fields)
    }
    fun save(context: Context) {
        val rows = JSONArray()
        fields.forEach { rows.put(JSONObject().put("offset", it.offset).put("mask", it.mask).put("value", it.value).put("letter", it.letter)) }
        val json = JSONObject().put("address", address).put("service", service.toString())
            .put("characteristic", characteristic.toString()).put("length", reportLength).put("fields", rows)
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().putString("gattProfile", json.toString()).apply()
    }
    companion object {
        fun load(context: Context): ControllerProfile? = runCatching {
            val json = JSONObject(context.getSharedPreferences("controller", Context.MODE_PRIVATE).getString("gattProfile", null) ?: return null)
            val rows = json.getJSONArray("fields")
            ControllerProfile(json.getString("address"), UUID.fromString(json.getString("service")), UUID.fromString(json.getString("characteristic")),
                json.getInt("length"), (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                    ButtonField(it.getInt("offset"), it.getInt("mask"), it.getInt("value"), it.getString("letter"))
                } })
        }.getOrNull()
    }
}
