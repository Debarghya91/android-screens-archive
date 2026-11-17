// AdminAccessTest.kt
// Pure JVM tests for the admin access rule: the admin flag read
// from the session token (AdminClaims), and the entitlement
// helper that applies it (EntitlementRules.isAdminUnlocked). No
// Android dependency, no Robolectric. Test tokens are built here
// with the platform base64url encoder; no real token is used. No
// em dashes in any string here.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.AdminClaims
import com.paymentstraininghub.app.billing.EntitlementRules
import com.paymentstraininghub.app.data.ProductCatalogue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AdminAccessTest {

    private fun tokenWithPayload(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString(
            """{"alg":"HS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8)
        )
        val payload = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.fake-signature"
    }

    @Test
    fun isAdminToken_trueWhenAppMetadataCarriesIsAdminTrue() {
        val token = tokenWithPayload(
            """{"sub":"user-1","app_metadata":{"provider":"email","is_admin":true}}"""
        )
        assertTrue(AdminClaims.isAdminToken(token))
    }

    @Test
    fun isAdminToken_falseWhenIsAdminIsFalse() {
        val token = tokenWithPayload(
            """{"sub":"user-1","app_metadata":{"provider":"email","is_admin":false}}"""
        )
        assertFalse(AdminClaims.isAdminToken(token))
    }

    @Test
    fun isAdminToken_falseWhenIsAdminIsMissing() {
        val token = tokenWithPayload(
            """{"sub":"user-1","app_metadata":{"provider":"email"}}"""
        )
        assertFalse(AdminClaims.isAdminToken(token))
    }

    @Test
    fun isAdminToken_falseWhenAppMetadataIsMissing() {
        val token = tokenWithPayload("""{"sub":"user-1","role":"authenticated"}""")
        assertFalse(AdminClaims.isAdminToken(token))
    }

    @Test
    fun isAdminToken_falseWhenIsAdminIsAString() {
        val token = tokenWithPayload(
            """{"sub":"user-1","app_metadata":{"is_admin":"true"}}"""
        )
        assertFalse(AdminClaims.isAdminToken(token))
    }

    @Test
    fun isAdminToken_falseForNullBlankAndMalformedTokens() {
        assertFalse(AdminClaims.isAdminToken(null))
        assertFalse(AdminClaims.isAdminToken(""))
        assertFalse(AdminClaims.isAdminToken("   "))
        assertFalse(AdminClaims.isAdminToken("not-a-jwt"))
        assertFalse(AdminClaims.isAdminToken("two.parts"))
        assertFalse(AdminClaims.isAdminToken("four.parts.are.here"))
        assertFalse(AdminClaims.isAdminToken("a.!!!.c"))
        val notJson = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not json".toByteArray(Charsets.UTF_8))
        assertFalse(AdminClaims.isAdminToken("a.$notJson.c"))
    }

    @Test
    fun isAdminUnlocked_trueForKnownProductsWhenAdmin() {
        assertTrue(
            EntitlementRules.isAdminUnlocked(true, ProductCatalogue.integratedProductId)
        )
        assertTrue(
            EntitlementRules.isAdminUnlocked(true, ProductCatalogue.allProductIds.first())
        )
    }

    @Test
    fun isAdminUnlocked_trueForRenewalIdsWhenAdmin() {
        assertTrue(
            EntitlementRules.isAdminUnlocked(true, ProductCatalogue.allRenewalProductIds.first())
        )
    }

    @Test
    fun isAdminUnlocked_falseWhenNotAdmin() {
        assertFalse(
            EntitlementRules.isAdminUnlocked(false, ProductCatalogue.integratedProductId)
        )
    }

    @Test
    fun isAdminUnlocked_falseForUnknownIdsEvenWhenAdmin() {
        assertFalse(
            EntitlementRules.isAdminUnlocked(true, "com.paymentstraininghub.app.unknown")
        )
        assertFalse(EntitlementRules.isAdminUnlocked(true, ""))
    }
}
