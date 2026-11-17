// CompetencyStore.kt
// Competency scores from checkpoint assessments, ported from
// iOS CompetencyStore (Services/CompetencyStore.swift, key
// pf.competencyTotals.v1). Each dimension keeps cumulative
// correct/total counts across every recorded assessment; the
// score is 100 * correct / total. Nothing is averaged with
// invented data, and a dimension with no assessment has no
// score. Persisted through AppStore (account-namespaced, as
// iOS AccountScopedStorage). This store also closes the
// Practice-screen gap noted at build-parity-7: Practice Mode
// competency writes now have a home.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.auth.AppStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object CompetencyStore {
    private const val KEY = "pf.competencyTotals.v1"

    private data class Totals(val correct: Int, val total: Int)

    private var totals: Map<CompetencyDimension, Totals> = emptyMap()

    private val _scores = MutableStateFlow<Map<CompetencyDimension, Double>>(emptyMap())
    val scores: StateFlow<Map<CompetencyDimension, Double>> = _scores.asStateFlow()

    /** Mean of the assessed dimensions only. 0 when nothing has been assessed. */
    val overallScore: Double
        get() {
            val values = _scores.value.values
            if (values.isEmpty()) return 0.0
            return values.sum() / values.size
        }

    val hasAssessments: Boolean get() = _scores.value.isNotEmpty()

    val overallLevel: String
        get() {
            if (!hasAssessments) return "Not assessed yet"
            if (overallScore < 50) return "Beginner"
            if (overallScore <= 75) return "Intermediate"
            return "Advanced"
        }

    /** Score for one dimension, null when it has never been assessed. */
    fun scoreFor(dimension: CompetencyDimension): Double? = _scores.value[dimension]

    suspend fun refresh() {
        val raw = runCatching { AppStore.getString(KEY) }.getOrNull() ?: return
        val restored = mutableMapOf<CompetencyDimension, Totals>()
        runCatching {
            val root = JSONObject(raw)
            for (dimension in CompetencyDimension.values()) {
                if (!root.has(dimension.label)) continue
                val entry = root.getJSONObject(dimension.label)
                restored[dimension] = Totals(
                    correct = entry.optInt("correct", 0),
                    total = entry.optInt("total", 0)
                )
            }
        }
        totals = restored
        refreshScores()
    }

    /** Record one assessment result. Adds to the cumulative recorded total. */
    suspend fun recordAssessment(dimension: CompetencyDimension, correct: Int, total: Int) {
        if (total <= 0 || correct < 0 || correct > total) return
        val entry = totals[dimension] ?: Totals(0, 0)
        totals = totals + (dimension to Totals(entry.correct + correct, entry.total + total))
        refreshScores()
        runCatching {
            val root = JSONObject()
            for ((dim, value) in totals) {
                val entryJson = JSONObject()
                entryJson.put("correct", value.correct)
                entryJson.put("total", value.total)
                root.put(dim.label, entryJson)
            }
            AppStore.putString(KEY, root.toString())
        }
    }

    private fun refreshScores() {
        val next = mutableMapOf<CompetencyDimension, Double>()
        for ((dimension, entry) in totals) {
            if (entry.total > 0) {
                next[dimension] = 100.0 * entry.correct.toDouble() / entry.total.toDouble()
            }
        }
        _scores.value = next
    }
}
