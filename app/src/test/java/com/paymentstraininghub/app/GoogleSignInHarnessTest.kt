// GoogleSignInHarnessTest.kt
// Google sign-in screen proofs: the Continue with Google button
// opens the browser with the Supabase authorisation URL, it shows
// only on the password form, a provider error callback shows the
// did-not-complete message, and a code callback without a stored
// verifier shows the interrupted message. No network calls and no
// real browser: the intent is captured from the activity shadow,
// and the verifier is never stored in the callback tests. No em
// dashes in any string here.

package com.paymentstraininghub.app

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import com.paymentstraininghub.app.ui.auth.AuthScreen
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoogleSignInHarnessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = true)
        AuthManager.cancelGoogleSignIn()
        DeepLinkRouter.clear()
    }

    @After
    fun teardown() {
        DeepLinkRouter.clear()
        AuthManager.cancelGoogleSignIn()
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

    @Test
    fun googleButtonOpensTheBrowserWithTheAuthorizeUrl() {
        renderAuthScreen()
        composeRule.onNodeWithTag("auth-google-button").performScrollTo()
        composeRule.onNodeWithTag("auth-google-button").performClick()
        composeRule.waitForIdle()
        val started = Shadows.shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull("tapping Continue with Google must open the browser", started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        val url = started.data?.toString().orEmpty()
        assertTrue(url.contains("/auth/v1/authorize?provider=google"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_to="))
        composeRule.onNodeWithText("Opening Google sign-in in your browser. Return here after signing in.")
            .assertExists()
    }

    @Test
    fun googleButtonShowsOnlyOnThePasswordForm() {
        renderAuthScreen()
        composeRule.onNodeWithTag("auth-google-button").assertExists()
        composeRule.onNodeWithTag("auth-email-code-button").performScrollTo()
        composeRule.onNodeWithTag("auth-email-code-button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth-google-button").assertDoesNotExist()
    }

    @Test
    fun providerErrorCallbackShowsTheDidNotCompleteMessage() {
        DeepLinkRouter.receive(TrainingHubDeepLink.OAuthCallback(code = null, error = "access_denied"))
        renderAuthScreen()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Google sign-in did not complete. Try again, or sign in with email.")
            .assertExists()
        assertEquals(null, DeepLinkRouter.pendingOAuthError.value)
    }

    @Test
    fun codeCallbackWithoutAVerifierShowsTheInterruptedMessage() {
        DeepLinkRouter.receive(TrainingHubDeepLink.OAuthCallback(code = "stale-code", error = null))
        renderAuthScreen()
        composeRule.waitForIdle()
        Thread.sleep(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Google sign-in was interrupted. Try again.").assertExists()
        assertEquals(null, DeepLinkRouter.pendingOAuthCode.value)
    }
}
