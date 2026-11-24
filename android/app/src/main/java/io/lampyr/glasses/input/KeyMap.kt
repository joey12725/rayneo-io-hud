package io.lampyr.glasses.input

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

class KeyMap(context: Context) {
    private val prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val mapping = mutableMapOf<Int, String>()
    private var identity: ControllerIdentity? = null
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    init { reload(); prefs.registerOnSharedPreferenceChangeListener(listener) }

    private fun reload() {
        mapping.clear()
        val saved = prefs.getString("keyMap", null)
        if (saved == null) mapping.putAll(DEFAULT)
        else runCatching {
            val json = JSONObject(saved)
            json.keys().forEach { key ->
                val value = json.getString(key)
                if (value in InputEngine.BIND) mapping[key.toInt()] = value
            }
        }
        identity = prefs.getString("controllerDescriptor", null)?.let {
            ControllerIdentity(it, prefs.getInt("controllerVendor", 0), prefs.getInt("controllerProduct", 0))
        }
    }

    fun isController(device: android.view.InputDevice?): Boolean = device != null && !device.isVirtual &&
        ControllerKeyPolicy.consume(identity, ControllerIdentity(device.descriptor, device.vendorId, device.productId))

    fun rememberController(device: android.view.InputDevice) {
        prefs.edit().putString("controllerDescriptor", device.descriptor)
            .putInt("controllerVendor", device.vendorId).putInt("controllerProduct", device.productId)
            .putString("controllerName", device.name).apply()
        reload()
    }

    val hasLearnedController: Boolean get() = identity != null
    fun letter(keyCode: Int): String? = mapping[keyCode]
    fun refresh() { reload() }
    fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

    fun replace(keys: Map<Int, String>) {
        require(keys.values.toSet().size == keys.size && keys.values.all { it in InputEngine.BIND } &&
            keys.values.containsAll(InputEngine.BIND.keys - OPTIONAL)) { "Every required button must be learned exactly once" }
        val json = JSONObject()
        keys.forEach { (key, letter) -> json.put(key.toString(), letter) }
        mapping.clear(); mapping.putAll(keys)
        prefs.edit().putString("keyMap", json.toString()).apply()
    }

    fun bind(keyCode: Int, letter: String) {
        require(letter in InputEngine.BIND)
        mapping[keyCode] = letter
        val json = JSONObject()
        mapping.forEach { (k, v) -> json.put(k.toString(), v) }
        prefs.edit().putString("keyMap", json.toString()).apply()
    }

    companion object {
        /**
         * The buttons of the learned controller (the saved `keyMap`), or null when no map was learned and every default
         * key exists. Settings > Shortcuts hides gestures on buttons that are not in it (★ on the 8BitDo Micro).
         */
        fun learnedLetters(context: Context): Set<String>? = runCatching {
            val saved = context.getSharedPreferences("controller", Context.MODE_PRIVATE).getString("keyMap", null) ?: return null
            val json = JSONObject(saved)
            json.keys().asSequence().map { json.getString(it) }.filter { it in InputEngine.BIND }.toSet().takeIf { it.isNotEmpty() }
        }.getOrNull()
        /** Buttons a controller may not report at all; learning lets the owner skip them. ★ on the 8BitDo Micro sends nothing in keyboard mode. */
        val OPTIONAL = setOf("star")
        val DEFAULT =
            mapOf(
                KeyEvent.KEYCODE_DPAD_UP to "up",
                KeyEvent.KEYCODE_DPAD_DOWN to "down",
                KeyEvent.KEYCODE_DPAD_LEFT to "left",
                KeyEvent.KEYCODE_DPAD_RIGHT to "right",
                KeyEvent.KEYCODE_ENTER to "y",
                KeyEvent.KEYCODE_DEL to "b",
                KeyEvent.KEYCODE_I to "x",
                KeyEvent.KEYCODE_O to "a",
                KeyEvent.KEYCODE_U to "l",
                KeyEvent.KEYCODE_SPACE to "l2",
                KeyEvent.KEYCODE_1 to "l2",
                KeyEvent.KEYCODE_E to "r",
                KeyEvent.KEYCODE_3 to "r2",
                KeyEvent.KEYCODE_MINUS to "minus",
                KeyEvent.KEYCODE_EQUALS to "plus",
                KeyEvent.KEYCODE_S to "star",
                KeyEvent.KEYCODE_H to "heart",
            )
    }
}
