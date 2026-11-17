// SearchRankingTest.kt
// Unit tests for the links-only search ranking: every query word
// must match, title evidence outranks text evidence, and ties keep
// catalogue order. The filter itself runs off the main thread in
// SearchScreen; only the pure matching and ranking are tested here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.SearchRanking
import com.paymentstraininghub.app.data.model.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {

    private fun entry(id: String, title: String, text: String, type: String = "lesson"): SearchEntry {
        return SearchEntry(id = id, title = title, url = "", type = type, text = text)
    }

    @Test
    fun emptyQueryMatchesAllAndKeepsOrder() {
        val entries = listOf(entry("a", "A", "x"), entry("b", "B", "y"))
        assertEquals(entries, SearchRanking.rank(entries, ""))
        assertEquals(entries, SearchRanking.rank(entries, "   "))
        assertTrue(SearchRanking.matches(entries[0], ""))
    }

    @Test
    fun allQueryWordsMustMatch() {
        val matched = entry("a", "Beneficiary not credited", "text")
        assertTrue(SearchRanking.matches(matched, "beneficiary credited"))
        assertFalse(SearchRanking.matches(matched, "beneficiary missing"))
        assertFalse(SearchRanking.matches(matched, "beneficiary not credited today"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndCoversText() {
        val textHit = entry("a", "Something else", "The pacs.008 message moves money")
        assertTrue(SearchRanking.matches(textHit, "PACS.008"))
        assertTrue(SearchRanking.matches(textHit, "pacs.008"))
    }

    @Test
    fun titleMatchOutranksTextMatch() {
        val titleHit = entry("t", "pacs.008 explained", "other words")
        val textHit = entry("x", "Something else", "mentions pacs.008 in passing")
        val ranked = SearchRanking.rank(listOf(textHit, titleHit), "pacs.008")
        assertEquals(listOf("t", "x"), ranked.map { it.id })
    }

    @Test
    fun exactPhraseTitleBeatsScatteredWordMatch() {
        val phrase = entry("p", "Beneficiary not credited", "")
        val scattered = entry("w", "Credited beneficiary cases", "")
        val ranked = SearchRanking.rank(listOf(scattered, phrase), "beneficiary not credited")
        assertEquals("p", ranked.first().id)
    }

    @Test
    fun nonMatchingEntriesAreDroppedAndTiesKeepCatalogueOrder() {
        val first = entry("a", "SEPA basics", "")
        val second = entry("b", "SEPA guide", "")
        val unrelated = entry("c", "Unrelated", "")
        val ranked = SearchRanking.rank(listOf(first, second, unrelated), "SEPA")
        assertEquals(listOf("a", "b"), ranked.map { it.id })
    }

    @Test
    fun queryWordsSplitAndNormalise() {
        assertEquals(listOf("pacs.008", "mt103"), SearchRanking.queryWords("  pacs.008   MT103 "))
        assertEquals(emptyList<String>(), SearchRanking.queryWords("   "))
    }
}
