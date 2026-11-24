package io.lampyr.glasses.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.lampyr.glasses.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext + IV enter preferences. The non-exportable wrapping key lives in Keystore. */
class AppSettings(context: Context) {
    val prefs = context.getSharedPreferences("live-settings", Context.MODE_PRIVATE)
    var url: String
        get() = prefs.getString("url", BuildConfig.GATEWAY_URL)!!.trimEnd('/')
        set(value) { prefs.edit().putString("url", value.trim().trimEnd('/')).apply() }
    var token: String
        get() = (if (!prefs.contains("token")) BuildConfig.GATEWAY_TOKEN else runCatching {
            val bytes = Base64.decode(prefs.getString("token", ""), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        }.getOrDefault("")).also { io.lampyr.glasses.debug.Trace.protect(it) }
        set(value) {
            io.lampyr.glasses.debug.Trace.protect(value.trim())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val wrapped = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            prefs.edit().putString("token", Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
        }
    var live: Boolean
        get() = prefs.getBoolean("live", token.isNotBlank())
        set(value) { prefs.edit().putBoolean("live", value).apply() }
    var grip: String
        get() = prefs.getString("grip", "cw")!!
        set(value) { prefs.edit().putString("grip", value).apply() }
    var previewText: Boolean
        get() = prefs.getBoolean("previewText", false)
        set(value) { prefs.edit().putBoolean("previewText", value).apply() }
    var boot: Boolean
        get() = prefs.getBoolean("boot", false)
        set(value) { prefs.edit().putBoolean("boot", value).apply() }
    /** Agent control confirm policy for send/call/spend/physical ops: outside_glasses (default), always, never. */
    var agentConfirm: String
        get() = prefs.getString("agent.confirm", "outside_glasses")!!
        set(value) { prefs.edit().putString("agent.confirm", value).apply() }
    /** Agent control link to the gateway (the assistant can drive the glasses). On by default in live mode. */
    var agentControl: Boolean
        get() = prefs.getBoolean("agent.enabled", true)
        set(value) { prefs.edit().putBoolean("agent.enabled", value).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gateway-token-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("gateway-token-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
