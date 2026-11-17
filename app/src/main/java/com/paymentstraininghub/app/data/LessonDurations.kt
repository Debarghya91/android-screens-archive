// LessonDurations.kt
// Payments Training Hub Android - bundled lesson narration lengths.
//
// The lesson-durations manifest (lesson-durations.json in assets,
// copied from the iOS bundle, which measures real narration audio)
// maps lesson ids to seconds. It covers the catalogue at the time
// it was built; lessons added since have no entry, and NO duration
// is ever guessed for them: a missing entry means no duration
// label, never a made-up number. Durations feed the chapter time
// line ("About X in total, Y left") and per-lesson minute labels.
//
// Loading is lazy, cached, and failure-proof: an unreadable or
// malformed manifest leaves an empty map, and the UI simply shows
// no durations. No em dashes in any user-facing text.

package com.paymentstraininghub.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object LessonDurations {

    private const val ASSET_NAME = "lesson-durations.json"

    @Volatile
    private var durations: Map<String, Int>? = null

    private val loadMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    /** Pure parse, unit-tested: id to seconds, empty on any bad input. */
    fun parseDurations(raw: String): Map<String, Int> {
        return runCatching {
            json.decodeFromString<Map<String, Int>>(raw)
                .filterValues { it > 0 }
        }.getOrElse { emptyMap() }
    }

    /** Load the manifest once, off the main thread. Safe to call repeatedly. */
    suspend fun ensureLoaded(context: Context) {
        if (durations != null) return
        loadMutex.withLock {
            if (durations != null) return
            durations = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                        parseDurations(reader.readText())
                    }
                }.getOrElse { emptyMap() }
            }
        }
    }

    /** Narration length in seconds, or null when unknown. Call [ensureLoaded] first. */
    fun secondsFor(lessonId: String): Int? = durations?.get(lessonId)

    /** True when the manifest loaded with at least one entry. */
    fun hasDurations(): Boolean = durations?.isNotEmpty() == true

    /** "3 min" for a lesson row. Rounds to the nearest minute, minimum 1. */
    fun formatMinutes(seconds: Int): String {
        val minutes = maxOf(1, (seconds + 30) / 60)
        return if (minutes == 1) "1 min" else "$minutes min"
    }

    /** "About 42 min of listening in total" or "About 1 hr 5 min of listening in total" for a chapter line. */
    fun formatTotal(seconds: Int): String {
        val minutes = maxOf(1, (seconds + 30) / 60)
        return "About ${formatMinutesAndHours(minutes)} of listening in total"
    }

    /** "12 min of listening left" or "1 hr 5 min of listening left" for the remaining time in a chapter. */
    fun formatLeft(seconds: Int): String {
        if (seconds <= 0) return "Nothing left"
        val minutes = maxOf(1, (seconds + 30) / 60)
        return "${formatMinutesAndHours(minutes)} of listening left"
    }

    private fun formatMinutesAndHours(minutes: Int): String {
        if (minutes < 60) {
            return if (minutes == 1) "1 min" else "$minutes min"
        }
        val hours = minutes / 60
        val rest = minutes % 60
        val hourPart = if (hours == 1) "1 hr" else "$hours hr"
        return if (rest == 0) hourPart else "$hourPart $rest min"
    }
}
