// InvestigationEngine.kt
// Builds InvestigationCase values for the Payments Mystery
// Investigation Experience: the derived tier from a Situation plus
// its full data-file item, and the authored tier from an
// ExceptionCase. Port of iOS Services/InvestigationEngine.swift
// (v76). Implements the derivation rules in
// INVESTIGATION-PLAN.md section 7 (rules 1 to 18) and the template
// tables verbatim. Every list has a template fallback, so the engine
// never returns a case with an empty briefing, empty evidence, empty
// hypotheses, or empty decisions.
//
// Parsing: the engine implements its own paragraph parsing following
// the plan rules exactly, as the Swift engine does (the existing
// explain parser is private on iOS and is untouched there; there is
// no equivalent Android parser to reuse). The deterministic shuffle
// ports PracticeModeBuilder (stableSeed plus deterministicShuffle)
// from iOS Models/PracticeMode.swift, which has no Android
// equivalent in v1. Message purposes reuse IsoCatalogue where the
// catalogue covers the code. Synthetic values are labeled SAMPLE,
// parties are generic, absent fields are stated once in plain
// words, and no real bank names, customer names, accounts,
// references, or dates are invented. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.features.investigation

import com.paymentstraininghub.app.data.FullSituationItem
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.features.iso.IsoCatalogue
import com.paymentstraininghub.app.features.lab.ExceptionCase
import com.paymentstraininghub.app.features.lab.ExceptionEvidence
import com.paymentstraininghub.app.features.lab.ExceptionLevel
import com.paymentstraininghub.app.features.lab.InvestigationStep

// MARK: - Parsed explain (engine-local)

/** The explain text split into its parts, following plan rule 1
 * (blank-line paragraphs only; never split on single newlines).
 * Any part may be empty; the builders below apply the fallback
 * chains from the plan rules. */
private data class InvestigationParsedExplain(
    var opening: List<String> = emptyList(),
    var flowSteps: List<String> = emptyList(),
    var flawedRequirement: List<String> = emptyList(),
    var rootCause: List<String> = emptyList(),
    var insight: String? = null,
    var baBody: List<String> = emptyList(),
    var controls: List<String> = emptyList(),
    var realRequirement: List<String> = emptyList(),
    var closingQuestion: String? = null
) {
    /** True when the explain carries the structured format at all
     * (an opening plus a BA Takeaway body or controls list). */
    val isParsed: Boolean get() = opening.isNotEmpty() && (baBody.isNotEmpty() || controls.isNotEmpty())
}

/** One flow step from the preferred source (visual flow steps when
 * present, parsed arrow lines otherwise). */
private data class InvestigationFlowStepSource(
    val title: String,
    val note: String?,
    val isDecision: Boolean
)

/** The failure-category key used by the template tables. Derived
 * from the failure_category field (derived tier) or the incident
 * type (authored tier) by keyword matching. */
private enum class InvestigationCategoryKey {
    REJECTED_OR_RETURNED,
    DUPLICATE,
    SANCTIONS,
    RECONCILIATION,
    OTHER
}

/** Ports of PracticeModeBuilder.firstSentence, stableSeed and
 * deterministicShuffle from iOS Models/PracticeMode.swift, which
 * the Swift engine reuses and which have no Android equivalent. */
internal object InvestigationDeterminism {

    /** First sentence of a text, capped so an option stays
     * readable. Same rule as DailyChallengeView on iOS. */
    fun firstSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val dotRange = trimmed.indexOf(". ")
        if (dotRange >= 0) {
            val first = trimmed.substring(0, dotRange)
            if (first.length >= 20) return "$first."
        }
        if (trimmed.length > 200) {
            return trimmed.substring(0, 200) + "..."
        }
        return trimmed
    }

    /** A stable 64-bit hash of a string (FNV-1a). The platform hash
     * is randomised per launch on iOS, so it must never be used
     * for a seed that has to be the same every time. */
    fun stableSeed(text: String): ULong {
        var hash: ULong = 0xcbf29ce484222325UL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            // Unsigned byte value, matching Swift's UInt8 iteration.
            hash = hash xor (byte.toInt() and 0xFF).toULong()
            hash *= 0x100000001b3UL
        }
        return hash
    }

    /** Deterministic shuffle (Fisher-Yates with a linear
     * congruential generator), so the same situation always shows
     * the same option order on every device. ULong arithmetic
     * wraps modulo 2^64, matching the Swift wrapping operators. */
    fun <T> deterministicShuffle(items: List<T>, seed: ULong): List<T> {
        val shuffled = items.toMutableList()
        var state = seed + 1UL
        if (shuffled.size <= 1) return shuffled
        for (index in shuffled.size - 1 downTo 1) {
            state = state * 6364136223846793005UL + 1442695040888963407UL
            val swap = (state % (index + 1).toULong()).toInt()
            val temp = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = temp
        }
        return shuffled
    }
}

// MARK: - Engine

object InvestigationEngine {

    // MARK: - Public API

    /** Builds the derived tier case for one situation. [fullItem]
     * carries the stage-map fields (messages, failure_category,
     * resolution, domain) when the data file supplied them; when
     * it is null, every field falls back to the regex and template
     * rules. The case is built per difficulty because Expert
     * changes evidence completeness. */
    fun caseFor(
        situation: Situation,
        fullItem: FullSituationItem?,
        difficulty: InvestigationDifficulty
    ): InvestigationCase {
        val parsed = parseExplain(situation.explain)
        val header = paymentHeader(title = situation.title, explain = situation.explain)
        val failureCategory = nonEmpty(fullItem?.failureCategory)
        val resolution = nonEmpty(fullItem?.resolution)
        val category = categoryKey(failureCategory)
        val messages = resolveMessages(fullItem = fullItem, title = situation.title, explain = situation.explain)

        val incidentReport = incidentReportText(parsed = parsed, situation = situation)
        val businessContext = businessContextText(parsed = parsed, situation = situation, fullItem = fullItem)
        val incidentType = incidentTypeText(failureCategory = failureCategory, levelDisplayName = situation.levelDisplayName)
        val customerImpact = incidentReport + "\n\n" + impactSentence(category)

        val realRequirementText = realRequirementText(parsed = parsed, takeaway = situation.takeaway)
        val rootCauseText = rootCauseText(parsed = parsed, takeaway = situation.takeaway, realRequirementText = realRequirementText)
        val flawedCore = flawedRequirementCore(parsed = parsed, rootCauseText = rootCauseText, category = category)
        val rootCauseFirst = InvestigationDeterminism.firstSentence(rootCauseText)

        val flowSteps = flowStepSources(situation = situation, parsed = parsed)
        val timelineSourceField = if (situation.visual?.flow?.steps?.isNotEmpty() == true) "visual" else "explain"
        val evidence = buildDerivedEvidence(
            caseId = situation.id,
            flowSteps = flowSteps,
            timelineSourceField = timelineSourceField,
            messages = messages,
            header = header,
            explain = situation.explain,
            domain = fullItem?.domain,
            failureCategory = failureCategory,
            incidentReport = incidentReport,
            parsed = parsed,
            resolution = resolution,
            difficulty = difficulty
        )
        val timeline = evidence.filter { it.kind == InvestigationEvidenceKind.TIMELINE }
        val supportingEvidenceIds = evidence
            .filter { it.kind == InvestigationEvidenceKind.TIMELINE || it.kind == InvestigationEvidenceKind.MESSAGE || it.kind == InvestigationEvidenceKind.LOG }
            .map { it.id }

        val diagnosis = diagnosisText(rootCauseText = rootCauseText, takeaway = situation.takeaway, insight = parsed.insight)
        val hypotheses = buildHypotheses(
            caseId = situation.id,
            assumptionCore = flawedCore,
            rootCauseFirstSentence = rootCauseFirst,
            category = category,
            diagnosisText = diagnosis
        )

        val decisionSteps = flowSteps.filter { it.isDecision }.take(4)
        val evidenceKinds = orderedKinds(evidence)
        val closingEvidenceNote = resolution?.let { "The stated resolution path for this case is: $it." }
            ?: "The real requirement section of the case states the fix."
        val actionText = correctiveActionText(realRequirementText = realRequirementText, takeaway = situation.takeaway)
        val decisions = buildDerivedDecisions(
            caseId = situation.id,
            decisionSteps = decisionSteps,
            hasMessages = messages.isNotEmpty(),
            evidenceKinds = evidenceKinds,
            correctiveOptionText = actionText,
            closingWhyCorrect = nonEmpty(situation.takeaway) ?: "The real requirement in the case text states the fix.",
            closingEvidenceNote = closingEvidenceNote
        )

        return InvestigationCase(
            id = situation.id,
            situationId = situation.id,
            title = situation.title,
            severity = severityLabel(level = situation.level, failureCategory = failureCategory),
            incidentType = incidentType,
            businessContext = businessContext,
            customerImpact = customerImpact,
            incidentReport = incidentReport,
            objectives = objectives,
            paymentHeader = header,
            timeline = timeline,
            evidence = evidence,
            hypotheses = hypotheses,
            decisions = decisions,
            correctDiagnosis = diagnosis,
            supportingEvidenceIds = supportingEvidenceIds,
            correctiveAction = actionText,
            preventiveControls = preventiveControls(parsed),
            lessons = lessons(takeaway = situation.takeaway, insight = parsed.insight),
            source = InvestigationSource.DERIVED,
            difficulty = difficulty,
            reflectionPrompt = parsed.closingQuestion
        )
    }

    /** Adapts an authored lab case to the experience. The narrative
     * becomes the incident report, evidence maps 1:1 (kind note,
     * except message-named items map to kind message), steps map to
     * decision points, resolution maps to corrective action plus
     * diagnosis, and the source takeaway maps to lessons. Authored
     * red herrings are preserved. */
    fun caseForLabCase(
        labCase: ExceptionCase,
        difficulty: InvestigationDifficulty
    ): InvestigationCase {
        val category = categoryKey(labCase.incidentType)
        val header = paymentHeader(title = labCase.title, explain = labCase.narrative)
        val resolutionText = nonEmpty(labCase.resolution)
            ?: nonEmpty(labCase.sourceTakeaway)
            ?: "The case does not state a resolution."
        val customerImpact = labCase.narrative + "\n\n" + impactSentence(category)

        var evidence = labCase.evidence.map { authoredEvidence(it) }
        if (evidence.isEmpty()) {
            evidence = listOf(
                InvestigationEvidence(
                    id = "${labCase.id}-note-1",
                    kind = InvestigationEvidenceKind.NOTE,
                    title = "Case narrative",
                    body = labCase.narrative,
                    structuredLines = emptyList(),
                    sourceField = "authored",
                    revealedAfterStep = 0,
                    completeness = InvestigationEvidenceCompleteness.Complete,
                    isRedHerring = false
                )
            )
        }
        evidence = applyingExpertCompleteness(evidence, difficulty)
        val timeline = evidence.filter { it.kind == InvestigationEvidenceKind.TIMELINE }
        val genuineIds = evidence.filter { !it.isRedHerring }.map { it.id }
        val supportingEvidenceIds = if (genuineIds.isEmpty()) evidence.map { it.id } else genuineIds

        val assumptionCore = authoredAssumptionCore(labCase = labCase, category = category)
        val rootCauseFirst = InvestigationDeterminism.firstSentence(resolutionText)
        val diagnosis = authoredDiagnosisText(resolutionText)
        val hypotheses = buildHypotheses(
            caseId = labCase.id,
            assumptionCore = assumptionCore,
            rootCauseFirstSentence = if (rootCauseFirst.isEmpty()) "the evidence does not state a root cause." else rootCauseFirst,
            category = category,
            diagnosisText = diagnosis
        )

        var decisions = labCase.steps.mapIndexed { offset, step ->
            authoredDecision(step = step, isLast = offset == labCase.steps.size - 1)
        }
        if (decisions.size < 2) {
            decisions = decisions + closingDecision(
                caseId = labCase.id,
                index = decisions.size + 1,
                correctOptionText = if (rootCauseFirst.isEmpty()) resolutionText else rootCauseFirst,
                whyCorrect = nonEmpty(labCase.sourceTakeaway) ?: "The resolution states the fix.",
                evidenceNote = "The resolution for this case states the fix."
            )
        }

        val takeaway = nonEmpty(labCase.sourceTakeaway)
        val lessonsList = mutableListOf<String>()
        if (takeaway != null) lessonsList.add(takeaway)
        if (rootCauseFirst.isNotEmpty() && rootCauseFirst != takeaway) lessonsList.add(rootCauseFirst)
        if (lessonsList.isEmpty()) lessonsList.add("Check the evidence before confirming the payment outcome.")

        return InvestigationCase(
            id = labCase.id,
            situationId = labCase.sourceSituationID ?: labCase.id,
            title = labCase.title,
            severity = severityLabel(level = labCase.level, failureCategory = labCase.incidentType),
            incidentType = labCase.incidentType,
            businessContext = authoredBusinessContext(labCase),
            customerImpact = customerImpact,
            incidentReport = labCase.narrative,
            objectives = objectives,
            paymentHeader = header,
            timeline = timeline,
            evidence = evidence,
            hypotheses = hypotheses,
            decisions = decisions,
            correctDiagnosis = diagnosis,
            supportingEvidenceIds = supportingEvidenceIds,
            correctiveAction = authoredCorrectiveActionText(resolutionText),
            preventiveControls = takeaway?.let { listOf(it) } ?: listOf("Check the status evidence before confirming the payment outcome."),
            lessons = lessonsList,
            source = InvestigationSource.AUTHORED,
            difficulty = difficulty,
            reflectionPrompt = null
        )
    }

    /** The derived severity label. Advanced plus a failure category
     * present is High; core plus a failure category present is
     * Medium; otherwise Moderate. The briefing states that severity
     * is derived from the case level and failure category.
     * [level] is the lowercase level string from the data file. */
    fun severityLabel(level: String, failureCategory: String?): String {
        val hasCategory = nonEmpty(failureCategory) != null
        return when {
            level.lowercase() == "advanced" && hasCategory -> "High"
            level.lowercase() == "core" && hasCategory -> "Medium"
            else -> "Moderate"
        }
    }

    /** The derived severity label for an authored lab case level. */
    fun severityLabel(level: ExceptionLevel, failureCategory: String?): String =
        severityLabel(level = level.displayName.lowercase(), failureCategory = failureCategory)

    /** The derived payment header. The regex looks for a currency
     * code plus a number, and for Rs or the rupee sign plus a
     * number, in the title first and then the explain. A scale
     * word after the number (million, billion, thousand, crore,
     * lakh) is kept as written. No match
     * means the amount is not stated in the case. Parties are
     * always generic. The reference is not stated unless the
     * explain names one with a label. */
    fun paymentHeader(title: String, explain: String): InvestigationPaymentHeader {
        val match = amountMatch(title) ?: amountMatch(explain)
        return InvestigationPaymentHeader(
            amountText = match?.first ?: NOT_STATED_AMOUNT_TEXT,
            currencyText = match?.second ?: "Not stated in the case",
            partiesText = "The customer sends the payment to the beneficiary through the sending bank and the beneficiary bank.",
            referenceText = referenceText(explain)
        )
    }

    /** The Rookie and Professional hint for one decision point.
     * Every hint guides METHOD only: it names no answer, no
     * evidence item, and no section of the case text. The learner
     * must still decide what to inspect and what it proves. */
    fun hintText(decision: InvestigationDecisionPoint): String {
        if (decision.isResolution) {
            return "Work backwards from the outcome. Ask what would have to be true for the payment to complete, then pick the action that makes it true."
        }
        if (decision.prompt.lowercase().contains("check first")) {
            return "Establish the order of events before interpreting any single record."
        }
        if (decision.isTechnical) {
            return "Compare what was sent with what was expected, and treat a missing record as a finding to explain, not a gap to skip."
        }
        return "Ask what the next step would prove, and pick the action that settles the most open questions."
    }

    // MARK: - Parsing (engine-local, plan rules 1 to 9)

    /** Splits the explain into paragraphs on blank lines only and
     * assigns each paragraph to a section by its markers. Mirrors
     * the marker semantics of the existing iOS parser without
     * editing or calling it: flow headers start with "Here" and end
     * with a colon and contain "happen"; flawed headers are the
     * requirement and assumption variants; "Why?", "BA Takeaway",
     * and "The real requirement" head their sections; the closing
     * question is the last paragraph when it addresses the reader. */
    private fun parseExplain(explain: String): InvestigationParsedExplain {
        val paragraphs = explain
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parsed = InvestigationParsedExplain()
        if (paragraphs.isEmpty()) return parsed

        val flowIndex = paragraphs.indexOfFirst { isFlowHeader(it) }.takeIf { it >= 0 }
        val flawedIndex = paragraphs.indexOfFirst { isFlawedHeader(it) }.takeIf { it >= 0 }
        val whyIndex = paragraphs.indexOfFirst { it == "Why?" }.takeIf { it >= 0 }
        val baIndex = paragraphs.indexOfFirst { it == "BA Takeaway" }.takeIf { it >= 0 }
        val realIndex = paragraphs.indexOfFirst { it.startsWith("The real requirement") }.takeIf { it >= 0 }

        var bodyEnd = paragraphs.size
        val last = paragraphs.last()
        if (last.startsWith("Have you") || last.startsWith("Did you know")) {
            parsed.closingQuestion = last
            bodyEnd -= 1
        }

        // Opening: paragraphs before the first "Here is" marker; when
        // there is no flow header, up to the flawed requirement when
        // one exists, otherwise the first 2 paragraphs (rule 2).
        val openingEnd: Int = when {
            flowIndex != null -> flowIndex
            flawedIndex != null -> flawedIndex
            else -> minOf(2, bodyEnd)
        }
        parsed.opening = slice(paragraphs, 0, minOf(openingEnd, bodyEnd))

        // Flow steps: the arrow list right after the flow header.
        if (flowIndex != null && flowIndex + 1 < paragraphs.size) {
            parsed.flowSteps = arrowLines(paragraphs[flowIndex + 1])
        }

        // Flawed requirement: the flawed paragraph plus what followed
        // from it, up to "Why?" (or the single flawed paragraph when
        // there is no "Why?" and a BA section follows).
        if (flawedIndex != null) {
            parsed.flawedRequirement = when {
                whyIndex != null && flawedIndex < whyIndex -> slice(paragraphs, flawedIndex, whyIndex)
                baIndex != null && baIndex > flawedIndex + 1 -> listOf(paragraphs[flawedIndex])
                else -> slice(paragraphs, flawedIndex, bodyEnd)
            }
        }

        // Root cause and insight: the paragraphs after "Why?" (or
        // after the flawed requirement when there is no "Why?"), up
        // to the insight sentence just before "BA Takeaway".
        if (baIndex != null && baIndex > 0) {
            val candidate = paragraphs[baIndex - 1]
            if (!candidate.contains("→") && !isFlowHeader(candidate)) {
                parsed.insight = candidate
            }
            val causeStart: Int? = when {
                whyIndex != null -> whyIndex + 1
                flawedIndex != null -> flawedIndex + 1
                else -> null
            }
            if (causeStart != null && causeStart < baIndex) {
                val causeEnd = if (parsed.insight == null) baIndex else baIndex - 1
                if (causeStart < causeEnd) {
                    parsed.rootCause = slice(paragraphs, causeStart, causeEnd)
                }
            }
        }

        // Solution: everything after "BA Takeaway" up to the real
        // requirement. Arrow lists are the controls; a paragraph
        // ending in "separate:" is their heading; the rest is body.
        if (baIndex != null) {
            val solutionEnd = realIndex ?: bodyEnd
            if (baIndex + 1 < solutionEnd) {
                val controls = mutableListOf<String>()
                val body = mutableListOf<String>()
                for (para in paragraphs.subList(baIndex + 1, minOf(solutionEnd, paragraphs.size))) {
                    val lines = arrowLines(para)
                    when {
                        lines.isNotEmpty() -> controls.addAll(lines)
                        para.endsWith("separate:") || para.contains("keep them separate:") -> Unit
                        else -> body.add(para)
                    }
                }
                parsed.controls = controls
                parsed.baBody = body
            }
        }

        // The real requirement: from its marker to the end of the body.
        if (realIndex != null && realIndex < bodyEnd) {
            parsed.realRequirement = slice(paragraphs, realIndex, bodyEnd)
        }

        return parsed
    }

    private fun isFlowHeader(para: String): Boolean =
        para.startsWith("Here") && para.endsWith(":") && para.contains("happen")

    private fun isFlawedHeader(para: String): Boolean =
        para.startsWith("The requirement said:") ||
            para.startsWith("The assumption was:") ||
            para.startsWith("The assumption said:") ||
            para.startsWith("The requirement says") ||
            para.startsWith("The requirement is written") ||
            para.startsWith("The instruction was:")

    private fun arrowLines(para: String): List<String> =
        para.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("→") }
            .map { it.drop(1).trim() }
            .filter { it.isNotEmpty() }

    /** A bounds-safe paragraph slice, empty when the range is invalid. */
    private fun slice(paragraphs: List<String>, start: Int, end: Int): List<String> {
        if (start < 0 || end <= start || start >= paragraphs.size) return emptyList()
        return paragraphs.subList(start, minOf(end, paragraphs.size)).toList()
    }

    // MARK: - Briefing derivation (rules 2, 4 to 9, 12 to 14)

    private fun incidentReportText(parsed: InvestigationParsedExplain, situation: Situation): String {
        val opening = parsed.opening.joinToString("\n\n").trim()
        if (opening.isNotEmpty()) return opening
        val explain = situation.explain.trim()
        if (explain.isNotEmpty()) return explain
        return situation.title
    }

    private fun businessContextText(
        parsed: InvestigationParsedExplain,
        situation: Situation,
        fullItem: FullSituationItem?
    ): String {
        val hook = parsed.opening.firstOrNull() ?: ""
        val domain = nonEmpty(fullItem?.domain) ?: ""
        return when {
            domain.isNotEmpty() && hook.isNotEmpty() -> "$domain. $hook"
            hook.isNotEmpty() -> hook
            domain.isNotEmpty() -> domain
            else -> if (situation.title.isEmpty()) "Payment investigation." else situation.title
        }
    }

    private fun incidentTypeText(failureCategory: String?, levelDisplayName: String): String {
        if (!failureCategory.isNullOrEmpty()) {
            return failureCategory.replaceFirstChar { it.uppercaseChar() }
        }
        return "$levelDisplayName payment incident"
    }

    private fun realRequirementText(parsed: InvestigationParsedExplain, takeaway: String): String {
        val joined = parsed.realRequirement.joinToString("\n\n").trim()
        if (joined.isNotEmpty()) return joined
        return takeaway.trim()
    }

    private fun rootCauseText(
        parsed: InvestigationParsedExplain,
        takeaway: String,
        realRequirementText: String
    ): String {
        val joined = parsed.rootCause.joinToString("\n\n").trim()
        if (joined.isNotEmpty()) return joined
        val combined = listOf(takeaway, realRequirementText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (combined.isNotEmpty()) return combined
        return "The case does not state a root cause in the text provided."
    }

    /** The flawed requirement core: the quoted text after the
     * requirement or assumption marker, else the first sentence of
     * the flawed section, else the first sentence of the Why?
     * section, else the category template assumption (rule 4). */
    private fun flawedRequirementCore(
        parsed: InvestigationParsedExplain,
        rootCauseText: String,
        category: InvestigationCategoryKey
    ): String {
        val substance = parsed.flawedRequirement.filter { !isHeaderOnlyFlawedParagraph(it) }
        var joined = substance.joinToString(" ").trim()
        joined = strippingFlawedHeaderPrefix(joined)
        val groups = firstMatchGroups("\"([^\"]+)\"", joined)
        val quoted = groups?.firstOrNull()
        if (quoted != null) {
            val trimmed = quoted.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        val cleaned = strippingQuotes(joined)
        if (cleaned.isNotEmpty()) {
            val first = InvestigationDeterminism.firstSentence(cleaned)
            if (first.isNotEmpty()) return first
        }
        val causeFirst = InvestigationDeterminism.firstSentence(rootCauseText)
        if (causeFirst.isNotEmpty()) return causeFirst
        return templateAssumption(category)
    }

    private fun isHeaderOnlyFlawedParagraph(para: String): Boolean {
        val exactHeaders = listOf(
            "The requirement said:",
            "The assumption was:",
            "The assumption said:",
            "The instruction was:"
        )
        if (para in exactHeaders) return true
        if (para.startsWith("The requirement says") || para.startsWith("The requirement is written")) {
            return para.length < 60
        }
        return false
    }

    private fun strippingFlawedHeaderPrefix(text: String): String {
        val prefixes = listOf(
            "The requirement said:",
            "The assumption was:",
            "The assumption said:",
            "The requirement says",
            "The requirement is written",
            "The instruction was:"
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                return text.drop(prefix.length).trim()
            }
        }
        return text
    }

    private fun strippingQuotes(text: String): String {
        var result = text.trim()
        if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
            result = result.drop(1).dropLast(1)
        }
        return result.trim()
    }

    /** The diagnosis as it reads in the report: a cause plus its
     * mechanism, never a bare fact. The first sentence of the root
     * cause text becomes the cause ("The root cause is that ..."),
     * and the remaining sentences carry the mechanism. When the
     * root cause is a single sentence, the takeaway or the insight
     * sentence supplies the mechanism, because both are case text.
     * The honest-absence fallback is returned unchanged. */
    private fun diagnosisText(
        rootCauseText: String,
        takeaway: String,
        insight: String?
    ): String {
        val trimmed = rootCauseText.trim()
        if (trimmed.isEmpty()) {
            return "The case does not state a root cause in the text provided."
        }
        if (trimmed == "The case does not state a root cause in the text provided.") {
            return trimmed
        }
        val sentences = splitSentences(trimmed)
        val first = sentences.firstOrNull() ?: return trimmed
        val cause = lowercasedFirst(first)
        val mechanism = sentences.drop(1).toMutableList()
        if (mechanism.isEmpty()) {
            val extra = nonEmpty(insight) ?: nonEmpty(takeaway)
            if (extra != null) {
                val extraFirst = splitSentences(extra).firstOrNull() ?: extra
                if (extraFirst.lowercase() != first.lowercase()) {
                    mechanism.add(extraFirst)
                }
            }
        }
        val framed = "The root cause is that $cause"
        if (mechanism.isEmpty()) return framed
        return (listOf(framed) + mechanism).joinToString(" ")
    }

    /** The authored-tier diagnosis: the resolution framed as the
     * investigation conclusion, so it reads as a finding rather
     * than a bare statement. The resolution text itself is
     * authored and is never rewritten. */
    private fun authoredDiagnosisText(resolutionText: String): String {
        val trimmed = resolutionText.trim()
        if (trimmed.isEmpty()) {
            return "The case does not state a root cause in the text provided."
        }
        if (trimmed.startsWith("The investigation concludes:")) return trimmed
        return "The investigation concludes: $trimmed"
    }

    /** The corrective action as it reads in the report and in the
     * closing decision: a verb-led ACTION, never a quoted
     * question. Candidates in order: the "It is" sentence of the
     * real requirement (the "is not" sentence states what the
     * requirement is not, so it must never be the action), the
     * first non-negation sentence of the real requirement, the
     * takeaway, then the template fix. A quoted question is
     * converted to a "Confirm ..." action; quotes are stripped. */
    private fun correctiveActionText(realRequirementText: String, takeaway: String): String {
        val itIs = itIsSentence(realRequirementText)
        if (itIs != null) {
            val action = actionSentence(itIs)
            if (action.isNotEmpty()) return action
        }
        for (sentence in splitSentences(realRequirementText)) {
            val lower = sentence.lowercase()
            if (lower.contains("is not") || lower.contains("isn't")) continue
            val action = actionSentence(sentence)
            if (action.isNotEmpty()) return action
        }
        val takeawayFirst = InvestigationDeterminism.firstSentence(takeaway)
        if (takeawayFirst.isNotEmpty()) {
            val action = actionSentence(takeawayFirst)
            if (action.isNotEmpty()) return action
        }
        return "Confirm the payment state from evidence before telling the customer it is complete."
    }

    /** The authored-tier corrective action: the authored resolution
     * unchanged, except that a resolution phrased as a question is
     * converted to a verb-led action, so no corrective action ever
     * reads as a quoted question. */
    private fun authoredCorrectiveActionText(resolutionText: String): String {
        val trimmed = resolutionText.trim()
        if (trimmed.isEmpty()) {
            return "Confirm the payment state from evidence before telling the customer it is complete."
        }
        if (trimmed.endsWith("?")) {
            return actionSentence(trimmed)
        }
        return trimmed
    }

    /** Converts one requirement sentence into a verb-led action
     * sentence. Questions become "Confirm ..." actions, "to ..."
     * infinitives become imperatives, and sentences that already
     * start with an action verb are kept. Quotes are stripped and
     * the result never ends with a question mark. */
    private fun actionSentence(sentence: String): String {
        var text = sentence.trim().replace("\"", "")
        if (text.startsWith("It is ", ignoreCase = true)) {
            text = text.drop(6).trim()
        }
        if (text.isEmpty()) return ""
        if (text.endsWith("?")) {
            val question = text.dropLast(1).trim()
            val words = question.split(" ", limit = 2)
            val firstWord = words.firstOrNull() ?: return ""
            val lowerFirst = firstWord.lowercase()
            val questionWords = setOf("what", "when", "how", "which", "why", "who", "where")
            val auxiliaryWords = setOf("is", "are", "was", "were", "did", "does", "do", "can", "could", "should", "has", "have")
            if (lowerFirst in questionWords) {
                return "Confirm ${lowercasedFirst(question)}."
            }
            if (lowerFirst in auxiliaryWords) {
                val rest = if (words.size > 1) words[1] else ""
                if (rest.isEmpty()) return ""
                return "Confirm whether ${lowercasedFirst(rest)}."
            }
            return "Confirm ${lowercasedFirst(question)}."
        }
        if (text.startsWith("to ") || text.startsWith("To ")) {
            text = text.drop(3).trim()
            return capitalizedFirst(ensuringPeriod(text))
        }
        val firstWord = text.split(" ", limit = 2).firstOrNull() ?: ""
        if (firstWord.lowercase() in ACTION_VERBS) {
            return capitalizedFirst(ensuringPeriod(text))
        }
        return "Follow the stated requirement: ${ensuringPeriod(text)}"
    }

    /** Splits text into sentences on ". ", "? " and "! " boundaries
     * and on newlines, keeping each terminator. Fragments with no
     * terminator keep their text; the caller adds one when needed. */
    private fun splitSentences(text: String): List<String> {
        val normalized = text.replace("\n", " ")
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        val chars = normalized.toCharArray()
        var index = 0
        while (index < chars.size) {
            val char = chars[index]
            current.append(char)
            if (char == '.' || char == '?' || char == '!') {
                val nextIsBoundary = index + 1 >= chars.size || chars[index + 1] == ' '
                if (nextIsBoundary) {
                    val trimmed = current.toString().trim()
                    if (trimmed.isNotEmpty()) sentences.add(trimmed)
                    current.clear()
                }
            }
            index += 1
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences.add(tail)
        return sentences
    }

    private fun lowercasedFirst(text: String): String {
        if (text.isEmpty()) return text
        return text[0].lowercaseChar() + text.drop(1)
    }

    private fun capitalizedFirst(text: String): String {
        if (text.isEmpty()) return text
        return text[0].uppercaseChar() + text.drop(1)
    }

    private fun ensuringPeriod(text: String): String {
        val trimmed = text.trim()
        if (trimmed.endsWith(".") || trimmed.endsWith("!")) return trimmed
        if (trimmed.endsWith("?")) return trimmed.dropLast(1) + "."
        return "$trimmed."
    }

    /** Verbs that already make a sentence a verb-led action. */
    private val ACTION_VERBS: Set<String> = setOf(
        "confirm", "check", "keep", "use", "send", "record", "validate",
        "request", "establish", "follow", "apply", "review", "compare",
        "trace", "investigate", "ensure", "require", "update", "fix",
        "raise", "ask", "wait", "treat", "show", "tell", "give", "hold",
        "stop", "start", "build", "add", "remove", "separate", "match",
        "reconcile", "monitor", "escalate", "notify", "document"
    )

    /** Controls: arrow lines in the BA Takeaway section, else the
     * full BA Takeaway section as one control, else one template
     * control (rule 7). */
    private fun preventiveControls(parsed: InvestigationParsedExplain): List<String> {
        if (parsed.controls.isNotEmpty()) return parsed.controls
        val body = parsed.baBody.joinToString(" ").trim()
        if (body.isNotEmpty()) return listOf(body)
        return listOf("Check the status evidence before confirming the payment outcome.")
    }

    private fun lessons(takeaway: String, insight: String?): List<String> {
        val result = mutableListOf<String>()
        val trimmedTakeaway = takeaway.trim()
        if (trimmedTakeaway.isNotEmpty()) result.add(trimmedTakeaway)
        if (insight != null) {
            val trimmed = insight.trim()
            if (trimmed.isNotEmpty() && trimmed !in result) result.add(trimmed)
        }
        if (result.isEmpty()) {
            result.add("Check the evidence before confirming the payment outcome.")
        }
        return result
    }

    /** The "It is" sentence of the real requirement, when one
     * exists. The "is not" sentence states what the requirement is
     * not, so callers must use this sentence, never the first
     * sentence of the requirement, as the fix. */
    private fun itIsSentence(text: String): String? {
        val rangeStart = text.indexOf("It is ")
        if (rangeStart < 0) return null
        val rest = text.substring(rangeStart).trim()
        val dotRange = rest.indexOf(". ")
        if (dotRange >= 0) {
            val sentence = rest.substring(0, dotRange) + "."
            return if (sentence.length >= 10) sentence else null
        }
        val questionRange = rest.indexOf("? ")
        if (questionRange >= 0) {
            val sentence = rest.substring(0, questionRange) + "?"
            return if (sentence.length >= 10) sentence else null
        }
        return if (rest.length >= 10) rest else null
    }

    // MARK: - Messages, header extraction, categories (rules 10, 11)

    /** Messages: stage-map messages when present, else message codes
     * found by regex in the title and explain, deduplicated, in
     * order of first mention, capped at 6 (rule 10). */
    private fun resolveMessages(
        fullItem: FullSituationItem?,
        title: String,
        explain: String
    ): List<String> {
        val listed = fullItem?.messages
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        if (!listed.isNullOrEmpty()) {
            val seen = mutableListOf<String>()
            for (code in listed) {
                val normalized = normalizedMessageCode(code)
                if (normalized !in seen) seen.add(normalized)
            }
            return seen.take(6)
        }
        return messageCodes(title + "\n" + explain).take(6)
    }

    private fun messageCodes(text: String): List<String> {
        val seen = mutableListOf<String>()
        for (raw in allMatches(
            "\\b(pacs\\.\\d{3}|pain\\.\\d{3}|camt\\.\\d{3}|MT\\d{3})\\b",
            text,
            ignoreCase = true
        )) {
            val normalized = normalizedMessageCode(raw)
            if (normalized !in seen) seen.add(normalized)
        }
        return seen
    }

    private fun normalizedMessageCode(code: String): String {
        val lower = code.lowercase()
        if (lower.startsWith("mt")) return "MT" + lower.drop(2)
        return lower
    }

    private fun categoryKey(text: String?): InvestigationCategoryKey {
        if (text == null) return InvestigationCategoryKey.OTHER
        val lower = text.lowercase()
        return when {
            lower.contains("duplicat") -> InvestigationCategoryKey.DUPLICATE
            lower.contains("sanction") -> InvestigationCategoryKey.SANCTIONS
            lower.contains("reconcil") -> InvestigationCategoryKey.RECONCILIATION
            lower.contains("reject") || lower.contains("return") -> InvestigationCategoryKey.REJECTED_OR_RETURNED
            else -> InvestigationCategoryKey.OTHER
        }
    }

    private fun amountMatch(text: String): Pair<String, String>? {
        val codePattern = CURRENCY_CODES.joinToString("|")
        val codeGroups = firstMatchGroups(
            "\\b($codePattern)\\s*(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s+([Mm]illions?|[Bb]illions?|[Tt]housands?|[Cc]rore|[Ll]akh))?",
            text,
            ignoreCase = true
        )
        if (codeGroups != null && codeGroups.size == 3) {
            val number = cleanAmountNumber(codeGroups[1])
            if (number.isNotEmpty()) {
                val scale = codeGroups[2]
                val amount = if (scale.isNotEmpty()) "${codeGroups[0].uppercase()} $number $scale" else "${codeGroups[0].uppercase()} $number"
                return Pair(amount, codeGroups[0].uppercase())
            }
        }
        val rsGroups = firstMatchGroups(
            "(Rs\\.?|₹)\\s*(\\d[\\d,]*(?:\\.\\d+)?)(?:\\s+([Mm]illions?|[Bb]illions?|[Tt]housands?|[Cc]rore|[Ll]akh))?",
            text,
            ignoreCase = true
        )
        if (rsGroups != null && rsGroups.size == 3) {
            val number = cleanAmountNumber(rsGroups[1])
            if (number.isNotEmpty()) {
                val symbol = if (rsGroups[0].contains("₹")) "₹" else "Rs"
                val scale = rsGroups[2]
                val amount = if (scale.isNotEmpty()) "$symbol $number $scale" else "$symbol $number"
                return Pair(amount, "INR")
            }
        }
        val trailingGroups = firstMatchGroups(
            "\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s*($codePattern)\\b",
            text,
            ignoreCase = true
        )
        if (trailingGroups != null && trailingGroups.size == 2) {
            val number = cleanAmountNumber(trailingGroups[0])
            if (number.isNotEmpty()) {
                return Pair("${trailingGroups[1].uppercase()} $number", trailingGroups[1].uppercase())
            }
        }
        return null
    }

    private fun cleanAmountNumber(raw: String): String {
        val trimmed = raw.trim(',')
        if (trimmed.none { it.isDigit() }) return ""
        return trimmed
    }

    private fun referenceText(explain: String): String {
        val groups = firstMatchGroups(
            "(?i)reference\\s*:\\s*[\"']?([A-Za-z0-9][A-Za-z0-9\\-]{3,})",
            explain
        )
        val value = groups?.firstOrNull()
        if (!value.isNullOrEmpty()) {
            val cleaned = value.trim('"', '\'', '.', ',', ';')
            if (cleaned.isNotEmpty()) return cleaned
        }
        return "Reference not stated in the case"
    }

    // MARK: - Evidence (rules 3, 17, 18)

    private fun flowStepSources(
        situation: Situation,
        parsed: InvestigationParsedExplain
    ): List<InvestigationFlowStepSource> {
        val steps = situation.visual?.flow?.steps
        if (!steps.isNullOrEmpty()) {
            return steps.map {
                InvestigationFlowStepSource(
                    title = it.title,
                    note = it.note,
                    isDecision = it.kind?.lowercase() == "decision"
                )
            }
        }
        return parsed.flowSteps.map {
            InvestigationFlowStepSource(title = it, note = null, isDecision = isDecisionLine(it))
        }
    }

    private fun isDecisionLine(line: String): Boolean =
        line.contains("?") || line.lowercase().contains("decided")

    private fun buildDerivedEvidence(
        caseId: String,
        flowSteps: List<InvestigationFlowStepSource>,
        timelineSourceField: String,
        messages: List<String>,
        header: InvestigationPaymentHeader,
        explain: String,
        domain: String?,
        failureCategory: String?,
        incidentReport: String,
        parsed: InvestigationParsedExplain,
        resolution: String?,
        difficulty: InvestigationDifficulty
    ): List<InvestigationEvidence> {
        val evidence = mutableListOf<InvestigationEvidence>()
        evidence.addAll(
            timelineEvidence(
                caseId = caseId,
                flowSteps = flowSteps,
                sourceField = timelineSourceField,
                incidentReport = incidentReport,
                header = header,
                parsed = parsed,
                resolution = resolution
            )
        )
        messages.forEachIndexed { index, code ->
            evidence.add(messageEvidence(code = code, index = index, caseId = caseId, header = header))
        }
        evidence.add(
            logEvidence(
                caseId = caseId,
                flowSteps = flowSteps,
                timelineSourceField = timelineSourceField,
                messages = messages,
                header = header
            )
        )
        if (triggersLedger(explain = explain, domain = domain)) {
            evidence.add(ledgerEvidence(caseId = caseId, header = header))
        }
        if (triggersRecon(explain = explain, failureCategory = failureCategory)) {
            evidence.add(reconEvidence(caseId = caseId, header = header))
        }
        if (triggersApi(explain)) {
            evidence.add(apiEvidence(caseId = caseId, header = header))
        }
        val clearing = clearingEvidence(caseId = caseId, explain = explain, header = header)
        if (clearing != null) evidence.add(clearing)
        return applyingExpertCompleteness(evidence, difficulty)
    }

    /** Timeline evidence: one item per flow step, in order. Each
     * item carries a relative sequence marker (T+0, T+1, ...) from
     * its step order, the amount and parties from the payment
     * header, and ONE narrative sentence derived from the case
     * text: the visual step note when present, else the case
     * sentence that best matches the step. The body never repeats
     * the step title. Where the case gives no further detail about
     * a step, the item says so once, in plain words. Nothing is
     * fabricated: no clock times, no invented amounts or parties. */
    private fun timelineEvidence(
        caseId: String,
        flowSteps: List<InvestigationFlowStepSource>,
        sourceField: String,
        incidentReport: String,
        header: InvestigationPaymentHeader,
        parsed: InvestigationParsedExplain,
        resolution: String?
    ): List<InvestigationEvidence> {
        if (flowSteps.isEmpty()) {
            val firstParagraph = incidentReport.split("\n\n").firstOrNull()?.trim() ?: ""
            return listOf(
                InvestigationEvidence(
                    id = "$caseId-timeline-1",
                    kind = InvestigationEvidenceKind.TIMELINE,
                    title = "What happened",
                    body = if (firstParagraph.isEmpty()) "The case states what happened in its opening text." else firstParagraph,
                    structuredLines = timelineFieldLines(sequence = 0, isDecision = false, header = header),
                    sourceField = "explain",
                    revealedAfterStep = 0,
                    completeness = InvestigationEvidenceCompleteness.Complete,
                    isRedHerring = false
                )
            )
        }
        val pool = timelineNarrativePool(parsed = parsed, resolution = resolution)
        val usedSentences = mutableSetOf<String>()
        val items = mutableListOf<InvestigationEvidence>()
        flowSteps.forEachIndexed { offset, step ->
            val body = timelineNarrativeSentence(step, pool, usedSentences)
            items.add(
                InvestigationEvidence(
                    id = "$caseId-timeline-${offset + 1}",
                    kind = InvestigationEvidenceKind.TIMELINE,
                    title = step.title,
                    body = body,
                    structuredLines = timelineFieldLines(sequence = offset, isDecision = step.isDecision, header = header),
                    sourceField = sourceField,
                    revealedAfterStep = 0,
                    completeness = InvestigationEvidenceCompleteness.Complete,
                    isRedHerring = false
                )
            )
        }
        return items
    }

    /** The one sequence label for a step offset, shared by the
     * timeline field lines and the processing log, so the same
     * event always carries the same label in both. The first
     * step or event is always T+0. Both renderers must use this
     * helper and the same flow-step order; neither may invent
     * its own labels, or the two sequences drift apart again. */
    private fun sequenceLabel(offset: Int): String = "T+$offset"

    /** The field lines for one timeline item: the relative sequence
     * marker, the amount (or one plain absence statement), and the
     * generic parties. A decision step is marked as one. */
    private fun timelineFieldLines(
        sequence: Int,
        isDecision: Boolean,
        header: InvestigationPaymentHeader
    ): List<String> {
        val lines = mutableListOf("Sequence: ${sequenceLabel(sequence)}")
        if (hasStatedAmount(header)) {
            lines.add("Amount: ${header.amountText}")
        } else {
            lines.add("Amount: not stated in the case")
        }
        lines.add("Parties: the customer and the beneficiary, through the sending bank and the beneficiary bank")
        if (isDecision) lines.add("Decision step")
        return lines
    }

    /** The sentence pool for timeline narratives, in order of
     * investigable value: the root cause section, the insight
     * sentence, the flawed requirement, the opening, the BA
     * Takeaway body and controls, the real requirement, and the
     * stage-map resolution. Every sentence comes from the case. */
    private fun timelineNarrativePool(
        parsed: InvestigationParsedExplain,
        resolution: String?
    ): List<String> {
        val pool = mutableListOf<String>()
        for (paragraph in parsed.rootCause) pool.addAll(splitSentences(paragraph))
        val insight = parsed.insight
        if (insight != null) pool.addAll(splitSentences(insight))
        for (paragraph in parsed.flawedRequirement) pool.addAll(splitSentences(paragraph))
        for (paragraph in parsed.opening) pool.addAll(splitSentences(paragraph))
        for (paragraph in parsed.baBody) pool.addAll(splitSentences(paragraph))
        for (control in parsed.controls) pool.add(ensuringPeriod(control))
        for (paragraph in parsed.realRequirement) pool.addAll(splitSentences(paragraph))
        if (resolution != null) pool.addAll(splitSentences(resolution))
        return pool
            .map { it.trim() }
            .filter { it.length >= 20 }
    }

    /** One narrative sentence for one step. The visual step note
     * wins when present; otherwise the best-scoring unused pool
     * sentence wins (message codes score double, distinctive words
     * score single). A sentence already used for an earlier step
     * is never reused, and the body never equals the step title.
     * When nothing in the case describes the step further, the
     * honest-absence sentence is used, once per item. */
    private fun timelineNarrativeSentence(
        step: InvestigationFlowStepSource,
        pool: List<String>,
        used: MutableSet<String>
    ): String {
        val note = nonEmpty(step.note)
        if (note != null) {
            val first = splitSentences(note).firstOrNull() ?: note
            if (first.lowercase() != step.title.lowercase()) {
                used.add(first.lowercase())
                return ensuringPeriod(first)
            }
        }
        val codes = messageCodes(step.title)
        val keywords = distinctiveWords(step.title)
        var best: Pair<String, Int>? = null
        for (sentence in pool) {
            val key = sentence.lowercase()
            if (key in used || key == step.title.lowercase()) continue
            var score = 0
            val lower = sentence.lowercase()
            for (code in codes) {
                if (lower.contains(code.lowercase())) score += 2
            }
            for (word in keywords) {
                if (lower.contains(word)) score += 1
            }
            if (score > 0 && (best == null || score > best.second)) {
                best = Pair(sentence, score)
            }
        }
        if (best != null) {
            used.add(best.first.lowercase())
            return ensuringPeriod(best.first)
        }
        return "The case records this step in the flow but gives no further detail about it."
    }

    /** Distinctive words from a step title: words of four or more
     * letters, lowercased, without the generic words that appear
     * in almost every step and would match the wrong sentences. */
    private fun distinctiveWords(title: String): List<String> {
        val stopwords = setOf(
            "that", "this", "with", "from", "were", "been", "have",
            "would", "could", "should", "after", "before", "through",
            "sent", "never", "status", "report", "request", "return",
            "arrived", "checked", "raised", "payment", "under"
        )
        return title.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 && it !in stopwords }
    }

    private fun hasStatedAmount(header: InvestigationPaymentHeader): Boolean =
        header.amountText != NOT_STATED_AMOUNT_TEXT

    private fun messageEvidence(
        code: String,
        index: Int,
        caseId: String,
        header: InvestigationPaymentHeader
    ): InvestigationEvidence {
        val purpose = IsoCatalogue.messages.firstOrNull { it.id.lowercase() == code.lowercase() }?.purpose
            ?: "The case names this message. Check the case text for what it proves in this investigation."
        val lines = mutableListOf("Message: $code")
        if (hasStatedAmount(header)) {
            lines.add("Amount: ${header.amountText}")
        }
        lines.add("From: the sending bank")
        lines.add("To: the beneficiary bank")
        if (header.referenceText != "Reference not stated in the case") {
            lines.add("Reference: ${header.referenceText}")
        }
        return InvestigationEvidence(
            id = "$caseId-message-${index + 1}",
            kind = InvestigationEvidenceKind.MESSAGE,
            title = code,
            body = purpose,
            structuredLines = lines,
            sourceField = "messages",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    /** One log item per case. When the case has flow steps, the
     * log carries exactly one line per flow step, in the same
     * order and with the same sequence labels as the timeline
     * (see sequenceLabel): the first step is T+0 in both, and
     * the same event carries the same label in both. The lines
     * are the step titles from the case text, so the log can
     * never drift from the timeline. When the case has no flow
     * steps, template lines use the message codes and the
     * derived amount with the same shared labels (T+0 and so
     * on, never clock times). */
    private fun logEvidence(
        caseId: String,
        flowSteps: List<InvestigationFlowStepSource>,
        timelineSourceField: String,
        messages: List<String>,
        header: InvestigationPaymentHeader
    ): InvestigationEvidence {
        if (flowSteps.isNotEmpty()) {
            val lines = flowSteps.mapIndexed { offset, step ->
                "${sequenceLabel(offset)} ${ensuringPeriod(step.title)}"
            }
            return InvestigationEvidence(
                id = "$caseId-log-1",
                kind = InvestigationEvidenceKind.LOG,
                title = "Processing log",
                body = "Log lines drawn from the case flow steps. Sequence markers are illustrative only; no clock times are stated.",
                structuredLines = lines,
                sourceField = timelineSourceField,
                revealedAfterStep = 0,
                completeness = InvestigationEvidenceCompleteness.Complete,
                isRedHerring = false
            )
        }
        val firstCode = messages.firstOrNull()
        val secondCode = if (messages.size > 1) messages[1] else null
        val lines = listOf(
            "${sequenceLabel(0)} Instruction received from the customer. Amount: ${amountShortText(header)}. SAMPLE.",
            "${sequenceLabel(1)} Sent: ${firstCode ?: "no message code is named in the case"}.",
            "${sequenceLabel(2)} Checked: ${secondCode ?: "no further message code is named in the case"}.",
            "${sequenceLabel(3)} Outcome: not stated in the case."
        )
        return InvestigationEvidence(
            id = "$caseId-log-1",
            kind = InvestigationEvidenceKind.LOG,
            title = "Processing log (SAMPLE)",
            body = "SAMPLE processing log. Sequence markers are illustrative only; no clock times are stated in the case.",
            structuredLines = lines,
            sourceField = "messages",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    private fun triggersLedger(explain: String, domain: String?): Boolean {
        val lowerDomain = domain?.lowercase() ?: ""
        val domainTrigger = listOf("clearing", "settlement", "reconciliation", "ledger", "accounting")
            .any { lowerDomain.contains(it) }
        val lowerExplain = explain.lowercase()
        return domainTrigger || lowerExplain.contains("ledger") || lowerExplain.contains("settlement")
    }

    private fun ledgerEvidence(caseId: String, header: InvestigationPaymentHeader): InvestigationEvidence {
        val stated = hasStatedAmount(header)
        val body = if (stated) {
            "SAMPLE ledger entries built only from the case amount. No account numbers are stated in the case."
        } else {
            "SAMPLE ledger entries. The case states no amount and no account numbers."
        }
        val lines = if (stated) {
            listOf(
                "Debit: the customer account, ${header.amountText}",
                "Credit: the settlement account, ${header.amountText}",
                "Values: SAMPLE"
            )
        } else {
            listOf(
                "Debit: the customer account",
                "Credit: the settlement account",
                "Values: SAMPLE"
            )
        }
        return InvestigationEvidence(
            id = "$caseId-ledger-1",
            kind = InvestigationEvidenceKind.LEDGER,
            title = "Ledger entries (SAMPLE)",
            body = body,
            structuredLines = lines,
            sourceField = "domain",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    private fun triggersRecon(explain: String, failureCategory: String?): Boolean {
        val categoryTrigger = failureCategory?.lowercase()?.contains("reconcil") ?: false
        return categoryTrigger || explain.lowercase().contains("reconciliation")
    }

    private fun reconEvidence(caseId: String, header: InvestigationPaymentHeader): InvestigationEvidence {
        val amountLine = if (hasStatedAmount(header)) {
            "Bank record amount: ${header.amountText}"
        } else {
            "Bank record amount: not stated in the case"
        }
        return InvestigationEvidence(
            id = "$caseId-recon-1",
            kind = InvestigationEvidenceKind.RECON,
            title = "Reconciliation break (SAMPLE)",
            body = "SAMPLE reconciliation break. The case does not state the matching records.",
            structuredLines = listOf(amountLine, "Values: SAMPLE"),
            sourceField = "failure_category",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    private fun triggersApi(explain: String): Boolean =
        containsMatch("\\bapi\\b", explain, ignoreCase = true)

    private fun apiEvidence(caseId: String, header: InvestigationPaymentHeader): InvestigationEvidence {
        val lines = mutableListOf("Request: status check for the payment (SAMPLE)")
        if (hasStatedAmount(header)) {
            lines.add("Amount: ${header.amountText}")
        }
        lines.add("Response: not stated in the case")
        return InvestigationEvidence(
            id = "$caseId-api-1",
            kind = InvestigationEvidenceKind.API,
            title = "API request and response (SAMPLE)",
            body = "SAMPLE request and response for the API named in the case.",
            structuredLines = lines,
            sourceField = "explain",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    private fun clearingEvidence(
        caseId: String,
        explain: String,
        header: InvestigationPaymentHeader
    ): InvestigationEvidence? {
        val matchedSystem = CLEARING_SYSTEMS.firstOrNull { containsWholeWord(it, explain) }
        if (!explain.lowercase().contains("clearing") && matchedSystem == null) return null
        val lines = mutableListOf<String>()
        if (matchedSystem != null) {
            lines.add("Clearing system: $matchedSystem")
        }
        lines.add("Status: not stated in the case")
        if (hasStatedAmount(header)) {
            lines.add("Amount: ${header.amountText}")
        }
        lines.add("Values: SAMPLE")
        return InvestigationEvidence(
            id = "$caseId-clearing-1",
            kind = InvestigationEvidenceKind.CLEARING,
            title = "Clearing status (SAMPLE)",
            body = "SAMPLE clearing status record.",
            structuredLines = lines,
            sourceField = "explain",
            revealedAfterStep = 0,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = false
        )
    }

    /** Expert difficulty: the last message evidence item, or the last
     * evidence item when no message evidence exists, starts partial
     * with the systemic reason. All other difficulties keep every
     * item complete. */
    private fun applyingExpertCompleteness(
        evidence: List<InvestigationEvidence>,
        difficulty: InvestigationDifficulty
    ): List<InvestigationEvidence> {
        if (difficulty != InvestigationDifficulty.EXPERT || evidence.isEmpty()) return evidence
        val targetIndex = evidence.indexOfLast { it.kind == InvestigationEvidenceKind.MESSAGE }
            .takeIf { it >= 0 } ?: (evidence.size - 1)
        val updated = evidence.toMutableList()
        updated[targetIndex] = updated[targetIndex].withCompleteness(
            InvestigationEvidenceCompleteness.Partial(reason = "available only after a targeted status request")
        )
        return updated
    }

    private fun amountShortText(header: InvestigationPaymentHeader): String =
        if (header.amountText == NOT_STATED_AMOUNT_TEXT) "not stated in the case" else header.amountText

    private fun orderedKinds(evidence: List<InvestigationEvidence>): List<InvestigationEvidenceKind> {
        val kinds = mutableListOf<InvestigationEvidenceKind>()
        for (item in evidence) {
            if (item.kind !in kinds) kinds.add(item.kind)
        }
        return kinds
    }

    // MARK: - Hypotheses (rule 15)

    /** Exactly three hypotheses, all competing candidate
     * explanations at the same level of specificity, in plain
     * mechanism language: the flawed requirement phrased as the
     * assumption the team acted on, the root cause phrased as a
     * mechanism claim, and the category alternative. No option
     * may contain an ISO or SWIFT message code, quote the
     * report root-cause sentence, or share any run of 8 or more
     * words with it; a candidate that breaks any of these rules
     * falls back to its category template, so the answer can
     * never leak through the options. The order is shuffled
     * deterministically by case id. */
    private fun buildHypotheses(
        caseId: String,
        assumptionCore: String,
        rootCauseFirstSentence: String,
        category: InvestigationCategoryKey,
        diagnosisText: String
    ): List<InvestigationHypothesis> {
        val used = mutableListOf<String>()

        val assumptionText = firstSafeCandidate(
            listOf(
                "The team assumed: ${hypothesisSafeSentence(assumptionCore)}",
                "The team assumed: ${templateAssumption(category)}"
            ),
            diagnosisText = diagnosisText,
            used = used
        )
        used.add(assumptionText.lowercase())
        val first = InvestigationHypothesis(
            id = "$caseId-hyp-1",
            text = ensuringPeriod(assumptionText),
            isCorrect = false,
            source = InvestigationHypothesisSource.FLAWED_REQUIREMENT
        )

        val causeText = firstSafeCandidate(
            listOf(
                hypothesisSafeSentence(rootCauseFirstSentence),
                correctHypothesisTemplate(category)
            ),
            diagnosisText = diagnosisText,
            used = used
        )
        used.add(causeText.lowercase())
        val second = InvestigationHypothesis(
            id = "$caseId-hyp-2",
            text = ensuringPeriod(causeText),
            isCorrect = true,
            source = InvestigationHypothesisSource.ROOT_CAUSE
        )

        val alternativeText = firstSafeCandidate(
            alternativeCandidates(category),
            diagnosisText = diagnosisText,
            used = used
        )
        val third = InvestigationHypothesis(
            id = "$caseId-hyp-3",
            text = ensuringPeriod(alternativeText),
            isCorrect = false,
            source = InvestigationHypothesisSource.CATEGORY_ALTERNATIVE
        )
        return InvestigationDeterminism.deterministicShuffle(
            listOf(first, second, third),
            seed = InvestigationDeterminism.stableSeed(caseId)
        )
    }

    /** One case sentence made safe for use as a hypothesis
     * option: quotes stripped, every message code replaced with
     * a generic phrase, capitalised, with a final full stop.
     * Safety against the diagnosis (no codes left, no long
     * shared run) is checked by the caller. */
    private fun hypothesisSafeSentence(sentence: String): String {
        val stripped = strippingMessageCodes(sentence.replace("\"", ""))
            .trim()
        if (stripped.isEmpty()) return ""
        return ensuringPeriod(capitalizedFirst(stripped))
    }

    /** Replaces every ISO 20022 and MT message code in the text
     * with a generic phrase, keeping any article that already
     * precedes the code ("a pacs.008" becomes "a payment
     * message"; a bare code becomes "the payment message"), so
     * hypothesis options never name a message code. */
    private fun strippingMessageCodes(text: String): String {
        val codePattern = "pacs\\.\\d{3}|pain\\.\\d{3}|camt\\.\\d{3}|MT\\d{3}"
        var result = text
        val withArticle = Regex("\\b(a|an|the)\\s+($codePattern)\\b", RegexOption.IGNORE_CASE)
        result = withArticle.replace(result) { match -> "${match.groupValues[1]} payment message" }
        val bare = Regex("\\b($codePattern)\\b", RegexOption.IGNORE_CASE)
        result = bare.replace(result) { "the payment message" }
        return result
    }

    /** The words of a text: lowercase alphanumeric tokens, so
     * punctuation and capitalisation never hide a shared run. */
    private fun words(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    /** The longest run of words the two texts share verbatim, in
     * order. A hypothesis option must stay below 8 against the
     * report root-cause sentence. */
    private fun longestSharedWordRun(first: String, second: String): Int {
        val firstWords = words(first)
        val secondWords = words(second)
        if (firstWords.isEmpty() || secondWords.isEmpty()) return 0
        var previous = IntArray(secondWords.size + 1)
        var longest = 0
        for (i in 1..firstWords.size) {
            val current = IntArray(secondWords.size + 1)
            for (j in 1..secondWords.size) {
                if (firstWords[i - 1] == secondWords[j - 1]) {
                    current[j] = previous[j - 1] + 1
                    longest = maxOf(longest, current[j])
                }
            }
            previous = current
        }
        return longest
    }

    /** True when a hypothesis candidate is safe to show: it says
     * something (at least four words), names no message code,
     * and shares no run of 8 or more words with the report
     * root-cause sentence. */
    private fun hypothesisCandidateIsSafe(candidate: String, diagnosisText: String): Boolean {
        if (words(candidate).size < 4) return false
        if (messageCodes(candidate).isNotEmpty()) return false
        return longestSharedWordRun(candidate, diagnosisText) < 8
    }

    /** The first candidate that is safe against the diagnosis
     * and not already used by another option, or the generic
     * fallback when every candidate fails. The fallback is
     * plain mechanism language with no case wording, so it
     * cannot quote the root cause. */
    private fun firstSafeCandidate(
        candidates: List<String>,
        diagnosisText: String,
        used: List<String>
    ): String {
        for (candidate in candidates) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty() || trimmed.lowercase() in used) continue
            if (hypothesisCandidateIsSafe(trimmed, diagnosisText)) {
                return trimmed
            }
        }
        return "The payment did not complete as expected, and the cause was never confirmed from the evidence."
    }

    /** The correct-candidate mechanism templates, used when the
     * case root-cause sentence cannot be phrased as a safe
     * option. Plain mechanism language, no message codes, one
     * per failure category. */
    private fun correctHypothesisTemplate(category: InvestigationCategoryKey): String =
        when (category) {
            InvestigationCategoryKey.REJECTED_OR_RETURNED ->
                "The payment failed at a later stage in the chain, and the failure was never traced back to its cause."
            InvestigationCategoryKey.DUPLICATE ->
                "The same payment was processed more than once because the first attempt was never confirmed."
            InvestigationCategoryKey.SANCTIONS ->
                "The payment was held by a screening check, and no one followed up on the hold."
            InvestigationCategoryKey.RECONCILIATION ->
                "The records in two systems disagreed, and the break was never investigated."
            InvestigationCategoryKey.OTHER ->
                "The payment was treated as complete once it was sent, and its outcome was never confirmed from the records."
        }

    /** The category alternative plus its fallback pool, in
     * preference order. The caller picks the first safe,
     * unused entry. */
    private fun alternativeCandidates(category: InvestigationCategoryKey): List<String> =
        listOf(
            categoryAlternative(category),
            categoryAlternative(InvestigationCategoryKey.OTHER),
            "A manual step in the process was missed.",
            "The payment was held for a check that was never completed.",
            "A timing difference between systems created a false break."
        )

    // MARK: - Decisions (rule 16)

    /** One decision per decision step found (at most four), plus
     * exactly one closing decision. When no decision step exists,
     * the first decision is the first-check decision. Minimum two
     * decisions, maximum five. Extra decision steps beyond four
     * stay in the timeline as events, not decisions. */
    private fun buildDerivedDecisions(
        caseId: String,
        decisionSteps: List<InvestigationFlowStepSource>,
        hasMessages: Boolean,
        evidenceKinds: List<InvestigationEvidenceKind>,
        correctiveOptionText: String,
        closingWhyCorrect: String,
        closingEvidenceNote: String
    ): List<InvestigationDecisionPoint> {
        val decisions = mutableListOf<InvestigationDecisionPoint>()
        if (decisionSteps.isEmpty()) {
            decisions.add(
                firstCheckDecision(
                    caseId = caseId,
                    index = 1,
                    hasMessages = hasMessages,
                    evidenceKinds = evidenceKinds
                )
            )
        } else {
            decisionSteps.forEachIndexed { offset, step ->
                decisions.add(stepDecision(caseId = caseId, index = offset + 1, step = step))
            }
        }
        decisions.add(
            closingDecision(
                caseId = caseId,
                index = decisions.size + 1,
                correctOptionText = correctiveOptionText,
                whyCorrect = closingWhyCorrect,
                evidenceNote = closingEvidenceNote
            )
        )
        return decisions
    }

    private fun stepDecision(
        caseId: String,
        index: Int,
        step: InvestigationFlowStepSource
    ): InvestigationDecisionPoint {
        val best = InvestigationDecisionOption(
            text = "Inspect the evidence for this step and confirm what it proves before moving on.",
            isBest = true,
            feedback = "Confirming each step from evidence is what separates an investigation from a guess."
        )
        val wrongs = listOf(
            InvestigationDecisionOption(
                text = "Assume this step completed because the earlier steps did.",
                isBest = false,
                feedback = "An earlier step completing proves nothing about this step.",
                consequence = "The investigation moves on without evidence for this step, and the fault stays hidden."
            ),
            InvestigationDecisionOption(
                text = "Skip this step and look only at the final outcome.",
                isBest = false,
                feedback = "The final outcome alone cannot show where the flow went wrong.",
                consequence = "Skipping the step leaves a gap the report cannot explain."
            ),
            InvestigationDecisionOption(
                text = "Ask the customer to explain what happened at this step.",
                isBest = false,
                feedback = "The customer cannot see inside the payment chain; the evidence must answer this.",
                consequence = "The customer is asked to answer a question only the evidence can settle."
            )
        )
        val (options, bestIndex) = shuffledOptions(listOf(best) + wrongs, seedText = "$caseId-dec-$index")
        val note = nonEmpty(step.note)
        val whyCorrect = if (note != null) {
            "The case evidence for this step says: $note"
        } else {
            "Each step in the flow must be confirmed from evidence before the investigation moves on."
        }
        return InvestigationDecisionPoint(
            id = "$caseId-dec-$index",
            prompt = "The flow reaches a decision point: ${step.title}. What should the investigator do next?",
            options = options,
            bestOptionIndex = bestIndex,
            whyCorrect = whyCorrect,
            evidenceNote = "The timeline shows this step as: ${step.title}.",
            hypothesisPrompt = "Does this step change your leading hypothesis?",
            isTechnical = mentionsMessageOrSystem(step.title + " " + (step.note ?: ""))
        )
    }

    private fun firstCheckDecision(
        caseId: String,
        index: Int,
        hasMessages: Boolean,
        evidenceKinds: List<InvestigationEvidenceKind>
    ): InvestigationDecisionPoint {
        val bestText = if (hasMessages) {
            "Start with the timeline, then check the message evidence."
        } else {
            "Start with the timeline and confirm each step from the evidence."
        }
        val best = InvestigationDecisionOption(
            text = bestText,
            isBest = true,
            feedback = "The timeline shows what happened in order, and the evidence for each step replaces guesses with facts."
        )
        val wrongs = listOf(
            InvestigationDecisionOption(
                text = "Ask the customer to send the payment again.",
                isBest = false,
                feedback = "Resending before the cause is known risks paying twice.",
                consequence = "The customer may pay twice for the same payment."
            ),
            InvestigationDecisionOption(
                text = "Wait for the next processing window and check again.",
                isBest = false,
                feedback = "Waiting adds no new evidence.",
                consequence = "The customer waits without an answer, and the cause stays unknown."
            ),
            InvestigationDecisionOption(
                text = "Start with the final outcome and skip the timeline.",
                isBest = false,
                feedback = "The final outcome alone cannot show where the flow went wrong.",
                consequence = "The investigation starts without the order of events it needs."
            )
        )
        val (options, bestIndex) = shuffledOptions(listOf(best) + wrongs, seedText = "$caseId-dec-$index")
        val kindsList = evidenceKinds.joinToString(", ") { it.displayName }
        return InvestigationDecisionPoint(
            id = "$caseId-dec-$index",
            prompt = "What should the investigator check first?",
            options = options,
            bestOptionIndex = bestIndex,
            whyCorrect = "The timeline shows what happened in order, and the evidence for each step replaces guesses with facts.",
            evidenceNote = "Evidence in this case: $kindsList.",
            hypothesisPrompt = "Does the timeline change your leading hypothesis?"
        )
    }

    /** The closing decision, built from the real requirement
     * (correct) plus the three category template distractors. */
    private fun closingDecision(
        caseId: String,
        index: Int,
        correctOptionText: String,
        whyCorrect: String,
        evidenceNote: String
    ): InvestigationDecisionPoint {
        val best = InvestigationDecisionOption(
            text = correctOptionText,
            isBest = true,
            feedback = "This is the fix the case states."
        )
        val wrongs = CORRECTIVE_DISTRACTORS.map {
            InvestigationDecisionOption(
                text = it.text,
                isBest = false,
                feedback = it.feedback,
                consequence = it.consequence
            )
        }
        val (options, bestIndex) = shuffledOptions(listOf(best) + wrongs, seedText = "$caseId-dec-$index")
        return InvestigationDecisionPoint(
            id = "$caseId-dec-$index",
            prompt = "What is the right corrective action?",
            options = options,
            bestOptionIndex = bestIndex,
            whyCorrect = whyCorrect,
            evidenceNote = evidenceNote,
            isResolution = true
        )
    }

    private fun shuffledOptions(
        options: List<InvestigationDecisionOption>,
        seedText: String
    ): Pair<List<InvestigationDecisionOption>, Int> {
        val shuffled = InvestigationDeterminism.deterministicShuffle(
            options,
            seed = InvestigationDeterminism.stableSeed(seedText)
        )
        val bestIndex = shuffled.indexOfFirst { it.isBest }.takeIf { it >= 0 } ?: 0
        return Pair(shuffled, bestIndex)
    }

    /** True when the text names a message code or a system concept:
     * the message and system decisions counted by the
     * technicalAccuracy dimension. */
    private fun mentionsMessageOrSystem(text: String): Boolean {
        if (messageCodes(text).isNotEmpty()) return true
        val lower = text.lowercase()
        if (lower.contains("system") || lower.contains("clearing") || lower.contains("ledger") ||
            lower.contains("validation") || lower.contains("character set")
        ) {
            return true
        }
        return containsWholeWord("API", text)
    }

    // MARK: - Authored tier mapping

    private fun authoredEvidence(item: ExceptionEvidence): InvestigationEvidence {
        val namesMessage = messageCodes(item.title + " " + item.detail).isNotEmpty()
        return InvestigationEvidence(
            id = item.id,
            kind = if (namesMessage) InvestigationEvidenceKind.MESSAGE else InvestigationEvidenceKind.NOTE,
            title = item.title,
            body = item.detail,
            structuredLines = emptyList(),
            sourceField = "authored",
            revealedAfterStep = item.revealedAfterStep,
            completeness = InvestigationEvidenceCompleteness.Complete,
            isRedHerring = item.isRedHerring
        )
    }

    private fun authoredDecision(
        step: InvestigationStep,
        isLast: Boolean
    ): InvestigationDecisionPoint {
        val options = step.options.mapIndexed { optionIndex, option ->
            InvestigationDecisionOption(
                text = option.text,
                isBest = optionIndex == step.bestOptionIndex,
                feedback = option.feedback,
                consequence = if (optionIndex == step.bestOptionIndex) "" else step.wrongTurnCost,
                evidenceNote = null,
                isRedHerring = option.isRedHerring
            )
        }
        val technicalText = step.prompt + " " + step.options.joinToString(" ") { it.text }
        return InvestigationDecisionPoint(
            id = step.id,
            prompt = step.prompt,
            options = options,
            bestOptionIndex = step.bestOptionIndex,
            whyCorrect = step.whyCorrect,
            evidenceNote = step.evidenceNote,
            hypothesisPrompt = if (isLast) null else "Does this step change your leading hypothesis?",
            isTechnical = mentionsMessageOrSystem(technicalText),
            isResolution = isLast
        )
    }

    private fun authoredAssumptionCore(
        labCase: ExceptionCase,
        category: InvestigationCategoryKey
    ): String {
        val herring = labCase.evidence.firstOrNull { it.isRedHerring }
        if (herring != null) {
            val first = InvestigationDeterminism.firstSentence(herring.detail)
            if (first.isNotEmpty()) return first
        }
        val firstStep = labCase.steps.firstOrNull()
        if (firstStep != null) {
            val wrong = firstStep.options.withIndex().firstOrNull { it.index != firstStep.bestOptionIndex }
            if (wrong != null) {
                val first = InvestigationDeterminism.firstSentence(wrong.value.text)
                if (first.isNotEmpty()) return first
            }
        }
        return templateAssumption(category)
    }

    private fun authoredBusinessContext(labCase: ExceptionCase): String {
        val first = InvestigationDeterminism.firstSentence(labCase.narrative)
        if (first.isNotEmpty()) return "${labCase.incidentType}. $first"
        return labCase.incidentType
    }

    // MARK: - Templates (plan section 7, verbatim unless noted)

    /** The three fixed investigation objectives, shown verbatim for
     * every case (rule 14). */
    private val objectives: List<String> = listOf(
        "Establish what happened to the payment.",
        "Identify the root cause from the evidence.",
        "Recommend the corrective action and the control that prevents recurrence."
    )

    private val CURRENCY_CODES: List<String> = listOf(
        "EUR", "USD", "GBP", "INR", "JPY", "CHF", "AUD", "CAD", "SGD", "HKD", "AED"
    )

    private val CLEARING_SYSTEMS: List<String> = listOf(
        "TARGET2", "CHAPS", "Fedwire", "SEPA", "RTGS", "NEFT", "IMPS",
        "Faster Payments", "ACH", "BACS", "CHIPS", "FedNow", "UPI"
    )

    private const val NOT_STATED_AMOUNT_TEXT = "Amount not stated in the case"

    /** Category alternatives (H3) by failure category, verbatim. */
    private fun categoryAlternative(category: InvestigationCategoryKey): String =
        when (category) {
            InvestigationCategoryKey.REJECTED_OR_RETURNED ->
                "A downstream system rejected the payment for a reason unrelated to the instruction."
            InvestigationCategoryKey.DUPLICATE ->
                "The payment was sent twice because the first attempt timed out."
            InvestigationCategoryKey.SANCTIONS ->
                "A sanctions list update held the payment after it was sent."
            InvestigationCategoryKey.RECONCILIATION ->
                "A timing difference between systems created a false break."
            InvestigationCategoryKey.OTHER ->
                "A fault in a downstream system stopped the payment after it left the sending bank."
        }

    /** Customer impact sentences by failure category, verbatim. */
    private fun impactSentence(category: InvestigationCategoryKey): String =
        when (category) {
            InvestigationCategoryKey.REJECTED_OR_RETURNED ->
                "The customer expected the payment to arrive and had to chase its status."
            InvestigationCategoryKey.DUPLICATE ->
                "The customer was debited more than once for the same payment."
            InvestigationCategoryKey.SANCTIONS ->
                "The customer's payment was held without an explanation the customer could act on."
            InvestigationCategoryKey.RECONCILIATION ->
                "The customer's balance and the bank's records did not agree."
            InvestigationCategoryKey.OTHER ->
                "The customer could not confirm whether the payment had completed."
        }

    /** Corrective-action distractors, verbatim, each with feedback
     * and a narrative consequence for a wrong pick. */
    private data class CorrectiveDistractor(
        val text: String,
        val feedback: String,
        val consequence: String
    )

    private val CORRECTIVE_DISTRACTORS: List<CorrectiveDistractor> = listOf(
        CorrectiveDistractor(
            "Ask the customer to send the payment again.",
            "Resending before the cause is known risks paying twice.",
            "The customer may pay twice for the same payment."
        ),
        CorrectiveDistractor(
            "Wait for the next processing window and check again.",
            "Waiting adds no new evidence.",
            "The customer waits without an answer, and the cause stays unknown."
        ),
        CorrectiveDistractor(
            "Raise a manual adjustment without investigating the cause.",
            "An adjustment without a cause can mask the fault.",
            "The fault stays in place and the next payment can fail the same way."
        )
    )

    /** Generic assumption continuations used only when the case text
     * carries no flawed requirement and no Why? section (rule 4
     * fallback). These are engine templates, not case facts; the
     * hypothesis builder prefixes them with "The team assumed:". */
    private fun templateAssumption(category: InvestigationCategoryKey): String =
        when (category) {
            InvestigationCategoryKey.REJECTED_OR_RETURNED ->
                "a failed payment could be handled the same way at every stage"
            InvestigationCategoryKey.DUPLICATE ->
                "a file received from the customer had already been checked"
            InvestigationCategoryKey.SANCTIONS ->
                "the payment would pass screening without a hold"
            InvestigationCategoryKey.RECONCILIATION ->
                "the records in both systems would agree"
            InvestigationCategoryKey.OTHER ->
                "the payment would complete once it was sent"
        }

    // MARK: - Small helpers

    /** The trimmed string, or null when it is null or empty. */
    private fun nonEmpty(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return if (trimmed.isEmpty()) null else trimmed
    }

    private fun containsMatch(pattern: String, text: String, ignoreCase: Boolean = false): Boolean {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(pattern, options).containsMatchIn(text)
    }

    private fun containsWholeWord(word: String, text: String): Boolean {
        val escaped = Regex.escape(word)
        return containsMatch("\\b$escaped\\b", text, ignoreCase = true)
    }

    /** The capture groups of the first match, or null when the
     * pattern does not match. Groups that did not participate are
     * empty strings. */
    private fun firstMatchGroups(pattern: String, text: String, ignoreCase: Boolean = false): List<String>? {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val match = Regex(pattern, options).find(text) ?: return null
        return match.groupValues.drop(1)
    }

    /** Every full match of the pattern, in order of appearance. */
    private fun allMatches(pattern: String, text: String, ignoreCase: Boolean = false): List<String> {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(pattern, options).findAll(text).map { it.value }.toList()
    }
}
