// PracticeExplain.kt
// Explain-text parsing for Practice Mode, ported from
// PracticeModeView.swift (parsePracticeExplain,
// sentencesMentioning) and SituationsView.swift (scheme). The
// full explain text is one field in a fixed paragraph format;
// this splits on the real structure only and never invents
// content. Sections with no matching paragraphs render the
// honest "Not stated in this case" fallback.

package com.paymentstraininghub.app.features.practice

import com.paymentstraininghub.app.data.model.Situation

data class PracticeExplain(
    val opening: List<String> = emptyList(),
    val flowSteps: List<String> = emptyList(),
    val flawedRequirement: List<String> = emptyList(),
    val rootCause: List<String> = emptyList(),
    val baBody: List<String> = emptyList(),
    val controls: List<String> = emptyList(),
    val realRequirement: List<String> = emptyList(),
    val closingQuestion: String? = null,
    val preTakeawayParagraphs: List<String> = emptyList()
)

fun parsePracticeExplain(explain: String): PracticeExplain {
    val paragraphs = explain.split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return PracticeExplain()

    fun isFlowHeader(para: String): Boolean {
        return para.startsWith("Here") && para.endsWith(":") && para.contains("happen")
    }
    fun isFlawedHeader(para: String): Boolean {
        return para.startsWith("The requirement said:") ||
            para.startsWith("The assumption was:") ||
            para.startsWith("The assumption said:") ||
            para.startsWith("The requirement says") ||
            para.startsWith("The requirement is written") ||
            para.startsWith("The instruction was:")
    }
    fun arrowLines(para: String): List<String> {
        return para.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("→") }
            .map { it.drop(1).trim() }
            .filter { it.isNotEmpty() }
    }

    val flowIndex = paragraphs.indexOfFirst { isFlowHeader(it) }.takeIf { it >= 0 }
    val flawedIndex = paragraphs.indexOfFirst { isFlawedHeader(it) }.takeIf { it >= 0 }
    val whyIndex = paragraphs.indexOfFirst { it == "Why?" }.takeIf { it >= 0 }
    val baIndex = paragraphs.indexOfFirst { it == "BA Takeaway" }.takeIf { it >= 0 }
    val realIndex = paragraphs.indexOfFirst { it.startsWith("The real requirement") }.takeIf { it >= 0 }

    var bodyEnd = paragraphs.size
    var closingQuestion: String? = null
    val last = paragraphs.last()
    if (last.startsWith("Have you") || last.startsWith("Did you know")) {
        closingQuestion = last
        bodyEnd -= 1
    }

    val preTakeawayParagraphs = if (baIndex != null) {
        paragraphs.subList(0, baIndex)
    } else {
        paragraphs.subList(0, bodyEnd)
    }

    val openingEnd = flowIndex ?: flawedIndex ?: baIndex ?: bodyEnd
    val opening = paragraphs.subList(0, openingEnd)

    val flowSteps = if (flowIndex != null && flowIndex + 1 < paragraphs.size) {
        arrowLines(paragraphs[flowIndex + 1])
    } else {
        emptyList()
    }

    val flawedRequirement = if (flawedIndex != null) {
        val end = whyIndex ?: baIndex ?: bodyEnd
        if (flawedIndex < end) paragraphs.subList(flawedIndex, end) else emptyList()
    } else {
        emptyList()
    }

    var rootCause: List<String> = emptyList()
    val baBody = mutableListOf<String>()
    val controls = mutableListOf<String>()
    if (baIndex != null && baIndex > 0) {
        val causeStart: Int? = if (whyIndex != null) {
            whyIndex + 1
        } else if (flawedIndex != null) {
            flawedIndex + 1
        } else {
            null
        }
        if (causeStart != null) {
            // The paragraph just before "BA Takeaway" is the
            // insight sentence when it is a plain sentence; an
            // arrow list or flow header there belongs to the
            // root cause instead.
            val beforeBA = paragraphs[baIndex - 1]
            val hasInsight = !beforeBA.contains("→") && !isFlowHeader(beforeBA)
            val causeEnd = if (hasInsight) baIndex - 1 else baIndex
            if (causeStart < causeEnd) {
                rootCause = paragraphs.subList(causeStart, causeEnd)
            }
        }
        val solutionEnd = realIndex ?: bodyEnd
        if (baIndex + 1 < solutionEnd) {
            for (para in paragraphs.subList(baIndex + 1, solutionEnd)) {
                val lines = arrowLines(para)
                if (lines.isNotEmpty()) {
                    controls.addAll(lines)
                } else if (para.endsWith("separate:") || para.contains("keep them separate:")) {
                    continue
                } else {
                    baBody.add(para)
                }
            }
        }
    }

    val realRequirement = if (realIndex != null && realIndex < bodyEnd) {
        paragraphs.subList(realIndex, bodyEnd)
    } else {
        emptyList()
    }

    return PracticeExplain(
        opening = opening,
        flowSteps = flowSteps,
        flawedRequirement = flawedRequirement,
        rootCause = rootCause,
        baBody = baBody,
        controls = controls,
        realRequirement = realRequirement,
        closingQuestion = closingQuestion,
        preTakeawayParagraphs = preTakeawayParagraphs
    )
}

/**
 * Sentences of a text that mention one of the given keywords
 * (case-insensitive). The sentences are returned verbatim, in the
 * order they appear; nothing is paraphrased or added.
 */
fun sentencesMentioning(text: String, keywords: List<String>): List<String> {
    val lowerKeywords = keywords.map { it.lowercase() }
    val found = mutableListOf<String>()
    val paragraphs = text.split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    for (paragraph in paragraphs) {
        val parts = paragraph.split(". ")
        for (part in parts) {
            val sentence = part.trim()
            if (sentence.length < 10) continue
            val lower = sentence.lowercase()
            if (lowerKeywords.none { lower.contains(it) }) continue
            val display = if (sentence.endsWith(".")) sentence else "$sentence."
            if (display !in found) {
                found.add(display)
            }
        }
    }
    return found
}

val TECHNICAL_KEYWORDS = listOf(
    "pacs.", "pain.", "camt.", "MT103", "MT202", "MT101", "MT900", "MT910",
    "MT940", "MT942", "ISO 20022", "SWIFT", "UETR", "FPS", "SEPA",
    "API", "reason code", "response code", "status code", "return code",
    "timeout", "cut-off", "cutoff", "clearing", "correspondent"
)

val DATA_KEYWORDS = listOf(
    "UTR", "RRN", "reference number", "account number", "IBAN",
    "sort code", "value date", "currency", "field "
)

private val SCHEME_CANDIDATES = listOf(
    "UPI" to "upi",
    "SEPA" to "sepa",
    "SWIFT" to "swift",
    "Cards" to "cards?",
    "Direct Debit" to "direct debit",
    "ACH" to "ach",
    "Faster Payments" to "faster payments",
    "CHAPS" to "chaps",
    "TARGET2" to "target2",
    "Fedwire" to "fedwire",
    "CHIPS" to "chips",
    "RuPay" to "rupay",
    "Visa" to "visa",
    "Mastercard" to "mastercard",
    "NEFT" to "neft",
    "RTGS" to "rtgs",
    "IMPS" to "imps",
    "BACS" to "bacs",
    "FedNow" to "fednow"
)

/** The scheme named by a situation title, or null when the title names none. Never invented. */
fun schemeFor(situation: Situation): String? {
    for ((label, pattern) in SCHEME_CANDIDATES) {
        val regex = Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE)
        if (regex.containsMatchIn(situation.title)) return label
    }
    return null
}
