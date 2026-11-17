// NotificationPreferences.kt
// Payments Training Hub Android - the learner's notification choices.
//
// Port of NotificationPreferences in LocalNotificationService.swift
// (iOS v66). Every kind defaults to ON, and the master switch gates
// them all: when masterEnabled is false, no request from this system
// is scheduled, whatever the individual kinds say. This mirrors the
// iOS defaults exactly, and deliberately does NOT follow the v1
// analytics/reminder default-OFF precedent: iOS says otherwise, so
// iOS wins. Defaults ON does not mean notifications fire without
// permission: scheduling is additionally gated on the system
// permission, which is asked once after onboarding and the tour.
//
// habitReminders is this system's own habit-reminder kind; it does
// not control the separate HabitStore reminder settings (frequency
// and time, default OFF), which keep their own storage. It only
// decides whether the habit reminders scheduled from those settings
// are sent. See HabitReminderScheduler.
//
// Preferences are stored as one JSON object under the base key
// pf.notifications.preferences.v1 through AppStore, which namespaces
// the key per account. Guest preferences stay in the guest namespace
// and never migrate to an account: AppStore.migrateGuestToAccount
// excludes this key. See LocalNotificationService and NOTIFICATIONS-NOTES.md.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.notifications

import org.json.JSONObject

/**
 * The learner's notification choices. Every kind defaults to on.
 * Mirrors the iOS struct field for field, including the field names
 * used in the stored JSON, so the stored shape stays comparable.
 */
data class NotificationPreferences(
    val masterEnabled: Boolean = true,
    val habitReminders: Boolean = true,
    val streakNudges: Boolean = true,
    val continueLearning: Boolean = true,
    val newContentAlerts: Boolean = true,
    val offers: Boolean = true
) {
    companion object {
        /** Encode one preferences value as a JSON object string. */
        fun encode(preferences: NotificationPreferences): String {
            return JSONObject()
                .put("masterEnabled", preferences.masterEnabled)
                .put("habitReminders", preferences.habitReminders)
                .put("streakNudges", preferences.streakNudges)
                .put("continueLearning", preferences.continueLearning)
                .put("newContentAlerts", preferences.newContentAlerts)
                .put("offers", preferences.offers)
                .toString()
        }

        /**
         * Decode a stored preferences value. Missing fields default
         * to true, matching the iOS Codable behaviour for a struct
         * whose fields all carry defaults. An unreadable value
         * yields the defaults, never a crash and never an invented
         * per-kind choice.
         */
        fun decode(raw: String?): NotificationPreferences {
            if (raw.isNullOrBlank()) return NotificationPreferences()
            return runCatching {
                val json = JSONObject(raw)
                NotificationPreferences(
                    masterEnabled = json.optBoolean("masterEnabled", true),
                    habitReminders = json.optBoolean("habitReminders", true),
                    streakNudges = json.optBoolean("streakNudges", true),
                    continueLearning = json.optBoolean("continueLearning", true),
                    newContentAlerts = json.optBoolean("newContentAlerts", true),
                    offers = json.optBoolean("offers", true)
                )
            }.getOrDefault(NotificationPreferences())
        }
    }
}
