package io.lampyr.glasses

import android.content.*
import io.lampyr.glasses.audio.PcmAudio
import io.lampyr.glasses.data.*
import java.io.File
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Memos go to the gateway (kind "memo") and on to its delivery targets by themselves, with retry and a visible state. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoUploadTest {
    @get:Rule val temp = TemporaryFolder()
    private var now = Instant.parse("2026-11-18T01:30:00Z").toEpochMilli()

    /** Fake gateway for the conversation routes; records every call. */
    private class Gateway : GatewayClient("https://fixture.example", "test") {
        val calls = mutableListOf<String>()
        val posts = mutableListOf<JsonObject>()
        val puts = mutableListOf<Triple<String, String, ByteArray>>()
        var postCode = 200; var putCodes = ArrayDeque<Int>(); var state = "audio"; var deliveries = "[]"
        override suspend fun exchange(path: String, method: String, body: JsonElement?, etag: String?, audio: File?): GatewayResponse {
            calls += "$method $path"
            return when {
                method == "POST" && path == "/ambient/conversations" -> { posts += body!!.jsonObject
                    if (postCode != 200) GatewayResponse(postCode) else GatewayResponse(200, """{"id":"11111111-2222-4333-8444-555555555555","kind":"memo","state":"received"}""") }
                method == "GET" && path.startsWith("/ambient/conversations/") -> GatewayResponse(200, """{"id":"x","state":"$state","error":${if (state == "failed") "\"Transcription failed\"" else "null"},"deliveries":$deliveries}""")
                else -> GatewayResponse(500)
            }
        }
        override suspend fun putAudio(path: String, audio: File, contentType: String): GatewayResponse {
            calls += "PUT $path"; puts += Triple(path, contentType, audio.readBytes())
            val code = putCodes.removeFirstOrNull() ?: 200
            return if (code == 200) GatewayResponse(200, """{"id":"x","state":"audio"}""") else GatewayResponse(code)
        }
        override suspend fun get(path: String): JsonElement = throw GatewayFailure("Offline")
        override suspend fun write(path: String, body: JsonElement, method: String): JsonElement = throw GatewayFailure("Offline")
        override suspend fun stream(path: String, body: ByteArray, contentType: String, accept: suspend (JsonElement) -> Unit) = throw GatewayFailure("Offline")
    }
    private fun wav(name: String, seconds: Double): File =
        File(temp.root, "memos").apply { mkdirs() }.resolve(name).apply { writeBytes(PcmAudio.wav(ByteArray((seconds * 32000).toInt() / 2 * 2), 16000)) }
    private var encodes = 0
    private val encoder: (File, File) -> Boolean = { _, ogg -> encodes++; ogg.writeBytes("OggS-fake-opus".toByteArray()); true }
    private fun memo(file: File, title: String = "Recording a Quick Memo") = Memo("a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b", title, "7:25 PM", 7,
        transcript = "I am going to record a quick memo.", filePath = file.absolutePath, createdAt = "2026-11-17T19:25:51.157038", pending = false)

    @Test fun aFinishedMemoIsPostedAsKindMemoPutAsOggAndFollowedToItsTargets() = runTest {
        val g = Gateway(); val up = MemoUploader(g, encoder, { now }, { ZoneId.of("America/Chicago") })
        val saved = mutableListOf<Memo>()
        var m = up.step(memo(wav("a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b.wav", 7.2)).copy(startedAt = "2026-11-18T01:25:44Z")) { saved += it }
        val body = g.posts.single()
        assertEquals("memo", body.s("kind")); assertEquals("Recording a Quick Memo", body.s("title")); assertEquals("glasses", body.s("device"))
        assertEquals("a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b", body.s("clientId"))
        assertEquals(7.2, body["speechSec"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("2026-11-18T01:25:44Z", body.s("startedAt")); assertEquals("2026-11-18T01:25:51.200Z", body.s("endedAt"))
        assertEquals("America/Chicago", body.s("tz"))
        val segment = body["segments"]!!.jsonArray.single().jsonObject
        assertEquals(0.0, segment["offsetSec"]!!.jsonPrimitive.double, 0.0); assertEquals(7.2, segment["durationSec"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("audio/ogg", g.puts.single().second); assertEquals("OggS-fake-opus", String(g.puts.single().third))
        assertEquals("/ambient/conversations/11111111-2222-4333-8444-555555555555/audio", g.puts.single().first)
        assertEquals("uploading", saved.first().upload); assertEquals("uploading", m.uploadLabel)
        assertTrue(m.audioSent); assertEquals(now + 60_000, m.nextAttemptAt)
        assertSame("waits for its poll time", m, up.step(m))
        now += 60_000; g.state = "uploaded"
        g.deliveries = """[{"target":"notes","name":"Notes folder","state":"delivered","deliveredAt":"2026-11-18T01:32:00Z"},{"target":"hook","name":"Webhook","state":"pending","error":"HTTP 503"}]"""
        m = up.step(m)
        assertEquals("uploaded", m.upload); assertEquals("uploaded", m.uploadLabel); assertFalse(m.uploadDone)
        assertTrue(m.delivered); assertEquals(listOf("Notes folder"), m.deliveredTo); assertEquals("Webhook: HTTP 503", m.deliveryError)
        now += 60_000; g.state = "timed"
        g.deliveries = """[{"target":"notes","name":"Notes folder","state":"delivered","deliveredAt":"2026-11-18T01:32:00Z"},{"target":"hook","name":"Webhook","state":"delivered","deliveredAt":"2026-11-18T01:33:00Z"}]"""
        m = up.step(m)
        assertEquals("delivered", m.upload); assertTrue(m.uploadDone); assertEquals("sent to Notes folder, Webhook", m.uploadLabel)
        assertEquals("2026-11-18T01:33:00Z", m.deliveredAt); assertNull(m.deliveryError)
        val calls = g.calls.size; now += 3600_000; assertSame(m, up.step(m)); assertEquals(calls, g.calls.size)
        assertEquals(1, g.posts.size); assertEquals(1, encodes)
    }

    @Test fun theOpusEncodeRunsOffTheCallersThread() = runTest {
        // The live data store's scope is the main thread; encoding minutes of audio there would freeze the app.
        val caller = java.lang.Thread.currentThread(); var encoderThread: java.lang.Thread? = null
        val up = MemoUploader(Gateway(), { wav, ogg -> encoderThread = java.lang.Thread.currentThread(); encoder(wav, ogg) }, { now })
        assertTrue(up.step(memo(wav("t.wav", 2.0))).audioSent)
        assertNotNull(encoderThread); assertNotSame(caller, encoderThread)
    }

    @Test fun anInterruptedUploadRetriesWithTheSameBytesAndNoSecondPost() = runTest {
        val g = Gateway().apply { putCodes.addAll(listOf(503, 400)) }; val up = MemoUploader(g, encoder, { now })
        var m = up.step(memo(wav("m1.wav", 3.0)))
        assertEquals("uploading", m.upload); assertFalse(m.audioSent); assertEquals(1, m.attempts); assertTrue(m.nextAttemptAt > now)
        now = m.nextAttemptAt; m = up.step(m); assertEquals(2, m.attempts)
        now = m.nextAttemptAt; m = up.step(m)
        assertTrue(m.audioSent); assertEquals(1, g.posts.size); assertEquals(1, encodes)
        assertEquals(1, g.puts.map { it.third.toList() }.distinct().size)
    }

    @Test fun withoutAnEncoderTheWavIsSent_tooShortAndRejectedMemosFail_busyWaits_missingRestarts() = runTest {
        val g = Gateway(); val noEncoder = MemoUploader(g, { _, _ -> false }, { now })
        val wavMemo = noEncoder.step(memo(wav("w.wav", 2.0)))
        assertEquals("audio/wav", g.puts.single().second); assertTrue(wavMemo.audioSent)
        assertEquals(44 + 64000, g.puts.single().third.size)

        val short = MemoUploader(Gateway(), encoder, { now }).step(memo(wav("s.wav", 0.5)))
        assertEquals("failed", short.upload); assertEquals("Too short", short.deliveryError); assertEquals("failed", short.uploadLabel)

        val rejected = MemoUploader(Gateway().apply { postCode = 422 }, encoder, { now }).step(memo(wav("r.wav", 2.0)))
        assertEquals("failed", rejected.upload)

        val busy = MemoUploader(Gateway().apply { postCode = 429 }, encoder, { now }).step(memo(wav("b.wav", 2.0)))
        assertEquals("", busy.upload); assertEquals("saved", busy.uploadLabel); assertEquals(now + 30 * 60_000, busy.nextAttemptAt)

        val gone = MemoUploader(Gateway().apply { putCodes.add(404) }, encoder, { now }).step(memo(wav("g.wav", 2.0)))
        assertNull(gone.serverId); assertEquals(1, gone.restarts); assertEquals("", gone.upload)

        val untitled = Gateway(); MemoUploader(untitled, encoder, { now }).step(memo(wav("u.wav", 2.0), title = "Transcribing…"))
        assertNull(untitled.posts.single()["title"])

        val failed = Gateway().apply { state = "failed" }; val up = MemoUploader(failed, encoder, { now })
        var m = up.step(memo(wav("f.wav", 2.0))); now = m.nextAttemptAt; m = up.step(m)
        assertEquals("failed", m.upload); assertEquals("Transcription failed", m.deliveryError)
        // Every target failed: the gateway reports failed, the memo row says so and names the target.
        val noTarget = Gateway().apply { state = "failed" }; noTarget.deliveries = """[{"target":"hook","name":"Webhook","state":"failed","error":"HTTP 401"}]"""
        val up2 = MemoUploader(noTarget, encoder, { now }); var n = up2.step(memo(wav("n.wav", 2.0))); now = n.nextAttemptAt; n = up2.step(n)
        assertEquals("failed", n.upload); assertFalse(n.delivered); assertEquals("Transcription failed", n.deliveryError)
    }

    @Test fun unsentMemosAndOrphanRecordingsUploadWithoutOpeningTheScreen() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }
        val done = wav("a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b.wav", 7.2)
        val later = wav("c5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b.wav", 4.0)
        val orphan = wav("0f0e0d0c-0b0a-4909-8807-060504030201.wav", 2.0)
        fun row(id: String, f: File, createdAt: String, extra: String = "") =
            """{"id":"$id","title":"Memo $id","at":"7:25 PM","durationSec":3,"transcript":"t","filePath":"${f.absolutePath.replace("\\", "\\\\")}","createdAt":"$createdAt","pending":false$extra}"""
        File(temp.root, "gateway-cache").mkdirs()
        // One memo a target already has, one recorded later that was never sent.
        SnapshotCache(File(temp.root, "gateway-cache")).put("memos", gatewayJson.parseToJsonElement("[" + listOf(
            row("a5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b", done, "2026-11-17T19:25:51.157038", ",\"upload\":\"delivered\",\"delivered\":true,\"deliveredTo\":[\"Notes folder\"]"),
            row("c5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b", later, "2026-11-17T23:10:00")).joinToString(",") + "]"))
        val g = Gateway()
        val data = LiveDataStore(context, backgroundScope, g, { now }, { 0.0 }, false, null, encoder)
        try {
            data.uploadMemos()
            assertEquals(listOf("c5127834-5f0e-4c6b-b5b8-2fa31ec4fd7b"), g.posts.map { it.s("clientId") })
            val cached = GatewayMappings.decode<List<Memo>>(SnapshotCache(File(temp.root, "gateway-cache"))["memos"]!!)
            assertEquals("sent to Notes folder", cached.single { it.id.startsWith("a512") }.uploadLabel)
            assertEquals("uploading", cached.single { it.id.startsWith("c512") }.upload)
            // A recording the list lost gets a list entry so it uploads too.
            val back = cached.single { it.id == "0f0e0d0c-0b0a-4909-8807-060504030201" }
            assertEquals(orphan.absolutePath, back.filePath); assertTrue(back.pending)
        } finally { data.close() }
    }
}
