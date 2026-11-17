// CrashCaptureTest.kt
// Unit tests for the pure crash-capture helpers: the breadcrumb
// ring buffer, report pruning selection, headline extraction, and
// report truncation. File and handler behaviour needs a device
// and is not unit-tested here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.CrashBreadcrumbs
import com.paymentstraininghub.app.security.CrashCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashCaptureTest {

    @Before
    fun clearBreadcrumbs() {
        CrashBreadcrumbs.clear()
    }

    @Test
    fun breadcrumbsKeepInsertionOrder() {
        CrashBreadcrumbs.record("play ch54s2 @0ms")
        CrashBreadcrumbs.record("pause ch54s2")
        assertEquals(
            listOf("play ch54s2 @0ms", "pause ch54s2"),
            CrashBreadcrumbs.snapshot()
        )
    }

    @Test
    fun breadcrumbsCapAtFiftyEntries() {
        repeat(60) { index -> CrashBreadcrumbs.record("entry $index") }
        val snapshot = CrashBreadcrumbs.snapshot()
        assertEquals(CrashBreadcrumbs.MAX_ENTRIES, snapshot.size)
        assertEquals("entry 10", snapshot.first())
        assertEquals("entry 59", snapshot.last())
    }

    @Test
    fun breadcrumbsTruncateLongEntries() {
        CrashBreadcrumbs.record("x".repeat(500))
        assertEquals(120, CrashBreadcrumbs.snapshot().single().length)
    }

    @Test
    fun pruneKeepsNewestFive() {
        val files = (1..8).map { "crash-$it.txt" to it.toLong() * 1000L }
        val keep = CrashCapture.filesToKeep(files, CrashCapture.MAX_REPORT_FILES)
        assertEquals(
            setOf("crash-4.txt", "crash-5.txt", "crash-6.txt", "crash-7.txt", "crash-8.txt"),
            keep
        )
    }

    @Test
    fun pruneKeepsEverythingWhenUnderCap() {
        val files = listOf("a.txt" to 1L, "b.txt" to 2L)
        assertEquals(setOf("a.txt", "b.txt"), CrashCapture.filesToKeep(files, 5))
    }

    @Test
    fun headlinePrefersExceptionLine() {
        val report = "Payments Training Hub crash report\n" +
            "Time: 2026-11-15 01:00:00\n" +
            "Exception: java.io.IOException: disk full\n" +
            "\nStack trace:\n..."
        assertEquals(
            "java.io.IOException: disk full",
            CrashCapture.headlineFor(report)
        )
    }

    @Test
    fun headlineFallsBackToFirstLine() {
        assertEquals("Something broke", CrashCapture.headlineFor("\nSomething broke\nmore"))
    }

    @Test
    fun headlineFallsBackForEmptyReport() {
        assertEquals("Crash report", CrashCapture.headlineFor(""))
    }

    @Test
    fun truncationKeepsShortReportsWhole() {
        val text = "short report"
        assertEquals(text, CrashCapture.truncateReport(text))
    }

    @Test
    fun truncationCapsLongReports() {
        val text = "y".repeat(CrashCapture.MAX_REPORT_CHARS + 1000)
        val truncated = CrashCapture.truncateReport(text)
        assertTrue(truncated.length <= CrashCapture.MAX_REPORT_CHARS + 20)
        assertTrue(truncated.endsWith("[truncated]"))
    }
}
