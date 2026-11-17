// OnboardingFlowScreen.kt
// First-run introduction, shown once per install, after sign-in:
// four short explanation pages in a swipe-only pager. Page 1 says
// why the app exists, page 2 who it is for, page 3 what it can
// do, and page 4 what is free. Each page is a few headed sections,
// not one wall of text, in very simple terms. There are no Next
// or Back buttons: the learner swipes between pages, taps the
// dots to jump, or taps Skip, which is always visible in the top
// bar. A looping swipe hint shows on every page except the last.
// The last page keeps Get started and the two content actions.
// Choosing Get started marks onboarding complete on this device
// and saves no profile; the two content actions do the same and
// then open the content. The question steps and the diagnostic
// are gone: they dropped new users before they saw a single
// lesson. OnboardingModels and DiagnosticAssessment remain in
// the tree, unused, so the profile shape stays available if a
// later design needs it.

package com.paymentstraininghub.app.features.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

/** One onboarding page: a heading, a short intro, and headed sections. */
internal data class OnboardingPage(
    val heading: String,
    val intro: String,
    val sections: List<Pair<String, String>>
)

/**
 * The four first-run pages, in order: why the app exists, who it
 * is for, what it can do, and what is free. Copy stays in very
 * simple terms, with no questions, and counts come from
 * ContentManifest. The About screen reuses the first three pages
 * verbatim, so the two places can never drift apart.
 */
internal val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        heading = "Why this app",
        intro = "Payments work is hard to learn from scattered articles and manuals. " +
            "This app teaches it in order, in plain language, the way banks actually process payments.",
        sections = listOf(
            "${ContentManifest.courseCount} courses, ${ContentManifest.lessonCount} short lessons" to
                "Each lesson takes a few minutes of plain reading or listening. " +
                "Lessons run in order, across ${ContentManifest.chapterCount} chapters, like a course book.",
            "${ContentManifest.situationCount} real payment situations" to
                "Real problems from real payment work, worked step by step the way a senior analyst handles them. " +
                "You learn what goes wrong and what to do next.",
            "Practice, not just reading" to
                "${ContentManifest.simulatorCount} simulators, labs, tests and revision, so you can try the work yourself " +
                "instead of only reading about it."
        )
    ),
    OnboardingPage(
        heading = "Who it is for",
        intro = "For everyone who works with payments, or wants to.",
        sections = listOf(
            "Business analysts and product owners" to
                "Learn the messages, flows and rules behind the requirements you write, " +
                "so nothing in a payment flow is a mystery.",
            "Developers and testers" to
                "See how payments move end to end, so integrations and test cases match how banks really work.",
            "Operations teams and new joiners" to
                "Start from the foundations. No payments background is needed, and every idea is explained in plain terms."
        )
    ),
    OnboardingPage(
        heading = "What it can do",
        intro = "Everything in one place, ready when you are.",
        sections = listOf(
            "Lessons with audio" to
                "Every lesson has audio. Listen while you travel, read when you sit down. " +
                "The app always knows where you left off.",
            "Interview preparation" to
                "${ContentManifest.interviewQuestionCount} interview questions with model answers, " +
                "${ContentManifest.faqCount} questions and answers, and " +
                "${ContentManifest.supplementalLessonCount} extra lessons. A free Payment Case arrives every week.",
            "Your progress follows you" to
                "You are signed in, so finished lessons, streaks and certificates are saved to your account " +
                "and follow you to any device.",
            "Search anything" to
                "Search finds any lesson, guide, case or question in seconds."
        )
    ),
    OnboardingPage(
        heading = "What is free",
        intro = "You can learn a lot before you pay anything.",
        sections = listOf(
            "First lesson of every chapter" to
                "The first lesson of every chapter is free, so you can try each part of the course.",
            "Free samples everywhere" to
                "Every section has free samples, and there is a free Payment Case every week.",
            "Free to browse" to
                "Outlines and the full catalogue are free to browse, so you always know what is inside.",
            "One year when you buy" to
                "A course you buy unlocks for one year. The Integrated Course unlocks everything."
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OnboardingFlowScreen(
    onFinish: () -> Unit,
    onFinishAndNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstLessonId by remember { mutableStateOf<String?>(null) }
    val freeCaseId = ContentRepository.freeSampleSituationIds.firstOrNull()
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == ONBOARDING_PAGES.lastIndex

    // The first lesson of the full course, for the direct action.
    // A failed load leaves that button disabled; Get started
    // always works.
    LaunchedEffect(Unit) {
        firstLessonId = runCatching {
            val chapters = ContentRepository.chapters()
            ReadingOrder.bookSequence(
                chapters.associate { it.id to it.lessonIds }
            ).firstOrNull()
        }.getOrNull()
    }

    fun finish() {
        OnboardingStore.markSkipped(context)
        onFinish()
    }

    fun finishAndNavigate(route: String) {
        OnboardingStore.markSkipped(context)
        onFinishAndNavigate(route)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Welcome", color = PfColors.navy) },
            actions = {
                TextButton(onClick = { finish() }) {
                    Text("Skip", color = PfColors.royal)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.background
            )
        )
        // Swipe-only pager: the content padding and page spacing
        // leave a peek of the next page at the edge, so the swipe
        // is discoverable without any Next button.
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 32.dp),
            pageSpacing = 16.dp,
            pageSize = PageSize.Fill,
            modifier = Modifier
                .weight(1f)
                .testTag("onboarding-pager")
        ) { pageIndex ->
            val page = ONBOARDING_PAGES[pageIndex]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Keyed by page: every page opens at the very
                    // top, never at another page's scroll position.
                    .verticalScroll(rememberTopScrollState(pageIndex))
                    .padding(vertical = PfSpacing.lg)
                    .testTag("onboarding-page-$pageIndex"),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.md)
            ) {
                Text(
                    "Learn payments, properly.",
                    style = PfTextStyles.screenTitle,
                    color = PfColors.navy
                )
                Text(
                    "Payments Training Hub",
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                Text(
                    page.heading,
                    style = PfTextStyles.sectionTitle,
                    color = PfColors.royal
                )
                Text(
                    page.intro,
                    style = PfTextStyles.body,
                    color = PfColors.secondaryText
                )
                page.sections.forEach { (title, body) ->
                    PfCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.sm)) {
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
        // Fixed bottom area: the swipe hint, the tappable dots,
        // and, on the last page only, Get started and the two
        // content actions. Nothing here scrolls with the page.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PfSpacing.lg,
                    vertical = PfSpacing.md
                ),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isLastPage) {
                SwipeHintGif()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-progress"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ONBOARDING_PAGES.indices.forEach { index ->
                    // A generous tap target with the dot centred
                    // inside it: the dots jump straight to a page.
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                            .testTag("onboarding-dot-$index"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (index == currentPage) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == currentPage) PfColors.royal else PfColors.border
                                )
                        )
                    }
                }
            }
            if (isLastPage) {
                PfPrimaryButton(
                    title = "Get started",
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth()
                )
                PfSecondaryButton(
                    title = "Start with the first lesson",
                    onClick = {
                        val id = firstLessonId
                        if (id != null) finishAndNavigate(Routes.lesson(id))
                    },
                    enabled = firstLessonId != null,
                    modifier = Modifier.fillMaxWidth()
                )
                PfSecondaryButton(
                    title = "Explore a free Payment Case",
                    onClick = {
                        val id = freeCaseId
                        if (id != null) finishAndNavigate(Routes.situation(id))
                    },
                    enabled = freeCaseId != null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
