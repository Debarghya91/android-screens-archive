// InvestigationSessionHolder.kt
// Retained investigation-run state: the whole run (step, run
// record, selections, decisions, diagnosis, report) survives a
// rotation or a navigation away and back within the process.
// The experience screen keeps its state in plain remember and
// mirrors it here on every change; on a fresh composition it
// restores the held snapshot for the same case. A completed run
// (a report exists) is never restored: re-entering a finished
// case starts a fresh briefing, so replay is never blocked by
// an old report. Process death still starts fresh, as with a
// ViewModel. One in-progress run is held at a time.

package com.paymentstraininghub.app.features.investigation

internal object InvestigationSessionHolder {

    /** The full journey state of one in-progress investigation. */
    data class Snapshot(
        val caseId: String,
        val currentCase: InvestigationCase,
        val step: InvestigationJourneyStep,
        val run: InvestigationRun,
        val selectedEvidenceId: String?,
        val statusRequested: Boolean,
        val leadingHypothesisId: String?,
        val decisionIndex: Int,
        val solvedDecisionIds: Set<String>,
        val pickedOption: Int?,
        val pickedWasBest: Boolean,
        val triedWrong: Set<Int>,
        val wrongPickSummaries: List<String>,
        val diagnosisHypothesisId: String?,
        val citedEvidenceIds: Set<String>,
        val report: InvestigationReport?,
        val didRecordCompletion: Boolean
    )

    private var snapshot: Snapshot? = null

    /**
     * The held snapshot for [caseId], or null when there is none,
     * it belongs to another case, or the held run completed. A
     * completed snapshot is cleared as it is refused.
     */
    @Synchronized
    fun restore(caseId: String): Snapshot? {
        val held = snapshot?.takeIf { it.caseId == caseId } ?: return null
        if (held.report != null) {
            snapshot = null
            return null
        }
        return held
    }

    /** Hold [snapshot], replacing any snapshot for another case. */
    @Synchronized
    fun save(snapshot: Snapshot) {
        this.snapshot = snapshot
    }

    /** Drop the held snapshot for [caseId], if any. Used by tests. */
    @Synchronized
    fun clear(caseId: String) {
        if (snapshot?.caseId == caseId) {
            snapshot = null
        }
    }
}
