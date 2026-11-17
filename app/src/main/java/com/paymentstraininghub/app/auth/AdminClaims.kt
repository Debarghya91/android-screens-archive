package com.paymentstraininghub.app.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Reads the admin flag from a Supabase access token, without any
 * Android dependency, so the rule is unit-tested on the JVM.
 *
 * Supabase Auth access tokens are JWTs whose payload carries
 * `app_metadata`, and the owner's account carries
 * `app_metadata.is_admin = true` (set with SQL on
 * raw_app_meta_data). An admin session unlocks every product on
 * every surface and is never prompted to buy. The flag is read
 * from the token itself, never from a stored boolean, so it
 * follows the session: a refreshed token re-evaluates it, and
 * signing out clears it with the session.
 *
 * The token is never verified here: signature verification is
 * the server's job, and this flag only ever unlocks content on
 * this device for a session Supabase itself issued. A malformed
 * token, a missing payload, or any parse failure reads as
 * not-admin (fail closed).
 */
object AdminClaims {

    /**
     * True when [accessToken] is a JWT whose payload carries
     * `app_metadata.is_admin` set to the boolean true. Null,
     * blank, malformed, and non-admin tokens all return false.
     */
    fun isAdminToken(accessToken: String?): Boolean {
        if (accessToken.isNullOrBlank()) return false
        val parts = accessToken.split(".")
        if (parts.size != 3) return false
        val payloadJson = try {
            String(decodeBase64Url(parts[1]), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return try {
            val payload = Json.parseToJsonElement(payloadJson).jsonObject
            val appMetadata = payload["app_metadata"]?.jsonObject ?: return false
            appMetadata["is_admin"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decodes unpadded base64url (the JWT encoding) with the
     * platform decoder, which requires padding: the missing
     * padding is restored first. Throws on invalid input.
     */
    private fun decodeBase64Url(unpadded: String): ByteArray {
        val padding = when (unpadded.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.getUrlDecoder().decode(unpadded + padding)
    }
}
