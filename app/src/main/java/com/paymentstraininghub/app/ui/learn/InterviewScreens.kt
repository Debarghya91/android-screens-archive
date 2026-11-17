// InterviewScreens.kt
// Interview questions, ported from InterviewQuestionsView.swift.
// The bank is bundled with the app (500 questions, 12 categories).
// Categories open to a paged list; tapping a question expands its
// model answer in place. Search matches question and answer text.
// Gating: question titles stay browsable for everyone (discovery),
// but model answers need Interview Ready (or the Integrated
// Course), except the first 5 questions in bank order, which are
// free samples. No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.learn

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.FreeSampleRules
import com.paymentstraininghub.app.data.InterviewBank
import com.paymentstraininghub.app.data.InterviewBankData
import com.paymentstraininghub.app.data.InterviewCategoryItem
import com.paymentstraininghub.app.data.InterviewQuestionItem
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState

private const val INTERVIEW_PAGE_SIZE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewQuestionsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var bank by remember { mutableStateOf<InterviewBankData?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    // Collected so the answer gate refreshes when entitlements
    // load or change, and when the premium gate resolves.
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val interviewUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        runCatching { BillingManager.isInterviewUnlocked() }.getOrDefault(false)
    }
    val freeQuestionIds = remember(bank) {
        FreeSampleRules.freeInterviewQuestionIds(
            bank?.allQuestions?.map { it.id } ?: emptyList()
        ).toSet()
    }

    LaunchedEffect(Unit) {
        bank = InterviewBank.load(context)
        attempted = true
    }

    val isSearching = searchText.trim().isNotEmpty()
    val searchResults = remember(bank, searchText) {
        if (isSearching) bank?.search(searchText) ?: emptyList() else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Interview Questions", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PfSpacing.large, vertical = PfSpacing.small),
            placeholder = { Text("Search interview questions") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )
        when {
            !attempted -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading interview questions...")
                }
            }
            bank == null -> {
                Column(
                    modifier = Modifier.padding(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Text("Interview questions are not available", style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(
                        "The interview questions are stored in the app itself and could not be loaded. This is not a connection problem. Try again, or reinstall the app if the problem continues.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            isSearching -> {
                if (searchResults.isEmpty()) {
                    Column(
                        modifier = Modifier.padding(PfSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        Text("No interview questions match that search", style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(
                            "Try a different word, for example sanctions, reconciliation or stakeholder.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                } else {
                    InterviewQuestionList(
                        questions = searchResults.map { it.second },
                        listKey = searchText,
                        categoryTitleFor = { question ->
                            searchResults.firstOrNull { it.second.id == question.id }?.first?.title
                        },
                        interviewUnlocked = interviewUnlocked,
                        freeQuestionIds = freeQuestionIds,
                        onNavigate = onNavigate
                    )
                }
            }
            else -> {
                val data = bank!!
                LazyColumn(
                    state = rememberTopLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    item {
                        Text(
                            "${data.total} interview questions across ${data.categories.size} categories. Open a category to practise, or search for a question.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        if (!interviewUnlocked) {
                            Text(
                                "The first ${FreeSampleRules.INTERVIEW_FREE_SAMPLES} questions are free. Unlock Interview Ready for model answers to all ${data.total} questions.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    items(data.categories, key = { it.id }) { category ->
                        PfCard(onClick = { onNavigate(Routes.interviewCategory(category.id)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(category.title, style = PfTypography.cardTitle, color = PfColors.navy)
                                    Text(
                                        "${category.questions.size} questions",
                                        style = PfTypography.callout,
                                        color = PfColors.secondaryText
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
                            }
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
fun InterviewCategoryScreen(categoryId: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var bank by remember { mutableStateOf<InterviewBankData?>(null) }
    var attempted by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val interviewUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        runCatching { BillingManager.isInterviewUnlocked() }.getOrDefault(false)
    }
    val freeQuestionIds = remember(bank) {
        FreeSampleRules.freeInterviewQuestionIds(
            bank?.allQuestions?.map { it.id } ?: emptyList()
        ).toSet()
    }

    LaunchedEffect(categoryId) {
        bank = InterviewBank.load(context)
        attempted = true
    }

    val category = bank?.categories?.firstOrNull { it.id == categoryId }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(category?.title ?: "Interview Questions", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        when {
            !attempted -> {
                Column(modifier = Modifier.padding(PfSpacing.large)) {
                    PfLoadingView(message = "Loading interview questions...")
                }
            }
            category == null -> {
                Column(
                    modifier = Modifier.padding(PfSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Text("Interview questions are not available", style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(
                        "The interview questions are stored in the app itself and could not be loaded. This is not a connection problem. Try again, or reinstall the app if the problem continues.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "${category.questions.size} situation-based questions. Tap one to see a model answer.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText,
                        modifier = Modifier.padding(horizontal = PfSpacing.large, vertical = PfSpacing.small)
                    )
                    InterviewQuestionList(
                        questions = category.questions,
                        listKey = categoryId,
                        categoryTitleFor = { category.title },
                        interviewUnlocked = interviewUnlocked,
                        freeQuestionIds = freeQuestionIds,
                        onNavigate = onNavigate
                    )
                }
            }
        }
    }
}

/**
 * One expandable question list: twenty at first, Load more adds
 * twenty. One answer stays open at a time; collapsing happens
 * only when the open question leaves the visible list.
 */
@Composable
private fun InterviewQuestionList(
    questions: List<InterviewQuestionItem>,
    listKey: Any?,
    categoryTitleFor: (InterviewQuestionItem) -> String?,
    interviewUnlocked: Boolean,
    freeQuestionIds: Set<String>,
    onNavigate: (String) -> Unit
) {
    var visibleCount by remember { mutableStateOf(INTERVIEW_PAGE_SIZE) }
    var expandedQuestionId by remember { mutableStateOf<String?>(null) }

    val visibleQuestions = questions.take(visibleCount)

    // Collapse the open answer only when its question left the
    // visible list; a Load more must not close an answer that is
    // still listed.
    LaunchedEffect(visibleQuestions) {
        val openId = expandedQuestionId
        if (openId != null && visibleQuestions.none { it.id == openId }) {
            expandedQuestionId = null
        }
    }

    LazyColumn(
        state = rememberTopLazyListState(listKey),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
    ) {
        items(visibleQuestions, key = { it.id }) { question ->
            val isExpanded = expandedQuestionId == question.id
            val answerLocked = !interviewUnlocked && question.id !in freeQuestionIds
            PfCard(
                onClick = {
                    expandedQuestionId = if (isExpanded) null else question.id
                }
            ) {
                Text(question.question, style = PfTypography.body, color = PfColors.navy)
                categoryTitleFor(question)?.let { PfPill(text = it) }
                if (answerLocked) {
                    PfPill(text = "Locked")
                }
                if (isExpanded) {
                    if (answerLocked) {
                        Text("Model answer locked", style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(
                            "The first ${FreeSampleRules.INTERVIEW_FREE_SAMPLES} questions are free. Unlock Interview Ready for model answers to every question.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        PfSecondaryButton(
                            title = "See Interview Ready",
                            onClick = { onNavigate(Routes.checkout(ProductCatalogue.interviewProductId)) }
                        )
                    } else {
                        Text("Model answer", style = PfTypography.cardTitle, color = PfColors.navy)
                        PfBodyText(text = question.answer, color = PfColors.secondaryText)
                    }
                }
            }
        }
        item {
            Text(
                "Showing ${visibleQuestions.size} of ${questions.size} questions",
                style = PfTypography.caption,
                color = PfColors.secondaryText
            )
        }
        if (questions.size > visibleQuestions.size) {
            item {
                PfSecondaryButton(title = "Load more questions", onClick = {
                    visibleCount += INTERVIEW_PAGE_SIZE
                })
            }
        }
        item { PfBottomInset() }
    }
}
