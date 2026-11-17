// SituationsScreen.kt
// Payment Cases tab: situations segregated into categories (all
// cases, free samples, and the six learning stages), with a
// side-drawer category navigation, search, and level and scheme
// filters. The list is paged: 20 cases per numbered page, so the
// full list is never rendered at once. The list loads the lightweight case index
// only (titles, stages, levels); the full case file is never
// downloaded for the list, and full detail loads per case, when
// a case is opened. Rows are teaser-only (title and pills): no excerpt,
// explain or takeaway text ever renders in the list. Locked rows
// carry a Locked pill; the detail hard-gates locked situations
// (D101, SituationDetailView in iOS v69). The free samples
// section mirrors the iOS list. No scheme pills: Android has no
// scheme derivation for situations (gap, see GATING-NOTES.md).
// The category drawer is a second, inner drawer: gestures stay
// disabled on it so it never fights the app drawer, and it opens
// only from the Categories button. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.ui.cases

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.PAGE_SIZE
import com.paymentstraininghub.app.data.SituationGating
import com.paymentstraininghub.app.data.availableSchemes
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.pageItems
import com.paymentstraininghub.app.data.pageRange
import com.paymentstraininghub.app.data.schemeForSituation
import com.paymentstraininghub.app.data.showingRangeText
import com.paymentstraininghub.app.data.totalPages
import com.paymentstraininghub.app.data.visiblePage
import com.paymentstraininghub.app.security.EventLog
import com.paymentstraininghub.app.ui.navigation.DrawerMenuButton
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfChip
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfEmptyState
import com.paymentstraininghub.app.ui.theme.PfErrorState
import com.paymentstraininghub.app.ui.theme.PfLoading
import com.paymentstraininghub.app.ui.theme.PfPaginationBar
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

private data class LevelFilter(val key: String?, val label: String)

private val LEVEL_FILTERS = listOf(
    LevelFilter(null, "All"),
    LevelFilter("foundation", "Foundation"),
    LevelFilter("core", "Core"),
    LevelFilter("advanced", "Advanced")
)

/** One case category in the side-drawer navigation. */
private sealed interface CaseCategory {
    data object All : CaseCategory
    data object FreeSamples : CaseCategory
    data class Stage(val stage: Int, val stageName: String) : CaseCategory
}

private fun categoryLabel(category: CaseCategory): String {
    return when (category) {
        is CaseCategory.All -> "All cases"
        is CaseCategory.FreeSamples -> "Free samples"
        is CaseCategory.Stage -> "Stage ${category.stage}: ${category.stageName}"
    }
}

/**
 * Saver for the selected case category: All and Free samples by
 * key, a stage by its number and name. An unparseable value
 * restores to All. The saver covers the state object, as
 * rememberSaveable requires for a custom saver.
 */
private val caseCategorySaver: Saver<MutableState<CaseCategory>, String> = Saver(
    save = { state ->
        when (val category = state.value) {
            is CaseCategory.All -> "all"
            is CaseCategory.FreeSamples -> "free"
            is CaseCategory.Stage -> "stage:${category.stage}:${category.stageName}"
        }
    },
    restore = { saved ->
        mutableStateOf(
            when {
                saved == "all" -> CaseCategory.All
                saved == "free" -> CaseCategory.FreeSamples
                saved.startsWith("stage:") -> {
                    val parts = saved.removePrefix("stage:").split(":", limit = 2)
                    val stage = parts.getOrNull(0)?.toIntOrNull()
                    val name = parts.getOrNull(1)
                    if (stage != null && name != null) {
                        CaseCategory.Stage(stage, name)
                    } else {
                        CaseCategory.All
                    }
                }
                else -> CaseCategory.All
            }
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SituationsScreen(
    onNavigate: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    isOuterDrawerOpen: () -> Boolean = { false }
) {
    var situations by remember { mutableStateOf<List<Situation>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLevel by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedScheme by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategory by rememberSaveable(saver = caseCategorySaver) {
        mutableStateOf<CaseCategory>(CaseCategory.All)
    }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    val agendas by AgendaStore.agendas.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categoryDrawerState = rememberDrawerState(DrawerValue.Closed)

    // Collected so lock pills refresh when entitlements load or change.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val billingProducts by BillingManager.products.collectAsState()
    val situationsUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        BillingManager.isSituationsUnlocked()
    }

    LaunchedEffect(retryTick) {
        loading = true
        loadError = false
        try {
            // The lightweight list only: titles, stages and
            // levels, never the full case file. Full detail
            // loads per case, when a case is opened.
            situations = ContentRepository.situationsIndex()
            AgendaStore.refreshIfNeeded(context.filesDir)
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    val trimmedQuery = query.trim()
    // Any filter change restarts paging at the first page: the
    // new filtered list starts at the top, never mid-list.
    LaunchedEffect(trimmedQuery, selectedLevel, selectedScheme, selectedCategory) {
        currentPage = 1
    }
    // Deduped once, up front: a repeated id in the loaded list
    // must never key two rows in the one LazyColumn.
    val dedupedSituations = remember(situations) {
        (situations ?: emptyList()).distinctBy { it.id }
    }
    val showFreeSamplesSection = trimmedQuery.isEmpty() && selectedLevel == null &&
        selectedScheme == null && selectedCategory == CaseCategory.All
    val filtered = remember(dedupedSituations, trimmedQuery, selectedLevel, selectedScheme, selectedCategory) {
        dedupedSituations.filter { situation ->
            // Search matches the title, the case id and the stage
            // name: searching "ABH-01" used to find nothing.
            val matchesQuery = trimmedQuery.isEmpty() ||
                situation.title.contains(trimmedQuery, ignoreCase = true) ||
                situation.id.contains(trimmedQuery, ignoreCase = true) ||
                situation.stageName.contains(trimmedQuery, ignoreCase = true)
            val matchesLevel = selectedLevel == null ||
                situation.level.lowercase() == selectedLevel
            val matchesScheme = selectedScheme == null ||
                schemeForSituation(situation) == selectedScheme
            val matchesCategory = when (val category = selectedCategory) {
                is CaseCategory.All -> true
                is CaseCategory.FreeSamples -> situation.isFreeSample
                is CaseCategory.Stage -> situation.stage == category.stage
            }
            matchesQuery && matchesLevel && matchesScheme && matchesCategory
        }
    }
    val schemes = remember(dedupedSituations) {
        availableSchemes(dedupedSituations)
    }
    val freeSamples = remember(dedupedSituations) {
        dedupedSituations.filter { it.isFreeSample }
    }
    // Paging slices the filtered list BEFORE grouping, so the
    // free-samples section and the stage groups together render
    // exactly one page, with no id keyed twice (see
    // pagedSituationDisplay). The page is clamped into range:
    // a filter change that shrinks the list never strands the
    // list on an empty page.
    val pages = totalPages(filtered.size, PAGE_SIZE)
    val safePage = currentPage.coerceIn(1, maxOf(1, pages))
    val pagedDisplay = remember(filtered, freeSamples, showFreeSamplesSection, safePage) {
        pagedSituationDisplay(
            filtered = filtered,
            freeSamples = freeSamples,
            showFreeSamplesSection = showFreeSamplesSection,
            page = safePage,
            pageSize = PAGE_SIZE
        )
    }
    val visibleFreeSamples = pagedDisplay.freeSamples
    val grouped = pagedDisplay.groups
    val (rangeStart, rangeEnd) = pageRange(filtered.size, safePage, PAGE_SIZE)
    val rangeText = showingRangeText(rangeStart, rangeEnd, filtered.size, "case", "cases")
    val stageCount = remember(dedupedSituations) {
        dedupedSituations.map { it.stage }.distinct().size
    }
    // Category rows for the side drawer, with live counts.
    val categories = remember(dedupedSituations) {
        val stageRows = dedupedSituations.groupBy { it.stage }.toSortedMap()
            .map { (stage, list) -> CaseCategory.Stage(stage, list.first().stageName) to list.size }
        listOf(
            CaseCategory.All to dedupedSituations.size,
            CaseCategory.FreeSamples to dedupedSituations.count { it.isFreeSample }
        ) + stageRows
    }

    ModalNavigationDrawer(
        drawerState = categoryDrawerState,
        // Gestures stay off: this inner drawer must never fight
        // the app drawer for edge swipes. It opens only from the
        // Categories button in the top bar.
        gesturesEnabled = false,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f),
                drawerContainerColor = PfColors.card,
                drawerContentColor = PfColors.navy
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberTopScrollState())
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Text(
                        "Categories",
                        style = PfTypography.screenTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "Cases segregated by learning stage. Pick a category to see only its cases.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    categories.forEach { (category, count) ->
                        CaseCategoryRow(
                            label = categoryLabel(category),
                            count = count,
                            isSelected = category == selectedCategory,
                            onClick = {
                                EventLog.record(
                                    "NAV",
                                    "CASES_CATEGORY_CLICK category=${categoryLabel(category)}"
                                )
                                selectedCategory = category
                                scope.launch { categoryDrawerState.close() }
                            }
                        )
                    }
                }
            }
        }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
            .casesPointerDiagnostic(
                isInnerDrawerOpen = { categoryDrawerState.isOpen },
                isOuterDrawerOpen = isOuterDrawerOpen
            )
    ) {
        TopAppBar(
            title = { Text("Payment Cases", style = PfTypography.screenTitle, color = PfColors.navy) },
            navigationIcon = { DrawerMenuButton(onOpenDrawer = onOpenDrawer) },
            actions = {
                IconButton(onClick = { scope.launch { categoryDrawerState.open() } }) {
                    Icon(Icons.Filled.List, contentDescription = "Categories")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                actionIconContentColor = PfColors.navy
            )
        )
        Column(modifier = Modifier.padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search situations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PfColors.royal,
                    unfocusedBorderColor = PfColors.border,
                    focusedLabelColor = PfColors.royal,
                    unfocusedLabelColor = PfColors.secondaryText,
                    focusedContainerColor = PfColors.card,
                    unfocusedContainerColor = PfColors.card
                )
            )
            Spacer(modifier = Modifier.height(PfSpacing.small))
            Text("Difficulty", style = PfTypography.captionBold, color = PfColors.royal)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                LEVEL_FILTERS.forEach { filter ->
                    PfChip(
                        text = filter.label,
                        isSelected = selectedLevel == filter.key,
                        onClick = { selectedLevel = filter.key }
                    )
                }
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            Text("Scheme", style = PfTypography.captionBold, color = PfColors.royal)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Row(
                modifier = Modifier.horizontalScroll(rememberTopScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
            ) {
                PfChip(
                    text = "All",
                    isSelected = selectedScheme == null,
                    onClick = { selectedScheme = null }
                )
                schemes.forEach { scheme ->
                    PfChip(
                        text = scheme,
                        isSelected = selectedScheme == scheme,
                        onClick = { selectedScheme = scheme }
                    )
                }
            }
        }

        when {
            loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PfLoading(message = "Loading Payment Situations...")
                }
            }
            loadError || situations == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.Center
                ) {
                    PfErrorState(
                        title = "Could not load Payment Situations",
                        message = "Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            filtered.isEmpty() -> {
                PfEmptyState(
                    icon = Icons.Filled.Search,
                    title = "No situations match this filter",
                    message = "Try different words, or clear the filters.",
                    modifier = Modifier.padding(PfSpacing.medium)
                )
            }
            else -> {
                val listState = rememberTopLazyListState(trimmedQuery, selectedLevel, selectedScheme, selectedCategory)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(PfSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    item {
                        // Counts come from the loaded list, never a
                        // static manifest number, so the line stays
                        // honest when the catalogue grows.
                        val loadedCount = dedupedSituations.size
                        Text(
                            "$loadedCount real-world situations across $stageCount learning stages. ${freeSamples.size} free samples are open without any purchase.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                    if (selectedCategory != CaseCategory.All) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Category: ${categoryLabel(selectedCategory)}",
                                    style = PfTypography.captionBold,
                                    color = PfColors.royal,
                                    modifier = Modifier.weight(1f)
                                )
                                PfChip(
                                    text = "Show all",
                                    isSelected = false,
                                    onClick = { selectedCategory = CaseCategory.All }
                                )
                            }
                        }
                    }
                    if (showFreeSamplesSection && visibleFreeSamples.isNotEmpty()) {
                        item {
                            PfSectionHeader(
                                title = "Free samples",
                                modifier = Modifier.padding(top = PfSpacing.small)
                            )
                        }
                        items(visibleFreeSamples, key = { it.id }) { situation ->
                            SituationRow(
                                situation = situation,
                                isLocked = false,
                                showFreePill = true,
                                number = null,
                                thumbnailPath = agendas.case(situation.id)?.thumbnail,
                                onClick = {
                                    EventLog.record("NAV", "CASES_ROW_CLICK id=${situation.id}")
                                    onNavigate(Routes.situation(situation.id))
                                }
                            )
                        }
                    }
                    grouped.forEach { (stage, stageName, list) ->
                        item {
                            PfSectionHeader(
                                title = "Stage $stage: $stageName",
                                modifier = Modifier.padding(top = PfSpacing.small)
                            )
                        }
                        itemsIndexed(list, key = { _, item -> item.id }) { index, situation ->
                            val locked = SituationGating.isSituationLocked(
                                situation = situation,
                                isSituationsUnlocked = situationsUnlocked,
                                weeklyCaseId = null
                            )
                            SituationRow(
                                situation = situation,
                                isLocked = locked,
                                showFreePill = situation.isFreeSample,
                                number = index + 1,
                                thumbnailPath = agendas.case(situation.id)?.thumbnail,
                                onClick = {
                                    EventLog.record("NAV", "CASES_ROW_CLICK id=${situation.id}")
                                    onNavigate(Routes.situation(situation.id))
                                }
                            )
                        }
                    }
                    item {
                        Text(
                            rangeText,
                            style = PfTypography.caption,
                            color = PfColors.secondaryText
                        )
                    }
                    item {
                        PfPaginationBar(
                            currentPage = safePage,
                            totalPages = pages,
                            rangeText = rangeText,
                            showRangeLine = false,
                            onPageChange = { page ->
                                currentPage = page
                                scope.launch { listState.scrollToItem(0) }
                            }
                        )
                    }
                    // Tab-bar clearance: the last item clears the bottom bar.
                    item { PfBottomInset() }
                }
            }
        }
    }
    }
}

@Composable
private fun CaseCategoryRow(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.smallRadius))
            .background(
                if (isSelected) PfColors.royal.copy(alpha = 0.10f) else PfColors.card
            )
            .clickable(onClick = onClick)
            .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isSelected) PfColors.gold else Color.Transparent)
        )
        Text(
            label,
            style = PfTypography.body,
            color = if (isSelected) PfColors.royal else PfColors.navy,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            style = PfTypography.captionBold,
            color = PfColors.secondaryText
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SituationRow(
    situation: Situation,
    isLocked: Boolean,
    showFreePill: Boolean,
    number: Int?,
    thumbnailPath: String? = null,
    onClick: () -> Unit
) {
    // Teaser only: title and pills. No excerpt, explain or
    // takeaway text renders here, locked or unlocked. Layout
    // mirrors iOS situationRow: number badge in stage groups,
    // gold bar, thumbnail (or the briefcase tile when the case
    // has none), title, pill flow, read time, chevron. No
    // bookmark icon: Android has no bookmark store.
    PfCard(onClick = onClick) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
            verticalAlignment = Alignment.Top
        ) {
            if (number != null) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PfColors.royal.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$number",
                        style = PfTypography.captionBold,
                        color = PfColors.royal
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(PfColors.gold)
            )
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                fallback = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PfColors.goldSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Work,
                            contentDescription = null,
                            tint = PfColors.goldText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
            ) {
                Text(
                    situation.title,
                    style = PfTypography.cardTitle,
                    color = PfColors.navy
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
                ) {
                    PfPill(text = situation.stageName)
                    PfPill(
                        text = situation.levelDisplayName,
                        background = PfColors.goldSoft,
                        foreground = PfColors.goldText
                    )
                    schemeForSituation(situation)?.let { scheme ->
                        PfPill(
                            text = scheme,
                            background = PfColors.royal.copy(alpha = 0.10f),
                            foreground = PfColors.royal
                        )
                    }
                    if (showFreePill) {
                        PfPill(
                            text = "Free",
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
                // No read-time label on case rows: the full case text
                // spans only 234 to 467 words across all cases, so
                // any whole-minute label collapses to one value and
                // carries no information. The label was removed
                // rather than stamped on every row.
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PfColors.secondaryText,
                modifier = Modifier
                    .padding(top = PfSpacing.xSmall)
                    .size(16.dp)
            )
        }
    }
}

/**
 * One page of the Payment Cases list: the free-samples section
 * rows and the stage groups, sliced from the filtered list
 * before grouping. Slicing first keeps the duplicate-key
 * invariant of [stageGroupsForSituationsList]: the section and
 * the groups together hold exactly the first [visibleCount]
 * filtered situations, each id once. Pure, so the paging
 * partition is pinned by unit tests.
 */
internal data class PagedSituationDisplay(
    val freeSamples: List<Situation>,
    val groups: List<Triple<Int, String, List<Situation>>>
)

internal fun pagedSituationDisplay(
    filtered: List<Situation>,
    freeSamples: List<Situation>,
    showFreeSamplesSection: Boolean,
    visibleCount: Int
): PagedSituationDisplay {
    val paged = visiblePage(filtered.distinctBy { it.id }, visibleCount)
    return groupPagedSituations(paged, freeSamples, showFreeSamplesSection)
}

/**
 * One numbered page of the Payment Cases list: the free-samples
 * section rows and the stage groups, sliced from the filtered
 * list before grouping. The page slice keeps the same
 * duplicate-key invariant as the prefix form: the section and
 * the groups together hold exactly the page's situations, each
 * id once. Pure, so the paging partition is pinned by unit
 * tests.
 */
internal fun pagedSituationDisplay(
    filtered: List<Situation>,
    freeSamples: List<Situation>,
    showFreeSamplesSection: Boolean,
    page: Int,
    pageSize: Int
): PagedSituationDisplay {
    val paged = pageItems(filtered.distinctBy { it.id }, page, pageSize)
    return groupPagedSituations(paged, freeSamples, showFreeSamplesSection)
}

/**
 * The shared grouping core: the free-samples section holds the
 * free samples inside the page slice, and the stage groups hold
 * the rest, so no id keys two rows in the one LazyColumn.
 */
private fun groupPagedSituations(
    paged: List<Situation>,
    freeSamples: List<Situation>,
    showFreeSamplesSection: Boolean
): PagedSituationDisplay {
    val pagedIds = paged.map { it.id }.toSet()
    val section = if (showFreeSamplesSection) {
        freeSamples.filter { it.id in pagedIds }
    } else {
        emptyList()
    }
    val groups = stageGroupsForSituationsList(
        filtered = paged,
        freeSampleIds = freeSamples.map { it.id }.toSet(),
        showFreeSamplesSection = showFreeSamplesSection
    )
    return PagedSituationDisplay(freeSamples = section, groups = groups)
}

/**
 * Stage groups for the Payment Cases list, with the duplicate-key crash
 * ruled out by construction: when the free-samples section is shown, the
 * free samples are kept out of the stage groups, so no situation id keys
 * two rows in the one LazyColumn (Compose throws
 * IllegalArgumentException on a repeated key, which killed the app on
 * the Cases tab the moment ABH-01 rendered in both sections). When the
 * section is hidden, because a filter is active, every filtered
 * situation stays in its stage group. Pure, so the partition is pinned
 * by unit tests.
 */
internal fun stageGroupsForSituationsList(
    filtered: List<Situation>,
    freeSampleIds: Set<String>,
    showFreeSamplesSection: Boolean
): List<Triple<Int, String, List<Situation>>> {
    // Dedupe by id first (first wins): a repeated id in the loaded
    // list would otherwise key two rows in the one LazyColumn and
    // crash exactly like the free-samples duplication did.
    val deduped = filtered.distinctBy { it.id }
    val groupedSource = if (showFreeSamplesSection) {
        deduped.filterNot { it.id in freeSampleIds }
    } else {
        deduped
    }
    return groupedSource.groupBy { it.stage }
        .toSortedMap()
        .map { (stage, list) -> Triple(stage, list.first().stageName, list) }
}

/**
 * Observation-only pointer diagnostic for the fast-scroll defect
 * (solved-APK defect 3). Watches gestures in the Initial pass and
 * never consumes anything, so scrolling, taps, and the drawers
 * behave exactly as before. Records down/up coordinates,
 * displacement, duration, consumption, drawer state, and whether
 * the gesture ended near the bottom bar (bottom 96dp of the
 * content area, where the bottom bar sits). Whether a bottom-bar
 * item actually received a click is correlated from the NAV event
 * log, which timestamps every tab navigation. Used only by
 * SituationsScreen.
 */
private fun Modifier.casesPointerDiagnostic(
    isInnerDrawerOpen: () -> Boolean,
    isOuterDrawerOpen: () -> Boolean
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val downTime = SystemClock.elapsedRealtime()
        val innerOpen = isInnerDrawerOpen()
        val outerOpen = isOuterDrawerOpen()
        val up = waitForUpOrCancellation()
        val upTime = SystemClock.elapsedRealtime()
        val upPosition = up?.position
        val displacement = upPosition?.let { (it - down.position).getDistance() } ?: 0f
        val bottomThresholdPx = size.height - with(density) { 96.dp.toPx() }
        PointerDiagnostics.record(
            PointerDiagnostics.Gesture(
                elapsedRealtimeMs = downTime,
                downX = down.position.x,
                downY = down.position.y,
                upX = upPosition?.x,
                upY = upPosition?.y,
                displacementPx = displacement,
                durationMs = upTime - downTime,
                cancelled = up == null,
                upConsumed = up?.isConsumed == true,
                innerDrawerOpen = innerOpen,
                outerDrawerOpen = outerOpen,
                endedNearBottom = upPosition != null && upPosition.y >= bottomThresholdPx
            )
        )
    }
}
