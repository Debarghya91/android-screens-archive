// RevisionCentreScreen.kt
// Spaced repetition revision centre, ported from iOS
// RevisionCentreView.swift. All concepts are real completed
// lessons the learner scheduled; due dates and intervals come
// from RevisionService, never invented. The review and
// add-lesson sheets are full-screen dialogs on Android.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.revision

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

private fun dueText(concept: RevisionConcept): String {
    val today = LocalDate.now()
    val dueDay = Instant.ofEpochMilli(concept.nextDueMillis)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    if (!dueDay.isAfter(today)) return "Due today"
    return "Next due " + dueDay.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisionCentreScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val concepts by RevisionService.concepts.collectAsState()
    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reviewingConcept by remember { mutableStateOf<RevisionConcept?>(null) }
    var showAddLesson by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        RevisionService.refresh()
        entries = runCatching { ContentRepository.loadSearchIndex().entries }
            .getOrDefault(emptyList())
        completedIds = HabitStore.loadCompletedLessonIds()
    }

    val dueToday = remember(concepts) { RevisionService.dueToday }
    val weakConcepts = remember(concepts) { RevisionService.weakConcepts }
    val allConcepts = remember(concepts) { RevisionService.allConcepts }
    val visibleAllConcepts = remember(allConcepts, visibleCount) {
        allConcepts.take(visibleCount)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Revision Centre", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState(selectedTab))
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xl)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                Text(
                    "Daily revision",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PfColors.navy
                )
                Text(
                    "Recall each concept from memory, then mark yourself correct or incorrect. Correct recalls push the concept further out; incorrect ones bring it back to today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; visibleCount = PAGE_SIZE },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    label = { Text("Due today (${dueToday.size})") }
                )
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; visibleCount = PAGE_SIZE },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    label = { Text("Weak concepts (${weakConcepts.size})") }
                )
                SegmentedButton(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; visibleCount = PAGE_SIZE },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    label = { Text("All (${allConcepts.size})") }
                )
            }

            when (selectedTab) {
                0 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        PfSectionHeader(title = "Due today")
                        if (dueToday.isEmpty()) {
                            PfCard {
                                Text(
                                    "Nothing is due today. Complete a lesson and add it below, or come back when a concept falls due.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                            }
                        } else {
                            dueToday.forEach { concept ->
                                ConceptRow(concept) { reviewingConcept = concept }
                            }
                        }
                    }
                }
                1 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        PfSectionHeader(title = "Weak concepts")
                        if (weakConcepts.isEmpty()) {
                            PfCard {
                                Text(
                                    "No weak concepts yet. A concept appears here when you have marked it incorrect more often than correct.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                            }
                        } else {
                            weakConcepts.forEach { concept ->
                                ConceptRow(concept) { reviewingConcept = concept }
                            }
                        }
                    }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        PfSectionHeader(title = "All revision concepts")
                        if (allConcepts.isEmpty()) {
                            PfCard {
                                Text(
                                    "No concepts scheduled yet. Add a completed lesson below to start revising it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                            }
                        } else {
                            visibleAllConcepts.forEach { concept ->
                                ConceptRow(concept) { reviewingConcept = concept }
                            }
                            if (allConcepts.size > visibleAllConcepts.size) {
                                Text(
                                    "Showing ${visibleAllConcepts.size} of ${allConcepts.size} concepts",
                                    style = PfTypography.caption,
                                    color = PfColors.secondaryText
                                )
                                PfSecondaryButton(
                                    title = "Load more concepts",
                                    onClick = { visibleCount += PAGE_SIZE }
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                PfSectionHeader(title = "Add a lesson to revision")
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        Text(
                            "Pick any lesson you have completed. It is scheduled for revision today, then at day 1, day 3, day 7 and day 30 as you recall it correctly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                        PfSecondaryButton(
                            title = "Choose a completed lesson",
                            onClick = { showAddLesson = true }
                        )
                    }
                }
            }
            PfBottomInset()
        }
    }

    reviewingConcept?.let { concept ->
        ReviewDialog(
            concept = concept,
            entries = entries,
            onDismiss = { reviewingConcept = null },
            onNavigate = onNavigate,
            onRecord = { correct ->
                scope.launch { RevisionService.recordReview(concept.id, correct) }
            }
        )
    }
    if (showAddLesson) {
        AddLessonDialog(
            entries = entries,
            completedIds = completedIds,
            onDismiss = { showAddLesson = false },
            onSchedule = { lessonId, title ->
                scope.launch { RevisionService.scheduleLessonForRevision(lessonId, title) }
                showAddLesson = false
            }
        )
    }
}

@Composable
private fun ConceptRow(concept: RevisionConcept, onReview: () -> Unit) {
    PfCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PfIconTile(icon = Icons.Filled.Refresh, size = 44.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)
            ) {
                Text(concept.title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(
                    dueText(concept),
                    style = PfTypography.captionBold,
                    color = PfColors.royal
                )
                Text(
                    "Correct ${concept.correctCount} \u2022 Incorrect ${concept.incorrectCount} \u2022 Interval ${concept.intervalDays} ${if (concept.intervalDays == 1) "day" else "days"}",
                    style = PfTypography.caption,
                    color = PfColors.secondaryText
                )
            }
            Button(
                onClick = onReview,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PfColors.royal,
                    contentColor = Color.White
                )
            ) { Text("Review") }
        }
    }
}

/// The review flow: recall the concept first, then mark Correct
/// or Incorrect. The answer is recorded in RevisionService,
/// which advances or resets the interval.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewDialog(
    concept: RevisionConcept,
    entries: List<SearchEntry>,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onRecord: (Boolean) -> Unit
) {
    var revealed by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf<Boolean?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
        ) {
            TopAppBar(
                title = { Text("Review", color = PfColors.navy) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = PfColors.navy)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberTopScrollState(concept.id))
                    .padding(PfSpacing.large),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
            ) {
                Text(
                    concept.title,
                    style = PfTypography.sectionTitle,
                    color = PfColors.navy
                )
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        Text(
                            "Recall from memory",
                            style = PfTypography.cardTitle,
                            color = PfColors.navy
                        )
                        Text(
                            "Before looking, try to recall the key points of this lesson: what it taught, why it matters at work, and where it can go wrong.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                        if (revealed) {
                            val entry = entries.firstOrNull { it.id == concept.id }
                            if (entry != null) {
                                PfBodyText(text = entry.text)
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        onDismiss()
                                        onNavigate(Routes.lesson(entry.id))
                                    }
                                ) {
                                    Text(
                                        "Open the lesson to check yourself",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.royal
                                    )
                                }
                            } else {
                                Text(
                                    "This lesson is not in the loaded catalogue right now.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                            }
                        } else {
                            PfSecondaryButton(
                                title = "I have recalled it, show me the summary",
                                onClick = { revealed = true }
                            )
                        }
                    }
                }
                if (recorded == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        PfPrimaryButton(
                            title = "I recalled it correctly",
                            onClick = {
                                onRecord(true)
                                recorded = true
                            }
                        )
                        PfSecondaryButton(
                            title = "I did not recall it",
                            onClick = {
                                onRecord(false)
                                recorded = false
                            }
                        )
                    }
                } else {
                    PfCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            Text(
                                if (recorded == true) {
                                    "Recorded as correct. The next review is pushed further out."
                                } else {
                                    "Recorded as incorrect. This concept is due again today."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                            PfPrimaryButton(title = "Done", onClick = onDismiss)
                        }
                    }
                }
                PfBottomInset()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLessonDialog(
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    onDismiss: () -> Unit,
    onSchedule: (String, String) -> Unit
) {
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    val completedLessons = remember(entries, completedIds) {
        entries.filter { it.isLesson && it.id in completedIds }
    }
    val unscheduled = remember(completedLessons) {
        completedLessons.filter { !RevisionService.isScheduled(it.id) }
    }
    val visibleUnscheduled = remember(unscheduled, visibleCount) {
        unscheduled.take(visibleCount)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
        ) {
            TopAppBar(
                title = { Text("Add a lesson", color = PfColors.navy) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = PfColors.navy)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberTopScrollState())
                    .padding(PfSpacing.large),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
            ) {
                if (unscheduled.isEmpty()) {
                    Text(
                        if (completedLessons.isEmpty()) {
                            "No completed lessons yet. Complete a lesson first, then add it here."
                        } else {
                            "Every completed lesson is already scheduled for revision."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                } else {
                    visibleUnscheduled.forEach { lesson ->
                        PfCard(onClick = { onSchedule(lesson.id, lesson.title) }) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PfIconTile(icon = Icons.Filled.MenuBook, size = 40.dp)
                                Text(
                                    lesson.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.navy,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.AddCircle,
                                    contentDescription = null,
                                    tint = PfColors.royal
                                )
                            }
                        }
                    }
                    if (unscheduled.size > visibleUnscheduled.size) {
                        Text(
                            "Showing ${visibleUnscheduled.size} of ${unscheduled.size} lessons",
                            style = PfTypography.caption,
                            color = PfColors.secondaryText
                        )
                        PfSecondaryButton(
                            title = "Load more lessons",
                            onClick = { visibleCount += PAGE_SIZE }
                        )
                    }
                }
                PfBottomInset()
            }
        }
    }
}
