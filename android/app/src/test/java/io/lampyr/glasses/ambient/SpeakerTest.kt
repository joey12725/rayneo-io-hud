package io.lampyr.glasses.ambient

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class SpeakerTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/speaker/$name")!!.readBytes()
    private fun floats(name: String): FloatArray = ByteBuffer.wrap(bytes(name)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
    private fun signal() = ByteBuffer.wrap(bytes("synthetic.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b -> FloatArray(b.remaining()) { b.get().toFloat() } }
    @Test fun fbankMatchesIndependentKaldiReferenceAfterMeanSubtraction() {
        val actual = SpeakerFeatures.fbank(signal()).flatMap { it.asIterable() }
        val ref = floats("reference.f32")
        assertEquals(ref.size, actual.size)
        val error = actual.indices.maxOf { abs(actual[it] - ref[it]) }
        println("FBANK_MAX_ABS=$error")
        assertTrue("fbank max error $error", error <= .001f)
        assertTrue(SpeakerFeatures.fbank(FloatArray(399)).isEmpty())
    }
    @Test fun embeddingMatchesReferenceOptIn() {
        val path = System.getenv("SPEAKER_MODEL_PATH")
        assumeTrue("Set SPEAKER_MODEL_PATH for model parity", !path.isNullOrBlank())
        val directory = java.nio.file.Files.createTempDirectory("speaker-python-parity").toFile()
        try {
        val command = System.getenv("SPEAKER_REFERENCE_PYTHON")?.let { listOf(it) } ?: listOf("py", "-3.12")
        val process = ProcessBuilder(command + listOf("../tools/speaker_reference.py", "--model", path!!, "--output", directory.absolutePath))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("Python reference failed: $output", 0, process.waitFor())
        assertArrayEquals(bytes("synthetic.pcm"), File(directory,"synthetic.pcm").readBytes())
        val actual = SpeakerEmbedding(File(path)).use { it.embed(signal()) }
        val ref = ByteBuffer.wrap(File(directory,"embedding.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            .let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val cosine = actual.indices.sumOf { actual[it].toDouble() * ref[it] } /
            sqrt(actual.sumOf { it.toDouble().pow(2) } * ref.sumOf { it.toDouble().pow(2) })
        println("EMBEDDING_COSINE=$cosine")
        assertTrue(cosine >= .999)
        } finally { directory.deleteRecursively() }
    }
    private fun v(a: Float = 1f, b: Float = 0f) = List(256) { if (it == 0) a else if (it == 1) b else 0f }
    private fun person(id: String, a: Float = 1f, b: Float = 0f) = VoicePerson(id, id, profiles = listOf(VoiceProfile("p-$id", v(a, b))))
    @Test fun decisionRequiresTwoWindowsAndUsesPersonMargin() {
        val decision = SpeakerDecision(); val a = person("a"); val b = person("b", 0f, 1f)
        assertNull(decision.decide(v().toFloatArray(), listOf(a, b)))
        assertEquals("a", decision.decide(v().toFloatArray(), listOf(a, b))!!.person.id)
        assertNull(decision.decide(v(0f, 1f).toFloatArray(), listOf(a, b)))
        assertEquals("b", decision.decide(v(0f, 1f).toFloatArray(), listOf(a, b))!!.person.id)
        assertNull(decision.decide(v(1f, 1f).toFloatArray(), listOf(a, b)))
        assertNull(decision.decide(v().toFloatArray(), listOf(a, b)))
        val multiple = a.copy(profiles = a.profiles + VoiceProfile("other", v()))
        assertNotNull(decision.decide(v().toFloatArray(), listOf(multiple, b)))
    }
    @Test fun invalidVectorsEmptyAndSuppressedWinnersNeverBecomeNames() {
        for (people in listOf(emptyList(), listOf(person("a"), person("b")), listOf(person("a").copy(me = true)), listOf(person("a").copy(muted = true)))) {
            val d = SpeakerDecision()
            repeat(3) { assertNull(d.decide(v().toFloatArray(), people)) }
        }
        val d = SpeakerDecision(); val a = person("a")
        for (bad in listOf(FloatArray(256), FloatArray(3), FloatArray(256) { Float.NaN }, FloatArray(256) { Float.POSITIVE_INFINITY })) {
            assertNull(d.decide(bad, listOf(a)))
            assertNull(d.decide(v().toFloatArray(), listOf(a)))
        }
        repeat(2) { assertNull(d.decide(v().toFloatArray(), listOf(person("bad").copy(profiles = listOf(VoiceProfile("z", v(0f))))))) }
        assertThrows(IllegalArgumentException::class.java) { d.decide(v().toFloatArray(), listOf(a), .29f) }
        assertThrows(IllegalArgumentException::class.java) { d.decide(v().toFloatArray(), listOf(a), Float.NaN) }
    }
    @Test fun thresholdMarginAndUnlinked() {
        val a = person("a").copy(status = "unlinked", blurb = "")
        for (score in listOf(.299f, .54f, .551f, .9f)) {
            val d = SpeakerDecision(); val input = v(score, sqrt(1 - score * score)).toFloatArray()
            d.decide(input, listOf(a))
            assertEquals(score >= .55f, d.decide(input, listOf(a)) != null)
        }
        val competitor = person("b", .95f, sqrt(1-.95f*.95f))
        repeat(3) { assertNull(SpeakerDecision().decide(v().toFloatArray(), listOf(a, competitor))) }
    }
    @Test fun downmixAndResamplingRejectAliasesAndPreserveDuration() {
        assertArrayEquals(floatArrayOf(200f, -200f), SpeakerPcm.mono(shortArrayOf(100,300,-100,-300), 2), 0f)
        val low = FloatArray(48000) { (10000 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val high = FloatArray(48000) { (10000 * sin(2 * PI * 12000 * it / 48000)).toFloat() }
        val a = SpeakerPcm.resample(low, 48000); val b = SpeakerPcm.resample(high, 48000)
        assertEquals(16000, a.size)
        assertTrue(a.drop(100).dropLast(100).map { it * it }.average() > 40_000_000)
        assertTrue(b.drop(100).dropLast(100).map { it * it }.average() < 100)
    }
    @Test fun policyMatrixTestsThresholdMarginStabilityAndSuppressingWinners() {
        fun ref(id:String,score:Float)=person(id,score,sqrt(1-score*score))
        for(threshold in listOf(.30f,.55f,.90f)) for(best in listOf(.299f,.301f,.549f,.551f,.899f,.901f,1f))
            for(margin in listOf(0f,.079f,.081f,.2f)) {
                val d=SpeakerDecision();val people=listOf(ref("a",best),ref("b",best-margin))
                assertNull(d.decide(v().toFloatArray(),people,threshold))
                assertEquals("score=$best margin=$margin threshold=$threshold",best>=threshold&&margin>=.08f,
                    d.decide(v().toFloatArray(),people,threshold)!=null)
            }
        for(suppressed in listOf(ref("owner",1f).copy(me=true),ref("muted",1f).copy(muted=true))) {
            val d=SpeakerDecision();val people=listOf(suppressed,ref("other",.8f))
            repeat(3) { assertNull(d.decide(v().toFloatArray(),people)) }
            assertNull(d.decide(v(0f,1f).toFloatArray(),listOf(person("other",0f,1f))))
        }
        val d=SpeakerDecision();val a=ref("a",.7f);val b=ref("b",.6f)
        d.decide(v().toFloatArray(),listOf(a,b))
        d.decide(v().toFloatArray(),listOf(a,b))
        assertNull(d.decide(v(0f,1f).toFloatArray(),listOf(a,b))) // new winner or ambiguity breaks stability
    }
    @Test fun fixedRatioKernelMatchesOriginalIncludingEdges() {
        fun original(input: FloatArray): FloatArray {
            val cutoff = (16000.0 / 48000) * .94; val radius = ceil(24 / cutoff).toInt()
            return FloatArray(input.size / 3) { i ->
                val pos = i.toDouble() * 48000 / 16000
                var value = 0.0; var total = 0.0
                for (j in max(0, floor(pos).toInt() - radius)..min(input.lastIndex, ceil(pos).toInt() + radius)) {
                    val d = j - pos; val z = PI * cutoff * d
                    val w = cutoff * (if(abs(z)<1e-12)1.0 else sin(z)/z) * (.5+.5*cos(PI*d/(radius+1)))
                    value += input[j]*w; total += w
                }
                (if(total==0.0)0.0 else value/total).toFloat()
            }
        }
        val random = java.util.Random(42)
        for (n in listOf(0,1,2,3,31,48001)) {
            val pcm = FloatArray(n) { (random.nextDouble()*65534-32767).toFloat() }
            assertArrayEquals(original(pcm),SpeakerPcm.resample(pcm,48000),1e-4f)
        }
    }
    @Test fun sixSecondResampleAndFbankFitsRecognitionBudget() {
        val pcm = FloatArray(6*48000) { (10000*sin(2*PI*700*it/48000)).toFloat() }
        // Warm the JVM before measuring the complete production frontend.
        repeat(2) { SpeakerFeatures.fbank(SpeakerPcm.resample(pcm,48000)) }
        val start = System.nanoTime()
        val features = SpeakerFeatures.fbank(SpeakerPcm.resample(pcm,48000))
        val elapsedMs = (System.nanoTime()-start)/1_000_000
        println("SPEAKER_FRONTEND_6S_MS=$elapsedMs")
        assertEquals(598,features.size)
        assertTrue("Six-second frontend took ${elapsedMs}ms",elapsedMs < 150)
    }
    @Test fun sampleEnrollmentCosinesOptIn() {
        val keys = listOf("SPEAKER_MODEL_PATH","SPEAKER_SAMPLE_PCM","SPEAKER_VOICES_JSON","SPEAKER_SAMPLE_PROFILE")
        val settings = keys.map { System.getenv(it) }
        assumeTrue("Set all speaker sample diagnostic environment variables",settings.all { !it.isNullOrBlank() })
        val voices = PeopleEngine.parse(io.lampyr.glasses.data.gatewayJson.parseToJsonElement(File(settings[2]!!).readText()))
        val owner = voices.people.single { p -> p.profiles.any { it.id == settings[3] } }
        val profile = owner.profiles.single { it.id == settings[3] }.embedding.toFloatArray()
        val others = voices.people.filter { it.id != owner.id }.flatMap { it.profiles }.map { it.embedding.toFloatArray() }
        val pcm = ByteBuffer.wrap(File(settings[1]!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .let { b -> FloatArray(b.remaining()) { b.get().toFloat() } }
        require(pcm.size >= 30*16000) { "Sample must contain at least 30 seconds" }
        fun cosine(a:FloatArray,b:FloatArray):Double = a.indices.sumOf { a[it].toDouble()*b[it] } /
            sqrt(a.sumOf { it.toDouble()*it }*b.sumOf { it.toDouble()*it })
        SpeakerEmbedding(File(settings[0]!!)).use { model ->
            val vector = model.embed(pcm.copyOfRange(0,30*16000))
            val self = cosine(vector,profile)
            println("SAMPLE_SELF_COSINE=$self")
            println("SAMPLE_BEST_OTHER=${others.maxOfOrNull { cosine(vector,it) } ?: -1.0}")
            for (seconds in listOf(6,3)) {
                val size = seconds*16000; val selfScores = mutableListOf<Double>(); val otherScores = mutableListOf<Double>()
                for (start in 0..pcm.size-size step size/2) {
                    val window = model.embed(pcm.copyOfRange(start,start+size))
                    selfScores += cosine(window,profile); otherScores += others.maxOfOrNull { cosine(window,it) } ?: -1.0
                }
                val sorted = selfScores.sorted(); val middle = sorted.size/2
                val median = if(sorted.size%2==0)(sorted[middle-1]+sorted[middle])/2 else sorted[middle]
                println("WINDOW${seconds}_SELF_MIN=${sorted.first()}")
                println("WINDOW${seconds}_SELF_MEDIAN=$median")
                println("WINDOW${seconds}_OTHER_MAX=${otherScores.max()}")
            }
            assertTrue("Sample self cosine must be at least 0.9",self >= .9)
        }
    }

}
