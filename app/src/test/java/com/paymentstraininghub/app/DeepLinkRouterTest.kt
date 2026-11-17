package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deep-link parsing and routing tests. The router is pure string
 * parsing plus in-memory state, so it runs on the JVM without
 * Android. State is cleared before each test because the router
 * is a shared singleton.
 */
class DeepLinkRouterTest {

    @Before
    fun clearRouterState() {
        DeepLinkRouter.clear()
        DeepLinkRouter.clearRecoveryToken()
    }

    @Test
    fun parse_lessonLinkWithQueryId() {
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://lesson?id=ch1s1")
        assertEquals(TrainingHubDeepLink.Lesson("ch1s1"), link)
    }

    @Test
    fun parse_lessonLinkWithPathId() {
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://lesson/ch10s3")
        assertEquals(TrainingHubDeepLink.Lesson("ch10s3"), link)
    }

    @Test
    fun parse_lessonLinkRejectsMissingOrUnsafeId() {
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://lesson"))
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://lesson?id="))
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://lesson?id=ch1s1/x"))
    }

    @Test
    fun parse_weeklyCaseLink() {
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://weekly-case")
        assertEquals(TrainingHubDeepLink.WeeklyFreeCase, link)
    }

    @Test
    fun parse_resetLinkWithRecoveryToken() {
        val link = DeepLinkRouter.parse(
            "com.paymentstraininghub.app://reset-password#access_token=abc123&type=recovery"
        )
        assertEquals(TrainingHubDeepLink.ResetPassword("abc123"), link)
    }

    @Test
    fun parse_resetLinkRejectsOtherTypesAndMissingToken() {
        assertNull(
            DeepLinkRouter.parse(
                "com.paymentstraininghub.app://reset-password#access_token=abc123&type=signup"
            )
        )
        assertNull(
            DeepLinkRouter.parse("com.paymentstraininghub.app://reset-password#type=recovery")
        )
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://reset-password"))
        assertNull(
            DeepLinkRouter.parse(
                "com.paymentstraininghub.app://reset-password#access_token=&type=recovery"
            )
        )
    }

    @Test
    fun parse_oauthCallbackLink() {
        // The Google sign-in return link is a known host: it
        // parses to the OAuth callback, routed to the sign-in
        // screen, which consumes the code.
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://auth-callback?code=x")
        assertEquals(TrainingHubDeepLink.OAuthCallback(code = "x", error = null), link)
        assertEquals("auth", DeepLinkRouter.routeFor(link!!))
    }

    @Test
    fun parse_rejectsUnknownHostsAndSchemes() {
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://settings"))
        assertNull(DeepLinkRouter.parse("https://www.paymentstraininghub.com/lessons/ch1s1"))
        assertNull(DeepLinkRouter.parse(""))
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://LESSON?id=ch1s1".replace("LESSON", "other")))
    }

    @Test
    fun routeFor_mapsEveryLinkKind() {
        assertEquals(
            "lesson/ch1s1",
            DeepLinkRouter.routeFor(TrainingHubDeepLink.Lesson("ch1s1"))
        )
        assertEquals(
            "weekly-case",
            DeepLinkRouter.routeFor(TrainingHubDeepLink.WeeklyFreeCase)
        )
        assertEquals(
            "reset-password",
            DeepLinkRouter.routeFor(TrainingHubDeepLink.ResetPassword("tok"))
        )
    }

    @Test
    fun receiveUri_routesContentLinkToOneShotEvent() = runBlocking {
        assertTrue(DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch2s1"))
        val event = withTimeout(2_000) { DeepLinkRouter.receiveEvent() }
        assertEquals(
            TrainingHubDeepLink.Lesson("ch2s1"),
            (event as DeepLinkRouter.PendingEvent.Link).link
        )
        assertNull(DeepLinkRouter.pendingRecoveryToken.value)
        // One-shot: a second receiver gets nothing, ever.
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveEvent() })
    }

    @Test
    fun receiveUri_routesResetLinkToTokenPlusRecoveryEvent() = runBlocking {
        assertTrue(
            DeepLinkRouter.receiveUri(
                "com.paymentstraininghub.app://reset-password#access_token=tok99&type=recovery"
            )
        )
        assertEquals("tok99", DeepLinkRouter.pendingRecoveryToken.value)
        val event = withTimeout(2_000) { DeepLinkRouter.receiveRecoveryEvent() }
        assertEquals("tok99", event.token)
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveRecoveryEvent() })
        // A reset link never enters the content-link stream.
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveEvent() })
    }

    @Test
    fun receiveUri_returnsFalseForNonLinks() = runBlocking {
        assertFalse(DeepLinkRouter.receiveUri("https://example.com/"))
        assertNull(DeepLinkRouter.pendingRecoveryToken.value)
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveEvent() })
    }

    @Test
    fun clearRecoveryToken_clearsOnlyTheToken() = runBlocking {
        DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch1s1")
        DeepLinkRouter.receiveUri(
            "com.paymentstraininghub.app://reset-password#access_token=tok&type=recovery"
        )
        DeepLinkRouter.clearRecoveryToken()
        assertNull(DeepLinkRouter.pendingRecoveryToken.value)
        // Both events are still waiting, each on its own stream.
        val linkEvent = withTimeout(2_000) { DeepLinkRouter.receiveEvent() }
        assertEquals(
            TrainingHubDeepLink.Lesson("ch1s1"),
            (linkEvent as DeepLinkRouter.PendingEvent.Link).link
        )
        val recoveryEvent = withTimeout(2_000) { DeepLinkRouter.receiveRecoveryEvent() }
        assertEquals("tok", recoveryEvent.token)
    }

    @Test
    fun clear_drainsPendingEvents() = runBlocking {
        DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch1s1")
        DeepLinkRouter.receiveUri(
            "com.paymentstraininghub.app://reset-password#access_token=tok&type=recovery"
        )
        DeepLinkRouter.clear()
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveEvent() })
        assertNull(withTimeoutOrNull(200) { DeepLinkRouter.receiveRecoveryEvent() })
    }

    @Test
    fun receive_assignsIncreasingIds() = runBlocking {
        DeepLinkRouter.receive(TrainingHubDeepLink.Lesson("ch1s1"))
        DeepLinkRouter.receive(TrainingHubDeepLink.WeeklyFreeCase)
        val events = withTimeout(2_000) {
            listOf(DeepLinkRouter.receiveEvent(), DeepLinkRouter.receiveEvent())
        }
        assertTrue(events[1].id > events[0].id)
        assertTrue(events[0].receivedAtMs > 0)
    }
}
