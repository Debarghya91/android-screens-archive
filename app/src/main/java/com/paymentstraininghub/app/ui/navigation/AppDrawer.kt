// AppDrawer.kt
// Payments Training Hub Android - the side drawer, matching the iOS
// SideDrawerView row for row: the same destinations in the same
// order with the same titles and subtitles, minus the removed Chat
// Assistant row, the navy gradient
// header with the gold PF mark, the "Account and settings" heading
// before Profile, the Keep Learning card, and the brand footer.
// The drawer slides in from the left over the main content with a
// dimmed background; the hamburger button opens it from the root
// screens. No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.features.habit.LearningHabitPlanner
import com.paymentstraininghub.app.ui.home.CapabilityItem
import com.paymentstraininghub.app.ui.home.drawerCapabilityItems
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.heroGradientBrush
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import kotlinx.coroutines.delay

/// One drawer destination: the iOS DrawerDestination, in display
/// order, with the same title, subtitle and icon meaning. The
/// [route] is the Android navigation route.
enum class DrawerDestination(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String?,
    /// The tab route this row is the active state for, or null
    /// when the row never shows the active state.
    val tabRoute: String? = null
) {
    HOME(
        "Home",
        "Your personalised home for continuing to learn",
        Icons.Filled.Home,
        Routes.HOME,
        Routes.HOME
    ),
    LEARN(
        "Learn",
        "Courses, journeys and all chapters",
        Icons.Filled.MenuBook,
        Routes.LEARN,
        Routes.LEARN
    ),
    SEARCH(
        "Search",
        "Search lessons, guides, cases and questions",
        Icons.Filled.Search,
        Routes.SEARCH,
        Routes.SEARCH
    ),
    PAYMENT_CASES(
        "Payment Cases",
        "Real-world payment situations to practise",
        Icons.Filled.Work,
        Routes.CASES,
        Routes.CASES
    ),
    PRACTICE(
        "Practice",
        "Test yourself on real payment scenarios",
        Icons.Filled.Science,
        Routes.PRACTICE
    ),
    WEEKLY_FREE_CASE(
        "Free Case of the Week",
        "One real payment case, free every week",
        Icons.Filled.CalendarMonth,
        Routes.WEEKLY_CASE
    ),
    WEEKLY_CHALLENGE(
        "Weekly Challenge",
        "One case a week, scored on investigation quality",
        Icons.Filled.EmojiEvents,
        Routes.CHALLENGE
    ),
    MY_PROGRESS(
        "My Progress",
        "Your learning analytics, streak and quiz scores",
        Icons.Filled.BarChart,
        Routes.PROGRESS,
        Routes.PROGRESS
    ),
    // The Chat Assistant row was removed 15 Nov 2026: the chat/helper
    // feature was removed from the app on the owner's order.
    SIMULATOR(
        "Payment Simulator",
        "Follow a payment through its lifecycle",
        Icons.Filled.Autorenew,
        Routes.SIMULATOR
    ),
    ISO_EXPLORER(
        "ISO 20022 Explorer",
        "Look up ISO 20022 messages and fields",
        Icons.Filled.FindInPage,
        Routes.ISO
    ),
    EXCEPTION_LAB(
        "Exception Lab",
        "Investigate payment exceptions",
        Icons.Filled.WarningAmber,
        Routes.LAB
    ),
    BA_STUDIO(
        "BA/PO Studio",
        "Draft BA and PO deliverables",
        Icons.Filled.Edit,
        Routes.STUDIO
    ),
    PROFILE(
        "Profile",
        "Your name, privacy and account settings",
        Icons.Filled.Person,
        Routes.PROFILE
    ),
    MY_PURCHASES(
        "My Purchases",
        "Your courses and purchases, payment and access status",
        Icons.Filled.ShoppingBag,
        Routes.CHECKOUT
    ),
    APPEARANCE(
        "Appearance",
        "Dark mode and display options",
        Icons.Filled.BrightnessMedium,
        Routes.APPEARANCE
    ),
    HELP_AND_SUPPORT(
        "Help and Support",
        "Customer support, your queries and FAQs",
        Icons.Filled.Help,
        Routes.HELP
    ),
    ABOUT(
        "About",
        "About Payments Training Hub, the operator and the curriculum",
        Icons.Filled.Info,
        Routes.ABOUT
    );
}

/**
 * The hamburger button that opens the drawer, on the root screens
 * only. A plain icon button in the top bar, matching iOS: no
 * shadow circle, no card background, so it can never read as a
 * floating element detached from the bar. The top bars on the tab
 * roots sit outside their scrolling content, so the button never
 * scrolls; auth and pushed screens have no hamburger at all, and
 * drawer edge gestures are disabled off the tab roots (see
 * AppNavGraph), so no drawer chrome can appear over a form.
 */
@Composable
fun DrawerMenuButton(onOpenDrawer: () -> Unit) {
    IconButton(onClick = onOpenDrawer) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = "Open menu",
            tint = PfColors.navy
        )
    }
}

/**
 * One item in the scrollable drawer list: the "Account and
 * settings" heading, one destination row, the "Everything
 * inside" heading, or one moved capability row. The flat list
 * is what the tour scrolls: the target row's index is scrolled
 * to the top of the list so it is always visible on its tour
 * step. The capability section sits after every destination,
 * so the tour's row indices are unchanged.
 */
private sealed interface DrawerListItem {
    data object AccountHeading : DrawerListItem
    data class Row(val destination: DrawerDestination) : DrawerListItem
    data object EverythingHeading : DrawerListItem
    data class Capability(val item: CapabilityItem) : DrawerListItem
}

/**
 * The drawer panel content. [currentRoute] is the current tab base,
 * for the active row state. [onSelect] fires after the drawer
 * closes. The guided tour was removed, so [onStartTour],
 * [tourHighlight], [tourCardHighlight] and [onTourTargetBounds]
 * are inert: they are kept so existing call sites keep compiling,
 * and no caller passes a tour target anymore.
 * [onNavigateRoute] fires for the moved capability rows in
 * the "Everything inside" section at the bottom of the list:
 * those entries used to live on Home and now live here, and
 * each one navigates straight to its route on a single tap.
 */
@Composable
fun AppDrawer(
    currentRoute: String?,
    onSelect: (DrawerDestination) -> Unit,
    onStartTour: () -> Unit,
    onContinueLearning: () -> Unit,
    tourHighlight: DrawerDestination? = null,
    tourCardHighlight: Boolean = false,
    onTourTargetBounds: (Rect?) -> Unit = {},
    onNavigateRoute: (String) -> Unit = {}
) {
    val destinations = DrawerDestination.values().toList()
    val drawerItems = remember(destinations) {
        val destinationItems = destinations.flatMap { destination ->
            if (destination == DrawerDestination.PROFILE) {
                listOf(
                    DrawerListItem.AccountHeading,
                    DrawerListItem.Row(destination)
                )
            } else {
                listOf(DrawerListItem.Row(destination))
            }
        }
        destinationItems + DrawerListItem.EverythingHeading +
            drawerCapabilityItems().map { DrawerListItem.Capability(it) }
    }
    val listState = rememberTopLazyListState()

    // Tour auto-scroll: when a drawer row becomes the tour target,
    // scroll it to the top of the list at once, then again after
    // the drawer slide settles, so the row is visible and settled
    // before the overlay ring is measured. Without this, rows
    // below the fold (Profile especially) stayed off screen while
    // the tour talked about them.
    LaunchedEffect(tourHighlight) {
        val target = tourHighlight ?: return@LaunchedEffect
        val index = drawerItems.indexOfFirst {
            it is DrawerListItem.Row && it.destination == target
        }
        if (index >= 0) {
            listState.scrollToItem(index)
            delay(400)
            listState.scrollToItem(index)
        }
    }
    LaunchedEffect(tourHighlight, tourCardHighlight) {
        if (tourHighlight == null && !tourCardHighlight) {
            onTourTargetBounds(null)
            // Scroll rule: with no tour target, the drawer list
            // restarts at the top instead of sitting at the last
            // spotlighted row.
            listState.scrollToItem(0)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(PfColors.card)
    ) {
        // Deep navy gradient header with the white brand name
        // and the tagline.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroGradientBrush())
                .padding(horizontal = 20.dp)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                    bottom = 20.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Payments Training Hub",
                    style = PfTypography.sectionTitle,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
                Text(
                    "Master the world of payments",
                    style = PfTypography.caption,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = PfSpacing.sm)
        ) {
            itemsIndexed(drawerItems) { index, item ->
                val isLastItem = index == drawerItems.lastIndex
                when (item) {
                    is DrawerListItem.AccountHeading -> {
                        // Account and settings rows sit under their
                        // own heading, so a settings row does not
                        // read as just another navigation destination.
                        Text(
                            "Account and settings",
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText,
                            modifier = Modifier
                                .padding(start = PfSpacing.xl)
                                .padding(top = PfSpacing.md, bottom = PfSpacing.xs)
                        )
                    }
                    is DrawerListItem.EverythingHeading -> {
                        // The moved Home capability entries: tracking
                        // and account destinations, one section.
                        Text(
                            "Everything inside",
                            style = PfTypography.captionBold,
                            color = PfColors.secondaryText,
                            modifier = Modifier
                                .padding(start = PfSpacing.xl)
                                .padding(top = PfSpacing.md, bottom = PfSpacing.xs)
                        )
                    }
                    is DrawerListItem.Row -> {
                        val destination = item.destination
                        val isHighlighted = destination == tourHighlight
                        DrawerRow(
                            destination = destination,
                            isActive = destination.tabRoute != null &&
                                destination.tabRoute == currentRoute,
                            isTourHighlight = isHighlighted,
                            onBounds = if (isHighlighted) {
                                { bounds -> onTourTargetBounds(bounds) }
                            } else {
                                null
                            },
                            onClick = {
                                onSelect(destination)
                            }
                        )
                        if (!isLastItem) {
                            Divider(
                                modifier = Modifier.padding(start = 80.dp),
                                color = PfColors.border
                            )
                        }
                    }
                    is DrawerListItem.Capability -> {
                        DrawerCapabilityRow(
                            item = item.item,
                            onClick = { onNavigateRoute(item.item.route) }
                        )
                        if (!isLastItem) {
                            Divider(
                                modifier = Modifier.padding(start = 80.dp),
                                color = PfColors.border
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(PfSpacing.lg))

        // Highlighted progress card: streak and weekly goal
        // progress, with a button to continue learning.
        KeepLearningCard(
            onContinue = onContinueLearning,
            isTourHighlight = tourCardHighlight,
            onBounds = if (tourCardHighlight) {
                { bounds -> onTourTargetBounds(bounds) }
            } else {
                null
            }
        )

        Spacer(modifier = Modifier.height(PfSpacing.lg))

        // Brand name at the very bottom, kept above the system
        // navigation bar by the real device bottom inset.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + PfSpacing.lg
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "Payments Training Hub",
                style = PfTypography.sectionTitle,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy
            )
            Text(
                "Payments, taught the way banks actually process them.",
                style = PfTypography.caption,
                color = PfColors.secondaryText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DrawerRow(
    destination: DrawerDestination,
    isActive: Boolean,
    isTourHighlight: Boolean,
    onBounds: ((Rect) -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        onBounds(coordinates.boundsInWindow())
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = PfSpacing.sm)
            .clip(RoundedCornerShape(PfSpacing.smallRadius))
            .background(
                when {
                    isTourHighlight -> PfColors.royal.copy(alpha = 0.12f)
                    isActive -> PfColors.royal.copy(alpha = 0.08f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .then(
                if (isTourHighlight) {
                    Modifier.border(
                        width = 2.dp,
                        color = PfColors.gold,
                        shape = RoundedCornerShape(PfSpacing.smallRadius)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = PfSpacing.xl, vertical = 15.dp)
            .semantics {
                contentDescription = if (isActive) {
                    "${destination.title}. ${destination.subtitle}. Current section."
                } else {
                    "${destination.title}. ${destination.subtitle}"
                }
            },
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = if (isActive) PfColors.royal else PfColors.navy,
            modifier = Modifier.size(40.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                destination.title,
                style = PfTypography.body,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) PfColors.royal else PfColors.navy
            )
            Text(
                destination.subtitle,
                style = PfTypography.caption,
                color = PfColors.secondaryText,
                maxLines = 2
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (isActive) PfColors.royal.copy(alpha = 0.8f)
            else PfColors.navy.copy(alpha = 0.55f)
        )
    }
}

/**
 * One moved capability row in the "Everything inside" drawer
 * section: the same size, icon, title, subtitle and chevron
 * as a drawer destination row, so it reads as tappable in the
 * same way and navigates on a single tap.
 */
@Composable
private fun DrawerCapabilityRow(
    item: CapabilityItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PfSpacing.sm)
            .clip(RoundedCornerShape(PfSpacing.smallRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = PfSpacing.xl, vertical = 15.dp)
            .semantics {
                contentDescription = "${item.title}. ${item.subtitle}"
            },
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = PfColors.navy,
            modifier = Modifier.size(40.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                item.title,
                style = PfTypography.body,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.navy
            )
            Text(
                item.subtitle,
                style = PfTypography.caption,
                color = PfColors.secondaryText,
                maxLines = 2
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = PfColors.navy.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun KeepLearningCard(
    onContinue: () -> Unit,
    isTourHighlight: Boolean,
    onBounds: ((Rect) -> Unit)? = null
) {
    var streak by remember { mutableStateOf(0) }
    var doneThisWeek by remember { mutableStateOf(0) }
    var weeklyGoal by remember { mutableStateOf(5) }
    var hasCompletedLesson by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val events = runCatching { HabitStore.loadEvents() }.getOrDefault(emptyList())
        val completedIds = runCatching { HabitStore.loadCompletedLessonIds() }
            .getOrDefault(emptySet())
        val goal = runCatching { HabitStore.loadWeeklyGoal() }.getOrDefault(0)
        streak = LearningHabitPlanner.streakCount(events)
        doneThisWeek = LearningHabitPlanner.completedThisWeek(events)
        // iOS ProgressStore defaults the weekly goal to 5; the
        // stored goal is untouched, this is the display default.
        weeklyGoal = if (goal > 0) goal else 5
        hasCompletedLesson = completedIds.isNotEmpty()
    }

    // Progress rule: never a 0-of-N counter. A fresh user sees
    // the starter line and no progress bar; a streak with no
    // lessons this week names the streak without a zero count.
    val progressLine = when {
        streak == 0 && doneThisWeek == 0 ->
            "Your first lesson is waiting. Start with Course 1."
        doneThisWeek == 0 ->
            "$streak day streak. No lessons yet this week."
        streak > 0 ->
            "$streak day streak. $doneThisWeek of $weeklyGoal lessons this week."
        else ->
            "$doneThisWeek of $weeklyGoal lessons this week. Start your streak today."
    }
    val progress = if (weeklyGoal > 0) {
        (doneThisWeek.toFloat() / weeklyGoal.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val showProgressBar = doneThisWeek > 0

    Row(
        modifier = Modifier
            .padding(horizontal = PfSpacing.lg)
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        onBounds(coordinates.boundsInWindow())
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(PfSpacing.cardRadius))
            .background(PfColors.background)
            .then(
                if (isTourHighlight) {
                    Modifier.border(
                        width = 2.dp,
                        color = PfColors.gold,
                        shape = RoundedCornerShape(PfSpacing.cardRadius)
                    )
                } else {
                    Modifier
                }
            )
            .padding(PfSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(PfColors.goldSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Whatshot,
                contentDescription = null,
                tint = PfColors.gold,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                if (hasCompletedLesson) "Keep learning" else "Start learning",
                style = PfTypography.body,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy
            )
            Text(
                progressLine,
                style = PfTypography.caption,
                color = PfColors.secondaryText,
                maxLines = 2
            )
            if (showProgressBar) {
                PfProgressBar(progress = progress, height = 6.dp)
            }
        }
        Spacer(modifier = Modifier.width(PfSpacing.sm))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(PfColors.royal.copy(alpha = 0.10f))
                .clickable(onClick = onContinue)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(min = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (hasCompletedLesson) "Continue" else "Start",
                style = PfTypography.body,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.royal
            )
        }
    }
}
