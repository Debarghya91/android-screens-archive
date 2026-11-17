// Payment lifecycle simulator. Guided mode steps through the 14 stages in
// order; Investigation mode allows free navigation and injected faults.
// Session only: leaving the screen discards the run. Simplified model copy
// is permanent on this screen. Gating: the first 2 stages are the free
// samples; the rest of the run needs the Payment Simulator (or the
// Integrated Course). No em dashes.
package com.paymentstraininghub.app.features.simulator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.FreeSampleRules
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.ui.enumSaver
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.nullableEnumSaver
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

private enum class SimulatorMode(val title: String, val description: String) {
    GUIDED(
        "Guided",
        "Step through the stages in order, one at a time."
    ),
    INVESTIGATION(
        "Investigation",
        "Move freely between stages and inject a fault to see what breaks."
    )
}

/** Saver for the selected sample payment, stored by its catalogue id. Covers the state object. */
private val samplePaymentSaver: Saver<MutableState<SamplePayment>, String> = Saver(
    save = { it.value.id },
    restore = { id ->
        mutableStateOf(
            SamplePaymentCatalogue.samples.firstOrNull { it.id == id }
                ?: SamplePaymentCatalogue.defaultSample
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(onNavigate: ((String) -> Unit)? = null) {
    // Collected so the gate refreshes when entitlements load or
    // change, and when the premium gate resolves.
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    val simulatorUnlocked = remember(ownedIds, serverIds, gateState) {
        runCatching { BillingManager.isSimulatorUnlocked() }.getOrDefault(false)
    }
    // Free samples: the first 2 stages, in order. Later stages
    // need the Payment Simulator.
    val freeStageCount = FreeSampleRules.SAMPLES_PER_PRODUCT
    var mode by rememberSaveable(saver = enumSaver(SimulatorMode.entries.toTypedArray())) {
        mutableStateOf(SimulatorMode.GUIDED)
    }
    var sample by rememberSaveable(saver = samplePaymentSaver) {
        mutableStateOf(SamplePaymentCatalogue.defaultSample)
    }
    var journeyVersion by remember { mutableIntStateOf(0) }
    val journey = remember { LifecycleJourney() }
    var currentNumber by rememberSaveable { mutableIntStateOf(1) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var faultToInject by rememberSaveable(
        saver = nullableEnumSaver(LifecycleFault.entries.toTypedArray())
    ) { mutableStateOf<LifecycleFault?>(null) }

    fun refresh() {
        journeyVersion++
    }
    // Reading journeyVersion here subscribes this composition to run changes:
    // LifecycleJourney is a plain session model, so the counter is the
    // recomposition trigger after visit/inject/clear/end actions.
    val runVersion = journeyVersion

    val stages = LifecycleStages.ordered
    val current = LifecycleStages.stage(currentNumber) ?: stages.first()
    val injection = journey.injection
    val blocked = injection != null && current.number > injection.stage.number
    // The stage content locks past the free samples, in either
    // mode, until the Simulator is unlocked.
    val stageLocked = !simulatorUnlocked && current.number > freeStageCount

    // Keep the current stage visited as the learner moves.
    LaunchedEffect(currentNumber, runVersion) {
        if (current !in journey.visitedStages) {
            journey.visit(current)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState(mode, sample, currentNumber, showSummary))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Payment lifecycle simulator", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Simplified model. Real architectures vary by bank, scheme, and rail.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SimulatorMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    label = { Text(entry.title) }
                )
            }
        }
        PfBodyText(
            text = mode.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample payment", style = MaterialTheme.typography.titleMedium)
                SamplePaymentCatalogue.samples.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = sample == option,
                                onClick = { sample = option },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sample == option, onClick = { sample = option })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "SAMPLE: ${sample.payerName} pays ${sample.payeeName}, ${sample.amountText}. " +
                        "Reference ${sample.paymentReference}. ${sample.purpose}. Channel: ${sample.channel}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Payer account ${sample.payerAccountReference}. Payee account ${sample.payeeAccountReference}. " +
                        "All names and references are fictional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (mode == SimulatorMode.GUIDED) {
            Text(
                "Guided run: stage ${current.number} of ${stages.size}. " +
                    "${journey.visitedStages.size} of ${stages.size} stages visited.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                progress = journey.visitedStages.size.toFloat() / stages.size.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (!simulatorUnlocked) {
            SimulatorLockedCard(
                text = "Investigation mode needs the Payment Simulator. The first 2 guided stages are free.",
                onNavigate = onNavigate,
                onBackToGuided = { mode = SimulatorMode.GUIDED }
            )
        } else {
            Text("Investigation run", style = MaterialTheme.typography.titleMedium)
            StagePicker(
                stages = stages,
                current = current,
                visited = journey.visitedStages,
                blockedStages = journey.blockedStages,
                onSelect = { currentNumber = it.number }
            )
        }

        if (injection != null) {
            FaultCard(
                injection = injection,
                onClear = {
                    journey.clearInjection()
                    refresh()
                }
            )
        }

        if (stageLocked && mode == SimulatorMode.GUIDED) {
            SimulatorLockedCard(
                text = "The first $freeStageCount stages are free. Unlock the Payment Simulator to run the rest of the payment.",
                onNavigate = onNavigate,
                onBackToGuided = null
            )
        } else if (mode == SimulatorMode.GUIDED || simulatorUnlocked) {
            StageDetailCard(stage = current, blocked = blocked)
        }

        if (mode == SimulatorMode.INVESTIGATION && injection == null && simulatorUnlocked) {
            FaultPickerCard(
                stage = current,
                onInject = { faultToInject = it }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == SimulatorMode.GUIDED) {
                OutlinedButton(
                    enabled = current.number > 1,
                    onClick = { currentNumber -= 1 }
                ) { Text("Previous stage") }
                val next = LifecycleStages.nextOf(current)
                val blockedByFault = injection != null &&
                    next != null && next.number > injection.stage.number
                val blockedByGate = !simulatorUnlocked && next != null && next.number > freeStageCount
                Button(
                    enabled = next != null && !blockedByFault && !blockedByGate,
                    onClick = {
                        if (next != null) {
                            currentNumber = next.number
                            journey.visit(next)
                            if (next.number == stages.size) journey.markReachedEnd()
                            refresh()
                        }
                    }
                ) { Text("Next stage") }
            }
            OutlinedButton(
                onClick = {
                    journey.markEndedEarly()
                    showSummary = true
                    refresh()
                }
            ) { Text("End run") }
            TextButton(
                onClick = {
                    journey.reset()
                    currentNumber = 1
                    showSummary = false
                    refresh()
                }
            ) { Text("Start again") }
        }

        if (journey.hasEnding) {
            Button(onClick = { showSummary = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Journey summary")
            }
        }

        Text(
            "Simplified model. Real architectures vary by bank, scheme, and rail. " +
                "Nothing on this screen is a real payment, a real account, or a real bank rule.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    faultToInject?.let { fault ->
        AlertDialog(
            onDismissRequest = { faultToInject = null },
            title = { Text("Inject fault: ${fault.title}") },
            text = {
                PfBodyText(
                    text = "${fault.description}\n\nEffect: ${fault.effect.title}. " +
                        fault.effect.downstreamWording,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        journey.inject(fault, current)
                        faultToInject = null
                        refresh()
                    }
                ) { Text("Inject") }
            },
            dismissButton = {
                TextButton(onClick = { faultToInject = null }) { Text("Cancel") }
            }
        )
    }

    if (showSummary) {
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text(journey.outcome.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(journey.outcome.detail)
                    val injectionSummary = journey.injection
                    if (injectionSummary != null) {
                        Text(
                            "Fault: ${injectionSummary.fault.title} at stage " +
                                "${injectionSummary.stage.number}, ${injectionSummary.stage.title}.",
                            fontWeight = FontWeight.Medium
                        )
                        Text(injectionSummary.fault.learnerNote)
                    }
                    Text(
                        "Visited: " + journey.visitedStages.joinToString(", ") {
                            "${it.number}. ${it.title}"
                        }
                    )
                    if (journey.blockedStages.isNotEmpty()) {
                        Text(
                            "Did not run: " + journey.blockedStages.joinToString(", ") {
                                "${it.number}. ${it.title}"
                            }
                        )
                    }
                    if (journey.unvisitedStages.isNotEmpty()) {
                        Text(
                            "Not visited: " + journey.unvisitedStages.joinToString(", ") {
                                "${it.number}. ${it.title}"
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSummary = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SimulatorLockedCard(
    text: String,
    onNavigate: ((String) -> Unit)?,
    onBackToGuided: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unlock the Payment Simulator", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            val navigate = onNavigate
            if (navigate != null) {
                Button(
                    onClick = { navigate(Routes.checkout(ProductCatalogue.simulatorProductId)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("See the Payment Simulator") }
            }
            if (onBackToGuided != null) {
                OutlinedButton(
                    onClick = onBackToGuided,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back to the free guided stages") }
            }
        }
    }
}

@Composable
private fun StagePicker(
    stages: List<LifecycleStage>,
    current: LifecycleStage,
    visited: List<LifecycleStage>,
    blockedStages: List<LifecycleStage>,
    onSelect: (LifecycleStage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stages.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stage ->
                    val blocked = stage in blockedStages
                    AssistChip(
                        onClick = { onSelect(stage) },
                        enabled = !blocked,
                        label = {
                            Text(
                                "${stage.number}. ${stage.title}" +
                                    if (stage in visited) " (visited)" else ""
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FaultCard(injection: LifecycleFaultInjection, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Fault injected: ${injection.fault.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            PfBodyText(
                text = injection.fault.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("Effect: ${injection.fault.effect.title}. ${injection.fault.effect.downstreamWording}")
            Text("What the operator does next:", fontWeight = FontWeight.Medium)
            injection.fault.operatorNextSteps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
            Text(injection.fault.learnerNote, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onClear) { Text("Clear fault and keep exploring") }
        }
    }
}

@Composable
private fun FaultPickerCard(stage: LifecycleStage, onInject: (LifecycleFault) -> Unit) {
    val applicable = LifecycleFault.entries.filter { it.canInjectAt(stage) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Inject a fault at this stage", style = MaterialTheme.typography.titleMedium)
            if (applicable.isEmpty()) {
                Text(
                    "No fault applies at this stage in this model. Faults can only be " +
                        "injected at a stage where they make sense.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                applicable.forEach { fault ->
                    OutlinedButton(
                        onClick = { onInject(fault) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(fault.title) }
                }
            }
        }
    }
}

@Composable
private fun StageDetailCard(stage: LifecycleStage, blocked: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Stage ${stage.number} of ${LifecycleStages.ordered.size}: ${stage.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (blocked) {
                Text(
                    "This stage does not run in this run because an injected fault stopped or held the payment earlier.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(stage.explanation, style = MaterialTheme.typography.bodyMedium)
            StageRow("What comes in", stage.incomingEvent)
            StageRow("What this stage does", stage.processingComponent)
            StageRow("State change", stage.stateTransition)
            StageRow("What changes in the data", stage.dataTransformation)
            StageRow("What goes out", stage.outgoingEvent)
            StageRow("Expected result", stage.expectedResult)
            StageRow("If it fails", stage.failureOutcome)
        }
    }
}

@Composable
private fun StageRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
