// AboutScreen.kt
// About Payments Training Hub, matching the iOS About
// view: the hero, the curriculum counts, the
// operator, how it works, and the app version with the support
// contact. Counts come from the loaded search index (which
// includes the bundled supplemental lessons), with
// ContentManifest fallbacks while it loads. One platform
// adaptation: where iOS says Payment Situations is "paid by
// In-App Purchase", Android says "paid through Google Play",
// the same precedent as the restore copy.

package com.paymentstraininghub.app.features.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.onboarding.ONBOARDING_PAGES
import com.paymentstraininghub.app.features.onboarding.OnboardingPage
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var chapterCount by remember { mutableStateOf(ContentManifest.chapterCount) }
    var lessonCount by remember { mutableStateOf(ContentManifest.lessonCount) }
    var guideCount by remember { mutableStateOf(ContentManifest.guideCount) }
    var situationCount by remember { mutableStateOf(ContentManifest.situationCount) }

    LaunchedEffect(Unit) {
        val index = runCatching { ContentRepository.loadSearchIndex() }.getOrNull()
        if (index != null) {
            chapterCount = runCatching { ContentRepository.chapters().size }
                .getOrElse { ContentManifest.chapterCount }
            lessonCount = index.counts.lesson
            guideCount = index.counts.guide
            situationCount = index.counts.situation
        }
    }

    val versionLabel = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: "Unknown"
            "Version $name (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (e: Exception) {
            "Version Unknown"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("About", color = PfColors.navy) },
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
            ) {
                Text(
                    "Payments Training Hub",
                    style = PfTextStyles.sectionTitle,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.navy,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Payments, taught the way banks actually process them.",
                    style = PfTextStyles.callout,
                    color = PfColors.secondaryText,
                    textAlign = TextAlign.Center
                )
            }
            // Why this app, who it is for, and what it can do: the
            // same copy as the first three introduction pages,
            // reused verbatim so the two places can never drift.
            ONBOARDING_PAGES.take(3).forEach { page ->
                AboutOnboardingBlock(page = page)
            }
            Text(
                "The curriculum",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfBodyText(
                text = "$chapterCount chapters and $lessonCount narrated lessons " +
                    "across ${ContentManifest.domainCount} domains, with " +
                    "$guideCount long-form guides, " +
                    "${ContentManifest.interviewQuestionCount} interview questions " +
                    "and $situationCount Payment Situations. Every lesson is " +
                    "built for working payments professionals, with real " +
                    "examples and field-level detail."
            )
            Text(
                "The operator",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfBodyText(
                text = "Payments Training Hub is built and run by a Senior " +
                    "Business Analyst who has run payments transformation " +
                    "at NatWest in the UK since 2021, deployed through " +
                    "Capco in Bangalore."
            )
            Text(
                "How it works",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfBodyText(
                text = "Sign in to start learning. The " +
                    "first lesson of every chapter is free. Courses unlock " +
                    "when you buy them, or buy the Integrated Course to " +
                    "unlock everything. Each purchase gives 1 year of " +
                    "access, paid through Google Play. Your progress " +
                    "syncs to your account across devices."
            )
            Text(
                "About this app",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    Text(
                        versionLabel,
                        style = PfTextStyles.body,
                        color = PfColors.navy
                    )
                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:$SUPPORT_EMAIL")
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // No mail app available.
                            }
                        }
                    ) {
                        Text(
                            "Contact support: $SUPPORT_EMAIL",
                            style = PfTextStyles.body,
                            color = PfColors.royal
                        )
                    }
                }
            }
        }
    }
}

/** One introduction block on the About screen: the page heading, its intro, and its headed sections. */
@Composable
private fun AboutOnboardingBlock(page: OnboardingPage) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
        Text(
            page.heading,
            style = PfTextStyles.captionBold,
            color = PfColors.secondaryText
        )
        PfBodyText(text = page.intro)
        page.sections.forEach { (title, body) ->
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    Text(
                        title,
                        style = PfTextStyles.sectionTitle,
                        color = PfColors.navy
                    )
                    Text(
                        body,
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
    }
}
