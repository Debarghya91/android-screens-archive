// OAuthTest.kt
// Unit tests for Google sign-in: the PKCE challenge (pinned
// against the RFC 7636 test vector), the authorisation URL shape,
// the code-exchange body shape, the auth-callback deep-link
// parsing, and the begin/complete verifier lifecycle. No network
// calls and no browser: the exchange itself is never invoked
// here. No em dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.auth.buildOAuthAuthorizeUrl
import com.paymentstraininghub.app.auth.buildPkceExchangeBody
import com.paymentstraininghub.app.auth.generatePkceVerifier
import com.paymentstraininghub.app.auth.pkceCodeChallenge
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthTest {

    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceCodeChallenge(verifier)
        )
    }

    @Test
    fun generatedVerifierIs43UrlSafeCharacters() {
        val verifier = generatePkceVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val challenge = pkceCodeChallenge(verifier)
        assertEquals(43, challenge.length)
        assertTrue(challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizeUrlCarriesProviderRedirectAndChallenge() {
        val url = buildOAuthAuthorizeUrl(
            provider = "google",
            redirectTo = SupabaseClient.OAUTH_REDIRECT_URL,
            codeChallenge = "challenge-value"
        )
        assertTrue(url.startsWith(SupabaseClient.BASE_URL + "/auth/v1/authorize?"))
        assertTrue(url.contains("provider=google"))
        assertTrue(url.contains("redirect_to=com.paymentstraininghub.app%3A%2F%2Fauth-callback"))
        assertTrue(url.contains("code_challenge=challenge-value"))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun pkceExchangeBodyCarriesCodeAndVerifier() {
        val body = buildPkceExchangeBody("auth-code-1", "verifier-1")
        val obj = Json.parseToJsonElement(body) as JsonObject
        assertEquals("auth-code-1", obj["auth_code"]?.jsonPrimitive?.contentOrNull)
        assertEquals("verifier-1", obj["code_verifier"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun callbackLinkWithCodeParses() {
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://auth-callback?code=abc123")
        assertTrue(link is TrainingHubDeepLink.OAuthCallback)
        val callback = link as TrainingHubDeepLink.OAuthCallback
        assertEquals("abc123", callback.code)
        assertNull(callback.error)
    }

    @Test
    fun callbackLinkWithErrorParses() {
        val link = DeepLinkRouter.parse(
            "com.paymentstraininghub.app://auth-callback?error=access_denied&error_description=denied"
        )
        assertTrue(link is TrainingHubDeepLink.OAuthCallback)
        val callback = link as TrainingHubDeepLink.OAuthCallback
        assertNull(callback.code)
        assertEquals("access_denied", callback.error)
    }

    @Test
    fun callbackLinkDecodesAnEncodedCode() {
        val link = DeepLinkRouter.parse(
            "com.paymentstraininghub.app://auth-callback?code=ab%2Bc%3D"
        )
        val callback = link as TrainingHubDeepLink.OAuthCallback
        assertEquals("ab+c=", callback.code)
    }

    @Test
    fun bareCallbackLinkParsesWithNoCodeOrError() {
        val link = DeepLinkRouter.parse("com.paymentstraininghub.app://auth-callback")
        assertTrue(link is TrainingHubDeepLink.OAuthCallback)
        val callback = link as TrainingHubDeepLink.OAuthCallback
        assertNull(callback.code)
        assertNull(callback.error)
    }

    @Test
    fun unknownHostDoesNotParseAsCallback() {
        assertNull(DeepLinkRouter.parse("com.paymentstraininghub.app://other?code=abc"))
        assertNull(DeepLinkRouter.parse("https://www.paymentstraininghub.com/auth-callback?code=abc"))
    }

    @Test
    fun callbackRouteIsAuth() {
        assertEquals(
            "auth",
            DeepLinkRouter.routeFor(TrainingHubDeepLink.OAuthCallback(code = "c", error = null))
        )
    }

    @Test
    fun routerHoldsCallbackUntilCleared() {
        try {
            DeepLinkRouter.receive(TrainingHubDeepLink.OAuthCallback(code = "code-1", error = null))
            assertEquals("code-1", DeepLinkRouter.pendingOAuthCode.value)
            assertNull(DeepLinkRouter.pendingOAuthError.value)
            assertTrue(DeepLinkRouter.pendingOAuthAgeMs(System.currentTimeMillis()) < 60_000L)
        } finally {
            DeepLinkRouter.clearOAuthCallback()
        }
        assertNull(DeepLinkRouter.pendingOAuthCode.value)
        assertEquals(Long.MAX_VALUE, DeepLinkRouter.pendingOAuthAgeMs(System.currentTimeMillis()))
    }

    @Test
    fun beginGoogleSignInReturnsAnAuthorizeUrl() {
        try {
            val url = AuthManager.beginGoogleSignIn()
            assertTrue(url.contains("/auth/v1/authorize?provider=google"))
            assertTrue(url.contains("code_challenge_method=S256"))
        } finally {
            AuthManager.cancelGoogleSignIn()
        }
    }

    @Test
    fun completeWithoutAVerifierFailsInterrupted() = runBlocking {
        AuthManager.cancelGoogleSignIn()
        val result = AuthManager.completeGoogleSignIn("some-code")
        assertTrue(result.isFailure)
        assertEquals(
            "Google sign-in was interrupted. Try again.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun completeWithABlankCodeConsumesTheVerifier() = runBlocking {
        try {
            AuthManager.beginGoogleSignIn()
            val first = AuthManager.completeGoogleSignIn("")
            assertTrue(first.isFailure)
            val second = AuthManager.completeGoogleSignIn("some-code")
            assertTrue(second.isFailure)
            assertEquals(
                "Google sign-in was interrupted. Try again.",
                second.exceptionOrNull()?.message
            )
        } finally {
            AuthManager.cancelGoogleSignIn()
        }
    }
}
