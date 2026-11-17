package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import com.paymentstraininghub.app.ui.navigation.NavigationDecision
import com.paymentstraininghub.app.ui.navigation.NavigationEventValidator
import com.paymentstraininghub.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the one-shot deep-link validation rules
 * (solved-APK defect 2): consumed ids, expiry, recovery
 * precedence, unknown routes, and start-destination handling.
 * No Android dependency: Routes.isKnownRoute is pure string
 * matching.
 */
class NavigationEventValidatorTest {

    private val nowMs = 1_700_000_000_000L

    private fun linkEvent(
        id: Long,
        receivedAtMs: Long = nowMs,
        lessonId: String = "ch1s1"
    ) = DeepLinkRouter.PendingEvent.Link(
        TrainingHubDeepLink.Lesson(lessonId),
        id,
        receivedAtMs
    )

    private fun decide(
        event: DeepLinkRouter.PendingEvent,
        lastConsumedId: Long = 0L,
        now: Long = nowMs,
        startDestination: String = Routes.HOME,
        recoveryTokenPending: Boolean = false,
        isKnownRoute: (String) -> Boolean = Routes::isKnownRoute
    ) = NavigationEventValidator.decide(
        event,
        lastConsumedId,
        now,
        startDestination,
        recoveryTokenPending,
        isKnownRoute
    )

    @Test
    fun freshLink_navigatesToItsRoute() {
        val decision = decide(linkEvent(id = 1L))
        assertEquals(NavigationDecision.Navigate("lesson/ch1s1"), decision)
    }

    @Test
    fun weeklyCaseLink_navigatesToWeeklyCase() {
        val event = DeepLinkRouter.PendingEvent.Link(
            TrainingHubDeepLink.WeeklyFreeCase,
            1L,
            nowMs
        )
        assertEquals(NavigationDecision.Navigate("weekly-case"), decide(event))
    }

    @Test
    fun recoveryEvent_navigatesToResetPassword() {
        val event = DeepLinkRouter.PendingEvent.Recovery("tok", 1L, nowMs)
        assertEquals(NavigationDecision.Navigate("reset-password"), decide(event))
    }

    @Test
    fun duplicateId_isDropped() {
        val decision = decide(linkEvent(id = 5L), lastConsumedId = 5L)
        assertTrue(decision is NavigationDecision.Drop)
        assertTrue((decision as NavigationDecision.Drop).reason.contains("duplicate-or-stale"))
    }

    @Test
    fun staleIdBelowConsumed_isDropped() {
        val decision = decide(linkEvent(id = 3L), lastConsumedId = 9L)
        assertTrue(decision is NavigationDecision.Drop)
    }

    @Test
    fun expiredEvent_isDropped() {
        val old = nowMs - NavigationEventValidator.EVENT_TTL_MS - 1_000L
        val decision = decide(linkEvent(id = 1L, receivedAtMs = old))
        assertTrue(decision is NavigationDecision.Drop)
        assertTrue((decision as NavigationDecision.Drop).reason.contains("expired"))
    }

    @Test
    fun eventAtTtlBoundary_navigates() {
        val edge = nowMs - NavigationEventValidator.EVENT_TTL_MS
        val decision = decide(linkEvent(id = 1L, receivedAtMs = edge))
        assertEquals(NavigationDecision.Navigate("lesson/ch1s1"), decision)
    }

    @Test
    fun linkWhileRecoveryPending_isDropped() {
        val decision = decide(linkEvent(id = 1L), recoveryTokenPending = true)
        assertTrue(decision is NavigationDecision.Drop)
        assertTrue(
            (decision as NavigationDecision.Drop).reason.contains("recovery-takes-precedence")
        )
    }

    @Test
    fun recoveryWhileRecoveryPending_stillNavigates() {
        val event = DeepLinkRouter.PendingEvent.Recovery("tok", 1L, nowMs)
        val decision = decide(event, recoveryTokenPending = true)
        assertEquals(NavigationDecision.Navigate("reset-password"), decision)
    }

    @Test
    fun unknownRoute_isDropped() {
        val decision = decide(
            linkEvent(id = 1L),
            isKnownRoute = { false }
        )
        assertTrue(decision is NavigationDecision.Drop)
        assertTrue((decision as NavigationDecision.Drop).reason.contains("unknown-route"))
    }

    @Test
    fun startDestinationRoute_isConsumedWithoutNavigating() {
        val decision = decide(
            linkEvent(id = 1L),
            startDestination = "lesson/ch1s1"
        )
        assertTrue(decision is NavigationDecision.Drop)
        assertTrue(
            (decision as NavigationDecision.Drop).reason.contains("already-start-destination")
        )
    }

    @Test
    fun duplicateCheckBeatsExpiry() {
        // A replayed old event reports as a duplicate, not as
        // expired: the id check runs first, so replays are
        // recognised as replays in the Diagnostics log.
        val old = nowMs - NavigationEventValidator.EVENT_TTL_MS - 1_000L
        val decision = decide(linkEvent(id = 2L, receivedAtMs = old), lastConsumedId = 2L)
        assertTrue(
            (decision as NavigationDecision.Drop).reason.contains("duplicate-or-stale")
        )
    }
}
