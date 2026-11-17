// HelpSupportScreen.kt
// Help and support, matching the iOS HelpSupportView: a Contact
// section with the support email (opens the mail app, plus a
// Copy button), and the Frequently asked questions from the live
// search index. Tapping
// a question expands it to the full answer, rendered with
// PfBodyText; tapping again collapses it to the 3-line preview.

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
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
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    var faqs by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var expandedFaqId by remember { mutableStateOf<String?>(null) }
    var showAllFaqs by remember { mutableStateOf(false) }
    var emailCopied by remember { mutableStateOf(false) }

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
                        displayedFaqs.forEach { faq ->
                            val isExpanded = expandedFaqId == faq.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedFaqId = if (isExpanded) null else faq.id
                                    }
                                    .semantics {
                                        contentDescription = if (isExpanded) {
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
                                    Icon(
                                        if (isExpanded) Icons.Filled.KeyboardArrowUp
                                        else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = PfColors.secondaryText
                                    )
                                }
                                Spacer(modifier = Modifier.height(PfSpacing.small))
                                if (isExpanded) {
                                    PfBodyText(
                                        text = faq.text,
                                        style = PfTextStyles.callout,
                                        color = PfColors.secondaryText
                                    )
                                } else {
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
