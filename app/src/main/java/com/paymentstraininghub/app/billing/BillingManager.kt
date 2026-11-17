package com.paymentstraininghub.app.billing

/*
 * BillingManager: Google Play Billing for the Android app.
 * Every product is a one-time purchase (INAPP) giving 1 year of
 * access, plus a renewal subscription (SUBS, a 1-year base plan)
 * at half the sale price. Product IDs and catalogue data live in
 * ProductCatalogue; Play Billing is the only source of a purchase
 * price. A hardcoded catalogue price is NEVER shown as a purchase
 * price: priceText returns the loaded ProductDetails price or
 * "Price unavailable", and purchase is refused until the product
 * has loaded. The single exception is DEBUG builds, where Checkout
 * may show the catalogue reference sale price labelled honestly
 * as the catalogue price with a "Play price not loaded" note, plus
 * a debug-only test unlock, so unlocking can be verified end to
 * end without Play products existing yet. Release behaviour is
 * unchanged and fail-closed. List prices are website display only
 * and are never shown in this app.
 *
 * Access rules (ProductCatalogue): a part unlocks only its own
 * chapters, while active. A standalone product unlocks only its
 * own experience, while active. The Integrated Course unlocks
 * everything, while active. A product is active when the user
 * holds an active purchase of it, an active renewal subscription
 * for it, or an active Integrated purchase or subscription.
 * Expired entitlements lock again. The first lesson of each
 * chapter is free (D77), plus the first 2 lessons of each part
 * in reading order. The 2 free sample situations stay free.
 *
 * SECURITY, mirroring iOS: in Release builds a product unlocks ONLY
 * when (a) Google Play reports an owned purchase for it through
 * queryPurchasesAsync or the purchases listener, with a
 * one-time purchase still inside its 1-year validity, or an
 * active renewal subscription, or (b) the signed-in user has an
 * active, unexpired row in Supabase user_purchases for this
 * product, or (c) a failed server read falls back to the
 * server-verified entitlement cache for that same account, which
 * replays only rows the server itself confirmed, only for 7 days,
 * and never for guests or for another account (see EntitlementStore
 * H2 cache). No other stored flag alone ever unlocks anything in
 * Release: the store starts locked and only verified entitlements
 * open it. A failed, cancelled, pending or unverified purchase
 * NEVER unlocks content. Only verified purchases are acknowledged.
 * The EntitlementStore unlocked-product cache is never read to gate
 * access in Release. Refunds, revocations and expiries remove the
 * entitlement on the next refreshEntitlements() call, and remove
 * the downloaded audio for the revoked product's chapters.
 *
 * Server entitlement read: user_purchases rows are read with NO
 * store filter (status=eq.active only), so a website purchase
 * unlocks the same account in-app, exactly as on iOS. The read
 * requests expires_at and purchased_at and enforces expiry
 * app-side: a row whose expiry has passed unlocks nothing, and a
 * row with no expiry falls back to purchased_at plus 1 year. When
 * the server has not been updated with the expiry columns yet,
 * the read retries without them and treats rows as active, as
 * before, so the parallel server update cannot break the read.
 * Renewal subscription ids in rows resolve to their base product.
 * Play rows are written only by the verify-play-purchase function
 * after it confirms the token with the Google Play Developer API
 * (see ServerSync); the read mirrors iOS, which trusts the server
 * row the record-purchase function wrote for Apple purchases.
 *
 * Fail closed (M1): a chapter no part contains and which is
 * not held out is LOCKED. An unmapped chapter is a catalogue
 * error and must never be given away silently. ch37 is held out
 * on purpose: it is in no product and is never sold, so it is
 * always reachable, matching iOS and the website. A lesson id
 * with no chapter number is never locked, matching iOS
 * LessonView.
 *
 * Local verification: a purchase is accepted only when its state is
 * PURCHASED and both its purchase token and its Play signature are
 * present. Full cryptographic verification of the Play signature
 * against the Play public key remains a follow-up; server-side
 * verification of the purchase token runs in the
 * verify-play-purchase function (see ServerSync). Nothing
 * here fakes verification: without a token and signature the
 * purchase unlocks nothing and is not acknowledged.
 *
 * Free sample lessons: isLessonUnlocked treats a lesson as free
 * when EntitlementRules.isFreeSampleLesson says so: lesson number
 * 1 within its chapter (D77), or lesson number 2 of its part's
 * first chapter (the 2-sample sequential rule), parsed from the
 * lesson id ("ch{n}s1", "ch{n}s2"). Verified 12 Nov 2026 against
 * the live search index (100 chapters, 746 lessons) and
 * re-verified 15 Nov 2026 at 881 lessons: every chapter's first
 * lesson in catalogue order is ch{n}s1, every chapter has an s1,
 * and the 72 supplemental lessons all carry s901+ numbers, so no
 * supplemental id collides with the sample rule. This matches the
 * iOS definition (first lesson of the chapter in catalogue order)
 * without a network call. Screens that already hold the chapter
 * list use ContentRepository.isSampleLesson, which checks
 * first-in-chapter directly; both rules agree on the live
 * catalogue for the first lesson.
 *
 * DEBUG/qa gate: DebugTestUnlock (below) is the only test unlock path.
 * Every debug path is guarded by BuildConfig.DEBUG or
 * BuildConfig.ENABLE_TEST_UNLOCK (qa build type only), and Release
 * startup purges debug keys. There is no Release bypass.
 */

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.paymentstraininghub.app.BuildConfig
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.CourseProduct
import com.paymentstraininghub.app.media.LessonDownloadStore
import com.paymentstraininghub.app.security.PremiumAccessGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.time.Instant
import org.json.JSONArray

object BillingManager {

    private const val MAX_RECONNECT_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var billingClient: BillingClient? = null
    private var reconnectAttempts = 0
    private var setupInFlight = false

    /** Application context, kept only to remove revoked downloads. Set once in startBillingClient. */
    private var appContext: Context? = null

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

    /** Loaded Play products by product ID, empty until the store loads them. */
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _isReady = MutableStateFlow(false)

    /** True once the billing client is connected and the first product query has run. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Billing notices for the Checkout screen, delivered only to
     * a live collector: a rendezvous channel holds nothing, so a
     * notice emitted while Checkout is not open is dropped
     * instead of surfacing stale on the next visit (the old
     * buffered SharedFlow replayed up to 16 stale notices).
     * Checkout is the only collector.
     */
    private val purchaseEventChannel = Channel<String>(Channel.RENDEZVOUS)

    /** Human readable purchase status messages, for UI snackbars. */
    val purchaseEvents: Flow<String> = purchaseEventChannel.receiveAsFlow()

    private val _ownedProductIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Base product IDs Play currently reports as owned and active
     * on this device: one-time purchases still inside their
     * 1-year validity, plus products with an active renewal
     * subscription. Set only from queryPurchasesAsync results
     * and verified purchase listener callbacks, never from stored
     * flags. Renewal ids are resolved to their base product.
     */
    val ownedProductIds: StateFlow<Set<String>> = _ownedProductIds.asStateFlow()

    /**
     * Renewal subscription ids Play currently reports as active
     * on this device. Set only from SUBS query results and
     * verified purchase listener callbacks.
     */
    private val _activeRenewalIds = MutableStateFlow<Set<String>>(emptySet())
    val activeRenewalIds: StateFlow<Set<String>> = _activeRenewalIds.asStateFlow()

    /**
     * Play-side expiry instants by base product id, for the
     * one-time purchases in [_ownedProductIds]: purchase time
     * plus 1 year. Products active only through a renewal
     * subscription carry a null expiry here: Play owns the
     * subscription state. Read at gate time so an entitlement
     * that expires mid-session locks without waiting for the
     * next refresh.
     */
    @Volatile
    private var playExpiries: Map<String, Long?> = emptyMap()

    /**
     * Server-side expiry instants by base product id, for the
     * rows in [_serverEntitledProductIds]. Null means the server
     * supplied no expiry and the store or server owns the state.
     * Read at gate time, like [playExpiries].
     */
    @Volatile
    private var serverExpiries: Map<String, Long?> = emptyMap()

    /**
     * Product IDs with an active user_purchases row for the signed-in
     * user on the server. This is the ONLY server path that unlocks
     * a product. It is set by a definitive server read, or, when a
     * server read fails, by replaying the server-verified cache for
     * the same account (H2); it is never set from any other stored
     * state. Guests (no account) never have one; their Play purchase
     * is what unlocks them. Mirrors StoreManager.serverEntitledProductIDs.
     */
    private val _serverEntitledProductIds = MutableStateFlow<Set<String>>(emptySet())
    val serverEntitledProductIds: StateFlow<Set<String>> = _serverEntitledProductIds.asStateFlow()

    /**
     * The premium access gate state (PremiumAccessGate). Screens
     * collect this alongside the entitlement flows so lock UI
     * refreshes when the gate resolves: isProductUnlocked denies
     * premium while the gate is Unknown or Blocked, so a screen
     * that never observes the gate would stay locked after the
     * gate allows.
     */
    val premiumGateState: StateFlow<PremiumAccessGate.GateState> = PremiumAccessGate.state

    /**
     * True when a Play purchase could not be recorded on the server.
     * The on-device Play entitlement still works; this only means
     * the purchase is not yet visible on other devices. Mirrors
     * StoreManager.serverEntitlementSyncPending.
     */
    private val _serverEntitlementSyncPending = MutableStateFlow(false)
    val serverEntitlementSyncPending: StateFlow<Boolean> = _serverEntitlementSyncPending.asStateFlow()

    /** DEBUG only: product IDs unlocked through DebugTestUnlock. Always empty in Release. */
    private val _debugUnlockedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Public read of the debug unlock set, so Checkout refreshes when a test unlock lands. Always empty in Release. */
    val debugUnlockedIds: StateFlow<Set<String>> = _debugUnlockedIds.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        scope.launch { handlePurchasesUpdated(billingResult, purchases) }
    }

    /**
     * Build the billing client and connect. Call once at launch.
     * On connect, loads all catalogue products, restores owned
     * products from Play, purges debug keys in Release, and retries
     * any queued server sync. Safe to call again: a second call
     * while a client exists is a no-op.
     */
    fun startBillingClient(context: Context) {
        if (billingClient != null) return
        appContext = context.applicationContext
        val client = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        billingClient = client
        connect(client)
    }

    /**
     * Retry the Play Billing connection from the start: the
     * reconnect counter resets, so a connection that gave up
     * after three attempts can be tried again. When no client
     * exists yet, the stored application context starts one.
     * Safe to call while a setup is in flight: connect() drops
     * the duplicate.
     */
    fun retryConnection() {
        reconnectAttempts = 0
        val client = billingClient
        if (client == null) {
            appContext?.let { startBillingClient(it) }
            return
        }
        connect(client)
    }

    private fun connect(client: BillingClient) {
        if (setupInFlight) return
        setupInFlight = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                setupInFlight = false
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempts = 0
                    scope.launch { loadProductsAndRestore() }
                } else {
                    scheduleReconnect(client)
                }
            }

            override fun onBillingServiceDisconnected() {
                _isReady.value = false
                scheduleReconnect(client)
            }
        })
    }

    private fun scheduleReconnect(client: BillingClient) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts += 1
        val backoffMs = 1000L * (1 shl (reconnectAttempts - 1))
        scope.launch {
            delay(backoffMs)
            connect(client)
        }
    }

    private suspend fun loadProductsAndRestore() {
        val client = billingClient ?: return
        loadProducts(client)
        _isReady.value = true
        if (BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) {
            _debugUnlockedIds.value = EntitlementStore.debugUnlockedProductIds()
        }
        EntitlementStore.purgeDebugKeys()
        reconcilePurchases()
        try {
            refreshEntitlementFromServer()
        } catch (e: Exception) {
            // A failed server read never locks Play entitlements;
            // refreshEntitlementFromServer already replayed the
            // cache or cleared the server set.
        }
        try {
            ServerSync.retryPendingServerSync()
        } catch (e: Exception) {
            // Server sync is best effort; Play entitlements already work.
        }
    }

    /**
     * Query Play for the 16 catalogue purchase products (INAPP)
     * and the 16 renewal subscription products (SUBS) and their
     * real prices. The two types are queried separately: one
     * query carries one product type. A failed load leaves
     * products empty, so prices stay unavailable and purchase
     * stays disabled until a later reconnect loads them. Nothing
     * is unlocked. Called at startup (via loadProductsAndRestore)
     * and retried once from purchase() and purchaseRenewal() when
     * a product has not loaded yet, matching the iOS StoreManager
     * purchase retry.
     */
    /**
     * Play Billing Library 8 Java API wrappers. billing-ktx 8.0.0
     * was compiled with Kotlin 2.1 metadata, which this Kotlin
     * 1.9.22 toolchain cannot read, so the app depends on the
     * plain Java billing artifact and wraps its callback APIs in
     * suspend functions. Behaviour is identical to the former
     * billing-ktx suspend calls. Cancellation of the caller does
     * not cancel the Play call, which has no cancel API; the
     * continuation simply ignores a late callback.
     */
    private suspend fun queryProductDetails(
        client: BillingClient,
        params: QueryProductDetailsParams
    ): Pair<BillingResult, QueryProductDetailsResult> =
        suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { billingResult, result ->
                if (cont.isActive) cont.resume(billingResult to result)
            }
        }

    private suspend fun queryPurchases(
        client: BillingClient,
        params: QueryPurchasesParams
    ): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (cont.isActive) cont.resume(billingResult to purchases)
            }
        }

    private suspend fun acknowledgePurchase(
        client: BillingClient,
        params: AcknowledgePurchaseParams
    ): BillingResult =
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { billingResult ->
                if (cont.isActive) cont.resume(billingResult)
            }
        }

    private suspend fun loadProducts(client: BillingClient) {
        val loaded = mutableMapOf<String, ProductDetails>()
        loaded.putAll(queryDetailsForType(client, ProductCatalogue.allProductIds, BillingClient.ProductType.INAPP))
        loaded.putAll(queryDetailsForType(client, ProductCatalogue.allRenewalProductIds, BillingClient.ProductType.SUBS))
        if (loaded.isNotEmpty()) {
            _products.value = loaded
        }
        // An empty result means no prices, so purchase stays
        // disabled until a later reconnect loads them. A partial
        // load still replaces the map only when something loaded,
        // so one failed type never wipes the other type's prices.
    }

    private suspend fun queryDetailsForType(
        client: BillingClient,
        productIds: List<String>,
        productType: String
    ): Map<String, ProductDetails> {
        return try {
            val productList = productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(productType)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()
            val (billingResult, result) = queryProductDetails(client, params)
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.productDetailsList.associateBy { it.productId }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            // A failed product load leaves products empty, so prices
            // stay unavailable and purchase stays disabled until a
            // later reconnect loads them. Nothing is unlocked.
            emptyMap()
        }
    }

    /**
     * The display price for one purchase product from Play Billing
     * when it has loaded, otherwise "Price unavailable". The Play
     * formatted price is the only price ever presented as a
     * purchase price. Pass a purchase id; renewal prices use
     * [renewalPriceText].
     */
    fun priceText(productId: String): String {
        return products.value[productId]?.oneTimePurchaseOfferDetails?.formattedPrice
            ?: "Price unavailable"
    }

    /**
     * The display price for one product's renewal subscription
     * from Play Billing when it has loaded, otherwise "Price
     * unavailable": the first offer's first pricing phase, which
     * is the 1-year base plan price. The Play formatted price is
     * the only renewal price ever presented.
     */
    fun renewalPriceText(productId: String): String {
        val renewalId = ProductCatalogue.productFor(productId)?.renewalProductId ?: return "Price unavailable"
        val details = products.value[renewalId] ?: return "Price unavailable"
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return "Price unavailable"
        return offer.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice
            ?: "Price unavailable"
    }

    /** True when this product has an active renewal subscription on this device. */
    fun hasActiveRenewal(productId: String): Boolean {
        val renewalId = ProductCatalogue.productFor(productId)?.renewalProductId ?: return false
        return _activeRenewalIds.value.contains(renewalId)
    }

    /**
     * DEBUG only: the catalogue reference SALE price for a product
     * ("Rs. 399", Indian grouping, from the unchanged
     * ProductCatalogue.priceInr values), or null in Release builds
     * and for unknown products. Checkout shows it only while the
     * Google Play price has not loaded, labelled as the catalogue
     * price with a Play-not-loaded note. It is never a purchase
     * price and never appears in Release. List prices are never
     * shown, in any build: list display is website-only.
     */
    fun debugCataloguePriceText(productId: String): String? {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return null
        val product = ProductCatalogue.productFor(productId) ?: return null
        return "Rs. " + java.text.NumberFormat
            .getNumberInstance(java.util.Locale("en", "IN"))
            .format(product.priceInr)
    }

    /**
     * DEBUG only: the catalogue reference RENEWAL price for a
     * product, or null in Release builds and for unknown products.
     * Same honesty rules as [debugCataloguePriceText].
     */
    fun debugCatalogueRenewalPriceText(productId: String): String? {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return null
        val product = ProductCatalogue.productFor(productId) ?: return null
        return "Rs. " + java.text.NumberFormat
            .getNumberInstance(java.util.Locale("en", "IN"))
            .format(product.renewalPriceInr)
    }

    /**
     * True when this product is owned outright and active: a Play
     * purchase on this device still inside its 1-year validity,
     * an active renewal subscription for it, or an active,
     * unexpired server entitlement for the signed-in user (a
     * definitive server read, or a fresh server-verified cache
     * record after a failed read). Renewal ids resolve to their
     * base product. The Integrated Course is not counted here:
     * see isProductUnlocked. In Release, ONLY those sources
     * count; no stored flag other than the server-verified cache
     * is ever consulted. In DEBUG the build-phase test unlock set
     * also counts. Expiry is re-checked at call time, so an
     * entitlement that expires mid-session locks without waiting
     * for the next refresh.
     */
    private fun isProductOwned(productId: String): Boolean {
        val base = EntitlementRules.baseProductIdFor(productId)
        if (base !in ProductCatalogue.allProductIds) return false
        val now = System.currentTimeMillis()
        if (_ownedProductIds.value.contains(base) &&
            EntitlementRules.isActive(playExpiries[base], now)
        ) return true
        if (_serverEntitledProductIds.value.contains(base) &&
            EntitlementRules.isActive(serverExpiries[base], now)
        ) return true
        if ((BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) && _debugUnlockedIds.value.contains(base)) return true
        return false
    }

    /**
     * True when this product is unlocked: an admin session
     * unlocks every known product, or the premium access gate
     * allows it AND the product is owned outright and active, or
     * the Integrated Course is owned and active. The Integrated
     * Course unlocks every product. Unknown product IDs never
     * unlock through the Integrated umbrella, or through the
     * admin bypass.
     */
    fun isProductUnlocked(productId: String): Boolean {
        // Admin bypass: the owner's account sees all content,
        // always, and is never prompted to buy. It is checked
        // before the premium gate, so an admin device the gate
        // would block still shows the owner's content. Unknown
        // ids never unlock, even for an admin.
        if (EntitlementRules.isAdminUnlocked(AuthManager.isCurrentUserAdmin(), productId)) return true
        // Anti-piracy gate: no product unlocks while the gate
        // denies premium (unknown state, blocked device or
        // install, failed integrity verdict). The gate is the
        // enforcement point; the entitlement below is only the
        // second half of the decision. Free samples bypass this
        // through isLessonUnlocked / SituationGating, which check
        // the sample lists before reaching here.
        if (!PremiumAccessGate.isAllowed()) return false
        val base = EntitlementRules.baseProductIdFor(productId)
        if (isProductOwned(base)) return true
        if (base == ProductCatalogue.integratedProductId) return false
        if (!ProductCatalogue.allProductIds.contains(base)) return false
        return isProductOwned(ProductCatalogue.integratedProductId)
    }

    /**
     * True when a chapter is unlocked: an admin session unlocks
     * every chapter, a held-out chapter (ch37, the only one) is
     * always reachable because it is in no product and is never
     * sold, the Integrated Course unlocks every mapped chapter,
     * or the part containing this chapter is owned and active.
     * A chapter in no part that is not held out is LOCKED for
     * non-admin learners (fail closed): an unmapped chapter is
     * a catalogue error, and it must never be given away
     * silently. The decision itself is the pure rule in
     * [EntitlementRules.isChapterUnlocked], matching iOS.
     */
    fun isChapterUnlocked(chapterId: String): Boolean {
        val part = ProductCatalogue.productForChapter(chapterId)
        return EntitlementRules.isChapterUnlocked(
            isAdmin = AuthManager.isCurrentUserAdmin(),
            isHeldOutChapter = chapterId in ReadingOrder.heldOutIds,
            hasContainingPart = part != null,
            integratedOwned = isProductOwned(ProductCatalogue.integratedProductId),
            containingPartOwned = part?.let { isProductOwned(it.productId) } ?: false
        )
    }

    /**
     * The part that unlocks a chapter, for the lock screen copy.
     * Null when the chapter is in no part, or when it is already
     * unlocked.
     */
    fun unlockingProductForChapter(chapterId: String): CourseProduct? {
        if (isChapterUnlocked(chapterId)) return null
        return ProductCatalogue.productForChapter(chapterId)
    }

    /**
     * True when a lesson is unlocked: it is a free sample lesson
     * (the first lesson of its chapter, or the second lesson of
     * its part's first chapter), or its chapter is unlocked. A
     * held-out chapter (ch37) is always unlocked. A chapter in
     * no part that is not held out stays LOCKED (fail closed).
     * chapterId is "ch{n}".
     */
    fun isLessonUnlocked(lessonId: String, chapterId: String): Boolean {
        val firstChapterId = ProductCatalogue.productForChapter(chapterId)?.chapterIds?.firstOrNull()
        if (EntitlementRules.isFreeSampleLesson(lessonId, chapterId, firstChapterId)) return true
        return isChapterUnlocked(chapterId)
    }

    /**
     * Situations are unlocked for paid access when Practice
     * Experiences is owned outright and active (a Play purchase
     * active on this device, an active renewal subscription, or
     * an active server entitlement for the signed-in user), or
     * when the Integrated Course is owned and active. The free
     * sample situations stay free regardless of this value.
     * Situations are Practice Experiences content; this is the
     * situations-facing name for [isPracticeUnlocked].
     */
    fun isSituationsUnlocked(): Boolean {
        return isPracticeUnlocked()
    }

    /** True when Practice Experiences is unlocked: itself, or the Integrated Course. */
    fun isPracticeUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.practiceProductId)
    }

    /** True when BA/PO Studio is unlocked: itself, or the Integrated Course. */
    fun isStudioUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.studioProductId)
    }

    /** True when the Payment Simulator is unlocked: itself, or the Integrated Course. */
    fun isSimulatorUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.simulatorProductId)
    }

    /** True when the Investigation Lab is unlocked: itself, or the Integrated Course. */
    fun isInvestigationLabUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.investigationLabProductId)
    }

    /** True when Interview Ready is unlocked: itself, or the Integrated Course. */
    fun isInterviewUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.interviewProductId)
    }

    /** True when the Integrated Course itself is owned and active. */
    fun isIntegratedUnlocked(): Boolean {
        return isProductUnlocked(ProductCatalogue.integratedProductId)
    }

    /** How many catalogue products are unlocked. Mirrors StoreManager.unlockedProductCount. */
    fun unlockedProductCount(): Int {
        return ProductCatalogue.allProductIds.count { isProductUnlocked(it) }
    }

    /**
     * Buy one product. Refuses when the Play product (and its real
     * price) has not loaded, and when the product is already
     * unlocked. The outcome arrives through the purchases listener:
     * only a PURCHASED purchase with a token and signature unlocks
     * anything. Returns the launch result, not the purchase outcome.
     */
    suspend fun purchase(activity: Activity, productId: String): BillingResult {
        // Anti-piracy gate: never take payment on a device or
        // install the gate has blocked, because the purchase could
        // never unlock there. An Unknown gate (refresh pending)
        // still allows the purchase: Play handles the payment and
        // the gate resolves by unlock time.
        if (PremiumAccessGate.state.value is PremiumAccessGate.GateState.Blocked) {
            emitEvent("Premium purchases are unavailable on this device or installation.")
            return billingError(
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                "Premium unavailable on this device or installation"
            )
        }
        // Sign-in required: a purchase belongs to an account, so
        // the Play flow never launches without one. The Buy tap
        // routes a signed-out learner to sign-in first, so this
        // is defence in depth for a session that dies mid-flow.
        val accountId = AuthManager.currentUserId()
        if (accountId.isNullOrBlank()) {
            emitEvent("Sign in to buy this course. Your purchase stays with your account.")
            return billingError(
                BillingClient.BillingResponseCode.ERROR,
                "Sign-in required for purchase"
            )
        }
        val catalogueProduct = ProductCatalogue.productFor(productId)
        if (catalogueProduct == null) {
            emitEvent("That product is not available. Please try again.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "That product is not available. Please try again."
            )
        }
        if (isProductUnlocked(productId)) {
            emitEvent("${catalogueProduct.name} is already unlocked.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                "Already unlocked"
            )
        }
        val client = billingClient
        if (client == null || !isReady.value) {
            emitEvent("Google Play billing is not ready yet. Try again in a moment.")
            return billingError(
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                "Billing client not ready"
            )
        }
        // The price was not loaded yet: retry the product load once,
        // matching iOS, before telling the buyer the price is
        // unavailable.
        var details = products.value[productId]
        if (details == null) {
            loadProducts(client)
            details = products.value[productId]
        }
        if (details == null) {
            emitEvent("The Google Play price has not loaded. No purchase can be made until it loads.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "The Google Play price has not loaded"
            )
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountId)
            .build()
        return withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
    }

    /**
     * Renew one product for 1 more year through its renewal
     * subscription (a 1-year Play base plan at half the sale
     * price). Refuses when the renewal product (and its real
     * price) has not loaded, and when the product already has an
     * active renewal subscription. Renewing a product whose
     * one-time purchase is still active is allowed: the
     * subscription keeps it active when the purchase term ends.
     * The outcome arrives through the purchases listener: only a
     * PURCHASED subscription purchase with a token and signature
     * unlocks anything. Returns the launch result, not the
     * purchase outcome.
     */
    suspend fun purchaseRenewal(activity: Activity, productId: String): BillingResult {
        if (PremiumAccessGate.state.value is PremiumAccessGate.GateState.Blocked) {
            emitEvent("Premium purchases are unavailable on this device or installation.")
            return billingError(
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                "Premium unavailable on this device or installation"
            )
        }
        // Sign-in required, as in purchase(): a renewal belongs
        // to an account, so the Play flow never launches without
        // one.
        val renewalAccountId = AuthManager.currentUserId()
        if (renewalAccountId.isNullOrBlank()) {
            emitEvent("Sign in to renew this course. Your renewal stays with your account.")
            return billingError(
                BillingClient.BillingResponseCode.ERROR,
                "Sign-in required for renewal"
            )
        }
        val catalogueProduct = ProductCatalogue.productFor(productId)
        if (catalogueProduct == null) {
            emitEvent("That product is not available. Please try again.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "That product is not available. Please try again."
            )
        }
        if (hasActiveRenewal(catalogueProduct.productId)) {
            emitEvent("${catalogueProduct.name} renewal is already active.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
                "Renewal already active"
            )
        }
        val client = billingClient
        if (client == null || !isReady.value) {
            emitEvent("Google Play billing is not ready yet. Try again in a moment.")
            return billingError(
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                "Billing client not ready"
            )
        }
        var details = products.value[catalogueProduct.renewalProductId]
        if (details == null) {
            loadProducts(client)
            details = products.value[catalogueProduct.renewalProductId]
        }
        if (details == null) {
            emitEvent("The Google Play renewal price has not loaded. No renewal can be made until it loads.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "The Google Play renewal price has not loaded"
            )
        }
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken.isNullOrBlank()) {
            emitEvent("The Google Play renewal offer has not loaded. No renewal can be made until it loads.")
            return billingError(
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                "The Google Play renewal offer has not loaded"
            )
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(renewalAccountId)
            .build()
        return withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
    }

    /**
     * Restore purchases for this Play account (reinstall, new
     * device). Re-queries Play, re-acknowledges where needed,
     * records restored purchases on the server for the signed-in
     * user, and reports whether anything was found.
     */
    suspend fun restore() {
        val client = billingClient
        if (client == null || !isReady.value) {
            emitEvent("Store unavailable, try again")
            return
        }
        // Resolve the premium gate before reporting: the restored
        // message counts unlocked products, and the gate denies
        // premium while Unknown, so a restore tapped in the first
        // seconds of a launch would otherwise misreport.
        appContext?.let { PremiumAccessGate.refresh(it) }
        val purchases = reconcilePurchases()
        val userId = AuthManager.currentUserId()
        var allRecorded = true
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (!isLocallyVerifiable(purchase)) continue
            val outcome = ServerSync.recordPurchase(purchase, userId)
            if (outcome.isFailure) {
                allRecorded = false
                ServerSync.recordFor(purchase, userId)?.let {
                    EntitlementStore.enqueuePendingServerSync(it)
                }
            }
        }
        _serverEntitlementSyncPending.value = !allRecorded && purchases.isNotEmpty()
        try {
            refreshEntitlementFromServer()
        } catch (e: Exception) {
            // Play entitlements already work; the server set keeps
            // its last definitive or cached value.
        }
        if (PremiumAccessGate.state.value is PremiumAccessGate.GateState.Blocked) {
            emitEvent("Premium content is unavailable on this device or installation.")
        } else if (unlockedProductCount() > 0) {
            emitEvent("Purchases restored. ${unlockedProductCount()} of ${ProductCatalogue.allProductIds.size} products unlocked.")
        } else {
            emitEvent("No purchase was found for this Google Play account.")
        }
    }

    /**
     * Re-check the Play entitlements and the server entitlements.
     * Any product previously owned but no longer reported by Play
     * has its entitlement REMOVED: this is the refund and
     * revocation path. Call on foreground (the coordinator wires
     * it) and after account changes.
     */
    fun refreshEntitlements() {
        scope.launch {
            try {
                reconcilePurchases()
            } catch (e: Exception) {
                // A failed refresh keeps the last known Play state;
                // it never invents or removes entitlements blindly.
            }
            try {
                refreshEntitlementFromServer()
            } catch (e: Exception) {
                // The server set keeps its last definitive or
                // cached value; Play entitlements are unaffected.
            }
        }
    }

    /**
     * Re-check both entitlement sources after authentication, and
     * record any on-device Play purchases on the server so they
     * follow the account to other devices. Call this after sign-in
     * completes. The coordinator wires it from the sign-in flow;
     * BillingManager cannot call it itself because AuthManager
     * owns the sign-in lifecycle. Mirrors
     * StoreManager.syncEntitlementsAfterSignIn.
     */
    fun syncEntitlementsAfterSignIn() {
        scope.launch {
            try {
                val purchases = reconcilePurchases()
                refreshEntitlementFromServer()
                val userId = AuthManager.currentUserId()
                for (purchase in purchases) {
                    if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                    if (!isLocallyVerifiable(purchase)) continue
                    val outcome = ServerSync.recordPurchase(purchase, userId)
                    if (outcome.isFailure) {
                        _serverEntitlementSyncPending.value = true
                        ServerSync.recordFor(purchase, userId)?.let {
                            EntitlementStore.enqueuePendingServerSync(it)
                        }
                    }
                }
                if (_serverEntitlementSyncPending.value) {
                    try {
                        ServerSync.retryPendingServerSync()
                    } catch (e: Exception) {
                        // Queued records retry again on the next launch.
                    }
                }
                refreshEntitlementFromServer()
            } catch (e: Exception) {
                // Sign-in itself succeeded; entitlement sync is
                // retried on the next launch and restore.
            }
        }
    }

    /**
     * Drop every account-scoped entitlement. Call on sign-out so
     * the next account never inherits the previous account's
     * server entitlements. On-device Play entitlements
     * are untouched: they belong to the Play account, not to the
     * app account. The server-verified cache is deliberately NOT
     * deleted here: it stays filed under the signed-out account's
     * namespace, the in-memory set is what is cleared, and account
     * deletion removes the whole namespace. A different account
     * reads different keys and can never replay this account's
     * cache. The coordinator wires this from AuthManager.signOut.
     * Mirrors StoreManager.clearAccountEntitlements.
     */
    fun clearAccountEntitlements() {
        _serverEntitledProductIds.value = emptySet()
        serverExpiries = emptyMap()
        _serverEntitlementSyncPending.value = false
    }

    /**
     * Retry recording on-device purchases on the server after a
     * failed sync. No-op when nothing is pending. Mirrors
     * StoreManager.retryPendingServerSync.
     */
    fun retryPendingServerSync() {
        if (!_serverEntitlementSyncPending.value) return
        scope.launch {
            try {
                ServerSync.retryPendingServerSync()
                _serverEntitlementSyncPending.value = false
                refreshEntitlementFromServer()
            } catch (e: Exception) {
                // Failures stay queued for the next retry.
            }
        }
    }

    // MARK: - Server purchase entitlement (mirrors StoreManager)

    /**
     * Re-read the server entitlements for the signed-in user from
     * Supabase user_purchases. This is the only server unlock path.
     * Guests have no server entitlement: both clear the set.
     * A definitive server answer (the read succeeded) REPLACES the
     * set and the cache, even when it is empty: an empty answer
     * means no active row, and it is the only response that clears
     * a cached entitlement. A failed read (no network, a server
     * error, an undecodable reply, or no usable token) never clears
     * on its own: the fresh server-verified cache for this account
     * is replayed instead, or the set is cleared when there is no
     * fresh cache. The Play entitlements are untouched. Mirrors
     * StoreManager.refreshEntitlementFromServer.
     */
    suspend fun refreshEntitlementFromServer() {
        val userId = AuthManager.currentUserId()
        if (userId.isNullOrBlank()) {
            _serverEntitledProductIds.value = emptySet()
            serverExpiries = emptyMap()
            return
        }
        val token = try {
            AuthManager.validAccessToken()
        } catch (e: Exception) {
            null
        }
        if (token.isNullOrBlank()) {
            applyCachedServerEntitlements(userId)
            return
        }
        val definitive = fetchServerEntitlements(userId, token)
        if (definitive != null) {
            applyDefinitiveServerEntitlements(definitive, userId)
        } else {
            applyCachedServerEntitlements(userId)
        }
    }

    /** Replay the fresh server-verified cache for one account, or clear the server set when there is none. */
    private suspend fun applyCachedServerEntitlements(userId: String) {
        val cached = cachedServerEntitlements(userId)
        if (cached != null) {
            _serverEntitledProductIds.value = cached.keys.toSet()
            serverExpiries = cached
        } else {
            _serverEntitledProductIds.value = emptySet()
            serverExpiries = emptyMap()
        }
    }

    /**
     * One server read for the signed-in user's active rows. Returns
     * a map of base product id to expiry instant (null when the
     * server supplied no expiry) when the server answered
     * definitively (the map may be empty: that means no active
     * row), or null on ANY failure (no network, a server error,
     * an undecodable reply). Null is never evidence about the
     * rows; only a returned map is. Callers must not treat null
     * as empty. The query has NO store filter, so a website
     * purchase unlocks the same account in-app, exactly as on
     * iOS. Renewal subscription ids resolve to their base
     * product. Expiry is enforced by the caller at gate time;
     * rows whose expiry has passed are dropped here. This helper
     * writes no state.
     */
    private suspend fun fetchServerEntitlements(
        userId: String,
        accessToken: String
    ): Map<String, Long?>? {
        // First try the expiry-aware shape. When the server has
        // not been updated with the expiry columns yet, PostgREST
        // rejects the unknown columns and this throws; the legacy
        // shape below keeps the read working until the server
        // update lands.
        try {
            return fetchServerEntitlementRows(
                userId,
                accessToken,
                "select=user_id,product_id,store,status,expires_at,purchased_at&user_id=eq.$userId&status=eq.active",
                withExpiry = true
            )
        } catch (e: Exception) {
            // Fall through to the legacy shape.
        }
        return try {
            fetchServerEntitlementRows(
                userId,
                accessToken,
                "select=user_id,product_id,store,status&user_id=eq.$userId&status=eq.active",
                withExpiry = false
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchServerEntitlementRows(
        userId: String,
        accessToken: String,
        query: String,
        withExpiry: Boolean
    ): Map<String, Long?> {
        val body = SupabaseClient.restGet(
            table = "user_purchases",
            accessToken = accessToken,
            query = query
        )
        val array = JSONArray(body)
        val known = ProductCatalogue.allProductIds.toSet()
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Long?>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val rawProductId = row.optString("product_id", "")
            val base = EntitlementRules.baseProductIdFor(rawProductId)
            if (base !in known) continue
            val expiresAt: Long? = if (!withExpiry) {
                null
            } else {
                parseServerInstant(row.optString("expires_at", ""))
                    ?: parseServerInstant(row.optString("purchased_at", ""))?.let { purchasedAt ->
                        EntitlementRules.expiryForPurchase(purchasedAt)
                    }
            }
            if (expiresAt != null && expiresAt <= now) continue
            if (!result.containsKey(base)) {
                result[base] = expiresAt
            } else {
                val existing = result[base]
                if (expiresAt == null || (existing != null && expiresAt > existing)) {
                    result[base] = expiresAt
                }
            }
        }
        return result
    }

    /** Parse a server timestamp (ISO 8601) to epoch millis, or null when absent or unparseable. */
    private fun parseServerInstant(raw: String): Long? {
        if (raw.isBlank()) return null
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply one definitive server answer: replace the in-memory set
     * and expiry map and overwrite the cache record for this
     * account, even when the answer is empty. An empty definitive
     * answer is what clears a cached entitlement; nothing else may
     * clear it.
     */
    private suspend fun applyDefinitiveServerEntitlements(entitlements: Map<String, Long?>, userId: String) {
        _serverEntitledProductIds.value = entitlements.keys.toSet()
        serverExpiries = entitlements
        try {
            EntitlementStore.saveServerEntitlementCache(userId, entitlements)
        } catch (e: Exception) {
            // The in-memory set is authoritative for this run; the
            // cache write is retried on the next definitive read.
        }
    }

    /** The fresh server-verified cache for one account, or null when there is no usable record. */
    private suspend fun cachedServerEntitlements(userId: String): Map<String, Long?>? {
        return try {
            EntitlementStore.cachedServerEntitlements(userId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reconcile the owned set with what Play reports right now,
     * for both one-time purchases (INAPP) and renewal
     * subscriptions (SUBS). Returns the raw Play purchase list
     * for callers that also need to record purchases on the
     * server. Products missing from the Play result, and
     * one-time purchases past their 1-year validity, lose their
     * entitlement and their cache entry. A failed query for one
     * type leaves the whole owned set untouched: a failed refresh
     * never invents or removes entitlements blindly.
     */
    private suspend fun reconcilePurchases(): List<Purchase> {
        val client = billingClient ?: return emptyList()
        val inAppResult = try {
            queryPurchases(
                client,
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        } catch (e: Exception) {
            return emptyList()
        }
        if (inAppResult.first.responseCode != BillingClient.BillingResponseCode.OK) {
            return emptyList()
        }
        val subsResult = try {
            queryPurchases(
                client,
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        } catch (e: Exception) {
            return emptyList()
        }
        if (subsResult.first.responseCode != BillingClient.BillingResponseCode.OK) {
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val owned = mutableSetOf<String>()
        val expiries = mutableMapOf<String, Long?>()
        val activeRenewals = mutableSetOf<String>()
        val userId = AuthManager.currentUserId()
        val allPurchases = inAppResult.second + subsResult.second
        // One-time purchases first: a purchase is active only
        // inside its 1-year validity. Renewal subscriptions are
        // applied after, and an active renewal always wins over a
        // finite purchase expiry, because Play reports the
        // subscription only while it is active.
        for (purchase in inAppResult.second) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            acknowledgeIfNeeded(client, purchase)
            for (productId in purchase.products) {
                val base = EntitlementRules.baseProductIdFor(productId)
                if (base !in ProductCatalogue.allProductIds) continue
                val expiresAt = EntitlementRules.expiryForPurchase(purchase.purchaseTime)
                if (expiresAt > now) {
                    owned.add(base)
                    val existing = expiries[base]
                    if (existing == null || expiresAt > existing) expiries[base] = expiresAt
                    EntitlementStore.setUnlocked(userId, base, true)
                } else {
                    EntitlementStore.setUnlocked(userId, base, false)
                }
            }
        }
        for (purchase in subsResult.second) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            acknowledgeIfNeeded(client, purchase)
            for (productId in purchase.products) {
                if (!EntitlementRules.isRenewalProductId(productId)) continue
                activeRenewals.add(productId)
                val base = EntitlementRules.baseProductIdFor(productId)
                owned.add(base)
                expiries[base] = null
                EntitlementStore.setUnlocked(userId, base, true)
            }
        }
        val previouslyOwned = _ownedProductIds.value
        val removedIds = previouslyOwned - owned
        for (removedId in removedIds) {
            EntitlementStore.setUnlocked(userId, removedId, false)
        }
        _ownedProductIds.value = owned
        playExpiries = expiries
        _activeRenewalIds.value = activeRenewals
        if (removedIds.isNotEmpty()) {
            removeDownloadsForRevokedProducts(removedIds)
        }
        return allPurchases
    }

    /**
     * Remove downloaded audio for products Play no longer reports
     * as active: a refunded, revoked or expired product must not
     * keep its lessons playable offline. A part removes its
     * chapters' downloads; the Integrated Course removes every
     * chapter's. Standalone products have no lesson downloads, so
     * they remove nothing. Best effort: a storage failure never
     * breaks the entitlement refresh.
     */
    private suspend fun removeDownloadsForRevokedProducts(removedProductIds: Set<String>) {
        val context = appContext ?: return
        try {
            if (removedProductIds.contains(ProductCatalogue.integratedProductId)) {
                LessonDownloadStore.removeAllDownloads(context)
                return
            }
            val chapterIds = removedProductIds.flatMap { productId ->
                ProductCatalogue.productFor(productId)?.chapterIds.orEmpty()
            }.distinct()
            if (chapterIds.isNotEmpty()) {
                LessonDownloadStore.removeDownloadsForChapterIds(context, chapterIds)
            }
        } catch (e: Exception) {
            // Storage failures never break entitlement refresh.
        }
    }

    private suspend fun handlePurchasesUpdated(
        billingResult: BillingResult,
        purchases: List<Purchase>?
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) return
                for (purchase in purchases) {
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> handlePurchased(purchase)
                        Purchase.PurchaseState.PENDING -> emitEvent("Purchase is pending approval. It will complete once it is approved.")
                        else -> Unit
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                emitEvent("Purchase cancelled. Nothing was charged.")
            }
            else -> {
                val message = billingResult.debugMessage
                emitEvent(
                    if (message.isNullOrBlank()) "Purchase failed. Please try again." else message
                )
            }
        }
    }

    private suspend fun handlePurchased(purchase: Purchase) {
        if (!isLocallyVerifiable(purchase)) {
            emitEvent("Purchase could not be verified. Please try again.")
            return
        }
        val client = billingClient
        if (client != null) acknowledgeIfNeeded(client, purchase)
        val userId = AuthManager.currentUserId()
        val now = System.currentTimeMillis()
        val newlyOwned = mutableSetOf<String>()
        val newExpiries = playExpiries.toMutableMap()
        val newRenewals = _activeRenewalIds.value.toMutableSet()
        for (productId in purchase.products) {
            val base = EntitlementRules.baseProductIdFor(productId)
            if (base !in ProductCatalogue.allProductIds) continue
            if (EntitlementRules.isRenewalProductId(productId)) {
                newRenewals.add(productId)
                newlyOwned.add(base)
                newExpiries[base] = null
            } else {
                val expiresAt = EntitlementRules.expiryForPurchase(purchase.purchaseTime)
                if (expiresAt > now) {
                    newlyOwned.add(base)
                    val existing = newExpiries[base]
                    if (existing == null || expiresAt > existing) newExpiries[base] = expiresAt
                }
            }
        }
        _ownedProductIds.value = _ownedProductIds.value + newlyOwned
        playExpiries = newExpiries
        _activeRenewalIds.value = newRenewals
        for (productId in newlyOwned) {
            EntitlementStore.setUnlocked(userId, productId, true)
        }
        scope.launch {
            if (userId.isNullOrBlank()) {
                // No account (the session died mid-purchase): the
                // Play entitlement above already unlocks this
                // device, and the next sign-in re-records every
                // Play purchase for the account. Nothing is queued
                // without an account: a record with no user id can
                // never be accepted by the server, so queueing it
                // would only retry forever.
                return@launch
            }
            val outcome = ServerSync.recordPurchase(purchase, userId)
            if (outcome.isFailure) {
                _serverEntitlementSyncPending.value = true
                ServerSync.recordFor(purchase, userId)?.let {
                    EntitlementStore.enqueuePendingServerSync(it)
                }
            } else {
                _serverEntitlementSyncPending.value = false
                try {
                    refreshEntitlementFromServer()
                } catch (e: Exception) {
                    // The Play entitlement already works; the server
                    // set refreshes on the next launch or restore.
                }
            }
        }
        emitEvent(
            "Thank you. ${purchase.products.firstOrNull()?.let { ProductCatalogue.productFor(it)?.name } ?: "The course"} is unlocked on this device."
        )
    }

    /**
     * Local verification only: PURCHASED state plus a purchase token
     * and a Play signature. Cryptographic signature verification
     * remains a follow-up; server-side purchase token verification
     * runs in the verify-play-purchase function, see ServerSync.
     * An unverified purchase is never acknowledged and never
     * unlocks anything.
     */
    private fun isLocallyVerifiable(purchase: Purchase): Boolean {
        return purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            purchase.purchaseToken.isNotBlank() &&
            purchase.signature.isNotBlank()
    }

    private suspend fun acknowledgeIfNeeded(client: BillingClient, purchase: Purchase) {
        if (purchase.isAcknowledged) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        try {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            acknowledgePurchase(client, params)
        } catch (e: Exception) {
            // Acknowledgement is retried on the next purchase query,
            // because isAcknowledged stays false until it succeeds.
            // The entitlement is still honoured: Play allows three
            // days before an unacknowledged purchase is refunded.
        }
    }

    /** DEBUG only entry point for DebugTestUnlock. No-op in Release. */
    internal fun debugUnlock(productId: String) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return
        _debugUnlockedIds.value = _debugUnlockedIds.value + productId
        scope.launch { EntitlementStore.setDebugUnlocked(productId, true) }
    }

    private fun emitEvent(message: String) {
        purchaseEventChannel.trySend(message)
    }

    private fun billingError(responseCode: Int, message: String): BillingResult {
        return BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(message)
            .build()
    }
}

/**
 * DEBUG and qa-test-build only test unlock. Writes a debug namespaced key through
 * EntitlementStore and updates BillingManager's in-memory debug set.
 * Every path is guarded by BuildConfig.DEBUG or BuildConfig.ENABLE_TEST_UNLOCK
 * (qa only): in a Release build this function does nothing, and Release startup
 * purges any debug keys left behind. Never use this to unlock content for real users.
 */
object DebugTestUnlock {
    fun unlockForTest(productId: String) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_TEST_UNLOCK) return
        BillingManager.debugUnlock(productId)
    }
}
