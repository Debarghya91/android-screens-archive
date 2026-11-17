// AccessModelTest.kt
// Access-model tests against the real AppNavGraph: content is
// browsable without a session, signing out keeps content
// browsable, deep links land directly with no session, and a
// reset link still reaches the set-new-password screen. Runs on
// SDK 28 only, like the other graph tests. The auth state is
// driven through AuthManager.setTestAuthState, which moves only
// the state and readiness the graph reads; no token is faked and
// no network is touched. No em dashes in any string here.

package com.paymentstraininghub.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class AccessModelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppStore.init(context)
        RenderFakeData.install()
        DeepLinkRouter.clear()
        DeepLinkRouter.clearRecoveryToken()
        // Signed out, restore finished: the default state for
        // each test, overridden explicitly where a test needs a
        // session or an unfinished restore.
        signOutForTest()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
        DeepLinkRouter.clear()
        DeepLinkRouter.clearRecoveryToken()
        signOutForTest(ready = false)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun markOnboardingDone() {
        TourStore.markOnboardingCompleted(context())
    }

    private fun signInAsTestLearner() {
        AuthManager.setTestAuthState(
            AuthManager.AuthState.SignedIn("test-user-id", "learner@example.com"),
            ready = true
        )
    }

    private fun signOutForTest(ready: Boolean = true) {
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = ready)
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    private fun assertTextAbsent(text: String) {
        assertTrue(
            "\"$text\" must not be on screen",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        )
    }

    private fun assertTagAbsent(tag: String) {
        assertTrue(
            "tag \"$tag\" must not be on screen",
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun unauthenticatedLaunchShowsBrowsableContent() {
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        composeRule.onRoot().assertExists()
        // The tab bar is showing: content, not the auth flow.
        composeRule.onNodeWithText("Payment Cases").assertExists()
        composeRule.onNodeWithText("My Progress").assertExists()
        // No sign-in prompt on launch: sign-in is prompted only
        // on a Buy action.
        assertTextAbsent("Use an emailed code instead")
        assertTextAbsent("No account yet? Create one")
    }

    @Test
    fun unauthenticatedDeepLinkLandsOnTheLesson() {
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s1")) }
        settle()
        // The lesson renders with no session: the audio button
        // and the lesson title are on screen.
        composeRule.onAllNodesWithTag("lessonAudioFab")
            .fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        composeRule.onNodeWithText("What a payment is").assertExists()
        assertTextAbsent("Use an emailed code instead")
    }

    @Test
    fun authenticatedLaunchRestoresToHome() {
        signInAsTestLearner()
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        composeRule.onNodeWithText("Payment Cases").assertExists()
        composeRule.onNodeWithText("My Progress").assertExists()
        assertTextAbsent("Use an emailed code instead")
        assertTextAbsent("No account yet? Create one")
    }

    @Test
    fun authenticatedDeepLinkLandsOnTheLesson() {
        signInAsTestLearner()
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s1")) }
        settle()
        composeRule.onAllNodesWithTag("lessonAudioFab")
            .fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        assertTextAbsent("Use an emailed code instead")
    }

    @Test
    fun signOutKeepsContentBrowsable() {
        signInAsTestLearner()
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        composeRule.onNodeWithText("Payment Cases").assertExists()
        // The session ends: the app stays on browsable content
        // as a guest instead of returning to the auth flow.
        signOutForTest()
        settle()
        composeRule.onNodeWithText("Payment Cases").assertExists()
        composeRule.onNodeWithText("My Progress").assertExists()
        assertTextAbsent("Use an emailed code instead")
    }

    @Test
    fun contentShowsWhileRestoreRuns() {
        // The restore has not reported yet, and no session is
        // known: content still shows, with no loading gate.
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        composeRule.onNodeWithText("Payment Cases").assertExists()
        composeRule.onNodeWithText("My Progress").assertExists()
        assertTextAbsent("Use an emailed code instead")
    }

    @Test
    fun resetDeepLinkWhileSignedOutShowsTheResetScreen() {
        // The cold-start reset path, as MainActivity delivers it:
        // the token is recorded with the router and the start
        // route is the reset screen. The reset screen lives in
        // the main graph, so it opens without a session.
        DeepLinkRouter.receive(TrainingHubDeepLink.ResetPassword("test-recovery-token"))
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.RESET_PASSWORD) }
        settle()
        composeRule.onNodeWithText("Choose a new password").assertIsDisplayed()
        assertTextAbsent("Payment Cases")
    }

    @Test
    fun warmDeepLinkWhileSignedOutRoutesAtOnce() {
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        composeRule.onNodeWithText("Payment Cases").assertExists()
        // A lesson link arrives while signed out: it routes at
        // once, with no sign-in wait.
        DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch1s1")
        settle()
        composeRule.onAllNodesWithTag("lessonAudioFab")
            .fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        assertTextAbsent("Use an emailed code instead")
    }

    @Test
    fun resetLinkStillReachesTheResetScreenWhenAContentLinkArrivesFirst() {
        // A content link arrives first; a reset link then
        // arrives. The reset must not queue behind the content
        // link, because the reset is itself the path to signing
        // in: the content link is dropped under recovery
        // precedence and the reset screen opens.
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.HOME) }
        settle()
        DeepLinkRouter.receiveUri("com.paymentstraininghub.app://lesson?id=ch1s1")
        DeepLinkRouter.receiveUri(
            "com.paymentstraininghub.app://reset-password#access_token=<redacted>&type=recovery"
        )
        settle()
        composeRule.onNodeWithText("Choose a new password").assertIsDisplayed()
        assertTagAbsent("lessonAudioFab")
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
