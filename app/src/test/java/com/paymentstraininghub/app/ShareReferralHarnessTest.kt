// ShareReferralHarnessTest.kt
// Referral share proofs: tapping "Share your code" on the Profile
// screen fires the Android share-sheet intent carrying the
// referral code, the invite text and the website link; and the
// intent builder shapes the chooser correctly. The tap used to do
// nothing: the failure was swallowed silently. No em dashes in any
// string here.

package com.paymentstraininghub.app

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.ReferralStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.ui.profile.ProfileScreen
import com.paymentstraininghub.app.ui.profile.buildReferralShareIntent
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
class ShareReferralHarnessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.install()
        AuthManager.setTestAuthState(
            AuthManager.AuthState.SignedIn("user-123", "learner@example.com"),
            ready = true
        )
    }

    @After
    fun teardown() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
        AuthManager.setTestAuthState(AuthManager.AuthState.Guest, ready = false)
    }

    @Test
    fun shareIntentBuilderShapesAChooserAroundTextSend() {
        val intent = buildReferralShareIntent("My referral code is ABCDEF12.")
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertEquals(
            "My Payments Training Hub referral code",
            send.getStringExtra(Intent.EXTRA_SUBJECT)
        )
        assertEquals(
            "My referral code is ABCDEF12.",
            send.getStringExtra(Intent.EXTRA_TEXT)
        )
    }

    @Test
    fun tappingShareYourCodeFiresTheShareSheetWithCodeAndLink() {
        val expectedCode = ReferralStore.codeForUserId("user-123")
        composeRule.setContent {
            PfTheme { ProfileScreen(onBack = {}, onNavigate = {}) }
        }
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your referral code").assertIsDisplayed()
        composeRule.onNodeWithText(expectedCode).assertExists()
        composeRule.onNodeWithTag("share-referral-code-button").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share-referral-code-button").performClick()
        composeRule.waitForIdle()
        val started = Shadows.shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull("tapping Share your code must start an activity", started)
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue("share text must carry the referral code", text.contains(expectedCode))
        assertTrue("share text must carry the website link", text.contains("paymentstraininghub.com"))
        assertTrue("share text must name the app", text.contains("Payments Training Hub"))
    }

    companion object {
        private const val SETTLE_MS = 1200L
    }
}
