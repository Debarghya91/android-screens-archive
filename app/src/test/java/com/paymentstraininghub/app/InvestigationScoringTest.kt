package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.investigation.InvestigationCase
import com.paymentstraininghub.app.features.investigation.InvestigationDifficulty
import com.paymentstraininghub.app.features.investigation.InvestigationHypothesis
import com.paymentstraininghub.app.features.investigation.InvestigationHypothesisSource
import com.paymentstraininghub.app.features.investigation.InvestigationPaymentHeader
import com.paymentstraininghub.app.features.investigation.InvestigationRun
import com.paymentstraininghub.app.features.investigation.InvestigationScoring
import com.paymentstraininghub.app.features.investigation.InvestigationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Investigation scoring tests for the root-cause rule (v74):
 * root-cause identification is the diagnosis pick alone, 100
 * when the diagnosis pick is the correct hypothesis and 0
 * otherwise. The citation is required to submit a diagnosis but
 * does not change this dimension.
 */
class InvestigationScoringTest {

    private fun minimalCase(): InvestigationCase {
        return InvestigationCase(
            id = "case-1",
            situationId = "SIT-1",
            title = "Test case",
            severity = "High",
            incidentType = "Test",
            businessContext = "",
            customerImpact = "",
            incidentReport = "",
            objectives = emptyList(),
            paymentHeader = InvestigationPaymentHeader("", "", "", ""),
            timeline = emptyList(),
            evidence = emptyList(),
            hypotheses = listOf(
                InvestigationHypothesis(
                    id = "h-wrong",
                    text = "Wrong cause",
                    isCorrect = false,
                    source = InvestigationHypothesisSource.CATEGORY_ALTERNATIVE
                ),
                InvestigationHypothesis(
                    id = "h-correct",
                    text = "Right cause",
                    isCorrect = true,
                    source = InvestigationHypothesisSource.ROOT_CAUSE
                )
            ),
            decisions = emptyList(),
            correctDiagnosis = "h-correct",
            supportingEvidenceIds = emptyList(),
            correctiveAction = "",
            preventiveControls = emptyList(),
            lessons = emptyList(),
            source = InvestigationSource.DERIVED,
            difficulty = InvestigationDifficulty.PROFESSIONAL,
            reflectionPrompt = null
        )
    }

    @Test
    fun rootCause_correctDiagnosisScores100() {
        val report = InvestigationScoring.score(
            minimalCase(),
            InvestigationRun(diagnosisHypothesisId = "h-correct")
        )
        assertEquals(100, report.dimensionScores.rootCauseIdentification)
    }

    @Test
    fun rootCause_citationDoesNotChangeTheScore() {
        val withoutCitation = InvestigationScoring.score(
            minimalCase(),
            InvestigationRun(diagnosisHypothesisId = "h-correct")
        )
        val withCitation = InvestigationScoring.score(
            minimalCase(),
            InvestigationRun(
                diagnosisHypothesisId = "h-correct",
                citedEvidenceIds = listOf("ev-1", "ev-2")
            )
        )
        assertEquals(
            withoutCitation.dimensionScores.rootCauseIdentification,
            withCitation.dimensionScores.rootCauseIdentification
        )
        assertEquals(100, withCitation.dimensionScores.rootCauseIdentification)
    }

    @Test
    fun rootCause_wrongDiagnosisScores0() {
        val report = InvestigationScoring.score(
            minimalCase(),
            InvestigationRun(diagnosisHypothesisId = "h-wrong")
        )
        assertEquals(0, report.dimensionScores.rootCauseIdentification)
    }

    @Test
    fun rootCause_noDiagnosisScores0() {
        val report = InvestigationScoring.score(minimalCase(), InvestigationRun.empty())
        assertEquals(0, report.dimensionScores.rootCauseIdentification)
    }

    @Test
    fun rootCause_unknownDiagnosisIdScores0() {
        val report = InvestigationScoring.score(
            minimalCase(),
            InvestigationRun(diagnosisHypothesisId = "h-missing")
        )
        assertEquals(0, report.dimensionScores.rootCauseIdentification)
    }
}
