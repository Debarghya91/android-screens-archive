// AudioPlaybackServiceTest.kt
// Wave 3 proven-crash proofs: the playback service calls
// startForeground() synchronously in onCreate, before any
// player or session work, and re-promotes on every
// onStartCommand. The ordering is proven through the
// CrashBreadcrumbs record: "foreground promoted" must precede
// "service created", which is recorded only after the player
// and session are built. Repeated starts, the scroll re-entry
// shape from the owner's crash, keep the foreground
// notification posted without exception. The service is driven
// through Robolectric's ServiceController, so the real
// onCreate and onStartCommand run.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.media.AudioPlaybackService
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.security.CrashBreadcrumbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class AudioPlaybackServiceTest {

    private var controller: org.robolectric.android.controller.ServiceController<AudioPlaybackService>? = null

    @Before
    fun clearBreadcrumbs() {
        CrashBreadcrumbs.clear()
    }

    @After
    fun destroyService() {
        runCatching { controller?.destroy() }
        controller = null
        runCatching { AudioPlayer.stop() }
        CrashBreadcrumbs.clear()
    }

    @Test
    fun foregroundIsPromotedSynchronouslyOnCreateBeforePlayerWork() {
        controller = Robolectric.buildService(AudioPlaybackService::class.java)
        val service = controller!!.create().get()
        val shadow = shadowOf(service)
        // Media3's own notification id: the playback
        // notification replaces this one in place later.
        assertEquals(1001, shadow.lastForegroundNotificationId)
        assertNotNull(shadow.lastForegroundNotification)
        // Ordering: the promotion breadcrumb precedes the
        // "service created" breadcrumb, which is recorded only
        // after the player and the session are built. Nothing
        // slow runs ahead of the foreground call.
        val crumbs = CrashBreadcrumbs.snapshot()
        val promotedAt = crumbs.indexOf("foreground promoted")
        val createdAt = crumbs.indexOf("service created")
        assertTrue("foreground promotion was not recorded", promotedAt >= 0)
        assertTrue("service creation was not recorded", createdAt >= 0)
        assertTrue(
            "startForeground must run before player/session work",
            promotedAt < createdAt
        )
    }

    @Test
    fun repeatedStartsKeepForegroundPostedWithoutException() {
        controller = Robolectric.buildService(AudioPlaybackService::class.java)
        val service = controller!!.create().get()
        // The scroll re-entry shape: the service is started
        // again and again while already running. Every start
        // re-promotes before Media3 sees the intent.
        repeat(5) {
            controller!!.startCommand(0, 0)
        }
        val shadow = shadowOf(service)
        assertEquals(1001, shadow.lastForegroundNotificationId)
        assertNotNull(shadow.lastForegroundNotification)
        assertTrue(
            CrashBreadcrumbs.snapshot().count { it == "foreground promoted" } >= 5
        )
    }
}
