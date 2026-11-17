// Port of ReferralInvite.swift and CommunityChallenge.swift (iOS).
// Deep links, referral invites, the weekly free case selection, and the
// weekly community challenge. No reward is offered, no invite code is
// generated, and nothing is recorded anywhere for referrals. The community
// leaderboard has no server backend in this version: it shows only the
// learner's own progress, with an honest empty state, and never invents
// community rows, totals, participation counts, or testimonials.
// No em dashes.
package com.paymentstraininghub.app.features.growth

import android.content.Context
import android.content.Intent
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.features.analytics.AnalyticsService
import com.paymentstraininghub.app.features.lab.ExceptionCase
import com.paymentstraininghub.app.features.lab.ExceptionCaseCatalogue
import com.paymentstraininghub.app.features.lab.ExceptionLabScoreBreakdown
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

// MARK: - Deep links

/**
 * A deep link into the app: one lesson, this week's free payment case,
 * or a password reset. Parsed from the app's custom scheme only. Auth
 * callbacks (host "auth-callback") and every other host return null.
 * A reset link carries its recovery token in the URL fragment
 * (#access_token, type=recovery); only a link with type=recovery and
 * a non-blank token parses, and the token is held only in
 * DeepLinkRouter.pendingRecoveryToken until the reset screen reads
 * and clears it.
 *
 * DeepLinkRouter API (for Worker C's NavGraph and the Activity that
 * receives the intent; manifest intent filters are Worker A's file):
 *   DeepLinkRouter.parse(uriString) -> TrainingHubDeepLink?
 *   DeepLinkRouter.routeFor(link) -> String   // "lesson/{id}", "weekly-case" or "reset-password"
 *   DeepLinkRouter.receive(link) / .clear() / .receiveEvent() (one-shot, direct channel receive)
 *   DeepLinkRouter.receiveRecoveryEvent() (one-shot, reset links only)
 *   DeepLinkRouter.pendingRecoveryToken (StateFlow) / .clearRecoveryToken()
 *   TrainingHubDeepLink.lessonURL(lessonID) / .weeklyCaseURL()
 */
sealed class TrainingHubDeepLink {
    /** Open one lesson by catalogue id, for example "ch1s1". */
    data class Lesson(val lessonID: String) : TrainingHubDeepLink()

    /** Open this week's free payment case. */
    object WeeklyFreeCase : TrainingHubDeepLink()

    /**
     * Open the set-new-password screen with a recovery token from a
     * reset link. The token is held in memory only and is never
     * rendered into a URL: [url] returns the bare reset host.
     */
    data class ResetPassword(val accessToken: String) : TrainingHubDeepLink()

    /**
     * The OAuth return from a provider sign-in (Google): the
     * authorisation [code] to exchange, or the provider [error]
     * when sign-in did not complete. Both are null only for a
     * bare callback link with no parameters, which the sign-in
     * screen treats as interrupted. The code is held in memory
     * only and is never rendered into a URL: [url] returns the
     * bare callback host.
     */
    data class OAuthCallback(val code: String?, val error: String?) : TrainingHubDeepLink()

    /** The URL for this deep link, in the documented format. */
    val url: String
        get() = when (this) {
            is Lesson -> "$SCHEME://lesson?id=$lessonID"
            WeeklyFreeCase -> "$SCHEME://weekly-case"
            is ResetPassword -> "$SCHEME://reset-password"
            is OAuthCallback -> "$SCHEME://auth-callback"
        }

    companion object {
        const val SCHEME: String = "com.paymentstraininghub.app"
        const val SITE_BASE: String = "https://www.paymentstraininghub.com"

        fun parse(uriString: String): TrainingHubDeepLink? {
            val trimmed = uriString.trim()
            val schemePrefix = "$SCHEME://"
            if (!trimmed.startsWith(schemePrefix)) return null
            val rest = trimmed.removePrefix(schemePrefix)
            val host = rest.substringBefore("?").substringBefore("#").substringBefore("/").lowercase()
            return when (host) {
                "lesson" -> {
                    val lessonID = lessonIDFrom(rest) ?: return null
                    Lesson(lessonID)
                }
                "weekly-case" -> WeeklyFreeCase
                "reset-password" -> {
                    val token = recoveryTokenFrom(trimmed) ?: return null
                    ResetPassword(token)
                }
                "auth-callback" -> {
                    val params = queryParamsFrom(rest)
                    OAuthCallback(code = params["code"], error = params["error"])
                }
                else -> null
            }
        }

        /**
         * The lesson id from a lesson link: the ?id= query item first,
         * then the first path component. The id must be one path-safe
         * token (letters, digits, hyphen, underscore).
         */
        private fun lessonIDFrom(rest: String): String? {
            val query = rest.substringAfter("?", "")
            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "id" && isValidLessonID(parts[1])) {
                    return parts[1]
                }
            }
            val pathID = rest.substringBefore("?").substringAfter("/", "").trim('/')
            if (isValidLessonID(pathID)) return pathID
            return null
        }

        /**
         * The query parameters of a link, URL-decoded when they
         * decode cleanly and used verbatim otherwise. The fragment
         * is ignored: OAuth callbacks carry the code in the query.
         */
        private fun queryParamsFrom(rest: String): Map<String, String> {
            val query = rest.substringAfter("?", "").substringBefore("#")
            if (query.isEmpty()) return emptyMap()
            val params = mutableMapOf<String, String>()
            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val value = try {
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
                params[parts[0]] = value
            }
            return params
        }

        /**
         * The recovery access token from a reset link fragment:
         * #access_token=...&type=recovery. Only type=recovery
         * parses; any other type, a missing token, or a missing
         * fragment returns null. Values are URL-decoded when they
         * decode cleanly, and used verbatim otherwise.
         */
        private fun recoveryTokenFrom(uriString: String): String? {
            val fragment = uriString.substringAfter("#", "")
            if (fragment.isEmpty()) return null
            var accessToken: String? = null
            var type: String? = null
            fragment.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val value = try {
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
                when (parts[0]) {
                    "access_token" -> accessToken = value
                    "type" -> type = value
                }
            }
            if (type != "recovery") return null
            return accessToken?.takeIf { it.isNotBlank() }
        }

        private fun isValidLessonID(value: String): Boolean {
            if (value.isEmpty()) return false
            return value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }

        /** A lesson link for any lesson id. */
        fun lessonURL(lessonID: String): String? {
            if (!isValidLessonID(lessonID)) return null
            return Lesson(lessonID).url
        }

        /**
         * A lesson link only when the entry is the first lesson of its
         * chapter (D77). The caller passes the already-verified result:
         * null for every other lesson, so a shared link can never promise
         * free access to a gated lesson.
         */
        fun sampleLessonURL(lessonID: String, isSampleLesson: Boolean): String? {
            if (!isSampleLesson) return null
            return lessonURL(lessonID)
        }

        /** The weekly-case link. It carries no case id on purpose. */
        fun weeklyCaseURL(): String = WeeklyFreeCase.url
    }
}

/**
 * Holds a deep link until the navigation host can route it.
 *
 * Navigation commands are ONE-SHOT events, never replayable state:
 * the old StateFlow holder replayed its latest value to every new
 * collector, so a restarted navigation effect could re-navigate to
 * a stale link. Each event below is delivered to exactly one
 * collector, in arrival order, and is then gone. The buffered
 * channel still holds a link that arrives before the navigation
 * host is ready, so a cold-start tap is never lost; a link that
 * must survive process death is handled separately through the
 * launch intent (MainActivity.consumeLaunchDeepLink), never by
 * replaying these channels.
 */
object DeepLinkRouter {

    /**
     * One navigation command. [id] is a monotonic sequence number
     * used to reject duplicates and stale replays; [receivedAtMs]
     * is the wall-clock arrival time, used for expiry validation.
     */
    sealed interface PendingEvent {
        val id: Long
        val receivedAtMs: Long

        /** A content link: a lesson or the weekly free case. */
        data class Link(
            val link: TrainingHubDeepLink,
            override val id: Long,
            override val receivedAtMs: Long
        ) : PendingEvent

        /** A password-reset link, carrying only its token. */
        data class Recovery(
            val token: String,
            override val id: Long,
            override val receivedAtMs: Long
        ) : PendingEvent
    }

    private val nextEventId = AtomicLong(0L)
    private val eventChannel = Channel<PendingEvent>(Channel.BUFFERED)
    private val recoveryChannel = Channel<PendingEvent.Recovery>(Channel.BUFFERED)

    /**
     * Suspend until the next content-link event arrives. The
     * graph consumes events with a direct channel receive, not
     * through a Flow: delivery then never depends on Flow
     * operator machinery between the channel and the consumer.
     * Each event is delivered to exactly one receiver, in
     * arrival order, and is then gone.
     */
    suspend fun receiveEvent(): PendingEvent = eventChannel.receive()

    /**
     * Suspend until the next password-reset event arrives. Reset
     * links travel on their own channel, so a reset link never
     * queues behind a content link that is waiting for sign-in.
     */
    suspend fun receiveRecoveryEvent(): PendingEvent.Recovery =
        recoveryChannel.receive()

    private val _pendingRecoveryToken = MutableStateFlow<String?>(null)

    /**
     * The recovery token from a pending reset link, held as DATA
     * for the set-new-password screen to read: the screen reads
     * the value and clears it, so it always finds the token when
     * it opens. This is not a navigation command: navigation is
     * driven by the Recovery event, delivered once, so a
     * restarted collector can never re-route to the reset screen
     * on a token that is still sitting here.
     */
    val pendingRecoveryToken: StateFlow<String?> = _pendingRecoveryToken.asStateFlow()

    fun parse(uriString: String): TrainingHubDeepLink? = TrainingHubDeepLink.parse(uriString)

    /**
     * The navigation route string for a deep link. The reset route
     * string must match Routes.RESET_PASSWORD; it is written as a
     * literal here, as the other routes are, so this file does not
     * depend on the navigation package.
     */
    fun routeFor(link: TrainingHubDeepLink): String = when (link) {
        is TrainingHubDeepLink.Lesson -> "lesson/${link.lessonID}"
        TrainingHubDeepLink.WeeklyFreeCase -> "weekly-case"
        is TrainingHubDeepLink.ResetPassword -> "reset-password"
        is TrainingHubDeepLink.OAuthCallback -> "auth"
    }

    /** The navigation route string for a pending event. */
    fun routeFor(event: PendingEvent): String = when (event) {
        is PendingEvent.Link -> routeFor(event.link)
        is PendingEvent.Recovery -> "reset-password"
    }

    /**
     * Record an incoming link as a one-shot event. A reset link
     * records its token in [pendingRecoveryToken] for the screen
     * AND emits a Recovery event on the recovery channel; it never
     * enters the content-link stream, so the recovery route is
     * the only path that can consume it and it never waits
     * behind a content link. An OAuth callback records its code
     * or error in [pendingOAuthCode]/[pendingOAuthError] for the
     * sign-in screen only, and emits no navigation event: the
     * sign-in screen is already showing when no session exists.
     */
    fun receive(link: TrainingHubDeepLink) {
        val id = nextEventId.incrementAndGet()
        val nowMs = System.currentTimeMillis()
        if (link is TrainingHubDeepLink.OAuthCallback) {
            _pendingOAuthCode.value = link.code
            _pendingOAuthError.value = link.error
            pendingOAuthReceivedAtMs = nowMs
        } else if (link is TrainingHubDeepLink.ResetPassword) {
            _pendingRecoveryToken.value = link.accessToken
            recoveryChannel.trySend(PendingEvent.Recovery(link.accessToken, id, nowMs))
        } else {
            eventChannel.trySend(PendingEvent.Link(link, id, nowMs))
        }
    }

    /** Parse and record an incoming link string. Returns false when it is not a deep link. */
    fun receiveUri(uriString: String): Boolean {
        val link = parse(uriString) ?: return false
        receive(link)
        return true
    }

    /** Drop every event still waiting in either channel. Used by tests. */
    fun clear() {
        while (eventChannel.tryReceive().isSuccess) {
            // Drain: each receive consumes one buffered event.
        }
        while (recoveryChannel.tryReceive().isSuccess) {
            // Drain: each receive consumes one buffered event.
        }
        clearOAuthCallback()
    }

    /** Clear the pending recovery token after the reset screen has read it. */
    fun clearRecoveryToken() {
        _pendingRecoveryToken.value = null
    }

    private val _pendingOAuthCode = MutableStateFlow<String?>(null)

    /**
     * The authorisation code from a pending OAuth callback, held
     * as DATA for the sign-in screen to read: the screen reads
     * the value, clears it, and exchanges it once. This is not a
     * navigation command: no navigation is needed, because the
     * sign-in screen is already the visible screen.
     */
    val pendingOAuthCode: StateFlow<String?> = _pendingOAuthCode.asStateFlow()

    private val _pendingOAuthError = MutableStateFlow<String?>(null)

    /** The provider error from a pending OAuth callback, for the sign-in screen to report. */
    val pendingOAuthError: StateFlow<String?> = _pendingOAuthError.asStateFlow()

    @Volatile
    private var pendingOAuthReceivedAtMs: Long = 0L

    /**
     * The age of a pending OAuth callback, or [Long.MAX_VALUE]
     * when none is pending. The sign-in screen ignores a stale
     * callback (an old code can never exchange), so a code left
     * over from an earlier run never fires on a later sign-in.
     */
    fun pendingOAuthAgeMs(nowMs: Long): Long {
        if (_pendingOAuthCode.value == null && _pendingOAuthError.value == null) {
            return Long.MAX_VALUE
        }
        return nowMs - pendingOAuthReceivedAtMs
    }

    /** Clear a pending OAuth callback after the sign-in screen has consumed it. */
    fun clearOAuthCallback() {
        _pendingOAuthCode.value = null
        _pendingOAuthError.value = null
        pendingOAuthReceivedAtMs = 0L
    }
}

// MARK: - Referral invitations

/**
 * One referral invitation: honest text plus links to the free experience.
 * No reward is offered, no invite code is generated, and nothing is
 * recorded anywhere: the text names only what is actually free, and the
 * links carry no tracking parameters.
 */
data class ReferralInvite(
    val kind: Kind,
    val webLink: String,
    val appLink: String
) {
    sealed class Kind {
        /** This week's free payment case. */
        object WeeklyFreeCase : Kind()

        /** One free sample lesson, by title. */
        data class SampleLesson(val title: String) : Kind()
    }

    companion object {
        /** An invite to this week's free payment case. */
        fun weeklyFreeCase(): ReferralInvite = ReferralInvite(
            kind = Kind.WeeklyFreeCase,
            webLink = TrainingHubDeepLink.SITE_BASE,
            appLink = TrainingHubDeepLink.weeklyCaseURL()
        )

        /**
         * An invite to one free sample lesson. The caller must have
         * verified the lesson is the first lesson of its chapter.
         */
        fun sampleLesson(lessonID: String, title: String, websitePath: String?): ReferralInvite? {
            val appLink = TrainingHubDeepLink.lessonURL(lessonID) ?: return null
            val trimmedPath = (websitePath ?: "").trim()
            val webLink = if (trimmedPath.isEmpty()) {
                TrainingHubDeepLink.SITE_BASE
            } else {
                TrainingHubDeepLink.SITE_BASE.trimEnd('/') + "/" + trimmedPath.trimStart('/')
            }
            return ReferralInvite(kind = Kind.SampleLesson(title), webLink = webLink, appLink = appLink)
        }
    }

    /** The subject line for a mailed invite. */
    val subject: String
        get() = when (kind) {
            Kind.WeeklyFreeCase -> "This week's free payment case"
            is Kind.SampleLesson -> "A free payments lesson worth a look"
        }

    /** The invite text, as shared. It names only the free experience. */
    val inviteText: String
        get() = when (kind) {
            Kind.WeeklyFreeCase -> listOf(
                "Payments Training Hub shares one real payment case free every week.",
                "This week's case is free to open, no purchase needed.",
                "Have a look: $webLink"
            ).joinToString("\n")
            is Kind.SampleLesson -> listOf(
                "I am learning payments with Payments Training Hub.",
                "The first lesson of each chapter is free to open, with the full content. " +
                    "This one is \"${kind.title}\".",
                "Have a look: $webLink"
            ).joinToString("\n")
        }

    /** The text handed to the share sheet. */
    val shareText: String get() = inviteText

    /** Post text for sharing the invite on LinkedIn. The learner pastes this into their own post. */
    val linkedInText: String
        get() = when (kind) {
            Kind.WeeklyFreeCase -> listOf(
                "Payments Training Hub shares one real payment case free every week.",
                "This week's case is free to open, no purchase needed.",
                webLink
            ).joinToString("\n\n")
            is Kind.SampleLesson -> listOf(
                "Learning payments with Payments Training Hub. The first lesson of each chapter is free to open, with the full content.",
                "Starting with: ${kind.title}",
                webLink
            ).joinToString("\n\n")
        }
}

/** LinkedIn share-offsite links. Builds the link only; it never posts. */
object LinkedInShare {
    fun shareOffsiteURL(url: String): String {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        return "https://www.linkedin.com/sharing/share-offsite/?url=$encoded"
    }
}

/** Shares plain text through the Android Sharesheet. No server tracking. */
fun shareTextViaSheet(context: Context, text: String, subject: String? = null) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

// MARK: - Weekly selection

/**
 * Deterministic weekly case selection from the existing lab catalogue, so
 * every learner sees the same case in the same week and the case changes
 * every Monday. The week is the ISO 8601 week (Monday to Sunday) in UTC.
 * The week ordinal (year times 53 plus week number) is the selection index.
 */
object CommunityChallenge {
    private fun isoCalendar(): Calendar {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.minimalDaysInFirstWeek = 4
        return calendar
    }

    /** The ISO week components for a moment: week-numbering year and week number (1 to 53). */
    fun weekComponents(timeMillis: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val calendar = isoCalendar()
        calendar.timeInMillis = timeMillis
        val year = calendar.get(Calendar.YEAR)
        val week = calendar.get(Calendar.WEEK_OF_YEAR)
        val month = calendar.get(Calendar.MONTH)
        // Week-numbering year: early-January days in week 52/53 belong to
        // the previous year; late-December days in week 1 belong to the next.
        val weekYear = when {
            month == Calendar.JANUARY && week >= 52 -> year - 1
            month == Calendar.DECEMBER && week == 1 -> year + 1
            else -> year
        }
        return weekYear to week
    }

    /** The storage and display key for the week, for example "2026-W46". */
    fun weekKey(timeMillis: Long = System.currentTimeMillis()): String {
        val (year, week) = weekComponents(timeMillis)
        return "$year-W${week.toString().padStart(2, '0')}"
    }

    /** A monotonic ordinal for the week: year times 53 plus week number. */
    fun weekOrdinal(timeMillis: Long = System.currentTimeMillis()): Int {
        val (year, week) = weekComponents(timeMillis)
        return year * 53 + week
    }

    /** The Monday starting the week, in UTC millis. */
    fun weekStartMillis(timeMillis: Long = System.currentTimeMillis()): Long {
        val calendar = isoCalendar()
        calendar.timeInMillis = timeMillis
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** The label for the week, for example "Week of 9 Nov 2026". */
    fun weekLabel(timeMillis: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("d MMM yyyy", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return "Week of ${format.format(java.util.Date(weekStartMillis(timeMillis)))}"
    }

    /** The weekly case for a week ordinal. Nil only when the catalogue is empty. */
    fun caseForWeek(ordinal: Int): ExceptionCase? {
        val cases = ExceptionCaseCatalogue.allCases
        if (cases.isEmpty()) return null
        val index = ((ordinal % cases.size) + cases.size) % cases.size
        return cases[index]
    }

    /** The weekly case for the week containing a moment. */
    fun caseFor(timeMillis: Long = System.currentTimeMillis()): ExceptionCase? =
        caseForWeek(weekOrdinal(timeMillis))

    fun exceptionCase(forID: String): ExceptionCase? =
        ExceptionCaseCatalogue.allCases.firstOrNull { it.id == forID }

    /** Shareable result text: week, case title, and score only. No answers, no name, no identifier. */
    fun shareText(weekKey: String, caseTitle: String, total: Int, maximum: Int): String =
        "I completed the Payments Training Hub community challenge for week $weekKey: " +
            "\"$caseTitle\". I scored $total out of $maximum."
}

// MARK: - Stored attempt and leaderboard

/** The stored result for one challenge week, keyed by week key. */
data class CommunityChallengeAttempt(
    val weekKey: String,
    val caseID: String,
    val bestTotal: Int,
    val bestMaximum: Int,
    val completions: Int,
    val lastCompletedAtMillis: Long,
    val lastBreakdown: ExceptionLabScoreBreakdown
)

/**
 * One anonymous row for a future shared leaderboard. No code in this
 * version creates one: there is no shared-results backend, so the
 * leaderboard always carries zero community entries.
 */
data class CommunityLeaderboardEntry(
    val anonymousLabel: String,
    val weekKey: String,
    val total: Int,
    val maximum: Int
)

/** What the leaderboard shows for one week. The view must never render community rows beyond this array. */
data class CommunityChallengeLeaderboard(
    val weekKey: String,
    val communityEntries: List<CommunityLeaderboardEntry>,
    val personalBest: CommunityChallengeAttempt?
) {
    /** True only when real community rows exist. False in this version, always. */
    val hasCommunityData: Boolean get() = communityEntries.isNotEmpty()
}

/**
 * Stores community challenge attempts locally, per account. Device only.
 * A shared board needs, at minimum: a server table for anonymous weekly
 * aggregates, an Edge Function that accepts only a week key and a score
 * (no name, no identifier, no device id), and a read endpoint returning
 * anonymous rows only. Nothing in this file calls the network.
 * AppStore is suspend-only and namespaces keys itself; loads and saves
 * run in this store's IO scope.
 */
object CommunityChallengeStore {
    private const val ATTEMPTS_KEY = "pf.communityChallengeAttempts.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _attempts = MutableStateFlow<List<CommunityChallengeAttempt>>(emptyList())
    val attempts: StateFlow<List<CommunityChallengeAttempt>> = _attempts.asStateFlow()

    /** The best attempt across all weeks, by score. */
    val personalBest: CommunityChallengeAttempt?
        get() = _attempts.value.maxByOrNull { it.bestTotal }

    val completedWeekKeys: Set<String> get() = _attempts.value.map { it.weekKey }.toSet()

    init {
        scope.launch { load() }
    }

    fun reloadForCurrentAccount() {
        _attempts.value = emptyList()
        scope.launch { load() }
    }

    fun recordCompletion(weekKey: String, caseID: String, breakdown: ExceptionLabScoreBreakdown) {
        val current = _attempts.value.toMutableList()
        val index = current.indexOfFirst { it.weekKey == weekKey }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                caseID = caseID,
                bestTotal = maxOf(existing.bestTotal, breakdown.total),
                bestMaximum = breakdown.maximum,
                completions = existing.completions + 1,
                lastCompletedAtMillis = System.currentTimeMillis(),
                lastBreakdown = breakdown
            )
        } else {
            current.add(
                CommunityChallengeAttempt(
                    weekKey = weekKey,
                    caseID = caseID,
                    bestTotal = breakdown.total,
                    bestMaximum = breakdown.maximum,
                    completions = 1,
                    lastCompletedAtMillis = System.currentTimeMillis(),
                    lastBreakdown = breakdown
                )
            )
        }
        _attempts.value = current
        save()
        AnalyticsService.practicalExerciseCompleted(caseID, "challenge")
    }

    fun attemptForWeekKey(weekKey: String): CommunityChallengeAttempt? =
        _attempts.value.firstOrNull { it.weekKey == weekKey }

    fun isCompleted(weekKey: String): Boolean = attemptForWeekKey(weekKey) != null

    /**
     * The leaderboard for a week: zero community entries until a
     * shared-results backend exists, plus the learner's own best attempt.
     */
    fun leaderboardForWeekKey(weekKey: String): CommunityChallengeLeaderboard =
        CommunityChallengeLeaderboard(
            weekKey = weekKey,
            communityEntries = emptyList(),
            personalBest = attemptForWeekKey(weekKey)
        )

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(ATTEMPTS_KEY) }.getOrNull()
            ?: return
        val decoded = runCatching { decodeAttempts(raw) }.getOrNull() ?: return
        _attempts.value = decoded
    }

    private fun save() {
        val encoded = runCatching { encodeAttempts(_attempts.value) }.getOrNull() ?: return
        scope.launch {
            runCatching { AppStore.putString(ATTEMPTS_KEY, encoded) }
        }
    }

    internal fun encodeAttempts(attempts: List<CommunityChallengeAttempt>): String {
        val array = JSONArray()
        attempts.forEach { attempt ->
            val b = attempt.lastBreakdown
            val breakdownJson = JSONObject()
                .put("bestFirstPoints", b.bestFirstPoints)
                .put("recoveredPoints", b.recoveredPoints)
                .put("wrongTurnDeductions", b.wrongTurnDeductions)
                .put("redHerringDeductions", b.redHerringDeductions)
                .put("efficiencyBonus", b.efficiencyBonus)
                .put("thoroughnessPoints", b.thoroughnessPoints)
                .put("total", b.total)
                .put("maximum", b.maximum)
                .put("stepsSolvedBestFirst", b.stepsSolvedBestFirst)
                .put("stepsSolvedAfterWrongTurn", b.stepsSolvedAfterWrongTurn)
                .put("totalWrongPicks", b.totalWrongPicks)
                .put("totalRedHerringPicks", b.totalRedHerringPicks)
                .put("evidenceOpenedCount", b.evidenceOpenedCount)
                .put("evidenceTotalCount", b.evidenceTotalCount)
            array.put(
                JSONObject()
                    .put("weekKey", attempt.weekKey)
                    .put("caseID", attempt.caseID)
                    .put("bestTotal", attempt.bestTotal)
                    .put("bestMaximum", attempt.bestMaximum)
                    .put("completions", attempt.completions)
                    .put("lastCompletedAtMillis", attempt.lastCompletedAtMillis)
                    .put("lastBreakdown", breakdownJson)
            )
        }
        return array.toString()
    }

    internal fun decodeAttempts(raw: String): List<CommunityChallengeAttempt> {
        val array = JSONArray(raw)
        val out = mutableListOf<CommunityChallengeAttempt>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val b = item.getJSONObject("lastBreakdown")
            out.add(
                CommunityChallengeAttempt(
                    weekKey = item.getString("weekKey"),
                    caseID = item.getString("caseID"),
                    bestTotal = item.getInt("bestTotal"),
                    bestMaximum = item.getInt("bestMaximum"),
                    completions = item.getInt("completions"),
                    lastCompletedAtMillis = item.getLong("lastCompletedAtMillis"),
                    lastBreakdown = ExceptionLabScoreBreakdown(
                        bestFirstPoints = b.getInt("bestFirstPoints"),
                        recoveredPoints = b.getInt("recoveredPoints"),
                        wrongTurnDeductions = b.getInt("wrongTurnDeductions"),
                        redHerringDeductions = b.getInt("redHerringDeductions"),
                        efficiencyBonus = b.getInt("efficiencyBonus"),
                        thoroughnessPoints = b.getInt("thoroughnessPoints"),
                        total = b.getInt("total"),
                        maximum = b.getInt("maximum"),
                        stepsSolvedBestFirst = b.getInt("stepsSolvedBestFirst"),
                        stepsSolvedAfterWrongTurn = b.getInt("stepsSolvedAfterWrongTurn"),
                        totalWrongPicks = b.getInt("totalWrongPicks"),
                        totalRedHerringPicks = b.getInt("totalRedHerringPicks"),
                        evidenceOpenedCount = b.getInt("evidenceOpenedCount"),
                        evidenceTotalCount = b.getInt("evidenceTotalCount")
                    )
                )
            )
        }
        return out
    }
}

// MARK: - Weekly free case opened marker

/** Records which weekly free cases have been opened, one marker per week. Device only. */
object WeeklyFreeCaseStore {
    private const val OPENED_KEY = "pf.weeklyFreeCaseOpened.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _opened = MutableStateFlow<Map<String, String>>(emptyMap())
    val opened: StateFlow<Map<String, String>> = _opened.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun reloadForCurrentAccount() {
        _opened.value = emptyMap()
        scope.launch { load() }
    }

    fun hasOpened(weekKey: String): Boolean = _opened.value.containsKey(weekKey)

    fun openedCaseID(weekKey: String): String? = _opened.value[weekKey]

    fun markOpened(weekKey: String, caseID: String) {
        _opened.value = _opened.value + (weekKey to caseID)
        save()
    }

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(OPENED_KEY) }.getOrNull()
            ?: return
        runCatching {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            _opened.value = map
        }
    }

    private fun save() {
        val obj = JSONObject()
        _opened.value.forEach { (key, value) -> obj.put(key, value) }
        val encoded = obj.toString()
        scope.launch {
            runCatching { AppStore.putString(OPENED_KEY, encoded) }
        }
    }
}
