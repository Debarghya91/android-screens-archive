// Wave3HomeTest.kt
// Wave 3 Home proofs: exactly three tabs with the right content
// in each, single-tap tab switching, Home opening at the top,
// no capability list on Home, the 13 moved entries in the
// drawer with single-tap navigation each, Coursera-style
// Continue Learning cards, and the greeting picture with its
// fallback. Pure tests pin the course-card builder, the drawer
// list, and the section-to-tab mapping.

package com.paymentstraininghub.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.AgendaEntry
import com.paymentstraininghub.app.data.Agendas
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.ui.home.ContinueLearningCards
import com.paymentstraininghub.app.ui.home.HomeScreen
import com.paymentstraininghub.app.ui.home.HomeTab
import com.paymentstraininghub.app.ui.home.InProgressCourse
import com.paymentstraininghub.app.ui.home.NextLesson
import com.paymentstraininghub.app.ui.home.ProfileAvatar
import com.paymentstraininghub.app.ui.home.buildInProgressCourses
import com.paymentstraininghub.app.ui.home.drawerCapabilityItems
import com.paymentstraininghub.app.ui.home.homeTabForSection
import com.paymentstraininghub.app.ui.learn.BookChapterRow
import com.paymentstraininghub.app.ui.learn.BookPartRow
import com.paymentstraininghub.app.ui.navigation.AppDrawer
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class Wave3HomePureTest {

    private fun chapter(id: String, lessonIds: List<String>, number: Int = 1) =
        Chapter(
            id = id,
            number = number,
            title = "Chapter $id",
            domain = "Foundations",
            lessonCount = lessonIds.size,
            lessonIds = lessonIds
        )

    // One chapter per part: ReadingOrder sorts lesson ids by
    // their s-number position, so cross-chapter ids in one
    // part would interleave. Single-chapter parts keep the
    // resume order obvious: first incomplete in the chapter.
    private fun parts(): List<BookPartRow> = listOf(
        BookPartRow(
            number = 1,
            title = "Course 1: Foundations",
            chapters = listOf(
                BookChapterRow(chapter("ch1", listOf("ch1s1", "ch1s2", "ch1s3", "ch1s4"), 1), 1)
            )
        ),
        BookPartRow(
            number = 2,
            title = "Course 2: Clearing",
            chapters = listOf(
                BookChapterRow(chapter("ch3", listOf("ch3s1", "ch3s2"), 3), 3)
            )
        ),
        BookPartRow(number = 3, title = "Course 3: Empty", chapters = emptyList())
    )

    private fun agendas() = Agendas(
        courses = mapOf(
            "part-1" to AgendaEntry("Why take course 1.", "assets/thumbs/course-part-1.svg"),
            "part-2" to AgendaEntry("Why take course 2.", "assets/thumbs/course-part-2.svg")
        )
    )

    private fun nextLesson(id: String, fromJourney: Boolean = false) = NextLesson(
        lessonId = id,
        title = "Lesson $id",
        chapterLabel = "Chapter 1",
        chapterTitle = "Foundations",
        fromJourney = fromJourney,
        journeyTitle = if (fromJourney) "Payments BA" else null,
        allComplete = false
    )

    @Test
    fun mixedCompletionBuildsCardsWithOwnPartResume() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch1s1", "ch1s2", "ch3s1"),
            nextLesson("ch1s3"),
            agendas()
        )
        assertEquals(2, courses.size)
        val first = courses[0]
        assertEquals(1, first.partNumber)
        assertEquals(2, first.done)
        assertEquals(4, first.total)
        assertEquals("ch1s3", first.nextLessonId)
        assertFalse(first.isStartCard)
        assertFalse(first.isJourneyNext)
        assertEquals("assets/thumbs/course-part-1.svg", first.thumbnailPath)
        val second = courses[1]
        assertEquals(2, second.partNumber)
        assertEquals(1, second.done)
        assertEquals(2, second.total)
        assertEquals("ch3s2", second.nextLessonId)
    }

    @Test
    fun journeyNextPartSortsFirstAndIsMarked() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch3s1"),
            nextLesson("ch3s2", fromJourney = true),
            agendas()
        )
        assertEquals(1, courses.size)
        assertEquals(2, courses[0].partNumber)
        assertTrue(courses[0].isJourneyNext)
        assertEquals("ch3s2", courses[0].nextLessonId)
    }

    @Test
    fun unstartedPartHoldingNextLessonIsAStartCard() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch1s1"),
            nextLesson("ch3s1"),
            agendas()
        )
        assertEquals(2, courses.size)
        val start = courses[0]
        assertEquals(2, start.partNumber)
        assertEquals(0, start.done)
        assertTrue(start.isStartCard)
        assertEquals("ch3s1", start.nextLessonId)
        assertEquals(1, courses[1].partNumber)
        assertEquals("ch1s2", courses[1].nextLessonId)
    }

    @Test
    fun completedPartIsExcluded() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch3s1", "ch3s2", "ch1s1"),
            nextLesson("ch1s2"),
            agendas()
        )
        assertEquals(listOf(1), courses.map { it.partNumber })
    }

    @Test
    fun freshPartWithoutNextLessonIsExcluded() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch1s1"),
            nextLesson("ch1s2"),
            agendas()
        )
        assertEquals(listOf(1), courses.map { it.partNumber })
    }

    @Test
    fun emptyPartIsExcludedAndNullNextLessonResumesFirstIncomplete() {
        val courses = buildInProgressCourses(
            parts(),
            setOf("ch1s1"),
            null,
            agendas()
        )
        assertEquals(listOf(1), courses.map { it.partNumber })
        assertEquals("ch1s2", courses[0].nextLessonId)
    }

    @Test
    fun drawerListHoldsAllThirteenEntriesInGroupOrder() {
        val items = drawerCapabilityItems()
        assertEquals(
            listOf(
                "Interview Questions",
                "My Progress",
                "Certificates",
                "Learning habit",
                "Courses and purchases",
                "Profile",
                "Notifications",
                "Help and Support",
                "Appearance",
                "About",
                "Diagnostics",
                "Privacy Policy",
                "Terms of Service"
            ),
            items.map { it.title }
        )
        assertEquals(
            listOf(
                Routes.INTERVIEW,
                Routes.PROGRESS,
                Routes.CERTIFICATES,
                Routes.HABIT,
                Routes.CHECKOUT,
                Routes.PROFILE,
                Routes.NOTIFICATIONS,
                Routes.HELP,
                Routes.APPEARANCE,
                Routes.ABOUT,
                Routes.DIAGNOSTICS,
                Routes.PRIVACY,
                Routes.TERMS
            ),
            items.map { it.route }
        )
        assertEquals(13, items.map { it.route }.toSet().size)
    }

    @Test
    fun sectionAnchorsMapToTabs() {
        assertEquals(HomeTab.COURSES, homeTabForSection(Routes.HOME_SECTION_COURSES))
        assertEquals(HomeTab.COURSES, homeTabForSection(Routes.HOME_SECTION_CATALOGUE))
        assertEquals(HomeTab.JOURNEY, homeTabForSection(Routes.HOME_SECTION_JOURNEYS))
        assertEquals(HomeTab.JOURNEY, homeTabForSection(Routes.HOME_SECTION_CONTINUE))
        assertEquals(HomeTab.JOURNEY, homeTabForSection(Routes.HOME_SECTION_CAPABILITIES))
        assertEquals(HomeTab.JOURNEY, homeTabForSection(null))
        assertEquals(HomeTab.JOURNEY, homeTabForSection("nonsense"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class Wave3HomeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.install()
        RenderFakeData.installAgendas()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
        RenderFakeData.resetStores()
        runBlocking { AppStore.putString("pf.completedLessons.v1", "[]") }
        runCatching { AudioPlayer.stop() }
    }

    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(1200L)
        composeRule.waitForIdle()
    }

    /**
     * A slow half-viewport drag. A fast swipeUp flings several
     * viewports per gesture, so settled positions skip over
     * mid-list items entirely; a slow drag steps through the
     * list without momentum overshoot.
     */
    private fun slowSwipeUp(list: androidx.compose.ui.test.SemanticsNodeInteraction) {
        list.performTouchInput {
            swipeUp(startY = height * 0.75f, endY = height * 0.25f, durationMillis = 600)
        }
        composeRule.waitForIdle()
    }

    private fun scrollUntilTextVisible(text: String, maxSwipes: Int = 40): Boolean {
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(maxSwipes) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                return true
            }
            slowSwipeUp(list)
        }
        return composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollUntilSubstringVisible(substring: String, maxSwipes: Int = 40): Boolean {
        // Card rows merge their title with the subtitle, so
        // match by substring.
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(maxSwipes) {
            if (composeRule.onAllNodes(hasText(substring, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                return true
            }
            slowSwipeUp(list)
        }
        return composeRule.onAllNodes(hasText(substring, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollIndexUntilVisible(matcher: androidx.compose.ui.test.SemanticsMatcher, maxIndex: Int = 80): Boolean {
        val list = composeRule.onNode(hasScrollToIndexAction())
        for (index in 0..maxIndex) {
            if (composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                return true
            }
            try {
                list.performScrollToIndex(index)
            } catch (e: IllegalArgumentException) {
                break
            }
            composeRule.waitForIdle()
        }
        return composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun homeShowsExactlyThreeTabs() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNodeWithTag("home-tab-journey").assertIsDisplayed()
        composeRule.onNodeWithTag("home-tab-courses").assertIsDisplayed()
        composeRule.onNodeWithTag("home-tab-practice").assertIsDisplayed()
        composeRule.onNodeWithText("Learning Journey").assertIsDisplayed()
        composeRule.onNodeWithText("Courses").assertIsDisplayed()
        composeRule.onNodeWithText("Practice").assertIsDisplayed()
        assertEquals(
            3,
            composeRule.onAllNodesWithTag("home-tab-journey").fetchSemanticsNodes().size +
                composeRule.onAllNodesWithTag("home-tab-courses").fetchSemanticsNodes().size +
                composeRule.onAllNodesWithTag("home-tab-practice").fetchSemanticsNodes().size
        )
    }

    @Test
    fun singleTapSwitchesTabsToTheRightContent() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // Default tab: the greeting and the journey content.
        composeRule.onNodeWithText("Master the world of payments, one lesson at a time.")
            .assertIsDisplayed()
        // One tap: Courses tab content, at the top.
        composeRule.onNodeWithTag("home-tab-courses").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Guided reading sessions").assertIsDisplayed()
        // One tap: Practice tab content, with the practice rows
        // reachable by scrolling the tab.
        composeRule.onNodeWithTag("home-tab-practice").performClick()
        composeRule.waitForIdle()
        assertTrue(scrollUntilSubstringVisible("Interview Questions"))
        assertTrue(scrollUntilSubstringVisible("Guides"))
        // One tap back: the greeting is in view again.
        composeRule.onNodeWithTag("home-tab-journey").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Master the world of payments, one lesson at a time.")
            .assertIsDisplayed()
    }

    @Test
    fun homeRendersNoCapabilityList() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // Every tab: the moved list must appear nowhere.
        listOf("home-tab-journey", "home-tab-courses", "home-tab-practice").forEach { tag ->
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitForIdle()
            assertTrue(
                composeRule.onAllNodesWithText("Everything inside")
                    .fetchSemanticsNodes().isEmpty()
            )
            assertTrue(
                composeRule.onAllNodesWithText("Your account and settings")
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun tabSwitchLandsAtTheTop() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(4) {
            list.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        // The greeting has scrolled away on the Journey tab.
        assertTrue(
            composeRule.onAllNodesWithText("Master the world of payments, one lesson at a time.")
                .fetchSemanticsNodes().isEmpty()
        )
        // Switching tabs lands at the new tab's top: its first
        // header is in view without scrolling.
        composeRule.onNodeWithTag("home-tab-courses").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Guided reading sessions").assertIsDisplayed()
    }

    @Test
    fun homeReselectReturnsToTopOfJourneyTab() {
        val tick = mutableStateOf(0)
        composeRule.setContent {
            PfTheme {
                HomeScreen(onNavigate = {}, onOpenDrawer = {}, reselectTick = tick.value)
            }
        }
        settle()
        composeRule.onNodeWithTag("home-tab-courses").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Guided reading sessions").assertIsDisplayed()
        composeRule.runOnIdle { tick.value++ }
        composeRule.waitForIdle()
        Thread.sleep(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Master the world of payments, one lesson at a time.")
            .assertIsDisplayed()
    }

    @Test
    fun drawerContainsAllThirteenEntriesAndEachNavigatesOnOneTap() {
        val navigated = mutableListOf<String>()
        composeRule.setContent {
            PfTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppDrawer(
                        currentRoute = Routes.HOME,
                        onSelect = {},
                        onStartTour = {},
                        onContinueLearning = {},
                        onNavigateRoute = { route -> navigated.add(route) }
                    )
                }
            }
        }
        settle()
        // The section heading sits at the bottom of the drawer
        // list: scroll it into view first.
        assertTrue(scrollIndexUntilVisible(hasText("Everything inside")))
        composeRule.onNodeWithText("Everything inside").assertIsDisplayed()
        val items = drawerCapabilityItems()
        for (item in items) {
            val matcher = hasContentDescription("${item.title}. ${item.subtitle}") and hasClickAction()
            assertTrue(
                "drawer entry ${item.title} never composed",
                scrollIndexUntilVisible(matcher)
            )
            composeRule.onNode(matcher).performClick()
            composeRule.waitForIdle()
            assertTrue(
                "drawer entry ${item.title} did not navigate to ${item.route}",
                navigated.contains(item.route)
            )
        }
        assertEquals(items.map { it.route }, navigated)
    }

    @Test
    fun continueLearningCardsShowThumbnailTitleBarAndPercentage() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            PfTheme {
                ContinueLearningCards(
                    courses = listOf(
                        InProgressCourse(
                            partNumber = 1,
                            title = "Course 1: Foundations",
                            done = 3,
                            total = 6,
                            nextLessonId = "ch1s4",
                            thumbnailPath = "assets/thumbs/course-part-1.svg",
                            isStartCard = false,
                            isJourneyNext = true
                        ),
                        InProgressCourse(
                            partNumber = 2,
                            title = "Course 2: Clearing",
                            done = 0,
                            total = 2,
                            nextLessonId = "ch3s1",
                            thumbnailPath = "assets/thumbs/course-part-2.svg",
                            isStartCard = true,
                            isJourneyNext = false
                        )
                    ),
                    lastCompletedTitle = "What a payment is",
                    onOpenLesson = { opened.add(it) }
                )
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(800L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue learning").assertIsDisplayed()
        composeRule.onNodeWithText("Course 1: Foundations").assertIsDisplayed()
        composeRule.onNodeWithText("Next in your journey").assertIsDisplayed()
        composeRule.onNodeWithText("50% complete. 3 of 6 lessons complete.").assertIsDisplayed()
        composeRule.onNodeWithText("Course 2: Clearing").assertIsDisplayed()
        composeRule.onNodeWithText("Start this course").assertIsDisplayed()
        // The start card carries no zero counter.
        assertTrue(
            composeRule.onAllNodes(hasText("0 of", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
        // Artwork renders for the cards, not the fallback tile.
        assertTrue(
            composeRule.onAllNodesWithTag("thumbnailArtwork", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        // One tap on a card resumes at its next lesson.
        composeRule.onNodeWithText("Course 1: Foundations").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("ch1s4"), opened)
    }

    @Test
    fun freshUserSeesStartCardNeverZeroCounter() {
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        assertTrue(scrollUntilTextVisible("Continue learning"))
        assertTrue(
            composeRule.onAllNodes(hasText("0 of", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun homeWithProgressShowsCourseCards() {
        runBlocking {
            AppStore.putString("pf.completedLessons.v1", "[\"ch1s1\", \"ch1s2\"]")
        }
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        // The fake catalogue puts ch1s1/ch1s2 in Course 1, so a
        // course card with a percentage renders on the Journey tab.
        // The journeys group sits above the progress blocks, so
        // the percentage card is below the fold on first paint:
        // scroll to it before asserting it renders.
        assertTrue(
            composeRule.onAllNodes(hasText("0 of", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(scrollUntilSubstringVisible("% complete."))
        assertTrue(
            composeRule.onAllNodes(hasText("% complete.", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun profileAvatarShowsPhotoWhenLoaderSucceeds() {
        var clicked = false
        composeRule.setContent {
            PfTheme {
                ProfileAvatar(
                    avatarUrl = "https://example.com/me.jpg",
                    onClick = { clicked = true },
                    imageLoader = {
                        // Bitmap.createBitmap is shadowed under
                        // Robolectric; the ImageBitmap
                        // constructor hits an unshadowed
                        // overload and throws.
                        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                )
            }
        }
        // The picture arrives asynchronously: wait for the
        // photo tag, then prove it is tappable.
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("profile-avatar-photo")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("profile-avatar-photo").assertExists()
        composeRule.onNodeWithTag("profile-avatar-photo").performClick()
        assertTrue(clicked)
    }

    @Test
    fun profileAvatarShowsFallbackWithoutAPicture() {
        composeRule.setContent {
            PfTheme {
                ProfileAvatar(
                    avatarUrl = null,
                    onClick = {},
                    imageLoader = { error("must not load without a URL") }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile-avatar-fallback").assertIsDisplayed()
    }

    @Test
    fun profileAvatarShowsFallbackWhenLoadingReturnsNull() {
        composeRule.setContent {
            PfTheme {
                ProfileAvatar(
                    avatarUrl = "https://example.com/broken.jpg",
                    onClick = {},
                    imageLoader = { null }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile-avatar-fallback").assertIsDisplayed()
    }

    @Test
    fun profileAvatarShowsFallbackWhenLoadingThrows() {
        composeRule.setContent {
            PfTheme {
                ProfileAvatar(
                    avatarUrl = "https://example.com/broken.jpg",
                    onClick = {},
                    imageLoader = { throw RuntimeException("network down") }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile-avatar-fallback").assertIsDisplayed()
    }

    @Test
    fun greetingBlockShowsFallbackForGuest() {
        // Home as a guest: no account, no picture, the default
        // icon renders in the greeting header.
        composeRule.setContent { PfTheme { HomeScreen(onNavigate = {}, onOpenDrawer = {}) } }
        settle()
        composeRule.onNodeWithTag("profile-avatar-fallback").assertIsDisplayed()
    }
}
