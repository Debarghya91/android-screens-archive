// NotificationSettingsScreen.kt
// Payments Training Hub Android - notification settings.
//
// Port of NotificationSettingsView.swift (iOS v66). One screen for
// every notification choice: a master switch, one toggle per
// notification type, and the system permission status in plain
// words. All preferences live in LocalNotificationService and every
// toggle writes through updatePreferences. All notifications are
// local only. The habit reminders toggle also keeps the scheduled
// habit reminders in step through the existing habit code; it never
// reimplements the habit scheduler. When the system permission is
// denied, this screen only points to system Settings. It never asks
// again from inside the app.
//
// Layout mirrors the iOS screen section for section (About, All
// notifications, Notification types, habit note) and the design
// reference (DesignSystem.swift): royal blue is the single primary
// (switches, the Open Settings button), deep navy for headings,
// secondary text for explanations, white cards with an 18dp radius
// and a hairline border on the warm off-white page background.
// Spacing follows the iOS scale: 24dp between sections (xl), 16dp
// screen and card padding (lg), 12dp inside cards (md). Only
// PfColors and PfSpacing members the v1 scaffold contract provides
// are used; larger gaps use explicit dp values mapped from the iOS
// scale in comments. Gold is not used here: no call to action on
// this screen is the single main action, and the iOS screen uses a
// secondary button for Open Settings. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.features.notifications.HabitReminderScheduler
import com.paymentstraininghub.app.features.notifications.LocalNotificationService
import com.paymentstraininghub.app.features.notifications.NotificationAuthorizationStatus
import com.paymentstraininghub.app.features.notifications.NotificationPreferences
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

/**
 * The notification settings screen, reached from Profile. The
 * master switch turns every type off at once while keeping the
 * per-type choices below, so turning it back on restores them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val preferences by LocalNotificationService.preferences.collectAsState()
    val authorizationStatus by LocalNotificationService.authorizationStatus.collectAsState()

    LaunchedEffect(Unit) {
        LocalNotificationService.refreshIfNamespaceChanged()
        LocalNotificationService.refreshAuthorizationStatus(appContext)
    }

    fun updatePreferences(updated: NotificationPreferences) {
        LocalNotificationService.updatePreferences(appContext, updated)
    }

    fun applyHabitReminderChange(isOn: Boolean) {
        scope.launch {
            HabitReminderScheduler.applyHabitReminderChange(appContext, isOn)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
    ) {
        TopAppBar(
            title = { Text("Notifications", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                navigationIconContentColor = PfColors.navy
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.medium),
            // iOS section spacing: xl (24dp).
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // MARK: - About and system permission
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("About notifications")
                SettingsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Payments Training Hub sends short notifications to help you keep " +
                                "learning. They remind you at a time you choose, tell you when " +
                                "new lessons arrive, and share offers. Choose which types you " +
                                "want below. You can change this any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "System permission",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                authorizationStatusText(authorizationStatus),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                        }
                        if (authorizationStatus == NotificationAuthorizationStatus.DENIED) {
                            Text(
                                "Notifications are off in system Settings, so nothing below " +
                                    "can reach you yet. Turn them on in Settings to receive them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Button(
                                onClick = { LocalNotificationService.openSystemSettings(context) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PfColors.royal.copy(alpha = 0.08f),
                                    contentColor = PfColors.royal
                                ),
                                contentPadding = PaddingValues(vertical = 14.dp)
                            ) {
                                Text(
                                    "Open Settings",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // MARK: - Master switch
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("All notifications")
                SettingsCard {
                    PreferenceToggle(
                        title = "All notifications",
                        explanation = "Turn every notification type on or off at once. " +
                            "Your choices below are kept when this is off.",
                        checked = preferences.masterEnabled,
                        enabled = true,
                        onCheckedChange = { newValue ->
                            updatePreferences(preferences.copy(masterEnabled = newValue))
                        }
                    )
                }
            }

            // MARK: - Per-type toggles
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Notification types")
                SettingsCard {
                    Column(
                        modifier = Modifier.alpha(if (preferences.masterEnabled) 1f else 0.5f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PreferenceToggle(
                            title = "Habit reminders",
                            explanation = "A gentle nudge at the time you chose.",
                            checked = preferences.habitReminders,
                            enabled = preferences.masterEnabled,
                            onCheckedChange = { newValue ->
                                updatePreferences(preferences.copy(habitReminders = newValue))
                                applyHabitReminderChange(newValue)
                            }
                        )
                        SettingsDivider()
                        PreferenceToggle(
                            title = "Streak nudges",
                            explanation = "A short note about your learning streak.",
                            checked = preferences.streakNudges,
                            enabled = preferences.masterEnabled,
                            onCheckedChange = { newValue ->
                                updatePreferences(preferences.copy(streakNudges = newValue))
                            }
                        )
                        SettingsDivider()
                        PreferenceToggle(
                            title = "Continue learning",
                            explanation = "A reminder to pick up where you left off.",
                            checked = preferences.continueLearning,
                            enabled = preferences.masterEnabled,
                            onCheckedChange = { newValue ->
                                updatePreferences(preferences.copy(continueLearning = newValue))
                            }
                        )
                        SettingsDivider()
                        PreferenceToggle(
                            title = "New lessons and cases",
                            explanation = "A note when new lessons or cases are added.",
                            checked = preferences.newContentAlerts,
                            enabled = preferences.masterEnabled,
                            onCheckedChange = { newValue ->
                                updatePreferences(preferences.copy(newContentAlerts = newValue))
                            }
                        )
                        SettingsDivider()
                        PreferenceToggle(
                            title = "Offers and announcements",
                            explanation = "Offers and news from Payments Training Hub.",
                            checked = preferences.offers,
                            enabled = preferences.masterEnabled,
                            onCheckedChange = { newValue ->
                                updatePreferences(preferences.copy(offers = newValue))
                            }
                        )
                    }
                }
            }

            // Habit reminders follow the time and frequency set on
            // the Habit screen, under Revision reminders. This
            // toggle only decides whether those reminders are sent.
            Text(
                "Habit reminders use the time and frequency set under Revision reminders " +
                    "on the Habit screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
    }
}

/** The system permission in plain words: allowed, not allowed, or not asked yet. */
private fun authorizationStatusText(
    status: NotificationAuthorizationStatus
): String {
    return when (status) {
        NotificationAuthorizationStatus.GRANTED -> "Allowed"
        NotificationAuthorizationStatus.DENIED -> "Not allowed"
        NotificationAuthorizationStatus.NOT_DETERMINED -> "Not asked yet"
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = PfColors.navy
    )
}

/**
 * A card container with the standard look: white, 18dp radius (the
 * iOS card radius), a hairline border, and a small elevation so it
 * lifts off the page. Mirrors PFCard in DesignSystem.swift.
 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.padding(PfSpacing.medium)) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PfColors.border)
    )
}

/**
 * One toggle row, matching the iOS settings toggle: title, one line
 * of explanation, royal switch. The whole row toggles, like the iOS
 * Toggle row.
 */
@Composable
private fun PreferenceToggle(
    title: String,
    explanation: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.navy
            )
            Text(
                explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PfColors.royal,
                checkedBorderColor = PfColors.royal,
                uncheckedBorderColor = PfColors.secondaryText
            )
        )
    }
}
