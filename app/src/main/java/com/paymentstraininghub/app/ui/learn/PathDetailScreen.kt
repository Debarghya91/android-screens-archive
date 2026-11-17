// PathDetailScreen.kt
// One guided learning path, ported from PathDetailView in
// Views/Paths/PathDetailView.swift. Sections in iOS order:
// header, active path, entry expectations, objectives, lessons
// grouped as numbered chapter steps, simulator exercises,
// assessment checkpoints, practice situations, capstone, how to
// complete, certificate. Lesson counts and progress are real,
// from the catalogue and completed-lesson data. The earned
// certificate card opens the path certificate view.
// No em dashes in any user-facing text.

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ActivePathStore
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.CompetencyStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathDetailScreen(pathId: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val activePathId by ActivePathStore.activePathId.collectAsState()
    val competencyScores by CompetencyStore.scores.collectAsState()
    val scope = rememberCoroutineScope()

    val path = remember(pathId) { LearningPaths.all.firstOrNull { it.id == pathId } }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var situations by remember { mutableStateOf<List<Situation>>(emptyList()) }
    var weeklyGoal by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(pathId, retryTick) {
        loadFailed = false
        try {
            chapters = ContentRepository.chapters()
            entries = ContentRepository.loadSearchIndex().entries
            completedIds = runCatching { HabitStore.loadCompletedLessonIds() }.getOrElse { emptySet() }
            situations = runCatching { ContentRepository.situations() }.getOrElse { emptyList() }
            weeklyGoal = runCatching { HabitStore.loadWeeklyGoal() }.getOrElse { 0 }
            ActivePathStore.refresh()
            CompetencyStore.refresh()
            AgendaStore.refreshIfNeeded(context.filesDir)
            loaded = true
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(path?.title ?: "Learning journey", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            path == null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    Text(
                        "This learning journey is not available.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            !loaded && !loadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading...")
                }
            }
            loadFailed && !loaded -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this journey",
                        message = "Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            else -> {
                PathDetailContent(
                    path = path,
                    chapters = chapters ?: emptyList(),
                    entries = entries,
                    completedIds = completedIds,
                    situations = situations,
                    weeklyGoal = weeklyGoal,
                    isActivePath = activePathId == path.id,
                    hasChosenJourney = activePathId != null,
                    recordedScore = competencyScores[path.assessmentDimension],
                    onSetActive = { scope.launch { ActivePathStore.setActive(path.id) } },
                    onRemoveActive = { scope.launch { ActivePathStore.setActive(null) } },
                    onNavigate = onNavigate
                )
            }
        }
    }
}

@Composable
private fun PathDetailContent(
    path: LearningPath,
    chapters: List<Chapter>,
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    situations: List<Situation>,
    weeklyGoal: Int,
    isActivePath: Boolean,
    hasChosenJourney: Boolean,
    recordedScore: Double?,
    onSetActive: () -> Unit,
    onRemoveActive: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val agendas by AgendaStore.agendas.collectAsState()
    // Gate inputs for the per-row unlock remember below: the
    // admin flag flips on session restore without touching any
    // entitlement flow, so it is collected here, in the scope
    // that uses it, alongside the entitlement flows.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val lessons = remember(path, entries) { path.lessons(entries) }
    val (completedCount, totalCount) = remember(path, entries, completedIds) {
        path.progress(entries, completedIds)
    }
    val progress = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()
    val isComplete = totalCount > 0 && completedCount == totalCount

    val chapterBlocks = remember(path, chapters, entries) {
        path.chapterNumbers.map { number ->
            val title = chapters.firstOrNull { it.number == number }?.title ?: ReadingOrder.chapterLabel("ch$number")
            val blockLessons = entries.filter {
                it.isLesson && ContentRepository.chapterNumberFromId(it.id) == number
            }
            Triple(number, title, blockLessons)
        }
    }
    val labs = remember(path, entries) {
        val wanted = path.chapterNumbers.toSet()
        entries.filter { entry ->
            entry.isLab && ContentRepository.chapterNumberFromId(entry.id)?.let { it in wanted } == true
        }
    }
    val situationById = remember(situations) { situations.associateBy { it.id } }
    val entryById = remember(entries) { entries.associateBy { it.id } }
    fun resolveSituation(id: String): Situation? {
        return situationById[id]
            ?: entryById[id]?.let { ContentRepository.situationFromEntry(it) }
    }
    val pathSituations = remember(path, situationById, entryById) {
        path.situationIds.mapNotNull { resolveSituation(it) }
    }
    val capstone = remember(path, situationById, entryById) {
        resolveSituation(path.capstoneSituationId)
    }
    val estimatedWeeks = remember(totalCount, weeklyGoal) {
        LearningPath.estimatedWeeks(totalLessons = totalCount, weeklyTarget = weeklyGoal)
    }

    LazyColumn(
        state = rememberTopLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.xLarge)
    ) {
        // Header.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfBodyText(text = path.description, color = PfColors.secondaryText)
                // Progress rule: no zero counter, no empty bar and
                // no 0% before the first lesson is done.
                if (completedCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        PfProgressBar(progress = progress, modifier = Modifier.weight(1f))
                        Text(
                            "${Math.round(progress * 100)}%",
                            style = PfTypography.body,
                            color = PfColors.navy
                        )
                    }
                    Text(
                        "$completedCount of $totalCount lessons complete",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                } else {
                    Text(
                        "$totalCount lessons. Not started yet.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        // Your journey card: what a journey is, and choosing or changing it.
        item {
            PfCard {
                if (isActivePath) {
                    Text("Your journey.", style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(
                        "Home follows this journey. The Continue learning block on Home shows " +
                            "where you left off and your next lesson in this journey, so you always " +
                            "know where to start.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Text(
                        "Changing journey never loses completed-lesson progress. Progress is kept per lesson, so everything you have completed stays complete.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    PfSecondaryButton(title = "Remove this journey", onClick = { onRemoveActive() })
                } else {
                    Text("Choose this journey?", style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(
                        "A journey is a set order of lessons for one goal. Choose this journey " +
                            "and Home will show its next lesson in Continue learning, so you always " +
                            "know where to start.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Text(
                        "Changing journey never loses completed-lesson progress. Progress is kept per lesson, so everything you have completed stays complete.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    PfPrimaryButton(
                        title = if (hasChosenJourney) "Change journey" else "Choose your journey",
                        onClick = { onSetActive() }
                    )
                }
            }
        }
        // Entry expectations.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Before you start")
                Text(path.entryExpectations, style = PfTypography.body, color = PfColors.secondaryText)
            }
        }
        // Objectives.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "What you will learn")
                path.objectives.forEach { objective ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text("•", style = PfTypography.body, color = PfColors.royal)
                        Text(objective, style = PfTypography.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        // Lessons, grouped as numbered chapter steps.
        item { PfSectionHeader(title = "Lessons") }
        if (lessons.isEmpty()) {
            item {
                Text(
                    "Lessons will appear here once the course catalogue has loaded.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            }
        }
        chapterBlocks.forEachIndexed { index, (number, title, blockLessons) ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Text(
                        "Step ${index + 1} of ${chapterBlocks.size}: $title",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "${ReadingOrder.chapterLabel("ch$number")} in the ${path.title} sequence, step ${index + 1} of ${chapterBlocks.size} in this journey.",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                }
            }
            items(blockLessons, key = { it.id }) { lesson ->
                val chapterId = "ch$number"
                val isSample = ContentRepository.isSampleLesson(lesson.id, chapters)
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
                        // Every lesson tap opens the lesson
                        // route: the lesson screen shows the
                        // paywall view when the lesson is locked.
                        // List rows never jump straight to
                        // checkout.
                        onNavigate(Routes.lesson(lesson.id))
                    }
                )
            }
        }
        // Simulator exercises.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Simulator exercises")
                if (labs.isEmpty()) {
                    Text(
                        "No simulator exercises are listed for this journey in the course catalogue.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        items(labs, key = { it.id }) { lab ->
            PfCard(onClick = { onNavigate(Routes.entry(lab.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lab.title, style = PfTypography.cardTitle, color = PfColors.navy, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
                }
            }
        }
        // Assessment checkpoints.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Assessment checkpoints")
                Text(
                    "A 5-question quiz drawn from the real lesson quizzes in this journey, scored against ${path.assessmentDimension.label}.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                if (recordedScore != null) {
                    Text(
                        "Your recorded score: ${Math.round(recordedScore)}%",
                        style = PfTypography.body,
                        color = PfColors.navy
                    )
                } else {
                    Text(
                        "Not assessed yet for this dimension.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
                Text(
                    "Checkpoints are optional practice. Completing this journey does not require them.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                PfPrimaryButton(title = "Take the checkpoint assessment", onClick = {
                    onNavigate(Routes.assessmentPath(path.id))
                })
            }
        }
        // Practice situations.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Practice situations")
                if (pathSituations.isEmpty()) {
                    Text(
                        "Situations will appear here once the catalogue has loaded.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        items(pathSituations, key = { it.id }) { situation ->
            PfCard(onClick = { onNavigate(Routes.situation(situation.id)) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    ThumbnailTile(
                        thumbnailPath = agendas.case(situation.id)?.thumbnail,
                        fallback = {
                            PfIconTile(
                                icon = Icons.Filled.Work,
                                size = 40.dp,
                            )
                        },
                        modifier = Modifier.size(width = 72.dp, height = 40.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(situation.title, style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(
                            "Case ${situation.id}",
                            style = PfTypography.callout,
                            color = PfColors.secondaryText
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
                }
            }
        }
        // Capstone.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Capstone")
                if (capstone == null) {
                    Text(
                        "The capstone will appear here once the catalogue has loaded.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        if (capstone != null) {
            item {
                PfCard(onClick = { onNavigate(Routes.situation(capstone.id)) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        ThumbnailTile(
                            thumbnailPath = agendas.case(capstone.id)?.thumbnail,
                                fallback = {
                                PfIconTile(
                                    icon = Icons.Filled.Work,
                                    size = 40.dp,
                                    )
                            },
                            modifier = Modifier.size(width = 72.dp, height = 40.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            PfPill(text = "Capstone situation")
                            Text(capstone.title, style = PfTypography.cardTitle, color = PfColors.navy)
                        }
                    }
                    Text(
                        "Work this real situation end to end. It is the final check for this journey.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        // How to complete.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "How to complete this journey")
                Text(path.completionCriteria, style = PfTypography.body, color = PfColors.secondaryText)
                if (estimatedWeeks != null && weeklyGoal > 0) {
                    Text(
                        "At your weekly goal of $weeklyGoal ${if (weeklyGoal == 1) "lesson" else "lessons"} a week, the $totalCount lessons in this journey take $estimatedWeeks ${if (estimatedWeeks == 1) "week" else "weeks"}.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        // Certificate.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfSectionHeader(title = "Certificate")
                if (path.certificateTitle == null) {
                    Text(
                        "This journey has no certificate. It prepares you for interviews; the five certificate journeys are listed under Certificates on the Dashboard.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                } else if (isComplete) {
                    PfCard(onClick = { onNavigate(Routes.pathCertificate(path.id)) }) {
                        Text("Certificate earned", style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(
                            "View and share your ${path.certificateTitle} certificate.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                } else {
                    Text(
                        "Complete all $totalCount lessons in this journey to earn the ${path.certificateTitle} certificate.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        item { PfBottomInset() }
    }
}
