package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ProgressVisibility
import com.paymentstraininghub.app.data.model.Chapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressVisibilityTest {

    private fun chapter(number: Int, lessonIds: List<String>): Chapter {
        return Chapter(
            id = "ch$number",
            number = number,
            title = "Chapter $number",
            domain = "",
            lessonCount = lessonIds.size,
            lessonIds = lessonIds
        )
    }

    @Test
    fun freshUserHasNoMeaningfulProgress() {
        val chapters = listOf(chapter(1, listOf("ch1s1", "ch1s2")), chapter(2, listOf("ch2s1")))
        assertFalse(ProgressVisibility.hasCompletedChapter(emptySet(), chapters))
        assertFalse(ProgressVisibility.hasCompletedChapter(emptySet(), emptyList()))
    }

    @Test
    fun partialChapterIsNotMeaningfulProgress() {
        val chapters = listOf(chapter(1, listOf("ch1s1", "ch1s2")))
        assertFalse(ProgressVisibility.hasCompletedChapter(setOf("ch1s1"), chapters))
    }

    @Test
    fun oneCompletedChapterIsMeaningfulProgress() {
        val chapters = listOf(
            chapter(1, listOf("ch1s1", "ch1s2")),
            chapter(2, listOf("ch2s1", "ch2s2"))
        )
        assertTrue(
            ProgressVisibility.hasCompletedChapter(setOf("ch1s1", "ch1s2"), chapters)
        )
    }

    @Test
    fun emptyChapterNeverCounts() {
        val chapters = listOf(chapter(1, emptyList()))
        assertFalse(ProgressVisibility.hasCompletedChapter(setOf("ch9s9"), chapters))
    }
}
