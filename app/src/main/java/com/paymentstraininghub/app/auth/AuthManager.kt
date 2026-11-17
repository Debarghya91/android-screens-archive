package com.paymentstraininghub.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The outcome of an email password signup: signed in at once
 * (the project confirms email automatically), or created but waiting
 * for the learner to open the email confirmation link before signing
 * in. Mirrors iOS PasswordSignupResult.
 */
sealed interface PasswordSignupResult {
    data object SignedIn : PasswordSignupResult
    data object NeedsEmailConfirmation : PasswordSignupResult

    /**
     * The address was already registered: no new account was
     * created and the password just entered was not saved.
     * [emailConfirmed] tells the screen whether to offer sign-in
     * (confirmed) or the confirmation resend (unconfirmed).
     */
    data class AlreadyRegistered(val emailConfirmed: Boolean) : PasswordSignupResult
}

/**
 * A password sign-in failure that carries guiding text alongside
 * the verbatim catalog message. GoTrue deliberately returns the
 * same "Invalid login credentials" error for a wrong password,
 * an unconfirmed address, and an account with no password at
 * all, so the message alone can never guide the learner: the
 * screen shows [guidance] under it, with the actions that fit.
 * [message] stays the catalog message, so callers that match on
 * it (the email signup screen) keep working unchanged.
 */
class SignInGuidanceError(
    val code: SignupErrorCode,
    val guidance: String,
    cause: Throwable? = null
) : Exception(code.message, cause)

/**
 * Owns the signed in state for the whole app, mirroring iOS
 * AuthService. Content is browsable without a session: a guest
 * sees every screen, with paid content locked to its free
 * samples, and sign-in is prompted only when the learner taps a
 * Buy action while signed out. After sign-in the learner
 * returns to the purchase. The owner's account carries the
 * admin flag in its session token (see [isAdmin]): an admin
 * session unlocks all content and is never prompted to buy.
 *
 * Sessions live in EncryptedSharedPreferences, never in DataStore:
 * tokens are secrets. When Supabase cannot be reached, every method
 * fails gracefully with a friendly message and browsing continues
 * as a guest: content never depends on a session.
 *
 * Account creation is email and password: terms acceptance blocks
 * account creation, email confirmation verifies the address, and
 * the once-only welcome popup is shown when the first session for
 * the new account is stored. Signup collects email and password
 * only. See [signUpWithPassword]. Sign-in is email and password,
 * or an emailed 8-digit code: the code path requests POST
 * /auth/v1/otp and verifies with POST /auth/v1/verify, trying the
 * email type first and then the signup type, because a code from
 * the confirm-signup email only verifies as a signup token. See
 * [sendEmailCode] and [verifyEmailCode].
 *
 * Account deletion goes through the delete-account edge function,
 * which deletes the Auth user with the service role key. The app
 * never deletes only locally and calls it done: local state is
 * cleared only after the function confirms with {"ok": true}.
 *
 * Sign out switches the AppStore namespace to guest. The signed out
 * account's stored data is NOT wiped: it stays under its own
 * namespace and loads again on the next sign in, so a different
 * account signing in next can never see it. Signing out returns
 * the app to guest browsing: content stays browsable with paid
 * content locked, and the guest namespace holds only data from
 * before sign-in.
 *
 * Email and password, or an emailed code. There is no
 * third-party sign-in on Android: that path was removed from
 * scope, and no sign-in button for it exists in the auth flow.
 */
object AuthManager {

    sealed interface AuthState {
        data object Guest : AuthState
        data class SignedIn(val userId: String, val email: String) : AuthState
    }

    /**
     * Hook for the sync owner (Worker G) to drop pending sync work
     * when an account is deleted. It runs only after the server has
     * confirmed the deletion, before the account namespace is
     * cleared. A throwing hook never blocks the deletion clearing.
     * Set it to a lambda that launches its own coroutine when the
     * work is suspending.
     */
    var onDeleteAccountLocal: (() -> Unit)? = null

    private const val PREFS_NAME = "pf_session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"

    /**
     * The per-account flag key for the registration welcome popup.
     * Same base key as iOS (pf.welcomeShown.v1). Written when the
     * popup is first shown, so it appears once, on the first sign-in
     * after registration, and never again.
     */
    private const val WELCOME_SHOWN_BASE_KEY = "pf.welcomeShown.v1"

    /**
     * The base key for the pending registration email, written in
     * the guest namespace at signup time and consumed by the first
     * session stored for that email. It exists so the welcome popup
     * still shows when email confirmation delays the first sign-in
     * past an app restart: the guest namespace is readable before
     * the namespace switch in [saveSession], and the record is
     * removed there before the switch, so it is consumed at most
     * once. It holds only an email address, never a password or a
     * token. It replaces the pre-account pending record, which no
     * longer exists.
     */
    private const val PENDING_WELCOME_EMAIL_BASE_KEY = "pf.registration.pendingEmail.v1"
    private const val PENDING_WELCOME_CASE_ID_BASE_KEY = "pf.registration.pendingCaseId.v1"

    // Supabase Auth tokens carry no expiry in AuthTokens (the shared
    // contract has no expiresIn field), so sessions are treated as
    // lasting the Supabase default of one hour. Refresh is cheap and
    // runs 60 seconds early, so a shorter server lifetime only means
    // one extra refresh, never a stale token being used.
    private const val DEFAULT_SESSION_SECONDS = 3600L
    private const val REFRESH_SKEW_MS = 60_000L

    private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * True once [init] has finished restoring the session from
     * storage, whether or not a session was found. Deep-link
     * consumers wait on it before routing a reset link, so a
     * reset never races the restore. Content never waits on it:
     * the app opens to browsable content while the restore runs.
     * It stays true for the rest of the process: later sign-ins
     * and sign-outs move [authState] only.
     */
    private val _authReady = MutableStateFlow(false)
    val authReady: StateFlow<Boolean> = _authReady.asStateFlow()

    /**
     * True when the signed-in account is an admin: its session
     * token carries app_metadata.is_admin = true (see
     * [AdminClaims]). Read from the token on every session store
     * and restore, and cleared with the session, so it can never
     * outlive the session that granted it. Admin unlocks every
     * product through the entitlement checks and is never
     * prompted to buy. False for guests.
     */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /**
     * Test seam: set the auth state, readiness, and admin flag
     * directly, without touching encrypted storage or the
     * network. Used by tests that render the navigation graph,
     * which reads [authReady], [authState], and [isAdmin].
     * Production code never calls this: only [init], sign-in,
     * sign-out and deletion move the state. The cached identity
     * follows the state, so [currentUserId] agrees with it; no
     * token is faked. [isAdmin] is honoured only for a signed-in
     * state: a guest is never admin.
     */
    internal fun setTestAuthState(state: AuthState, ready: Boolean, isAdmin: Boolean = false) {
        if (state is AuthState.SignedIn) {
            cachedUserId = state.userId
            cachedEmail = state.email
        } else {
            cachedUserId = null
            cachedEmail = ""
        }
        _isAdmin.value = isAdmin && state is AuthState.SignedIn
        _authState.value = state
        _authReady.value = ready
    }

    /**
     * True while the registration welcome popup should show: the
     * first sign-in right after a registration completes (email and
     * password set, terms accepted). The once-only rule
     * lives in the pf.welcomeShown.v1 flag, written when the popup
     * is first shown, so it never appears on a later login. Mirrors
     * iOS AuthService.showRegistrationWelcome.
     */
    private val _showRegistrationWelcome = MutableStateFlow(false)
    val showRegistrationWelcome: StateFlow<Boolean> = _showRegistrationWelcome.asStateFlow()

    /** The case ID shown in the registration welcome popup, in PF-XXXXXX form. Null when no popup is showing. */
    private val _registrationWelcomeCaseID = MutableStateFlow<String?>(null)
    val registrationWelcomeCaseID: StateFlow<String?> = _registrationWelcomeCaseID.asStateFlow()

    /**
     * Whether the signed-in account's email address is verified,
     * from the last GET /auth/v1/user refresh. Null when unknown
     * (guest, or no refresh has run yet).
     */
    private val _emailVerified = MutableStateFlow<Boolean?>(null)
    val emailVerified: StateFlow<Boolean?> = _emailVerified.asStateFlow()

    private var appContext: Context? = null
    private var prefsCache: SharedPreferences? = null
    private var initialised = false
    private val refreshMutex = Mutex()

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var cachedRefreshToken: String? = null

    @Volatile
    private var cachedExpiresAtMs: Long = 0L

    @Volatile
    private var cachedUserId: String? = null

    @Volatile
    private var cachedEmail: String = ""

    /**
     * The PKCE verifier for an in-flight Google sign-in, held in
     * memory only. It is consumed once by the callback exchange;
     * when the app is killed mid-flow it is gone and the learner
     * signs in again, which is the safe failure: a code without
     * its verifier can never be exchanged.
     */
    @Volatile
    private var pendingPkceVerifier: String? = null

    /**
     * Restore the session from encrypted prefs, set the AppStore
     * namespace, and refresh the token when it is expired. Best
     * effort: init never throws. A refresh rejected by the server
     * (400/401) clears the dead session to guest; a network failure
     * keeps the stored session so a later call can retry.
     * [authReady] is set as soon as the stored state is known, before
     * the refresh attempt, so no consumer waits on a network call:
     * a rejected refresh flips the state to guest.
     */
    suspend fun init(context: Context) {
        if (initialised) {
            _authReady.value = true
            return
        }
        initialised = true
        val app = context.applicationContext
        appContext = app
        AppStore.init(app)
        prefsCache = try {
            createEncryptedPrefs(app)
        } catch (_: Exception) {
            null
        }
        val prefs = prefsCache
        val access = prefs?.getString(KEY_ACCESS, null)
        val refresh = prefs?.getString(KEY_REFRESH, null)
        val userId = prefs?.getString(KEY_USER_ID, null)
        val email = prefs?.getString(KEY_EMAIL, null).orEmpty()
        val expiresAt = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || userId.isNullOrBlank()) {
            clearLocalSession()
            AppStore.setCurrentUserId(null)
            _authState.value = AuthState.Guest
            _authReady.value = true
            reloadIdentityStores()
            return
        }
        cachedAccessToken = access
        cachedRefreshToken = refresh
        cachedExpiresAtMs = expiresAt
        cachedUserId = userId
        cachedEmail = email
        _isAdmin.value = AdminClaims.isAdminToken(access)
        AppStore.setCurrentUserId(userId)
        _authState.value = AuthState.SignedIn(userId, email)
        _authReady.value = true
        // No migration offer on a launch restore: the offer belongs
        // to a fresh sign in, not to every app launch.
        reloadIdentityStores()
        if (expiresAt <= System.currentTimeMillis() + REFRESH_SKEW_MS) {
            tryRefreshOrKeepSession()
        }
    }

    suspend fun signInEmail(email: String, password: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isValidAuthEmail(cleanEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        if (password.isEmpty()) {
            return Result.failure(Exception("Enter your password."))
        }
        return try {
            val tokens = SupabaseClient.signInWithPassword(cleanEmail, password)
            saveSession(tokens)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(signInFailure(e))
        }
    }

    /**
     * Request an emailed 8-digit sign-in code for an address. The
     * code expires after one hour. A new address creates an
     * unconfirmed user server-side; the code still arrives,
     * because both email templates carry the code line. Failures
     * map through [otpAuthMessage], never through the reset-link
     * wording, so a code problem never reads as a reset problem.
     */
    suspend fun sendEmailCode(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isValidAuthEmail(cleanEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        return try {
            SupabaseClient.sendEmailOtp(cleanEmail)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(otpAuthMessage(e)))
        }
    }

    /**
     * Exchange an emailed code for a session. Spaces and dashes
     * are stripped before validation, so a pasted code verifies.
     * The verify tries the email type first and falls back to the
     * signup type (see SupabaseClient.verifyEmailOtp), so a code
     * from the confirm-signup email signs in too. A successful
     * verify stores the session exactly like a password sign-in,
     * so the learner is signed in the same way.
     */
    suspend fun verifyEmailCode(email: String, code: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isValidAuthEmail(cleanEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        val cleanCode = normalizeEmailCode(code)
        if (!isValidEmailCode(cleanCode)) {
            return Result.failure(Exception("Enter the 8-digit code from the email."))
        }
        return try {
            val tokens = SupabaseClient.verifyEmailOtp(cleanEmail, cleanCode)
            saveSession(tokens)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(otpAuthMessage(e)))
        }
    }

    /**
     * Send a fresh confirmation email to an address, without
     * requiring a session. The sign-in screen offers this when a
     * password sign-in fails on an address that may not be
     * confirmed yet: GoTrue returns the same invalid-credentials
     * error for an unconfirmed address as for a wrong password,
     * so the learner is offered the confirmation path instead of
     * being told only that the password is wrong.
     */
    suspend fun resendConfirmationEmail(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isValidAuthEmail(cleanEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        return try {
            SupabaseClient.resendVerificationEmail(cleanEmail)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    /**
     * Start Google sign-in: generate a PKCE verifier, keep it in
     * memory, and return the authorisation URL for the browser.
     * The provider returns to the app's auth-callback deep link
     * with a code, and [completeGoogleSignIn] exchanges it. No
     * network call happens here, so this never fails.
     */
    fun beginGoogleSignIn(): String {
        val verifier = generatePkceVerifier()
        pendingPkceVerifier = verifier
        return buildOAuthAuthorizeUrl(
            provider = "google",
            redirectTo = SupabaseClient.OAUTH_REDIRECT_URL,
            codeChallenge = pkceCodeChallenge(verifier)
        )
    }

    /**
     * Finish Google sign-in with the authorisation code from the
     * auth-callback deep link. The stored verifier is consumed
     * exactly once: a second completion, or a completion after
     * the app was killed mid-flow, fails with the interrupted
     * message and the learner signs in again. A successful
     * exchange stores the session exactly like a password
     * sign-in, so the learner is signed in the same way.
     */
    suspend fun completeGoogleSignIn(code: String): Result<Unit> {
        val verifier = pendingPkceVerifier
        pendingPkceVerifier = null
        if (verifier.isNullOrBlank() || code.isBlank()) {
            return Result.failure(Exception("Google sign-in was interrupted. Try again."))
        }
        return try {
            val tokens = SupabaseClient.exchangePkceCode(code.trim(), verifier)
            saveSession(tokens)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    /** Drop a pending Google sign-in, for example when the learner leaves the auth flow. */
    fun cancelGoogleSignIn() {
        pendingPkceVerifier = null
    }

    suspend fun signUpEmail(email: String, password: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!isValidAuthEmail(cleanEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(Exception("Choose a password of at least 6 characters."))
        }
        return try {
            val tokens = SupabaseClient.signUp(cleanEmail, password)
            saveSession(tokens)
            Result.success(Unit)
        } catch (e: SupabaseException) {
            if (e.serverMessage?.trim()?.equals("email confirmation required", ignoreCase = true) == true) {
                Result.failure(Exception("Check your email to confirm your account, then sign in."))
            } else {
                Result.failure(Exception(passwordAuthMessage(e)))
            }
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    /**
     * Create an account with email and password, mirroring iOS
     * AuthService.signUpWithPassword. The guards run in this order:
     * terms must be accepted (TERMS_NOT_ACCEPTED otherwise), the
     * email must be valid, and the password must be at least 8
     * characters (WEAK_PASSWORD otherwise). The signup body carries
     * the terms acceptance time and the case ID (generated once at
     * signup start) as user metadata. The pending registration
     * email and case ID are recorded before the session decision,
     * so the first session for this email shows the welcome popup
     * with the same case ID that was sent at signup, even when
     * email confirmation delays the sign-in. A failure
     * carries a catalog message (EMAIL_ALREADY_REGISTERED when the
     * email is taken). Signup collects email and password only.
     */
    suspend fun signUpWithPassword(
        email: String,
        password: String,
        termsAccepted: Boolean
    ): Result<PasswordSignupResult> {
        val trimmedEmail = email.trim().lowercase()
        if (!termsAccepted) {
            return Result.failure(Exception(SignupErrorCode.TERMS_NOT_ACCEPTED.message))
        }
        if (!isValidAuthEmail(trimmedEmail)) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        if (password.length < 8) {
            return Result.failure(Exception(SignupErrorCode.WEAK_PASSWORD.message))
        }
        return try {
            // Generated once, before the network call, and sent in
            // the signup body as signup_case_id: the same ID the
            // welcome popup shows and the website and iOS see.
            val caseId = CaseIdStore.generate()
            val response = SupabaseClient.signUpWithMetadata(
                email = trimmedEmail,
                password = password,
                termsAcceptedAt = iso8601Now(),
                caseId = caseId
            )
            when (response) {
                is SignupResponse.SignedIn -> {
                    // The pending registration email and case ID
                    // are recorded before the session decision, so
                    // the first session for this email shows the
                    // welcome popup with the same case ID that was
                    // sent at signup, even when email confirmation
                    // delays the sign-in. A duplicate signup records
                    // nothing: no new account exists to welcome.
                    recordPendingWelcomeEmail(trimmedEmail)
                    recordPendingWelcomeCaseId(caseId)
                    saveSession(response.tokens)
                    Result.success(PasswordSignupResult.SignedIn)
                }
                SignupResponse.NeedsEmailConfirmation -> {
                    recordPendingWelcomeEmail(trimmedEmail)
                    recordPendingWelcomeCaseId(caseId)
                    Result.success(PasswordSignupResult.NeedsEmailConfirmation)
                }
                is SignupResponse.AlreadyRegistered -> {
                    Result.success(PasswordSignupResult.AlreadyRegistered(response.emailConfirmed))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    /**
     * Dismiss the registration welcome popup. The shown flag was
     * already written when the popup appeared, so this only clears
     * the presentation state. Mirrors iOS dismissRegistrationWelcome.
     */
    fun dismissRegistrationWelcome() {
        _showRegistrationWelcome.value = false
        _registrationWelcomeCaseID.value = null
    }

    /**
     * Sign out: tell Supabase (best effort), then clear the session
     * and switch the AppStore namespace to guest. The account's
     * stored data is NOT wiped: it reloads on the next sign in.
     */
    suspend fun signOut() {
        val token = cachedAccessToken
        if (token != null) {
            try {
                SupabaseClient.signOut(token)
            } catch (_: Exception) {
                // Sign out is local first: a failed server logout
                // never keeps the user signed in here.
            }
        }
        clearLocalSession()
        AppStore.setCurrentUserId(null)
        _authState.value = AuthState.Guest
        _emailVerified.value = null
        dismissRegistrationWelcome()
        reloadIdentityStores()
    }

    /**
     * Delete the account through the delete-account edge function.
     * On failure, or when the function does not confirm, this
     * returns failure and clears NOTHING: the session stays and no
     * local data is touched. Only after the function confirms with
     * {"ok": true} does local clearing run: pending sync work is
     * dropped through [onDeleteAccountLocal], every AppStore key in
     * the deleted account's namespace is removed (which takes the
     * case ID, the welcome flag and the referral credit balance
     * with it), the session and encrypted prefs are cleared, and the app returns to guest. Purchase
     * records are untouched: Google Play owns those, and the
     * entitlement cache for the account goes with the namespace
     * clear. Server purchase rows are retained anonymized: the
     * user_purchases foreign key is ON DELETE SET NULL, so
     * deleting the account nulls user_id on its purchase rows
     * and keeps them for tax and refund audit (retention
     * Option A, decided 13 Nov 2026).
     */
    suspend fun deleteAccount(): Result<Unit> {
        val userId = cachedUserId
            ?: return Result.failure(Exception(SignupErrorCode.SESSION_EXPIRED.message))
        val token = validAccessToken()
            ?: return Result.failure(Exception(SignupErrorCode.SESSION_EXPIRED.message))
        if (cachedUserId != userId) {
            return Result.failure(Exception("Your session expired. Sign in again and try again."))
        }
        val confirmed: Boolean = try {
            val response = SupabaseClient.invokeFunction("delete-account", token, "{}")
            try {
                JSONObject(response).optBoolean("ok", false)
            } catch (_: Exception) {
                false
            }
        } catch (e: SupabaseException) {
            // A catalog error body (DELETION_FAILED, CASCADE_FAILED,
            // UNAUTHENTICATED) carries its own message. Anything
            // else means the function is missing, not deployed, or
            // rejected the call: nothing is cleared.
            val catalogError = SignupError.fromResponseBodyOrNull(e.body)
            if (catalogError != null) {
                return Result.failure(Exception(catalogError.displayMessage))
            }
            return Result.failure(
                Exception("Account deletion is not available yet. Please try again later.")
            )
        } catch (e: Exception) {
            return Result.failure(Exception(passwordAuthMessage(e)))
        }
        if (!confirmed) {
            return Result.failure(
                Exception("Account deletion is not available yet. Please try again later.")
            )
        }
        // The server confirmed the deletion. Only now is local state
        // cleared: on any failure above, the session stays and
        // nothing is touched.
        try {
            onDeleteAccountLocal?.invoke()
        } catch (_: Exception) {
            // A failing hook never blocks deletion clearing.
        }
        try {
            AppStore.clearNamespace(userId)
        } catch (_: Exception) {
            // The account id can never sign in again, so orphaned
            // keys under it are unreachable. Still clear the session.
        }
        clearLocalSession()
        AppStore.setCurrentUserId(null)
        _authState.value = AuthState.Guest
        _emailVerified.value = null
        dismissRegistrationWelcome()
        reloadIdentityStores()
        return Result.success(Unit)
    }

    fun currentUserId(): String? = cachedUserId

    /**
     * True when the current session belongs to an admin account:
     * its token carries app_metadata.is_admin = true. Read from
     * the cached flag, which [saveSession] and [init] set from
     * the token and [clearLocalSession] clears with the session.
     * Entitlement checks call this synchronously at gate time.
     */
    fun isCurrentUserAdmin(): Boolean = _isAdmin.value

    /**
     * The cached access token, which may be expired. Prefer
     * [validAccessToken] for server calls.
     */
    fun accessToken(): String? = cachedAccessToken

    /**
     * A valid access token, refreshing it first when it is expired
     * or about to expire. Returns null when there is no session, or
     * when a refresh fails. A refresh rejected by the server
     * (400/401) clears the dead session to guest; a transient
     * failure keeps the stored session so the next call can retry.
     * Concurrent callers share one refresh through a mutex.
     */
    suspend fun validAccessToken(): String? {
        val token = cachedAccessToken ?: return null
        if (cachedExpiresAtMs > System.currentTimeMillis() + REFRESH_SKEW_MS) {
            return token
        }
        return refreshMutex.withLock {
            val current = cachedAccessToken ?: return@withLock null
            if (cachedExpiresAtMs > System.currentTimeMillis() + REFRESH_SKEW_MS) {
                return@withLock current
            }
            val refresh = cachedRefreshToken ?: return@withLock null
            try {
                val tokens = SupabaseClient.refreshToken(refresh)
                saveSession(tokens)
                tokens.accessToken
            } catch (e: SupabaseException) {
                if (e.status == 400 || e.status == 401) {
                    // A rejected refresh means the session is dead.
                    // Clear it so the app falls back to guest
                    // instead of retrying forever.
                    clearLocalSession()
                    AppStore.setCurrentUserId(null)
                    _authState.value = AuthState.Guest
                    _emailVerified.value = null
                    reloadIdentityStores()
                }
                null
            } catch (_: Exception) {
                // Transient failure: keep the stored session so the
                // next call retries instead of signing the user out
                // for being offline.
                null
            }
        }
    }

    /**
     * Re-read the signed-in user from Supabase Auth and update the
     * email verification status. Silent on failure: a stale badge
     * is better than an error for a status refresh. Mirrors iOS
     * AuthService.refreshVerificationStatus.
     */
    suspend fun refreshVerificationStatus() {
        val token = try {
            validAccessToken()
        } catch (_: Exception) {
            null
        } ?: return
        val userId = cachedUserId ?: return
        try {
            val body = SupabaseClient.getUser(token)
            val obj = JSONObject(body)
            if (obj.optString("id", "") != userId) return
            _emailVerified.value = !obj.isNull("email_confirmed_at") &&
                obj.optString("email_confirmed_at", "").isNotBlank()
        } catch (_: Exception) {
            // Keep the last known status; the next sign-in refreshes it.
        }
    }

    /**
     * Send a fresh verification email to the signed-in user's
     * address. The link confirms the address; the user stays signed
     * in either way. Mirrors iOS AuthService.resendVerificationEmail.
     */
    suspend fun resendVerificationEmail(): Result<Unit> {
        val email = cachedEmail.trim()
        if (cachedUserId == null || email.isEmpty()) {
            return Result.failure(Exception("You are not signed in. Sign in and try again."))
        }
        return try {
            SupabaseClient.resendVerificationEmail(email)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    /**
     * Send a password reset email to an address. The caller shows
     * the same generic success message whether or not an account
     * exists for the address, so this failure carries only a
     * validation or network message: a transport failure is
     * NETWORK_ERROR, anything else is UNKNOWN_ERROR, and neither
     * reveals whether the address has an account.
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Result.failure(Exception("Enter a valid email address."))
        }
        return try {
            SupabaseClient.sendPasswordResetEmail(cleanEmail)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(Exception(SignupErrorCode.NETWORK_ERROR.message))
        } catch (e: Exception) {
            Result.failure(Exception(SignupErrorCode.UNKNOWN_ERROR.message))
        }
    }

    /**
     * Set a new password with a recovery access token from a reset
     * link. The password must be at least 8 characters
     * (WEAK_PASSWORD otherwise). An expired or invalid token maps
     * to RESET_LINK_EXPIRED through the GoTrue mapping, so the
     * reset screen can offer a new link. No session is stored: the
     * learner signs in with the new password afterwards.
     */
    suspend fun updatePasswordWithRecoveryToken(token: String, newPassword: String): Result<Unit> {
        if (token.isBlank()) {
            return Result.failure(Exception(SignupErrorCode.RESET_LINK_EXPIRED.message))
        }
        if (newPassword.length < 8) {
            return Result.failure(Exception(SignupErrorCode.WEAK_PASSWORD.message))
        }
        return try {
            SupabaseClient.updatePasswordWithRecoveryToken(token, newPassword)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(passwordAuthMessage(e)))
        }
    }

    private suspend fun tryRefreshOrKeepSession() {
        val refresh = cachedRefreshToken ?: return
        try {
            val tokens = SupabaseClient.refreshToken(refresh)
            saveSession(tokens)
        } catch (e: SupabaseException) {
            if (e.status == 400 || e.status == 401) {
                clearLocalSession()
                AppStore.setCurrentUserId(null)
                _authState.value = AuthState.Guest
                _emailVerified.value = null
                reloadIdentityStores()
            }
        } catch (_: Exception) {
            // Keep the stored session; validAccessToken retries later.
        }
    }

    private suspend fun saveSession(tokens: AuthTokens) {
        // Take a pending registration BEFORE the namespace switch,
        // while the guest namespace is still current: the pending
        // email lives there. Only a fresh sign-in (from guest) can
        // consume one; a token refresh for a signed-in account
        // never touches it.
        val wasGuest = _authState.value is AuthState.Guest
        val pendingEmail = if (wasGuest) takePendingWelcomeEmail() else null
        val pendingCaseId = if (wasGuest) takePendingWelcomeCaseId() else null
        val expiresAt = System.currentTimeMillis() + DEFAULT_SESSION_SECONDS * 1000L
        cachedAccessToken = tokens.accessToken
        cachedRefreshToken = tokens.refreshToken
        cachedExpiresAtMs = expiresAt
        cachedUserId = tokens.userId
        cachedEmail = tokens.email
        _isAdmin.value = AdminClaims.isAdminToken(tokens.accessToken)
        try {
            prefsCache?.edit()
                ?.putString(KEY_ACCESS, tokens.accessToken)
                ?.putString(KEY_REFRESH, tokens.refreshToken)
                ?.putLong(KEY_EXPIRES_AT, expiresAt)
                ?.putString(KEY_USER_ID, tokens.userId)
                ?.putString(KEY_EMAIL, tokens.email)
                ?.apply()
        } catch (_: Exception) {
            // The in memory session stands for this run even when
            // persistence fails.
        }
        // Switch namespaces BEFORE publishing the signed in state,
        // so no observer can read the new account through the
        // previous account's store state.
        AppStore.setCurrentUserId(tokens.userId)
        _authState.value = AuthState.SignedIn(tokens.userId, tokens.email)
        _emailVerified.value = null
        // Show the welcome popup after the namespace switch, so the
        // case ID and the welcome flag are written for this account.
        consumePendingWelcome(tokens, pendingEmail, pendingCaseId)
        reloadIdentityStores()
    }

    /**
     * Record the email of a registration waiting for its first
     * sign-in, in the current (guest) namespace. A lost record only
     * means no welcome popup for this signup; the account itself is
     * unaffected. Never throws.
     */
    private suspend fun recordPendingWelcomeEmail(email: String) {
        try {
            AppStore.putString(PENDING_WELCOME_EMAIL_BASE_KEY, email)
        } catch (_: Exception) {
            // A lost pending record only means no welcome popup.
        }
    }

    /**
     * Take the pending registration email, removing it. Returns null
     * when there is none. Taking it here means it is consumed at
     * most once, by the first session stored after registration.
     * Never throws.
     */
    private suspend fun takePendingWelcomeEmail(): String? {
        return try {
            val pending = AppStore.getString(PENDING_WELCOME_EMAIL_BASE_KEY)
                ?.trim()?.takeIf { it.isNotEmpty() }
            try {
                AppStore.remove(PENDING_WELCOME_EMAIL_BASE_KEY)
            } catch (_: Exception) {
                // The record was read; a failed remove only risks a
                // second consume, which the welcome flag still guards.
            }
            pending
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Record the case ID generated for a registration waiting for
     * its first sign-in, in the current (guest) namespace, beside
     * the pending email. A lost record only means the welcome
     * popup shows a freshly generated ID; the account itself is
     * unaffected. Never throws.
     */
    private suspend fun recordPendingWelcomeCaseId(caseId: String) {
        try {
            AppStore.putString(PENDING_WELCOME_CASE_ID_BASE_KEY, caseId)
        } catch (_: Exception) {
            // A lost pending record only means a fresh ID at welcome.
        }
    }

    /**
     * Take the pending registration case ID, removing it. Returns
     * null when there is none. Consumed at most once, by the first
     * session stored after registration, beside the pending email.
     * Never throws.
     */
    private suspend fun takePendingWelcomeCaseId(): String? {
        return try {
            val pending = AppStore.getString(PENDING_WELCOME_CASE_ID_BASE_KEY)
                ?.trim()?.takeIf { it.isNotEmpty() }
            try {
                AppStore.remove(PENDING_WELCOME_CASE_ID_BASE_KEY)
            } catch (_: Exception) {
                // The record was read; a failed remove only risks a
                // second consume, which the welcome flag still guards.
            }
            pending
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Show the welcome popup once for a newly stored session when a
     * pending registration email matches the session email, with
     * the account's case ID from CaseIdStore. The pending email is
     * only honoured when it matches, so a welcome can never land on
     * the wrong account; a mismatch drops it. The popup flag is
     * written at show time, so killing the app while the popup is
     * up can never make it appear again. Mirrors iOS
     * the iOS pending-registration handling in AuthService. Never throws: a failure
     * here must never break sign-in.
     */
    private suspend fun consumePendingWelcome(
        tokens: AuthTokens,
        pendingEmail: String?,
        pendingCaseId: String?
    ) {
        try {
            if (pendingEmail == null) return
            if (tokens.email.trim().lowercase() != pendingEmail.trim().lowercase()) return
            val alreadyShown = try {
                AppStore.getBoolean(WELCOME_SHOWN_BASE_KEY) == true
            } catch (_: Exception) {
                false
            }
            if (alreadyShown) return
            try {
                AppStore.putBoolean(WELCOME_SHOWN_BASE_KEY, true)
            } catch (_: Exception) {
                // The popup still shows this once; a missed flag only
                // risks a repeat, never a lost welcome.
            }
            // The case ID sent at signup is the account's case ID.
            // Persist it for this account when it is a valid
            // canonical ID, so the popup and the Profile screen
            // show the same ID the server received. When there is
            // no pending ID, or it fails validation, fall back to
            // the stored or freshly generated ID.
            val signupCaseId = CaseIdStore.normalized(pendingCaseId)
            if (signupCaseId != null) {
                try {
                    AppStore.putString(CaseIdStore.CASE_ID_BASE_KEY, signupCaseId)
                } catch (_: Exception) {
                    // The fallback below generates an ID for this run.
                }
            }
            _registrationWelcomeCaseID.value = CaseIdStore.ensureCaseId(tokens.userId)
            _showRegistrationWelcome.value = true
        } catch (_: Exception) {
            // Sign-in stands even when the welcome bookkeeping fails.
        }
    }

    /**
     * Reload the account-scoped stores for the current namespace, so
     * no account's referral state can linger on screen after an
     * identity transition. Mirrors the store reload half of iOS
     * AuthService.reloadAllStores for the stores this workstream
     * owns. Never throws.
     */
    private suspend fun reloadIdentityStores() {
        try {
            ReferralStore.reloadForCurrentAccount()
        } catch (_: Exception) {
            // Stores keep their last value; screens reload again on entry.
        }
    }

    private fun clearLocalSession() {
        cachedAccessToken = null
        cachedRefreshToken = null
        cachedExpiresAtMs = 0L
        cachedUserId = null
        cachedEmail = ""
        _isAdmin.value = false
        try {
            prefsCache?.edit()?.clear()?.apply()
        } catch (_: Exception) {
            // Clearing is best effort; the in memory session is gone.
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Turn a password sign-in failure into the failure the screen
     * shows. Failures whose catalog code carries guidance (an
     * unconfirmed address, or the invalid-credentials error that
     * also covers unconfirmed addresses and passwordless
     * accounts) become a [SignInGuidanceError], so the screen can
     * show the guiding text and the fitting actions under the
     * catalog message. Every other failure keeps the plain
     * catalog message from [passwordAuthMessage].
     */
    private fun signInFailure(error: Throwable): Exception {
        if (error is SupabaseException) {
            val server = error.serverMessage?.trim().orEmpty()
            if (server.isNotEmpty()) {
                val catalogError = SignupError.fromGoTrueMessage(server)
                val guidance = signInGuidanceFor(catalogError.code)
                if (guidance != null) {
                    return SignInGuidanceError(catalogError.code, guidance, error)
                }
                return Exception(catalogError.displayMessage)
            }
            return Exception(SignupErrorCode.UNKNOWN_ERROR.message)
        }
        return Exception(passwordAuthMessage(error))
    }

    /**
     * Turn a password-path failure into a catalog message. GoTrue
     * sends no catalog codes, so its stable message text is mapped
     * (see SignupError.fromGoTrueMessage); a transport failure keeps
     * the connection wording, and anything else is UNKNOWN_ERROR.
     * Mirrors iOS AuthService.passwordAuthError. The server's own
     * message is used only to pick the wording; raw error bodies are
     * never shown.
     */
    private fun passwordAuthMessage(error: Throwable): String {
        if (error is IOException) {
            return SignupErrorCode.NETWORK_ERROR.message
        }
        if (error is SupabaseException) {
            val server = error.serverMessage?.trim().orEmpty()
            if (server.isNotEmpty()) {
                return SignupError.fromGoTrueMessage(server).displayMessage
            }
            return SignupErrorCode.UNKNOWN_ERROR.message
        }
        val text = (error.message ?: "").lowercase()
        if (text.contains("cancel")) {
            return "Sign-in was cancelled. Nothing changed."
        }
        if (text.contains("network") || text.contains("internet") || text.contains("connection")) {
            return SignupErrorCode.NETWORK_ERROR.message
        }
        return SignupErrorCode.UNKNOWN_ERROR.message
    }

    private fun iso8601Now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}

/**
 * The guiding text shown under a password sign-in failure, or
 * null when the catalog message stands on its own. GoTrue
 * returns "Invalid login credentials" for three different
 * situations (a wrong password, an unconfirmed address, and an
 * account created with an emailed code that has no password),
 * so the invalid-credentials guidance names all three and points
 * at the code path and the password reset; the screen adds the
 * resend-confirmation action beside it. Pure, so the guidance
 * is pinned by unit tests.
 */
internal fun signInGuidanceFor(code: SignupErrorCode): String? {
    return when (code) {
        SignupErrorCode.CREDENTIALS_INVALID ->
            "The email address may not be confirmed yet. If this account was created " +
                "with an emailed code, it has no password, so sign in with an emailed code " +
                "instead. Otherwise, check the password or reset it."
        SignupErrorCode.EMAIL_NOT_VERIFIED ->
            "Open the confirmation email to verify your address, or request a new confirmation email."
        else -> null
    }
}

/** The code as GoTrue compares it: spaces and dashes stripped, so a pasted code verifies. */
internal fun normalizeEmailCode(code: String): String =
    code.trim().replace(" ", "").replace("-", "")

/**
 * True when the code is shaped like a GoTrue email OTP: 6 to 8
 * digits after normalisation. The dashboard issues 8-digit
 * codes; the tolerance accepts a shorter code rather than
 * rejecting a valid one.
 */
internal fun isValidEmailCode(code: String): Boolean {
    val clean = normalizeEmailCode(code)
    return clean.length in 6..8 && clean.all { it.isDigit() }
}

/**
 * Turn an emailed-code failure into the message the screen shows.
 * A rejected or expired code names the code, never the reset
 * link: the catalog's reset-link wording would mislead here, so
 * the OTP path maps GoTrue text itself. Rate limits ask the
 * learner to wait; transport failures keep the connection
 * wording; anything else is UNKNOWN_ERROR. Pure, so the mapping
 * is pinned by unit tests.
 */
internal fun otpAuthMessage(error: Throwable): String {
    if (error is IOException) {
        return SignupErrorCode.NETWORK_ERROR.message
    }
    if (error is SupabaseException) {
        val server = error.serverMessage?.trim().orEmpty()
        if (server.isNotEmpty()) {
            val text = server.lowercase()
            if (text.contains("rate limit") || text.contains("too many")) {
                return "Too many codes requested. Wait a little while, then request a new code."
            }
            if (text.contains("expired") || text.contains("invalid")) {
                return "That code is not valid or has expired. Request a new code and try again."
            }
            return SignupErrorCode.UNKNOWN_ERROR.message
        }
        return SignupErrorCode.UNKNOWN_ERROR.message
    }
    val text = (error.message ?: "").lowercase()
    if (text.contains("cancel")) {
        return "Sign-in was cancelled. Nothing changed."
    }
    if (text.contains("network") || text.contains("internet") || text.contains("connection")) {
        return SignupErrorCode.NETWORK_ERROR.message
    }
    return SignupErrorCode.UNKNOWN_ERROR.message
}

/**
 * True when an already trimmed, lowercased address is shaped like
 * a real email address: a local part, @, a domain with at least
 * one dot, and no whitespace, within the 254-character limit.
 * The old check (non-empty and contains @) accepted "a@" and
 * "a@b", which GoTrue then rejected with a generic error; this
 * check fails fast with the validation message instead. Pure, so
 * the accepted and rejected shapes are pinned by unit tests.
 * Callers trim and lowercase before calling: surrounding spaces
 * and uppercase letters are accepted, never rejected.
 */
internal fun isValidAuthEmail(cleanEmail: String): Boolean {
    if (cleanEmail.isEmpty() || cleanEmail.length > MAX_AUTH_EMAIL_LENGTH) return false
    if (cleanEmail.any { it.isWhitespace() }) return false
    val parts = cleanEmail.split("@")
    if (parts.size != 2) return false
    val local = parts[0]
    val domain = parts[1]
    if (local.isEmpty() || domain.isEmpty()) return false
    if (!domain.contains(".")) return false
    if (domain.startsWith(".") || domain.endsWith(".")) return false
    if (domain.split(".").any { it.isEmpty() }) return false
    return true
}

/** The longest email address an auth field accepts: the RFC limit. */
internal const val MAX_AUTH_EMAIL_LENGTH = 254

/** The longest password an auth field accepts. */
internal const val MAX_AUTH_PASSWORD_LENGTH = 128

/** An email field value capped at the RFC limit, so a pasted wall of text cannot overflow the form. Pure. */
internal fun cappedAuthEmailInput(value: String): String = value.take(MAX_AUTH_EMAIL_LENGTH)

/** A password field value capped at the password limit. Pure. */
internal fun cappedAuthPasswordInput(value: String): String = value.take(MAX_AUTH_PASSWORD_LENGTH)

/** A code field value: separators stripped, capped at 8 digits, so a pasted code lands clean. Pure. */
internal fun cappedAuthCodeInput(value: String): String = normalizeEmailCode(value).take(8)
