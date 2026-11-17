// InvestigationStore.kt
// Investigation Experience result storage. Port of iOS
// Services/InvestigationStore.swift (v76), following the Android
// ExceptionLabStore pattern (AppStore, StateFlow, org.json).
//
// Results live in AppStore, namespaced per account through
// AppStore itself, mirroring ExceptionLabStore: the same key
// namespacing and the same reload pattern on account change, so
// one account's results are never served to another. Results are
// device-only and are never mirrored to any server table: no
// server table exists for investigation results, and no sync is
// flagged and no Supabase call is made from this file. No em
// dashes in any user-facing text.

package com.paymentstraininghub.app.features.investigation

import com.paymentstraininghub.app.auth.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Stores investigation results locally, per account. Device only:
 * nothing here leaves the device. */
object InvestigationStore {

    /** The storage key, the same key scheme as iOS
     * (pf.investigationResults.v1). AppStore applies the account
     * namespace to this base key. */
    const val RESULTS_KEY = "pf.investigationResults.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _results = MutableStateFlow<List<InvestigationResult>>(emptyList())

    /** All results on this device, in the order the cases were
     * first completed. */
    val results: StateFlow<List<InvestigationResult>> = _results.asStateFlow()

    /** Ids of the cases completed at least once. */
    val completedCaseIds: Set<String> get() = _results.value.map { it.caseId }.toSet()

    /** How many cases have been completed at least once. */
    val completedCount: Int get() = _results.value.size

    init {
        scope.launch { load() }
    }

    /** Reload for the current account namespace, clearing the
     * previous account's results first. The coordinator calls this
     * on account change; guest results reach an account only
     * through the guest-to-account migration offer, which copies
     * the namespaced key before this reload runs. */
    fun reloadForCurrentAccount() {
        _results.value = emptyList()
        scope.launch { load() }
    }

    // MARK: - Recording

    /** Record one completed investigation. The best overall score
     * for the case is kept; a lower retry never lowers it. The
     * stored difficulty follows the run that set the best score.
     * The report from this completion is kept as the most recent
     * one. */
    fun recordCompletion(report: InvestigationReport) {
        val overall = report.dimensionScores.overall
        val current = _results.value.toMutableList()
        val index = current.indexOfFirst { it.caseId == report.caseId }
        if (index >= 0) {
            val existing = current[index]
            val isNewBest = overall >= existing.bestOverall
            current[index] = InvestigationResult(
                caseId = report.caseId,
                situationId = report.situationId,
                difficulty = if (isNewBest) report.difficulty else existing.difficulty,
                bestOverall = maxOf(existing.bestOverall, overall),
                completions = existing.completions + 1,
                lastCompletedAtMillis = report.completedAtMillis,
                lastReport = report
            )
        } else {
            current.add(
                InvestigationResult(
                    caseId = report.caseId,
                    situationId = report.situationId,
                    difficulty = report.difficulty,
                    bestOverall = overall,
                    completions = 1,
                    lastCompletedAtMillis = report.completedAtMillis,
                    lastReport = report
                )
            )
        }
        _results.value = current
        save()
    }

    /** The stored result for one case, if it has been completed. */
    fun resultFor(caseId: String): InvestigationResult? =
        _results.value.firstOrNull { it.caseId == caseId }

    /** True when the case has been completed at least once. */
    fun isCompleted(caseId: String): Boolean = resultFor(caseId) != null

    /** The best overall score for one case, or null when it has never
     * been completed. */
    fun bestOverallFor(caseId: String): Int? = resultFor(caseId)?.bestOverall

    // MARK: - Persistence

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(RESULTS_KEY) }.getOrNull() ?: return
        val decoded = runCatching { decodeResults(raw) }.getOrNull() ?: return
        _results.value = decoded
    }

    private fun save() {
        val encoded = runCatching { encodeResults(_results.value) }.getOrNull() ?: return
        scope.launch {
            runCatching { AppStore.putString(RESULTS_KEY, encoded) }
        }
    }

    internal fun encodeResults(results: List<InvestigationResult>): String {
        val array = JSONArray()
        results.forEach { result ->
            array.put(encodeResult(result))
        }
        return array.toString()
    }

    private fun encodeResult(result: InvestigationResult): JSONObject {
        val report = result.lastReport
        val scores = report.dimensionScores
        val scoresJson = JSONObject()
            .put("evidenceInterpretation", scores.evidenceInterpretation)
            .put("efficiency", scores.efficiency)
            .put("technicalAccuracy", scores.technicalAccuracy)
            .put("rootCauseIdentification", scores.rootCauseIdentification)
            .put("resolutionQuality", scores.resolutionQuality)
        val reportJson = JSONObject()
            .put("caseID", report.caseId)
            .put("situationID", report.situationId)
            .put("difficulty", report.difficulty.key)
            .put("completedAtMillis", report.completedAtMillis)
            .put("dimensionScores", scoresJson)
            .put("incorrectAssumptions", JSONArray(report.incorrectAssumptions))
            .put("citedEvidenceIDs", JSONArray(report.citedEvidenceIds))
            .put("wrongPicks", report.wrongPicks)
            .put("evidenceOpenedCount", report.evidenceOpenedCount)
            .put("decisionsCorrect", report.decisionsCorrect)
            .put("decisionsTotal", report.decisionsTotal)
        return JSONObject()
            .put("caseID", result.caseId)
            .put("situationID", result.situationId)
            .put("difficulty", result.difficulty.key)
            .put("bestOverall", result.bestOverall)
            .put("completions", result.completions)
            .put("lastCompletedAtMillis", result.lastCompletedAtMillis)
            .put("lastReport", reportJson)
    }

    internal fun decodeResults(raw: String): List<InvestigationResult> {
        val array = JSONArray(raw)
        val out = mutableListOf<InvestigationResult>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            out.add(decodeResult(item))
        }
        return out
    }

    private fun decodeResult(item: JSONObject): InvestigationResult {
        val reportJson = item.getJSONObject("lastReport")
        val scoresJson = reportJson.getJSONObject("dimensionScores")
        val report = InvestigationReport(
            caseId = reportJson.getString("caseID"),
            situationId = reportJson.getString("situationID"),
            difficulty = InvestigationDifficulty.fromKey(reportJson.optString("difficulty", null)),
            completedAtMillis = reportJson.getLong("completedAtMillis"),
            dimensionScores = InvestigationDimensionScores(
                evidenceInterpretation = scoresJson.getInt("evidenceInterpretation"),
                efficiency = scoresJson.getInt("efficiency"),
                technicalAccuracy = scoresJson.getInt("technicalAccuracy"),
                rootCauseIdentification = scoresJson.getInt("rootCauseIdentification"),
                resolutionQuality = scoresJson.getInt("resolutionQuality")
            ),
            incorrectAssumptions = reportJson.getJSONArray("incorrectAssumptions").toStringList(),
            citedEvidenceIds = reportJson.getJSONArray("citedEvidenceIDs").toStringList(),
            wrongPicks = reportJson.getInt("wrongPicks"),
            evidenceOpenedCount = reportJson.getInt("evidenceOpenedCount"),
            decisionsCorrect = reportJson.getInt("decisionsCorrect"),
            decisionsTotal = reportJson.getInt("decisionsTotal")
        )
        return InvestigationResult(
            caseId = item.getString("caseID"),
            situationId = item.getString("situationID"),
            difficulty = InvestigationDifficulty.fromKey(item.optString("difficulty", null)),
            bestOverall = item.getInt("bestOverall"),
            completions = item.getInt("completions"),
            lastCompletedAtMillis = item.getLong("lastCompletedAtMillis"),
            lastReport = report
        )
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            out.add(getString(i))
        }
        return out
    }
}
