// SolvedApkHarnessTest.kt
// Regression tests for the three solved-APK defects, against the
// real AppNavGraph and the real LessonScreen with realistic fake
// content: the audio pause crash, the stale deep-link redirect,
// and the Cases fast-scroll landing. Runs on SDK 28 only: the
// audio test waits past the reported 2 second crash window, and
// the fling test runs 100 gestures.

package com.paymentstraininghub.app

import android.content.Context
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.tour.TourStore
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.security.EventLog
import com.paymentstraininghub.app.ui.cases.PointerDiagnostics
import com.paymentstraininghub.app.ui.lesson.LessonScreen
import com.paymentstraininghub.app.ui.navigation.AppNavGraph
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class SolvedApkHarnessTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.install()
        // Every test in this class renders the app behind a
        // session, matching the signed-in learner. Content
        // renders without a session too; the session keeps the
        // entitlement and sync paths in their signed-in shape.
        // The deep-link test's flip is the introduction
        // finishing.
        AuthManager.setTestAuthState(
            AuthManager.AuthState.SignedIn("test-user-id", "learner@example.com"),
            ready = true
        )
        EventLog.clear()
        PointerDiagnostics.clear()
        DeepLinkRouter.clear()
        DeepLinkRouter.clearRecoveryToken()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
        runCatching { AudioPlayer.stop() }
        AudioPlayer.pendingAutoPlayLessonId = null
        EventLog.clear()
        PointerDiagnostics.clear()
        DeepLinkRouter.clear()
        DeepLinkRouter.clearRecoveryToken()
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun markOnboardingAndTourDone() {
        TourStore.markOnboardingCompleted(context())
        TourStore.markTourDone(context())
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    private fun navLogCount(fragment: String): Int =
        EventLog.snapshot().count { it.tag == "NAV" && it.message.contains(fragment) }

    /**
     * Scroll the open drawer list until the My Progress row is
     * composed: it sits eighth in a lazy list whose viewport
     * shows one row in the 320x470 test window, so the finder
     * sees nothing until the list scrolls. The drags stay
     * entirely inside the list bounds (a drag starting below
     * the list never reaches it), stopping as soon as the row
     * appears, plus one short drag to bring it fully into view
     * before the tap.
     */
    private fun scrollDrawerToProgressRow() {
        repeat(12) {
            val found = composeRule.onAllNodesWithContentDescription(PROGRESS_ROW_DESCRIPTION)
                .fetchSemanticsNodes().isNotEmpty()
            if (found) {
                composeRule.onRoot().performTouchInput {
                    swipe(
                        Offset(center.x * 0.35f, center.y - 5f),
                        Offset(center.x * 0.35f, center.y - 65f),
                        durationMillis = 150
                    )
                }
                composeRule.waitForIdle()
                return
            }
            composeRule.onRoot().performTouchInput {
                swipe(
                    Offset(center.x * 0.35f, center.y - 5f),
                    Offset(center.x * 0.35f, center.y - 115f),
                    durationMillis = 200
                )
            }
            composeRule.waitForIdle()
        }
    }

    private fun assertStillOnCases() {
        assertTrue(
            composeRule.onAllNodesWithText("Payment Cases")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "My Progress content appeared",
            composeRule.onAllNodesWithText("See courses")
                .fetchSemanticsNodes().isEmpty()
        )
        assertEquals(0, navLogCount("route=progress"))
    }

    // Defect 1: audio play, pause from the floating button, wait.

    @Test
    fun audioFabPlayPauseWaitFiveSecondsSurvives() {
        composeRule.setContent { PfTheme { LessonScreen(lessonId = "ch1s1") } }
        settle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        // Past the roughly 2 second window in the crash report.
        Thread.sleep(5000L)
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag("lessonAudioFab").assertExists()
        assertTrue(
            "No PLAY_REQUESTED lifecycle entry was recorded",
            EventLog.snapshot().any { it.tag == "AUDIO" && it.message.contains("PLAY_REQUESTED") }
        )
    }

    @Test
    fun audioPauseThenReleaseAndServiceTeardownSurvives() {
        composeRule.setContent { PfTheme { LessonScreen(lessonId = "ch1s1") } }
        settle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        Thread.sleep(500L)
        // Memory pressure releases the idle player, and the
        // playback service is destroyed, while paused: the
        // use-after-release window the serialised release closes.
        AudioPlayer.releaseIfIdle()
        AudioPlayer.notePlaybackServiceAlive(false)
        Thread.sleep(3000L)
        composeRule.waitForIdle()
        // Resume rebuilds the player from the saved position.
        composeRule.onNodeWithTag("lessonAudioFab").performClick()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag("lessonAudioFab").assertExists()
    }

    @Test
    fun autoPlayHandoffIsConsumedExactlyOnce() {
        AudioPlayer.pendingAutoPlayLessonId = "ch1s1"
        composeRule.setContent { PfTheme { LessonScreen(lessonId = "ch1s1") } }
        settle()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        assertNull(
            "The autoplay handoff flag was not consumed",
            AudioPlayer.pendingAutoPlayLessonId
        )
        composeRule.onRoot().assertExists()
    }

    // Defect 2: one-shot deep links across the introduction
    // flip and collector recreation.

    @Test
    fun deepLinkDeliveredOnceAcrossGateFlipAndRecreation() {
        // Onboarding incomplete: fresh-shadow defaults. The
        // link arrives while the introduction shows.
        DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch2s1")
        val graphKey = mutableStateOf(0)
        composeRule.setContent {
            key(graphKey.value) { AppNavGraph(initialRoute = Routes.HOME) }
        }
        settle()
        composeRule.onNodeWithText("Welcome").assertExists()
        composeRule.onNodeWithText("Skip").performClick()
        Thread.sleep(1500L)
        composeRule.waitForIdle()
        assertEquals(
            "The deep link must navigate exactly once after the introduction finishes",
            1,
            navLogCount("DEEPLINK_RECEIVED route=lesson/ch2s1")
        )
        // Recreate the graph (and its collector): the consumed
        // event must never navigate again.
        composeRule.runOnUiThread { graphKey.value++ }
        settle()
        Thread.sleep(1000L)
        composeRule.waitForIdle()
        assertEquals(
            "A recreated collector re-navigated on a consumed event",
            1,
            navLogCount("DEEPLINK_RECEIVED route=lesson/ch2s1")
        )
    }

    // Defect 3: fast scrolling in Payment Cases, drawer and
    // bottom-bar navigation, Categories drawer.

    @Test
    fun hundredFastFlingsStayOnCases() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        assertStillOnCases()
        val root = composeRule.onRoot()
        // Flings start and end inside the Cases content area:
        // a root swipeUp starts on the bottom bar, outside the
        // observed Column, so the pointer diagnostic would see
        // nothing. These cross the list itself, like real flings.
        repeat(100) {
            root.performTouchInput {
                swipe(
                    Offset(center.x, center.y + 150f),
                    Offset(center.x, center.y - 150f),
                    durationMillis = 80
                )
            }
            composeRule.waitForIdle()
        }
        Thread.sleep(500L)
        composeRule.waitForIdle()
        assertStillOnCases()
        assertTrue(
            "No gestures were observed by the pointer diagnostic",
            PointerDiagnostics.snapshot().isNotEmpty()
        )
    }

    @Test
    fun edgeAndBottomFlingsStayOnCases() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        val root = composeRule.onRoot()
        // Flings starting at the left edge, where the outer
        // drawer gestures used to fight the list.
        repeat(10) {
            root.performTouchInput {
                swipe(
                    Offset(8f, center.y),
                    Offset(8f, center.y - 500f),
                    durationMillis = 80
                )
            }
            composeRule.waitForIdle()
        }
        // Flings starting just above the bottom bar. The scope
        // exposes no size: the root fills the window, so twice
        // the centre height is the bottom edge.
        repeat(10) {
            root.performTouchInput {
                swipe(
                    Offset(center.x, center.y * 2 - 20f),
                    Offset(center.x, center.y * 2 - 520f),
                    durationMillis = 80
                )
            }
            composeRule.waitForIdle()
        }
        Thread.sleep(500L)
        composeRule.waitForIdle()
        assertStillOnCases()
    }

    @Test
    fun drawerProgressSelectionNavigatesOnce() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        composeRule.onNodeWithContentDescription("Open menu").performClick()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        scrollDrawerToProgressRow()
        composeRule.onNodeWithContentDescription(
            PROGRESS_ROW_DESCRIPTION
        ).performClick()
        Thread.sleep(1000L)
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("See courses")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals(1, navLogCount("APP_DRAWER_TAB route=progress"))
    }

    @Test
    fun bottomBarProgressNavigatesOnce() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        // The tab label lives on the unmerged icon node: the
        // merged tree carries no node with this description.
        composeRule.onNodeWithContentDescription("My Progress", useUnmergedTree = true)
            .performClick()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("See courses")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals(1, navLogCount("BOTTOM_BAR_PROGRESS route=progress"))
    }

    @Test
    fun rapidRepeatedDrawerSelectionNavigatesOnce() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        composeRule.onNodeWithContentDescription("Open menu").performClick()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        scrollDrawerToProgressRow()
        val row = composeRule.onNodeWithContentDescription(
            PROGRESS_ROW_DESCRIPTION
        )
        row.performClick()
        // A second tap while the drawer is closing is either
        // dropped by the in-progress guard or lands on a node
        // that is already gone; either way it must not navigate.
        runCatching { row.performClick() }
        Thread.sleep(1000L)
        composeRule.waitForIdle()
        assertEquals(1, navLogCount("APP_DRAWER_TAB route=progress"))
    }

    @Test
    fun categoriesDrawerInteractionStaysOnCases() {
        markOnboardingAndTourDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        composeRule.onNodeWithContentDescription("Categories").performClick()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All cases").performClick()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        assertStillOnCases()
        assertTrue(
            navLogCount("CASES_CATEGORY_CLICK") >= 1
        )
    }

    private companion object {
        const val SETTLE_MS = 1200L
        const val PROGRESS_ROW_DESCRIPTION =
            "My Progress. Your learning analytics, streak and quiz scores"
    }
}
