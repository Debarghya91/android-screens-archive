// Wave2HarnessTest.kt
// Wave-2 interaction tests with realistic data shapes: thumbnails
// rendering with agendas loaded in every list, pagination with
// full-size data (881 lessons, 616 cases), the Recommended vs
// journey agreement, the dual-format lesson
// timings, the play-then-pause audio path, empty-state survival,
// and top-on-reentry. Runs on SDK 28 and 36; the full render
// sweep across all four SDK levels stays in RenderHarnessTest.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.ActivePathStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ReadTime
import com.paymentstraininghub.app.data.readingText
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.ui.cases.SituationDetailScreen
import com.paymentstraininghub.app.ui.cases.SituationsScreen
import com.paymentstraininghub.app.ui.checkout.CheckoutScreen
import com.paymentstraininghub.app.ui.home.HomeScreen
import com.paymentstraininghub.app.ui.learn.ChapterDetailScreen
import com.paymentstraininghub.app.ui.learn.GuideListScreen
import com.paymentstraininghub.app.ui.learn.PathDetailScreen
import com.paymentstraininghub.app.ui.learn.SessionDetailScreen
import com.paymentstraininghub.app.ui.lesson.LessonScreen
import com.paymentstraininghub.app.ui.progress.ProgressScreen
import com.paymentstraininghub.app.ui.search.SearchScreen
import com.paymentstraininghub.app.ui.theme.PfTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = Application::class)
class Wave2HarnessTest {

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
        runBlocking { ActivePathStore.setActive(null) }
        runCatching { AudioPlayer.stop() }
    }

    // Thumbnail tags live inside clickable Cards, whose merged
    // semantics hide them: query the unmerged tree.
    private fun artworkCount(): Int =
        composeRule.onAllNodesWithTag("thumbnailArtwork", useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun fallbackCount(): Int =
        composeRule.onAllNodesWithTag("thumbnailFallback", useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    /**
     * The search debounce is a 150ms delay: the compose test
     * clock does not advance it on its own under Robolectric,
     * so advance it explicitly after typing.
     */
    private fun advanceSearchDebounce() {
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule.waitForIdle()
        Thread.sleep(300L)
        composeRule.waitForIdle()
    }

    /**
     * A slow half-viewport drag. The default fast swipeUp flings
     * several viewports per gesture, so settled positions skip
     * over mid-list items entirely; a slow drag steps through
     * the list without momentum overshoot.
     */
    /**
     * Step through the list one index at a time until a node
     * matching [matcher] is composed. Index scrolling is
     * deterministic where gestures are not: the test viewport
     * fits only ~2 list items, so a drag barely moves and a
     * fling overshoots. The sweep ends when the target is
     * found or the index runs past the last item (the scroll
     * action throws out-of-bounds, which ends the sweep).
     */
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

    private fun slowSwipeUp(list: SemanticsNodeInteraction) {
        list.performTouchInput {
            swipeUp(startY = height * 0.75f, endY = height * 0.25f, durationMillis = 600)
        }
        composeRule.waitForIdle()
    }

    /**
     * Swipe up until a node with [text] is composed, or the list
     * ends. Lazy lists only compose visible items, so a node
     * below the fold does not exist until it scrolls into view.
     */
    private fun scrollUntilVisible(text: String, maxSwipes: Int = 40): Boolean {
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(maxSwipes) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                return true
            }
            slowSwipeUp(list)
        }
        return composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollUntilSubstringVisible(substring: String, maxSwipes: Int = 40): Boolean {
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(maxSwipes) {
            if (composeRule.onAllNodes(hasText(substring, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                return true
            }
            slowSwipeUp(list)
        }
        return composeRule.onAllNodes(hasText(substring, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
    }

    // Thumbnails with agendas loaded.

    @Test
    fun thumbnailsRenderWithAgendasOnHome() {
        RenderFakeData.installAgendas()
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // Course parts live on the Courses tab since wave 3:
        // one tap switches, then the part rows render.
        composeRule.onNodeWithTag("home-tab-courses").performClick()
        composeRule.waitForIdle()
        assertTrue(scrollUntilVisible("Course 1: Foundations of Money and Payments"))
        // Rows composed by scrolling load their thumbnails
        // asynchronously: let the loads land before counting.
        settle()
        assertTrue("expected artwork tiles on Home, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInPathDetail() {
        RenderFakeData.installAgendas()
        composeRule.setContent {
            PfTheme { PathDetailScreen(pathId = "fundamentals", onBack = {}, onNavigate = {}) }
        }
        settle()
        // Sweep the whole screen slowly: lesson rows compose as
        // they scroll into view and their thumbnails load
        // asynchronously, so track the most artwork seen at any
        // settled position rather than one snapshot.
        val list = composeRule.onNode(hasScrollToIndexAction())
        var maxArtwork = artworkCount()
        repeat(30) {
            slowSwipeUp(list)
            Thread.sleep(250L)
            composeRule.waitForIdle()
            maxArtwork = maxOf(maxArtwork, artworkCount())
        }
        assertTrue("expected artwork tiles in PathDetail, found $maxArtwork", maxArtwork > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInSessionDetail() {
        RenderFakeData.installAgendas()
        composeRule.setContent {
            PfTheme { SessionDetailScreen(sessionId = 1, onBack = {}, onNavigate = {}) }
        }
        settle()
        assertTrue(scrollUntilVisible("Up next"))
        settle()
        assertTrue("expected artwork tiles in SessionDetail, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInChapterDetail() {
        RenderFakeData.installAgendas()
        composeRule.setContent {
            PfTheme { ChapterDetailScreen(chapterNumber = 1, onBack = {}, onNavigate = {}) }
        }
        settle()
        assertTrue(scrollUntilVisible("What a payment is"))
        settle()
        assertTrue("expected artwork tiles in ChapterDetail, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInSearch() {
        RenderFakeData.installAgendas()
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("payment")
        advanceSearchDebounce()
        settle()
        assertTrue("expected artwork tiles in Search, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInCases() {
        RenderFakeData.installAgendas()
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        assertTrue("expected artwork tiles in Cases, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun thumbnailsRenderWithAgendasInCaseDetail() {
        RenderFakeData.installAgendas()
        composeRule.setContent {
            PfTheme { SituationDetailScreen(situationId = "ABH-01", onBack = {}, onNavigate = {}) }
        }
        settle()
        assertTrue("expected artwork tiles in CaseDetail, found ${artworkCount()}", artworkCount() > 0)
    }

    @Test
    fun libraryScreensRenderFallbackTilesWithoutThumbnailData() {
        // Guides carry no thumbnail in the agendas data: the icon
        // fallback must render through ThumbnailTile, never a
        // blank or a crash.
        RenderFakeData.installAgendas()
        composeRule.setContent { PfTheme { GuideListScreen(onBack = {}, onNavigate = {}) } }
        settle()
        assertTrue("expected fallback tiles in GuideList, found ${fallbackCount()}", fallbackCount() > 0)
        assertEquals(0, artworkCount())
    }

    // Pagination with full-size data.

    @Test
    fun casesPaginationShowsTwentyThenTurnsThePage() {
        RenderFakeData.installFullSize()
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        assertTrue(scrollIndexUntilVisible(hasText("Showing 1-20 of 616 cases")))
        composeRule.onNodeWithText("Showing 1-20 of 616 cases").assertExists()
        assertTrue(scrollIndexUntilVisible(hasTestTag("pagination-page-2")))
        composeRule.onNodeWithTag("pagination-page-2").performClick()
        settle()
        // The page change returns the list to the top, which
        // disposes the bottom items: step through again until
        // the new range line composes.
        assertTrue(scrollIndexUntilVisible(hasText("Showing 21-40 of 616 cases")))
        composeRule.onNodeWithText("Showing 21-40 of 616 cases").assertExists()
    }

    @Test
    fun searchPaginationShowsTwentyThenTurnsThePage() {
        RenderFakeData.installFullSize()
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("payment")
        advanceSearchDebounce()
        settle()
        assertTrue(
            composeRule.onAllNodes(hasText("Showing 1-20 of", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
        // The pagination bar sits near the end of the list:
        // step through the list until it composes, then tap
        // page 2. The page change returns the list to the top,
        // so the range line is visible again at once.
        assertTrue(scrollIndexUntilVisible(hasTestTag("pagination-page-2")))
        composeRule.onNodeWithTag("pagination-page-2").performClick()
        settle()
        assertTrue(
            composeRule.onAllNodes(hasText("Showing 21-40 of", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun casesCategoryDrawerFiltersTheList() {
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNodeWithContentDescription("Categories").performClick()
        composeRule.waitForIdle()
        Thread.sleep(500L)
        composeRule.waitForIdle()
        // The list's stage header carries the same string, so
        // match the clickable drawer row, not the header.
        composeRule.onNode(
            hasText("Stage 1: Payment mechanics", substring = true) and hasClickAction()
        ).performClick()
        settle()
        // The filtered rows sit below the fold (the test
        // viewport fits only the list header): step through
        // the list until the case row composes.
        assertTrue(
            scrollIndexUntilVisible(
                hasText("Returned payment: closed beneficiary account", substring = true)
            )
        )
        // Two stage-1 cases in the fake data; paging resets on
        // the filter change, so the whole filtered list shows.
        assertTrue(scrollIndexUntilVisible(hasText("Showing 1-2 of 2 cases")))
        composeRule.onNodeWithText("Showing 1-2 of 2 cases").assertExists()
    }

    @Test
    fun searchAllFiltersSelectedStaysUnfiltered() {
        RenderFakeData.installFullSize()
        composeRule.setContent { PfTheme { SearchScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNode(hasSetTextAction()).performTextInput("payment")
        advanceSearchDebounce()
        settle()
        // Select every type filter: all-selected must mean
        // unfiltered (the wave-1 fix), not zero results. The
        // chips sit in a FlowRow, not a scrollable: click them
        // directly, no scroll-to.
        listOf("Lessons", "Situations", "Guides", "FAQs", "Labs", "Tests").forEach { label ->
            composeRule.onNodeWithText(label).performClick()
            composeRule.waitForIdle()
        }
        settle()
        // The pagination bar sits near the end of the list:
        // step through the list until it composes.
        assertTrue(
            "all-filters search must still page results",
            scrollIndexUntilVisible(hasTestTag("pagination-page-2"))
        )
    }

    // Recommended vs journey agreement.

    @Test
    fun homeHasNoRecommendedSectionWithoutAJourney() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // The journeys group sits directly under the greeting, so
        // Continue learning is below the fold on first paint:
        // scroll to it before asserting it is displayed.
        assertTrue(scrollUntilVisible("Continue learning"))
        composeRule.onNodeWithText("Continue learning").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Recommended for you")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun homeNextLessonAgreesWithTheChosenJourney() {
        runBlocking { ActivePathStore.setActive("fundamentals") }
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // The Continue block names the journey's next lesson and
        // says so; the journey card shows as "Your journey".
        // Order matters for a lazy list: assert the absence on
        // the first paint, then scroll down in screen order,
        // asserting each block while it is composed.
        assertTrue(
            composeRule.onAllNodesWithText("Recommended for you")
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(scrollUntilVisible("Your journey"))
        assertTrue(
            composeRule.onAllNodesWithText("Your journey")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            scrollUntilSubstringVisible("Next in your journey: Payments Fundamentals.")
        )
        assertTrue(
            composeRule.onAllNodes(hasText("Next in your journey: Payments Fundamentals.", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    // Timings: dual format on the lesson screen.

    @Test
    fun lessonScreenShowsListenAndReadTimes() {
        composeRule.setContent { PfTheme { LessonScreen(lessonId = "ch1s1") } }
        settle()
        val detail = RenderFakeData.lessonDetail("ch1s1")
        val readMinutes = ReadTime.minutesForText(detail.readingText())
        assertTrue(
            composeRule.onAllNodes(hasText("Listening length:", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onNodeWithText("Reading time: about $readMinutes min").assertExists()
    }

    @Test
    fun caseScreensShowNoReadTimeLabel() {
        // Case texts collapse to one minute value, so the label
        // was removed rather than stamped on every case.
        composeRule.setContent { PfTheme { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        assertTrue(
            composeRule.onAllNodes(hasText("min read", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    // Audio: the reported play-then-pause path through the floating button.

    @Test
    fun audioPlayThenPauseThroughFabSurvives() {
        composeRule.setContent { PfTheme { LessonScreen(lessonId = "ch1s1") } }
        settle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        // Past the roughly 2 second window in the crash report.
        Thread.sleep(3000L)
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag("lessonAudioFab").assertExists()
    }

    // Crash sweep: empty states, expansion, top-on-reentry.

    @Test
    fun emptyStatesRenderWithoutCrashing() {
        RenderFakeData.installEmpty()
        val screens: List<@Composable () -> Unit> = listOf(
            { HomeScreen(onNavigate = {}, onOpenDrawer = {}) },
            { SearchScreen(onNavigate = {}, onOpenDrawer = {}) },
            { SituationsScreen(onNavigate = {}, onOpenDrawer = {}) },
            { ProgressScreen(onNavigate = {}, onOpenDrawer = {}) },
            { PathDetailScreen(pathId = "fundamentals", onBack = {}, onNavigate = {}) },
            { SessionDetailScreen(sessionId = 1, onBack = {}, onNavigate = {}) },
            { ChapterDetailScreen(chapterNumber = 1, onBack = {}, onNavigate = {}) },
            { GuideListScreen(onBack = {}, onNavigate = {}) },
            { CheckoutScreen(productId = null, onBack = {}) },
            // Unknown ids must render an error state, never crash.
            { PathDetailScreen(pathId = "no-such-journey", onBack = {}, onNavigate = {}) },
            { SessionDetailScreen(sessionId = 999, onBack = {}, onNavigate = {}) },
            { ChapterDetailScreen(chapterNumber = 999, onBack = {}, onNavigate = {}) },
            { SituationDetailScreen(situationId = "NOPE", onBack = {}, onNavigate = {}) }
        )
        // One setContent per test (the rule forbids a second):
        // switch screens through state, keyed so each screen
        // gets a fresh composition.
        var index by mutableStateOf(0)
        composeRule.setContent { PfTheme { key(index) { screens[index]() } } }
        screens.indices.forEach { i ->
            if (i > 0) {
                composeRule.runOnIdle { index = i }
            }
            composeRule.waitForIdle()
            Thread.sleep(300L)
            composeRule.waitForIdle()
            composeRule.onRoot().assertExists()
        }
    }

    @Test
    fun homeCourseExpansionRendersChapters() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // Course parts live on the Courses tab since wave 3.
        composeRule.onNodeWithTag("home-tab-courses").performClick()
        composeRule.waitForIdle()
        assertTrue(scrollUntilVisible("Course 1: Foundations of Money and Payments"))
        composeRule.onNodeWithText("Course 1: Foundations of Money and Payments").performClick()
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("Chapter 1")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun homeReentryOpensAtTheTop() {
        // One setContent: re-entry is a keyed recomposition,
        // which disposes the old subtree exactly as leaving
        // and re-entering the tab does.
        var visit by mutableStateOf(0)
        composeRule.setContent { PfTheme { key(visit) { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } } }
        settle()
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(4) {
            list.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        // Re-entering Home (fresh composition, as on Back or a
        // tab switch) must land at the top, never mid-list.
        composeRule.runOnIdle { visit = 1 }
        settle()
        composeRule.onNodeWithText("Master the world of payments, one lesson at a time.")
            .assertIsDisplayed()
    }

    @Test
    fun homeShowsJourneyExplainerAndPracticeTab() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // The journey explainer stays on the Learning Journey
        // tab; the capability list is gone from Home entirely.
        assertTrue(scrollUntilVisible("What is a learning journey?"))
        assertTrue(
            composeRule.onAllNodesWithText("Everything inside")
                .fetchSemanticsNodes().isEmpty()
        )
        // The practice capabilities moved to the Practice tab:
        // one tap switches, and the Simulator row is there.
        composeRule.onNodeWithTag("home-tab-practice").performClick()
        composeRule.waitForIdle()
        // CapabilityRow is a Card: its title merges with the
        // subtitle, so match by substring.
        assertTrue(scrollUntilSubstringVisible("Payment Simulator"))
        assertTrue(
            composeRule.onAllNodes(hasText("Payment Simulator", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
