package io.lampyr.glasses.ambient

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SpeakerModelTest {
    @Test fun resumeAndChecksumFailureNeverActivateAnUnverifiedModel()=runBlocking {
        val root=Files.createTempDirectory("model-fake").toFile()
        val ranges=mutableListOf<String?>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();ranges+=request.header("Range")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if(ranges.size==1)206 else 200).message("synthetic")
                .header("Content-Range","bytes 3-5/6").body("def".toResponseBody()).build()
        }.build()
        try {
            root.resolve("wespeaker.part").writeText("abc")
            val model=SpeakerModel(root,{true},client)
            model.download(true)
            assertEquals(listOf("bytes=3-",null,null),ranges)
            assertFalse(model.ready);assertFalse(model.file.exists())
            assertTrue(model.status.value.startsWith("failed"))
            assertFalse(root.resolve("wespeaker.part").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun wifiOnlyRefusesDownloadBeforeOpeningNetworkAndMissingCheckIsOffline()=runBlocking {
        val root=Files.createTempDirectory("model-wifi").toFile();var calls=0
        val client=OkHttpClient.Builder().addInterceptor { calls++;error("No network is allowed in this test") }.build()
        try {
            val model=SpeakerModel(root,{only->!only},client)
            model.check();assertEquals("missing",model.status.value)
            model.download(true);assertEquals(0,calls);assertFalse(model.ready)
        } finally { root.deleteRecursively() }
    }
}
