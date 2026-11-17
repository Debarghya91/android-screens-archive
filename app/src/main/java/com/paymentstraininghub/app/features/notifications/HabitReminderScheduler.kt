// HabitReminderScheduler.kt
// Payments Training Hub Android - habit reminder scheduling.
//
// Port of LearningHabitScheduler plus the scheduling half of
// LearningHabitStore.applyCurrentSchedule in LearningHabit.swift
// (iOS). This is the SEPARATE habit system: its unique-work names
// ("pf.learningHabit.dailyReminder" and
// "pf.learningHabit.weeklyReminder", mirroring the iOS request
// identifiers) are never added, removed, or rescheduled by
// LocalNotificationService or NotificationSchedulers.
//
// Exactly one habit reminder is scheduled at a time: daily and
// weekly use distinct unique-work names, and apply always cancels
// both before adding. Daily fires every day at the chosen time;
// weekly fires every Monday at the chosen time. WorkManager
// one-shots do not repeat, so each fired reminder chains the next
// occurrence (see HabitReminderWorker), and every foreground
// refresh re-applies the schedule, so the chain can never die
// silently. Copy is encouraging and never shaming: no mention of
// missed days, falling behind, or broken streaks. Reminders default
// to OFF and need explicit opt-in (see HabitScreen).
//
// Scheduling needs three things at once: a firing frequency in the
// stored HabitStore settings, the habitReminders toggle ON in the
// notification preferences, and system permission. The settings are
// read directly from AppStore (not from HabitStore's StateFlow),
// because this scheduler also runs in background worker processes
// where the StateFlow may not have loaded yet. The settings base key
// is duplicated here, as iOS AuthService duplicates store keys in
// its migration list; keep the two in step. Unlike the notification
// preferences, habit reminder settings DO migrate from guest to
// account: the key is in the iOS guest migration list, and Android
// migrateGuestToAccount copies it. No em dashes in any user-facing
// text.

package com.paymentstraininghub.app.features.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.features.habit.HabitReminderFrequency
import com.paymentstraininghub.app.features.habit.HabitReminderSettings
import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object HabitReminderScheduler {

    /** Unique-work name for the daily reminder, mirroring the iOS request identifier. */
    const val DAILY_WORK_NAME = "pf.learningHabit.dailyReminder"

    /** Unique-work name for the weekly reminder, mirroring the iOS request identifier. */
    const val WEEKLY_WORK_NAME = "pf.learningHabit.weeklyReminder"

    private val ALL_WORK_NAMES = listOf(DAILY_WORK_NAME, WEEKLY_WORK_NAME)

    /**
     * The reminder settings base key. Duplicated from
     * HabitStore.REMINDER_SETTINGS_KEY, which is private there.
     * Keep the two in step.
     */
    private const val REMINDER_SETTINGS_BASE_KEY = "pf.learningHabit.reminderSettings.v1"

    /** A scheduled fire time must be at least this far in the future. */
    private const val MINIMUM_LEAD_TIME_MILLIS: Long = 15L * 60L * 1000L

    /**
     * The notification body for one frequency. Null for off. Copy
     * is encouraging and never shaming.
     */
    fun bodyFor(frequency: HabitReminderFrequency): String? {
        return when (frequency) {
            HabitReminderFrequency.OFF -> null
            HabitReminderFrequency.DAILY ->
                "One small lesson is here when you have a few minutes. No rush."
            HabitReminderFrequency.WEEKLY ->
                "A new week. One small step is plenty, and your next lesson is ready when you are."
        }
    }

    /**
     * Make the scheduled habit reminder match the stored settings,
     * the habit-reminders toggle, and the current permission:
     * schedule when reminders are on and delivery is allowed,
     * otherwise remove any scheduled habit reminder. Called on
     * foreground, on account reload, from the settings screen, and
     * from HabitScreen after every settings save, so a stale
     * reminder can never outlive its settings or its account.
     */
    suspend fun applyCurrentSchedule(context: Context) {
        val appContext = context.applicationContext
        val settings = loadSettings()
        val preferences = loadPreferencesFresh()
        val allowed = LocalNotificationService.currentAuthorizationStatus(appContext) ==
            NotificationAuthorizationStatus.GRANTED
        if (settings.isEnabled && preferences.masterEnabled && preferences.habitReminders && allowed) {
            apply(appContext, settings)
        } else {
            cancelAll(appContext)
        }
    }

    /**
     * Keep the scheduled habit reminders in step with the habit
     * reminders toggle, mirroring applyHabitReminderChange in
     * NotificationSettingsView.swift. Turning the toggle off
     * cancels the scheduled work but keeps the learner's saved
     * frequency and time untouched, so turning it back on can
     * restore them. Turning it on re-applies the saved settings,
     * which schedules only when reminders are on and the system
     * allows delivery. This never asks the system for permission:
     * that prompt belongs to the opt-in path in HabitScreen.
     */
    suspend fun applyHabitReminderChange(context: Context, isOn: Boolean) {
        if (isOn) {
            applyCurrentSchedule(context)
        } else {
            cancelAll(context)
        }
    }

    /**
     * Replace any scheduled habit reminder with the work item for
     * these settings, or with nothing when reminders are off.
     */
    suspend fun apply(context: Context, settings: HabitReminderSettings) {
        val appContext = context.applicationContext
        cancelAll(appContext)
        val body = bodyFor(settings.frequency) ?: return
        val workName = when (settings.frequency) {
            HabitReminderFrequency.DAILY -> DAILY_WORK_NAME
            HabitReminderFrequency.WEEKLY -> WEEKLY_WORK_NAME
            HabitReminderFrequency.OFF -> return
        }
        val fireAtMillis = nextFireTimeMillis(settings)
        val delayMillis = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val userId = AppStore.currentUserId() ?: ""
        val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    NotificationWorkInput.KEY_BODY to body,
                    NotificationWorkInput.KEY_FREQUENCY to settings.frequency.name,
                    NotificationWorkInput.KEY_USER_ID to userId
                )
            )
            .addTag("pf.learningHabit")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Remove every scheduled habit reminder. */
    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        for (name in ALL_WORK_NAMES) {
            workManager.cancelUniqueWork(name)
        }
    }

    /**
     * The stored reminder settings for the current account,
     * read directly from AppStore so background processes get the
     * real values. Same JSON shape HabitStore writes.
     */
    suspend fun loadSettings(): HabitReminderSettings {
        val raw = runCatching { AppStore.getString(REMINDER_SETTINGS_BASE_KEY) }.getOrNull()
            ?: return HabitReminderSettings()
        return runCatching {
            val json = JSONObject(raw)
            val frequency = when (json.optString("frequency", "off").lowercase()) {
                "daily" -> HabitReminderFrequency.DAILY
                "weekly" -> HabitReminderFrequency.WEEKLY
                else -> HabitReminderFrequency.OFF
            }
            HabitReminderSettings(
                frequency = frequency,
                hour = json.optInt("hour", 9).coerceIn(0, 23),
                minute = json.optInt("minute", 0).coerceIn(0, 59)
            )
        }.getOrDefault(HabitReminderSettings())
    }

    /**
     * The next fire time for these settings: the next occurrence of
     * the chosen time (daily: today or tomorrow; weekly: the next
     * Monday) that is at least the minimum lead time away.
     */
    private fun nextFireTimeMillis(settings: HabitReminderSettings): Long {
        val nowMillis = System.currentTimeMillis()
        val earliest = nowMillis + MINIMUM_LEAD_TIME_MILLIS
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, settings.hour)
        calendar.set(Calendar.MINUTE, settings.minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        when (settings.frequency) {
            HabitReminderFrequency.DAILY -> {
                while (calendar.timeInMillis <= earliest) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            HabitReminderFrequency.WEEKLY -> {
                while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY ||
                    calendar.timeInMillis <= earliest
                ) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            HabitReminderFrequency.OFF -> {
                // Unreachable: apply returns early for OFF. Return
                // tomorrow's slot so no caller can schedule "now".
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }
}
