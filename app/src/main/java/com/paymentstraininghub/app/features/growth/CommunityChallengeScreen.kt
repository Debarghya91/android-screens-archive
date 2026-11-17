// Weekly community challenge: one lab case per week, the lab's own scoring,
// an explanation after completion, a personal best, and an honest empty
// leaderboard. Gating follows the lab's existing rule. D101 PENDING: the
// challenge follows the lab side of the situations-detail inconsistency.
// No em dashes.
package com.paymentstraininghub.app.features.growth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.features.analytics.AnalyticsEvent
import com.paymentstraininghub.app.features.analytics.AnalyticsService
import com.paymentstraininghub.app.features.lab.ExceptionLabStore
import com.paymentstraininghub.app.features.lab.LabCasePlayer
import com.paymentstraininghub.app.features.lab.LabGating
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@Composable
fun CommunityChallengeScreen() {
    val context = LocalContext.current
    val weekKey = CommunityChallenge.weekKey()
    val weekLabel = CommunityChallenge.weekLabel()
    val weeklyCase = CommunityChallenge.caseFor()

    var situationsUnlocked by remember { mutableStateOf(false) }
    // Collected so the unlock flag refreshes when entitlements load
    // or change, and when the premium gate resolves.
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    LaunchedEffect(Unit) {
        AnalyticsService.track(AnalyticsEvent.ChallengeViewed)
    }
    LaunchedEffect(ownedIds, serverIds, gateState, isAdmin) {
        situationsUnlocked = runCatching {
            BillingManager.isSituationsUnlocked()
        }.getOrDefault(false)
    }
    val attempts by CommunityChallengeStore.attempts.collectAsState()
    var playing by remember { mutableStateOf(false) }

    val leaderboard = CommunityChallengeStore.leaderboardForWeekKey(weekKey)
    val personalBest = CommunityChallengeStore.personalBest
    val thisWeekAttempt = CommunityChallengeStore.attemptForWeekKey(weekKey)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (playing && weeklyCase != null) {
            LabCasePlayer(
                case = weeklyCase,
                onCompleted = { breakdown ->
                    ExceptionLabStore.recordCompletion(weeklyCase.id, breakdown)
                    CommunityChallengeStore.recordCompletion(weekKey, weeklyCase.id, breakdown)
                },
                onExit = { playing = false }
            )
            Spacer(modifier = Modifier.height(8.dp))
            return@Column
        }

        Text("Community challenge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "$weekLabel (week $weekKey). One case, the same for everyone this week. A new case appears every Monday.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (weeklyCase == null) {
            Text("This week's challenge is not available yet.")
        } else {
            val open = LabGating.isCaseOpen(weeklyCase, situationsUnlocked)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(weeklyCase.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${weeklyCase.incidentType}. Level: ${weeklyCase.level.displayName}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (thisWeekAttempt != null) {
                        Text(
                            "Your best this week: ${thisWeekAttempt.bestTotal} out of " +
                                "${thisWeekAttempt.bestMaximum}. Completed ${thisWeekAttempt.completions} " +
                                "time${if (thisWeekAttempt.completions == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(
                            onClick = {
                                shareTextViaSheet(
                                    context,
                                    CommunityChallenge.shareText(
                                        weekKey,
                                        weeklyCase.title,
                                        thisWeekAttempt.bestTotal,
                                        thisWeekAttempt.bestMaximum
                                    )
                                )
                            }
                        ) { Text("Share my result") }
                    }
                    if (open) {
                        Button(onClick = { playing = true }) {
                            Text(if (thisWeekAttempt != null) "Play this week's case again" else "Play this week's case")
                        }
                    } else {
                        Text(
                            "Locked. This week's case is grounded in a Payment Situation. " +
                                "Unlock Practice Experiences to play it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Leaderboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!leaderboard.hasCommunityData) {
                    Text(
                        "No community results yet. There is no shared leaderboard in this version: " +
                            "no scores have been collected from other learners, and none are shown. " +
                            "A shared board needs a server that accepts only a week key and a score, " +
                            "with no name or identifier.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (personalBest != null) {
                    val bestCase = CommunityChallenge.exceptionCase(personalBest.caseID)
                    Text(
                        "Your personal best: ${personalBest.bestTotal} out of ${personalBest.bestMaximum}" +
                            if (bestCase != null) " on \"${bestCase.title}\" (week ${personalBest.weekKey})." else " (week ${personalBest.weekKey}).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "You have no challenge results yet. Your results stay on this device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (attempts.isNotEmpty()) {
            Text("Your past weeks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            attempts.sortedByDescending { it.weekKey }.forEach { attempt ->
                val attemptCase = CommunityChallenge.exceptionCase(attempt.caseID)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Week ${attempt.weekKey}: ${attemptCase?.title ?: attempt.caseID}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Best: ${attempt.bestTotal} out of ${attempt.bestMaximum}. " +
                                "Completed ${attempt.completions} time${if (attempt.completions == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
