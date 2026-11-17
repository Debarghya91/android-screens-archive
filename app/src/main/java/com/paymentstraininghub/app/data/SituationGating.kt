package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.Situation
import java.time.LocalDate

// SituationGating.kt
// The single shared gating logic for Payment Situations, ported from
// iOS v69/v70: the D101 hard-gate predicate in SituationDetailView,
// the eligible pools in DailyChallengeView and PracticeModeBuilder,
// and the grounding gate in ChatAnswerPipeline. Every surface that
// shows situation content must go through these pure functions, so
// the rule cannot drift between screens. Nothing here reads
// entitlement state: callers pass isSituationsUnlocked from
// BillingManager, which folds Play ownership, server entitlement
// and the full-course umbrella into one boolean.
//
// Free pool: the 2 free sample situations plus this week's free
// case (3 items at most). A nil or unknown weekly case id leaves
// only the free samples: the gate never invents a situation and
// never falls back to locked content.
// No em dashes in any user-facing text.

object SituationGating {

    /** The 2 free sample situation ids, from the single source of truth in ContentRepository. */
    val freeSampleIds: Set<String> get() = ContentRepository.freeSampleSituationIds.toSet()

    /**
     * The situation ids a non-entitled learner may see: the free
     * samples plus this week's free case, when one is known.
     * Mirrors ChatAnswerPipeline.eligibleSituationIDs.
     */
    fun eligibleSituationIds(
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Set<String> {
        return if (weeklyCaseId.isNullOrBlank()) {
            freeSampleIds
        } else {
            freeSampleIds + weeklyCaseId
        }
    }

    /**
     * True when a situation id is locked for this learner: it is a
     * situation, it is not in the free pool, and Payment Situations
     * is not unlocked.
     */
    fun isLockedSituationId(
        id: String,
        isSituation: Boolean,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Boolean {
        if (!isSituation || isSituationsUnlocked) return false
        return id !in eligibleSituationIds(weeklyCaseId, freeSampleIds)
    }

    /**
     * True when a situation is locked for this learner, by id and
     * free-sample flag: not a free sample, not this week's free
     * case, and Practice Experiences not unlocked. A situation
     * counts as a free sample when its flag is set or its id is
     * in the free sample list, so a situation built by either
     * constructor is recognised either way. Mirrors
     * PracticeModeBuilder.isLocked.
     */
    fun isSituationLocked(
        situation: Situation,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Boolean {
        if (isSituationsUnlocked) return false
        if (situation.isFreeSample || situation.id in freeSampleIds) return false
        if (weeklyCaseId != null && situation.id == weeklyCaseId) return false
        return true
    }

    /**
     * The D101 hard-gate predicate for the situation detail: locked
     * when the situation is not a free sample, not this week's
     * free case on the weekly path, and Payment Situations is not
     * unlocked. The weekly exemption is path-scoped on purpose:
     * the same situation opened from the list, search or any other
     * path stays gated, because only the weekly detail host passes
     * isWeeklyFreeCase. Mirrors SituationDetailView.isLocked.
     */
    fun isDetailLocked(
        isFreeSample: Boolean,
        isWeeklyFreeCase: Boolean,
        isSituationsUnlocked: Boolean
    ): Boolean {
        return !isFreeSample && !isWeeklyFreeCase && !isSituationsUnlocked
    }

    /**
     * The situations a learner may see in a gated surface.
     * Entitled learners get the input unchanged, in catalogue
     * order. Everyone else gets only the free samples plus this
     * week's free case. Mirrors DailyChallengeView.eligiblePool
     * and PracticeModeBuilder.eligiblePool (the same rule).
     */
    fun eligiblePool(
        situations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): List<Situation> {
        if (isSituationsUnlocked) return situations
        return situations.filter { situation ->
            if (situation.isFreeSample || situation.id in freeSampleIds) return@filter true
            weeklyCaseId != null && situation.id == weeklyCaseId
        }
    }

    /**
     * True when a search index entry is a locked situation: a
     * situation, not a free sample, and Payment Situations not
     * unlocked. Search rows show teaser fields only for locked
     * situations. Mirrors SearchView.SearchResultRow.isLockedSituation.
     * Note: the weekly case is NOT exempt in search, matching iOS:
     * search never passes the weekly-path flag.
     */
    fun isLockedSituationEntry(
        entry: SearchEntry,
        isSituationsUnlocked: Boolean,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Boolean {
        return entry.isSituation &&
            entry.id !in freeSampleIds &&
            !isSituationsUnlocked
    }

    /**
     * First sentence of a takeaway, capped so an option stays
     * readable. Same rule as DailyChallengeView and
     * PracticeModeBuilder. Returns "" for empty text, so callers
     * can reject too-short options.
     */
    fun firstSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val dotSpace = trimmed.indexOf(". ")
        if (dotSpace >= 0) {
            val first = trimmed.substring(0, dotSpace)
            if (first.length >= 20) return "$first."
        }
        if (trimmed.length > 200) {
            return trimmed.substring(0, 200) + "..."
        }
        return trimmed
    }

    /**
     * Approximate read time for text content, at 200 words per
     * minute, rounded up, at least 1 minute. Computed from the
     * actual text, never guessed: empty text has no estimate.
     * Delegates to [ReadTime], the single rate for the app.
     * Mirrors ReadTime.label in SharedChapterViews.swift.
     */
    fun readTimeLabel(text: String): String? = ReadTime.label(text)
}

/**
 * Daily Challenge gating and deterministic pick, ported from
 * DailyChallengeView.swift (v70). The challenge itself has no
 * Android screen in v1: this object is the pure logic, delivered
 * so the screen can be built on it without re-deriving the rule.
 * See GATING-NOTES.md for the gap.
 *
 * The pick: today's situation is eligiblePool[dayNumber % poolSize],
 * where dayNumber is days since the epoch in the local calendar.
 * For non-entitled learners the day number cycles within the small
 * free pool, so the same free case repeats across days: that is
 * the documented fallback for the small pool, never a fallback to
 * locked content. Options are the situation's own takeaway plus
 * three other real takeaways from the gated pool, shuffled
 * deterministically by the day number.
 */
object DailyChallengeGate {

    /** Days since the epoch, local calendar: the deterministic seed. Mirrors DailyChallengeView.dayNumber. */
    fun dayNumber(date: LocalDate = LocalDate.now()): Long = date.toEpochDay()

    /** The gated pick pool. Entitled learners get the full list unchanged. */
    fun eligiblePool(
        situations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?
    ): List<Situation> = SituationGating.eligiblePool(situations, isSituationsUnlocked, weeklyCaseId)

    /** Today's situation, deterministic by day. Null when the pool is empty. */
    fun todaysSituation(pool: List<Situation>, dayNumber: Long): Situation? {
        if (pool.isEmpty()) return null
        val size = pool.size.toLong()
        val index = ((dayNumber % size) + size) % size
        return pool[index.toInt()]
    }

    /** One answer option: real takeaway text, and whether it is today's situation's own. */
    data class ChallengeOption(val text: String, val isCorrect: Boolean)

    /**
     * The four answer options: today's takeaway plus three other
     * real takeaways from the gated pool, shuffled deterministically
     * by the day number. Every option comes from the gated pool, so
     * a non-entitled learner never sees a locked takeaway as a
     * distractor. Mirrors DailyChallengeView.options exactly,
     * including the small-pool fill and the LCG shuffle.
     */
    fun options(pool: List<Situation>, today: Situation, dayNumber: Long): List<ChallengeOption> {
        if (pool.isEmpty()) return emptyList()
        val result = mutableListOf(
            ChallengeOption(SituationGating.firstSentence(today.takeaway).ifBlank { "See the situation takeaway." }, true)
        )
        val offsets = listOf(97L, 211L, 331L)
        for (offset in offsets) {
            val other = pool[((dayNumber + offset) % pool.size.toLong()).toInt()]
            if (other.id == today.id) continue
            val text = SituationGating.firstSentence(other.takeaway)
            if (text.isBlank()) continue
            if (result.none { it.text == text }) {
                result.add(ChallengeOption(text, false))
            }
        }
        if (result.size < 4) {
            var step = 1L
            while (result.size < 4 && step <= pool.size) {
                val other = pool[((dayNumber + step) % pool.size.toLong()).toInt()]
                step += 1
                if (other.id == today.id) continue
                val text = SituationGating.firstSentence(other.takeaway)
                if (text.isBlank()) continue
                if (result.none { it.text == text }) {
                    result.add(ChallengeOption(text, false))
                }
            }
        }
        val shuffled = result.toMutableList()
        // The iOS shuffle runs on an unsigned 64-bit seed with the
        // same LCG constants; ULong arithmetic wraps the same way,
        // so the order matches iOS for the same day number.
        var seed: ULong = (dayNumber + 1).toULong()
        for (index in shuffled.size - 1 downTo 1) {
            seed = seed * 6364136223846793005u + 1442695040888963407u
            val swap = (seed % (index + 1).toULong()).toInt()
            val temp = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = temp
        }
        return shuffled
    }

    /**
     * The scenario text: the opening of the explain, before the
     * structured breakdown, so the question does not give the
     * answer away. Mirrors DailyChallengeView.scenarioText.
     */
    fun scenarioText(explain: String): String {
        val paragraphs = explain.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val opening = paragraphs.take(2).joinToString("\n\n")
        return if (opening.isEmpty()) explain else opening
    }
}

/**
 * Practice Mode gating and decision builder, ported from
 * PracticeMode.swift (PracticeModeBuilder, v70). Practice Mode has
 * no Android screen in v1: this object is the pure logic, delivered
 * so the screen can be built on it without re-deriving the rule.
 * See GATING-NOTES.md for the gap. The BA/PO studio is a different
 * feature and keeps its own StudioGating; it gains the weekly-case
 * exemption separately.
 */
object PracticeModeGate {

    /** One practice decision: the prompt, four options from real takeaways, and which is the situation's own. */
    data class PracticeDecision(
        val id: String,
        val situationId: String,
        val prompt: String,
        val options: List<String>,
        val correctOptionIndex: Int,
        val explanation: String
    )

    /** The situations a learner may practise. Mirrors PracticeModeBuilder.eligiblePool. */
    fun eligiblePool(
        allSituations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): List<Situation> =
        SituationGating.eligiblePool(allSituations, isSituationsUnlocked, weeklyCaseId, freeSampleIds)

    /** True when Practice Mode must not show this situation's content. Mirrors PracticeModeBuilder.isLocked. */
    fun isLocked(
        situation: Situation,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Boolean =
        SituationGating.isSituationLocked(situation, isSituationsUnlocked, weeklyCaseId, freeSampleIds)

    /** True when a situation can be practised: a decision can be built for it from the full list. */
    fun isEligible(situation: Situation, allSituations: List<Situation>): Boolean =
        decision(situation, allSituations) != null

    /** True when a situation can be practised by this learner, from the gated pool alone. */
    fun isEligible(
        situation: Situation,
        allSituations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds
    ): Boolean =
        decision(situation, allSituations, isSituationsUnlocked, weeklyCaseId, freeSampleIds) != null

    /**
     * Build the decision for one situation from the eligible pool
     * only, or null when the situation is locked for this learner
     * or no decision can be built from the pool. Never falls back
     * to locked content. Mirrors PracticeModeBuilder.decision
     * (the gated overload).
     */
    fun decision(
        situation: Situation,
        allSituations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?,
        freeSampleIds: Set<String> = SituationGating.freeSampleIds,
        seedSalt: ULong = 0u
    ): PracticeDecision? {
        val pool = eligiblePool(allSituations, isSituationsUnlocked, weeklyCaseId, freeSampleIds)
        val focal = pool.firstOrNull { it.id == situation.id } ?: return null
        return decision(focal, pool, seedSalt)
    }

    /**
     * Build the decision for one situation, or null when it is not
     * eligible. [seedSalt] lets the recall question reuse the same
     * option pool in a different order. Mirrors
     * PracticeModeBuilder.decision (the ungated overload).
     */
    fun decision(
        situation: Situation,
        allSituations: List<Situation>,
        seedSalt: ULong = 0u
    ): PracticeDecision? {
        val correctText = SituationGating.firstSentence(situation.takeaway)
        if (correctText.isEmpty() || correctText.length < 10) return null
        val ownIndex = allSituations.indexOfFirst { it.id == situation.id }
        if (ownIndex < 0 || allSituations.isEmpty()) return null

        val pool = mutableListOf(correctText to true)
        val offsets = listOf(97, 211, 331, 53, 149, 271, 17, 401)
        for (offset in offsets) {
            if (pool.size >= 4) break
            val other = allSituations[(ownIndex + offset) % allSituations.size]
            if (other.id == situation.id) continue
            val text = SituationGating.firstSentence(other.takeaway)
            if (text.length < 10) continue
            if (pool.any { it.first == text }) continue
            pool.add(text to false)
        }
        if (pool.size < 4) {
            for (other in allSituations) {
                if (pool.size >= 4) break
                if (other.id == situation.id) continue
                val text = SituationGating.firstSentence(other.takeaway)
                if (text.length < 10) continue
                if (pool.any { it.first == text }) continue
                pool.add(text to false)
            }
        }
        if (pool.size != 4) return null

        val shuffled = deterministicShuffle(pool, stableSeed(situation.id) + seedSalt)
        val correctIndex = shuffled.indexOfFirst { it.second }
        if (correctIndex < 0) return null
        return PracticeDecision(
            id = "practice-${situation.id}-$seedSalt",
            situationId = situation.id,
            prompt = "What would you do next?",
            options = shuffled.map { it.first },
            correctOptionIndex = correctIndex,
            explanation = situation.takeaway
        )
    }

    /** A stable 64-bit hash of a string (FNV-1a). Mirrors PracticeModeBuilder.stableSeed. */
    fun stableSeed(text: String): ULong {
        var hash: ULong = 0xcbf29ce484222325u
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            // Unsigned byte value, matching the iOS UInt8 hash input.
            hash = hash xor (byte.toInt() and 0xFF).toULong()
            hash *= 0x100000001b3u
        }
        return hash
    }

    /** Deterministic shuffle (Fisher-Yates with a linear congruential generator). Mirrors PracticeModeBuilder.deterministicShuffle. */
    fun <T> deterministicShuffle(items: List<T>, seed: ULong): List<T> {
        val shuffled = items.toMutableList()
        var state = seed + 1u
        if (shuffled.size <= 1) return shuffled
        for (index in shuffled.size - 1 downTo 1) {
            state = state * 6364136223846793005u + 1442695040888963407u
            val swap = (state % (index + 1).toULong()).toInt()
            val temp = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = temp
        }
        return shuffled
    }
}
