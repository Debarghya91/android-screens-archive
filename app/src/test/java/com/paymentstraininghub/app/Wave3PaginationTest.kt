// Wave3PaginationTest.kt
// Wave 3 true-pagination proofs: the search index load requests
// only the compact file, the Payment Cases list requests only
// the lightweight index, and opening a case requests only its
// detail chunk. A recording fake fetcher serves realistic
// payloads shaped like the deployed files, so every assertion
// is about what was actually requested, never about display
// slicing alone. Fallbacks are proven too: a missing compact
// file falls back to the full index, and a missing chunk falls
// back to the full situations file. No em dashes in any string
// here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.FullSituationItem
import com.paymentstraininghub.app.data.SearchRanking
import com.paymentstraininghub.app.data.SituationsIndexDoc
import com.paymentstraininghub.app.data.SituationsIndexEntry
import com.paymentstraininghub.app.data.model.SearchCounts
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.SearchIndex
import com.paymentstraininghub.app.data.visiblePage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class Wave3PaginationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val requested = mutableListOf<String>()
    private var compactBody: String? = null
    private var fullIndexBody: String? = null
    private var situationsIndexBody: String? = null
    private var chunkBodies: Map<Int, String> = emptyMap()
    private var fullSituationsBody: String? = null
    private var failChunks = false

    private fun compactEntries(): List<SearchEntry> {
        val lessons = (1..45).map { n ->
            SearchEntry(
                id = "ch${(n % 9) + 1}s$n",
                title = "Payment lesson $n: SWIFT gpi tracking and pacs.008 flows",
                url = "/lessons/ch${(n % 9) + 1}s$n.html",
                type = "lesson",
                tags = listOf("swift", "gpi", "tracking", "pacs.008", "settlement", "cut-off"),
                excerpt = "Payment lesson $n: how a SWIFT gpi payment is tracked from instruction to credit...",
                chapter = "Payment Foundations",
                domain = "Foundations"
            )
        }
        return lessons + listOf(
            SearchEntry(
                id = "guide-pacs002",
                title = "Guide: pacs.002 Explained",
                url = "/guides/pacs002.html",
                type = "guide",
                text = "Guide: pacs.002 Explained. Payment status reports tell the sender whether a payment was accepted or rejected, and why."
            ),
            SearchEntry(
                id = "faq-1",
                title = "FAQ: What is a pacs.008?",
                url = "/faq/faq-1.html",
                type = "faq",
                text = "FAQ: What is a pacs.008? A pacs.008 is the ISO 20022 customer credit transfer message."
            ),
            SearchEntry(
                id = "ABH-01",
                title = "Returned payment: closed beneficiary account",
                url = "/situations/ABH-01.html",
                type = "situation",
                tags = listOf("returned", "pacs.004", "AC04", "closed", "account"),
                excerpt = "A corporate client sent a pacs.008 payment of EUR 40,000 to a beneficiary in Germany..."
            )
        )
    }

    private fun fullEntries(): List<SearchEntry> {
        return compactEntries().map { entry ->
            if (entry.isLesson) {
                entry.copy(
                    text = "FULLTEXTMARKER ${entry.title} The full body of the lesson, with every paragraph the compact file drops.",
                    tags = emptyList(),
                    excerpt = "",
                    chapter = null,
                    domain = null
                )
            } else {
                entry
            }
        }
    }

    private fun countsFor(entries: List<SearchEntry>) = SearchCounts(
        lesson = entries.count { it.isLesson },
        guide = entries.count { it.isGuide },
        situation = entries.count { it.isSituation },
        lab = entries.count { it.isLab },
        test = entries.count { it.isTest },
        faq = entries.count { it.isFaq },
        total = entries.size
    )

    /** 45 cases: 2 free samples, 42 staged cases, 1 derived record. Chunk 0 holds cases 1-28, chunk 1 holds 29-45. */
    private fun indexEntries(): List<SituationsIndexEntry> {
        val list = mutableListOf<SituationsIndexEntry>()
        list.add(
            SituationsIndexEntry(
                id = "ABH-01",
                title = "Returned payment: closed beneficiary account",
                level = "foundation",
                stage = JsonPrimitive(1),
                stageName = "Payment mechanics",
                domain = "Cross-Border Payments",
                chunk = 0
            )
        )
        list.add(
            SituationsIndexEntry(
                id = "JEB-C01",
                title = "Batch rejection: one bad payment stops payroll",
                level = "core",
                stage = JsonPrimitive(2),
                stageName = "Processing decisions",
                domain = "Clearing and Settlement",
                chunk = 0
            )
        )
        for (n in 3..44) {
            val stage = (n % 6) + 1
            list.add(
                SituationsIndexEntry(
                    id = "CASE-%03d".format(n),
                    title = "Payment case number $n about a delayed settlement",
                    level = listOf("foundation", "core", "advanced")[n % 3],
                    stage = JsonPrimitive(stage),
                    stageName = "Stage $stage",
                    domain = "Cross-Border Payments",
                    chunk = if (n <= 28) 0 else 1
                )
            )
        }
        list.add(
            SituationsIndexEntry(
                id = "DERIVED-01",
                title = "Derived case from the search index",
                level = "core",
                stage = JsonPrimitive(3),
                stageName = "Stage 3",
                domain = null,
                chunk = null,
                derived = true
            )
        )
        return list
    }

    private fun fullItemFor(entry: SituationsIndexEntry): FullSituationItem =
        FullSituationItem(
            id = entry.id,
            title = entry.title,
            level = entry.level,
            explain = "Full explain text for ${entry.id}: a payment of USD 12,500 stopped at an intermediary bank and the analyst traced the messages to release it.",
            takeaway = "Takeaway for ${entry.id}: trace the messages first.",
            stage = entry.stage,
            stageName = entry.stageName,
            domain = entry.domain,
            messages = listOf("pacs.008", "pacs.002"),
            failureCategory = "Beneficiary account problem",
            resolution = "Confirm the details with the client and resend."
        )

    @Before
    fun installFetcher() {
        ContentRepository.clearTestOverrides()
        requested.clear()
        failChunks = false
        val compact = compactEntries()
        compactBody = json.encodeToString(SearchIndex(countsFor(compact), compact))
        val full = fullEntries()
        fullIndexBody = json.encodeToString(SearchIndex(countsFor(full), full))
        val index = indexEntries()
        situationsIndexBody = json.encodeToString(
            SituationsIndexDoc(total = index.size, chunkSize = 28, items = index)
        )
        chunkBodies = mapOf(
            0 to json.encodeToString(index.take(28).map { fullItemFor(it) }),
            1 to json.encodeToString(index.drop(28).take(17).map { fullItemFor(it) })
        )
        fullSituationsBody = "window.PA_SITUATIONS = " +
            json.encodeToString(index.map { fullItemFor(it) }) + ";"
        ContentRepository.testFetcher = { url ->
            requested.add(url)
            when (url) {
                ContentRepository.COMPACT_SEARCH_INDEX_URL ->
                    compactBody ?: throw IOException("404 compact index")
                ContentRepository.SEARCH_INDEX_URL ->
                    fullIndexBody ?: throw IOException("404 full index")
                ContentRepository.SITUATIONS_INDEX_URL ->
                    situationsIndexBody ?: throw IOException("404 situations index")
                ContentRepository.SITUATIONS_URL ->
                    fullSituationsBody ?: throw IOException("404 situations file")
                else -> {
                    val base = ContentRepository.SITUATIONS_CHUNK_BASE_URL
                    if (url.startsWith(base)) {
                        if (failChunks) throw IOException("404 chunk")
                        val number = url.removePrefix(base)
                            .removePrefix("chunk-").removeSuffix(".json").toInt()
                        chunkBodies[number] ?: throw IOException("404 chunk $number")
                    } else {
                        throw IOException("Unexpected URL: $url")
                    }
                }
            }
        }
    }

    @After
    fun clearFetcher() {
        ContentRepository.clearTestOverrides()
    }

    @Test
    fun searchIndexLoadRequestsOnlyTheCompactFile() = runBlocking {
        val index = ContentRepository.loadSearchIndex()
        assertEquals(
            listOf(ContentRepository.COMPACT_SEARCH_INDEX_URL),
            requested
        )
        // Supplemental lessons may be merged in when another
        // test class already loaded them in this worker, so
        // assert on the fake entries, not the exact total.
        val fakeIds = compactEntries().map { it.id }.toSet()
        val mine = index.entries.filter { it.id in fakeIds }
        assertEquals(48, mine.size)
        val lesson = mine.first { it.isLesson }
        assertEquals("", lesson.text)
        assertTrue(lesson.tags.isNotEmpty())
        assertEquals("Payment Foundations", lesson.chapterName)
        assertEquals("Foundations", lesson.domainLabel())
        assertTrue(lesson.preview.startsWith("Payment lesson"))
        val guide = mine.first { it.isGuide }
        assertTrue(guide.text.isNotBlank())
        // A second load serves the cache: no second request.
        ContentRepository.loadSearchIndex()
        assertEquals(1, requested.size)
    }

    @Test
    fun searchRankingOverCompactEntriesReturnsRankedResults() = runBlocking {
        val fakeIds = compactEntries().map { it.id }.toSet()
        val entries = ContentRepository.loadSearchIndex().entries
            .filter { it.id in fakeIds }
        val ranked = SearchRanking.rank(entries, "swift gpi tracking")
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.first().isLesson)
        // Stopwords in the query do not block a tags match.
        val stopwordRanked = SearchRanking.rank(entries, "what is swift gpi")
        assertTrue(stopwordRanked.isNotEmpty())
        assertTrue(stopwordRanked.first().isLesson)
        // A query with no matching word finds nothing.
        assertTrue(SearchRanking.rank(entries, "beneficiary not credited today").isEmpty())
        // Exact-phrase title evidence beats scattered words.
        val phrase = SearchRanking.rank(entries, "Returned payment: closed beneficiary account")
        assertEquals("ABH-01", phrase.first().id)
    }

    @Test
    fun compactIndexMissingFallsBackToFullIndex() = runBlocking {
        compactBody = null
        val index = ContentRepository.loadSearchIndex()
        assertEquals(
            listOf(
                ContentRepository.COMPACT_SEARCH_INDEX_URL,
                ContentRepository.SEARCH_INDEX_URL
            ),
            requested
        )
        val lesson = index.entries.first { it.isLesson }
        assertTrue(lesson.text.contains("FULLTEXTMARKER"))
    }

    @Test
    fun situationsListRequestsOnlyTheLightweightIndex() = runBlocking {
        val list = ContentRepository.situationsIndex()
        assertEquals(listOf(ContentRepository.SITUATIONS_INDEX_URL), requested)
        assertEquals(45, list.size)
        val first = list.first { it.id == "ABH-01" }
        assertEquals("Returned payment: closed beneficiary account", first.title)
        assertEquals(1, first.stage)
        assertEquals("Payment mechanics", first.stageName)
        assertEquals("foundation", first.level)
        assertTrue(first.isFreeSample)
        assertTrue(first.isStageAuthoritative)
        // List rows carry no body text: that arrives per case.
        assertEquals("", first.explain)
        assertEquals("", first.takeaway)
        val derived = list.first { it.id == "DERIVED-01" }
        assertFalse(derived.isStageAuthoritative)
        assertEquals(3, derived.stage)
        // A second load serves the cache: no second request.
        ContentRepository.situationsIndex()
        assertEquals(1, requested.size)
    }

    @Test
    fun openingACaseRequestsOnlyItsChunk() = runBlocking {
        ContentRepository.situationsIndex()
        requested.clear()
        val detail = ContentRepository.situationDetail("CASE-030")
        assertNotNull(detail)
        assertEquals("CASE-030", detail!!.id)
        assertTrue(detail.explain.contains("Full explain text"))
        assertTrue(detail.takeaway.contains("Takeaway"))
        assertEquals(
            listOf(ContentRepository.situationsChunkUrl(1)),
            requested
        )
        assertFalse(requested.contains(ContentRepository.SITUATIONS_URL))
    }

    @Test
    fun openingASecondCaseInTheSameChunkRequestsNothingNew() = runBlocking {
        ContentRepository.situationsIndex()
        ContentRepository.situationDetail("CASE-030")
        requested.clear()
        val detail = ContentRepository.situationDetail("CASE-031")
        assertNotNull(detail)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun chunkMissingFallsBackToFullFile() = runBlocking {
        ContentRepository.situationsIndex()
        failChunks = true
        requested.clear()
        val detail = ContentRepository.situationDetail("CASE-030")
        assertNotNull(detail)
        assertEquals("CASE-030", detail!!.id)
        assertTrue(detail.explain.contains("Full explain text"))
        assertTrue(requested.contains(ContentRepository.SITUATIONS_URL))
    }

    @Test
    fun situationsIndexMissingFallsBackToFullList() = runBlocking {
        situationsIndexBody = null
        val list = ContentRepository.situationsIndex()
        assertEquals(45, list.size)
        assertTrue(requested.contains(ContentRepository.SITUATIONS_URL))
        val first = list.first { it.id == "ABH-01" }
        assertTrue(first.explain.contains("Full explain text"))
    }

    @Test
    fun indexEntryMappingMatchesFullItemMapping() {
        val entries = indexEntries().filter { !it.derived }
        for (entry in entries) {
            val fromEntry = ContentRepository.situationFromIndexEntry(entry)
            val fromFull = ContentRepository.situationFromFull(fullItemFor(entry))
            assertEquals("stage for ${entry.id}", fromFull.stage, fromEntry.stage)
            assertEquals("stageName for ${entry.id}", fromFull.stageName, fromEntry.stageName)
            assertEquals("level for ${entry.id}", fromFull.level, fromEntry.level)
            assertEquals("free sample for ${entry.id}", fromFull.isFreeSample, fromEntry.isFreeSample)
            assertEquals(
                "authoritative for ${entry.id}",
                fromFull.isStageAuthoritative,
                fromEntry.isStageAuthoritative
            )
        }
        // A string stage and a missing stage resolve the same way.
        val stringStage = SituationsIndexEntry(
            id = "STR-01", title = "String stage", level = "core",
            stage = JsonPrimitive("4"), stageName = null, chunk = 0
        )
        assertEquals(4, ContentRepository.situationFromIndexEntry(stringStage).stage)
        val noStage = SituationsIndexEntry(
            id = "NUL-01", title = "No stage", level = "core",
            stage = JsonNull, stageName = null, chunk = 0
        )
        val fromNoStage = ContentRepository.situationFromIndexEntry(noStage)
        assertEquals(
            ContentRepository.situationFromFull(fullItemFor(noStage)).stage,
            fromNoStage.stage
        )
    }

    @Test
    fun pagedListsShowTwentyThenTwentyMore() = runBlocking {
        val fakeIds = compactEntries().map { it.id }.toSet()
        val entries = ContentRepository.loadSearchIndex().entries
            .filter { it.id in fakeIds }
        val ranked = SearchRanking.rank(entries, "swift gpi")
        assertTrue(ranked.size > 40)
        assertEquals(20, visiblePage(ranked, 20).size)
        assertEquals(40, visiblePage(ranked, 40).size)
        val cases = ContentRepository.situationsIndex()
        assertEquals(20, visiblePage(cases, 20).size)
        assertEquals(40, visiblePage(cases, 40).size)
        assertEquals(45, visiblePage(cases, 60).size)
    }
}
