// AccessPolicyTest.kt
// Pure JVM tests for the access-model decisions: the main graph
// start destination for a cold-start deep link, and the
// purchase-route rule that prompts sign-in only on Buy actions
// while signed out. No Android dependency, no Robolectric. No em
// dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.navigation.AccessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    // Main graph start destination: the cold-start link when it
    // names a known route, Home otherwise. Content links land
    // directly, with no session.

    @Test
    fun mainStartDestination_plainLaunchStartsOnHome() {
        assertEquals("home", AccessPolicy.mainStartDestination(null))
    }

    @Test
    fun mainStartDestination_lessonLinkStartsOnTheLesson() {
        assertEquals("lesson/ch1s1", AccessPolicy.mainStartDestination("lesson/ch1s1"))
    }

    @Test
    fun mainStartDestination_weeklyCaseLinkStartsOnTheWeeklyCase() {
        assertEquals("weekly-case", AccessPolicy.mainStartDestination("weekly-case"))
    }

    @Test
    fun mainStartDestination_homeSectionLinkIsKept() {
        assertEquals(
            "home?section=courses",
            AccessPolicy.mainStartDestination("home?section=courses")
        )
    }

    @Test
    fun mainStartDestination_situationAndCheckoutLinksAreKept() {
        assertEquals(
            "situation/ABH-01",
            AccessPolicy.mainStartDestination("situation/ABH-01")
        )
        assertEquals(
            "checkout/com.paymentstraininghub.app.part-1",
            AccessPolicy.mainStartDestination("checkout/com.paymentstraininghub.app.part-1")
        )
    }

    @Test
    fun mainStartDestination_resetLinkStartsOnTheResetScreen() {
        assertEquals("reset-password", AccessPolicy.mainStartDestination("reset-password"))
    }

    @Test
    fun mainStartDestination_authLinkStartsOnTheSignInScreen() {
        assertEquals("auth", AccessPolicy.mainStartDestination("auth"))
    }

    @Test
    fun mainStartDestination_unknownRouteFallsBackToHome() {
        assertEquals("home", AccessPolicy.mainStartDestination("bogus/route"))
        assertEquals("home", AccessPolicy.mainStartDestination(""))
    }

    // Purchase routes: the checkout list and one product's
    // checkout only. Nothing else ever prompts sign-in.

    @Test
    fun isPurchaseRoute_checkoutListAndProductCheckoutOnly() {
        assertTrue(AccessPolicy.isPurchaseRoute("checkout"))
        assertTrue(AccessPolicy.isPurchaseRoute("checkout/com.paymentstraininghub.app.part-1"))
        assertFalse(AccessPolicy.isPurchaseRoute("home"))
        assertFalse(AccessPolicy.isPurchaseRoute("lesson/ch1s1"))
        assertFalse(AccessPolicy.isPurchaseRoute("situation/ABH-01"))
        assertFalse(AccessPolicy.isPurchaseRoute("auth"))
        assertFalse(AccessPolicy.isPurchaseRoute("checkoutx"))
        assertFalse(AccessPolicy.isPurchaseRoute(""))
    }

    // Purchase attempts: sign-in only while signed out.

    @Test
    fun routeForPurchaseAttempt_signedOutBuyOpensSignIn() {
        assertEquals("auth", AccessPolicy.routeForPurchaseAttempt("checkout", signedIn = false))
        assertEquals(
            "auth",
            AccessPolicy.routeForPurchaseAttempt(
                "checkout/com.paymentstraininghub.app.part-1",
                signedIn = false
            )
        )
    }

    @Test
    fun routeForPurchaseAttempt_signedInBuyOpensCheckout() {
        assertEquals("checkout", AccessPolicy.routeForPurchaseAttempt("checkout", signedIn = true))
        assertEquals(
            "checkout/com.paymentstraininghub.app.part-1",
            AccessPolicy.routeForPurchaseAttempt(
                "checkout/com.paymentstraininghub.app.part-1",
                signedIn = true
            )
        )
    }

    @Test
    fun routeForPurchaseAttempt_contentRoutesNeverPrompt() {
        assertEquals(
            "lesson/ch1s1",
            AccessPolicy.routeForPurchaseAttempt("lesson/ch1s1", signedIn = false)
        )
        assertEquals("home", AccessPolicy.routeForPurchaseAttempt("home", signedIn = false))
        assertEquals("search", AccessPolicy.routeForPurchaseAttempt("search", signedIn = false))
        assertEquals("cases", AccessPolicy.routeForPurchaseAttempt("cases", signedIn = false))
        assertEquals("auth", AccessPolicy.routeForPurchaseAttempt("auth", signedIn = false))
    }
}
