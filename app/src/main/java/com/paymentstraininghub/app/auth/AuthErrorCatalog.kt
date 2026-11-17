// AuthErrorCatalog.kt
// Payments Training Hub Android - shared error catalog for the
// email signup flow, the website parity contract, case IDs, and
// the password reset flow. Mirrors iOS AuthErrorCatalog.swift. The
// server codes and the customer-facing messages below are the FIXED
// contract shared with the website: the messages are verbatim from
// the canonical catalog (audit/ERROR-CODE-CATALOG.md, the ONE
// canonical source) and must not be reworded here. An unknown server
// code always falls back to UNKNOWN_ERROR, so a new server code can
// never surface raw text to a learner. Signup is email and password
// with Supabase email confirmation only: no code-based signup flow
// exists and no phone number is collected.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.auth

import org.json.JSONObject

// MARK: - Code alphabet

/**
 * The alphabet shared by case IDs and referral codes: uppercase
 * letters without I, L and O, and digits without 0 and 1, so a code
 * read aloud or typed from a screenshot cannot be confused.
 * Mirrors iOS PFCodeAlphabet.
 */
object CodeAlphabet {
    const val CHARACTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
}

// MARK: - Error catalog

/**
 * Every server error code in the signup contract, with its exact
 * customer-facing message. Add a new code here first, with the
 * website's wording, before any call site uses it. Mirrors iOS
 * PFSignupErrorCode. The [message] values are verbatim from the
 * canonical catalog document; where the iOS catalog and the document
 * were compared, all kept messages matched word for word. The reset
 * flow codes (RESET_LINK_EXPIRED, WEAK_PASSWORD, NETWORK_ERROR,
 * PASSWORD_MISMATCH) follow the same conventions and were added
 * with the email signup rework; their wording is fixed here until
 * the parallel website and iOS program publishes its revised spec,
 * at which point any divergence must be reconciled.
 */
enum class SignupErrorCode(val code: String, val message: String) {
    EMAIL_ALREADY_REGISTERED(
        "EMAIL_ALREADY_REGISTERED",
        "This email already has an account. Sign in instead."
    ),
    EMAIL_NOT_VERIFIED(
        "EMAIL_NOT_VERIFIED",
        "Verify your email address first."
    ),
    CASE_ID_INVALID(
        "CASE_ID_INVALID",
        "That case ID does not look right. Check it and try again."
    ),
    CASE_ID_NOT_FOUND(
        "CASE_ID_NOT_FOUND",
        "We cannot find that case ID. Start a new signup."
    ),
    REFERRAL_INVALID(
        "REFERRAL_INVALID",
        "That referral code is not valid."
    ),
    REFERRAL_SELF(
        "REFERRAL_SELF",
        "You cannot use your own referral code."
    ),
    REFERRAL_ALREADY_USED(
        "REFERRAL_ALREADY_USED",
        "A referral discount was already used on this account."
    ),
    TERMS_NOT_ACCEPTED(
        "TERMS_NOT_ACCEPTED",
        "Accept the terms and conditions to continue."
    ),
    CREDENTIALS_INVALID(
        "CREDENTIALS_INVALID",
        "Email or password does not match our records."
    ),
    SESSION_EXPIRED(
        "SESSION_EXPIRED",
        "Your session expired. Sign in again."
    ),
    UNKNOWN_ERROR(
        "UNKNOWN_ERROR",
        "Something went wrong. Try again, and contact support if it keeps happening."
    ),
    REQUEST_INVALID(
        "REQUEST_INVALID",
        "That request does not look right. Check the details and try again."
    ),
    CASE_EXPIRED(
        "CASE_EXPIRED",
        "No unfinished signup was found for that case ID. It may have expired. Start a new signup."
    ),
    CASE_ALREADY_COMPLETED(
        "CASE_ALREADY_COMPLETED",
        "That signup is already complete. Sign in to your account instead."
    ),
    CASE_GENERATION_FAILED(
        "CASE_GENERATION_FAILED",
        "Your signup could not be started. Please try again."
    ),
    UNAUTHENTICATED(
        "UNAUTHENTICATED",
        "Your sign-in has expired. Sign in again and try once more."
    ),
    SERVER_NOT_CONFIGURED(
        "SERVER_NOT_CONFIGURED",
        "This service is not available right now. Please try again later."
    ),
    DELETION_FAILED(
        "DELETION_FAILED",
        "Your account could not be deleted. Nothing was changed. Please try again."
    ),
    CASCADE_FAILED(
        "CASCADE_FAILED",
        "Your account could not be fully deleted, so nothing was changed. Please try again or contact support."
    ),
    METHOD_NOT_ALLOWED(
        "METHOD_NOT_ALLOWED",
        "That request is not allowed."
    ),
    RESET_LINK_EXPIRED(
        "RESET_LINK_EXPIRED",
        "This password reset link has expired or is no longer valid. Request a new link."
    ),
    WEAK_PASSWORD(
        "WEAK_PASSWORD",
        "Choose a password of at least 8 characters."
    ),
    NETWORK_ERROR(
        "NETWORK_ERROR",
        "Check your connection and try again."
    ),
    PASSWORD_MISMATCH(
        "PASSWORD_MISMATCH",
        "The passwords do not match."
    );

    companion object {
        /**
         * The catalog code for a raw server code string. Unknown,
         * missing and empty codes fall back to UNKNOWN_ERROR.
         */
        fun from(serverCode: String?): SignupErrorCode {
            if (serverCode.isNullOrBlank()) return UNKNOWN_ERROR
            return fromRaw(serverCode) ?: UNKNOWN_ERROR
        }

        /**
         * The catalog code for a raw server code string, or null
         * when the string is not a known code. Used where callers
         * must tell a known code from an unknown one.
         */
        fun fromRaw(serverCode: String?): SignupErrorCode? {
            val trimmed = serverCode?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            return SignupErrorCode.values().firstOrNull { it.code == trimmed }
        }
    }
}

/**
 * One catalog error: a code plus its verbatim message. The catalog
 * message is always shown; the server text is kept only for
 * debugging and is never shown to a learner. No catalog message
 * carries a placeholder, so no substitution step exists. Mirrors
 * iOS PFSignupError.
 */
class SignupError(
    val code: SignupErrorCode,
    val serverMessage: String? = null,
    val retryAfterSeconds: Int? = null
) : Exception() {

    /** The text to show the learner. Always the verbatim catalog message. */
    val displayMessage: String = code.message

    override val message: String
        get() = displayMessage

    companion object {
        /**
         * Parse an error body of the contract shape {code, message,
         * retry_after_seconds}. Anything unparseable, or any code
         * outside the catalog, is UNKNOWN_ERROR.
         */
        fun fromResponseBody(body: String?): SignupError {
            if (body.isNullOrBlank()) return SignupError(SignupErrorCode.UNKNOWN_ERROR)
            return try {
                val obj = JSONObject(body)
                val code = SignupErrorCode.from(
                    obj.optString("code", "").takeIf { it.isNotBlank() }
                )
                val retryAfter = if (obj.has("retry_after_seconds")) {
                    obj.optInt("retry_after_seconds")
                } else {
                    null
                }
                val serverMessage = obj.optString("message", "").takeIf { it.isNotBlank() }
                SignupError(code, serverMessage, retryAfter)
            } catch (_: Exception) {
                SignupError(SignupErrorCode.UNKNOWN_ERROR)
            }
        }

        /**
         * Parse an error body, returning null when it carries no
         * known catalog code. Used where callers need a different
         * fallback for a body that is not a catalog error at all
         * (for example a missing Edge Function on account deletion).
         */
        fun fromResponseBodyOrNull(body: String?): SignupError? {
            if (body.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(body)
                val raw = obj.optString("code", "").takeIf { it.isNotBlank() } ?: return null
                val code = SignupErrorCode.fromRaw(raw) ?: return null
                val retryAfter = if (obj.has("retry_after_seconds")) {
                    obj.optInt("retry_after_seconds")
                } else {
                    null
                }
                val serverMessage = obj.optString("message", "").takeIf { it.isNotBlank() }
                SignupError(code, serverMessage, retryAfter)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Map a GoTrue (Supabase Auth) message to the closest catalog
         * code. GoTrue sends no catalog codes, so the signup,
         * password sign-in and password reset paths match on its
         * stable message text. Anything unmatched is UNKNOWN_ERROR.
         * The mapping follows the canonical catalog's Supabase Auth
         * error mapping table. The expired or invalid token text is
         * the recovery link text: it maps to RESET_LINK_EXPIRED, and
         * it is checked before the session expiry texts, so a reset
         * link failure never reads as a session expiry.
         */
        fun fromGoTrueMessage(message: String): SignupError {
            val text = message.lowercase()
            if (text.contains("already registered") || text.contains("already exists")) {
                return SignupError(SignupErrorCode.EMAIL_ALREADY_REGISTERED, message)
            }
            if (text.contains("email not confirmed") || text.contains("not confirmed")) {
                return SignupError(SignupErrorCode.EMAIL_NOT_VERIFIED, message)
            }
            if (text.contains("invalid login credentials") || text.contains("invalid email or password")) {
                return SignupError(SignupErrorCode.CREDENTIALS_INVALID, message)
            }
            if (text.contains("token has expired or is invalid")) {
                return SignupError(SignupErrorCode.RESET_LINK_EXPIRED, message)
            }
            if (text.contains("expired token") || text.contains("refresh token")) {
                return SignupError(SignupErrorCode.SESSION_EXPIRED, message)
            }
            if (text.contains("password") &&
                (text.contains("too short") || text.contains("weak") || text.contains("at least"))
            ) {
                return SignupError(SignupErrorCode.WEAK_PASSWORD, message)
            }
            return SignupError(SignupErrorCode.UNKNOWN_ERROR, message)
        }
    }
}

// MARK: - Case ID

/**
 * A signup case ID: "PF-" plus 6 characters from the shared
 * alphabet. Mirrors iOS SignupCaseID, with the input normalisation
 * from the case ID design: surrounding whitespace trimmed, inner
 * spaces and hyphens removed, letters uppercased, so "pf 7k4q2x" and
 * "PF7K4Q2X" normalise to "PF-7K4Q2X". The Swift normaliser only
 * trims and uppercases, so it rejects those two forms; the design
 * document requires accepting them, and the Android form accepts
 * everything the Swift form accepts plus those variants. This
 * validator is for case IDs in the shared alphabet only:
 * server-issued IDs and the client-generated IDs from CaseIdStore
 * both use the same "PF-" plus 6-character scheme.
 */
object SignupCaseID {
    const val PREFIX = "PF-"
    const val SUFFIX_LENGTH = 6

    private val ALLOWED = CodeAlphabet.CHARACTERS.toSet()

    /**
     * The normalised case ID for raw input, or null when the input
     * is not a well-formed case ID after normalisation.
     */
    fun normalized(value: String): String? {
        val cleaned = value.trim().uppercase()
            .replace(" ", "")
            .replace("-", "")
        if (!cleaned.startsWith("PF")) return null
        val suffix = cleaned.removePrefix("PF")
        if (suffix.length != SUFFIX_LENGTH) return null
        if (!suffix.all { it in ALLOWED }) return null
        return PREFIX + suffix
    }

    /**
     * True when the value is exactly "PF-" plus 6 alphabet
     * characters. Callers normalise first; this checks the exact
     * shape, so lowercase or unhyphenated input must go through
     * [normalized] first.
     */
    fun isValid(value: String): Boolean {
        if (!value.startsWith(PREFIX)) return false
        val suffix = value.removePrefix(PREFIX)
        if (suffix.length != SUFFIX_LENGTH) return false
        return suffix.all { it in ALLOWED }
    }
}
