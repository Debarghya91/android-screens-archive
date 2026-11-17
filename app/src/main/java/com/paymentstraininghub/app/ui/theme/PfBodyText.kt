package com.paymentstraininghub.app.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.BreakIterator
import java.util.Locale

/**
 * Paragraph splitting ported from iOS PFParagraphs
 * (DesignSystem.swift). Splits long text into readable paragraphs
 * WITHOUT changing any wording. Line breaks mark paragraph
 * boundaries first; a long block is then split into sentence
 * groups (at most three sentences, about 480 characters) so it
 * never renders as one wall of text. Sentence boundaries come
 * from BreakIterator, the JVM equivalent of the system sentence
 * enumeration iOS uses, so abbreviations and decimals do not
 * split sentences.
 */
object PfParagraphs {
    private const val MAX_SENTENCES_PER_PARAGRAPH = 3
    private const val MAX_PARAGRAPH_CHARS = 480

    fun split(text: String): List<String> {
        val blocks = text
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val paragraphs = mutableListOf<String>()
        for (block in blocks) {
            paragraphs.addAll(splitLongBlock(block))
        }
        return paragraphs
    }

    private fun splitLongBlock(block: String): List<String> {
        val sentences = sentencesOf(block)
        if (sentences.size <= MAX_SENTENCES_PER_PARAGRAPH && block.length <= MAX_PARAGRAPH_CHARS) {
            return listOf(block)
        }
        if (sentences.size <= 1) return listOf(block)
        val chunks = mutableListOf<String>()
        var current = ""
        var currentCount = 0
        for (sentence in sentences) {
            if (current.isNotEmpty() &&
                (currentCount >= MAX_SENTENCES_PER_PARAGRAPH ||
                    current.length + 1 + sentence.length > MAX_PARAGRAPH_CHARS)
            ) {
                chunks.add(current)
                current = ""
                currentCount = 0
            }
            current = if (current.isEmpty()) sentence else "$current $sentence"
            currentCount++
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    internal fun sentencesOf(block: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(block)
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = block.substring(start, end).trim()
            if (sentence.isNotEmpty()) sentences.add(sentence)
            start = end
            end = iterator.next()
        }
        return mergeTitleFragments(sentences)
    }

    // BreakIterator splits a bare title ("Dr.") from the sentence it
    // introduces, where the system enumeration iOS uses keeps them
    // together. A sentence that is only a known title abbreviation
    // rejoins the sentence that follows it, so grouping boundaries
    // match iOS. No wording changes: the join is the original text.
    private val titleFragment = Regex(
        "^(Dr|Mr|Mrs|Ms|St|Prof|Sr|Jr|Hon|Rev|Fr)\\.$"
    )

    private fun mergeTitleFragments(sentences: List<String>): List<String> {
        if (sentences.size < 2) return sentences
        val merged = mutableListOf<String>()
        var index = 0
        while (index < sentences.size) {
            val sentence = sentences[index]
            if (titleFragment.matches(sentence) && index + 1 < sentences.size) {
                merged.add("$sentence ${sentences[index + 1]}")
                index += 2
            } else {
                merged.add(sentence)
                index++
            }
        }
        return merged
    }
}

/**
 * Renders long text as logical paragraphs, ported from iOS
 * PFBodyText: one Text per paragraph, md paragraph spacing, and
 * 4sp of extra line spacing. Defaults match the iOS body text
 * (body style, navy). Pass a style or color to match a surface
 * that already uses different values, for example a callout quiz
 * explanation or a secondary description. Empty text renders
 * nothing. Short text renders as a single Text, exactly as
 * before, so adopting this renderer is safe for short copy too.
 */
@Composable
fun PfBodyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PfTextStyles.body,
    color: Color = PfColors.navy,
    lineSpacing: TextUnit = 4.sp,
    textAlign: TextAlign? = null
) {
    val paragraphs = remember(text) { PfParagraphs.split(text) }
    if (paragraphs.isEmpty()) return
    val lineHeight = when {
        style.lineHeight != TextUnit.Unspecified ->
            (style.lineHeight.value + lineSpacing.value).sp
        style.fontSize != TextUnit.Unspecified ->
            (style.fontSize.value + lineSpacing.value).sp
        else -> 26.sp
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.md)
    ) {
        for (paragraph in paragraphs) {
            Text(
                text = paragraph,
                style = style.copy(color = color, lineHeight = lineHeight),
                textAlign = textAlign
            )
        }
    }
}
