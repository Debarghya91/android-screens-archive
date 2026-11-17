// Wave3SearchRankingTest.kt
// Wave 3 ranking proofs for compact index entries: matches and
// scores run over tags exactly as over body text, stopwords in a
// query do not block a match, and an all-stopword query falls
// back to its words instead of matching nothing. The existing
// SearchRankingTest pins the full-index behaviour unchanged.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.SearchRanking
import com.paymentstraininghub.app.data.model.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave3SearchRankingTest {

    private fun compactLesson(
        id: String,
        title: String,
        tags: List<String>,
        excerpt: String = ""
    ) = SearchEntry(
        id = id,
        title = title,
        url = "/lessons/$id.html",
        type = "lesson",
        tags = tags,
        excerpt = excerpt,
        chapter = "Payment Foundations",
        domain = "Foundations"
    )

    @Test
    fun tagsMatchLikeBodyText() {
        val entry = compactLesson(
            "ch1s1",
            "What a payment is",
            listOf("swift", "gpi", "tracking", "pacs.008")
        )
        assertTrue(SearchRanking.matches(entry, "swift gpi"))
        assertTrue(SearchRanking.matches(entry, "pacs.008"))
        assertFalse(SearchRanking.matches(entry, "sepa direct debit"))
    }

    @Test
    fun stopwordQueryMatchesThroughTags() {
        val entry = compactLesson(
            "ch1s1",
            "What a payment is",
            listOf("swift", "gpi", "tracking")
        )
        assertTrue(SearchRanking.matches(entry, "what is swift gpi"))
        assertTrue(SearchRanking.matches(entry, "how does gpi tracking"))
    }

    @Test
    fun allStopwordQueryFallsBackToItsWords() {
        val entry = SearchEntry(
            id = "faq-1",
            title = "What is what",
            url = "/faq/faq-1.html",
            type = "faq",
            text = "What is what, and how does it work?"
        )
        // Every query word is a stopword: the fallback keeps the
        // words, so the query still matches instead of matching
        // everything or nothing.
        assertEquals(listOf("what", "is"), SearchRanking.effectiveWords("what is"))
        assertTrue(SearchRanking.matches(entry, "what is"))
    }

    @Test
    fun effectiveWordsDropsStopwords() {
        assertEquals(
            listOf("swift", "gpi"),
            SearchRanking.effectiveWords("what is swift gpi")
        )
        assertEquals(
            listOf("pacs.008", "rejected"),
            SearchRanking.effectiveWords("why was the pacs.008 rejected")
        )
    }

    @Test
    fun titleEvidenceBeatsTagsOnlyEvidence() {
        val titleMatch = compactLesson(
            "ch1s1",
            "SWIFT gpi tracking",
            listOf("settlement")
        )
        val tagsMatch = compactLesson(
            "ch1s2",
            "What a payment is",
            listOf("swift", "gpi", "tracking")
        )
        val ranked = SearchRanking.rank(listOf(tagsMatch, titleMatch), "swift gpi tracking")
        assertEquals("ch1s1", ranked.first().id)
        assertTrue(
            SearchRanking.score(titleMatch, "swift gpi tracking") >
                SearchRanking.score(tagsMatch, "swift gpi tracking")
        )
    }

    @Test
    fun excerptDoesNotCreateMatches() {
        // The excerpt is display text only: ranking reads the
        // title, the text, and the tags, never the excerpt.
        val entry = compactLesson(
            "ch1s1",
            "What a payment is",
            listOf("settlement"),
            excerpt = "This excerpt mentions zebras and quantum ledgers."
        )
        assertFalse(SearchRanking.matches(entry, "zebras"))
        assertFalse(SearchRanking.matches(entry, "quantum"))
    }
}
