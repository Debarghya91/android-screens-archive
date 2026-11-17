// DailyChallenge.kt
// Pure Daily Challenge selection logic, ported from iOS
// DailyChallengeView.swift. One real situation per day, picked
// deterministically from the situations by the calendar day, so
// every learner gets the same challenge each day. The question
// is "What is the key lesson here?": the correct option is the
// situation's own takeaway, and the wrong options are the
// takeaways of three other real situations. The v70 gate
// applies before pick and display: a learner without Payment
// Situations unlocked sees only the free samples plus this
// week's free case, so no locked explain or takeaway text can
// reach a non-entitled learner through this surface.

package com.paymentstraininghub.app.features.challenge

import com.paymentstraininghub.app.data.model.Situation
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DailyChallenge {

    data class Option(val text: String, val isCorrect: Boolean)

    /// Calendar day key, local time, for example 2026-11-11.
    fun dayKey(date: LocalDate = LocalDate.now()): String =
        date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    /// Days since the epoch, local calendar: the deterministic
    /// seed. LocalDate.toEpochDay is the same count iOS computes
    /// with Calendar day components from the epoch start-of-day.
    fun dayNumber(date: LocalDate = LocalDate.now()): Int =
        date.toEpochDay().toInt()

    /// The situations a learner may see in this challenge.
    /// Entitled learners get the input unchanged, in catalogue
    /// order, so their pick, options and shuffle are identical
    /// to the ungated behavior. Everyone else gets only the free
    /// samples plus this week's free case. A nil weekly case id
    /// leaves only the free samples: the gate never invents a
    /// situation and never falls back to locked content.
    fun eligiblePool(
        situations: List<Situation>,
        isSituationsUnlocked: Boolean,
        weeklyCaseId: String?
    ): List<Situation> {
        if (isSituationsUnlocked) return situations
        return situations.filter { situation ->
            if (situation.isFreeSample) return@filter true
            weeklyCaseId != null && situation.id == weeklyCaseId
        }
    }

    /// Today's situation, deterministic by day. For non-entitled
    /// learners the day number cycles within the small free pool,
    /// so the same free case repeats across days: the documented
    /// fallback for the small pool, never a fallback to locked
    /// content.
    fun todaysSituation(pool: List<Situation>, dayNumber: Int): Situation? {
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(dayNumber, pool.size)]
    }

    /// The four answer options: today's takeaway plus three other
    /// real takeaways, shuffled deterministically by the day
    /// number. Every option comes from the gated pool.
    fun options(pool: List<Situation>, today: Situation, dayNumber: Int): List<Option> {
        val result = mutableListOf(Option(firstSentence(today.takeaway), true))
        val offsets = listOf(97, 211, 331)
        for (offset in offsets) {
            val other = pool[Math.floorMod(dayNumber + offset, pool.size)]
            if (other.id == today.id) continue
            val text = firstSentence(other.takeaway)
            if (result.none { it.text == text }) result.add(Option(text, false))
        }
        // Small-pool fill: with only 6 or 7 free cases the fixed
        // offsets can land on the same case twice, leaving fewer
        // than four options. Walk the pool in day order until
        // there are four options or the pool is exhausted.
        if (result.size < 4) {
            var step = 1
            while (result.size < 4 && step <= pool.size) {
                val other = pool[Math.floorMod(dayNumber + step, pool.size)]
                step += 1
                if (other.id == today.id) continue
                val text = firstSentence(other.takeaway)
                if (result.none { it.text == text }) result.add(Option(text, false))
            }
        }
        // Deterministic shuffle: the same LCG iOS uses, with
        // wrapping 64-bit arithmetic.
        val shuffled = result.toMutableList()
        var seed = (dayNumber + 1).toLong().toULong()
        for (index in shuffled.size - 1 downTo 1) {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            val swap = (seed % (index + 1).toULong()).toInt()
            val tmp = shuffled[index]
            shuffled[index] = shuffled[swap]
            shuffled[swap] = tmp
        }
        return shuffled
    }

    /// First sentence of a takeaway, capped so an option stays readable.
    fun firstSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "See the situation takeaway."
        val dotSpace = trimmed.indexOf(". ")
        if (dotSpace >= 0) {
            val first = trimmed.substring(0, dotSpace)
            if (first.length >= 20) return "$first."
        }
        if (trimmed.length > 200) return trimmed.substring(0, 200) + "..."
        return trimmed
    }

    /// The scenario text: the opening of the explain, before the
    /// structured breakdown, so the question does not give the
    /// answer away. The first two paragraphs, or the whole
    /// explain when there are fewer.
    fun scenarioText(situation: Situation): String {
        val paragraphs = situation.explain.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val opening = paragraphs.take(2).joinToString("\n\n")
        return opening.ifEmpty { situation.explain }
    }
}
