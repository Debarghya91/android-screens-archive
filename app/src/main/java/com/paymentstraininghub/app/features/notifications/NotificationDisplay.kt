// NotificationDisplay.kt
// Payments Training Hub Android - notification channels and posting.
//
// The Android half of the iOS schedule() primitive in
// NotificationSchedulers.swift: one channel per notification kind,
// the title "Payments Training Hub", the body, the default sound,
// no badge, and a tap that opens the deep-link route. iOS has no
// channels; one channel per kind is the Android-idiomatic equivalent
// of the per-kind toggles, because it lets the learner control each
// kind in system Settings too. Channel names mirror the settings
// toggle titles exactly.
//
// Appearance follows the iOS design reference (DesignSystem.swift):
// royal blue is the single primary, so the notification accent
// colour is royal #17408C; gold is never used here, because a
// notification is not a call to action. The small icon is the
// scaffold-owned drawable "ic_notification" when present (white
// silhouette on transparent, as Android requires for status-bar
// icons); the lookup is by name so this file compiles before the
// scaffold adds the drawable, and falls back to the app icon and
// then to a system icon. The scaffold must add ic_notification;
// see NOTIFICATIONS-NOTES.md.
//
// Taps reuse the existing deep-link scheme: the route string is
// validated with DeepLinkRouter.parse, and only the two real routes
// (a lesson link and the weekly-case link, on the
// com.paymentstraininghub.app scheme) are opened. Anything else opens the
// app on the Home tab through the package launch intent. No new
// scheme is invented, and no Activity class is referenced: the tap
// intent is ACTION_VIEW, resolved by the manifest intent filter
// that the scaffold owns. No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.paymentstraininghub.app.features.growth.DeepLinkRouter

/**
 * One notification kind: its channel, its stable notification id,
 * and its WorkManager unique-work name. The four "pf.notify." kinds
 * belong to LocalNotificationService; HABIT_REMINDER belongs to
 * HabitReminderScheduler and is listed here only because it shares
 * the posting path. Cancelling the four kinds never cancels a habit
 * reminder: the cancel paths use these exact names, never a prefix.
 */
enum class NotificationKind(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val notificationId: Int,
    val uniqueWorkName: String
) {
    STREAK(
        channelId = "pf.notify.streak",
        channelName = "Streak nudges",
        channelDescription = "A short note about your learning streak.",
        notificationId = 1001,
        uniqueWorkName = "pf.notify.streak"
    ),
    CONTINUE_LEARNING(
        channelId = "pf.notify.continue",
        channelName = "Continue learning",
        channelDescription = "A reminder to pick up where you left off.",
        notificationId = 1002,
        uniqueWorkName = "pf.notify.continue"
    ),
    NEW_CONTENT(
        channelId = "pf.notify.newcontent",
        channelName = "New lessons and cases",
        channelDescription = "A note when new lessons or cases are added.",
        notificationId = 1003,
        uniqueWorkName = "pf.notify.newcontent"
    ),
    OFFER(
        channelId = "pf.notify.offer",
        channelName = "Offers and announcements",
        channelDescription = "Offers and news from Payments Training Hub.",
        notificationId = 1004,
        uniqueWorkName = "pf.notify.offer"
    ),
    HABIT_REMINDER(
        channelId = "pf.learningHabit.reminder",
        channelName = "Habit reminders",
        channelDescription = "A gentle nudge at the time you chose.",
        notificationId = 1005,
        uniqueWorkName = "pf.learningHabit.reminder"
    );

    companion object {
        /** The four kinds owned by LocalNotificationService. */
        val localNotificationKinds: List<NotificationKind> =
            listOf(STREAK, CONTINUE_LEARNING, NEW_CONTENT, OFFER)

        fun fromName(name: String?): NotificationKind? =
            NotificationKind.entries.firstOrNull { it.name == name }
    }
}

/** Creates the notification channels and posts local notifications. */
object NotificationDisplay {

    /** The notification title for every kind, mirroring iOS schedule(). */
    const val TITLE = "Payments Training Hub"

    /**
     * Royal blue, the single primary (DesignSystem.swift pfRoyal,
     * #17408C). Kept as a literal ARGB value so background workers
     * do not depend on the Compose theme object.
     */
    private val ROYAL_ARGB: Int = 0xFF17408C.toInt()

    /** Create every channel that does not exist yet. Safe to call on every post. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (kind in NotificationKind.entries) {
            if (manager.getNotificationChannel(kind.channelId) != null) continue
            val channel = NotificationChannel(
                kind.channelId,
                kind.channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = kind.channelDescription
                // No badge, mirroring iOS: no badge is set on any request.
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Post one notification. The body is the exact copy decided at
     * schedule time; this function never edits copy and never adds
     * a price. The tap opens the deep-link route, or Home when the
     * route is not one of the two real routes.
     */
    fun post(context: Context, kind: NotificationKind, body: String, route: String?) {
        val appContext = context.applicationContext
        // Posting without the runtime permission throws on API 33+,
        // so check here even though the schedulers re-validate
        // before calling: a denied permission means no post, never
        // a crash. Below API 33 the permission does not exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannels(appContext)
        val notification = NotificationCompat.Builder(appContext, kind.channelId)
            .setSmallIcon(smallIconRes(appContext))
            .setContentTitle(TITLE)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(ROYAL_ARGB)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(appContext, kind, route))
            .build()
        NotificationManagerCompat.from(appContext).notify(kind.notificationId, notification)
    }

    /**
     * The tap intent for a route. Only a route that parses through
     * DeepLinkRouter becomes a deep-link intent; anything else,
     * including a null route, becomes the package launch intent,
     * which opens the app on the Home tab.
     */
    private fun contentIntent(
        context: Context,
        kind: NotificationKind,
        route: String?
    ): PendingIntent {
        val intent = deepLinkIntent(route) ?: launchIntent(context)
        return PendingIntent.getActivity(
            context,
            kind.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deepLinkIntent(route: String?): Intent? {
        if (route.isNullOrBlank()) return null
        // Validation only: the parsed link is not needed here, the
        // URL string itself is the intent data, and MainActivity
        // parses it again through DeepLinkRouter on receipt.
        if (DeepLinkRouter.parse(route) == null) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(route)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun launchIntent(context: Context): Intent {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: Intent(Intent.ACTION_MAIN).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(context.packageName)
            }
    }

    private fun smallIconRes(context: Context): Int {
        val branded = context.resources.getIdentifier(
            "ic_notification",
            "drawable",
            context.packageName
        )
        if (branded != 0) return branded
        val appIcon = context.applicationInfo.icon
        if (appIcon != 0) return appIcon
        return android.R.drawable.ic_dialog_info
    }
}
