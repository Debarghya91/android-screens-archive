// Learning habits: today's small goal, weekly target, question of the day
// from the real question bank, the weekly lab entry, revision reminder
// settings, streak display without guilt copy, and milestones from recorded
// progress. Restyled to the iOS look (gating port); revision reminders
// are local notifications, scheduled on this device through
// HabitReminderScheduler when reminders are on and the system permission
// allows delivery. Turning reminders on asks for the notification
// permission first, and the choice is saved only when it is granted,
// mirroring the iOS opt-in path. No em dashes.
package com.paymentstraininghub.app.features.habit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.analytics.AnalyticsConsentDialog
import com.paymentstraininghub.app.features.analytics.AnalyticsService
import com.paymentstraininghub.app.features.lab.ExceptionCaseCatalogue
import com.paymentstraininghub.app.features.notifications.HabitReminderScheduler
import com.paymentstraininghub.app.features.notifications.LocalNotificationService
import com.paymentstraininghub.app.features.notifications.NotificationAuthorizationStatus
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfChip
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfOutlineButton
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class QuestionOfDay {
    data class Interview(val question: HabitInterviewQuestion) : QuestionOfDay()
    data class Quiz(
        val question: String,
        val options: List<String>,
        val explanation: String,
        val lessonTitle: String
    ) : QuestionOfDay()
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HabitScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val reminderSettings by HabitStore.reminderSettings.collectAsState()
    var events by remember { mutableStateOf<List<HabitLearningEvent>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var weeklyGoal by remember { mutableStateOf(0) }
    var totalLessons by remember { mutableStateOf(0) }
    var dailyGoalTitle by remember { mutableStateOf<String?>(null) }
    var dailyGoalDone by remember { mutableStateOf(false) }
    var contentLoading by remember { mutableStateOf(true) }
    var contentFailed by remember { mutableStateOf(false) }
    var questionOfDay by remember { mutableStateOf<QuestionOfDay?>(null) }
    var questionLoading by remember { mutableStateOf(true) }
    var showAnswer by remember { mutableStateOf(false) }
    var showAnalyticsConsent by remember { mutableStateOf(false) }
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Revision-reminder notification state (notifications port).
    val notificationPreferences by LocalNotificationService.preferences.collectAsState()
    val authorizationStatus by LocalNotificationService.authorizationStatus.collectAsState()
    var pendingFrequency by remember { mutableStateOf<HabitReminderFrequency?>(null) }
    var reminderStatusMessage by remember { mutableStateOf<String?>(null) }

    fun saveAndApply(settings: HabitReminderSettings) {
        HabitStore.saveReminderSettings(settings)
        scope.launch {
            HabitReminderScheduler.applyCurrentSchedule(appContext)
        }
    }

    fun isNotificationPermissionGranted(): Boolean {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // The explicit opt-in path, mirroring LearningHabitStore
    // enableReminders: ask the system for permission, and only when
    // it is granted save the settings and schedule the reminder.
    // A denial leaves the stored settings off. This flow never sets
    // the install-level permission-asked flag: that flag belongs to
    // the one-time prompt after onboarding and the tour.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val frequency = pendingFrequency
        pendingFrequency = null
        if (frequency == null) return@rememberLauncherForActivityResult
        val current = HabitStore.reminderSettings.value
        if (granted) {
            saveAndApply(current.copy(frequency = frequency))
            reminderStatusMessage = "Reminders on."
        } else {
            saveAndApply(current.copy(frequency = HabitReminderFrequency.OFF))
            reminderStatusMessage = "Notifications were not allowed, so reminders stay off. " +
                "You can allow them in the Settings app, under Notifications."
        }
    }

    fun requestOrEnable(frequency: HabitReminderFrequency) {
        if (frequency == HabitReminderFrequency.OFF) {
            saveAndApply(HabitStore.reminderSettings.value.copy(frequency = frequency))
            reminderStatusMessage = "Reminders turned off."
            return
        }
        if (isNotificationPermissionGranted()) {
            saveAndApply(HabitStore.reminderSettings.value.copy(frequency = frequency))
            reminderStatusMessage = "Reminders on."
        } else {
            pendingFrequency = frequency
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The status line: what is on, or why reminders stay off.
    // Mirrors HabitReminderSettingsView.statusText on iOS.
    val savedTimeText = reminderSettings.hour.toString().padStart(2, '0') + ":" +
        reminderSettings.minute.toString().padStart(2, '0')
    val scheduleText = when (reminderSettings.frequency) {
        HabitReminderFrequency.OFF -> "Off"
        HabitReminderFrequency.DAILY -> "Daily"
        HabitReminderFrequency.WEEKLY -> "Every Monday"
    }
    val reminderStatusText = when {
        reminderSettings.isEnabled && !notificationPreferences.habitReminders ->
            "Reminders are set for $scheduleText at $savedTimeText, but paused in " +
                "Notification settings. Turn Habit reminders back on there to receive them."
        reminderSettings.isEnabled ->
            "Reminders on: $scheduleText at $savedTimeText."
        authorizationStatus == NotificationAuthorizationStatus.DENIED ->
            "Notifications are turned off for Payments Training Hub in the Settings app, " +
                "under Notifications. Reminders stay off until you turn them on there."
        else -> "Reminders are off. Turn them on only if you want them."
    }

    LaunchedEffect(Unit) {
        HabitStore.reloadForCurrentAccount()
        LocalNotificationService.refreshIfNamespaceChanged()
        LocalNotificationService.refreshAuthorizationStatus(appContext)
        events = HabitStore.loadEvents()
        completedIds = HabitStore.loadCompletedLessonIds()
        weeklyGoal = HabitStore.loadWeeklyGoal()
        val entries = runCatching { ContentRepository.loadSearchIndex().entries }
            .getOrElse { emptyList() }
        val lessons = entries.filter { it.isLesson }
        totalLessons = lessons.size
        if (entries.isEmpty()) {
            contentFailed = true
        } else {
            val completed = completedIds.ifEmpty {
                events.filter { it.type == "lessonCompleted" }.mapNotNull { it.lessonID }.toSet()
            }
            val next = lessons.firstOrNull { it.id !in completed }
            dailyGoalTitle = next?.citation
            dailyGoalDone = next != null &&
                LearningHabitPlanner.didCompleteLessonToday(next.id, events)
        }
        contentLoading = false

        // Question of the day: the real interview bank first, quiz questions
        // from a loaded lesson detail second. Never invented. The bank
        // parse (342 KB of JSON) runs off Main: this effect runs on Main.
        val bank = withContext(Dispatchers.IO) { HabitQuestionBank.loadFromAssets(context) }
        if (!bank.isNullOrEmpty()) {
            LearningHabitPlanner.dailyQuestion(Calendar.getInstance(), bank)?.let {
                questionOfDay = QuestionOfDay.Interview(it)
            }
        } else if (lessons.isNotEmpty()) {
            val lesson = LearningHabitPlanner.dailyQuestion(Calendar.getInstance(), lessons)
            if (lesson != null) {
                val detail = runCatching { ContentRepository.lessonDetail(lesson.id) }.getOrNull()
                val quiz = detail?.quiz
                if (!quiz.isNullOrEmpty()) {
                    val picked = LearningHabitPlanner.dailyQuestion(Calendar.getInstance(), quiz)
                    if (picked != null) {
                        questionOfDay = QuestionOfDay.Quiz(
                            question = picked.question,
                            options = picked.options,
                            explanation = picked.explanation,
                            lessonTitle = lesson.title
                        )
                    }
                }
            }
        }
        questionLoading = false
    }

    val streak = LearningHabitPlanner.streakCount(events)
    val completedCount = completedIds.ifEmpty {
        events.filter { it.type == "lessonCompleted" }.mapNotNull { it.lessonID }.toSet()
    }.size
    val completedThisWeek = LearningHabitPlanner.completedThisWeek(events)
    val weeklyCase = LearningHabitPlanner.weeklyInvestigationCase(
        Calendar.getInstance(),
        ExceptionCaseCatalogue.allCases
    )
    val milestones = LearningHabitPlanner.milestones(completedCount, totalLessons)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Learning habits", color = PfColors.navy, style = PfTypography.cardTitle) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
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
                .background(PfColors.background)
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)
        ) {
            PfCard {
            Text("Today's small goal", style = PfTypography.cardTitle, color = PfColors.navy)
            when {
                contentLoading -> Text("Loading...", style = PfTypography.body, color = PfColors.secondaryText)
                contentFailed -> Text(
                    "The catalogue could not be loaded. Check your connection and reopen this screen.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                dailyGoalTitle != null -> {
                    Text(dailyGoalTitle ?: "", style = PfTypography.body, color = PfColors.navy)
                    if (dailyGoalDone) Text("Completed today.", style = PfTypography.body, color = PfColors.secondaryText)
                }
                else -> Text(
                    "Every lesson in the loaded catalogue is recorded as complete.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            }
        }

        PfCard {
            Text("Weekly target", style = PfTypography.cardTitle, color = PfColors.navy)
            Text(
                LearningHabitPlanner.weeklyTargetText(completedThisWeek, weeklyGoal),
                style = PfTypography.body,
                color = PfColors.navy
            )
            if (weeklyGoal <= 0) {
                Text(
                    "Set a weekly target in Learning Preferences to track it here.",
                    style = PfTypography.caption,
                    color = PfColors.secondaryText
                )
            }
        }

        PfCard {
            Text("Streak", style = PfTypography.cardTitle, color = PfColors.navy)
            Text(LearningHabitPlanner.streakText(streak), style = PfTypography.body, color = PfColors.navy)
            Text(
                "Counts consecutive days with at least one recorded learning event.",
                style = PfTypography.caption,
                color = PfColors.secondaryText
            )
        }

        PfCard {
            Text("Question of the day", style = PfTypography.cardTitle, color = PfColors.navy)
            when {
                questionLoading -> Text("Loading...", style = PfTypography.body, color = PfColors.secondaryText)
                questionOfDay == null -> Text(
                    "The question of the day is not available yet. Check your connection and reopen this screen.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                questionOfDay is QuestionOfDay.Interview -> {
                    val item = (questionOfDay as QuestionOfDay.Interview).question
                    PfBodyText(text = item.question, style = PfTypography.body, color = PfColors.navy)
                    if (showAnswer) {
                        PfBodyText(text = item.answer, style = PfTypography.body, color = PfColors.navy)
                    } else {
                        TextButton(onClick = { showAnswer = true }) {
                            Text("Show the model answer", color = PfColors.royal)
                        }
                    }
                }
                questionOfDay is QuestionOfDay.Quiz -> {
                    val item = (questionOfDay as QuestionOfDay.Quiz)
                    Text(
                        "From the lesson quiz for: ${item.lessonTitle}",
                        style = PfTypography.caption,
                        color = PfColors.secondaryText
                    )
                    Text(item.question, style = PfTypography.body, color = PfColors.navy)
                    item.options.forEachIndexed { index, option ->
                        Text("${index + 1}. $option", style = PfTypography.body, color = PfColors.navy)
                    }
                    if (showAnswer) {
                        PfBodyText(text = item.explanation, style = PfTypography.body, color = PfColors.navy)
                    } else {
                        TextButton(onClick = { showAnswer = true }) {
                            Text("Show the explanation", color = PfColors.royal)
                        }
                    }
                }
            }
        }

        PfCard {
            Text("This week's investigation", style = PfTypography.cardTitle, color = PfColors.navy)
            if (weeklyCase != null) {
                Text(weeklyCase.title, style = PfTypography.body, fontWeight = FontWeight.Medium, color = PfColors.navy)
                Text(
                    "${weeklyCase.incidentType}. Level: ${weeklyCase.level.displayName}.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                Text(
                    "Open it in the Exception Lab. The same case runs all week, and a new case appears each week.",
                    style = PfTypography.caption,
                    color = PfColors.secondaryText
                )
            } else {
                Text("No investigation case is available.", style = PfTypography.body, color = PfColors.secondaryText)
            }
        }

        PfCard {
            Text("Revision reminders", style = PfTypography.cardTitle, color = PfColors.navy)
            Text(
                "A gentle nudge at a time you choose. Reminders are off unless you turn " +
                    "them on, and you can turn them off here at any time. Nothing here " +
                    "tracks missed days. When reminders are on, a local notification is " +
                    "scheduled on this device at the time you choose.",
                style = PfTypography.caption,
                color = PfColors.secondaryText
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                HabitReminderFrequency.entries.forEach { frequency ->
                    PfChip(
                        text = frequency.displayTitle,
                        isSelected = reminderSettings.frequency == frequency,
                        onClick = { requestOrEnable(frequency) }
                    )
                }
            }
            if (reminderSettings.isEnabled) {
                Text(
                    "Reminder time: ${reminderSettings.hour.toString().padStart(2, '0')}:" +
                        reminderSettings.minute.toString().padStart(2, '0'),
                    style = PfTypography.body,
                    color = PfColors.navy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    PfOutlineButton(
                        title = "Hour -",
                        onClick = {
                            saveAndApply(
                                reminderSettings.copy(hour = (reminderSettings.hour + 23) % 24)
                            )
                        }
                    )
                    PfOutlineButton(
                        title = "Hour +",
                        onClick = {
                            saveAndApply(
                                reminderSettings.copy(hour = (reminderSettings.hour + 1) % 24)
                            )
                        }
                    )
                    PfOutlineButton(
                        title = "Minute -",
                        onClick = {
                            saveAndApply(
                                reminderSettings.copy(minute = (reminderSettings.minute + 45) % 60)
                            )
                        }
                    )
                    PfOutlineButton(
                        title = "Minute +",
                        onClick = {
                            saveAndApply(
                                reminderSettings.copy(minute = (reminderSettings.minute + 15) % 60)
                            )
                        }
                    )
                }
                if (reminderSettings.frequency == HabitReminderFrequency.WEEKLY) {
                    Text(
                        "Weekly reminders are for Mondays.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
                Text(
                    reminderStatusText,
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                if (reminderStatusMessage != null) {
                    Text(
                        reminderStatusMessage ?: "",
                        style = PfTypography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.royal
                    )
                }
            }
        }

        PfCard {
            Text("Milestones", style = PfTypography.cardTitle, color = PfColors.navy)
            Text(
                "$completedCount lesson${if (completedCount == 1) "" else "s"} recorded as complete.",
                style = PfTypography.body,
                color = PfColors.navy
            )
            if (milestones.isEmpty()) {
                Text(
                    "Milestones appear once the catalogue has loaded.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            }
            milestones.forEach { milestone ->
                Text(
                    "${if (milestone.isReached) "Reached" else "Next"}: ${milestone.title}",
                    style = PfTypography.body,
                    color = PfColors.navy
                )
            }
        }

        PfCard {
            Text("Analytics", style = PfTypography.cardTitle, color = PfColors.navy)
            Text(
                "Analytics is opt-in and off by default. No event leaves this device in this build.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            PfPrimaryButton(title = "Analytics settings", onClick = { showAnalyticsConsent = true })
        }
        Spacer(modifier = Modifier.height(PfSpacing.small))
    }

    if (showAnalyticsConsent) {
        AnalyticsConsentDialog(
            isConsented = AnalyticsService.isConsented,
            hasDecided = AnalyticsService.hasDecided,
            onGrant = {
                AnalyticsService.grantConsent()
                showAnalyticsConsent = false
            },
            onDecline = {
                AnalyticsService.declineConsent()
                showAnalyticsConsent = false
            },
            onWithdraw = {
                AnalyticsService.withdrawConsent()
                showAnalyticsConsent = false
            },
            onDismiss = { showAnalyticsConsent = false }
        )
    }
    }
}
