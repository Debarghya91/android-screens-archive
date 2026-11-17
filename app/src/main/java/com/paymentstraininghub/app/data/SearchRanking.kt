// SearchRanking.kt
// Payments Training Hub Android - pure search matching and ranking
// for the links-only search. Search results are LINKS to lessons,
// guides, situations, labs, tests and FAQs: never generated
// answers, never answer summaries. This file only decides which
// catalogue entries match a query and in what order they appear;
// it never invents content.
//
// Matching: every effective query word must appear in the entry
// title, text or tags (case-insensitive substring). Stopwords are
// ignored, so a natural query such as "what is swift gpi" matches
// on its distinctive words; a query of only stopwords keeps all
// its words. Ranking: title matches beat text and tag matches,
// exact-phrase title matches beat word matches, and ties keep
// catalogue order (the sort is stable). The filter runs off the
// main thread in SearchScreen; these functions are pure so the
// ranking is unit-tested. No em dashes in any user-facing text.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.SearchEntry

object SearchRanking {

    /**
     * Query stopwords. Mirrored in tools/generate-paged-data.py,
     * which never emits these words as tags. Keep the two lists
     * in sync: a word ignored here but present as a tag is
     * harmless, but a word kept here and absent from tags can
     * never match a compact entry.
     */
    val stopWords: Set<String> = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "than",
        "so", "as", "at", "by", "for", "in", "into", "of", "off",
        "on", "onto", "to", "up", "down", "out", "over", "under",
        "from", "with", "without", "about", "after", "before",
        "between", "through", "during", "each", "other", "some",
        "any", "no", "nor", "not", "only", "own", "same", "too",
        "very", "just", "also", "more", "most", "less", "least",
        "can", "could", "may", "might", "must", "shall", "should",
        "will", "would", "do", "does", "did", "done", "is", "am",
        "are", "was", "were", "be", "been", "being", "have", "has",
        "had", "having", "i", "you", "he", "she", "it", "we",
        "they", "me", "him", "her", "us", "them", "my", "your",
        "his", "its", "our", "their", "this", "that", "these",
        "those", "there", "here", "when", "where", "which", "who",
        "whom", "whose", "why", "how", "what", "all", "both",
        "few", "many", "much", "every", "either", "neither",
        "one", "two", "get", "got", "make", "made", "take"
    )

    /** Query words: lowercase, split on whitespace, blanks dropped. */
    fun queryWords(query: String): List<String> {
        return query.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    }

    /**
     * The words a query actually matches on: [queryWords] minus
     * stopwords. When every word is a stopword, all words are
     * kept, so a query such as "not" still matches entries that
     * carry it.
     */
    fun effectiveWords(query: String): List<String> {
        val words = queryWords(query)
        val kept = words.filter { it !in stopWords }
        return kept.ifEmpty { words }
    }

    /** True when every effective query word appears in the title, text or tags. An empty query matches all. */
    fun matches(entry: SearchEntry, query: String): Boolean {
        val words = effectiveWords(query)
        if (words.isEmpty()) return true
        val haystack = (entry.title + " " + entry.text + " " + entry.tags.joinToString(" ")).lowercase()
        return words.all { word -> haystack.contains(word) }
    }

    /**
     * Rank score: higher is a better match. Title evidence dominates
     * text and tag evidence, so a lesson named for the query
     * outranks a lesson that merely mentions it in passing. The
     * exact-phrase check uses the full query, stopwords included,
     * so a title carrying the whole phrase still wins.
     */
    fun score(entry: SearchEntry, query: String): Int {
        val words = effectiveWords(query)
        if (words.isEmpty()) return 0
        val title = entry.title.lowercase()
        val text = (entry.text + " " + entry.tags.joinToString(" ")).lowercase()
        val trimmed = query.trim().lowercase()
        var points = 0
        if (title.contains(trimmed)) points += 50
        if (title.startsWith(words.first())) points += 20
        val titleWords = words.count { title.contains(it) }
        val textWords = words.count { text.contains(it) }
        points += titleWords * 30
        points += textWords * 5
        if (titleWords == words.size) points += 25
        return points
    }

    /** Matching entries in ranked order. Non-matching entries are dropped. */
    fun rank(entries: List<SearchEntry>, query: String): List<SearchEntry> {
        val words = queryWords(query)
        if (words.isEmpty()) return entries
        return entries
            .filter { matches(it, query) }
            .sortedByDescending { score(it, query) }
    }

    /** Every filterable entry type, in chip order. */
    val typeKeys: List<String> = listOf("lesson", "situation", "guide", "faq", "lab", "test")

    /**
     * True when a type selection filters nothing: no type is
     * selected, or every type is selected. Selecting all filters
     * means "everything", never "nothing": an all-selected state
     * must return the same results as no filter at all.
     */
    fun selectionIsUnfiltered(selectedTypes: Set<String>): Boolean {
        return selectedTypes.isEmpty() || typeKeys.all { it in selectedTypes }
    }

    /**
     * Filter, then rank. The type filter runs BEFORE ranking, so a
     * type can never be ranked out of the results before the
     * filter sees it. An unfiltered selection (none, or all types)
     * returns every match. Pure, so the all-filters case is pinned
     * by unit tests.
     */
    fun filterAndRank(
        entries: List<SearchEntry>,
        query: String,
        selectedTypes: Set<String>
    ): List<SearchEntry> {
        val filtered = if (selectionIsUnfiltered(selectedTypes)) {
            entries
        } else {
            entries.filter { it.type.lowercase() in selectedTypes }
        }
        return rank(filtered, query)
    }
}
