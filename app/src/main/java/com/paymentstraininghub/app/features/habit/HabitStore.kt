// Habit storage: reminder settings (settings only, default off, no
// notifications scheduled) and read-only progress from the real progress
// keys. Progress parsing is defensive: timestamps may be epoch millis,
// epoch seconds, Apple reference-date seconds, or ISO strings, and any
// unreadable value yields an empty state, never an invented number.
// AppStore is suspend-only and namespaces keys itself; callers pass base
// keys to getString/putString. No em dashes.
package com.paymentstraininghub.app.features.habit

import android.content.Context
import com.paymentstraininghub.app.auth.AppStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object HabitStore {
    private const val REMINDER_SETTINGS_KEY = "pf.learningHabit.reminderSettings.v1"
    private const val LEARNING_EVENTS_KEY = "pf.learningEvents.v1"
    private const val COMPLETED_LESSONS_KEY = "pf.completedLessons.v1"
    private const val WEEKLY_GOAL_KEY = "pf.weeklyGoal.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _reminderSettings = MutableStateFlow(HabitReminderSettings())
    val reminderSettings: StateFlow<HabitReminderSettings> = _reminderSettings.asStateFlow()

    init {
        scope.launch { loadReminderSettings() }
    }

    fun reloadForCurrentAccount() {
        _reminderSettings.value = HabitReminderSettings()
        scope.launch { loadReminderSettings() }
    }

    fun saveReminderSettings(settings: HabitReminderSettings) {
        _reminderSettings.value = settings
        val json = JSONObject()
            .put("frequency", settings.frequency.name.lowercase())
            .put("hour", settings.hour)
            .put("minute", settings.minute)
        val encoded = json.toString()
        scope.launch {
            runCatching { AppStore.putString(REMINDER_SETTINGS_KEY, encoded) }
        }
    }

    fun disableReminders() {
        saveReminderSettings(_reminderSettings.value.copy(frequency = HabitReminderFrequency.OFF))
    }

    private suspend fun loadReminderSettings() {
        val raw = runCatching { AppStore.getString(REMINDER_SETTINGS_KEY) }.getOrNull()
            ?: return
        runCatching {
            val json = JSONObject(raw)
            val frequency = when (json.optString("frequency", "off").lowercase()) {
                "daily" -> HabitReminderFrequency.DAILY
                "weekly" -> HabitReminderFrequency.WEEKLY
                else -> HabitReminderFrequency.OFF
            }
            _reminderSettings.value = HabitReminderSettings(
                frequency = frequency,
                hour = json.optInt("hour", 9).coerceIn(0, 23),
                minute = json.optInt("minute", 0).coerceIn(0, 59)
            )
        }
    }

    /** Recorded learning events, or empty when the key is absent or unreadable. */
    suspend fun loadEvents(): List<HabitLearningEvent> {
        val raw = runCatching { AppStore.getString(LEARNING_EVENTS_KEY) }.getOrNull()
            ?: return emptyList()
        return runCatching { parseEvents(raw) }.getOrDefault(emptyList())
    }

    /** Recorded completed lesson ids, or empty when the key is absent or unreadable. */
    suspend fun loadCompletedLessonIds(): Set<String> {
        val raw = runCatching { AppStore.getString(COMPLETED_LESSONS_KEY) }.getOrNull()
            ?: return emptySet()
        return runCatching { parseCompletedIds(raw) }.getOrDefault(emptySet())
    }

    /** The shared weekly goal, or 0 when unset or unreadable. */
    suspend fun loadWeeklyGoal(): Int {
        val raw = runCatching { AppStore.getString(WEEKLY_GOAL_KEY) }.getOrNull()
            ?: return 0
        return raw.trim().trim('"').toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    /** Save the shared weekly goal. Onboarding writes the chosen intensity target here. */
    suspend fun saveWeeklyGoal(goal: Int) {
        runCatching { AppStore.putString(WEEKLY_GOAL_KEY, goal.coerceAtLeast(0).toString()) }
    }

    internal fun parseEvents(raw: String): List<HabitLearningEvent> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = JSONArray(trimmed)
        val out = mutableListOf<HabitLearningEvent>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type.isEmpty()) continue
            val timestamp = parseTimestamp(item) ?: continue
            out.add(
                HabitLearningEvent(
                    type = type,
                    lessonID = item.optString("lessonID", "").ifEmpty { null },
                    timestampMillis = timestamp
                )
            )
        }
        return out
    }

    internal fun parseCompletedIds(raw: String): Set<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptySet()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return (0 until array.length()).mapNotNull { array.optString(it, "").ifEmpty { null } }.toSet()
        }
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val keys = obj.keys()
            val out = mutableSetOf<String>()
            while (keys.hasNext()) {
                val key = keys.next()
                if (obj.optBoolean(key, true)) out.add(key)
            }
            return out
        }
        return emptySet()
    }

    private fun parseTimestamp(item: JSONObject): Long? {
        val rawValue = item.opt("timestamp") ?: item.opt("timestampMillis") ?: return null
        return when (rawValue) {
            is Number -> normaliseEpoch(rawValue.toDouble())
            is String -> {
                val asNumber = rawValue.toDoubleOrNull()
                if (asNumber != null) {
                    normaliseEpoch(asNumber)
                } else {
                    parseIsoDate(rawValue)
                }
            }
            else -> null
        }
    }

    private fun normaliseEpoch(value: Double): Long? {
        return when {
            value <= 0 -> null
            value > 1e12 -> value.toLong()
            value > 1e9 -> (value * 1000).toLong()
            else -> ((value + 978_307_200.0) * 1000).toLong()
        }
    }

    private fun parseIsoDate(raw: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        formats.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(raw)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }
}

/** One interview question from the bundled bank. */
data class HabitInterviewQuestion(val id: String, val question: String, val answer: String)

/**
 * Loads the bundled interview question bank from app assets
 * (interview-questions.json, the same file the iOS app bundles). Returns
 * null when the asset is missing or unreadable; the caller falls back to
 * quiz questions from loaded lesson details. No question is ever invented.
 */
object HabitQuestionBank {
    fun loadFromAssets(context: Context): List<HabitInterviewQuestion>? {
        return runCatching {
            val text = context.assets.open("interview-questions.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val categories = root.getJSONArray("categories")
            val out = mutableListOf<HabitInterviewQuestion>()
            for (i in 0 until categories.length()) {
                val category = categories.getJSONObject(i)
                val questions = category.getJSONArray("questions")
                for (j in 0 until questions.length()) {
                    val item = questions.getJSONObject(j)
                    out.add(
                        HabitInterviewQuestion(
                            id = item.getString("id"),
                            question = item.getString("question"),
                            answer = item.getString("answer")
                        )
                    )
                }
            }
            out.ifEmpty { null }
        }.getOrNull()
    }
}
