package io.lampyr.glasses.ambient

import io.lampyr.glasses.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionRuntimeTest {
    private lateinit var root: File
    @Before fun before() { root=Files.createTempDirectory("recognition-runtime").toFile(); Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain(); root.deleteRecursively() }
    @Test fun inferenceIsOffAudioThreadBusyTicksDropAndMuteInvalidatesPendingWork()=runTest {
        val gateway=object:GatewayClient("https://fake.invalid","synthetic") {
            override suspend fun exchange(path:String,method:String,body:JsonElement?,etag:String?,audio:File?)=GatewayResponse(304)
        }
        val engine=PeopleEngine(root.resolve("people.json"),gateway)
        val model=SpeakerModel(root.resolve("model"),{false})
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val count=AtomicInteger();var listening=true
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val audioThread=java.lang.Thread.currentThread().id
        val recognizer=OnDeviceSpeakerRecognizer(scope,model,engine,{listening},{.55f},embedOverride={
            assertNotEquals(audioThread,java.lang.Thread.currentThread().id)
            assertEquals(24000,it.size)
            count.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS)
            finished.countDown();FloatArray(256) { i->if(i==0)1f else 0f }
        },modelReady={true})
        try {
            recognizer.recent(ShortArray(71999) { 1000 },1,1000)
            assertEquals(0,count.get())
            recognizer.recent(shortArrayOf(1000),1,1010)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            recognizer.recent(ShortArray(144000) { 1000 },1,5500)
            assertEquals(1,count.get())
            listening=false;recognizer.reset();release.countDown()
            assertTrue(finished.await(5,TimeUnit.SECONDS))
            recognizer.recent(ShortArray(144000) { 1000 },1,8500)
            assertEquals(1,count.get());assertTrue(engine.merge(PeopleSnapshot()).heard.isEmpty())
        } finally { release.countDown();recognizer.close();scope.cancel() }
    }
    @Test fun missingModelNeverEmbeds()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val gateway=GatewayClient("https://fake.invalid","synthetic")
        val model=SpeakerModel(root.resolve("model"),{false})
        var calls=0
        val r=OnDeviceSpeakerRecognizer(scope,model,PeopleEngine(root.resolve("people"),gateway),{true},{.55f},embedOverride={calls++;FloatArray(256)})
        try { r.recent(ShortArray(288000),1,10000); assertEquals(0,calls) } finally { r.close();scope.cancel() }
    }
    @Test fun recognitionDecoderIsSkippedForNonSpeechAndUnavailableModel()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var ready=false; var creates=0
        val recognized=CountDownLatch(1)
        val r=object:RecentSpeakerRecognizer {
            override val ready get()=ready
            override fun recent(pcm:ShortArray,channels:Int,utc:Long):SpeakerMatch { recognized.countDown();return SpeakerMatch() }
        }
        val worker=RecognitionAudioWorker(scope,{r},{true},decoderFactory={ output ->
            creates++
            object:PacketPcmDecoder {
                override fun packet(bytes:ByteArray,timestampUs:Long) { output(ShortArray(960),1,timestampUs) }
                override fun close() {}
            }
        })
        val p=AmbientPacket(byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte()),1000,"lifelog-mono")
        try {
            repeat(100) { worker.offer(p) }
            ready=true;repeat(100) { worker.offer(p.copy(speech=false)) }
            assertEquals(0,creates)
            worker.offer(p);assertTrue(recognized.await(5,TimeUnit.SECONDS));assertEquals(1,creates)
        } finally { worker.close();scope.cancel() }
    }
    @Test fun stalledRecognitionDecoderCannotBlockRecordingOrDropItsPackets()=runTest {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val recognized=AtomicInteger()
        val r=RecentSpeakerRecognizer { _,_,_ -> recognized.incrementAndGet();SpeakerMatch() }
        val worker=RecognitionAudioWorker(scope,{r},{true},decoderFactory={ output ->
            object:PacketPcmDecoder {
                override fun packet(bytes:ByteArray,timestampUs:Long) {
                    entered.countDown();release.await(5,TimeUnit.SECONDS);output(ShortArray(960),1,timestampUs);finished.countDown()
                }
                override fun close() {}
            }
        })
        val p=AmbientPacket(byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte()),1000,"lifelog-mono")
        try {
            worker.offer(p);assertTrue(entered.await(5,TimeUnit.SECONDS))
            val store=ConversationStore(root.resolve("conversations"))
            ConversationRecorder(store,monotonic={0}).use { recorder ->
                repeat(200) { i ->
                    assertTrue(recorder.packet(p.opus,p.utc+i*20,"lifelog-mono",false))
                    worker.offer(p.copy(utc=p.utc+i*20))
                }
            }
            assertEquals(200*960L,store.all().single().samples)
            assertEquals(1,store.all().size)
            worker.reset();release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));assertEquals(0,recognized.get())
        } finally { release.countDown();worker.close();scope.cancel() }
    }

}
