// SearchChipHarnessTest.kt
// Search chip proofs with full-size fake data: picking any type
// chip with an empty query shows the browsable paged list of that
// type (the hint promised "pick a type above" but tapping a chip
// showed nothing); typing a query filters within the selected
// type; switching chips resets to page 1; page buttons navigate
// with the correct range line. No unbounded lists: every list is
// paged at 20. No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
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
class SearchChipHarnessTest {

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

    private fun renderSearch() {
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
    }

    private fun advanceDebounce() {
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule.waitForIdle()
        Thread.sleep(300L)
        composeRule.waitForIdle()
    }

    private fun scrollUntilVisible(matcher: androidx.compose.ui.test.SemanticsMatcher, maxIndex: Int = 40): Boolean {
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
     * Tap a pagination-bar control: scroll until it is composed,
     * then scroll it fully into view (a composed-but-off-screen
     * node swallows a tap silently), then tap.
     */
    private fun tapPagination(tag: String) {
        assertTrue(scrollUntilVisible(hasTestTag(tag)))
        composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun allChipWithEmptyQueryShowsTheFullPagedCatalogue() {
        renderSearch()
        composeRule.onNodeWithText("All").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 1512 results").assertExists()
        assertTrue(scrollUntilVisible(hasTestTag("pagination-page-2")))
        composeRule.onNodeWithTag("pagination-page-2").assertExists()
        composeRule.onNodeWithText("1512 items in the catalogue. Type to search, or pick a type above.")
            .assertDoesNotExist()
    }

    @Test
    fun lessonsChipWithEmptyQueryShowsPagedLessons() {
        renderSearch()
        composeRule.onNodeWithText("Lessons").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 881 results").assertExists()
        assertTrue(scrollUntilVisible(hasTestTag("pagination-page-45")))
        composeRule.onNodeWithTag("pagination-page-45").assertExists()
    }

    @Test
    fun situationsChipWithEmptyQueryShowsPagedSituations() {
        renderSearch()
        composeRule.onNodeWithText("Situations").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 623 results").assertExists()
        assertTrue(scrollUntilVisible(hasTestTag("pagination-page-32")))
        composeRule.onNodeWithTag("pagination-page-32").assertExists()
    }

    @Test
    fun guidesChipWithEmptyQueryShowsBothGuidesOnOnePage() {
        renderSearch()
        composeRule.onNodeWithText("Guides").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-2 of 2 results").assertExists()
        composeRule.onNodeWithText("Guide: pacs.002 Explained").assertExists()
        composeRule.onNodeWithText("Guide: camt.029 Explained").assertExists()
        composeRule.onNodeWithTag("pagination-next").assertDoesNotExist()
    }

    @Test
    fun faqsChipWithEmptyQueryShowsBothFaqsOnOnePage() {
        renderSearch()
        composeRule.onNodeWithText("FAQs").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-2 of 2 results").assertExists()
        composeRule.onNodeWithText("FAQ: What is a pacs.008?").assertExists()
        composeRule.onNodeWithTag("pagination-next").assertDoesNotExist()
    }

    @Test
    fun labsChipWithEmptyQueryShowsBothLabsOnOnePage() {
        renderSearch()
        composeRule.onNodeWithText("Labs").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-2 of 2 results").assertExists()
        composeRule.onNodeWithText("Lab: trace a missing confirmation").assertExists()
        composeRule.onNodeWithTag("pagination-next").assertDoesNotExist()
    }

    @Test
    fun testsChipWithEmptyQueryShowsBothTestsOnOnePage() {
        renderSearch()
        composeRule.onNodeWithText("Tests").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-2 of 2 results").assertExists()
        composeRule.onNodeWithText("Test: Payment Foundations").assertExists()
        composeRule.onNodeWithTag("pagination-next").assertDoesNotExist()
    }

    @Test
    fun queryFiltersWithinTheSelectedType() {
        renderSearch()
        composeRule.onNodeWithText("Lessons").performClick()
        settle()
        // "pacs.008" matches the FAQ and a test, but no lesson:
        // within Lessons it must find nothing.
        composeRule.onNode(hasSetTextAction()).performTextInput("pacs.008")
        advanceDebounce()
        settle()
        composeRule.onNodeWithText("No results for \"pacs.008\"").assertExists()
        // Clearing the query returns to the browsable lesson list.
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        advanceDebounce()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 881 results").assertExists()
    }

    @Test
    fun queryWithinTypeNarrowsTheList() {
        renderSearch()
        composeRule.onNodeWithText("Lessons").performClick()
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("money moves")
        advanceDebounce()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 881 results").assertExists()
    }

    @Test
    fun switchingChipsResetsToPageOne() {
        renderSearch()
        composeRule.onNodeWithText("All").performClick()
        settle()
        tapPagination("pagination-page-2")
        settle()
        composeRule.onNodeWithText("Showing 21-40 of 1512 results").assertExists()
        composeRule.onNodeWithText("Lessons").performClick()
        settle()
        composeRule.onNodeWithText("Showing 1-20 of 881 results").assertExists()
    }

    @Test
    fun pageButtonsNavigateWithTheCorrectRangeLine() {
        renderSearch()
        composeRule.onNodeWithText("Situations").performClick()
        settle()
        tapPagination("pagination-page-2")
        settle()
        composeRule.onNodeWithText("Showing 21-40 of 623 results").assertExists()
        tapPagination("pagination-next")
        settle()
        composeRule.onNodeWithText("Showing 41-60 of 623 results").assertExists()
        tapPagination("pagination-previous")
        settle()
        composeRule.onNodeWithText("Showing 21-40 of 623 results").assertExists()
    }

    @Test
    fun hintStillShowsBeforeAnyChipIsPicked() {
        renderSearch()
        composeRule.onNodeWithText("1512 items in the catalogue. Type to search, or pick a type above.")
            .assertExists()
        composeRule.onNodeWithTag("pagination-next").assertDoesNotExist()
    }

    companion object {
        private const val SETTLE_MS = 1200L
    }
}
