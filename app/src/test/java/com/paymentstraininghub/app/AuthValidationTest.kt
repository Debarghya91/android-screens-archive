// AuthValidationTest.kt
// Unit tests for the professional-grade auth validation matrix:
// email shapes, input caps, the GoTrue error mapping (a wrong
// password must never read as "verify first"), the combined
// sign-in guidance, and the code error wording. No network calls:
// AuthManager is exercised only through its validation guards,
// which return before any request. No em dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SignupError
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.auth.SupabaseException
import com.paymentstraininghub.app.auth.cappedAuthCodeInput
import com.paymentstraininghub.app.auth.cappedAuthEmailInput
import com.paymentstraininghub.app.auth.cappedAuthPasswordInput
import com.paymentstraininghub.app.auth.isValidAuthEmail
import com.paymentstraininghub.app.auth.otpAuthMessage
import com.paymentstraininghub.app.auth.signInGuidanceFor
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {

    @Test
    fun validEmailShapesPass() {
        assertTrue(isValidAuthEmail("learner@example.com"))
        assertTrue(isValidAuthEmail("first.last+tag@sub.example.co.in"))
        assertTrue(isValidAuthEmail("a@b.co"))
    }

    @Test
    fun emptyAndMalformedEmailsFail() {
        assertFalse(isValidAuthEmail(""))
        assertFalse(isValidAuthEmail("not-an-email"))
        assertFalse(isValidAuthEmail("a@"))
        assertFalse(isValidAuthEmail("@example.com"))
        assertFalse(isValidAuthEmail("a@b"))
        assertFalse(isValidAuthEmail("a@.com"))
        assertFalse(isValidAuthEmail("a@example."))
        assertFalse(isValidAuthEmail("a@example..com"))
        assertFalse(isValidAuthEmail("a b@example.com"))
        assertFalse(isValidAuthEmail("a@@example.com"))
        assertFalse(isValidAuthEmail("a@example.com "))
    }

    @Test
    fun overlongEmailFails() {
        val local = "a".repeat(250)
        assertFalse(isValidAuthEmail("$local@example.com"))
    }

    @Test
    fun signInEmailRejectsBadEmailBeforeAnyNetworkCall() = runBlocking {
        val result = AuthManager.signInEmail("a@", "password123")
        assertTrue(result.isFailure)
        assertEquals("Enter a valid email address.", result.exceptionOrNull()?.message)
    }

    @Test
    fun signInEmailAcceptsTrimmedUppercaseEmailThenChecksPassword() = runBlocking {
        // Trailing spaces and uppercase are normalised, not
        // rejected: the failure below is the password guard,
        // which proves the email guard passed.
        val result = AuthManager.signInEmail("  Learner@Example.COM  ", "")
        assertTrue(result.isFailure)
        assertEquals("Enter your password.", result.exceptionOrNull()?.message)
    }

    @Test
    fun signUpRejectsBadEmailBeforeTermsAndPasswordChecks() = runBlocking {
        val result = AuthManager.signUpWithPassword("a@", "password123", termsAccepted = true)
        assertTrue(result.isFailure)
        assertEquals("Enter a valid email address.", result.exceptionOrNull()?.message)
    }

    @Test
    fun signUpTermsGuardRunsBeforeEmailGuard() = runBlocking {
        val result = AuthManager.signUpWithPassword("a@", "password123", termsAccepted = false)
        assertEquals(
            SignupErrorCode.TERMS_NOT_ACCEPTED.message,
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun emailInputIsCappedAt254() {
        val capped = cappedAuthEmailInput("a".repeat(300) + "@example.com")
        assertEquals(254, capped.length)
        assertEquals("a@b.com", cappedAuthEmailInput("a@b.com"))
    }

    @Test
    fun passwordInputIsCappedAt128() {
        val capped = cappedAuthPasswordInput("p".repeat(200))
        assertEquals(128, capped.length)
    }

    @Test
    fun codeInputStripsSeparatorsAndCapsAt8() {
        assertEquals("12345678", cappedAuthCodeInput("1234 5678"))
        assertEquals("12345678", cappedAuthCodeInput("1234-5678"))
        assertEquals("12345678", cappedAuthCodeInput("123456789012"))
        assertEquals("", cappedAuthCodeInput("  -  "))
    }

    @Test
    fun wrongPasswordNeverMapsToVerifyFirst() {
        val error = SignupError.fromGoTrueMessage("Invalid login credentials")
        assertEquals(SignupErrorCode.CREDENTIALS_INVALID, error.code)
        assertEquals(
            "Email or password does not match our records.",
            error.displayMessage
        )
    }

    @Test
    fun unconfirmedMessageMapsToVerifyFirst() {
        val error = SignupError.fromGoTrueMessage("Email not confirmed")
        assertEquals(SignupErrorCode.EMAIL_NOT_VERIFIED, error.code)
        assertEquals("Verify your email address first.", error.displayMessage)
    }

    @Test
    fun alreadyRegisteredMessageMapsToAlreadyRegistered() {
        val error = SignupError.fromGoTrueMessage("User already registered")
        assertEquals(SignupErrorCode.EMAIL_ALREADY_REGISTERED, error.code)
    }

    @Test
    fun invalidCredentialsGuidanceNamesAllThreeCases() {
        val guidance = signInGuidanceFor(SignupErrorCode.CREDENTIALS_INVALID)
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("not be confirmed"))
        assertTrue(guidance.contains("no password"))
        assertTrue(guidance.contains("emailed code"))
        assertTrue(guidance.contains("reset"))
    }

    @Test
    fun verifyFirstGuidanceOffersResend() {
        val guidance = signInGuidanceFor(SignupErrorCode.EMAIL_NOT_VERIFIED)
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("confirmation email"))
    }

    @Test
    fun otherCodesCarryNoGuidance() {
        assertNull(signInGuidanceFor(SignupErrorCode.UNKNOWN_ERROR))
        assertNull(signInGuidanceFor(SignupErrorCode.WEAK_PASSWORD))
        assertNull(signInGuidanceFor(SignupErrorCode.NETWORK_ERROR))
    }

    @Test
    fun expiredCodeNamesTheCodeNeverTheResetLink() {
        val message = otpAuthMessage(
            SupabaseException("rejected", serverMessage = "Token has expired or is invalid")
        )
        assertTrue(message.contains("code"))
        assertFalse(message.contains("reset link"))
    }

    @Test
    fun wrongCodeNamesTheCode() {
        val message = otpAuthMessage(
            SupabaseException("rejected", serverMessage = "Invalid token")
        )
        assertTrue(message.contains("code"))
        assertTrue(message.contains("Request a new code"))
    }

    @Test
    fun rateLimitedCodeRequestAsksForAWait() {
        val message = otpAuthMessage(
            SupabaseException("limited", serverMessage = "Rate limit exceeded")
        )
        assertTrue(message.contains("Wait"))
        assertTrue(message.contains("new code"))
    }

    @Test
    fun networkFailureMidFlowIsTheConnectionMessage() {
        val message = otpAuthMessage(IOException("No route to host"))
        assertEquals(SignupErrorCode.NETWORK_ERROR.message, message)
    }

    @Test
    fun verifyCodeRejectsBadCodeShapeWithoutNetwork() = runBlocking {
        val result = AuthManager.verifyEmailCode("learner@example.com", "12")
        assertTrue(result.isFailure)
        assertEquals("Enter the 8-digit code from the email.", result.exceptionOrNull()?.message)
    }

    @Test
    fun verifyCodeChecksEmailBeforeCode() = runBlocking {
        val result = AuthManager.verifyEmailCode("not-an-email", "1234 5678")
        assertEquals("Enter a valid email address.", result.exceptionOrNull()?.message)
    }
}
