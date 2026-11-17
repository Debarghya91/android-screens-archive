// AuthScreenFormTest.kt
// Sign-in screen form proofs: the password form shows first with
// the wordmark, the single primary button, the Forgot password
// link, the "or" divider and the emailed-code alternative; the
// emailed-code form replaces it behind a tap and only one form
// is ever visible; and the empty-field validation errors show
// without any network call. No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.ui.auth.AuthScreen
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuthScreenFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setup() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = true)
    }

    @After
    fun teardown() {
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
    }

    private fun renderAuthScreen() {
        composeRule.setContent {
            PfTheme {
                AuthScreen(onSignedIn = {}, onCreateAccount = {}, onForgotPassword = {})
            }
        }
        composeRule.waitForIdle()
    }

    /** Types into the text field inside the tagged PfField. */
    private fun typeIntoField(tag: String, text: String) {
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
            .performTextInput(text)
    }

    @Test
    fun passwordFormShowsFirstWithWordmarkAndActions() {
        renderAuthScreen()
        composeRule.onNodeWithText("Payments Training Hub").assertIsDisplayed()
        composeRule.onNodeWithTag("auth-email-field").assertExists()
        composeRule.onNodeWithTag("auth-password-field").assertExists()
        composeRule.onNodeWithTag("auth-sign-in-button").assertExists()
        composeRule.onNodeWithText("Forgot password?").assertExists()
        composeRule.onNodeWithText("or").assertExists()
        composeRule.onNodeWithTag("auth-email-code-button").assertExists()
        composeRule.onNodeWithTag("auth-create-account-button").assertExists()
        // The emailed-code form is hidden until tapped, and there
        // is no marketing headline.
        composeRule.onNodeWithTag("auth-code-field").assertDoesNotExist()
        composeRule.onNodeWithTag("auth-send-code-button").assertDoesNotExist()
        composeRule.onNodeWithText("Master Banking and Payments").assertDoesNotExist()
    }

    @Test
    fun emailedCodeFormReplacesThePasswordForm() {
        renderAuthScreen()
        // The alternative buttons sit below the fold in the test
        // viewport: scroll each one into view before tapping, or
        // the tap lands outside the window and nothing happens.
        composeRule.onNodeWithTag("auth-email-code-button").performScrollTo()
        composeRule.onNodeWithTag("auth-email-code-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth-send-code-button").assertExists()
        composeRule.onNode(hasText("We will email you an 8-digit code", substring = true))
            .assertExists()
        composeRule.onNodeWithTag("auth-password-field").assertDoesNotExist()
        composeRule.onNodeWithTag("auth-sign-in-button").assertDoesNotExist()
        // Back returns to the password form, with the code form gone.
        composeRule.onNodeWithTag("auth-back-to-password-button").performScrollTo()
        composeRule.onNodeWithTag("auth-back-to-password-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth-password-field").assertExists()
        composeRule.onNodeWithTag("auth-sign-in-button").assertExists()
        composeRule.onNodeWithTag("auth-send-code-button").assertDoesNotExist()
    }

    @Test
    fun emptyEmailShowsAValidationErrorWithoutNetwork() {
        renderAuthScreen()
        composeRule.onNodeWithTag("auth-sign-in-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enter your email address.").assertExists()
    }

    @Test
    fun emptyPasswordShowsAValidationErrorWithoutNetwork() {
        renderAuthScreen()
        typeIntoField("auth-email-field", "learner@example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth-sign-in-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enter your password.").assertExists()
    }

    @Test
    fun codeRequestValidatesTheEmailWithoutNetwork() {
        renderAuthScreen()
        composeRule.onNodeWithTag("auth-email-code-button").performScrollTo()
        composeRule.onNodeWithTag("auth-email-code-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth-send-code-button").performScrollTo()
        composeRule.onNodeWithTag("auth-send-code-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enter your email address.").assertExists()
        composeRule.onNodeWithTag("auth-code-field").assertDoesNotExist()
    }
}
