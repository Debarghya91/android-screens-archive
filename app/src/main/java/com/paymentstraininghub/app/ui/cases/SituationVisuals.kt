package com.paymentstraininghub.app.ui.cases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.model.SituationDiagram
import com.paymentstraininghub.app.data.model.SituationFlow
import com.paymentstraininghub.app.data.model.SituationFlowStep
import com.paymentstraininghub.app.data.model.SituationStacked
import com.paymentstraininghub.app.data.model.SituationVisual
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography

/**
 * Port of the iOS situation visual renderers (SituationsView.swift,
 * `situationVisualSection`, `situationFlowView`, `situationDiagramView`,
 * `situationStackedView`). Three visual kinds: a numbered flow, a
 * centre-and-parts diagram, and a proportional stacked bar. Step
 * kinds follow iOS: "decision" steps render as a gold diamond with a
 * DECISION tag, everything else as a numbered royal circle.
 */

/** The "Visual investigation" header plus the visual for its kind. */
@Composable
fun SituationVisualSection(visual: SituationVisual) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        Text("Visual investigation", style = PfTypography.captionBold)
        when (visual.type) {
            "flow" -> {
                val flow = visual.flow
                if (flow != null) SituationFlowView(flow)
            }
            "diagram" -> {
                val diagram = visual.diagram
                if (diagram != null) SituationDiagramView(diagram)
            }
            "stacked" -> {
                val stacked = visual.stacked
                if (stacked != null) SituationStackedView(stacked)
            }
        }
    }
}

/** Numbered steps with a down-arrow between them, in one card. */
@Composable
fun SituationFlowView(flow: SituationFlow) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            flow.steps.forEachIndexed { index, step ->
                SituationFlowStepRow(index = index, step = step)
                if (index < flow.steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = PfColors.secondaryText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SituationFlowStepRow(index: Int, step: SituationFlowStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
        verticalAlignment = Alignment.Top
    ) {
        if (step.kind == "decision") {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .rotate(45f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PfColors.gold),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = PfTypography.captionBold.copy(color = Color.White),
                    modifier = Modifier.rotate(-45f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(PfColors.royal),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = PfTypography.captionBold.copy(color = Color.White)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (step.kind == "decision") {
                Text(
                    "DECISION",
                    style = PfTypography.captionBold.copy(color = PfColors.goldText)
                )
            }
            Text(step.title, style = PfTypography.cardTitle)
            if (!step.note.isNullOrBlank()) {
                Text(
                    step.note.orEmpty(),
                    style = PfTypography.callout.copy(color = PfColors.secondaryText)
                )
            }
        }
    }
}

/** A gold-bordered centre card with the parts listed below it. */
@Composable
fun SituationDiagramView(diagram: SituationDiagram) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        PfCard(
            modifier = Modifier.border(
                width = 2.dp,
                color = PfColors.gold,
                shape = RoundedCornerShape(PfSpacing.cardRadius)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "MAIN POINT",
                    style = PfTypography.captionBold.copy(color = PfColors.goldText)
                )
                Text(
                    diagram.centre,
                    style = PfTypography.cardTitle.copy(color = PfColors.royal),
                    textAlign = TextAlign.Center
                )
            }
        }
        diagram.parts.forEach { part ->
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(part.label, style = PfTypography.cardTitle)
                    if (!part.note.isNullOrBlank()) {
                        Text(
                            part.note.orEmpty(),
                            style = PfTypography.callout.copy(color = PfColors.secondaryText)
                        )
                    }
                }
            }
        }
    }
}

private val stackedPalette = listOf(
    PfColors.royal,
    PfColors.gold,
    PfColors.navy,
    Color(red = 0.08f, green = 0.50f, blue = 0.24f),
    Color(red = 0.49f, green = 0.23f, blue = 0.93f),
    Color(red = 0.71f, green = 0.33f, blue = 0.04f)
)

/** A proportional bar with a legend row per segment. */
@Composable
fun SituationStackedView(stacked: SituationStacked) {
    val total = stacked.segments.sumOf { maxOf(0.0, it.value) }
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        if (total > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                stacked.segments.forEachIndexed { index, segment ->
                    val fraction = maxOf(0.0, segment.value) / total
                    if (fraction > 0) {
                        Box(
                            modifier = Modifier
                                .weight(fraction.toFloat())
                                .height(28.dp)
                                .background(stackedPalette[index % stackedPalette.size])
                        )
                    }
                }
            }
        }
        stacked.segments.forEachIndexed { index, segment ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(stackedPalette[index % stackedPalette.size])
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(segment.label, style = PfTypography.callout)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            formatStackedValue(segment.value, stacked.unit),
                            style = PfTypography.captionBold.copy(color = PfColors.royal)
                        )
                    }
                    if (!segment.note.isNullOrBlank()) {
                        Text(
                            segment.note.orEmpty(),
                            style = PfTypography.callout.copy(color = PfColors.secondaryText)
                        )
                    }
                }
            }
        }
    }
}

/** Whole numbers print without decimals, others with one decimal, plus the unit. */
internal fun formatStackedValue(value: Double, unit: String?): String {
    val number = if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.1f", value)
    }
    return if (unit.isNullOrBlank()) number else "$number $unit"
}

/** The Flow tab fallback: parsed arrow steps rendered as a flow. */
@Composable
fun FlowStepsAsFlow(steps: List<String>) {
    SituationFlowView(
        SituationFlow(
            stepSeconds = null,
            steps = steps.map { SituationFlowStep(kind = "step", title = it, note = null) }
        )
    )
}

/** A tab section: captionBold navy title above the content, small spacing. */
@Composable
fun SituationTabSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        Text(title, style = PfTypography.captionBold)
        content()
    }
}

/** The full-text fallback used whenever the explain does not parse. */
@Composable
fun FullExplainSection(explain: String) {
    SituationTabSection(title = "Full case text") {
        PfBodyText(explain)
    }
}
