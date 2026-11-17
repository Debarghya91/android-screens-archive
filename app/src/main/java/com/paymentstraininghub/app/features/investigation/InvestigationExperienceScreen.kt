// InvestigationExperienceScreen.kt
// The investigation experience: one case investigated from briefing
// to report. Port of iOS
// Views/Investigation/InvestigationExperienceView.swift (v76).
// The journey has six states: briefing, evidence, hypotheses,
// decisions, diagnosis and report. This screen owns the journey
// state machine and the run record, and re-checks the situations
// gate on every render. A locked case renders only the locked
// gate, with no case text at all.
//
// The host provides the navigation stack, as ExceptionLabScreen
// does. No new colors, fonts, storage keys or network calls.
// Content protection (.pfProtectedContent on iOS) has no Android
// equivalent in v1; see INVESTIGATION-NOTES.md. No em dashes in
// any user-facing text.

package com.paymentstraininghub.app.features.investigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfGoldButton
import com.paymentstraininghub.app.ui.theme.PfHeroHeader
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfRowButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// MARK: - Journey steps

internal enum class InvestigationJourneyStep(val title: String) {
    BRIEFING("Briefing"),
    EVIDENCE("Evidence"),
    HYPOTHESES("Hypotheses"),
    DECISIONS("Decisions"),
    DIAGNOSIS("Diagnosis"),
    REPORT("Report")
}

// MARK: - Shared helpers

/** Guidance copy for each difficulty, from the architecture plan.
 * Difficulty changes the suggested order, hints, and completeness;
 * it never locks evidence and never changes the number of decision
 * points or the case content. This is the view wording from iOS,
 * which differs slightly from the model guidance copy. */
private fun investigationDifficultyGuidance(difficulty: InvestigationDifficulty): String =
    when (difficulty) {
        InvestigationDifficulty.ROOKIE ->
            "Guided. All evidence is available from the start, in a suggested order you can follow or ignore. Hints guide your method before each decision, without naming the answer."
        InvestigationDifficulty.PROFESSIONAL ->
            "Independent. All evidence is available from the start, and hints appear only after a wrong pick."
        InvestigationDifficulty.EXPERT ->
            "One evidence item starts incomplete until you request its status. No hints."
    }

/** Severity pill colors: High reads as an error, Medium reads as
 * gold, Moderate reads as royal. */
private fun investigationSeverityColors(severity: String): Pair<Color, Color> =
    when (severity.lowercase()) {
        "high" -> PfColors.error.copy(alpha = 0.10f) to PfColors.error
        "medium" -> PfColors.goldSoft to PfColors.goldText
        "moderate" -> PfColors.royal.copy(alpha = 0.10f) to PfColors.royal
        else -> PfColors.royal.copy(alpha = 0.10f) to PfColors.royal
    }

/** Labels and icons for the evidence kinds, in workspace order:
 * timeline first, then messages, then the rest. */
private object InvestigationEvidencePresentation {
    fun label(kind: InvestigationEvidenceKind): String = when (kind) {
        InvestigationEvidenceKind.TIMELINE -> "Timeline"
        InvestigationEvidenceKind.MESSAGE -> "Messages"
        InvestigationEvidenceKind.LOG -> "Logs"
        InvestigationEvidenceKind.LEDGER -> "Ledger"
        InvestigationEvidenceKind.RECON -> "Reconciliation"
        InvestigationEvidenceKind.API -> "API"
        InvestigationEvidenceKind.CLEARING -> "Clearing"
        InvestigationEvidenceKind.NOTE -> "Notes"
    }

    fun singularLabel(kind: InvestigationEvidenceKind): String = when (kind) {
        InvestigationEvidenceKind.TIMELINE -> "Timeline"
        InvestigationEvidenceKind.MESSAGE -> "Message"
        InvestigationEvidenceKind.LOG -> "Log"
        InvestigationEvidenceKind.LEDGER -> "Ledger entry"
        InvestigationEvidenceKind.RECON -> "Reconciliation record"
        InvestigationEvidenceKind.API -> "API record"
        InvestigationEvidenceKind.CLEARING -> "Clearing record"
        InvestigationEvidenceKind.NOTE -> "Note"
    }

    fun icon(kind: InvestigationEvidenceKind): ImageVector = when (kind) {
        InvestigationEvidenceKind.TIMELINE -> Icons.Filled.Schedule
        InvestigationEvidenceKind.MESSAGE -> Icons.Filled.Email
        InvestigationEvidenceKind.LOG -> Icons.Filled.Description
        InvestigationEvidenceKind.LEDGER -> Icons.Filled.ReceiptLong
        InvestigationEvidenceKind.RECON -> Icons.Filled.SwapHoriz
        InvestigationEvidenceKind.API -> Icons.Filled.Api
        InvestigationEvidenceKind.CLEARING -> Icons.Filled.Verified
        InvestigationEvidenceKind.NOTE -> Icons.Filled.Notes
    }
}

/** The first message code named in a title, if any: an ISO 20022 code
 * (pacs, pain or camt) or an MT code. The message explorer opens the
 * code directly, and shows its list for a code it does not cover. */
private fun investigationMessageCode(title: String): String? {
    val pattern = Regex("""(?i)\b(pacs|pain|camt)\.[0-9]{3}\b|\bMT[0-9]{3}\b""")
    return pattern.find(title)?.value
}

/** Structured lines split into field pairs on the first colon. A line
 * with no usable colon renders as a plain line, with an empty key. */
private fun investigationFieldPairs(lines: List<String>): List<Pair<String, String>> =
    lines.map { line ->
        val colon = line.indexOf(":")
        if (colon >= 0) {
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                Pair(key, value)
            } else {
                Pair("", line)
            }
        } else {
            Pair("", line)
        }
    }

/** API lines split into request and response blocks. Lines before any
 * response marker belong to the request; a line starting with
 * "Response" starts the response block, and a line starting with
 * "Request" starts the request block again. */
private fun investigationApiSections(lines: List<String>): Pair<List<String>, List<String>> {
    val request = mutableListOf<String>()
    val response = mutableListOf<String>()
    var inResponse = false
    for (line in lines) {
        val lowered = line.lowercase()
        when {
            lowered.startsWith("response") -> {
                inResponse = true
                response.add(line)
            }
            lowered.startsWith("request") -> {
                inResponse = false
                request.add(line)
            }
            inResponse -> response.add(line)
            else -> request.add(line)
        }
    }
    return Pair(request, response)
}

/** The debit or credit side named in a ledger line, if any, so the
 * ledger table can label each posting. */
private fun investigationLedgerSide(line: String): String? {
    val lowered = line.lowercase()
    if (lowered.contains("credit") || lowered.contains(" cr ") || lowered.endsWith(" cr")) {
        return "Credit"
    }
    if (lowered.contains("debit") || lowered.contains(" dr ") || lowered.endsWith(" dr")) {
        return "Debit"
    }
    return null
}

/** Monospace style for log and API lines, matching the iOS
 * footnote monospaced rendering on soft navy blocks. */
private val InvestigationMonoStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 19.sp
)

/** A radio circle matching the iOS selection control: a 24dp ring,
 * filled centre when selected. */
@Composable
private fun InvestigationRadioCircle(isSelected: Boolean, borderColor: Color = PfColors.royal) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(2.dp, if (isSelected) borderColor else PfColors.border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(borderColor)
            )
        }
    }
}

/** A checkbox square matching the iOS citation control. */
@Composable
private fun InvestigationCheckSquare(isChecked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(2.dp, if (isChecked) PfColors.royal else PfColors.secondaryText, RoundedCornerShape(4.dp))
            .background(if (isChecked) PfColors.royal else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (isChecked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// MARK: - Experience screen

/** One investigation, from briefing to report. Owns the journey state
 * machine, the run record and the case actually being investigated.
 * The passed case already reflects its difficulty; the briefing step
 * lets the learner pick another difficulty before starting, and the
 * rebuild callback rebuilds the case through the engine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestigationExperienceScreen(
    investigation: InvestigationCase,
    isFreeSample: Boolean,
    isWeeklyFreeCase: Boolean,
    onDifficultyChange: (InvestigationDifficulty) -> InvestigationCase,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    // Collected so the gate refreshes when BillingManager loads.
    @Suppress("UNUSED_VARIABLE")
    val billingProducts by BillingManager.products.collectAsState()
    // Collected so the lock state refreshes when the admin
    // flag lands on restore: the bare gate call below only
    // re-evaluates on recomposition.
    @Suppress("UNUSED_VARIABLE")
    val isAdmin by AuthManager.isAdmin.collectAsState()

    // A retained run for this case, when one is in progress: a
    // rotation or a navigation away and back resumes it instead
    // of restarting the briefing. Completed runs never restore.
    val restored = remember(investigation.id) {
        InvestigationSessionHolder.restore(investigation.id)
    }

    var currentCase by remember { mutableStateOf(restored?.currentCase ?: investigation) }
    var step by remember { mutableStateOf(restored?.step ?: InvestigationJourneyStep.BRIEFING) }
    var run by remember { mutableStateOf(restored?.run ?: InvestigationRun.empty()) }
    var selectedEvidenceId by remember { mutableStateOf(restored?.selectedEvidenceId) }
    var statusRequested by remember { mutableStateOf(restored?.statusRequested ?: false) }
    var leadingHypothesisId by remember { mutableStateOf(restored?.leadingHypothesisId) }
    var decisionIndex by remember { mutableStateOf(restored?.decisionIndex ?: 0) }
    var solvedDecisionIds by remember { mutableStateOf(restored?.solvedDecisionIds ?: emptySet()) }
    var pickedOption by remember { mutableStateOf(restored?.pickedOption) }
    var pickedWasBest by remember { mutableStateOf(restored?.pickedWasBest ?: false) }
    var triedWrong by remember { mutableStateOf(restored?.triedWrong ?: emptySet()) }
    var wrongPickSummaries by remember { mutableStateOf(restored?.wrongPickSummaries ?: emptyList()) }
    var diagnosisHypothesisId by remember { mutableStateOf(restored?.diagnosisHypothesisId) }
    var citedEvidenceIds by remember { mutableStateOf(restored?.citedEvidenceIds ?: emptySet()) }
    var report by remember { mutableStateOf(restored?.report) }
    var didRecordCompletion by remember { mutableStateOf(restored?.didRecordCompletion ?: false) }

    // The run is mirrored to the retained holder on every change
    // (the snapshot is a data class, so the effect re-runs only
    // when something actually changed), and restored from it on
    // a fresh composition, so a rotation or a navigation away
    // and back keeps the learner mid-investigation.
    val heldSnapshot = InvestigationSessionHolder.Snapshot(
        caseId = investigation.id,
        currentCase = currentCase,
        step = step,
        run = run,
        selectedEvidenceId = selectedEvidenceId,
        statusRequested = statusRequested,
        leadingHypothesisId = leadingHypothesisId,
        decisionIndex = decisionIndex,
        solvedDecisionIds = solvedDecisionIds,
        pickedOption = pickedOption,
        pickedWasBest = pickedWasBest,
        triedWrong = triedWrong,
        wrongPickSummaries = wrongPickSummaries,
        diagnosisHypothesisId = diagnosisHypothesisId,
        citedEvidenceIds = citedEvidenceIds,
        report = report,
        didRecordCompletion = didRecordCompletion
    )
    LaunchedEffect(heldSnapshot) {
        InvestigationSessionHolder.save(heldSnapshot)
    }

    /** The Investigation Lab gate, re-checked on every render
     * including appear, matching SituationDetailView: free sample,
     * weekly free case, or the Investigation Lab unlock (the Lab
     * product, or the Integrated Course). Nothing else decides it. */
    val isLocked = !isFreeSample && !isWeeklyFreeCase && !BillingManager.isInvestigationLabUnlocked()

    val difficulty = currentCase.difficulty

    /** Difficulty may change only before the investigation starts:
     * once any evidence is opened, any hypothesis revised, any
     * decision picked or a diagnosis chosen, the difficulty is fixed. */
    val difficultyChangeAllowed = run.openedEvidenceIds.isEmpty() &&
        run.hypothesisRevisions.isEmpty() &&
        run.decisionPicks.isEmpty() &&
        run.diagnosisHypothesisId == null

    /** The evidence list. On iOS this is timeline plus evidence;
     * the timeline is a subset of the evidence, so concatenating
     * would render every timeline item twice. Android uses the
     * evidence list alone: every scoring use is by id, so the
     * scores are identical and no row renders twice. */
    val allEvidence: List<InvestigationEvidence> = currentCase.evidence

    /** Evidence the learner may see now. All evidence is available
     * from the start at every difficulty: the learner decides
     * which evidence to inspect, in any order. Rookie offers a
     * suggested order in the workspace header; it is a suggestion,
     * never a lock. Only Expert completeness can withhold an item
     * body, and only until the status request is made. */
    val visibleEvidence: List<InvestigationEvidence> = allEvidence

    val currentDecision: InvestigationDecisionPoint? =
        currentCase.decisions.getOrNull(decisionIndex)

    val openedEvidence: List<InvestigationEvidence> =
        allEvidence.filter { it.id in run.openedEvidenceIds }

    val citedEvidence: List<InvestigationEvidence> =
        allEvidence.filter { it.id in citedEvidenceIds }

    /** Incorrect assumptions for the report: earlier leading
     * hypotheses that were wrong, wrong decision picks, and anything
     * the scoring pass recorded, deduplicated in that order. */
    val incorrectAssumptions: List<String> = remember(run, wrongPickSummaries, report) {
        val items = mutableListOf<String>()
        for (revision in run.hypothesisRevisions) {
            val prior = currentCase.hypotheses.firstOrNull { it.id == revision.first }
            if (prior != null && !prior.isCorrect && prior.text !in items) {
                items.add(prior.text)
            }
        }
        for (summary in wrongPickSummaries) {
            if (summary !in items) items.add(summary)
        }
        report?.incorrectAssumptions?.forEach { assumption ->
            if (assumption !in items) items.add(assumption)
        }
        items
    }

    val scrollState = rememberTopScrollState()

    // The scroll anchor at the top of the journey
    // (investigationJourneyTop on iOS). Step transitions scroll
    // back to it, so the learner always lands on the new content
    // without having to scroll manually. Decision changes do NOT
    // scroll (Tester 2 E7): re-scrolling on every decision stole
    // taps mid-decision.
    LaunchedEffect(step) {
        scrollState.scrollTo(0)
    }

    fun resetJourneyState() {
        run = InvestigationRun.empty()
        selectedEvidenceId = null
        statusRequested = false
        leadingHypothesisId = null
        decisionIndex = 0
        solvedDecisionIds = emptySet()
        pickedOption = null
        pickedWasBest = false
        triedWrong = emptySet()
        wrongPickSummaries = emptyList()
        diagnosisHypothesisId = null
        citedEvidenceIds = emptySet()
        report = null
        didRecordCompletion = false
    }

    fun changeDifficulty(to: InvestigationDifficulty) {
        if (!difficultyChangeAllowed || to == currentCase.difficulty) return
        currentCase = onDifficultyChange(to)
        resetJourneyState()
    }

    /** Starts the investigation: the workspace auto-displays the
     * first evidence item before any tap, so that item counts as
     * opened from the start. The opened set is initialised with
     * it here, so "Evidence opened" is 1 when it is displayed
     * and the evidence-interpretation score counts it, exactly
     * as if the learner had tapped it. */
    fun beginInvestigation() {
        val first = visibleEvidence.firstOrNull()
        if (first != null) {
            run = run.copy(openedEvidenceIds = run.openedEvidenceIds + first.id)
        }
        step = InvestigationJourneyStep.EVIDENCE
    }

    fun selectEvidence(item: InvestigationEvidence) {
        selectedEvidenceId = item.id
        run = run.copy(openedEvidenceIds = run.openedEvidenceIds + item.id)
    }

    fun requestStatus() {
        statusRequested = true
        run = run.copy(requestedStatusAction = true)
    }

    fun selectLeadingHypothesis(hypothesis: InvestigationHypothesis) {
        val current = leadingHypothesisId
        if (current != null && current != hypothesis.id) {
            run = run.copy(
                hypothesisRevisions = run.hypothesisRevisions + Pair(current, hypothesis.id)
            )
        }
        leadingHypothesisId = hypothesis.id
    }

    /** Enter the decisions step at the first unsolved decision, or
     * the last decision when all are solved. Moving to another
     * decision clears any feedback showing for the previous one. */
    fun goToDecisions() {
        val target = currentCase.decisions.indexOfFirst { it.id !in solvedDecisionIds }
            .takeIf { it >= 0 } ?: maxOf(0, currentCase.decisions.size - 1)
        if (target != decisionIndex) {
            decisionIndex = target
            pickedOption = null
            pickedWasBest = false
            triedWrong = emptySet()
        }
        step = InvestigationJourneyStep.DECISIONS
    }

    /** Pick an option for a decision, following the Lab pattern: a
     * wrong pick can be retried, and the decision is solved only on
     * the best pick. The run keeps the first pick per decision, so
     * scoring can tell a best-first decision from a recovered one. */
    fun pickDecisionOption(decision: InvestigationDecisionPoint, index: Int) {
        if (pickedOption != null || index in triedWrong) return
        if (index < 0 || index >= decision.options.size) return
        if (decision.id !in run.decisionPicks) {
            run = run.copy(decisionPicks = run.decisionPicks + Pair(decision.id, index))
        }
        if (index == decision.bestOptionIndex) {
            pickedWasBest = true
        } else {
            pickedWasBest = false
            triedWrong = triedWrong + index
            wrongPickSummaries = wrongPickSummaries + "Action considered: ${decision.options[index].text}"
        }
        pickedOption = index
    }

    fun continueDecision(decision: InvestigationDecisionPoint) {
        solvedDecisionIds = solvedDecisionIds + decision.id
        pickedOption = null
        pickedWasBest = false
        triedWrong = emptySet()
        if (decisionIndex + 1 < currentCase.decisions.size) {
            decisionIndex += 1
        } else {
            step = InvestigationJourneyStep.DIAGNOSIS
        }
    }

    fun confirmDiagnosis() {
        val diagnosisId = diagnosisHypothesisId ?: return
        if (citedEvidenceIds.isEmpty()) return
        val updatedRun = run.copy(
            diagnosisHypothesisId = diagnosisId,
            citedEvidenceIds = allEvidence.filter { it.id in citedEvidenceIds }.map { it.id }
        )
        run = updatedRun
        val scored = InvestigationScoring.score(investigationCase = currentCase, run = updatedRun)
        report = scored
        if (!didRecordCompletion) {
            didRecordCompletion = true
            InvestigationStore.recordCompletion(scored)
        }
        step = InvestigationJourneyStep.REPORT
    }

    // System Back mirrors the on-screen step Back: a selected
    // evidence item is cleared first, then each step returns to
    // the previous one. The briefing step and the report step do
    // not intercept: Back there pops the route, as the on-screen
    // Back and Done buttons do. Without this, Back mid-journey
    // popped the route and discarded the run.
    val stepBackTarget = when (step) {
        InvestigationJourneyStep.EVIDENCE -> InvestigationJourneyStep.BRIEFING
        InvestigationJourneyStep.HYPOTHESES -> InvestigationJourneyStep.EVIDENCE
        InvestigationJourneyStep.DECISIONS -> InvestigationJourneyStep.HYPOTHESES
        InvestigationJourneyStep.DIAGNOSIS,
        InvestigationJourneyStep.BRIEFING,
        InvestigationJourneyStep.REPORT -> null
    }
    BackHandler(enabled = selectedEvidenceId != null || stepBackTarget != null ||
        step == InvestigationJourneyStep.DIAGNOSIS) {
        if (selectedEvidenceId != null) {
            selectedEvidenceId = null
        } else if (step == InvestigationJourneyStep.DIAGNOSIS) {
            goToDecisions()
        } else if (stepBackTarget != null) {
            step = stepBackTarget
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Investigation", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                navigationIconContentColor = PfColors.navy
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .background(PfColors.background)
                .padding(PfSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
        ) {
            Box(modifier = Modifier.testTag("investigationJourneyTop").height(0.dp).fillMaxWidth())
            if (isLocked) {
                InvestigationLockedGateView(
                    onCheckout = { onNavigate(Routes.checkout(ProductCatalogue.investigationLabProductId)) }
                )
            } else {
                // Progress header.
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Step ${step.ordinal + 1} of 6",
                            style = PfTextStyles.captionBold,
                            color = PfColors.navy,
                            modifier = Modifier.weight(1f)
                        )
                        Text(step.title, style = PfTextStyles.caption, color = PfColors.secondaryText)
                    }
                    PfProgressBar(progress = (step.ordinal + 1) / 6f)
                }
                when (step) {
                    InvestigationJourneyStep.BRIEFING -> InvestigationBriefingView(
                        investigation = currentCase,
                        difficultyChangeAllowed = difficultyChangeAllowed,
                        onDifficultySelected = ::changeDifficulty,
                        onStart = ::beginInvestigation
                    )
                    InvestigationJourneyStep.EVIDENCE -> InvestigationEvidenceWorkspaceView(
                        investigation = currentCase,
                        visibleEvidence = visibleEvidence,
                        totalEvidenceCount = allEvidence.size,
                        selectedEvidenceId = selectedEvidenceId,
                        openedEvidenceIds = run.openedEvidenceIds,
                        statusRequested = statusRequested,
                        onSelect = ::selectEvidence,
                        onRequestStatus = ::requestStatus,
                        onContinue = { step = InvestigationJourneyStep.HYPOTHESES },
                        onBack = { step = InvestigationJourneyStep.BRIEFING },
                        onOpenMessageExplorer = { code -> onNavigate(Routes.isoMessage(code)) }
                    )
                    InvestigationJourneyStep.HYPOTHESES -> InvestigationHypothesisView(
                        investigation = currentCase,
                        leadingHypothesisId = leadingHypothesisId,
                        onSelect = ::selectLeadingHypothesis,
                        onContinue = ::goToDecisions,
                        onBack = { step = InvestigationJourneyStep.EVIDENCE }
                    )
                    InvestigationJourneyStep.DECISIONS -> {
                        val decision = currentDecision
                        if (decision != null) {
                            InvestigationDecisionView(
                                decision = decision,
                                decisionIndex = decisionIndex,
                                decisionCount = currentCase.decisions.size,
                                solvedCount = solvedDecisionIds.size,
                                difficulty = difficulty,
                                hypotheses = currentCase.hypotheses,
                                leadingHypothesisId = leadingHypothesisId,
                                isSolved = decision.id in solvedDecisionIds,
                                pickedOption = pickedOption,
                                pickedWasBest = pickedWasBest,
                                triedWrong = triedWrong,
                                onPick = { pickDecisionOption(decision, it) },
                                onRetry = { pickedOption = null },
                                onContinue = { continueDecision(decision) },
                                onReviseHypothesis = ::selectLeadingHypothesis,
                                onBack = { step = InvestigationJourneyStep.HYPOTHESES },
                                onReviewEvidence = { step = InvestigationJourneyStep.EVIDENCE }
                            )
                        } else {
                            // No decision to show: the case has no decisions, or
                            // every decision is solved. Move on to the diagnosis.
                            PfCard {
                                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                                    Text(
                                        "There are no more decisions in this case.",
                                        style = PfTextStyles.body,
                                        color = PfColors.navy
                                    )
                                    PfPrimaryButton(
                                        title = "Continue to the diagnosis",
                                        onClick = { step = InvestigationJourneyStep.DIAGNOSIS }
                                    )
                                }
                            }
                        }
                    }
                    InvestigationJourneyStep.DIAGNOSIS -> InvestigationDiagnosisView(
                        investigation = currentCase,
                        openedEvidence = openedEvidence,
                        diagnosisHypothesisId = diagnosisHypothesisId,
                        onDiagnosisSelected = { diagnosisHypothesisId = it },
                        citedEvidenceIds = citedEvidenceIds,
                        onCitedChanged = { citedEvidenceIds = it },
                        onConfirm = ::confirmDiagnosis,
                        onBack = ::goToDecisions,
                        onGoToEvidence = { step = InvestigationJourneyStep.EVIDENCE }
                    )
                    InvestigationJourneyStep.REPORT -> {
                        val completedReport = report
                        if (completedReport != null) {
                            InvestigationReportView(
                                investigation = currentCase,
                                report = completedReport,
                                incorrectAssumptions = incorrectAssumptions,
                                citedEvidence = citedEvidence,
                                onDone = onBack
                            )
                        } else {
                            PfCard {
                                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                                    Text(
                                        "The report is not available yet. Complete the diagnosis to see it.",
                                        style = PfTextStyles.body,
                                        color = PfColors.navy
                                    )
                                    PfPrimaryButton(title = "Done", onClick = onBack)
                                }
                            }
                        }
                    }
                }
            }
            PfBottomInset()
        }
    }
}

/** The locked gate: generic copy plus the checkout entry, reusing
 * the SituationDetailView pattern. No case text of any kind
 * renders here: no title, no id, no briefing and no evidence.
 * The Investigation Lab unlocks this case; the Integrated Course
 * includes the Lab too. */
@Composable
private fun InvestigationLockedGateView(onCheckout: () -> Unit) {
    val price = BillingManager.priceText(ProductCatalogue.investigationLabProductId)
    val priceSuffix = if (price != "Price unavailable") " for $price" else ""
    PfCard(
        modifier = Modifier.testTag("investigationLockedGate")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PfIconTile(icon = Icons.Filled.Lock, size = 44.dp)
                Text("Unlock this case", style = PfTextStyles.cardTitle, color = PfColors.navy)
            }
            Text(
                "This investigation case is part of the Investigation Lab: the full 616-item Investigation Experience. " +
                    "Unlock all 616 cases$priceSuffix. " +
                    "The free samples stay free. The Integrated Course includes the Lab too.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            PfPrimaryButton(title = "Go to checkout", onClick = onCheckout)
        }
    }
}

// MARK: - Briefing

@Composable
private fun InvestigationBriefingView(
    investigation: InvestigationCase,
    difficultyChangeAllowed: Boolean,
    onDifficultySelected: (InvestigationDifficulty) -> Unit,
    onStart: () -> Unit
) {
    val difficulties = listOf(
        InvestigationDifficulty.ROOKIE,
        InvestigationDifficulty.PROFESSIONAL,
        InvestigationDifficulty.EXPERT
    )
    val results by InvestigationStore.results.collectAsState()
    val previousResult = results.firstOrNull { it.caseId == investigation.id }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        // Header section.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Text(investigation.incidentType, style = PfTextStyles.captionBold, color = PfColors.royal)
            Text(investigation.title, style = PfTextStyles.screenTitle, color = PfColors.navy)
            Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                PfPill(
                    text = "Case ${investigation.situationId}",
                    background = PfColors.navy.copy(alpha = 0.08f),
                    foreground = PfColors.navy
                )
                val severityColors = investigationSeverityColors(investigation.severity)
                PfPill(
                    text = "Severity: ${investigation.severity}",
                    background = severityColors.first,
                    foreground = severityColors.second
                )
                PfPill(
                    text = investigation.difficulty.displayName,
                    background = PfColors.goldSoft,
                    foreground = PfColors.goldText
                )
            }
            Text(
                "Severity is derived from the case level and failure category.",
                style = PfTextStyles.caption,
                color = PfColors.secondaryText
            )
        }

        // Context section.
        InvestigationBriefingTextSection(title = "Business context", text = investigation.businessContext)
        InvestigationBriefingTextSection(title = "Customer impact", text = investigation.customerImpact)
        InvestigationBriefingTextSection(title = "Incident report", text = investigation.incidentReport)

        // The payment details card. The amount row always renders,
        // and carries the single not-stated statement when the case
        // names no amount. Currency and reference rows are omitted
        // when the case does not state them, so an absent field is
        // never repeated across the panel.
        PfCard {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                Text("Payment details", style = PfTextStyles.cardTitle, color = PfColors.navy)
                InvestigationPaymentRow(label = "Amount", value = investigation.paymentHeader.amountText)
                if (investigation.paymentHeader.currencyText != "Not stated in the case") {
                    InvestigationPaymentRow(label = "Currency", value = investigation.paymentHeader.currencyText)
                }
                InvestigationPaymentRow(label = "Parties", value = investigation.paymentHeader.partiesText)
                if (investigation.paymentHeader.referenceText != "Reference not stated in the case") {
                    InvestigationPaymentRow(label = "Reference", value = investigation.paymentHeader.referenceText)
                }
            }
        }

        // Objectives section.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Investigation objectives")
            investigation.objectives.forEachIndexed { index, objective ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .border(2.dp, PfColors.royal, CircleShape)
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", style = PfTextStyles.captionBold, color = PfColors.royal)
                    }
                    Spacer(modifier = Modifier.width(PfSpacing.sm))
                    Text(objective, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                }
            }
        }

        // Difficulty section.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Choose your difficulty")
            Text(
                "Difficulty can be changed here, before the investigation starts.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            difficulties.forEach { difficulty ->
                InvestigationDifficultyRow(
                    difficulty = difficulty,
                    isSelected = investigation.difficulty == difficulty,
                    difficultyChangeAllowed = difficultyChangeAllowed,
                    onSelected = { onDifficultySelected(difficulty) }
                )
            }
            if (!difficultyChangeAllowed) {
                Text(
                    "Difficulty is fixed once the investigation has started.",
                    style = PfTextStyles.captionBold,
                    color = PfColors.secondaryText
                )
            }
        }

        if (previousResult != null) {
            Text(
                "Best score so far: ${previousResult.bestOverall} out of 100. " +
                    "Completed ${previousResult.completions} ${if (previousResult.completions == 1) "time" else "times"}.",
                style = PfTextStyles.captionBold,
                color = PfColors.royal
            )
        }
        PfGoldButton(title = "Start the investigation", onClick = onStart)
        Text(
            "Your investigation results stay on this device.",
            style = PfTextStyles.caption,
            color = PfColors.secondaryText
        )
    }
}

@Composable
private fun InvestigationBriefingTextSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
        Text(title, style = PfTextStyles.captionBold, color = PfColors.navy)
        PfBodyText(text = text, style = PfTextStyles.body, color = PfColors.navy)
    }
}

@Composable
private fun InvestigationPaymentRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = PfTextStyles.captionBold,
            color = PfColors.secondaryText,
            modifier = Modifier.width(80.dp)
        )
        Text(value, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InvestigationDifficultyRow(
    difficulty: InvestigationDifficulty,
    isSelected: Boolean,
    difficultyChangeAllowed: Boolean,
    onSelected: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isSelected) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) PfColors.royal else PfColors.border,
                shape = RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(enabled = difficultyChangeAllowed, onClick = onSelected)
            .semantics { contentDescription = "Difficulty: ${difficulty.displayName}" }
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                InvestigationRadioCircle(isSelected = isSelected)
            }
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Column(
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                modifier = Modifier.weight(1f).alpha(if (difficultyChangeAllowed) 1f else 0.7f)
            ) {
                Text(difficulty.displayName, style = PfTextStyles.body, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
                Text(
                    investigationDifficultyGuidance(difficulty),
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}

// MARK: - Evidence workspace

@Composable
private fun InvestigationEvidenceWorkspaceView(
    investigation: InvestigationCase,
    visibleEvidence: List<InvestigationEvidence>,
    totalEvidenceCount: Int,
    selectedEvidenceId: String?,
    openedEvidenceIds: Set<String>,
    statusRequested: Boolean,
    onSelect: (InvestigationEvidence) -> Unit,
    onRequestStatus: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onOpenMessageExplorer: (String) -> Unit
) {
    val groupedEvidence = InvestigationEvidenceKind.orderedKinds.mapNotNull { kind ->
        val items = visibleEvidence.filter { it.kind == kind }
        if (items.isEmpty()) null else Pair(kind, items)
    }

    val selectedEvidence: InvestigationEvidence? =
        selectedEvidenceId?.let { id -> visibleEvidence.firstOrNull { it.id == id } }
            ?: visibleEvidence.firstOrNull()

    /** Every item is available from the start at every difficulty.
     * Rookie adds a suggested order; it is a suggestion, never a
     * lock, and Continue is always available. */
    val headerSubtitle = when (investigation.difficulty) {
        InvestigationDifficulty.ROOKIE ->
            "${visibleEvidence.size} of $totalEvidenceCount items available. Suggested order: timeline first, then messages, then the rest. Open them in any order you choose."
        InvestigationDifficulty.PROFESSIONAL ->
            "${visibleEvidence.size} of $totalEvidenceCount items available. All evidence is available from the start."
        InvestigationDifficulty.EXPERT ->
            "${visibleEvidence.size} of $totalEvidenceCount items available. One item may be incomplete until you request its status."
    }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        PfHeroHeader {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                Text("Evidence workspace", style = PfTextStyles.sectionTitle, color = Color.White)
                Text(headerSubtitle, style = PfTextStyles.body, color = Color.White.copy(alpha = 0.85f))
            }
        }
        if (visibleEvidence.isEmpty()) {
            Text(
                "No evidence is available for this case yet.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
                groupedEvidence.forEach { (kind, items) ->
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                InvestigationEvidencePresentation.label(kind),
                                style = PfTextStyles.captionBold,
                                color = PfColors.navy,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${items.size} items", style = PfTextStyles.caption, color = PfColors.secondaryText)
                        }
                        items.forEach { item ->
                            InvestigationEvidenceRow(
                                item = item,
                                isSelected = selectedEvidence?.id == item.id,
                                isOpened = item.id in openedEvidenceIds,
                                onSelect = { onSelect(item) }
                            )
                        }
                    }
                }
            }
            val selected = selectedEvidence
            if (selected != null) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                        PfPill(text = InvestigationEvidencePresentation.singularLabel(selected.kind))
                        if (selected.id in openedEvidenceIds) {
                            PfPill(
                                text = "Opened",
                                background = PfColors.success.copy(alpha = 0.12f),
                                foreground = PfColors.success
                            )
                        }
                    }
                    Text(selected.title, style = PfTextStyles.cardTitle, color = PfColors.navy)
                    InvestigationDetailContent(
                        item = selected,
                        investigation = investigation,
                        statusRequested = statusRequested,
                        onRequestStatus = onRequestStatus,
                        onOpenMessageExplorer = onOpenMessageExplorer
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Box(modifier = Modifier.weight(1f)) {
                PfSecondaryButton(title = "Back", onClick = onBack)
            }
            Box(modifier = Modifier.weight(1f)) {
                PfPrimaryButton(title = "Continue to hypotheses", onClick = onContinue)
            }
        }
    }
}

@Composable
private fun InvestigationEvidenceRow(
    item: InvestigationEvidence,
    isSelected: Boolean,
    isOpened: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isSelected) PfColors.royal.copy(alpha = 0.08f) else PfColors.card)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) PfColors.royal else PfColors.border,
                shape = RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(onClick = onSelect)
            .semantics { contentDescription = "Evidence: ${item.title}" }
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PfIconTile(icon = InvestigationEvidencePresentation.icon(item.kind), size = 36.dp)
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Text(
                item.title,
                style = PfTextStyles.body,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.navy,
                modifier = Modifier.weight(1f)
            )
            if (isOpened) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Opened",
                    tint = PfColors.success,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(PfSpacing.xs))
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PfColors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Completeness decides what renders. Partial and unavailable
 * items show their reason and never show withheld body text. In
 * Expert, the partial item offers a status request, and the full
 * record appears only after the request is made. */
@Composable
private fun InvestigationDetailContent(
    item: InvestigationEvidence,
    investigation: InvestigationCase,
    statusRequested: Boolean,
    onRequestStatus: () -> Unit,
    onOpenMessageExplorer: (String) -> Unit
) {
    when (val completeness = item.completeness) {
        is InvestigationEvidenceCompleteness.Complete -> {
            InvestigationEvidenceRenderer(
                item = item,
                investigation = investigation,
                onOpenMessageExplorer = onOpenMessageExplorer
            )
        }
        is InvestigationEvidenceCompleteness.Partial -> {
            if (statusRequested && investigation.difficulty == InvestigationDifficulty.EXPERT) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                    Text(
                        "Status received. The full record is now shown.",
                        style = PfTextStyles.captionBold,
                        color = PfColors.success
                    )
                    InvestigationEvidenceRenderer(
                        item = item,
                        investigation = investigation,
                        onOpenMessageExplorer = onOpenMessageExplorer
                    )
                }
            } else {
                InvestigationIncompleteCard(
                    title = "This evidence is incomplete.",
                    reason = completeness.reason,
                    showRequestAction = investigation.difficulty == InvestigationDifficulty.EXPERT,
                    onRequestStatus = onRequestStatus
                )
            }
        }
        is InvestigationEvidenceCompleteness.Unavailable -> {
            InvestigationIncompleteCard(
                title = "This evidence is not available.",
                reason = completeness.reason,
                showRequestAction = false,
                onRequestStatus = onRequestStatus
            )
        }
    }
}

@Composable
private fun InvestigationIncompleteCard(
    title: String,
    reason: String,
    showRequestAction: Boolean,
    onRequestStatus: () -> Unit
) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Text(title, style = PfTextStyles.cardTitle, color = PfColors.navy)
            Text(reason, style = PfTextStyles.body, color = PfColors.secondaryText)
            if (showRequestAction) {
                PfPrimaryButton(title = "Request status", onClick = onRequestStatus)
            }
        }
    }
}

@Composable
private fun InvestigationEvidenceRenderer(
    item: InvestigationEvidence,
    investigation: InvestigationCase,
    onOpenMessageExplorer: (String) -> Unit
) {
    when (item.kind) {
        InvestigationEvidenceKind.TIMELINE -> {
            val positionText = investigation.timeline.indexOfFirst { it.id == item.id }
                .takeIf { it >= 0 }
                ?.let { "Step ${it + 1} of ${investigation.timeline.size}" }
            InvestigationTimelineView(evidence = item, positionText = positionText)
        }
        InvestigationEvidenceKind.MESSAGE -> InvestigationMessageEvidenceView(
            evidence = item,
            onOpenMessageExplorer = onOpenMessageExplorer
        )
        InvestigationEvidenceKind.LOG -> InvestigationLogEvidenceView(evidence = item)
        InvestigationEvidenceKind.LEDGER -> InvestigationLedgerEvidenceView(evidence = item)
        InvestigationEvidenceKind.RECON -> InvestigationReconEvidenceView(evidence = item)
        InvestigationEvidenceKind.API -> InvestigationApiEvidenceView(evidence = item)
        InvestigationEvidenceKind.CLEARING -> InvestigationClearingEvidenceView(evidence = item)
        InvestigationEvidenceKind.NOTE -> InvestigationNoteEvidenceView(evidence = item)
    }
}

// MARK: - Hypotheses

@Composable
private fun InvestigationHypothesisView(
    investigation: InvestigationCase,
    leadingHypothesisId: String?,
    onSelect: (InvestigationHypothesis) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Text("What do you believe happened?", style = PfTextStyles.sectionTitle, color = PfColors.navy)
            Text(
                "Pick a leading hypothesis. You can revise it at any time before the diagnosis. Each revision is recorded for the final report.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            Text(
                "Test each hypothesis against the evidence you opened. The strongest hypothesis is the one the records support, not the one that sounds most familiar.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
        }
        if (investigation.hypotheses.isEmpty()) {
            Text(
                "No hypotheses were provided for this case.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
        } else {
            investigation.hypotheses.forEach { hypothesis ->
                InvestigationHypothesisCard(
                    hypothesis = hypothesis,
                    isLeading = leadingHypothesisId == hypothesis.id,
                    onSelect = { onSelect(hypothesis) }
                )
            }
        }
        if (leadingHypothesisId == null && investigation.hypotheses.isNotEmpty()) {
            Text(
                "Pick a leading hypothesis to continue.",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Box(modifier = Modifier.weight(1f)) {
                PfSecondaryButton(title = "Back", onClick = onBack)
            }
            Box(modifier = Modifier.weight(1f)) {
                PfPrimaryButton(
                    title = "Continue to decisions",
                    onClick = onContinue,
                    enabled = leadingHypothesisId != null || investigation.hypotheses.isEmpty()
                )
            }
        }
    }
}

@Composable
private fun InvestigationHypothesisCard(
    hypothesis: InvestigationHypothesis,
    isLeading: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isLeading) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
            .border(
                width = if (isLeading) 2.dp else 1.dp,
                color = if (isLeading) PfColors.royal else PfColors.border,
                shape = RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(onClick = onSelect)
            .semantics { contentDescription = "Hypothesis: ${hypothesis.text}" }
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                InvestigationRadioCircle(isSelected = isLeading)
            }
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Column(
                verticalArrangement = Arrangement.spacedBy(PfSpacing.sm),
                modifier = Modifier.weight(1f)
            ) {
                Text(hypothesis.text, style = PfTextStyles.body, color = PfColors.navy)
                if (isLeading) {
                    PfPill(
                        text = "Leading hypothesis",
                        background = PfColors.royal.copy(alpha = 0.10f),
                        foreground = PfColors.royal
                    )
                }
            }
        }
    }
}

// MARK: - Decisions

@Composable
private fun InvestigationDecisionView(
    decision: InvestigationDecisionPoint,
    decisionIndex: Int,
    decisionCount: Int,
    solvedCount: Int,
    difficulty: InvestigationDifficulty,
    hypotheses: List<InvestigationHypothesis>,
    leadingHypothesisId: String?,
    isSolved: Boolean,
    pickedOption: Int?,
    pickedWasBest: Boolean,
    triedWrong: Set<Int>,
    onPick: (Int) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onReviseHypothesis: (InvestigationHypothesis) -> Unit,
    onBack: () -> Unit,
    onReviewEvidence: () -> Unit
) {
    val optionLetters = listOf("A", "B", "C", "D", "E", "F")
    val isLastDecision = decisionIndex >= decisionCount - 1
    val continueTitle = if (isLastDecision) "Continue to the diagnosis" else "Continue to the next decision"

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Decision ${decisionIndex + 1} of $decisionCount",
                    style = PfTextStyles.captionBold,
                    color = PfColors.navy,
                    modifier = Modifier.weight(1f)
                )
                Text("$solvedCount solved", style = PfTextStyles.caption, color = PfColors.secondaryText)
            }
            PfProgressBar(progress = if (decisionCount > 0) solvedCount.toFloat() / decisionCount.toFloat() else 0f)
        }
        Text(decision.prompt, style = PfTextStyles.cardTitle, color = PfColors.navy)
        if (isSolved) {
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = PfColors.navy,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("You solved this decision.", style = PfTextStyles.cardTitle, color = PfColors.navy)
                    }
                    Text(decision.whyCorrect, style = PfTextStyles.body, color = PfColors.secondaryText)
                    PfPrimaryButton(title = continueTitle, onClick = onContinue)
                }
            }
        } else {
            // The Rookie hint, shown before the pick. It guides method
            // only: it names no answer and no evidence item.
            if (difficulty == InvestigationDifficulty.ROOKIE && pickedOption == null) {
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                        Text("Hint", style = PfTextStyles.captionBold, color = PfColors.navy)
                        Text(
                            InvestigationEngine.hintText(decision),
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
            if (decision.options.isEmpty()) {
                Text(
                    "No options were provided for this decision.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            } else {
                decision.options.forEachIndexed { index, option ->
                    InvestigationDecisionOptionButton(
                        option = option,
                        index = index,
                        letter = if (index < optionLetters.size) optionLetters[index] else "${index + 1}",
                        decision = decision,
                        pickedOption = pickedOption,
                        triedWrong = triedWrong,
                        onPick = { onPick(index) }
                    )
                }
            }
            val picked = pickedOption
            if (picked != null && picked in decision.options.indices) {
                InvestigationDecisionFeedbackCard(
                    decision = decision,
                    pickedIndex = picked,
                    pickedWasBest = pickedWasBest,
                    difficulty = difficulty,
                    hypotheses = hypotheses,
                    leadingHypothesisId = leadingHypothesisId,
                    continueTitle = continueTitle,
                    onRetry = onRetry,
                    onContinue = onContinue,
                    onReviseHypothesis = onReviseHypothesis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Box(modifier = Modifier.weight(1f)) {
                PfSecondaryButton(title = "Back", onClick = onBack)
            }
            Box(modifier = Modifier.weight(1f)) {
                PfSecondaryButton(title = "Review evidence", onClick = onReviewEvidence)
            }
        }
    }
}

@Composable
private fun InvestigationDecisionOptionButton(
    option: InvestigationDecisionOption,
    index: Int,
    letter: String,
    decision: InvestigationDecisionPoint,
    pickedOption: Int?,
    triedWrong: Set<Int>,
    onPick: () -> Unit
) {
    val isPicked = pickedOption == index
    val isBest = index == decision.bestOptionIndex
    val wasTried = index in triedWrong
    val feedbackShown = pickedOption != null
    val borderColor = when {
        feedbackShown && isBest -> PfColors.success
        feedbackShown && isPicked -> PfColors.error
        isPicked -> PfColors.royal
        else -> PfColors.border
    }
    val stateText = when {
        feedbackShown && isBest -> ", best next step"
        feedbackShown && isPicked -> ", not the best next step"
        wasTried -> ", already tried"
        else -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isPicked) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
            .border(
                width = if (feedbackShown && (isBest || isPicked)) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(enabled = !feedbackShown && !wasTried, onClick = onPick)
            .semantics { contentDescription = "Option $letter: ${option.text}$stateText" }
            .alpha(if (wasTried && !isPicked) 0.6f else 1f)
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                InvestigationRadioCircle(isSelected = isPicked, borderColor = borderColor)
            }
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Column(
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                modifier = Modifier.weight(1f)
            ) {
                Text("$letter. ${option.text}", style = PfTextStyles.body, color = PfColors.navy)
                if (feedbackShown && isBest) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = PfColors.success,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Best next step", style = PfTextStyles.captionBold, color = PfColors.success)
                    }
                } else if (feedbackShown && isPicked) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HighlightOff,
                            contentDescription = null,
                            tint = PfColors.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Not the best next step", style = PfTextStyles.captionBold, color = PfColors.error)
                    }
                } else if (wasTried) {
                    Text("Already tried", style = PfTextStyles.captionBold, color = PfColors.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun InvestigationDecisionFeedbackCard(
    decision: InvestigationDecisionPoint,
    pickedIndex: Int,
    pickedWasBest: Boolean,
    difficulty: InvestigationDifficulty,
    hypotheses: List<InvestigationHypothesis>,
    leadingHypothesisId: String?,
    continueTitle: String,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onReviseHypothesis: (InvestigationHypothesis) -> Unit
) {
    val option = decision.options[pickedIndex]
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (pickedWasBest) Icons.Filled.CheckCircle else Icons.Filled.HighlightOff,
                    contentDescription = null,
                    tint = if (pickedWasBest) PfColors.success else PfColors.error,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    if (pickedWasBest) "Best next step." else "Not the best next step.",
                    style = PfTextStyles.cardTitle,
                    color = PfColors.navy
                )
            }
            Text(option.feedback, style = PfTextStyles.body, color = PfColors.navy)
            if (pickedWasBest) {
                Text(decision.whyCorrect, style = PfTextStyles.body, color = PfColors.secondaryText)
                decision.evidenceNote?.let { note ->
                    Text(note, style = PfTextStyles.body, color = PfColors.secondaryText)
                }
                decision.hypothesisPrompt?.let { prompt ->
                    InvestigationHypothesisRevisionSection(
                        prompt = prompt,
                        hypotheses = hypotheses,
                        leadingHypothesisId = leadingHypothesisId,
                        onReviseHypothesis = onReviseHypothesis
                    )
                }
                PfPrimaryButton(title = continueTitle, onClick = onContinue)
            } else {
                Text("Consequence: ${option.consequence}", style = PfTextStyles.body, color = PfColors.secondaryText)
                Text(
                    "This wrong turn costs ${decision.wrongTurnCost} points.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                if (difficulty == InvestigationDifficulty.PROFESSIONAL) {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                        Text("Hint", style = PfTextStyles.captionBold, color = PfColors.navy)
                        Text(
                            InvestigationEngine.hintText(decision),
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    }
                }
                PfPrimaryButton(title = "Try again", onClick = onRetry)
            }
        }
    }
}

@Composable
private fun InvestigationHypothesisRevisionSection(
    prompt: String,
    hypotheses: List<InvestigationHypothesis>,
    leadingHypothesisId: String?,
    onReviseHypothesis: (InvestigationHypothesis) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
        Text(prompt, style = PfTextStyles.body, color = PfColors.navy)
        hypotheses.forEach { hypothesis ->
            val isLeading = leadingHypothesisId == hypothesis.id
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PfSpacing.cornerRadius))
                    .background(if (isLeading) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
                    .border(
                        1.dp,
                        if (isLeading) PfColors.royal else PfColors.border,
                        RoundedCornerShape(PfSpacing.cornerRadius)
                    )
                    .clickable(onClick = { onReviseHypothesis(hypothesis) })
                    .semantics { contentDescription = "Set leading hypothesis: ${hypothesis.text}" }
                    .padding(PfSpacing.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hypothesis.text,
                        style = PfTextStyles.body,
                        color = PfColors.navy,
                        modifier = Modifier.weight(1f)
                    )
                    if (isLeading) {
                        PfPill(
                            text = "Leading",
                            background = PfColors.royal.copy(alpha = 0.10f),
                            foreground = PfColors.royal
                        )
                    }
                }
            }
        }
        Text(
            "You can keep your current leading hypothesis and continue. Any change is recorded for the final report.",
            style = PfTextStyles.caption,
            color = PfColors.secondaryText
        )
    }
}

// MARK: - Diagnosis

@Composable
private fun InvestigationDiagnosisView(
    investigation: InvestigationCase,
    openedEvidence: List<InvestigationEvidence>,
    diagnosisHypothesisId: String?,
    onDiagnosisSelected: (String) -> Unit,
    citedEvidenceIds: Set<String>,
    onCitedChanged: (Set<String>) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onGoToEvidence: () -> Unit
) {
    /** True after a blocked submit with no citation chosen. The
     * citation gate error stays visible until the learner cites
     * an evidence item. */
    var showCitationGateError by remember { mutableStateOf(false) }

    LaunchedEffect(citedEvidenceIds) {
        if (citedEvidenceIds.isNotEmpty()) {
            showCitationGateError = false
        }
    }

    val guidanceText: String? = when {
        diagnosisHypothesisId == null && citedEvidenceIds.isEmpty() ->
            "Pick a root cause and cite at least one evidence item you opened."
        diagnosisHypothesisId == null ->
            "Pick a root cause from the list."
        citedEvidenceIds.isEmpty() ->
            "Cite at least one evidence item you opened to support the diagnosis."
        else -> null
    }

    /** A blocked submit is never silent: with no citation chosen,
     * the citation gate error shows and stays visible until a
     * citation is chosen. With a citation, the confirm action
     * runs and the parent still requires a root-cause pick. */
    fun confirmTapped() {
        if (citedEvidenceIds.isEmpty()) {
            showCitationGateError = true
        } else {
            onConfirm()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Text("Confirm the diagnosis", style = PfTextStyles.sectionTitle, color = PfColors.navy)
            Text(
                "Pick the root cause and cite the evidence that supports it. A diagnosis without cited evidence is not accepted.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            if (investigation.difficulty == InvestigationDifficulty.ROOKIE) {
                Text(
                    "Cite the items you opened whose content matches the cause you picked. A strong citation is one that proves the cause, not one that only mentions it.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Root cause")
            if (investigation.hypotheses.isEmpty()) {
                Text(
                    "No hypotheses were provided for this case.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            } else {
                investigation.hypotheses.forEach { hypothesis ->
                    InvestigationDiagnosisRow(
                        hypothesis = hypothesis,
                        isPicked = diagnosisHypothesisId == hypothesis.id,
                        onPick = { onDiagnosisSelected(hypothesis.id) }
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Supporting evidence")
            if (openedEvidence.isEmpty()) {
                Text(
                    "You have not opened any evidence yet. Open evidence first, then return here to cite it.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                PfSecondaryButton(title = "Go to the evidence workspace", onClick = onGoToEvidence)
            } else {
                openedEvidence.forEach { item ->
                    InvestigationCitationRow(
                        item = item,
                        isCited = item.id in citedEvidenceIds,
                        onToggle = {
                            onCitedChanged(
                                if (item.id in citedEvidenceIds) citedEvidenceIds - item.id
                                else citedEvidenceIds + item.id
                            )
                        }
                    )
                }
            }
        }
        if (guidanceText != null) {
            Text(guidanceText, style = PfTextStyles.captionBold, color = PfColors.secondaryText)
        }
        if (showCitationGateError && citedEvidenceIds.isEmpty()) {
            Text(
                "Choose one piece of evidence you opened to support this diagnosis, then submit again.",
                style = PfTextStyles.body,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.error,
                modifier = Modifier.testTag("investigationCitationGate")
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            Box(modifier = Modifier.weight(1f)) {
                PfSecondaryButton(title = "Back", onClick = onBack)
            }
            Box(modifier = Modifier.weight(1f)) {
                PfGoldButton(title = "Confirm diagnosis", onClick = ::confirmTapped)
            }
        }
    }
}

@Composable
private fun InvestigationDiagnosisRow(
    hypothesis: InvestigationHypothesis,
    isPicked: Boolean,
    onPick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isPicked) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
            .border(
                width = if (isPicked) 2.dp else 1.dp,
                color = if (isPicked) PfColors.royal else PfColors.border,
                shape = RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(onClick = onPick)
            .semantics { contentDescription = "Diagnosis: ${hypothesis.text}" }
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                InvestigationRadioCircle(isSelected = isPicked)
            }
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Text(hypothesis.text, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InvestigationCitationRow(
    item: InvestigationEvidence,
    isCited: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(if (isCited) PfColors.royal.copy(alpha = 0.06f) else PfColors.card)
            .border(
                1.dp,
                if (isCited) PfColors.royal else PfColors.border,
                RoundedCornerShape(PfSpacing.cornerRadius)
            )
            .clickable(onClick = onToggle)
            .semantics { contentDescription = "Cite evidence: ${item.title}" }
            .padding(PfSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InvestigationCheckSquare(isChecked = isCited)
            Spacer(modifier = Modifier.width(PfSpacing.sm))
            Column(
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                modifier = Modifier.weight(1f)
            ) {
                Text(item.title, style = PfTextStyles.body, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
                Text(
                    InvestigationEvidencePresentation.singularLabel(item.kind),
                    style = PfTextStyles.caption,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}

// MARK: - Report

@Composable
private fun InvestigationReportView(
    investigation: InvestigationCase,
    report: InvestigationReport,
    incorrectAssumptions: List<String>,
    citedEvidence: List<InvestigationEvidence>,
    onDone: () -> Unit
) {
    val completedLabel = remember(report.completedAtMillis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(report.completedAtMillis))
    }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Text("Investigation report", style = PfTextStyles.sectionTitle, color = PfColors.navy)
            Text(
                "Difficulty: ${investigation.difficulty.displayName}. Completed $completedLabel.",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
        }

        // Incident summary.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Incident summary")
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                        PfPill(
                            text = "Case ${investigation.situationId}",
                            background = PfColors.navy.copy(alpha = 0.08f),
                            foreground = PfColors.navy
                        )
                        PfPill(text = investigation.incidentType)
                        val severityColors = investigationSeverityColors(investigation.severity)
                        PfPill(
                            text = "Severity: ${investigation.severity}",
                            background = severityColors.first,
                            foreground = severityColors.second
                        )
                    }
                    Text(investigation.title, style = PfTextStyles.cardTitle, color = PfColors.navy)
                    Text(investigation.incidentReport, style = PfTextStyles.body, color = PfColors.navy)
                    Text("Business context", style = PfTextStyles.captionBold, color = PfColors.navy)
                    Text(investigation.businessContext, style = PfTextStyles.body, color = PfColors.secondaryText)
                }
            }
        }

        InvestigationReportTextSection(title = "Correct root cause", text = investigation.correctDiagnosis)

        // Evidence supporting the diagnosis.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Evidence supporting the diagnosis")
            PfCard {
                if (citedEvidence.isEmpty()) {
                    Text("No evidence was cited.", style = PfTextStyles.body, color = PfColors.secondaryText)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                        citedEvidence.forEach { item ->
                            InvestigationCitedEvidenceRow(item = item, investigation = investigation)
                        }
                    }
                }
            }
        }

        InvestigationReportListSection(
            title = "Incorrect assumptions made",
            items = incorrectAssumptions,
            emptyText = "No incorrect assumptions were recorded."
        )

        InvestigationReportTextSection(title = "Corrective action", text = investigation.correctiveAction)
        InvestigationReportTextSection(title = "Customer and operational impact", text = investigation.customerImpact)

        InvestigationReportListSection(
            title = "Preventive controls",
            items = investigation.preventiveControls,
            emptyText = "No preventive controls were recorded for this case."
        )
        InvestigationReportListSection(
            title = "Technical and architectural lessons",
            items = investigation.lessons,
            emptyText = "No lessons were recorded for this case."
        )

        // The closing question from the case, rendered as a reflection
        // prompt. Shown only when the case carries one.
        val reflectionPrompt = investigation.reflectionPrompt
        if (!reflectionPrompt.isNullOrEmpty()) {
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    Text("Reflection", style = PfTextStyles.cardTitle, color = PfColors.navy)
                    Text(reflectionPrompt, style = PfTextStyles.body, color = PfColors.secondaryText)
                }
            }
        }

        // Overall investigation score.
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            PfSectionHeader(title = "Overall investigation score")
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Overall", style = PfTextStyles.cardTitle, color = PfColors.navy, modifier = Modifier.weight(1f))
                        Text(
                            "${report.dimensionScores.overall} out of 100",
                            style = PfTextStyles.sectionTitle,
                            color = PfColors.navy
                        )
                    }
                    PfProgressBar(progress = report.dimensionScores.overall / 100f)
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PfColors.border))
                    InvestigationDimensionRow(label = "Evidence interpretation", value = report.dimensionScores.evidenceInterpretation)
                    InvestigationDimensionRow(label = "Efficiency", value = report.dimensionScores.efficiency)
                    InvestigationDimensionRow(label = "Technical accuracy", value = report.dimensionScores.technicalAccuracy)
                    InvestigationDimensionRow(label = "Root cause identification", value = report.dimensionScores.rootCauseIdentification)
                    InvestigationDimensionRow(label = "Resolution quality", value = report.dimensionScores.resolutionQuality)
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PfColors.border))
                    Text(
                        "Decisions correct: ${report.decisionsCorrect} of ${report.decisionsTotal}. " +
                            "Evidence opened: ${report.evidenceOpenedCount}. Wrong picks: ${report.wrongPicks}.",
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }

        PfPrimaryButton(title = "Done", onClick = onDone)
        Text(
            "Your investigation results stay on this device.",
            style = PfTextStyles.caption,
            color = PfColors.secondaryText
        )
    }
}

@Composable
private fun InvestigationReportTextSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        PfSectionHeader(title = title)
        PfCard {
            Text(text, style = PfTextStyles.body, color = PfColors.navy)
        }
    }
}

@Composable
private fun InvestigationReportListSection(title: String, items: List<String>, emptyText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        PfSectionHeader(title = title)
        PfCard {
            if (items.isEmpty()) {
                Text(emptyText, style = PfTextStyles.body, color = PfColors.secondaryText)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•", style = PfTextStyles.body, color = PfColors.royal)
                            Spacer(modifier = Modifier.width(PfSpacing.sm))
                            Text(item, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvestigationCitedEvidenceRow(item: InvestigationEvidence, investigation: InvestigationCase) {
    val supports = item.id in investigation.supportingEvidenceIds
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
        Text(item.title, style = PfTextStyles.body, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            PfPill(text = InvestigationEvidencePresentation.singularLabel(item.kind))
            if (supports) {
                PfPill(
                    text = "Supports the diagnosis",
                    background = PfColors.success.copy(alpha = 0.12f),
                    foreground = PfColors.success
                )
            } else {
                PfPill(
                    text = "Does not support the diagnosis",
                    background = PfColors.navy.copy(alpha = 0.08f),
                    foreground = PfColors.navy
                )
            }
        }
    }
}

@Composable
private fun InvestigationDimensionRow(label: String, value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
            Text("$value", style = PfTextStyles.body, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
        }
        PfProgressBar(progress = value / 100f)
    }
}

// MARK: - Evidence renderers

/** Timeline evidence: the step position, one narrative sentence
 * derived from the case text, and the step fields (sequence,
 * amount, parties) as a field table. The narrative sentence never
 * repeats the step title, and the fields never render as numbered
 * step bullets. */
@Composable
private fun InvestigationTimelineView(evidence: InvestigationEvidence, positionText: String?) {
    /** The body renders only when it adds to the title. The engine
     * guarantees this; the guard keeps a title repeat out even if
     * an authored item ever carries one. */
    val showsBody = evidence.body.isNotEmpty() && evidence.body != evidence.title
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            if (positionText != null) {
                Text(positionText, style = PfTextStyles.captionBold, color = PfColors.royal)
            }
            if (showsBody) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                InvestigationKeyValueTable(lines = evidence.structuredLines)
            }
        }
    }
}

/** Message evidence: the message text, a field table from the
 * structured lines, and a link into the message explorer when the
 * title names a message code. */
@Composable
private fun InvestigationMessageEvidenceView(
    evidence: InvestigationEvidence,
    onOpenMessageExplorer: (String) -> Unit
) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (evidence.body.isNotEmpty()) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                InvestigationKeyValueTable(lines = evidence.structuredLines)
            }
            val code = investigationMessageCode(evidence.title)
            if (code != null) {
                PfRowButton(
                    title = "Open $code in the message explorer",
                    onClick = { onOpenMessageExplorer(code) },
                    modifier = Modifier.semantics { contentDescription = "Open $code in the message explorer" }
                )
            }
        }
    }
}

/** Log evidence: monospace lines on a soft navy block. */
@Composable
private fun InvestigationLogEvidenceView(evidence: InvestigationEvidence) {
    val logLines = if (evidence.structuredLines.isNotEmpty()) {
        evidence.structuredLines
    } else {
        evidence.body.split("\n").filter { it.isNotEmpty() }
    }
    val showsBodySeparately = evidence.structuredLines.isNotEmpty() &&
        evidence.body.isNotEmpty() &&
        evidence.body != evidence.structuredLines.joinToString("\n")
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (showsBodySeparately) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PfSpacing.cornerRadius))
                    .background(PfColors.navySoft)
                    .padding(PfSpacing.md)
            ) {
                logLines.forEach { line ->
                    Text(
                        line,
                        style = InvestigationMonoStyle,
                        color = PfColors.navy
                    )
                }
            }
        }
    }
}

/** Ledger evidence: the ledger text plus debit and credit postings
 * from the structured lines, each labelled with its side. */
@Composable
private fun InvestigationLedgerEvidenceView(evidence: InvestigationEvidence) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (evidence.body.isNotEmpty()) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
                    evidence.structuredLines.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(line, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                            val side = investigationLedgerSide(line)
                            if (side != null) {
                                PfPill(
                                    text = side,
                                    background = PfColors.royal.copy(alpha = 0.10f),
                                    foreground = PfColors.royal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reconciliation evidence: the break text plus a break table from
 * the structured lines. */
@Composable
private fun InvestigationReconEvidenceView(evidence: InvestigationEvidence) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (evidence.body.isNotEmpty()) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                InvestigationKeyValueTable(lines = evidence.structuredLines)
            }
        }
    }
}

/** API evidence: request and response blocks on soft navy. */
@Composable
private fun InvestigationApiEvidenceView(evidence: InvestigationEvidence) {
    val sections = investigationApiSections(evidence.structuredLines)
    val showsBodySeparately = evidence.structuredLines.isNotEmpty() &&
        evidence.body.isNotEmpty() &&
        evidence.body != evidence.structuredLines.joinToString("\n")
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (showsBodySeparately) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isEmpty()) {
                InvestigationApiBlock(
                    title = "Request and response",
                    lines = evidence.body.split("\n").filter { it.isNotEmpty() }
                )
            } else {
                if (sections.first.isNotEmpty()) {
                    InvestigationApiBlock(title = "Request", lines = sections.first)
                }
                if (sections.second.isNotEmpty()) {
                    InvestigationApiBlock(title = "Response", lines = sections.second)
                }
            }
        }
    }
}

@Composable
private fun InvestigationApiBlock(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
        Text(title, style = PfTextStyles.captionBold, color = PfColors.navy)
        Column(
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PfSpacing.cornerRadius))
                .background(PfColors.navySoft)
                .padding(PfSpacing.md)
        ) {
            lines.forEach { line ->
                Text(
                    line,
                    style = InvestigationMonoStyle,
                    color = PfColors.navy
                )
            }
        }
    }
}

/** Clearing evidence: the status text plus a status record table
 * from the structured lines. */
@Composable
private fun InvestigationClearingEvidenceView(evidence: InvestigationEvidence) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
            if (evidence.body.isNotEmpty()) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                InvestigationKeyValueTable(lines = evidence.structuredLines)
            }
        }
    }
}

/** Note evidence: a prose card, with any structured lines as bullets. */
@Composable
private fun InvestigationNoteEvidenceView(evidence: InvestigationEvidence) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            if (evidence.body.isNotEmpty()) {
                Text(evidence.body, style = PfTextStyles.body, color = PfColors.navy)
            }
            if (evidence.structuredLines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                    evidence.structuredLines.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•", style = PfTextStyles.body, color = PfColors.royal)
                            Spacer(modifier = Modifier.width(PfSpacing.sm))
                            Text(line, style = PfTextStyles.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Shared evidence table

/** A field table from structured lines: each "Field: value" line
 * renders as a labelled row, and lines without a field name render
 * as plain lines. Shared by the message, reconciliation and
 * clearing renderers. */
@Composable
private fun InvestigationKeyValueTable(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        investigationFieldPairs(lines).forEach { pair ->
            if (pair.first.isEmpty()) {
                Text(pair.second, style = PfTextStyles.body, color = PfColors.navy)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                    Text(pair.first, style = PfTextStyles.captionBold, color = PfColors.secondaryText)
                    Text(pair.second, style = PfTextStyles.body, color = PfColors.navy)
                }
            }
        }
    }
}
