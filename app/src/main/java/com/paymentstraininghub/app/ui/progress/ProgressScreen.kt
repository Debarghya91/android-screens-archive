// ProgressScreen.kt
// Payments Training Hub Android - My Progress tab. The dashboard is real:
// completed counts from the shared progress store (the same key
// LessonScreen writes), streak and weekly progress from the
// learning-event log, quiz accuracy from recorded quiz scores,
// and competency scores from CompetencyStore. Totals come from
// the loaded catalogue at runtime, never hardcoded. Part
// completion follows the reading-order map. No invented
// statistics anywhere: every card has an honest empty state.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.CompetencyStore
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProgressStore
import com.paymentstraininghub.app.data.ProgressVisibility
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.features.assessment.FinalAssessmentStore
import com.paymentstraininghub.app.features.habit.HabitLearningEvent
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.features.habit.LearningHabitPlanner
import com.paymentstraininghub.app.ui.navigation.DrawerMenuButton
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onNavigate: (String) -> Unit, onOpenDrawer: () -> Unit) {
    val certificates by FinalAssessmentStore.certificates.collectAsState()
    val competencyScores by CompetencyStore.scores.collectAsState()
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var events by remember { mutableStateOf<List<HabitLearningEvent>>(emptyList()) }
    var quizSummary by remember { mutableStateOf<ProgressStore.QuizSummary?>(null) }
    var weeklyGoal by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        FinalAssessmentStore.refresh()
        CompetencyStore.refresh()
        chapters = runCatching { ContentRepository.chapters() }.getOrNull()
        completedIds = runCatching { HabitStore.loadCompletedLessonIds() }.getOrElse { emptySet() }
        events = runCatching { HabitStore.loadEvents() }.getOrElse { emptyList() }
        quizSummary = runCatching { ProgressStore.loadQuizSummary() }.getOrNull()
        weeklyGoal = runCatching { HabitStore.loadWeeklyGoal() }.getOrElse { 0 }
    }

    val earnedCertificates = remember(certificates) {
        FinalAssessmentStore.allCertificates()
    }
    val chapterList = chapters ?: emptyList()
    val allLessonIds = remember(chapterList) { chapterList.flatMap { it.lessonIds } }
    val completedTotal = allLessonIds.count { it in completedIds }
    val partRows = remember(chapterList, completedIds) {
        ReadingOrder.parts.mapNotNull { (partNumber, partTitle) ->
            val partChapterIds = ReadingOrder.chapters
                .filter { it.partNumber == partNumber }
                .map { it.internalId }
                .toSet()
            val partLessons = chapterList
                .filter { it.id in partChapterIds }
                .flatMap { it.lessonIds }
            if (partLessons.isEmpty()) {
                null
            } else {
                Triple(partNumber, partTitle, partLessons.count { it in completedIds } to partLessons.size)
            }
        }
    }
    val streak = remember(events) { LearningHabitPlanner.streakCount(events) }
    val completedThisWeek = remember(events) { LearningHabitPlanner.completedThisWeek(events) }
    // Progress rule: counters appear only after meaningful
    // progress (the first chapter completed). Before that, the
    // progress card shows a starter state, never a zero counter.
    val meaningfulProgress = remember(chapterList, completedIds) {
        ProgressVisibility.hasCompletedChapter(completedIds, chapterList)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "My Progress",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PfColors.navy
                )
            },
            navigationIcon = { DrawerMenuButton(onOpenDrawer = onOpenDrawer) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Your progress",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    if (chapterList.isEmpty()) {
                        Text(
                            "Loading your progress...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    } else if (!meaningfulProgress) {
                        Text(
                            "Your progress will appear here once you finish your first chapter. Start with the first lesson of Course 1.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    } else {
                        Text(
                            "$completedTotal of ${allLessonIds.size} lessons complete",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.navy
                        )
                        partRows.forEach { (partNumber, partTitle, counts) ->
                            CountRow(
                                "Course $partNumber: $partTitle",
                                if (counts.first > 0) {
                                    "${counts.first} of ${counts.second}"
                                } else {
                                    "Not started yet"
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    Button(
                        onClick = { onNavigate(Routes.LEARN) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PfColors.royal,
                            contentColor = Color.White
                        )
                    ) { Text("Continue learning") }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Streak",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    Text(
                        LearningHabitPlanner.streakText(streak),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                    Text(
                        when {
                            completedThisWeek > 0 && weeklyGoal > 0 ->
                                "$completedThisWeek lessons this week, goal $weeklyGoal."
                            completedThisWeek > 0 ->
                                "$completedThisWeek lessons this week."
                            else ->
                                "No lessons yet this week."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Quiz accuracy",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    val summary = quizSummary
                    val accuracy = summary?.accuracyPercent
                    if (summary == null || accuracy == null) {
                        Text(
                            "No quiz attempts yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.navy
                        )
                        Text(
                            "Each lesson ends with a quiz. Take one and your accuracy will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    } else {
                        val lessonWord = if (summary.lessonsAttempted == 1) "lesson" else "lessons"
                        val attemptWord = if (summary.totalAttempts == 1) "attempt" else "attempts"
                        Text(
                            "$accuracy% across ${summary.lessonsAttempted} $lessonWord, " +
                                "${summary.totalAttempts} $attemptWord.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.navy
                        )
                        Text(
                            "Best score per lesson.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Competency",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    if (competencyScores.isEmpty()) {
                        Text(
                            "No assessments yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.navy
                        )
                        Text(
                            "Take a diagnostic or final assessment to see your competency scores here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    } else {
                        CompetencyDimension.entries.forEach { dimension ->
                            val score = competencyScores[dimension]
                            if (score != null) {
                                CountRow(dimension.label, "${score.roundToInt()}%")
                            }
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Suggested next action",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    Text(
                        "Work through the chapters in order, from what a payment is to practice and interview preparation. The first lesson of each chapter is free.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    Button(
                        onClick = { onNavigate(Routes.LEARN) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PfColors.royal,
                            contentColor = Color.White
                        )
                    ) { Text("Browse the course") }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "The course",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    Text(
                        "The catalogue has ${ContentManifest.chapterCount} chapters and " +
                            "${ContentManifest.lessonCount} lessons across ${ContentManifest.domainCount} domains. " +
                            "The first lesson of each chapter is free. Other lessons unlock with a purchase.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    OutlinedButton(onClick = { onNavigate(Routes.CHECKOUT) }) {
                        Text("See courses")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "Certificates",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    if (earnedCertificates.isEmpty()) {
                        Text(
                            "No certificates earned yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.navy
                        )
                        Text(
                            "Certificates are earned by completing a course and passing its final assessment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    } else {
                        earnedCertificates.forEach { certificate ->
                            Text(
                                "${certificate.courseName} - ${certificate.scoreText} (${Math.round(certificate.scorePercent).toInt()}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    OutlinedButton(onClick = { onNavigate(Routes.CERTIFICATES) }) {
                        Text("View certificates")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Column(modifier = Modifier.padding(PfSpacing.medium)) {
                    Text(
                        "The catalogue",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    CountRow("Chapters", ContentManifest.chapterCount)
                    CountRow("Lessons", ContentManifest.lessonCount)
                    CountRow("Payment Situations", ContentManifest.situationCount)
                    CountRow("FAQs", ContentManifest.faqCount)
                    CountRow("Guides", ContentManifest.guideCount)
                    CountRow("Tests", ContentManifest.testCount)
                    CountRow("Labs", ContentManifest.labCount)
                    CountRow("Simulators", ContentManifest.simulatorCount)
                }
            }
            // Tab-bar clearance: the last item clears the bottom bar.
            PfBottomInset()
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    CountRow(label, count.toString())
}

@Composable
private fun CountRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PfSpacing.xSmall)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = PfColors.navy,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = PfColors.secondaryText
        )
    }
}
