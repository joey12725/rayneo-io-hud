package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.*
import java.io.File
import java.nio.file.Files
import java.time.*

class ConversationTest {
    private lateinit var root: File
    private val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()) // exactly 20ms
    private var now = Instant.parse("2026-11-12T23:59:59Z").toEpochMilli()
    @Before fun before() { root = Files.createTempDirectory("conversation-test").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun speech(store: ConversationStore, packets: Int = 1500): ConversationRecord {
        ConversationRecorder(store, { ZoneId.of("America/Chicago") }).use { r -> repeat(packets) { r.packet(packet, now + it * 20L, "fake", false) } }
        return store.all().first()
    }
    @Test fun exactOffsetsMergingThirtySecondRuleGapAndMidnight() {
        val store = ConversationStore(root)
        var mono = 0L
        ConversationRecorder(store, { ZoneId.of("UTC") }, monotonic = { mono }).use { r ->
            r.packet(packet, now, "fake", false); r.boundary()
            r.packet(packet, now + 319, "fake", false) // 299ms silence merges
            r.packet(packet, now + 639, "fake", false) // 300ms silence new anchor
            mono = 599999
            r.tick(now + 659 + 599999)
            assertEquals("recording", store.all().single().state)
            mono = 600000
            r.tick(now + 659 + 600000)
            assertEquals("skipped", store.all().single().state)
            r.packet(packet, now + 601000, "fake", false)
        }
        assertEquals(2, store.all().size)
        val manifest = store.all().last().manifest
        assertEquals(2, manifest.segments.size)
        assertEquals(.04, manifest.segments.last().offsetSec, 0.0)
        assertEquals(.06, manifest.speechSec, 0.0); manifest.validate()
        val file = store.audio(manifest.clientId)
        val samples = mutableListOf<Long>(); OggReader.audio(file) { _, s -> samples += s }
        assertEquals(listOf(0L,960L,1920L), samples)
        val nextRoot = root.resolve("threshold")
        assertEquals("skipped", speech(ConversationStore(nextRoot),1499).state)
        assertEquals("waiting", speech(ConversationStore(root.resolve("thirty"))).state)
    }
    @Test fun muteCloseDropAndExclusionsDoNotBridge() {
        val store = ConversationStore(root); val r = ConversationRecorder(store)
        r.packet(packet, now, "fake", false)
        r.beginExclusion("memo", "memo", now+20)
        assertFalse(r.packet(packet, now+20,"fake",false))
        r.endExclusion("memo",now+1000); r.packet(packet,now+1000,"fake",false)
        r.close() // same path as mute / service stop
        r.packet(packet, now+2000,"fake",false); r.drop(now+2020,"drop")
        r.packet(packet,now+2040,"fake",false); r.close()
        // A drop (lost audio) anchors a new segment inside the same conversation; only close/mute ends it.
        assertEquals(2,store.all().size); assertEquals(3840,store.all().sumOf { it.samples })
        assertEquals(2,store.all().first().manifest.segments.size); store.all().first().manifest.validate()
    }
    @Test fun submillisecondFrameEndpointsAndBackwardClockRemainExact() {
        val store=ConversationStore(root)
        val shortPacket=byteArrayOf(0x80.toByte(),0) // 2.5ms CELT packet
        ConversationRecorder(store).use { r ->
            repeat(400) { r.packet(shortPacket,now,"fake",false) }
        }
        val m=store.all().single().manifest
        assertEquals(1.0,m.speechSec,0.0)
        assertEquals(1000,java.time.Duration.between(Instant.parse(m.startedAt),Instant.parse(m.endedAt)).toMillis())
        assertEquals(1.0,m.segments.single().durationSec,0.0)
        m.validate()
    }
    @Test fun manifestHasOnlyContractFieldsTimezoneAndFrameAccurateAnchors() {
        val r=speech(ConversationStore(root)); r.manifest.validate()
        val json=privateJson.encodeToJsonElement(ConversationManifest.serializer(),r.manifest).jsonObject
        assertEquals(setOf("clientId","device","startedAt","endedAt","speechSec","tz","segments","fixes"),json.keys)
        assertEquals("glasses",json.s("device")); assertEquals("America/Chicago",json.s("tz"))
        assertEquals(setOf("offsetSec","at","durationSec"),json.a("segments").first().jsonObject.keys)
        assertEquals(30.0,r.manifest.speechSec,0.0)
        assertThrows(java.time.DateTimeException::class.java) { r.manifest.copy(tz="invalid/zone").validate() }
        assertThrows(IllegalArgumentException::class.java) { r.manifest.copy(segments=listOf(ManifestSegment(1.0,r.manifest.startedAt,30.0))).validate() }
    }
    @Test fun restartTrimsUncommittedTailSealsConversationAndKeepsCorruptAudio() {
        val store=ConversationStore(root); var mono=0L
        val r=ConversationRecorder(store,monotonic={mono})
        r.packet(packet,now,"fake",false); r.checkpoint()
        val id=store.all().single().manifest.clientId
        r.packet(packet,now+20,"fake",false); r.abort()
        store.audio(id).appendBytes(byteArrayOf(1,2,3))
        val recovered=ConversationStore(root); ConversationRecorder(recovered).close()
        val row=recovered.get(id)!!; assertEquals("skipped",row.state); assertEquals(960,row.samples)
        var count=0; OggReader.audio(recovered.audio(id)) { _,_->count++ }; assertEquals(1,count)
        ConversationRecorder(recovered).use { it.packet(packet,now+2000,"fake",false) }
        assertEquals(2,recovered.all().size)
        val badRoot=root.resolve("bad");val badStore=ConversationStore(badRoot)
        val bad=ConversationRecorder(badStore);bad.packet(packet,now,"fake",false);bad.abort()
        val file=badStore.audio(badStore.all().single().manifest.clientId);file.writeBytes(byteArrayOf(7))
        val read=ConversationStore(badRoot);ConversationRecorder(read).close()
        assertEquals("failed",read.all().single().state);assertArrayEquals(byteArrayOf(7),file.readBytes())
    }
    @Test fun sizeCapClosesBeforeNinetyMbAndFixesAreOptionalValidated() {
        val store=ConversationStore(root)
        ConversationRecorder(store,sizeCap=1000).use { r ->
            repeat(40) { r.packet(packet,now+it*20,"fake",false) }
            val id=store.all().first().manifest.clientId
            r.fix(GeoFix(id,now,lat=Double.NaN,lon=0.0,source="fake",confidence="fake"))
            r.fix(GeoFix(id,now,lat=41.0,lon=-87.0,source="fake",confidence="fake"))
        }
        assertTrue(store.all().size>1)
        store.all().forEach { assertTrue(store.audio(it.manifest.clientId).length()<1000);it.manifest.validate() }
        assertEquals(1,store.all().sumOf { it.manifest.fixes.size })
    }
    private var responseCode=200
    private var network=true
    private var wifi=false
    private var failPut=false
    private var remoteState="timed"
    private val calls= mutableListOf<String>()
    private val gateway=object:GatewayClient("https://fake.invalid","synthetic") {
        override suspend fun exchange(path:String,method:String,body:JsonElement?,etag:String?,audio:File?):GatewayResponse {
            calls += "$method $path"
            if(method=="PUT" && failPut) error("synthetic death")
            return GatewayResponse(responseCode,"{\"id\":\"server-id\",\"state\":\"$remoteState\"}")
        }
    }
    @Test fun queueStagesResumeAcrossDeathAndSuccessfulUpload()=runTest {
        val store=ConversationStore(root);val row=speech(store);failPut=true
        ConversationUploader(store,gateway,{network},{wifi},{now}).tick()
        assertEquals("server-id",store.get(row.manifest.clientId)!!.serverId)
        assertEquals("waiting",store.get(row.manifest.clientId)!!.state)
        now+=5000;failPut=false;responseCode=200
        val restored=ConversationStore(root)
        ConversationUploader(restored,gateway,{network},{wifi},{now}).tick()
        assertTrue(restored.get(row.manifest.clientId)!!.audioSent)
        assertEquals(1,calls.count { it.startsWith("POST") })
        responseCode=200;now+=60000
        ConversationUploader(restored,gateway,{network},{wifi},{now}).tick()
        assertEquals("transcribed",restored.get(row.manifest.clientId)!!.state)
        val count=calls.size;ConversationUploader(ConversationStore(root),gateway,{network},{wifi},{now}).tick();assertEquals(count,calls.size)
    }
    @Test fun queueWifi429GlobalBackoff400TerminalAndRetention()=runTest {
        val store=ConversationStore(root);val row=speech(store)
        wifi=true
        var onWifi=false
        fun uploader(s:ConversationStore=store)=ConversationUploader(s,gateway,{only->network&&(!only||onWifi)},{wifi},{now})
        uploader().tick();assertTrue(calls.isEmpty())
        onWifi=true;responseCode=429;uploader().tick()
        assertEquals(now+1800000,store.blockedUntil)
        val restored=ConversationStore(root);uploader(restored).tick();assertEquals(1,calls.size)
        now+=1799999;uploader(restored).tick();assertEquals(1,calls.size)
        now++;responseCode=400;uploader(restored).tick()
        assertEquals("failed",restored.get(row.manifest.clientId)!!.state)
        val count=calls.size;now+=86400000;uploader(restored).tick();assertEquals(count,calls.size)
        restored.save(restored.get(row.manifest.clientId)!!.copy(state="failed",audioSent=true)) // processing failure after successful audio upload
        restored.retain(now+31L*86400000,30)
        assertFalse(restored.audio(row.manifest.clientId).exists());assertNull(restored.get(row.manifest.clientId))
        restored.saveIfPresent(row.copy(state="transcribed",audioSent=true))
        assertNull(restored.get(row.manifest.clientId)) // an in-flight poll cannot resurrect retained data
    }
}
