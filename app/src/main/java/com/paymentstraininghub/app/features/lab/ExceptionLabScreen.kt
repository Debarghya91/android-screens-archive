// Exception investigation lab. Cases are ordered by level, foundation
// first, then core, then advanced. Gating mirrors iOS: non-free cases are
// hidden until Practice Experiences is unlocked; free-sample and practice
// cases are open to all. Case rows open the unified investigation
// experience; the old lab player file is kept but no longer reachable
// from this list, exactly as on iOS. No em dashes.
package com.paymentstraininghub.app.features.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.features.investigation.InvestigationStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@Composable
fun ExceptionLabScreen(onNavigate: (String) -> Unit) {
    // BillingManager.isInvestigationLabUnlocked() is the
    // Investigation Lab gate: the Investigation Lab, or the
    // Integrated Course, unlocks it. The list and the
    // Investigation Experience gate on the same entitlement.
    var labUnlocked by remember { mutableStateOf(false) }
    var gatingChecked by remember { mutableStateOf(false) }
    // Collected so the gate refreshes when entitlements load or
    // change, when the premium gate resolves, and when the
    // admin flag lands on restore. A one-shot read here used
    // to freeze the guest-time value for the screen's lifetime.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    val isAdmin by AuthManager.isAdmin.collectAsState()
    LaunchedEffect(ownedIds, serverIds, gateState, isAdmin) {
        labUnlocked = runCatching {
            BillingManager.isInvestigationLabUnlocked()
        }.getOrDefault(false)
        gatingChecked = true
    }

    val results by ExceptionLabStore.results.collectAsState()
    // Collected so investigation best scores refresh when a run
    // completes and the learner returns to this list.
    @Suppress("UNUSED_VARIABLE")
    val investigationResults by InvestigationStore.results.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState())
            .padding(PfSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
    ) {
        Text("Exception investigation lab", style = PfTextStyles.screenTitle, color = PfColors.navy)
        Text(
            "Investigate real payment incidents step by step. Scores measure investigation " +
                "quality: best-first actions earn points, wrong turns and red herrings cost points, " +
                "and opening genuine evidence earns thoroughness points.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )
        Text(
            "How scoring works: ${ExceptionLabScoring.BEST_FIRST_POINTS_PER_STEP} points for a step solved on the " +
                "first pick, ${ExceptionLabScoring.RECOVERED_POINTS_PER_STEP} points for a step solved after a wrong turn, " +
                "${ExceptionLabScoring.WRONG_PICK_DEDUCTION} points off per wrong pick, " +
                "${ExceptionLabScoring.RED_HERRING_PICK_DEDUCTION} points off per red herring pick on top of that, " +
                "an efficiency bonus of ${ExceptionLabScoring.EFFICIENCY_BONUS_NO_WRONG_TURN} points with no wrong turn " +
                "or ${ExceptionLabScoring.EFFICIENCY_BONUS_ONE_WRONG_TURN} points with one, and " +
                "${ExceptionLabScoring.THOROUGHNESS_POINTS_PER_EVIDENCE} points per genuine evidence item opened.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )
        Text(
            "${results.size} of ${ExceptionCaseCatalogue.allCases.size} cases completed. " +
                "Your lab scores stay on this device.",
            style = PfTextStyles.body,
            color = PfColors.secondaryText
        )

        if (!gatingChecked) {
            PfLoadingView(message = "Checking access...")
        }

        ExceptionLevel.entries.forEach { level ->
            val levelCases = ExceptionCaseCatalogue.allCases.filter { it.level == level }
            if (levelCases.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                    PfSectionHeader(title = level.displayName)
                    Text(
                        when (level) {
                            ExceptionLevel.FOUNDATION -> "3 steps, 3 options each."
                            ExceptionLevel.CORE -> "3 steps, 4 options each."
                            ExceptionLevel.ADVANCED -> "4 steps, 4 options each."
                        },
                        style = PfTextStyles.callout,
                        color = PfColors.secondaryText
                    )
                }
                levelCases.forEach { case ->
                    val open = LabGating.isCaseOpen(case, labUnlocked)
                    val result = results.firstOrNull { it.caseID == case.id }
                    val investigationResult = InvestigationStore.resultFor(case.id)
                    LabCaseRow(
                        case = case,
                        open = open,
                        result = result,
                        investigationBestOverall = investigationResult?.bestOverall,
                        // Locked rows open the same case route:
                        // the Investigation Experience shows the
                        // paywall view for a locked case. Tapping
                        // a locked row never jumps to checkout.
                        onOpen = { onNavigate(Routes.investigationLab(case.id)) },
                        onLocked = { onNavigate(Routes.investigationLab(case.id)) }
                    )
                }
            }
        }
        PfBottomInset()
    }
}

@Composable
private fun LabCaseRow(
    case: ExceptionCase,
    open: Boolean,
    result: ExceptionLabResult?,
    investigationBestOverall: Int?,
    onOpen: () -> Unit,
    onLocked: () -> Unit
) {
    PfCard(
        // Locked rows stay tappable: the tap opens the case
        // route, where the paywall view explains the case and
        // offers the buy button. A disabled row would dead-end.
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
            Text(case.title, style = PfTextStyles.cardTitle, color = PfColors.navy)
            Text(
                "${case.incidentType}. ${case.steps.size} steps, ${case.evidence.size} evidence items.",
                style = PfTextStyles.body,
                color = PfColors.secondaryText
            )
            if (case.isPracticeExercise) {
                PfPill(text = "Practice exercise")
            } else if (case.isGroundedInFreeSample) {
                PfPill(text = "Free sample case")
            }
            if (result != null) {
                Text(
                    "Best score: ${result.bestTotal} out of ${result.bestMaximum}. " +
                        "Completed ${result.completions} time${if (result.completions == 1) "" else "s"}.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
            if (investigationBestOverall != null) {
                Text(
                    "Investigation best score: $investigationBestOverall out of 100.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
            if (!open) {
                Text(
                    "Locked. This case is grounded in a Payment Situation. Unlock the Investigation Lab to open it.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                PfSecondaryButton(
                    title = "Unlock this case",
                    onClick = onLocked
                )
            }
        }
    }
}
