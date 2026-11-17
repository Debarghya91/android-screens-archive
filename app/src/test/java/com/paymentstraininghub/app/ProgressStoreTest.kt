// ProgressStoreTest.kt
// Unit tests for the shared progress store (Tester 2 C1/G3). The
// pure codec is tested on the JVM; the suspend AppStore functions
// are not JVM-testable, and HabitStore itself cannot be called
// here because its org.json parsing is stubbed on the JVM
// ("not mocked"). The cross-store contract is pinned at the wire
// format instead: the exact JSON shapes ProgressStore writes are
// the shapes HabitStore.parseCompletedIds and parseEvents read
// (array of id strings, and event objects with type, lessonID
// and a numeric timestamp).

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ProgressStore
import com.paymentstraininghub.app.data.ProgressStore.LearningEventJson
import com.paymentstraininghub.app.data.ProgressStore.QuizScoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressStoreTest {

    @Test
    fun completedIdsRoundTrip() {
        val ids = setOf("ch1s1", "ch54s2", "ch54s901-supp")
        assertEquals(ids, ProgressStore.parseCompletedIds(ProgressStore.encodeCompletedIds(ids)))
        assertEquals(emptySet<String>(), ProgressStore.parseCompletedIds(null))
        assertEquals(emptySet<String>(), ProgressStore.parseCompletedIds(""))
        assertEquals(emptySet<String>(), ProgressStore.parseCompletedIds("garbage"))
    }

    @Test
    fun addCompletedIdIsIdempotentAndWritesArrayShape() {
        val raw = ProgressStore.addCompletedId(null, "ch1s1")
        // The exact shape HabitStore.parseCompletedIds reads: a
        // JSON array of id strings.
        assertEquals("[\"ch1s1\"]", raw)
        val raw2 = ProgressStore.addCompletedId(raw, "ch1s1")
        assertEquals("[\"ch1s1\"]", raw2)
        assertEquals(setOf("ch1s1"), ProgressStore.parseCompletedIds(raw2))
        val raw3 = ProgressStore.addCompletedId(raw2, "ch2s1")
        assertEquals(setOf("ch1s1", "ch2s1"), ProgressStore.parseCompletedIds(raw3))
    }

    @Test
    fun objectShapeParsesForBackwardsCompatibility() {
        assertEquals(setOf("ch1s1"), ProgressStore.parseCompletedIds("{\"ch1s1\":true}"))
        assertEquals(
            setOf("ch1s1"),
            ProgressStore.parseCompletedIds("{\"ch1s1\":true,\"ch2s1\":false}")
        )
    }

    @Test
    fun eventsRoundTripAndUseHabitStoreFieldNames() {
        val event = LearningEventJson(
            type = "lessonCompleted",
            lessonID = "ch1s1",
            timestamp = 1_700_000_000_000L
        )
        val raw = ProgressStore.appendEvent(null, event)
        // The exact field names HabitStore.parseEvents reads:
        // type, lessonID, and a numeric timestamp.
        assertTrue(raw.contains("\"type\":\"lessonCompleted\""))
        assertTrue(raw.contains("\"lessonID\":\"ch1s1\""))
        assertTrue(raw.contains("\"timestamp\":1700000000000"))
        val parsed = ProgressStore.parseEvents(raw)
        assertEquals(1, parsed.size)
        assertEquals("lessonCompleted", parsed[0].type)
        assertEquals("ch1s1", parsed[0].lessonID)
        assertEquals(1_700_000_000_000L, parsed[0].timestamp)
    }

    @Test
    fun eventLogIsCappedAtMaxEvents() {
        var raw: String? = null
        repeat(505) { i ->
            raw = ProgressStore.appendEvent(
                raw,
                LearningEventJson("lessonCompleted", "ch1s$i", 1_700_000_000_000L + i)
            )
        }
        val parsed = ProgressStore.parseEvents(raw)
        assertEquals(ProgressStore.MAX_EVENTS, parsed.size)
        assertEquals("ch1s504", parsed.last().lessonID)
    }

    @Test
    fun scoreMergeKeepsBestAndCountsAttempts() {
        var raw: String? = null
        raw = ProgressStore.recordScore(raw, "ch1s1", 2, 4, 1000L)
        raw = ProgressStore.recordScore(raw, "ch1s1", 4, 4, 2000L)
        raw = ProgressStore.recordScore(raw, "ch1s1", 1, 4, 3000L)
        val score = ProgressStore.parseScores(raw)["ch1s1"]!!
        assertEquals(3, score.attempts)
        assertEquals(1, score.correct)
        assertEquals(4, score.bestCorrect)
        assertEquals(4, score.bestTotal)
    }

    @Test
    fun scoreTieKeepsLargerQuestionCount() {
        var raw: String? = null
        raw = ProgressStore.recordScore(raw, "ch1s1", 2, 2, 1000L)
        raw = ProgressStore.recordScore(raw, "ch1s1", 4, 4, 2000L)
        val score = ProgressStore.parseScores(raw)["ch1s1"]!!
        assertEquals(4, score.bestCorrect)
        assertEquals(4, score.bestTotal)
    }

    @Test
    fun invalidScoresAreIgnored() {
        val over = ProgressStore.recordScore(null, "ch1s1", 5, 4, 1000L)
        assertTrue(ProgressStore.parseScores(over).isEmpty())
        val empty = ProgressStore.recordScore(null, "ch1s1", 1, 0, 1000L)
        assertTrue(ProgressStore.parseScores(empty).isEmpty())
    }

    @Test
    fun summaryRollsUpBestScores() {
        val scores = mapOf(
            "ch1s1" to QuizScoreJson(2, 4, 2, 3, 4, 1000L),
            "ch1s2" to QuizScoreJson(1, 2, 1, 1, 2, 1000L)
        )
        val summary = ProgressStore.summariseScores(scores)
        assertEquals(2, summary.lessonsAttempted)
        assertEquals(3, summary.totalAttempts)
        assertEquals(66, summary.accuracyPercent)
        assertNull(ProgressStore.summariseScores(emptyMap()).accuracyPercent)
    }

    @Test
    fun scoresRoundTrip() {
        val scores = mapOf("ch1s1" to QuizScoreJson(2, 4, 1, 2, 4, 1000L))
        assertEquals(scores, ProgressStore.parseScores(ProgressStore.encodeScores(scores)))
        assertEquals(emptyMap<String, QuizScoreJson>(), ProgressStore.parseScores(null))
        assertEquals(emptyMap<String, QuizScoreJson>(), ProgressStore.parseScores("garbage"))
    }
}
