// Port of AnalyticsService.swift (iOS). A local event layer with explicit
// opt-in consent. No analytics SDK is integrated. No event leaves this
// device: every recorded event goes to an in-memory buffer capped at 200.
// There is exactly one backend hookup point, forwardToBackend, which does
// nothing today. A future transport plugs in there and nowhere else, and it
// must keep the consent gate in track() in front of it.
//
// Consent: default OFF, per account. No event is recorded before consent,
// and no event is emitted retroactively when consent is granted later.
// Withdrawing consent stops recording, deletes the buffered events and the
// stored previous scores. First-occurrence flags are kept across
// withdrawal: they record only that a milestone event was already emitted
// once. The consent flag must never be migrated guest to account.
//
// What is never recorded: names, emails, account identifiers, question
// text, answer text, note text, chat text, search words, payment details,
// transaction identifiers, prices, precise location, or device identifiers.
// Properties carry only content identifiers, counts, scores as numbers,
// and controlled category words supplied by call sites. Call sites must
// never pass free text into category parameters.
//
// No install event is defined: a true install event is not observable
// without an attribution SDK, and none must be fabricated. firstLaunch
// means the first launch observed while consent is active.
//
// Wired call sites in Worker H code:
//   - ExceptionLabStore.recordCompletion -> practicalExerciseCompleted (kind "lab")
//   - CommunityChallengeStore.recordCompletion -> practicalExerciseCompleted (kind "challenge")
//   - CommunityChallengeScreen first composition -> challengeViewed
// (Chat-assistant analytics wiring was removed 15 Nov 2026 with
// the chat feature.)
// Not wired here (owned by other workers; helpers provided below):
//   - lesson open / lesson complete / quiz submit -> LessonView
//   - purchase attempt / completion / failure -> checkout
//   - app launch / return after absence -> app entry point
// AppStore is suspend-only and namespaces keys itself; all persistence
// below runs in this service's IO scope, with in-memory state updated
// synchronously so track() stays safe to call from any thread. No em dashes.
package com.paymentstraininghub.app.features.analytics

import com.paymentstraininghub.app.auth.AppStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The six analytics categories. */
enum class AnalyticsEventCategory(val rawValue: String) {
    ACQUISITION("acquisition"),
    ACTIVATION("activation"),
    RETENTION("retention"),
    LEARNING_EFFECTIVENESS("learning_effectiveness"),
    MONETISATION("monetisation"),
    TECHNICAL_QUALITY("technical_quality")
}

/** One property value. String properties are sanitised at record time. */
sealed class AnalyticsPropertyValue {
    data class StringValue(val value: String) : AnalyticsPropertyValue()
    data class IntValue(val value: Int) : AnalyticsPropertyValue()
    data class DoubleValue(val value: Double) : AnalyticsPropertyValue()
    data class BoolValue(val value: Boolean) : AnalyticsPropertyValue()
}

/**
 * The full event catalogue. Every subclass is one event a call site may
 * record. Names are snake_case and must not be changed: a future backend
 * keys dashboards off them.
 */
sealed class AnalyticsEvent {
    abstract val name: String
    abstract val category: AnalyticsEventCategory
    abstract val properties: Map<String, AnalyticsPropertyValue>

    object FirstLaunch : AnalyticsEvent() {
        override val name = "first_launch"
        override val category = AnalyticsEventCategory.ACQUISITION
        override val properties = emptyMap<String, AnalyticsPropertyValue>()
    }

    data class FirstLessonStarted(val lessonID: String, val chapterID: String) : AnalyticsEvent() {
        override val name = "first_lesson_started"
        override val category = AnalyticsEventCategory.ACQUISITION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "chapter_id" to AnalyticsPropertyValue.StringValue(chapterID)
        )
    }

    data class LessonStarted(val lessonID: String, val chapterID: String) : AnalyticsEvent() {
        override val name = "lesson_started"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "chapter_id" to AnalyticsPropertyValue.StringValue(chapterID)
        )
    }

    data class LessonCompleted(val lessonID: String, val chapterID: String) : AnalyticsEvent() {
        override val name = "lesson_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "chapter_id" to AnalyticsPropertyValue.StringValue(chapterID)
        )
    }

    data class FirstLessonCompleted(val lessonID: String, val chapterID: String) : AnalyticsEvent() {
        override val name = "first_lesson_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "chapter_id" to AnalyticsPropertyValue.StringValue(chapterID)
        )
    }

    data class QuizCompleted(
        val lessonID: String,
        val correct: Int,
        val total: Int,
        val scorePercent: Double
    ) : AnalyticsEvent() {
        override val name = "quiz_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "correct" to AnalyticsPropertyValue.IntValue(correct),
            "total" to AnalyticsPropertyValue.IntValue(total),
            "score_percent" to AnalyticsPropertyValue.DoubleValue(scorePercent)
        )
    }

    data class FirstQuizCompleted(
        val lessonID: String,
        val correct: Int,
        val total: Int,
        val scorePercent: Double
    ) : AnalyticsEvent() {
        override val name = "first_quiz_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "correct" to AnalyticsPropertyValue.IntValue(correct),
            "total" to AnalyticsPropertyValue.IntValue(total),
            "score_percent" to AnalyticsPropertyValue.DoubleValue(scorePercent)
        )
    }

    data class PracticalExerciseCompleted(val exerciseID: String, val kind: String) : AnalyticsEvent() {
        override val name = "practical_exercise_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "exercise_id" to AnalyticsPropertyValue.StringValue(exerciseID),
            "kind" to AnalyticsPropertyValue.StringValue(kind)
        )
    }

    data class FirstPracticalExerciseCompleted(val exerciseID: String, val kind: String) : AnalyticsEvent() {
        override val name = "first_practical_exercise_completed"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "exercise_id" to AnalyticsPropertyValue.StringValue(exerciseID),
            "kind" to AnalyticsPropertyValue.StringValue(kind)
        )
    }

    data class PathSelected(val pathID: String, val role: String) : AnalyticsEvent() {
        override val name = "path_selected"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "path_id" to AnalyticsPropertyValue.StringValue(pathID),
            "role" to AnalyticsPropertyValue.StringValue(role)
        )
    }

    data class FirstPathSelected(val pathID: String, val role: String) : AnalyticsEvent() {
        override val name = "first_path_selected"
        override val category = AnalyticsEventCategory.ACTIVATION
        override val properties = mapOf(
            "path_id" to AnalyticsPropertyValue.StringValue(pathID),
            "role" to AnalyticsPropertyValue.StringValue(role)
        )
    }

    object AppLaunched : AnalyticsEvent() {
        override val name = "app_launched"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = emptyMap<String, AnalyticsPropertyValue>()
    }

    data class ReturnAfterAbsence(val daysSinceLastLaunch: Int) : AnalyticsEvent() {
        override val name = "return_after_absence"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = mapOf(
            "days_since_last_launch" to AnalyticsPropertyValue.IntValue(daysSinceLastLaunch)
        )
    }

    data class PathContinued(val pathID: String) : AnalyticsEvent() {
        override val name = "path_continued"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = mapOf(
            "path_id" to AnalyticsPropertyValue.StringValue(pathID)
        )
    }

    object ChallengeViewed : AnalyticsEvent() {
        override val name = "challenge_viewed"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = emptyMap<String, AnalyticsPropertyValue>()
    }

    data class ChallengeCompleted(val correct: Boolean) : AnalyticsEvent() {
        override val name = "challenge_completed"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = mapOf(
            "correct" to AnalyticsPropertyValue.BoolValue(correct)
        )
    }

    data class FirstChallengeCompleted(val correct: Boolean) : AnalyticsEvent() {
        override val name = "first_challenge_completed"
        override val category = AnalyticsEventCategory.RETENTION
        override val properties = mapOf(
            "correct" to AnalyticsPropertyValue.BoolValue(correct)
        )
    }

    data class AssessmentStarted(
        val dimension: String,
        val questionCount: Int,
        val timed: Boolean,
        val randomised: Boolean
    ) : AnalyticsEvent() {
        override val name = "assessment_started"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "dimension" to AnalyticsPropertyValue.StringValue(dimension),
            "question_count" to AnalyticsPropertyValue.IntValue(questionCount),
            "timed" to AnalyticsPropertyValue.BoolValue(timed),
            "randomised" to AnalyticsPropertyValue.BoolValue(randomised)
        )
    }

    data class AssessmentCompleted(
        val dimension: String,
        val correct: Int,
        val total: Int,
        val scorePercent: Double,
        val elapsedSeconds: Int,
        val unansweredCount: Int
    ) : AnalyticsEvent() {
        override val name = "assessment_completed"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "dimension" to AnalyticsPropertyValue.StringValue(dimension),
            "correct" to AnalyticsPropertyValue.IntValue(correct),
            "total" to AnalyticsPropertyValue.IntValue(total),
            "score_percent" to AnalyticsPropertyValue.DoubleValue(scorePercent),
            "elapsed_seconds" to AnalyticsPropertyValue.IntValue(elapsedSeconds),
            "unanswered_count" to AnalyticsPropertyValue.IntValue(unansweredCount)
        )
    }

    data class FinalAssessmentStarted(
        val productID: String,
        val attemptNumber: Int,
        val isResume: Boolean
    ) : AnalyticsEvent() {
        override val name = "final_assessment_started"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID),
            "attempt_number" to AnalyticsPropertyValue.IntValue(attemptNumber),
            "is_resume" to AnalyticsPropertyValue.BoolValue(isResume)
        )
    }

    data class FinalAssessmentCompleted(
        val productID: String,
        val attemptNumber: Int,
        val correct: Int,
        val total: Int,
        val passed: Boolean,
        val scorePercent: Double,
        val elapsedSeconds: Int,
        val unansweredCount: Int
    ) : AnalyticsEvent() {
        override val name = "final_assessment_completed"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID),
            "attempt_number" to AnalyticsPropertyValue.IntValue(attemptNumber),
            "correct" to AnalyticsPropertyValue.IntValue(correct),
            "total" to AnalyticsPropertyValue.IntValue(total),
            "passed" to AnalyticsPropertyValue.BoolValue(passed),
            "score_percent" to AnalyticsPropertyValue.DoubleValue(scorePercent),
            "elapsed_seconds" to AnalyticsPropertyValue.IntValue(elapsedSeconds),
            "unanswered_count" to AnalyticsPropertyValue.IntValue(unansweredCount)
        )
    }

    data class ScoreImproved(
        val kind: String,
        val scopeID: String,
        val previousScorePercent: Double,
        val currentScorePercent: Double
    ) : AnalyticsEvent() {
        override val name = "score_improved"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "kind" to AnalyticsPropertyValue.StringValue(kind),
            "scope_id" to AnalyticsPropertyValue.StringValue(scopeID),
            "previous_score_percent" to AnalyticsPropertyValue.DoubleValue(previousScorePercent),
            "current_score_percent" to AnalyticsPropertyValue.DoubleValue(currentScorePercent)
        )
    }

    data class CertificateEarned(val productID: String) : AnalyticsEvent() {
        override val name = "certificate_earned"
        override val category = AnalyticsEventCategory.LEARNING_EFFECTIVENESS
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID)
        )
    }

    data class PaywallViewed(val source: String, val productID: String?) : AnalyticsEvent() {
        override val name = "paywall_viewed"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = buildMap {
            put("source", AnalyticsPropertyValue.StringValue(source))
            if (productID != null) put("product_id", AnalyticsPropertyValue.StringValue(productID))
        }
    }

    data class ProductSelected(val productID: String, val source: String) : AnalyticsEvent() {
        override val name = "product_selected"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID),
            "source" to AnalyticsPropertyValue.StringValue(source)
        )
    }

    data class PurchaseStarted(val productID: String) : AnalyticsEvent() {
        override val name = "purchase_started"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID)
        )
    }

    data class PurchaseCompleted(val productID: String) : AnalyticsEvent() {
        override val name = "purchase_completed"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID)
        )
    }

    data class PurchaseFailed(val productID: String, val failureCategory: String) : AnalyticsEvent() {
        override val name = "purchase_failed"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID),
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class PurchaseCancelled(val productID: String) : AnalyticsEvent() {
        override val name = "purchase_cancelled"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID)
        )
    }

    object RestoreStarted : AnalyticsEvent() {
        override val name = "restore_started"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = emptyMap<String, AnalyticsPropertyValue>()
    }

    data class RestoreCompleted(val restoredCount: Int) : AnalyticsEvent() {
        override val name = "restore_completed"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "restored_count" to AnalyticsPropertyValue.IntValue(restoredCount)
        )
    }

    data class RestoreFailed(val failureCategory: String) : AnalyticsEvent() {
        override val name = "restore_failed"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class EntitlementRevoked(val productID: String, val reason: String) : AnalyticsEvent() {
        override val name = "entitlement_revoked"
        override val category = AnalyticsEventCategory.MONETISATION
        override val properties = mapOf(
            "product_id" to AnalyticsPropertyValue.StringValue(productID),
            "reason" to AnalyticsPropertyValue.StringValue(reason)
        )
    }

    data class ContentLoadFailed(val kind: String, val failureCategory: String) : AnalyticsEvent() {
        override val name = "content_load_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "kind" to AnalyticsPropertyValue.StringValue(kind),
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class LessonLoadFailed(val lessonID: String, val failureCategory: String) : AnalyticsEvent() {
        override val name = "lesson_load_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class AudioPlaybackFailed(val lessonID: String, val failureCategory: String) : AnalyticsEvent() {
        override val name = "audio_playback_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class AudioDownloadFailed(val lessonID: String, val failureCategory: String) : AnalyticsEvent() {
        override val name = "audio_download_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "lesson_id" to AnalyticsPropertyValue.StringValue(lessonID),
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class AiAnswerFailed(val failureCategory: String, val fallbackUsed: Boolean) : AnalyticsEvent() {
        override val name = "ai_answer_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory),
            "fallback_used" to AnalyticsPropertyValue.BoolValue(fallbackUsed)
        )
    }

    data class SyncFailed(val failureCategory: String) : AnalyticsEvent() {
        override val name = "sync_failed"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "failure_category" to AnalyticsPropertyValue.StringValue(failureCategory)
        )
    }

    data class SearchNoResults(val source: String) : AnalyticsEvent() {
        override val name = "search_no_results"
        override val category = AnalyticsEventCategory.TECHNICAL_QUALITY
        override val properties = mapOf(
            "source" to AnalyticsPropertyValue.StringValue(source)
        )
    }

}

/** One recorded event in the in-memory buffer. Carries no account identifier. */
data class AnalyticsRecord(
    val name: String,
    val category: AnalyticsEventCategory,
    val properties: Map<String, AnalyticsPropertyValue>,
    val timestampMillis: Long
)

/** The analytics event layer. See the file header for the consent rules. */
object AnalyticsService {
    const val MAX_BUFFERED_EVENTS: Int = 200

    private const val CONSENT_KEY = "pf.analytics.consent.v1"
    private const val FIRST_LAUNCH_RECORDED_KEY = "pf.analytics.firstLaunchRecorded.v1"
    private const val LAST_LAUNCH_DAY_KEY = "pf.analytics.lastLaunchDay.v1"
    private const val PREVIOUS_SCORES_KEY = "pf.analytics.previousScores.v1"

    private val lock = Any()
    private val buffer = ArrayDeque<AnalyticsRecord>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** First-occurrence suffixes already emitted for this account, in memory. Loaded from storage on init and account change. */
    private val firstEmitted = mutableSetOf<String>()

    private val _isConsented = MutableStateFlow(false)
    val consentState: StateFlow<Boolean> = _isConsented.asStateFlow()

    private val _hasDecided = MutableStateFlow(false)
    val decidedState: StateFlow<Boolean> = _hasDecided.asStateFlow()

    /** True when the current account has consented. Default false. */
    val isConsented: Boolean get() = _isConsented.value

    /** True when the current account has made a consent decision. */
    val hasDecided: Boolean get() = _hasDecided.value

    init {
        scope.launch {
            refreshConsent()
            loadFirstFlags()
        }
    }

    /** Re-read consent for the current account. Call on account change. */
    fun reloadForCurrentAccount() {
        synchronized(lock) { buffer.clear() }
        synchronized(firstEmitted) { firstEmitted.clear() }
        scope.launch {
            refreshConsent()
            loadFirstFlags()
        }
    }

    private suspend fun refreshConsent() {
        val stored = runCatching { AppStore.getBoolean(CONSENT_KEY) }.getOrNull()
        _hasDecided.value = stored != null
        _isConsented.value = stored ?: false
    }

    private suspend fun loadFirstFlags() {
        FIRST_FLAG_SUFFIXES.forEach { suffix ->
            val emitted = runCatching {
                AppStore.getBoolean(firstFlagKey(suffix))
            }.getOrNull() ?: false
            if (emitted) {
                synchronized(firstEmitted) { firstEmitted.add(suffix) }
            }
        }
    }

    fun grantConsent() {
        _hasDecided.value = true
        _isConsented.value = true
        scope.launch {
            runCatching { AppStore.putBoolean(CONSENT_KEY, true) }
        }
    }

    fun declineConsent() {
        _hasDecided.value = true
        _isConsented.value = false
        synchronized(lock) { buffer.clear() }
        scope.launch {
            runCatching { AppStore.putBoolean(CONSENT_KEY, false) }
        }
    }

    /** Withdraw consent: stops recording, deletes buffered events and stored previous scores. */
    fun withdrawConsent() {
        _hasDecided.value = true
        _isConsented.value = false
        synchronized(lock) { buffer.clear() }
        scope.launch {
            runCatching { AppStore.putBoolean(CONSENT_KEY, false) }
            runCatching { AppStore.putString(PREVIOUS_SCORES_KEY, "{}") }
        }
    }

    /** Record an event. Drops every event while consent is off. Safe to call from any thread. */
    fun track(event: AnalyticsEvent) {
        if (!isConsented) return
        val record = AnalyticsRecord(
            name = event.name,
            category = event.category,
            properties = sanitise(event.properties),
            timestampMillis = System.currentTimeMillis()
        )
        synchronized(lock) {
            buffer.addLast(record)
            while (buffer.size > MAX_BUFFERED_EVENTS) buffer.removeFirst()
        }
        forwardToBackend(record)
    }

    /** The buffered events, oldest first. For debugging and future transports only. */
    fun bufferedEvents(): List<AnalyticsRecord> = synchronized(lock) { buffer.toList() }

    /**
     * The single backend hookup point. Does nothing today. A future
     * transport plugs in here and nowhere else, and must keep the consent
     * gate in track() in front of it.
     */
    private fun forwardToBackend(record: AnalyticsRecord) {
        // No-op: no analytics backend is integrated in this build.
    }

    private fun sanitise(properties: Map<String, AnalyticsPropertyValue>): Map<String, AnalyticsPropertyValue> {
        return properties.mapValues { (_, value) ->
            when (value) {
                is AnalyticsPropertyValue.StringValue -> AnalyticsPropertyValue.StringValue(
                    value.value.trim().replace(Regex("\\s+"), " ").take(200)
                )
                else -> value
            }
        }
    }

    /** Emit a first-occurrence event only once per account. Returns true when it was emitted. */
    private fun trackFirst(suffix: String, event: AnalyticsEvent): Boolean {
        val already = synchronized(firstEmitted) {
            if (firstEmitted.contains(suffix)) true else {
                firstEmitted.add(suffix)
                false
            }
        }
        if (already) return false
        scope.launch {
            runCatching { AppStore.putBoolean(firstFlagKey(suffix), true) }
        }
        track(event)
        return true
    }

    private fun firstFlagKey(suffix: String): String = "pf.analytics.first.$suffix.v1"

    private val FIRST_FLAG_SUFFIXES = listOf(
        "lessonStarted", "lessonCompleted", "quizCompleted",
        "practicalExercise", "pathSelected", "challengeCompleted"
    )

    // MARK: - Record helpers for call sites

    /** For the app entry point: first launch, launch, and return-after-absence. */
    fun noteAppLaunched(todayMillis: Long = System.currentTimeMillis()) {
        if (!isConsented) return
        scope.launch {
            val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = dayFormat.format(Date(todayMillis))
            val firstRecorded = runCatching {
                AppStore.getBoolean(FIRST_LAUNCH_RECORDED_KEY)
            }.getOrNull() ?: false
            if (!firstRecorded) {
                runCatching { AppStore.putBoolean(FIRST_LAUNCH_RECORDED_KEY, true) }
                track(AnalyticsEvent.FirstLaunch)
            }
            track(AnalyticsEvent.AppLaunched)
            val lastDay = runCatching { AppStore.getString(LAST_LAUNCH_DAY_KEY) }.getOrNull()
            if (lastDay != null && lastDay != today) {
                val days = runCatching {
                    val last = dayFormat.parse(lastDay)?.time ?: return@runCatching null
                    ((todayMillis - last) / 86_400_000L).toInt()
                }.getOrNull()
                if (days != null && days > 1) {
                    track(AnalyticsEvent.ReturnAfterAbsence(days))
                }
            }
            runCatching { AppStore.putString(LAST_LAUNCH_DAY_KEY, today) }
        }
    }

    fun lessonStarted(lessonID: String, chapterID: String) {
        track(AnalyticsEvent.LessonStarted(lessonID, chapterID))
        trackFirst("lessonStarted", AnalyticsEvent.FirstLessonStarted(lessonID, chapterID))
    }

    fun lessonCompleted(lessonID: String, chapterID: String) {
        track(AnalyticsEvent.LessonCompleted(lessonID, chapterID))
        trackFirst("lessonCompleted", AnalyticsEvent.FirstLessonCompleted(lessonID, chapterID))
    }

    fun quizCompleted(lessonID: String, correct: Int, total: Int, scorePercent: Double) {
        track(AnalyticsEvent.QuizCompleted(lessonID, correct, total, scorePercent))
        trackFirst("quizCompleted", AnalyticsEvent.FirstQuizCompleted(lessonID, correct, total, scorePercent))
        checkScoreImproved("quiz", lessonID, scorePercent)
    }

    fun practicalExerciseCompleted(exerciseID: String, kind: String) {
        track(AnalyticsEvent.PracticalExerciseCompleted(exerciseID, kind))
        trackFirst(
            "practicalExercise",
            AnalyticsEvent.FirstPracticalExerciseCompleted(exerciseID, kind)
        )
    }

    fun pathSelected(pathID: String, role: String) {
        track(AnalyticsEvent.PathSelected(pathID, role))
        trackFirst("pathSelected", AnalyticsEvent.FirstPathSelected(pathID, role))
    }

    fun challengeCompleted(correct: Boolean) {
        track(AnalyticsEvent.ChallengeCompleted(correct))
        trackFirst("challengeCompleted", AnalyticsEvent.FirstChallengeCompleted(correct))
    }

    fun purchaseStarted(productID: String) = track(AnalyticsEvent.PurchaseStarted(productID))
    fun purchaseCompleted(productID: String) = track(AnalyticsEvent.PurchaseCompleted(productID))
    fun purchaseFailed(productID: String, failureCategory: String) =
        track(AnalyticsEvent.PurchaseFailed(productID, failureCategory))

    fun purchaseCancelled(productID: String) = track(AnalyticsEvent.PurchaseCancelled(productID))
    fun paywallViewed(source: String, productID: String?) =
        track(AnalyticsEvent.PaywallViewed(source, productID))

    fun productSelected(productID: String, source: String) =
        track(AnalyticsEvent.ProductSelected(productID, source))

    /**
     * Score improvement: fires only when a new score beats the best
     * previous score stored for the same kind and scope. Scores compared
     * are always real recorded scores; no score is estimated or seeded.
     */
    fun checkScoreImproved(kind: String, scopeID: String, scorePercent: Double) {
        if (!isConsented) return
        scope.launch {
            val mapKey = "$kind:$scopeID"
            val stored = runCatching {
                val raw = AppStore.getString(PREVIOUS_SCORES_KEY) ?: "{}"
                JSONObject(raw)
            }.getOrNull() ?: JSONObject()
            val previous = if (stored.has(mapKey)) stored.optDouble(mapKey) else null
            if (previous != null && scorePercent > previous) {
                track(AnalyticsEvent.ScoreImproved(kind, scopeID, previous, scorePercent))
            }
            if (previous == null || scorePercent > previous) {
                stored.put(mapKey, scorePercent)
                runCatching { AppStore.putString(PREVIOUS_SCORES_KEY, stored.toString()) }
            }
        }
    }
}
