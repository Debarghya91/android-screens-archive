// InvestigationScoring.kt
// Scores one completed investigation run into the five-dimension
// investigation report, following the dimension rules in
// INVESTIGATION-PLAN.md section 5.1 exactly, as amended by v74:
// root-cause identification is the diagnosis pick alone. Port of
// iOS Services/InvestigationScoring.swift (v76). Wrong-pick and
// red-herring costs reuse the Lab constants, so the two scores can
// never drift apart. No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.investigation

import com.paymentstraininghub.app.features.lab.ExceptionLabScoring
import kotlin.math.roundToInt

object InvestigationScoring {

    /** Scores one run of one case into its investigation report.
     * Dimension rules: evidenceInterpretation is the share of
     * relevant evidence opened, where relevant means in
     * supportingEvidenceIds or cited by the learner; efficiency
     * follows the Lab mapping (0 wrong picks is 100, 1 wrong pick
     * is 90, otherwise 100 minus 5 per wrong pick and 10 extra per
     * red-herring pick, floored at 0); technicalAccuracy is the
     * share of message and system decisions answered correctly,
     * defaulting to 100 when no such decision exists;
     * rootCauseIdentification is 100 when the diagnosis pick is
     * the correct hypothesis and 0 otherwise; the citation is
     * required to submit a diagnosis but does not change this
     * dimension; resolutionQuality is the share of corrective-action
     * and preventive-control decisions answered correctly,
     * defaulting to 100 when none exists. Overall is the rounded
     * mean of the five, computed by InvestigationDimensionScores. */
    fun score(investigationCase: InvestigationCase, run: InvestigationRun): InvestigationReport {
        val decisions = investigationCase.decisions
        val evidenceIds = investigationCase.evidence.map { it.id }.toSet()
        val openedIds = run.openedEvidenceIds.intersect(evidenceIds)

        var wrongPicks = 0
        var redHerringPicks = 0
        var decisionsCorrect = 0
        for (decision in decisions) {
            val picked = run.decisionPicks[decision.id] ?: continue
            if (picked == decision.bestOptionIndex) {
                decisionsCorrect += 1
            } else {
                wrongPicks += 1
                if (picked in decision.options.indices && decision.options[picked].isRedHerring) {
                    redHerringPicks += 1
                }
            }
        }

        val dimensionScores = InvestigationDimensionScores(
            evidenceInterpretation = evidenceInterpretationScore(
                investigationCase = investigationCase,
                run = run,
                openedIds = openedIds,
                evidenceIds = evidenceIds
            ),
            efficiency = efficiencyScore(wrongPicks = wrongPicks, redHerringPicks = redHerringPicks),
            technicalAccuracy = pickAccuracy(
                decisions = decisions.filter { it.isTechnical },
                picks = run.decisionPicks
            ),
            rootCauseIdentification = rootCauseScore(investigationCase = investigationCase, run = run),
            resolutionQuality = pickAccuracy(
                decisions = decisions.filter { it.isResolution },
                picks = run.decisionPicks
            )
        )

        return InvestigationReport(
            caseId = investigationCase.id,
            situationId = investigationCase.situationId,
            difficulty = investigationCase.difficulty,
            completedAtMillis = System.currentTimeMillis(),
            dimensionScores = dimensionScores,
            incorrectAssumptions = incorrectAssumptions(investigationCase = investigationCase, run = run),
            citedEvidenceIds = run.citedEvidenceIds,
            wrongPicks = wrongPicks,
            evidenceOpenedCount = openedIds.size,
            decisionsCorrect = decisionsCorrect,
            decisionsTotal = decisions.size
        )
    }

    // MARK: - Dimensions

    private fun evidenceInterpretationScore(
        investigationCase: InvestigationCase,
        run: InvestigationRun,
        openedIds: Set<String>,
        evidenceIds: Set<String>
    ): Int {
        val relevant = investigationCase.supportingEvidenceIds.toSet()
            .union(run.citedEvidenceIds)
            .intersect(evidenceIds)
        if (relevant.isEmpty()) return 100
        val opened = relevant.intersect(openedIds).size
        return (opened.toDouble() / relevant.size.toDouble() * 100).roundToInt()
    }

    private fun efficiencyScore(wrongPicks: Int, redHerringPicks: Int): Int {
        if (wrongPicks == 0) return 100
        if (wrongPicks == 1) return 90
        val score = 100 -
            wrongPicks * ExceptionLabScoring.WRONG_PICK_DEDUCTION -
            redHerringPicks * ExceptionLabScoring.RED_HERRING_PICK_DEDUCTION
        return maxOf(0, score)
    }

    /** The share of the given decisions answered correctly, as a
     * 0 to 100 score. Unanswered decisions count as incorrect.
     * Defaults to 100 when the list is empty. */
    private fun pickAccuracy(
        decisions: List<InvestigationDecisionPoint>,
        picks: Map<String, Int>
    ): Int {
        if (decisions.isEmpty()) return 100
        val correct = decisions.count { picks[it.id] == it.bestOptionIndex }
        return (correct.toDouble() / decisions.size.toDouble() * 100).roundToInt()
    }

    /** Root-cause identification is the diagnosis pick alone:
     * 100 when the picked hypothesis is the correct one, 0
     * otherwise. Citations do not change this dimension. */
    private fun rootCauseScore(investigationCase: InvestigationCase, run: InvestigationRun): Int {
        val diagnosisId = run.diagnosisHypothesisId ?: return 0
        val correct = investigationCase.hypotheses.firstOrNull { it.isCorrect } ?: return 0
        val picked = hypothesisFor(diagnosisId, investigationCase) ?: return 0
        if (picked.id != correct.id) return 0
        return 100
    }

    // MARK: - Incorrect assumptions

    /** The incorrect assumptions the learner held: the texts of
     * incorrect hypotheses revised away from, in revision order,
     * plus the diagnosis text when the diagnosis pick itself was
     * incorrect. Each text appears once. */
    private fun incorrectAssumptions(
        investigationCase: InvestigationCase,
        run: InvestigationRun
    ): List<String> {
        val assumptions = mutableListOf<String>()
        for (revision in run.hypothesisRevisions) {
            val held = hypothesisFor(revision.first, investigationCase)
            if (held != null && !held.isCorrect && held.text !in assumptions) {
                assumptions.add(held.text)
            }
        }
        val diagnosisId = run.diagnosisHypothesisId
        if (diagnosisId != null) {
            val picked = hypothesisFor(diagnosisId, investigationCase)
            if (picked != null && !picked.isCorrect && picked.text !in assumptions) {
                assumptions.add(picked.text)
            }
        }
        return assumptions
    }

    /** Resolves a hypothesis reference to its hypothesis, matching
     * by id first and by exact text second, so a run record that
     * carries texts instead of ids still scores. */
    private fun hypothesisFor(
        idOrText: String,
        investigationCase: InvestigationCase
    ): InvestigationHypothesis? {
        val byId = investigationCase.hypotheses.firstOrNull { it.id == idOrText }
        if (byId != null) return byId
        return investigationCase.hypotheses.firstOrNull { it.text == idOrText }
    }
}
