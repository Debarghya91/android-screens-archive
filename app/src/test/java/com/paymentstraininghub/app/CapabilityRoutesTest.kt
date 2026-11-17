// CapabilityRoutesTest.kt
// Unit tests for the Home capabilities list: every entry links to
// a known route, home-section links use valid section keys, and no
// route is listed twice in the same group.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.home.CAPABILITY_GROUPS
import com.paymentstraininghub.app.ui.home.allCapabilityRoutes
import com.paymentstraininghub.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRoutesTest {

    private val validSections = setOf(
        Routes.HOME_SECTION_CONTINUE,
        Routes.HOME_SECTION_STATUS,
        Routes.HOME_SECTION_JOURNEYS,
        Routes.HOME_SECTION_COURSES,
        Routes.HOME_SECTION_CATALOGUE,
        Routes.HOME_SECTION_CAPABILITIES
    )

    @Test
    fun everyCapabilityRouteIsKnown() {
        allCapabilityRoutes().forEach { route ->
            assertTrue("unknown route: $route", Routes.isKnownRoute(route))
        }
    }

    @Test
    fun homeSectionLinksUseValidSections() {
        allCapabilityRoutes()
            .filter { it.startsWith("home?") }
            .forEach { route ->
                val section = route.substringAfter("section=")
                assertTrue("unknown section: $section", section in validSections)
            }
    }

    @Test
    fun everyGroupHasItemsAndNoGroupRepeatsARoute() {
        assertTrue(CAPABILITY_GROUPS.isNotEmpty())
        CAPABILITY_GROUPS.forEach { group ->
            assertTrue(group.title, group.items.isNotEmpty())
            assertEquals(
                group.items.map { it.route }.toSet().size,
                group.items.size
            )
            group.items.forEach { item ->
                assertTrue(item.title, item.title.isNotBlank())
                assertTrue(item.title, item.subtitle.isNotBlank())
            }
        }
    }

    @Test
    fun capabilitiesCoverTheWholeProduct() {
        val routes = allCapabilityRoutes().toSet()
        // Lessons, journeys, cases, simulator, studio, practice,
        // interview prep, audio, search, purchases, progress.
        assertTrue(Routes.homeSection(Routes.HOME_SECTION_COURSES) in routes)
        assertTrue(Routes.homeSection(Routes.HOME_SECTION_JOURNEYS) in routes)
        assertTrue(Routes.CASES in routes)
        assertTrue(Routes.SIMULATOR in routes)
        assertTrue(Routes.STUDIO in routes)
        assertTrue(Routes.PRACTICE in routes)
        assertTrue(Routes.INTERVIEW in routes)
        assertTrue(Routes.SEARCH in routes)
        assertTrue(Routes.CHECKOUT in routes)
        assertTrue(Routes.PROGRESS in routes)
        assertTrue(Routes.ISO in routes)
        assertTrue(Routes.LAB in routes)
        assertTrue(Routes.DAILY_CHALLENGE in routes)
        assertTrue(Routes.WEEKLY_CASE in routes)
        assertTrue(Routes.PRIVACY in routes)
        assertTrue(Routes.TERMS in routes)
    }
}
