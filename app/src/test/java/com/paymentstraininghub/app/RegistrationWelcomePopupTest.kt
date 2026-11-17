// RegistrationWelcomePopupTest.kt
// Welcome-popup proofs: the popup stays on screen past the old
// 3 second auto-dismiss window (the "finish your profile" flash),
// and dismisses exactly once when the learner presses Continue
// or taps the card. No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.paymentstraininghub.app.ui.auth.RegistrationWelcomePopup
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RegistrationWelcomePopupTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var dismissCalls = 0

    private fun renderPopup(profileIncomplete: Boolean = true) {
        dismissCalls = 0
        composeRule.setContent {
            PfTheme {
                RegistrationWelcomePopup(
                    caseID = "PF-ABCDEF",
                    profileIncomplete = profileIncomplete,
                    onDismiss = { dismissCalls++ }
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun popupStaysVisiblePastTheOldThreeSecondDismiss() {
        renderPopup()
        composeRule.mainClock.advanceTimeBy(10_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Congratulations!").assertIsDisplayed()
        composeRule.onNodeWithText("Next step: finish your profile.").assertIsDisplayed()
        composeRule.onNodeWithText("Your case ID is PF-ABCDEF. Keep it for future reference.")
            .assertIsDisplayed()
        assertEquals(0, dismissCalls)
    }

    @Test
    fun continueDismissesExactlyOnce() {
        renderPopup()
        composeRule.onNodeWithTag("welcome-continue-button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissCalls)
        composeRule.mainClock.advanceTimeBy(10_000L)
        composeRule.waitForIdle()
        assertEquals(1, dismissCalls)
    }

    @Test
    fun tappingTheCardDismisses() {
        renderPopup()
        composeRule.onNodeWithText("Welcome to Payments Training Hub.").performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissCalls)
    }

    @Test
    fun completeProfilePointsAtBrowsingCourses() {
        renderPopup(profileIncomplete = false)
        composeRule.onNodeWithText("Next step: browse courses.").assertIsDisplayed()
    }
}
