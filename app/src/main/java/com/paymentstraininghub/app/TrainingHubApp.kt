package com.paymentstraininghub.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.SupplementalLessons
import com.paymentstraininghub.app.features.settings.AppearanceStore
import com.paymentstraininghub.app.features.studio.StudioTeachingStore
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.security.CrashCapture
import com.paymentstraininghub.app.security.SecurityMonitor
import com.paymentstraininghub.app.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application startup, in the order the stores require:
 *
 * 1. [AppStore.init]: every store throws until storage is
 *    initialised.
 * 2. [SyncService.initialize]: creates the durable pending queue
 *    and loads the persisted last-sync time.
 * 3. [SyncService.lessonIdsProvider]: the known lesson ids from
 *    the catalogue, so local completion and quiz reads can
 *    enumerate lessons. The snapshot starts empty and fills when
 *    the search index first loads; sync tolerates an empty set
 *    (server-to-local import still runs).
 * 4. [AuthManager.onDeleteAccountLocal]: after the server confirms
 *    an account deletion, pending sync work is dropped on the
 *    application scope, which survives navigation and Activity
 *    recreation. This assignment used to live in a composition
 *    effect; it belongs here.
 * 5. [AuthManager.init] on the application scope: restores the
 *    session from encrypted storage and refreshes it when needed.
 * 6. [SyncService.startForegroundSync]: syncs on foreground
 *    returns when work is pending or the last sync is stale.
 * 7. Best-effort catalogue warm-up, so the lesson-id snapshot and
 *    the Home screen are ready early. Failures are ignored: every
 *    screen loads the index on demand with its own retry.
 *
 * Nothing here touches the UI or the billing client: billing
 * starts from the navigation graph, and notifications initialise
 * there too, matching the iOS app launch order.
 */
class TrainingHubApp : Application() {

    /** Application-lifetime scope: survives Activity recreation. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Crash capture first, before any other init: even an
        // early startup crash must leave a report (audit fix 7).
        CrashCapture.install(this)
        // Soft device-trust reporting: log + Diagnostics view
        // only, never blocking. Runs on its own background scope.
        SecurityMonitor.refresh(this)
        AppStore.init(this)
        SupplementalLessons.init(this)
        // Decode the bundled reading-order map over the embedded
        // copy, off the main thread. The embedded copy is already in
        // force from class load, so display numbers work even
        // before this finishes; any failure keeps the embedded map.
        applicationScope.launch {
            runCatching { ReadingOrder.initFromAsset(this@TrainingHubApp) }
        }
        StudioTeachingStore.init(this)
        AppearanceStore.init(this)
        SyncService.initialize(this)
        SyncService.lessonIdsProvider = { ContentRepository.knownLessonIdsSnapshot() }
        AuthManager.onDeleteAccountLocal = {
            applicationScope.launch { SyncService.clearForAccountDeletion() }
        }
        applicationScope.launch { AuthManager.init(this@TrainingHubApp) }
        SyncService.startForegroundSync()
        applicationScope.launch {
            runCatching { ContentRepository.loadSearchIndex() }
        }
    }

    /**
     * Memory pressure (audit fix 2): drop the reloadable
     * in-memory caches and release the player when it is idle.
     * TRIM_MEMORY_RUNNING_CRITICAL and above covers critical
     * pressure, backgrounding, and the higher trim levels. The
     * player is never released while playing, and releaseIfIdle
     * refuses while a MediaSession holds the player.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.i(TAG, "onTrimMemory level=$level: dropping caches, releasing idle player")
            ContentRepository.dropMemoryCaches()
            AudioPlayer.releaseIfIdle()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.i(TAG, "onLowMemory: dropping caches, releasing idle player")
        ContentRepository.dropMemoryCaches()
        AudioPlayer.releaseIfIdle()
    }

    companion object {
        private const val TAG = "TrainingHubApp"
    }
}
