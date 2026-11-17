// FinalAssessmentScreen.kt
// Final Assessment for one course bundle, ported from iOS
// FinalAssessmentView.swift. 20 questions drawn ONLY from the
// real lesson quiz questions of the course's chapters, loaded in
// batches of 8. The pass mark is strictly above 70 percent, so
// 15 or more correct out of 20. Each course allows at most 3
// attempts; when the attempts are exhausted without a pass the
// assessment locks. Questions and options are shuffled with a
// fresh seed on every attempt. The exam has a 30-minute
// countdown for all 20 questions, on a wall-clock end time kept
// per course, so leaving and coming back resumes the same clock;
// at zero the exam auto-submits with the answers given so far.
// Passing issues a Certificate of Completion for the course.
// The learner name is the signed-in account's email when signed
// in, else "Guest Learner": Android collects no display name,
// and that is the iOS fallback too.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfProgressRing
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalAssessmentScreen(
    productId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val course = remember(productId) {
        ProductCatalogue.products.firstOrNull { it.productId == productId }
    }
    val scope = rememberCoroutineScope()

    val certificates by FinalAssessmentStore.certificates.collectAsState()
    val attemptsMap by FinalAssessmentStore.attemptsUsed.collectAsState()
    val authState by AuthManager.authState.collectAsState()
    // The certificate names the signed-in account (G10); guests
    // keep the "Guest Learner" fallback.
    val learnerName = (authState as? AuthManager.AuthState.SignedIn)
        ?.email?.takeIf { it.isNotBlank() } ?: "Guest Learner"

    val sessionKey = remember(productId) { "final:$productId" }
    val session = remember(sessionKey) { AssessmentSessionHolder.sessionFor(sessionKey) }
    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(session) { session.resumeTicker() }

    var loadedQuestions by remember { mutableStateOf<List<AssessmentQuestion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var earnedCertificate by remember { mutableStateOf<CourseCertificate?>(null) }
    var storedRemainingSeconds by remember { mutableStateOf<Int?>(null) }
    var storeLoaded by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    val isStarted by session.isStarted.collectAsState()
    val isFinished by session.isFinished.collectAsState()
    val currentIndex by session.currentIndex.collectAsState()
    val questions by session.questions.collectAsState()
    val answers by session.answers.collectAsState()
    val timeOnQuestion by session.timeOnQuestion.collectAsState()
    val totalSecondsLeft by session.totalSecondsLeft.collectAsState()
    val elapsedSeconds by session.elapsedSeconds.collectAsState()

    val existingCertificate = course?.let { certificates[it.productId] }
    val attemptsUsed = course?.let { attemptsMap[it.productId] ?: 0 } ?: 0
    val attemptsRemaining = course?.let { FinalAssessmentStore.attemptsRemaining(it.productId) } ?: 0
    val isLocked = course?.let { FinalAssessmentStore.isLocked(it.productId) } ?: false

    // The destination-level ownership guard: entry points (the
    // certificates list, checkout) only offer this screen when
    // the course is owned, but the route itself is deep-linkable,
    // so the screen re-checks. A learner without the course (and
    // without the Integrated Course or admin) sees the paywall
    // view, never the exam. Collected flows make a purchase or a
    // session restore refresh the guard while the screen is open.
    // An exam already started or finished is never paywalled
    // away: the guard branch below runs only before a start.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val isOwned = remember(productId, ownedIds, serverIds, gateState, isAdmin) {
        runCatching { BillingManager.isProductUnlocked(productId) }.getOrDefault(false)
    }

    val endTimeKey = remember(productId) {
        "pf.finalAssessment.endTime.$productId.v1"
    }

    LaunchedEffect(currentIndex) { selectedOption = null }

    LaunchedEffect(productId) {
        FinalAssessmentStore.refresh()
        storeLoaded = true
        val storedEnd = runCatching { AppStore.getLong(endTimeKey) }.getOrNull() ?: 0L
        if (storedEnd > 0L) {
            val remaining = ((storedEnd - System.currentTimeMillis()) / 1000L).toInt()
            storedRemainingSeconds = if (remaining > 0) remaining else null
        }
    }

    // Collect 20 real quiz questions from the course's chapters,
    // in chapter order. Lesson pages are loaded in batches of 8
    // at a time; a lesson whose page fails to load is skipped,
    // never invented. Stops as soon as 20 questions are found.
    LaunchedEffect(retryTick, storeLoaded, isOwned) {
        if (!storeLoaded) return@LaunchedEffect
        val targetCourse = course ?: return@LaunchedEffect
        if (existingCertificate != null || isLocked) {
            isLoading = false
            return@LaunchedEffect
        }
        // No questions are fetched for a learner who does not
        // own the course: the paywall view needs none. A purchase
        // while the screen is open flips isOwned and re-runs
        // this effect, so the exam loads without re-entry.
        if (!isOwned) {
            isLoading = false
            return@LaunchedEffect
        }
        if (loadedQuestions.size >= FinalAssessmentStore.QUESTION_COUNT) return@LaunchedEffect
        isLoading = true
        loadError = null
        val collected = mutableListOf<AssessmentQuestion>()
        var catalogueLoaded = false
        try {
            val entries = ContentRepository.loadSearchIndex().entries
            catalogueLoaded = true
            val seen = mutableSetOf<String>()
            val orderedEntries = mutableListOf<com.paymentstraininghub.app.data.model.SearchEntry>()
            for (chapterId in targetCourse.chapterIds) {
                val number = ContentRepository.chapterNumberFromId(chapterId)
                for (entry in entries) {
                    if (!entry.isLesson) continue
                    if (ContentRepository.chapterNumberFromId(entry.id) != number) continue
                    if (seen.add(entry.id)) orderedEntries.add(entry)
                }
            }
            var index = 0
            while (collected.size < FinalAssessmentStore.QUESTION_COUNT && index < orderedEntries.size) {
                val end = minOf(index + 8, orderedEntries.size)
                val batch = orderedEntries.subList(index, end)
                index = end
                val batchQuestions = batch.map { entry ->
                    scope.async {
                        val detail = runCatching {
                            ContentRepository.lessonDetail(entry.id, context.filesDir)
                        }.getOrNull() ?: return@async emptyList()
                        detail.quiz.map { quiz ->
                            AssessmentQuestion(
                                id = quiz.id,
                                lessonId = entry.id,
                                lessonTitle = entry.title,
                                question = quiz.question,
                                options = quiz.options,
                                correctOptionIndex = quiz.correctOptionIndex,
                                explanation = quiz.explanation
                            )
                        }
                    }
                }.awaitAll().flatten()
                for (question in batchQuestions) {
                    if (collected.size >= FinalAssessmentStore.QUESTION_COUNT) break
                    collected.add(question)
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
        val targetCourse = course ?: return@LaunchedEffect
        if (isFinished && !AssessmentSessionHolder.isResultRecorded(sessionKey)) {
            AssessmentSessionHolder.markResultRecorded(sessionKey)
            scope.launch { runCatching { AppStore.remove(endTimeKey) } }
            storedRemainingSeconds = null
            earnedCertificate = FinalAssessmentStore.recordAttempt(
                course = targetCourse,
                correct = session.correctCount,
                total = questions.size,
                learnerName = learnerName
            )
        }
    }

    fun startExam(totalLimit: Int) {
        AssessmentSessionHolder.clearResultRecorded(sessionKey)
        earnedCertificate = null
        selectedOption = null
        scope.launch {
            runCatching {
                AppStore.putLong(
                    endTimeKey,
                    System.currentTimeMillis() + totalLimit * 1000L
                )
            }
        }
        session.start(
            sourceQuestions = loadedQuestions,
            timed = true,
            randomised = true,
            totalSecondsOverride = totalLimit,
            perQuestionTimeout = false
        )
    }

    fun resumeExam() {
        val remaining = storedRemainingSeconds
        if (remaining != null) {
            startExam(remaining)
        } else {
            scope.launch { runCatching { AppStore.remove(endTimeKey) } }
            startExam(AssessmentSession.FINAL_ASSESSMENT_TOTAL_SECONDS)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Final Assessment", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )

        // The 30-minute countdown, pinned above the exam content
        // so it stays visible while the learner scrolls. Red
        // under 5 minutes.
        if (isStarted && !isFinished) {
            val lowTime = totalSecondsLeft <= 5 * 60
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PfSpacing.large, vertical = PfSpacing.small),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        tint = if (lowTime) PfColors.error else PfColors.royal
                    )
                    Text(
                        "${formatElapsed(totalSecondsLeft)} left",
                        style = PfTypography.sectionTitle,
                        color = if (lowTime) PfColors.error else PfColors.navy
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        Text(
                            "Question ${currentIndex + 1} of ${questions.size}",
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText
                        )
                    }
                }
                PfProgressBar(
                    progress = totalSecondsLeft.toFloat() / maxOf(session.totalLimitSeconds, 1).toFloat(),
                    height = 6.dp
                )
            }
            Divider(color = PfColors.border)
        }

        if (course == null) {
            Column(modifier = Modifier.padding(PfSpacing.large)) {
                Text(
                    "That course was not found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            return@Column
        }

        val currentQuestion = session.currentQuestion
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState(currentIndex, isFinished))
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
        ) {
            when {
                isFinished -> {
                    val passed = session.correctCount >= FinalAssessmentStore.PASS_CORRECT_COUNT
                    PfCard {
                        Row(
                            modifier = Modifier,
                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PfProgressRing(
                                progress = (session.scorePercent / 100.0).toFloat(),
                                size = 110.dp,
                                color = if (passed) PfColors.success else PfColors.royal
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                                Text(
                                    if (passed) "Passed" else "Not passed",
                                    style = PfTypography.sectionTitle,
                                    color = if (passed) PfColors.success else PfColors.navy
                                )
                                Text(
                                    "${session.correctCount} of ${questions.size} correct",
                                    style = PfTypography.sectionTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "${Math.round(session.scorePercent).toInt()}% in ${course.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                Text(
                                    "Pass mark: more than 70%, at least ${FinalAssessmentStore.PASS_CORRECT_COUNT} out of ${questions.size}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                Text(
                                    "Time used: ${formatElapsed(elapsedSeconds)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                if (session.unansweredCount > 0) {
                                    Text(
                                        "${session.unansweredCount} ${if (session.unansweredCount == 1) "question was" else "questions were"} left unanswered.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                        }
                    }
                    val certificate = earnedCertificate ?: existingCertificate
                    if (passed && certificate != null) {
                        CourseCertificateCard(certificate = certificate)
                        PfPrimaryButton(
                            title = "View, share or save certificate",
                            onClick = { onNavigate(Routes.courseCertificate(course.productId)) }
                        )
                    } else if (isLocked) {
                        LockedCard(courseName = course.name)
                    } else {
                        PfCard {
                            Column(
                                modifier = Modifier,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Text(
                                    "$attemptsRemaining ${if (attemptsRemaining == 1) "attempt" else "attempts"} left",
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "Review your answers below, then retake the assessment. Questions and options are shuffled again, so the retake is different.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                        PfPrimaryButton(
                            title = "Retake Final Assessment",
                            onClick = { startExam(AssessmentSession.FINAL_ASSESSMENT_TOTAL_SECONDS) }
                        )
                    }
                    Text(
                        "Review",
                        style = PfTypography.sectionTitle,
                        color = PfColors.navy
                    )
                    questions.forEach { question ->
                        val chosen = answers[question.id]
                        val wasCorrect = chosen == question.correctOptionIndex
                        PfCard {
                            Column(
                                modifier = Modifier,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        when {
                                            chosen == null -> Icons.Filled.RemoveCircle
                                            wasCorrect -> Icons.Filled.CheckCircle
                                            else -> Icons.Filled.HighlightOff
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
                                        style = PfTypography.captionBold,
                                        color = when {
                                            chosen == null -> PfColors.secondaryText
                                            wasCorrect -> PfColors.success
                                            else -> PfColors.error
                                        }
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            question.lessonTitle,
                                            style = PfTypography.caption,
                                            color = PfColors.secondaryText
                                        )
                                    }
                                }
                                Text(
                                    question.question,
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "Correct answer: ${question.options[question.correctOptionIndex]}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.navy
                                )
                                if (chosen != null && chosen != question.correctOptionIndex) {
                                    Text(
                                        "Your answer: ${question.options[chosen]}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.secondaryText
                                    )
                                }
                                timeOnQuestion[question.id]?.let { seconds ->
                                    Text(
                                        "Time on this question: ${seconds}s",
                                        style = PfTypography.caption,
                                        color = PfColors.secondaryText
                                    )
                                }
                                PfBodyText(text = question.explanation, color = PfColors.secondaryText)
                                PfSecondaryButton(
                                    title = "Open the lesson: ${question.lessonTitle}",
                                    onClick = { onNavigate(Routes.lesson(question.lessonId)) }
                                )
                            }
                        }
                    }
                }
                (existingCertificate ?: earnedCertificate) != null -> {
                    val certificate = existingCertificate ?: earnedCertificate!!
                    CourseCertificateCard(certificate = certificate)
                    PfPrimaryButton(
                        title = "View, share or save certificate",
                        onClick = { onNavigate(Routes.courseCertificate(course.productId)) }
                    )
                }
                isLocked -> {
                    LockedCard(courseName = course.name)
                }
                // The paywall view: a learner without the
                // course sees what to expect plus the buy
                // button, never questions. It sits after the
                // finished, certificate and locked branches
                // so a certificate earned earlier still shows
                // even after the entitlement lapses, and a
                // failed-attempt lock still shows its wait
                // message.
                !isOwned && !isStarted -> {
                    Text(
                        "Final assessment: ${course.name}",
                        style = PfTypography.screenTitle, color = PfColors.navy
                    )
                    Spacer(Modifier.padding(PfSpacing.xSmall))
                    Text(
                        "20 questions drawn from the real lesson quizzes for this course. " +
                            "You have 30 minutes for all 20 questions. You need more than 70 percent " +
                            "to pass, that is at least ${FinalAssessmentStore.PASS_CORRECT_COUNT} out of 20 correct. " +
                            "Pass it to earn a certificate for ${course.name}. You get " +
                            "${FinalAssessmentStore.MAX_ATTEMPTS} attempts. " +
                            "Unlock ${course.name} to take the assessment.",
                        style = PfTypography.body, color = PfColors.secondaryText
                    )
                    Spacer(Modifier.padding(PfSpacing.medium))
                    PfPrimaryButton(
                        title = "Go to checkout",
                        onClick = { onNavigate(Routes.checkout(productId)) }
                    )
                }
                isLoading -> {
                    PfLoadingView(message = "Loading real quiz questions...")
                }
                loadError != null -> {
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                        ) {
                            Text(
                                "Could not load the Final Assessment",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                            Text(
                                loadError ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            PfPrimaryButton(title = "Retry", onClick = { retryTick++ })
                        }
                    }
                }
                loadedQuestions.size < FinalAssessmentStore.QUESTION_COUNT -> {
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                        ) {
                            Text(
                                "Not enough real questions yet",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                            PfBodyText(
                                text = "Only ${loadedQuestions.size} real quiz ${if (loadedQuestions.size == 1) "question was" else "questions were"} found in ${course.name}. The Final Assessment needs ${FinalAssessmentStore.QUESTION_COUNT}. No questions were invented to fill the gap. Check your connection and try again.",
                                color = PfColors.secondaryText
                            )
                            PfPrimaryButton(title = "Retry", onClick = { retryTick++ })
                        }
                    }
                }
                !isStarted -> {
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PfPill(text = course.name)
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "${loadedQuestions.size} questions",
                                        style = PfTypography.captionBold,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                            Text(
                                "Final Assessment for ${course.name}",
                                style = PfTypography.sectionTitle,
                                color = PfColors.navy
                            )
                            Text(
                                "20 questions drawn from the real lesson quizzes for this course. These questions test what the course itself teaches, so they are not answerable from generic knowledge. You need more than 70 percent to pass, that is at least 15 out of 20 correct.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Divider(color = PfColors.border)
                            Text(
                                "Attempt ${attemptsUsed + 1} of ${FinalAssessmentStore.MAX_ATTEMPTS}. $attemptsRemaining ${if (attemptsRemaining == 1) "attempt" else "attempts"} left.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                            Text(
                                "Questions and options are shuffled with a fresh seed on every attempt, so each attempt is different. Passing issues your Certificate of Completion for this course.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Timer, contentDescription = null, tint = PfColors.royal)
                                Text(
                                    "30-minute limit",
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                            }
                            PfBodyText(
                                text = "You have 30 minutes for all 20 questions. The timer starts when you tap Start and keeps running even if you leave the app or lock your phone. When it reaches zero, your answers so far are submitted. Unanswered questions count as wrong.",
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    val remaining = storedRemainingSeconds
                    if (remaining != null) {
                        PfCard {
                            Column(
                                modifier = Modifier,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                            ) {
                                Text(
                                    "Exam in progress",
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "You started this assessment earlier and the timer kept running. You have ${formatElapsed(remaining)} left.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                PfPrimaryButton(
                                    title = "Resume Final Assessment",
                                    onClick = { resumeExam() }
                                )
                            }
                        }
                    } else {
                        PfPrimaryButton(
                            title = "Start Final Assessment",
                            onClick = { startExam(AssessmentSession.FINAL_ASSESSMENT_TOTAL_SECONDS) }
                        )
                    }
                }
                else -> {
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PfPill(text = course.name)
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "Question ${currentIndex + 1} of ${questions.size}",
                                        style = PfTypography.captionBold,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                            PfProgressBar(
                                progress = currentIndex.toFloat() / maxOf(questions.size, 1).toFloat(),
                                height = 6.dp
                            )
                            if (currentQuestion != null) {
                                Text(
                                    currentQuestion.question,
                                    style = PfTypography.sectionTitle,
                                    color = PfColors.navy
                                )
                                currentQuestion.options.forEachIndexed { index, option ->
                                    val isSelected = selectedOption == index
                                    Card(
                                        onClick = { selectedOption = index },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) {
                                                PfColors.royal.copy(alpha = 0.08f)
                                            } else {
                                                PfColors.card
                                            }
                                        ),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) PfColors.royal else PfColors.border
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(PfSpacing.medium),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) PfColors.royal else PfColors.secondaryText
                                            )
                                            Text(
                                                option,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = PfColors.navy,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PfPrimaryButton(
                        title = if (currentIndex == questions.size - 1) "See my score" else "Next question",
                        enabled = selectedOption != null,
                        onClick = {
                            val selected = selectedOption ?: return@PfPrimaryButton
                            session.answerAndAdvance(selected)
                        }
                    )
                    Text(
                        "Questions come from the real lesson quizzes for ${course.name}.",
                        style = PfTypography.caption,
                        color = PfColors.secondaryText
                    )
                }
            }
            PfBottomInset()
        }
    }
}

@Composable
private fun LockedCard(courseName: String) {
    PfCard {
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
        ) {
            Text(
                "No attempts left",
                style = PfTypography.cardTitle,
                color = PfColors.navy
            )
            Text(
                "All ${FinalAssessmentStore.MAX_ATTEMPTS} attempts for $courseName have been used without a pass. The Final Assessment is locked. Contact Payments Training Hub support if you need another attempt.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
    }
}
