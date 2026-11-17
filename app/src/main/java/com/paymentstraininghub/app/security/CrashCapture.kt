// CrashCapture.kt
// Payments Training Hub Android - in-app crash capture (audit fix 7).
// No stack trace exists for the Redmi audio crashes and the owner has
// no cable workflow, so the app records its own fatal exceptions: an
// uncaught-exception handler writes a report to app-private storage,
// capped at the 5 newest files, each capped at ~64 KB, and chains to
// the previous default handler so the process still dies normally.
// A report is never swallowed, never uploaded, and never leaves the
// device except when the owner shares it from the Diagnostics view.
//
// Breadcrumbs are short action records ("play ch54s2 @12345ms") kept
// in a 50-entry ring buffer and attached to the report. No user
// content and no PII is recorded: lesson ids and playback state only.
//
// Scope guard: Java/Kotlin crashes only. A native codec crash or a
// low-memory kill leaves no report; the run-marker heuristic in
// [previousDeathUnreported] tells those deaths apart from clean runs.

package com.paymentstraininghub.app.security

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.paymentstraininghub.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Short action records attached to crash reports. Thread-safe. */
object CrashBreadcrumbs {

    const val MAX_ENTRIES = 50
    private const val MAX_ENTRY_CHARS = 120

    private val entries: ArrayDeque<String> = ArrayDeque()

    /** Record one breadcrumb. Old entries drop off past [MAX_ENTRIES]. */
    @Synchronized
    fun record(entry: String) {
        entries.addLast(entry.take(MAX_ENTRY_CHARS))
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    /** A snapshot of the recorded entries, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    /** Drop every recorded entry. Used by tests. */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}

object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val REPORTS_DIR_NAME = "crash-reports"
    private const val MARKER_FILE_NAME = "last-run.txt"

    /** The newest report files kept; older ones are pruned on install. */
    const val MAX_REPORT_FILES = 5

    /** Each report is truncated to this many characters before writing. */
    const val MAX_REPORT_CHARS = 64 * 1024

    /** One saved report, for the Diagnostics list. */
    data class CrashReportInfo(
        val fileName: String,
        val writtenAtMs: Long,
        val headline: String
    )

    /**
     * A snapshot of the audio state for the report, supplied by
     * AudioPlayer. Reads StateFlow values only: never touch the
     * ExoPlayer instance from the crashing thread.
     */
    var audioStateProvider: (() -> String)? = null

    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    /**
     * True when the previous process died without writing a crash
     * report: the run marker from the previous launch is newer than
     * every report file. That points at a low-memory kill or a
     * native crash, which no Java handler can record. False on the
     * first launch, and false when the previous run left a report.
     */
    @Volatile
    var previousDeathUnreported: Boolean = false
        private set

    /**
     * Install the handler. Call first in Application.onCreate,
     * before any other init, so even an early crash is captured.
     * Safe to call twice: the second call is a no-op.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        previousDeathUnreported = computePreviousDeathUnreported(appContext)
        writeRunMarker(appContext)
        pruneReports(appContext)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeReport(appContext, thread, throwable)
            // Chain: the process must still die normally. Never
            // swallow a fatal exception.
            try {
                previousHandler?.uncaughtException(thread, throwable)
            } catch (_: Exception) {
                // The previous handler failed; nothing left to do.
            }
        }
    }

    /** The saved reports, newest first. Never throws. */
    fun listReports(context: Context): List<CrashReportInfo> {
        return runCatching {
            reportsDir(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file ->
                    CrashReportInfo(
                        fileName = file.name,
                        writtenAtMs = file.lastModified(),
                        headline = headlineFor(runCatching { file.readText() }.getOrDefault(""))
                    )
                }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** The full text of one report, or null when it cannot be read. */
    fun readReport(context: Context, fileName: String): String? {
        // The file name comes from our own list; reject anything
        // that could escape the reports directory anyway.
        if (fileName.contains("/") || fileName.contains("..")) return null
        return runCatching {
            File(reportsDir(context), fileName).takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    /** Delete every saved report. Never throws. */
    fun clearReports(context: Context) {
        runCatching {
            reportsDir(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash-") }
                ?.forEach { it.delete() }
        }
    }

    /**
     * One line of device info for the Diagnostics view and for
     * pasting into a support email: model, Android version, app
     * version, and free storage. No identifiers of any kind.
     */
    fun deviceInfo(context: Context): String {
        val freeBytes = runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(-1L)
        val freeText = if (freeBytes >= 0) {
            "${freeBytes / (1024 * 1024)} MB free"
        } else {
            "free storage unknown"
        }
        return "Payments Training Hub ${BuildConfig.VERSION_NAME} " +
            "(code ${BuildConfig.VERSION_CODE}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), $freeText"
    }

    /**
     * The first exception line of a report, for the list row. Pure
     * and unit-tested: the report header carries the exception
     * headline on its own line, and anything else falls back to
     * the first non-blank line.
     */
    fun headlineFor(reportText: String): String {
        val lines = reportText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("Exception: ")) return line.removePrefix("Exception: ").trim()
        }
        return reportText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Crash report"
    }

    /**
     * Which files to keep when pruning: the [maxCount] newest by
     * last-modified time. Pure and unit-tested; the file layer
     * only supplies (name, lastModified) pairs.
     */
    fun filesToKeep(files: List<Pair<String, Long>>, maxCount: Int): Set<String> {
        return files.sortedByDescending { it.second }.take(maxCount).map { it.first }.toSet()
    }

    /** Truncate report text to the write cap. Pure and unit-tested. */
    fun truncateReport(text: String): String {
        return if (text.length > MAX_REPORT_CHARS) {
            text.take(MAX_REPORT_CHARS) + "\n[truncated]"
        } else {
            text
        }
    }

    private fun reportsDir(context: Context): File {
        return File(context.noBackupFilesDir, REPORTS_DIR_NAME)
    }

    private fun writeRunMarker(context: Context) {
        runCatching {
            val dir = reportsDir(context)
            dir.mkdirs()
            File(dir, MARKER_FILE_NAME).writeText(System.currentTimeMillis().toString())
        }
    }

    private fun computePreviousDeathUnreported(context: Context): Boolean {
        return runCatching {
            val dir = reportsDir(context)
            val marker = File(dir, MARKER_FILE_NAME)
            if (!marker.isFile) return@runCatching false
            val markerMs = marker.readText().trim().toLongOrNull() ?: return@runCatching false
            val newestReportMs = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash-") }
                ?.maxOfOrNull { it.lastModified() }
                ?: 0L
            // The previous run started at markerMs. A report from
            // that run would be newer than the marker. No such
            // report means the process died without one.
            newestReportMs < markerMs
        }.getOrDefault(false)
    }

    private fun pruneReports(context: Context) {
        runCatching {
            val dir = reportsDir(context)
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                ?: return@runCatching
            val keep = filesToKeep(files.map { it.name to it.lastModified() }, MAX_REPORT_FILES)
            files.filter { it.name !in keep }.forEach { it.delete() }
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        // Re-entrancy guard: if writing a report throws, the handler
        // would otherwise recurse until the stack is gone.
        if (!handlingCrash.compareAndSet(false, true)) return
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val report = buildString {
                appendLine("Payments Training Hub crash report")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine()
                appendLine("Stack trace:")
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                appendLine(writer.toString())
                appendLine("Breadcrumbs:")
                val crumbs = CrashBreadcrumbs.snapshot()
                if (crumbs.isEmpty()) {
                    appendLine("(none)")
                } else {
                    crumbs.forEach { appendLine(it) }
                }
                appendLine()
                appendLine("Audio state:")
                appendLine(runCatching { audioStateProvider?.invoke() }.getOrNull() ?: "(unknown)")
                appendLine()
                appendLine("Event log:")
                val events = runCatching { EventLog.formattedLines() }.getOrNull().orEmpty()
                if (events.isEmpty()) {
                    appendLine("(none)")
                } else {
                    events.forEach { appendLine(it) }
                }
            }
            val dir = reportsDir(context)
            dir.mkdirs()
            File(dir, "crash-$timestamp.txt").writeText(truncateReport(report))
            pruneReports(context)
        } catch (_: Exception) {
            // The process is dying; a failed write must never mask
            // the original crash or throw from the handler.
            Log.w(TAG, "Crash report write failed")
        } finally {
            handlingCrash.set(false)
        }
    }
}
