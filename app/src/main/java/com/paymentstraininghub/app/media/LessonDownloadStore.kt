package com.paymentstraininghub.app.media

import android.content.Context
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.security.PinningObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Explicit per-lesson audio downloads for offline listening.
 *
 * A lesson is downloaded only when the learner taps Download in the
 * unlocked lesson view. Nothing downloads automatically, and locked
 * lessons have no download control: the control lives in the unlocked
 * lesson view only, under the same gating as playback. This store
 * holds no entitlement check of its own; it must only be called from
 * the unlocked lesson view.
 *
 * Sandbox rule: downloaded audio stays inside this app's private
 * sandbox, like Netflix or Hotstar downloads. It is never shown in
 * the Files app, never shared or exported, and it is gone when the
 * app is deleted. Every download path must stay in the app's private
 * internal storage: never move audio to external storage, a shared
 * folder, MediaStore, a FileProvider, or a share sheet.
 *
 * M7 mechanism (Android-appropriate port of the iOS file
 * protection): NEW downloads go to context.noBackupFilesDir, under
 * a "downloads" subfolder, one file per lesson named by the lesson
 * id. Files there are private to this app, are not visible to other
 * apps or to the Files app, are removed when the app is uninstalled,
 * and are EXCLUDED from Android Auto Backup by the platform, so
 * downloaded audio can never be restored onto another device or
 * leave the device through a backup. Downloads written by earlier
 * builds live in context.filesDir/downloads; they stay readable as
 * a legacy fallback, and re-downloading a lesson migrates it: the
 * new file lands in noBackupFilesDir and the legacy file is deleted.
 * Android file-based encryption is platform-managed with no per-file
 * API, so there is no Android equivalent of the iOS per-file
 * completeUnlessOpen protection class; the sandbox plus the
 * no-backup location is the mechanism. Manifest backup-rules work
 * belongs to the scaffold workstream, not to this store.
 *
 * Downloads stream from the primary audio host, then the fallback host
 * once. A download does NOT continue if the app is killed: there is no
 * background download in this run. A killed download leaves no partial
 * file, because the file is moved into place only after the download
 * completes. The learner taps Download again to retry.
 */
object LessonDownloadStore {

    private const val DOWNLOADS_DIR_NAME = "downloads"
    private const val TEMP_SUFFIX = ".tmp"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(PinningObserver)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** The private downloads folder: no-backup internal storage. Created on first use. */
    private fun downloadsDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, DOWNLOADS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** The legacy downloads folder (earlier builds): filesDir/downloads. Read fallback only. */
    private fun legacyDownloadsDir(context: Context): File {
        return File(context.filesDir, DOWNLOADS_DIR_NAME)
    }

    /** The legacy file for a lesson, when one exists from an earlier build. */
    private fun legacyFile(context: Context, lessonId: String): File {
        return File(legacyDownloadsDir(context), sanitisedStem(lessonId) + ".mp3")
    }

    /** Only letters, digits, dot, underscore and hyphen survive in a file name. */
    private fun sanitisedStem(lessonId: String): String {
        val safe = lessonId.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return safe.ifBlank { "lesson" }
    }

    private fun destinationFile(context: Context, lessonId: String): File {
        return File(downloadsDir(context), sanitisedStem(lessonId) + ".mp3")
    }

    private fun tempFile(context: Context, lessonId: String): File {
        return File(downloadsDir(context), sanitisedStem(lessonId) + TEMP_SUFFIX)
    }

    /** True when a complete downloaded file for this lesson is on this device. */
    suspend fun isDownloaded(context: Context, lessonId: String): Boolean {
        return withContext(Dispatchers.IO) { downloadedFile(context, lessonId) != null }
    }

    /**
     * The downloaded file for a lesson, or null when there is none.
     * A zero-length file counts as no download: it can only be a leftover.
     * The no-backup location wins; the legacy filesDir location is the
     * read fallback for downloads written by earlier builds.
     */
    fun downloadedFile(context: Context, lessonId: String): File? {
        val file = destinationFile(context, lessonId)
        if (file.exists() && file.isFile && file.length() > 0) return file
        val legacy = legacyFile(context, lessonId)
        return if (legacy.exists() && legacy.isFile && legacy.length() > 0) legacy else null
    }

    /**
     * Download this lesson's narration. Streams to a temp file in the
     * same private folder, then moves it into place atomically once the
     * download completes, so a failed or killed download never leaves a
     * partial file behind. Tries the primary audio host first, then the
     * fallback host once.
     */
    suspend fun download(context: Context, detail: LessonDetail): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val destination = destinationFile(appContext, detail.id)
            val temp = tempFile(appContext, detail.id)
            val urls = listOf(
                ContentRepository.audioUrl(detail.audioFileName, true),
                ContentRepository.audioUrl(detail.audioFileName, false)
            )
            var lastError: String? = null
            // True once this attempt started moving the temp file
            // into place: only then may a failure delete the
            // destination, because the move already removed any
            // previous good copy. A network failure before the
            // move must never delete an existing download.
            var moveAttempted = false
            for (url in urls) {
                moveAttempted = false
                try {
                    if (temp.exists()) temp.delete()
                    val request = Request.Builder().url(url).get().build()
                    var expectedLength: Long? = null
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = "Download failed with status ${response.code}."
                            return@use
                        }
                        val body = response.body
                        if (body == null) {
                            lastError = "Download failed: no audio was returned."
                            return@use
                        }
                        expectedLength = response.header("Content-Length")?.toLongOrNull()
                            ?.takeIf { it > 0 }
                        body.byteStream().use { input ->
                            temp.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (temp.exists() && temp.length() > 0) {
                        // Validate against Content-Length when the
                        // server sent one: a short file is a failed
                        // download, not a playable lesson.
                        val expected = expectedLength
                        if (expected != null && temp.length() != expected) {
                            lastError = "Download failed: the audio file was incomplete."
                            temp.delete()
                            continue
                        }
                        if (destination.exists()) destination.delete()
                        moveAttempted = true
                        val moved = temp.renameTo(destination)
                        if (!moved) {
                            try {
                                temp.copyTo(destination, overwrite = true)
                                temp.delete()
                            } catch (e: Exception) {
                                // A copyTo failure midway leaves a
                                // partial destination (audit fix 6):
                                // delete it so the lesson streams
                                // cleanly instead of "playing" a
                                // truncated file.
                                runCatching { destination.delete() }
                                throw e
                            }
                        }
                        // Migration: the new file is in the no-backup
                        // location, so the legacy copy is deleted.
                        legacyFile(appContext, detail.id).delete()
                        return@withContext Result.success(Unit)
                    }
                    lastError = "Download failed: the audio file was empty."
                } catch (e: Exception) {
                    lastError = "Download failed: check your connection and try again."
                    if (moveAttempted) {
                        // The move started but did not complete: no
                        // partial destination may survive.
                        runCatching { destination.delete() }
                    }
                } finally {
                    if (temp.exists() && destination.exists() && destination.length() > 0) {
                        temp.delete()
                    }
                }
            }
            if (temp.exists()) temp.delete()
            Result.failure(IllegalStateException(lastError ?: "Download failed."))
        }
    }

    /** Remove this lesson's downloaded file, if there is one. Streaming still works afterwards. */
    suspend fun deleteDownload(context: Context, lessonId: String) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            destinationFile(appContext, lessonId).delete()
            tempFile(appContext, lessonId).delete()
            legacyFile(appContext, lessonId).delete()
        }
    }

    /**
     * Remove ALL downloaded lesson audio, from both locations.
     * Called when the full course entitlement is removed (refund
     * or revocation), so revoked content does not stay playable
     * offline. Best effort: failures are ignored by the caller.
     */
    suspend fun removeAllDownloads(context: Context) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            removeFilesIn(downloadsDir(appContext)) { true }
            removeFilesIn(legacyDownloadsDir(appContext)) { true }
        }
    }

    /**
     * Remove downloaded audio for the given chapters, from both
     * locations. Files are matched by the "<chapterId>s" filename
     * prefix: lesson ids are "ch{n}s{m}", so "ch12s" matches every
     * lesson of chapter 12 and nothing else (chapter 1 is "ch1s",
     * which never prefixes "ch12s..."). Called when a chapter
     * bundle entitlement is removed.
     */
    suspend fun removeDownloadsForChapterIds(context: Context, chapterIds: List<String>) {
        if (chapterIds.isEmpty()) return
        val prefixes = chapterIds.map { "${it}s" }
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val matches: (File) -> Boolean = { file ->
                prefixes.any { prefix -> file.name.startsWith(prefix) }
            }
            removeFilesIn(downloadsDir(appContext), matches)
            removeFilesIn(legacyDownloadsDir(appContext), matches)
        }
    }

    private fun removeFilesIn(dir: File, matches: (File) -> Boolean) {
        if (!dir.exists()) return
        dir.listFiles()
            ?.filter { it.isFile && matches(it) }
            ?.forEach { runCatching { it.delete() } }
    }

    /** Total bytes used by downloaded lesson audio in both download locations. */
    suspend fun downloadedSizeBytes(context: Context): Long {
        return withContext(Dispatchers.IO) {
            sizeIn(File(context.noBackupFilesDir, DOWNLOADS_DIR_NAME)) +
                sizeIn(File(context.filesDir, DOWNLOADS_DIR_NAME))
        }
    }

    private fun sizeIn(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.sumOf { it.length() }
            ?: 0L
    }
}
