package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchCounts
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.SearchIndex
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.model.SituationVisual
import com.paymentstraininghub.app.security.PinningObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ContentRepository.kt
// Loads content from the live website so it stays
// current. Ported from the iOS ContentService.swift. No backend of its
// own. If a live fetch fails, the caller shows a clear error with Retry,
// never a silent failure.

/// Where a loaded lesson detail came from. LIVE means the lesson
/// page was fetched from the website. CACHED means the live fetch
/// failed and a page saved on this device from a previous open was
/// used instead. Views show an honest banner for cached detail,
/// never presenting it as live.
enum class LessonDetailSource {
    LIVE,
    CACHED
}

/// One full situation as shipped in the live situations data file
/// (window.PA_SITUATIONS). The source name and source URL are
/// deliberately not decoded: they are never shown in learner UI.
/// All fields carry defaults so one malformed item cannot break the
/// whole decode; items with a blank id are skipped.
@Serializable
data class FullSituationItem(
    val id: String = "",
    val title: String = "",
    val level: String = "",
    val module: String? = null,
    val seq: Int? = null,
    val explain: String = "",
    val takeaway: String = "",
    val visual: SituationVisual? = null,
    /// Authoritative stage from the data file, 1 to 6. Decoded
    /// flexibly (integer, or a string holding an integer) so a
    /// future format change cannot break the whole decode.
    val stage: JsonElement? = null,
    @SerialName("stage_name") val stageName: String? = null,
    val domain: String? = null,
    /// Message codes named in the data file for this item, when the
    /// stage-map join supplied them. Read by the investigation
    /// engine; absent items fall back to codes found in the text.
    /// Additive only (the one permitted decoder addition from the
    /// iOS investigation plan); absent fields decode as null.
    val messages: List<String>? = null,
    /// Failure category from the data file, when present. Read by
    /// the investigation engine for severity, impact, and
    /// hypothesis templates.
    @SerialName("failure_category") val failureCategory: String? = null,
    /// Resolution path from the data file, when present. Read by
    /// the investigation engine for the closing decision note.
    val resolution: String? = null
) {
    /// The resolved stage value, or null when the item carries no
    /// usable stage field.
    val resolvedStage: Int?
        get() {
            val element = stage ?: return null
            if (element is JsonNull) return null
            val primitive = element as? JsonPrimitive ?: return null
            primitive.intOrNull?.let { return it }
            return primitive.contentOrNull?.trim()?.toIntOrNull()
        }
}

/// One lightweight case list record from situations-index.json:
/// everything the Payment Cases list renders (title, stage,
/// level) plus the detail chunk holding the full case. No
/// explain, takeaway or visual: those arrive only when the case
/// is opened, from its chunk file. [chunk] is null for derived
/// records, which have no chunk and fall back to the full file.
@Serializable
data class SituationsIndexEntry(
    val id: String = "",
    val title: String = "",
    val level: String = "",
    val stage: JsonElement? = null,
    @SerialName("stage_name") val stageName: String? = null,
    val domain: String? = null,
    val chunk: Int? = null,
    val derived: Boolean = false
) {
    /// The resolved stage value, mirroring [FullSituationItem.resolvedStage].
    val resolvedStage: Int?
        get() {
            val element = stage ?: return null
            if (element is JsonNull) return null
            val primitive = element as? JsonPrimitive ?: return null
            primitive.intOrNull?.let { return it }
            return primitive.contentOrNull?.trim()?.toIntOrNull()
        }
}

/// The lightweight case list document: the total case count, the
/// chunk size the generator used, and one record per case, in
/// list order.
@Serializable
data class SituationsIndexDoc(
    val total: Int = 0,
    val chunkSize: Int = 28,
    val items: List<SituationsIndexEntry> = emptyList()
)

object ContentRepository {

    const val SEARCH_INDEX_URL = "https://www.paymentstraininghub.com/assets/search-index.json"
    /// Compact search index, fetched first: ids, titles, urls,
    /// types, tags, excerpts and lesson chapter/domain, without
    /// the full lesson and case text. Falls back to
    /// [SEARCH_INDEX_URL] when the compact file is missing.
    const val COMPACT_SEARCH_INDEX_URL = "https://www.paymentstraininghub.com/assets/search-index-compact.json"
    const val READING_ORDER_URL = "https://www.paymentstraininghub.com/assets/reading-order.json"
    const val SITUATIONS_URL = "https://www.paymentstraininghub.com/assets/situations-data.js"
    /// Lightweight case list: id, title, level, stage and the
    /// detail chunk number per case. No explain or takeaway text.
    /// The Payment Cases list loads this, never the full file.
    const val SITUATIONS_INDEX_URL = "https://www.paymentstraininghub.com/assets/situations-index.json"
    /// Detail chunks: one JSON array of full cases per file, 28
    /// cases each. Fetched only when a case is opened.
    /// [situationsChunkUrl] formats the chunk number with two digits.
    const val SITUATIONS_CHUNK_BASE_URL = "https://www.paymentstraininghub.com/assets/situations-chunks/"
    const val WEEKLY_CASES_URL = "https://www.paymentstraininghub.com/assets/weekly-cases-data.js"
    const val LESSON_BASE = "https://www.paymentstraininghub.com/lessons/"
    const val PRIMARY_AUDIO = "https://paymentforge-audio.pages.dev/audio/"
    const val FALLBACK_AUDIO = "https://paymentforge-audio-backup.pages.dev/audio/"

    /**
     * Authenticated situations delivery stays OFF, mirroring the
     * inert iOS flag ContentService.authenticatedSituationsDeliveryEnabled.
     * Situations keep loading from the public content host. Do not
     * implement an authenticated endpoint path while this flag is
     * false; the server-side delivery work is queued separately and
     * must not be built by flipping this constant alone.
     */
    const val authenticatedSituationsDeliveryEnabled: Boolean = false

    /// The 2 free Payment Situations samples, ungated on the website.
    /// Decided 15 Nov 2026: ABH-01 and JEB-C01 only.
    val freeSampleSituationIds: List<String> = listOf(
        "ABH-01", "JEB-C01"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val indexMutex = Mutex()
    private val situationsMutex = Mutex()
    private val situationsIndexMutex = Mutex()
    private val situationsIndexDocMutex = Mutex()
    private val chunkMutex = Mutex()
    private val weeklyCasesMutex = Mutex()
    private val fullItemsMutex = Mutex()
    private val sourceMutex = Mutex()

    @Volatile
    private var cachedIndex: SearchIndex? = null

    /**
     * The lesson ids from the last loaded search index, for
     * [SyncService.lessonIdsProvider]. Updated when the index
     * first loads; empty until then, which sync tolerates (the
     * server-to-local import still runs). Volatile because it is
     * written under the index mutex and read from sync coroutines.
     */
    @Volatile
    private var knownLessonIds: Set<String> = emptySet()

    /** The known lesson ids, or an empty set before the first index load. Never throws. */
    fun knownLessonIdsSnapshot(): Set<String> = knownLessonIds

    @Volatile
    private var cachedSituations: List<Situation>? = null

    @Volatile
    private var cachedSituationsIndex: List<Situation>? = null

    @Volatile
    private var cachedSituationsIndexDoc: SituationsIndexDoc? = null

    /** Fetched detail chunks, by chunk number. Small: one chunk holds 28 cases. */
    private val chunkCache = mutableMapOf<Int, List<FullSituationItem>>()

    @Volatile
    private var cachedWeeklyCases: List<Situation>? = null

    @Volatile
    private var cachedFullItems: List<FullSituationItem>? = null

    // MARK: - Render harness test seam

    /**
     * Test-only content overrides for the Robolectric render
     * harness. All default to null, which preserves production
     * behavior exactly: live fetches run as before. Tests set
     * them to realistic fake data so every screen composes
     * without network access, and clear them in teardown.
     * The lesson detail provider returns a detail for a lesson
     * id, or null when the test wants the load to fail fast
     * instead of reaching the network.
     */
    @Volatile
    internal var testSearchIndexOverride: SearchIndex? = null

    @Volatile
    internal var testSituationsOverride: List<Situation>? = null

    @Volatile
    internal var testWeeklyCasesOverride: List<Situation>? = null

    @Volatile
    internal var testFullItemsOverride: List<FullSituationItem>? = null

    @Volatile
    internal var testLessonDetailProvider: ((String) -> com.paymentstraininghub.app.data.model.LessonDetail?)? = null

    /**
     * Test-only network seam: when set, [fetchText] calls this
     * instead of reaching the network, so pagination tests can
     * record exactly which URLs were requested and serve fake
     * payloads. Null in production, always. Cleared by
     * [clearTestOverrides].
     */
    @Volatile
    internal var testFetcher: (suspend (String) -> String)? = null

    /** Clear every test override and drop the memory caches, so no fake data leaks into another test. */
    internal fun clearTestOverrides() {
        testSearchIndexOverride = null
        testSituationsOverride = null
        testWeeklyCasesOverride = null
        testFullItemsOverride = null
        testLessonDetailProvider = null
        testFetcher = null
        dropMemoryCaches()
    }

    /**
     * Drop every reloadable in-memory cache (audit fix 2), called
     * from onTrimMemory under memory pressure. The search index,
     * situations, weekly cases and full items are all re-fetched
     * on demand; the lesson-id snapshot is kept because it is
     * small and sync reads it. Clearing a volatile reference
     * while a load is in flight is safe: the load re-checks the
     * cache under its mutex and the next caller reloads.
     */
    fun dropMemoryCaches() {
        cachedIndex = null
        cachedSituations = null
        cachedSituationsIndex = null
        cachedSituationsIndexDoc = null
        chunkCache.clear()
        cachedWeeklyCases = null
        cachedFullItems = null
    }

    private val detailSources = mutableMapOf<String, LessonDetailSource>()

    // MARK: - Search index

    /// Load the search index. The result is cached in memory and the
    /// load is thread-safe: concurrent callers share one fetch.
    /// Bounded retry (3 attempts, exponential backoff). Throws
    /// IOException when every attempt fails.
    // MARK: - Reading order refresh

    /** Cached website copy of the reading-order map, for offline launches. */
    private const val READING_ORDER_CACHE_FILE = "reading-order-remote.json"

    /**
     * Refresh the display-numbering map from the website's copy, on
     * launch. The cached copy applies first (offline fallback), then
     * the website copy replaces it when the fetch succeeds and the
     * map validates. Every failure keeps the map already in force
     * (bundled or cached): display numbering never breaks because
     * the network did. Never throws.
     */
    suspend fun refreshReadingOrder(filesDir: File?) {
        if (filesDir == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                val cached = File(filesDir, READING_ORDER_CACHE_FILE)
                if (cached.exists()) {
                    ReadingOrder.applyRemote(cached.readText())
                }
            }
            // Cancellation is not a fetch failure: rethrow it
            // instead of treating a cancelled refresh as a
            // keep-the-old-map outcome.
            val body = runCatching { fetchText(READING_ORDER_URL) }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            } ?: return@withContext
            if (!ReadingOrder.applyRemote(body)) return@withContext
            runCatching {
                File(filesDir, READING_ORDER_CACHE_FILE).writeText(body)
            }
        }
    }

    // MARK: - Search index

    suspend fun loadSearchIndex(): SearchIndex {
        testSearchIndexOverride?.let { return it }
        cachedIndex?.let { return it }
        return indexMutex.withLock {
            cachedIndex?.let { return@withLock it }
            val loaded = fetchSearchIndexCompactFirst()
            val merged = mergeSupplemental(loaded)
            cachedIndex = merged
            knownLessonIds = merged.entries
                .filter { it.isLesson }
                .map { it.id }
                .toSet()
            merged
        }
    }

    /// The fetched index with the bundled supplemental lessons
    /// merged in (iOS indexWithSupplemental), so they appear in
    /// lessons, chapters and search exactly like catalogue lessons.
    /// Counts are updated, so the numbers stay honest.
    private suspend fun mergeSupplemental(index: SearchIndex): SearchIndex {
        val extra = SupplementalLessons.load().entries
        if (extra.isEmpty()) return index
        val counts = SearchCounts(
            lesson = index.counts.lesson + extra.count { it.isLesson },
            guide = index.counts.guide + extra.count { it.isGuide },
            situation = index.counts.situation + extra.count { it.isSituation },
            lab = index.counts.lab + extra.count { it.isLab },
            test = index.counts.test + extra.count { it.isTest },
            faq = index.counts.faq + extra.count { it.isFaq },
            total = index.counts.total + extra.size
        )
        return SearchIndex(counts = counts, entries = index.entries + extra)
    }

    /**
     * The search index, compact file first. One attempt at the
     * compact file: a 404 means it is not deployed, and retrying
     * it would only delay the fallback. Any failure falls back
     * to the full index with the usual retry, so search keeps
     * working exactly as before when the compact file is missing.
     * Cancellation is never swallowed: a cancelled load stays
     * cancelled instead of falling back.
     */
    private suspend fun fetchSearchIndexCompactFirst(): SearchIndex {
        try {
            val body = fetchText(COMPACT_SEARCH_INDEX_URL)
            return withContext(Dispatchers.Default) {
                json.decodeFromString<SearchIndex>(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fetchSearchIndexWithRetry()
        }
    }

    private suspend fun fetchSearchIndexWithRetry(): SearchIndex {
        var lastError: IOException = IOException("The website did not respond.")
        for (attempt in 0 until 3) {
            try {
                val body = fetchText(SEARCH_INDEX_URL)
                // The index is ~1.9 MB of JSON: decode off the caller
                // thread. Callers run on Main (LaunchedEffect), and
                // parsing here froze first launch (H3). Default is
                // the CPU-bound dispatcher; fetchText already used IO.
                return withContext(Dispatchers.Default) {
                    json.decodeFromString<SearchIndex>(body)
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException("The website did not respond.", e)
            }
            if (attempt < 2) delay(500L * (1L shl attempt))
        }
        throw lastError
    }

    // MARK: - Chapters

    /// Chapters derived from the search index, exactly like iOS:
    /// lesson entries grouped by exact chapter number (a plain prefix
    /// match on "ch1" would also catch ch10 to ch19), in numeric
    /// order. The chapter title is the chapter name parsed from the
    /// first lesson's catalogue text, falling back to "Chapter N".
    /// The domain is the domain label named in the first lesson's
    /// catalogue text, or "" when the text names none.
    suspend fun chapters(): List<Chapter> {
        val index = loadSearchIndex()
        val lessons = index.entries.filter { it.isLesson }
        val grouped = lessons.groupBy { chapterNumberFromId(it.id) }
        return grouped.entries
            .filter { it.key != null }
            .sortedBy { it.key }
            .map { (number, entries) ->
                val num = number!!
                val first = entries.first()
                Chapter(
                    id = "ch$num",
                    number = num,
                    title = first.chapterName ?: ReadingOrder.chapterLabel("ch$num"),
                    domain = first.domainLabel() ?: "",
                    lessonCount = entries.size,
                    lessonIds = entries.map { it.id }
                )
            }
    }

    /// The chapter number from a lesson id (ch<N>s<M>), or null.
    /// Shared with chapter grouping so the rule lives in one place.
    internal fun chapterNumberFromId(lessonId: String): Int? {
        if (!lessonId.startsWith("ch")) return null
        val digits = lessonId.drop(2).takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    /// True when this lesson is the first lesson of its chapter: the
    /// free sample lesson. One per chapter, full content, no purchase
    /// needed.
    fun isSampleLesson(lessonId: String, chapters: List<Chapter>): Boolean {
        for (chapter in chapters) {
            if (chapter.lessonIds.contains(lessonId)) {
                return chapter.lessonIds.firstOrNull() == lessonId
            }
        }
        return false
    }

    // MARK: - Lesson detail

    /// Fetch a lesson page and extract its content.
    /// Bounded retry (3 attempts, exponential backoff) so a single
    /// transient network failure does not surface as a lesson error.
    /// When the live fetch fails and a cached copy of the page exists
    /// from a previous open, the cached copy is returned instead, so
    /// a previously opened lesson stays readable offline. Throws only
    /// when neither the live fetch nor the cache produces a lesson.
    ///
    /// [filesDir] is the app's internal files directory
    /// (context.filesDir), used for the lesson page cache. Pass null
    /// to skip caching entirely. The lesson screen passes
    /// context.filesDir.
    suspend fun lessonDetail(
        id: String,
        filesDir: File? = null
    ): com.paymentstraininghub.app.data.model.LessonDetail {
        return lessonDetailWithSource(id, filesDir).first
    }

    /// Load a lesson detail and report where it came from. Tries the
    /// live page first; on failure, parses the cached page HTML when
    /// one exists and reports CACHED. Throws when both fail.
    suspend fun lessonDetailWithSource(
        id: String,
        filesDir: File? = null
    ): Pair<com.paymentstraininghub.app.data.model.LessonDetail, LessonDetailSource> {
        val provider = testLessonDetailProvider
        if (provider != null) {
            val detail = provider(id)
                ?: throw IOException("The test lesson detail provider has no detail for this lesson.")
            return Pair(detail, LessonDetailSource.LIVE)
        }
        try {
            val detail = fetchLiveLessonDetail(id, filesDir)
            recordSource(id, LessonDetailSource.LIVE)
            return Pair(detail, LessonDetailSource.LIVE)
        } catch (e: Exception) {
            val cached = cachedLessonDetail(id, filesDir)
            if (cached != null) {
                recordSource(id, LessonDetailSource.CACHED)
                return Pair(cached, LessonDetailSource.CACHED)
            }
            throw e
        }
    }

    /// Where the last loaded detail for this lesson came from.
    /// LIVE when the lesson has never been loaded or its last load
    /// was live. Used for the cached banner.
    suspend fun lessonDetailSource(id: String): LessonDetailSource {
        return sourceMutex.withLock { detailSources[id] ?: LessonDetailSource.LIVE }
    }

    private suspend fun recordSource(id: String, source: LessonDetailSource) {
        sourceMutex.withLock { detailSources[id] = source }
    }

    private suspend fun fetchLiveLessonDetail(
        id: String,
        filesDir: File?
    ): com.paymentstraininghub.app.data.model.LessonDetail {
        // Supplemental lessons have no page on the website: their
        // detail is built locally from the bundled entry, with no
        // network fetch, no retry delay, and nothing cached. The
        // local build is never stale, so it reports LIVE.
        if (id.endsWith("-supp")) {
            val chapterTitle = runCatching {
                chapters().firstOrNull { it.number == chapterNumberFromId(id) }?.title
            }.getOrNull()
                ?: chapterNumberFromId(id)?.let { ReadingOrder.chapterLabel("ch$it") }
                ?: "Chapter"
            return SupplementalLessons.detailFor(id, chapterTitle)
                ?: throw IOException("The supplemental lesson could not be loaded.")
        }
        val pageUrl = LESSON_BASE + id + ".html"
        var lastError: IOException = IOException("The website did not respond.")
        for (attempt in 0 until 3) {
            try {
                val html = fetchText(pageUrl)
                if (filesDir != null) {
                    // Disk write off the caller thread (audit fix
                    // 8): this ran on Main after fetchText returned,
                    // adding jank to the lesson-open path.
                    withContext(Dispatchers.IO) {
                        CacheStore.writeCached(filesDir, id, html)
                    }
                }
                // HTML parsing off the caller (Main) thread: lesson
                // pages are large and the parse froze navigation.
                return withContext(Dispatchers.Default) {
                    LessonDetailParser.parse(id, html)
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException("The website did not respond.", e)
            }
            if (attempt < 2) delay(500L * (1L shl attempt))
        }
        throw lastError
    }

    private suspend fun cachedLessonDetail(
        id: String,
        filesDir: File?
    ): com.paymentstraininghub.app.data.model.LessonDetail? {
        if (filesDir == null) return null
        // Disk read plus HTML parse, both off the caller thread.
        return withContext(Dispatchers.IO) {
            val html = CacheStore.readCached(filesDir, id) ?: return@withContext null
            LessonDetailParser.parse(id, html)
        }
    }

    // MARK: - Situations

    /// Stage names, the same six learning stages as the website.
    val situationStageNames: Map<Int, String> = mapOf(
        1 to "Payment mechanics",
        2 to "Processing decisions",
        3 to "Operational exceptions",
        4 to "Cross-system consequences",
        5 to "Complex banking scenarios",
        6 to "Senior SME judgement"
    )

    /// The stage name for a stage number.
    fun stageNameFor(stage: Int): String =
        situationStageNames[stage] ?: "Stage $stage"

    /// The per-item stage map from the website
    /// (content/premium-500/situation-stage-map.json). The 35 items
    /// added after the map was written are not in it and fall back
    /// by level. Generated from the map file; do not hand-edit.
    private val situationStageMap: Map<String, Int> = mapOf(
        "ABH-01" to 1, "ABH-02" to 1, "ABH-03" to 3, "ABH-04" to 1,
        "ABH-05" to 1, "ABH-06" to 1, "ABH-07" to 1, "ABH-08" to 1,
        "ABH-09" to 1, "ABH-10" to 2, "ABH-11" to 1, "ABH-12" to 2,
        "ABH-13" to 1, "ABH-14" to 2, "ABH-15" to 2, "ABH-16" to 2,
        "ABH-17" to 2, "ABH-18" to 6, "ABH-19" to 1, "ABH-20" to 1,
        "ABH-21" to 1, "ABH-22" to 1, "ABH-23" to 1, "ABH-24" to 2,
        "ABH-25" to 6, "ABH-26" to 6, "ABH-27" to 1, "ABH-28" to 1,
        "ABH-29" to 1, "ABH-30" to 1, "ABH-31" to 6, "ABH-32" to 6,
        "ABH-33" to 6, "ABH-34" to 6, "ABH-35" to 6, "ABH-36" to 6,
        "ABH-37" to 6, "ABH-38" to 6, "ABH-39" to 6, "ABH-40" to 6,
        "ABH-41" to 6, "ABH-42" to 6, "ABH-43" to 6, "ABH-44" to 6,
        "ABH-45" to 6, "ABH-46" to 6, "ABH-47" to 6, "ABH-48" to 6,
        "ABH-49" to 6, "ABH-50" to 6, "INS-001" to 1, "INS-002" to 1,
        "INS-003" to 2, "INS-004" to 4, "INS-005" to 3, "INS-006" to 4,
        "INS-007" to 6, "INS-008" to 6, "INS-009" to 6, "INS-010" to 6,
        "INS-011" to 6, "INS-012" to 1, "INS-013" to 6, "INS-014" to 6,
        "INS-015" to 6, "INS-016" to 6, "INS-017" to 6, "INS-018" to 6,
        "INS-019" to 6, "INS-020" to 6, "INS-021" to 6, "INS-022" to 6,
        "INS-023" to 6, "INS-024" to 2, "INS-025" to 6, "INS-026" to 6,
        "INS-027" to 1, "INS-028" to 1, "INS-029" to 1, "INS-030" to 6,
        "INS-031" to 1, "INS-032" to 4, "INS-033" to 4, "INS-034" to 4,
        "INS-035" to 4, "INS-036" to 4, "INS-037" to 6, "INS-038" to 6,
        "INS-039" to 6, "INS-040" to 6, "INS-041" to 6, "INS-042" to 6,
        "INS-043" to 6, "INS-044" to 6, "INS-045" to 6, "INS-046" to 6,
        "INS-047" to 6, "INS-048" to 6, "INS-049" to 6, "INS-050" to 3,
        "INS-051" to 6, "INS-052" to 6, "INS-053" to 6, "INS-054" to 6,
        "INS-055" to 6, "INS-056" to 6, "INS-057" to 4, "INS-058" to 6,
        "INS-059" to 6, "INS-060" to 6, "INS-061" to 6, "INS-062" to 4,
        "INS-063" to 6, "INS-064" to 4, "INS-065" to 6, "INS-066" to 5,
        "INS-067" to 6, "INS-068" to 6, "INS-069" to 1, "INS-070" to 1,
        "INS-071" to 4, "INS-072" to 6, "INS-073" to 6, "INS-074" to 6,
        "INS-075" to 6, "INS-076" to 6, "INS-077" to 6, "INS-078" to 6,
        "INS-079" to 6, "INS-080" to 6, "INS-081" to 6, "INS-082" to 6,
        "INS-083" to 6, "INS-084" to 6, "INS-085" to 6, "INS-086" to 3,
        "INS-087" to 3, "INS-088" to 1, "INS-089" to 6, "INS-090" to 1,
        "INS-091" to 6, "INS-092" to 6, "INS-093" to 6, "INS-094" to 6,
        "INS-095" to 6, "INS-096" to 6, "INS-097" to 6, "INS-098" to 6,
        "INS-099" to 6, "INS-100" to 6, "INS-101" to 1, "INS-102" to 1,
        "INS-103" to 4, "INS-104" to 1, "INS-105" to 1, "INS-106" to 2,
        "INS-107" to 2, "INS-108" to 3, "INS-109" to 3, "INS-110" to 2,
        "INS-111" to 2, "INS-112" to 3, "INS-113" to 2, "INS-114" to 6,
        "INS-115" to 6, "INS-116" to 3, "INS-117" to 2, "INS-118" to 3,
        "INS-119" to 6, "INS-120" to 6, "INS-121" to 1, "INS-122" to 1,
        "INS-123" to 1, "INS-124" to 1, "INS-125" to 1, "INS-126" to 1,
        "INS-127" to 5, "INS-128" to 1, "INS-129" to 3, "INS-130" to 3,
        "INS-131" to 3, "INS-132" to 3, "INS-133" to 3, "INS-134" to 3,
        "INS-135" to 5, "INS-136" to 4, "INS-137" to 5, "INS-138" to 2,
        "INS-139" to 1, "INS-140" to 2, "INS-141" to 5, "INS-142" to 5,
        "INS-143" to 5, "INS-144" to 5, "INS-145" to 2, "INS-146" to 2,
        "INS-147" to 4, "INS-148" to 6, "INS-149" to 2, "INS-150" to 5,
        "INS-151" to 4, "INS-152" to 1, "INS-153" to 5, "INS-154" to 1,
        "INS-155" to 1, "INS-156" to 1, "INS-157" to 1, "INS-158" to 3,
        "INS-159" to 3, "INS-160" to 3, "INS-161" to 3, "INS-162" to 3,
        "INS-163" to 1, "INS-164" to 5, "INS-165" to 1, "INS-166" to 5,
        "INS-167" to 5, "INS-168" to 1, "INS-169" to 2, "INS-170" to 4,
        "JEB-A01" to 6, "JEB-A02" to 3, "JEB-A03" to 3, "JEB-A04" to 4,
        "JEB-A05" to 3, "JEB-A06" to 2, "JEB-A07" to 2, "JEB-A08" to 1,
        "JEB-A09" to 2, "JEB-A10" to 4, "JEB-A11" to 1, "JEB-A12" to 3,
        "JEB-A13" to 1, "JEB-A14" to 3, "JEB-A15" to 4, "JEB-B01" to 1,
        "JEB-B02" to 2, "JEB-B03" to 6, "JEB-B04" to 1, "JEB-B05" to 5,
        "JEB-B06" to 6, "JEB-B07" to 4, "JEB-B08" to 2, "JEB-B09" to 2,
        "JEB-B10" to 6, "JEB-B11" to 1, "JEB-B12" to 2, "JEB-B13" to 3,
        "JEB-B14" to 1, "JEB-C01" to 2, "JEB-C02" to 4, "JEB-C03" to 2,
        "JEB-C04" to 1, "JEB-C05" to 2, "JEB-C06" to 2, "JEB-C07" to 6,
        "JEB-C08" to 1, "JEB-C09" to 6, "JEB-C10" to 6, "JEB-C11" to 1,
        "JEB-C12" to 3, "JEB-C13" to 1, "JEB-C14" to 6, "JEB-C15" to 1,
        "JEB-D01" to 1, "JEB-D02" to 2, "JEB-D03" to 1, "JEB-D04" to 3,
        "JEB-D05" to 3, "JEB-D06" to 2, "JEB-D07" to 5, "JEB-D08" to 1,
        "JEB-D09" to 5, "JEB-D10" to 1, "JEB-D11" to 1, "JEB-D12" to 1,
        "JEB-D13" to 5, "JEB-D14" to 1, "JEB-D15" to 5, "JEB-E01" to 6,
        "JEB-E02" to 3, "JEB-E03" to 6, "JEB-E04" to 3, "JEB-E05" to 5,
        "JEB-E06" to 4, "JEB-E07" to 6, "JEB-E08" to 2, "JEB-E09" to 2,
        "JEB-E10" to 1, "JEB-E11" to 1, "JEB-E12" to 1, "JEB-E13" to 1,
        "JEB-E14" to 1, "JEB-E15" to 2, "JEB-F01" to 6, "JEB-F02" to 2,
        "JEB-F03" to 6, "JEB-F04" to 5, "JEB-F05" to 6, "JEB-F06" to 6,
        "JEB-F07" to 2, "JEB-F08" to 6, "JEB-F09" to 6, "JEB-F10" to 1,
        "JEB-F11" to 2, "JEB-F12" to 1, "JEB-F13" to 5, "JEB-F14" to 3,
        "PAI-C001" to 1, "PAI-C002" to 1, "PAI-C003" to 2, "PAI-C004" to 1,
        "PAI-C005" to 1, "PAI-C006" to 1, "PAI-C007" to 1, "PAI-C008" to 1,
        "PAI-C009" to 3, "PAI-C010" to 3, "PAI-C011" to 3, "PAI-C012" to 3,
        "PAI-C013" to 3, "PAI-C014" to 3, "PAI-C015" to 3, "PAI-C016" to 3,
        "PAI-C017" to 3, "PAI-C018" to 4, "PAI-C019" to 4, "PAI-C020" to 2,
        "PAI-C021" to 3, "PAI-C022" to 1, "PAI-C023" to 1, "PAI-C024" to 1,
        "PAI-C025" to 4, "PAI-C026" to 1, "PAI-C027" to 3, "PAI-C028" to 3,
        "PAI-C029" to 3, "PAI-C030" to 3, "PAI-C031" to 3, "PAI-C032" to 3,
        "PAI-C033" to 1, "PAI-C034" to 3, "PAI-C035" to 2, "PAI-C036" to 4,
        "PAI-C037" to 3, "PAI-C038" to 5, "PAI-C039" to 1, "PAI-C040" to 1,
        "PAI-C041" to 1, "PAI-C042" to 3, "PAI-C043" to 1, "PAI-C044" to 5,
        "PAI-C045" to 2, "PAI-C046" to 3, "PAI-C047" to 4, "PAI-C048" to 1,
        "PAI-C049" to 5, "PAI-C050" to 1, "PAI-C051" to 5, "PAI-C052" to 1,
        "PAI-C053" to 3, "PAI-C054" to 2, "PAI-C055" to 1, "PAI-C056" to 1,
        "PAI-C057" to 1, "PAI-C058" to 2, "PAI-C059" to 1, "PAI-C060" to 3,
        "PAI-C061" to 2, "PAI-C062" to 4, "PAI-C063" to 1, "PAI-C064" to 2,
        "PAI-C065" to 1, "PAI-C066" to 1, "PAI-C067" to 5, "PAI-C068" to 1,
        "PAI-C069" to 1, "PAI-C070" to 1, "PAI-C071" to 5, "PAI-C072" to 3,
        "PAI-C073" to 3, "PAI-C074" to 1, "PAI-C075" to 4, "PAI-C076" to 4,
        "PAI-C077" to 1, "PAI-C078" to 1, "PAI-C079" to 1, "PAI-C080" to 1,
        "PAI-C081" to 1, "PAI-C082" to 1, "PAI-C083" to 1, "PAI-C084" to 1,
        "PAI-C085" to 1, "PAI-C086" to 1, "PAI-C087" to 1, "PAI-C088" to 1,
        "PAI-C089" to 4, "PAI-C090" to 3, "PAI-C091" to 1, "PAI-C092" to 1,
        "PAI-C093" to 1, "PAI-C094" to 2, "PAI-C095" to 1, "PAI-C096" to 1,
        "PAI-C097" to 1, "PAI-C098" to 1, "PAI-C099" to 4, "PAI-C100" to 1,
        "PAI-C101" to 1, "PAI-C102" to 1, "RUN3-001" to 3, "RUN3-002" to 4,
        "RUN3-003" to 1, "RUN3-004" to 3, "RUN3-005" to 3, "RUN3-006" to 3,
        "RUN3-007" to 1, "RUN3-008" to 1, "RUN3-009" to 1, "RUN3-010" to 1,
        "RUN3-011" to 1, "RUN3-012" to 1, "RUN3-013" to 1, "RUN4-001" to 1,
        "RUN4-002" to 5, "RUN4-003" to 5, "RUN4-004" to 1, "RUN4-005" to 1,
        "RUN4-006" to 1, "RUN4-007" to 1, "RUN4-008" to 5, "RUN4-009" to 1,
        "RUN4-010" to 4, "RUN4-011" to 4, "RUN4-012" to 2, "RUN4-013" to 5,
        "RUN4-014" to 5, "RUN4-015" to 5, "RUN4-016" to 5, "RUN4-017" to 5,
        "RUN4-018" to 1, "RUN4-019" to 5, "RUN4-020" to 1, "RUN5-001" to 1,
        "RUN5-002" to 1, "RUN5-003" to 1, "RUN5-004" to 1, "RUN5-005" to 1,
        "RUN5-006" to 1, "RUN5-007" to 1, "RUN5-008" to 1, "RUN6-001" to 1,
        "RUN6-002" to 1, "RUN6-003" to 1, "RUN6-004" to 1, "RUN6-005" to 6,
        "RUN6-006" to 1, "RUN6-007" to 1, "RUN6-008" to 1, "RUN6-009" to 1,
        "RUN6-010" to 1, "RUN6-011" to 1, "RUN6-012" to 2, "RUN6-013" to 2,
        "RUN6-014" to 5, "RUN6-015" to 1, "RUN6-016" to 1, "RUN6-017" to 1,
        "RUN6-018" to 1, "RUN6-019" to 1, "RUN6-020" to 1, "RUN6-021" to 1,
        "RUN6-022" to 1, "RUN6-023" to 1, "RUN6-024" to 1, "RUN6-025" to 1,
        "RUN6-026" to 1, "RUN6-027" to 2, "RUN6-028" to 1, "RUN6-029" to 1,
        "RUN6-030" to 1, "RUN6-031" to 1, "RUN6-032" to 1, "RUN7-001" to 1,
        "RUN7-002" to 1, "RUN7-003" to 1, "RUN7-004" to 1, "RUN7-005" to 1,
        "RUN7-006" to 3, "RUN7-007" to 1, "RUN7-008" to 1, "RUN7-009" to 1,
        "RUN7-010" to 6, "SCN-01" to 4, "SCN-02" to 4, "SCN-03" to 5,
        "SCN-04" to 3, "SCN-05" to 3, "SCN-06" to 3, "SCN-07" to 4,
        "SCN-08" to 3, "SCN-09" to 2, "SCN-10" to 4, "SCN-11" to 3,
        "SCN-12" to 2, "SCN-13" to 3, "SCN-14" to 3, "SCN-15" to 1,
        "SCN-16" to 3, "SCN-17" to 1, "SCN-18" to 2, "SCN-19" to 3,
        "SCN-20" to 1, "SCN-21" to 4, "SCN-22" to 3, "SCN-23" to 2,
        "SCN-24" to 2, "SCN-25" to 2, "SCN-26" to 6, "SCN-27" to 4,
        "SCN-28" to 6, "SCN-29" to 4, "SCN-30" to 3, "SCN-31" to 2,
        "SCN-32" to 3, "SCN-33" to 6, "SCN-34" to 4, "SCN-35" to 4,
        "SCN-36" to 4,
    
    )

    /// Where a situation's stage came from. AUTHORITATIVE means
    /// the live data file's own `stage` field; STAGE_MAP means the
    /// website per-item stage map; LEVEL_FALLBACK means the level
    /// fallback (foundation to 1, advanced to 6, anything else to 3).
    /// Only AUTHORITATIVE is presented as the item's stage.
    enum class SituationStageSource {
        AUTHORITATIVE,
        STAGE_MAP,
        LEVEL_FALLBACK
    }

    /// The stage source for one full situation item.
    fun stageSourceFor(item: FullSituationItem): SituationStageSource {
        val stage = item.resolvedStage
        if (stage != null && stage in 1..6) return SituationStageSource.AUTHORITATIVE
        if (situationStageMap[item.id] != null) return SituationStageSource.STAGE_MAP
        return SituationStageSource.LEVEL_FALLBACK
    }

    /// The stage for one full situation item. Priority: the data
    /// file's own `stage` field when it is present and in range
    /// (authoritative), then the website per-item stage map, then
    /// the level fallback. No stage numbers are invented beyond
    /// that map and fallback.
    fun stageFor(item: FullSituationItem): Int {
        val stage = item.resolvedStage
        if (stage != null && stage in 1..6) return stage
        val mapped = situationStageMap[item.id]
        if (mapped != null && mapped in 1..6) return mapped
        return when (item.level.lowercase()) {
            "foundation" -> 1
            "advanced" -> 6
            else -> 3
        }
    }

    /// The stage name for one full situation item: the data file's
    /// own `stage_name` when it is present and the stage is
    /// authoritative, otherwise the standard name for the resolved
    /// stage.
    fun stageNameFor(item: FullSituationItem): String {
        val stage = stageFor(item)
        if (stageSourceFor(item) == SituationStageSource.AUTHORITATIVE) {
            val name = item.stageName
            if (!name.isNullOrEmpty()) return name
        }
        return stageNameFor(stage)
    }

    /// Load the full situations list. The file is a JS assignment
    /// (window.PA_SITUATIONS = [...];) so the prefix and trailing
    /// semicolon are stripped before JSON decoding. Bounded retry
    /// (3 attempts, exponential backoff). The result is merged with
    /// the search index excerpts like iOS: full items first, plus any
    /// index situation entry missing from the full file, built from
    /// the entry with a derived stage. When the full file cannot be
    /// fetched at all, the list falls back to the search index
    /// entries so it never goes empty. The result is cached in
    /// memory and the load is thread-safe.
    suspend fun situations(): List<Situation> {
        testSituationsOverride?.let { return it }
        cachedSituations?.let { return it }
        return situationsMutex.withLock {
            cachedSituations?.let { return@withLock it }
            val loaded = loadSituationsMerged()
            cachedSituations = loaded
            loaded
        }
    }

    /// The lightweight Payment Cases list: id, title, stage and
    /// level per case, from situations-index.json (about 120 KB),
    /// never the full 1.8 MB data file. The list renders titles
    /// and pills only, so explain, takeaway and visual stay
    /// empty here; full detail arrives per case through
    /// [situationDetail]. When the index file cannot be fetched,
    /// the list falls back to [situations], so it never goes
    /// empty because the compact file is missing. Practice Mode,
    /// the Daily Challenge and the investigation engine keep
    /// using [situations] and [fullSituationItems]: they need
    /// full case text for several cases at once, which the
    /// lightweight list deliberately does not carry.
    suspend fun situationsIndex(): List<Situation> {
        testSituationsOverride?.let { return it }
        cachedSituationsIndex?.let { return it }
        return situationsIndexMutex.withLock {
            cachedSituationsIndex?.let { return@withLock it }
            val loaded = try {
                loadSituationsIndexDoc().items.map { situationFromIndexEntry(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                situations()
            }
            cachedSituationsIndex = loaded
            loaded
        }
    }

    /// One list row from one index record. Derived records keep
    /// their generated stage and level and are marked derived;
    /// every other record goes through [situationFromFull] on a
    /// shell item, so stage, stage name, level and the free
    /// sample mark resolve through the same code as the full load.
    internal fun situationFromIndexEntry(entry: SituationsIndexEntry): Situation {
        if (entry.derived) {
            val stage = entry.resolvedStage?.takeIf { it in 1..6 } ?: 1
            val level = when (entry.level.lowercase()) {
                "foundation" -> "foundation"
                "advanced" -> "advanced"
                else -> "core"
            }
            return Situation(
                id = entry.id,
                title = entry.title,
                stage = stage,
                stageName = entry.stageName?.takeIf { it.isNotEmpty() } ?: "Stage $stage",
                level = level,
                explain = "",
                takeaway = "",
                isFreeSample = freeSampleSituationIds.contains(entry.id),
                isStageAuthoritative = false,
                visual = null
            )
        }
        return situationFromFull(
            FullSituationItem(
                id = entry.id,
                title = entry.title,
                level = entry.level,
                stage = entry.stage,
                stageName = entry.stageName,
                domain = entry.domain
            )
        )
    }

    /// The cached lightweight index document, fetched once. Used
    /// by [situationsIndex] for the list and by [situationDetail]
    /// to find the chunk holding one case.
    private suspend fun loadSituationsIndexDoc(): SituationsIndexDoc {
        cachedSituationsIndexDoc?.let { return it }
        return situationsIndexDocMutex.withLock {
            cachedSituationsIndexDoc?.let { return@withLock it }
            val body = fetchText(SITUATIONS_INDEX_URL)
            val doc = withContext(Dispatchers.Default) {
                json.decodeFromString<SituationsIndexDoc>(body)
            }
            cachedSituationsIndexDoc = doc
            doc
        }
    }

    /// The full detail of one case: explain, takeaway and visual.
    /// Fetches only the chunk holding the case (28 cases), never
    /// the whole data file. When the chunk path fails for any
    /// reason, the full file is the fallback, then the loaded
    /// list. Returns null when the case exists nowhere. The
    /// detail screen calls this instead of loading the whole
    /// list and picking one id.
    suspend fun situationDetail(id: String): Situation? {
        testSituationsOverride?.let { return it.firstOrNull { it.id == id } }
        try {
            detailFromChunks(id)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall through to the full-file fallback below.
        }
        val fromFull = try {
            fullSituationItems().firstOrNull { it.id == id }?.let { situationFromFull(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fromFull != null) return fromFull
        return try {
            situations().firstOrNull { it.id == id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /// The chunk path for one case: the index record names its
    /// chunk, the chunk file is fetched (and cached) once, and
    /// the case is picked out of it. Null when the index has no
    /// record for the id, the record names no chunk, or the
    /// chunk does not hold the case.
    private suspend fun detailFromChunks(id: String): Situation? {
        val doc = loadSituationsIndexDoc()
        val entry = doc.items.firstOrNull { it.id == id } ?: return null
        val chunkNumber = entry.chunk ?: return null
        val items = loadChunk(chunkNumber)
        return items.firstOrNull { it.id == id }?.let { situationFromFull(it) }
    }

    /// One detail chunk, fetched once and cached in memory.
    private suspend fun loadChunk(chunkNumber: Int): List<FullSituationItem> {
        chunkCache[chunkNumber]?.let { return it }
        return chunkMutex.withLock {
            chunkCache[chunkNumber]?.let { return@withLock it }
            val text = fetchText(situationsChunkUrl(chunkNumber))
            val items = withContext(Dispatchers.Default) {
                // Chunk files are bare JSON arrays: the parser's
                // defensive bracket search handles them.
                parseSituationItemsData(text, "window.PA_SITUATIONS = ")
            }
            chunkCache[chunkNumber] = items
            items
        }
    }

    /// The URL of one detail chunk file. Chunk numbers format
    /// with two digits, matching the generated file names.
    internal fun situationsChunkUrl(chunkNumber: Int): String =
        SITUATIONS_CHUNK_BASE_URL + "chunk-%02d.json".format(chunkNumber)

    /// The raw full situations data-file items, as loaded from the
    /// live file, for callers that need the stage-map fields
    /// (messages, failure_category, resolution, domain) that
    /// [Situation] does not carry. The investigation engine reads
    /// these fields through this accessor, mirroring iOS
    /// ContentService.fullSituations. The result is cached in
    /// memory and the load is thread-safe. Throws IOException
    /// when every fetch attempt fails; callers that can proceed
    /// without the raw fields catch and pass null instead.
    suspend fun fullSituationItems(): List<FullSituationItem> {
        testFullItemsOverride?.let { return it }
        cachedFullItems?.let { return it }
        return fullItemsMutex.withLock {
            cachedFullItems?.let { return@withLock it }
            val loaded = fetchFullSituationsWithRetry()
            cachedFullItems = loaded
            loaded
        }
    }

    private suspend fun loadSituationsMerged(): List<Situation> {
        val fullItems = try {
            fetchFullSituationsWithRetry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val indexEntries = try {
            loadSearchIndex().entries.filter { it.isSituation }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (fullItems == null) {
            if (indexEntries.isEmpty()) {
                throw IOException("The website did not respond.")
            }
            return indexEntries.map { situationFromEntry(it) }
        }
        val result = fullItems.map { situationFromFull(it) }.toMutableList()
        val seenIds = result.map { it.id }.toHashSet()
        for (entry in indexEntries) {
            if (!seenIds.contains(entry.id)) {
                result.add(situationFromEntry(entry))
            }
        }
        return result
    }

    private suspend fun fetchFullSituationsWithRetry(): List<FullSituationItem> {
        var lastError: IOException = IOException("The website did not respond.")
        for (attempt in 0 until 3) {
            try {
                val text = fetchText(SITUATIONS_URL)
                // Same H3 fix as the search index: the situations
                // file is large; parse off the caller (Main) thread.
                return withContext(Dispatchers.Default) {
                    parseSituationsData(text)
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException("The website did not respond.", e)
            }
            if (attempt < 2) delay(500L * (1L shl attempt))
        }
        throw lastError
    }

    /// Strip the JS assignment wrapper and decode the situations
    /// array. Items that fail to decode individually are skipped so
    /// one malformed item cannot empty the whole list.
    internal fun parseSituationsData(text: String): List<FullSituationItem> {
        return parseSituationItemsData(text, "window.PA_SITUATIONS = ")
    }

    /// The shared JS-assignment parser for the situations and weekly
    /// cases data files. Both files are the same FullSituationItem
    /// shape; the weekly file carries an extra `week` field, which
    /// the decoder ignores (ignoreUnknownKeys). When the wrapper
    /// spelling differs, the array brackets are found defensively.
    internal fun parseSituationItemsData(text: String, prefix: String): List<FullSituationItem> {
        var cleaned = text.trim()
        if (cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length).trim()
        } else {
            // Defensive: find the array brackets when the wrapper
            // spelling differs.
            val open = cleaned.indexOf('[')
            val close = cleaned.lastIndexOf(']')
            if (open >= 0 && close > open) {
                cleaned = cleaned.substring(open, close + 1)
            }
        }
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.dropLast(1).trim()
        }
        val array = json.decodeFromString<JsonArray>(cleaned)
        val items = mutableListOf<FullSituationItem>()
        for (element in array) {
            try {
                // Non-reified form: the reified decodeFromJsonElement<T>
                // overload arrived in kotlinx-serialization 1.7, which
                // needs Kotlin 2.0; this project pins Kotlin 1.9.22
                // with the 1.6.3 runtime.
                val item = json.decodeFromJsonElement(FullSituationItem.serializer(), element)
                if (item.id.isNotBlank()) items.add(item)
            } catch (e: Exception) {
                // Skip one malformed item; never fail the whole list.
            }
        }
        return items
    }

    // MARK: - Weekly cases

    /// The 52 weekly free cases, in week order, from the dedicated
    /// weekly cases data file (window.PA_WEEKLY_CASES). Free public
    /// content: no purchase and no sign-in required. Bounded retry
    /// (3 attempts, exponential backoff), cached in memory, thread
    /// safe. Throws IOException when every attempt fails. Callers
    /// that can fall back to the catalogue should use
    /// weeklyCasesOrNull instead. No disk cache: consistent with
    /// the v1 decision not to cache the index or situations on
    /// disk (assumption #11). iOS keeps a disk cache for the
    /// weekly list; the absence here is a documented deviation.
    /// Mirrors ContentService.weeklyCases.
    suspend fun weeklyCases(): List<Situation> {
        testWeeklyCasesOverride?.let { return it }
        cachedWeeklyCases?.let { return it }
        return weeklyCasesMutex.withLock {
            cachedWeeklyCases?.let { return@withLock it }
            val loaded = fetchWeeklyCasesWithRetry().map { situationFromFull(it) }
            cachedWeeklyCases = loaded
            loaded
        }
    }

    /// The weekly cases, or null when the list cannot be loaded.
    /// Callers use the catalogue fallback pick when this is null,
    /// matching iOS WeeklyFreeCaseView.
    suspend fun weeklyCasesOrNull(): List<Situation>? {
        return try {
            weeklyCases()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchWeeklyCasesWithRetry(): List<FullSituationItem> {
        var lastError: IOException = IOException("The website did not respond.")
        for (attempt in 0 until 3) {
            try {
                val text = fetchText(WEEKLY_CASES_URL)
                // Same H3 fix: parse off the caller (Main) thread.
                return withContext(Dispatchers.Default) {
                    parseSituationItemsData(text, "window.PA_WEEKLY_CASES = ")
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = IOException("The website did not respond.", e)
            }
            if (attempt < 2) delay(500L * (1L shl attempt))
        }
        throw lastError
    }

    /// Build a Situation from one full data-file item. The stage and
    /// stage name come from the data file's own `stage` and
    /// `stage_name` fields when present; ContentRepository falls back
    /// to the website stage map and then to the level, and the result
    /// is marked derived in that case. The level comes from the item
    /// itself, and the explain, takeaway and visual are the full
    /// ones, not the search index excerpt.
    fun situationFromFull(item: FullSituationItem): Situation {
        val level = when (item.level.lowercase()) {
            "foundation" -> "foundation"
            "advanced" -> "advanced"
            else -> "core"
        }
        return Situation(
            id = item.id,
            title = item.title,
            stage = stageFor(item),
            stageName = stageNameFor(item),
            level = level,
            explain = item.explain,
            takeaway = item.takeaway,
            isFreeSample = freeSampleSituationIds.contains(item.id),
            isStageAuthoritative = stageSourceFor(item) == SituationStageSource.AUTHORITATIVE,
            visual = item.visual
        )
    }

    /// Build a Situation from a search index entry.
    ///
    /// The search index carries only id, title and text for situations,
    /// so stage, stage name, level, explain and takeaway are derived
    /// as follows: explain is the entry text, takeaway is the entry
    /// preview, and stage and level use the defaults below unless the
    /// entry text contains a stage or level hint. No stage numbers or
    /// levels are invented beyond those hints. The result is marked
    /// derived (isStageAuthoritative is false): the authoritative
    /// stage lives in the full data file, not the index.
    fun situationFromEntry(entry: SearchEntry): Situation {
        val lower = entry.text.lowercase()
        var stage = 1
        var stageName = "Stage 1"
        for (candidate in 1..6) {
            if (lower.contains("stage $candidate")) {
                stage = candidate
                stageName = "Stage $candidate"
                break
            }
        }
        val level = when {
            lower.contains("foundation") -> "foundation"
            lower.contains("advanced") -> "advanced"
            else -> "core"
        }
        return Situation(
            id = entry.id,
            title = entry.title,
            stage = stage,
            stageName = stageName,
            level = level,
            explain = entry.text,
            takeaway = entry.preview,
            isFreeSample = freeSampleSituationIds.contains(entry.id),
            isStageAuthoritative = false,
            visual = null
        )
    }

    // MARK: - Audio URLs

    /// The audio URL for a lesson file: primary host by default,
    /// fallback host when [primary] is false.
    fun audioUrl(fileName: String, primary: Boolean = true): String =
        (if (primary) PRIMARY_AUDIO else FALLBACK_AUDIO) + fileName

    // MARK: - Network

    /// Fetch a URL as text. Throws IOException on any failure or
    /// non-200 response. Runs on the IO dispatcher. Tests set
    /// [testFetcher] to record requests and serve fake payloads.
    private suspend fun fetchText(url: String): String {
        testFetcher?.let { return it(url) }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The website did not respond.")
                }
                response.body?.string() ?: throw IOException("The website did not respond.")
            }
        }
    }
}
