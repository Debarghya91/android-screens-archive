// CaseIdStore.kt
// Payments Training Hub Android - the case ID for an account, shown
// in the registration welcome popup and on the Profile screen.
//
// CANONICAL SCHEME (reconciled 13 Nov 2026 with the website and iOS
// spec): "PF-" plus 6 characters from the shared alphabet
// ABCDEFGHJKMNPQRSTUVWXYZ23456789 (no 0, O, 1, I or L), generated
// from a secure random source at signup start and sent in the
// Supabase signup body as user metadata signup_case_id, so the
// website, iOS and support all see the same ID for the same
// account. The value is persisted per account in AppStore under
// its base key, so account deletion removes it with the rest of
// the namespace. No phone number or other personal data is
// involved.
//
// This replaced a provisional hash-derived scheme (SHA-256 of the
// user id) whose output could contain 0 and 1, outside the shared
// alphabet. No build ever shipped the provisional scheme, so a
// stored value that fails validation is treated as absent and
// replaced with a fresh generated ID.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.auth

import java.security.SecureRandom

object CaseIdStore {

    /** The AppStore base key for the persisted case ID. Namespaced per account by AppStore. */
    const val CASE_ID_BASE_KEY = "pf.caseId.v1"

    /** Canonical alphabet: no 0, O, 1, I or L. Matches website and iOS. */
    const val CASE_ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private val CASE_ID_REGEX =
        Regex("^PF-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$")

    fun isValid(caseId: String?): Boolean =
        caseId != null && CASE_ID_REGEX.matches(caseId.trim().uppercase())

    fun normalized(caseId: String?): String? {
        val cleaned = caseId?.trim()?.uppercase().orEmpty()
        return if (CASE_ID_REGEX.matches(cleaned)) cleaned else null
    }

    /** "PF-" plus 6 random characters from the canonical alphabet. */
    fun generate(): String {
        val random = SecureRandom()
        val out = StringBuilder("PF-")
        repeat(6) { out.append(CASE_ID_ALPHABET[random.nextInt(CASE_ID_ALPHABET.length)]) }
        return out.toString()
    }

    /**
     * The case ID stored for the current account, or null when none
     * is stored yet. Reads only; callers that need a value use
     * [ensureCaseId]. A stored value that fails validation (for
     * example a provisional hash-derived ID from an unshipped
     * build) reads as no stored value. Never throws: a storage
     * failure reads as no stored value.
     */
    suspend fun loadCaseId(): String? {
        return try {
            normalized(AppStore.getString(CASE_ID_BASE_KEY))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The case ID for the current account: the stored canonical ID
     * when one exists, otherwise a fresh generated ID, persisted
     * for the account before it is returned. A failed persist
     * still returns the generated value for this run; the next
     * call generates and persists again. The userId parameter is
     * kept only for call-site compatibility and is no longer an
     * input to the ID. The caller must call this while the account
     * namespace is current (AuthManager after the namespace
     * switch, or a screen while signed in), so the value lands
     * under the right account.
     */
    suspend fun ensureCaseId(userId: String): String {
        val stored = loadCaseId()
        if (stored != null) return stored
        val fresh = generate()
        try {
            AppStore.putString(CASE_ID_BASE_KEY, fresh)
        } catch (_: Exception) {
            // The generated value still stands for this run; the
            // next call generates and persists it again.
        }
        return fresh
    }
}
