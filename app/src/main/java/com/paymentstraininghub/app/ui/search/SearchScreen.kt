// SearchScreen.kt
// Search tab: links to lessons, guides, situations, labs, tests
// and FAQs only, never generated answers. Filtering runs off the
// main thread with a short debounce, ranked so title matches
// outrank passing mentions, and previous results stay visible
// while a new filter runs. Recent searches, multi-select type
// filter chips, honest result counts, and error plus retry when
// the index fails to load. Type filters are multi-select: the
// filter runs before ranking, and selecting every type means
// "everything", never "nothing" (see SearchRanking.filterAndRank).
// Lessons respect gating when the chapter list has loaded;
// situations open directly (the detail owns the D101
// hard gate); labs open the lab; guides, FAQs and tests open
// their in-app detail or quiz, never a dead row.
//
// Gating (iOS v69, SearchView.SearchResultRow): a locked situation
// row shows teaser fields only (title, type pill, read time, Locked
// pill) and NEVER excerpt text. A free sample situation shows a Free
// pill. A locked lesson shows a Premium pill. Matching still checks
// entry text, as on iOS: only titles render for locked rows, so a
// match on body text never leaks the matched words.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.Agendas
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.PAGE_SIZE
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.SearchRanking
import com.paymentstraininghub.app.data.SituationGating
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.pageItems
import com.paymentstraininghub.app.data.pageRange
import com.paymentstraininghub.app.data.showingRangeText
import com.paymentstraininghub.app.data.totalPages
import com.paymentstraininghub.app.ui.navigation.DrawerMenuButton
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.setSaver
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfChip
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfEmptyState
import com.paymentstraininghub.app.ui.theme.PfErrorState
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfLoading
import com.paymentstraininghub.app.ui.theme.PfPaginationBar
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private data class TypeFilter(val key: String?, val label: String)

private val TYPE_FILTERS = listOf(
    TypeFilter(null, "All"),
    TypeFilter("lesson", "Lessons"),
    TypeFilter("situation", "Situations"),
    TypeFilter("guide", "Guides"),
    TypeFilter("faq", "FAQs"),
    TypeFilter("lab", "Labs"),
    TypeFilter("test", "Tests")
)

/** Results per page: 20, numbered pages. Lists never render everything at once. */
private const val SEARCH_PAGE_SIZE = PAGE_SIZE

/** Stored recent searches, newest first, capped. */
private const val SEARCH_RECENTS_KEY = "search.recents.v1"
private const val MAX_RECENTS = 8

/** Keystroke debounce before a filter run starts. */
private const val SEARCH_DEBOUNCE_MS = 150L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onNavigate: (String) -> Unit, onOpenDrawer: () -> Unit) {
    // Collected so gating refreshes when BillingManager loads.
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
    val agendas by AgendaStore.agendas.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<SearchEntry>?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Multi-select type filter: an empty set means no filter, and
    // a set holding every type means no filter too (selecting all
    // filters must return results, never an empty list).
    var selectedTypes by rememberSaveable(saver = setSaver()) {
        mutableStateOf<Set<String>>(emptySet())
    }
    // True once the learner has picked any type chip, including
    // All: from then on, an empty query shows the browsable paged
    // list for the selection instead of the hint. The hint
    // promises "pick a type above", so picking one must produce
    // a list; without this flag, All (an empty selection) showed
    // only the hint, and the promise was dead.
    var browsing by rememberSaveable { mutableStateOf(false) }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }
    var debouncedQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var filtering by remember { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }

    LaunchedEffect(retryTick) {
        loading = true
        loadError = false
        try {
            entries = ContentRepository.loadSearchIndex().entries
            chapters = ContentRepository.chapters()
            AgendaStore.refreshIfNeeded(context.filesDir)
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        recents = runCatching {
            Json.decodeFromString<List<String>>(AppStore.getString(SEARCH_RECENTS_KEY) ?: "[]")
        }.getOrElse { emptyList() }
    }

    fun recordRecent(searched: String) {
        val clean = searched.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + recents.filter { it != clean }).take(MAX_RECENTS)
        recents = updated
        scope.launch {
            runCatching { AppStore.putString(SEARCH_RECENTS_KEY, Json.encodeToString(updated)) }
        }
    }

    val trimmedQuery = query.trim()

    // Debounce: each keystroke restarts the delay, so a filter
    // run starts only once typing pauses.
    LaunchedEffect(trimmedQuery) {
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = trimmedQuery
    }

    LaunchedEffect(debouncedQuery, selectedTypes) {
        currentPage = 1
    }

    // Filtering runs off the main thread. Previous results stay
    // visible while a run is in flight (filtering only changes
    // the count line), so typing never flashes an empty list.
    // The type filter runs before ranking, inside filterAndRank.
    LaunchedEffect(entries, debouncedQuery, selectedTypes, browsing) {
        val all = entries ?: emptyList()
        if (debouncedQuery.isEmpty() && selectedTypes.isEmpty() && !browsing) {
            results = emptyList()
            filtering = false
            return@LaunchedEffect
        }
        filtering = true
        val ranked = withContext(Dispatchers.Default) {
            SearchRanking.filterAndRank(all, debouncedQuery, selectedTypes)
        }
        results = ranked
        filtering = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
    ) {
        TopAppBar(
            title = { Text("Search", style = PfTypography.screenTitle, color = PfColors.navy) },
            navigationIcon = { DrawerMenuButton(onOpenDrawer = onOpenDrawer) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy
            )
        )
        Column(modifier = Modifier.padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search lessons, situations, guides") },
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                TYPE_FILTERS.forEach { filter ->
                    val key = filter.key
                    val isSelected = if (key == null) {
                        SearchRanking.selectionIsUnfiltered(selectedTypes)
                    } else {
                        key in selectedTypes
                    }
                    PfChip(
                        text = filter.label,
                        isSelected = isSelected,
                        onClick = {
                            browsing = true
                            selectedTypes = if (key == null) {
                                emptySet()
                            } else if (key in selectedTypes) {
                                selectedTypes - key
                            } else {
                                selectedTypes + key
                            }
                        }
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
                    PfLoading(message = "Loading the course catalogue...")
                }
            }
            loadError || entries == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.Center
                ) {
                    PfErrorState(
                        title = "Could not load search",
                        message = "Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            trimmedQuery.isEmpty() && selectedTypes.isEmpty() && !browsing -> {
                Column(
                    modifier = Modifier.padding(PfSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Text(
                        "${entries?.size ?: 0} items in the catalogue. Type to search, or pick a type above.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    if (recents.isNotEmpty()) {
                        Text(
                            "Recent searches",
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                            recents.forEach { recent ->
                                PfChip(
                                    text = recent,
                                    isSelected = false,
                                    onClick = { query = recent }
                                )
                            }
                        }
                    }
                }
            }
            results.isEmpty() && (filtering || trimmedQuery != debouncedQuery) -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PfSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PfLoading(message = "Searching...")
                }
            }
            results.isEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    PfEmptyState(
                        icon = Icons.Filled.Search,
                        title = if (trimmedQuery.isEmpty()) "No results match this filter"
                        else "No results for \"$trimmedQuery\"",
                        message = "Try different words, or browse all chapters on Home.",
                        actionTitle = "Browse chapters",
                        onAction = { onNavigate(Routes.LEARN) }
                    )
                }
            }
            else -> {
                val pages = totalPages(results.size, SEARCH_PAGE_SIZE)
                val safePage = currentPage.coerceIn(1, maxOf(1, pages))
                val shown = pageItems(results, safePage, SEARCH_PAGE_SIZE)
                val (rangeStart, rangeEnd) = pageRange(results.size, safePage, SEARCH_PAGE_SIZE)
                val rangeText = showingRangeText(rangeStart, rangeEnd, results.size, "result", "results")
                val listState = rememberTopLazyListState(debouncedQuery, selectedTypes)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(PfSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    item {
                        Text(
                            if (filtering || trimmedQuery != debouncedQuery) "Searching..."
                            else rangeText,
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                    items(shown, key = { it.id }) { entry ->
                        val chapterList = chapters
                        SearchResultCard(
                            entry = entry,
                            bookLabel = bookLabelFor(entry),
                            thumbnailPath = thumbnailPathFor(entry, agendas),
                            isLockedSituation = SituationGating.isLockedSituationEntry(
                                entry = entry,
                                isSituationsUnlocked = situationsUnlocked
                            ),
                            isLockedLesson = isLessonLocked(entry, chapterList),
                            isFreeSampleSituation = entry.isSituation &&
                                ContentRepository.freeSampleSituationIds.contains(entry.id),
                            onClick = {
                                recordRecent(trimmedQuery)
                                onResultClick(entry, chapters, context, onNavigate)
                            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultCard(
    entry: SearchEntry,
    bookLabel: String?,
    thumbnailPath: String?,
    isLockedSituation: Boolean,
    isLockedLesson: Boolean,
    isFreeSampleSituation: Boolean,
    onClick: () -> Unit
) {
    val rowTag = if (isLockedSituation) "lockedSituationSearchRow" else "searchResultRow"
    PfCard(
        modifier = Modifier.testTag(rowTag),
        onClick = onClick
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
            verticalAlignment = Alignment.Top
        ) {
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                fallback = { PfIconTile(icon = typeIcon(entry), size = 40.dp) }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        style = PfTypography.cardTitle,
                        color = PfColors.navy,
                        modifier = Modifier.weight(1f)
                    )
                    PfPill(
                        text = typeLabel(entry),
                        background = PfColors.goldSoft,
                        foreground = PfColors.goldText
                    )
                }
                if (bookLabel != null) {
                    Text(
                        bookLabel,
                        style = PfTypography.caption,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
        ) {
            if (isLockedSituation) {
                // No read-time pill: case texts are uniformly short,
                // so the label collapsed to one value on every case.
                PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
            } else if (isFreeSampleSituation) {
                PfPill(text = "Free", background = PfColors.goldSoft, foreground = PfColors.goldText)
            } else if (isLockedLesson) {
                PfPill(text = "Premium", background = PfColors.navySoft, foreground = PfColors.navy)
            }
        }
        // Preview text renders only for unlocked rows. A locked
        // situation shows no excerpt at all. The model preview
        // covers both files: full text, or the compact excerpt.
        if (!isLockedSituation) {
            val preview = entry.preview
            if (preview.isNotEmpty()) {
                Text(
                    preview,
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}

/**
 * The thumbnail for a search result: lessons and cases carry one
 * in agendas.json; guides, labs, tests and FAQs have no thumbnail
 * data, so those rows render the type icon fallback.
 */
private fun thumbnailPathFor(entry: SearchEntry, agendas: Agendas): String? {
    return when {
        entry.isLesson -> agendas.lesson(entry.id)?.thumbnail
        entry.isSituation -> agendas.case(entry.id)?.thumbnail
        else -> null
    }
}

private fun typeIcon(entry: SearchEntry): androidx.compose.ui.graphics.vector.ImageVector {
    return when (typeKey(entry)) {
        "lesson" -> Icons.Filled.MenuBook
        "guide" -> Icons.Filled.Book
        "situation" -> Icons.Filled.Work
        "lab" -> Icons.Filled.Science
        "test" -> Icons.Filled.CheckCircle
        "faq" -> Icons.Filled.QuestionAnswer
        else -> Icons.Filled.Search
    }
}

/** True when a lesson entry is locked: it has a chapter, it is not the free sample, and the chapter is locked. */
private fun isLessonLocked(entry: SearchEntry, chapters: List<Chapter>?): Boolean {
    if (!entry.isLesson) return false
    val chapterId = chapterIdForLesson(entry.id) ?: return false
    val chapterList = chapters ?: return false
    if (ContentRepository.isSampleLesson(entry.id, chapterList)) return false
    return !BillingManager.isLessonUnlocked(entry.id, chapterId)
}

private fun onResultClick(
    entry: SearchEntry,
    chapters: List<Chapter>?,
    context: Context,
    onNavigate: (String) -> Unit
) {
    when (typeKey(entry)) {
        "lesson" -> {
            val chapterId = chapterIdForLesson(entry.id)
            val chapterList = chapters
            if (chapterId != null && chapterList != null) {
                val sample = ContentRepository.isSampleLesson(entry.id, chapterList)
                val unlocked = sample || BillingManager.isLessonUnlocked(entry.id, chapterId)
                if (unlocked) {
                    onNavigate(Routes.lesson(entry.id))
                } else {
                    val productId = ProductCatalogue.productForChapter(chapterId)?.productId
                    if (productId != null) onNavigate(Routes.checkout(productId))
                    else onNavigate(Routes.CHECKOUT)
                }
            } else {
                // Chapters did not load, so gating cannot be checked
                // here. The lesson screen owns its own gate.
                onNavigate(Routes.lesson(entry.id))
            }
        }
        "situation" -> onNavigate(Routes.situation(entry.id))
        "lab" -> onNavigate(Routes.LAB)
        // Guides, FAQs and tests open in-app (Tester 2 A1): their
        // index URLs are relative, so the old http-only browser
        // branch left every one of these rows dead.
        "guide", "faq" -> onNavigate(Routes.entry(entry.id))
        "test" -> onNavigate(Routes.testQuiz(entry.id))
        else -> {
            if (entry.url.startsWith("http")) {
                openUrlInBrowser(context, entry.url)
            }
        }
    }
}

/** Display-number label for a lesson row ("Chapter 26, Lesson 1"), or null when no label applies. */
private fun bookLabelFor(entry: SearchEntry): String? {
    if (!entry.isLesson) return null
    return ReadingOrder.chapterLessonLabel(entry.id, entry.chapterName).takeIf { it != "Lesson" }
}

/** Normalised entry type: "lesson", "guide", "situation", "lab", "test" or "faq". */
private fun typeKey(entry: SearchEntry): String = entry.type.lowercase()

private fun typeLabel(entry: SearchEntry): String {
    return when (typeKey(entry)) {
        "lesson" -> "Lesson"
        "guide" -> "Guide"
        "situation" -> "Situation"
        "lab" -> "Lab"
        "test" -> "Test"
        "faq" -> "FAQ"
        else -> typeKey(entry).replaceFirstChar { it.uppercase() }
    }
}

private fun chapterIdForLesson(lessonId: String): String? {
    if (!lessonId.startsWith("ch")) return null
    val digits = lessonId.drop(2).takeWhile { it.isDigit() }
    return if (digits.isEmpty()) null else "ch$digits"
}

private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No browser available. Nothing else can open this entry.
    }
}
