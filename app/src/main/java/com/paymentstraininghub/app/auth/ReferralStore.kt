// ReferralStore.kt
// Payments Training Hub Android - the signed-in user's OWN referral
// code and credit balance, for display and sharing only. Mirrors iOS
// ReferralStore.swift. APP RULE (decided): the app shows the code
// with a share affordance and shows the credit balance, and states
// plainly that referral discount redemption is website-only,
// because Google Play billing cannot take an external discount.
// Nothing in this file touches BillingManager or any purchase path:
// no discount is ever wired into a purchase, and no referral code
// entry exists in the app signup flow. The code is derived
// deterministically from the account id (SHA-256 over a fixed prefix
// plus the id, mapped onto the shared alphabet), so it is stable
// across devices for the same account without a server round trip.
// LIMIT: there is no referral read endpoint in the fixed contract,
// so this locally derived code is provisional until the server
// issues referral codes through an endpoint; the website validates
// every code at redemption, so a code the server does not know is
// refused there with REFERRAL_INVALID. The credit balance is
// likewise local-only until a referral read endpoint exists: it
// starts at 0 and the UI says credits are confirmed on the website.
// Neither value is ever presented as server-confirmed.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.auth

import com.paymentstraininghub.app.data.ProductCatalogue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * The decided referral and promo rules, encoded as pure predicates
 * so the website and server enforcement and the app's display
 * copy cannot drift apart. Nothing here touches a purchase path:
 * Google Play billing cannot take an external discount, so
 * referral and promo redemption stay website-only and are
 * enforced by the website and server. These predicates exist so
 * any future display or eligibility copy reads the same rules.
 *
 * Referral (decided 15 Nov 2026): Rs 100 to the REFERRER only,
 * on products of Rs 999 and above only (Practice Experiences,
 * Interview Ready, The Integrated Course), and only when the
 * referred person buys. The referred person gets no credit.
 * The old Rs 2,000 interview-bank unlock rule is retired and
 * must never be implemented; no threshold logic lives here.
 *
 * FIRSTLAUNCH25 (decided 15 Nov 2026): 25 percent off, applies
 * as the coupon layer on sale prices for parts and standalone
 * items only. It EXCLUDES The Integrated Course and NEVER
 * stacks with the referral: a purchase uses at most one of them.
 */
object ReferralPromoRules {

    /** Referral credit in rupees, paid to the referrer only. */
    const val REFERRAL_CREDIT_INR: Int = 100

    /** The minimum sale price for a referral-eligible product. */
    const val REFERRAL_MINIMUM_PRICE_INR: Int = 999

    /** The launch promo code. */
    const val PROMO_CODE: String = "FIRSTLAUNCH25"

    /** The launch promo discount, in percent, off the sale price. */
    const val PROMO_DISCOUNT_PERCENT: Int = 25

    /** The referral credit goes to the referrer only, never to the referred person. */
    const val REFERRAL_GOES_TO_REFERRER_ONLY: Boolean = true

    /** The referral credit is earned only when the referred person buys. */
    const val REFERRAL_REQUIRES_COMPLETED_PURCHASE: Boolean = true

    /** FIRSTLAUNCH25 never stacks with the referral. */
    const val PROMO_STACKS_WITH_REFERRAL: Boolean = false

    /**
     * True when a purchase of this product can earn the referrer
     * the Rs 100 credit: the product's sale price is Rs 999 or
     * above. Unknown products never qualify.
     */
    fun isReferralEligible(productId: String): Boolean {
        val product = ProductCatalogue.productFor(productId) ?: return false
        return product.priceInr >= REFERRAL_MINIMUM_PRICE_INR
    }

    /**
     * True when FIRSTLAUNCH25 applies to this product: every
     * product except The Integrated Course. Unknown products
     * never qualify.
     */
    fun isPromoEligible(productId: String): Boolean {
        val product = ProductCatalogue.productFor(productId) ?: return false
        return product.productId != ProductCatalogue.integratedProductId
    }
}

object ReferralStore {

    private const val CREDIT_BALANCE_BASE_KEY = "pf.referral.creditBalance.v1"

    /** The live learning website, named in the share text as the place a referral discount is applied. */
    const val SITE_BASE_URL = "https://www.paymentstraininghub.com"

    private val _creditBalance = MutableStateFlow(0)

    /**
     * The locally recorded referral credit balance for the current
     * account. Namespaced per account, so deletion removes it with
     * the namespace. See the file header: local-only until a
     * referral read endpoint exists.
     */
    val creditBalance: StateFlow<Int> = _creditBalance.asStateFlow()

    /**
     * The signed-in user's own referral code: 8 uppercase characters
     * from the shared alphabet. Null for a guest: a guest has no
     * account for a code to belong to.
     */
    fun referralCode(): String? {
        val userId = AuthManager.currentUserId() ?: return null
        return codeForUserId(userId)
    }

    /**
     * Derive the referral code for an account id. Deterministic:
     * the same id always yields the same code, on every device.
     * Mirrors iOS ReferralStore.code(forUserID:) byte for byte: the
     * first 8 bytes of SHA-256 over "pf-referral:<id>", each mapped
     * onto the shared alphabet by remainder.
     */
    fun codeForUserId(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("pf-referral:$userId".toByteArray(Charsets.UTF_8))
        val alphabet = CodeAlphabet.CHARACTERS
        val code = StringBuilder()
        for (index in 0 until 8) {
            val unsigned = digest[index].toInt() and 0xFF
            code.append(alphabet[unsigned % alphabet.length])
        }
        return code.toString()
    }

    /**
     * Reload the credit balance for the current namespace. Called on
     * every identity transition through AuthManager, and by screens
     * when the signed-in state changes.
     */
    suspend fun reloadForCurrentAccount() {
        val stored = try {
            AppStore.getLong(CREDIT_BALANCE_BASE_KEY)
        } catch (_: Exception) {
            null
        }
        _creditBalance.value = stored?.toInt() ?: 0
    }

    /**
     * The share text for the user's own code. It names the website
     * as the place a referral discount is applied, and promises no
     * discount inside the app. Empty when there is no code (guest).
     */
    fun shareText(): String {
        val code = referralCode() ?: return ""
        return listOf(
            "I am learning payments with Payments Training Hub.",
            "My referral code is $code.",
            "Referral discounts are applied on the website: $SITE_BASE_URL"
        ).joinToString("\n")
    }
}
