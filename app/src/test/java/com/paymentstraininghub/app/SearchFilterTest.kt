package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.SearchRanking
import com.paymentstraininghub.app.data.model.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {

    private fun entry(id: String, title: String, text: String, type: String): SearchEntry {
        return SearchEntry(id = id, title = title, url = "", type = type, text = text)
    }

    private val entries = listOf(
        entry("l1", "pacs.008 explained", "message", "lesson"),
        entry("s1", "pacs.008 case", "case", "situation"),
        entry("g1", "pacs.008 guide", "guide", "guide"),
        entry("f1", "pacs.008 faq", "faq", "faq"),
        entry("b1", "pacs.008 lab", "lab", "lab"),
        entry("t1", "pacs.008 test", "test", "test"),
        entry("l2", "unrelated lesson", "nothing", "lesson")
    )

    @Test
    fun allTypesSelectedReturnsSameAsNoFilter() {
        val all = SearchRanking.typeKeys.toSet()
        val unfiltered = SearchRanking.filterAndRank(entries, "pacs.008", emptySet())
        val allSelected = SearchRanking.filterAndRank(entries, "pacs.008", all)
        assertEquals(unfiltered.map { it.id }, allSelected.map { it.id })
        assertEquals(6, allSelected.size)
    }

    @Test
    fun allTypesSelectedWithEmptyQueryReturnsEverything() {
        val all = SearchRanking.typeKeys.toSet()
        val result = SearchRanking.filterAndRank(entries, "", all)
        assertEquals(entries.map { it.id }, result.map { it.id })
    }

    @Test
    fun singleTypeFiltersToThatType() {
        val result = SearchRanking.filterAndRank(entries, "pacs.008", setOf("test"))
        assertEquals(listOf("t1"), result.map { it.id })
    }

    @Test
    fun subsetFiltersToThoseTypes() {
        val result = SearchRanking.filterAndRank(entries, "pacs.008", setOf("lesson", "guide"))
        assertEquals(listOf("g1", "l1"), result.map { it.id }.sorted())
    }

    @Test
    fun filterRunsBeforeRankingSoRareTypesSurvive() {
        // The test entry ranks last on title evidence; filtering
        // first must still return it when its type is selected.
        val result = SearchRanking.filterAndRank(entries, "pacs.008", setOf("test", "lab"))
        assertEquals(2, result.size)
        assertTrue(result.map { it.id }.containsAll(listOf("t1", "b1")))
    }

    @Test
    fun selectionIsUnfilteredOnlyForNoneOrAll() {
        assertTrue(SearchRanking.selectionIsUnfiltered(emptySet()))
        assertTrue(SearchRanking.selectionIsUnfiltered(SearchRanking.typeKeys.toSet()))
        assertFalse(SearchRanking.selectionIsUnfiltered(setOf("lesson")))
        assertFalse(SearchRanking.selectionIsUnfiltered(setOf("lesson", "guide", "faq", "lab", "test")))
    }

    @Test
    fun typeMatchingIsCaseInsensitive() {
        val mixed = listOf(entry("x", "pacs.008", "", "Lesson"))
        val result = SearchRanking.filterAndRank(mixed, "pacs.008", setOf("lesson"))
        assertEquals(listOf("x"), result.map { it.id })
    }
}
