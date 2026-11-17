// EmailOtpTest.kt
// Unit tests for the emailed-code sign-in path: the OTP request
// and verify shapes, the dual-type verify fallback (email first,
// then signup, for codes from the confirm-signup email), the
// code validation, the OTP error wording, and the password
// sign-in guidance that treats invalid credentials as
// possibly-unconfirmed. No network calls: the verify seam is a
// fake lambda. No em dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.AuthTokens
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.auth.SupabaseException
import com.paymentstraininghub.app.auth.buildOtpRequestBody
import com.paymentstraininghub.app.auth.buildVerifyRequestBody
import com.paymentstraininghub.app.auth.isValidEmailCode
import com.paymentstraininghub.app.auth.normalizeEmailCode
import com.paymentstraininghub.app.auth.otpAuthMessage
import com.paymentstraininghub.app.auth.shouldFallbackVerifyType
import com.paymentstraininghub.app.auth.signInGuidanceFor
import com.paymentstraininghub.app.auth.verifyEmailOtpWithFallback
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class EmailOtpTest {

    private fun tokens() = AuthTokens("access", "refresh", "user-1", "learner@example.com")

    private fun rejectedToken() = SupabaseException(
        "Token has expired or is invalid",
        status = 400,
        serverMessage = "Token has expired or is invalid"
    )

    @Test
    fun otpRequestBodyCarriesEmailAndCreateUser() {
        val body = Json.parseToJsonElement(buildOtpRequestBody("learner@example.com")) as JsonObject
        assertEquals("learner@example.com", body["email"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["create_user"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["should_create_user"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun verifyRequestBodyCarriesEmailTokenAndType() {
        val emailBody = Json.parseToJsonElement(
            buildVerifyRequestBody("learner@example.com", "12345678", "email")
        ) as JsonObject
        assertEquals("learner@example.com", emailBody["email"]?.jsonPrimitive?.contentOrNull)
        assertEquals("12345678", emailBody["token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("email", emailBody["type"]?.jsonPrimitive?.contentOrNull)
        val signupBody = Json.parseToJsonElement(
            buildVerifyRequestBody("learner@example.com", "12345678", "signup")
        ) as JsonObject
        assertEquals("learner@example.com", signupBody["email"]?.jsonPrimitive?.contentOrNull)
        assertEquals("12345678", signupBody["token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("signup", signupBody["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun fallbackOnlyForRejectedTokens() {
        assertTrue(shouldFallbackVerifyType(rejectedToken()))
        assertTrue(shouldFallbackVerifyType(SupabaseException("rejected", status = 401)))
        assertFalse(shouldFallbackVerifyType(SupabaseException("slow down", status = 429)))
        assertFalse(shouldFallbackVerifyType(SupabaseException("server", status = 500)))
        assertFalse(shouldFallbackVerifyType(SupabaseException("unreadable")))
    }

    @Test
    fun emailTypeSuccessNeverTriesSignup() = runBlocking {
        val tried = mutableListOf<String>()
        val result = verifyEmailOtpWithFallback { type ->
            tried.add(type)
            tokens()
        }
        assertEquals("user-1", result.userId)
        assertEquals(listOf("email"), tried)
    }

    @Test
    fun rejectedEmailTypeFallsBackToSignup() = runBlocking {
        val tried = mutableListOf<String>()
        val result = verifyEmailOtpWithFallback { type ->
            tried.add(type)
            if (type == "email") throw rejectedToken()
            tokens()
        }
        assertEquals("user-1", result.userId)
        assertEquals(listOf("email", "signup"), tried)
    }

    @Test
    fun bothTypesRejectedSurfacesTheSignupError() = runBlocking {
        val signupError = rejectedToken()
        try {
            verifyEmailOtpWithFallback { type ->
                if (type == "email") throw rejectedToken()
                throw signupError
            }
            fail("expected the signup error")
        } catch (e: SupabaseException) {
            assertSame(signupError, e)
        }
    }

    @Test
    fun networkFailureNeverFallsBack() = runBlocking {
        val tried = mutableListOf<String>()
        val networkError = IOException("no connection")
        try {
            verifyEmailOtpWithFallback { type ->
                tried.add(type)
                throw networkError
            }
            fail("expected the network error")
        } catch (e: IOException) {
            assertSame(networkError, e)
        }
        assertEquals(listOf("email"), tried)
    }

    @Test
    fun rateLimitNeverFallsBack() = runBlocking {
        val tried = mutableListOf<String>()
        try {
            verifyEmailOtpWithFallback { type ->
                tried.add(type)
                throw SupabaseException("Too many requests", status = 429, serverMessage = "Too many requests")
            }
            fail("expected the rate limit error")
        } catch (e: SupabaseException) {
            assertEquals(429, e.status)
        }
        assertEquals(listOf("email"), tried)
    }

    @Test
    fun codeNormalisationStripsSpacesAndDashes() {
        assertEquals("12345678", normalizeEmailCode("1234 5678"))
        assertEquals("12345678", normalizeEmailCode("1234-5678"))
        assertEquals("12345678", normalizeEmailCode("  12345678  "))
    }

    @Test
    fun codeValidationAcceptsSixToEightDigits() {
        assertTrue(isValidEmailCode("12345678"))
        assertTrue(isValidEmailCode("123456"))
        assertTrue(isValidEmailCode("1234567"))
        assertTrue(isValidEmailCode("1234 5678"))
        assertFalse(isValidEmailCode("12345"))
        assertFalse(isValidEmailCode("123456789"))
        assertFalse(isValidEmailCode("1234567a"))
        assertFalse(isValidEmailCode(""))
        assertFalse(isValidEmailCode("abcdefgh"))
    }

    @Test
    fun otpErrorsNameTheCodeNeverTheResetLink() {
        val expired = otpAuthMessage(rejectedToken())
        assertTrue(expired.contains("code"))
        assertFalse(expired.contains("reset link"))
        val invalid = otpAuthMessage(
            SupabaseException("x", status = 400, serverMessage = "Invalid token")
        )
        assertTrue(invalid.contains("code"))
        assertEquals(
            SignupErrorCode.NETWORK_ERROR.message,
            otpAuthMessage(IOException("no connection"))
        )
        val rateLimited = otpAuthMessage(
            SupabaseException("x", status = 429, serverMessage = "Email rate limit exceeded")
        )
        assertTrue(rateLimited.contains("Wait"))
        assertEquals(
            SignupErrorCode.UNKNOWN_ERROR.message,
            otpAuthMessage(SupabaseException("x", status = 400, serverMessage = "Something odd"))
        )
    }

    @Test
    fun credentialsGuidanceNamesAllThreeCauses() {
        val guidance = signInGuidanceFor(SignupErrorCode.CREDENTIALS_INVALID)
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("not be confirmed"))
        assertTrue(guidance.contains("no password"))
        assertTrue(guidance.contains("emailed code"))
        assertTrue(guidance.contains("reset"))
        assertFalse(guidance.contains("\u2014"))
    }

    @Test
    fun unverifiedGuidancePointsAtConfirmation() {
        val guidance = signInGuidanceFor(SignupErrorCode.EMAIL_NOT_VERIFIED)
        assertNotNull(guidance)
        assertTrue(guidance!!.contains("confirmation email"))
    }

    @Test
    fun otherCodesCarryNoGuidance() {
        assertNull(signInGuidanceFor(SignupErrorCode.UNKNOWN_ERROR))
        assertNull(signInGuidanceFor(SignupErrorCode.NETWORK_ERROR))
        assertNull(signInGuidanceFor(SignupErrorCode.SESSION_EXPIRED))
    }
}
