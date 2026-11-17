// InvestigationModels.kt
// Models for the Payments Mystery Investigation Experience: the
// investigation case, its evidence, hypotheses, decisions, report,
// and the in-memory record of one learner run.
//
// Port of iOS Models/InvestigationExperience.swift (v76). The case
// model is built by InvestigationEngine from a situation (derived
// tier) or from an authored lab case (authored tier). It carries no
// entitlement, purchase, auth, or progress state; gating stays with
// the existing stores and views. No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.investigation

import com.paymentstraininghub.app.features.lab.ExceptionLabScoring
import kotlin.math.roundToInt

// MARK: - Evidence

/** The kind of one evidence item, used to group the workspace list
 * and to pick the renderer for the item. */
enum class InvestigationEvidenceKind(val displayName: String) {
    TIMELINE("Timeline"),
    MESSAGE("Messages"),
    LOG("Logs"),
    LEDGER("Ledger"),
    RECON("Reconciliation"),
    API("API"),
    CLEARING("Clearing"),
    NOTE("Notes");

    companion object {
        /** Workspace order: timeline first, then messages, then the rest. */
        val orderedKinds: List<InvestigationEvidenceKind> = listOf(
            TIMELINE, MESSAGE, LOG, LEDGER, RECON, API, CLEARING, NOTE
        )
    }
}

/** Whether an evidence item is fully available. Partial and
 * unavailable reasons are systemic only (for example "available
 * only after a targeted status request"), never invented facts. */
sealed class InvestigationEvidenceCompleteness {
    /** The systemic reason, when the item is partial or unavailable. */
    abstract val reason: String?

    object Complete : InvestigationEvidenceCompleteness() {
        override val reason: String? = null
    }
    data class Partial(override val reason: String) : InvestigationEvidenceCompleteness()
    data class Unavailable(override val reason: String) : InvestigationEvidenceCompleteness()

    /** True when the item is fully available. */
    val isComplete: Boolean get() = this is Complete
}

/** One piece of evidence in an investigation case. [structuredLines]
 * carries field tables and log lines; [sourceField] names the
 * structured field the item derived from (for example "explain",
 * "messages", "visual", "domain", or "authored"), for auditability.
 * [revealedAfterStep] is kept from the authored Lab data for
 * auditability only: it never gates visibility, because the
 * learner decides which evidence to inspect, in any order, at
 * every difficulty. [isRedHerring] is authored tier only and is
 * always false in the derived tier. */
data class InvestigationEvidence(
    val id: String,
    val kind: InvestigationEvidenceKind,
    val title: String,
    val body: String,
    val structuredLines: List<String>,
    val sourceField: String,
    val revealedAfterStep: Int,
    val completeness: InvestigationEvidenceCompleteness,
    val isRedHerring: Boolean
) {
    /** A copy of this item with a different completeness, used when
     * the Expert difficulty marks one item partial. */
    fun withCompleteness(completeness: InvestigationEvidenceCompleteness): InvestigationEvidence =
        copy(completeness = completeness)
}

// MARK: - Hypotheses

/** Where one hypothesis came from: the flawed requirement stated in
 * the case, the root cause, or the category alternative template. */
enum class InvestigationHypothesisSource(val key: String) {
    FLAWED_REQUIREMENT("flawedRequirement"),
    ROOT_CAUSE("rootCause"),
    CATEGORY_ALTERNATIVE("categoryAlternative")
}

/** One hypothesis the learner can hold and revise. Exactly one
 * hypothesis per case is correct. */
data class InvestigationHypothesis(
    val id: String,
    val text: String,
    val isCorrect: Boolean,
    val source: InvestigationHypothesisSource
)

// MARK: - Decisions

/** One option in a decision point. [consequence] is the narrative
 * consequence shown on a wrong pick; it is empty for the best
 * option. [isRedHerring] marks an option that follows a distraction
 * in the evidence; it is authored tier only and is always false in
 * the derived tier, mirroring the evidence rule. Scoring charges
 * extra for a red-herring pick. */
data class InvestigationDecisionOption(
    val text: String,
    val isBest: Boolean,
    val feedback: String,
    val consequence: String = "",
    val evidenceNote: String? = null,
    val isRedHerring: Boolean = false
)

/** One decision point in the journey. [wrongTurnCost] is the score
 * cost of a wrong pick, reusing the Lab constant. [isTechnical]
 * marks a message or system decision (the technicalAccuracy
 * dimension); [isResolution] marks a corrective-action or
 * preventive-control decision (the resolutionQuality dimension).
 * [hypothesisPrompt] is an optional prompt to reconsider the
 * leading hypothesis after this decision. */
data class InvestigationDecisionPoint(
    val id: String,
    val prompt: String,
    val options: List<InvestigationDecisionOption>,
    val bestOptionIndex: Int,
    val whyCorrect: String,
    val wrongTurnCost: Int = ExceptionLabScoring.WRONG_PICK_DEDUCTION,
    val evidenceNote: String? = null,
    val hypothesisPrompt: String? = null,
    val isTechnical: Boolean = false,
    val isResolution: Boolean = false
)

// MARK: - Payment header and difficulty

/** The derived payment header for a case. Amount and currency come
 * from the title regex, or the not-stated wording when the title
 * and explain name none. Parties are always generic; the reference
 * is always not stated unless the explain names one. */
data class InvestigationPaymentHeader(
    val amountText: String,
    val currencyText: String,
    val partiesText: String,
    val referenceText: String
)

/** The difficulty mode for one run. Difficulty changes the
 * suggested evidence order, hints, and completeness; it never
 * locks evidence and never changes the number of decision points
 * or the case content. */
enum class InvestigationDifficulty(val key: String, val displayName: String) {
    ROOKIE("rookie", "Rookie"),
    PROFESSIONAL("professional", "Professional"),
    EXPERT("expert", "Expert");

    /** The guidance copy from the model, as on iOS. The briefing
     * view uses its own guidance wording (see
     * investigationDifficultyGuidance in the experience screen),
     * which differs slightly from this model copy on iOS too. */
    val guidance: String
        get() = when (this) {
            ROOKIE -> "Guided. All evidence is available from the start, in a suggested order you can follow or ignore, and each decision shows a method hint before you pick."
            PROFESSIONAL -> "Independent. All evidence is available from the start, and hints appear only after a wrong pick."
            EXPERT -> "Expert. One evidence item starts partial until you request its status, and no hints appear."
        }

    companion object {
        fun fromKey(key: String?): InvestigationDifficulty =
            entries.firstOrNull { it.key == key } ?: PROFESSIONAL
    }
}

/** Which tier built the case: derived from a situation by the
 * engine, or adapted from an authored lab case. */
enum class InvestigationSource(val key: String) {
    DERIVED("derived"),
    AUTHORED("authored")
}

// MARK: - Case

/** One investigation case: the briefing, the evidence workspace
 * contents, the hypothesis set, the decision points, and the
 * report source material. [timeline] is the kind-timeline subset
 * of [evidence], in journey order; the same items appear in both
 * lists. [reflectionPrompt] carries the case closing question,
 * which renders as a reflection prompt in the report and is never
 * a question the learner must answer. The case is built per
 * difficulty because Expert changes evidence completeness. */
data class InvestigationCase(
    val id: String,
    val situationId: String,
    val title: String,
    val severity: String,
    val incidentType: String,
    val businessContext: String,
    val customerImpact: String,
    val incidentReport: String,
    val objectives: List<String>,
    val paymentHeader: InvestigationPaymentHeader,
    val timeline: List<InvestigationEvidence>,
    val evidence: List<InvestigationEvidence>,
    val hypotheses: List<InvestigationHypothesis>,
    val decisions: List<InvestigationDecisionPoint>,
    val correctDiagnosis: String,
    val supportingEvidenceIds: List<String>,
    val correctiveAction: String,
    val preventiveControls: List<String>,
    val lessons: List<String>,
    val source: InvestigationSource,
    val difficulty: InvestigationDifficulty,
    val reflectionPrompt: String?
)

// MARK: - Scores, report, stored result

/** The five investigation dimensions, each 0 to 100. [overall] is
 * the mean of the five, rounded to the nearest integer. */
data class InvestigationDimensionScores(
    val evidenceInterpretation: Int,
    val efficiency: Int,
    val technicalAccuracy: Int,
    val rootCauseIdentification: Int,
    val resolutionQuality: Int
) {
    val overall: Int
        get() {
            val sum = evidenceInterpretation + efficiency + technicalAccuracy +
                rootCauseIdentification + resolutionQuality
            return (sum / 5.0).roundToInt()
        }
}

/** The investigation report for one completed run.
 * [completedAtMillis] is epoch milliseconds, the Android form of
 * the iOS completedAt Date. */
data class InvestigationReport(
    val caseId: String,
    val situationId: String,
    val difficulty: InvestigationDifficulty,
    val completedAtMillis: Long,
    val dimensionScores: InvestigationDimensionScores,
    val incorrectAssumptions: List<String>,
    val citedEvidenceIds: List<String>,
    val wrongPicks: Int,
    val evidenceOpenedCount: Int,
    val decisionsCorrect: Int,
    val decisionsTotal: Int
)

/** The stored result for one case: the best overall score earned on
 * it, how many times it has been completed, and the report from the
 * most recent completion. [difficulty] is the difficulty of the run
 * that set the best score. */
data class InvestigationResult(
    val caseId: String,
    val situationId: String,
    val difficulty: InvestigationDifficulty,
    val bestOverall: Int,
    val completions: Int,
    val lastCompletedAtMillis: Long,
    val lastReport: InvestigationReport
)

// MARK: - Run record

/** The in-memory record of one learner run, owned by the experience
 * screen and handed to InvestigationScoring at completion.
 * Hypothesis revision endpoints and the diagnosis pick are
 * hypothesis ids; decision pick keys are decision point ids.
 * [requestedStatusAction] records the Expert-only status request
 * action, which is an investigation action, not a scored decision. */
data class InvestigationRun(
    val openedEvidenceIds: Set<String> = emptySet(),
    val hypothesisRevisions: List<Pair<String, String>> = emptyList(),
    val decisionPicks: Map<String, Int> = emptyMap(),
    val citedEvidenceIds: List<String> = emptyList(),
    val diagnosisHypothesisId: String? = null,
    val requestedStatusAction: Boolean = false
) {
    companion object {
        fun empty(): InvestigationRun = InvestigationRun()
    }
}
