// CheckoutScreen.kt
// Courses (checkout). Product cards from
// ProductCatalogue. Prices come ONLY from BillingManager.priceText
// (purchases) and BillingManager.renewalPriceText (renewals);
// when either returns "Price unavailable" the matching button is
// DISABLED. No hardcoded rupee prices anywhere in Release. DEBUG
// builds only: while the Play price has not loaded, the catalogue
// reference sale price is shown labelled as the catalogue price
// with a Play-not-loaded note, plus a debug-only test unlock, so
// unlocking can be verified end to end before Play products exist.
// Every purchase gives 1 year of access; each product renews for
// 1 more year through its renewal subscription at half the sale
// price. The Integrated Course includes everything. List prices
// are website display only and are never shown here. No em dashes
// in any user-facing text.

package com.paymentstraininghub.app.ui.checkout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.BuildConfig
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.DebugTestUnlock
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.CourseProduct
import com.paymentstraininghub.app.data.model.ProductScope
import com.paymentstraininghub.app.features.assessment.FinalAssessmentStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import kotlinx.coroutines.launch

private const val PRICE_UNAVAILABLE = "Price unavailable"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    productId: String?,
    onBack: () -> Unit,
    onShowAllCourses: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    // Collected so prices and owned state refresh when BillingManager loads.
    @Suppress("UNUSED_VARIABLE")
    val billingProducts by BillingManager.products.collectAsState()
    // Collected so owned state refreshes when entitlements load or
    // change, and when the premium gate resolves.
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too: it
    // is a key of the per-row owned remember below.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    // Collected so owned state refreshes when a DEBUG test unlock
    // lands. Always empty in Release.
    @Suppress("UNUSED_VARIABLE")
    val debugIds by BillingManager.debugUnlockedIds.collectAsState()
    // Collected so renewal state refreshes when a renewal
    // subscription starts or ends.
    @Suppress("UNUSED_VARIABLE")
    val renewalIds by BillingManager.activeRenewalIds.collectAsState()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var purchasingId by remember { mutableStateOf<String?>(null) }
    var renewingId by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Final Assessment state for the owned-course rows below.
    // Purchase events from BillingManager land in the same
    // message line (Tester 2 E10): the SharedFlow previously had
    // zero collectors, so billing messages vanished.
    LaunchedEffect(Unit) {
        FinalAssessmentStore.refresh()
        BillingManager.purchaseEvents.collect { message = it }
    }

    val allProducts = ProductCatalogue.products
    val shownProducts = remember(productId, allProducts) {
        if (productId != null) {
            val single = allProducts.firstOrNull { it.productId == productId }
            if (single != null) listOf(single) else allProducts
        } else {
            allProducts
        }
    }
    val showingSingle = productId != null &&
        allProducts.any { it.productId == productId }
    val allPricesUnavailable = shownProducts.isNotEmpty() &&
        shownProducts.all { priceUnavailable(BillingManager.priceText(it.productId)) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Courses", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                navigationIconContentColor = PfColors.navy
            )
        )
        LazyColumn(
            state = rememberTopLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            item {
                Text(
                    "Buy a course to unlock it for 1 year. Each course unlocks its own chapters. The Integrated Course unlocks everything: all 10 courses, Studio, Simulator, Investigation Lab, Practice Experiences, and Interview Ready.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PfColors.navy
                )
                Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                Text(
                    "Each purchase gives 1 year of access. Renew for 1 more year at half the price through the renewal subscription. Purchases restore with the same Google Play account, and sync to your account when you are signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            if (allPricesUnavailable) {
                item {
                    Text(
                        "Prices could not be loaded from Google Play. No purchase can be made until the price loads.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.error
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    OutlinedButton(
                        onClick = {
                            message = null
                            BillingManager.retryConnection()
                        }
                    ) {
                        Text("Retry connection")
                    }
                    if (BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) {
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        Text(
                            "Test build: catalogue prices are shown below. The Google Play price has not loaded, so the test unlock can be used to verify unlocking without a payment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
            if (showingSingle) {
                item {
                    OutlinedButton(onClick = onShowAllCourses) {
                        Text("See all courses")
                    }
                }
            }
            items(shownProducts, key = { it.productId }) { product ->
                // Keyed on every gate input, including the admin
                // flag and the DEBUG test-unlock set: bare calls
                // here would keep the guest-time values when
                // restore lands while this list is on screen.
                val owned = remember(
                    product.productId, ownedIds, serverIds, gateState, debugIds, isAdmin
                ) { BillingManager.isProductUnlocked(product.productId) }
                val renewalActive = remember(
                    product.productId, renewalIds
                ) { BillingManager.hasActiveRenewal(product.productId) }
                ProductCard(
                    product = product,
                    priceText = BillingManager.priceText(product.productId),
                    cataloguePriceText = BillingManager.debugCataloguePriceText(product.productId),
                    renewalPriceText = BillingManager.renewalPriceText(product.productId),
                    catalogueRenewalPriceText = BillingManager.debugCatalogueRenewalPriceText(product.productId),
                    owned = owned,
                    renewalActive = renewalActive,
                    busy = purchasingId == product.productId,
                    renewBusy = renewingId == product.productId,
                    onNavigate = onNavigate,
                    onTestUnlock = if (BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) {
                        {
                            DebugTestUnlock.unlockForTest(product.productId)
                            message = "${product.name} unlocked for testing. This is a debug build only: no payment was taken and nothing was bought."
                        }
                    } else {
                        null
                    },
                    onBuy = {
                        // One purchase flow at a time: a second Buy
                        // or Renew tap while a flow is in flight is
                        // ignored, so two Play sheets can never
                        // overlap and strand each other.
                        if (purchasingId != null || renewingId != null || restoring) return@ProductCard
                        // Sign-in is prompted only at a Buy action:
                        // a signed-out learner goes to the sign-in
                        // screen and returns here after signing in.
                        if (AuthManager.authState.value is AuthManager.AuthState.Guest) {
                            onNavigate(Routes.AUTH)
                            return@ProductCard
                        }
                        if (activity == null) {
                            message = "Could not start the purchase on this device. Please try again."
                            return@ProductCard
                        }
                        purchasingId = product.productId
                        message = null
                        scope.launch {
                            try {
                                BillingManager.purchase(activity, product.productId)
                                message = "Returned from Google Play. If the purchase completed, the content is now unlocked."
                            } catch (e: Exception) {
                                message = "Purchase did not complete. Please try again."
                            } finally {
                                purchasingId = null
                            }
                        }
                    },
                    onRenew = {
                        if (purchasingId != null || renewingId != null || restoring) return@ProductCard
                        // Sign-in is prompted only at a purchase
                        // action: a signed-out learner goes to the
                        // sign-in screen and returns here after
                        // signing in.
                        if (AuthManager.authState.value is AuthManager.AuthState.Guest) {
                            onNavigate(Routes.AUTH)
                            return@ProductCard
                        }
                        if (activity == null) {
                            message = "Could not start the renewal on this device. Please try again."
                            return@ProductCard
                        }
                        renewingId = product.productId
                        message = null
                        scope.launch {
                            try {
                                BillingManager.purchaseRenewal(activity, product.productId)
                                message = "Returned from Google Play. If the renewal completed, access continues for 1 more year."
                            } catch (e: Exception) {
                                message = "Renewal did not complete. Please try again."
                            } finally {
                                renewingId = null
                            }
                        }
                    }
                )
            }
            item {
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = PfColors.navy)
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PfColors.card),
                    border = BorderStroke(1.dp, PfColors.border)
                ) {
                    Column(modifier = Modifier.padding(PfSpacing.medium)) {
                        Text(
                            "Free, no purchase needed",
                            style = MaterialTheme.typography.titleMedium,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        ChecklistLine("The first lesson of each chapter, with full content")
                        ChecklistLine("The first 2 lessons of each course, in reading order")
                        ChecklistLine("2 sample Payment Situations")
                        ChecklistLine("5 free interview questions")
                        ChecklistLine("The weekly free case")
                        ChecklistLine("ISO 20022 Explorer, free for everyone")
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            "What buying unlocks",
                            style = MaterialTheme.typography.titleMedium,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        ChecklistLine("A course unlocks only its own chapters, for 1 year")
                        ChecklistLine("The Integrated Course unlocks everything for 1 year: all 10 courses, Studio, Simulator, Investigation Lab, Practice Experiences, and Interview Ready.")
                        ChecklistLine("Practice Experiences unlocks the full set of ${ContentManifest.situationCount} situations, Practice Mode, the Daily Challenge, labs, ISO exercises, and weekly cases.")
                        ChecklistLine("Interview Ready unlocks ${ContentManifest.interviewQuestionCount} interview questions and ${ContentManifest.faqCount} FAQ items.")
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        Text(
                            "How to choose",
                            style = MaterialTheme.typography.titleMedium,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        Text(
                            "Each chapter sits in exactly one course, so courses never overlap. Buying two courses never buys the same chapter twice.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        Text(
                            "Every purchase gives 1 year of access. Each product renews for 1 more year at half the price.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        // Same one-flow-at-a-time guard as Buy and
                        // Renew: restoring while a purchase flow is
                        // in flight is ignored.
                        if (purchasingId != null || renewingId != null) return@OutlinedButton
                        restoring = true
                        message = null
                        scope.launch {
                            try {
                                BillingManager.restore()
                                message = "Restore request finished. Any courses you own are now unlocked on this device."
                            } catch (e: Exception) {
                                message = "Restore did not complete. Please try again."
                            } finally {
                                restoring = false
                            }
                        }
                    },
                    enabled = !restoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (restoring) {
                        CircularProgressIndicator(color = PfColors.royal)
                    } else {
                        Text("Restore Purchases")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: CourseProduct,
    priceText: String,
    cataloguePriceText: String?,
    renewalPriceText: String,
    catalogueRenewalPriceText: String?,
    owned: Boolean,
    renewalActive: Boolean,
    busy: Boolean,
    renewBusy: Boolean,
    onNavigate: (String) -> Unit,
    onTestUnlock: (() -> Unit)?,
    onBuy: () -> Unit,
    onRenew: () -> Unit
) {
    val priceAvailable = !priceUnavailable(priceText)
    // DEBUG only: while the Play price has not loaded, the
    // catalogue reference price is shown, labelled as such. Null
    // in Release, so Release cards are unchanged.
    val showCataloguePrice = !priceAvailable && cataloguePriceText != null
    val renewalAvailable = !priceUnavailable(renewalPriceText)
    val showCatalogueRenewalPrice = !renewalAvailable && catalogueRenewalPriceText != null
    val certificates by FinalAssessmentStore.certificates.collectAsState()
    val attemptsMap by FinalAssessmentStore.attemptsUsed.collectAsState()
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(product.name, style = MaterialTheme.typography.titleLarge, color = PfColors.navy)
                    Text(product.tagline, style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
                }
                if (owned) {
                    Surface(
                        color = PfColors.goldSoft,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Owned",
                            style = MaterialTheme.typography.labelSmall,
                            color = PfColors.goldText,
                            modifier = Modifier.padding(
                                horizontal = PfSpacing.small,
                                vertical = PfSpacing.xSmall
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(product.description, style = MaterialTheme.typography.bodyMedium, color = PfColors.navy)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(product.audience, style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(contentsText(product), style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
            Text("1 year of access", style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                when {
                    priceAvailable -> priceText
                    showCataloguePrice -> cataloguePriceText!!
                    else -> PRICE_UNAVAILABLE
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (priceAvailable || showCataloguePrice) PfColors.navy else PfColors.error
            )
            if (showCataloguePrice) {
                Text(
                    "Catalogue price. Google Play price not loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            if (product.scope == ProductScope.INTEGRATED) {
                Text(
                    "Unlocks everything: all 10 courses, Studio, Simulator, Investigation Lab, Practice Experiences, and Interview Ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            ChecklistLine("1 year of access, renews for 1 more year at half the price")
            ChecklistLine("Restores with the same Google Play account")
            if (product.includesSituations && product.scope == ProductScope.PRACTICE) {
                ChecklistLine("The full set of ${ContentManifest.situationCount} situations, Practice Mode, the Daily Challenge, labs, ISO exercises, and weekly cases")
            } else if (product.scope == ProductScope.PART) {
                ChecklistLine("Unlocks only its own chapters")
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            Button(
                onClick = onBuy,
                enabled = !owned && priceAvailable && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PfColors.royal,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    busy -> CircularProgressIndicator(color = Color.White)
                    owned -> Text("Owned")
                    priceAvailable -> Text("Buy for $priceText")
                    else -> Text(PRICE_UNAVAILABLE)
                }
            }
            if (owned && !renewalActive) {
                Spacer(modifier = Modifier.height(PfSpacing.small))
                OutlinedButton(
                    onClick = onRenew,
                    enabled = renewalAvailable && !renewBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        renewBusy -> CircularProgressIndicator(color = PfColors.royal)
                        renewalAvailable -> Text("Renew for 1 more year: $renewalPriceText")
                        showCatalogueRenewalPrice -> Text("Renewal price not loaded")
                        else -> Text("Renewal price unavailable")
                    }
                }
                if (showCatalogueRenewalPrice) {
                    Text(
                        "Catalogue renewal price $catalogueRenewalPriceText. Google Play renewal price not loaded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
            }
            if (owned && renewalActive) {
                Spacer(modifier = Modifier.height(PfSpacing.small))
                Text(
                    "Renewal active: access continues while the renewal subscription stays active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            if (!priceAvailable && !owned) {
                Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                Text(
                    if (showCataloguePrice) {
                        "The Google Play price has not loaded, so no real purchase can be made from this build yet."
                    } else {
                        "The Google Play price has not loaded. No purchase can be made until it loads."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            // DEBUG only test unlock, shown only while the Play
            // price has not loaded: it unlocks the product locally
            // so unlocking can be verified end to end without Play.
            // Never present in Release (onTestUnlock is null there).
            if (showCataloguePrice && !owned && onTestUnlock != null) {
                Spacer(modifier = Modifier.height(PfSpacing.small))
                OutlinedButton(
                    onClick = onTestUnlock,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test unlock (debug only, no payment)")
                }
            }
            // Final Assessment entry for an owned course: the
            // certificate when earned, attempts left when open, or
            // a locked note when all attempts were used without a
            // pass. Situations has no chapters and no assessment,
            // so it never reaches here.
            if (owned && product.chapterIds.isNotEmpty()) {
                val certificate = certificates[product.productId]
                val remaining = FinalAssessmentStore.attemptsRemaining(product.productId)
                val used = attemptsMap[product.productId] ?: 0
                Spacer(modifier = Modifier.height(PfSpacing.small))
                Divider(color = PfColors.border)
                Spacer(modifier = Modifier.height(PfSpacing.small))
                if (certificate != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(Routes.courseCertificate(product.productId)) }
                    ) {
                        Icon(
                            Icons.Filled.WorkspacePremium,
                            contentDescription = null,
                            tint = PfColors.gold,
                            modifier = Modifier.padding(end = PfSpacing.small)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Certificate earned",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                            Text(
                                "${certificate.courseName} - ${certificate.scoreText} (${Math.round(certificate.scorePercent).toInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = PfColors.secondaryText
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = "View certificate",
                            tint = PfColors.secondaryText
                        )
                    }
                } else if (remaining > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(Routes.finalAssessment(product.productId)) }
                    ) {
                        Icon(
                            Icons.Filled.Assignment,
                            contentDescription = null,
                            tint = PfColors.royal,
                            modifier = Modifier.padding(end = PfSpacing.small)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Final Assessment",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                            Text(
                                "20 questions, pass above 70%. $used of ${FinalAssessmentStore.MAX_ATTEMPTS} attempts used.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PfColors.secondaryText
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = "Open Final Assessment",
                            tint = PfColors.secondaryText
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = PfColors.secondaryText,
                            modifier = Modifier.padding(end = PfSpacing.small)
                        )
                        Text(
                            "Final Assessment locked. All ${FinalAssessmentStore.MAX_ATTEMPTS} attempts used. Contact Payments Training Hub support for another attempt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistLine(text: String) {
    Row(
        modifier = Modifier.padding(vertical = PfSpacing.xSmall),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = PfColors.success,
            modifier = Modifier.padding(end = PfSpacing.small)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = PfColors.navy)
    }
}

private fun contentsText(product: CourseProduct): String {
    return when (product.scope) {
        ProductScope.INTEGRATED -> "All 10 courses, 99 chapters, and every standalone product"
        ProductScope.PART -> "${product.chapterIds.size} chapters"
        ProductScope.STUDIO -> "156 teaching units and 5 career guides"
        ProductScope.SIMULATOR -> "All ${ContentManifest.simulatorCount} simulators"
        ProductScope.INVESTIGATION_LAB -> "616 investigation cases"
        ProductScope.PRACTICE -> "${ContentManifest.situationCount} situations, Practice Mode, Daily Challenge, labs, exercises, weekly cases"
        ProductScope.INTERVIEW -> "${ContentManifest.interviewQuestionCount} interview questions and ${ContentManifest.faqCount} FAQ items"
    }
}

private fun priceUnavailable(priceText: String): Boolean {
    return priceText.isBlank() || priceText == PRICE_UNAVAILABLE
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
