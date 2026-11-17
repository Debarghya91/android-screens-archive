// NavigationEvents.kt
// One-shot deep-link validation and navigation-source logging
// for the solved-APK defects. The validator is pure Kotlin with
// no Android dependency, so the stale/expired/duplicate rules
// are unit-tested on the JVM. Every navigation the graph makes
// on a user's behalf is logged with its source, so a redirect
// seen on a device can be traced to the exact trigger.

package com.paymentstraininghub.app.ui.navigation

import android.os.SystemClock
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.security.EventLog

/** Validation outcome for one pending deep-link event. */
sealed interface NavigationDecision {
    /** Route to [route]. The event is consumed exactly once. */
    data class Navigate(val route: String) : NavigationDecision

    /** Drop the event without navigating, for [reason]. */
    data class Drop(val reason: String) : NavigationDecision
}

object NavigationEventValidator {

    /**
     * How long a pending event stays routable. A link that arrives
     * while onboarding shows must survive the flow (a minute or
     * two); anything older than this is stale and must never
     * navigate, however it reached the consumer.
     */
    const val EVENT_TTL_MS = 30 * 60 * 1000L

    /**
     * Decide what to do with [event]. [lastConsumedId] is the id
     * of the most recently consumed event (0 when none has been
     * consumed); ids are monotonic, so anything at or below it is
     * a duplicate or a stale replay. [nowMs] is wall-clock time.
     * [startDestination] is the graph's start route: an event
     * whose route is the start destination is consumed without
     * navigating, because a cold-start link is already handled
     * through the launch intent. [recoveryTokenPending] drops
     * content links while a reset token waits, preserving the
     * recovery-takes-precedence rule. [isKnownRoute] validates
     * the destination.
     */
    fun decide(
        event: DeepLinkRouter.PendingEvent,
        lastConsumedId: Long,
        nowMs: Long,
        startDestination: String,
        recoveryTokenPending: Boolean,
        isKnownRoute: (String) -> Boolean
    ): NavigationDecision {
        if (event.id <= lastConsumedId) {
            return NavigationDecision.Drop("duplicate-or-stale id=${event.id}")
        }
        if (nowMs - event.receivedAtMs > EVENT_TTL_MS) {
            return NavigationDecision.Drop("expired id=${event.id}")
        }
        if (event is DeepLinkRouter.PendingEvent.Link && recoveryTokenPending) {
            return NavigationDecision.Drop("recovery-takes-precedence id=${event.id}")
        }
        val route = DeepLinkRouter.routeFor(event)
        if (!isKnownRoute(route)) {
            return NavigationDecision.Drop("unknown-route id=${event.id} route=$route")
        }
        if (route == startDestination) {
            return NavigationDecision.Drop("already-start-destination id=${event.id} route=$route")
        }
        return NavigationDecision.Navigate(route)
    }
}

/**
 * Log one navigation with its source, for the Diagnostics event
 * log. [source] names the trigger (BOTTOM_BAR_*, APP_DRAWER_*,
 * DEEPLINK_RECEIVED, DEEPLINK_RECOVERY_RECEIVED,
 * POST_ONBOARDING_NAVIGATION). [currentRoute]
 * is the destination the user was on when the navigation fired,
 * or null when unknown.
 */
fun logNavigationEvent(source: String, route: String, currentRoute: String?) {
    EventLog.record(
        "NAV",
        "$source route=$route from=${currentRoute ?: "?"} " +
            "elapsedMs=${SystemClock.elapsedRealtime()}"
    )
}
