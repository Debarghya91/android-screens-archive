package com.paymentstraininghub.app.ui.lesson

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.model.LessonBeat
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing

/**
 * The beats of a lesson as a vertical flow, with the beat matching the
 * narration highlighted in gold.
 *
 * The v58 rule: the markers are PLAIN DOTS, not numbers. The beats are
 * the lesson's points, not a sequence, so no numbers appear anywhere in
 * this flow. Flow lessons get arrows between the points, showing the
 * direction of the flow; other lessons get a plain rail. The website's
 * flow-diagram SVG is never rendered here: the native flow is the only
 * visual. No WebView is used anywhere in this file.
 *
 * Rows are tappable when [onBeatTap] is supplied and
 * [beatsTappable] is true: tapping a beat seeks the audio to it.
 * Supplemental lessons pass beatsTappable false: their beats are
 * untimed, so the rows do not respond and render dimmed, with a
 * no-timing note from the caller, instead of silently doing
 * nothing (Tester 2 A2). The current and selected beats are
 * marked with text as well as colour, never colour alone.
 */
@Composable
fun BeatsFlowContent(
    beats: List<LessonBeat>,
    currentBeatIndex: Int?,
    showsFlowArrows: Boolean,
    selectedBeatId: String? = null,
    beatsTappable: Boolean = true,
    onBeatTap: (LessonBeat) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PfSpacing.medium)
            .semantics(mergeDescendants = false) {
                contentDescription = "Lesson flow, ${beats.size} points"
            }
    ) {
        beats.forEachIndexed { index, beat ->
            BeatRow(
                beat = beat,
                isCurrent = index == currentBeatIndex,
                isSelected = beat.id == selectedBeatId,
                isLast = index == beats.lastIndex,
                showsFlowArrows = showsFlowArrows,
                beatsTappable = beatsTappable,
                onBeatTap = onBeatTap
            )
        }
    }
}

@Composable
private fun BeatRow(
    beat: LessonBeat,
    isCurrent: Boolean,
    isSelected: Boolean,
    isLast: Boolean,
    showsFlowArrows: Boolean,
    beatsTappable: Boolean,
    onBeatTap: (LessonBeat) -> Unit
) {
    val labelParts = buildList {
        add(beat.title)
        if (beat.note.isNotBlank()) add(beat.note)
        if (isCurrent) add("Now playing")
        if (isSelected) add("Selected")
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(
            if (isCurrent) PfColors.royal.copy(alpha = 0.06f) else PfColors.card
        )
        .then(
            if (isSelected) {
                Modifier.border(
                    width = 1.dp,
                    color = PfColors.gold,
                    shape = RoundedCornerShape(14.dp)
                )
            } else {
                Modifier
            }
        )
        .clickable(enabled = beatsTappable) { onBeatTap(beat) }
        .semantics(mergeDescendants = true) {
            contentDescription = labelParts.joinToString(". ")
        }
        .padding(vertical = 2.dp)

    Row(
        modifier = rowModifier.alpha(if (beatsTappable) 1f else 0.6f),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier
                .width(20.dp)
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) PfColors.gold else PfColors.navy)
            )
            if (!isLast) {
                if (showsFlowArrows) {
                    Box(
                        modifier = Modifier.height(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = PfColors.royal.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(22.dp)
                            .background(PfColors.royal.copy(alpha = 0.35f))
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = PfSpacing.medium)
                .padding(bottom = if (isLast) 0.dp else PfSpacing.small)
        ) {
            Text(
                text = beat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.navy
            )
            if (beat.note.isNotBlank()) {
                Text(
                    text = beat.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText,
                    modifier = Modifier.padding(top = PfSpacing.xSmall)
                )
            }
            if (isCurrent) {
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal,
                    modifier = Modifier.padding(top = PfSpacing.xSmall)
                )
            }
            if (isSelected) {
                Text(
                    text = "Selected point",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal,
                    modifier = Modifier.padding(top = PfSpacing.xSmall)
                )
            }
        }
    }
}
