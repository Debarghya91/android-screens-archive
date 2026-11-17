// DailyChallengeScreen.kt
// Daily Challenge: one real situation per day, ported from iOS
// DailyChallengeView.swift. The situation is picked
// deterministically by the calendar day, the options are real
// takeaways, and completion is tracked locally, one per day.
// The v70 entitlement gate applies before pick and display.
// No em dashes in any user-facing text.
//
// One deliberate fix over iOS: on revisiting a completed day,
// iOS leaves the answer unselected, so the result card reads
// "Not this time" even for a correct answer. Here the stored
// correctness decides the result text when nothing is selected
// this visit; first-answer behavior is identical.

package com.paymentstraininghub.app.features.challenge

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.schemeForSituation
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseSelector
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyChallengeScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val situationsUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        BillingManager.isSituationsUnlocked()
    }

    val today = remember { LocalDate.now() }
    val dayKey = remember(today) { DailyChallenge.dayKey(today) }
    val dayNumber = remember(today) { DailyChallenge.dayNumber(today) }

    var situations by remember { mutableStateOf<List<Situation>?>(null) }
    var weeklyCases by remember { mutableStateOf<List<Situation>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var answered by remember { mutableStateOf(false) }
    var wasCorrectToday by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dayKey) {
        val completed = runCatching {
            AppStore.getBoolean("pf.dailyChallenge.completed.$dayKey.v1")
        }.getOrNull() ?: false
        val correct = runCatching {
            AppStore.getBoolean("pf.dailyChallenge.correct.$dayKey.v1")
        }.getOrNull() ?: false
        wasCorrectToday = correct
        if (completed) answered = true
        loading = true
        try {
            situations = ContentRepository.situations()
            weeklyCases = ContentRepository.weeklyCasesOrNull()
        } catch (e: Exception) {
            situations = emptyList()
        } finally {
            loading = false
        }
    }

    // This week's free case id, picked exactly the way the
    // weekly view picks it: the dedicated 52-case weekly list
    // wins when it has loaded, otherwise the catalogue fallback.
    val weeklyCaseId: String? = remember(situations, weeklyCases) {
        val weekly = weeklyCases
        if (!weekly.isNullOrEmpty()) {
            WeeklyFreeCaseSelector.situationId(weekly.map { it.id }, today)
        } else {
            situations?.let { WeeklyFreeCaseSelector.situationId(it.map { s -> s.id }, today) }
        }
    }

    val pool = remember(situations, weeklyCases, situationsUnlocked, weeklyCaseId) {
        val source = (situations ?: emptyList()).toMutableList()
        if (!situationsUnlocked) {
            val weekly = weeklyCases?.firstOrNull { it.id == weeklyCaseId }
            if (weekly != null && source.none { it.id == weekly.id }) source.add(weekly)
        }
        DailyChallenge.eligiblePool(source, situationsUnlocked, weeklyCaseId)
    }

    val todaysSituation = remember(pool, dayNumber) {
        DailyChallenge.todaysSituation(pool, dayNumber)
    }
    val options = remember(pool, todaysSituation, dayNumber) {
        todaysSituation?.let { DailyChallenge.options(pool, it, dayNumber) } ?: emptyList()
    }
    val scenarioText = remember(todaysSituation) {
        todaysSituation?.let { DailyChallenge.scenarioText(it) } ?: ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Daily Challenge", color = PfColors.navy) },
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
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xl)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                Text(
                    "One real case, 3 to 5 minutes",
                    style = MaterialTheme.typography.titleLarge,
                    color = PfColors.navy
                )
                Text(
                    if (situationsUnlocked) {
                        "A new situation every day, the same one for every learner. Read the case, pick the key lesson, then check yourself against the situation's own takeaway."
                    } else {
                        "A new free case every day, drawn from the free samples and this week's free case. Read the case, pick the key lesson, then check yourself against the situation's own takeaway."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
                if (answered) {
                    PfPill(
                        text = if (wasCorrectToday) "Completed today, correct" else "Completed today",
                        background = PfColors.success.copy(alpha = 0.12f),
                        foreground = PfColors.success
                    )
                }
            }

            when {
                loading -> {
                    PfLoadingView(message = "Loading today's challenge...")
                }
                todaysSituation != null -> {
                    val situation = todaysSituation
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                PfPill(text = situation.stageName)
                                PfPill(
                                    text = situation.levelDisplayName,
                                    background = PfColors.goldSoft,
                                    foreground = PfColors.goldText
                                )
                                schemeForSituation(situation)?.let { scheme ->
                                    PfPill(text = scheme)
                                }
                            }
                            Text(
                                situation.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = PfColors.navy
                            )
                            PfBodyText(text = scenarioText)
                        }
                    }

                    Text(
                        "What is the key lesson here?",
                        style = MaterialTheme.typography.titleMedium,
                        color = PfColors.navy
                    )

                    options.forEachIndexed { index, option ->
                        val isSelected = selectedOption == index
                        val borderColor = when {
                            answered && option.isCorrect -> PfColors.success
                            answered && isSelected -> PfColors.error
                            isSelected -> PfColors.royal
                            else -> PfColors.border
                        }
                        Card(
                            onClick = { if (!answered) selectedOption = index },
                            enabled = !answered,
                            colors = CardDefaults.cardColors(containerColor = PfColors.card),
                            border = BorderStroke(
                                if (isSelected || (answered && option.isCorrect)) 2.dp else 1.dp,
                                borderColor
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
                                    when {
                                        answered && option.isCorrect -> Icons.Filled.CheckCircle
                                        answered && isSelected -> Icons.Filled.HighlightOff
                                        isSelected -> Icons.Filled.CheckCircle
                                        else -> Icons.Filled.RadioButtonUnchecked
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        answered && option.isCorrect -> PfColors.success
                                        answered && isSelected -> PfColors.error
                                        isSelected -> PfColors.royal
                                        else -> PfColors.secondaryText
                                    }
                                )
                                Text(
                                    option.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.navy,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (!answered) {
                        PfPrimaryButton(
                            title = "Check my answer",
                            enabled = selectedOption != null,
                            onClick = {
                                val selected = selectedOption ?: return@PfPrimaryButton
                                answered = true
                                val correct = options[selected].isCorrect
                                wasCorrectToday = correct
                                scope.launch {
                                    runCatching {
                                        AppStore.putBoolean(
                                            "pf.dailyChallenge.completed.$dayKey.v1", true
                                        )
                                        AppStore.putBoolean(
                                            "pf.dailyChallenge.correct.$dayKey.v1", correct
                                        )
                                    }
                                }
                            }
                        )
                    }

                    if (answered) {
                        val correct = selectedOption?.let { options[it].isCorrect } ?: wasCorrectToday
                        PfCard {
                            Column(
                                modifier = Modifier,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Text(
                                    if (correct) {
                                        "Correct. That is the key lesson of this situation."
                                    } else {
                                        "Not this time. Here is the situation's own takeaway."
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PfColors.navy
                                )
                                PfBodyText(text = situation.takeaway)
                                // Opened without the weekly-path flag on
                                // purpose: the gate treats the weekly
                                // case as free only on the weekly path,
                                // and any other path stays gated.
                                androidx.compose.material3.TextButton(
                                    onClick = { onNavigate(Routes.situation(situation.id)) }
                                ) {
                                    Text(
                                        "Open the full situation",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.royal
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        "Today's challenge is not available yet. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            PfBottomInset()
        }
    }
}
