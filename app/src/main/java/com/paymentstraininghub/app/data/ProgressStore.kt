// ProgressStore.kt
// Payments Training Hub Android - the ONE shared progress store.
//
// The completion-store split (Tester 2 C1/G3): LessonScreen used to
// write only "lesson.completed.<id>", while every progress reader
// (HabitStore, Home, Learn, Chapter detail) read
// "pf.completedLessons.v1", which NOTHING wrote, and
// "pf.learningEvents.v1", which had no writer at all. Completed
// counters never moved, streaks never started, and quiz scores
// were never recorded.
//
// Every completion and quiz write now goes through this store, and
// writes land in ALL the places readers already look:
//   - the legacy per-lesson boolean (SyncService and the lesson
//     screen read it),
//   - "pf.completedLessons.v1" (the JSON array every progress
//     reader parses),
//   - "pf.learningEvents.v1" (the event log streaks and weekly
//     counts read; events use the exact shapes HabitStore parses:
//     type "lessonCompleted"/"quizCompleted", lessonID, and a
//     millisecond epoch "timestamp"),
//   - "pf.quizScores.v1" (per-lesson quiz results, best score kept).
//
// All keys are base keys: AppStore namespaces them per account, so
// one account never sees another's progress. The codec functions
// are pure and unit-tested; the suspend functions never throw.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.auth.AppStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProgressStore {

    /** Base key for the completed-lesson id array. Read by HabitStore. */
    const val COMPLETED_LESSONS_KEY = "pf.completedLessons.v1"

    /** Base key for the learning-event log. Read by HabitStore. */
    const val LEARNING_EVENTS_KEY = "pf.learningEvents.v1"

    /** Base key for per-lesson quiz scores. */
    const val QUIZ_SCORES_KEY = "pf.quizScores.v1"

    /** The event log is capped so it can never grow without bound. */
    const val MAX_EVENTS = 500

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Stored shapes (keys match what HabitStore parses)

    @Serializable
    data class LearningEventJson(
        @SerialName("type") val type: String,
        @SerialName("lessonID") val lessonID: String? = null,
        @SerialName("timestamp") val timestamp: Long
    )

    @Serializable
    data class QuizScoreJson(
        @SerialName("correct") val correct: Int,
        @SerialName("total") val total: Int,
        @SerialName("attempts") val attempts: Int,
        @SerialName("bestCorrect") val bestCorrect: Int,
        @SerialName("bestTotal") val bestTotal: Int,
        @SerialName("updatedAtMillis") val updatedAtMillis: Long
    )

    /** Roll-up of all recorded quiz scores, for the progress dashboard. */
    data class QuizSummary(
        val lessonsAttempted: Int,
        val totalAttempts: Int,
        val bestCorrect: Int,
        val bestTotal: Int
    ) {
        /** Accuracy from best scores only, 0 to 100. Null when nothing is recorded. */
        val accuracyPercent: Int?
            get() = if (bestTotal > 0) {
                (100.0 * bestCorrect.toDouble() / bestTotal.toDouble()).toInt()
            } else {
                null
            }
    }

    // MARK: - Pure codec (unit-tested)

    fun encodeCompletedIds(ids: Set<String>): String {
        return json.encodeToString(ids.sorted())
    }

    /**
     * Parse the completed-ids value. The array shape is what this
     * store writes; the object shape (id to true) is what HabitStore
     * also accepts, so both parse here and stay in agreement.
     */
    fun parseCompletedIds(raw: String?): Set<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptySet()
        return runCatching {
            if (trimmed.startsWith("[")) {
                json.decodeFromString<List<String>>(trimmed).toSet()
            } else {
                json.decodeFromString<Map<String, Boolean>>(trimmed)
                    .filterValues { it }.keys
            }
        }.getOrElse { emptySet() }
    }

    fun addCompletedId(existingRaw: String?, lessonId: String): String {
        return encodeCompletedIds(parseCompletedIds(existingRaw) + lessonId)
    }

    fun encodeEvents(events: List<LearningEventJson>): String {
        return json.encodeToString(events)
    }

    fun parseEvents(raw: String?): List<LearningEventJson> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<List<LearningEventJson>>(trimmed)
        }.getOrElse { emptyList() }
    }

    fun appendEvent(
        existingRaw: String?,
        event: LearningEventJson,
        maxEvents: Int = MAX_EVENTS
    ): String {
        val appended = parseEvents(existingRaw) + event
        return encodeEvents(appended.takeLast(maxEvents))
    }

    fun encodeScores(scores: Map<String, QuizScoreJson>): String {
        return json.encodeToString(scores)
    }

    fun parseScores(raw: String?): Map<String, QuizScoreJson> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, QuizScoreJson>>(trimmed)
        }.getOrElse { emptyMap() }
    }

    /**
     * Merge one quiz result into the stored scores. Attempts count
     * every submission; the best score is the highest percentage,
     * with the larger question count winning a tie.
     */
    fun recordScore(
        existingRaw: String?,
        lessonId: String,
        correct: Int,
        total: Int,
        nowMillis: Long
    ): String {
        if (total <= 0 || correct < 0 || correct > total) {
            return existingRaw ?: encodeScores(emptyMap())
        }
        val scores = parseScores(existingRaw).toMutableMap()
        val previous = scores[lessonId]
        val attempts = (previous?.attempts ?: 0) + 1
        val previousBestPercent = if (previous != null && previous.bestTotal > 0) {
            previous.bestCorrect.toDouble() / previous.bestTotal.toDouble()
        } else {
            -1.0
        }
        val newPercent = correct.toDouble() / total.toDouble()
        val keepPreviousBest = previous != null &&
            (previousBestPercent > newPercent ||
                (previousBestPercent == newPercent && previous.bestTotal >= total))
        val bestCorrect = if (keepPreviousBest) previous?.bestCorrect ?: correct else correct
        val bestTotal = if (keepPreviousBest) previous?.bestTotal ?: total else total
        scores[lessonId] = QuizScoreJson(
            correct = correct,
            total = total,
            attempts = attempts,
            bestCorrect = bestCorrect,
            bestTotal = bestTotal,
            updatedAtMillis = nowMillis
        )
        return encodeScores(scores)
    }

    fun summariseScores(scores: Map<String, QuizScoreJson>): QuizSummary {
        return QuizSummary(
            lessonsAttempted = scores.size,
            totalAttempts = scores.values.sumOf { it.attempts },
            bestCorrect = scores.values.sumOf { it.bestCorrect },
            bestTotal = scores.values.sumOf { it.bestTotal }
        )
    }

    // MARK: - Suspend store API (never throws)

    /**
     * Mark a lesson complete everywhere readers look: the legacy
     * boolean, the shared completed-ids array, and the event log.
     * Re-marking an already-complete lesson writes nothing new to
     * the array or the log.
     */
    suspend fun markLessonComplete(
        lessonId: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        runCatching { AppStore.putBoolean("lesson.completed.$lessonId", true) }
        val existingIds = runCatching { AppStore.getString(COMPLETED_LESSONS_KEY) }.getOrNull()
        val alreadyComplete = lessonId in parseCompletedIds(existingIds)
        if (alreadyComplete) return
        runCatching {
            AppStore.putString(COMPLETED_LESSONS_KEY, addCompletedId(existingIds, lessonId))
        }
        val existingEvents = runCatching { AppStore.getString(LEARNING_EVENTS_KEY) }.getOrNull()
        runCatching {
            AppStore.putString(
                LEARNING_EVENTS_KEY,
                appendEvent(
                    existingEvents,
                    LearningEventJson(
                        type = "lessonCompleted",
                        lessonID = lessonId,
                        timestamp = nowMillis
                    )
                )
            )
        }
    }

    /** Record one submitted lesson quiz, and log the quiz event. */
    suspend fun recordQuizScore(
        lessonId: String,
        correct: Int,
        total: Int,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (total <= 0 || correct < 0 || correct > total) return
        val existing = runCatching { AppStore.getString(QUIZ_SCORES_KEY) }.getOrNull()
        runCatching {
            AppStore.putString(
                QUIZ_SCORES_KEY,
                recordScore(existing, lessonId, correct, total, nowMillis)
            )
        }
        val existingEvents = runCatching { AppStore.getString(LEARNING_EVENTS_KEY) }.getOrNull()
        runCatching {
            AppStore.putString(
                LEARNING_EVENTS_KEY,
                appendEvent(
                    existingEvents,
                    LearningEventJson(
                        type = "quizCompleted",
                        lessonID = lessonId,
                        timestamp = nowMillis
                    )
                )
            )
        }
    }

    /** Completed lesson ids, from the same key every reader uses. */
    suspend fun loadCompletedIds(): Set<String> {
        val raw = runCatching { AppStore.getString(COMPLETED_LESSONS_KEY) }.getOrNull()
        return parseCompletedIds(raw)
    }

    /** Per-lesson quiz scores, or empty when nothing is recorded. */
    suspend fun loadScores(): Map<String, QuizScoreJson> {
        val raw = runCatching { AppStore.getString(QUIZ_SCORES_KEY) }.getOrNull()
        return parseScores(raw)
    }

    /** The quiz roll-up for the progress dashboard. */
    suspend fun loadQuizSummary(): QuizSummary {
        return summariseScores(loadScores())
    }
}
