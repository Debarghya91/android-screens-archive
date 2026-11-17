package com.paymentstraininghub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// ThumbnailStore.kt
// Fetches and caches thumbnail SVG files from the website. The
// SVG text is cached in memory and on disk, so a thumbnail loads
// once per install and stays available offline. Every failure is
// silent to the UI: callers get null and render their fallback
// tile, never an error. Failures are tracked per URL, so one dead
// thumbnail suppresses only its own refetch for a minute and can
// never poison every other thumbnail. The mutex guards the
// caches only and is never held across network I/O, so one slow
// fetch cannot serialize every row behind it. Never throws.

object ThumbnailStore {
    private const val THUMBS_DIR = "thumbs"

    /** How long one failed URL suppresses its own refetch. */
    private const val FAILURE_QUIET_MS = 60 * 1000L

    /** Memory cache cap: thumbnails are about 1 KB each. */
    private const val MEMORY_CACHE_MAX = 200

    /** Cap on tracked per-URL failures, so the map cannot grow unbounded. */
    private const val FAILURE_TRACK_MAX = 500

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Guards [memoryCache] and [failureUntilMs] only. Never held across network I/O. */
    private val mutex = Mutex()

    private val memoryCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MEMORY_CACHE_MAX
        }
    }

    /** Per-URL backoff: the earliest time each failed URL may be fetched again. */
    private val failureUntilMs = LinkedHashMap<String, Long>()

    private val _failedPaths = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Thumbnail paths whose latest fetch failed and are inside
     * their backoff window. The UI still renders the fallback
     * tile for these; Diagnostics reads this set so a dead
     * thumbnail is findable instead of silently blank.
     */
    val failedPaths: StateFlow<Set<String>> = _failedPaths.asStateFlow()

    /**
     * Test-only fetch override. When set, [svgFor] uses it instead
     * of the network, so unit tests can script success and failure
     * without touching the network. Never set in production code.
     */
    internal var testFetcher: (suspend (String) -> String?)? = null

    /** Test-only reset: clears the caches, the failure tracking and the fetch override. */
    internal fun resetForTest() {
        // Called from tests only, never concurrently with a fetch.
        memoryCache.clear()
        failureUntilMs.clear()
        _failedPaths.value = emptySet()
        testFetcher = null
    }

    /**
     * The SVG text for a relative thumbnail path, or null when it
     * cannot be loaded. [filesDir] is the app files directory, or
     * null to skip the disk cache. Never throws.
     */
    suspend fun svgFor(relativePath: String, filesDir: File?): String? {
        val key = relativePath.trim()
        if (key.isEmpty()) return null
        mutex.withLock {
            memoryCache[key]?.let { return it }
        }
        if (filesDir != null) {
            val cached = runCatching {
                withContext(Dispatchers.IO) { diskFile(filesDir, key).readText() }
            }.getOrNull()
            if (!cached.isNullOrBlank()) {
                mutex.withLock { memoryCache[key] = cached }
                return cached
            }
        }
        val now = System.currentTimeMillis()
        val suppressed = mutex.withLock {
            val until = failureUntilMs[key] ?: 0L
            now < until
        }
        if (suppressed) return null
        // The network fetch runs OUTSIDE the mutex: concurrent
        // rows fetch in parallel, and one slow fetch cannot stall
        // the rest of the list.
        val url = agendaThumbnailUrl(key)
        val fetcher = testFetcher
        val body = if (fetcher != null) {
            runCatching { fetcher(url) }.getOrNull()
        } else {
            runCatching { fetchText(url) }.getOrNull()
        }
        if (body.isNullOrBlank()) {
            mutex.withLock {
                if (failureUntilMs.size >= FAILURE_TRACK_MAX) failureUntilMs.clear()
                failureUntilMs[key] = System.currentTimeMillis() + FAILURE_QUIET_MS
                _failedPaths.value = _failedPaths.value + key
            }
            return null
        }
        mutex.withLock {
            memoryCache[key] = body
            failureUntilMs.remove(key)
            if (key in _failedPaths.value) _failedPaths.value = _failedPaths.value - key
        }
        if (filesDir != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = diskFile(filesDir, key)
                    file.parentFile?.mkdirs()
                    file.writeText(body)
                }
            }
        }
        return body
    }

    /** Disk cache file for a thumbnail path: the path flattened to one safe file name. */
    internal fun diskFile(filesDir: File, relativePath: String): File {
        val safe = relativePath.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(filesDir, THUMBS_DIR), safe)
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.string() ?: throw java.io.IOException("Empty response")
        }
    }
}
