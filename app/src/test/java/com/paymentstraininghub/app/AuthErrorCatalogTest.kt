package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.SignupError
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.data.ContentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auth error catalog mapping tests: GoTrue server messages map to
 * the catalog codes the screens show, so a learner never sees a
 * raw server string. Plus the chapter-number parser the catalogue
 * grouping depends on.
 */
class AuthErrorCatalogTest {

    @Test
    fun fromGoTrueMessage_alreadyRegistered() {
        assertEquals(
            SignupErrorCode.EMAIL_ALREADY_REGISTERED,
            SignupError.fromGoTrueMessage("User already registered").code
        )
        assertEquals(
            SignupErrorCode.EMAIL_ALREADY_REGISTERED,
            SignupError.fromGoTrueMessage("A user with this email already exists").code
        )
    }

    @Test
    fun fromGoTrueMessage_emailNotVerified() {
        assertEquals(
            SignupErrorCode.EMAIL_NOT_VERIFIED,
            SignupError.fromGoTrueMessage("Email not confirmed").code
        )
    }

    @Test
    fun fromGoTrueMessage_invalidCredentials() {
        assertEquals(
            SignupErrorCode.CREDENTIALS_INVALID,
            SignupError.fromGoTrueMessage("Invalid login credentials").code
        )
    }

    @Test
    fun fromGoTrueMessage_expiredResetLink() {
        assertEquals(
            SignupErrorCode.RESET_LINK_EXPIRED,
            SignupError.fromGoTrueMessage("Token has expired or is invalid").code
        )
    }

    @Test
    fun fromGoTrueMessage_expiredSession() {
        assertEquals(
            SignupErrorCode.SESSION_EXPIRED,
            SignupError.fromGoTrueMessage("Expired refresh token").code
        )
    }

    @Test
    fun fromGoTrueMessage_weakPassword() {
        assertEquals(
            SignupErrorCode.WEAK_PASSWORD,
            SignupError.fromGoTrueMessage("Password is too short").code
        )
        assertEquals(
            SignupErrorCode.WEAK_PASSWORD,
            SignupError.fromGoTrueMessage("Password must be at least 8 characters").code
        )
    }

    @Test
    fun fromGoTrueMessage_unknownMessageFallsBack() {
        assertEquals(
            SignupErrorCode.UNKNOWN_ERROR,
            SignupError.fromGoTrueMessage("Something completely different").code
        )
    }

    @Test
    fun fromGoTrueMessage_matchingIsCaseInsensitive() {
        assertEquals(
            SignupErrorCode.RESET_LINK_EXPIRED,
            SignupError.fromGoTrueMessage("TOKEN HAS EXPIRED OR IS INVALID").code
        )
    }
}

/**
 * Chapter-number parsing: the catalogue groups lessons by exact
 * chapter number, so "ch1" must never catch ch10 to ch19.
 */
class ChapterNumberTest {

    @Test
    fun chapterNumberFromId_parsesLessonIds() {
        assertEquals(1, ContentRepository.chapterNumberFromId("ch1s1"))
        assertEquals(10, ContentRepository.chapterNumberFromId("ch10s3"))
        assertEquals(100, ContentRepository.chapterNumberFromId("ch100s1"))
    }

    @Test
    fun chapterNumberFromId_rejectsNonLessonIds() {
        assertNull(ContentRepository.chapterNumberFromId("guide-x"))
        assertNull(ContentRepository.chapterNumberFromId("ch"))
        assertNull(ContentRepository.chapterNumberFromId(""))
        assertNull(ContentRepository.chapterNumberFromId("situation-1"))
    }
}
