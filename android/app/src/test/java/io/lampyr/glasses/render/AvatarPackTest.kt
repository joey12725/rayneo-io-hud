package io.lampyr.glasses.render

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import io.lampyr.glasses.render.avatar.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Avatar packs: manifest v1 parsing, fallbacks, playback timing, scaling cache, import and graceful failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarPackTest {
    @get:Rule val temp = TemporaryFolder()

    private fun manifest(states: String = DEFAULT_STATES, extra: String = "") = """
        {"schema":1,"id":"test-pack","name":"Test","license":"CC0-1.0","cell":{"w":10,"h":15},
         "atlas":{"file":"atlas.png","cols":4},"threshold":100,"states":{$states}$extra}
    """.trimIndent()

    // ---------- manifest parsing ----------
    @Test fun parsesSchemaV1() {
        val m = AvatarManifest.parse(manifest(extra = ""","portraitCrop":[1,2,8,8],"fallbacks":{"searching":"thinking"}"""))
        assertEquals("test-pack", m.id); assertEquals("Test", m.name); assertEquals("CC0-1.0", m.license)
        assertEquals(10, m.cellW); assertEquals(15, m.cellH); assertEquals(4, m.cols); assertEquals("atlas.png", m.atlasFile)
        assertEquals(listOf(1, 2, 8, 8), m.portraitCrop); assertEquals(100, m.threshold)
        assertEquals(Playback.LOOP, m.states.getValue("idle").playback)
        assertEquals(listOf(AvatarFrame(0, 350), AvatarFrame(1, 350), AvatarFrame(2, 450)), m.states.getValue("idle").frames)
        assertEquals(mapOf("searching" to "thinking"), m.fallbacks)
        assertEquals(6, m.cellCount); assertEquals(2, m.rows)
    }

    @Test fun defaultsPortraitCropToACentredSquare() {
        assertEquals(listOf(0, 0, 10, 10), AvatarManifest.parse(manifest()).portraitCrop)
    }

    @Test fun rejectsInvalidManifests() {
        fun bad(text: String, expect: String) {
            val e = runCatching { AvatarManifest.parse(text) }.exceptionOrNull()
            assertTrue("expected failure for $expect", e is AvatarPackException)
            assertTrue("${e!!.message} should mention $expect", e.message!!.contains(expect))
        }
        bad("not json", "not JSON")
        bad(manifest().replace("\"schema\":1", "\"schema\":2"), "unsupported schema")
        bad(manifest().replace("\"test-pack\"", "\"../evil\""), "id")
        bad(manifest().replace("\"atlas.png\"", "\"../atlas.png\""), "atlas.file")
        bad(manifest(states = """"idle":{"playback":"bounce","frames":[{"cell":0,"ms":100}]}"""), "unknown playback")
        bad(manifest(states = """"idle":{"playback":"loop","frames":[]}"""), "no frames")
        bad(manifest(states = """"Idle!":{"playback":"loop","frames":[{"cell":0,"ms":100}]}"""), "bad state name")
        bad(manifest(extra = ""","portraitCrop":[5,5,10,10]"""), "portraitCrop")
        bad(manifest(states = ""), "states is empty")
    }

    // ---------- fallback chain ----------
    @Test fun missingStatesFollowPackFallbacksThenBuiltInsThenIdle() {
        val m = AvatarManifest.parse(manifest(extra = ""","fallbacks":{"searching":"speaking","loop_a":"loop_b","loop_b":"loop_a"}"""))
        assertEquals("idle", m.resolve("idle"))
        assertEquals("speaking", m.resolve("speaking"))
        assertEquals("speaking", m.resolve("searching"))            // pack fallback wins over the built-in (working)
        assertEquals("speaking", m.resolve("teaching"))             // built-in: teaching -> speaking
        assertEquals("speaking", m.resolve("debating"))
        assertEquals("thinking", m.resolve("checking_watch"))       // built-in chain checking_watch -> waiting -> thinking
        assertEquals("idle", m.resolve("not-a-state"))              // unknown -> idle
        assertEquals("idle", m.resolve("loop_a"))                   // cycles stop
    }

    @Test fun packWithoutIdleUsesItsFirstState() {
        val m = AvatarManifest.parse(manifest(states = """"speaking":{"playback":"loop","frames":[{"cell":0,"ms":100}]}"""))
        assertEquals("speaking", m.resolve("idle")); assertEquals("speaking", m.resolve("thinking"))
    }

    @Test fun everyCanonicalStateHasABuiltInPathToIdle() {
        assertEquals(50, AvatarStates.ALL.size); assertEquals(50, AvatarStates.ALL.toSet().size)
        AvatarStates.ALL.filter { it != AvatarStates.IDLE }.forEach { s ->
            var cur: String? = s; val seen = mutableSetOf<String>()
            while (cur != null && cur != AvatarStates.IDLE) { assertTrue("cycle at $s", seen.add(cur)); cur = AvatarStates.BUILTIN_FALLBACKS[cur] }
            assertEquals("$s reaches idle", AvatarStates.IDLE, cur)
        }
        assertEquals("happy", AvatarStates.fromLegacyId(17)); assertEquals("checking_watch", AvatarStates.fromLegacyId(50)); assertNull(AvatarStates.fromLegacyId(51))
        assertEquals("thinking", AvatarStates.forStatus("thinking")); assertEquals("idle", AvatarStates.forStatus("compacting")); assertEquals("idle", AvatarStates.forStatus(null))
    }

    // ---------- playback timing (identical to the former atlas: 350/350/450 ms frames) ----------
    private fun clip(p: String, bounded: String = "") = AvatarManifest.parse(manifest(states =
        """"idle":{"playback":"$p"$bounded,"frames":[{"cell":0,"ms":350},{"cell":1,"ms":350},{"cell":2,"ms":450}]}""")).states.getValue("idle")

    @Test fun loopAndSubtleLoopWrapAround() {
        for (p in listOf("loop", "subtle_loop")) {
            val c = clip(p)
            assertEquals(listOf(0, 0, 1, 2, 2, 0, 1, 1), listOf(0.0, .3, .4, .8, 1.1, 1.2, 1.6, 12.0).map { c.frameIndex(0.0, it) })
        }
    }

    @Test fun onceAndOnceThenHoldStopOnTheLastFrame() {
        for (p in listOf("once", "once_then_hold")) {
            val c = clip(p)
            assertEquals(listOf(0, 1, 2, 2, 2), listOf(0.0, .4, .8, 1.2, 60.0).map { c.frameIndex(0.0, it) })
        }
    }

    @Test fun boundedLoopLoopsForThreeSecondsThenHolds() {
        val c = clip("bounded_loop")
        assertEquals(0, c.frameIndex(0.0, 2.4))    // 2.4 % 1.15 = 0.1 -> frame 0 (still looping)
        assertEquals(1, c.frameIndex(0.0, 2.9))    // 2.9 % 1.15 = 0.6 -> frame 1
        assertEquals(2, c.frameIndex(0.0, 3.0))    // looping stopped at 3 s: elapsed past the clip -> last frame
        val custom = clip("bounded_loop", ""","boundedLoopSeconds":5""")
        assertEquals(1, custom.frameIndex(0.0, 4.0)) // 4.0 % 1.15 = 0.55
        assertEquals(2, custom.frameIndex(0.0, 5.0))
    }

    @Test fun timeBeforeTheStartShowsTheFirstFrame() {
        assertEquals(0, clip("loop").frameIndex(5.0, 4.0))
    }

    // ---------- rendering ----------
    private fun atlas(cols: Int = 4, rows: Int = 2, cw: Int = 10, ch: Int = 15): Bitmap =
        Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888).apply {
            eraseColor(BLACK)
            for (k in 0 until cols * rows) setPixel(k % cols * cw + k % cw, k / cols * ch + 1, GREEN) // one lit pixel per cell
        }

    @Test fun poolingLightsATargetPixelWhenAnySourcePixelIsBrightEnough() {
        val src = IntArray(4 * 4) { BLACK }
        src[5] = 0xff006400.toInt()   // green 100: lit
        src[15] = 0xff006300.toInt()  // green 99: not lit
        assertArrayEquals(booleanArrayOf(true, false, false, false), AvatarRaster.pool(src, 4, 4, 2, 2, 100))
        assertArrayEquals(BooleanArray(64) { it == 2 * 8 + 2 || it == 2 * 8 + 3 || it == 3 * 8 + 2 || it == 3 * 8 + 3 }, AvatarRaster.pool(src, 4, 4, 8, 8, 100))
    }

    @Test fun drawsCellsAndCachesScaledFramesInABoundedLru() {
        val pack = AtlasAvatarPack(AvatarManifest.parse(manifest()), atlas())
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val g = Gfx(Canvas(bmp), pack)
        repeat(40) { i -> g.drawAvatar("idle", 0.0, 0.0, 0, 0, 10 + i, 15) }
        assertEquals(AtlasAvatarPack.SCALED_CACHE, pack.scaled.size)
        g.clear(); g.drawAvatar("speaking", 0.0, 0.0, 0, 0, 10, 15)
        assertEquals(GREEN, bmp.getPixel(3, 1))   // cell 3 lights x = 3
        assertEquals(BLACK, bmp.getPixel(0, 1))
        assertTrue(pack.drawPortrait(g, 0, 0, 20))
    }

    @Test fun atlasSmallerThanTheManifestIsRejected() {
        val e = runCatching { AtlasAvatarPack(AvatarManifest.parse(manifest()), atlas(cols = 2)) }.exceptionOrNull()
        assertTrue(e is AvatarPackException)
    }

    @Test fun oversizedAtlasesAreRejectedFromTheHeaderBeforeDecoding() {
        val m = AvatarManifest.parse(manifest())
        val huge = runCatching { AvatarPacks.decode(png(Bitmap.createBitmap(AvatarPacks.MAX_ATLAS_SIDE + 1, 1, Bitmap.Config.ARGB_8888))) }.exceptionOrNull()
        assertTrue(huge is AvatarPackException && "too large" in huge.message.orEmpty())
        val wide = runCatching { AvatarPacks.decode(png(atlas(cols = 8)), m) }.exceptionOrNull()
        assertTrue(wide is AvatarPackException && "manifest describes" in wide.message.orEmpty())
        assertEquals(40, AvatarPacks.decode(png(atlas()), m).width)
    }

    @Test fun missingPackDrawsTheGracefulPlaceholder() {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val g = Gfx(Canvas(bmp), null)
        g.clear(); g.drawAvatar("thinking", 0.0, 1.0)
        val px = IntArray(W * H); bmp.getPixels(px, 0, W, 0, 0, W, H)
        val lit = px.indices.filter { (px[it] ushr 8 and 255) > 0 }
        assertTrue(lit.isNotEmpty())
        assertTrue(lit.all { it % W in 8 until 136 && it / W in 10 until 202 })
        // Icons fall back too.
        g.clear(); g.icon("avatar-portrait", 16, 18, 72)
        bmp.getPixels(px, 0, W, 0, 0, W, H); assertTrue(px.any { (it ushr 8 and 255) > 0 })
    }

    @Test fun bundledPlaceholderPackLoadsAndDrawsEveryCanonicalState() {
        val pack = AvatarPacks.fromAssets(RuntimeEnvironment.getApplication())
        assertEquals("default", pack.manifest.id); assertEquals("CC0-1.0", pack.manifest.license)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val g = Gfx(Canvas(bmp), pack)
        AvatarStates.ALL.forEach { s ->
            g.clear(); assertTrue(s, pack.draw(g, s, 0.0, 0.5, 8, 10, 128, 192))
            val px = IntArray(128 * 192); bmp.getPixels(px, 0, 128, 8, 10, 128, 192)
            assertTrue("$s draws something", px.any { it == GREEN })
        }
    }

    // ---------- the phone setting ----------
    private fun context(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = temp.root }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test fun importResetAndBrokenPacksFallBackToTheBundledPack() {
        val store = AvatarPackStore(context())
        assertEquals(AvatarPackStore.DEFAULT, store.setting)
        assertEquals("default", store.currentPack()!!.manifest.id)
        // A zipped folder (one top-level directory) imports and becomes current.
        val m = store.importZip(zip("my-pack/manifest.json" to manifest().toByteArray(), "my-pack/atlas.png" to png(atlas()), "../../escape.txt" to byteArrayOf(1)).inputStream())
        assertEquals("test-pack", m.id)
        assertEquals("file:test-pack", store.setting)
        assertEquals("test-pack", store.currentPack()!!.manifest.id)
        assertEquals("Test", store.currentInfo()!!.name)
        assertFalse(File(temp.root, "escape.txt").exists()); assertFalse(File(temp.root.parentFile, "escape.txt").exists())
        // A pack whose atlas is missing is refused and leaves the current pack alone.
        assertTrue(runCatching { store.importZip(zip("manifest.json" to manifest().replace("test-pack", "other").toByteArray()).inputStream()) }.exceptionOrNull() is AvatarPackException)
        assertEquals("file:test-pack", store.setting)
        // Corrupt files on disk degrade to the bundled pack.
        File(temp.root, "avatar/test-pack/atlas.png").writeText("not a png")
        assertEquals("default", store.currentPack()!!.manifest.id)
        store.resetToDefault()
        assertEquals(AvatarPackStore.DEFAULT, store.setting)
        assertEquals("default", store.currentPack()!!.manifest.id)
    }

    @Test fun urlImportsRequireHttps() {
        val e = runCatching { AvatarPackStore(context()).importPackFromUrl("http://example.invalid/pack.zip") }.exceptionOrNull()
        assertTrue(e is AvatarPackException)
    }

    companion object {
        private const val DEFAULT_STATES = """
            "idle":{"playback":"loop","frames":[{"cell":0,"ms":350},{"cell":1,"ms":350},{"cell":2,"ms":450}]},
            "speaking":{"playback":"loop","frames":[{"cell":3,"ms":200}]},
            "thinking":{"playback":"once","frames":[{"cell":4,"ms":200},{"cell":5,"ms":200}]}
        """
    }
}
