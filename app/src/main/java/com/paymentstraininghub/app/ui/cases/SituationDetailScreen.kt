// SituationDetailScreen.kt
// One Payment Situation. D101 HARD GATE (iOS v69, SituationDetailView):
// a locked situation shows ONLY the teaser header (case id, title,
// stage and level pills, read time, Locked pill) plus the paywall
// gate card. No explain, takeaway, visual or practice entry renders
// while locked, and there is no excerpt text: the gate carries
// generic copy only. A situation is locked when it is not a free
// sample, not this week's free case on the weekly path, and Payment
// Situations is not unlocked. The weekly exemption is path-scoped:
// only the weekly detail host passes isWeeklyFreeCase, so the same
// situation opened from the list or search stays gated. When the
// situation is open through the weekly path but not owned, an upsell
// card offers the full set. Tabs (Overview, Analysis, Flow) and the
// visual renderers are ported from iOS SituationsView; the explain
// parser lives in data/SituationExplainParser.kt. No bookmark: a
// pre-existing gap, see GATING-NOTES.md. No practice-mode entry:
// iOS PracticeModeView is a separate 1,195-line exercise screen
// with no Android equivalent yet.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.cases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ParsedSituationExplain
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.SituationGating
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.model.SituationVisual
import com.paymentstraininghub.app.data.parseSituationExplain
import com.paymentstraininghub.app.data.schemeForSituation
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorState
import com.paymentstraininghub.app.ui.theme.PfLoading
import com.paymentstraininghub.app.ui.theme.PfLockedGateCard
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.AgendaBlock
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.PfRowButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SituationDetailScreen(
    situationId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    isWeeklyFreeCase: Boolean = false
) {
    // Collected so the gate refreshes when entitlements load or change.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    val billingProducts by BillingManager.products.collectAsState()
    val situationsUnlocked = remember(ownedIds, serverIds, gateState) {
        BillingManager.isSituationsUnlocked()
    }

    var situation by remember { mutableStateOf<Situation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    val agendas by AgendaStore.agendas.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(situationId, retryTick) {
        loading = true
        loadError = false
        try {
            // Detail only: the chunk holding this case, never the
            // whole list. The full file is the fallback inside.
            situation = ContentRepository.situationDetail(situationId)
            AgendaStore.refreshIfNeeded(context.filesDir)
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    val practiceProductId = ProductCatalogue.practiceProductId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
    ) {
        TopAppBar(
            title = { Text("Payment Situation", style = PfTypography.screenTitle, color = PfColors.navy) },
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
            loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PfLoading(message = "Loading the situation...")
                }
            }
            loadError -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.Center
                ) {
                    PfErrorState(
                        title = "Could not load this situation",
                        message = "Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            situation == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "This situation was not found",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    Text(
                        "It may have been removed from the catalogue. Go back and pick another case.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    PfPrimaryButton(title = "Back to Payment Cases", onClick = onBack)
                }
            }
            else -> {
                val item = situation!!
                val isLocked = SituationGating.isDetailLocked(
                    isFreeSample = item.isFreeSample,
                    isWeeklyFreeCase = isWeeklyFreeCase,
                    isSituationsUnlocked = situationsUnlocked
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberTopScrollState())
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    // Teaser header: always shown, locked or unlocked.
                    // It carries no explain or takeaway text. Order
                    // and pills match iOS SituationDetailView: pills
                    // first, then the case id, then the title.
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall),
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
                        ) {
                            PfPill(text = item.stageName)
                            PfPill(
                                text = item.levelDisplayName,
                                background = PfColors.goldSoft,
                                foreground = PfColors.goldText
                            )
                            schemeForSituation(item)?.let { scheme ->
                                PfPill(
                                    text = scheme,
                                    background = PfColors.royal.copy(alpha = 0.10f),
                                    foreground = PfColors.royal
                                )
                            }
                            if (item.isFreeSample) {
                                PfPill(
                                    text = "Free sample",
                                    background = PfColors.success.copy(alpha = 0.12f),
                                    foreground = PfColors.success
                                )
                            }
                            if (isLocked) {
                                PfPill(
                                    text = "Locked",
                                    background = PfColors.goldSoft,
                                    foreground = PfColors.goldText
                                )
                            }
                        }
                        Text(
                            "Case ${item.id}",
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText
                        )
                        // Thumbnail beside the title, and the
                        // highlighted agenda block: both are public
                        // catalogue copy, like the title, so they
                        // render in the teaser header locked or
                        // unlocked. No explain or takeaway text.
                        val caseAgenda = agendas.case(item.id)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (caseAgenda?.thumbnail != null) {
                                ThumbnailTile(
                                    thumbnailPath = caseAgenda.thumbnail,
                                    modifier = Modifier.size(width = 96.dp, height = 54.dp)
                                )
                            }
                            Text(
                                item.title,
                                style = PfTypography.sectionTitle,
                                color = PfColors.navy,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        val agendaText = caseAgenda?.agenda
                        if (!agendaText.isNullOrBlank()) {
                            AgendaBlock(agenda = agendaText, label = "Why work this case")
                        }
                        // No read-time label: case texts are uniformly
                        // short, so a whole-minute label collapses to
                        // one value on every case. See SituationsScreen.
                    }

                    if (isLocked) {
                        // The hard gate: generic copy only. Nothing
                        // below this branch renders case content.
                        val priceText = billingProducts[practiceProductId]?.let {
                            BillingManager.priceText(practiceProductId)
                        }
                        val priceClause = if (priceText != null && priceText != "Price unavailable") {
                            " for $priceText"
                        } else {
                            ""
                        }
                        PfLockedGateCard(
                            title = "Unlock this case",
                            bodyText = "This situation is part of Practice Experiences. Unlock all ${ContentManifest.situationCount} situations$priceClause. The 2 free samples stay free. The Integrated Course includes everything too.",
                            checkoutTitle = "Go to checkout",
                            onCheckout = { onNavigate(Routes.checkout(practiceProductId)) },
                            gateTestTag = "situationLockedGate"
                        )
                    } else {
                        // Unlocked: the three iOS tabs, then the
                        // investigation entry, then the upsell card,
                        // in the same order as SituationDetailView.
                        val parsed = remember(item.explain) { parseSituationExplain(item.explain) }
                        var selectedTab by remember { mutableIntStateOf(0) }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf("Overview", "Analysis", "Flow").forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    shape = RoundedCornerShape(8.dp),
                                    label = { Text(label) }
                                )
                            }
                        }
                        when (selectedTab) {
                            1 -> SituationAnalysisTab(parsed = parsed, explain = item.explain)
                            2 -> SituationFlowTab(visual = item.visual, parsed = parsed)
                            else -> SituationOverviewTab(
                                parsed = parsed,
                                takeaway = item.takeaway,
                                explain = item.explain
                            )
                        }

                        // Investigation entry (investigation port):
                        // unlocked cases open the unified
                        // investigation journey for this case.
                        PfRowButton(
                            title = "Investigate this case",
                            onClick = {
                                if (isWeeklyFreeCase) {
                                    onNavigate(Routes.investigationSituationWeekly(item.id))
                                } else {
                                    onNavigate(Routes.investigationSituation(item.id))
                                }
                            }
                        )

                        // Practice entry (G17): opens Practice Mode
                        // on this case when it is practice-eligible;
                        // PracticeScreen falls back to the first
                        // eligible case and owns the gate.
                        PfRowButton(
                            title = "Practise this case",
                            onClick = { onNavigate(Routes.practiceSituation(item.id)) }
                        )

                        // Upsell only when the learner does not own
                        // Situations and this is not a free sample:
                        // owners and free samples get no upsell.
                        if (!item.isFreeSample && !situationsUnlocked) {
                            val priceText = billingProducts[practiceProductId]?.let {
                                BillingManager.priceText(practiceProductId)
                            }
                            val priceClause = if (priceText != null && priceText != "Price unavailable") {
                                " for $priceText"
                            } else {
                                ""
                            }
                            PfCard {
                                Text(
                                    "This situation is part of Practice Experiences. Unlock all ${ContentManifest.situationCount} situations$priceClause. The Integrated Course includes everything too.",
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                                PfPrimaryButton(
                                    title = "Go to checkout",
                                    onClick = { onNavigate(Routes.checkout(practiceProductId)) }
                                )
                            }
                        }
                    }
                    PfBottomInset()
                }
            }
        }
    }
}

/**
 * Overview tab: Business Problem (the opening paragraphs), Scenario
 * (the "what happened" steps, numbered), and Key Learning Outcomes
 * (the takeaway, as written in the data). When the explain does not
 * parse, the full case text renders instead, so nothing is lost.
 */
@Composable
private fun SituationOverviewTab(
    parsed: ParsedSituationExplain,
    takeaway: String,
    explain: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
        if (parsed.isParsed) {
            SituationTabSection(title = "Business Problem") {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    parsed.opening.forEach { paragraph ->
                        PfBodyText(
                            text = paragraph,
                            style = PfTypography.body,
                            color = PfColors.navy
                        )
                    }
                }
            }
            if (parsed.flowSteps.isNotEmpty()) {
                SituationTabSection(title = "Scenario") {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        parsed.flowSteps.forEachIndexed { index, step ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(PfColors.royal),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = PfTypography.captionBold,
                                        color = androidx.compose.ui.graphics.Color.White
                                    )
                                }
                                PfBodyText(
                                    text = step,
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                            }
                        }
                    }
                }
            }
            SituationTabSection(title = "Key Learning Outcomes") {
                PfBodyText(
                    text = takeaway,
                    style = PfTypography.body,
                    color = PfColors.navy
                )
            }
        } else {
            FullExplainSection(explain)
            SituationTabSection(title = "Key Learning Outcomes") {
                PfBodyText(
                    text = takeaway,
                    style = PfTypography.body,
                    color = PfColors.navy
                )
            }
        }
    }
}

/**
 * Analysis tab: the flawed requirement, the root cause, and the
 * insight sentence. Each section renders only when it has content;
 * when all three are empty, the full case text renders instead.
 */
@Composable
private fun SituationAnalysisTab(parsed: ParsedSituationExplain, explain: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
        if (parsed.isParsed) {
            if (parsed.flawedRequirement.isNotEmpty()) {
                SituationTabSection(title = "The flawed requirement") {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        parsed.flawedRequirement.forEach { paragraph ->
                            PfBodyText(
                                text = paragraph,
                                style = PfTypography.body,
                                color = PfColors.navy
                            )
                        }
                    }
                }
            }
            if (parsed.rootCause.isNotEmpty()) {
                SituationTabSection(title = "Root cause") {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        parsed.rootCause.forEach { paragraph ->
                            PfBodyText(
                                text = paragraph,
                                style = PfTypography.body,
                                color = PfColors.navy
                            )
                        }
                    }
                }
            }
            if (parsed.insight != null) {
                SituationTabSection(title = "Insight") {
                    PfBodyText(
                        text = parsed.insight,
                        style = PfTypography.body,
                        color = PfColors.navy
                    )
                }
            }
            if (parsed.flawedRequirement.isEmpty() && parsed.rootCause.isEmpty() && parsed.insight == null) {
                FullExplainSection(explain)
            }
        } else {
            FullExplainSection(explain)
        }
    }
}

/**
 * Flow tab: the visual (flow, diagram or stacked), rendered natively.
 * When the situation has no visual, the "what happened" steps from
 * the explain text render as a native numbered flow. When there are
 * neither, the tab says so instead of showing an empty screen.
 */
@Composable
private fun SituationFlowTab(visual: SituationVisual?, parsed: ParsedSituationExplain) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
        when {
            visual != null -> SituationVisualSection(visual)
            parsed.flowSteps.isNotEmpty() -> FlowStepsAsFlow(parsed.flowSteps)
            else -> Text(
                "No visual is available for this situation.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }
    }
}
