// InvestigationHost.kt
// Navigation hosts for the investigation experience: they load the
// case source (a situation, a weekly case, or an authored lab case),
// build the initial InvestigationCase at Professional difficulty
// through the engine, and hand the rebuild callback to the
// experience screen so a difficulty picked before starting rebuilds
// through the engine for that difficulty. This mirrors the iOS
// entry points: SituationDetailView.investigationEntry,
// ExceptionInvestigationLabView case rows, and the weekly-case
// detail host, all of which build at Professional and rebuild on
// difficulty change.
//
// No new top-level module: these are detail destinations reached
// only from the existing entry points (Situation detail, Lab list,
// Weekly case). No deep link is added. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.features.investigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.FullSituationItem
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.features.lab.ExceptionCaseCatalogue
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfEmptyStateView
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfSpacing

/** The investigation experience for one situation, reached from
 * the Situation detail. [isWeeklyFreeCase] marks the weekly path,
 * the D101 exemption for this path only: the gating rule itself
 * lives in the experience screen. The full item comes from the
 * loaded full situations data when available, else null, following
 * the iOS allSituations pattern; when it is null the engine falls
 * back to the regex and template rules. */
@Composable
fun InvestigationSituationRoute(
    situationId: String,
    isWeeklyFreeCase: Boolean,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var loaded by remember { mutableStateOf<LoadedSituation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(situationId, isWeeklyFreeCase, retryTick) {
        loading = true
        loadError = false
        loaded = null
        try {
            loaded = loadSituationForInvestigation(situationId, isWeeklyFreeCase)
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    when {
        loading -> InvestigationHostLoading(onBack = onBack)
        loadError -> InvestigationHostError(
            title = "Could not load this case",
            message = "Check your connection and try again.",
            onBack = onBack,
            onRetry = { retryTick++ }
        )
        loaded == null -> InvestigationHostNotFound(onBack = onBack)
        else -> {
            val item = loaded!!
            InvestigationExperienceScreen(
                investigation = InvestigationEngine.caseFor(
                    situation = item.situation,
                    fullItem = item.fullItem,
                    difficulty = InvestigationDifficulty.PROFESSIONAL
                ),
                isFreeSample = item.situation.isFreeSample,
                isWeeklyFreeCase = isWeeklyFreeCase,
                onDifficultyChange = { difficulty ->
                    InvestigationEngine.caseFor(
                        situation = item.situation,
                        fullItem = item.fullItem,
                        difficulty = difficulty
                    )
                },
                onBack = onBack,
                onNavigate = onNavigate
            )
        }
    }
}

/** The investigation experience for this week's free case, reached
 * from the Weekly case screen. The dedicated weekly-case list wins
 * when it has loaded; otherwise the pick falls back to the
 * catalogue order, exactly as on iOS, so the weekly case never
 * goes empty. The weekly path is always the free exemption. */
@Composable
fun InvestigationWeeklyRoute(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var loaded by remember { mutableStateOf<LoadedSituation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(retryTick) {
        loading = true
        loadError = false
        loaded = null
        try {
            loaded = loadWeeklySituationForInvestigation()
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    when {
        loading -> InvestigationHostLoading(onBack = onBack)
        loadError -> InvestigationHostError(
            title = "Could not load this week's case",
            message = "Check your connection and try again.",
            onBack = onBack,
            onRetry = { retryTick++ }
        )
        loaded == null -> InvestigationHostNotFound(onBack = onBack)
        else -> {
            val item = loaded!!
            InvestigationExperienceScreen(
                investigation = InvestigationEngine.caseFor(
                    situation = item.situation,
                    fullItem = item.fullItem,
                    difficulty = InvestigationDifficulty.PROFESSIONAL
                ),
                isFreeSample = item.situation.isFreeSample,
                isWeeklyFreeCase = true,
                onDifficultyChange = { difficulty ->
                    InvestigationEngine.caseFor(
                        situation = item.situation,
                        fullItem = item.fullItem,
                        difficulty = difficulty
                    )
                },
                onBack = onBack,
                onNavigate = onNavigate
            )
        }
    }
}

/** The investigation experience for one authored lab case, reached
 * from the Lab list. The case is built through the lab-case
 * adapter at Professional difficulty; the rebuild callback
 * rebuilds through the adapter for the picked difficulty. A lab
 * case that needs no unlock passes isFreeSample true, matching
 * the iOS row wiring (isFreeSample: !requiresSituationsUnlock). */
@Composable
fun InvestigationLabCaseRoute(
    labCaseId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val labCase = remember(labCaseId) { ExceptionCaseCatalogue.caseById(labCaseId) }
    if (labCase == null) {
        InvestigationHostNotFound(onBack = onBack)
        return
    }
    InvestigationExperienceScreen(
        investigation = InvestigationEngine.caseForLabCase(
            labCase,
            InvestigationDifficulty.PROFESSIONAL
        ),
        isFreeSample = !labCase.requiresSituationsUnlock,
        isWeeklyFreeCase = false,
        onDifficultyChange = { difficulty ->
            InvestigationEngine.caseForLabCase(labCase, difficulty)
        },
        onBack = onBack,
        onNavigate = onNavigate
    )
}

// MARK: - Loading

private data class LoadedSituation(
    val situation: Situation,
    val fullItem: FullSituationItem?
)

/** Loads one situation plus its full data-file item. On the weekly
 * path the weekly list is tried first (a weekly item may not be in
 * the catalogue); the catalogue is the fallback in both paths.
 * The full item is null when the raw data could not be loaded, in
 * which case the engine falls back to regex and template rules.
 * Throws only when neither list can be loaded at all. */
private suspend fun loadSituationForInvestigation(
    situationId: String,
    isWeeklyFreeCase: Boolean
): LoadedSituation? {
    if (isWeeklyFreeCase) {
        val weekly = runCatching { WeeklyCaseLoader.weeklyItems() }.getOrNull()
        val weeklyMatch = weekly?.firstOrNull { it.id == situationId }
        if (weeklyMatch != null) {
            return LoadedSituation(
                situation = ContentRepository.situationFromFull(weeklyMatch),
                fullItem = weeklyMatch
            )
        }
    }
    val situations = ContentRepository.situations()
    val situation = situations.firstOrNull { it.id == situationId } ?: return null
    val fullItem = runCatching { ContentRepository.fullSituationItems() }.getOrNull()
        ?.firstOrNull { it.id == situationId }
    return LoadedSituation(situation = situation, fullItem = fullItem)
}

/** Loads this week's situation plus its full data-file item. The
 * dedicated weekly list wins; the catalogue pick is the fallback. */
private suspend fun loadWeeklySituationForInvestigation(): LoadedSituation? {
    val weekly = runCatching { WeeklyCaseLoader.weeklyItems() }.getOrNull()
    if (!weekly.isNullOrEmpty()) {
        val picked = WeeklyCaseSelector.pickThisWeek(weekly)
        if (picked != null) {
            return LoadedSituation(
                situation = ContentRepository.situationFromFull(picked),
                fullItem = picked
            )
        }
    }
    val situations = ContentRepository.situations()
    if (situations.isEmpty()) return null
    val pickedId = WeeklyCaseSelector.situationId(situations.map { it.id }) ?: return null
    val situation = situations.firstOrNull { it.id == pickedId } ?: return null
    val fullItem = runCatching { ContentRepository.fullSituationItems() }.getOrNull()
        ?.firstOrNull { it.id == pickedId }
    return LoadedSituation(situation = situation, fullItem = fullItem)
}

// MARK: - Host states

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvestigationHostLoading(onBack: () -> Unit) {
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
            modifier = Modifier.padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
        ) {
            PfLoadingView(message = "Loading the case...")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvestigationHostError(
    title: String,
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
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
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            PfErrorStateView(
                title = title,
                message = message,
                retryTitle = "Retry",
                onRetry = onRetry
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvestigationHostNotFound(onBack: () -> Unit) {
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
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            PfEmptyStateView(
                icon = Icons.Filled.Search,
                title = "This case was not found",
                message = "It may have been removed from the catalogue. Go back and pick another case.",
                actionTitle = "Back",
                onAction = onBack
            )
        }
    }
}
