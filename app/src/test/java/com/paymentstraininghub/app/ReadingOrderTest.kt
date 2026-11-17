// ReadingOrderTest.kt
// Unit tests for the single display-numbering resolver. The bundled
// map is reading-order v1.1: 99 book chapters in reading order plus
// held-out ch37 with no book number. The version and status are
// pinned deliberately: a map update changes them on purpose, and
// the test author updates the pins with it.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ReadingOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingOrderTest {

    @Test
    fun bundledMapVersionAndStatusArePinned() {
        assertEquals("v1.1-numbering-1115", ReadingOrder.version)
        assertEquals("active", ReadingOrder.status)
    }

    @Test
    fun ninetyNineChaptersWithSequentialBookNumbers() {
        val chapters = ReadingOrder.chapters
        assertEquals(99, chapters.size)
        assertEquals((1..99).toList(), chapters.map { it.bookNumber }.sorted())
        assertEquals(99, chapters.map { it.internalId }.toSet().size)
    }

    @Test
    fun tenPartsCoverAllChapters() {
        assertEquals((1..10).toList(), ReadingOrder.parts.map { it.first })
        assertEquals("Foundations of Money and Payments", ReadingOrder.parts.first().second)
    }

    @Test
    fun ch37IsHeldOutWithNoBookNumber() {
        assertNull(ReadingOrder.bookNumberFor("ch37"))
        assertTrue(ReadingOrder.heldOutIds.contains("ch37"))
        assertFalse(ReadingOrder.orderedChapterIds.contains("ch37"))
        assertEquals(Int.MAX_VALUE, ReadingOrder.chapterSortKey("ch37"))
    }

    @Test
    fun knownChapterMappings() {
        assertEquals(1, ReadingOrder.bookNumberFor("ch1"))
        // The misleading pair from the owner report: catalogue
        // neighbours ch8 and ch38 are book chapters 25 and 26.
        assertEquals(25, ReadingOrder.bookNumberFor("ch8"))
        assertEquals(26, ReadingOrder.bookNumberFor("ch38"))
        assertEquals(27, ReadingOrder.bookNumberFor("ch54"))
        assertEquals(96, ReadingOrder.bookNumberFor("ch100"))
        assertEquals(27, ReadingOrder.bookNumberForChapterNumber(54))
    }

    @Test
    fun displayNumbersFollowMapThenSNumber() {
        assertEquals(2, ReadingOrder.displayNumberFor("ch54s2"))
        assertNull(ReadingOrder.displayNumberFor("ch54s901-supp"))
        // A lesson added after the map was built takes its
        // s-number position.
        assertEquals(99, ReadingOrder.displayNumberFor("ch54s99"))
        assertEquals("Lesson 2", ReadingOrder.lessonLabel("ch54s2"))
        assertNull(ReadingOrder.lessonLabel("ch54s901-supp"))
    }

    @Test
    fun chapterLessonLabelsNeverShowInternalIds() {
        assertEquals("Chapter 27, Lesson 2", ReadingOrder.chapterLessonLabel("ch54s2"))
        assertEquals("Chapter 27", ReadingOrder.chapterLessonLabel("ch54s901-supp"))
        assertEquals(
            "Held Out Chapter",
            ReadingOrder.chapterLessonLabel("ch37s1", "Held Out Chapter")
        )
        assertEquals("Lesson", ReadingOrder.chapterLessonLabel("ch999s1"))
    }

    @Test
    fun displayChapterLineStripsStaleInternalPrefix() {
        assertEquals(
            "Chapter 27: SWIFT Fundamentals",
            ReadingOrder.displayChapterLine("ch54s2", "SWIFT Fundamentals")
        )
        assertEquals("Chapter 27", ReadingOrder.displayChapterLine("ch54s2", "Chapter 54"))
        assertEquals("Some Name", ReadingOrder.displayChapterLine("ch37s1", "Some Name"))
        assertEquals("Chapter", ReadingOrder.displayChapterLine("ch37s1", ""))
    }

    @Test
    fun bookSequenceOrdersByBookThenLesson() {
        val sequence = ReadingOrder.bookSequence(
            mapOf(
                "ch54" to listOf("ch54s2", "ch54s901-supp", "ch54s1"),
                "ch1" to listOf("ch1s1")
            )
        )
        // Supplemental lessons follow their chapter's catalogue
        // lessons, matching iOS v84.
        assertEquals(listOf("ch1s1", "ch54s1", "ch54s2", "ch54s901-supp"), sequence)
    }

    @Test
    fun bookSequenceExcludesHeldOutChapters() {
        val sequence = ReadingOrder.bookSequence(
            mapOf(
                "ch37" to listOf("ch37s1"),
                "ch1" to listOf("ch1s1")
            )
        )
        assertEquals(listOf("ch1s1"), sequence)
    }

    @Test
    fun nextAndPreviousCrossChapterBoundary() {
        val sequence = listOf("ch1s1", "ch1s2", "ch2s1")
        assertEquals("ch2s1", ReadingOrder.nextLesson("ch1s2", sequence))
        assertEquals("ch1s2", ReadingOrder.previousLesson("ch2s1", sequence))
        assertNull(ReadingOrder.nextLesson("ch2s1", sequence))
        assertNull(ReadingOrder.previousLesson("ch1s1", sequence))
        assertNull(ReadingOrder.nextLesson("missing", sequence))
    }

    @Test
    fun parseMapRejectsBadInput() {
        assertNull(ReadingOrder.parseMap(""))
        assertNull(ReadingOrder.parseMap("not json"))
        assertNull(ReadingOrder.parseMap("{}"))
        assertNull(ReadingOrder.parseMap("{\"version\":\"x\",\"parts\":[]}"))
        assertNull(ReadingOrder.parseMap("{\"version\":\"\",\"parts\":[{\"part\":1,\"title\":\"P\",\"chapters\":[]}]}"))
    }

    @Test
    fun applyRemoteAndResetToBundled() {
        val minimal = "{\"version\":\"test-remote\",\"status\":\"draft\"," +
            "\"parts\":[{\"part\":1,\"title\":\"P\",\"chapters\":" +
            "[{\"id\":\"ch1\",\"book_number\":1,\"lessons\":" +
            "[{\"id\":\"ch1s1\",\"display_number\":1}]}]}]}"
        try {
            assertTrue(ReadingOrder.applyRemote(minimal))
            assertEquals("test-remote", ReadingOrder.version)
            assertEquals(1, ReadingOrder.chapters.size)
            assertFalse(ReadingOrder.applyRemote("garbage"))
            assertEquals("test-remote", ReadingOrder.version)
        } finally {
            ReadingOrder.resetToBundled()
        }
        assertEquals("v1.1-numbering-1115", ReadingOrder.version)
        assertEquals(99, ReadingOrder.chapters.size)
    }
}
