package com.paymentstraininghub.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import com.paymentstraininghub.app.ui.navigation.AppNavGraph
import com.paymentstraininghub.app.ui.theme.PfTheme

/**
 * The single Activity. It renders the navigation graph inside the
 * app theme and owns deep-link intake: a cold-start link becomes
 * the graph's start route, and a link that arrives while the app
 * is running is forwarded through [DeepLinkRouter] for the graph's
 * collectors to route.
 *
 * Intent data is cleared after it is consumed, so an Activity
 * recreation (for example rotation) never re-fires the same link:
 * without that, a completed password reset would yank the learner
 * back to the reset screen on rotation, because the launching
 * intent still carries the reset link.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE: block screenshots and screen recording of
        // lesson content. This is the only Activity, so one flag
        // covers the whole app. It does not block accessibility
        // services or the system share sheet.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        val initialRoute = consumeLaunchDeepLink(intent)
        setContent {
            PfTheme {
                AppNavGraph(initialRoute = initialRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uriString = intent.data?.toString() ?: return
        DeepLinkRouter.receiveUri(uriString)
        intent.data = null
    }

    /**
     * The start route for a cold-start deep link, or null for a
     * plain launch. A reset link also records its token with the
     * router before routing, because the set-new-password screen
     * reads the token from the router (never from the intent) and
     * clears it after one read. Content links are NOT recorded
     * here: the start route already lands on them, and recording
     * would route them a second time.
     */
    private fun consumeLaunchDeepLink(intent: Intent?): String? {
        val uriString = intent?.data?.toString() ?: return null
        val link = DeepLinkRouter.parse(uriString)
        intent.data = null
        if (link == null) return null
        if (link is TrainingHubDeepLink.ResetPassword) {
            DeepLinkRouter.receive(link)
        }
        if (link is TrainingHubDeepLink.OAuthCallback) {
            // The code is recorded for the sign-in screen, and the
            // launch stays plain: the code waits in the router
            // until the sign-in screen opens and consumes it.
            // Routing anywhere else would strand the callback
            // behind content navigation.
            DeepLinkRouter.receive(link)
            return null
        }
        return DeepLinkRouter.routeFor(link)
    }
}
