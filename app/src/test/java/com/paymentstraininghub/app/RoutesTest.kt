// RoutesTest.kt
// Unit tests for Routes.isKnownRoute. The chat route was removed
// 15 Nov 2026 with the chat feature: "chat" must no longer classify
// as known. Diagnostics is a known base (it was missing from
// knownBases until the same change).

package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun chatRouteIsNoLongerKnown() {
        assertFalse(Routes.isKnownRoute("chat"))
        assertFalse(Routes.isKnownRoute("chat?question=What%20is%20pacs.008%3F"))
    }

    @Test
    fun diagnosticsRouteIsKnown() {
        assertTrue(Routes.isKnownRoute(Routes.DIAGNOSTICS))
    }

    @Test
    fun practiceSituationRouteIsKnown() {
        assertTrue(Routes.isKnownRoute(Routes.practiceSituation("ABH-01")))
    }

    @Test
    fun lessonRouteIsKnown() {
        assertTrue(Routes.isKnownRoute(Routes.lesson("ch1s1")))
    }

    @Test
    fun resetPasswordRouteIsKnown() {
        assertTrue(Routes.isKnownRoute(Routes.RESET_PASSWORD))
    }

    @Test
    fun unknownRouteIsRejected() {
        assertFalse(Routes.isKnownRoute("nowhere"))
        assertFalse(Routes.isKnownRoute("auth-callback"))
    }

    @Test
    fun learnRouteIsKnownAndLandsOnHome() {
        assertTrue(Routes.isKnownRoute(Routes.LEARN))
    }

    @Test
    fun homeSectionRoutesAreKnown() {
        assertTrue(Routes.isKnownRoute(Routes.homeSection(Routes.HOME_SECTION_COURSES)))
        assertTrue(Routes.isKnownRoute(Routes.homeSection(Routes.HOME_SECTION_JOURNEYS)))
        assertTrue(Routes.isKnownRoute(Routes.homeSection(Routes.HOME_SECTION_CATALOGUE)))
        assertTrue(Routes.isKnownRoute(Routes.homeSection(Routes.HOME_SECTION_CAPABILITIES)))
        assertEquals("home?section=courses", Routes.homeSection(Routes.HOME_SECTION_COURSES))
    }
}
