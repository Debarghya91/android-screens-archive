// Investigation player shared by the lab, the weekly free case and the
// community challenge. Evidence reveals as steps are solved; a step is
// solved only when the best option is picked; wrong picks are recorded as
// wrong turns. Red herring notes appear only in the completed summary.
// No em dashes.
package com.paymentstraininghub.app.features.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.ui.theme.PfBodyText

/**
 * Plays one case. [onCompleted] fires once, when the last step is solved,
 * with the score breakdown. The caller owns recording and navigation.
 */
@Composable
fun LabCasePlayer(
    case: ExceptionCase,
    onCompleted: (ExceptionLabScoreBreakdown) -> Unit,
    onExit: () -> Unit
) {
    var stepIndex by remember(case.id) { mutableStateOf(0) }
    var results by remember(case.id) { mutableStateOf<List<ExceptionStepResult>>(emptyList()) }
    var openedEvidence by remember(case.id) { mutableStateOf<Set<String>>(emptySet()) }
    var pickedOption by remember(case.id, stepIndex) { mutableStateOf<Int?>(null) }
    var wrongPicksThisStep by remember(case.id, stepIndex) { mutableStateOf(0) }
    var redHerringPicksThisStep by remember(case.id, stepIndex) { mutableStateOf(0) }
    var finished by remember(case.id) { mutableStateOf(false) }
    var breakdown by remember(case.id) { mutableStateOf<ExceptionLabScoreBreakdown?>(null) }

    val solvedCount = results.size
    val visibleEvidence = case.evidence.filter { it.revealedAfterStep <= solvedCount }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!finished) {
            Text(case.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${case.incidentType}. Level: ${case.level.displayName}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (case.isPracticeExercise) {
                Text("Practice exercise", style = MaterialTheme.typography.labelLarge)
            }
            PfBodyText(
                text = case.narrative,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (case.messages.isNotEmpty()) {
                Text(
                    "Messages named in this case: ${case.messages.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    "This case names no payment message.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (case.systems.isNotEmpty()) {
                Text(
                    "Systems involved: ${case.systems.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text("Evidence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Open the evidence before you decide. New evidence appears as you solve steps. " +
                    "Opening a red herring earns no points and costs none; acting on one costs more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            visibleEvidence.forEach { item ->
                EvidenceRow(
                    item = item,
                    opened = item.id in openedEvidence,
                    onOpen = { openedEvidence = openedEvidence + item.id }
                )
            }
            val hiddenCount = case.evidence.size - visibleEvidence.size
            if (hiddenCount > 0) {
                Text(
                    "$hiddenCount more evidence item${if (hiddenCount == 1) "" else "s"} will appear as you solve steps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val step = case.steps.getOrNull(stepIndex)
            if (step != null) {
                Text(
                    "Step ${stepIndex + 1} of ${case.steps.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(step.prompt, style = MaterialTheme.typography.bodyMedium)
                step.options.forEachIndexed { optionIndex, option ->
                    val picked = pickedOption == optionIndex
                    val isBest = optionIndex == step.bestOptionIndex
                    OutlinedButton(
                        onClick = {
                            if (pickedOption != null && pickedOption == step.bestOptionIndex) return@OutlinedButton
                            pickedOption = optionIndex
                            if (!isBest) {
                                wrongPicksThisStep++
                                if (option.isRedHerring) redHerringPicksThisStep++
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !(pickedOption != null && pickedOption == step.bestOptionIndex)
                    ) {
                        Text(option.text, modifier = Modifier.fillMaxWidth())
                    }
                    if (picked) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isBest) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(option.feedback, style = MaterialTheme.typography.bodyMedium)
                                if (isBest) {
                                    Text(step.whyCorrect, style = MaterialTheme.typography.bodyMedium)
                                    step.evidenceNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                } else {
                                    Text(
                                        "Cost of this wrong turn: ${step.wrongTurnCost}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
                val stepSolved = pickedOption != null && pickedOption == step.bestOptionIndex
                if (stepSolved) {
                    Button(
                        onClick = {
                            val result = ExceptionStepResult(
                                stepID = step.id,
                                wrongPicks = wrongPicksThisStep,
                                redHerringPicks = redHerringPicksThisStep,
                                solved = true
                            )
                            val updated = results + result
                            results = updated
                            if (stepIndex + 1 >= case.steps.size) {
                                val score = ExceptionLabScoring.breakdown(
                                    forCase = case,
                                    stepResults = updated,
                                    evidenceOpenedIDs = openedEvidence
                                )
                                breakdown = score
                                finished = true
                                onCompleted(score)
                            } else {
                                stepIndex++
                                pickedOption = null
                                wrongPicksThisStep = 0
                                redHerringPicksThisStep = 0
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (stepIndex + 1 >= case.steps.size) "Finish investigation" else "Next step")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExit) { Text("Leave case") }
            }
        } else {
            val score = breakdown
            if (score != null) {
                CaseSummary(case = case, breakdown = score, onExit = onExit)
            }
        }
    }
}

@Composable
private fun EvidenceRow(item: ExceptionEvidence, opened: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, fontWeight = FontWeight.Medium)
            if (opened) {
                Text(item.detail, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Tap to open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CaseSummary(case: ExceptionCase, breakdown: ExceptionLabScoreBreakdown, onExit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Investigation complete", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(case.title, style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Score: ${breakdown.total} out of ${breakdown.maximum}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Best first actions: +${breakdown.bestFirstPoints} (${breakdown.stepsSolvedBestFirst} steps solved on the first pick, ${ExceptionLabScoring.BEST_FIRST_POINTS_PER_STEP} points each)")
                Text("Recovered steps: +${breakdown.recoveredPoints} (${breakdown.stepsSolvedAfterWrongTurn} steps solved after a wrong turn, ${ExceptionLabScoring.RECOVERED_POINTS_PER_STEP} points each)")
                Text("Wrong turns: -${breakdown.wrongTurnDeductions} (${breakdown.totalWrongPicks} wrong picks, ${ExceptionLabScoring.WRONG_PICK_DEDUCTION} points each)")
                Text("Red herring picks: -${breakdown.redHerringDeductions} (${breakdown.totalRedHerringPicks} picks, ${ExceptionLabScoring.RED_HERRING_PICK_DEDUCTION} points each on top of the wrong-turn deduction)")
                Text("Efficiency bonus: +${breakdown.efficiencyBonus}")
                Text("Thoroughness: +${breakdown.thoroughnessPoints} (${breakdown.evidenceOpenedCount} of ${breakdown.evidenceTotalCount} genuine evidence items opened, ${ExceptionLabScoring.THOROUGHNESS_POINTS_PER_EVIDENCE} points each)")
            }
        }
        Text("Resolution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(case.resolution, style = MaterialTheme.typography.bodyMedium)
        case.sourceTakeaway?.let { takeaway ->
            Text("From the source situation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(takeaway, style = MaterialTheme.typography.bodyMedium)
        }
        val herrings = case.evidence.filter { it.isRedHerring && it.redHerringNote != null }
        if (herrings.isNotEmpty()) {
            Text("Red herrings in this case", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            herrings.forEach { item ->
                Text(item.title, fontWeight = FontWeight.Medium)
                Text(item.redHerringNote ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Your lab scores stay on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Back to the lab") }
    }
}
