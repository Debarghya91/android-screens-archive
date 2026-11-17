// Port of LearningHabit.swift planner (iOS). Pure computations over recorded
// progress and real catalogue content. No state, no persistence, no gating.
// Streak wording is neutral: a plain count, never guilt copy. No em dashes.
package com.paymentstraininghub.app.features.habit

import com.paymentstraininghub.app.features.lab.ExceptionCase
import java.util.Calendar
import java.util.TimeZone

/** One recorded learning event, as read from the progress keys. */
data class HabitLearningEvent(
    val type: String,
    val lessonID: String?,
    val timestampMillis: Long
)

/** One honest progress milestone. */
data class LearningHabitMilestone(
    val threshold: Int,
    val title: String,
    val isReached: Boolean
)

/** Reminder frequency. Default is off. */
enum class HabitReminderFrequency(val displayTitle: String) {
    OFF("Off"),
    DAILY("Daily"),
    WEEKLY("Weekly")
}

data class HabitReminderSettings(
    val frequency: HabitReminderFrequency = HabitReminderFrequency.OFF,
    val hour: Int = 9,
    val minute: Int = 0
) {
    val isEnabled: Boolean get() = frequency != HabitReminderFrequency.OFF
}

object LearningHabitPlanner {
    /** Lesson-count thresholds for milestones, before the whole-catalogue threshold. */
    val milestoneThresholds: List<Int> = listOf(1, 5, 10, 25, 50, 100, 250, 500)

    /** Days since the epoch, local calendar, start of day: the deterministic seed for the daily question. */
    fun dayNumber(calendar: Calendar = Calendar.getInstance()): Int {
        val start = startOfDay(calendar)
        val epoch = Calendar.getInstance(calendar.timeZone).apply {
            timeInMillis = 0
        }
        val epochStart = startOfDay(epoch)
        return ((start.timeInMillis - epochStart.timeInMillis) / 86_400_000L).toInt()
    }

    /** Whole weeks since the epoch: the deterministic seed for the weekly investigation case. */
    fun weekNumber(calendar: Calendar = Calendar.getInstance()): Int =
        dayNumber(calendar) / 7

    /** One question per day from the real bank, deterministic by calendar day. Nil when the bank is empty. */
    fun <T> dailyQuestion(forCalendar: Calendar, questions: List<T>): T? {
        if (questions.isEmpty()) return null
        val index = ((dayNumber(forCalendar) % questions.size) + questions.size) % questions.size
        return questions[index]
    }

    /**
     * One investigation case per week from the real lab catalogue,
     * deterministic by week. Only the case title, incident type and level
     * are ever shown outside the lab.
     */
    fun weeklyInvestigationCase(forCalendar: Calendar, cases: List<ExceptionCase>): ExceptionCase? {
        if (cases.isEmpty()) return null
        val index = ((weekNumber(forCalendar) % cases.size) + cases.size) % cases.size
        return cases[index]
    }

    /** Neutral streak wording: a plain count, or a plain statement. Never guilt copy. */
    fun streakText(streakCount: Int): String = when {
        streakCount <= 0 -> "No streak right now."
        streakCount == 1 -> "1 day in a row."
        else -> "$streakCount days in a row."
    }

    /** Weekly target wording from the shared weekly goal. Read-only here. */
    fun weeklyTargetText(completedThisWeek: Int, weeklyGoal: Int): String {
        if (weeklyGoal <= 0) return "Weekly target not set"
        val lessonWord = if (weeklyGoal == 1) "lesson" else "lessons"
        return "$completedThisWeek of $weeklyGoal $lessonWord this week"
    }

    /** Every milestone for this catalogue, in threshold order. Reached means recorded completions reach it. */
    fun milestones(completedCount: Int, totalLessons: Int): List<LearningHabitMilestone> {
        val thresholds = milestoneThresholds.toMutableSet()
        if (totalLessons > 0) thresholds.add(totalLessons)
        val ordered = thresholds
            .filter { totalLessons <= 0 || it <= totalLessons }
            .sorted()
        return ordered.map { threshold ->
            LearningHabitMilestone(
                threshold = threshold,
                title = milestoneTitle(threshold, totalLessons),
                isReached = completedCount >= threshold
            )
        }
    }

    fun nextMilestone(completedCount: Int, totalLessons: Int): LearningHabitMilestone? =
        milestones(completedCount, totalLessons).firstOrNull { !it.isReached }

    private fun milestoneTitle(threshold: Int, totalLessons: Int): String {
        if (totalLessons > 0 && threshold == totalLessons && threshold != 1) {
            return "All $totalLessons lessons complete"
        }
        if (threshold == 1) return "First lesson complete"
        return "$threshold lessons complete"
    }

    /**
     * Consecutive days with at least one learning event, ending today (or
     * yesterday when nothing happened today yet). Zero when there are no
     * events; never estimated. Port of ProgressStore.streakCount.
     */
    fun streakCount(events: List<HabitLearningEvent>, nowMillis: Long = System.currentTimeMillis()): Int {
        if (events.isEmpty()) return 0
        val activeDays = events.map { startOfDayMillis(it.timestampMillis) }.toSet()
        var day = startOfDayMillis(nowMillis)
        if (day !in activeDays) {
            val yesterday = day - 86_400_000L
            if (yesterday !in activeDays) return 0
            day = yesterday
        }
        var streak = 0
        while (day in activeDays) {
            streak++
            day -= 86_400_000L
        }
        return streak
    }

    /** Distinct lessons completed in the current calendar week (Monday start, local time). */
    fun completedThisWeek(events: List<HabitLearningEvent>, nowMillis: Long = System.currentTimeMillis()): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val weekStart = calendar.timeInMillis
        return events
            .filter { it.type == "lessonCompleted" && it.timestampMillis >= weekStart }
            .mapNotNull { it.lessonID }
            .toSet()
            .size
    }

    /** True when a completion event for this lesson is recorded today. */
    fun didCompleteLessonToday(
        lessonID: String,
        events: List<HabitLearningEvent>,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val todayStart = startOfDayMillis(nowMillis)
        return events.any {
            it.type == "lessonCompleted" && it.lessonID == lessonID &&
                startOfDayMillis(it.timestampMillis) == todayStart
        }
    }

    private fun startOfDay(calendar: Calendar): Calendar {
        val copy = calendar.clone() as Calendar
        copy.set(Calendar.HOUR_OF_DAY, 0)
        copy.set(Calendar.MINUTE, 0)
        copy.set(Calendar.SECOND, 0)
        copy.set(Calendar.MILLISECOND, 0)
        return copy
    }

    private fun startOfDayMillis(millis: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = millis
        return startOfDay(calendar).timeInMillis
    }
}
