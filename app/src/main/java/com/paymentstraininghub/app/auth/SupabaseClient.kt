package com.paymentstraininghub.app.auth

import com.paymentstraininghub.app.security.PinningObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An error from the Supabase HTTP API. [serverMessage] is the message
 * Supabase itself returned, when it sent one. Callers use it to pick
 * friendly wording; the raw body is never shown to the user.
 */
class SupabaseException(
    message: String,
    val status: Int? = null,
    val body: String? = null,
    val serverMessage: String? = null
) : Exception(message)

/** The session reply from Supabase Auth. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String
)

/**
 * The outcome of a password signup that carries signup metadata.
 * [SignedIn] means the reply carried a session, so the learner is
 * signed in at once. [NeedsEmailConfirmation] means the account was
 * created but no session exists until the confirmation link is
 * opened. Mirrors the iOS PasswordSignupResult decision, which
 * reads the same reply shape.
 */
sealed interface SignupResponse {
    data class SignedIn(val tokens: AuthTokens) : SignupResponse
    data object NeedsEmailConfirmation : SignupResponse

    /**
     * The address was already registered before this attempt, so
     * no new account was created and the password just entered
     * was NOT saved: GoTrue keeps the first password. [emailConfirmed]
     * is true when the existing account already confirmed its
     * address, so the learner should sign in; false when it is
     * still unconfirmed, so the learner should resend the
     * confirmation email or sign in after confirming.
     */
    data class AlreadyRegistered(val emailConfirmed: Boolean) : SignupResponse
}

/**
 * A thin OkHttp wrapper over the Supabase HTTP API, mirroring iOS
 * SupabaseClient: Auth, REST (PostgREST) and Edge Functions, with the
 * headers Supabase expects (apikey plus an Authorization Bearer
 * token: the publishable key for public calls, the user's access
 * token once signed in).
 *
 * Only the publishable key is shipped here. Never add a service role
 * key to this file: server side work runs in Edge Functions with
 * their own secrets.
 *
 * Every call retries transient failures the iOS way: up to 3
 * attempts, exponential backoff (0.5s, then 1s), for transport
 * errors and HTTP 429 / 5xx only. A 4xx response is returned at
 * once as a [SupabaseException], never retried.
 *
 * JSON is parsed with JSONObject and every read is null safe.
 */
object SupabaseClient {

    const val BASE_URL = "https://eofsotgclhwsdwvbbtib.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_4d5gOEpkTOMp7NadcV9ErA_Ujcc2ahU"

    /**
     * The password reset redirect URL, on the app's custom scheme.
     * It must be allowlisted in the Supabase dashboard redirect
     * URLs, and the scaffold must add a manifest intent filter for
     * the "reset-password" host on this scheme, or reset links will
     * not reach the app.
     */
    const val RESET_PASSWORD_REDIRECT_URL = "com.paymentstraininghub.app://reset-password"

    /**
     * The OAuth redirect URL, on the app's custom scheme. The
     * manifest already declares the "auth-callback" host for this
     * scheme, and the Supabase dashboard redirect allowlist must
     * carry this URL, or the provider will refuse to return to
     * the app after sign-in. Google sign-in uses the PKCE flow:
     * the browser returns ?code= to this URL, and the code is
     * exchanged with the stored verifier (see [exchangePkceCode]).
     */
    const val OAUTH_REDIRECT_URL = "com.paymentstraininghub.app://auth-callback"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // MARK: - Auth

    /** POST /auth/v1/token?grant_type=password */
    suspend fun signInWithPassword(email: String, password: String): AuthTokens {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
        val response = request("$BASE_URL/auth/v1/token?grant_type=password", "POST", body)
        return parseTokens(response)
            ?: throw SupabaseException("The server reply could not be read. Please try again.")
    }

    /**
     * POST /auth/v1/otp: request an emailed sign-in code. GoTrue
     * emails an 8-digit code that expires after one hour. A new
     * address creates an unconfirmed user, and with Confirm email
     * on, GoTrue sends the confirm-signup template for that first
     * code: both templates carry the code line, and the verify
     * path below accepts the token either template issues. This
     * is Supabase email OTP only: no SMS provider, no mobile
     * number, and no code is ever shown from this client.
     */
    suspend fun sendEmailOtp(email: String) {
        request("$BASE_URL/auth/v1/otp", "POST", buildOtpRequestBody(email))
    }

    /**
     * POST /auth/v1/verify: exchange an emailed code for a session.
     * The email-OTP type is tried first; when GoTrue rejects it,
     * the signup-confirmation type is tried once, because a code
     * from the confirm-signup email (the new-address case) only
     * verifies as a signup token. Network failures and rate
     * limits never fall back: only a rejected token retries with
     * the other type. See [verifyEmailOtpWithFallback].
     */
    suspend fun verifyEmailOtp(email: String, token: String): AuthTokens {
        return verifyEmailOtpWithFallback { type ->
            verifyOtpWithType(email, token, type)
        }
    }

    /** One POST /auth/v1/verify attempt with the given GoTrue verify type. */
    internal suspend fun verifyOtpWithType(email: String, token: String, type: String): AuthTokens {
        val response = request(
            "$BASE_URL/auth/v1/verify",
            "POST",
            buildVerifyRequestBody(email, token, type)
        )
        return parseTokens(response)
            ?: throw SupabaseException("The server reply could not be read. Please try again.")
    }

    /**
     * POST /auth/v1/token?grant_type=pkce: exchange an OAuth
     * authorisation code for a session, proving the PKCE verifier
     * that was generated when the browser flow started. A code is
     * single-use and short-lived: a stale or already-used code
     * fails here and the learner signs in again.
     */
    suspend fun exchangePkceCode(code: String, codeVerifier: String): AuthTokens {
        val response = request(
            "$BASE_URL/auth/v1/token?grant_type=pkce",
            "POST",
            buildPkceExchangeBody(code, codeVerifier)
        )
        return parseTokens(response)
            ?: throw SupabaseException("The server reply could not be read. Please try again.")
    }

    /** POST /auth/v1/signup. Throws when the account was created but email confirmation is required before a session exists. */
    suspend fun signUp(email: String, password: String): AuthTokens {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
        val response = request("$BASE_URL/auth/v1/signup", "POST", body)
        parseTokens(response)?.let { return it }
        val hasUser = try {
            JSONObject(response).optJSONObject("user") != null
        } catch (_: Exception) {
            false
        }
        if (hasUser) {
            throw SupabaseException(
                "Email confirmation is required before signing in.",
                serverMessage = "email confirmation required"
            )
        }
        throw SupabaseException("The server reply could not be read. Please try again.")
    }

    /**
     * POST /auth/v1/signup with the email signup metadata: the
     * terms acceptance time and the case ID, as user metadata. The
     * case ID is generated at signup start (see CaseIdStore) and
     * sent here, mirroring iOS signUpWithPassword, so the website,
     * iOS and support all see the same ID for the account. Returns
     * [SignupResponse.SignedIn] when the reply carries a session,
     * or [SignupResponse.NeedsEmailConfirmation] when the account
     * was created without one.
     */
    suspend fun signUpWithMetadata(
        email: String,
        password: String,
        termsAcceptedAt: String,
        caseId: String
    ): SignupResponse {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put(
                "data",
                JSONObject()
                    .put("terms_accepted_at", termsAcceptedAt)
                    .put("signup_case_id", caseId)
            )
            .toString()
        val response = request("$BASE_URL/auth/v1/signup", "POST", body)
        return classifySignupResponse(response)
    }

    /** POST /auth/v1/token?grant_type=refresh_token */
    suspend fun refreshToken(refreshToken: String): AuthTokens {
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
        val response = request("$BASE_URL/auth/v1/token?grant_type=refresh_token", "POST", body)
        return parseTokens(response)
            ?: throw SupabaseException("The server reply could not be read. Please try again.")
    }

    /** POST /auth/v1/logout. Best effort: callers ignore failures. */
    suspend fun signOut(accessToken: String) {
        request("$BASE_URL/auth/v1/logout", "POST", null, accessToken)
    }

    /** GET /auth/v1/user. Returns the raw user JSON string. */
    suspend fun getUser(accessToken: String): String {
        return request("$BASE_URL/auth/v1/user", "GET", null, accessToken)
    }

    /**
     * POST /auth/v1/resend with type signup: send a fresh verification
     * email to the signed-in user's address. The link confirms the
     * address; the user stays signed in either way. Mirrors iOS
     * AuthService.resendVerificationEmail.
     */
    suspend fun resendVerificationEmail(email: String) {
        val body = JSONObject()
            .put("email", email)
            .put("type", "signup")
            .toString()
        request("$BASE_URL/auth/v1/resend", "POST", body)
    }

    /**
     * POST /auth/v1/recover: send a password reset email. The body
     * carries the email address and the app redirect URL; the
     * request helper adds the apikey header and the publishable key
     * bearer token. The redirect URL must be allowlisted in the
     * Supabase dashboard, or the link in the email will not reach
     * the app. Callers must never reveal whether an account exists
     * for the address: the generic success message is shown for
     * every accepted request. Errors map through the catalog at the
     * AuthManager layer.
     */
    suspend fun sendPasswordResetEmail(email: String) {
        val body = JSONObject()
            .put("email", email)
            .put("redirect_to", RESET_PASSWORD_REDIRECT_URL)
            .toString()
        request("$BASE_URL/auth/v1/recover", "POST", body)
    }

    /**
     * PUT /auth/v1/user: set a new password with a recovery access
     * token from a reset link. The token is the Bearer token for
     * this call; the body carries only the new password. An expired
     * or invalid token fails here and maps to RESET_LINK_EXPIRED
     * through the catalog at the AuthManager layer. No session is
     * stored: the learner signs in with the new password afterwards.
     */
    suspend fun updatePasswordWithRecoveryToken(token: String, newPassword: String) {
        val body = JSONObject()
            .put("password", newPassword)
            .toString()
        request("$BASE_URL/auth/v1/user", "PUT", body, token)
    }

    // MARK: - Edge Functions

    /** POST /functions/v1/{name}. Returns the raw response body string. */
    suspend fun invokeFunction(name: String, accessToken: String, bodyJson: String): String {
        return request("$BASE_URL/functions/v1/$name", "POST", bodyJson, accessToken)
    }

    // MARK: - REST (PostgREST)

    /**
     * GET rows from a table. Pass the query string, for example
     * "select=*&user_id=eq.<uuid>". The query is appended as given:
     * callers build PostgREST queries and it must not be encoded.
     * Returns the raw response body string.
     */
    suspend fun restGet(table: String, accessToken: String?, query: String = ""): String {
        return request(restUrl(table, query), "GET", null, accessToken)
    }

    /** POST rows into a table. Returns the raw response body string (usually empty with return=minimal). */
    suspend fun restPost(
        table: String,
        accessToken: String?,
        bodyJson: String,
        query: String = ""
    ): String {
        return request(
            restUrl(table, query),
            "POST",
            bodyJson,
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
    }

    /** PATCH rows in a table matching the query, for example "id=eq.<uuid>". */
    suspend fun restPatch(
        table: String,
        accessToken: String?,
        bodyJson: String,
        query: String = ""
    ): String {
        return request(
            restUrl(table, query),
            "PATCH",
            bodyJson,
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
    }

    /**
     * DELETE rows in a table matching the query, for example
     * "id=eq.<uuid>". The query is required: a delete with no
     * filter is never issued. Returns the raw response body
     * string (usually empty with return=minimal).
     */
    suspend fun restDelete(
        table: String,
        accessToken: String?,
        query: String
    ): String {
        require(query.isNotBlank()) { "restDelete requires a filter query" }
        return request(
            restUrl(table, query),
            "DELETE",
            null,
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
    }

    /**
     * Upsert rows into a table, merging on the named conflict columns,
     * for example "user_id,lesson_id". This is what keeps sync from
     * duplicating records: the same row is updated, not added.
     */
    suspend fun restUpsert(
        table: String,
        accessToken: String?,
        bodyJson: String,
        onConflict: String,
        query: String = ""
    ): String {
        val fullQuery = if (query.isBlank()) {
            "on_conflict=$onConflict"
        } else {
            "on_conflict=$onConflict&$query"
        }
        return request(
            restUrl(table, fullQuery),
            "POST",
            bodyJson,
            accessToken,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    private fun restUrl(table: String, query: String): String {
        val base = "$BASE_URL/rest/v1/$table"
        return if (query.isBlank()) base else "$base?$query"
    }

    // MARK: - Storage (signed URLs)

    /**
     * Signs a private storage object and returns an absolute URL
     * to fetch it. The storage sign reply carries a relative
     * signedURL, which is resolved against the storage base.
     * Mirrors the iOS avatar resolution exactly: the profiles
     * row stores a storage path (not a URL) and the display
     * asks for a fresh signed URL. Returns null when the reply
     * carries no signed URL. Throws on HTTP failure; callers
     * that treat the picture as optional catch it.
     */
    suspend fun storageSignedUrl(
        bucket: String,
        path: String,
        accessToken: String?,
        expiresInSeconds: Int = 3600
    ): String? {
        val body = request(
            "$BASE_URL/storage/v1/object/sign/$bucket/$path",
            "POST",
            "{\"expiresIn\": $expiresInSeconds}",
            accessToken
        )
        return parseSignedUrl(body, BASE_URL)
    }

    /** Parses a storage sign reply of the shape {"signedURL": "..."}. */
    internal fun parseSignedUrl(body: String, baseUrl: String): String? {
        return try {
            val obj = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val signed = (obj["signedURL"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return null
            when {
                signed.startsWith("http://") || signed.startsWith("https://") -> signed
                signed.startsWith("/") -> "$baseUrl/storage/v1$signed"
                else -> "$baseUrl/storage/v1/$signed"
            }
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Core request with bounded retry

    private suspend fun request(
        url: String,
        method: String,
        bodyJson: String? = null,
        accessToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        var delayMs = 500L
        var lastIOException: IOException? = null
        for (attempt in 1..3) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("apikey", PUBLISHABLE_KEY)
                    .header("Authorization", "Bearer ${accessToken ?: PUBLISHABLE_KEY}")
                    .header("Accept", "application/json")
                for ((name, value) in extraHeaders) {
                    builder.header(name, value)
                }
                val body = bodyJson?.toRequestBody(jsonMediaType)
                when (method) {
                    "GET" -> builder.get()
                    "DELETE" -> builder.delete(body)
                    "POST" -> builder.post(body ?: "".toRequestBody(jsonMediaType))
                    "PATCH" -> builder.patch(body ?: "".toRequestBody(jsonMediaType))
                    else -> builder.method(method, body)
                }
                val (code, responseBody) = client.newCall(builder.build()).execute().use { response ->
                    response.code to (response.body?.string().orEmpty())
                }
                if ((code == 429 || code in 500..599) && attempt < 3) {
                    delay(delayMs)
                    delayMs *= 2
                    continue
                }
                if (code !in 200..299) {
                    throw httpError(code, responseBody)
                }
                return@withContext responseBody
            } catch (e: SupabaseException) {
                throw e
            } catch (e: IOException) {
                lastIOException = e
                if (attempt == 3) throw e
                delay(delayMs)
                delayMs *= 2
            }
        }
        throw lastIOException ?: IOException("Request failed")
    }

    private fun httpError(status: Int, body: String): SupabaseException {
        val serverMessage = parseServerMessage(body)
        val message = serverMessage
            ?: "The server returned an error ($status). Please try again."
        return SupabaseException(message, status, body, serverMessage)
    }

    private fun parseServerMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = JSONObject(body)
            for (key in listOf("msg", "message", "error_description", "error")) {
                val raw = obj.opt(key)
                if (raw is String && raw.isNotBlank()) {
                    return raw.trim()
                }
            }
            val nested = obj.optJSONObject("error")?.optString("message", "")?.trim()
            nested?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTokens(body: String): AuthTokens? {
        return try {
            val obj = JSONObject(body)
            val access = obj.optString("access_token", "").trim()
            val refresh = obj.optString("refresh_token", "").trim()
            val user = obj.optJSONObject("user") ?: return null
            val id = user.optString("id", "").trim()
            val email = user.optString("email", "").trim()
            if (access.isEmpty() || refresh.isEmpty() || id.isEmpty()) return null
            AuthTokens(access, refresh, id, email)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Classifies a POST /auth/v1/signup reply into its [SignupResponse].
 * Pure, so the reply shapes are pinned by unit tests.
 *
 * GoTrue returns TWO success shapes, and the app used to recognise only
 * one. With autoconfirm off (this project: Confirm email is on), a
 * successful signup returns the bare user object itself at the top
 * level: id, email, confirmation_sent_at, app_metadata, user_metadata,
 * identities, with NO session tokens and NO nested "user" key. The old
 * code looked only for the nested "user" key, so every successful
 * signup showed the generic error even though the account was created
 * and the confirmation email was sent. The session shape (access_token,
 * refresh_token, nested "user") still means [SignupResponse.SignedIn].
 * Anything without a recognisable user, including an error body, throws
 * [SupabaseException], exactly as before.
 *
 * A signup for an address that is ALREADY registered also returns 200
 * with a user object, so a duplicate looks like a success unless the
 * reply is inspected. The empty-identities signal does NOT hold on
 * this project: a real new-signup reply recorded in the tests carries
 * "identities": [], so it cannot tell a duplicate from a new account.
 * Two signals do: an existing account that already confirmed its
 * address carries a non-empty email_confirmed_at (a new account never
 * does), and a re-signup refreshes confirmation_sent_at while
 * created_at stays at the original signup, so a gap of more than two
 * minutes between the two timestamps means the address predates this
 * attempt. Both timestamps are server-generated, so device clock skew
 * cannot fake the gap. When neither signal is present the reply is
 * treated as a new account, exactly as before: an undetected duplicate
 * degrades to the check-email step, never to an error.
 */
internal fun classifySignupResponse(response: String): SignupResponse {
    val obj = try {
        Json.parseToJsonElement(response) as JsonObject
    } catch (_: Exception) {
        throw SupabaseException("The server reply could not be read. Please try again.")
    }
    fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    fun JsonObject.objectField(name: String): JsonObject? =
        this[name] as? JsonObject

    val access = obj.stringField("access_token")
    val refresh = obj.stringField("refresh_token")
    val nestedUser = obj.objectField("user")
    if (access.isNotEmpty() && refresh.isNotEmpty() && nestedUser != null) {
        val id = nestedUser.stringField("id")
        val email = nestedUser.stringField("email")
        if (id.isNotEmpty()) {
            return SignupResponse.SignedIn(AuthTokens(access, refresh, id, email))
        }
    }
    if (nestedUser != null) {
        return classifyUserObject(nestedUser)
    }
    val topId = obj.stringField("id")
    val topEmail = obj.stringField("email")
    if (topId.isNotEmpty() && topEmail.isNotEmpty()) {
        return classifyUserObject(obj)
    }
    throw SupabaseException("The server reply could not be read. Please try again.")
}

/** The gap between created_at and confirmation_sent_at above which a signup reply is a re-signup, not a new account. */
internal const val DUPLICATE_SIGNUP_GAP_MS = 120_000L

/**
 * A signup user object is a duplicate when the account already
 * confirmed its address, or when the confirmation email was sent
 * more than [DUPLICATE_SIGNUP_GAP_MS] after the account was
 * created (a re-signup refreshes the sent time; a new signup
 * sends at creation). Anything else is a new account waiting for
 * confirmation. Unparseable or missing timestamps fall back to
 * the new-account outcome. Pure, so the signals are pinned by
 * unit tests.
 */
internal fun classifyUserObject(user: JsonObject): SignupResponse {
    fun stringField(name: String): String =
        (user[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (stringField("email_confirmed_at").isNotEmpty() || stringField("confirmed_at").isNotEmpty()) {
        return SignupResponse.AlreadyRegistered(emailConfirmed = true)
    }
    val createdAt = parseGoTrueInstant(stringField("created_at"))
    val confirmationSentAt = parseGoTrueInstant(stringField("confirmation_sent_at"))
    if (createdAt != null && confirmationSentAt != null &&
        confirmationSentAt - createdAt > DUPLICATE_SIGNUP_GAP_MS
    ) {
        return SignupResponse.AlreadyRegistered(emailConfirmed = false)
    }
    return SignupResponse.NeedsEmailConfirmation
}

/** An ISO-8601 GoTrue timestamp as epoch millis, or null when it does not parse. Pure. */
internal fun parseGoTrueInstant(value: String): Long? {
    if (value.isEmpty()) return null
    return try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/**
 * The POST /auth/v1/otp request body for an emailed sign-in code.
 * Both user-creation spellings are sent: GoTrue accepts
 * create_user and the newer should_create_user, and unknown
 * fields are ignored, so the request works whichever spelling
 * the server reads. Built with kotlinx.serialization, so the
 * request shape is pinned by plain unit tests. Pure.
 */
internal fun buildOtpRequestBody(email: String): String {
    return buildJsonObject {
        put("email", JsonPrimitive(email))
        put("create_user", JsonPrimitive(true))
        put("should_create_user", JsonPrimitive(true))
    }.toString()
}

/**
 * The POST /auth/v1/verify request body for an emailed code.
 * [type] is "email" for a magic-link code or "signup" for a
 * confirm-signup code. Built with kotlinx.serialization, so the
 * request shape is pinned by plain unit tests. Pure.
 */
internal fun buildVerifyRequestBody(email: String, token: String, type: String): String {
    return buildJsonObject {
        put("email", JsonPrimitive(email))
        put("token", JsonPrimitive(token))
        put("type", JsonPrimitive(type))
    }.toString()
}

/**
 * True when a rejected email-OTP verify attempt is worth
 * retrying with the signup-confirmation type: only a 4xx
 * rejection of the token itself. A missing status (the reply
 * could not be read), a rate limit, and a server error never
 * fall back, because the other type cannot succeed either and
 * the token may already be consumed.
 */
internal fun shouldFallbackVerifyType(error: SupabaseException): Boolean {
    val status = error.status ?: return false
    return status in 400..499 && status != 429
}

/**
 * Verify an emailed code, trying the email-OTP type first and
 * falling back to the signup-confirmation type once when the
 * first attempt is rejected (see [shouldFallbackVerifyType]).
 * The [verify] seam performs one attempt for a type, so both
 * paths are pinned by unit tests without a network call: an
 * email-type success never tries signup, a rejected email
 * attempt tries signup once, and a failure of both surfaces
 * the signup attempt's error.
 */
internal suspend fun verifyEmailOtpWithFallback(
    verify: suspend (type: String) -> AuthTokens
): AuthTokens {
    try {
        return verify("email")
    } catch (e: SupabaseException) {
        if (!shouldFallbackVerifyType(e)) throw e
        return verify("signup")
    }
}

/**
 * The Supabase OAuth authorisation URL for a provider, with the
 * PKCE challenge. The browser opens this URL; the provider
 * returns to [redirectTo] with ?code=, and the code is exchanged
 * with the verifier that produced [codeChallenge]. Pure, so the
 * URL shape is pinned by unit tests.
 */
internal fun buildOAuthAuthorizeUrl(provider: String, redirectTo: String, codeChallenge: String): String {
    val encodedRedirect = java.net.URLEncoder.encode(redirectTo, "UTF-8")
    return "${SupabaseClient.BASE_URL}/auth/v1/authorize" +
        "?provider=$provider" +
        "&redirect_to=$encodedRedirect" +
        "&code_challenge=$codeChallenge" +
        "&code_challenge_method=S256"
}

/**
 * The POST /auth/v1/token?grant_type=pkce request body. Pure, so
 * the exchange shape is pinned by unit tests.
 */
internal fun buildPkceExchangeBody(code: String, codeVerifier: String): String {
    return buildJsonObject {
        put("auth_code", JsonPrimitive(code))
        put("code_verifier", JsonPrimitive(codeVerifier))
    }.toString()
}

/**
 * A PKCE code verifier: 32 random bytes, base64url without
 * padding, 43 characters. Pure apart from the random source,
 * which tests inject for a deterministic value.
 */
internal fun generatePkceVerifier(random: java.security.SecureRandom = java.security.SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The S256 PKCE code challenge for a verifier: base64url SHA-256
 * without padding. Pure, and pinned against the RFC 7636 test
 * vector by unit tests.
 */
internal fun pkceCodeChallenge(verifier: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
