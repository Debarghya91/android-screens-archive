package com.paymentstraininghub.app.ui.navigation

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptionsBuilder
import com.paymentstraininghub.app.security.EventLog

/**
 * One tracing wrapper for every navigation the graph makes,
 * plus a destination-change listener. Every line carries the
 * source call site, the requested route, the route the user
 * was on, the timestamp, and a snapshot of the back stack, so
 * a redirect seen on a device can be traced to the exact
 * trigger. Lines go to the Diagnostics event log (bounded,
 * shown in Diagnostics and appended to crash reports) and to
 * logcat under the same tag, so an emulator reproduction can
 * capture them with `adb logcat -s NavTrace`.
 *
 * This adds logging only: it never changes a route, an option,
 * or a timing. The destination listener also fires for Back
 * presses and pops, which are not wrapped, so those are traced
 * too. No user content is recorded: routes, argument keys and
 * short argument values, and state flags only.
 */
object NavTrace {

    const val TAG = "NavTrace"

    /**
     * Log and perform one navigation. [source] names the trigger
     * (BOTTOM_BAR_*, APP_DRAWER_TAB_*, HOME_PROGRESS_CARD,
     * CONTENT_NAV, DEEPLINK_*, AFTER_AUTH_*, ...). [detail]
     * carries extra context, such as a sign-in redirect.
     */
    fun navigate(
        navController: NavController,
        source: String,
        route: String,
        detail: String = "",
        builder: NavOptionsBuilder.() -> Unit = {}
    ) {
        val current = navController.currentBackStackEntry?.destination?.route ?: "?"
        record(
            "NAVIGATE src=$source route=$route from=$current " +
                "stack=[${stackSnapshot(navController)}]" +
                if (detail.isNotEmpty()) " $detail" else ""
        )
        navController.navigate(route, builder)
    }

    /** Log a navigation that was deliberately dropped, with the reason. */
    fun logDrop(source: String, route: String, reason: String) {
        record("NAVIGATE_DROPPED src=$source route=$route reason=$reason")
    }

    /**
     * Install the destination-change listener. It logs every
     * destination the graph lands on, with its arguments and a
     * back-stack snapshot. Returns the listener so the caller
     * can remove it on dispose.
     */
    fun install(navController: NavController): NavController.OnDestinationChangedListener {
        val listener = object : NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: Bundle?
            ) {
                val args = arguments?.keySet()?.joinToString(",") { key ->
                    "$key=${arguments.get(key).toString().take(24)}"
                }.orEmpty()
                record(
                    "DESTINATION route=${destination.route ?: "?"} args=[$args] " +
                        "stack=[${stackSnapshot(controller)}]"
                )
            }
        }
        navController.addOnDestinationChangedListener(listener)
        return listener
    }

    /** The current back stack as route patterns, oldest first. Never throws. */
    fun stackSnapshot(navController: NavController): String =
        runCatching {
            navController.currentBackStack.value.joinToString(">") {
                it.destination.route ?: "?"
            }
        }.getOrDefault("?")

    private fun record(message: String) {
        val line = "elapsedMs=${SystemClock.elapsedRealtime()} " +
            "thread=${Thread.currentThread().name} $message"
        EventLog.record(TAG, line)
        Log.d(TAG, line)
    }
}
