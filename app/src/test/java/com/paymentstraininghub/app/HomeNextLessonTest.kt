// HomeNextLessonTest.kt
// Unit tests for the merged Home's one next-lesson rule, the
// "where you left off" lookup, and the course-parts builder.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitLearningEvent
import com.paymentstraininghub.app.ui.home.buildBookParts
import com.paymentstraininghub.app.ui.home.lastCompletedLessonId
import com.paymentstraininghub.app.ui.home.resolveNextLesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNextLessonTest {

    private fun chapter(id: String, number: Int, lessons: List<String>) = Chapter(
        id = id,
        number = number,
        title = "Chapter $number",
        domain = "Test",
        lessonCount = lessons.size,
        lessonIds = lessons
    )

    private fun entry(id: String) = SearchEntry(
        id = id,
        title = "Lesson $id",
        url = "",
        type = "lesson",
        text = ""
    )

    private fun journey(chapterNumbers: List<Int>) = LearningPath(
        id = "test-journey",
        title = "Test Journey",
        description = "A test journey.",
        level = "Foundation",
        systemImage = "book.fill",
        objectives = emptyList(),
        chapterNumbers = chapterNumbers,
        situationIds = emptyList(),
        capstoneSituationId = "",
        assessmentDimension = CompetencyDimension.LIFECYCLE,
        certificateTitle = null,
        entryExpectations = "",
        completionCriteria = ""
    )

    private val chapters = listOf(
        chapter("ch1", 1, listOf("ch1s1", "ch1s2")),
        chapter("ch2", 2, listOf("ch2s1", "ch2s2"))
    )
    private val orderedIds = listOf("ch1s1", "ch1s2", "ch2s1", "ch2s2")
    private val entries = orderedIds.map(::entry)
    private val titles = entries.associateBy({ it.id }, { it.title })

    @Test
    fun noJourneyFollowsReadingOrder() {
        val next = resolveNextLesson(orderedIds, chapters, titles, entries, setOf("ch1s1"), null)
        assertEquals("ch1s2", next?.lessonId)
        assertFalse(next?.fromJourney ?: true)
        assertFalse(next?.allComplete ?: true)
    }

    @Test
    fun newLearnerStartsAtTheFirstLesson() {
        val next = resolveNextLesson(orderedIds, chapters, titles, entries, emptySet(), null)
        assertEquals("ch1s1", next?.lessonId)
    }

    @Test
    fun chosenJourneyWinsOverReadingOrder() {
        // The journey covers chapter 2 only. Reading order would
        // say ch1s1; the journey says ch2s1. Home must say ch2s1.
        val next = resolveNextLesson(
            orderedIds, chapters, titles, entries, emptySet(), journey(listOf(2))
        )
        assertEquals("ch2s1", next?.lessonId)
        assertTrue(next?.fromJourney ?: false)
        assertEquals("Test Journey", next?.journeyTitle)
    }

    @Test
    fun completedJourneyFallsBackToReadingOrder() {
        val next = resolveNextLesson(
            orderedIds, chapters, titles, entries,
            setOf("ch2s1", "ch2s2"), journey(listOf(2))
        )
        assertEquals("ch1s1", next?.lessonId)
        assertFalse(next?.fromJourney ?: true)
    }

    @Test
    fun everythingCompleteReviewsTheFirstLesson() {
        val next = resolveNextLesson(
            orderedIds, chapters, titles, entries, orderedIds.toSet(), null
        )
        assertEquals("ch1s1", next?.lessonId)
        assertTrue(next?.allComplete ?: false)
    }

    @Test
    fun emptyCourseHasNoNextLesson() {
        assertNull(resolveNextLesson(emptyList(), emptyList(), emptyMap(), emptyList(), emptySet(), null))
    }

    @Test
    fun lastCompletedIsTheLatestCompletionEvent() {
        val events = listOf(
            HabitLearningEvent("lessonCompleted", "ch1s1", 1000L),
            HabitLearningEvent("lessonCompleted", "ch1s2", 3000L),
            HabitLearningEvent("lessonCompleted", "ch2s1", 2000L),
            HabitLearningEvent("other", "ch2s2", 9999L)
        )
        assertEquals("ch1s2", lastCompletedLessonId(events))
        assertNull(lastCompletedLessonId(emptyList()))
        assertNull(lastCompletedLessonId(listOf(HabitLearningEvent("other", "ch1s1", 1L))))
    }

    @Test
    fun bookPartsFollowTheCatalogueAndSkipUnmappedChapters() {
        // ch1 and ch2 are in part 1; ch37 is in no part.
        val parts = buildBookParts(
            listOf(
                chapter("ch1", 1, listOf("ch1s1")),
                chapter("ch2", 2, listOf("ch2s1")),
                chapter("ch37", 37, listOf("ch37s1"))
            )
        )
        assertEquals(10, parts.size)
        val part1 = parts.first { it.number == 1 }
        assertEquals(listOf("ch1", "ch2"), part1.chapters.map { it.chapter.id })
        assertTrue(parts.none { part -> part.chapters.any { it.chapter.id == "ch37" } })
        assertEquals("Foundations of Money and Payments", part1.title)
    }
}
