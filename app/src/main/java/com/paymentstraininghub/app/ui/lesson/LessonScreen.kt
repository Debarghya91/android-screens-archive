package com.paymentstraininghub.app.ui.lesson

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaEntry
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LessonDetailSource
import com.paymentstraininghub.app.data.ProgressStore
import com.paymentstraininghub.app.data.ReadTime
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.readingText
import com.paymentstraininghub.app.data.model.LessonBeat
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.QuizQuestion
import com.paymentstraininghub.app.media.AudioPlayer
import com.paymentstraininghub.app.media.LessonDownloadStore
import com.paymentstraininghub.app.security.EventLog
import com.paymentstraininghub.app.ui.theme.PfBodyText
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.AgendaBlock
import com.paymentstraininghub.app.ui.theme.ThumbnailTile
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One narrated lesson: the flow first, narration off by default (Play
 * starts it), key moments synced to the audio, transcript, checklist,
 * sources, quiz and the next lesson. Mirrors the iOS LessonView, with
 * the iOS Content / Transcript / Resources tabs flattened into stacked
 * sections and the transcript made expandable.
 *
 * The diagram SVG is never rendered and no WebView is used: the beats
 * flow in [BeatsFlowContent] is the only visual.
 *
 * Gating: a lesson whose chapter is not unlocked and which is not the
 * free sample lesson shows the locked state: title, lock explanation
 * and a Buy button. No transcript, beats, audio or quiz is shown for a
 * locked lesson. The detail is still fetched so the real title can be
 * shown; only the title and chapter line are displayed while locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(
    lessonId: String,
    onNavigateToCheckout: (String) -> Unit = {},
    onNavigateToLesson: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onNavigateToCheckoutList: () -> Unit = {}
) {
    val context = LocalContext.current
    var reloadKey by remember(lessonId) { mutableStateOf(0) }
    var uiState by remember(lessonId) { mutableStateOf<LessonUiState>(LessonUiState.Loading) }
    var fromCache by remember(lessonId) { mutableStateOf(false) }
    var isSample by remember(lessonId) { mutableStateOf(false) }
    var isUnlocked by remember(lessonId) { mutableStateOf(false) }

    // Collected so a gate flip can unlock or lock this lesson
    // without a full reload (see the flip effect below). They are
    // deliberately NOT keys of the load effect: gate emissions
    // used to reset the screen to the spinner on every billing
    // refresh (Tester 2 P1).
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // The admin flag is a fourth gate input: it flips when the
    // session restore lands, after this screen has composed,
    // without touching any entitlement flow. Without it as a
    // flip-effect key, an admin with no purchases keeps the
    // guest-time Locked state until re-entry.
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val agendas by AgendaStore.agendas.collectAsState()

    // Agendas refresh on entry; failure is silent and leaves the
    // lesson working with no agenda or thumbnail.
    LaunchedEffect(lessonId) {
        AgendaStore.refreshIfNeeded(context.filesDir)
    }

    LaunchedEffect(lessonId, reloadKey) {
        uiState = LessonUiState.Loading
        fromCache = false
        try {
            val chapters = ContentRepository.chapters()
            val chapterId = chapterIdFromLessonId(lessonId)
            val sample = chapterId != null && ContentRepository.isSampleLesson(lessonId, chapters)
            val unlocked = chapterId == null || BillingManager.isLessonUnlocked(lessonId, chapterId)
            isSample = sample
            isUnlocked = unlocked

            val (loaded, source) = ContentRepository.lessonDetailWithSource(
                lessonId,
                context.filesDir
            )
            fromCache = source == LessonDetailSource.CACHED
            if (!unlocked && !sample) {
                // A stale autoplay handoff for a lesson that
                // loads Locked must not survive: the Content
                // branch that consumes it never runs, so the flag
                // would otherwise autoplay this lesson on a later
                // unlocked visit, unprompted.
                if (AudioPlayer.pendingAutoPlayLessonId == lessonId) {
                    AudioPlayer.pendingAutoPlayLessonId = null
                }
                val product = chapterId?.let { ProductCatalogue.productForChapter(it) }
                val productId = product?.productId
                val productName = product?.name
                val price = productId?.let { BillingManager.priceText(it) }
                uiState = LessonUiState.Locked(loaded, productId, productName, price)
            } else {
                val chapterLessons = chapters.firstOrNull { it.id == chapterId }?.lessonIds
                    ?: emptyList()
                val siblings = chapterLessons.filter { it != lessonId }
                // Learner-facing titles come from the merged index
                // (catalogue plus supplemental, which carry real
                // titles), never from the raw id: supplemental ids
                // like ch54s901-supp would otherwise render as the
                // misleading "Chapter 54, Lesson 901". A title that
                // is still unknown falls back to the lesson's
                // display number, never to a raw s-number.
                val titleById = ContentRepository.loadSearchIndex().entries
                    .associate { it.id to it.title }
                val displayTitle: (String) -> String = { id ->
                    titleById[id]?.takeIf { it.isNotBlank() }
                        ?: ReadingOrder.lessonLabel(id)
                        ?: "Lesson"
                }
                // Book reading order: mapped chapters navigate the
                // whole book (supplemental lessons follow their
                // chapter's catalogue lessons, matching iOS);
                // held-out chapters navigate within the chapter
                // only, outside the main reading path.
                val bookSequence = ReadingOrder.bookSequence(
                    chapters.associate { it.id to it.lessonIds }
                )
                val bookIndex = bookSequence.indexOf(lessonId)
                val chapterIndex = chapterLessons.indexOf(lessonId)
                val previousId: String?
                val nextId: String?
                val chapterPositionLine: String?
                val bookPositionLine: String?
                if (bookIndex >= 0) {
                    previousId = ReadingOrder.previousLesson(lessonId, bookSequence)
                    nextId = ReadingOrder.nextLesson(lessonId, bookSequence)
                    val book = chapterId?.let { ReadingOrder.bookNumberFor(it) }
                    val position = ReadingOrder.displayNumberFor(lessonId)
                    chapterPositionLine = when {
                        book != null && position != null ->
                            "Lesson $position of ${chapterLessons.size} in Chapter $book"
                        book != null ->
                            "Supplemental lesson in Chapter $book"
                        else -> null
                    }
                    val part = chapterId?.let { ReadingOrder.partFor(it) }
                    bookPositionLine = if (part != null) {
                        "Course ${part.first}: ${part.second} • Lesson ${bookIndex + 1} of ${bookSequence.size} in the full course"
                    } else {
                        "Lesson ${bookIndex + 1} of ${bookSequence.size} in the full course"
                    }
                } else {
                    previousId = if (chapterIndex > 0) chapterLessons[chapterIndex - 1] else null
                    nextId = if (chapterIndex >= 0 && chapterIndex + 1 < chapterLessons.size) {
                        chapterLessons[chapterIndex + 1]
                    } else {
                        null
                    }
                    val position = ReadingOrder.displayNumberFor(lessonId)
                    chapterPositionLine = if (position != null) {
                        "Lesson $position of ${chapterLessons.size} in this chapter"
                    } else {
                        "Supplemental lesson in this chapter"
                    }
                    bookPositionLine = null
                }
                uiState = LessonUiState.Content(
                    detail = loaded,
                    siblingIds = siblings,
                    siblingTitles = siblings.associateWith { displayTitle(it) },
                    previousLessonId = previousId,
                    previousLessonTitle = previousId?.let { displayTitle(it) },
                    nextLessonId = nextId,
                    nextLessonTitle = nextId?.let { displayTitle(it) },
                    chapterPositionLine = chapterPositionLine,
                    bookPositionLine = bookPositionLine
                )
            }
        } catch (e: Exception) {
            uiState = LessonUiState.Error(
                "This lesson could not be loaded. Check your connection and tap Retry."
            )
        }
    }

    // Gate flips only: when a lesson opened Locked becomes
    // unlocked, reload once; when a Content lesson becomes
    // locked, rebuild the Locked state from the cached detail
    // without refetching. Gate emissions never reset the screen
    // to the spinner (Tester 2 P1). isLessonUnlocked ignores the
    // gate, matching the load effect above. The admin flag is a
    // key too: it flips on restore, after composition.
    LaunchedEffect(ownedIds, serverIds, gateState, isAdmin) {
        val chapterId = chapterIdFromLessonId(lessonId) ?: return@LaunchedEffect
        val unlocked = BillingManager.isLessonUnlocked(lessonId, chapterId)
        if (unlocked == isUnlocked) return@LaunchedEffect
        isUnlocked = unlocked
        when (val state = uiState) {
            is LessonUiState.Locked -> if (unlocked) reloadKey++
            is LessonUiState.Content -> if (!unlocked && !isSample) {
                // The floating audio button only renders for a
                // Content state, so a lesson that flips to Locked
                // while its narration plays would keep playing
                // with no on-screen stop control. Stop this
                // lesson's playback as it locks, and drop any
                // pending autoplay handoff for it.
                if (AudioPlayer.currentLessonId.value == lessonId) {
                    AudioPlayer.stop()
                }
                if (AudioPlayer.pendingAutoPlayLessonId == lessonId) {
                    AudioPlayer.pendingAutoPlayLessonId = null
                }
                val product = ProductCatalogue.productForChapter(chapterId)
                val productId = product?.productId
                uiState = LessonUiState.Locked(
                    state.detail,
                    productId,
                    product?.name,
                    productId?.let { BillingManager.priceText(it) }
                )
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lesson", color = PfColors.navy) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PfColors.royal
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PfColors.background
                )
            )
        },
        // Floating play/pause, reachable while scrolled down. It
        // shares the lesson player state exactly (same flows, same
        // action as the PlayerCard button), and only shows for a
        // loaded lesson that has narration.
        floatingActionButton = {
            val contentState = uiState as? LessonUiState.Content
            val detail = contentState?.detail
            if (detail != null && detail.audioFileName.isNotBlank()) {
                LessonAudioFab(detail = detail)
            }
        },
        containerColor = PfColors.background
    ) { padding ->
        when (val state = uiState) {
            is LessonUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PfColors.royal)
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        Text(
                            text = "Loading lesson...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
            is LessonUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    LessonCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                            Text(
                                text = "Could not load the full lesson",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.error
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            SecondaryButton(text = "Retry") { reloadKey++ }
                        }
                    }
                }
            }
            is LessonUiState.Locked -> {
                LockedLessonContent(
                    detail = state.detail,
                    productId = state.productId,
                    productName = state.productName,
                    priceText = state.priceText,
                    agendaEntry = agendas.lesson(lessonId),
                    modifier = Modifier.padding(padding),
                    onNavigateToCheckout = onNavigateToCheckout,
                    onNavigateToCheckoutList = onNavigateToCheckoutList
                )
            }
            is LessonUiState.Content -> {
                LessonContent(
                    lessonId = lessonId,
                    detail = state.detail,
                    siblingIds = state.siblingIds,
                    siblingTitles = state.siblingTitles,
                    previousLessonId = state.previousLessonId,
                    previousLessonTitle = state.previousLessonTitle,
                    nextLessonId = state.nextLessonId,
                    nextLessonTitle = state.nextLessonTitle,
                    positionLines = listOfNotNull(
                        state.chapterPositionLine,
                        state.bookPositionLine
                    ),
                    fromCache = fromCache,
                    // The card names this lesson a free sample only
                    // when its chapter is locked: a sample inside
                    // an owned chapter, or for an admin, is
                    // simply part of the course. The old predicate
                    // (isSample && !isUnlocked) could never be
                    // true, because a sample is always unlocked.
                    isFreeSample = isSample &&
                        runCatching {
                            val sampleChapterId = chapterIdFromLessonId(lessonId)
                            sampleChapterId != null &&
                                !BillingManager.isChapterUnlocked(sampleChapterId)
                        }.getOrDefault(false),
                    agendaEntry = agendas.lesson(lessonId),
                    modifier = Modifier.padding(padding),
                    onRetryLoad = { reloadKey++ },
                    onNavigateToLesson = onNavigateToLesson
                )
            }
        }
    }
}

private sealed interface LessonUiState {
    data object Loading : LessonUiState
    data class Error(val message: String) : LessonUiState
    data class Locked(
        val detail: LessonDetail,
        val productId: String?,
        val productName: String?,
        val priceText: String?
    ) : LessonUiState

    data class Content(
        val detail: LessonDetail,
        val siblingIds: List<String>,
        val siblingTitles: Map<String, String>,
        val previousLessonId: String?,
        val previousLessonTitle: String?,
        val nextLessonId: String?,
        val nextLessonTitle: String?,
        val chapterPositionLine: String?,
        val bookPositionLine: String?
    ) : LessonUiState
}

/** The chapter id for a lesson id, for example "ch5" for "ch5s3". Null when the id has no chapter. */
private fun chapterIdFromLessonId(lessonId: String): String? {
    val match = Regex("^ch(\\d+)s\\d+").find(lessonId) ?: return null
    return "ch" + match.groupValues[1]
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private fun formatTimePadded(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    return "${(totalSeconds / 60).toString().padStart(2, '0')}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

// MARK: - Locked lesson

@Composable
private fun LockedLessonContent(
    detail: LessonDetail,
    productId: String?,
    productName: String?,
    priceText: String?,
    agendaEntry: AgendaEntry? = null,
    modifier: Modifier = Modifier,
    onNavigateToCheckout: (String) -> Unit,
    onNavigateToCheckoutList: () -> Unit
) {
    // priceText is "Price unavailable" until Play Billing loads the
    // product. Never show that string as a purchase price: without a
    // loaded price the copy names the bundle and the button carries
    // no price.
    val priceAvailable = priceText != null && priceText != "Price unavailable"
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState(detail.id))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LessonHeader(detail = detail, isComplete = false, agendaEntry = agendaEntry)
        LessonCard {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = PfColors.royal,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Locked lesson",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PfColors.navy
                        )
                        Text(
                            text = "Buy the course to unlock this chapter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                }
                if (productName != null && priceAvailable) {
                    Text(
                        text = "This lesson is part of $productName. Buy it for $priceText to unlock this chapter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                } else if (productName != null) {
                    Text(
                        text = "This lesson is part of $productName. Buy it to unlock this chapter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                } else {
                    Text(
                        text = "This lesson is part of a course. Buy the course to unlock it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                }
                if (productId != null) {
                    Button(
                        onClick = { onNavigateToCheckout(productId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PfColors.gold,
                            contentColor = PfColors.navy
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (productName != null && priceAvailable) {
                                "Buy $productName for $priceText"
                            } else if (productName != null) {
                                "Buy $productName"
                            } else {
                                "Buy to unlock"
                            }
                        )
                    }
                } else {
                    // No owning bundle: the lesson still offers a
                    // way forward instead of a dead end (Tester 2
                    // A14/G18).
                    Button(
                        onClick = onNavigateToCheckoutList,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PfColors.gold,
                            contentColor = PfColors.navy
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("See purchase options")
                    }
                }
                Text(
                    text = "The first lesson of every chapter is free to try.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}

// MARK: - Lesson content

@Composable
private fun LessonContent(
    lessonId: String,
    detail: LessonDetail,
    siblingIds: List<String>,
    siblingTitles: Map<String, String>,
    previousLessonId: String?,
    previousLessonTitle: String?,
    nextLessonId: String?,
    nextLessonTitle: String?,
    positionLines: List<String>,
    fromCache: Boolean,
    isFreeSample: Boolean,
    agendaEntry: AgendaEntry? = null,
    modifier: Modifier = Modifier,
    onRetryLoad: () -> Unit,
    onNavigateToLesson: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var checkedItems by remember(detail.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var quizAnswers by remember(detail.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var quizSubmitted by remember(detail.id) { mutableStateOf(false) }
    var isComplete by remember(detail.id) { mutableStateOf(false) }
    var savedPositionMs by remember(detail.id) { mutableStateOf(0L) }
    var selectedBeatId by remember(detail.id) { mutableStateOf<String?>(null) }
    var showFinishedPrompt by remember(detail.id) { mutableStateOf(false) }
    var didAutoAdvance by remember(detail.id) { mutableStateOf(false) }

    // Finish handling, matching iOS handleAudioFinished: when the
    // narration ends, auto-play moves to the next lesson after half
    // a second (once per lesson); otherwise the "Lesson finished"
    // card is offered. The delayed navigation is cancelled when the
    // screen leaves, so a manual navigation is never overridden.
    // The delayed op is generation-guarded (solved-APK defect 1):
    // after the delay it re-checks that no new playback session
    // started, the lesson is still current, and nothing is playing
    // again, before navigating.
    DisposableEffect(detail.id) {
        var advanceJob: Job? = null
        AudioPlayer.setOnPlaybackEnded(detail.id) {
            if (AudioPlayer.currentLessonId.value == detail.id) {
                showFinishedPrompt = false
                val next = nextLessonId
                if (next != null && AudioPlayer.autoAdvanceEnabled.value) {
                    if (!didAutoAdvance) {
                        didAutoAdvance = true
                        val generationAtEnd = AudioPlayer.playbackGeneration
                        advanceJob = scope.launch {
                            delay(500)
                            if (generationAtEnd != AudioPlayer.playbackGeneration ||
                                AudioPlayer.currentLessonId.value != detail.id ||
                                AudioPlayer.isPlaying.value
                            ) {
                                EventLog.record(
                                    "AUDIO",
                                    "AUTO_ADVANCE_ABORTED lesson=${detail.id} gen=$generationAtEnd"
                                )
                                return@launch
                            }
                            EventLog.record(
                                "AUDIO",
                                "AUTO_ADVANCE lesson=${detail.id} next=$next gen=$generationAtEnd"
                            )
                            AudioPlayer.pendingAutoPlayLessonId = next
                            onNavigateToLesson(next)
                        }
                    }
                } else if (next != null) {
                    showFinishedPrompt = true
                }
            }
        }
        onDispose {
            advanceJob?.cancel()
            // Token clear (audit fix 8): only this lesson's
            // callback is removed, so a newer screen's callback
            // survives this dispose during navigation.
            AudioPlayer.clearOnPlaybackEnded(detail.id)
        }
    }

    LaunchedEffect(detail.id) {
        val checklistRaw = AppStore.getString("lesson.checklist.${detail.id}")
        checkedItems = checklistRaw
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        val answersRaw = AppStore.getString("quiz.answers.${detail.id}")
        quizAnswers = answersRaw
            ?.split(";")
            ?.mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val index = parts[1].toIntOrNull()
                    if (index != null) parts[0] to index else null
                } else {
                    null
                }
            }
            ?.toMap()
            ?: emptyMap()
        quizSubmitted = AppStore.getBoolean("quiz.submitted.${detail.id}") == true
        isComplete = AppStore.getBoolean("lesson.completed.${detail.id}") == true
        savedPositionMs = AppStore.getLong("audio.position.${detail.id}") ?: 0L
        // Auto-play handoff: arriving from auto-advance or the
        // finished prompt starts this lesson's narration at once.
        // Every other arrival leaves playback stopped, as on iOS.
        if (AudioPlayer.pendingAutoPlayLessonId == detail.id &&
            detail.audioFileName.isNotBlank()
        ) {
            AudioPlayer.pendingAutoPlayLessonId = null
            AudioPlayer.playLesson(context, detail)
        }
    }

    // Supplemental flow beats are untimed (all times zero): the
    // lesson view never highlights or seeks from them, matching
    // iOS. Without this guard the all-zero times would highlight
    // the last beat.
    //
    // Playback position is NOT collected here (audit fix 2): the
    // 250 ms position tick used to recompose this whole screen 4
    // times a second. Position is observed only inside
    // BeatsFlowSection (beat highlight, via derivedStateOf, so it
    // recomposes only when the beat changes) and PlayerSliderRow
    // (the slider, the only UI that renders the live position).
    val isSupplementalDetail = detail.id.endsWith("-supp")

    val inThisLessonIntro = remember(detail) {
        val firstTakeaway = detail.takeaways.firstOrNull()?.takeIf { it.isNotBlank() }
        firstTakeaway ?: detail.beats
            .map { it.title }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }

    // Sibling, previous and next titles arrive resolved from the
    // merged index (see the load effect): title only in lists,
    // matching iOS, with no raw s-number fallback anywhere.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberTopScrollState(detail.id))
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LessonHeader(
            detail = detail,
            isComplete = isComplete,
            positionLines = positionLines,
            agendaEntry = agendaEntry
        )

        if (isFreeSample) {
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Text(
                        text = "Free sample lesson",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                    Text(
                        text = "This lesson is free, with the full content. The rest of this chapter unlocks with the course.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
            }
        }

        if (fromCache) {
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                    Text(
                        text = "Showing the copy saved on this device. The latest version could not be loaded.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.navy
                    )
                    SecondaryButton(text = "Retry live version") { onRetryLoad() }
                }
            }
        }

        if (inThisLessonIntro != null) {
            Section(title = "In this lesson") {
                LessonCard {
                    PfBodyText(
                        text = inThisLessonIntro,
                        style = MaterialTheme.typography.bodyLarge,
                        color = PfColors.navy
                    )
                }
            }
        }

        if (detail.beats.isNotEmpty()) {
            BeatsFlowSection(
                detail = detail,
                lessonId = lessonId,
                isSupplementalDetail = isSupplementalDetail,
                selectedBeatId = selectedBeatId,
                onBeatTap = { beat ->
                    // Untimed supplemental beats never seek:
                    // the tap only selects, and selection is
                    // skipped too, so the flow stays static.
                    // A beat tap before this lesson has loaded
                    // starts playback at the beat instead of
                    // silently seeking nothing (Tester 2 P2).
                    if (!isSupplementalDetail) {
                        selectedBeatId = beat.id
                        val targetMs = (beat.startTime * 1000).toLong()
                        if (AudioPlayer.currentLessonId.value == detail.id) {
                            AudioPlayer.seekTo(targetMs)
                        } else {
                            AudioPlayer.playLesson(context, detail, targetMs)
                        }
                    }
                }
            )
        }

        if (detail.audioFileName.isBlank()) {
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Text(
                        text = "No narration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                    Text(
                        text = "This lesson has no recorded narration. Read the full text below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    val readingMinutes = remember(detail) {
                        ReadTime.minutesForText(detail.readingText())
                    }
                    if (readingMinutes != null) {
                        Text(
                            text = "Reading time: about $readingMinutes min",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
        } else {
            PlayerCard(
                detail = detail,
                savedPositionMs = savedPositionMs
            )
        }

        // The "lesson finished" card: Play next lesson, or stay here.
        // Shown only when narration finished with auto-play off.
        if (showFinishedPrompt && nextLessonId != null) {
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                    Text(
                        text = "Lesson finished",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                    Text(
                        text = "Up next: ${nextLessonTitle ?: "Next lesson"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                showFinishedPrompt = false
                                AudioPlayer.pendingAutoPlayLessonId = nextLessonId
                                onNavigateToLesson(nextLessonId)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PfColors.royal,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Play next lesson")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(text = "Stay here") {
                                showFinishedPrompt = false
                            }
                        }
                    }
                }
            }
        }

        PrevNextRow(
            previousLessonId = previousLessonId,
            previousLessonTitle = previousLessonTitle,
            nextLessonId = nextLessonId,
            nextLessonTitle = nextLessonTitle,
            onNavigateToLesson = onNavigateToLesson
        )

        if (detail.takeaways.isNotEmpty()) {
            Section(title = "Key takeaways") {
                LessonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        detail.takeaways.take(5).forEach { takeaway ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = PfColors.gold,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .padding(top = 2.dp)
                                )
                                Text(
                                    text = takeaway,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PfColors.navy,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (detail.doThisAtWork.isNotEmpty()) {
            Section(title = "Do this at work") {
                LessonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        detail.doThisAtWork.forEachIndexed { index, item ->
                            val isChecked = checkedItems.contains(index)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val updated = if (isChecked) {
                                            checkedItems - index
                                        } else {
                                            checkedItems + index
                                        }
                                        checkedItems = updated
                                        scope.launch {
                                            AppStore.putString(
                                                "lesson.checklist.${detail.id}",
                                                updated.sorted().joinToString(",")
                                            )
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = if (isChecked) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Filled.RadioButtonUnchecked
                                    },
                                    contentDescription = if (isChecked) {
                                        "Checked: $item"
                                    } else {
                                        "Not checked: $item"
                                    },
                                    tint = if (isChecked) PfColors.success else PfColors.secondaryText,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(top = 1.dp)
                                )
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PfColors.navy,
                                    textDecoration = if (isChecked) {
                                        TextDecoration.LineThrough
                                    } else {
                                        TextDecoration.None
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        TranscriptSection(detail = detail)

        Section(title = "Sources") {
            LessonCard {
                if (detail.sources.isEmpty()) {
                    Text(
                        text = "No sources are listed for this lesson.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        detail.sources.forEach { source ->
                            Text(
                                text = source,
                                style = MaterialTheme.typography.bodySmall,
                                color = PfColors.secondaryText
                            )
                        }
                    }
                }
            }
        }

        QuizSection(
            detail = detail,
            answers = quizAnswers,
            submitted = quizSubmitted,
            onAnswerSelected = { questionId, optionIndex ->
                val updated = quizAnswers + (questionId to optionIndex)
                quizAnswers = updated
                scope.launch {
                    AppStore.putString(
                        "quiz.answers.${detail.id}",
                        updated.entries.joinToString(";") { "${it.key}=${it.value}" }
                    )
                }
            },
            onSubmit = {
                quizSubmitted = true
                scope.launch {
                    AppStore.putBoolean(
                        "quiz.submitted.${detail.id}",
                        true
                    )
                    val correct = detail.quiz.count { quizAnswers[it.id] == it.correctOptionIndex }
                    ProgressStore.recordQuizScore(detail.id, correct, detail.quiz.size)
                }
            },
            onRetake = {
                quizAnswers = emptyMap()
                quizSubmitted = false
                scope.launch {
                    AppStore.putString("quiz.answers.${detail.id}", "")
                    AppStore.putBoolean("quiz.submitted.${detail.id}", false)
                }
            }
        )

        if (siblingIds.isNotEmpty()) {
            Section(title = "More in this chapter") {
                LessonCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        siblingIds.forEach { siblingId ->
                            val label = siblingTitles[siblingId] ?: "Lesson"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToLesson(siblingId) }
                                    .padding(vertical = PfSpacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PfColors.navy,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = "Open lesson: $label",
                                    tint = PfColors.royal
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isComplete) {
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                    Text(
                        text = "Finished listening and reading? Mark the lesson complete to track your progress.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    Button(
                        onClick = {
                            isComplete = true
                            scope.launch {
                                ProgressStore.markLessonComplete(detail.id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PfColors.royal,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mark lesson complete")
                    }
                }
            }
        }

        if (nextLessonId != null) {
            Section(title = "Next lesson") {
                LessonCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToLesson(nextLessonId) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nextLessonTitle ?: "Next lesson",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.navy
                            )
                            Text(
                                text = "Continue to the next lesson",
                                style = MaterialTheme.typography.bodySmall,
                                color = PfColors.secondaryText
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = PfColors.royal
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Header, player, transcript, quiz

@Composable
private fun LessonHeader(
    detail: LessonDetail,
    isComplete: Boolean,
    positionLines: List<String> = emptyList(),
    agendaEntry: AgendaEntry? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
        Text(
            text = ReadingOrder.displayChapterLine(detail.id, detail.chapterTitle),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = PfColors.royal
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (agendaEntry?.thumbnail != null) {
                ThumbnailTile(
                    thumbnailPath = agendaEntry.thumbnail,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp)
                )
            }
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PfColors.navy,
                modifier = Modifier.weight(1f)
            )
        }
        val agendaText = agendaEntry?.agenda
        if (!agendaText.isNullOrBlank()) {
            AgendaBlock(agenda = agendaText, label = "Why take this lesson")
        }
        positionLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = PfColors.secondaryText
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PfColors.royal.copy(alpha = 0.08f))
                    .padding(horizontal = PfSpacing.medium, vertical = PfSpacing.xSmall)
            ) {
                Text(
                    text = ReadingOrder.lessonLabel(detail.id) ?: "Lesson",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
            }
            if (isComplete) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = PfColors.success,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Completed",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.success
                    )
                }
            }
        }
    }
}

/**
 * The "How it flows" section. This is the only place outside the
 * player slider that observes the 250 ms position tick (audit fix
 * 2): the beat index is derived with derivedStateOf, so this
 * section recomposes only when the highlighted beat actually
 * changes, not on every tick. The rest of the lesson screen never
 * subscribes to the tick at all.
 */
@Composable
private fun BeatsFlowSection(
    detail: LessonDetail,
    lessonId: String,
    isSupplementalDetail: Boolean,
    selectedBeatId: String?,
    onBeatTap: (LessonBeat) -> Unit
) {
    val positionState = AudioPlayer.positionMs.collectAsState()
    val playingLessonId by AudioPlayer.currentLessonId.collectAsState()
    val currentBeatIndex by remember(detail.beats, playingLessonId, isSupplementalDetail) {
        derivedStateOf {
            if (isSupplementalDetail || playingLessonId != lessonId || detail.beats.isEmpty()) {
                null
            } else {
                val nowSec = positionState.value / 1000.0
                val index = detail.beats.indexOfFirst { nowSec >= it.startTime && nowSec < it.endTime }
                if (index >= 0) {
                    index
                } else if (nowSec >= (detail.beats.lastOrNull()?.endTime ?: 0.0)) {
                    detail.beats.lastIndex
                } else {
                    null
                }
            }
        }
    }
    Section(title = "How it flows") {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            LessonCard(padding = 0.dp) {
                BeatsFlowContent(
                    beats = detail.beats,
                    currentBeatIndex = currentBeatIndex,
                    showsFlowArrows = !detail.diagramSvg.isNullOrBlank(),
                    selectedBeatId = selectedBeatId,
                    beatsTappable = !isSupplementalDetail,
                    onBeatTap = onBeatTap
                )
            }
            if (isSupplementalDetail) {
                Text(
                    text = "These points have no audio timing, so tapping them does not move the audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PfColors.secondaryText
                )
            }
        }
    }
}

/**
 * Floating play/pause for the lesson screen. It shares the
 * lesson player state exactly: the same isPlaying,
 * currentLessonId and isPlayStarting flows, and the same action
 * as the PlayerCard button (toggle when this lesson is loaded,
 * start it otherwise). While a start is in flight for this
 * lesson it shows a spinner and ignores taps, matching the
 * PlayerCard.
 */
@Composable
private fun LessonAudioFab(detail: LessonDetail) {
    val context = LocalContext.current
    val isPlaying by AudioPlayer.isPlaying.collectAsState()
    val playingLessonId by AudioPlayer.currentLessonId.collectAsState()
    val isPlayStarting by AudioPlayer.isPlayStarting.collectAsState()
    val isThisLesson = playingLessonId == detail.id
    val startingThisLesson = isPlayStarting && isThisLesson
    FloatingActionButton(
        onClick = {
            if (isThisLesson) {
                AudioPlayer.togglePlayPause()
            } else {
                AudioPlayer.playLesson(context, detail)
            }
        },
        modifier = Modifier.testTag("lessonAudioFab"),
        containerColor = PfColors.royal,
        contentColor = Color.White
    ) {
        if (startingThisLesson) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                imageVector = if (isThisLesson && isPlaying) {
                    Icons.Filled.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
                contentDescription = if (isThisLesson && isPlaying) "Pause" else "Play"
            )
        }
    }
}

@Composable
private fun PlayerCard(detail: LessonDetail, savedPositionMs: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPlaying by AudioPlayer.isPlaying.collectAsState()
    val isBuffering by AudioPlayer.isBuffering.collectAsState()
    // Position is NOT collected here (audit fix 2): the 250 ms
    // tick recomposed this whole card 4 times a second. Only
    // PlayerSliderRow below subscribes to the tick.
    val durationMs by AudioPlayer.durationMs.collectAsState()
    val playingLessonId by AudioPlayer.currentLessonId.collectAsState()
    val playbackSpeed by AudioPlayer.playbackSpeed.collectAsState()
    val sleepRemaining by AudioPlayer.sleepTimerRemainingMin.collectAsState()
    val errorMessage by AudioPlayer.errorMessage.collectAsState()
    val isUsingFallback by AudioPlayer.isUsingFallback.collectAsState()
    val isPlayingDownloadedCopy by AudioPlayer.isPlayingDownloadedCopy.collectAsState()
    val resumedFromMs by AudioPlayer.resumedFromMs.collectAsState()
    val isMuted by AudioPlayer.isMuted.collectAsState()
    val autoAdvanceEnabled by AudioPlayer.autoAdvanceEnabled.collectAsState()
    val isPlayStarting by AudioPlayer.isPlayStarting.collectAsState()

    val isThisLesson = playingLessonId == detail.id
    // A start in flight for this lesson: the play button shows a
    // spinner and ignores taps until playback begins (Tester 2
    // P3). A start for another lesson leaves this card alone.
    val startingThisLesson = isPlayStarting && isThisLesson
    val effectiveDuration = if (isThisLesson && durationMs > 0) {
        durationMs
    } else if (detail.durationSec > 0) {
        (detail.durationSec * 1000).toLong()
    } else {
        0L
    }
    val statusText = when {
        startingThisLesson -> "Starting..."
        isThisLesson && isPlaying -> "Playing"
        isThisLesson -> "Paused"
        else -> "Not started yet"
    }

    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepMenuExpanded by remember { mutableStateOf(false) }

    var downloadState by remember(detail.id) { mutableStateOf<DownloadUiState>(DownloadUiState.Checking) }
    var showRemoveConfirm by remember(detail.id) { mutableStateOf(false) }
    LaunchedEffect(detail.id) {
        downloadState = if (LessonDownloadStore.isDownloaded(context, detail.id)) {
            DownloadUiState.Downloaded
        } else {
            DownloadUiState.NotDownloaded
        }
    }

    LessonCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(PfColors.royal)
                        .clickable(enabled = !startingThisLesson) {
                            if (isThisLesson) {
                                AudioPlayer.togglePlayPause()
                            } else {
                                AudioPlayer.playLesson(context, detail)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (startingThisLesson) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (isThisLesson && isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (isThisLesson && isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Listen to this lesson",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                    Text(
                        text = "Tap play to start the narration, then read along below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PfColors.secondaryText
                    )
                    if (effectiveDuration > 0) {
                        Text(
                            text = "Listening length: ${formatTime(effectiveDuration)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.secondaryText
                        )
                    }
                    // Dual format: the honest listen time above, plus
                    // the read time from the full lesson text. Both
                    // figures are shown so neither misleads alone.
                    val readingMinutes = remember(detail) {
                        ReadTime.minutesForText(detail.readingText())
                    }
                    if (readingMinutes != null) {
                        Text(
                            text = "Reading time: about $readingMinutes min",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.secondaryText
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = PfColors.royal
                    )
                    if (isThisLesson && isBuffering) {
                        CircularProgressIndicator(
                            color = PfColors.royal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            PlayerSliderRow(
                detail = detail,
                isThisLesson = isThisLesson,
                effectiveDuration = effectiveDuration
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { AudioPlayer.seekBy(-15_000L) },
                    enabled = isThisLesson,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.FastRewind,
                            contentDescription = null,
                            tint = PfColors.royal
                        )
                        Text("Back 15s", color = PfColors.navy)
                    }
                }
                TextButton(
                    onClick = { AudioPlayer.toggleMute() },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = PfColors.royal
                        )
                        Text(if (isMuted) "Unmute" else "Mute", color = PfColors.navy)
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = AudioPlayer.speedLabel(playbackSpeed),
                                color = PfColors.royal,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Speed", color = PfColors.navy)
                        }
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false }
                    ) {
                        AudioPlayer.playbackSpeeds.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text(AudioPlayer.speedLabel(speed)) },
                                onClick = {
                                    AudioPlayer.setSpeed(speed)
                                    speedMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(onClick = { sleepMenuExpanded = true }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (sleepRemaining != null) "${sleepRemaining}m left" else "Sleep",
                                color = PfColors.royal,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Timer", color = PfColors.navy)
                        }
                    }
                    DropdownMenu(
                        expanded = sleepMenuExpanded,
                        onDismissRequest = { sleepMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Off") },
                            onClick = {
                                AudioPlayer.setSleepTimer(null)
                                sleepMenuExpanded = false
                            }
                        )
                        listOf(5, 10, 15, 30).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes minutes") },
                                onClick = {
                                    AudioPlayer.setSleepTimer(minutes)
                                    sleepMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { AudioPlayer.seekBy(15_000L) },
                    enabled = isThisLesson,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.FastForward,
                            contentDescription = null,
                            tint = PfColors.royal
                        )
                        Text("Forward 15s", color = PfColors.navy)
                    }
                }
            }

            when (val state = downloadState) {
                is DownloadUiState.Checking -> {
                    Text(
                        text = "Checking for a downloaded copy...",
                        style = MaterialTheme.typography.bodySmall,
                        color = PfColors.secondaryText
                    )
                }
                is DownloadUiState.NotDownloaded -> {
                    Text(
                        text = "Listen offline by downloading the narration to this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PfColors.secondaryText
                    )
                    SecondaryButton(text = "Download audio") {
                        downloadState = DownloadUiState.Downloading
                        scope.launch {
                            val result = LessonDownloadStore.download(context, detail)
                            downloadState = if (result.isSuccess) {
                                DownloadUiState.Downloaded
                            } else {
                                DownloadUiState.Failed(
                                    result.exceptionOrNull()?.message
                                        ?: "Download failed. Check your connection and try again."
                                )
                            }
                        }
                    }
                }
                is DownloadUiState.Downloading -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = PfColors.royal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Downloading audio...",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                    }
                }
                is DownloadUiState.Downloaded -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            tint = PfColors.navy,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Downloaded to this device",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = PfColors.navy
                        )
                    }
                    Text(
                        text = "Playing this lesson uses the downloaded copy. Remove it to free space; streaming still works afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PfColors.secondaryText
                    )
                    SecondaryButton(text = "Remove download") {
                        showRemoveConfirm = true
                    }
                }
                is DownloadUiState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.error
                    )
                    SecondaryButton(text = "Try download again") {
                        downloadState = DownloadUiState.Downloading
                        scope.launch {
                            val result = LessonDownloadStore.download(context, detail)
                            downloadState = if (result.isSuccess) {
                                DownloadUiState.Downloaded
                            } else {
                                DownloadUiState.Failed(
                                    result.exceptionOrNull()?.message
                                        ?: "Download failed. Check your connection and try again."
                                )
                            }
                        }
                    }
                }
            }

            // Playback queue: auto-advance is off by default. When it
            // is on, the next lesson starts on its own when this one
            // finishes; when it is off, a "Play next lesson" card is
            // offered instead. Matches the iOS toggle below the
            // download section.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-play next lesson",
                    style = PfTypography.captionBold,
                    color = PfColors.navy
                )
                Switch(
                    checked = autoAdvanceEnabled,
                    onCheckedChange = { AudioPlayer.setAutoAdvance(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PfColors.royal,
                        uncheckedThumbColor = PfColors.secondaryText,
                        uncheckedTrackColor = PfColors.border
                    )
                )
            }

            val resumedMs = if (isThisLesson) resumedFromMs else null
            if (resumedMs != null && resumedMs > 0) {
                Text(
                    text = "Resumed from ${formatTimePadded(resumedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
            } else if (!isThisLesson && savedPositionMs > 0) {
                Text(
                    text = "Resume from ${formatTimePadded(savedPositionMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
            }
            if (isThisLesson && isUsingFallback) {
                Text(
                    text = "The main audio was unavailable, so this lesson is playing from the backup copy. The content is the same.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PfColors.secondaryText
                )
            }
            if (isThisLesson && isPlayingDownloadedCopy) {
                Text(
                    text = "Playing the downloaded copy saved on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
            }
            if (isThisLesson && errorMessage != null) {
                Text(
                    text = "The audio could not be played. Check your connection and tap Retry audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PfColors.error
                )
                SecondaryButton(text = "Retry audio") {
                    AudioPlayer.playLesson(context, detail)
                }
            }
            // Removal confirmation (G24): deleting a download is
            // destructive and used to happen on one tap.
            if (showRemoveConfirm) {
                AlertDialog(
                    onDismissRequest = { showRemoveConfirm = false },
                    title = { Text("Remove this download?") },
                    text = {
                        Text(
                            "The audio will be deleted from this device. " +
                                "You can stream the lesson or download it again later."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRemoveConfirm = false
                                scope.launch {
                                    LessonDownloadStore.deleteDownload(context, detail.id)
                                    downloadState = DownloadUiState.NotDownloaded
                                }
                            }
                        ) {
                            Text("Remove")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveConfirm = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

private sealed interface DownloadUiState {
    data object Checking : DownloadUiState
    data object NotDownloaded : DownloadUiState
    data object Downloading : DownloadUiState
    data object Downloaded : DownloadUiState
    data class Failed(val message: String) : DownloadUiState
}

/**
 * The progress slider and time labels. This small composable is
 * the only UI that renders the live playback position, so it is
 * the only one that subscribes to the 250 ms position tick
 * (audit fix 2). The rest of PlayerCard recomposes only when
 * play state, buffering, or the other slow-changing states move.
 */
@Composable
private fun PlayerSliderRow(
    detail: LessonDetail,
    isThisLesson: Boolean,
    effectiveDuration: Long
) {
    val positionMs by AudioPlayer.positionMs.collectAsState()
    var isScrubbing by remember(detail.id) { mutableStateOf(false) }
    var scrubValue by remember(detail.id) { mutableStateOf(0f) }
    // The live position can run past the metadata duration (the
    // range comes from metadata, the value from the player), so
    // the shown position is coerced into the slider range: an
    // out-of-range value must never reach the Slider.
    val shownPosition = if (isThisLesson) {
        positionMs.coerceIn(0L, maxOf(effectiveDuration, 0L))
    } else {
        0L
    }
    Column {
        Slider(
            value = if (isScrubbing) {
                scrubValue
            } else {
                shownPosition.toFloat()
            },
            onValueChange = { value ->
                isScrubbing = true
                scrubValue = value
            },
            onValueChangeFinished = {
                isScrubbing = false
                AudioPlayer.seekTo(scrubValue.toLong())
            },
            valueRange = 0f..maxOf(effectiveDuration, 1L).toFloat(),
            // The slider seeks the loaded lesson only (Tester 2
            // P2): enabled before this lesson has loaded, a scrub
            // silently seeked whatever lesson was playing.
            enabled = isThisLesson && effectiveDuration > 0
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isScrubbing) scrubValue.toLong() else shownPosition),
                style = MaterialTheme.typography.bodySmall,
                color = PfColors.secondaryText
            )
            Text(
                text = formatTime(effectiveDuration),
                style = MaterialTheme.typography.bodySmall,
                color = PfColors.secondaryText
            )
        }
    }
}

@Composable
private fun PrevNextRow(
    previousLessonId: String?,
    previousLessonTitle: String?,
    nextLessonId: String?,
    nextLessonTitle: String?,
    onNavigateToLesson: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PrevNextButton(
            directionLabel = "Previous",
            targetId = previousLessonId,
            targetTitle = previousLessonTitle,
            isPrevious = true,
            modifier = Modifier.weight(1f),
            onNavigateToLesson = onNavigateToLesson
        )
        Spacer(modifier = Modifier.width(PfSpacing.medium))
        PrevNextButton(
            directionLabel = "Next",
            targetId = nextLessonId,
            targetTitle = nextLessonTitle,
            isPrevious = false,
            modifier = Modifier.weight(1f),
            onNavigateToLesson = onNavigateToLesson
        )
    }
}

@Composable
private fun PrevNextButton(
    directionLabel: String,
    targetId: String?,
    targetTitle: String?,
    isPrevious: Boolean,
    modifier: Modifier = Modifier,
    onNavigateToLesson: (String) -> Unit
) {
    val enabled = targetId != null
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) {
                if (targetId != null) onNavigateToLesson(targetId)
            }
            .padding(PfSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)
    ) {
        if (isPrevious) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) PfColors.royal else PfColors.secondaryText.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = directionLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) PfColors.navy else PfColors.secondaryText
            )
            // Full lesson titles can be long: two lines with an
            // ellipsis, inside the weighted column, so the row
            // never overflows a narrow phone or a large font scale.
            Text(
                text = targetTitle ?: "No ${directionLabel.lowercase()} lesson",
                style = MaterialTheme.typography.bodySmall,
                color = PfColors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!isPrevious) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) PfColors.royal else PfColors.secondaryText.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}

/// Characters of the first paragraph shown while the full
/// lesson text is collapsed. Every real lesson parses to a
/// single paragraph, so collapsing by paragraph count showed
/// the whole text and the toggle did nothing (proven on all
/// 881 lessons, wave 3). The preview is character-based, so
/// collapsed always shows strictly less than expanded.
internal const val TRANSCRIPT_PREVIEW_CHARS = 280

/// The collapsed preview of the transcript: the first
/// paragraph, truncated with an ellipsis when the text
/// continues past it, either in the same paragraph or in
/// later paragraphs. Pure, so the toggle states are pinned
/// by unit tests.
internal fun transcriptCollapsedPreview(paragraphs: List<String>): String {
    val first = paragraphs.firstOrNull() ?: return ""
    val continues = paragraphs.size > 1 || first.length > TRANSCRIPT_PREVIEW_CHARS
    if (!continues) return first
    return first.take(TRANSCRIPT_PREVIEW_CHARS).trimEnd() + "..."
}

/// True when expanding shows more than the collapsed preview:
/// more than one paragraph, or one paragraph longer than the
/// preview. When false, the whole text already fits, so no
/// toggle is shown at all: a dead control is worse than none.
internal fun transcriptCanExpand(paragraphs: List<String>): Boolean {
    if (paragraphs.size > 1) return true
    return (paragraphs.firstOrNull()?.length ?: 0) > TRANSCRIPT_PREVIEW_CHARS
}

@Composable
internal fun TranscriptSection(detail: LessonDetail) {
    val context = LocalContext.current
    var expanded by remember(detail.id) { mutableStateOf(false) }
    var highlightedIndex by remember(detail.id) { mutableStateOf<Int?>(null) }
    var transcriptNote by remember(detail.id) { mutableStateOf<String?>(null) }

    val paragraphs = remember(detail.transcript) {
        detail.transcript
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    val canExpand = remember(paragraphs) { transcriptCanExpand(paragraphs) }
    val previewText = remember(paragraphs) { transcriptCollapsedPreview(paragraphs) }
    // Paragraphs render seekable whenever the full text is
    // shown: expanded, or short enough that no toggle exists.
    val showParagraphs = expanded || !canExpand

    fun timestampForParagraph(index: Int): Double? {
        // Supplemental lessons have no audio timing: every beat
        // time is zero, so a paragraph tap shows the no-timing
        // note instead of seeking to the start.
        if (detail.id.endsWith("-supp")) return null
        if (detail.beats.isEmpty()) return null
        return detail.beats.getOrNull(index)?.startTime ?: detail.beats.last().startTime
    }

    Section(title = "Full lesson text") {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
            // The toggle exists only when expanding shows more:
            // a short transcript has nothing to expand into.
            if (canExpand) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transcript-toggle")
                        .clickable { expanded = !expanded }
                        .padding(vertical = PfSpacing.xSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (expanded) "Hide full text" else "Show full text",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.royal
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = PfColors.royal
                    )
                }
            }
            // The seek hint shows only when paragraphs are
            // seekable. While collapsed, tapping the preview
            // expands the text instead of seeking.
            if (showParagraphs) {
                Text(
                    text = "Tap a paragraph to jump the audio to it. Paragraphs without a timestamp cannot jump the audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PfColors.secondaryText
                )
            }
            if (transcriptNote != null) {
                Text(
                    text = transcriptNote!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = PfColors.royal
                )
            }
            LessonCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                    if (!showParagraphs) {
                        // Collapsed: the preview only. One tap on
                        // it expands, like the toggle row.
                        PfBodyText(
                            text = previewText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PfColors.navy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    transcriptNote = null
                                    expanded = true
                                }
                                .padding(PfSpacing.small)
                        )
                    } else {
                        paragraphs.forEachIndexed { index, paragraph ->
                        val timestamp = timestampForParagraph(index)
                        val isHighlighted = highlightedIndex == index
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isHighlighted) PfColors.goldSoft else Color.Transparent
                                )
                                .then(
                                    if (isHighlighted) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = PfColors.gold,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    if (timestamp == null) {
                                        highlightedIndex = null
                                        transcriptNote = "This paragraph has no audio timing, so it cannot jump the audio."
                                    } else {
                                        highlightedIndex = index
                                        val targetMs = (timestamp * 1000).toLong()
                                        // A paragraph tap before this
                                        // lesson has loaded starts
                                        // playback at the paragraph
                                        // instead of silently seeking
                                        // nothing (Tester 2 P2).
                                        if (AudioPlayer.currentLessonId.value == detail.id) {
                                            transcriptNote = null
                                            AudioPlayer.seekTo(targetMs)
                                        } else {
                                            transcriptNote = "Starting the narration at this paragraph."
                                            AudioPlayer.playLesson(context, detail, targetMs)
                                        }
                                    }
                                }
                                .padding(PfSpacing.small)
                        ) {
                            if (timestamp != null) {
                                Text(
                                    text = formatTime((timestamp * 1000).toLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PfColors.royal
                                )
                            }
                            // Paragraph rendering inside each
                            // timestamped transcript block: the outer
                            // "\n\n" split (and its beat mapping) is
                            // unchanged; long blocks get sentence-group
                            // breathing room within the tap target.
                            PfBodyText(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyLarge,
                                color = PfColors.navy
                            )
                        }
                    }
                    }
                    if (canExpand && !expanded) {
                        Text(
                            text = "Expand to read the full text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Quiz

@Composable
private fun QuizSection(
    detail: LessonDetail,
    answers: Map<String, Int>,
    submitted: Boolean,
    onAnswerSelected: (String, Int) -> Unit,
    onSubmit: () -> Unit,
    onRetake: () -> Unit
) {
    val quiz = detail.quiz
    val correctCount = quiz.count { answers[it.id] == it.correctOptionIndex }
    val allAnswered = quiz.isNotEmpty() && answers.size == quiz.size

    Section(title = "Check your understanding") {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            if (detail.quizIntro.isNotBlank()) {
                LessonCard {
                    Text(
                        text = detail.quizIntro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                }
            }
            if (submitted && quiz.isNotEmpty()) {
                LessonCard {
                    Text(
                        text = "Last score: You scored $correctCount of ${quiz.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                }
            }
            if (quiz.isEmpty()) {
                LessonCard {
                    Text(
                        text = "Read the lesson text above and think of one place at work where this applies, then mark the lesson complete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.navy
                    )
                }
            } else {
                quiz.forEachIndexed { index, question ->
                    QuizQuestionCard(
                        question = question,
                        number = index + 1,
                        selected = answers[question.id],
                        submitted = submitted,
                        onAnswerSelected = onAnswerSelected
                    )
                }
                if (allAnswered && !submitted) {
                    LessonCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                            Text(
                                text = "All questions answered. Submit to see your score.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Button(
                                onClick = onSubmit,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PfColors.royal,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Submit answers")
                            }
                        }
                    }
                }
                if (submitted) {
                    LessonCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                            Text(
                                text = "You scored $correctCount of ${quiz.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.navy
                            )
                            SecondaryButton(text = "Retake quiz") { onRetake() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizQuestionCard(
    question: QuizQuestion,
    number: Int,
    selected: Int?,
    submitted: Boolean,
    onAnswerSelected: (String, Int) -> Unit
) {
    LessonCard {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
            Text(
                text = "Q$number. ${question.question}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PfColors.navy
            )
            Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                question.options.forEachIndexed { optionIndex, option ->
                    val isSelected = selected == optionIndex
                    val isCorrectOption = optionIndex == question.correctOptionIndex
                    val background = when {
                        !isSelected -> PfColors.navy.copy(alpha = 0.06f)
                        submitted && isCorrectOption -> PfColors.success.copy(alpha = 0.12f)
                        submitted -> PfColors.error.copy(alpha = 0.12f)
                        else -> PfColors.royal.copy(alpha = 0.12f)
                    }
                    val border = when {
                        !isSelected -> PfColors.navy.copy(alpha = 0.10f)
                        submitted && isCorrectOption -> PfColors.success
                        submitted -> PfColors.error
                        else -> PfColors.royal
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(background)
                            .border(
                                width = 1.dp,
                                color = border,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = !submitted) {
                                onAnswerSelected(question.id, optionIndex)
                            }
                            .padding(PfSpacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = when {
                                !isSelected -> PfColors.secondaryText
                                submitted && isCorrectOption -> PfColors.success
                                submitted -> PfColors.error
                                else -> PfColors.royal
                            },
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 1.dp)
                        )
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PfColors.navy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (submitted && selected != null) {
                val isCorrect = selected == question.correctOptionIndex
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xSmall)) {
                    Text(
                        text = if (isCorrect) "Correct" else "Not quite",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCorrect) PfColors.success else PfColors.error
                    )
                    PfBodyText(
                        text = question.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
            }
        }
    }
}

// MARK: - Shared small pieces

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = PfColors.navy
        )
        content()
    }
}

@Composable
private fun LessonCard(
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PfColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content
        )
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PfColors.royal),
        border = BorderStroke(1.dp, PfColors.royal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}
