// NotificationWorkers.kt
// Payments Training Hub Android - WorkManager workers for local
// notifications.
//
// The delivery worker is the Android equivalent of a fired
// UNCalendarNotificationTrigger: it posts one notification. The
// refresh worker is the Android equivalent of the iOS foreground
// refresh, running once a day so fired one-shots are re-armed. The
// habit worker posts one habit reminder and chains the next
// occurrence. All three are local only: no FCM, no network of their
// own, apart from the catalogue read inside refreshAll.
//
// Every worker re-validates before posting, because the learner may
// have changed accounts, toggles, or the system permission between
// scheduling and firing:
//   * the account must still be the account the work was scheduled
//     for (the user id captured in the input data), so a scheduled
//     notification can never leak across an account switch;
//   * the system permission must still allow delivery;
//   * the master switch and the kind's own toggle must still be on,
//     read fresh from AppStore in the worker's own process.
// A worker that fails validation returns success without posting:
// the work is done, there is nothing to retry, and the next
// foreground refresh re-arms the schedule if it should be armed.
// Workers never return Result.retry: refresh is idempotent and runs
// again on the next foreground and the next reconciler run.
//
// Workers run in whatever process WorkManager uses, which may be a
// fresh process with no in-memory store state. Each worker therefore
// initialises AppStore itself (idempotent) and reads storage
// directly instead of trusting StateFlow values. No em dashes in
// any user-facing text.

package com.paymentstraininghub.app.features.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ProductCatalogue

/** Input-data keys shared by the schedulers and the workers. Values are all strings. */
object NotificationWorkInput {
    const val KEY_KIND = "kind"
    const val KEY_BODY = "body"
    const val KEY_ROUTE = "route"
    const val KEY_USER_ID = "userId"
    const val KEY_PRODUCT_ID = "productId"
    const val KEY_FREQUENCY = "frequency"
}

/**
 * Posts one scheduled notification, after re-validating the account,
 * the permission, and the toggles. One-shot work: it never
 * re-enqueues itself. Re-arming is refreshAll's job, on foreground
 * and from the daily reconciler.
 */
class NotificationDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppStore.init(applicationContext)
        val kind = NotificationKind.fromName(inputData.getString(NotificationWorkInput.KEY_KIND))
        val body = inputData.getString(NotificationWorkInput.KEY_BODY)
        val route = inputData.getString(NotificationWorkInput.KEY_ROUTE)
        val scheduledUserId = inputData.getString(NotificationWorkInput.KEY_USER_ID) ?: ""
        val productId = inputData.getString(NotificationWorkInput.KEY_PRODUCT_ID).orEmpty()
        if (kind == null || body.isNullOrEmpty() || route.isNullOrEmpty()) {
            return Result.success()
        }
        if (!isStillValid(kind, scheduledUserId)) return Result.success()
        if (kind == NotificationKind.OFFER && productId.isNotEmpty()) {
            // Drop an offer for a bundle the learner has since
            // unlocked. Only when BillingManager has loaded: in a
            // fresh process with no Play state yet, the
            // schedule-time check stands.
            if (BillingManager.isReady.value && isOfferProductUnlocked(productId)) {
                return Result.success()
            }
        }
        NotificationDisplay.post(applicationContext, kind, body, route)
        return Result.success()
    }

    private suspend fun isStillValid(kind: NotificationKind, scheduledUserId: String): Boolean {
        val preferences = loadPreferencesFresh()
        // Loading preferences touches AppStore, which loads the
        // persisted identity first, so the comparison below reads
        // the real current account, not a pre-load null.
        val currentUserId = AppStore.currentUserId() ?: ""
        if (currentUserId != scheduledUserId) return false
        if (LocalNotificationService.currentAuthorizationStatus(applicationContext) !=
            NotificationAuthorizationStatus.GRANTED
        ) {
            return false
        }
        if (!preferences.masterEnabled) return false
        return when (kind) {
            NotificationKind.STREAK -> preferences.streakNudges
            NotificationKind.CONTINUE_LEARNING -> preferences.continueLearning
            NotificationKind.NEW_CONTENT -> preferences.newContentAlerts
            NotificationKind.OFFER -> preferences.offers
            NotificationKind.HABIT_REMINDER -> preferences.habitReminders
        }
    }

    private fun isOfferProductUnlocked(productId: String): Boolean {
        return if (productId == ProductCatalogue.practiceProductId) {
            BillingManager.isSituationsUnlocked()
        } else {
            BillingManager.isProductUnlocked(productId)
        }
    }
}

/**
 * Reconciles the whole schedule once a day, mirroring the iOS
 * foreground refresh. Also re-applies the habit reminder schedule,
 * so a fired habit reminder is re-armed without a foreground.
 * Never fails the run: a failed refresh is retried by the next
 * foreground and the next reconciler run.
 */
class NotificationRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppStore.init(applicationContext)
        runCatching {
            NotificationSchedulers.refreshAll(applicationContext)
        }
        runCatching {
            HabitReminderScheduler.applyCurrentSchedule(applicationContext)
        }
        return Result.success()
    }
}

/**
 * Posts one habit reminder and chains the next occurrence. The body
 * and frequency were decided at schedule time; the worker
 * re-validates the account, the permission, the habit toggle, and
 * the stored reminder settings before posting, and chains the next
 * occurrence only when everything is still enabled.
 */
class HabitReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppStore.init(applicationContext)
        val frequencyName = inputData.getString(NotificationWorkInput.KEY_FREQUENCY).orEmpty()
        val body = inputData.getString(NotificationWorkInput.KEY_BODY)
        val scheduledUserId = inputData.getString(NotificationWorkInput.KEY_USER_ID) ?: ""
        if (body.isNullOrEmpty() || frequencyName.isEmpty()) {
            return Result.success()
        }
        if (!isStillValid(scheduledUserId)) return Result.success()
        // Habit reminders carry no route on iOS either: tapping one
        // simply opens the app on Home.
        NotificationDisplay.post(
            applicationContext,
            NotificationKind.HABIT_REMINDER,
            body,
            null
        )
        // Chain the next occurrence. REPLACE policy in the
        // scheduler collapses any duplicate, so chaining here and
        // re-arming on foreground can never stack reminders.
        runCatching {
            HabitReminderScheduler.applyCurrentSchedule(applicationContext)
        }
        return Result.success()
    }

    private suspend fun isStillValid(scheduledUserId: String): Boolean {
        val preferences = loadPreferencesFresh()
        val currentUserId = AppStore.currentUserId() ?: ""
        if (currentUserId != scheduledUserId) return false
        if (LocalNotificationService.currentAuthorizationStatus(applicationContext) !=
            NotificationAuthorizationStatus.GRANTED
        ) {
            return false
        }
        if (!preferences.masterEnabled || !preferences.habitReminders) return false
        val settings = HabitReminderScheduler.loadSettings()
        return settings.isEnabled
    }
}

/** Read the current account's preferences fresh from storage, for worker processes. */
internal suspend fun loadPreferencesFresh(): NotificationPreferences {
    val raw = runCatching {
        AppStore.getString(LocalNotificationService.PREFERENCES_BASE_KEY)
    }.getOrNull()
    return NotificationPreferences.decode(raw)
}
