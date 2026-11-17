package com.paymentstraininghub.app.data

/**
 * Port of iOS `parseSituationExplain` (SituationsView.swift), verbatim
 * semantics. The seven-part explain text is split into its structural
 * parts so the detail view can render the Overview, Analysis and Flow
 * tabs. When the text does not follow the format, [ParsedSituationExplain.isParsed]
 * is false and the detail view falls back to the full text, so no
 * information is ever lost or fabricated.
 *
 * The format, in order: an opening, a "Here is what should happen:"
 * flow section whose arrow lines are the scenario steps, a flawed
 * requirement block, an optional "Why?" root-cause block, an optional
 * insight paragraph, a "BA Takeaway" solution block (arrow lines are
 * controls, other paragraphs are the body), an optional "The real
 * requirement" block, and an optional closing question ("Have you" /
 * "Did you know").
 */
data class ParsedSituationExplain(
    val opening: List<String>,
    val flowSteps: List<String>,
    val flawedRequirement: List<String>,
    val rootCause: List<String>,
    val insight: String?,
    val baBody: List<String>,
    val controls: List<String>,
    val realRequirement: List<String>,
    val closingQuestion: String?
) {
    val isParsed: Boolean
        get() = opening.isNotEmpty() && (baBody.isNotEmpty() || controls.isNotEmpty())
}

private fun isFlowHeader(paragraph: String): Boolean =
    paragraph.startsWith("Here") && paragraph.endsWith(":") && paragraph.contains("happen")

private fun isFlawedHeader(paragraph: String): Boolean =
    paragraph.startsWith("The requirement said:") ||
        paragraph.startsWith("The assumption was:") ||
        paragraph.startsWith("The assumption said:") ||
        paragraph.startsWith("The requirement says") ||
        paragraph.startsWith("The requirement is written") ||
        paragraph.startsWith("The instruction was:")

/** Lines starting with "→", with the arrow stripped and the rest trimmed. */
private fun arrowLines(paragraph: String): List<String> =
    paragraph.split("\n")
        .map { it.trim() }
        .filter { it.startsWith("→") }
        .map { it.drop(1).trim() }
        .filter { it.isNotEmpty() }

fun parseSituationExplain(explain: String): ParsedSituationExplain {
    val paragraphs = explain.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    val empty = ParsedSituationExplain(
        opening = emptyList(),
        flowSteps = emptyList(),
        flawedRequirement = emptyList(),
        rootCause = emptyList(),
        insight = null,
        baBody = emptyList(),
        controls = emptyList(),
        realRequirement = emptyList(),
        closingQuestion = null
    )
    if (paragraphs.isEmpty()) return empty

    var bodyEnd = paragraphs.size
    var closingQuestion: String? = null
    val last = paragraphs.last()
    if (last.startsWith("Have you") || last.startsWith("Did you know")) {
        closingQuestion = last
        bodyEnd = paragraphs.size - 1
    }

    val flowIndex = paragraphs.indexOfFirst { isFlowHeader(it) }.takeIf { it >= 0 }
    val flawedIndex = paragraphs.indexOfFirst { isFlawedHeader(it) }.takeIf { it >= 0 }
    val whyIndex = paragraphs.indexOfFirst { it == "Why?" }.takeIf { it >= 0 }
    val baIndex = paragraphs.indexOfFirst { it == "BA Takeaway" }.takeIf { it >= 0 }
    val realIndex = paragraphs.indexOfFirst { it.startsWith("The real requirement") }.takeIf { it >= 0 }

    val openingEnd = flowIndex ?: flawedIndex ?: bodyEnd
    val opening = paragraphs.subList(0, openingEnd)

    val flowSteps = if (flowIndex != null && flowIndex + 1 < paragraphs.size) {
        arrowLines(paragraphs[flowIndex + 1])
    } else {
        emptyList()
    }

    val flawedRequirement = if (flawedIndex != null) {
        when {
            whyIndex != null && whyIndex > flawedIndex -> paragraphs.subList(flawedIndex, whyIndex)
            baIndex != null && baIndex > flawedIndex + 1 -> listOf(paragraphs[flawedIndex])
            else -> paragraphs.subList(flawedIndex, bodyEnd)
        }
    } else {
        emptyList()
    }

    var insight: String? = null
    var rootCause: List<String> = emptyList()
    if (baIndex != null && baIndex > 0) {
        val candidate = paragraphs[baIndex - 1]
        if (!candidate.contains("→") && !isFlowHeader(candidate)) {
            insight = candidate
        }
        val causeStart = when {
            whyIndex != null -> whyIndex + 1
            flawedIndex != null -> flawedIndex + 1
            else -> null
        }
        if (causeStart != null && causeStart < baIndex - 1) {
            val causeEnd = if (insight == null) baIndex else baIndex - 1
            rootCause = paragraphs.subList(causeStart, causeEnd)
        }
    }

    val baBody = mutableListOf<String>()
    val controls = mutableListOf<String>()
    if (baIndex != null) {
        val solutionEnd = realIndex ?: bodyEnd
        if (baIndex + 1 < solutionEnd) {
            for (i in baIndex + 1 until solutionEnd) {
                val paragraph = paragraphs[i]
                val lines = arrowLines(paragraph)
                if (lines.isNotEmpty()) {
                    controls += lines
                } else if (paragraph.endsWith("separate:") || paragraph.contains("keep them separate:")) {
                    // A heading for the control list, not content.
                } else {
                    baBody += paragraph
                }
            }
        }
    }

    val realRequirement = if (realIndex != null && realIndex < bodyEnd) {
        paragraphs.subList(realIndex, bodyEnd)
    } else {
        emptyList()
    }

    return ParsedSituationExplain(
        opening = opening,
        flowSteps = flowSteps,
        flawedRequirement = flawedRequirement,
        rootCause = rootCause,
        insight = insight,
        baBody = baBody,
        controls = controls,
        realRequirement = realRequirement,
        closingQuestion = closingQuestion
    )
}
