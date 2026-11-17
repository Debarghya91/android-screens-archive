// WeeklyFreeCaseScreen.kt
// This week's free payment case: one situation every week, free to
// open, picked deterministically by ISO week. Ported from iOS
// WeeklyFreeCaseView (v67) and WeeklyFreeCaseSelector. The dedicated
// 52-case weekly list wins when it loads; otherwise the pick falls
// back to the same selector over the catalogue, in catalogue order.
// The pick changes every Monday, runs continuously across year
// boundaries, and wraps after 52 weeks. The detail host passes the
// weekly-path exemption, so this week's case opens fully here even
// when Payment Situations is not unlocked; the same situation from
// the list or search stays gated. The opened marker is one per ISO
// week, per account, device only. No em dashes.
package com.paymentstraininghub.app.features.growth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.ui.cases.SituationDetailScreen
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorState
import com.paymentstraininghub.app.ui.theme.PfLoading
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.pfHeroBrush
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.time.LocalDate

@Composable
fun WeeklyFreeCaseScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val weekKey = remember(today) { WeeklyFreeCaseSelector.weekKey(today) }
    val weekLabel = remember(today) { WeeklyFreeCaseSelector.weekLabel(today) }

    var weeklyCases by remember { mutableStateOf<List<Situation>?>(null) }
    var catalogue by remember { mutableStateOf<List<Situation>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var showDetail by remember { mutableStateOf(false) }

    // System Back from the in-case view returns to the weekly
    // case summary, mirroring its on-screen Back, instead of
    // popping the route.
    BackHandler(enabled = showDetail) { showDetail = false }

    val opened by WeeklyFreeCaseStore.opened.collectAsState()

    LaunchedEffect(retryTick) {
        loading = true
        loadError = false
        val weekly = ContentRepository.weeklyCasesOrNull()
        weeklyCases = weekly
        val loadedCatalogue = try {
            ContentRepository.situations()
        } catch (e: Exception) {
            null
        }
        catalogue = loadedCatalogue
        loadError = weekly.isNullOrEmpty() && loadedCatalogue.isNullOrEmpty()
        loading = false
    }

    // Weekly list wins; catalogue fallback uses the same selector.
    val weeklyPickIndex = remember(weeklyCases, today) {
        val list = weeklyCases
        if (list.isNullOrEmpty()) null
        else WeeklyFreeCaseSelector.index(today, list.size)
    }
    val weeklyPick = remember(weeklyCases, weeklyPickIndex) {
        val list = weeklyCases
        val index = weeklyPickIndex
        if (list.isNullOrEmpty() || index == null) null else list[index]
    }
    val fallbackPick = remember(catalogue, today, weeklyPick) {
        if (weeklyPick != null) return@remember null
        val list = catalogue ?: return@remember null
        val pickedId = WeeklyFreeCaseSelector.situationId(list.map { it.id }, today) ?: return@remember null
        list.firstOrNull { it.id == pickedId }
    }
    val picked = weeklyPick ?: fallbackPick
    val fromWeeklyList = weeklyPick != null
    val positionLabel = if (fromWeeklyList && weeklyPickIndex != null && weeklyCases != null) {
        "Case ${weeklyPickIndex + 1} of ${weeklyCases!!.size}"
    } else {
        null
    }
    val hasOpened = opened.containsKey(weekKey)
    val pickedInCatalogue = remember(picked, catalogue) {
        picked != null && catalogue?.any { it.id == picked.id } == true
    }

    if (showDetail && picked != null) {
        // The weekly detail host: marks the week opened on appear
        // and passes the weekly-path exemption. When the picked
        // case is not in the catalogue list, the fallback detail
        // renders the loaded case object directly, so a weekly-only
        // case still opens.
        WeeklyCaseDetailHost(
            situation = picked,
            weekKey = weekKey,
            inCatalogue = pickedInCatalogue,
            onBack = { showDetail = false },
            onNavigate = onNavigate
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
            .verticalScroll(rememberTopScrollState())
            .padding(PfSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
    ) {
        // Hero card: navy gradient, eyebrow, week label.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PfSpacing.cardRadius))
                .background(pfHeroBrush())
                .padding(PfSpacing.medium)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(
                    "Free every week",
                    style = PfTypography.captionBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text(
                    "One real payment case, free this week",
                    style = PfTypography.heroTitle,
                    color = Color.White
                )
                Text(
                    weekLabel,
                    style = PfTypography.body,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    "A new case appears every Monday. Free to open, no purchase and no sign-in needed.",
                    style = PfTypography.callout,
                    color = Color.White.copy(alpha = 0.9f)
                )
                if (hasOpened) {
                    Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                    PfPill(
                        text = "Opened",
                        background = Color.White.copy(alpha = 0.2f),
                        foreground = Color.White
                    )
                }
            }
        }

        when {
            loading -> {
                PfLoading(message = "Loading this week's case...")
            }
            loadError || picked == null -> {
                PfErrorState(
                    title = "Could not load this week's case",
                    message = "Check your connection and try again.",
                    retryTitle = "Retry",
                    onRetry = { retryTick++ }
                )
            }
            else -> {
                val item = picked!!
                PfCard {
                    if (positionLabel != null) {
                        Text(
                            positionLabel,
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText
                        )
                    }
                    Text(
                        item.title,
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "Stage ${item.stage}: ${item.stageName}. Level: ${item.levelDisplayName}.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    PfPrimaryButton(
                        title = "Open this week's case",
                        onClick = { showDetail = true }
                    )
                    PfSecondaryButton(
                        title = "Investigate this week's case",
                        onClick = { onNavigate(Routes.investigationSituationWeekly(item.id)) }
                    )
                }

                PfCard {
                    Text(
                        "Invite a colleague",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "Share this week's free case. The invite is only text and a link. There is no referral reward.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    PfSecondaryButton(
                        title = "Share this week's case",
                        onClick = {
                            val invite = ReferralInvite.weeklyFreeCase()
                            shareTextViaSheet(context, invite.shareText, invite.subject)
                        }
                    )
                    PfSecondaryButton(
                        title = "Share on LinkedIn",
                        onClick = {
                            val invite = ReferralInvite.weeklyFreeCase()
                            copyToClipboard(context, invite.linkedInText)
                            openUrlInBrowser(context, LinkedInShare.shareOffsiteURL(invite.webLink))
                        }
                    )
                    Text(
                        "The post text is copied for you to paste. Nothing is posted automatically.",
                        style = PfTypography.caption,
                        color = PfColors.secondaryText
                    )
                }

                PfCard {
                    Text(
                        "How the weekly case works",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "Every Monday a different real payment case becomes free for the week. It is the same case for everyone that week. The six free samples in Payment Cases stay free all the time, and the first lesson of each chapter is free to open with the full content.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(PfSpacing.small))
    }
}

@Composable
private fun WeeklyCaseDetailHost(
    situation: Situation,
    weekKey: String,
    inCatalogue: Boolean,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    LaunchedEffect(weekKey, situation.id) {
        WeeklyFreeCaseStore.markOpened(weekKey, situation.id)
    }
    if (inCatalogue) {
        SituationDetailScreen(
            situationId = situation.id,
            onBack = onBack,
            onNavigate = onNavigate,
            isWeeklyFreeCase = true
        )
    } else {
        // Weekly-only case: render the loaded object directly.
        // The weekly path is free by definition, so no gate renders.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PfColors.background)
                .verticalScroll(rememberTopScrollState(weekKey, situation.id))
                .padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            PfSecondaryButton(title = "Back", onClick = onBack)
            Text(
                "Case ${situation.id}",
                style = PfTypography.captionBold,
                color = PfColors.secondaryText
            )
            Text(
                situation.title,
                style = PfTypography.heroTitle,
                color = PfColors.navy
            )
            Text(
                "Stage ${situation.stage}: ${situation.stageName}. Level: ${situation.levelDisplayName}.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            Text(
                situation.explain,
                style = PfTypography.body,
                color = PfColors.navy
            )
            if (situation.takeaway.isNotBlank()) {
                PfCard {
                    Text(
                        "Takeaway",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        situation.takeaway,
                        style = PfTypography.body,
                        color = PfColors.navy
                    )
                }
            }
            PfCard {
                Text(
                    "Unlock all ${ContentManifest.situationCount} situations",
                    style = PfTypography.cardTitle,
                    color = PfColors.navy
                )
                Text(
                    "This case is free this week. Practice Experiences unlocks the full set of ${ContentManifest.situationCount} situations, plus Practice Mode, the Daily Challenge, labs, ISO exercises, and weekly cases. The Integrated Course includes everything too.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                PfPrimaryButton(
                    title = "See Practice Experiences",
                    onClick = { onNavigate(Routes.checkout(ProductCatalogue.practiceProductId)) }
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("LinkedIn post", text))
    } catch (e: Exception) {
        // Clipboard unavailable; the browser link still opens.
    }
}

private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No browser available.
    }
}
