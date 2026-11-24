package io.lampyr.glasses.ambient

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SpeakerModel(private val directory: File, private val network: (Boolean) -> Boolean,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()) {
    val file = File(directory, "wespeaker.onnx")
    private val state = MutableStateFlow("missing")
    val status = state.asStateFlow()
    @Volatile var ready = false; private set
    private val lock = kotlinx.coroutines.sync.Mutex()
    suspend fun check() = withContext(Dispatchers.IO) {
        ready = file.isFile && digest(file) == SHA256
        state.value = if (ready) "ready" else "missing"
    }
    suspend fun download(wifiOnly: Boolean) {
        if (!lock.tryLock()) return
        try { withContext(Dispatchers.IO) {
            directory.mkdirs()
            val part = File(directory, "wespeaker.part")
            repeat(3) { attempt ->
                try {
                    check(network(wifiOnly)) { if (wifiOnly) "Connect to Wi-Fi" else "No network" }
                    val offset = part.length()
                    val request = Request.Builder().url(URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                    val call = http.newCall(request)
                    val cancel = launch { try { awaitCancellation() } finally { call.cancel() } }
                    try { call.execute().use { response ->
                        if (response.code == 416) { part.delete(); error("Restarting download") }
                        check(response.isSuccessful) { "Model download HTTP ${response.code}" }
                        val resume = response.code == 206 && offset > 0
                        if (resume) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid partial response" }
                        val body = response.body ?: error("Empty download")
                        val total = body.contentLength() + if (resume) offset else 0
                        FileOutputStream(part, resume).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(65536); var size = if (resume) offset else 0
                                while (true) {
                                    ensureActive(); check(network(wifiOnly)) { "Waiting for allowed network" }
                                    val n = input.read(buffer); if (n < 0) break
                                    output.write(buffer, 0, n); size += n
                                    check(size <= 100_000_000) { "Model exceeds expected size" }
                                    state.value = "downloading ${if (total > 0) size * 100 / total else 0}%"
                                }
                            }
                            output.fd.sync()
                        }
                    } } finally { cancel.cancel() }
                    if (digest(part) != SHA256) { part.delete(); error("Model checksum mismatch") }
                    java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    ready = true; state.value = "ready"; return@withContext
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    state.value = "failed: ${e.message?.take(100) ?: "Download unavailable"}"
                    if (attempt < 2) delay(1000L shl attempt)
                }
            }
        } } finally { lock.unlock() }
    }
    companion object {
        const val URL = "https://huggingface.co/Wespeaker/wespeaker-voxceleb-resnet34-LM/resolve/main/voxceleb_resnet34_LM.onnx"
        const val SHA256 = "7bb2f06e9df17cdf1ef14ee8a15ab08ed28e8d0ef5054ee135741560df2ec068"
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

fun allowedNetwork(context: Context, wifiOnly: Boolean): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
}
