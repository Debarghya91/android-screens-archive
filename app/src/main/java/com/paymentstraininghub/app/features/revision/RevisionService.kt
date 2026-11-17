// RevisionService.kt
// Spaced repetition for completed lessons, ported from iOS
// RevisionService.swift. Stored locally through AppStore,
// namespaced per account exactly as iOS AccountScopedStorage.
// Intervals, in days: same day (0), next day (1), day 3, day 7,
// day 30. On a correct recall the concept advances to the next
// interval; on an incorrect recall it resets to the same day.
// Nothing here invents content: a concept is always a real
// completed lesson.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.revision

import com.paymentstraininghub.app.auth.AppStore
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RevisionConcept(
    val id: String,
    val title: String,
    val kind: String,
    val nextDueMillis: Long,
    val intervalDays: Int,
    val correctCount: Int,
    val incorrectCount: Int
) {
    /// True when this concept is due for revision today or earlier.
    val isDueToday: Boolean
        get() = nextDueMillis <= RevisionService.todayEndMillis()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("kind", kind)
        .put("nextDueMillis", nextDueMillis)
        .put("intervalDays", intervalDays)
        .put("correctCount", correctCount)
        .put("incorrectCount", incorrectCount)

    companion object {
        fun fromJson(json: JSONObject): RevisionConcept = RevisionConcept(
            id = json.getString("id"),
            title = json.getString("title"),
            kind = json.optString("kind", "Lesson"),
            nextDueMillis = json.getLong("nextDueMillis"),
            intervalDays = json.getInt("intervalDays"),
            correctCount = json.getInt("correctCount"),
            incorrectCount = json.getInt("incorrectCount")
        )
    }
}

/// Spaced repetition for lessons the learner wants to revise.
object RevisionService {

    /// The intervals, in days, in order. Index 0 is "same day".
    val INTERVALS: List<Int> = listOf(0, 1, 3, 7, 30)

    private const val KEY = "pf.revisionConcepts.v1"

    private val _concepts = MutableStateFlow<List<RevisionConcept>>(emptyList())
    val concepts: StateFlow<List<RevisionConcept>> = _concepts.asStateFlow()

    /// Concepts due for revision today, earliest due first.
    val dueToday: List<RevisionConcept>
        get() = _concepts.value
            .filter { it.nextDueMillis <= todayEndMillis() }
            .sortedBy { it.nextDueMillis }

    /// Concepts where incorrect recalls outnumber correct ones.
    val weakConcepts: List<RevisionConcept>
        get() = _concepts.value
            .filter { it.incorrectCount > it.correctCount }
            .sortedBy { it.nextDueMillis }

    /// All scheduled concepts, by next due date.
    val allConcepts: List<RevisionConcept>
        get() = _concepts.value.sortedBy { it.nextDueMillis }

    /// Start of today, local time, in epoch millis.
    fun startOfTodayMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /// End of today (start of today plus 24 hours minus 1 second),
    /// matching the iOS due-today boundary.
    fun todayEndMillis(): Long = startOfTodayMillis() + 24 * 60 * 60 * 1000L - 1000L

    suspend fun refresh() {
        val raw = runCatching { AppStore.getString(KEY) }.getOrNull() ?: return
        val restored = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { RevisionConcept.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _concepts.value = restored
    }

    private suspend fun persist() {
        runCatching {
            val array = JSONArray()
            _concepts.value.forEach { array.put(it.toJson()) }
            AppStore.putString(KEY, array.toString())
        }
    }

    /// True when a lesson is already scheduled for revision.
    fun isScheduled(lessonId: String): Boolean =
        _concepts.value.any { it.id == lessonId }

    /// Schedule a lesson for revision, due today. A lesson already
    /// scheduled is left untouched, so its interval is never reset
    /// by re-adding it.
    suspend fun scheduleLessonForRevision(lessonId: String, title: String) {
        if (isScheduled(lessonId)) return
        _concepts.value = _concepts.value + RevisionConcept(
            id = lessonId,
            title = title,
            kind = "Lesson",
            nextDueMillis = startOfTodayMillis(),
            intervalDays = 0,
            correctCount = 0,
            incorrectCount = 0
        )
        persist()
    }

    /// Record one recall review. Correct advances to the next
    /// interval (staying at the longest interval once reached);
    /// incorrect resets to the same day.
    suspend fun recordReview(conceptId: String, correct: Boolean) {
        val index = _concepts.value.indexOfFirst { it.id == conceptId }
        if (index < 0) return
        val current = _concepts.value[index]
        val nextInterval = if (correct) {
            val currentPosition = INTERVALS.indexOf(current.intervalDays).takeIf { it >= 0 } ?: 0
            INTERVALS[minOf(currentPosition + 1, INTERVALS.size - 1)]
        } else {
            0
        }
        val nextDue = LocalDate.now().plusDays(nextInterval.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val updated = current.copy(
            nextDueMillis = nextDue,
            intervalDays = nextInterval,
            correctCount = if (correct) current.correctCount + 1 else current.correctCount,
            incorrectCount = if (correct) current.incorrectCount else current.incorrectCount + 1
        )
        _concepts.value = _concepts.value.toMutableList().also { it[index] = updated }
        persist()
    }

    /// Remove a concept from revision.
    suspend fun removeConcept(id: String) {
        _concepts.value = _concepts.value.filter { it.id != id }
        persist()
    }
}
