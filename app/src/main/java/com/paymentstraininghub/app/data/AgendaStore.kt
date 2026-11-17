package com.paymentstraininghub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// AgendaStore.kt
// Agendas and thumbnails for lessons, chapters, courses and
// cases, from the website's agendas.json. The file is fetched
// once, cached to disk, and applied silently: when the fetch or
// the cache fails, the app keeps working with no agendas, and no
// screen ever crashes or claims content exists. Key scheme,
// verified against the live file on 16 Nov 2026:
//   lessons keyed by the lesson id (ch1s1), the same id the
//     search index uses;
//   chapters keyed by the chapter id (ch1 to ch100);
//   courses keyed by the bundle id (integrated, part-1 to
//     part-10), the same id ProductCatalogue uses;
//   cases keyed by the case id (ABH-01).
// Thumbnail paths are relative (assets/thumbs/ch1s1.svg);
// thumbnailUrl resolves them against the website base. No em
// dashes in any user-facing text.

/** One agenda entry: the why-take-it text and a thumbnail path. */
data class AgendaEntry(
    val agenda: String,
    val thumbnail: String?
)

/** The whole agendas document, split into its four maps. */
data class Agendas(
    val lessons: Map<String, AgendaEntry> = emptyMap(),
    val chapters: Map<String, AgendaEntry> = emptyMap(),
    val courses: Map<String, AgendaEntry> = emptyMap(),
    val cases: Map<String, AgendaEntry> = emptyMap()
) {
    val isEmpty: Boolean
        get() = lessons.isEmpty() && chapters.isEmpty() && courses.isEmpty() && cases.isEmpty()

    /** The agenda entry for a lesson id, or null when absent. */
    fun lesson(id: String): AgendaEntry? = lessons[id]

    /** The agenda entry for a chapter id (chN), or null when absent. */
    fun chapter(chapterId: String): AgendaEntry? = chapters[chapterId]

    /** The agenda entry for a bundle id (part-N, integrated), or null when absent. */
    fun course(bundleId: String): AgendaEntry? = courses[bundleId]

    /** The agenda entry for a case id, or null when absent. */
    fun case(caseId: String): AgendaEntry? = cases[caseId]
}

@Serializable
private data class AgendaEntryJson(
    val agenda: String = "",
    val thumbnail: String? = null
)

@Serializable
private data class AgendasJson(
    val lessons: Map<String, AgendaEntryJson> = emptyMap(),
    val chapters: Map<String, AgendaEntryJson> = emptyMap(),
    val courses: Map<String, AgendaEntryJson> = emptyMap(),
    val cases: Map<String, AgendaEntryJson> = emptyMap()
)

/**
 * Parse agendas.json text. Never throws: a malformed document
 * yields empty maps, and entries with a blank agenda are dropped,
 * so a half-written file can never surface an empty agenda block.
 * Pure, so the key joins are pinned by unit tests.
 */
fun parseAgendas(jsonText: String): Agendas {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    val decoded = runCatching { json.decodeFromString<AgendasJson>(jsonText) }.getOrNull()
        ?: return Agendas()
    fun convert(map: Map<String, AgendaEntryJson>): Map<String, AgendaEntry> =
        map.mapNotNull { (key, value) ->
            if (key.isBlank() || value.agenda.isBlank()) null
            else key to AgendaEntry(value.agenda, value.thumbnail?.takeIf { it.isNotBlank() })
        }.toMap()
    return Agendas(
        lessons = convert(decoded.lessons),
        chapters = convert(decoded.chapters),
        courses = convert(decoded.courses),
        cases = convert(decoded.cases)
    )
}

/** Resolve a relative thumbnail path against the website base. Absolute URLs pass through. */
fun agendaThumbnailUrl(relativePath: String): String {
    val trimmed = relativePath.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return AgendaStore.WEBSITE_BASE + trimmed.trimStart('/')
}

/**
 * What the last agendas load did. Screens keep rendering with no
 * agendas when a load fails, but the failure is recorded here so
 * Diagnostics can show the cause instead of hiding it.
 */
data class AgendaLoadStatus(
    /** Epoch millis of the last network attempt, 0 when none has run. */
    val lastAttemptMs: Long = 0L,
    /** Epoch millis of the last successful load, 0 when none has succeeded. */
    val lastSuccessMs: Long = 0L,
    /** Short description of the last failure, null when the last attempt succeeded. */
    val lastError: String? = null
)

object AgendaStore {
    const val AGENDAS_URL = "https://www.paymentstraininghub.com/assets/agendas.json"
    const val WEBSITE_BASE = "https://www.paymentstraininghub.com/"

    private const val CACHE_FILE = "agendas.json"

    /** Minimum gap between network attempts, so a dead network cannot spin fetch retries. */
    private const val RETRY_GAP_MS = 5 * 60 * 1000L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    private val _agendas = MutableStateFlow(Agendas())
    val agendas: StateFlow<Agendas> = _agendas.asStateFlow()

    private val _loadStatus = MutableStateFlow(AgendaLoadStatus())
    val loadStatus: StateFlow<AgendaLoadStatus> = _loadStatus.asStateFlow()

    @Volatile
    private var lastAttemptMs: Long = 0L

    @Volatile
    private var cacheApplied: Boolean = false

    /**
     * Test-only fetch override. When set, refresh uses it instead
     * of the network, so tests can script success and failure
     * without touching the network. Never set in production code.
     */
    internal var testFetcher: (suspend (String) -> String?)? = null

    /** Test-only: install agendas directly, as if a fetch had succeeded. */
    internal fun setTestAgendas(value: Agendas) {
        _agendas.value = value
        _loadStatus.value = AgendaLoadStatus(
            lastAttemptMs = System.currentTimeMillis(),
            lastSuccessMs = System.currentTimeMillis(),
            lastError = null
        )
    }

    /** Test-only reset: clears the loaded agendas, the status and the fetch override. */
    internal fun resetForTest() {
        _agendas.value = Agendas()
        _loadStatus.value = AgendaLoadStatus()
        lastAttemptMs = 0L
        cacheApplied = false
        testFetcher = null
    }

    /**
     * Load agendas: the disk cache first (offline-tolerant), then
     * one network fetch when the last attempt is old enough. Every
     * failure leaves the current value in place, so screens render
     * with no agendas rather than erroring, and the failure is
     * recorded in [loadStatus] so Diagnostics can show the cause.
     * Safe to call from every agenda-consuming screen: concurrent
     * callers share one load, and repeat calls within the retry
     * gap are no-ops. Never throws.
     */
    suspend fun refreshIfNeeded(filesDir: File?) {
        if (filesDir == null) return
        mutex.withLock {
            if (!cacheApplied) {
                cacheApplied = true
                runCatching {
                    val cached = File(filesDir, CACHE_FILE)
                    if (cached.exists()) {
                        val parsed = withContext(Dispatchers.Default) {
                            parseAgendas(cached.readText())
                        }
                        if (!parsed.isEmpty) {
                            _agendas.value = parsed
                            _loadStatus.value = _loadStatus.value.copy(
                                lastSuccessMs = System.currentTimeMillis(),
                                lastError = null
                            )
                        }
                    }
                }.onFailure { error ->
                    _loadStatus.value = _loadStatus.value.copy(
                        lastError = "Cached agendas could not be read: ${error.message}"
                    )
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastAttemptMs < RETRY_GAP_MS) return
            lastAttemptMs = now
            _loadStatus.value = _loadStatus.value.copy(lastAttemptMs = now)
            val fetcher = testFetcher
            val result = if (fetcher != null) {
                runCatching { fetcher(AGENDAS_URL) }
            } else {
                runCatching { fetchText(AGENDAS_URL) }
            }
            val body = result.getOrNull()
            if (body == null) {
                _loadStatus.value = _loadStatus.value.copy(
                    lastError = "Agendas download failed: ${result.exceptionOrNull()?.message}"
                )
                return
            }
            val parsed = withContext(Dispatchers.Default) { parseAgendas(body) }
            if (parsed.isEmpty) {
                _loadStatus.value = _loadStatus.value.copy(
                    lastError = "Agendas download was empty or could not be read."
                )
                return
            }
            _agendas.value = parsed
            _loadStatus.value = _loadStatus.value.copy(
                lastSuccessMs = System.currentTimeMillis(),
                lastError = null
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    File(filesDir, CACHE_FILE).writeText(body)
                }
            }
        }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.string() ?: throw java.io.IOException("Empty response")
        }
    }
}
