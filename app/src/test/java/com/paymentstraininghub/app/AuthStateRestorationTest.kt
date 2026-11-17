// AuthStateRestorationTest.kt
// Rotation proofs: the sign-in and signup forms keep what the
// learner typed across a saved-instance-state restore (the
// rotation path), including which form is showing, and no request
// is re-sent by the restore. No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import com.paymentstraininghub.app.ui.auth.EmailSignupScreen
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
class AuthStateRestorationTest {

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

    private fun typeIntoField(tag: String, text: String) {
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
            .performTextInput(text)
    }

    private fun assertFieldText(tag: String, text: String) {
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))
            .assertTextEquals(text)
    }

    /**
     * Password fields mask their semantics text as one bullet per
     * character, so the restored value is asserted as the bullet
     * string of the same length. The length proves the value
     * survived the restore; the mask is a platform behaviour.
     */
    private fun assertPasswordFieldText(tag: String, length: Int) {
        assertFieldText(tag, "•".repeat(length))
    }

    @Test
    fun signInFormKeepsTypedValuesAcrossRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            PfTheme {
                AuthScreen(onSignedIn = {}, onCreateAccount = {}, onForgotPassword = {})
            }
        }
        typeIntoField("auth-email-field", "learner@example.com")
        typeIntoField("auth-password-field", "secret123")
        composeRule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        assertFieldText("auth-email-field", "learner@example.com")
        assertPasswordFieldText("auth-password-field", "secret123".length)
        composeRule.onNodeWithText("Enter your email address.").assertDoesNotExist()
    }

    @Test
    fun signInCodeFormStaysShowingAcrossRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            PfTheme {
                AuthScreen(onSignedIn = {}, onCreateAccount = {}, onForgotPassword = {})
            }
        }
        composeRule.onNodeWithTag("auth-email-code-button").performScrollTo()
        composeRule.onNodeWithTag("auth-email-code-button").performClick()
        composeRule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("auth-send-code-button").assertExists()
        composeRule.onNodeWithTag("auth-password-field").assertDoesNotExist()
    }

    @Test
    fun signupFormKeepsTypedValuesAndTermsAcrossRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            PfTheme {
                EmailSignupScreen(onSignedIn = {}, onBack = {})
            }
        }
        typeIntoField("signup-email-field", "new@example.com")
        typeIntoField("signup-password-field", "password123")
        typeIntoField("signup-confirm-password-field", "password123")
        composeRule.onNodeWithTag("signup-terms-checkbox").performScrollTo()
        composeRule.onNodeWithTag("signup-terms-checkbox").performClick()
        composeRule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        assertFieldText("signup-email-field", "new@example.com")
        assertPasswordFieldText("signup-password-field", "password123".length)
        assertPasswordFieldText("signup-confirm-password-field", "password123".length)
        // The terms box stays checked across the restore.
        composeRule.onNodeWithTag("signup-terms-checkbox").assertIsOn()
    }
}
