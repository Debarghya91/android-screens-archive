// NotificationSchedulers.kt
// Payments Training Hub Android - local notification schedulers for
// streak nudges, continue learning, new content alerts and offers.
//
// Port of NotificationSchedulers.swift (iOS v66). Local only:
// WorkManager only. No FCM, no entitlements, no device token code,
// no network of its own, apart from reading the live catalogue
// through ContentRepository for the new-content check and for route
// resolution, exactly as the iOS schedulers read ContentService.
// This file edits no other file and hooks into no store: it only
// reads the existing stores and asks WorkManager to replace one
// unique one-shot work item per kind.
//
// Contract: object NotificationSchedulers { suspend fun refreshAll(context) }.
// Call refreshAll on every foreground (AppNavGraph wires it through
// LocalNotificationService), on every preference change, and from
// the daily reconciler worker, so a fired one-shot is re-armed even
// when the app is not foregrounded for a day. The iOS schedulers
// re-arm on foreground only; the reconciler is the one Android
// adaptation, because WorkManager one-shots do not repeat and an
// Android learner who never opens the app would otherwise get one
// streak nudge ever. Set RECONCILER_ENABLED to false to restore
// exact iOS foreground-only behaviour.
//
// Gates: refreshAll schedules nothing unless the system permission
// allows delivery AND masterEnabled is true. When that gate fails,
// the four pending work items below are cancelled, so a stale nudge
// can never outlive its permission. Each kind is then gated by its
// own toggle, and a kind whose toggle is off has its pending work
// cancelled.
//
// One pending work item per kind, unique-work names prefixed
// "pf.notify.". Scheduling a kind always replaces its existing work
// (ExistingWorkPolicy.REPLACE), so a refresh replaces rather than
// stacks. The offers kind is the one exception to replace-on-every-
// refresh: see the weekly cadence note in refreshOffers. All fire
// times fall between 09:00 and 20:00 local time, except the offer
// fire time, which is 7 days out at 11:00. Every work item carries
// the body and the deep-link route in its input data, for the
// delivery worker. No badge is set on any notification.
//
// WorkManager delivery is inexact: a one-shot fires at or after its
// delay, and Doze or battery optimisation may push it later. The
// slot times below are therefore targets, not exact alarms. Exact
// alarms (AlarmManager with SCHEDULE_EXACT_ALARM) were deliberately
// not used: the brief requires WorkManager, and an exact-alarm
// permission prompt would be a second permission ask for a reminder
// that stays useful when it arrives a little late.
//
// Routes (verified against TrainingHubDeepLink and DeepLinkRouter
// in GrowthModels.kt; the scheme is com.paymentstraininghub.app). Only TWO
// routes exist in this build:
//   lesson:      com.paymentstraininghub.app://lesson?id=<lesson id>
//   weekly case: com.paymentstraininghub.app://weekly-case
// There is NO route for progress/streak and NO route for
// checkout/purchases. Every scheduler below therefore uses only the
// two real routes: streak and continue learning open a real lesson
// link, new content opens the new lesson when one exists (weekly
// case otherwise), and offers open a locked lesson in the offered
// bundle, whose lock screen is the existing purchase path, or the
// weekly case for Practice Experiences.
//
// Copy: plain spoken language, short, no salesy advertising tone,
// no em dashes. The only brand name used is Payments Training Hub.
// Offer copy never states a price: the only purchase price is the
// Play Billing display price, shown by the store, never hardcoded.
// This file never reads ProductCatalogue prices.
//
// Android divergences from iOS, stated honestly:
//   1. No active-path candidate: Android v1 has no ActivePathStore,
//      so the continue-learning path fallback and the path leg of
//      the recent-chapter lookup are absent. The lesson candidates
//      below are the whole behaviour until a path store lands.
//   2. No lastLessonID key: Android v1 has no ProgressStore, so the
//      recent-chapter lookup uses the most recently touched lesson
//      from the recorded events as the last-lesson proxy.
//   3. Situations umbrella: the Integrated Course unlocks
//      Practice Experiences (situations), so full-course owners
//      never see a Practice Experiences offer. The offer check
//      below uses BillingManager.isSituationsUnlocked, which
//      enforces that umbrella.
//   4. Unmapped chapters: iOS fails closed (unmapped chapter is
//      locked); Android BillingManager leaves an unmapped chapter
//      open. lockedLessonRoute below uses the Android rule.
//   5. Offer checks read BillingManager, the Play-backed authority,
//      never EntitlementStore, which is a cache that Release builds
//      never consult to gate access. When BillingManager has not
//      finished loading (a fresh background process), offer
//      evaluation is skipped and existing offer work is left
//      untouched, so an empty in-memory owned set is never mistaken
//      for "everything locked".

package com.paymentstraininghub.app.features.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.CourseProduct
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.growth.TrainingHubDeepLink
import com.paymentstraininghub.app.features.habit.HabitLearningEvent
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.features.habit.LearningHabitPlanner
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NotificationSchedulers {

    // MARK: - Stored state (base keys, namespaced per account)

    /**
     * The seen-content marker: "v1" plus every catalogue entry id
     * on screen at the last live refresh, one per line. Compared
     * against the live catalogue to find genuinely new entries.
     * See refreshNewContent for the format and its limits.
     */
    private const val CONTENT_MARKER_BASE_KEY = "pf.notifications.lastSeenContentMarker.v1"

    /** When an offer was last scheduled, epoch millis as a string, for the weekly cadence. */
    private const val LAST_OFFER_SCHEDULED_BASE_KEY = "pf.notifications.lastOfferScheduledAt.v1"

    /** The product id of the last scheduled offer, so a pending offer for a bundle the learner has since unlocked can be dropped instead of firing. */
    private const val LAST_OFFER_PRODUCT_BASE_KEY = "pf.notifications.lastOfferProductID.v1"

    /**
     * Keys in this file that must never migrate from the guest
     * namespace to an account. Mirrored in AppStore's exclusion
     * list; keep the two in step. Guest notification state stays
     * with the guest namespace.
     */
    val nonMigratingBaseKeys: Set<String> = setOf(
        LocalNotificationService.PREFERENCES_BASE_KEY,
        CONTENT_MARKER_BASE_KEY,
        LAST_OFFER_SCHEDULED_BASE_KEY,
        LAST_OFFER_PRODUCT_BASE_KEY
    )

    // MARK: - Thresholds

    /**
     * Continue learning fires only when nothing in progress was
     * touched for this long, and no activity of any kind was
     * recorded for this long. 48 hours: a learner who was active
     * yesterday is not nagged about a lesson from last week.
     */
    private const val CONTINUE_STALE_INTERVAL_MILLIS: Long = 48L * 3600L * 1000L

    /** Offers schedule at most once per week. */
    private const val OFFER_CADENCE_MILLIS: Long = 7L * 24L * 3600L * 1000L

    /**
     * A scheduled fire time must be at least this far in the
     * future, so a refresh never schedules a notification for a
     * moment that has already passed.
     */
    private const val MINIMUM_LEAD_TIME_MILLIS: Long = 15L * 60L * 1000L

    /** The daily reconciler's unique-work name. */
    const val RECONCILER_WORK_NAME = "pf.notify.reconciler"

    /**
     * Whether the daily reconciler worker is enqueued. True: the
     * schedule is re-armed once a day even without a foreground.
     * False restores exact iOS foreground-only behaviour.
     */
    const val RECONCILER_ENABLED = true

    // MARK: - Entry point

    /**
     * Reconcile all four notification kinds with the current
     * preferences, permission state and recorded progress. Safe to
     * call on every foreground: each kind ends with at most one
     * pending work item, or none when its gate or condition fails.
     * Preferences and permission state are read fresh here, so this
     * is correct in a background worker process as well as in the
     * foreground process.
     */
    suspend fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val preferences = loadPreferences()
        val deliveryAllowed = LocalNotificationService.currentAuthorizationStatus(appContext) ==
            NotificationAuthorizationStatus.GRANTED
        if (!deliveryAllowed || !preferences.masterEnabled) {
            cancelAll(appContext)
            return
        }
        val events = runCatching { HabitStore.loadEvents() }.getOrDefault(emptyList())
        val completedIds = runCatching { HabitStore.loadCompletedLessonIds() }
            .getOrDefault(emptySet())
        val entries = loadCatalogueEntries()
        refreshStreakNudge(appContext, preferences.streakNudges, events, completedIds, entries)
        refreshContinueLearning(appContext, preferences.continueLearning, events, completedIds)
        refreshNewContent(appContext, preferences.newContentAlerts, completedIds, entries)
        refreshOffers(appContext, preferences.offers, events, completedIds, entries)
    }

    /** Cancel every pending work item belonging to this system. Habit reminder work is never touched. */
    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        for (kind in NotificationKind.localNotificationKinds) {
            workManager.cancelUniqueWork(kind.uniqueWorkName)
        }
    }

    /** Cancel one kind's pending work item. */
    fun cancelKind(context: Context, kind: NotificationKind) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(kind.uniqueWorkName)
    }

    /**
     * Enqueue the daily reconciler, which calls refreshAll once a
     * day. KEEP policy: repeat calls are harmless. WorkManager
     * persists unique periodic work across reboots, so no boot
     * receiver is needed.
     */
    fun enqueueReconciler(context: Context) {
        if (!RECONCILER_ENABLED) return
        val request = PeriodicWorkRequestBuilder<NotificationRefreshWorker>(
            1, TimeUnit.DAYS,
            4, TimeUnit.HOURS
        ).addTag("pf.notify").build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            RECONCILER_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private suspend fun loadPreferences(): NotificationPreferences {
        val raw = runCatching {
            AppStore.getString(LocalNotificationService.PREFERENCES_BASE_KEY)
        }.getOrNull()
        return NotificationPreferences.decode(raw)
    }

    /**
     * The live catalogue entries, or null when the catalogue cannot
     * be loaded. A null result means route resolution falls back to
     * the weekly case, and the new-content check does nothing.
     */
    private suspend fun loadCatalogueEntries(): List<SearchEntry>? {
        return runCatching { ContentRepository.loadSearchIndex().entries }.getOrNull()
    }

    // MARK: - Streak nudge

    /**
     * Schedule one evening nudge when a streak exists and the
     * learner has no recorded activity today. Signal:
     * LearningHabitPlanner.streakCount (consecutive active days
     * ending today or yesterday, computed from learning events)
     * greater than zero, and no event recorded today. Fire time:
     * today 19:00, or tomorrow 19:00 when today is too soon.
     * Route: the lesson to resume, or the weekly case when no
     * lesson can be picked.
     */
    private suspend fun refreshStreakNudge(
        context: Context,
        isEnabled: Boolean,
        events: List<HabitLearningEvent>,
        completedIds: Set<String>,
        entries: List<SearchEntry>?
    ) {
        if (!isEnabled) {
            cancelKind(context, NotificationKind.STREAK)
            return
        }
        val nowMillis = System.currentTimeMillis()
        val streak = LearningHabitPlanner.streakCount(events, nowMillis)
        val hasActivityToday = events.any { isSameDay(it.timestampMillis, nowMillis) }
        if (streak <= 0 || hasActivityToday) {
            cancelKind(context, NotificationKind.STREAK)
            return
        }
        val body = if (streak == 1) {
            "You have a 1 day streak. One short lesson keeps it going."
        } else {
            "You are on a $streak day streak. One short lesson keeps it going."
        }
        schedule(
            context = context,
            kind = NotificationKind.STREAK,
            body = body,
            route = resumeRoute(events, completedIds, entries),
            fireAtMillis = nextDaytimeSlot(hour = 19, minute = 0, nowMillis = nowMillis),
            productId = null
        )
    }

    // MARK: - Continue learning

    /**
     * Schedule one nudge when an in-progress lesson exists that the
     * learner has not returned to recently. Signal: the most
     * recently touched started-but-incomplete lesson (latest
     * lessonStarted, lessonCompleted, lessonDuration, audioListened
     * or quiz event for a lesson id that is not complete). The
     * iOS active-path fallback is absent: Android v1 has no path
     * store. The nudge fires only when that lesson was last
     * touched at least 48 hours ago AND no activity of any kind
     * was recorded in the last 48 hours. Bookmarks do not count as
     * touches: they record saving, not learning. Fire time: today
     * 18:00, or tomorrow 18:00 when today is too soon. Route: the
     * lesson link for the lesson itself.
     */
    private suspend fun refreshContinueLearning(
        context: Context,
        isEnabled: Boolean,
        events: List<HabitLearningEvent>,
        completedIds: Set<String>
    ) {
        if (!isEnabled) {
            cancelKind(context, NotificationKind.CONTINUE_LEARNING)
            return
        }
        val nowMillis = System.currentTimeMillis()
        val candidate = mostRecentIncompleteLesson(events, completedIds)
        val lastActivityMillis = events.maxOfOrNull { it.timestampMillis }
        val route = candidate?.let { TrainingHubDeepLink.lessonURL(it.lessonId) }
        if (candidate == null ||
            nowMillis - candidate.lastTouchedMillis < CONTINUE_STALE_INTERVAL_MILLIS ||
            lastActivityMillis == null ||
            nowMillis - lastActivityMillis < CONTINUE_STALE_INTERVAL_MILLIS ||
            route == null
        ) {
            cancelKind(context, NotificationKind.CONTINUE_LEARNING)
            return
        }
        schedule(
            context = context,
            kind = NotificationKind.CONTINUE_LEARNING,
            body = "Your next lesson is ready when you are. Pick it up when you have a few minutes.",
            route = route,
            fireAtMillis = nextDaytimeSlot(hour = 18, minute = 0, nowMillis = nowMillis),
            productId = null
        )
    }

    // MARK: - New content

    /**
     * Schedule one alert when the live catalogue contains entries
     * the learner has not seen. Freshness signal: the set of
     * catalogue entry ids. SearchEntry carries no date, version or
     * ordering field, so there is no "newer than" timestamp to
     * compare. The marker therefore stores the full set of ids
     * seen at the last live refresh, and an alert fires only when
     * the live set contains ids the marker does not. Catalogue
     * reorder, removal or rename alone never fires: only net new
     * ids fire, and content edits that keep the same id never
     * fire. The first refresh with no marker records the baseline
     * silently and schedules nothing, so the whole catalogue is
     * never announced as new. The marker advances to the current
     * live set on every live refresh, including when the alert is
     * scheduled, so one batch alerts at most once. A refresh that
     * cannot load the live catalogue never moves the marker and
     * never schedules (the Android equivalent of the iOS
     * isShowingCachedContent guard: Android keeps no disk cache
     * for the index, so a failed fetch simply yields nothing).
     * Fire time: today 12:00, or tomorrow 12:00 when today is too
     * soon. Route: the first new lesson in catalogue order, the
     * weekly case when the new entries are situations, or the
     * first incomplete lesson when the new entries are only
     * guides, labs, tests or FAQs.
     */
    private suspend fun refreshNewContent(
        context: Context,
        isEnabled: Boolean,
        completedIds: Set<String>,
        entries: List<SearchEntry>?
    ) {
        if (!isEnabled) {
            cancelKind(context, NotificationKind.NEW_CONTENT)
            return
        }
        if (entries == null) return
        val currentIDs = entries.map { it.id }.toSet()
        val stored = runCatching { AppStore.getString(CONTENT_MARKER_BASE_KEY) }.getOrNull()
        val seenIDs = stored?.let { seenIDsFrom(it) }
        if (seenIDs == null) {
            runCatching { AppStore.putString(CONTENT_MARKER_BASE_KEY, contentMarkerValue(currentIDs)) }
            cancelKind(context, NotificationKind.NEW_CONTENT)
            return
        }
        val newIDs = currentIDs - seenIDs
        runCatching { AppStore.putString(CONTENT_MARKER_BASE_KEY, contentMarkerValue(currentIDs)) }
        if (newIDs.isEmpty()) {
            cancelKind(context, NotificationKind.NEW_CONTENT)
            return
        }
        val newEntries = entries.filter { newIDs.contains(it.id) }
        val nowMillis = System.currentTimeMillis()
        val body: String
        val route: String
        val newLesson = newEntries.firstOrNull { it.isLesson }
        val newLessonRoute = newLesson?.let { TrainingHubDeepLink.lessonURL(it.id) }
        if (newLesson != null && newLessonRoute != null) {
            body = "New lessons have been added. Have a look when you have a few minutes."
            route = newLessonRoute
        } else if (newEntries.any { it.isSituation }) {
            body = "New payment cases have been added. Have a look when you have a few minutes."
            route = TrainingHubDeepLink.weeklyCaseURL()
        } else {
            val firstIncomplete = firstIncompleteLessonID(entries, completedIds)
            val firstIncompleteRoute = firstIncomplete?.let { TrainingHubDeepLink.lessonURL(it) }
            body = "New learning material has been added. Have a look when you have a few minutes."
            route = firstIncompleteRoute ?: TrainingHubDeepLink.weeklyCaseURL()
        }
        schedule(
            context = context,
            kind = NotificationKind.NEW_CONTENT,
            body = body,
            route = route,
            fireAtMillis = nextDaytimeSlot(hour = 12, minute = 0, nowMillis = nowMillis),
            productId = null
        )
    }

    /** The marker value for a set of ids: a version line, then one id per line, sorted. */
    private fun contentMarkerValue(ids: Set<String>): String {
        return "v1\n" + ids.sorted().joinToString("\n")
    }

    /** The ids stored in a marker, or null when the stored value is missing, unversioned or empty. */
    private fun seenIDsFrom(stored: String): Set<String>? {
        val lines = stored.split("\n")
        if (lines.firstOrNull() != "v1") return null
        val ids = lines.drop(1).filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return null
        return ids
    }

    // MARK: - Offers

    /**
     * One tasteful offer about a paid product, at most weekly.
     * Catalogue (at most 3 messages, all read-only against the
     * billing layer, and only for products BillingManager reports
     * NOT unlocked: isProductUnlocked for parts, and
     * isSituationsUnlocked for Practice Experiences):
     *   1. The Integrated Course, when the full course is locked.
     *   2. Practice Experiences, when situations are locked.
     *   3. The part for the chapter the learner most recently
     *      worked in, via productForChapter, when that chapter is
     *      locked and maps to a part.
     * When every eligible product is unlocked, nothing schedules.
     * Choice rotates by ISO week number over the eligible
     * messages, so the same message does not repeat every week
     * while more than one product is locked. Cadence: an offer is
     * scheduled only when none was scheduled in the last 7 days;
     * the pending work is then left untouched until the week
     * passes, because removing and re-adding it on every
     * foreground would push its fire date out forever. The fire
     * date is 7 days out at 11:00. Fully covered by the offers
     * toggle: off cancels the pending work. No prices, no gating
     * changes, no purchase code: the route opens a locked lesson
     * in the bundle, whose lock screen is the existing purchase
     * path, or the weekly case for Situations, because no
     * purchases route exists in this build.
     */
    private suspend fun refreshOffers(
        context: Context,
        isEnabled: Boolean,
        events: List<HabitLearningEvent>,
        completedIds: Set<String>,
        entries: List<SearchEntry>?
    ) {
        if (!isEnabled) {
            cancelKind(context, NotificationKind.OFFER)
            return
        }
        // BillingManager has not loaded yet (a fresh background
        // process): leave existing offer work untouched. An empty
        // in-memory owned set must never read as "all locked".
        if (!BillingManager.isReady.value) return
        val eligible = eligibleOffers(events, completedIds, entries)
        if (eligible.isEmpty()) {
            cancelKind(context, NotificationKind.OFFER)
            clearOfferState()
            return
        }
        val lastProductId = runCatching { AppStore.getString(LAST_OFFER_PRODUCT_BASE_KEY) }
            .getOrNull()
        if (lastProductId != null && isOfferProductUnlocked(lastProductId)) {
            cancelKind(context, NotificationKind.OFFER)
            clearOfferState()
        }
        val lastScheduledMillis = runCatching {
            AppStore.getString(LAST_OFFER_SCHEDULED_BASE_KEY)
        }.getOrNull()?.toLongOrNull()
        val nowMillis = System.currentTimeMillis()
        if (lastScheduledMillis != null &&
            nowMillis - lastScheduledMillis < OFFER_CADENCE_MILLIS
        ) {
            return
        }
        val week = isoWeekOfYear(nowMillis)
        val offer = eligible[week % eligible.size]
        schedule(
            context = context,
            kind = NotificationKind.OFFER,
            body = offer.body,
            route = offer.route,
            fireAtMillis = offerFireDate(nowMillis),
            productId = offer.productId
        )
        runCatching { AppStore.putString(LAST_OFFER_SCHEDULED_BASE_KEY, nowMillis.toString()) }
        runCatching { AppStore.putString(LAST_OFFER_PRODUCT_BASE_KEY, offer.productId) }
    }

    /** One offer message: the product it is about, its copy, and the deep-link route its tap opens. */
    private data class OfferMessage(
        val productId: String,
        val body: String,
        val route: String
    )

    /** The offer messages whose product is currently locked, in catalogue order: full course, Practice Experiences, recent chapter part. */
    private fun eligibleOffers(
        events: List<HabitLearningEvent>,
        completedIds: Set<String>,
        entries: List<SearchEntry>?
    ): List<OfferMessage> {
        val offers = mutableListOf<OfferMessage>()
        val fullCourseId = ProductCatalogue.integratedProductId
        if (!BillingManager.isProductUnlocked(fullCourseId)) {
            offers.add(
                OfferMessage(
                    productId = fullCourseId,
                    body = "The full course is there when you want every chapter in one place.",
                    route = lockedLessonRoute(entries, null)
                        ?: fallbackLessonRoute(entries, completedIds)
                        ?: TrainingHubDeepLink.weeklyCaseURL()
                )
            )
        }
        val situationsId = ProductCatalogue.practiceProductId
        if (!BillingManager.isSituationsUnlocked()) {
            offers.add(
                OfferMessage(
                    productId = situationsId,
                    body = "The full set of payment cases is there when you want to work through real situations.",
                    route = TrainingHubDeepLink.weeklyCaseURL()
                )
            )
        }
        val bundle = bundleForRecentChapter(events, completedIds)
        if (bundle != null && !BillingManager.isProductUnlocked(bundle.productId)) {
            offers.add(
                OfferMessage(
                    productId = bundle.productId,
                    body = "${bundle.name} is there when you want the full set of lessons on that topic.",
                    route = lockedLessonRoute(entries, bundle.chapterIds.toSet())
                        ?: fallbackLessonRoute(entries, completedIds)
                        ?: TrainingHubDeepLink.weeklyCaseURL()
                )
            )
        }
        return offers
    }

    /**
     * True when an offered product has since been unlocked. The
     * Practice Experiences product uses isSituationsUnlocked, so
     * the check matches the eligibility check exactly.
     */
    private fun isOfferProductUnlocked(productId: String): Boolean {
        return if (productId == ProductCatalogue.practiceProductId) {
            BillingManager.isSituationsUnlocked()
        } else {
            BillingManager.isProductUnlocked(productId)
        }
    }

    /**
     * The bundle that unlocks the chapter the learner most
     * recently worked in, from the most recent incomplete lesson,
     * then the last touched lesson. Nil when every chapter tried
     * is unlocked or maps to no bundle. The iOS path leg is
     * absent: Android v1 has no path store.
     */
    private fun bundleForRecentChapter(
        events: List<HabitLearningEvent>,
        completedIds: Set<String>
    ): CourseProduct? {
        val chapterIds = mutableListOf<String>()
        mostRecentIncompleteLesson(events, completedIds)?.let { recent ->
            chapterNumberFromLessonId(recent.lessonId)?.let { chapterIds.add("ch$it") }
        }
        lastTouchedLessonId(events)?.let { lastId ->
            chapterNumberFromLessonId(lastId)?.let { chapterIds.add("ch$it") }
        }
        for (chapterId in chapterIds) {
            val bundle = ProductCatalogue.productForChapter(chapterId) ?: continue
            if (!isChapterUnlocked(chapterId)) return bundle
        }
        return null
    }

    /** Clear the stored offer schedule state, for example when every bundle is unlocked or the offered bundle was bought. */
    private suspend fun clearOfferState() {
        runCatching { AppStore.remove(LAST_OFFER_SCHEDULED_BASE_KEY) }
        runCatching { AppStore.remove(LAST_OFFER_PRODUCT_BASE_KEY) }
    }

    /** The offer fire date: 7 days from now at 11:00 local, always in the future and always in daytime hours. */
    private fun offerFireDate(nowMillis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        calendar.add(Calendar.DAY_OF_YEAR, 7)
        calendar.set(Calendar.HOUR_OF_DAY, 11)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** The ISO 8601 week number (1 to 53) for a moment, in the local time zone. */
    private fun isoWeekOfYear(nowMillis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.minimalDaysInFirstWeek = 4
        calendar.timeInMillis = nowMillis
        return calendar.get(Calendar.WEEK_OF_YEAR)
    }

    // MARK: - Lesson resolution

    /** Event types that count as touching a lesson. Bookmarks are excluded: saving is not learning. */
    private val lessonTouchTypes: Set<String> = setOf(
        "lessonStarted", "lessonCompleted", "lessonDuration",
        "audioListened", "quizAttempted", "quizCompleted"
    )

    /** The latest touch per lesson id, from recorded events. */
    private fun lastTouchByLesson(events: List<HabitLearningEvent>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (event in events) {
            if (!lessonTouchTypes.contains(event.type)) continue
            val lessonId = event.lessonID ?: continue
            val existing = result[lessonId]
            if (existing == null || event.timestampMillis > existing) {
                result[lessonId] = event.timestampMillis
            }
        }
        return result
    }

    /** One touched lesson with its last touch time. */
    private data class TouchedLesson(val lessonId: String, val lastTouchedMillis: Long)

    /**
     * The most recently touched lesson that is not complete, with
     * its last touch time. Null when every touched lesson is
     * complete, or when no lesson was ever touched. Needs no
     * catalogue: the id alone builds the lesson link, and the
     * router resolves it against the catalogue when tapped.
     */
    private fun mostRecentIncompleteLesson(
        events: List<HabitLearningEvent>,
        completedIds: Set<String>
    ): TouchedLesson? {
        return lastTouchByLesson(events)
            .filter { !completedIds.contains(it.key) }
            .maxByOrNull { it.value }
            ?.let { TouchedLesson(lessonId = it.key, lastTouchedMillis = it.value) }
    }

    /** The lesson id of the most recently touched lesson of any kind, or null when nothing was touched. */
    private fun lastTouchedLessonId(events: List<HabitLearningEvent>): String? {
        return lastTouchByLesson(events).maxByOrNull { it.value }?.key
    }

    /** The first incomplete lesson id in catalogue order, or null while the catalogue is unavailable or everything is complete. */
    private fun firstIncompleteLessonID(
        entries: List<SearchEntry>?,
        completedIds: Set<String>
    ): String? {
        if (entries == null) return null
        return entries.firstOrNull { it.isLesson && !completedIds.contains(it.id) }?.id
    }

    /**
     * The route for resuming learning: the most recently touched
     * incomplete lesson, the first incomplete lesson in catalogue
     * order, or the weekly case when neither can be picked. (The
     * iOS active-path leg between the two is absent: Android v1
     * has no path store.)
     */
    private fun resumeRoute(
        events: List<HabitLearningEvent>,
        completedIds: Set<String>,
        entries: List<SearchEntry>?
    ): String {
        mostRecentIncompleteLesson(events, completedIds)?.let { recent ->
            TrainingHubDeepLink.lessonURL(recent.lessonId)?.let { return it }
        }
        firstIncompleteLessonID(entries, completedIds)?.let { lessonId ->
            TrainingHubDeepLink.lessonURL(lessonId)?.let { return it }
        }
        return TrainingHubDeepLink.weeklyCaseURL()
    }

    /** The route for the first incomplete lesson, for offer fallbacks. Null while the catalogue is unavailable or when everything is complete. */
    private fun fallbackLessonRoute(
        entries: List<SearchEntry>?,
        completedIds: Set<String>
    ): String? {
        val lessonId = firstIncompleteLessonID(entries, completedIds) ?: return null
        return TrainingHubDeepLink.lessonURL(lessonId)
    }

    /** The first lesson of each chapter: the free sample lessons. Offer routes skip these, so the tap lands on a locked lesson whose lock screen is the purchase path. */
    private fun sampleLessonIds(entries: List<SearchEntry>?): Set<String> {
        if (entries == null) return emptySet()
        val seenChapters = mutableSetOf<Int>()
        val samples = mutableSetOf<String>()
        for (entry in entries) {
            if (!entry.isLesson) continue
            val chapter = entry.chapterNumber ?: continue
            if (seenChapters.add(chapter)) {
                samples.add(entry.id)
            }
        }
        return samples
    }

    /**
     * True when a chapter is unlocked: its owning bundle is
     * unlocked, or the Integrated Course is unlocked (through
     * BillingManager's umbrella). A chapter no bundle contains
     * stays open, mirroring BillingManager.isLessonUnlocked,
     * rather than locking content the catalogue cannot map.
     */
    private fun isChapterUnlocked(chapterId: String): Boolean {
        val owningProductId = ProductCatalogue.productForChapter(chapterId)?.productId
            ?: return true
        return BillingManager.isProductUnlocked(owningProductId)
    }

    /**
     * A locked, non-sample lesson link: the first lesson in
     * catalogue order, optionally restricted to the given chapter
     * ids, whose chapter BillingManager reports locked. Tapping it
     * opens the lesson's locked state, which carries the existing
     * purchase path. Null when no such lesson exists in the loaded
     * catalogue, for example while it is still loading.
     */
    private fun lockedLessonRoute(
        entries: List<SearchEntry>?,
        onlyChapterIds: Set<String>?
    ): String? {
        if (entries == null) return null
        val samples = sampleLessonIds(entries)
        for (lesson in entries) {
            if (!lesson.isLesson) continue
            val chapterNumber = lesson.chapterNumber ?: continue
            val chapterId = "ch$chapterNumber"
            if (onlyChapterIds != null && !onlyChapterIds.contains(chapterId)) continue
            if (samples.contains(lesson.id)) continue
            if (isChapterUnlocked(chapterId)) continue
            TrainingHubDeepLink.lessonURL(lesson.id)?.let { return it }
        }
        return null
    }

    /** The chapter number from a lesson id (ch<N>s<M>), or null. Same rule as ContentRepository.chapterNumberFromId. */
    private fun chapterNumberFromLessonId(lessonId: String): Int? {
        if (!lessonId.startsWith("ch")) return null
        val digits = lessonId.drop(2).takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    // MARK: - Time helpers

    /**
     * The next daytime slot at the given hour: today when that
     * time is still at least the minimum lead time away,
     * otherwise tomorrow. The hour must be within 09:00 to 20:00;
     * every caller passes a daytime hour.
     */
    private fun nextDaytimeSlot(hour: Int, minute: Int, nowMillis: Long): Long {
        val earliest = nowMillis + MINIMUM_LEAD_TIME_MILLIS
        val today = Calendar.getInstance()
        today.timeInMillis = nowMillis
        today.set(Calendar.HOUR_OF_DAY, hour)
        today.set(Calendar.MINUTE, minute)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)
        if (today.timeInMillis > earliest) return today.timeInMillis
        today.add(Calendar.DAY_OF_YEAR, 1)
        return today.timeInMillis
    }

    private fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
        val first = Calendar.getInstance()
        first.timeInMillis = firstMillis
        val second = Calendar.getInstance()
        second.timeInMillis = secondMillis
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    // MARK: - Scheduling primitive

    /**
     * Replace any pending work with this kind's unique-work name
     * with one work item firing once at fireAtMillis. The input
     * data carries only the kind, the body, the route, the account
     * the schedule was made for, and (for offers) the product id,
     * so the delivery worker can re-validate before posting. The
     * delay is clamped to zero, so a fire time in the past (clock
     * changes between computing the slot and enqueueing) fires at
     * once instead of failing.
     */
    private suspend fun schedule(
        context: Context,
        kind: NotificationKind,
        body: String,
        route: String,
        fireAtMillis: Long,
        productId: String?
    ) {
        val userId = AppStore.currentUserId() ?: ""
        val delayMillis = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val input: Data = workDataOf(
            NotificationWorkInput.KEY_KIND to kind.name,
            NotificationWorkInput.KEY_BODY to body,
            NotificationWorkInput.KEY_ROUTE to route,
            NotificationWorkInput.KEY_USER_ID to userId,
            NotificationWorkInput.KEY_PRODUCT_ID to (productId ?: "")
        )
        val request = OneTimeWorkRequestBuilder<NotificationDeliveryWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag("pf.notify")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            kind.uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
