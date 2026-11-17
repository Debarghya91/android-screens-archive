// ReadTimeTest.kt
// Unit tests for the honest timing helpers: the 200-words-per-
// minute read rate, and the listening labels on chapter totals
// and lesson rows.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.LessonDurations
import com.paymentstraininghub.app.data.ReadTime
import com.paymentstraininghub.app.data.SituationGating
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.data.readingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTimeTest {

    private fun words(n: Int): String = (1..n).joinToString(" ") { "word" }

    @Test
    fun emptyTextHasNoEstimate() {
        assertNull(ReadTime.minutesForText(""))
        assertNull(ReadTime.minutesForText("   \n\t "))
        assertNull(ReadTime.label(""))
    }

    @Test
    fun rateIs200WordsPerMinuteRoundedUp() {
        assertEquals(1, ReadTime.minutesForText(words(1)))
        assertEquals(1, ReadTime.minutesForText(words(200)))
        assertEquals(2, ReadTime.minutesForText(words(201)))
        assertEquals(2, ReadTime.minutesForText(words(400)))
        assertEquals(3, ReadTime.minutesForText(words(401)))
        assertEquals(4, ReadTime.minutesForText(words(739)))
    }

    @Test
    fun labelText() {
        assertEquals("About 2 min read", ReadTime.label(words(258)))
        assertEquals("About 1 min read", ReadTime.label(words(50)))
    }

    @Test
    fun situationGatingDelegatesToTheSameRate() {
        assertEquals(ReadTime.label(words(350)), SituationGating.readTimeLabel(words(350)))
    }

    @Test
    fun lessonReadingTextCoversTranscriptTakeawaysAndWorkItems() {
        val detail = LessonDetail(
            id = "ch1s1",
            title = "Test lesson",
            chapterTitle = "Chapter 1",
            audioFileName = "ch1s1.mp3",
            transcript = words(200),
            takeaways = listOf(words(100)),
            doThisAtWork = listOf(words(100)),
            sources = emptyList(),
            quiz = emptyList(),
            nextLessonId = null,
            beats = emptyList(),
            diagramSvg = null,
            durationSec = 240.0,
            nextLessonTitle = null,
            quizIntro = ""
        )
        // 200 + 100 + 100 = 400 words, exactly 2 minutes.
        assertEquals(2, ReadTime.minutesForText(detail.readingText()))
    }

    @Test
    fun lessonDurationsFormatMinutes() {
        assertEquals("1 min", LessonDurations.formatMinutes(83))
        assertEquals("4 min", LessonDurations.formatMinutes(240))
        assertEquals("16 min", LessonDurations.formatMinutes(961))
    }

    @Test
    fun chapterTotalsSayListening() {
        assertEquals("About 42 min of listening in total", LessonDurations.formatTotal(42 * 60))
        assertEquals("About 1 hr 5 min of listening in total", LessonDurations.formatTotal(65 * 60))
        assertEquals("12 min of listening left", LessonDurations.formatLeft(12 * 60))
        assertEquals("Nothing left", LessonDurations.formatLeft(0))
    }
}
