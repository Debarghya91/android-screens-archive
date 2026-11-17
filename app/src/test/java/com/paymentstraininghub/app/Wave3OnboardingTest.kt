// Wave3OnboardingTest.kt
// First-run introduction proofs: four pages in order (why the
// app exists, who it is for, what it can do, what is free), each
// with a heading and headed sections, in simple terms with no
// questions; swipe-only navigation with tappable dots and no Next
// or Back buttons; the swipe hint shows on every page except the
// last; Skip finishes from any page; the last page finishes
// through Get started or the two content actions.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.onboarding.ONBOARDING_PAGES
import com.paymentstraininghub.app.features.onboarding.OnboardingFlowScreen
import com.paymentstraininghub.app.features.onboarding.SWIPE_HINT_DESCRIPTION
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class Wave3OnboardingPureTest {

    @Test
    fun fourPagesInOrderWithHeadings() {
        assertEquals(
            listOf(
                "Why this app",
                "Who it is for",
                "What it can do",
                "What is free"
            ),
            ONBOARDING_PAGES.map { it.heading }
        )
        for (page in ONBOARDING_PAGES) {
            assertTrue(page.intro.isNotBlank())
            assertTrue(page.sections.size >= 3)
            for ((title, body) in page.sections) {
                assertTrue(title.isNotBlank())
                assertTrue(body.isNotBlank())
            }
        }
    }

    @Test
    fun noPageAsksAQuestion() {
        for (page in ONBOARDING_PAGES) {
            val copy = page.heading + page.intro +
                page.sections.joinToString(" ") { (title, body) -> "$title $body" }
            assertFalse("page ${page.heading} asks a question", copy.contains("?"))
            assertFalse("page ${page.heading} uses an em dash", copy.contains("\u2014"))
        }
    }

    @Test
    fun contentsAreNamedAcrossPages() {
        val copy = ONBOARDING_PAGES.joinToString(" ") { page ->
            page.heading + " " + page.intro +
                " " + page.sections.joinToString(" ") { (title, body) -> "$title $body" }
        }
        assertTrue(copy.contains("881"))
        assertTrue(copy.contains("564"))
        assertTrue(copy.contains("500"))
        assertTrue(copy.contains("320"))
        assertTrue(copy.contains("72"))
        assertTrue(copy.contains("10 courses"))
        assertTrue(copy.contains("10 simulators"))
        assertTrue(copy.contains("100 chapters"))
    }

    @Test
    fun copyIsSignInFirstAware() {
        val copy = ONBOARDING_PAGES.joinToString(" ") { page ->
            page.heading + " " + page.intro +
                " " + page.sections.joinToString(" ") { (title, body) -> "$title $body" }
        }
        assertTrue(copy.contains("signed in"))
        assertFalse(copy.contains("never forces sign-in"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class Wave3OnboardingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.install()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
    }

    /**
     * Swipes the pager one page forward, like a learner's left
     * swipe. A bare swipeLeft() flings with enough velocity to
     * skip two pages, so the drag is slow and short: 160px over
     * 500ms clears the snap threshold (the page is 256px wide)
     * without fling momentum.
     */
    private fun swipeToNextPage() {
        composeRule.onNodeWithTag("onboarding-pager").performTouchInput {
            swipeLeft(startX = centerX + 80f, endX = centerX - 80f, durationMillis = 500)
        }
        composeRule.waitForIdle()
    }

    /** Swipes the pager one page back, with the same gentle drag. */
    private fun swipeToPreviousPage() {
        composeRule.onNodeWithTag("onboarding-pager").performTouchInput {
            swipeRight(startX = centerX - 80f, endX = centerX + 80f, durationMillis = 500)
        }
        composeRule.waitForIdle()
    }

    /**
     * Asserts the page is the centred, current page: the pager
     * peeks the neighbours at the edges, so their headings exist
     * in the tree too, and only the centred page's middle sits in
     * the middle half of the screen.
     */
    private fun assertCurrentPage(index: Int) {
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val pageBounds = composeRule.onNodeWithTag("onboarding-page-$index")
            .fetchSemanticsNode().boundsInRoot
        val centerX = pageBounds.center.x
        assertTrue(
            "page $index is not centred: bounds $pageBounds in root $rootBounds",
            centerX > rootBounds.width * 0.25f && centerX < rootBounds.width * 0.75f
        )
    }

    @Test
    fun pagesAdvanceInOrderThroughSwipe() {
        composeRule.setContent {
            PfTheme { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Why this app").assertIsDisplayed()
        assertCurrentPage(0)
        swipeToNextPage()
        composeRule.onNodeWithText("Who it is for").assertExists()
        assertCurrentPage(1)
        swipeToNextPage()
        composeRule.onNodeWithText("What it can do").assertExists()
        assertCurrentPage(2)
        swipeToPreviousPage()
        composeRule.onNodeWithText("Who it is for").assertExists()
        assertCurrentPage(1)
        swipeToNextPage()
        swipeToNextPage()
        composeRule.onNodeWithText("What is free").assertExists()
        assertCurrentPage(3)
        composeRule.onNodeWithTag("onboarding-page-3").assertExists()
    }

    @Test
    fun dotsJumpToPages() {
        composeRule.setContent {
            PfTheme { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding-dot-3").performClick()
        composeRule.waitForIdle()
        assertCurrentPage(3)
        composeRule.onNodeWithText("What is free").assertExists()
        composeRule.onNodeWithTag("onboarding-dot-0").performClick()
        composeRule.waitForIdle()
        assertCurrentPage(0)
        composeRule.onNodeWithText("Why this app").assertIsDisplayed()
    }

    @Test
    fun noNextOrBackButtonsExist() {
        composeRule.setContent {
            PfTheme { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
        }
        composeRule.waitForIdle()
        // Every page, including the last: swipe-only means no
        // Next and no Back button anywhere in the flow.
        repeat(4) { page ->
            composeRule.onNodeWithText("Next").assertDoesNotExist()
            composeRule.onNodeWithText("Back").assertDoesNotExist()
            if (page < 3) swipeToNextPage()
        }
    }

    @Test
    fun swipeHintShowsExceptOnTheLastPage() {
        composeRule.setContent {
            PfTheme { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(SWIPE_HINT_DESCRIPTION).assertExists()
        swipeToNextPage()
        composeRule.onNodeWithContentDescription(SWIPE_HINT_DESCRIPTION).assertExists()
        swipeToNextPage()
        swipeToNextPage()
        assertCurrentPage(3)
        composeRule.onNodeWithContentDescription(SWIPE_HINT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun skipFinishesFromAnyPage() {
        var finished = 0
        composeRule.setContent {
            PfTheme {
                OnboardingFlowScreen(
                    onFinish = { finished++ },
                    onFinishAndNavigate = {}
                )
            }
        }
        composeRule.waitForIdle()
        swipeToNextPage()
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun getStartedFinishesOnTheLastPage() {
        var finished = 0
        composeRule.setContent {
            PfTheme {
                OnboardingFlowScreen(
                    onFinish = { finished++ },
                    onFinishAndNavigate = {}
                )
            }
        }
        composeRule.waitForIdle()
        repeat(3) { swipeToNextPage() }
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun firstLessonActionNavigatesToTheFirstLesson() {
        var navigated: String? = null
        composeRule.setContent {
            PfTheme {
                OnboardingFlowScreen(
                    onFinish = {},
                    onFinishAndNavigate = { route -> navigated = route }
                )
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        repeat(3) { swipeToNextPage() }
        composeRule.onNodeWithText("Start with the first lesson").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "expected a lesson route, got $navigated",
            navigated != null && navigated!!.startsWith("lesson/")
        )
    }

    @Test
    fun noQuestionTextIsShownOnAnyPage() {
        composeRule.setContent {
            PfTheme { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
        }
        composeRule.waitForIdle()
        repeat(4) { page ->
            assertTrue(
                "question text on page $page",
                composeRule.onAllNodes(hasText("?", substring = true))
                    .fetchSemanticsNodes().isEmpty()
            )
            if (page < 3) {
                swipeToNextPage()
            }
        }
    }
}
