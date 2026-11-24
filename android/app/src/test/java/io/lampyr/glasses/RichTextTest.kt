package io.lampyr.glasses

import io.lampyr.glasses.data.*
import io.lampyr.glasses.render.*
import org.junit.Assert.*
import org.junit.Test

class RichTextTest {
    @Test
    fun linkSummariesPreserveSurroundingTextAndUseAliases() {
        assertEquals(
            "\uE002 sent an Instagram reel",
            summarizeLinks("https://www.instagram.com/reel/abc/?id=2"),
        )
        assertEquals(
            "watch \uE002 Instagram reel please",
            summarizeLinks("watch https://instagram.com/reels/abc please"),
        )
        assertEquals(
            "go \uE001 link to YouTube and \uE001 link to nytimes",
            summarizeLinks("go https://youtu.be/123 and https://www.nytimes.com/2026/news"),
        )
        assertEquals(
            "hi \uE001 link to example",
            summarizeLinks("hi https://www.example.com/path?x=1"),
        )
    }

    @Test
    fun richTextWrapsSpacesEmojiNewlinesAndLongWords() {
        val lines = layout("a b ❤️ c\nabcdefghij", 48)
        assertEquals(listOf("a", "b"), lines[0].mapNotNull { it.w })
        assertEquals("❤️", lines[1].first().e)
        assertTrue(lines.all { lineWidth(it) <= 48 })
        assertEquals(2, pages("one\ntwo\nthree", 120, 2).size)
        assertEquals(1, pages("", 120, 2).size)
        assertEquals("Élan…", fit("Élan very long", 100))
    }

    @Test
    fun graphemesKeepVariationSelectorsAndSurrogates() {
        assertEquals(listOf("a", "❤️", "😂"), graphemes("a❤️😂"))
        assertEquals("CAFE - ...", norm5("café — …"))
    }

    @Test
    fun notificationSortUsesImportanceTimesDecay() {
        val now = 100000000L
        val stale = Notification("old", "joy", "joy", "old", "", 1.0, now - 24 * 3600000)
        val fresh = Notification("new", "joy", "joy", "new", "", .3, now)
        val mid = Notification("mid", "joy", "joy", "mid", "", .8, now - 30 * 60000)
        assertEquals(
            listOf("mid", "new", "old"),
            sortNotifications(listOf(stale, fresh, mid), now).map { it.id },
        )
    }

    @Test
    fun textGridClipsAndAligns() {
        val grid = TextGrid(8, 3)
        grid.put(-1, 0, "abc")
        grid.putR(1, "hi")
        grid.center(2, "ok")
        assertEquals(listOf("BC      ", "      HI", "   OK   "), grid.lines())
        grid.clear()
        assertTrue(grid.lines().all { it.isBlank() })
    }
}
