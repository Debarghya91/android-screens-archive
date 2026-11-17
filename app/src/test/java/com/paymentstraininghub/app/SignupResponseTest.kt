package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.SignupResponse
import com.paymentstraininghub.app.auth.SupabaseException
import com.paymentstraininghub.app.auth.classifySignupResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SignupResponseTest {

    // Shape GoTrue actually returns when Confirm email is on: the bare
    // user object at the top level, no session, no nested "user" key.
    // Modelled on a real 200 reply from this project's Auth API.
    private val bareUserReply = """
        {
          "id": "ad611841-bcfa-45c4-85c0-a24df775ae8f",
          "aud": "authenticated",
          "role": "authenticated",
          "email": "friend@example.com",
          "phone": "",
          "confirmation_sent_at": "2026-11-15T15:51:08Z",
          "app_metadata": {"provider": "email", "providers": ["email"]},
          "user_metadata": {"email": "friend@example.com", "email_verified": false},
          "identities": [],
          "created_at": "2026-11-15T15:51:08Z",
          "updated_at": "2026-11-15T15:51:10Z",
          "is_anonymous": false
        }
    """.trimIndent()

    private val sessionReply = """
        {
          "access_token": "tok-1",
          "refresh_token": "ref-1",
          "user": {"id": "u-1", "email": "a@b.com"}
        }
    """.trimIndent()

    @Test
    fun bareUserReplyMeansNeedsEmailConfirmation() {
        val result = classifySignupResponse(bareUserReply)
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun sessionReplyMeansSignedIn() {
        val result = classifySignupResponse(sessionReply)
        assertTrue(result is SignupResponse.SignedIn)
        val tokens = (result as SignupResponse.SignedIn).tokens
        assertEquals("tok-1", tokens.accessToken)
        assertEquals("ref-1", tokens.refreshToken)
        assertEquals("u-1", tokens.userId)
        assertEquals("a@b.com", tokens.email)
    }

    @Test
    fun nestedUserWithoutTokensMeansNeedsEmailConfirmation() {
        val result = classifySignupResponse("""{"user": {"id": "u-1", "email": "a@b.com"}}""")
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun errorBodyThrows() {
        try {
            classifySignupResponse("""{"code": 400, "error_code": "invalid_credentials", "msg": "Invalid login credentials"}""")
            fail("an error body must not classify as a successful signup")
        } catch (expected: SupabaseException) {
            assertTrue(expected.message!!.contains("could not be read"))
        }
    }

    @Test
    fun garbageThrows() {
        try {
            classifySignupResponse("not json")
            fail("garbage must not classify as a successful signup")
        } catch (expected: SupabaseException) {
            assertTrue(expected.message!!.contains("could not be read"))
        }
    }

    @Test
    fun emptyObjectThrows() {
        try {
            classifySignupResponse("{}")
            fail("an empty object must not classify as a successful signup")
        } catch (expected: SupabaseException) {
            assertTrue(expected.message!!.contains("could not be read"))
        }
    }

    @Test
    fun emptyIdentitiesAloneNeverMeansAlreadyRegistered() {
        // The bare-user fixture above carries "identities": [] and
        // is a real NEW signup reply: the empty-identities signal
        // does not hold on this project, and must never flip a new
        // signup into the already-registered outcome.
        val result = classifySignupResponse(bareUserReply)
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun confirmedAtMeansAlreadyRegisteredConfirmed() {
        val reply = """
            {
              "id": "u-9", "email": "old@example.com",
              "email_confirmed_at": "2026-11-10T09:00:00Z",
              "confirmation_sent_at": "2026-11-10T08:59:00Z",
              "created_at": "2026-11-10T08:59:00Z",
              "identities": []
            }
        """.trimIndent()
        val result = classifySignupResponse(reply)
        assertEquals(SignupResponse.AlreadyRegistered(emailConfirmed = true), result)
    }

    @Test
    fun confirmationSentLongAfterCreationMeansAlreadyRegisteredUnconfirmed() {
        // A re-signup refreshes confirmation_sent_at while
        // created_at stays at the original signup: a five minute
        // gap means the address predates this attempt.
        val reply = """
            {
              "id": "u-9", "email": "old@example.com",
              "confirmation_sent_at": "2026-11-15T16:00:00Z",
              "created_at": "2026-11-15T15:51:08Z",
              "identities": []
            }
        """.trimIndent()
        val result = classifySignupResponse(reply)
        assertEquals(SignupResponse.AlreadyRegistered(emailConfirmed = false), result)
    }

    @Test
    fun confirmationSentAtCreationMeansNewAccount() {
        // Fresh signup: the confirmation mail goes out at
        // creation, so the two timestamps agree.
        val reply = """
            {
              "id": "u-9", "email": "new@example.com",
              "confirmation_sent_at": "2026-11-15T15:51:08Z",
              "created_at": "2026-11-15T15:51:08Z",
              "identities": []
            }
        """.trimIndent()
        val result = classifySignupResponse(reply)
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun smallGapMeansNewAccount() {
        // A re-signup seconds after the first attempt is
        // indistinguishable from a new account and degrades to
        // the check-email step, never to an error.
        val reply = """
            {
              "id": "u-9", "email": "new@example.com",
              "confirmation_sent_at": "2026-11-15T15:51:30Z",
              "created_at": "2026-11-15T15:51:08Z",
              "identities": []
            }
        """.trimIndent()
        val result = classifySignupResponse(reply)
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun unparseableTimestampsMeanNewAccount() {
        val reply = """
            {
              "id": "u-9", "email": "new@example.com",
              "confirmation_sent_at": "not-a-time",
              "created_at": "also-not-a-time",
              "identities": []
            }
        """.trimIndent()
        val result = classifySignupResponse(reply)
        assertEquals(SignupResponse.NeedsEmailConfirmation, result)
    }

    @Test
    fun nestedConfirmedUserMeansAlreadyRegisteredConfirmed() {
        val result = classifySignupResponse(
            """{"user": {"id": "u-1", "email": "a@b.com", "email_confirmed_at": "2026-11-10T09:00:00Z"}}"""
        )
        assertEquals(SignupResponse.AlreadyRegistered(emailConfirmed = true), result)
    }
}
