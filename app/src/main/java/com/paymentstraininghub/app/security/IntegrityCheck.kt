// IntegrityCheck.kt
// Payments Training Hub Android - Play Integrity verification for
// the premium access gate. The app requests an integrity token
// from Google Play with a fresh random nonce, then sends the token
// to the verify-integrity Edge Function, which decodes it with the
// Play Integrity API (server-side, using the Play service
// account) and answers whether the device, the app install and
// the licence check out. The verdict is NEVER computed on the
// device: the token is an encrypted payload the app cannot open,
// and only the server's answer counts. A locally minted token
// proves nothing on its own.
//
// The check runs for guests too: the function accepts apikey-only
// calls, and a signed-in user's bearer token is attached when one
// exists. The token is single-use and bound to this app, this
// device and the nonce, so the server also checks the decoded
// nonce matches the one the app sent.
//
// Outcomes: Verified (the server ran the check and answered pass
// or fail), Unavailable (no answer: no network, Play services
// missing, or a server error), NotConfigured (the function is not
// deployed or the Play secrets are not set yet, so verification
// cannot run). The gate decides what each outcome means; this
// file only reports honestly. Nothing here throws for an expected
// failure.

package com.paymentstraininghub.app.security

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object IntegrityCheck {

    private const val TAG = "IntegrityCheck"
    private const val FUNCTION_PATH = "/functions/v1/verify-integrity"

    /** Whole-flow timeout: token request plus server round trip. */
    private const val FLOW_TIMEOUT_MS = 30_000L

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(PinningObserver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface IntegrityOutcome {
        /** The server verified the token and answered. */
        data class Verified(val passed: Boolean, val code: String?) : IntegrityOutcome

        /** No verdict could be obtained (network, Play services, server error). */
        data class Unavailable(val reason: String) : IntegrityOutcome

        /** The function is not deployed or the Play secrets are unset. */
        data object NotConfigured : IntegrityOutcome
    }

    /**
     * Requests a fresh integrity token and has the server verify
     * it. Never throws: every failure path returns an outcome.
     */
    suspend fun requestAndVerify(context: Context): IntegrityOutcome {
        val outcome = withTimeoutOrNull(FLOW_TIMEOUT_MS) {
            try {
                requestAndVerifyInner(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Integrity verification failed: ${e.message}")
                IntegrityOutcome.Unavailable(e.message ?: e.javaClass.simpleName)
            }
        }
        return outcome ?: IntegrityOutcome.Unavailable("Integrity verification timed out")
    }

    private suspend fun requestAndVerifyInner(context: Context): IntegrityOutcome {
        val nonce = UUID.randomUUID().toString()
        val token = requestToken(context, nonce)
            ?: return IntegrityOutcome.Unavailable("Play Integrity token request failed")
        return verifyTokenWithServer(context, token, nonce)
    }

    private suspend fun requestToken(context: Context, nonce: String): String? {
        return try {
            val manager = IntegrityManagerFactory.create(context)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            val task: Task<IntegrityTokenResponse> = manager.requestIntegrityToken(request)
            suspendCancellableCoroutine { continuation ->
                task.addOnSuccessListener { response ->
                    if (continuation.isActive) continuation.resume(response.token())
                }
                task.addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Integrity token request failed: ${e.message}")
            null
        }
    }

    private suspend fun verifyTokenWithServer(
        context: Context,
        token: String,
        nonce: String,
    ): IntegrityOutcome {
        val bearer = try {
            AuthManager.validAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val bodyJson = JSONObject().apply {
            put("integrity_token", token)
            put("nonce", nonce)
            put("package_name", context.packageName)
        }
        val requestBuilder = Request.Builder()
            .url(SupabaseClient.BASE_URL + FUNCTION_PATH)
            .header("apikey", SupabaseClient.PUBLISHABLE_KEY)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
        if (!bearer.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearer")
        }
        val response = try {
            httpClient.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "Integrity server call failed: ${e.message}")
            return IntegrityOutcome.Unavailable(e.message ?: e.javaClass.simpleName)
        }
        return response.use {
            val rawBody = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
            val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
            val code = parsed?.optString("code")?.takeIf { value -> value.isNotBlank() }
            when {
                it.code == 404 -> IntegrityOutcome.NotConfigured
                it.code == 503 && code == "verification_unavailable" ->
                    IntegrityOutcome.NotConfigured
                it.isSuccessful -> {
                    val passed = parsed?.optBoolean("ok", false) == true
                    IntegrityOutcome.Verified(passed, code)
                }
                else -> IntegrityOutcome.Unavailable("Integrity server answered HTTP ${it.code}")
            }
        }
    }
}
