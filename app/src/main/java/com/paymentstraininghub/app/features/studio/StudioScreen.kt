// BA and Product Owner studio, teaching-first, ported from
// BAProductStudioView.swift. Every lesson introduces one item,
// explains what it is and why it matters, then teaches how to
// write its output, with one worked example. Practice comes
// after the teaching, inside each lesson. The 34 key concepts,
// 30 tasks, 50 techniques, 6 competency groups with 29
// individual competencies, 5 perspectives and 2 orientations
// are all here, in order, plus 5 career guides. Practice Studio
// exercises stay as they were: grounded in real lessons and
// situations, with drafts, section checklists and rubric
// review. Drafts stay on this device. Gating mirrors LessonView
// and SituationsView: a situation exercise opens when its
// source is a free sample, this week's free case, or Practice
// Experiences is unlocked. Teaching lessons and career guides
// are gated by BA/PO Studio: the first 2 of each are free.
// No em dashes.
package com.paymentstraininghub.app.features.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.billing.FreeSampleRules
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseSelector
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.setSaver
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfChip
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfGoldButton
import com.paymentstraininghub.app.ui.theme.PfOutlineButton
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(onNavigate: ((String) -> Unit)? = null) {
    var situationsUnlocked by remember { mutableStateOf(false) }
    var gatingChecked by remember { mutableStateOf(false) }
    var weeklyCaseId by remember { mutableStateOf<String?>(null) }
    var lessons by remember { mutableStateOf<List<StudioLesson>>(emptyList()) }
    var guides by remember { mutableStateOf<List<StudioCareerGuide>>(emptyList()) }
    var teachingLoaded by remember { mutableStateOf(false) }
    // Collected so the Studio gate refreshes when entitlements
    // load or change, and when the premium gate resolves.
    @Suppress("UNUSED_VARIABLE")
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Admin flips land on restore without touching any
    // entitlement flow, so the flag is a gate input too.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val studioUnlocked = remember(ownedIds, serverIds, gateState, isAdmin) {
        runCatching { BillingManager.isStudioUnlocked() }.getOrDefault(false)
    }
    // Free samples: the first 2 teaching units and the first 2
    // career guides, in catalogue order. Everything else needs
    // BA/PO Studio (or the Integrated Course).
    val freeLessonIds = remember(lessons) { FreeSampleRules.sampleIds(lessons.map { it.id }).toSet() }
    val freeGuideIds = remember(guides) { FreeSampleRules.sampleIds(guides.map { it.id }).toSet() }
    val isStudioLessonOpen: (StudioLesson) -> Boolean = { lesson ->
        studioUnlocked || lesson.id in freeLessonIds
    }
    val isStudioGuideOpen: (StudioCareerGuide) -> Boolean = { guide ->
        studioUnlocked || guide.id in freeGuideIds
    }
    // The situations gate is read in its own keyed effect, not
    // in the one-shot load below: a one-shot read froze the
    // guest-time value for the screen's lifetime.
    LaunchedEffect(ownedIds, serverIds, gateState, isAdmin) {
        situationsUnlocked = runCatching {
            BillingManager.isSituationsUnlocked()
        }.getOrDefault(false)
        gatingChecked = true
    }
    LaunchedEffect(Unit) {
        // Weekly list only. Null leaves only the free samples.
        val weekly = ContentRepository.weeklyCasesOrNull()
        weeklyCaseId = if (weekly.isNullOrEmpty()) {
            null
        } else {
            WeeklyFreeCaseSelector.situationId(weekly.map { it.id })
        }
        lessons = StudioTeachingStore.lessons()
        guides = StudioTeachingStore.guides()
        teachingLoaded = true
    }
    val isLessonUnlocked: (String) -> Boolean = { lessonId ->
        runCatching {
            BillingManager.isLessonUnlocked(lessonId, StudioGating.chapterIdForLesson(lessonId))
        }.getOrDefault(false)
    }

    val drafts by StudioDraftStore.drafts.collectAsState()
    var openExercise by remember { mutableStateOf<StudioExercise?>(null) }
    var openLesson by remember { mutableStateOf<StudioLesson?>(null) }
    var openArea by remember { mutableStateOf<StudioKnowledgeArea?>(null) }
    var openGuide by remember { mutableStateOf<StudioCareerGuide?>(null) }
    var selectedKind by remember { mutableStateOf<StudioLessonKind?>(null) }
    var selectedType by remember { mutableStateOf<StudioTaskType?>(null) }

    // System Back mirrors the on-screen Back through the detail
    // stack, innermost first: an exercise returns to its lesson,
    // a lesson to its area, and only the top level pops the
    // route. Without this, Back from a detail discarded the
    // learner's place and left the route.
    val anyDetailOpen = openExercise != null || openLesson != null ||
        openArea != null || openGuide != null
    BackHandler(enabled = anyDetailOpen) {
        when {
            openExercise != null -> openExercise = null
            openLesson != null -> openLesson = null
            openArea != null -> openArea = null
            openGuide != null -> openGuide = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
            .verticalScroll(rememberTopScrollState(openExercise?.id, openLesson?.id, openArea, openGuide?.id, selectedKind, selectedType))
            .padding(PfSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)
    ) {
        // Detail views take priority, innermost first: an exercise
        // opened from a lesson returns to the lesson on back, and
        // a lesson opened from an area returns to the area.
        val exercise = openExercise
        val lesson = openLesson
        val area = openArea
        val guide = openGuide
        when {
            exercise != null -> {
                StudioDetail(
                    exercise = exercise,
                    onBack = { openExercise = null },
                    onNavigate = onNavigate
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                return@Column
            }
            lesson != null -> {
                StudioLessonDetail(
                    lesson = lesson,
                    onBack = { openLesson = null },
                    onOpenExercise = { openExercise = it },
                    isExerciseOpen = { exercise ->
                        StudioGating.isExerciseOpen(
                            exercise,
                            isLessonUnlocked,
                            situationsUnlocked,
                            weeklyCaseId
                        )
                    },
                    onNavigate = onNavigate
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                return@Column
            }
            area != null -> {
                StudioKnowledgeAreaContent(
                    area = area,
                    lessons = lessons,
                    onBack = { openArea = null },
                    onOpenLesson = { openLesson = it },
                    isLessonOpen = isStudioLessonOpen,
                    onNavigate = onNavigate
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                return@Column
            }
            guide != null -> {
                StudioCareerGuideContent(
                    guide = guide,
                    onBack = { openGuide = null }
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                return@Column
            }
        }

        Text("BA and PO studio", style = PfTypography.screenTitle, color = PfColors.navy)

        // Header card: the teaching promise, the order, and the
        // disclaimer. Always shown, even while content loads.
        PfCard {
            Text(
                "Learn the craft, then practise it",
                style = PfTypography.cardTitle,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy
            )
            Text(
                "Every lesson introduces one item, explains what it is and why it matters, then teaches how to write its output, with one worked example.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            Text(
                "Work through Level 1, then Level 2, then Level 3. The 34 key concepts, 30 tasks, 50 techniques, 6 competency groups with 29 individual competencies, and 5 perspectives are all here, in order. Practice comes after the teaching, inside each lesson.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            Text(
                "Your drafts stay on this device.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            Text(
                "About this Studio",
                style = PfTypography.cardTitle,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy
            )
            Text(
                StudioDisclaimer.TEXT,
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }

        // My drafts: exercise drafts and lesson write-your-own
        // drafts, newest first, resolved against the loaded
        // content. Unresolvable drafts are skipped, never shown
        // with invented titles.
        val allDrafts = StudioDraftStore.allDrafts()
        if (allDrafts.isNotEmpty()) {
            Text("My drafts", style = PfTypography.sectionTitle, color = PfColors.navy)
            allDrafts.forEach { draft ->
                val draftLesson = lessons.firstOrNull { it.id == draft.exerciseID }
                val draftExercise = StudioExercises.all.firstOrNull { it.id == draft.exerciseID }
                when {
                    draftLesson != null -> {
                        // Gated like the lesson list: opening a
                        // saved draft must not bypass the Studio
                        // gate. Locked drafts show the lock and do
                        // not open.
                        val lessonOpen = isStudioLessonOpen(draftLesson)
                        PfCard(
                            onClick = if (lessonOpen) {
                                { openLesson = draftLesson }
                            } else {
                                null
                            }
                        ) {
                            Text(
                                "Write your own: ${draftLesson.outputName}",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                            Text(
                                "Lesson: ${draftLesson.title}. ${draft.wordCount} words. Updated ${formatDraftDate(draft.updatedAtMillis)}.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            if (!lessonOpen) {
                                PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                                Text(
                                    "Locked. Unlock BA/PO Studio to open this lesson.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                    draftExercise != null -> {
                        // Gated like the exercise list (Tester 2 A7):
                        // opening a saved draft must not bypass the
                        // exercise gate. Locked drafts show the lock
                        // and do not open.
                        val exerciseOpen = StudioGating.isExerciseOpen(
                            draftExercise,
                            isLessonUnlocked,
                            situationsUnlocked,
                            weeklyCaseId
                        )
                        PfCard(
                            onClick = if (exerciseOpen) {
                                { openExercise = draftExercise }
                            } else {
                                null
                            }
                        ) {
                            Text(draftExercise.title, style = PfTypography.cardTitle, color = PfColors.navy)
                            Text(
                                "${draft.wordCount} words. Updated ${formatDraftDate(draft.updatedAtMillis)}.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            if (!exerciseOpen) {
                                PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                                Text(
                                    when (draftExercise.sourceKind) {
                                        StudioExerciseSource.LESSON ->
                                            "Locked. This exercise is grounded in a lesson that needs unlocking."
                                        StudioExerciseSource.SITUATION ->
                                            "Locked. This exercise is grounded in a Payment Situation. Unlock Practice Experiences to open it."
                                    },
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Your drafts stay on this device.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }

        if (!teachingLoaded) {
            Text("Loading the teaching lessons...", style = PfTypography.body, color = PfColors.secondaryText)
        } else {
            // Learn in order.
            Text("Learn in order", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "Start at Level 1 and work down. Each lesson teaches before it asks you to practise.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            Text("Filter by kind", style = PfTypography.body, color = PfColors.secondaryText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                PfChip(
                    text = "All",
                    isSelected = selectedKind == null,
                    onClick = { selectedKind = null }
                )
                StudioLessonKind.entries.forEach { kind ->
                    PfChip(
                        text = kind.displayName,
                        isSelected = selectedKind == kind,
                        onClick = { selectedKind = kind }
                    )
                }
            }
            val visibleLessons = if (selectedKind == null) {
                lessons
            } else {
                lessons.filter { it.kindEnum == selectedKind }
            }
            StudioLevel.entries.forEach { level ->
                val levelLessons = visibleLessons.filter { it.levelEnum == level }
                Text(level.displayName, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(level.blurb, style = PfTypography.body, color = PfColors.secondaryText)
                Text(
                    "${levelLessons.size} lessons",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                levelLessons.forEach { item ->
                    val draft = drafts.firstOrNull { it.exerciseID == item.id }
                    val lessonOpen = isStudioLessonOpen(item)
                    PfCard(
                        onClick = if (lessonOpen) {
                            { openLesson = item }
                        } else {
                            null
                        }
                    ) {
                        Text(item.title, style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(item.subtitle, style = PfTypography.body, color = PfColors.secondaryText)
                        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            PfPill(text = item.kindEnum.displayName)
                            PfPill(text = item.levelEnum.shortName)
                            item.babokReference?.let { reference ->
                                Text(reference, style = PfTypography.body, color = PfColors.secondaryText)
                            }
                        }
                        if (draft != null) {
                            Text(
                                "Draft: ${draft.wordCount} words",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                        if (!lessonOpen) {
                            PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                            Text(
                                "Locked. The first 2 lessons are free. Unlock BA/PO Studio to open this lesson.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                            val navigate = onNavigate
                            if (navigate != null) {
                                PfSecondaryButton(
                                    title = "Go to checkout",
                                    onClick = { navigate(Routes.checkout(ProductCatalogue.studioProductId)) }
                                )
                            }
                        }
                    }
                }
            }

            // Browse by knowledge area.
            Text("Browse by knowledge area", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "The 30 tasks, grouped by their knowledge area. Each area opens with an introduction, then its tasks in order.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            StudioKnowledgeArea.entries.forEach { knowledgeArea ->
                val areaTasks = lessons.filter {
                    it.kindEnum == StudioLessonKind.TASK &&
                        it.knowledgeAreaEnum == knowledgeArea
                }
                PfCard(onClick = { openArea = knowledgeArea }) {
                    Text(knowledgeArea.displayName, style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(
                        "${areaTasks.size} tasks",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
            }

            // Career guides.
            Text("Career guides", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "Step by step routes into business analysis, product ownership, product management, and payments specialism, with the skills, tools, and portfolio evidence each route needs.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            guides.forEach { careerGuide ->
                val guideOpen = isStudioGuideOpen(careerGuide)
                PfCard(
                    onClick = if (guideOpen) {
                        { openGuide = careerGuide }
                    } else {
                        null
                    }
                ) {
                    Text(careerGuide.title, style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(careerGuide.audience, style = PfTypography.body, color = PfColors.secondaryText)
                    if (!guideOpen) {
                        PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                        Text(
                            "Locked. The first 2 career guides are free. Unlock BA/PO Studio to open this guide.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        val navigate = onNavigate
                        if (navigate != null) {
                            PfSecondaryButton(
                                title = "Go to checkout",
                                onClick = { navigate(Routes.checkout(ProductCatalogue.studioProductId)) }
                            )
                        }
                    }
                }
            }
        }

        // Practice Studio.
        Text("Practice Studio", style = PfTypography.sectionTitle, color = PfColors.navy)
        Text(
            "Practice exercises grounded in real lessons and situations. Work through the teaching first, then use these exercises to practise the deliverables. Gating is unchanged: exercises grounded in free samples stay open.",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
        if (!gatingChecked) {
            Text("Checking access...", style = PfTypography.body, color = PfColors.secondaryText)
        }
        Text("Filter by task type", style = PfTypography.body, color = PfColors.secondaryText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            PfChip(
                text = "All",
                isSelected = selectedType == null,
                onClick = { selectedType = null }
            )
            StudioTaskType.entries.forEach { type ->
                PfChip(
                    text = type.displayName,
                    isSelected = selectedType == type,
                    onClick = { selectedType = type }
                )
            }
        }
        val visibleExercises = if (selectedType == null) {
            StudioExercises.all
        } else {
            StudioExercises.all.filter { it.taskType == selectedType }
        }
        visibleExercises.forEach { exercise ->
            val open = StudioGating.isExerciseOpen(
                exercise,
                isLessonUnlocked,
                situationsUnlocked,
                weeklyCaseId
            )
            val draft = drafts.firstOrNull { it.exerciseID == exercise.id }
            PfCard(
                onClick = if (open) {
                    { openExercise = exercise }
                } else {
                    null
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(exercise.title, style = PfTypography.cardTitle, color = PfColors.navy)
                        Text(exercise.taskType.displayName, style = PfTypography.body, color = PfColors.secondaryText)
                        Text(exercise.sourceCitation, style = PfTypography.caption, color = PfColors.secondaryText)
                    }
                    if (!open) {
                        PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                    }
                }
                if (draft != null) {
                    Text(
                        "Draft saved: ${draft.wordCount} words.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
                if (!open) {
                    Text(
                        when (exercise.sourceKind) {
                            StudioExerciseSource.LESSON ->
                                "Locked. This exercise is grounded in a lesson that needs unlocking."
                            StudioExerciseSource.SITUATION ->
                                "Locked. This exercise is grounded in a Payment Situation. Unlock Practice Experiences to open it."
                        },
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    // The checkout button renders only when a
                    // navigator is supplied (AppNavGraph passes
                    // one); without it the lock note stands alone.
                    val navigate = onNavigate
                    if (navigate != null) {
                        val checkoutProductId = when (exercise.sourceKind) {
                            StudioExerciseSource.SITUATION -> ProductCatalogue.practiceProductId
                            StudioExerciseSource.LESSON -> ProductCatalogue.productForChapter(
                                StudioGating.chapterIdForLesson(exercise.sourceID)
                            )?.productId
                        }
                        PfSecondaryButton(
                            title = "Go to checkout",
                            onClick = {
                                if (checkoutProductId != null) {
                                    navigate(Routes.checkout(checkoutProductId))
                                } else {
                                    navigate(Routes.CHECKOUT)
                                }
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(PfSpacing.small))
    }
}

private fun formatDraftDate(millis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

@Composable
private fun StudioBulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        items.forEach { item ->
            Text(
                "• $item",
                style = PfTypography.body,
                color = PfColors.navy
            )
        }
    }
}

@Composable
private fun StudioLessonDetail(
    lesson: StudioLesson,
    onBack: () -> Unit,
    onOpenExercise: (StudioExercise) -> Unit,
    isExerciseOpen: (StudioExercise) -> Boolean,
    onNavigate: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)) {
        PfOutlineButton(title = "Back", onClick = onBack)
        lesson.knowledgeAreaEnum?.let { area ->
            Text(area.displayName, style = PfTypography.body, color = PfColors.secondaryText)
        }
        Text(lesson.title, style = PfTypography.screenTitle, color = PfColors.navy)
        Text(lesson.subtitle, style = PfTypography.body, color = PfColors.secondaryText)
        Text(
            "Read the teaching first. Practice follows below.",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
        Text("What this is, and why it matters", style = PfTypography.sectionTitle, color = PfColors.navy)
        PfBodyText(
            text = lesson.explanation.joinToString("\n\n"),
            style = PfTypography.body,
            color = PfColors.navy
        )
        if (lesson.keyPoints.isNotEmpty()) {
            Text("Key points", style = PfTypography.cardTitle, color = PfColors.navy)
            StudioBulletList(items = lesson.keyPoints)
        }
        Text(
            "How to write: ${lesson.outputName}",
            style = PfTypography.sectionTitle,
            color = PfColors.navy
        )
        Text("What goes in, in order", style = PfTypography.cardTitle, color = PfColors.navy)
        lesson.outputStructure.forEachIndexed { index, section ->
            Text(
                "${index + 1}. ${section.title}",
                style = PfTypography.body,
                fontWeight = FontWeight.Medium,
                color = PfColors.navy
            )
            Text(section.guidance, style = PfTypography.body, color = PfColors.navy)
        }
        PfBodyText(
            text = lesson.howToWrite.joinToString("\n\n"),
            style = PfTypography.body,
            color = PfColors.navy
        )
        Text(lesson.workedExampleTitle, style = PfTypography.sectionTitle, color = PfColors.navy)
        PfBodyText(
            text = lesson.workedExample.joinToString("\n\n"),
            style = PfTypography.body,
            color = PfColors.navy
        )
        Text(
            "Example only. This is a fictional illustration written for this lesson. Use its shape, not its words, and check every rule and figure against the owning team before using it for real work.",
            style = PfTypography.caption,
            color = PfColors.secondaryText
        )

        if (lesson.hasPractice) {
            Text(
                "Supporting practice, after the teaching",
                style = PfTypography.sectionTitle,
                color = PfColors.navy
            )
            Text(
                "The scenarios, questions, and exercises below practise what the teaching above explained. They are not part of the explanation. Work through them after reading the teaching.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }
        if (lesson.practiceScenarios.isNotEmpty()) {
            Text("Practice scenarios", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "Read each scenario, decide what you would do, then compare your thinking with the takeaway. The takeaway is shown with the scenario, so read the story first.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            lesson.practiceScenarios.forEach { scenario ->
                PfCard {
                    Text(scenario.title, style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(scenario.id, style = PfTypography.caption, color = PfColors.secondaryText)
                    PfBodyText(
                        text = scenario.story.joinToString("\n\n"),
                        style = PfTypography.body,
                        color = PfColors.navy
                    )
                    Text("Takeaway", style = PfTypography.body, fontWeight = FontWeight.Medium, color = PfColors.navy)
                    Text(scenario.takeaway, style = PfTypography.body, color = PfColors.navy)
                }
            }
        }
        if (lesson.practiceQuestions.isNotEmpty()) {
            Text("Practice questions", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "Answer each question in your own words before opening the model answer. The model answer is one good answer, not the only answer.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            lesson.practiceQuestions.forEach { item ->
                var expanded by remember(item.id) { mutableStateOf(false) }
                PfCard {
                    Column(
                        modifier = Modifier.clickable { expanded = !expanded }
                    ) {
                        Text(item.question, style = PfTypography.body, fontWeight = FontWeight.Medium, color = PfColors.navy)
                        if (expanded) {
                            if (item.answer.isEmpty()) {
                                Text(
                                    "This is a reflection prompt. There is no model answer. Discuss your answer with a colleague or mentor, or write it in the workspace below.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            } else {
                                Text("Model answer", style = PfTypography.body, fontWeight = FontWeight.Medium, color = PfColors.navy)
                                PfBodyText(
                                    text = item.answer.joinToString("\n\n"),
                                    style = PfTypography.body,
                                    color = PfColors.navy
                                )
                            }
                        }
                    }
                }
            }
        }
        val linkedExercises = lesson.linkedExerciseIDs.mapNotNull { id ->
            StudioExercises.all.firstOrNull { it.id == id }
        }
        if (linkedExercises.isNotEmpty()) {
            Text("Linked practice exercises", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "These Practice Studio exercises follow this teaching. Gating is unchanged: an exercise grounded in a locked chapter or situation stays locked until it unlocks.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            linkedExercises.forEach { exercise ->
                // Gated like the exercise list (Tester 2 A7): a
                // linked exercise grounded in locked content stays
                // locked here too; the card shows the lock and does
                // not open.
                val open = isExerciseOpen(exercise)
                PfCard(
                    onClick = if (open) {
                        { onOpenExercise(exercise) }
                    } else {
                        null
                    }
                ) {
                    Text(exercise.title, style = PfTypography.cardTitle, color = PfColors.navy)
                    Text(exercise.sourceCitation, style = PfTypography.body, color = PfColors.secondaryText)
                    if (!open) {
                        PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                        Text(
                            when (exercise.sourceKind) {
                                StudioExerciseSource.LESSON ->
                                    "Locked. This exercise is grounded in a lesson that needs unlocking."
                                StudioExerciseSource.SITUATION ->
                                    "Locked. This exercise is grounded in a Payment Situation. Unlock Practice Experiences to open it."
                            },
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
        }
        StudioLessonWorkspace(lesson = lesson, onNavigate = onNavigate)
    }
}

@Composable
private fun StudioLessonWorkspace(
    lesson: StudioLesson,
    onNavigate: ((String) -> Unit)?
) {
    val drafts by StudioDraftStore.drafts.collectAsState()
    val saved = drafts.firstOrNull { it.exerciseID == lesson.id }
    // The draft survives rotation (rememberSaveable) and is
    // persisted as the learner types (debounced autosave below
    // plus a save on leaving), so work is never lost to a
    // rotation, a navigation, or a missed Save tap. The explicit
    // Save button stays for an immediate save.
    var draftText by rememberSaveable(lesson.id) { mutableStateOf(saved?.text ?: "") }
    var completedSections by rememberSaveable(lesson.id, saver = setSaver()) {
        mutableStateOf(saved?.completedSections?.toSet() ?: emptySet())
    }
    var savedAt by remember(lesson.id) { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember(lesson.id) { mutableStateOf(false) }
    var lastSavedText by remember(lesson.id) { mutableStateOf(saved?.text ?: "") }
    var lastSavedSections by remember(lesson.id) {
        mutableStateOf(saved?.completedSections?.toSet() ?: emptySet())
    }

    LaunchedEffect(draftText, completedSections) {
        if (draftText == lastSavedText && completedSections == lastSavedSections) {
            return@LaunchedEffect
        }
        delay(1_000L)
        StudioDraftStore.saveDraft(lesson.id, draftText, completedSections.toList())
        lastSavedText = draftText
        lastSavedSections = completedSections
        savedAt = System.currentTimeMillis()
    }

    // A save on leaving covers edits made inside the debounce
    // window. The effect is keyed on the lesson, and onDispose
    // reads the state delegates captured when the effect was
    // created, so a lesson switch saves the outgoing lesson's
    // draft under its own id, never the incoming lesson's.
    DisposableEffect(lesson.id) {
        onDispose {
            if (draftText != lastSavedText || completedSections != lastSavedSections) {
                StudioDraftStore.saveDraft(lesson.id, draftText, completedSections.toList())
            }
        }
    }

    val wordCount = StudioRubricReview.wordCount(draftText)

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)) {
        Text(
            "Write your own: ${lesson.outputName}",
            style = PfTypography.sectionTitle,
            color = PfColors.navy
        )
        Text(
            "Write the output this lesson taught, following the structure above, in your own words. Use the checklist to tick each section as you cover it.",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
        OutlinedTextField(
            value = draftText,
            onValueChange = { draftText = it; savedAt = null },
            label = { Text("Write your draft here. Start with the first section in the checklist below, in your own words.") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            minLines = 8,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PfColors.royal,
                unfocusedBorderColor = PfColors.border,
                focusedLabelColor = PfColors.royal,
                unfocusedLabelColor = PfColors.secondaryText,
                focusedContainerColor = PfColors.card,
                unfocusedContainerColor = PfColors.card
            )
        )
        Text(
            "$wordCount words. ${lesson.wordCountGuidance}",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
        Text("Section checklist", style = PfTypography.cardTitle, color = PfColors.navy)
        lesson.outputStructure.forEach { section ->
            val done = section.title in completedSections
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = done,
                    onCheckedChange = { checked ->
                        completedSections = if (checked) {
                            completedSections + section.title
                        } else {
                            completedSections - section.title
                        }
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PfColors.royal,
                        uncheckedColor = PfColors.border,
                        checkmarkColor = PfColors.card
                    )
                )
                Column {
                    Text(section.title, style = PfTypography.body, color = PfColors.navy)
                    Text(section.guidance, style = PfTypography.body, color = PfColors.secondaryText)
                }
            }
        }
        PfPrimaryButton(
            title = "Save draft",
            onClick = {
                StudioDraftStore.saveDraft(lesson.id, draftText, completedSections.toList())
                lastSavedText = draftText
                lastSavedSections = completedSections
                savedAt = System.currentTimeMillis()
            }
        )
        savedAt?.let { at ->
            Text(
                "Draft saved ${formatDraftDate(at)}.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }
        if (saved != null) {
            TextButton(onClick = { showDeleteConfirm = true }) {
                Text("Delete draft", color = PfColors.error)
            }
        }
        Text(
            "Your drafts stay on this device.",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete draft") },
            text = { Text("The draft is only on this device. Deleting it cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        StudioDraftStore.deleteDraftFor(lesson.id)
                        draftText = ""
                        completedSections = emptySet()
                        savedAt = null
                        showDeleteConfirm = false
                    }
                ) { Text("Delete", color = PfColors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep draft") }
            }
        )
    }
}

@Composable
private fun StudioKnowledgeAreaContent(
    area: StudioKnowledgeArea,
    lessons: List<StudioLesson>,
    onBack: () -> Unit,
    onOpenLesson: (StudioLesson) -> Unit,
    isLessonOpen: (StudioLesson) -> Boolean,
    onNavigate: ((String) -> Unit)?
) {
    val tasks = lessons.filter {
        it.kindEnum == StudioLessonKind.TASK && it.knowledgeAreaEnum == area
    }
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)) {
        PfOutlineButton(title = "Back", onClick = onBack)
        Text(area.displayName, style = PfTypography.screenTitle, color = PfColors.navy)
        PfBodyText(
            text = area.introduction.joinToString("\n\n"),
            style = PfTypography.body,
            color = PfColors.navy
        )
        Text(
            "${tasks.size} tasks",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )
        tasks.forEach { task ->
            val taskOpen = isLessonOpen(task)
            PfCard(
                onClick = if (taskOpen) {
                    { onOpenLesson(task) }
                } else {
                    null
                }
            ) {
                Text(task.title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(task.subtitle, style = PfTypography.body, color = PfColors.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    PfPill(text = task.kindEnum.displayName)
                    PfPill(text = task.levelEnum.shortName)
                    task.babokReference?.let { reference ->
                        Text(reference, style = PfTypography.body, color = PfColors.secondaryText)
                    }
                }
                if (!taskOpen) {
                    PfPill(text = "Locked", background = PfColors.navySoft, foreground = PfColors.navy)
                    Text(
                        "Locked. Unlock BA/PO Studio to open this task.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    val navigate = onNavigate
                    if (navigate != null) {
                        PfSecondaryButton(
                            title = "Go to checkout",
                            onClick = { navigate(Routes.checkout(ProductCatalogue.studioProductId)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioCareerGuideContent(
    guide: StudioCareerGuide,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)) {
        PfOutlineButton(title = "Back", onClick = onBack)
        Text(guide.title, style = PfTypography.screenTitle, color = PfColors.navy)
        Text("For: ${guide.audience}", style = PfTypography.body, color = PfColors.secondaryText)
        Text(guide.timeframe, style = PfTypography.body, color = PfColors.secondaryText)
        PfBodyText(
            text = guide.description.joinToString("\n\n"),
            style = PfTypography.body,
            color = PfColors.navy
        )
        guide.sections.forEachIndexed { index, section ->
            Text(
                "${index + 1}. ${section.heading}",
                style = PfTypography.sectionTitle,
                color = PfColors.navy
            )
            PfBodyText(
                text = section.paragraphs.joinToString("\n\n"),
                style = PfTypography.body,
                color = PfColors.navy
            )
            if (section.steps.isNotEmpty()) {
                Text("Steps", style = PfTypography.cardTitle, color = PfColors.navy)
                section.steps.forEachIndexed { stepIndex, step ->
                    Text(
                        "Step ${stepIndex + 1}. ${step.title}",
                        style = PfTypography.body,
                        fontWeight = FontWeight.Medium,
                        color = PfColors.navy
                    )
                    Text(step.action, style = PfTypography.body, color = PfColors.navy)
                    Text(step.timeframe, style = PfTypography.body, color = PfColors.secondaryText)
                }
            }
            if (section.keyPoints.isNotEmpty()) {
                Text("Key points", style = PfTypography.cardTitle, color = PfColors.navy)
                StudioBulletList(items = section.keyPoints)
            }
        }
        if (guide.skills.isNotEmpty()) {
            Text("Skills to build", style = PfTypography.sectionTitle, color = PfColors.navy)
            StudioBulletList(items = guide.skills)
        }
        if (guide.tools.isNotEmpty()) {
            Text("Tools to learn", style = PfTypography.sectionTitle, color = PfColors.navy)
            StudioBulletList(items = guide.tools)
        }
        if (guide.certifications.isNotEmpty()) {
            Text("Certifications", style = PfTypography.sectionTitle, color = PfColors.navy)
            guide.certifications.forEach { certification ->
                Text(
                    "${certification.name} (${certification.body})",
                    style = PfTypography.body,
                    fontWeight = FontWeight.Medium,
                    color = PfColors.navy
                )
                Text(certification.note, style = PfTypography.body, color = PfColors.navy)
            }
            Text(
                StudioCareerGuide.CERTIFICATION_NOTE,
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }
        if (guide.portfolio.isNotEmpty()) {
            Text("Portfolio artifacts", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "Build proof you can do the work. Each artifact below is evidence a reviewer can check.",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
            StudioBulletList(items = guide.portfolio)
        }
        if (guide.mistakes.isNotEmpty()) {
            Text("Common mistakes", style = PfTypography.sectionTitle, color = PfColors.navy)
            StudioBulletList(items = guide.mistakes)
        }
    }
}

@Composable
private fun StudioDetail(
    exercise: StudioExercise,
    onBack: () -> Unit,
    onNavigate: ((String) -> Unit)?
) {
    val drafts by StudioDraftStore.drafts.collectAsState()
    val saved = drafts.firstOrNull { it.exerciseID == exercise.id }
    var text by remember(exercise.id) { mutableStateOf(saved?.text ?: "") }
    var ticked by remember(exercise.id) {
        mutableStateOf(saved?.completedSections?.toSet() ?: emptySet())
    }
    var showReview by remember(exercise.id) { mutableStateOf(false) }
    var savedNote by remember(exercise.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.mediumSmall)) {
        PfOutlineButton(title = "All exercises", onClick = onBack)
        Text(exercise.title, style = PfTypography.screenTitle, color = PfColors.navy)
        Text(exercise.taskType.displayName, style = PfTypography.body, color = PfColors.secondaryText)
        Text(exercise.sourceCitation, style = PfTypography.body, color = PfColors.secondaryText)
        Text("Task brief", style = PfTypography.sectionTitle, color = PfColors.navy)
        PfBodyText(text = exercise.brief, style = PfTypography.body, color = PfColors.navy)
        PfBodyText(text = exercise.groundingNote, style = PfTypography.body, color = PfColors.navy)
        PfBodyText(text = exercise.wordCountGuidance, style = PfTypography.body, color = PfColors.navy)

        Text("Worked example", style = PfTypography.sectionTitle, color = PfColors.navy)
        Text(
            "This worked example is for a different but related task. It shows structure only; " +
                "it is not course content and states no scheme rule.",
            style = PfTypography.caption,
            color = PfColors.secondaryText
        )
        PfCard {
            Text(exercise.workedExampleTitle, style = PfTypography.body, fontWeight = FontWeight.Medium, color = PfColors.navy)
            PfBodyText(text = exercise.workedExampleBody, style = PfTypography.body, color = PfColors.navy)
        }

        Text("Your draft", style = PfTypography.sectionTitle, color = PfColors.navy)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; savedNote = false },
            label = { Text("Write your ${exercise.taskType.displayName.lowercase()} here") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            minLines = 8,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PfColors.royal,
                unfocusedBorderColor = PfColors.border,
                focusedLabelColor = PfColors.royal,
                unfocusedLabelColor = PfColors.secondaryText,
                focusedContainerColor = PfColors.card,
                unfocusedContainerColor = PfColors.card
            )
        )
        Text(
            "${StudioRubricReview.wordCount(text)} words. ${exercise.wordCountGuidance}",
            style = PfTypography.body,
            color = PfColors.secondaryText
        )

        Text("Sections to cover", style = PfTypography.sectionTitle, color = PfColors.navy)
        exercise.requiredSections.forEach { section ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = section.title in ticked,
                    onCheckedChange = { checked ->
                        ticked = if (checked) ticked + section.title else ticked - section.title
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PfColors.royal,
                        uncheckedColor = PfColors.border,
                        checkmarkColor = PfColors.card
                    )
                )
                Text(section.title, style = PfTypography.body, color = PfColors.navy)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            PfPrimaryButton(
                title = "Save draft",
                onClick = {
                    StudioDraftStore.saveDraft(exercise.id, text, ticked.toList())
                    savedNote = true
                },
                modifier = Modifier.weight(1f)
            )
            PfOutlineButton(
                title = "Checklist review",
                onClick = { showReview = true },
                modifier = Modifier.weight(1f)
            )
        }
        if (savedNote) {
            Text("Draft saved on this device.", style = PfTypography.body, color = PfColors.secondaryText)
        }
        if (saved != null) {
            TextButton(
                onClick = {
                    StudioDraftStore.deleteDraftFor(exercise.id)
                    text = ""
                    ticked = emptySet()
                    showReview = false
                }
            ) { Text("Delete draft", color = PfColors.royal) }
        }

        if (showReview) {
            Text("Checklist review, not AI", style = PfTypography.sectionTitle, color = PfColors.navy)
            Text(
                "This review looks only for section words, word counts, list lines and Gherkin " +
                    "keywords. It never judges quality, and it never presents its result as an " +
                    "assessment of the writing.",
                style = PfTypography.caption,
                color = PfColors.secondaryText
            )
            StudioRubricReview.checks(exercise, text).forEach { check ->
                PfCard {
                    Text(
                        "${if (check.passed) "Pass" else "Not yet"}: ${check.label}",
                        style = PfTypography.body,
                        fontWeight = FontWeight.Medium,
                        color = PfColors.navy
                    )
                    Text(check.detail, style = PfTypography.body, color = PfColors.navy)
                }
            }
        }
    }
}
