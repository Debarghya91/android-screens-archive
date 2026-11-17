// Port of StudioExercises.swift and StudioDraftStore.swift (iOS). Every
// exercise is grounded in one real lesson or one real Payment Situation.
// Worked examples are authored teaching content for a different but related
// task. Drafts stay on this device. The rubric review is a checklist, not
// AI. No em dashes.
package com.paymentstraininghub.app.features.studio

import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The BA and PO task types the studio practises. */
enum class StudioTaskType(val displayName: String) {
    BUSINESS_REQUIREMENTS("Business requirements"),
    BRD_FSD("BRD and FSD preparation"),
    FUNCTIONAL_SPECIFICATIONS("Functional specifications"),
    USER_STORIES("User stories"),
    ACCEPTANCE_CRITERIA("Acceptance criteria"),
    GHERKIN_SCENARIOS("Gherkin scenarios"),
    API_REQUIREMENTS("API requirements"),
    ISO_MESSAGE_MAPPING("ISO message mapping"),
    FIELD_VALIDATION("Field validation"),
    EXCEPTION_HANDLING("Exception handling"),
    RECONCILIATION("Reconciliation"),
    TEST_SCENARIOS("Test scenarios"),
    IMPACT_ASSESSMENT("Impact assessment"),
    STAKEHOLDER_MANAGEMENT("Stakeholder management"),
    NON_FUNCTIONAL_REQUIREMENTS("Non-functional requirements")
}

/** What an exercise is grounded in: one real lesson or one real situation. */
enum class StudioExerciseSource(val displayName: String) {
    LESSON("Lesson"),
    SITUATION("Situation")
}

/** One section the learner's draft should cover. */
data class StudioRequiredSection(val title: String, val keywords: List<String>)

/** One studio exercise. */
data class StudioExercise(
    val id: String,
    val title: String,
    val taskType: StudioTaskType,
    val sourceKind: StudioExerciseSource,
    val sourceID: String,
    val sourceTitle: String,
    val brief: String,
    val groundingNote: String,
    val requiredSections: List<StudioRequiredSection>,
    val workedExampleTitle: String,
    val workedExampleBody: String,
    val suggestedMinimumWords: Int,
    val suggestedMaximumWords: Int,
    val minimumAcceptanceCriteria: Int? = null,
    val requiresGherkin: Boolean
) {
    /** The citation shown with the exercise. */
    val sourceCitation: String get() = "${sourceKind.displayName} $sourceID: $sourceTitle"

    val wordCountGuidance: String get() =
        "Suggested $suggestedMinimumWords to $suggestedMaximumWords words."
}

/** All studio exercises. Data lives in StudioExerciseData.kt. */
object StudioExercises {
    val all: List<StudioExercise> = StudioExerciseData.all
    fun exerciseById(id: String): StudioExercise? = all.firstOrNull { it.id == id }
}

/**
 * Gating for the studio, mirroring LessonView and SituationsView exactly:
 * lesson exercises need the lesson unlocked (which includes the free first
 * lesson of each chapter, D77); situation exercises need Practice
 * Experiences unlocked or a free sample source. The free-sample list is
 * ContentRepository's single source of truth. BillingManager takes the
 * chapter id ("ch{n}") alongside the lesson id; [chapterIdForLesson]
 * derives it from the lesson id.
 */
object StudioGating {
    val FREE_SAMPLE_SITUATION_IDS: Set<String> get() = ContentRepository.freeSampleSituationIds.toSet()
    val PRACTICE_PRODUCT_ID: String get() = ProductCatalogue.practiceProductId

    /** The chapter id ("ch{n}") for a lesson id ("ch{n}s{m}"), or "" when the id has no chapter number. */
    fun chapterIdForLesson(lessonID: String): String {
        if (!lessonID.startsWith("ch")) return ""
        val digits = lessonID.drop(2).takeWhile { it.isDigit() }
        if (digits.isEmpty()) return ""
        return "ch$digits"
    }

    /**
     * True when an exercise is open for this learner. A lesson
     * exercise opens when its lesson is unlocked (the caller
     * passes the lesson gate). A situation exercise opens when its
     * source situation is a free sample, when it is this week's
     * free case (the weekly-case exemption, mirroring the iOS
     * Practice Mode gate), or when Practice Experiences is unlocked.
     * An unknown weekly case id (null) fails closed to the free
     * samples plus unlock, exactly as on iOS.
     */
    fun isExerciseOpen(
        exercise: StudioExercise,
        isLessonUnlocked: (String) -> Boolean,
        situationsUnlocked: Boolean,
        weeklyCaseId: String? = null
    ): Boolean = when (exercise.sourceKind) {
        StudioExerciseSource.LESSON -> isLessonUnlocked(exercise.sourceID)
        StudioExerciseSource.SITUATION ->
            exercise.sourceID in FREE_SAMPLE_SITUATION_IDS ||
                (weeklyCaseId != null && exercise.sourceID == weeklyCaseId) ||
                situationsUnlocked
    }
}

/** One saved studio draft. There is no server table for studio drafts. */
data class StudioDraft(
    val id: String = UUID.randomUUID().toString(),
    val exerciseID: String,
    val text: String,
    val completedSections: List<String> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    // Kept for stored-draft compatibility: drafts saved while the
    // AI-review feature existed may carry this field. Nothing
    // writes it any more, and nothing reads it.
    val aiReviewRequestedAtMillis: Long? = null
) {
    val wordCount: Int get() = StudioRubricReview.wordCount(text)
}

/**
 * Stores studio drafts locally, one draft per exercise, under the current
 * account namespace. Device only. AppStore is suspend-only and namespaces
 * keys itself; loads and saves run in this store's IO scope.
 */
object StudioDraftStore {
    private const val DRAFTS_KEY = "pf.studioDrafts.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _drafts = MutableStateFlow<List<StudioDraft>>(emptyList())
    val drafts: StateFlow<List<StudioDraft>> = _drafts.asStateFlow()

    init {
        scope.launch { load() }
    }

    /** Reload for the current account namespace. Call on account change. */
    fun reloadForCurrentAccount() {
        _drafts.value = emptyList()
        scope.launch { load() }
    }

    fun draftFor(exerciseID: String): StudioDraft? =
        _drafts.value.firstOrNull { it.exerciseID == exerciseID }

    fun hasDraftFor(exerciseID: String): Boolean = draftFor(exerciseID) != null

    /** All drafts, newest updated first. */
    fun allDrafts(): List<StudioDraft> =
        _drafts.value.sortedByDescending { it.updatedAtMillis }

    /**
     * Save the draft for one exercise. Saving an empty draft with no ticked
     * sections and no AI-review flag creates nothing: an empty workspace is
     * not a draft.
     */
    fun saveDraft(exerciseID: String, text: String, completedSections: List<String>) {
        val existing = draftFor(exerciseID)
        if (existing != null) {
            val updated = existing.copy(
                text = text,
                completedSections = completedSections,
                updatedAtMillis = System.currentTimeMillis()
            )
            _drafts.value = _drafts.value.map { if (it.id == existing.id) updated else it }
            save()
            return
        }
        if (text.isBlank() && completedSections.isEmpty()) return
        _drafts.value = _drafts.value + StudioDraft(
            exerciseID = exerciseID,
            text = text,
            completedSections = completedSections
        )
        save()
    }

    /** Delete the draft for one exercise. There is no copy anywhere else. */
    fun deleteDraftFor(exerciseID: String) {
        _drafts.value = _drafts.value.filter { it.exerciseID != exerciseID }
        save()
    }

    fun deleteDraft(id: String) {
        _drafts.value = _drafts.value.filter { it.id != id }
        save()
    }

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(DRAFTS_KEY) }.getOrNull()
            ?: return
        val decoded = runCatching { decodeDrafts(raw) }.getOrNull() ?: return
        _drafts.value = decoded
    }

    private fun save() {
        val encoded = runCatching { encodeDrafts(_drafts.value) }.getOrNull() ?: return
        scope.launch {
            runCatching { AppStore.putString(DRAFTS_KEY, encoded) }
        }
    }

    internal fun encodeDrafts(drafts: List<StudioDraft>): String {
        val array = JSONArray()
        drafts.forEach { draft ->
            val sections = JSONArray()
            draft.completedSections.forEach { sections.put(it) }
            val item = JSONObject()
                .put("id", draft.id)
                .put("exerciseID", draft.exerciseID)
                .put("text", draft.text)
                .put("completedSections", sections)
                .put("createdAtMillis", draft.createdAtMillis)
                .put("updatedAtMillis", draft.updatedAtMillis)
            if (draft.aiReviewRequestedAtMillis != null) {
                item.put("aiReviewRequestedAtMillis", draft.aiReviewRequestedAtMillis)
            }
            array.put(item)
        }
        return array.toString()
    }

    internal fun decodeDrafts(raw: String): List<StudioDraft> {
        val array = JSONArray(raw)
        val out = mutableListOf<StudioDraft>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val sectionsArray = item.optJSONArray("completedSections") ?: JSONArray()
            val sections = (0 until sectionsArray.length()).map { sectionsArray.getString(it) }
            out.add(
                StudioDraft(
                    id = item.getString("id"),
                    exerciseID = item.getString("exerciseID"),
                    text = item.getString("text"),
                    completedSections = sections,
                    createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                    updatedAtMillis = item.optLong("updatedAtMillis", System.currentTimeMillis()),
                    aiReviewRequestedAtMillis = if (item.has("aiReviewRequestedAtMillis")) {
                        item.getLong("aiReviewRequestedAtMillis")
                    } else {
                        null
                    }
                )
            )
        }
        return out
    }
}

/** One deterministic check on a draft. */
data class StudioRubricCheck(
    val id: String,
    val label: String,
    val passed: Boolean,
    val detail: String
)

/**
 * The deterministic local review. Pure functions with no network, no AI
 * and no stored state, so the same draft always gets the same result.
 * It never judges quality, and it never presents its result as an
 * assessment of the writing. Checklist review, not AI.
 */
object StudioRubricReview {
    /** Words in a draft: runs of non-whitespace characters. */
    fun wordCount(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * List lines in a draft: non-empty lines starting with a bullet (-, *,
     * or bullet character), a number followed by "." or ")", or "AC"
     * followed by a number, a space or a colon.
     */
    fun criteriaLineCount(text: String): Int =
        text.lines().count { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                false
            } else if (trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•")) {
                true
            } else if (trimmed.first().isDigit()) {
                val rest = trimmed.dropWhile { it.isDigit() }
                rest.startsWith(".") || rest.startsWith(")")
            } else {
                val upper = trimmed.uppercase()
                if (upper.startsWith("AC")) {
                    val first = upper.drop(2).firstOrNull()
                    first != null && (first.isDigit() || first == ' ' || first == ':' || first == '-')
                } else {
                    false
                }
            }
        }

    /** True when a whole word appears in the text, case-insensitively. */
    fun containsWord(word: String, text: String): Boolean {
        val needle = word.lowercase()
        return text.lowercase().split(Regex("[^A-Za-z0-9]+")).any { it == needle }
    }

    /**
     * All checks for one exercise and draft, in display order: word count,
     * one check per required section, then the criteria-count and Gherkin
     * checks when the exercise has them. An empty draft returns the
     * word-count check only, marked as not passed.
     */
    fun checks(forExercise: StudioExercise, draft: String): List<StudioRubricCheck> {
        val trimmed = draft.trim()
        val words = wordCount(trimmed)
        val result = mutableListOf<StudioRubricCheck>()
        val wordDetail = when {
            words == 0 -> "No words yet. ${forExercise.wordCountGuidance}"
            words < forExercise.suggestedMinimumWords -> "$words words. ${forExercise.wordCountGuidance}"
            words > forExercise.suggestedMaximumWords ->
                "$words words, above the suggested maximum. ${forExercise.wordCountGuidance} Consider tightening the draft."
            else -> "$words words. ${forExercise.wordCountGuidance}"
        }
        result.add(
            StudioRubricCheck(
                id = "words",
                label = "Word count",
                passed = words >= forExercise.suggestedMinimumWords,
                detail = wordDetail
            )
        )
        if (words == 0) return result
        val lowerDraft = trimmed.lowercase()
        forExercise.requiredSections.forEach { section ->
            val found = section.keywords.firstOrNull { lowerDraft.contains(it.lowercase()) }
            result.add(
                StudioRubricCheck(
                    id = "section-${section.title}",
                    label = "Section: ${section.title}",
                    passed = found != null,
                    detail = if (found != null) {
                        "Found a mention of \"$found\". This only checks that the section is mentioned, not that it is complete."
                    } else {
                        "Not found yet. Add a section covering: ${section.title}."
                    }
                )
            )
        }
        forExercise.minimumAcceptanceCriteria?.let { minimum ->
            val count = criteriaLineCount(trimmed)
            result.add(
                StudioRubricCheck(
                    id = "criteria",
                    label = "Acceptance criteria count",
                    passed = count >= minimum,
                    detail = "Counted $count list lines (lines starting with a bullet, a number, or AC). " +
                        "At least $minimum expected. This counts lines only; it does not judge whether each criterion is testable."
                )
            )
        }
        if (forExercise.requiresGherkin) {
            listOf("Given", "When", "Then").forEach { keyword ->
                val found = containsWord(keyword, trimmed)
                result.add(
                    StudioRubricCheck(
                        id = "gherkin-$keyword",
                        label = "Gherkin keyword: $keyword",
                        passed = found,
                        detail = if (found) {
                            "Found \"$keyword\"."
                        } else {
                            "Not found yet. Each scenario needs Given, When and Then."
                        }
                    )
                )
            }
        }
        return result
    }
}
