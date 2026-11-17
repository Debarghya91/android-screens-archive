package com.paymentstraininghub.app.data

import java.io.File

// CacheStore.kt
// Payments Training Hub Android - on-disk cache for fetched lesson pages.
// Ported from the iOS lesson detail cache in ContentService.swift.
//
// Storage: internal storage only, under <filesDir>/lesson-detail-cache.
// Callers pass context.filesDir (the lesson screen passes its own
// filesDir through ContentRepository.lessonDetail). Cached pages stay
// inside this app's private sandbox, like audio downloads. They are
// never shown in the Files app, never shared or exported, and they
// are gone when the app is deleted. Never move this cache to external
// storage, a shared container, or a share sheet.

object CacheStore {

    const val CACHE_DIR_NAME = "lesson-detail-cache"

    /// Maximum lesson detail pages kept on disk, and maximum total
    /// size. The oldest pages are evicted first when either bound is
    /// passed. Lesson pages are HTML text, so 50 pages stay well
    /// under 50MB in practice; both bounds are enforced.
    const val MAX_FILES = 50
    const val MAX_BYTES: Long = 50L * 1024L * 1024L

    /// The cache directory under the given filesDir. Created on write.
    fun cacheDir(filesDir: File): File = File(filesDir, CACHE_DIR_NAME)

    /// The cache file name for a lesson id. Only letters, numbers,
    /// hyphen and underscore survive; every other character becomes
    /// a hyphen, so an id can never escape the cache directory.
    /// An id with no characters at all maps to "lesson.html".
    fun sanitisedFileName(lessonId: String): String {
        val safe = lessonId.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("")
        val base = if (safe.isEmpty()) "lesson" else safe
        return "$base.html"
    }

    /// The cached page HTML for a lesson, null when nothing was cached
    /// or the cached file is empty.
    fun readCached(filesDir: File, lessonId: String): String? {
        return try {
            val file = File(cacheDir(filesDir), sanitisedFileName(lessonId))
            if (!file.isFile) return null
            val html = file.readText(Charsets.UTF_8)
            if (html.isBlank()) null else html
        } catch (e: Exception) {
            null
        }
    }

    /// Save a fetched lesson page to the cache and enforce the bounds.
    /// Best effort: a failed write never fails the load that succeeded.
    fun writeCached(filesDir: File, lessonId: String, html: String) {
        try {
            val directory = cacheDir(filesDir)
            directory.mkdirs()
            val file = File(directory, sanitisedFileName(lessonId))
            file.writeText(html, Charsets.UTF_8)
            enforceBounds(filesDir)
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    /// The lesson ids with a cached page, derived from the cached file
    /// names. The ".html" suffix is stripped; ids that were sanitised
    /// on write come back in their sanitised form.
    fun cachedIds(filesDir: File): List<String> {
        return try {
            val directory = cacheDir(filesDir)
            if (!directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".html") }
                ?.map { it.name.removeSuffix(".html") }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /// One cached file, for eviction selection. The modification date
    /// decides age; a missing date is treated as the oldest possible.
    data class CacheFileInfo(
        val fileName: String,
        val lastModified: Long,
        val sizeBytes: Long
    )

    /// The file names to evict so the remaining files fit both bounds.
    /// Oldest first; ties break by file name so the result is stable.
    /// Pure logic with no disk access.
    fun filesToEvict(files: List<CacheFileInfo>, maxFiles: Int, maxBytes: Long): List<String> {
        val ordered = files.sortedWith(
            compareBy<CacheFileInfo> { it.lastModified }.thenBy { it.fileName }
        )
        var remainingCount = ordered.size
        var remainingBytes = ordered.sumOf { it.sizeBytes }
        val evicted = mutableListOf<String>()
        for (file in ordered) {
            if (remainingCount <= maxFiles && remainingBytes <= maxBytes) break
            evicted.add(file.fileName)
            remainingCount -= 1
            remainingBytes -= file.sizeBytes
        }
        return evicted
    }

    /// Enforce the cache bounds on disk, evicting the oldest files
    /// first. Best effort: failures are ignored.
    fun enforceBounds(filesDir: File) {
        try {
            val directory = cacheDir(filesDir)
            val listed = directory.listFiles() ?: return
            val infos = listed.filter { it.isFile }.map { file ->
                CacheFileInfo(
                    fileName = file.name,
                    lastModified = file.lastModified(),
                    sizeBytes = file.length()
                )
            }
            val toEvict = filesToEvict(infos, MAX_FILES, MAX_BYTES)
            for (fileName in toEvict) {
                try {
                    File(directory, fileName).delete()
                } catch (e: Exception) {
                    // Best effort only.
                }
            }
        } catch (e: Exception) {
            // Best effort only.
        }
    }
}
