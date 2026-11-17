package com.paymentstraininghub.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// PfComponents.kt
// Reusable components ported from DesignSystem.swift: PFCard,
// PFPrimaryButton, PFGoldButton, PFSecondaryButton, PFPill, PFChip,
// PFLevelPill, PFSectionHeader, PFIconTile, PFProgressBar,
// PFProgressRing, PFStatCard, PFOfflineBanner, PFErrorStateView,
// PFEmptyStateView and PFLoadingView. Screens in this workstream
// use these instead of raw Material defaults so the Android look
// matches iOS: white cards with a hairline border, 18dp card radius,
// 14dp button radius, royal primary actions, gold only sparingly.
// No em dashes in any user-facing text.

/** Hero gradient: deepest navy, through navy, to royal blue. Used ONLY on hero and premium sections, never on every card. */
fun pfHeroBrush(): Brush = Brush.linearGradient(
    colors = listOf(PfColors.navyDeep, PfColors.navy, PfColors.royal),
    start = Offset.Zero,
    end = Offset.Infinite
)

/** A card container with the standard look: white, rounded, a hairline border and a soft shadow. Mirrors PFCard. */
@Composable
fun PfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }
    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(PfSpacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, PfColors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall),
            content = content
        )
    }
}

/** The primary royal-blue call-to-action button. Mirrors PFPrimaryButton. */
@Composable
fun PfPrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = PfColors.royal,
            contentColor = Color.White,
            disabledContainerColor = PfColors.royal.copy(alpha = 0.5f),
            disabledContentColor = Color.White
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
    ) {
        Text(title, style = PfTypography.cardTitle, color = Color.White)
    }
}

/** A gold accent button, used sparingly for the single main call to action. Mirrors PFGoldButton. */
@Composable
fun PfGoldButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = PfColors.gold,
            contentColor = PfColors.navy,
            disabledContainerColor = PfColors.gold.copy(alpha = 0.5f),
            disabledContentColor = PfColors.navy
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
    ) {
        Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
    }
}

/** A secondary button: royal text on a soft royal fill. Mirrors PFSecondaryButton. */
@Composable
fun PfSecondaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = PfColors.royal.copy(alpha = 0.08f),
            contentColor = PfColors.royal,
            disabledContainerColor = PfColors.royal.copy(alpha = 0.04f),
            disabledContentColor = PfColors.royal.copy(alpha = 0.5f)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
    ) {
        Text(title, style = PfTypography.body, fontWeight = FontWeight.SemiBold, color = PfColors.royal)
    }
}

/** A small pill badge. Mirrors PFPill. */
@Composable
fun PfPill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = PfColors.royal.copy(alpha = 0.10f),
    foreground: Color = PfColors.royal
) {
    Surface(
        modifier = modifier,
        color = background,
        shape = CircleShape
    ) {
        Text(
            text,
            style = PfTypography.captionBold,
            color = foreground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** A level pill: Foundation royal, Intermediate gold, Advanced navy. Mirrors PFLevelPill. */
@Composable
fun PfLevelPill(text: String, modifier: Modifier = Modifier) {
    val (background, foreground) = when (text.lowercase()) {
        "foundation" -> PfColors.royal.copy(alpha = 0.10f) to PfColors.royal
        "intermediate" -> PfColors.goldSoft to PfColors.navy
        "advanced" -> PfColors.navy.copy(alpha = 0.08f) to PfColors.navy
        else -> PfColors.royal.copy(alpha = 0.10f) to PfColors.royal
    }
    PfPill(text = text, modifier = modifier, background = background, foreground = foreground)
}

/** A selectable chip for filters and interests. Mirrors PFChip. */
@Composable
fun PfChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (isSelected) PfColors.royal else PfColors.card,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) PfColors.royal else PfColors.border
        ),
        onClick = onClick
    ) {
        Text(
            text,
            style = PfTypography.captionBold,
            color = if (isSelected) Color.White else PfColors.navy,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** A section header with a title and an optional trailing link. Mirrors PFSectionHeader. */
@Composable
fun PfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = PfTypography.sectionTitle, modifier = Modifier.weight(1f))
        if (actionTitle != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionTitle, style = PfTypography.callout, fontWeight = FontWeight.SemiBold, color = PfColors.royal)
            }
        }
    }
}

/** A coloured icon tile: a rounded square with a soft royal background and a royal icon. Mirrors PFIconTile. */
@Composable
fun PfIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(PfColors.royal.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PfColors.royal,
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

/** A thin progress bar. Mirrors PFProgressBar. */
@Composable
fun PfProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(PfColors.navy.copy(alpha = 0.10f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(PfColors.royal)
        )
    }
}

/** A circular progress ring with a % label in the centre. Mirrors PFProgressRing. */
@Composable
fun PfProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    strokeWidth: Dp = 10.dp,
    color: Color = PfColors.royal
) {
    val clamped = progress.coerceIn(0f, 1f)
    val trackColor = PfColors.navy.copy(alpha = 0.10f)
    val percent = (clamped * 100).toInt()
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            drawCircle(color = trackColor, style = stroke)
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                style = stroke
            )
        }
        Text(
            "$percent%",
            style = PfTypography.sectionTitle,
            fontWeight = FontWeight.Bold,
            color = PfColors.navy
        )
    }
}

/** A stat card: an icon tile, a big number and a label. Mirrors PFStatCard. */
@Composable
fun PfStatCard(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(PfSpacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, PfColors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PfSpacing.mediumSmall, horizontal = PfSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
        ) {
            PfIconTile(icon = icon, size = 40.dp)
            Text(value, style = PfTypography.sectionTitle, textAlign = TextAlign.Center)
            Text(label, style = PfTypography.caption, textAlign = TextAlign.Center)
            if (caption != null) {
                Text(caption, style = PfTypography.caption, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A small offline banner shown when a screen displays cached content. Mirrors PFOfflineBanner. */
@Composable
fun PfOfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(PfColors.goldSoft)
            .padding(horizontal = PfSpacing.mediumSmall, vertical = PfSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.WifiOff,
            contentDescription = null,
            tint = PfColors.navy,
            modifier = Modifier.size(18.dp)
        )
        Text(
            "You are offline. Showing saved content.",
            style = PfTypography.captionBold,
            color = PfColors.navy
        )
    }
}

/** A loading row: spinner plus a message. Mirrors PFLoadingView. */
@Composable
fun PfLoading(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        CircularProgressIndicator(color = PfColors.royal, modifier = Modifier.size(20.dp))
        Text(message, style = PfTypography.body, color = PfColors.secondaryText)
    }
}

/** An error state: title, the real error text and a retry button. Mirrors PFErrorStateView. */
@Composable
fun PfErrorState(
    title: String,
    message: String,
    retryTitle: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    PfCard(modifier = modifier) {
        Text(title, style = PfTypography.cardTitle)
        Text(message, style = PfTypography.body, color = PfColors.secondaryText)
        PfPrimaryButton(title = retryTitle, onClick = onRetry)
    }
}

/** An empty state: icon, title, honest message and one real action. Mirrors PFEmptyStateView. */
@Composable
fun PfEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null
) {
    PfCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = PfColors.royal, modifier = Modifier.size(32.dp))
            Text(title, style = PfTypography.cardTitle, textAlign = TextAlign.Center)
            Text(message, style = PfTypography.body, color = PfColors.secondaryText, textAlign = TextAlign.Center)
            if (actionTitle != null && onAction != null) {
                PfPrimaryButton(title = actionTitle, onClick = onAction)
            }
        }
    }
}

/**
 * The hard-gate card shared by the locked surfaces: a lock tile, a
 * title, generic copy and the checkout entry. It carries no case
 * text of its own; callers pass only generic copy. Mirrors the
 * lockedGateView pattern in SituationDetailView, PracticeModeView
 * and InvestigationExperienceView. [gateTestTag] mirrors the iOS
 * accessibility identifiers (for example "situationLockedGate").
 */
@Composable
fun PfLockedGateCard(
    title: String,
    bodyText: String,
    checkoutTitle: String,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier,
    gateTestTag: String? = null,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    val tagged = if (gateTestTag != null) modifier.testTag(gateTestTag) else modifier
    PfCard(modifier = tagged) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
        ) {
            PfIconTile(icon = androidx.compose.material.icons.Icons.Filled.Lock, size = 44.dp)
            Text(title, style = PfTypography.cardTitle, modifier = Modifier.weight(1f))
        }
        PfBodyText(text = bodyText, style = PfTypography.body, color = PfColors.secondaryText)
        PfPrimaryButton(title = checkoutTitle, onClick = onCheckout)
        extraContent()
    }
}

/** A bordered outline button matching the secondary treatment, for small inline actions. */
@Composable
fun PfOutlineButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, PfColors.royal),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PfColors.royal),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp, horizontal = PfSpacing.medium)
    ) {
        Text(title, style = PfTypography.body, fontWeight = FontWeight.SemiBold, color = PfColors.royal)
    }
}

// MARK: - Components merged in by the v2 coordinator
//
// The components below come from the auth port (PfField) and the
// investigation port (PfRowButton, PfHeroHeader, PfSkeletonView,
// PfLoadingView, PfEmptyStateView, PfErrorStateView, PfBottomInset).
// PfHeroHeader's content receiver was changed from the investigation
// file's Column receiver to ColumnScope so it typechecks against the
// Column composable. No em dashes in any user-facing text.

/**
 * A labelled text field matching the iOS auth screens: a semibold
 * caption label above a rounded-border field. The label is always
 * visible, so the field never relies on placeholder text alone.
 */
@Composable
fun PfField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = PfTypography.captionBold,
            color = PfColors.secondaryText
        )
        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            placeholder = {
                if (placeholder != null) {
                    Text(text = placeholder, style = PfTypography.body)
                }
            },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = PfTypography.body.copy(color = PfColors.navy),
            shape = RoundedCornerShape(PfSpacing.cornerRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PfColors.navy,
                unfocusedTextColor = PfColors.navy,
                disabledTextColor = PfColors.secondaryText,
                cursorColor = PfColors.royal,
                focusedBorderColor = PfColors.royal,
                unfocusedBorderColor = PfColors.border,
                disabledBorderColor = PfColors.border,
                focusedContainerColor = PfColors.card,
                unfocusedContainerColor = PfColors.card,
                disabledContainerColor = PfColors.card,
                focusedPlaceholderColor = PfColors.secondaryText,
                unfocusedPlaceholderColor = PfColors.secondaryText
            )
        )
    }
}

/** A full-width royal row button with a trailing chevron, for navigation rows. */
@Composable
fun PfRowButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfSpacing.cornerRadius))
            .background(PfColors.royal)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = PfSpacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = PfTextStyles.body,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Text(">", style = PfTextStyles.body, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** A hero header card: gradient background, rounded, lifted with a shadow. */
@Composable
fun PfHeroHeader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(PfSpacing.cardRadius))
            .clip(RoundedCornerShape(PfSpacing.cardRadius))
            .background(PfColors.heroGradient)
            .padding(PfSpacing.xl),
        content = content
    )
}

/** A shimmer-free skeleton row: a pulsing rounded bar, used while a
 * list is loading so the screen never shows a blank white page. */
@Composable
fun PfSkeletonView(height: Dp = 76.dp) {
    val transition = rememberInfiniteTransition(label = "pfSkeleton")
    val alphaValue by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pfSkeletonAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .alpha(alphaValue)
            .clip(RoundedCornerShape(PfSpacing.cardRadius))
            .background(PfColors.navy.copy(alpha = 0.08f))
            .semantics { contentDescription = "Loading" }
    )
}

/** A loading row: spinner plus a message, for screens whose content
 * is still arriving. */
@Composable
fun PfLoadingView(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(color = PfColors.royal, modifier = Modifier.size(20.dp))
        Text(message, style = PfTextStyles.body, color = PfColors.secondaryText)
    }
}

/** An empty state: icon, title, honest message and one real action.
 * Never used to hide a missing feature; the action always goes
 * somewhere that exists. */
@Composable
fun PfEmptyStateView(icon: ImageVector, title: String, message: String, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    PfCard {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PfSpacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PfColors.royal,
                modifier = Modifier.size(28.dp)
            )
            Text(title, style = PfTextStyles.cardTitle, color = PfColors.navy, textAlign = TextAlign.Center)
            Text(message, style = PfTextStyles.body, color = PfColors.secondaryText, textAlign = TextAlign.Center)
            if (actionTitle != null && onAction != null) {
                Spacer(modifier = Modifier.height(PfSpacing.sm))
                PfPrimaryButton(title = actionTitle, onClick = onAction)
            }
        }
    }
}

/** An error state: icon, title, the real error text and a retry
 * button that calls the supplied action. */
@Composable
fun PfErrorStateView(title: String, message: String, retryTitle: String, onRetry: () -> Unit) {
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = PfColors.navy,
                    modifier = Modifier.size(20.dp)
                )
                Text(title, style = PfTextStyles.cardTitle, color = PfColors.navy)
            }
            Text(message, style = PfTextStyles.body, color = PfColors.secondaryText)
            PfPrimaryButton(title = retryTitle, onClick = onRetry)
        }
    }
}

/** A 20dp spacer row helper, matching the iOS bottom safe-area inset
 * pattern used under scrolling journey content. */
@Composable
fun PfBottomInset() {
    Spacer(modifier = Modifier.height(PfSpacing.xl).width(1.dp))
}
