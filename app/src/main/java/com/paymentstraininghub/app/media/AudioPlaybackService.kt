package com.paymentstraininghub.app.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.paymentstraininghub.app.R
import com.paymentstraininghub.app.security.CrashBreadcrumbs
import com.paymentstraininghub.app.security.EventLog

/**
 * Background playback service for lesson narration.
 *
 * Holds a MediaSession bound to the shared ExoPlayer from [AudioPlayer],
 * so the same playback is driven from the lesson view, the notification,
 * lock-screen controls and headset buttons. The notification is provided
 * by Media3's default provider, with play, pause and stop actions, on the
 * "playback" notification channel.
 *
 * Foreground promotion (proven crash fix, wave 3): the service MUST
 * call startForeground() synchronously, FIRST, in onCreate and on
 * every onStartCommand, before any player or session work. The
 * owner's phone proved the old shape fatal: startForegroundService()
 * was called, but the service relied on Media3 promoting it only
 * once playback started, and a slow or aborted play path meant no
 * foreground call inside the system window, so Android killed the
 * app with RemoteServiceException. The early notification uses
 * Media3's own notification id, so Media3's playback notification
 * replaces it in place on the same id, and Media3 still starts
 * and stops foreground mode for ongoing playback afterwards.
 *
 * The manifest declaration for this service is owned by the app scaffold.
 * The service never releases the shared player: the player is a singleton
 * owned by [AudioPlayer] and survives the service being destroyed.
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createPlaybackChannel()
        // Foreground FIRST, before the player or the session is
        // built: nothing slow may run ahead of this call.
        promoteToForeground()
        AudioTrace.log("SERVICE_CREATE_ENTER", detail = "session=$SESSION_ID")
        // Explicit small icon (audit fix 8): Media3's bundled
        // default is fine, but a branded icon removes any
        // OEM-specific doubt for one line. setSmallIcon lives on
        // the provider, not the Builder, in Media3 1.3.1.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)
        // The alive flag goes up BEFORE the player is built: a
        // releaseIfIdle running between getPlayer and the flag
        // would otherwise release the just-built player while
        // the service is starting. If building the player or the
        // session throws, the flag is lowered again so a dead
        // service can never block idle release.
        AudioPlayer.notePlaybackServiceAlive(true)
        try {
            val player = AudioPlayer.getPlayer(this)
            mediaSession = MediaSession.Builder(this, player)
                .setId(SESSION_ID)
                .build()
        } catch (e: Exception) {
            AudioTrace.log(
                "SERVICE_CREATE_FAILED",
                detail = "session=$SESSION_ID",
                throwable = e
            )
            AudioPlayer.notePlaybackServiceAlive(false)
            throw e
        }
        CrashBreadcrumbs.record("service created")
        EventLog.record("AUDIO", "SERVICE_CREATED session=$SESSION_ID thread=${Thread.currentThread().name}")
        AudioTrace.log("SERVICE_CREATE_EXIT", detail = "session=$SESSION_ID")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-promote on EVERY start, before Media3 sees the
        // intent. A service started while already running (a
        // scroll re-entry, a second play tap) carries a fresh
        // system deadline when Media3 has stopped foreground
        // mode after playback stopped; promoting here keeps the
        // crash impossible on every path, not just the first
        // start. Same id as Media3's notification, so a live
        // playback notification is only briefly replaced by the
        // placeholder until Media3 posts its next update.
        promoteToForeground()
        AudioTrace.log(
            "SERVICE_START",
            detail = "startId=$startId action=${intent?.action ?: "?"} session=$SESSION_ID"
        )
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * The synchronous foreground promotion: a plain notification
     * on the playback channel, posted with Media3's own
     * notification id ([DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID],
     * 1001, verified against the Media3 1.3.1 bytecode), so the
     * playback notification Media3 posts later replaces this
     * one in place and Media3's stop and cancel lifecycle
     * manages the same notification. No player, session,
     * network or coroutine work runs here or before it. The
     * breadcrumb records the promotion for the ordering test
     * and for Diagnostics: it must precede "service created".
     */
    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payments Training Hub")
            .setContentText("Getting the lesson ready...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        val notificationId = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(notificationId, notification)
        }
        CrashBreadcrumbs.record("foreground promoted")
        EventLog.record("AUDIO", "FOREGROUND_PROMOTED notificationId=$notificationId thread=${Thread.currentThread().name}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        AudioTrace.log("SERVICE_DESTROY_ENTER", detail = "session=$SESSION_ID")
        // Ownership (solved-APK defect 1): releasing the session
        // must not release the shared player. The session is
        // released here; the player stays owned by AudioPlayer,
        // which is only notified that the service is going away
        // and decides about release itself. Nothing in this app
        // calls stopSelf on this service: the system and
        // MediaSessionService own its lifetime, and this
        // teardown line is the stop record.
        mediaSession?.release()
        mediaSession = null
        AudioPlayer.notePlaybackServiceAlive(false)
        CrashBreadcrumbs.record("service destroyed")
        EventLog.record("AUDIO", "SERVICE_DESTROYED session=$SESSION_ID thread=${Thread.currentThread().name}")
        AudioTrace.log("SERVICE_DESTROY_EXIT", detail = "session=$SESSION_ID")
        super.onDestroy()
    }

    private fun createPlaybackChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Lesson narration playback controls"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val SESSION_ID = "paymentstraininghub-playback"
    }
}
