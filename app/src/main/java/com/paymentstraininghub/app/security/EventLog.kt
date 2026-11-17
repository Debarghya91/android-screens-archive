// EventLog.kt
// Structured, timestamped event log for the three solved-APK
// defects: audio lifecycle, navigation sources, and pointer
// diagnostics. Entries are short, bounded, and in-memory only:
// the newest entries are shown in Diagnostics (with Copy) and
// appended to crash reports, so a defect seen on a device can be
// traced to the exact event that caused it. No user content is
// recorded: routes, lesson ids, coordinates, and state flags only.

package com.paymentstraininghub.app.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {

    /** One recorded event, oldest first in snapshots. */
    data class Entry(
        val elapsedRealtimeMs: Long,
        val wallTimeMs: Long,
        val tag: String,
        val message: String
    ) {
        /** One display line: wall-clock time, tag, message. */
        fun displayLine(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(wallTimeMs))
            return "$time [$tag] $message"
        }
    }

    const val MAX_ENTRIES = 120
    private const val MAX_MESSAGE_CHARS = 200

    private val entries: ArrayDeque<Entry> = ArrayDeque()

    private val _entriesFlow = MutableStateFlow<List<Entry>>(emptyList())
    val entriesFlow: StateFlow<List<Entry>> = _entriesFlow.asStateFlow()

    /** Record one event. Old entries drop off past [MAX_ENTRIES]. */
    @Synchronized
    fun record(tag: String, message: String) {
        entries.addLast(
            Entry(
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                wallTimeMs = System.currentTimeMillis(),
                tag = tag,
                message = message.take(MAX_MESSAGE_CHARS)
            )
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
        _entriesFlow.value = entries.toList()
    }

    /** A snapshot of the recorded entries, oldest first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** The snapshot rendered as display lines, for reports and Copy. */
    @Synchronized
    fun formattedLines(): List<String> = entries.map { it.displayLine() }

    /** Drop every recorded entry. Used by tests. */
    @Synchronized
    fun clear() {
        entries.clear()
        _entriesFlow.value = emptyList()
    }
}
