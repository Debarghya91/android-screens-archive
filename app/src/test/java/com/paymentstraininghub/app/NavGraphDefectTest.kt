// NavGraphDefectTest.kt
// Navigation-defect reproduction against the real AppNavGraph:
// the Courses idle redirect and the Cases fast-scroll landing on
// My Progress, plus the Learn route landing on the merged Home.
// Runs on SDK 28 only: the idle test waits past the reported
// 10 to 20 second window.

package com.paymentstraininghub.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.tour.TourStore
import com.paymentstraininghub.app.ui.navigation.AppNavGraph
import com.paymentstraininghub.app.ui.navigation.Routes
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The graph enqueues notification work on launch. The manifest
 * disables the WorkManager initializer, so the test application
 * must supply the configuration (WorkManager 2.9 on-demand
 * initialization).
 */
class TestWorkApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class NavGraphDefectTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppStore.init(context)
        // Sign in and mark onboarding done so the tests see the
        // navigation graph itself, past the introduction.
        AuthManager.setTestAuthState(
            AuthManager.AuthState.SignedIn("test-user-id", "learner@example.com"),
            ready = true
        )
        TourStore.markOnboardingCompleted(context)
        TourStore.markTourDone(context)
        RenderFakeData.install()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    @Test
    fun coursesIdleForTwentyOneSecondsDoesNotRedirectHome() {
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CHECKOUT) }
        settle()
        composeRule.onNodeWithText("Courses").assertExists()
        // Idle past the reported 10 to 20 second window. No
        // input, no navigation: the Courses page must still be
        // showing, and Home must not have appeared.
        Thread.sleep(21_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Courses").assertExists()
        assertTrue(
            "Home greeting appeared after idle on Courses",
            composeRule.onAllNodesWithText("Master the world of payments, one lesson at a time.")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun casesFastScrollDoesNotLandOnMyProgress() {
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        assertTrue(
            composeRule.onAllNodesWithText("Payment Cases")
                .fetchSemanticsNodes().isNotEmpty()
        )
        // Swipe on the root: the graph also composes the closed
        // app drawer (a second scrollable), so the list cannot
        // be picked by its scroll action alone. A centre swipe
        // lands on the Cases list, as on the device.
        val root = composeRule.onRoot()
        // Ten fast flings, the reported gesture: the destination
        // must stay on Payment Cases throughout.
        repeat(10) {
            root.performTouchInput { swipeUp(durationMillis = 80) }
            composeRule.waitForIdle()
        }
        Thread.sleep(500L)
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("Payment Cases")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "My Progress content appeared after fast scrolling in Cases",
            composeRule.onAllNodesWithText("See courses")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun learnRouteLandsOnHomeCoursesSection() {
        composeRule.setContent { AppNavGraph(initialRoute = Routes.LEARN) }
        settle()
        // The Learn route renders the merged Home scrolled to
        // the courses section: the Courses header is displayed
        // and the Home top bar is showing.
        assertTrue(
            composeRule.onAllNodesWithText("Payments Training Hub")
                .fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onNodeWithText("Courses").assertExists()
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
