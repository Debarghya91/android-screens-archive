// AuthLinks.kt
// Payments Training Hub Android - the website links the auth flow
// opens in the browser, and the support mailbox. Mirrors iOS
// SupabaseConfig.siteBaseURL, termsURL and privacyPolicyURL, and the
// support address from the case ID design. The same URL strings are
// already used by the legal destinations in AppNavGraph.

package com.paymentstraininghub.app.ui.auth

/** Website links and the support address for the auth and profile flows. */
object AuthLinks {
    /** The live learning website. */
    const val SITE_BASE_URL = "https://www.paymentstraininghub.com"

    /** The terms page. */
    const val TERMS_URL = "https://www.paymentstraininghub.com/terms"

    /** The privacy policy page. */
    const val PRIVACY_URL = "https://www.paymentstraininghub.com/privacy"

    /** The support mailbox for account help. */
    const val SUPPORT_EMAIL = "support@paymentstraininghub.com"
}
