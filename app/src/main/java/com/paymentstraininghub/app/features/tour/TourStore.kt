// TourStore.kt
// Device-scoped tour and onboarding completion flags, mirroring the
// iOS install-level AppStorage flags (pf.tour.done and the
// onboarding completion flag). Device-scoped, not account-scoped:
// completing the tour or onboarding once covers every account on
// the device, exactly as on iOS.

package com.paymentstraininghub.app.features.tour

import android.content.Context

object TourStore {
    private const val PREFS_NAME = "pf_tour"
    private const val KEY_TOUR_DONE = "tour_done"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    fun isTourDone(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUR_DONE, false)
    }

    fun markTourDone(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOUR_DONE, true)
            .apply()
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun markOnboardingCompleted(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
    }
}
