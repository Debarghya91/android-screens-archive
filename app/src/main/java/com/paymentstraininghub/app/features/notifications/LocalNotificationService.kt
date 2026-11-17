// LocalNotificationService.kt
// Payments Training Hub Android - local notification preferences,
// system permission state, the one-time permission ask, and the
// scheduling refresh entry point.
//
// Port of LocalNotificationService.swift (iOS v66). Local only.
// Nothing here uses FCM, APNs, device tokens, or any server. All
// notifications are scheduled on this device through WorkManager
// (see NotificationSchedulers) and posted through NotificationManager
// (see NotificationDisplay).
//
// Preferences are namespaced per account through AppStore, following
// the HabitStore pattern: one JSON value, loads never write, and
// reload clears in-memory state first. The permission-asked flag is
// different: it is install level, stored under a plain
// SharedPreferences key with no account namespace, so the system
// prompt is shown at most once per install whatever account is
// signed in. AppStore cannot hold an un-namespaced key (every
// get/put namespaces internally), so the flag deliberately lives in
// SharedPreferences via InstallFlags. Never pass the asked key
// through AppStore.
//
// WorkManager unique-work names for this system all start with
// "pf.notify.". The habit reminders use different unique-work names
// ("pf.learningHabit.dailyReminder" and
// "pf.learningHabit.weeklyReminder") and are owned by
// HabitReminderScheduler; the cancel path here cancels only the four
// exact "pf.notify." names, so habit reminders are never touched.
//
// Tapping a notification routes through the same mechanism as a
// tapped deep link: the notification carries an ACTION_VIEW intent
// for the deep-link URL string, parsed by DeepLinkRouter, and the
// navigation graph routes the one-shot event. There is no second
// router. The router's buffered event channel is itself the hold
// for a link that arrives before the navigation graph is ready: a
// cold-start tap is never lost, and the event is delivered once,
// never replayed. No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paymentstraininghub.app.auth.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The system notification permission, in the same three states the
 * iOS settings screen shows. Android has no provisional grant, so
 * there are only these three: not asked yet (Android 13 or later,
 * permission not granted, never asked in the app), allowed, and not
 * allowed (denied in the app prompt or turned off in system
 * Settings). On Android 12 and earlier there is no runtime
 * permission: the state is allowed unless notifications are turned
 * off in system Settings.
 */
enum class NotificationAuthorizationStatus {
    NOT_DETERMINED,
    GRANTED,
    DENIED
}

/**
 * Install-level flags: values that belong to the install, not to any
 * account, stored in plain SharedPreferences with no account
 * namespace. Mirrors the plain UserDefaults keys on iOS. The
 * onboarding and tour flags are the contract with the onboarding
 * and tour work: that work writes them through [markOnboardingCompleted]
 * and [markTourDone] when its flow finishes, which arms the
 * one-time permission prompt. Until they are written, the prompt
 * stays silent, exactly as on iOS before onboarding and the tour
 * complete.
 */
object InstallFlags {
    private const val PREFS_NAME = "pf_install_flags"

    /** Install-level permission-asked flag. Never pass through AppStore. */
    const val PERMISSION_ASKED_KEY = "pf.notifications.permissionAsked.v1"

    /** Install-level onboarding-completed flag, mirroring iOS pf.onboarding.completed.v1. */
    const val ONBOARDING_COMPLETED_KEY = "pf.onboarding.completed.v1"

    /** Install-level tour-done flag, mirroring iOS pf.tour.done. */
    const val TOUR_DONE_KEY = "pf.tour.done"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAskedPermission(context: Context): Boolean =
        prefs(context).getBoolean(PERMISSION_ASKED_KEY, false)

    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(PERMISSION_ASKED_KEY, true).apply()
    }

    fun hasCompletedOnboarding(context: Context): Boolean =
        prefs(context).getBoolean(ONBOARDING_COMPLETED_KEY, false)

    /**
     * Record that onboarding finished. Called by the onboarding flow
     * on finish (including Skip), mirroring the iOS OnboardingStore
     * flag flip. Safe to call more than once.
     */
    fun markOnboardingCompleted(context: Context) {
        prefs(context).edit().putBoolean(ONBOARDING_COMPLETED_KEY, true).apply()
    }

    fun isTourDone(context: Context): Boolean =
        prefs(context).getBoolean(TOUR_DONE_KEY, false)

    /**
     * Record that the guided tour finished (completed or skipped).
     * Called by the tour flow on finish, mirroring the iOS
     * pf.tour.done flag. Safe to call more than once.
     */
    fun markTourDone(context: Context) {
        prefs(context).edit().putBoolean(TOUR_DONE_KEY, true).apply()
    }
}

/**
 * The shared local notification service: preferences, permission
 * state, the one-time permission ask, and the scheduling refresh
 * entry point. Mirrors LocalNotificationService in iOS v66. All
 * public entry points take a Context and are safe to call from any
 * thread; storage and scheduling work runs on an IO scope.
 */
object LocalNotificationService {

    /** Base key for the preferences value, namespaced per account by AppStore. */
    const val PREFERENCES_BASE_KEY = "pf.notifications.preferences.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _preferences = MutableStateFlow(NotificationPreferences())
    /** The current account's notification choices. Defaults to every kind on. */
    val preferences: StateFlow<NotificationPreferences> = _preferences.asStateFlow()

    private val _authorizationStatus =
        MutableStateFlow(NotificationAuthorizationStatus.NOT_DETERMINED)
    /** The system notification permission state, last read. Never persisted. */
    val authorizationStatus: StateFlow<NotificationAuthorizationStatus> =
        _authorizationStatus.asStateFlow()

    /** True when the system will deliver notifications. */
    val isAuthorized: Boolean get() = _authorizationStatus.value ==
        NotificationAuthorizationStatus.GRANTED

    /**
     * The account the in-memory preferences were loaded from
     * (user id, or null for guest). Compared on every write and
     * schedule refresh so a namespace change (sign-in or sign-out)
     * reloads the right account's preferences instead of writing
     * one account's choices into another namespace.
     *
     * Saving is explicit: only updatePreferences writes, and loads
     * never write. A reload into an account with no stored
     * preferences therefore never writes a default value into that
     * namespace (the property iOS gets from suppress-save while
     * loading, where the published value auto-saves on change).
     */
    @Volatile
    private var loadedUserId: String? = null

    @Volatile
    private var initialised = false

    /**
     * Load preferences for the current account and read the system
     * permission state. Call once at launch (AppNavGraph does this)
     * and again after any identity change via
     * [reloadForCurrentAccount]. Safe to call more than once.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        if (initialised) {
            refreshAuthorizationStatus(appContext)
            return
        }
        initialised = true
        AppStore.init(appContext)
        scope.launch {
            loadPreferences()
            refreshAuthorizationStatusNow(appContext)
            refreshScheduledNotificationsNow(appContext)
        }
    }

    // MARK: - Permission asked flag

    /**
     * True once the learner has been asked about notifications on
     * this install, whether they allowed, declined in the in-app
     * explanation, or were already decided when asked. Install
     * level, not per account: signing in, signing out, or
     * switching accounts never re-arms the ask.
     */
    fun hasAskedPermission(context: Context): Boolean =
        InstallFlags.hasAskedPermission(context)

    /**
     * Record that the learner has been asked, without showing the
     * system prompt. Used by the "Not now" answer in the in-app
     * explanation, so declining never asks again.
     */
    fun markPermissionAsked(context: Context) {
        InstallFlags.markPermissionAsked(context)
    }

    // MARK: - Authorisation

    /**
     * Re-read the system permission state. Never persisted: the
     * system is the source of truth.
     */
    fun refreshAuthorizationStatus(context: Context) {
        val appContext = context.applicationContext
        scope.launch { refreshAuthorizationStatusNow(appContext) }
    }

    /**
     * The permission state for this device right now, read
     * synchronously. Used by the permission gate and by workers
     * that must decide without waiting for a StateFlow update.
     */
    fun currentAuthorizationStatus(context: Context): NotificationAuthorizationStatus {
        val appContext = context.applicationContext
        val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) {
                NotificationAuthorizationStatus.GRANTED
            } else {
                NotificationAuthorizationStatus.DENIED
            }
        }
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Granted but turned off in system Settings after
            // granting: nothing can reach the learner.
            return if (enabled) {
                NotificationAuthorizationStatus.GRANTED
            } else {
                NotificationAuthorizationStatus.DENIED
            }
        }
        return if (hasAskedPermission(appContext)) {
            NotificationAuthorizationStatus.DENIED
        } else {
            NotificationAuthorizationStatus.NOT_DETERMINED
        }
    }

    private fun refreshAuthorizationStatusNow(context: Context) {
        _authorizationStatus.value = currentAuthorizationStatus(context)
    }

    /**
     * Record the outcome of a permission request: the asked flag is
     * recorded whether the answer was allow or deny, and the
     * schedule is refreshed at once when permission is granted, so
     * the first notifications are set up without waiting for the
     * next foreground. Mirrors the tail of iOS requestAuthorization.
     * The system prompt itself is shown by the permission gate's
     * ActivityResult launcher; this service never prompts directly,
     * because the prompt needs an Activity.
     */
    fun onPermissionResult(context: Context, granted: Boolean) {
        val appContext = context.applicationContext
        markPermissionAsked(appContext)
        scope.launch {
            refreshAuthorizationStatusNow(appContext)
            refreshScheduledNotificationsNow(appContext)
        }
    }

    // MARK: - Preferences and scheduling

    /**
     * Save new preferences and make the schedule match them. The
     * schedule refresh uses the last known permission state, which
     * the app refreshes whenever it comes to the foreground.
     */
    fun updatePreferences(context: Context, new: NotificationPreferences) {
        val appContext = context.applicationContext
        scope.launch {
            refreshIfNamespaceChangedInternal()
            runCatching {
                AppStore.putString(PREFERENCES_BASE_KEY, NotificationPreferences.encode(new))
            }
            _preferences.value = new
            refreshScheduledNotificationsNow(appContext)
        }
    }

    /**
     * Reload preferences when the identity namespace has changed
     * since the last load. A no-op when it has not, so calling it
     * on every settings appearance costs nothing and never
     * flickers the toggles.
     */
    fun refreshIfNamespaceChanged() {
        scope.launch { refreshIfNamespaceChangedInternal() }
    }

    private suspend fun refreshIfNamespaceChangedInternal() {
        // Touch AppStore first so the persisted identity is loaded
        // before the namespace is compared.
        runCatching { AppStore.getString(PREFERENCES_BASE_KEY) }
        val current = AppStore.currentUserId()
        if (current == loadedUserId && initialised) return
        _preferences.value = NotificationPreferences()
        loadPreferences()
    }

    /**
     * Make the scheduled notifications match the current
     * preferences and permission state: when delivery is allowed
     * and the master switch is on, the schedulers rebuild this
     * system's work from the current preferences and on-device
     * state; otherwise every pending item with a "pf.notify."
     * unique-work name is cancelled. Habit reminder work is never
     * touched: its unique-work names carry a different prefix.
     * Cheap and idempotent: safe to call on every foreground and
     * every preference change.
     */
    fun refreshScheduledNotifications(context: Context) {
        val appContext = context.applicationContext
        scope.launch { refreshScheduledNotificationsNow(appContext) }
    }

    internal suspend fun refreshScheduledNotificationsNow(context: Context) {
        refreshIfNamespaceChangedInternal()
        if (!isAuthorized || !_preferences.value.masterEnabled) {
            NotificationSchedulers.cancelAll(context)
            return
        }
        NotificationSchedulers.refreshAll(context)
    }

    /**
     * Reload preferences for the current account namespace,
     * clearing the previous account's preferences first, then
     * refresh the schedule for the newly loaded preferences. The
     * permission state is not reset: it belongs to the system, not
     * to any account. Called on identity transitions from
     * AppNavGraph, which observes AuthManager.authState. Guest
     * preferences are NOT copied into the new account: the new
     * account starts with its own stored preferences, or the
     * defaults when it has none.
     */
    fun reloadForCurrentAccount(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            _preferences.value = NotificationPreferences()
            loadPreferences()
            refreshAuthorizationStatusNow(appContext)
            refreshScheduledNotificationsNow(appContext)
        }
    }

    /**
     * Open the system Settings page for this app, where the learner
     * can turn notifications on or off after denying them. Used by
     * the settings surface when the state is denied: the system
     * prompt cannot be shown again. Mirrors iOS openSystemSettings.
     */
    fun openSystemSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // No Settings activity available. The settings screen
            // already explains where to change the permission.
        }
    }

    // MARK: - Private

    private suspend fun loadPreferences() {
        val raw = runCatching { AppStore.getString(PREFERENCES_BASE_KEY) }.getOrNull()
        _preferences.value = NotificationPreferences.decode(raw)
        loadedUserId = AppStore.currentUserId()
    }
}
