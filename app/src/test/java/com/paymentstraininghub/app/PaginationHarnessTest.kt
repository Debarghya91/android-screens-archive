// PaginationHarnessTest.kt
// Numbered-pagination interaction proofs with full-size fake
// data (881 lessons, 616 cases): Previous and Next step through
// pages and disable at the ends, tapping a number jumps, a page
// change returns the list to the top, and a filter or query
// change resets to page 1. No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.ui.cases.SituationsScreen
import com.paymentstraininghub.app.ui.search.SearchScreen
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PaginationHarnessTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.installFullSize()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    private fun advanceSearchDebounce() {
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule.waitForIdle()
        Thread.sleep(300L)
        composeRule.waitForIdle()
    }

    private fun scrollIndexUntilVisible(matcher: SemanticsMatcher, maxIndex: Int = 80): Boolean {
        val list = composeRule.onNode(hasScrollToIndexAction())
        for (index in 0..maxIndex) {
            if (composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                return true
            }
            try {
                list.performScrollToIndex(index)
            } catch (e: IllegalArgumentException) {
                break
            }
            composeRule.waitForIdle()
        }
        return composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Tap a pagination-bar control. Existence in the semantics
     * tree is not enough: the bar may be composed but below the
     * fold, and a tap on an off-screen node silently does
     * nothing. Scroll until the control is composed, then scroll
     * it fully into view, then tap.
     */
    private fun tapPagination(tag: String) {
        assertTrue(scrollIndexUntilVisible(hasTestTag(tag)))
        composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun casesPreviousDisabledOnFirstPageAndNextDisabledOnLastPage() {
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        assertTrue(scrollIndexUntilVisible(hasTestTag("pagination-previous")))
        composeRule.onNodeWithTag("pagination-previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("pagination-next").assertIsEnabled()
        tapPagination("pagination-page-31")
        settle()
        assertTrue(scrollIndexUntilVisible(hasTestTag("pagination-next")))
        composeRule.onNodeWithTag("pagination-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("pagination-previous").assertIsEnabled()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 601-616 of 616 cases")))
        composeRule.onNodeWithText("Showing 601-616 of 616 cases").assertExists()
    }

    @Test
    fun casesNextAndPreviousStepThroughPages() {
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        tapPagination("pagination-next")
        settle()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 21-40 of 616 cases")))
        tapPagination("pagination-next")
        settle()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 41-60 of 616 cases")))
        tapPagination("pagination-previous")
        settle()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 21-40 of 616 cases")))
    }

    @Test
    fun casesPageChangeReturnsTheListToTheTop() {
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        tapPagination("pagination-page-2")
        settle()
        // The list header is the first item: it is displayed only
        // when the list has returned to the top.
        composeRule.onNode(hasText("real-world situations", substring = true)).assertIsDisplayed()
    }

    @Test
    fun casesFilterChangeResetsToPageOne() {
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        tapPagination("pagination-page-3")
        settle()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 41-60 of 616 cases")))
        // CASE-003 is the only full-size situation whose id
        // contains this query: the generated ids run CASE-003 to
        // CASE-616 (the first two slots hold small-list cases),
        // so CASE-001 and CASE-002 match nothing here.
        composeRule.onNode(hasSetTextAction()).performTextInput("CASE-003")
        settle()
        // One case matches, on page 1, with no pagination bar.
        // The range line is the last list item and sits below
        // the fold until scrolled into view, so scroll first.
        assertTrue(scrollIndexUntilVisible(hasText("Showing 1 of 1 case")))
        composeRule.onNodeWithText("Showing 1 of 1 case").assertExists()
        assertTrue(
            composeRule.onAllNodes(hasTestTag("pagination-next"))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun searchPageChangeReturnsTheListToTheTop() {
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("payment")
        advanceSearchDebounce()
        settle()
        tapPagination("pagination-page-2")
        settle()
        // The range line is the first item: it is displayed only
        // when the list has returned to the top.
        composeRule.onNode(hasText("Showing 21-40 of", substring = true)).assertIsDisplayed()
        tapPagination("pagination-previous")
        settle()
        composeRule.onNode(hasText("Showing 1-20 of", substring = true)).assertIsDisplayed()
    }

    @Test
    fun searchQueryChangeResetsToPageOne() {
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("payment")
        advanceSearchDebounce()
        settle()
        tapPagination("pagination-page-2")
        settle()
        composeRule.onNode(hasText("Showing 21-40 of", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("settlement")
        advanceSearchDebounce()
        settle()
        composeRule.onNode(hasText("Showing 1-20 of", substring = true)).assertIsDisplayed()
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
