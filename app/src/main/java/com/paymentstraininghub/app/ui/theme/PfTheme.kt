package com.paymentstraininghub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paymentstraininghub.app.features.settings.AppearanceStore

// PfTheme.kt
// The one design language for the Android app, ported from the iOS
// DesignSystem.swift. Royal blue is the single primary, premium gold
// sparingly for the main call to action and highlights, deep navy for
// headings and authority. The pf surfaces stay light even when the
// system dark mode toggle is on, matching iOS.
//
// This package did not exist in v1: eleven v1 files already import
// PfColors and PfSpacing from here, so the scaffold could not
// compile without it.
//
// MERGED by the v2 coordinator from three workstream theme files
// (gating, investigation, auth) into this single copy. Token values
// are the iOS values. Spacing: the canonical iOS scale is xs (4),
// sm (8), md (12), lg (16), xl (24), xxl (32); the Android aliases
// map xSmall to xs, small to sm, medium to md (12, per the scaffold
// guide), large to lg (16), xLarge to xl (24), xxLarge to xxl (32).
// Two workstreams authored against medium = 16; the merged value is
// 12 per the guide, so their screens render slightly tighter until
// eyeballed at first compile. Both typography objects are kept:
// PfTypography (with iOS text colors) and PfTextStyles (with iOS
// line heights, no colors), because different ported screens use
// different ones. No em dashes in any user-facing text.

/** Colors, ported from the Color extension in DesignSystem.swift. */
object PfColors {
    /** Royal blue, the single primary color. iOS pfRoyal (0.09, 0.25, 0.55). */
    val royal: Color = Color(0xFF17408C)

    /** A lighter royal blue for secondary accents and selected states. iOS pfRoyalLight. */
    val royalLight: Color = Color(0xFF295CAE)

    /** Premium gold, used sparingly for the main call to action and highlights. iOS pfGold. */
    val gold: Color = Color(0xFFC9A326)

    /** A soft gold tint for backgrounds behind gold highlights. iOS pfGoldSoft (gold at 12%). */
    val goldSoft: Color = Color(0xFFC9A326).copy(alpha = 0.12f)

    /** A darker gold for small text on light backgrounds. iOS pfGoldText. Never use pfGold for small text. */
    val goldText: Color = Color(0xFF8C7014)

    /** Darker still, for small text on goldSoft: 5.39:1 on the tint (goldText is 4.30:1). */
    val goldTextDark: Color = Color(0xFF7A6110)

    /** Deep executive navy for headings and authority. iOS pfNavy (0.05, 0.11, 0.22). */
    val navy: Color = Color(0xFF0D1C38)

    /** Deepest navy, the top of the hero gradient. iOS pfNavyDeep. */
    val navyDeep: Color = Color(0xFF050F24)

    /** Soft navy for card backgrounds on light screens. iOS pfNavySoft (navy at 6%). */
    val navySoft: Color = Color(0xFF0D1C38).copy(alpha = 0.06f)

    /** Warm off-white page background. iOS pfBackground. */
    val background: Color = Color(0xFFF5F7FA)

    /** Card background. iOS pfCard. */
    val card: Color = Color.White

    /** Hairline border for cards and fields. iOS pfBorder. */
    val border: Color = Color(0xFFE8EDF5)

    /** Secondary text, darkened on iOS to read at roughly 7:1 on white. iOS pfSecondaryText. */
    val secondaryText: Color = Color(0xFF47576E)

    /** Success green for correct answers and completed states. iOS pfSuccess. */
    val success: Color = Color(0xFF218C52)

    /** Darker green for small text on the success tint: 5.60:1 (success is 3.66:1). */
    val successTextDark: Color = Color(0xFF1B6B41)

    /** Error red for incorrect answers. iOS pfError. */
    val error: Color = Color(0xFFC73338)

    /** Hero gradient: deepest navy at the top, through navy, to royal
     * blue at the bottom. White text sits on it. Gradients are used
     * ONLY on the hero and premium sections, never on every card. */
    val heroGradient: Brush = Brush.linearGradient(
        colors = listOf(navyDeep, navy, royal),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
}

/**
 * The one spacing scale, ported from PFSpacing in DesignSystem.swift.
 * Canonical names (xs, sm, md, lg, xl, xxl) plus the v1 aliases
 * (xSmall, small, medium, large, xLarge, xxLarge) and the three radii.
 * medium is the iOS md (12); large is the iOS lg (16), the standard
 * screen and card padding.
 */
object PfSpacing {
    /** iOS xs: 4. */
    val xs = 4.dp

    /** iOS sm: 8. */
    val sm = 8.dp

    /** iOS md: 12. */
    val md = 12.dp

    /** iOS lg: 16. The standard screen and card padding. */
    val lg = 16.dp

    /** iOS xl: 24. */
    val xl = 24.dp

    /** iOS xxl: 32. */
    val xxl = 32.dp

    /** Alias for xs: 4. */
    val xSmall = 4.dp

    /** Alias for sm: 8. */
    val small = 8.dp

    /** Alias for md: 12. */
    val mediumSmall = 12.dp

    /** Alias for md: 12, per the scaffold guide. */
    val medium = 12.dp

    /** Alias for lg: 16. */
    val large = 16.dp

    /** Alias for xl: 24. */
    val xLarge = 24.dp

    /** Alias for xxl: 32. */
    val xxLarge = 32.dp

    /** iOS cardRadius: 18, for cards. */
    val cardRadius = 18.dp

    /** iOS cornerRadius: 14, for buttons, pills and fields. */
    val cornerRadius = 14.dp

    /** iOS smallRadius: 12, for small tiles. */
    val smallRadius = 12.dp
}

/**
 * Semantic text styles, ported from the Font extension in
 * DesignSystem.swift, with the iOS text colors baked in. iOS styles
 * are Dynamic Type styles; the sizes below are their default point
 * sizes in sp, so the Android text hierarchy matches: hero 28 bold,
 * screen 22 bold, section 20 semibold, card title 17 semibold,
 * body 17, callout 16, caption 12.
 */
object PfTypography {
    val heroTitle: TextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PfColors.navy)
    val screenTitle: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PfColors.navy)
    val sectionTitle: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
    val cardTitle: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
    val body: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, color = PfColors.navy)
    val callout: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, color = PfColors.navy)
    val caption: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = PfColors.secondaryText)
    val captionBold: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
}

/**
 * The same eight semantic text styles with iOS line heights and no
 * baked-in color, for screens that set their own text color. Kept
 * alongside PfTypography because the investigation port uses it.
 */
object PfTextStyles {
    val heroTitle: TextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val screenTitle: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val sectionTitle: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
    val cardTitle: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val callout: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val caption: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    val captionBold: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
}

private val PfLightColorScheme = lightColorScheme(
    primary = PfColors.royal,
    onPrimary = Color.White,
    primaryContainer = PfColors.royal.copy(alpha = 0.10f),
    onPrimaryContainer = PfColors.royal,
    secondary = PfColors.gold,
    onSecondary = PfColors.navy,
    secondaryContainer = PfColors.goldSoft,
    onSecondaryContainer = PfColors.goldText,
    tertiary = PfColors.navy,
    onTertiary = Color.White,
    background = PfColors.background,
    onBackground = PfColors.navy,
    surface = PfColors.card,
    onSurface = PfColors.navy,
    surfaceVariant = PfColors.navySoft,
    onSurfaceVariant = PfColors.secondaryText,
    outline = PfColors.border,
    error = PfColors.error,
    onError = Color.White
)

// The dark scheme applies to Material surfaces only (dialogs,
// switches, ripples): the pf-painted cards and lesson pages stay
// light, matching the iOS dark mode behaviour described on the
// Appearance screen. It is selected only by the stored Dark mode
// preference, never by the system setting.
private val PfDarkColorScheme = darkColorScheme(
    primary = PfColors.royal,
    onPrimary = Color.White,
    primaryContainer = PfColors.royal.copy(alpha = 0.30f),
    onPrimaryContainer = Color.White,
    secondary = PfColors.gold,
    onSecondary = PfColors.navy,
    secondaryContainer = PfColors.goldSoft,
    onSecondaryContainer = PfColors.goldText,
    tertiary = Color(0xFF9DB2E0),
    onTertiary = PfColors.navyDeep,
    background = Color(0xFF121826),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF1C2438),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF2A3450),
    onSurfaceVariant = Color(0xFFB8C0D0),
    outline = Color(0xFF3A4560),
    error = PfColors.error,
    onError = Color.White
)

private val PfMaterialTypography = Typography(
    displayLarge = PfTypography.heroTitle,
    displayMedium = PfTypography.heroTitle,
    headlineLarge = PfTypography.screenTitle,
    headlineMedium = PfTypography.screenTitle,
    headlineSmall = PfTypography.sectionTitle,
    titleLarge = PfTypography.sectionTitle,
    titleMedium = PfTypography.cardTitle,
    titleSmall = PfTypography.captionBold,
    bodyLarge = PfTypography.body,
    bodyMedium = PfTypography.callout,
    bodySmall = PfTypography.caption,
    labelLarge = PfTypography.captionBold,
    labelMedium = PfTypography.captionBold,
    labelSmall = PfTypography.caption
)

/**
 * The app theme. Wraps MaterialTheme with the pf scheme and
 * typography. The colour scheme follows the stored Dark mode
 * preference from the Appearance screen (Material surfaces only;
 * pf-painted cards and lesson pages stay light, matching iOS),
 * never the system setting: the [darkTheme] parameter is accepted
 * so callers can pass the system value, and is ignored on
 * purpose. The Larger text preference scales the font size by
 * 1.15 across the app.
 */
@Composable
fun PfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    @Suppress("UNUSED_PARAMETER")
    val ignoredDarkTheme = darkTheme
    val storedDarkMode by AppearanceStore.darkMode.collectAsState()
    val largerText by AppearanceStore.largerText.collectAsState()
    val density = LocalDensity.current
    val scaledDensity = if (largerText) {
        Density(density.density, density.fontScale * 1.15f)
    } else {
        density
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = if (storedDarkMode) PfDarkColorScheme else PfLightColorScheme,
            typography = PfMaterialTypography,
            content = content
        )
    }
}

/**
 * Hero gradient: deepest navy at the top, through navy, to royal
 * blue at the bottom. White text sits on it. Gradients are used
 * ONLY on the hero and premium sections, never on every card.
 * Mirrors iOS Color.pfHeroGradient (topLeading to bottomTrailing).
 */
fun heroGradientBrush(): Brush {
    return Brush.linearGradient(
        colors = listOf(PfColors.navyDeep, PfColors.navy, PfColors.royal),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
}
