// HomeScreen.kt
// Payments Training Hub Android - Home tab. Home shows exactly
// three tabs at the top, always visible: Learning Journey (the
// greeting, Continue learning, Daily Challenge, progress and
// the journeys), Courses (guided reading sessions, the 10
// courses and the catalogue), and Practice (guides, labs,
// tests, interview questions and every hands-on tool). The
// capability list is gone from Home: its account and tracking
// entries live in the side drawer. There is no separate Learn
// page: the Learn route renders this screen on the Courses
// tab, and Home accepts a section anchor ("home?section=courses")
// so every Learn link lands on the tab it promised.
//
// Progress numbers come only from recorded stores (completed
// lesson ids, learning events, weekly goal); nothing is invented.
// The progress rule holds: progress UI appears only after
// meaningful progress (the first chapter completed); before that,
// starter states, never zero counters. Every tab opens at the
// top on every entry, and tapping the Home bottom-bar tab
// returns to the top of the Learning Journey tab with the tab
// row visible. The greeting names "Learner": Android stores no
// display name, and iOS falls back to "Learner" for guests too.
// The greeting shows the signed-in learner's profile picture
// when one is uploaded, with the default person icon as the
// fallback. No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.ProfilePictureStore
import com.paymentstraininghub.app.data.ActivePathStore
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.GuidedSessions
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.data.PathBookmarkStore
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ProgressVisibility
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.challenge.DailyChallenge
import com.paymentstraininghub.app.features.habit.HabitLearningEvent
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.features.habit.LearningHabitPlanner
import com.paymentstraininghub.app.ui.enumSaver
import com.paymentstraininghub.app.ui.learn.CatalogueTableHeader
import com.paymentstraininghub.app.ui.learn.CatalogueTableRow
import com.paymentstraininghub.app.ui.learn.GuidedPathCard
import com.paymentstraininghub.app.ui.learn.LibraryRow
import com.paymentstraininghub.app.ui.learn.PartGroupCard
import com.paymentstraininghub.app.ui.learn.PathLevelFilter
import com.paymentstraininghub.app.ui.learn.SessionCard
import com.paymentstraininghub.app.ui.learn.countSubtitle
import com.paymentstraininghub.app.ui.navigation.DrawerMenuButton
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.setSaver
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlinx.coroutines.launch

/** The three Home tabs, in order. Tab 1 is the default. */
internal enum class HomeTab(val label: String) {
    JOURNEY("Learning Journey"),
    COURSES("Courses"),
    PRACTICE("Practice")
}

/** The tab a section anchor lands on. Unknown sections land on the Learning Journey tab. */
internal fun homeTabForSection(section: String?): HomeTab = when (section) {
    Routes.HOME_SECTION_COURSES, Routes.HOME_SECTION_CATALOGUE -> HomeTab.COURSES
    else -> HomeTab.JOURNEY
}

/** One Home block: a stable key, the tab it belongs to (null means every tab), and its content. */
private data class HomeBlock(
    val key: String,
    val tab: HomeTab?,
    val content: @Composable () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    initialSection: String? = null,
    /** Increments whenever the Home bottom-bar tab is tapped while Home is already showing: Home resets to the top of the Learning Journey tab. */
    reselectTick: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var events by remember { mutableStateOf<List<HabitLearningEvent>>(emptyList()) }
    var weeklyGoal by remember { mutableStateOf(0) }
    var challengeCompletedToday by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    var expandedParts by rememberSaveable(saver = setSaver()) {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var expandedProducts by rememberSaveable(saver = setSaver()) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var journeyFilter by rememberSaveable(
        saver = enumSaver(PathLevelFilter.entries.toTypedArray())
    ) { mutableStateOf(PathLevelFilter.ALL) }
    var selectedTab by rememberSaveable(
        saver = enumSaver(HomeTab.entries.toTypedArray())
    ) { mutableStateOf(homeTabForSection(initialSection)) }

    val activePathId by ActivePathStore.activePathId.collectAsState()
    val bookmarkedIds by PathBookmarkStore.bookmarkedIds.collectAsState()
    val agendas by AgendaStore.agendas.collectAsState()
    val authState by AuthManager.authState.collectAsState()
    val avatarUrl by ProfilePictureStore.avatarUrl.collectAsState()

    // The greeting picture follows the signed-in account: a
    // fresh fetch when the account changes, cleared for guests.
    // Failures leave the default icon; Home never waits on it.
    LaunchedEffect(authState) {
        val signedIn = authState as? AuthManager.AuthState.SignedIn
        ProfilePictureStore.refreshFor(signedIn?.userId)
    }

    LaunchedEffect(retryTick) {
        loading = true
        loadError = false
        try {
            chapters = ContentRepository.chapters()
            entries = ContentRepository.loadSearchIndex().entries
            completedIds = HabitStore.loadCompletedLessonIds()
            events = HabitStore.loadEvents()
            weeklyGoal = HabitStore.loadWeeklyGoal()
            ActivePathStore.refresh()
            PathBookmarkStore.refresh()
            AgendaStore.refreshIfNeeded(context.filesDir)
            val dayKey = DailyChallenge.dayKey(LocalDate.now())
            challengeCompletedToday = runCatching {
                AppStore.getBoolean("pf.dailyChallenge.completed.$dayKey.v1")
            }.getOrNull() ?: false
        } catch (e: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    val entryTitles = remember(entries) { entries.associateBy({ it.id }, { it.title }) }
    // Book reading order, not catalogue order: the resume lesson is
    // the first incomplete lesson in the order a learner reads the
    // book, and held-out chapters sort last.
    val orderedChapters = remember(chapters) {
        chapters?.sortedBy { ReadingOrder.chapterSortKey(it.id) } ?: emptyList()
    }
    val orderedLessonIds = remember(orderedChapters) {
        orderedChapters.flatMap { ReadingOrder.sortLessonIds(it.lessonIds) }
    }
    val isNewLearner = completedIds.isEmpty()
    val activePath = remember(activePathId) {
        LearningPaths.all.firstOrNull { it.id == activePathId }
    }
    val activePathProgress = remember(activePath, entries, completedIds) {
        activePath?.progress(entries, completedIds)
    }
    // The one next lesson: the chosen journey's next lesson when a
    // journey is chosen, else the reading-order next lesson.
    val nextLesson = remember(orderedLessonIds, orderedChapters, entryTitles, entries, completedIds, activePath) {
        resolveNextLesson(orderedLessonIds, orderedChapters, entryTitles, entries, completedIds, activePath)
    }
    val lastCompletedId = remember(events) { lastCompletedLessonId(events) }
    val lastCompletedTitle = lastCompletedId?.let { entryTitles[it] }
    val completedCount = remember(orderedLessonIds, completedIds) {
        orderedLessonIds.count { it in completedIds }
    }
    val streak = remember(events) { LearningHabitPlanner.streakCount(events) }
    val completedThisWeek = remember(events) { LearningHabitPlanner.completedThisWeek(events) }
    // Progress rule: progress UI appears only after meaningful
    // progress (the first chapter completed). Before that, the
    // progress card shows a starter state, never a zero counter.
    val meaningfulProgress = remember(orderedChapters, completedIds) {
        ProgressVisibility.hasCompletedChapter(completedIds, orderedChapters)
    }
    val bookParts = remember(orderedChapters) { buildBookParts(orderedChapters) }
    val inProgressCourses = remember(bookParts, completedIds, nextLesson, agendas) {
        buildInProgressCourses(bookParts, completedIds, nextLesson, agendas)
    }
    val filteredJourneys = remember(journeyFilter) {
        LearningPaths.all.filter { path ->
            journeyFilter == PathLevelFilter.ALL ||
                journeyFilter.label.equals(path.level, ignoreCase = true)
        }
    }
    // The catalogue table lists the courses: the Integrated Course
    // plus the 10 parts. Standalone products live in the
    // capabilities list and on the Courses page.
    val catalogueProducts = remember {
        ProductCatalogue.products.filter { it.id == "integrated" || it.id.startsWith("part-") }
    }
    val guideCount = remember(entries) { entries.count { it.isGuide } }
    val testCount = remember(entries) { entries.count { it.isTest } }

    val blocks = buildList {
        add(
            HomeBlock("greeting", HomeTab.JOURNEY) {
                GreetingBlock(
                    isNewLearner = isNewLearner,
                    avatarUrl = avatarUrl,
                    onAvatarClick = { onNavigate(Routes.PROFILE) }
                )
            }
        )
        when {
            loading -> {
                add(
                    HomeBlock("loading", null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = PfColors.royal)
                        }
                        Text(
                            "Loading the course catalogue...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                )
            }
            loadError || chapters == null -> {
                add(
                    HomeBlock("load-error", null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = PfColors.card),
                            border = BorderStroke(1.dp, PfColors.border)
                        ) {
                            Column(modifier = Modifier.padding(PfSpacing.medium)) {
                                Text(
                                    "Could not load the course catalogue",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PfColors.navy
                                )
                                Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                                Text(
                                    "Check your connection and try again.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                Spacer(modifier = Modifier.height(PfSpacing.small))
                                Button(
                                    onClick = { retryTick++ },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PfColors.royal,
                                        contentColor = Color.White
                                    )
                                ) { Text("Retry") }
                            }
                        }
                    }
                )
            }
            else -> {
                // JOURNEY tab order: the journeys group sits
                // directly under the greeting, then Continue
                // Learning, Daily Challenge, Progress, and the
                // depth strip last.
                add(
                    HomeBlock("journeys-header", HomeTab.JOURNEY) {
                        HomeSectionHeader(
                            title = "Your learning journey",
                            subtitle = "Journeys and guided reading sessions. Pick one goal and follow it in order."
                        )
                    }
                )
                add(
                    HomeBlock("journeys-explainer", HomeTab.JOURNEY) {
                        JourneyExplainerCard(hasChosenJourney = activePath != null)
                    }
                )
                if (activePath != null && activePathProgress != null) {
                    val (done, total) = activePathProgress
                    add(
                        HomeBlock("your-journey", HomeTab.JOURNEY) {
                            YourJourneyCard(
                                journeyTitle = activePath.title,
                                journeyDescription = activePath.description,
                                done = done,
                                total = total,
                                onOpenJourney = { onNavigate(Routes.path(activePath.id)) }
                            )
                        }
                    )
                }
                add(
                    HomeBlock("journeys-filter", HomeTab.JOURNEY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            PathLevelFilter.values().forEach { filter ->
                                FilterChip(
                                    selected = journeyFilter == filter,
                                    onClick = { journeyFilter = filter },
                                    label = { Text(filter.label) }
                                )
                            }
                        }
                    }
                )
                filteredJourneys.forEach { path ->
                    add(
                        HomeBlock("journey-${path.id}", HomeTab.JOURNEY) {
                            GuidedPathCard(
                                path = path,
                                entries = entries,
                                completedIds = completedIds,
                                isBookmarked = path.id in bookmarkedIds,
                                onToggleBookmark = {
                                    scope.launch { PathBookmarkStore.toggle(path.id) }
                                },
                                onClick = { onNavigate(Routes.path(path.id)) }
                            )
                        }
                    )
                }
                if (nextLesson != null) {
                    add(
                        HomeBlock("continue", HomeTab.JOURNEY) {
                            // Fresh users and finished courses get
                            // the single start or review card, never
                            // a zero counter. Learners mid-course
                            // get the in-progress course cards.
                            if (completedCount == 0 || nextLesson.allComplete ||
                                inProgressCourses.isEmpty()
                            ) {
                                ContinueLearningCard(
                                    next = nextLesson,
                                    lastCompletedTitle = lastCompletedTitle,
                                    completedCount = completedCount,
                                    onOpenLesson = { onNavigate(Routes.lesson(it)) }
                                )
                            } else {
                                ContinueLearningCards(
                                    courses = inProgressCourses,
                                    lastCompletedTitle = lastCompletedTitle,
                                    onOpenLesson = { onNavigate(Routes.lesson(it)) }
                                )
                            }
                        }
                    )
                }
                add(
                    HomeBlock("challenge", HomeTab.JOURNEY) {
                        NavCard(
                            title = "Daily Challenge",
                            subtitle = if (challengeCompletedToday) {
                                "Completed today. One real case, 3 to 5 minutes. Come back tomorrow for a new case."
                            } else {
                                "One real case, 3 to 5 minutes. Read the case, pick the key lesson."
                            },
                            buttonText = if (challengeCompletedToday) "Review today's case" else "Take today's challenge",
                            onClick = { onNavigate(Routes.DAILY_CHALLENGE) }
                        )
                    }
                )
                add(
                    HomeBlock("progress", HomeTab.JOURNEY) {
                        ProgressCard(
                            meaningfulProgress = meaningfulProgress,
                            completedCount = completedCount,
                            totalCount = orderedLessonIds.size,
                            completedThisWeek = completedThisWeek,
                            weeklyGoal = weeklyGoal,
                            streak = streak,
                            onOpenProgress = { onNavigate(Routes.PROGRESS) },
                            onStartLearning = { onNavigate(Routes.homeSection(Routes.HOME_SECTION_COURSES)) }
                        )
                    }
                )
                add(
                    HomeBlock("depth", HomeTab.JOURNEY) { DepthStrip() }
                )
                add(
                    HomeBlock("sessions-header", HomeTab.COURSES) {
                        HomeSectionHeader(
                            title = "Guided reading sessions",
                            subtitle = "Eight short reading sessions that walk the foundations in order."
                        )
                    }
                )
                GuidedSessions.all.forEach { session ->
                    add(
                        HomeBlock("session-${session.id}", HomeTab.COURSES) {
                            SessionCard(
                                session = session,
                                entries = entries,
                                completedIds = completedIds,
                                onClick = { onNavigate(Routes.session(session.id)) }
                            )
                        }
                    )
                }
                add(
                    HomeBlock("courses-header", HomeTab.COURSES) {
                        HomeSectionHeader(
                            title = "Courses",
                            subtitle = "10 courses, from foundations to the future. Open a course to see its chapters and start a lesson."
                        )
                    }
                )
                bookParts.forEach { part ->
                    add(
                        HomeBlock("course-${part.number}", HomeTab.COURSES) {
                            PartGroupCard(
                                part = part,
                                entries = entries,
                                completedIds = completedIds,
                                agendas = agendas,
                                isExpanded = part.number in expandedParts,
                                onToggleExpanded = {
                                    expandedParts = if (part.number in expandedParts) {
                                        expandedParts - part.number
                                    } else {
                                        expandedParts + part.number
                                    }
                                },
                                onChapterClick = { onNavigate(Routes.chapter(it)) }
                            )
                        }
                    )
                }
                add(
                    HomeBlock("catalogue-header", HomeTab.COURSES) {
                        HomeSectionHeader(
                            title = "The catalogue",
                            subtitle = "Every course with its chapters and lessons. Open a row to see its chapters."
                        )
                    }
                )
                add(
                    HomeBlock("catalogue-table-header", HomeTab.COURSES) {
                        CatalogueTableHeader()
                    }
                )
                catalogueProducts.forEach { product ->
                    add(
                        HomeBlock("catalogue-${product.id}", HomeTab.COURSES) {
                            CatalogueTableRow(
                                product = product,
                                entries = entries,
                                chapterList = orderedChapters,
                                completedIds = completedIds,
                                agendas = agendas,
                                isExpanded = product.id in expandedProducts,
                                onToggleExpanded = {
                                    expandedProducts = if (product.id in expandedProducts) {
                                        expandedProducts - product.id
                                    } else {
                                        expandedProducts + product.id
                                    }
                                },
                                onChapterClick = { onNavigate(Routes.chapter(it)) }
                            )
                        }
                    )
                }
                add(
                    HomeBlock("practice-header", HomeTab.PRACTICE) {
                        HomeSectionHeader(
                            title = "Practice",
                            subtitle = "Guides, labs, tests and interview questions, plus every hands-on tool."
                        )
                    }
                )
                add(
                    HomeBlock("library-guides", HomeTab.PRACTICE) {
                        LibraryRow(
                            title = "Guides",
                            subtitle = countSubtitle(guideCount, "guide", "guides", "in the library"),
                            icon = Icons.Filled.Description,
                            onClick = { onNavigate(Routes.GUIDES) }
                        )
                    }
                )
                add(
                    HomeBlock("library-tests", HomeTab.PRACTICE) {
                        LibraryRow(
                            title = "Tests",
                            subtitle = countSubtitle(testCount, "test", "tests", "in the library"),
                            icon = Icons.Filled.Quiz,
                            onClick = { onNavigate(Routes.TESTS) }
                        )
                    }
                )
                add(
                    HomeBlock("library-interview", HomeTab.PRACTICE) {
                        LibraryRow(
                            title = "Interview Questions",
                            subtitle = "${ContentManifest.interviewQuestionCount} questions with model answers",
                            icon = Icons.Filled.QuestionAnswer,
                            onClick = { onNavigate(Routes.INTERVIEW) }
                        )
                    }
                )
                // Labs renders here once, as a practice row: the
                // old library Labs row is gone, so the route is
                // listed exactly once on this tab.
                practiceTabItems().forEach { item ->
                    add(
                        HomeBlock("practice-${item.route}", HomeTab.PRACTICE) {
                            CapabilityRow(item = item, onNavigate = onNavigate)
                        }
                    )
                }
            }
        }
        add(
            // The bottom inset belongs to no tab: it renders
            // under every tab's list.
            HomeBlock("bottom-inset", null) {
                PfBottomInset()
            }
        )
    }

    val listState = rememberTopLazyListState()
    // Every tab switch lands at the very top of the new tab:
    // a tab is an entry, and every entry opens at the top.
    LaunchedEffect(selectedTab) {
        listState.scrollToItem(0)
    }
    // Section anchors select the tab they promise. The
    // capabilities anchor opens the drawer instead: those
    // entries live there now. Once per section value, so a
    // later state change never re-opens the drawer.
    var drawerOpenedForSection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialSection) {
        if (initialSection != null) {
            selectedTab = homeTabForSection(initialSection)
            if (initialSection == Routes.HOME_SECTION_CAPABILITIES &&
                drawerOpenedForSection != initialSection
            ) {
                drawerOpenedForSection = initialSection
                onOpenDrawer()
            }
        }
    }
    // Bottom-bar Home re-tap: back to the top of the Learning
    // Journey tab. The first observed tick is the starting
    // value, not a tap, so it is recorded and ignored.
    val seenReselectTick = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(reselectTick) {
        val seen = seenReselectTick.value
        if (seen != null && reselectTick != seen) {
            selectedTab = HomeTab.JOURNEY
            listState.scrollToItem(0)
        }
        seenReselectTick.value = reselectTick
    }

    val visibleBlocks = remember(blocks, selectedTab) {
        blocks.filter { it.tab == null || it.tab == selectedTab }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "Payments Training Hub",
                    style = MaterialTheme.typography.headlineMedium,
                    color = PfColors.navy
                )
            },
            navigationIcon = { DrawerMenuButton(onOpenDrawer = onOpenDrawer) },
            actions = {
                IconButton(onClick = { onNavigate(Routes.PROFILE) }) {
                    Icon(Icons.Filled.Person, contentDescription = "Profile")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                actionIconContentColor = PfColors.navy
            )
        )
        HomeTabRow(
            selectedTab = selectedTab,
            onSelect = { selectedTab = it }
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            items(visibleBlocks, key = { it.key }) { block ->
                block.content()
            }
        }
    }
}

/**
 * The Home tab row: exactly three large buttons, fixed above
 * the scrolling list so they never scroll away. The selected
 * tab is a solid royal button with white bold text; the others
 * are card buttons with navy text and a border, so every tab
 * reads as tappable at a glance. Taps switch on the first tap:
 * the click sets the tab state directly, with no debounce and
 * no gating effect in between.
 */
@Composable
private fun HomeTabRow(selectedTab: HomeTab, onSelect: (HomeTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        HomeTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Button(
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("home-tab-${tab.name.lowercase()}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) PfColors.royal else PfColors.card,
                    contentColor = if (selected) Color.White else PfColors.navy
                ),
                border = if (selected) {
                    null
                } else {
                    BorderStroke(1.dp, PfColors.border)
                }
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun GreetingBlock(
    isNewLearner: Boolean,
    avatarUrl: String?,
    onAvatarClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)
    ) {
        ProfileAvatar(
            avatarUrl = avatarUrl,
            onClick = onAvatarClick
        )
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
            Text(
                greetingText() + " \uD83D\uDC4B",
                style = MaterialTheme.typography.displayLarge,
                color = PfColors.navy
            )
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")),
                style = MaterialTheme.typography.labelSmall,
                color = PfColors.royal
            )
            Text(
                "Master the world of payments, one lesson at a time.",
                style = MaterialTheme.typography.bodyLarge,
                color = PfColors.secondaryText
            )
            if (isNewLearner) {
                Text(
                    "Welcome to Payments Training Hub.",
                    style = MaterialTheme.typography.titleMedium,
                    color = PfColors.royal
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(
    meaningfulProgress: Boolean,
    completedCount: Int,
    totalCount: Int,
    completedThisWeek: Int,
    weeklyGoal: Int,
    streak: Int,
    onOpenProgress: () -> Unit,
    onStartLearning: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(
                "Your progress",
                style = MaterialTheme.typography.titleMedium,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            if (meaningfulProgress) {
                Text(
                    "$completedCount of $totalCount lessons completed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.navy
                )
                Text(
                    LearningHabitPlanner.weeklyTargetText(completedThisWeek, weeklyGoal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
                Text(
                    LearningHabitPlanner.streakText(streak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                OutlinedButton(onClick = onOpenProgress) {
                    Text("Open Progress")
                }
            } else {
                Text(
                    "Your progress will appear here once you finish your first chapter. Start with the first lesson.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                OutlinedButton(onClick = onStartLearning) {
                    Text("Start learning")
                }
            }
        }
    }
}

@Composable
private fun NavCard(title: String, subtitle: String, buttonText: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PfColors.navy)
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
            Spacer(modifier = Modifier.height(PfSpacing.small))
            OutlinedButton(onClick = onClick) { Text(buttonText) }
        }
    }
}

/// Time-based greeting, iOS hours: night is night, not evening.
/// The name is "Learner": Android stores no display name.
private fun greetingText(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val base = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    return "$base, Learner"
}
