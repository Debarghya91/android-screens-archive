// ChapterDetailScreen.kt
// One chapter's lessons, ported from ChapterDetailView in
// CoursesView.swift: chapter title, completion summary, the
// locked-chapter banner naming the unlocking bundle, lesson rows
// with lock/sample states, "Showing N of M lessons" with Load
// more (10 at a time). Locked lessons open Checkout for the
// owning bundle; free-sample and unlocked lessons open the
// lesson. Lesson rows show display numbers from the
// reading-order map, never internal ids, and the chapter time
// line ("About X in total, Y left") comes from the bundled
// lesson-durations manifest; a lesson with no manifest entry
// shows no duration, and no duration is ever guessed.

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LessonDurations
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.AgendaBlock
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

private const val CHAPTER_PAGE_SIZE = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDetailScreen(chapterNumber: Int, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too: it
    // is a key of the per-row unlock remember below.
    val isAdmin by AuthManager.isAdmin.collectAsState()

    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadFailed by remember { mutableStateOf(false) }
    var visibleCount by rememberSaveable { mutableStateOf(CHAPTER_PAGE_SIZE) }
    var retryTick by remember { mutableStateOf(0) }
    var durationsTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val agendas by AgendaStore.agendas.collectAsState()

    LaunchedEffect(chapterNumber, retryTick) {
        loadFailed = false
        try {
            chapters = ContentRepository.chapters()
            entries = ContentRepository.loadSearchIndex().entries
            completedIds = runCatching { HabitStore.loadCompletedLessonIds() }.getOrElse { emptySet() }
            runCatching { LessonDurations.ensureLoaded(context) }
            AgendaStore.refreshIfNeeded(context.filesDir)
            durationsTick++
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val chapterId = "ch$chapterNumber"
    val chapter = chapters?.firstOrNull { it.number == chapterNumber }
    val lessons = remember(entries, chapterNumber) {
        val inChapter = entries.filter {
            it.isLesson && ContentRepository.chapterNumberFromId(it.id) == chapterNumber
        }
        val byId = inChapter.associateBy { it.id }
        ReadingOrder.sortLessonIds(inChapter.map { it.id }).mapNotNull { byId[it] }
    }
    val completedCount = lessons.count { it.id in completedIds }
    val isLocked = !BillingManager.isChapterUnlocked(chapterId)
    val bundle = ProductCatalogue.productForChapter(chapterId)
    val visibleLessons = lessons.take(visibleCount)
    // Duration totals from the manifest only, recomputed once it
    // loads (durationsTick). Lessons with no entry contribute
    // nothing; the line hides entirely when nothing is known.
    @Suppress("UNUSED_VARIABLE")
    val durationsLoaded = durationsTick
    val totalSeconds = lessons.mapNotNull { LessonDurations.secondsFor(it.id) }.sum()
    val leftSeconds = lessons
        .filter { it.id !in completedIds }
        .mapNotNull { LessonDurations.secondsFor(it.id) }
        .sum()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(ReadingOrder.chapterLabel(chapterId), color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            chapters == null && !loadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading lessons...")
                }
            }
            loadFailed && chapters == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this chapter",
                        message = "No lessons are available for this chapter yet. Check your connection and try again.",
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
                        // Thumbnail beside the chapter title, when
                        // the chapter has one; otherwise the title
                        // alone, never a placeholder.
                        val chapterAgenda = agendas.chapter(chapterId)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (chapterAgenda?.thumbnail != null) {
                                ThumbnailTile(
                                    thumbnailPath = chapterAgenda.thumbnail,
                                    modifier = Modifier.size(width = 96.dp, height = 54.dp)
                                )
                            }
                            Text(
                                chapter?.title ?: ReadingOrder.chapterLabel(chapterId),
                                style = PfTypography.screenTitle,
                                color = PfColors.navy,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    val chapterAgendaText = agendas.chapter(chapterId)?.agenda
                    if (!chapterAgendaText.isNullOrBlank()) {
                        item {
                            AgendaBlock(agenda = chapterAgendaText, label = "Why take this chapter")
                        }
                    }
                    item {
                        // Progress rule: no zero counter. Before
                        // the first lesson is done, the starter
                        // line replaces the count.
                        Text(
                            if (completedCount > 0) {
                                "$completedCount of ${lessons.size} lessons complete"
                            } else {
                                "${lessons.size} lessons. Start with the first lesson."
                            },
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                    if (totalSeconds > 0) {
                        item {
                            Text(
                                "${LessonDurations.formatTotal(totalSeconds)}, " +
                                    LessonDurations.formatLeft(leftSeconds),
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    if (isLocked) {
                        item {
                            PfCard {
                                Text("Locked chapter", style = PfTypography.cardTitle, color = PfColors.navy)
                                if (bundle != null) {
                                    Text(
                                        "Unlock this chapter with ${bundle.name} for ${BillingManager.priceText(bundle.productId)}.",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText
                                    )
                                    Text(
                                        "Unlock this chapter with The Integrated Course.",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText
                                    )
                                    PfPrimaryButton(title = "See purchase options", onClick = {
                                        onNavigate(Routes.CHECKOUT)
                                    })
                                } else {
                                    Text(
                                        "This chapter sits outside the main reading order and is not part of any course. Its first lesson is free.",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                        }
                    }
                    if (lessons.isEmpty()) {
                        item {
                            Text(
                                "No lessons are available for this chapter yet. Check your connection and try again.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    items(visibleLessons, key = { it.id }) { lesson ->
                        val isSample = chapters?.let { ContentRepository.isSampleLesson(lesson.id, it) } ?: false
                        // Keyed on every gate input, including the
                        // admin flag: a bare call here would keep
                        // the guest-time value when restore lands
                        // while this list is on screen.
                        val unlocked = remember(
                            lesson.id, isSample, chapterId, ownedIds, serverIds, isAdmin
                        ) { isLessonOpen(lesson.id, chapterId, isSample) }
                        val minutes = LessonDurations.secondsFor(lesson.id)
                            ?.let { "Listen ${LessonDurations.formatMinutes(it)}" }
                        val subtitle = listOfNotNull(
                            ReadingOrder.lessonLabel(lesson.id),
                            minutes
                        ).joinToString(" • ").ifEmpty { null }
                        LearnLessonRow(
                            lessonId = lesson.id,
                            title = lesson.title,
                            subtitle = subtitle,
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
                                visibleCount += CHAPTER_PAGE_SIZE
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
        }
    }
}
