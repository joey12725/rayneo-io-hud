package io.lampyr.glasses.data

import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Request as HttpRequest

val gatewayJson = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
fun obj(vararg fields: Pair<String, JsonElement?>) = JsonObject(fields.filter { it.second != null }.associate { it.first to it.second!! })
fun str(value: String?) = value?.let(::JsonPrimitive)
fun JsonElement.s(key: String, default: String = "") = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull } ?: default
fun JsonElement.n(key: String, default: Double = 0.0) = s(key).toDoubleOrNull() ?: default
fun JsonElement.b(key: String) = s(key) == "true"
fun JsonElement.a(key: String) = (this as? JsonObject)?.get(key) as? JsonArray ?: JsonArray(emptyList())
fun segment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

class GatewayFailure(val display: String, val code: Int = 0) : IOException(display)

/** Incremental lines are decoded only after UTF-8 bytes have been assembled by Okio. */
class NdjsonParser(private val accept: (JsonElement) -> Unit) {
    private val pending = StringBuilder()
    fun feed(chunk: String) {
        pending.append(chunk)
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) break
            parse(pending.substring(0, end)); pending.delete(0, end + 1)
        }
    }
    fun finish() { parse(pending.toString()); pending.clear() }
    private fun parse(line: String) { if (line.isNotBlank()) accept(gatewayJson.parseToJsonElement(line.trim())) }
}

class EventCursor(initial: String = "") {
    var value = initial; private set
    suspend fun consume(batch: JsonElement, accept: suspend (JsonElement) -> Unit) {
        // Advance only after all actions have been accepted; entity IDs may repeat with new seq.
        for (event in batch.a("events")) accept(event)
        value = batch.s("cursor", value)
    }
}

open class GatewayClient(
    val baseUrl: String,
    private val token: String,
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build(),
) {
    /** Status-preserving API for durable queues (304/400/409/429 have distinct semantics).
     * Fakes override this method; no queue needs real gateway access in tests. */
    open suspend fun exchange(path: String, method: String = "GET", body: JsonElement? = null,
        etag: String? = null, audio: File? = null): GatewayResponse = withContext(Dispatchers.IO) {
        val payload = audio?.asRequestBody("audio/ogg".toMediaType())
            ?: body?.toString()?.toRequestBody("application/json".toMediaType())
        val builder = request(path, method, payload).newBuilder()
        if (etag != null) builder.header("If-None-Match", etag)
        val call = http.newCall(builder.build())
        if (audio != null && method == "PUT") call.timeout().clearTimeout()
        val cancel = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { call.execute().use { r ->
            GatewayResponse(r.code, r.body?.string().orEmpty(), r.header("ETag"))
        } } finally { cancel.cancel() }
    }
    /** [exchange] for an audio PUT with an explicit media type (memos send Ogg/Opus, or WAV if encoding failed). */
    open suspend fun putAudio(path: String, audio: File, contentType: String): GatewayResponse =
        if (contentType == "audio/ogg") exchange(path, "PUT", audio = audio) else withContext(Dispatchers.IO) {
            val call = http.newCall(request(path, "PUT", audio.asRequestBody(contentType.toMediaType())))
            call.timeout().clearTimeout()
            val cancel = launch { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { r -> GatewayResponse(r.code, r.body?.string().orEmpty(), r.header("ETag")) } } finally { cancel.cancel() }
        }
    /** Same configured API base and bearer as HTTP; credentials never enter the URL. A reconnect adds
     * X-Assistant-Call-Resume so the gateway replaces this phone's previous (possibly half-dead) call socket. */
    open fun callSocketFactory(): io.lampyr.glasses.audio.JoyCallSocketFactory = object : io.lampyr.glasses.audio.JoyCallSocketFactory {
        override fun connect(listener: io.lampyr.glasses.audio.JoyCallSocket.Listener) = callSocket(listener, null)
        override fun reconnect(listener: io.lampyr.glasses.audio.JoyCallSocket.Listener, resume: String?) = callSocket(listener, resume ?: "1")
    }
    internal fun callRequest(resume: String?): HttpRequest {
        val request = request("/assistant/call", "GET", null)
        val url = request.url.toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val builder = request.newBuilder().url(url)
        if (resume != null) builder.header("X-Assistant-Call-Resume", resume.filter { it.isLetterOrDigit() || it in "-_." }.take(64).ifEmpty { "1" })
        return builder.build()
    }
    private fun callSocket(listener: io.lampyr.glasses.audio.JoyCallSocket.Listener, resume: String?): io.lampyr.glasses.audio.JoyCallSocket {
        // WebSocket pings find a dead link within ~30 s (OkHttp fails the socket on a missed pong), which starts the reconnect.
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
            .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS).build()
        val opened = java.util.concurrent.atomic.AtomicBoolean()
        val socket = client.newWebSocket(callRequest(resume), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.set(true); listener.opened() }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.text(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = listener.binary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); listener.ended("connection closed") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.ended("connection closed")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.ended("connection lost")
        })
        return object : io.lampyr.glasses.audio.JoyCallSocket {
            override fun text(value: String) = socket.send(value)
            override fun binary(value: ByteArray) = socket.queueSize() < 3_200 && socket.send(okio.ByteString.of(*value))
            override fun close() { if (!opened.get() || !socket.close(1000, null)) socket.cancel() }
        }
    }
    /** Agent-control link (/agent/link): same base URL and header bearer as HTTP, no read timeout. */
    open fun agentSocketFactory() = io.lampyr.glasses.agent.AgentSocketFactory { listener ->
        val request = request("/agent/link", "GET", null)
        val url = request.url.toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
            .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val socket = client.newWebSocket(request.newBuilder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.opened()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.text(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); listener.ended("closed $code") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.ended("closed $code")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.ended(response?.let { "HTTP ${it.code}" } ?: "connection lost")
        })
        object : io.lampyr.glasses.agent.AgentSocket {
            override fun send(text: String) = socket.send(text)
            override fun close() { if (!socket.close(1000, null)) socket.cancel() }
        }
    }
    private fun request(path: String, method: String, body: RequestBody?): HttpRequest {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://localhost")) { "Use an HTTPS gateway URL" }
        return HttpRequest.Builder().url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token").method(method, body).build()
    }
    private suspend fun <T> execute(request: HttpRequest, read: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        // Cancellation must also stop a blocked long poll or NDJSON read.
        val cancel = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw GatewayFailure(
                    if ("/t3/" in request.url.encodedPath) t3FailureText(response.code, response.body?.string().orEmpty())
                    else when (response.code) {
                    401, 403 -> "Check token"
                    409 -> "Action changed; refresh and try again"
                    404 -> "Not available yet"
                    else -> "Gateway error (${response.code})"
                }, response.code)
                read(response)
            }
        } catch (e: IOException) {
            ensureActive()
            throw if (e is GatewayFailure) e else GatewayFailure("Offline · cached data shown")
        } finally { cancel.cancel() }
    }
    open suspend fun get(path: String): JsonElement = execute(request(path, "GET", null)) {
        gatewayJson.parseToJsonElement(it.body?.string().orEmpty().ifBlank { "null" })
    }
    open suspend fun write(path: String, body: JsonElement = obj(), method: String = "POST"): JsonElement =
        execute(request(path, method, if (method == "DELETE") null else body.toString().toRequestBody("application/json".toMediaType()))) {
            gatewayJson.parseToJsonElement(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    open suspend fun stream(path: String, body: ByteArray, contentType: String, accept: suspend (JsonElement) -> Unit) {
        execute(request(path, "POST", body.toRequestBody(contentType.toMediaType()))) {
            val source = it.body?.source() ?: throw GatewayFailure("Empty response")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isNotBlank()) accept(gatewayJson.parseToJsonElement(line))
            }
        }
    }
    suspend fun events(cursor: EventCursor, saveCursor: suspend (String) -> Unit, accept: suspend (JsonElement) -> Unit) {
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            try {
                cursor.consume(get("/events?since=${segment(cursor.value)}&wait=25"), accept)
                saveCursor(cursor.value); backoff = 1000L
                delay(100) // Avoid hot loops against a gateway that returns immediately.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { delay(if (e is GatewayFailure && e.display == "Check token") 60_000 else backoff); backoff = (backoff * 2).coerceAtMost(60_000) }
        }
    }
}

/** T3 routes answer {error} naming the machine and the reason ("Mac is offline (network error)",
 * "Desktop is read-only; re-pair with write access"); show that instead of a bare status. */
fun t3FailureText(code: Int, body: String): String {
    val detail = runCatching { gatewayJson.parseToJsonElement(body.ifBlank { "{}" }).s("error") }.getOrDefault("").trim()
    return when {
        detail == "Authentication temporarily unavailable" -> "Gateway busy · retry"
        code == 401 || code == 403 && (detail.isBlank() || detail == "Token not valid for this API") -> "Check token"
        code == 409 && detail.isBlank() -> "Action changed; refresh and try again"
        code == 404 && (detail.isBlank() || detail == "Not found") -> "Not available yet"
        detail.isNotBlank() -> if (detail.length > 60) detail.take(59).trimEnd() + "…" else detail
        else -> "Gateway error ($code)"
    }
}

data class GatewayResponse(val code: Int, val body: String = "", val etag: String? = null) {
    fun json() = gatewayJson.parseToJsonElement(body.ifBlank { "{}" })
    fun requireSuccess(): GatewayResponse { if (code !in 200..299) throw GatewayFailure("Gateway error ($code)"); return this }
}
