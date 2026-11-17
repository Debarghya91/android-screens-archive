// Port of ExceptionCases.swift and ExceptionLabStore.swift (iOS). Each case
// is an investigation exercise built from one real Payment Situation. No
// scheme-specific mandatory rule is invented here. Cases are ordered by
// level, foundation first, then core, then advanced, so investigations get
// progressively harder. Lab results stay on this device. No em dashes.
package com.paymentstraininghub.app.features.lab

import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.features.analytics.AnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The level of a case. Display names match iOS SituationLevel. */
enum class ExceptionLevel(val displayName: String, val order: Int) {
    FOUNDATION("Foundation", 0),
    CORE("Core", 1),
    ADVANCED("Advanced", 2)
}

/**
 * One piece of evidence in a case. An item becomes visible once
 * [revealedAfterStep] steps have been solved (0 means visible from the
 * start). A red herring note is shown only in the completed-case summary,
 * never during play.
 */
data class ExceptionEvidence(
    val id: String,
    val title: String,
    val detail: String,
    val revealedAfterStep: Int,
    val isRedHerring: Boolean,
    val redHerringNote: String? = null
)

/** One possible next action in an investigation step. */
data class InvestigationOption(
    val text: String,
    val isRedHerring: Boolean,
    val feedback: String
)

/** One investigation step: the learner selects the next action. */
data class InvestigationStep(
    val id: String,
    val prompt: String,
    val options: List<InvestigationOption>,
    val bestOptionIndex: Int,
    val whyCorrect: String,
    val wrongTurnCost: String,
    val evidenceNote: String? = null
)

/** One investigation case in the lab. */
data class ExceptionCase(
    val id: String,
    val incidentType: String,
    val title: String,
    val level: ExceptionLevel,
    val narrative: String,
    val evidence: List<ExceptionEvidence>,
    val steps: List<InvestigationStep>,
    val resolution: String,
    val messages: List<String>,
    val systems: List<String>,
    val sourceSituationID: String? = null,
    val sourceSituationTitle: String? = null,
    val sourceTakeaway: String? = null
) {
    /** True when this case is a purely authored teaching exercise. */
    val isPracticeExercise: Boolean get() = sourceSituationID == null

    /** True when this case is grounded in one of the free-sample situations. */
    val isGroundedInFreeSample: Boolean get() =
        sourceSituationID != null && sourceSituationID in LabGating.FREE_SAMPLE_SITUATION_IDS

    /** True when opening this case requires the situations entitlement. */
    val requiresSituationsUnlock: Boolean get() =
        sourceSituationID != null && !isGroundedInFreeSample

    /** Evidence items that are genuine clues, used for thoroughness scoring. */
    val genuineEvidenceCount: Int get() = evidence.count { !it.isRedHerring }
}

/** All investigation cases, in play order. Data lives in ExceptionCaseData.kt. */
object ExceptionCaseCatalogue {
    val allCases: List<ExceptionCase> = ExceptionCaseData.allCases
    fun caseById(id: String): ExceptionCase? = allCases.firstOrNull { it.id == id }
}

/** How one step was solved. A step is solved only when the best option is picked. */
data class ExceptionStepResult(
    val stepID: String,
    val wrongPicks: Int,
    val redHerringPicks: Int,
    val solved: Boolean
) {
    /** True when the step was solved on the first pick. */
    val solvedBestFirst: Boolean get() = solved && wrongPicks == 0
}

/**
 * The score breakdown for one completed case. Deduction fields hold positive
 * magnitudes; the UI shows them with a minus sign. The total is never below zero.
 */
data class ExceptionLabScoreBreakdown(
    val bestFirstPoints: Int,
    val recoveredPoints: Int,
    val wrongTurnDeductions: Int,
    val redHerringDeductions: Int,
    val efficiencyBonus: Int,
    val thoroughnessPoints: Int,
    val total: Int,
    val maximum: Int,
    val stepsSolvedBestFirst: Int,
    val stepsSolvedAfterWrongTurn: Int,
    val totalWrongPicks: Int,
    val totalRedHerringPicks: Int,
    val evidenceOpenedCount: Int,
    val evidenceTotalCount: Int
)

/** Pure scoring rules for the lab. The UI states these same numbers. */
object ExceptionLabScoring {
    const val BEST_FIRST_POINTS_PER_STEP: Int = 20
    const val RECOVERED_POINTS_PER_STEP: Int = 8
    const val WRONG_PICK_DEDUCTION: Int = 5
    const val RED_HERRING_PICK_DEDUCTION: Int = 10
    const val EFFICIENCY_BONUS_NO_WRONG_TURN: Int = 10
    const val EFFICIENCY_BONUS_ONE_WRONG_TURN: Int = 5
    const val THOROUGHNESS_POINTS_PER_EVIDENCE: Int = 4

    /** The highest score a case can earn. */
    fun maximum(forCase: ExceptionCase): Int =
        forCase.steps.size * BEST_FIRST_POINTS_PER_STEP +
            EFFICIENCY_BONUS_NO_WRONG_TURN +
            forCase.genuineEvidenceCount * THOROUGHNESS_POINTS_PER_EVIDENCE

    /** The score breakdown for a completed case. Pure function. */
    fun breakdown(
        forCase: ExceptionCase,
        stepResults: List<ExceptionStepResult>,
        evidenceOpenedIDs: Set<String>
    ): ExceptionLabScoreBreakdown {
        val bestFirst = stepResults.count { it.solvedBestFirst }
        val recovered = stepResults.count { it.solved && !it.solvedBestFirst }
        val wrongPicks = stepResults.sumOf { it.wrongPicks }
        val redHerringPicks = stepResults.sumOf { it.redHerringPicks }
        val openedGenuine = forCase.evidence.count { !it.isRedHerring && it.id in evidenceOpenedIDs }
        val efficiency = when (wrongPicks) {
            0 -> EFFICIENCY_BONUS_NO_WRONG_TURN
            1 -> EFFICIENCY_BONUS_ONE_WRONG_TURN
            else -> 0
        }
        val bestFirstPoints = bestFirst * BEST_FIRST_POINTS_PER_STEP
        val recoveredPoints = recovered * RECOVERED_POINTS_PER_STEP
        val wrongTurnDeductions = wrongPicks * WRONG_PICK_DEDUCTION
        val redHerringDeductions = redHerringPicks * RED_HERRING_PICK_DEDUCTION
        val thoroughnessPoints = openedGenuine * THOROUGHNESS_POINTS_PER_EVIDENCE
        val total = maxOf(
            0,
            bestFirstPoints + recoveredPoints + efficiency + thoroughnessPoints -
                wrongTurnDeductions - redHerringDeductions
        )
        return ExceptionLabScoreBreakdown(
            bestFirstPoints = bestFirstPoints,
            recoveredPoints = recoveredPoints,
            wrongTurnDeductions = wrongTurnDeductions,
            redHerringDeductions = redHerringDeductions,
            efficiencyBonus = efficiency,
            thoroughnessPoints = thoroughnessPoints,
            total = total,
            maximum = maximum(forCase),
            stepsSolvedBestFirst = bestFirst,
            stepsSolvedAfterWrongTurn = recovered,
            totalWrongPicks = wrongPicks,
            totalRedHerringPicks = redHerringPicks,
            evidenceOpenedCount = openedGenuine,
            evidenceTotalCount = forCase.genuineEvidenceCount
        )
    }
}

/**
 * Gating for the lab, mirroring iOS InvestigationGate: a case grounded in a
 * non-free situation requires the Investigation Lab entitlement; a case
 * grounded in a free-sample situation is open to all; a purely authored
 * practice exercise is open to all. The Investigation Experience screen
 * gates on the same entitlement, so a case the list shows as open never
 * hits a different gate inside. (The case property that marks
 * situation-grounded cases is still named requiresSituationsUnlock; it
 * means "grounded in a non-free situation", and the entitlement that
 * opens it is the Investigation Lab.) The free-sample list is
 * ContentRepository's single source of truth; it is never copied here.
 * The lab product id is ProductCatalogue's.
 */
object LabGating {
    val FREE_SAMPLE_SITUATION_IDS: Set<String> get() = ContentRepository.freeSampleSituationIds.toSet()

    /** The Investigation Lab product id from ProductCatalogue. */
    val LAB_PRODUCT_ID: String get() = ProductCatalogue.investigationLabProductId

    fun isCaseOpen(case: ExceptionCase, labUnlocked: Boolean): Boolean =
        !case.requiresSituationsUnlock || labUnlocked
}

/** The stored result for one investigation case. */
data class ExceptionLabResult(
    val caseID: String,
    val bestTotal: Int,
    val bestMaximum: Int,
    val completions: Int,
    val lastCompletedAtMillis: Long,
    val lastBreakdown: ExceptionLabScoreBreakdown
)

/**
 * Stores investigation lab results locally, per account. Device only:
 * nothing here leaves the device and no server call is made.
 *
 * AppStore is suspend-only and applies account namespacing itself, so
 * callers pass base keys to getString/putString and never call keyFor.
 * Loads and saves run in this store's IO scope; in-memory state updates
 * synchronously so the UI never waits on storage.
 */
object ExceptionLabStore {
    private const val RESULTS_KEY = "pf.exceptionLabResults.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _results = MutableStateFlow<List<ExceptionLabResult>>(emptyList())
    val results: StateFlow<List<ExceptionLabResult>> = _results.asStateFlow()

    val completedCaseIDs: Set<String> get() = _results.value.map { it.caseID }.toSet()
    val completedCount: Int get() = _results.value.size

    init {
        scope.launch { load() }
    }

    /** Reload for the current account namespace. Call on account change. */
    fun reloadForCurrentAccount() {
        _results.value = emptyList()
        scope.launch { load() }
    }

    fun resultFor(caseID: String): ExceptionLabResult? =
        _results.value.firstOrNull { it.caseID == caseID }

    fun isCompleted(caseID: String): Boolean = resultFor(caseID) != null

    /** Record one completed investigation. A lower retry never lowers the best. */
    fun recordCompletion(caseID: String, breakdown: ExceptionLabScoreBreakdown) {
        val current = _results.value.toMutableList()
        val index = current.indexOfFirst { it.caseID == caseID }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                bestTotal = maxOf(existing.bestTotal, breakdown.total),
                bestMaximum = breakdown.maximum,
                completions = existing.completions + 1,
                lastCompletedAtMillis = System.currentTimeMillis(),
                lastBreakdown = breakdown
            )
        } else {
            current.add(
                ExceptionLabResult(
                    caseID = caseID,
                    bestTotal = breakdown.total,
                    bestMaximum = breakdown.maximum,
                    completions = 1,
                    lastCompletedAtMillis = System.currentTimeMillis(),
                    lastBreakdown = breakdown
                )
            )
        }
        _results.value = current
        save()
        AnalyticsService.practicalExerciseCompleted(caseID, "lab")
    }

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(RESULTS_KEY) }.getOrNull()
            ?: return
        val decoded = runCatching { decodeResults(raw) }.getOrNull() ?: return
        _results.value = decoded
    }

    private fun save() {
        val encoded = runCatching { encodeResults(_results.value) }.getOrNull() ?: return
        scope.launch {
            runCatching { AppStore.putString(RESULTS_KEY, encoded) }
        }
    }

    internal fun encodeResults(results: List<ExceptionLabResult>): String {
        val array = JSONArray()
        results.forEach { result ->
            val breakdown = result.lastBreakdown
            val breakdownJson = JSONObject()
                .put("bestFirstPoints", breakdown.bestFirstPoints)
                .put("recoveredPoints", breakdown.recoveredPoints)
                .put("wrongTurnDeductions", breakdown.wrongTurnDeductions)
                .put("redHerringDeductions", breakdown.redHerringDeductions)
                .put("efficiencyBonus", breakdown.efficiencyBonus)
                .put("thoroughnessPoints", breakdown.thoroughnessPoints)
                .put("total", breakdown.total)
                .put("maximum", breakdown.maximum)
                .put("stepsSolvedBestFirst", breakdown.stepsSolvedBestFirst)
                .put("stepsSolvedAfterWrongTurn", breakdown.stepsSolvedAfterWrongTurn)
                .put("totalWrongPicks", breakdown.totalWrongPicks)
                .put("totalRedHerringPicks", breakdown.totalRedHerringPicks)
                .put("evidenceOpenedCount", breakdown.evidenceOpenedCount)
                .put("evidenceTotalCount", breakdown.evidenceTotalCount)
            array.put(
                JSONObject()
                    .put("caseID", result.caseID)
                    .put("bestTotal", result.bestTotal)
                    .put("bestMaximum", result.bestMaximum)
                    .put("completions", result.completions)
                    .put("lastCompletedAtMillis", result.lastCompletedAtMillis)
                    .put("lastBreakdown", breakdownJson)
            )
        }
        return array.toString()
    }

    internal fun decodeResults(raw: String): List<ExceptionLabResult> {
        val array = JSONArray(raw)
        val out = mutableListOf<ExceptionLabResult>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val b = item.getJSONObject("lastBreakdown")
            out.add(
                ExceptionLabResult(
                    caseID = item.getString("caseID"),
                    bestTotal = item.getInt("bestTotal"),
                    bestMaximum = item.getInt("bestMaximum"),
                    completions = item.getInt("completions"),
                    lastCompletedAtMillis = item.getLong("lastCompletedAtMillis"),
                    lastBreakdown = ExceptionLabScoreBreakdown(
                        bestFirstPoints = b.getInt("bestFirstPoints"),
                        recoveredPoints = b.getInt("recoveredPoints"),
                        wrongTurnDeductions = b.getInt("wrongTurnDeductions"),
                        redHerringDeductions = b.getInt("redHerringDeductions"),
                        efficiencyBonus = b.getInt("efficiencyBonus"),
                        thoroughnessPoints = b.getInt("thoroughnessPoints"),
                        total = b.getInt("total"),
                        maximum = b.getInt("maximum"),
                        stepsSolvedBestFirst = b.getInt("stepsSolvedBestFirst"),
                        stepsSolvedAfterWrongTurn = b.getInt("stepsSolvedAfterWrongTurn"),
                        totalWrongPicks = b.getInt("totalWrongPicks"),
                        totalRedHerringPicks = b.getInt("totalRedHerringPicks"),
                        evidenceOpenedCount = b.getInt("evidenceOpenedCount"),
                        evidenceTotalCount = b.getInt("evidenceTotalCount")
                    )
                )
            )
        }
        return out
    }
}
