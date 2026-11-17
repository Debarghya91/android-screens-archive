// SessionDetailScreen.kt
// One guided session's lessons, ported from GuidedPathDetailView
// in HomeView.swift: the session description, completion summary
// with a progress bar, the Up next card (Continue, or Review
// again when finished), and the session's lessons in chapter
// order with lock/sample states, 20 at a time. Nothing on this
// screen is gated at the session level; individual lessons keep
// their own lock states.

package com.paymentstraininghub.app.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.GuidedSessions
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

private const val SESSION_PAGE_SIZE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(sessionId: Int, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too: it
    // is a key of the per-row unlock remember below.
    val isAdmin by AuthManager.isAdmin.collectAsState()

    val session = remember(sessionId) { GuidedSessions.byId(sessionId) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(SESSION_PAGE_SIZE) }
    var retryTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val agendas by AgendaStore.agendas.collectAsState()

    LaunchedEffect(sessionId, retryTick) {
        loadFailed = false
        try {
            chapters = ContentRepository.chapters()
            entries = ContentRepository.loadSearchIndex().entries
            completedIds = runCatching { HabitStore.loadCompletedLessonIds() }.getOrElse { emptySet() }
            AgendaStore.refreshIfNeeded(context.filesDir)
            loaded = true
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val lessons = remember(session, entries) { session?.lessons(entries) ?: emptyList() }
    val completedCount = lessons.count { it.id in completedIds }
    val next = lessons.firstOrNull { it.id !in completedIds } ?: lessons.firstOrNull()
    val visibleLessons = lessons.take(visibleCount)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(session?.title ?: "Session", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            session == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    Text(
                        "This session is not available.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            !loaded && !loadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading lessons...")
                }
            }
            loadFailed && !loaded -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this session",
                        message = "No lessons are available for this session yet. Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
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
                        Text(session.description, style = PfTypography.body, color = PfColors.secondaryText)
                    }
                    item {
                        PfCard {
                            // Progress rule: no zero counter and no
                            // empty bar before the first lesson.
                            if (completedCount > 0) {
                                Text(
                                    "$completedCount of ${lessons.size} lessons complete",
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                                PfProgressBar(
                                    progress = if (lessons.isEmpty()) 0f else completedCount.toFloat() / lessons.size.toFloat()
                                )
                            } else {
                                Text(
                                    "${lessons.size} lessons. Not started yet.",
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                            }
                        }
                    }
                    if (next != null) {
                        item {
                            PfCard {
                                Text("Up next", style = PfTypography.cardTitle, color = PfColors.navy)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                                ) {
                                    ThumbnailTile(
                                        thumbnailPath = agendas.lesson(next.id)?.thumbnail,
                                        modifier = Modifier.size(width = 72.dp, height = 40.dp),
                                        fallback = {
                                            PfIconTile(icon = Icons.Filled.MenuBook, size = 40.dp)
                                        }
                                    )
                                    Text(
                                        next.title,
                                        style = PfTypography.body,
                                        color = PfColors.navy,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                PfPrimaryButton(
                                    title = if (completedCount == lessons.size) "Review again" else "Continue",
                                    onClick = {
                                        onNavigate(Routes.lesson(next.id))
                                    }
                                )
                            }
                        }
                    }
                    if (lessons.isEmpty()) {
                        item {
                            Text(
                                "No lessons are available for this session yet. Check your connection and try again.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    items(visibleLessons, key = { it.id }) { lesson ->
                        val chapterNumber = ContentRepository.chapterNumberFromId(lesson.id)
                        val chapterId = chapterNumber?.let { "ch$it" } ?: ""
                        val isSample = chapters?.let { ContentRepository.isSampleLesson(lesson.id, it) } ?: false
                        // Keyed on every gate input, including the
                        // admin flag: a bare call here would keep
                        // the guest-time value when restore lands
                        // while this list is on screen.
                        val unlocked = remember(
                            lesson.id, isSample, chapterId, ownedIds, serverIds, isAdmin
                        ) { isLessonOpen(lesson.id, chapterId, isSample) }
                        LearnLessonRow(
                            lessonId = lesson.id,
                            title = lesson.title,
                            subtitle = ReadingOrder.chapterLessonLabel(lesson.id, lesson.chapterName),
                            isSample = isSample,
                            unlocked = unlocked,
                            thumbnailPath = agendas.lesson(lesson.id)?.thumbnail,
                            onClick = {
                                if (unlocked) {
                                    onNavigate(Routes.lesson(lesson.id))
                                } else {
                                    onNavigate(checkoutRouteForChapterId(chapterId))
                                }
                            }
                        )
                    }
                    if (lessons.isNotEmpty()) {
                        item {
                            Text(
                                "Showing ${visibleLessons.size} of ${lessons.size} lessons",
                                style = PfTypography.caption,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    if (lessons.size > visibleLessons.size) {
                        item {
                            PfSecondaryButton(title = "Load more lessons", onClick = {
                                visibleCount += SESSION_PAGE_SIZE
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}
