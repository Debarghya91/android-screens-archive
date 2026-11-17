// DiagnosticAssessment.kt
// The onboarding quick check, ported from
// DiagnosticAssessmentView.swift: up to 6 real quiz questions
// from the first lessons of chapters 69, 1, 4, 2 and 3, trying at
// most 12 lessons. No timer and no pass mark; it only sets the
// starting point, and it can be skipped on its own. When no quiz
// questions can be loaded, the learner rates confidence per
// competency dimension instead: a self-assessment, never
// presented as a test score.

package com.paymentstraininghub.app.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class DiagnosticOutcome(
    val score: Double,
    val takenAtMillis: Long,
    val isSelfAssessment: Boolean,
    val questionCount: Int
) {
    companion object {
        /** Quiz score as a fraction correct, null when there were no questions. A score is never computed from zero questions. */
        fun quizScore(correct: Int, total: Int): Double? {
            if (total <= 0 || correct < 0 || correct > total) return null
            return correct.toDouble() / total.toDouble()
        }

        /**
         * Self-assessment score from confidence ratings of 1 to 5,
         * one per competency dimension. Each rating maps to a
         * fraction: 1 is 0, 2 is 0.25, 3 is 0.5, 4 is 0.75 and 5
         * is 1. The result is the average across the ratings
         * given. Null when no rating was given, or when any
         * rating is outside 1 to 5.
         */
        fun selfAssessmentScore(ratings: List<Int>): Double? {
            if (ratings.isEmpty()) return null
            if (ratings.any { it !in 1..5 }) return null
            val fractions = ratings.map { (it - 1).toDouble() / 4.0 }
            return fractions.sum() / fractions.size.toDouble()
        }
    }
}

private data class AssessmentQuestion(
    val id: String,
    val lessonId: String,
    val lessonTitle: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String
)

private enum class DiagnosticPhase { LOADING, QUIZ, SELF_ASSESSMENT, RESULT }

private val SOURCE_CHAPTER_NUMBERS = listOf(69, 1, 4, 2, 3)
private const val MAX_QUESTIONS = 6
private const val MAX_LESSONS_TRIED = 12

private val CONFIDENCE_LABELS = listOf(
    "Not confident",
    "A little confident",
    "Fairly confident",
    "Confident",
    "Very confident"
)

/**
 * The diagnostic step. [onComplete] fires once when the check
 * ends: a non-null outcome carries the score, null means the
 * learner skipped the check.
 */
@Composable
fun DiagnosticAssessment(onComplete: (DiagnosticOutcome?) -> Unit) {
    var phase by remember { mutableStateOf(DiagnosticPhase.LOADING) }
    var questions by remember { mutableStateOf<List<AssessmentQuestion>>(emptyList()) }
    var indexLoaded by remember { mutableStateOf<Boolean?>(null) }
    var loadTick by remember { mutableStateOf(0) }
    var currentIndex by remember { mutableStateOf(0) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var answers by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var confidence by remember { mutableStateOf<Map<CompetencyDimension, Int>>(emptyMap()) }
    var result by remember { mutableStateOf<DiagnosticOutcome?>(null) }

    LaunchedEffect(loadTick) {
        val collected = mutableListOf<AssessmentQuestion>()
        var tried = 0
        val entries = runCatching { ContentRepository.loadSearchIndex().entries }
            .getOrElse { emptyList() }
        indexLoaded = entries.isNotEmpty()
        outer@ for (number in SOURCE_CHAPTER_NUMBERS) {
            val chapterLessons = entries.filter { entry ->
                entry.isLesson &&
                    ContentRepository.chapterNumberFromId(entry.id) == number
            }
            for (entry in chapterLessons) {
                if (collected.size >= MAX_QUESTIONS || tried >= MAX_LESSONS_TRIED) break@outer
                tried += 1
                val detail = runCatching { ContentRepository.lessonDetail(entry.id) }
                    .getOrNull() ?: continue
                for (quiz in detail.quiz) {
                    if (collected.size >= MAX_QUESTIONS) break
                    if (quiz.options.size <= 1) continue
                    if (quiz.correctOptionIndex < 0 ||
                        quiz.correctOptionIndex >= quiz.options.size
                    ) continue
                    collected.add(
                        AssessmentQuestion(
                            id = quiz.id,
                            lessonId = entry.id,
                            lessonTitle = entry.title,
                            question = quiz.question,
                            options = quiz.options,
                            correctOptionIndex = quiz.correctOptionIndex,
                            explanation = quiz.explanation
                        )
                    )
                }
            }
        }
        questions = collected
        phase = when {
            collected.isNotEmpty() -> DiagnosticPhase.QUIZ
            entries.isNotEmpty() -> DiagnosticPhase.SELF_ASSESSMENT
            else -> DiagnosticPhase.LOADING
        }
    }

    fun finishQuiz() {
        val correct = questions.count { answers[it.id] == it.correctOptionIndex }
        val score = DiagnosticOutcome.quizScore(correct, questions.size)
        if (score == null) {
            onComplete(null)
            return
        }
        result = DiagnosticOutcome(
            score = score,
            takenAtMillis = System.currentTimeMillis(),
            isSelfAssessment = false,
            questionCount = questions.size
        )
        phase = DiagnosticPhase.RESULT
    }

    fun finishSelfAssessment() {
        val ratings = CompetencyDimension.entries.mapNotNull { confidence[it] }
        val score = DiagnosticOutcome.selfAssessmentScore(ratings) ?: return
        result = DiagnosticOutcome(
            score = score,
            takenAtMillis = System.currentTimeMillis(),
            isSelfAssessment = true,
            questionCount = 0
        )
        phase = DiagnosticPhase.RESULT
    }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)) {
        PfCard {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                Text(
                    "Quick check: about 3 minutes",
                    style = PfTextStyles.cardTitle,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.navy
                )
                Text(
                    "Up to $MAX_QUESTIONS questions from real lesson quizzes. There is no timer and no pass mark. This only sets your starting point, and you can skip it.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            }
        }
        when (phase) {
            DiagnosticPhase.LOADING -> {
                PfCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        CircularProgressIndicator(color = PfColors.royal)
                        Text(
                            if (indexLoaded != true) {
                                "Loading the course list, then the quiz questions..."
                            } else {
                                "Loading real quiz questions..."
                            },
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    }
                }
                PfSecondaryButton(title = "Try again", onClick = { loadTick += 1 })
                PfSecondaryButton(title = "Skip this check", onClick = { onComplete(null) })
            }
            DiagnosticPhase.QUIZ -> {
                val question = questions.getOrNull(currentIndex)
                if (question != null) {
                    Text(
                        "Question ${currentIndex + 1} of ${questions.size}",
                        style = PfTextStyles.captionBold,
                        color = PfColors.secondaryText
                    )
                    PfProgressBar(
                        progress = currentIndex.toFloat() /
                            maxOf(questions.size, 1).toFloat()
                    )
                    Text(
                        question.question,
                        style = PfTextStyles.sectionTitle,
                        color = PfColors.navy
                    )
                    question.options.forEachIndexed { index, option ->
                        DiagnosticOptionRow(
                            text = option,
                            isSelected = selectedOption == index,
                            onClick = { selectedOption = index }
                        )
                    }
                    Text(
                        "From the lesson: ${question.lessonTitle}",
                        style = PfTextStyles.caption,
                        color = PfColors.secondaryText
                    )
                }
                PfPrimaryButton(
                    title = if (currentIndex == questions.size - 1) {
                        "See my starting point"
                    } else {
                        "Next question"
                    },
                    onClick = {
                        val current = questions.getOrNull(currentIndex) ?: return@PfPrimaryButton
                        val selected = selectedOption ?: return@PfPrimaryButton
                        answers = answers + (current.id to selected)
                        selectedOption = null
                        if (currentIndex == questions.size - 1) {
                            finishQuiz()
                        } else {
                            currentIndex += 1
                        }
                    },
                    enabled = selectedOption != null
                )
                PfSecondaryButton(title = "Skip this check", onClick = { onComplete(null) })
            }
            DiagnosticPhase.SELF_ASSESSMENT -> {
                Text(
                    "Self-assessment, not a test",
                    style = PfTextStyles.sectionTitle,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.navy
                )
                Text(
                    "No quiz questions could be loaded, so there is no test to take. Rate how confident you feel in each area instead. Your ratings are your own view, not a test score.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                CompetencyDimension.entries.forEach { dimension ->
                    val rating = confidence[dimension]
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        Text(
                            dimension.label,
                            style = PfTextStyles.body,
                            fontWeight = FontWeight.SemiBold,
                            color = PfColors.navy
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            (1..5).forEach { value ->
                                val isSelected = rating == value
                                Text(
                                    "$value",
                                    style = PfTextStyles.body,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) PfColors.card else PfColors.navy,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(PfSpacing.smallRadius))
                                        .background(
                                            if (isSelected) PfColors.royal else PfColors.card
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) PfColors.royal else PfColors.border,
                                            shape = RoundedCornerShape(PfSpacing.smallRadius)
                                        )
                                        .clickable {
                                            confidence = confidence + (dimension to value)
                                        }
                                        .padding(
                                            horizontal = PfSpacing.md,
                                            vertical = PfSpacing.small
                                        )
                                )
                            }
                        }
                        Text(
                            if (rating != null) CONFIDENCE_LABELS[rating - 1]
                            else "Not rated yet",
                            style = PfTextStyles.caption,
                            color = PfColors.secondaryText
                        )
                    }
                }
                // Gated like iOS allDimensionsRated (Tester 2 A5):
                // the button stays disabled with a hint until
                // every dimension is rated, instead of silently
                // returning when ratings are incomplete.
                val allRated = confidence.size == CompetencyDimension.entries.size
                if (!allRated) {
                    Text(
                        "Rate each area to see your starting point: ${confidence.size} of ${CompetencyDimension.entries.size} rated.",
                        style = PfTextStyles.caption,
                        color = PfColors.secondaryText
                    )
                }
                PfPrimaryButton(
                    title = "See my starting point",
                    onClick = { finishSelfAssessment() },
                    enabled = allRated
                )
                PfSecondaryButton(title = "Skip this check", onClick = { onComplete(null) })
            }
            DiagnosticPhase.RESULT -> {
                val outcome = result
                if (outcome != null) {
                    val resultTitle = if (outcome.isSelfAssessment) {
                        "Average confidence: ${(outcome.score * 100).roundToInt()}%"
                    } else {
                        val correct = questions.count {
                            answers[it.id] == it.correctOptionIndex
                        }
                        "$correct of ${questions.size} correct"
                    }
                    val resultDetail = if (outcome.isSelfAssessment) {
                        "This is your own confidence rating, not a test score."
                    } else {
                        val noun = if (outcome.questionCount == 1) "question" else "questions"
                        "${(outcome.score * 100).roundToInt()}% across ${outcome.questionCount} real quiz $noun."
                    }
                    Text(
                        resultTitle,
                        style = PfTextStyles.sectionTitle,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.navy
                    )
                    Text(
                        resultDetail,
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                    Text(
                        "This is a starting point, not a grade. It only helps place you, and it is stored in your onboarding profile.",
                        style = PfTextStyles.caption,
                        color = PfColors.secondaryText
                    )
                    if (!outcome.isSelfAssessment) {
                        Text(
                            "Review",
                            style = PfTextStyles.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                        questions.forEach { question ->
                            val chosen = answers[question.id]
                            val wasCorrect = chosen == question.correctOptionIndex
                            PfCard {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                                ) {
                                    Text(
                                        if (wasCorrect) "Correct" else "Not correct",
                                        style = PfTextStyles.captionBold,
                                        color = if (wasCorrect) PfColors.success else PfColors.error
                                    )
                                    Text(
                                        question.lessonTitle,
                                        style = PfTextStyles.caption,
                                        color = PfColors.secondaryText
                                    )
                                    Text(
                                        question.question,
                                        style = PfTextStyles.body,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PfColors.navy
                                    )
                                    Text(
                                        "Correct answer: ${question.options[question.correctOptionIndex]}",
                                        style = PfTextStyles.body,
                                        color = PfColors.navy
                                    )
                                    if (chosen != null) {
                                        Text(
                                            "Your answer: ${question.options[chosen]}",
                                            style = PfTextStyles.body,
                                            color = PfColors.secondaryText
                                        )
                                    }
                                    PfBodyText(
                                        text = question.explanation,
                                        color = PfColors.secondaryText
                                    )
                                }
                            }
                        }
                    }
                    PfSecondaryButton(
                        title = "Retake the check",
                        onClick = {
                            currentIndex = 0
                            selectedOption = null
                            answers = emptyMap()
                            confidence = emptyMap()
                            result = null
                            loadTick += 1
                            phase = DiagnosticPhase.LOADING
                        }
                    )
                    PfPrimaryButton(title = "Continue", onClick = { onComplete(outcome) })
                }
            }
        }
    }
}

@Composable
private fun DiagnosticOptionRow(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.smallRadius))
            .background(
                if (isSelected) PfColors.royal.copy(alpha = 0.10f) else PfColors.card
            )
            .border(
                width = 1.dp,
                color = if (isSelected) PfColors.royal else PfColors.border,
                shape = RoundedCornerShape(PfSpacing.smallRadius)
            )
            .clickable(onClick = onClick)
            .padding(PfSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        // A checkmark plus the option text when selected, so
        // selection is never shown by colour alone.
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = PfColors.royal)
        }
        Text(text, style = PfTextStyles.body, color = PfColors.navy)
    }
}
