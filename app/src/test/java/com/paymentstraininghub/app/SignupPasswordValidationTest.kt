// SignupPasswordValidationTest.kt
// Unit tests for the account-creation password validation: the
// length check, the exact-match comparison (never trimmed), and
// the empty-confirmation case. No em dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.ui.auth.signupPasswordError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignupPasswordValidationTest {

    @Test
    fun shortPasswordIsWeak() {
        assertEquals(SignupErrorCode.WEAK_PASSWORD, signupPasswordError("short", "short"))
        assertEquals(SignupErrorCode.WEAK_PASSWORD, signupPasswordError("", ""))
        assertEquals(SignupErrorCode.WEAK_PASSWORD, signupPasswordError("1234567", "1234567"))
    }

    @Test
    fun eightCharactersPassesTheLengthCheck() {
        assertNull(signupPasswordError("12345678", "12345678"))
    }

    @Test
    fun mismatchIsReported() {
        assertEquals(
            SignupErrorCode.PASSWORD_MISMATCH,
            signupPasswordError("password1", "password2")
        )
    }

    @Test
    fun emptyConfirmationIsAMismatch() {
        assertEquals(
            SignupErrorCode.PASSWORD_MISMATCH,
            signupPasswordError("password1", "")
        )
    }

    @Test
    fun passwordsAreComparedExactlyNeverTrimmed() {
        // A trailing space is part of the password: trimming here
        // would create an account the learner cannot sign into.
        assertEquals(
            SignupErrorCode.PASSWORD_MISMATCH,
            signupPasswordError("password1 ", "password1")
        )
        assertEquals(
            SignupErrorCode.PASSWORD_MISMATCH,
            signupPasswordError("password1", "password1 ")
        )
        assertNull(signupPasswordError("password1 ", "password1 "))
    }

    @Test
    fun lengthCheckRunsBeforeMismatch() {
        assertEquals(
            SignupErrorCode.WEAK_PASSWORD,
            signupPasswordError("short", "different")
        )
    }
}
