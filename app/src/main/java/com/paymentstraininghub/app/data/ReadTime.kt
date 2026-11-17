// ReadTime.kt
// Payments Training Hub Android - honest reading-time computation.
//
// One reading rate for the whole app: 200 words per minute,
// rounded up, at least 1 minute. The rate is applied to the FULL
// text the learner reads, never to a teaser or excerpt: for a
// lesson that is the transcript plus the takeaways and the
// do-this-at-work items, all of which render on the lesson
// screen. Empty text has no estimate (null), so callers show no
// label rather than stamping a fake one. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.LessonDetail

object ReadTime {

    const val WORDS_PER_MINUTE = 200

    /** Whole minutes for a word count, rounded up, at least 1. Zero or negative has no estimate. */
    fun minutesForWords(words: Int): Int? {
        if (words <= 0) return null
        return maxOf(1, (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE)
    }

    /** Word count of free text: whitespace-separated non-blank tokens. */
    fun wordCount(text: String): Int {
        return text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /** Whole minutes for free text, or null when the text is empty. */
    fun minutesForText(text: String): Int? = minutesForWords(wordCount(text))

    /** "About N min read" for free text, or null when the text is empty. */
    fun label(text: String): String? {
        val mins = minutesForText(text) ?: return null
        return "About $mins min read"
    }
}

/**
 * The full text a learner reads on the lesson screen: the lesson
 * transcript (the reading text), the key takeaways, and the
 * do-this-at-work items. Quiz questions are not reading text and
 * are excluded.
 */
fun LessonDetail.readingText(): String {
    return (listOf(transcript) + takeaways + doThisAtWork)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}
