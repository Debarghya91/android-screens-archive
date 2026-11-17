package com.paymentstraininghub.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server row shapes for sync, mirroring the iOS definitions in
 * Models/UserProfile.swift (ServerProgressRow, QuizAttemptRow,
 * LearningActivityRow, LessonBookmarkRow, UserLearningPathRow,
 * LearningPreferences) and Services/PracticeStore.swift
 * (practice_attempts). Field names use @SerialName values matching
 * the iOS CodingKeys exactly. No table name or row shape here is
 * invented: every table below is named in iOS SyncService.swift or
 * PracticeStore.swift.
 *
 * Skeleton scope: SyncService syncs user_progress, quiz_attempts,
 * lesson_bookmarks and user_preferences only. LearningActivityRow,
 * UserLearningPathRow and PracticeAttemptRow are defined here so
 * follow-up work can wire them without re-deriving the shapes; they
 * are NOT synced by the skeleton. See the SyncService header for the
 * follow-up list.
 *
 * Encoding note: rows are encoded with a Json instance configured
 * with explicitNulls = false (see SyncService.syncJson), so null
 * optional columns are omitted instead of sent as JSON null. That
 * matches iOS behaviour for enrolled_at, which must be omitted when
 * absent because the column defaults to now() on insert.
 */

/** Table names used by sync. Every name comes from the iOS source. */
object SyncTables {
    const val USER_PROGRESS = "user_progress"
    const val QUIZ_ATTEMPTS = "quiz_attempts"
    const val LESSON_BOOKMARKS = "lesson_bookmarks"
    const val LEARNING_ACTIVITY = "learning_activity"
    const val USER_PREFERENCES = "user_preferences"
    const val USER_LEARNING_PATHS = "user_learning_paths"
    const val PRACTICE_ATTEMPTS = "practice_attempts"

    /**
     * Natural upsert conflict keys from iOS SyncService.swift.
     * quiz_attempts and practice_attempts intentionally have NO entry:
     * they are history tables with no unique key by design and are
     * written with plain inserts, with duplicates prevented by a
     * read-first dedupe (quiz) or a synced flag (practice).
     */
    val ON_CONFLICT: Map<String, String> = mapOf(
        USER_PROGRESS to "user_id,lesson_id",
        LESSON_BOOKMARKS to "user_id,lesson_id",
        LEARNING_ACTIVITY to "user_id,activity_date",
        USER_LEARNING_PATHS to "user_id,path_id",
        USER_PREFERENCES to "user_id"
    )
}

/** One row of the user_progress table. Mirrors iOS ServerProgressRow. */
@Serializable
data class ServerProgressRow(
    @SerialName("user_id") val userId: String,
    @SerialName("lesson_id") val lessonId: String,
    @SerialName("completed") val completed: Boolean,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/** One row of the quiz_attempts table. Mirrors iOS QuizAttemptRow. */
@Serializable
data class QuizAttemptRow(
    @SerialName("user_id") val userId: String,
    @SerialName("lesson_id") val lessonId: String,
    @SerialName("correct") val correct: Int,
    @SerialName("total") val total: Int,
    @SerialName("score") val score: Int,
    @SerialName("attempted_at") val attemptedAt: String? = null
)

/**
 * One row of the learning_activity table: one row per user per day.
 * Mirrors iOS LearningActivityRow. Defined for follow-up wiring;
 * the skeleton does not sync this table.
 */
@Serializable
data class LearningActivityRow(
    @SerialName("user_id") val userId: String,
    @SerialName("activity_date") val activityDate: String,
    @SerialName("lessons_completed") val lessonsCompleted: Int,
    @SerialName("minutes") val minutes: Int
)

/**
 * One row of the lesson_bookmarks table. Mirrors iOS
 * LessonBookmarkRow. Situation bookmarks have no server table and
 * stay on the device only.
 */
@Serializable
data class LessonBookmarkRow(
    @SerialName("user_id") val userId: String,
    @SerialName("lesson_id") val lessonId: String
)

/**
 * One row of the user_learning_paths table. Mirrors iOS
 * UserLearningPathRow. Defined for follow-up wiring; the skeleton
 * does not sync this table.
 */
@Serializable
data class UserLearningPathRow(
    @SerialName("user_id") val userId: String,
    @SerialName("path_id") val pathId: String,
    @SerialName("enrolled_at") val enrolledAt: String? = null,
    @SerialName("progress_pct") val progressPct: Int
)

/**
 * One row of the user_preferences table. Mirrors iOS
 * LearningPreferences. The weekly goal is NOT part of this row: on
 * iOS it lives only in ProgressStore and no server column is written
 * for it.
 */
@Serializable
data class UserPreferencesRow(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("experience_level") val experienceLevel: String? = null,
    @SerialName("domain_interests") val domainInterests: List<String> = emptyList(),
    @SerialName("include_audio") val includeAudio: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * One row of the practice_attempts table. Shape mirrors the private
 * PracticeAttemptRow in iOS PracticeStore.swift exactly (user_id,
 * situation_id, chosen_option_index, is_correct, attempted_at).
 * Defined for follow-up wiring only; practice sync is owned by the
 * PracticeStore follow-up on iOS and is NOT synced by this skeleton.
 */
@Serializable
data class PracticeAttemptRow(
    @SerialName("user_id") val userId: String,
    @SerialName("situation_id") val situationId: String,
    @SerialName("chosen_option_index") val chosenOptionIndex: Int,
    @SerialName("is_correct") val isCorrect: Boolean,
    @SerialName("attempted_at") val attemptedAt: String
)

/**
 * Provisional local shape for one quiz attempt stored under the
 * "quiz.answers.<lessonId>" AppStore key. The key scheme comes from
 * the coordinator brief; the stored JSON shape is NOT confirmed yet
 * (Worker D owns the quiz store). SyncService decodes this shape
 * defensively: a value that does not decode is skipped for that
 * lesson only, and the sync continues. Follow-up: confirm the real
 * stored shape with Worker D and replace this model if it differs.
 * This defensive local handling is NOT the server rule: a server
 * decode failure always fails the sync, never reads as empty data.
 */
@Serializable
data class LocalQuizAttempt(
    @SerialName("lesson_id") val lessonId: String? = null,
    @SerialName("correct") val correct: Int = 0,
    @SerialName("total") val total: Int = 0,
    @SerialName("attempted_at") val attemptedAt: String? = null
)

/**
 * Sync status. Modelled as a sealed interface because a Kotlin enum
 * cannot carry the timestamp and reason payloads the brief requires
 * (Succeeded(at), Failed(reason)).
 */
sealed interface SyncStatus {
    /** No sync is running and no result is outstanding. */
    data object Idle : SyncStatus

    /** A sync run is in flight. */
    data object Syncing : SyncStatus

    /** The last run fully succeeded at the given epoch millis. */
    data class Succeeded(val at: Long) : SyncStatus

    /** The last run failed. Local data is untouched; retry later. */
    data class Failed(val reason: String) : SyncStatus
}

/** Outcome of one syncAll() run. */
data class SyncResult(
    /** True when the run completed all wired table steps. */
    val success: Boolean,
    /** True when the run was skipped (guest, or Supabase unconfigured). */
    val skipped: Boolean = false,
    /** True when the run was abandoned because the user changed mid-run. */
    val abandoned: Boolean = false,
    /** Epoch millis of success, or null when the run did not succeed. */
    val syncedAt: Long? = null,
    /** Tables whose step completed in this run. */
    val tablesSynced: List<String> = emptyList(),
    /** Pending queue operations still held after this run. */
    val pendingRemaining: Int = 0,
    /** Human readable failure reason, or null on success or skip. */
    val error: String? = null
)
