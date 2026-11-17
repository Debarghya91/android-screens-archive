// HomeBrowseSections.kt
// Payments Training Hub Android - the merged Home's shared pieces:
// the one next-lesson resolution (the chosen journey agrees with
// the Continue learning block, so Home never names two different
// next lessons), the course parts builder, the complete
// capabilities list, and the section composables Home composes
// from. Pure helpers are unit-tested; no em dashes in any
// user-facing text.

package com.paymentstraininghub.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.Agendas
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitLearningEvent
import com.paymentstraininghub.app.ui.learn.BookChapterRow
import com.paymentstraininghub.app.ui.learn.BookPartRow
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.ThumbnailTile

/** One resolved next lesson: where Home sends the learner on one tap. */
data class NextLesson(
    val lessonId: String,
    val title: String,
    val chapterLabel: String,
    val chapterTitle: String,
    /** True when the lesson comes from the chosen journey's order. */
    val fromJourney: Boolean,
    val journeyTitle: String?,
    /** True when every lesson in reading order is complete. */
    val allComplete: Boolean
)

/**
 * The one next-lesson rule for Home. When a journey is chosen, the
 * next lesson is the journey's first incomplete lesson in journey
 * order; when the journey is complete, or no journey is chosen,
 * it is the first incomplete lesson in full-course reading order.
 * When everything is complete, it is the first lesson again, as a
 * review. Home therefore names exactly one next lesson, and it
 * always agrees with the chosen journey.
 */
fun resolveNextLesson(
    orderedLessonIds: List<String>,
    orderedChapters: List<Chapter>,
    entryTitles: Map<String, String>,
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    activePath: LearningPath?
): NextLesson? {
    val firstLessonId = orderedLessonIds.firstOrNull() ?: return null
    val journeyNext = activePath?.firstIncompleteLesson(entries, completedIds)
    val readingNextId = orderedLessonIds.firstOrNull { it !in completedIds }
    val allComplete = readingNextId == null && orderedLessonIds.isNotEmpty()
    val chosenId = journeyNext?.id ?: readingNextId ?: firstLessonId
    val chapter = orderedChapters.firstOrNull { chosenId in it.lessonIds }
    return NextLesson(
        lessonId = chosenId,
        title = entryTitles[chosenId]
            ?: chapter?.let { ReadingOrder.chapterLessonLabel(chosenId, it.title) }
            ?: chosenId,
        chapterLabel = chapter?.let { ReadingOrder.chapterLabel(it.id) } ?: "",
        chapterTitle = chapter?.title ?: "",
        fromJourney = journeyNext != null,
        journeyTitle = if (journeyNext != null) activePath?.title else null,
        allComplete = allComplete
    )
}

/** The most recently completed lesson, from the habit events, or null when nothing is complete. */
fun lastCompletedLessonId(events: List<HabitLearningEvent>): String? {
    return events
        .filter { it.type == "lessonCompleted" && it.lessonID != null }
        .maxByOrNull { it.timestampMillis }
        ?.lessonID
}

/**
 * The 10 course parts, built from the catalogue: each part's
 * chapters in reading order, with display numbers. Chapters in no
 * part (the held-out chapter) appear in no part, as in the
 * catalogue table. Pure and unit-tested.
 */
internal fun buildBookParts(chapters: List<Chapter>): List<BookPartRow> {
    val byId = chapters.associateBy { it.id }
    return (1..10).mapNotNull { number ->
        val product = ProductCatalogue.products.firstOrNull { it.id == "part-$number" }
            ?: return@mapNotNull null
        val rows = product.chapterIds.mapNotNull { id ->
            val chapter = byId[id] ?: return@mapNotNull null
            BookChapterRow(chapter, ReadingOrder.bookNumberFor(id) ?: Int.MAX_VALUE)
        }.sortedBy { it.bookNumber }
        BookPartRow(
            number = number,
            title = product.name.substringAfter(": ").ifEmpty { product.name },
            chapters = rows
        )
    }
}

/** One capability: what the product can do, and where it lives. */
data class CapabilityItem(
    val title: String,
    val subtitle: String,
    val route: String,
    val icon: ImageVector
)

/** A group of capabilities under one heading. */
data class CapabilityGroup(val title: String, val items: List<CapabilityItem>)

/**
 * Every capability in the app, grouped, each linked to the route
 * where the learner reaches it. Every route is a real destination
 * in the navigation graph; nothing is listed that does not exist.
 * Verified by CapabilityRoutesTest against the route table.
 */
val CAPABILITY_GROUPS: List<CapabilityGroup> = listOf(
    CapabilityGroup(
        "Learn",
        listOf(
            CapabilityItem(
                "Lessons and courses",
                "10 courses, 100 chapters, 881 narrated lessons",
                Routes.homeSection(Routes.HOME_SECTION_COURSES),
                Icons.Filled.MenuBook
            ),
            CapabilityItem(
                "Learning journeys",
                "Set orders of lessons for one goal",
                Routes.homeSection(Routes.HOME_SECTION_JOURNEYS),
                Icons.Filled.Map
            ),
            CapabilityItem(
                "Course catalogue",
                "Every course with its chapters, at a glance",
                Routes.homeSection(Routes.HOME_SECTION_CATALOGUE),
                Icons.Filled.Book
            ),
            CapabilityItem(
                "Audio lessons",
                "Every lesson has narration. Play, change speed, set a sleep timer, download to listen offline",
                Routes.homeSection(Routes.HOME_SECTION_CONTINUE),
                Icons.Filled.Headphones
            ),
            CapabilityItem(
                "Search the course",
                "Any lesson, guide, case or question",
                Routes.SEARCH,
                Icons.Filled.Search
            ),
            CapabilityItem(
                "Guides",
                "Short guides on payments topics",
                Routes.GUIDES,
                Icons.Filled.Description
            ),
            CapabilityItem(
                "Tests",
                "Chapter tests with scores",
                Routes.TESTS,
                Icons.Filled.Quiz
            ),
            CapabilityItem(
                "Revision",
                "Revisit what you have learned",
                Routes.REVISION,
                Icons.Filled.PlayArrow
            )
        )
    ),
    CapabilityGroup(
        "Practise",
        listOf(
            CapabilityItem(
                "Payment Cases",
                "Real payment problems, worked step by step",
                Routes.CASES,
                Icons.Filled.Work
            ),
            CapabilityItem(
                "Practice Mode",
                "Decide what to do next for real situations",
                Routes.PRACTICE,
                Icons.Filled.QuestionAnswer
            ),
            CapabilityItem(
                "Payment Simulator",
                "Walk a payment through every stage",
                Routes.SIMULATOR,
                Icons.Filled.PlayArrow
            ),
            CapabilityItem(
                "ISO 20022 Explorer",
                "Messages, fields and mappings",
                Routes.ISO,
                Icons.Filled.CreditCard
            ),
            CapabilityItem(
                "Exception Lab",
                "Investigate real payment exceptions",
                Routes.LAB,
                Icons.Filled.Science
            ),
            CapabilityItem(
                "BA/PO Studio",
                "Practical exercises for BAs and POs",
                Routes.STUDIO,
                Icons.Filled.People
            ),
            CapabilityItem(
                "Daily Challenge",
                "One real case, 3 to 5 minutes",
                Routes.DAILY_CHALLENGE,
                Icons.Filled.CalendarToday
            ),
            CapabilityItem(
                "Weekly case",
                "A new free case every week",
                Routes.WEEKLY_CASE,
                Icons.Filled.CalendarToday
            ),
            CapabilityItem(
                "Community Challenge",
                "Take the challenge with other learners",
                Routes.CHALLENGE,
                Icons.Filled.People
            ),
            CapabilityItem(
                "Labs",
                "Hands-on labs for payments work",
                Routes.LABS,
                Icons.Filled.Science
            )
        )
    ),
    CapabilityGroup(
        "Prepare and track",
        listOf(
            CapabilityItem(
                "Interview Questions",
                "500 questions with model answers",
                Routes.INTERVIEW,
                Icons.Filled.QuestionAnswer
            ),
            CapabilityItem(
                "My Progress",
                "Streak, weekly goal and certificates",
                Routes.PROGRESS,
                Icons.Filled.BarChart
            ),
            CapabilityItem(
                "Certificates",
                "Your journey and course certificates",
                Routes.CERTIFICATES,
                Icons.Filled.EmojiEvents
            ),
            CapabilityItem(
                "Learning habit",
                "Your weekly goal and learning streak",
                Routes.HABIT,
                Icons.Filled.CalendarToday
            ),
            CapabilityItem(
                "Courses and purchases",
                "Buy a course, renew, see what you own",
                Routes.CHECKOUT,
                Icons.Filled.ShoppingBag
            )
        )
    ),
    CapabilityGroup(
        "Your account and settings",
        listOf(
            CapabilityItem(
                "Profile",
                "Your account and sign-in",
                Routes.PROFILE,
                Icons.Filled.Person
            ),
            CapabilityItem(
                "Notifications",
                "Reminders and course news",
                Routes.NOTIFICATIONS,
                Icons.Filled.Notifications
            ),
            CapabilityItem(
                "Help and Support",
                "FAQs and support contact",
                Routes.HELP,
                Icons.Filled.HelpOutline
            ),
            CapabilityItem(
                "Appearance",
                "Dark mode and larger text",
                Routes.APPEARANCE,
                Icons.Filled.Settings
            ),
            CapabilityItem(
                "About",
                "About Payments Training Hub and the curriculum",
                Routes.ABOUT,
                Icons.Filled.Info
            ),
            CapabilityItem(
                "Diagnostics",
                "Crash reports and device info",
                Routes.DIAGNOSTICS,
                Icons.Filled.BugReport
            ),
            CapabilityItem(
                "Privacy Policy",
                "How your data is handled",
                Routes.PRIVACY,
                Icons.Filled.Lock
            ),
            CapabilityItem(
                "Terms of Service",
                "The terms of using the app",
                Routes.TERMS,
                Icons.Filled.Description
            )
        )
    )
)

/** Every capability route, for the link-landing test. */
fun allCapabilityRoutes(): List<String> = CAPABILITY_GROUPS.flatMap { group ->
    group.items.map { it.route }
}

/**
 * The 13 capability entries that live in the side drawer: the
 * whole "Prepare and track" group plus the whole "Your account
 * and settings" group, in group order. The homepage list is
 * gone; these entries moved here, nothing was deleted.
 */
internal fun drawerCapabilityItems(): List<CapabilityItem> =
    CAPABILITY_GROUPS
        .filter { it.title == "Prepare and track" || it.title == "Your account and settings" }
        .flatMap { it.items }

/**
 * The hands-on rows for the Practice tab: the whole "Practise"
 * group plus Revision. Guides, Tests and Interview Questions
 * render above these as library rows with their counts.
 */
internal fun practiceTabItems(): List<CapabilityItem> {
    val practise = CAPABILITY_GROUPS.firstOrNull { it.title == "Practise" }?.items.orEmpty()
    val revision = CAPABILITY_GROUPS.flatMap { it.items }
        .firstOrNull { it.route == Routes.REVISION }
    return practise + listOfNotNull(revision)
}

/** One in-progress course for the Continue learning cards. */
data class InProgressCourse(
    val partNumber: Int,
    val title: String,
    val done: Int,
    val total: Int,
    val nextLessonId: String,
    val thumbnailPath: String?,
    /** True when this course holds the one next lesson and the learner has not started it: a start card, never a zero counter. */
    val isStartCard: Boolean,
    /** True when the resume lesson is the chosen journey's next lesson. */
    val isJourneyNext: Boolean
)

/**
 * The Continue learning cards, Coursera-style: one card per
 * course the learner has started but not finished, plus a
 * start card for the course holding the one next lesson when
 * the learner has not started that course yet. Completed
 * courses show no card. The course holding the one next
 * lesson leads, so the prominent resume always agrees with
 * the chosen journey ([resolveNextLesson]); every other card
 * resumes at its own course's first incomplete lesson in
 * reading order. Pure and unit-tested.
 */
internal fun buildInProgressCourses(
    bookParts: List<BookPartRow>,
    completedIds: Set<String>,
    nextLesson: NextLesson?,
    agendas: Agendas
): List<InProgressCourse> {
    val courses = bookParts.mapNotNull { part ->
        val lessonIds = part.chapters.flatMap { it.chapter.lessonIds }
        val ordered = ReadingOrder.sortLessonIds(lessonIds)
        if (ordered.isEmpty()) return@mapNotNull null
        val done = ordered.count { it in completedIds }
        if (done >= ordered.size) return@mapNotNull null
        val holdsNext = nextLesson != null && nextLesson.lessonId in ordered
        if (done == 0 && !holdsNext) return@mapNotNull null
        val resumeId = nextLesson?.lessonId?.takeIf { holdsNext }
            ?: ordered.firstOrNull { it !in completedIds }
            ?: return@mapNotNull null
        InProgressCourse(
            partNumber = part.number,
            title = part.title,
            done = done,
            total = ordered.size,
            nextLessonId = resumeId,
            thumbnailPath = agendas.course("part-${part.number}")?.thumbnail,
            isStartCard = done == 0,
            isJourneyNext = holdsNext && nextLesson?.fromJourney == true
        )
    }
    return courses.sortedWith(
        compareByDescending<InProgressCourse> { it.nextLessonId == nextLesson?.lessonId }
            .thenBy { it.partNumber }
    )
}

/**
 * Continue learning as in-progress course cards: thumbnail,
 * title, progress bar with percentage, one tap to resume at
 * the next lesson. A start card (no bar, no percentage, no
 * zero counter) leads when the next lesson sits in a course
 * the learner has not started. Fresh users never see this
 * section: Home shows the start card instead.
 */
@Composable
internal fun ContinueLearningCards(
    courses: List<InProgressCourse>,
    lastCompletedTitle: String?,
    onOpenLesson: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        Text(
            "Continue learning",
            style = MaterialTheme.typography.titleMedium,
            color = PfColors.navy
        )
        if (lastCompletedTitle != null) {
            Text(
                "Last finished: $lastCompletedTitle",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
        courses.forEach { course ->
            Card(
                onClick = { onOpenLesson(course.nextLessonId) },
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PfSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    ThumbnailTile(
                        thumbnailPath = course.thumbnailPath,
                        modifier = Modifier.size(width = 72.dp, height = 40.dp),
                        fallback = { PfIconTile(icon = Icons.Filled.MenuBook, size = 40.dp) }
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
                    ) {
                        Text(
                            course.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = PfColors.navy
                        )
                        if (course.isJourneyNext) {
                            Text(
                                "Next in your journey",
                                style = MaterialTheme.typography.labelSmall,
                                color = PfColors.royal
                            )
                        }
                        if (course.isStartCard) {
                            Text(
                                "Your next lesson is in this course.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Text(
                                "Start this course",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.royal
                            )
                        } else {
                            val percent = (course.done * 100) / course.total
                            PfProgressBar(
                                progress = course.done.toFloat() / course.total.toFloat(),
                                height = 8.dp
                            )
                            Text(
                                "$percent% complete. ${course.done} of ${course.total} lessons complete.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PfColors.secondaryText
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeSectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = PfColors.navy)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
        }
    }
}

/**
 * The depth strip: the scale of the course as structure, not
 * clutter. Counts come from the manifest only.
 */
@Composable
internal fun DepthStrip() {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(
                "The full course",
                style = MaterialTheme.typography.titleMedium,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                "${ContentManifest.courseCount} courses, ${ContentManifest.chapterCount} chapters, " +
                    "${ContentManifest.lessonCount} narrated lessons.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.navy
            )
            Text(
                "From what a payment is, through rails, messages and settlement, " +
                    "to exceptions, architecture and the BA at work.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                "${ContentManifest.situationCount} Payment Situations, " +
                    "${ContentManifest.interviewQuestionCount} interview questions, " +
                    "${ContentManifest.faqCount} FAQs.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
    }
}

/**
 * The one Continue learning block: where the learner left off,
 * plus the next lesson, in one tap. The next lesson agrees with
 * the chosen journey (see [resolveNextLesson]); there is no
 * second recommendation anywhere on Home.
 */
@Composable
internal fun ContinueLearningCard(
    next: NextLesson,
    lastCompletedTitle: String?,
    completedCount: Int,
    onOpenLesson: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(
                "Continue learning",
                style = MaterialTheme.typography.titleMedium,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                "Where you left off",
                style = MaterialTheme.typography.labelSmall,
                color = PfColors.royal
            )
            Text(
                when {
                    lastCompletedTitle != null -> lastCompletedTitle
                    completedCount > 0 -> "$completedCount lessons complete."
                    else -> "You have not started yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                "Your next lesson",
                style = MaterialTheme.typography.labelSmall,
                color = PfColors.royal
            )
            Text(
                next.title,
                style = MaterialTheme.typography.titleLarge,
                color = PfColors.navy
            )
            if (next.chapterLabel.isNotBlank()) {
                Text(
                    "${next.chapterLabel}: ${next.chapterTitle}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            if (next.fromJourney && next.journeyTitle != null) {
                Text(
                    "Next in your journey: ${next.journeyTitle}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.navy
                )
            } else if (next.allComplete) {
                Text(
                    "You have completed every lesson. Start again from the first lesson to review.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.navy
                )
            } else if (completedCount == 0) {
                Text(
                    "Start here: the first lesson of the course. It is a free sample, open without any purchase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.navy
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            Button(
                onClick = { onOpenLesson(next.lessonId) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PfColors.royal,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    when {
                        next.allComplete -> "Review this lesson"
                        completedCount == 0 -> "Start this lesson"
                        else -> "Continue this lesson"
                    }
                )
            }
        }
    }
}

/**
 * The in-context journey explainer: what a journey is, in plain
 * words, where the learner chooses one. The choice itself happens
 * on each journey's detail screen ("Choose your journey" before a
 * choice, "Change journey" after one).
 */
@Composable
internal fun JourneyExplainerCard(hasChosenJourney: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(
                "What is a learning journey?",
                style = MaterialTheme.typography.titleMedium,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                "A journey is a set order of lessons for one goal, such as becoming a payments BA. " +
                    "Choose one and Home will show its next lesson in Continue learning, " +
                    "so you always know where to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                if (hasChosenJourney) {
                    "You have chosen a journey. To change it, open another journey below and choose it. " +
                        "Your completed lessons are never lost."
                } else {
                    "You have not chosen a journey yet. Open any journey below to see its lessons, " +
                        "then choose it. Until then, Home follows the full course in order."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
        }
    }
}

/** The chosen journey, shown as "Your journey" with its next step. */
@Composable
internal fun YourJourneyCard(
    journeyTitle: String,
    journeyDescription: String,
    done: Int,
    total: Int,
    onOpenJourney: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(
                "Your journey",
                style = MaterialTheme.typography.titleMedium,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
            Text(
                journeyTitle,
                style = MaterialTheme.typography.titleLarge,
                color = PfColors.navy
            )
            Text(
                journeyDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            if (done > 0 && total > 0) {
                Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                Text(
                    "$done of $total lessons complete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.secondaryText
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.small))
            OutlinedButton(onClick = onOpenJourney) { Text("Open your journey") }
        }
    }
}

/** One capability row: title, subtitle, chevron, one tap to its route. */
@Composable
internal fun CapabilityRow(item: CapabilityItem, onNavigate: (String) -> Unit) {
    Card(
        onClick = { onNavigate(item.route) },
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PfSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = PfColors.royal,
                modifier = Modifier.padding(end = PfSpacing.small)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = PfColors.navy)
                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = PfColors.secondaryText)
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = PfColors.secondaryText
            )
        }
    }
}
