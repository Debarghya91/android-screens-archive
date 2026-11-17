// AssessmentScreen.kt
// Short assessments drawn from real lesson quizzes, ported from
// iOS AssessmentView. Each assessment is 5 questions taken from
// the real quiz questions of lessons in the dimension's chapters
// (or the path's chapters). The explanation shown is the real
// quiz explanation. The result is recorded in CompetencyStore
// only; no score is shown that was not earned. Two modes, both
// optional, are offered on the setup screen: timed mode (60
// seconds per question, with a total time limit; an expired
// question is marked unanswered, never guessed) and randomised
// mode (questions and options shuffled with a fresh seed per
// session). If fewer than 5 real questions are found, the setup
// screen says so instead of inventing questions.

package com.paymentstraininghub.app.features.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.CompetencyStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressRing
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(
    dimension: CompetencyDimension,
    chapterNumbers: List<Int>,
    sourceTitle: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    // The session is retained across rotation and navigation
    // (AssessmentSessionHolder): questions, answers, index, and
    // timers survive. The ticker stops on dispose and resumes on
    // the next composition.
    val sessionKey = remember(dimension, chapterNumbers) {
        "assessment:$dimension:${chapterNumbers.joinToString(",")}"
    }
    val session = remember(sessionKey) { AssessmentSessionHolder.sessionFor(sessionKey) }
    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(session) { session.resumeTicker() }

    var loadedQuestions by remember { mutableStateOf<List<AssessmentQuestion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var timedMode by remember { mutableStateOf(false) }
    var randomisedMode by remember { mutableStateOf(true) }
    var retryTick by remember { mutableStateOf(0) }

    val isStarted by session.isStarted.collectAsState()
    val isFinished by session.isFinished.collectAsState()
    val currentIndex by session.currentIndex.collectAsState()
    val questions by session.questions.collectAsState()
    val answers by session.answers.collectAsState()
    // timeOnQuestion changes only when advancing (verified in
    // AssessmentSession: the 250 ms ticker never writes it), so
    // it stays collected here. The ticking timers are collected
    // only inside AssessmentTimerText and TimeUsedText below, so
    // the ticker recomposes those leaves alone (Tester 2 E3).
    val timeOnQuestion by session.timeOnQuestion.collectAsState()

    LaunchedEffect(currentIndex) { selectedOption = null }

    // Retry that actually reloads (Tester 2 A4): the old retry
    // only bumped retryTick, and the load effect early-returns
    // when questions are already loaded, so Retry did nothing.
    // Resetting the session and clearing the loaded questions
    // lets the load effect run again.
    fun retryLoad() {
        session.resetToSetup()
        loadedQuestions = emptyList()
        retryTick++
    }

    LaunchedEffect(retryTick) {
        if (loadedQuestions.isNotEmpty()) return@LaunchedEffect
        isLoading = true
        loadError = null
        val collected = mutableListOf<AssessmentQuestion>()
        var tried = 0
        var catalogueLoaded = false
        try {
            val entries = ContentRepository.loadSearchIndex().entries
            catalogueLoaded = true
            outer@ for (number in chapterNumbers) {
                val chapterLessons = entries.filter {
                    it.isLesson && ContentRepository.chapterNumberFromId(it.id) == number
                }
                for (entry in chapterLessons) {
                    if (collected.size >= 5 || tried >= 12) break@outer
                    tried += 1
                    val detail = runCatching {
                        ContentRepository.lessonDetail(entry.id, context.filesDir)
                    }.getOrNull() ?: continue
                    for (quiz in detail.quiz) {
                        if (collected.size >= 5) break
                        collected.add(
                            AssessmentQuestion(
                                id = quiz.id,
                                lessonId = entry.id,
                                lessonTitle = entry.title,
                                question = quiz.question,
                                options = quiz.options,
                                correctOptionIndex = quiz.correctOptionIndex,
                                explanation = quiz.explanation
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            catalogueLoaded = false
        }
        if (collected.isEmpty() && !catalogueLoaded) {
            loadError = "The course catalogue has not loaded yet. Check your connection and try again."
        }
        loadedQuestions = collected
        isLoading = false
    }

    LaunchedEffect(isFinished) {
        if (isFinished && !AssessmentSessionHolder.isResultRecorded(sessionKey)) {
            AssessmentSessionHolder.markResultRecorded(sessionKey)
            CompetencyStore.recordAssessment(
                dimension = dimension,
                correct = session.correctCount,
                total = questions.size
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Assessment", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            isLoading -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading real quiz questions...")
                }
            }
            loadError != null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load the assessment",
                        message = loadError!!,
                        retryTitle = "Retry",
                        onRetry = { retryLoad() }
                    )
                }
            }
            isFinished -> {
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        PfCard {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                PfProgressRing(progress = (session.scorePercent / 100).toFloat(), size = 110.dp)
                                Text(
                                    "${session.correctCount} of ${questions.size} correct",
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "${Math.round(session.scorePercent)}% in ${dimension.label}",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                                TimeUsedText(session = session)
                                if (session.unansweredCount > 0) {
                                    Text(
                                        "${session.unansweredCount} ${if (session.unansweredCount == 1) "question was" else "questions were"} left unanswered.",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText
                                    )
                                }
                                Text(
                                    "Recorded in your competency profile.",
                                    style = PfTypography.callout,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                    item { PfSectionHeader(title = "Review") }
                    items(questions, key = { it.id }) { question ->
                        val chosen = answers[question.id]
                        val wasCorrect = chosen == question.correctOptionIndex
                        PfCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Icon(
                                    when {
                                        chosen == null -> Icons.Filled.RemoveCircleOutline
                                        wasCorrect -> Icons.Filled.CheckCircle
                                        else -> Icons.Filled.Close
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        chosen == null -> PfColors.secondaryText
                                        wasCorrect -> PfColors.success
                                        else -> PfColors.error
                                    }
                                )
                                Text(
                                    when {
                                        chosen == null -> "Not answered"
                                        wasCorrect -> "Correct"
                                        else -> "Not correct"
                                    },
                                    style = PfTypography.cardTitle,
                                    color = when {
                                        chosen == null -> PfColors.secondaryText
                                        wasCorrect -> PfColors.success
                                        else -> PfColors.error
                                    }
                                )
                            }
                            Text(question.lessonTitle, style = PfTypography.caption, color = PfColors.secondaryText)
                            Text(question.question, style = PfTypography.body, color = PfColors.navy)
                            Text(
                                "Correct answer: ${question.options[question.correctOptionIndex]}",
                                style = PfTypography.body,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.navy
                            )
                            if (chosen != null && !wasCorrect && chosen in question.options.indices) {
                                Text(
                                    "Your answer: ${question.options[chosen]}",
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                            }
                            timeOnQuestion[question.id]?.let { seconds ->
                                Text(
                                    "Time on this question: ${seconds}s",
                                    style = PfTypography.callout,
                                    color = PfColors.secondaryText
                                )
                            }
                            if (question.explanation.isNotEmpty()) {
                                PfBodyText(text = question.explanation, color = PfColors.secondaryText)
                            }
                            PfSecondaryButton(title = "Open the lesson: ${question.lessonTitle}", onClick = {
                                onNavigate(Routes.lesson(question.lessonId))
                            })
                        }
                    }
                    item {
                        PfPrimaryButton(title = "Retake assessment", onClick = {
                            AssessmentSessionHolder.clearResultRecorded(sessionKey)
                            session.start(loadedQuestions, timedMode, randomisedMode)
                        })
                    }
                    item {
                        PfSecondaryButton(title = "Change options", onClick = {
                            AssessmentSessionHolder.clearResultRecorded(sessionKey)
                            session.resetToSetup()
                        })
                    }
                    item {
                        Text(
                            "Questions come from the real lesson quizzes for $sourceTitle.",
                            style = PfTypography.callout,
                            color = PfColors.secondaryText
                        )
                    }
                    item { PfBottomInset() }
                }
            }
            loadedQuestions.isEmpty() -> {
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large)
                ) {
                    item {
                        PfCard {
                            Text(
                                "No quiz questions were found in these lessons yet. The lesson pages may still be loading, or these lessons have no quiz on the site.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    item { PfBottomInset() }
                }
            }
            !isStarted -> {
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        PfCard {
                            Text(
                                "${loadedQuestions.size} ${if (loadedQuestions.size == 1) "question" else "questions"}",
                                style = PfTypography.captionBold,
                                color = PfColors.secondaryText
                            )
                            Text("Assessment for $sourceTitle", style = PfTypography.screenTitle, color = PfColors.navy)
                            Text(
                                "Questions come from the real lesson quizzes for $sourceTitle.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            if (loadedQuestions.size < 5) {
                                Text(
                                    "Only ${loadedQuestions.size} real quiz ${if (loadedQuestions.size == 1) "question was" else "questions were"} found in these lessons, so the assessment uses ${if (loadedQuestions.size == 1) "that one" else "those ${loadedQuestions.size}"}. No questions were invented to reach 5.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Timed mode", style = PfTypography.body, color = PfColors.navy, modifier = Modifier.weight(1f))
                                Switch(checked = timedMode, onCheckedChange = { timedMode = it })
                            }
                            Text(
                                "60 seconds per question, with a total limit of ${formatElapsed(AssessmentSession.SECONDS_PER_QUESTION * maxOf(loadedQuestions.size, 1))} for the session. When time runs out on a question it is marked unanswered, never guessed.",
                                style = PfTypography.callout,
                                color = PfColors.secondaryText
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Randomise questions and options",
                                    style = PfTypography.body,
                                    color = PfColors.navy,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = randomisedMode, onCheckedChange = { randomisedMode = it })
                            }
                            Text(
                                "Questions and options are shuffled with a fresh seed each session, so a retake is different. The correct answer is tracked by content, never by position.",
                                style = PfTypography.callout,
                                color = PfColors.secondaryText
                            )
                            PfPrimaryButton(title = "Start assessment", onClick = {
                                session.start(loadedQuestions, timedMode, randomisedMode)
                            })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
            else -> {
                val question = questions.getOrNull(currentIndex)
                if (question == null) {
                    Column(modifier = Modifier.padding(PfSpacing.large)) {
                        PfErrorStateView(
                            title = "Could not load the assessment",
                            message = "The assessment questions could not be read. Try again.",
                            retryTitle = "Retry",
                            onRetry = { retryLoad() }
                        )
                    }
                } else {
                    LazyColumn(
                        state = rememberTopLazyListState(currentIndex),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                    ) {
                        item {
                            Text(
                                "Question ${currentIndex + 1} of ${questions.size}",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                        }
                        if (session.isTimed) {
                            item {
                                AssessmentTimerText(session = session)
                            }
                        }
                        item {
                            Text(question.question, style = PfTypography.body, color = PfColors.navy)
                        }
                        items(question.options.withIndex().toList(), key = { it.index }) { (index, option) ->
                            val selected = selectedOption == index
                            Card(
                                onClick = { selectedOption = index },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) PfColors.royal.copy(alpha = 0.08f) else PfColors.card
                                ),
                                border = BorderStroke(1.dp, if (selected) PfColors.royal else PfColors.border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    option,
                                    style = PfTypography.body,
                                    color = PfColors.navy,
                                    modifier = Modifier.padding(PfSpacing.medium)
                                )
                            }
                        }
                        item {
                            PfPrimaryButton(
                                title = if (currentIndex == questions.size - 1) "See my score" else "Next question",
                                enabled = selectedOption != null,
                                onClick = {
                                    selectedOption?.let { session.answerAndAdvance(it) }
                                }
                            )
                        }
                        item {
                            Text(
                                "Questions come from the real lesson quizzes for $sourceTitle.",
                                style = PfTypography.callout,
                                color = PfColors.secondaryText
                            )
                        }
                        item { PfBottomInset() }
                    }
                }
            }
        }
    }
}

/**
 * The ticking question/total timers, as a leaf (Tester 2 E3).
 * Collects the 250 ms ticker flows here so only this leaf
 * recomposes four times a second, never the whole screen.
 */
@Composable
private fun AssessmentTimerText(session: AssessmentSession) {
    val secondsLeft by session.secondsLeftForQuestion.collectAsState()
    val totalSecondsLeft by session.totalSecondsLeft.collectAsState()
    Column {
        Text(
            "${secondsLeft}s left on this question",
            style = PfTypography.body,
            fontWeight = FontWeight.SemiBold,
            color = PfColors.navy
        )
        Text(
            "Total ${formatElapsed(totalSecondsLeft)} left",
            style = PfTypography.callout,
            color = PfColors.secondaryText
        )
    }
}

/** The finished-screen time-used line, as a leaf for the same reason. */
@Composable
private fun TimeUsedText(session: AssessmentSession) {
    val elapsedSeconds by session.elapsedSeconds.collectAsState()
    Text(
        "Time used: ${formatElapsed(elapsedSeconds)}",
        style = PfTypography.body,
        color = PfColors.secondaryText
    )
}
