// AccessPolicy.kt
// Payments Training Hub Android - the pure access-model
// decisions, as pure Kotlin with no Android dependency, so the
// rules are unit-tested on the JVM. The access model (17 Nov
// 2026) replaced the mandatory sign-in gate: content is
// browsable without a session, the first-run introduction shows
// on first launch regardless of session, and sign-in is
// prompted only when the learner taps a Buy action while signed
// out. After sign-in the learner returns to the purchase. An
// admin session (see AdminClaims) unlocks everything and is
// never prompted to buy; that rule lives in the entitlement
// checks, not here.

package com.paymentstraininghub.app.ui.navigation

object AccessPolicy {

    /**
     * The main graph's start destination for a cold-start deep
     * link [initialRoute]: the link itself when it names a known
     * route, Home otherwise. Content links land directly, with
     * no session: the link opens whether or not the learner is
     * signed in.
     */
    fun mainStartDestination(initialRoute: String?): String {
        return if (initialRoute != null && Routes.isKnownRoute(initialRoute)) {
            initialRoute
        } else {
            Routes.HOME
        }
    }

    /**
     * True when [route] is a purchase route: the checkout list
     * or one product's checkout screen. Only purchase routes
     * ever prompt sign-in.
     */
    fun isPurchaseRoute(route: String): Boolean {
        return route == Routes.CHECKOUT || route.startsWith(Routes.CHECKOUT + "/")
    }

    /**
     * The route a purchase attempt opens for [route] and
     * [signedIn]: the sign-in screen when the learner is signed
     * out and the route is a purchase route, the route itself
     * otherwise. The caller remembers the purchase route and
     * opens it after sign-in.
     */
    fun routeForPurchaseAttempt(route: String, signedIn: Boolean): String {
        return if (!signedIn && isPurchaseRoute(route)) Routes.AUTH else route
    }
}
