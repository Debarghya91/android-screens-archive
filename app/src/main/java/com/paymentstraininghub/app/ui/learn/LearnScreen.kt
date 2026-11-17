// LearnScreen.kt
// Learn tab, ported from CoursesView.swift. Three sections under
// a segmented control:
// - Full Course (default): the whole course in reading order:
//   ten courses, chapters numbered 1 to 99 in the reading-order
//   map, lessons one after another. Chapters outside the main
//   reading path sit in a separated section at the end, with no
//   number in the full course order.
// - Paths: guided role paths filterable by level, then the eight
//   guided sessions kept only as named reading paths.
// - Courses: the catalogue as a table, grouped by the course
//   that unlocks each chapter, plus the guides/labs/tests
//   library.
// Shared tools below all sections: Interview Questions,
// Practice Mode, and Practice experiences.
// Switching sections returns to the top. Counts and progress are
// real, from the catalogue and completed-lesson data. The first
// lesson of each chapter is a free sample. Locked lessons open
// Checkout for the owning product. No course starts expanded:
// the course list is a navigation pane, and tapping a course
// opens its chapter list. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ActivePathStore
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.Agendas
import com.paymentstraininghub.app.data.ContentManifest
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.GuidedSession
import com.paymentstraininghub.app.data.GuidedSessions
import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.data.PathBookmarkStore
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.Chapter
import com.paymentstraininghub.app.data.model.CourseProduct
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.SearchIndex
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.DrawerMenuButton
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfChip
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfErrorStateView
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.AgendaBlock
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopLazyListState
import kotlinx.coroutines.launch

/** One course of the full course, with its chapters in reading order. */
internal data class BookPartRow(
    val number: Int,
    val title: String,
    val chapters: List<BookChapterRow>
)

/** One chapter in the full course: its catalogue data and display number. */
internal data class BookChapterRow(
    val chapter: Chapter,
    val bookNumber: Int
)

internal enum class PathLevelFilter(val label: String) {
    ALL("All"),
    FOUNDATION("Foundation"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced")
}

/** Counts come from the loaded search index only; while loading, no number rather than a made-up one. */
internal fun countSubtitle(count: Int?, singular: String, plural: String, suffix: String): String {
    return if (count != null) {
        "$count ${if (count == 1) singular else plural} $suffix"
    } else {
        "${plural.replaceFirstChar { it.uppercase() }} $suffix"
    }
}

internal fun pathIcon(systemImage: String): ImageVector {
    return when (systemImage) {
        "book.fill" -> Icons.Filled.MenuBook
        "doc.text.fill" -> Icons.Filled.Description
        "bolt.fill" -> Icons.Filled.Bolt
        "creditcard.fill" -> Icons.Filled.CreditCard
        "gearshape.fill" -> Icons.Filled.Settings
        "person.fill.questionmark" -> Icons.Filled.QuestionAnswer
        else -> Icons.Filled.MenuBook
    }
}

@Composable
internal fun GuidedPathCard(
    path: LearningPath,
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onClick: () -> Unit
) {
    val lessons = remember(path, entries) { path.lessons(entries) }
    val (completedCount, totalCount) = remember(path, entries, completedIds) {
        path.progress(entries, completedIds)
    }
    val progress = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount.toFloat()
    PfCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            PfIconTile(icon = pathIcon(path.systemImage), size = 48.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(path.title, style = PfTypography.cardTitle, color = PfColors.navy)
                PfBodyText(text = path.description, color = PfColors.secondaryText)
                Text(
                    "${path.level} • ${lessons.size} lessons",
                    style = PfTypography.callout,
                    color = PfColors.secondaryText
                )
                // Progress rule: no zero counter and no empty bar
                // before the first lesson is done. A fresh user
                // sees the starter line instead.
                if (completedCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
                    ) {
                        PfProgressBar(progress = progress, height = 6.dp, modifier = Modifier.weight(1f))
                        Text(
                            "${Math.round(progress * 100)}%",
                            style = PfTypography.captionBold,
                            color = PfColors.navy
                        )
                    }
                    Text(
                        "$completedCount of $totalCount lessons complete",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                } else {
                    Text(
                        "Not started yet. Open the journey to begin.",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                }
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this journey",
                    tint = if (isBookmarked) PfColors.gold else PfColors.secondaryText
                )
            }
        }
    }
}

@Composable
internal fun SessionCard(
    session: GuidedSession,
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    onClick: () -> Unit
) {
    val lessons = remember(session, entries) { session.lessons(entries) }
    val completedCount = lessons.count { it.id in completedIds }
    val progress = if (lessons.isEmpty()) 0f else completedCount.toFloat() / lessons.size.toFloat()
    PfCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(PfColors.royal.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${session.id}", style = PfTypography.cardTitle, color = PfColors.royal)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(session.title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text("Session ${session.id}", style = PfTypography.caption, color = PfColors.secondaryText)
                Text(session.description, style = PfTypography.callout, color = PfColors.secondaryText)
                // Progress rule: no zero counter and no empty bar
                // before the first lesson is done.
                if (completedCount > 0) {
                    Text(
                        "${lessons.size} lessons, $completedCount complete",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                    PfProgressBar(progress = progress, height = 6.dp)
                } else {
                    Text(
                        "${lessons.size} lessons. Not started yet.",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
    }
}

/**
 * The catalogue table header: navy background, white bold labels,
 * aligned with the fixed column widths of [CatalogueTableRow].
 * Colours are explicit PfColors, so the pairings are identical in
 * light and dark mode (the pf-painted surfaces stay light in both).
 */
@Composable
internal fun CatalogueTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(PfColors.navy)
            .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        Box(modifier = Modifier.size(width = 56.dp, height = 32.dp))
        Text(
            "Course",
            style = PfTypography.captionBold,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            "Ch.",
            style = PfTypography.captionBold,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp)
        )
        Text(
            "Lessons",
            style = PfTypography.captionBold,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp)
        )
        Text(
            "Status",
            style = PfTypography.captionBold,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp)
        )
    }
}

/**
 * One catalogue table row: the course thumbnail beside the course
 * name, chapter and lesson counts, and the Owned or Locked state.
 * Tapping opens the course agenda and its chapter rows. Zebra
 * striping alternates the card and page backgrounds; all text
 * uses explicit PfColors, legible in both modes.
 */
@Composable
internal fun CatalogueTableRow(
    product: CourseProduct,
    entries: List<SearchEntry>,
    chapterList: List<Chapter>,
    completedIds: Set<String>,
    agendas: Agendas,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChapterClick: (Int) -> Unit
) {
    val bundleChapterNumbers = remember(product) {
        product.chapterIds.mapNotNull { ContentRepository.chapterNumberFromId(it) }.sorted()
    }
    val bundleLessons = remember(product, entries) {
        val wanted = bundleChapterNumbers.toSet()
        val seen = mutableSetOf<String>()
        entries.filter { entry ->
            entry.isLesson &&
                ContentRepository.chapterNumberFromId(entry.id)?.let { it in wanted } == true &&
                seen.add(entry.id)
        }
    }
    val completedCount = bundleLessons.count { it.id in completedIds }
    val isOwned = BillingManager.isProductUnlocked(product.productId)
    val agendaEntry = agendas.course(product.id)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PfColors.border)
            .background(PfColors.card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
                .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThumbnailTile(
                thumbnailPath = agendaEntry?.thumbnail,
                modifier = Modifier.size(width = 56.dp, height = 32.dp),
                fallback = { PfIconTile(icon = Icons.Filled.Folder, size = 32.dp) }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
            ) {
                Text(
                    product.name,
                    style = PfTypography.cardTitle,
                    color = PfColors.navy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Progress rule: a completed count renders only
                // once at least one lesson is done; no zero
                // counter, no empty bar.
                if (completedCount > 0) {
                    Text(
                        "$completedCount of ${bundleLessons.size} lessons complete",
                        style = PfTypography.caption,
                        color = PfColors.secondaryText
                    )
                    PfProgressBar(
                        progress = completedCount.toFloat() / bundleLessons.size.toFloat(),
                        height = 6.dp
                    )
                }
            }
            Text(
                "${bundleChapterNumbers.size}",
                style = PfTypography.body,
                color = PfColors.navy,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp)
            )
            Text(
                "${bundleLessons.size}",
                style = PfTypography.body,
                color = PfColors.navy,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp)
            )
            Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                PfPill(
                    text = if (isOwned) "Owned" else "Locked",
                    background = if (isOwned) PfColors.success.copy(alpha = 0.12f) else PfColors.goldSoft,
                    // Darkened text colours on the tints keep both
                    // pills above WCAG AA (5.60:1 and 5.39:1).
                    foreground = if (isOwned) PfColors.successTextDark else PfColors.goldTextDark
                )
            }
            Icon(
                if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = PfColors.secondaryText
            )
        }
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PfColors.background)
                    .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.small),
                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
            ) {
                val agenda = agendaEntry?.agenda
                if (!agenda.isNullOrBlank()) {
                    AgendaBlock(agenda = agenda, label = "Why take this course")
                }
                // Why, and who it is for, from the catalogue.
                Text(
                    product.tagline,
                    style = PfTypography.body,
                    color = PfColors.navy
                )
                Text(
                    product.audience,
                    style = PfTypography.callout,
                    color = PfColors.secondaryText
                )
                bundleChapterNumbers.forEach { number ->
                    val chapter = chapterList.firstOrNull { it.number == number }
                    val chapterLessons = entries.filter {
                        it.isLesson && ContentRepository.chapterNumberFromId(it.id) == number
                    }
                    val chapterCompleted = chapterLessons.count { it.id in completedIds }
                    TableChapterRow(
                        displayNumber = ReadingOrder.bookNumberFor("ch$number"),
                        chapterLabel = ReadingOrder.chapterLabel("ch$number"),
                        title = chapter?.title ?: ReadingOrder.chapterLabel("ch$number"),
                        lessonCount = chapterLessons.size,
                        completedCount = chapterCompleted,
                        locked = !BillingManager.isChapterUnlocked("ch$number"),
                        thumbnailPath = agendas.chapter("ch$number")?.thumbnail,
                        onClick = { onChapterClick(number) }
                    )
                }
            }
        }
    }
}

/** One chapter row inside an expanded catalogue table row. */
@Composable
internal fun TableChapterRow(
    displayNumber: Int?,
    chapterLabel: String,
    title: String,
    lessonCount: Int,
    completedCount: Int,
    locked: Boolean,
    thumbnailPath: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PfColors.card)
            .border(1.dp, PfColors.border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(PfSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbnailTile(
            thumbnailPath = thumbnailPath,
            modifier = Modifier.size(width = 48.dp, height = 27.dp),
            fallback = {
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .clip(CircleShape)
                        .background(PfColors.royal.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (displayNumber != null) {
                        Text("$displayNumber", style = PfTypography.captionBold, color = PfColors.royal)
                    } else {
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = null,
                            tint = PfColors.royal,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
        ) {
            Text(chapterLabel, style = PfTypography.caption, color = PfColors.secondaryText)
            Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
            Text(
                if (completedCount > 0) {
                    "$lessonCount lessons, $completedCount complete"
                } else {
                    "$lessonCount lessons. Not started yet."
                },
                style = PfTypography.callout,
                color = PfColors.secondaryText
            )
        }
        if (locked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
            ) {
                Text("Premium", style = PfTypography.captionBold, color = PfColors.goldText)
                Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = PfColors.secondaryText)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
    }
}

@Composable
internal fun PartGroupCard(
    part: BookPartRow,
    entries: List<SearchEntry>,
    completedIds: Set<String>,
    agendas: Agendas,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChapterClick: (Int) -> Unit
) {
    val partNumbers = remember(part) { part.chapters.map { it.chapter.number }.toSet() }
    val partLessons = remember(part, entries) {
        entries.filter { entry ->
            entry.isLesson &&
                ContentRepository.chapterNumberFromId(entry.id)?.let { it in partNumbers } == true
        }
    }
    val completedCount = partLessons.count { it.id in completedIds }
    val progress = if (partLessons.isEmpty()) 0f else completedCount.toFloat() / partLessons.size.toFloat()
    val chapterWord = if (part.chapters.size == 1) "chapter" else "chapters"
    val agendaEntry = agendas.course("part-${part.number}")
    val product = remember(part.number) {
        ProductCatalogue.products.firstOrNull { it.id == "part-${part.number}" }
    }

    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpanded() }
            ) {
                ThumbnailTile(
                    thumbnailPath = agendaEntry?.thumbnail,
                    modifier = Modifier.size(width = 64.dp, height = 36.dp),
                    fallback = { PfIconTile(icon = Icons.Filled.MenuBook, size = 44.dp) }
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Text(
                        "Course ${part.number}: ${part.title}",
                        style = PfTypography.cardTitle,
                        color = PfColors.navy
                    )
                    Text(
                        "${part.chapters.size} $chapterWord, ${partLessons.size} lessons",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                    // Why take it, at a glance: the catalogue tagline.
                    product?.let {
                        Text(
                            it.tagline,
                            style = PfTypography.callout,
                            color = PfColors.navy
                        )
                    }
                    // Progress rule: a completed count and bar
                    // render only once at least one lesson is done.
                    if (completedCount > 0) {
                        Text(
                            "$completedCount of ${partLessons.size} lessons complete",
                            style = PfTypography.callout,
                            color = PfColors.secondaryText
                        )
                    } else {
                        Text(
                            "Not started yet.",
                            style = PfTypography.callout,
                            color = PfColors.secondaryText
                        )
                    }
                }
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = PfColors.secondaryText
                    )
                }
            }
            if (completedCount > 0) {
                PfProgressBar(progress = progress, height = 6.dp)
            }
            if (isExpanded) {
                val agenda = agendaEntry?.agenda
                if (!agenda.isNullOrBlank()) {
                    AgendaBlock(agenda = agenda, label = "Why take this course")
                }
                // Who it is for, from the catalogue.
                product?.let {
                    Text(
                        it.audience,
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                }
                part.chapters.forEach { row ->
                    val number = row.chapter.number
                    val chapterLessons = entries.filter {
                        it.isLesson && ContentRepository.chapterNumberFromId(it.id) == number
                    }
                    val chapterCompleted = chapterLessons.count { it.id in completedIds }
                    val chapterProgress = if (chapterLessons.isEmpty()) 0f
                    else chapterCompleted.toFloat() / chapterLessons.size.toFloat()
                    ChapterRow(
                        displayNumber = row.bookNumber,
                        chapterLabel = "Chapter ${row.bookNumber}",
                        title = row.chapter.title,
                        lessonCount = chapterLessons.size,
                        completedCount = chapterCompleted,
                        progress = chapterProgress,
                        locked = !BillingManager.isChapterUnlocked(row.chapter.id),
                        thumbnailPath = agendas.chapter(row.chapter.id)?.thumbnail,
                        onClick = { onChapterClick(number) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChapterRow(
    displayNumber: Int?,
    chapterLabel: String,
    title: String,
    lessonCount: Int,
    completedCount: Int,
    progress: Float,
    locked: Boolean,
    thumbnailPath: String? = null,
    onClick: () -> Unit
) {
    PfCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 64.dp, height = 36.dp),
                fallback = {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(PfColors.royal.copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (displayNumber != null) {
                            Text("$displayNumber", style = PfTypography.cardTitle, color = PfColors.royal)
                        } else {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = null,
                                tint = PfColors.royal,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(chapterLabel, style = PfTypography.caption, color = PfColors.secondaryText)
                Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
                // Progress rule: no zero counter and no empty bar.
                if (completedCount > 0) {
                    Text(
                        "$lessonCount lessons, $completedCount complete",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                    PfProgressBar(progress = progress, height = 6.dp)
                } else {
                    Text(
                        "$lessonCount lessons. Not started yet.",
                        style = PfTypography.callout,
                        color = PfColors.secondaryText
                    )
                }
            }
            if (locked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)
                ) {
                    Text("Premium", style = PfTypography.captionBold, color = PfColors.goldText)
                    Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = PfColors.secondaryText)
                }
            }
        }
    }
}

@Composable
internal fun LibraryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    PfCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            PfIconTile(icon = icon, size = 44.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(subtitle, style = PfTypography.callout, color = PfColors.secondaryText)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
        }
    }
}

@Composable
internal fun ToolCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    PfCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            PfIconTile(icon = icon, size = 44.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(subtitle, style = PfTypography.callout, color = PfColors.secondaryText)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PfColors.secondaryText)
        }
    }
}
