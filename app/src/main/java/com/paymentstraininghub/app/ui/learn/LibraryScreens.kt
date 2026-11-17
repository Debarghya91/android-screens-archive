// LibraryScreens.kt
// Guides, labs and tests lists plus the shared entry reading
// view, ported from CoursesView.swift (GuideListView, LabListView,
// TestListView, EntryDetailView). Lists page 20 at a time with
// "Showing N of M" and a Load more button. Guide and lab rows
// open the reading view, which shows the entry's own catalogue
// text; test rows open the real quiz. Counts come from the loaded
// search index only. No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.assessment.TestEntryInfo
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

private const val PAGE_SIZE = 20

/** The catalogue preview: the entry text trimmed to 140 characters, as on iOS. */
private fun SearchEntry.preview(): String {
    val trimmed = text.trim()
    return if (trimmed.length <= 140) trimmed else trimmed.take(140) + "..."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = PfColors.navy) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
    )
}

@Composable
/**
 * One guide, lab or FAQ row. Guides, labs and FAQs carry no
 * thumbnail in the agendas data, so [thumbnailPath] is null and
 * the icon tile renders as the fallback through [ThumbnailTile];
 * the wiring is in place if thumbnails are ever supplied.
 */
private fun EntryRow(entry: SearchEntry, icon: ImageVector, thumbnailPath: String? = null, onClick: () -> Unit) {
    PfCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                fallback = { PfIconTile(icon = icon, size = 40.dp) }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(entry.title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(entry.preview(), style = PfTypography.callout, color = PfColors.secondaryText)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideListScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<SearchEntry>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(retryTick) {
        loadFailed = false
        entries = runCatching { ContentRepository.loadSearchIndex().entries.filter { it.isGuide } }
            .getOrElse {
                loadFailed = true
                emptyList()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(title = "Guides", onBack = onBack)
        when {
            entries == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading guides...")
                }
            }
            loadFailed && entries.isNullOrEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load the guides",
                        message = "No guides are available yet. Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            entries.isNullOrEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    Text(
                        "No guides are available yet. Check your connection and try again.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            else -> {
                val guides = entries!!
                val visible = guides.take(visibleCount)
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    items(visible, key = { it.id }) { entry ->
                        EntryRow(entry = entry, icon = Icons.Filled.Book) {
                            onNavigate(Routes.entry(entry.id))
                        }
                    }
                    item {
                        Text(
                            "Showing ${visible.size} of ${guides.size} guides",
                            style = PfTypography.caption,
                            color = PfColors.secondaryText
                        )
                    }
                    if (guides.size > visible.size) {
                        item {
                            PfSecondaryButton(title = "Load more guides", onClick = {
                                visibleCount += PAGE_SIZE
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabListScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<SearchEntry>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(retryTick) {
        loadFailed = false
        entries = runCatching { ContentRepository.loadSearchIndex().entries.filter { it.isLab } }
            .getOrElse {
                loadFailed = true
                emptyList()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(title = "Labs", onBack = onBack)
        when {
            entries == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading labs...")
                }
            }
            loadFailed && entries.isNullOrEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load the labs",
                        message = "No labs are available yet. Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            entries.isNullOrEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    Text(
                        "No labs are available yet. Check your connection and try again.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            else -> {
                val labs = entries!!
                val visible = labs.take(visibleCount)
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    items(visible, key = { it.id }) { entry ->
                        EntryRow(entry = entry, icon = Icons.Filled.Science) {
                            onNavigate(Routes.entry(entry.id))
                        }
                    }
                    item {
                        Text(
                            "Showing ${visible.size} of ${labs.size} labs",
                            style = PfTypography.caption,
                            color = PfColors.secondaryText
                        )
                    }
                    if (labs.size > visible.size) {
                        item {
                            PfSecondaryButton(title = "Load more labs", onClick = {
                                visibleCount += PAGE_SIZE
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestListScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<SearchEntry>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(retryTick) {
        loadFailed = false
        entries = runCatching { ContentRepository.loadSearchIndex().entries.filter { it.isTest } }
            .getOrElse {
                loadFailed = true
                emptyList()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(title = "Tests", onBack = onBack)
        when {
            entries == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading tests...")
                }
            }
            loadFailed && entries.isNullOrEmpty() -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load the tests",
                        message = "No tests are available yet. Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            else -> {
                val tests = entries!!
                val visible = tests.take(visibleCount)
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        Text(
                            "Each test asks real questions from the course and shows your score with explanations. Open a test to take it.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                    if (tests.isEmpty()) {
                        item {
                            Text(
                                "No tests are available yet. Check your connection and try again.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    items(visible, key = { it.id }) { entry ->
                        TestRow(entry = entry) { onNavigate(Routes.testQuiz(entry.id)) }
                    }
                    if (tests.isNotEmpty()) {
                        item {
                            Text(
                                "Showing ${visible.size} of ${tests.size} tests",
                                style = PfTypography.caption,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    if (tests.size > visible.size) {
                        item {
                            PfSecondaryButton(title = "Load more tests", onClick = {
                                visibleCount += PAGE_SIZE
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}

@Composable
private fun TestRow(entry: SearchEntry, thumbnailPath: String? = null, onClick: () -> Unit) {
    val info = remember(entry) { TestEntryInfo.from(entry) }
    PfCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                fallback = { PfIconTile(icon = Icons.Filled.CheckCircle, size = 40.dp) }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(entry.title, style = PfTypography.cardTitle, color = PfColors.navy)
                info.domainLabel?.let {
                    Text(it, style = PfTypography.caption, color = PfColors.secondaryText)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = PfColors.secondaryText)
                    Text(info.rowMetaText, style = PfTypography.caption, color = PfColors.secondaryText)
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
        }
    }
}

/**
 * The shared reading view for guide and lab entries: the entry
 * title and its own catalogue text, in paragraphs. No page fetch:
 * the catalogue text is the content, as on iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(entryId: String, onBack: () -> Unit) {
    var entry by remember { mutableStateOf<SearchEntry?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(entryId, retryTick) {
        loadFailed = false
        loaded = false
        entry = runCatching { ContentRepository.loadSearchIndex().entries.firstOrNull { it.id == entryId } }
            .getOrElse {
                loadFailed = true
                null
            }
        loaded = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(title = entry?.title ?: "Reading", onBack = onBack)
        when {
            !loaded && !loadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading...")
                }
            }
            loadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this item",
                        message = "Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            entry == null -> {
                // The catalogue loaded but holds no such item
                // (Tester 2 A12): a plain not-found message, not
                // a spinner that never ends or a retry that
                // cannot help. Back is in the top bar.
                Column(
                    modifier = Modifier.padding(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    Text(
                        "This item is no longer in the catalogue.",
                        style = PfTypography.screenTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "It may have been moved or removed. Go back and pick another item.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        Text(entry!!.title, style = PfTypography.screenTitle, color = PfColors.navy)
                    }
                    item {
                        PfBodyText(text = entry!!.text, color = PfColors.navy)
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}
