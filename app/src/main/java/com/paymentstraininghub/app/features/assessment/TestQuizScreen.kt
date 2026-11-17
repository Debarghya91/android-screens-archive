// TestQuizScreen.kt
// Runs one real test, ported from TestQuizView in CoursesView.swift:
// loads the test page's own TEST_DATA questions, asks them one at a
// time, scores the attempt, and reviews every answer with the page's
// explanations. Untimed in the app: the page's duration is shown as
// the time to allow, and the time used is shown on the results, but
// no countdown ends the attempt early. Questions stay in the page's
// own order, so a retake asks the same questions in the same order.
// Scores are not saved anywhere: the attempt lives only while this
// screen is on screen. Gated like the lesson screen: a test opens
// only when every chapter it draws on is unlocked
// (EntryAccessRules.testQuizDestination); a locked test shows the
// paywall view and its questions are never loaded.

package com.paymentstraininghub.app.features.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.EntryAccessRules
import com.paymentstraininghub.app.billing.TestQuizDestination
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfLockedGateCard
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfProgressRing
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestQuizScreen(entryId: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var entry by remember { mutableStateOf<SearchEntry?>(null) }
    var entryLoadFailed by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadedInfo by remember { mutableStateOf<TestPageInfo?>(null) }
    var loadedQuestions by remember { mutableStateOf<List<TestQuizQuestion>>(emptyList()) }
    var isStarted by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var answers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var startedAtMs by remember { mutableStateOf<Long?>(null) }
    var finishedAtMs by remember { mutableStateOf<Long?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // Collected so the test gate refreshes when entitlements
    // load or change, and when the admin flag lands on restore,
    // matching the lesson and entry-detail screens. They are
    // keys of the flip effect below, not of the load effect, so
    // a billing refresh never resets the screen to the spinner.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    val isAdmin by AuthManager.isAdmin.collectAsState()

    LaunchedEffect(entryId, retryTick) {
        isLoading = true
        loadError = null
        entryLoadFailed = false
        isLocked = false
        try {
            val found = ContentRepository.loadSearchIndex().entries
                .firstOrNull { it.id == entryId && it.isTest }
            if (found == null) {
                entryLoadFailed = true
            } else {
                entry = found
                // The test gate, matching iOS: a test opens only
                // when every chapter it draws on is unlocked. A
                // locked test shows the paywall view and its
                // questions are never loaded.
                val destination = EntryAccessRules.testQuizDestination(entryId) { chapterId ->
                    BillingManager.isChapterUnlocked(chapterId)
                }
                if (destination is TestQuizDestination.Paywall) {
                    loadedInfo = null
                    loadedQuestions = emptyList()
                    isLocked = true
                } else {
                    val (info, questions) = TestPageLoader.load(found)
                    loadedInfo = info
                    loadedQuestions = questions
                }
            }
        } catch (e: TestPageError) {
            loadError = e.message
        } catch (e: Exception) {
            loadError = TestPageError.BadResponse.message
        } finally {
            isLoading = false
        }
    }

    // A gate flip while the screen is open unlocks or locks the
    // test without leaving the screen: entitlements landing
    // after entry (restore, purchase, admin flag) re-evaluate
    // the gate, a flip to unlocked reloads the questions, and a
    // flip to locked drops the loaded questions so no stale
    // content stays on screen. The load effect owns the
    // decision while it is running.
    LaunchedEffect(ownedIds, serverIds, gateState, isAdmin) {
        if (entry == null || isLoading) return@LaunchedEffect
        val destination = EntryAccessRules.testQuizDestination(entryId) { chapterId ->
            BillingManager.isChapterUnlocked(chapterId)
        }
        val nowLocked = destination is TestQuizDestination.Paywall
        if (nowLocked == isLocked) return@LaunchedEffect
        if (nowLocked) {
            isLocked = true
            loadedInfo = null
            loadedQuestions = emptyList()
            answers = emptyMap()
            selectedOption = null
            currentIndex = 0
            isStarted = false
            isFinished = false
        } else {
            retryTick++
        }
    }

    val catalogueInfo = entry?.let { TestEntryInfo.from(it) }
    val currentQuestion = loadedQuestions.getOrNull(currentIndex)
    val correctCount = loadedQuestions.count { answers[it.id] == it.correctOptionIndex }
    val scorePercentRounded = if (loadedQuestions.isEmpty()) 0
    else Math.round(correctCount.toDouble() / loadedQuestions.size.toDouble() * 100).toInt()
    val passed: Boolean? = loadedInfo?.passMarkPercent?.let { scorePercentRounded >= it }
    val durationMinutes = loadedInfo?.durationMinutes ?: catalogueInfo?.estimatedMinutes
    val elapsedText = if (startedAtMs != null && finishedAtMs != null) {
        formatElapsed(((finishedAtMs!! - startedAtMs!!) / 1000L).toInt())
    } else {
        null
    }

    fun startTest() {
        answers = emptyMap()
        currentIndex = 0
        selectedOption = null
        isFinished = false
        startedAtMs = System.currentTimeMillis()
        finishedAtMs = null
        isStarted = true
    }

    fun answerAndAdvance() {
        val question = currentQuestion ?: return
        val selected = selectedOption ?: return
        answers = answers + (question.id to selected)
        selectedOption = null
        if (currentIndex >= loadedQuestions.size - 1) {
            finishedAtMs = System.currentTimeMillis()
            isFinished = true
        } else {
            currentIndex += 1
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(entry?.title ?: "Test", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(PfSpacing.large),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = PfColors.royal)
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    Text("Loading the test questions...", style = PfTypography.body, color = PfColors.secondaryText)
                }
            }
            entryLoadFailed -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this test",
                        message = "The test could not be found in the course catalogue. Check your connection and try again.",
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            loadError != null -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfErrorStateView(
                        title = "Could not load this test",
                        message = loadError!!,
                        retryTitle = "Retry",
                        onRetry = { retryTick++ }
                    )
                }
            }
            isLocked -> {
                // The paywall view: title and type stay visible
                // (discovery); the questions were never loaded.
                // The buy button goes to checkout for the product
                // that unlocks this test.
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        PfPill(text = "Test")
                    }
                    item {
                        Text(entry?.title ?: "Test", style = PfTypography.screenTitle, color = PfColors.navy)
                    }
                    item {
                        val productId = EntryAccessRules.testUnlockingProductId(entryId)
                        val product = ProductCatalogue.productFor(productId)
                        PfLockedGateCard(
                            title = "Test locked",
                            bodyText = testLockCopy(catalogueInfo, product?.name),
                            checkoutTitle = "Go to checkout",
                            onCheckout = { onNavigate(Routes.checkout(productId)) },
                            gateTestTag = "testLockedGate"
                        )
                    }
                    item { PfBottomInset() }
                }
            }
            loadedQuestions.isEmpty() -> {
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        PfCard {
                            Text("This test has no questions yet", style = PfTypography.cardTitle, color = PfColors.navy)
                            val count = catalogueInfo?.questionCount
                            val questionWord = if (count == 1) "question" else "questions"
                            Text(
                                if (count != null) {
                                    "The catalogue lists $count $questionWord for this test, but no questions could be read from the test page. The page may have changed, or the connection may have dropped part of it. Try again, or come back later."
                                } else {
                                    "No questions could be read from the test page. Try again, or come back later."
                                },
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            PfPrimaryButton(title = "Retry", onClick = { retryTick++ })
                        }
                    }
                    item { PfBottomInset() }
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
                                PfProgressRing(progress = scorePercentRounded / 100f, size = 110.dp)
                                Text(
                                    "$correctCount of ${loadedQuestions.size} correct",
                                    style = PfTypography.cardTitle,
                                    color = PfColors.navy
                                )
                                Text(
                                    "$scorePercentRounded% on ${entry?.title ?: "this test"}",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                                loadedInfo?.passMarkPercent?.let { passMark ->
                                    Text("Pass mark: $passMark%.", style = PfTypography.body, color = PfColors.secondaryText)
                                }
                                if (passed != null) {
                                    Text(
                                        if (passed) "Passed. Well done." else "Not passed yet. Read the review below and try again.",
                                        style = PfTypography.body,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (passed) PfColors.success else PfColors.error
                                    )
                                }
                                elapsedText?.let {
                                    Text("Time used: $it", style = PfTypography.body, color = PfColors.secondaryText)
                                }
                                Text(
                                    "This score is for this attempt only and is not saved.",
                                    style = PfTypography.callout,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                    item { PfSectionHeader(title = "Review") }
                    items(loadedQuestions, key = { it.id }) { question ->
                        TestReviewCard(question = question, chosen = answers[question.id], onNavigate = onNavigate)
                    }
                    item {
                        PfPrimaryButton(title = "Retake test", onClick = { startTest() })
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
                            Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                                PfPill(text = "Test")
                                Text(
                                    "${loadedQuestions.size} ${if (loadedQuestions.size == 1) "question" else "questions"}",
                                    style = PfTypography.captionBold,
                                    color = PfColors.secondaryText
                                )
                            }
                            Text(entry?.title ?: "Test", style = PfTypography.screenTitle, color = PfColors.navy)
                            catalogueInfo?.domainLabel?.let {
                                Text(it, style = PfTypography.callout, color = PfColors.secondaryText)
                            }
                            durationMinutes?.let { minutes ->
                                Text(
                                    "Allow about $minutes ${if (minutes == 1) "minute" else "minutes"}.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                            loadedInfo?.passMarkPercent?.let { passMark ->
                                Text("Pass mark: $passMark%.", style = PfTypography.body, color = PfColors.secondaryText)
                            }
                            Text(
                                "Questions come from this test on the course website. Answer every question to see your score and the explanations. Your answers stay on this device for this attempt and are not saved.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            PfPrimaryButton(title = "Start test", onClick = { startTest() })
                        }
                    }
                    item { PfBottomInset() }
                }
            }
            else -> {
                val question = currentQuestion
                if (question == null) {
                    Column(modifier = Modifier.padding(PfSpacing.large)) {
                        PfErrorStateView(
                            title = "Could not load this test",
                            message = "The test questions could not be read. Try again.",
                            retryTitle = "Retry",
                            onRetry = { retryTick++ }
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
                            Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                                PfPill(text = "Test")
                                question.chapterId?.let { chapterId ->
                                    ContentRepository.chapterNumberFromId(chapterId)?.let { number ->
                                        PfPill(text = ReadingOrder.chapterLabel("ch$number"))
                                    }
                                }
                            }
                        }
                        item {
                            Text(
                                "Question ${currentIndex + 1} of ${loadedQuestions.size}",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                        }
                        item {
                            PfProgressBar(
                                progress = currentIndex.toFloat() / maxOf(loadedQuestions.size, 1).toFloat(),
                                height = 6.dp
                            )
                        }
                        item {
                            question.scenario?.let {
                                PfBodyText(text = it, color = PfColors.secondaryText)
                            }
                            Text(question.question, style = PfTypography.body, color = PfColors.navy)
                        }
                        items(question.options.withIndex().toList(), key = { it.index }) { (index, option) ->
                            val selected = selectedOption == index
                            Card(
                                onClick = { selectedOption = index },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) PfColors.royal.copy(alpha = 0.08f) else PfColors.card
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) PfColors.royal else PfColors.border
                                ),
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
                                title = if (currentIndex == loadedQuestions.size - 1) "See my score" else "Next question",
                                enabled = selectedOption != null,
                                onClick = { answerAndAdvance() }
                            )
                        }
                        item {
                            Text(
                                "Questions come from this test on the course website.",
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

/** What-to-expect copy for a locked test, matching the entry-detail lock copy. No question text is included. */
private fun testLockCopy(info: TestEntryInfo?, productName: String?): String {
    val what = if (info?.questionCount != null) {
        "${info.questionCount} questions" +
            (info.domainLabel?.let { " on $it" } ?: "") + ". "
    } else {
        ""
    }
    val bundle = productName ?: "The Integrated Course"
    return "This test belongs to $bundle. $what" +
        "Unlock it to take the test and see your score with explanations, with 1 year of access."
}

@Composable
private fun TestReviewCard(
    question: TestQuizQuestion,
    chosen: Int?,
    onNavigate: (String) -> Unit
) {
    val wasCorrect = chosen == question.correctOptionIndex
    PfCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            Icon(
                if (wasCorrect) Icons.Filled.CheckCircle else Icons.Filled.Close,
                contentDescription = null,
                tint = if (wasCorrect) PfColors.success else PfColors.error
            )
            Text(
                if (wasCorrect) "Correct" else "Not correct",
                style = PfTypography.cardTitle,
                color = if (wasCorrect) PfColors.success else PfColors.error
            )
            question.chapterId?.let { chapterId ->
                ContentRepository.chapterNumberFromId(chapterId)?.let { number ->
                    PfPill(text = ReadingOrder.chapterLabel("ch$number"))
                }
            }
        }
        question.scenario?.let {
            PfBodyText(text = it, color = PfColors.secondaryText)
        }
        Text(question.question, style = PfTypography.body, color = PfColors.navy)
        Text(
            "Correct answer: ${question.options[question.correctOptionIndex]}",
            style = PfTypography.body,
            fontWeight = FontWeight.SemiBold,
            color = PfColors.navy
        )
        if (chosen != null && !wasCorrect && chosen in question.options.indices) {
            Text("Your answer: ${question.options[chosen]}", style = PfTypography.body, color = PfColors.navy)
            val why = question.whyWrong.getOrNull(chosen)?.trim()?.takeIf { it.isNotEmpty() }
            why?.let { PfBodyText(text = it, color = PfColors.secondaryText) }
        }
        if (question.explanation.isNotEmpty()) {
            PfBodyText(text = question.explanation, color = PfColors.secondaryText)
        }
        question.source?.let {
            Text("Source: $it", style = PfTypography.callout, color = PfColors.secondaryText)
        }
        question.chapterId?.let { chapterId ->
            ContentRepository.chapterNumberFromId(chapterId)?.let { number ->
                PfPrimaryButton(title = "Open ${ReadingOrder.chapterLabel("ch$number")}", onClick = {
                    onNavigate(Routes.chapter(number))
                })
            }
        }
    }
}
