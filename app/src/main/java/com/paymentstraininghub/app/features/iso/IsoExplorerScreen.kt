// ISO 20022 message explorer. Message list, expandable field trees,
// relationships, follow-the-payment journey, mapping workbench, and the
// structural validation problems shared by every message. The scheme-guides
// disclaimer is permanent on this screen. No em dashes.
package com.paymentstraininghub.app.features.iso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.ui.enumSaver
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

private enum class IsoTab(val title: String) {
    MESSAGES("Messages"),
    JOURNEY("Journey"),
    MAPPING("Mapping")
}

const val ISO_SCHEME_DISCLAIMER: String =
    "Mandatory and optional follow the ISO 20022 base schema. Scheme usage " +
        "guides govern what is actually mandatory in a live payment. This " +
        "explorer states no scheme rules."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoExplorerScreen(initialMessageId: String? = null) {
    var tab by rememberSaveable(saver = enumSaver(IsoTab.entries.toTypedArray())) {
        mutableStateOf(IsoTab.MESSAGES)
    }
    var selectedId by rememberSaveable { mutableStateOf(initialMessageId) }
    val selected = selectedId?.let { IsoCatalogue.message(it) }

    // System Back from a message detail returns to the message
    // list, mirroring the on-screen "All messages" button, instead
    // of popping the route.
    BackHandler(enabled = selected != null) { selectedId = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState(tab, selectedId))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ISO 20022 explorer", style = MaterialTheme.typography.headlineSmall)
        Text(
            ISO_SCHEME_DISCLAIMER,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            IsoTab.entries.forEach { entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    label = { Text(entry.title) }
                )
            }
        }

        when (tab) {
            IsoTab.MESSAGES -> {
                if (selected != null) {
                    OutlinedButton(onClick = { selectedId = null }) { Text("All messages") }
                    MessageDetail(
                        message = selected,
                        onOpenRelated = { selectedId = it }
                    )
                } else {
                    IsoFamily.entries.forEach { family ->
                        Text(
                            family.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(family.description, style = MaterialTheme.typography.bodyMedium)
                        IsoCatalogue.messages.filter { it.family == family }.forEach { message ->
                            MessageRow(message = message, onOpen = { selectedId = message.id })
                        }
                    }
                    ValidationProblemsCard()
                }
            }
            IsoTab.JOURNEY -> JourneySection(onOpenMessage = {
                selectedId = it
                tab = IsoTab.MESSAGES
            })
            IsoTab.MAPPING -> MappingSection()
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MessageRow(message: IsoMessage, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${message.isoName}. ${message.allFields().size} elements in the field tree.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageDetail(message: IsoMessage, onOpenRelated: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message.id, style = MaterialTheme.typography.headlineSmall)
        Text(message.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            "${message.isoName}. Root element: ${message.rootElement}.",
            style = MaterialTheme.typography.bodyMedium
        )
        PfBodyText(
            text = message.purpose,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "Family: ${message.family.displayName}.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Field tree", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Expand a block to see its elements. Requirement labels follow the base schema only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message.fields.forEach { field ->
            FieldRow(field = field, depth = 0)
        }

        Text("Sample XML", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Abbreviated sample with fictional values. The namespace declaration and non-essential blocks are omitted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message.sampleXML,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberTopScrollState(message.id))
            )
        }

        if (message.relationships.isNotEmpty()) {
            Text("Related messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            message.relationships.forEach { relationship ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "${relationship.kind}: ${relationship.relatedMessageID}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(relationship.note, style = MaterialTheme.typography.bodyMedium)
                        if (IsoCatalogue.message(relationship.relatedMessageID) != null) {
                            OutlinedButton(onClick = { onOpenRelated(relationship.relatedMessageID) }) {
                                Text("Open ${relationship.relatedMessageID}")
                            }
                        }
                    }
                }
            }
        }
        ValidationProblemsCard()
    }
}

@Composable
private fun FieldRow(field: IsoField, depth: Int) {
    var expanded by remember { mutableStateOf(depth == 0) }
    Column(modifier = Modifier.padding(start = (depth * 12).dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (field.isBlock) expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (field.isBlock) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(field.element, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                PfBodyText(
                    text = field.definition,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // A label, not a control (Tester 2 A3): the old
            // AssistChip had an empty click handler and looked
            // tappable while doing nothing.
            PfPill(text = field.requirement.shortLabel)
        }
        Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            PfBodyText(
                text = field.usageNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            field.exampleValue?.let { example ->
                Text(
                    "Example value: $example",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                field.requirement.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            field.children.forEach { child ->
                FieldRow(field = child, depth = depth + 1)
            }
        }
    }
}

@Composable
private fun ValidationProblemsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Common structural validation problems",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Structural checks only: whether a live payment is accepted also depends on the " +
                    "scheme usage guide and the receiving bank, which this explorer does not cover.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IsoCatalogue.commonProblems.forEach { problem ->
                Text(problem.title, fontWeight = FontWeight.Medium)
                Text(problem.explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun JourneySection(onOpenMessage: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Follow the payment",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "One payment, three messages: pain.001 to pacs.008 to pacs.002, using the same " +
                "fictional values throughout. All values are illustrative.",
            style = MaterialTheme.typography.bodyMedium
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shared values", fontWeight = FontWeight.Medium)
                IsoCatalogue.sharedValues.forEach { value ->
                    Text("${value.label}: ${value.value}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        IsoCatalogue.journeySteps.forEachIndexed { index, step ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Step ${index + 1}: ${step.stepTitle}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Message: ${step.messageID}", style = MaterialTheme.typography.bodyMedium)
                    Text(step.narrative, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Abbreviated sample with fictional values.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = step.sampleXML,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberTopScrollState(step.messageID))
                    )
                    OutlinedButton(onClick = { onOpenMessage(step.messageID) }) {
                        Text("Open ${step.messageID} in the explorer")
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Mapping workbench",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "How the same ideas are carried across pain.001, pacs.008 and pacs.002. " +
                "The mapping is illustrative: scheme usage guides govern what is required in a live payment.",
            style = MaterialTheme.typography.bodyMedium
        )
        IsoCatalogue.mappingRows.forEach { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.concept, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("pain.001: ${row.pain001}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text("pacs.008: ${row.pacs008}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text("pacs.002: ${row.pacs002}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text(row.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
