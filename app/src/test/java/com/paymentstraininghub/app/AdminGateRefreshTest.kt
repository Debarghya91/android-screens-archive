// AdminGateRefreshTest.kt
// Regression tests for the admin gate-refresh defect: gate
// evaluations keyed only on the entitlement flows
// (ownedIds, serverIds, gateState) never re-ran when the admin
// flag landed on session restore, because restore flips the
// flag without touching any entitlement flow. An admin with no
// purchases kept the guest-time locked state on every gated
// screen until re-entry. The fix adds AuthManager.isAdmin as a
// gate input on every evaluation site. These tests flip the
// flag in place, with no navigation, and require the visible
// screen to follow. Runs on SDK 28 only, like the other graph
// tests. The auth state is driven through
// AuthManager.setTestAuthState, which moves only the state,
// readiness and admin flag the graph reads; no token is faked
// and no network is touched. BillingManager is left in its
// default empty state, the suite-wide convention: no test
// purchases or unlocks, so the guest starts owning nothing.
// Realistic shapes: ch1s3 is a genuinely locked lesson (third
// in chapter 1, a sample under neither rule), ch1s1 is the
// chapter sample, and the situations list carries free samples
// plus locked cases. No em dashes in any string here.

package com.paymentstraininghub.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
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
class AdminGateRefreshTest {

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

    private fun signInAsAdmin() {
        AuthManager.setTestAuthState(
            AuthManager.AuthState.SignedIn("admin-user-id", "debarghya.092008@gmail.com"),
            ready = true,
            isAdmin = true
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

    private fun assertTextPresent(text: String) {
        assertTrue(
            "\"$text\" must be on screen",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun assertDescriptionAbsent(description: String) {
        assertTrue(
            "content description \"$description\" must not be on screen",
            composeRule.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun assertDescriptionPresent(description: String) {
        assertTrue(
            "content description \"$description\" must be on screen",
            composeRule.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun assertLessonContentShown() {
        // The audio button renders only for the Content state,
        // and the takeaway card renders only with the content.
        // The lock explanation belongs to the Locked state only.
        assertTrue(
            "lesson audio button must be on screen",
            composeRule.onAllNodesWithTag("lessonAudioFab")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTextPresent("A payment is an instruction to move money between accounts.")
        assertTextAbsent("Buy the course to unlock this chapter.")
    }

    private fun assertLessonLockedShown() {
        composeRule.onNodeWithText("Locked lesson").assertIsDisplayed()
        assertTextPresent("Buy the course to unlock this chapter.")
        assertTrue(
            "lesson audio button must not be on screen for a locked lesson",
            composeRule.onAllNodesWithTag("lessonAudioFab")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun lockedLessonUnlocksWhenAdminSignInLands() {
        // The sharpest site: a lesson opened Locked while a
        // guest must unlock in place when the admin flag lands,
        // with no navigation and no reload tap.
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s3")) }
        settle()
        assertLessonLockedShown()
        signInAsAdmin()
        settle()
        assertLessonContentShown()
    }

    @Test
    fun lockedLessonUnlocksWhenRestoreCompletesAsAdmin() {
        // The cold-start race: the lesson composes while the
        // restore is still running, then the restore completes
        // as an admin. The lesson must unlock in place.
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s3")) }
        settle()
        assertLessonLockedShown()
        signInAsAdmin()
        settle()
        assertLessonContentShown()
    }

    @Test
    fun contentLessonLocksWhenAdminSignsOut() {
        // The reverse direction: a lesson opened unlocked as an
        // admin must lock in place when the session ends, with
        // no navigation.
        signInAsAdmin()
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s3")) }
        settle()
        assertLessonContentShown()
        signOutForTest()
        settle()
        assertLessonLockedShown()
    }

    @Test
    fun freeSampleCardShowsForGuestOnUnownedChapterSample() {
        // The first lesson of a chapter the guest does not own
        // is a free sample, and the card must say so. The old
        // predicate could never be true, so the card never
        // rendered at all.
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s1")) }
        settle()
        assertLessonContentShown()
        composeRule.onNodeWithText("Free sample lesson").assertIsDisplayed()
    }

    @Test
    fun freeSampleCardHiddenForAdminOnSampleLesson() {
        // For an admin the chapter is unlocked, so the sample
        // lesson is simply part of the course: no free-sample
        // card, even though the lesson is the chapter sample.
        signInAsAdmin()
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.lesson("ch1s1")) }
        settle()
        assertLessonContentShown()
        assertTextAbsent("Free sample lesson")
    }

    @Test
    fun situationsLockPillsClearWhenAdminSignInLands() {
        // The remembered situations gate on the cases list must
        // follow the admin flag in place: locked pills as a
        // guest, none as an admin, with no navigation.
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.CASES) }
        settle()
        assertTextPresent("Locked")
        signInAsAdmin()
        settle()
        assertTextAbsent("Locked")
    }

    @Test
    fun chapterListUnlocksWhenAdminSignInLands() {
        // The chapter list rows must follow the admin flag in
        // place: ch1s3 carries a locked row icon as a guest and
        // an open row icon as an admin, with no navigation.
        markOnboardingDone()
        composeRule.setContent { AppNavGraph(initialRoute = Routes.chapter(1)) }
        settle()
        assertTextPresent("Accounts and ledgers")
        assertDescriptionPresent("Locked lesson")
        signInAsAdmin()
        settle()
        assertDescriptionAbsent("Locked lesson")
        assertDescriptionPresent("Open lesson")
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
