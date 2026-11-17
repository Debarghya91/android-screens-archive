// PracticeScreen.kt
// Practice Mode, ported from PracticeModeView.swift: the
// first practice-eligible situation from the gated pool, worked
// through 12 numbered sections: business problem, scenario
// context, the decision, available actions, the recommended
// solution (hidden until a decision is confirmed), explanation
// of alternatives, the end-to-end process flow, happy path,
// negative paths, technical and data considerations, and the
// knowledge assessment. Every option is a real takeaway; no
// distractor ever comes from a locked situation. A situation
// with no takeaway is not eligible, never faked.
//
// One deliberate divergence: the assessment score is shown but
// the competency-profile write is not made, because Android has
// no CompetencyStore or ProgressStore (the same pre-existing
// gap behind ProgressScreen showing no quiz scores). The
// decision attempt itself is recorded in PracticeStore and
// mirrored to Supabase, matching iOS.

package com.paymentstraininghub.app.features.practice

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.PracticeModeGate
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.model.SituationFlowStep
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseSelector
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

private val OPTION_LETTERS = listOf("A", "B", "C", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    initialSituationId: String? = null
) {
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val isSituationsUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        BillingManager.isSituationsUnlocked()
    }

    var situations by remember { mutableStateOf<List<Situation>?>(null) }
    var weeklyCaseId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(retryTick) {
        loading = true
        loadFailed = false
        val loaded = runCatching { ContentRepository.situations() }
        if (loaded.isFailure) {
            // Cancellation is not a load failure: rethrow it so
            // a cancelled effect never lands in the error state.
            val failure = loaded.exceptionOrNull()
            if (failure is CancellationException) throw failure
            // A failed load is a failure, not an empty list: the
            // old runCatching-to-emptyList swallowed it and the
            // screen showed "no free case is ready" with no way
            // to retry.
            loadFailed = true
            situations = emptyList()
        } else {
            situations = loaded.getOrElse { emptyList() }
        }
        val weekly = runCatching { ContentRepository.weeklyCases() }.getOrElse { e ->
            if (e is CancellationException) throw e
            emptyList()
        }
        weeklyCaseId = WeeklyFreeCaseSelector.situationId(
            weekly.map { it.id },
            LocalDate.now()
        )
        loading = false
    }

    // The practice situation: the requested one when it is in
    // the gated eligible pool (from "Practise this case"), else
    // the first practice-eligible situation, matching the iOS
    // sheet entry.
    val situation = remember(situations, isSituationsUnlocked, weeklyCaseId, initialSituationId) {
        val all = situations ?: return@remember null
        val pool = PracticeModeGate.eligiblePool(all, isSituationsUnlocked, weeklyCaseId)
        val eligible = pool.filter { candidate ->
            PracticeModeGate.isEligible(
                candidate, all, isSituationsUnlocked, weeklyCaseId
            )
        }
        eligible.firstOrNull { it.id == initialSituationId } ?: eligible.firstOrNull()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Practice", color = PfColors.navy) },
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
        when {
            loading || situations == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = PfColors.royal)
                    Text(
                        "The free cases will appear here once the course list has loaded.",
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText,
                        modifier = Modifier.padding(top = PfSpacing.small)
                    )
                }
            }
            situation == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (loadFailed) {
                            "The cases could not be loaded. Check your connection and try again."
                        } else {
                            "No free case is ready to practise yet."
                        },
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                    if (loadFailed) {
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfPrimaryButton(title = "Retry", onClick = { retryTick++ })
                    }
                }
            }
            else -> {
                PracticeContent(
                    situation = situation,
                    allSituations = situations ?: emptyList(),
                    isSituationsUnlocked = isSituationsUnlocked,
                    weeklyCaseId = weeklyCaseId,
                    onNavigate = onNavigate
                )
            }
        }
    }
}

@Composable
private fun PracticeContent(
    situation: Situation,
    allSituations: List<Situation>,
    isSituationsUnlocked: Boolean,
    weeklyCaseId: String?,
    onNavigate: (String) -> Unit
) {
    val decision = remember(situation, allSituations, isSituationsUnlocked, weeklyCaseId) {
        PracticeModeGate.decision(
            situation, allSituations, isSituationsUnlocked, weeklyCaseId
        )
    }
    val recallDecision = remember(situation, allSituations, isSituationsUnlocked, weeklyCaseId) {
        PracticeModeGate.decision(
            situation, allSituations, isSituationsUnlocked, weeklyCaseId,
            seedSalt = 1000u
        )
    }
    val isLocked = remember(situation, isSituationsUnlocked, weeklyCaseId) {
        PracticeModeGate.isLocked(situation, isSituationsUnlocked, weeklyCaseId)
    }
    val parsed = remember(situation) { parsePracticeExplain(situation.explain) }
    val attempts by PracticeStore.attempts.collectAsState()
    val previousAttempts = remember(attempts, situation) {
        attempts.filter { it.situationId == situation.id }
    }

    var selectedOption by remember(situation) { mutableStateOf<Int?>(null) }
    var decisionMade by remember(situation) { mutableStateOf(false) }
    var quizSelection by remember(situation) { mutableStateOf<Int?>(null) }
    var quizAnswered by remember(situation) { mutableStateOf(false) }

    val decisionIsCorrect = decision != null && selectedOption != null &&
        selectedOption == decision.correctOptionIndex
    val quizIsCorrect = recallDecision != null && quizSelection != null &&
        quizSelection == recallDecision.correctOptionIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState(situation.id))
            .padding(PfSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
    ) {
        when {
            isLocked -> {
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            "Practice Mode is locked for this case",
                            style = PfTextStyles.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                        Text(
                            "This case needs Practice Experiences. Practise a free case instead.",
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                        PfPrimaryButton(
                            title = "Practise a free case instead",
                            onClick = { onNavigate(Routes.CASES) }
                        )
                    }
                }
            }
            decision == null -> {
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            "Practice Mode is not available for this situation",
                            style = PfTextStyles.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                        Text(
                            "This situation has no takeaway to practise against, so no decision can be built from it. Read the full situation instead.",
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                        PfPrimaryButton(
                            title = "Read the full situation",
                            onClick = { onNavigate(Routes.situation(situation.id)) }
                        )
                    }
                }
            }
            else -> {
                PracticeHeader(situation = situation, previousAttempts = previousAttempts)
                BusinessProblemSection(situation = situation, parsed = parsed)
                ScenarioContextSection(parsed = parsed)
                DecisionSection(
                    decision = decision,
                    selectedOption = selectedOption,
                    decisionMade = decisionMade,
                    onSelect = { selectedOption = it },
                    onConfirm = {
                        val chosen = selectedOption ?: return@DecisionSection
                        decisionMade = true
                        PracticeStore.recordAttempt(
                            situationId = situation.id,
                            chosenOptionIndex = chosen,
                            isCorrect = chosen == decision.correctOptionIndex
                        )
                    },
                    decisionIsCorrect = decisionIsCorrect
                )
                AvailableActionsSection(decision = decision)
                SolutionSection(
                    decision = decision,
                    parsed = parsed,
                    decisionMade = decisionMade
                )
                AlternativesSection(decision = decision, selectedOption = selectedOption)
                FlowSection(situation = situation, parsed = parsed)
                HappyPathSection(parsed = parsed)
                NegativePathsSection(parsed = parsed)
                TechnicalSection(situation = situation)
                DataSection(situation = situation)
                KnowledgeAssessmentSection(
                    recallDecision = recallDecision,
                    quizSelection = quizSelection,
                    quizAnswered = quizAnswered,
                    onSelect = { quizSelection = it },
                    onCheck = { quizAnswered = true },
                    quizIsCorrect = quizIsCorrect,
                    correctTotal = (if (decisionIsCorrect) 1 else 0) +
                        (if (quizIsCorrect) 1 else 0),
                    decisionMade = decisionMade
                )
            }
        }
    }
}

@Composable
private fun PracticeHeader(
    situation: Situation,
    previousAttempts: List<PracticeAttempt>
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            PracticePill(text = situation.stageName)
            PracticePill(
                text = situation.level,
                background = PfColors.goldSoft,
                foreground = PfColors.goldText
            )
            schemeFor(situation)?.let { schemeName ->
                PracticePill(text = schemeName)
            }
        }
        Text(
            situation.title,
            style = PfTextStyles.screenTitle,
            color = PfColors.navy
        )
        Text(
            "Read the case, decide what you would do next, then check yourself against the recommended solution.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )
        if (previousAttempts.isNotEmpty()) {
            val correct = previousAttempts.count { it.isCorrect }
            PracticePill(
                text = "Your attempts on this case: ${previousAttempts.size}, $correct correct",
                background = PfColors.royal.copy(alpha = 0.10f),
                foreground = PfColors.royal
            )
        }
    }
}

@Composable
private fun PracticePill(
    text: String,
    background: Color = PfColors.navySoft,
    foreground: Color = PfColors.navy
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = PfSpacing.small, vertical = 4.dp)
    ) {
        Text(text, style = PfTextStyles.captionBold, color = foreground)
    }
}

@Composable
private fun SectionTitle(title: String, step: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(PfColors.royal),
            contentAlignment = Alignment.Center
        ) {
            Text(step, style = PfTextStyles.captionBold, color = Color.White)
        }
        Text(
            title,
            style = PfTextStyles.sectionTitle,
            fontWeight = FontWeight.Bold,
            color = PfColors.navy
        )
    }
}

@Composable
private fun NotStatedText(what: String) {
    Text(
        "Not stated in this case: $what.",
        style = PfTextStyles.body,
        color = PfColors.secondaryText
    )
}

@Composable
private fun BusinessProblemSection(situation: Situation, parsed: PracticeExplain) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Business problem", "1")
        Text(
            situation.title,
            style = PfTextStyles.body,
            fontWeight = FontWeight.SemiBold,
            color = PfColors.navy
        )
        PfBodyText(text = parsed.opening.joinToString("\n\n"))
    }
}

@Composable
private fun ScenarioContextSection(parsed: PracticeExplain) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Scenario context", "2")
        if (parsed.flowSteps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                parsed.flowSteps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            "${index + 1}",
                            style = PfTextStyles.captionBold,
                            color = PfColors.royal
                        )
                        Text(step, style = PfTextStyles.body, color = PfColors.navy)
                    }
                }
            }
        }
        PfBodyText(text = parsed.preTakeawayParagraphs.joinToString("\n\n"))
    }
}

@Composable
private fun DecisionSection(
    decision: PracticeModeGate.PracticeDecision,
    selectedOption: Int?,
    decisionMade: Boolean,
    onSelect: (Int) -> Unit,
    onConfirm: () -> Unit,
    decisionIsCorrect: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Your decision", "3")
        Text(
            decision.prompt,
            style = PfTextStyles.cardTitle,
            fontWeight = FontWeight.Bold,
            color = PfColors.navy
        )
        decision.options.forEachIndexed { index, option ->
            val isSelected = selectedOption == index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PfSpacing.smallRadius))
                    .background(
                        if (isSelected) PfColors.royal.copy(alpha = 0.10f)
                        else PfColors.card
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) PfColors.royal else PfColors.border,
                        shape = RoundedCornerShape(PfSpacing.smallRadius)
                    )
                    .clickable(enabled = !decisionMade) { onSelect(index) }
                    // Disabled-with-visible-state (Tester 2 E6):
                    // unchosen options dim once the decision is
                    // made, so the freeze is visible, not silent.
                    .alpha(if (decisionMade && !isSelected) 0.55f else 1f)
                    .padding(PfSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
            ) {
                Text(
                    "${OPTION_LETTERS[index]}.",
                    style = PfTextStyles.body,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
                Text(option, style = PfTextStyles.body, color = PfColors.navy)
            }
        }
        PfPrimaryButton(
            title = "Confirm my decision",
            onClick = onConfirm,
            enabled = selectedOption != null && !decisionMade
        )
        if (decisionMade) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
            ) {
                Icon(
                    if (decisionIsCorrect) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
                    contentDescription = null,
                    tint = if (decisionIsCorrect) PfColors.success else PfColors.error
                )
                Text(
                    if (decisionIsCorrect) {
                        "Correct. That matches the recommended solution for this case."
                    } else {
                        "Not the recommended solution for this case. See below."
                    },
                    style = PfTextStyles.cardTitle,
                    color = PfColors.navy
                )
            }
        }
    }
}

@Composable
private fun AvailableActionsSection(decision: PracticeModeGate.PracticeDecision) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Available actions", "4")
        Text(
            "These are the four actions to choose from. Pick one above to reveal the solution.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )
        PfCard {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                decision.options.forEachIndexed { index, option ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            "${OPTION_LETTERS[index]}.",
                            style = PfTextStyles.body,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.royal
                        )
                        Text(option, style = PfTextStyles.body, color = PfColors.navy)
                    }
                }
            }
        }
    }
}

@Composable
private fun SolutionSection(
    decision: PracticeModeGate.PracticeDecision,
    parsed: PracticeExplain,
    decisionMade: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Recommended solution", "5")
        if (!decisionMade) {
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    Text(
                        "Make a decision to reveal the solution",
                        style = PfTextStyles.cardTitle,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.navy
                    )
                    Text(
                        "The recommended solution stays hidden until you decide what you would do next. Choose an option above, then confirm your decision.",
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        } else {
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                    Text(
                        "One thing to remember",
                        style = PfTextStyles.cardTitle,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.navy
                    )
                    PfBodyText(text = decision.explanation)
                    PfBodyText(text = parsed.baBody.joinToString("\n\n"))
                    if (parsed.controls.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            parsed.controls.forEach { control ->
                                Text(
                                    "→ $control",
                                    style = PfTextStyles.body,
                                    color = PfColors.navy
                                )
                            }
                        }
                    }
                    if (parsed.realRequirement.isNotEmpty()) {
                        Text(
                            "The real requirement",
                            style = PfTextStyles.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                        PfBodyText(text = parsed.realRequirement.joinToString("\n\n"))
                    }
                    parsed.closingQuestion?.let { closing ->
                        Text(closing, style = PfTextStyles.body, color = PfColors.navy)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternativesSection(
    decision: PracticeModeGate.PracticeDecision,
    selectedOption: Int?
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Explanation of alternatives", "6")
        PfCard {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
                decision.options.forEachIndexed { index, option ->
                    val isCorrect = index == decision.correctOptionIndex
                    val wasChosen = selectedOption == index
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                        ) {
                            Icon(
                                if (isCorrect) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
                                contentDescription = null,
                                tint = if (isCorrect) PfColors.success else PfColors.error
                            )
                            Text(
                                OPTION_LETTERS[index] +
                                    if (wasChosen) " (your choice)" else "",
                                style = PfTextStyles.captionBold,
                                color = PfColors.navy
                            )
                        }
                        Text(option, style = PfTextStyles.body, color = PfColors.navy)
                        Text(
                            if (isCorrect) {
                                "This is the situation's own takeaway, so it is the recommended solution for this case."
                            } else {
                                "This is the takeaway of another real situation. It is not the resolution stated for this case."
                            },
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    }
                    if (index < decision.options.size - 1) {
                        Divider(color = PfColors.border)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowSection(situation: Situation, parsed: PracticeExplain) {
    var expanded by remember(situation) { mutableStateOf(false) }
    val visualFlowSteps: List<SituationFlowStep>? =
        if (situation.visual?.type == "flow") situation.visual?.flow?.steps else null
    val flowSteps: List<SituationFlowStep>? = when {
        !visualFlowSteps.isNullOrEmpty() -> visualFlowSteps
        parsed.flowSteps.isNotEmpty() -> parsed.flowSteps.map {
            SituationFlowStep(kind = "step", title = it, note = null)
        }
        else -> null
    }
    val diagram = if (situation.visual?.type == "diagram") {
        situation.visual?.diagram
    } else {
        null
    }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("End-to-end process flow", "7")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Show the flow",
                style = PfTextStyles.body,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.royal
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = PfColors.royal
            )
        }
        if (expanded) {
            when {
                flowSteps != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        flowSteps.forEachIndexed { index, step ->
                            val isDecision = (step.kind ?: "").lowercase() == "decision"
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isDecision) PfColors.gold else PfColors.royal
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = PfTextStyles.captionBold,
                                        color = if (isDecision) PfColors.navy else Color.White
                                    )
                                }
                                Column {
                                    if (isDecision) {
                                        Text(
                                            "DECISION",
                                            style = PfTextStyles.captionBold,
                                            color = PfColors.goldText
                                        )
                                    }
                                    Text(
                                        step.title,
                                        style = PfTextStyles.body,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PfColors.navy
                                    )
                                    step.note?.takeIf { it.isNotEmpty() }?.let { note ->
                                        Text(
                                            note,
                                            style = PfTextStyles.body,
                                            color = PfColors.secondaryText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                diagram != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            diagram.centre,
                            style = PfTextStyles.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                        diagram.parts.forEach { part ->
                            Column {
                                Text(
                                    part.label,
                                    style = PfTextStyles.body,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PfColors.navy
                                )
                                part.note?.takeIf { it.isNotEmpty() }?.let { note ->
                                    Text(
                                        note,
                                        style = PfTextStyles.body,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    NotStatedText("no flow is given for this case")
                }
            }
        }
    }
}

@Composable
private fun HappyPathSection(parsed: PracticeExplain) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Happy path", "8")
        if (parsed.baBody.isEmpty() && parsed.controls.isEmpty()) {
            NotStatedText("the happy path is not described")
        } else {
            Text(
                "The recommended path, as stated in this case:",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            PfBodyText(text = parsed.baBody.joinToString("\n\n"))
            if (parsed.controls.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    parsed.controls.forEach { control ->
                        Text(
                            "→ $control",
                            style = PfTextStyles.body,
                            color = PfColors.navy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NegativePathsSection(parsed: PracticeExplain) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Negative paths", "9")
        if (parsed.flawedRequirement.isEmpty() && parsed.rootCause.isEmpty()) {
            NotStatedText("the negative paths are not described")
        } else {
            if (parsed.flawedRequirement.isNotEmpty()) {
                Text(
                    "What went wrong, as stated in this case:",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                PfBodyText(text = parsed.flawedRequirement.joinToString("\n\n"))
            }
            if (parsed.rootCause.isNotEmpty()) {
                Text(
                    "Root cause, as stated in this case:",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                PfBodyText(text = parsed.rootCause.joinToString("\n\n"))
            }
        }
    }
}

@Composable
private fun TechnicalSection(situation: Situation) {
    val sentences = remember(situation) {
        sentencesMentioning(situation.explain, TECHNICAL_KEYWORDS)
    }
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Technical considerations", "10")
        if (sentences.isEmpty()) {
            NotStatedText("no message names, systems or codes are named")
        } else {
            Text(
                "Showing the first 5 mentions from this case.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                sentences.take(5).forEach { sentence ->
                    Text(sentence, style = PfTextStyles.body, color = PfColors.navy)
                }
            }
        }
    }
}

@Composable
private fun DataSection(situation: Situation) {
    val technical = remember(situation) {
        sentencesMentioning(situation.explain, TECHNICAL_KEYWORDS)
    }
    val sentences = remember(situation, technical) {
        sentencesMentioning(situation.explain, DATA_KEYWORDS)
            .filter { it !in technical }
    }
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Data considerations", "11")
        if (sentences.isEmpty()) {
            NotStatedText("no reference numbers, identifiers or data fields are named")
        } else {
            Text(
                "Showing the first 5 mentions from this case.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                sentences.take(5).forEach { sentence ->
                    Text(sentence, style = PfTextStyles.body, color = PfColors.navy)
                }
            }
        }
    }
}

@Composable
private fun KnowledgeAssessmentSection(
    recallDecision: PracticeModeGate.PracticeDecision?,
    quizSelection: Int?,
    quizAnswered: Boolean,
    onSelect: (Int) -> Unit,
    onCheck: () -> Unit,
    quizIsCorrect: Boolean,
    correctTotal: Int,
    decisionMade: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        SectionTitle("Knowledge assessment", "12")
        Text(
            "Two questions on this case: your decision above, and one recall check below.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )
        if (recallDecision != null) {
            Text(
                "Which of these is the one thing to remember from this case?",
                style = PfTextStyles.cardTitle,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy
            )
            recallDecision.options.forEachIndexed { index, option ->
                val isSelected = quizSelection == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PfSpacing.smallRadius))
                        .background(
                            if (isSelected) PfColors.royal.copy(alpha = 0.10f)
                            else PfColors.card
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) PfColors.royal else PfColors.border,
                            shape = RoundedCornerShape(PfSpacing.smallRadius)
                        )
                        .clickable(enabled = !quizAnswered) { onSelect(index) }
                        // Same visible disabled state as the
                        // decision options above (Tester 2 E6).
                        .alpha(if (quizAnswered && !isSelected) 0.55f else 1f)
                        .padding(PfSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Text(
                        "${OPTION_LETTERS[index]}.",
                        style = PfTextStyles.body,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.royal
                    )
                    Text(option, style = PfTextStyles.body, color = PfColors.navy)
                }
            }
            PfPrimaryButton(
                title = "Check my answer",
                onClick = onCheck,
                enabled = quizSelection != null && !quizAnswered && decisionMade
            )
            if (quizAnswered) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Icon(
                        if (quizIsCorrect) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
                        contentDescription = null,
                        tint = if (quizIsCorrect) PfColors.success else PfColors.error
                    )
                    Text(
                        if (quizIsCorrect) {
                            "Correct. That is the situation's own takeaway."
                        } else {
                            "Not this time. The one thing to remember is the takeaway shown in the solution above."
                        },
                        style = PfTextStyles.cardTitle,
                        color = PfColors.navy
                    )
                }
                Text(
                    "Knowledge assessment: $correctTotal of 2 correct.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}
