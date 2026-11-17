// HelpSupportScreen.kt
// Help and support, matching the iOS HelpSupportView: a Contact
// section with the support email (opens the mail app, plus a
// Copy button), and the Frequently asked questions from the live
// search index. Tapping
// a question expands it to the full answer, rendered with
// PfBodyText; tapping again collapses it to the 3-line preview.
// FAQ answers belong to Interview Ready, matching iOS: the first
// 5 in catalogue order are free samples. A locked FAQ shows its
// title only, never preview text, and expanding it shows the
// paywall view with the checkout button.

package com.paymentstraininghub.app.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.EntryAccessRules
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

const val SUPPORT_EMAIL = "support@paymentstraininghub.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(
    onBack: () -> Unit,
    // Kept for call-site compatibility: the guided tour was
    // removed, so this callback is never invoked.
    @Suppress("UNUSED_PARAMETER") onReplayTour: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var faqs by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var expandedFaqId by remember { mutableStateOf<String?>(null) }
    var showAllFaqs by remember { mutableStateOf(false) }
    var emailCopied by remember { mutableStateOf(false) }
    // Collected so the FAQ gate refreshes when entitlements load
    // or change, and when the admin flag lands on restore.
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val interviewUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        runCatching { BillingManager.isInterviewUnlocked() }.getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        val entries = runCatching { ContentRepository.loadSearchIndex().entries }
            .getOrElse { emptyList() }
        faqs = entries.filter { it.isFaq }
    }

    val displayedFaqs = if (showAllFaqs) faqs else faqs.take(20)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Help and Support", color = PfColors.navy) },
            actions = {
                TextButton(onClick = onBack) {
                    Text("Done", color = PfColors.royal)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                actionIconContentColor = PfColors.navy
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            Text(
                "Contact",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openMailApp(context) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Icon(
                        Icons.Filled.Email,
                        contentDescription = null,
                        tint = PfColors.royal
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Email support",
                            style = PfTextStyles.body,
                            fontWeight = FontWeight.SemiBold,
                            color = PfColors.navy
                        )
                        Text(
                            SUPPORT_EMAIL,
                            style = PfTextStyles.callout,
                            color = PfColors.secondaryText
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            copySupportEmail(context)
                            emailCopied = true
                        }
                    ) {
                        Text(if (emailCopied) "Copied" else "Copy")
                    }
                }
            }
            Text(
                "Diagnostics",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDiagnostics),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Diagnostics",
                            style = PfTextStyles.body,
                            fontWeight = FontWeight.SemiBold,
                            color = PfColors.navy
                        )
                        Text(
                            "Crash reports and device info to share with support.",
                            style = PfTextStyles.callout,
                            color = PfColors.secondaryText
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = PfColors.secondaryText
                    )
                }
            }
            Text(
                "Frequently asked questions",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            if (faqs.isEmpty()) {
                Text(
                    "FAQs are loading. Check your connection and try again.",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
            } else {
                PfCard {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        displayedFaqs.forEachIndexed { _, faq ->
                            val isExpanded = expandedFaqId == faq.id
                            // The index is the FAQ's position in
                            // the full FAQ list in catalogue
                            // order, not its position in the
                            // displayed page: the first 5 are the
                            // free samples.
                            val indexInType = faqs.indexOfFirst { it.id == faq.id }.takeIf { it >= 0 }
                            val isLocked = EntryAccessRules.isLocked(
                                faq,
                                indexInType = indexInType,
                                isPracticeUnlocked = false,
                                isInterviewUnlocked = interviewUnlocked,
                                isChapterUnlocked = true
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedFaqId = if (isExpanded) null else faq.id
                                    }
                                    .semantics {
                                        contentDescription = if (isLocked) {
                                            "${faq.title}. Answer locked. Tap to see how to unlock this answer."
                                        } else if (isExpanded) {
                                            "${faq.title}. ${faq.text}. Tap to collapse this answer."
                                        } else {
                                            "${faq.title}. ${faq.preview}. Tap to read the full answer."
                                        }
                                    }
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        faq.title,
                                        style = PfTextStyles.cardTitle,
                                        color = PfColors.navy,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isLocked) {
                                        Icon(
                                            Icons.Filled.Lock,
                                            contentDescription = "Answer locked",
                                            tint = PfColors.secondaryText
                                        )
                                    }
                                    Icon(
                                        if (isExpanded) Icons.Filled.KeyboardArrowUp
                                        else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = PfColors.secondaryText
                                    )
                                }
                                Spacer(modifier = Modifier.height(PfSpacing.small))
                                if (isExpanded) {
                                    if (isLocked) {
                                        Text(
                                            "This FAQ answer is part of Interview Ready: " +
                                                "${ContentManifest.faqCount} FAQ answers and " +
                                                "${ContentManifest.interviewQuestionCount} interview questions, " +
                                                "with 1 year of access. The Integrated Course includes " +
                                                "Interview Ready too.",
                                            style = PfTextStyles.callout,
                                            color = PfColors.secondaryText
                                        )
                                        PfPrimaryButton(
                                            title = "View Interview Ready",
                                            onClick = {
                                                onNavigate(Routes.checkout(ProductCatalogue.interviewProductId))
                                            }
                                        )
                                    } else {
                                        PfBodyText(
                                            text = faq.text,
                                            style = PfTextStyles.callout,
                                            color = PfColors.secondaryText
                                        )
                                    }
                                } else if (!isLocked) {
                                    Text(
                                        faq.preview,
                                        style = PfTextStyles.callout,
                                        color = PfColors.secondaryText,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                        if (faqs.size > 20) {
                            TextButton(
                                onClick = { showAllFaqs = !showAllFaqs },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (showAllFaqs) "Show fewer questions"
                                    else "Show all ${faqs.size} questions",
                                    style = PfTextStyles.body,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PfColors.royal
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "For more answers, search the Search tab. It searches the full FAQ list.",
                style = PfTextStyles.caption,
                color = PfColors.secondaryText
            )
        }
    }
}

private fun openMailApp(context: Context) {
    try {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:$SUPPORT_EMAIL")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No mail app available. The address stays visible on screen.
    }
}

private fun copySupportEmail(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as? ClipboardManager ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("Support email", SUPPORT_EMAIL)
    )
}
