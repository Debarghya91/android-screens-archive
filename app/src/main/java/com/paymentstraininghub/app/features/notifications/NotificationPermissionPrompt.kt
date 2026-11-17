// NotificationPermissionPrompt.kt
// Payments Training Hub Android - the one-time notification
// permission explanation and prompt.
//
// Port of the permission explanation in MainTabView
// (TrainingHubApp.swift, iOS v66) plus the request half of
// LocalNotificationService.requestAuthorization. Presented once,
// after onboarding and the welcome tour have completed, and never
// again: both answers record the install-level asked flag.
//
// Guards, mirroring maybeShowNotificationPermissionPrompt: onboarding
// complete (install-level flag), the tour overlay not showing, the
// tour done unless the fallback applies, the learner not yet asked
// (install-level flag), and the system decision still not
// determined. The splash guard has no Android equivalent in v1
// (there is no splash in AppNavGraph) and is not replicated. The
// fallback covers a tour that never runs: the check 2 seconds after
// launch passes it, and the tour-showing guard keeps it silent
// while the tour is actually running.
//
// On Android 13 and later the system prompt is the POST_NOTIFICATIONS
// runtime permission, shown through an ActivityResult launcher.
// On Android 12 and earlier there is no runtime permission: the
// dialog never appears, the asked flag is recorded on first check,
// and the schedule is refreshed at once. When permission was
// already decided (granted through the habit opt-in, or decided in
// system Settings), the stored decision is used without prompting,
// so a denial is never re-prompted. No em dashes in any user-facing
// text.

package com.paymentstraininghub.app.features.notifications

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.delay

/**
 * The hook the onboarding and tour work calls when its flow
 * finishes. Sets the two install-level flags that arm the
 * permission prompt, mirroring the iOS flag flips in
 * OnboardingStore and MainTabView.finishTour. The gate re-checks
 * on the next foreground; if onboarding finishes while the app is
 * already foregrounded, the gate also re-checks when the
 * navigation destination changes, because AppNavGraph recomposes.
 */
object NotificationOnboardingHook {
    fun onOnboardingAndTourCompleted(context: Context) {
        InstallFlags.markOnboardingCompleted(context)
        InstallFlags.markTourDone(context)
    }

    fun onOnboardingCompleted(context: Context) {
        InstallFlags.markOnboardingCompleted(context)
    }

    fun onTourFinished(context: Context) {
        InstallFlags.markTourDone(context)
    }
}

/**
 * Whether the one-time explanation should be shown right now.
 * Pure check over the install flags and the system state, so the
 * onboarding flow can ask the same question at its own finish
 * point if it ever shows the prompt itself.
 */
fun shouldShowPermissionPrompt(
    context: Context,
    allowTourFallback: Boolean = false,
    isTourShowing: Boolean = false
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (isTourShowing) return false
    if (!InstallFlags.hasCompletedOnboarding(context)) return false
    if (!InstallFlags.isTourDone(context) && !allowTourFallback) return false
    if (InstallFlags.hasAskedPermission(context)) return false
    return LocalNotificationService.currentAuthorizationStatus(context) ==
        NotificationAuthorizationStatus.NOT_DETERMINED
}

/**
 * The one-time permission gate. Place once in AppNavGraph, above
 * the navigation host. It renders nothing except the explanation
 * dialog, and only when every guard passes. [isTourShowing] must
 * be true while the guided tour overlay is visible, once the tour
 * exists; it is false in v1, which has no tour UI.
 */
@Composable
fun NotificationPermissionGate(isTourShowing: Boolean = false) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var showDialog by remember { mutableStateOf(false) }
    var fallbackAllowed by remember { mutableStateOf(false) }
    var recheckTick by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        LocalNotificationService.onPermissionResult(appContext, granted)
    }

    // Re-check on every foreground, mirroring the iOS onAppear and
    // onChange checks in MainTabView.
    DisposableEffect(Unit) {
        val owner = ProcessLifecycleOwner.get()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                recheckTick++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Fallback for a tour that never runs: allow the tour bypass
    // shortly after launch, mirroring the iOS post-splash check.
    // The tour-showing guard keeps this silent while the tour is
    // actually running.
    LaunchedEffect(Unit) {
        delay(2000L)
        fallbackAllowed = true
    }

    LaunchedEffect(recheckTick, fallbackAllowed, isTourShowing) {
        if (showDialog) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime permission below Android 13: record the
            // ask and arm the schedule, never show the dialog.
            if (!InstallFlags.hasAskedPermission(appContext)) {
                InstallFlags.markPermissionAsked(appContext)
                LocalNotificationService.refreshScheduledNotifications(appContext)
            }
            return@LaunchedEffect
        }
        val status = LocalNotificationService.currentAuthorizationStatus(appContext)
        if (status != NotificationAuthorizationStatus.NOT_DETERMINED) {
            // Already decided (for example granted through the
            // habit opt-in): record the ask without prompting, so
            // the explanation can never appear later, and refresh
            // the schedule for the stored decision.
            if (!InstallFlags.hasAskedPermission(appContext)) {
                InstallFlags.markPermissionAsked(appContext)
                LocalNotificationService.refreshScheduledNotifications(appContext)
            }
            return@LaunchedEffect
        }
        if (shouldShowPermissionPrompt(appContext, fallbackAllowed, isTourShowing)) {
            showDialog = true
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                // Dismissing without answering counts as "Not
                // now": the asked flag is recorded so the
                // explanation can never nag.
                showDialog = false
                LocalNotificationService.markPermissionAsked(appContext)
            },
            title = { Text("Turn on reminders?") },
            text = {
                Text(
                    "Payments Training Hub can send short reminders about your streak, " +
                        "picking up where you left off, and new lessons, plus occasional offers. " +
                        "You can change this later in Settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) { Text("Allow notifications") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        LocalNotificationService.markPermissionAsked(appContext)
                    }
                ) { Text("Not now") }
            }
        )
    }
}
