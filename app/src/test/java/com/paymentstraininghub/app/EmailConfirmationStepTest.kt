// EmailConfirmationStepTest.kt
// Confirmation-step proofs: the code field normalises a pasted
// code (spaces and dashes stripped), the Verify button fires with
// the normalised code, the resend button is disabled during the
// cooldown with a countdown label, and the verifying state
// disables the actions. No network calls: callbacks are fakes.
// No em dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.paymentstraininghub.app.auth.cappedAuthCodeInput
import com.paymentstraininghub.app.ui.auth.EmailConfirmationStep
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EmailConfirmationStepTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var verifyCalls = 0
    private var signInCalls = 0
    private var resendCalls = 0
    private var code by mutableStateOf("")

    private fun renderStep(
        verifying: Boolean = false,
        busy: Boolean = false,
        resending: Boolean = false,
        cooldownSeconds: Int = 0
    ) {
        verifyCalls = 0
        signInCalls = 0
        resendCalls = 0
        code = ""
        composeRule.setContent {
            PfTheme {
                Column {
                    EmailConfirmationStep(
                        email = "learner@example.com",
                        code = code,
                        onCodeChange = { code = cappedAuthCodeInput(it) },
                        verifying = verifying,
                        busy = busy,
                        resending = resending,
                        cooldownSeconds = cooldownSeconds,
                        onVerifyCode = { verifyCalls++ },
                        onSignInWithPassword = { signInCalls++ },
                        onResend = { resendCalls++ }
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun codeFieldStripsSpacesAndDashesAsTyped() {
        renderStep()
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("signup-code-field")))
            .performTextInput("1234 5678")
        composeRule.waitForIdle()
        assertEquals("12345678", code)
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("signup-code-field")))
            .assertTextEquals("12345678")
    }

    @Test
    fun verifyButtonFiresWithTheNormalisedCode() {
        renderStep()
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("signup-code-field")))
            .performTextInput("1234-5678")
        composeRule.onNodeWithTag("signup-verify-code-button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, verifyCalls)
        assertEquals("12345678", code)
    }

    @Test
    fun resendIsDisabledDuringTheCooldownWithACountdownLabel() {
        renderStep(cooldownSeconds = 25)
        composeRule.onNodeWithTag("signup-resend-button").assertIsNotEnabled()
        composeRule.onNodeWithText("Resend in 25 seconds").assertIsDisplayed()
        composeRule.onNodeWithTag("signup-resend-button").performClick()
        composeRule.waitForIdle()
        assertEquals(0, resendCalls)
    }

    @Test
    fun resendIsEnabledWhenNoCooldownIsRunning() {
        renderStep(cooldownSeconds = 0)
        composeRule.onNodeWithTag("signup-resend-button").assertIsEnabled()
        composeRule.onNodeWithText("Resend confirmation email").assertIsDisplayed()
        composeRule.onNodeWithTag("signup-resend-button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, resendCalls)
    }

    @Test
    fun verifyingDisablesVerifyAndShowsVerifying() {
        renderStep(verifying = true)
        composeRule.onNodeWithTag("signup-verify-code-button").assertIsNotEnabled()
        composeRule.onNodeWithText("Verifying...").assertIsDisplayed()
        composeRule.onNodeWithTag("signup-verify-code-button").performClick()
        composeRule.waitForIdle()
        assertEquals(0, verifyCalls)
    }

    @Test
    fun signInWithPasswordButtonFires() {
        renderStep()
        composeRule.onNodeWithTag("signup-sign-in-button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, signInCalls)
        assertTrue(verifyCalls == 0)
    }

    @Test
    fun linkAlternativeIsNamedAlongsideTheCodeField() {
        renderStep()
        composeRule.onNodeWithText("Check your email").assertIsDisplayed()
        composeRule.onNodeWithTag("signup-code-field").assertExists()
        composeRule.onNodeWithText("Verify code and sign in").assertIsDisplayed()
    }
}
