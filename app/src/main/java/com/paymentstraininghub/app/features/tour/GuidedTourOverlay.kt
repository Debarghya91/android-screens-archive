// GuidedTourOverlay.kt
// The guided tour: a start card, 6 steps (Home, the Search,
// Payment Cases and My Progress tabs, then the Profile drawer
// row, then the Keep Learning card), and a completion card.
// There is no Learn tab: the course lives on Home, so the Home
// step describes the three Home tabs and the learning journey
// in one step. The copy is plain-language Android wording: it
// explains the main features in very simple terms (learning
// journeys and your journey, where to start a lesson, how to buy
// a course, audio lessons). Video lessons are not mentioned:
// the app has no video lessons. The Chat Assistant step was
// removed 15 Nov 2026 with the chat feature. The app stays fully
// usable while the tour runs: the dim layer never consumes
// touches, only the tour card itself does.
//
// Spotlight mechanics: every step draws a spotlight on a real,
// on-screen element: a double gold ring around the measured
// target plus a gold arrow pointing at it from the card's side.
// Tab steps ring the measured tab item (the tab bar reports its
// bounds, and Material3 tabs are equally spaced, so the ring
// sits on the real item); drawer steps ring the measured window
// bounds of the target row or card, reported by the drawer after
// it auto-scrolls the target into view. Measured bounds arrive
// in window coordinates, so they are translated into the
// overlay's local coordinates before drawing: drawing them raw
// shifted the ring down by the status bar height and pushed it
// off the tab bar entirely, which is why owners saw no highlight
// at all. When the tab bar has not reported bounds yet, a
// fallback rect covers the tab item's usual place at the bottom
// of the screen, so a tab step never points at nothing. Dialog
// cards use smaller fonts in a translucent navy box, per the
// owner's order: the app behind stays visible through the card.

package com.paymentstraininghub.app.features.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.ui.navigation.DrawerDestination
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography

private enum class TourPhase { START, STEP, COMPLETE }

internal data class TourStep(
    val title: String,
    val body: String,
    val tabIndex: Int?,
    val destination: DrawerDestination?,
    val highlightsCard: Boolean = false
)

internal val TOUR_STEPS = listOf(
    TourStep(
        "Home",
        "Home has three tabs at the top. Learning Journey shows your next lesson, your journey, your progress and the daily challenge. Courses holds the guided reading sessions, all 10 courses and the full catalogue. Practice holds guides, labs, tests and interview questions. A journey is a set order of lessons for one goal, such as becoming a payments BA. Choose one and Home always shows its next lesson.",
        tabIndex = 0,
        destination = null
    ),
    TourStep(
        "Search",
        "Search finds any lesson, guide, case or question in the course. Type a word, such as pacs.008. Use the chips to show only lessons, or only cases.",
        tabIndex = 1,
        destination = null
    ),
    TourStep(
        "Payment Cases",
        "Payment Cases are real payment problems, worked step by step the way a senior BA handles them. Start with the free samples. Each case shows what went wrong and what to do next.",
        tabIndex = 2,
        destination = null
    ),
    TourStep(
        "My Progress",
        "My Progress shows your streak, your weekly goal and your certificates. Your progress appears here after you finish your first chapter.",
        tabIndex = 3,
        destination = null
    ),
    TourStep(
        "Profile and Purchases",
        "Profile holds your account and settings. My Purchases sits just below Profile in the menu. To buy a course, open My Purchases and pick a course, or open any locked lesson. Buying unlocks that course for one year. The Integrated Course unlocks everything.",
        tabIndex = null,
        destination = DrawerDestination.PROFILE
    ),
    TourStep(
        "Start Learning",
        "This card jumps to your next lesson. Every lesson has audio: tap Play to listen and read along. You can change the speed, set a sleep timer, and download a lesson to listen offline.",
        tabIndex = null,
        destination = null,
        highlightsCard = true
    )
)

/** The tab item's rect inside the tab bar's window bounds. Tabs are equally spaced. */
internal fun tabItemRect(tabBarBounds: Rect, tabIndex: Int, tabCount: Int = 4): Rect {
    val itemWidth = tabBarBounds.width / tabCount
    return Rect(
        left = tabBarBounds.left + itemWidth * tabIndex,
        top = tabBarBounds.top,
        right = tabBarBounds.left + itemWidth * (tabIndex + 1),
        bottom = tabBarBounds.bottom
    )
}

/**
 * The fallback tab rect in overlay-local pixels, used when the
 * tab bar has not reported bounds yet: the bottom strip of the
 * overlay, split into equal tab quarters. A tab step never
 * points at nothing.
 */
internal fun fallbackTabRect(
    overlaySize: IntSize,
    tabIndex: Int,
    tabBarHeightPx: Float,
    tabCount: Int = 4
): Rect {
    val itemWidth = overlaySize.width / tabCount.toFloat()
    return Rect(
        left = itemWidth * tabIndex,
        top = overlaySize.height - tabBarHeightPx,
        right = itemWidth * (tabIndex + 1),
        bottom = overlaySize.height.toFloat()
    )
}

/**
 * The guided tour overlay, rendered above the drawer and the tabs.
 * [tabBarBounds] is the tab bar's bounds in window pixels, or null
 * when the tab bar is not on screen. [drawerTargetBounds] is the
 * measured window bounds of the drawer row or card the tour is
 * spotlighting on steps 5 to 6, or null before the drawer reports
 * it (the row's own gold ring still shows inside the drawer).
 * [onStepChanged] reports the current step index, or null for the
 * start and completion cards, so the host can open or close the
 * drawer and highlight the target row. [onFinish] fires when the
 * tour ends (Skip Tour or Explore the Platform); the host marks
 * the tour done.
 */
@Composable
fun GuidedTourOverlay(
    tabBarBounds: Rect?,
    tabBarVisible: Boolean,
    drawerTargetBounds: Rect? = null,
    onStepChanged: (Int?) -> Unit,
    onFinish: () -> Unit
) {
    var phase by remember { mutableStateOf(TourPhase.START) }
    var stepIndex by remember { mutableIntStateOf(0) }
    // The overlay's own origin in window coordinates, and its
    // size: measured bounds arrive in window coordinates and
    // must be translated into overlay-local coordinates before
    // the spotlight is drawn, or the ring lands a status bar
    // height too low.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    fun goToStep(index: Int) {
        stepIndex = index
        phase = TourPhase.STEP
        onStepChanged(index)
    }

    fun finish() {
        onStepChanged(null)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayOrigin = coordinates.boundsInWindow().topLeft
            }
            .onSizeChanged { overlaySize = it }
    ) {
        // Dim layer: visible, but never consumes a touch, so the
        // app stays fully usable while the tour runs.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // Gold spotlight: a double ring plus an arrow on the real
        // target. Tab steps use the measured tab item, or the
        // fallback bottom strip when the tab bar has not
        // reported bounds yet. Drawer steps use the measured row
        // or card bounds; while those are missing, the row's own
        // gold ring inside the drawer is the highlight.
        if (phase == TourPhase.STEP) {
            val step = TOUR_STEPS[stepIndex]
            val tabIndex = step.tabIndex
            val density = LocalDensity.current
            val spotlightBounds: Rect? = when {
                tabIndex != null && tabBarVisible && tabBarBounds != null -> {
                    tabItemRect(tabBarBounds, tabIndex).translate(-overlayOrigin)
                }
                tabIndex != null && tabBarVisible && overlaySize != IntSize.Zero -> {
                    val tabBarHeightPx = with(density) { 84.dp.toPx() }
                    fallbackTabRect(overlaySize, tabIndex, tabBarHeightPx)
                }
                tabIndex == null && drawerTargetBounds != null -> {
                    drawerTargetBounds.translate(-overlayOrigin)
                }
                else -> null
            }
            if (spotlightBounds != null) {
                // The card sits above the tab bar and above the
                // Keep Learning card, so the arrow points down at
                // those targets. The Profile row is scrolled to
                // the top of the drawer, above the bottom card, so
                // its arrow points up.
                val arrowPointsDown = tabIndex != null || step.highlightsCard
                TourSpotlight(
                    bounds = spotlightBounds,
                    arrowPointsDown = arrowPointsDown,
                    contentDescription = "Highlighting ${step.title}"
                )
            }
        }

        when (phase) {
            TourPhase.START -> {
                TourCard(
                    modifier = Modifier.align(Alignment.Center),
                    content = {
                        TourStartCard(
                            onStart = { goToStep(0) },
                            onSkip = { finish() }
                        )
                    }
                )
            }
            TourPhase.STEP -> {
                val step = TOUR_STEPS[stepIndex]
                // Drawer steps anchor at the bottom, except the
                // card step, whose target sits at the bottom of
                // the drawer: its tooltip anchors at the top so it
                // never covers its own target.
                val cardModifier = if (step.tabIndex != null) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = with(LocalDensity.current) {
                            if (tabBarBounds != null && tabBarVisible) {
                                tabBarBounds.height.toDp() + 16.dp
                            } else {
                                16.dp
                            }
                        })
                } else if (step.highlightsCard) {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                }
                TourCard(
                    modifier = cardModifier,
                    content = {
                        TourStepCard(
                            stepIndex = stepIndex,
                            step = step,
                            onBack = {
                                if (stepIndex == 0) {
                                    phase = TourPhase.START
                                    onStepChanged(null)
                                } else {
                                    goToStep(stepIndex - 1)
                                }
                            },
                            onNext = {
                                if (stepIndex >= TOUR_STEPS.lastIndex) {
                                    phase = TourPhase.COMPLETE
                                    onStepChanged(null)
                                } else {
                                    goToStep(stepIndex + 1)
                                }
                            },
                            onSkip = { finish() }
                        )
                    }
                )
            }
            TourPhase.COMPLETE -> {
                TourCard(
                    modifier = Modifier.align(Alignment.Center),
                    content = {
                        TourCompletionCard(
                            onExplore = { finish() },
                            onReplay = { goToStep(0) }
                        )
                    }
                )
            }
        }
    }
}

/** Which way the spotlight arrow points, from the card toward the ring. */
private enum class ArrowDirection { DOWN, UP }

/**
 * The tour spotlight: a thick double ring on the target's
 * overlay-local bounds, plus a gold arrow pointing at the ring
 * from the card's side. The outer ring is 4dp gold, the inner
 * ring 2dp white, so the highlight reads on light and dark
 * targets alike under the dim layer. The test tag lets tests
 * prove every step renders its highlight.
 */
@Composable
private fun TourSpotlight(
    bounds: Rect,
    arrowPointsDown: Boolean,
    contentDescription: String
) {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tour-spotlight")
            .semantics { this.contentDescription = contentDescription }
    ) {
        with(density) {
            val outerInset = 2.dp.toPx()
            val innerInset = 8.dp.toPx()
            val corner = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            drawRoundRect(
                color = PfColors.gold,
                topLeft = Offset(bounds.left + outerInset, bounds.top + outerInset),
                size = Size(
                    (bounds.width - outerInset * 2).coerceAtLeast(0f),
                    (bounds.height - outerInset * 2).coerceAtLeast(0f)
                ),
                cornerRadius = corner,
                style = Stroke(width = 4.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bounds.left + innerInset, bounds.top + innerInset),
                size = Size(
                    (bounds.width - innerInset * 2).coerceAtLeast(0f),
                    (bounds.height - innerInset * 2).coerceAtLeast(0f)
                ),
                cornerRadius = corner,
                style = Stroke(width = 2.dp.toPx())
            )
            // Arrow: a stem plus a filled triangle head, just
            // outside the ring on the card's side.
            val direction = if (arrowPointsDown) ArrowDirection.DOWN else ArrowDirection.UP
            val centerX = bounds.center.x
            val stemLength = 28.dp.toPx()
            val headHalfWidth = 10.dp.toPx()
            val headLength = 14.dp.toPx()
            val gap = 4.dp.toPx()
            when (direction) {
                ArrowDirection.DOWN -> {
                    val headTipY = bounds.top - gap
                    val headBaseY = headTipY - headLength
                    drawLine(
                        color = PfColors.gold,
                        start = Offset(centerX, headBaseY - stemLength),
                        end = Offset(centerX, headBaseY),
                        strokeWidth = 4.dp.toPx()
                    )
                    drawPath(
                        path = Path().apply {
                            moveTo(centerX, headTipY)
                            lineTo(centerX - headHalfWidth, headBaseY)
                            lineTo(centerX + headHalfWidth, headBaseY)
                            close()
                        },
                        color = PfColors.gold
                    )
                }
                ArrowDirection.UP -> {
                    val headTipY = bounds.bottom + gap
                    val headBaseY = headTipY + headLength
                    drawLine(
                        color = PfColors.gold,
                        start = Offset(centerX, headBaseY + stemLength),
                        end = Offset(centerX, headBaseY),
                        strokeWidth = 4.dp.toPx()
                    )
                    drawPath(
                        path = Path().apply {
                            moveTo(centerX, headTipY)
                            lineTo(centerX - headHalfWidth, headBaseY)
                            lineTo(centerX + headHalfWidth, headBaseY)
                            close()
                        },
                        color = PfColors.gold
                    )
                }
            }
        }
    }
}

@Composable
private fun TourCard(
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // Translucent navy box with smaller fonts inside, per the
    // owner's order: the app behind stays faintly visible, and the
    // card never reads as a solid sheet over the tour target.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PfSpacing.lg)
            .clip(RoundedCornerShape(PfSpacing.cardRadius))
            .background(PfColors.navy.copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(PfSpacing.cardRadius)
            )
            // The card consumes its own taps so they never fall
            // through to the usable app underneath.
            .clickable(enabled = true, onClick = {})
            .padding(PfSpacing.lg),
        content = { content() }
    )
}

@Composable
private fun TourStartCard(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PfSpacing.md)
    ) {
        Text(
            "Welcome to Payments Training Hub!",
            style = PfTypography.cardTitle,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            "Take a quick tour of the key areas of the app. It only takes a minute. The app stays fully usable while the tour runs.",
            style = PfTypography.callout,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
        TourGoldButton(title = "Start Tour", onClick = onStart)
        TourTextButton(title = "Skip Tour", onClick = onSkip)
    }
}

@Composable
private fun TourStepCard(
    stepIndex: Int,
    step: TourStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                step.title,
                style = PfTypography.body,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                "${stepIndex + 1} of ${TOUR_STEPS.size}",
                style = PfTypography.captionBold,
                color = PfColors.gold
            )
        }
        Text(
            step.body,
            style = PfTypography.callout,
            color = Color.White.copy(alpha = 0.85f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back is always shown: on the first step it returns
            // to the start card, matching iOS goBack.
            TourOutlineButton(title = "Back", onClick = onBack)
            TourGoldButton(title = "Next", onClick = onNext)
        }
        TourTextButton(title = "Skip Tour", onClick = onSkip)
    }
}

@Composable
private fun TourCompletionCard(onExplore: () -> Unit, onReplay: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PfSpacing.md)
    ) {
        Text(
            "You're all set!",
            style = PfTypography.cardTitle,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            "You've explored the key areas of Payments Training Hub. You can revisit this tour anytime from Help & Support.",
            style = PfTypography.callout,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
        TourGoldButton(title = "Explore the Platform", onClick = onExplore)
        TourOutlineButton(title = "Replay Tour", onClick = onReplay)
    }
}

@Composable
private fun TourTextButton(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = PfTypography.callout,
        fontWeight = FontWeight.SemiBold,
        color = PfColors.gold,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/**
 * Tour-local buttons, one size smaller than the shared buttons
 * and readable on the translucent navy card: gold fill for the
 * primary action, white outline for the secondary one.
 */
@Composable
private fun TourGoldButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = PfColors.gold,
            contentColor = PfColors.navy
        ),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Text(title, style = PfTypography.callout, fontWeight = FontWeight.SemiBold, color = PfColors.navy)
    }
}

@Composable
private fun TourOutlineButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(PfSpacing.cornerRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = PfSpacing.medium)
    ) {
        Text(title, style = PfTypography.callout, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
