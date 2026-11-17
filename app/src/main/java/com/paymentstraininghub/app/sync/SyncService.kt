package com.paymentstraininghub.app.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Syncs local progress and preferences to Supabase after sign-in.
 * Mirrors iOS SyncService.swift. Signed-in users only: every entry
 * point is a no-op for guests and when Supabase is not configured.
 *
 * WHAT SYNCS in this skeleton:
 * - Lesson completions to user_progress (union merge).
 * - Quiz attempts to quiz_attempts (full history, read-first dedupe).
 * - Lesson bookmarks to lesson_bookmarks (union). Situation
 *   bookmarks have no server table and stay on the device.
 * - Learning preferences to user_preferences (local-saved-wins).
 *
 * WHAT DOES NOT SYNC here (follow-ups, documented at the end of
 * this header): learning_activity, user_learning_paths,
 * practice_attempts, path certificate backfill, notes (device-only),
 * the weekly goal (no server column), streaks and derived stats
 * (computed from events), purchases (record-purchase edge function
 * only), and profile rows (owned by AuthManager).
 *
 * CONFLICT RULES (implemented here, mirroring the iOS file header;
 * nothing is ever deleted on either side):
 * - Completed lessons: UNION of local and server. No updated_at
 *   comparison is performed: a lesson the server already has is
 *   never re-pushed, so the server keeps its own completed_at and
 *   updated_at, and a lesson completed on any device stays
 *   completed. Note: the coordinator brief described this as
 *   "server wins by updated_at for progress/quiz". That summary
 *   does not match the iOS source, which states explicitly that no
 *   updated_at comparison is performed for progress. The iOS
 *   behaviour is the spec, so union is what is implemented here.
 * - Quiz attempts: keyed by lesson, correct count, total and
 *   timestamp. Every attempt is kept; a local attempt is never
 *   dropped because a server read failed. A decode failure fails
 *   the sync instead, and the attempt stays local and pending.
 * - Bookmarks: union. There is no delete path: removing a bookmark
 *   on one device does not delete the server row.
 * - Learning preferences: local wins once the user has explicitly
 *   saved on this device (iOS D58); the server row only restores
 *   preferences on a device that never saved. This is deliberately
 *   NOT last-write-wins by updated_at: the server updated_at is
 *   decoded but never compared, and the local store keeps no
 *   updated_at. A sync run never overwrites a server preference
 *   row; only an explicit save pushes, through pushPreferences().
 *
 * IDEMPOTENT WRITES: user_progress, lesson_bookmarks and
 * user_preferences are written with restUpsert on their natural keys
 * (see SyncTables.ON_CONFLICT, taken from iOS), so a repeat push
 * updates the same row instead of duplicating it. quiz_attempts has
 * no unique key by design (history table, server-generated id), so
 * it is written with restPost, which is a plain PostgREST insert
 * matching iOS restInsert, and the read-first dedupe above is what
 * stops a retry from duplicating. Limitation carried over from iOS:
 * two quiz attempts with identical lesson, correct count, total and
 * timestamp (same second) are indistinguishable to the dedupe key
 * and collapse to one server row.
 *
 * ERROR HANDLING: a server decoding failure is a sync failure,
 * never an empty dataset. No local data is overwritten or wiped on
 * any failure. Network errors are retryable (bounded attempts per
 * trigger, then retry on foreground or on the next local change).
 * Auth errors stop the run after the fresh-token attempt:
 * validAccessToken() already refreshes an expiring token, so
 * looping would not help. If the signed-in user changes mid-sync,
 * the run is abandoned and nothing is written for the wrong
 * account.
 *
 * TRIGGERS: noteLocalChange() marks dirty state and starts a
 * debounced sync (800ms of quiet, per the coordinator brief; iOS
 * uses about 2 seconds). Returning to the foreground syncs when
 * work is pending or the last successful sync is stale (15 minutes,
 * as on iOS). syncAfterSignIn() runs a full refresh for the newly
 * signed-in user. Pending queue ops survive app restarts through
 * SyncPendingQueue; the local stores remain the durable record of
 * progress itself.
 *
 * CONTRACT SIGNATURES (confirmed against the landed Worker E
 * files by the coordinator; argument order and suspendness below
 * are the real ones, and the call sites in this file match them):
 * - object SupabaseClient: val BASE_URL, val PUBLISHABLE_KEY, and
 *   suspend fun restGet(table, accessToken, query): String,
 *   restPost(table, accessToken, bodyJson, query): String,
 *   restPatch(table, accessToken, bodyJson, query): String,
 *   restUpsert(table, accessToken, bodyJson, onConflict, query):
 *   String, restDelete(table, accessToken, query): String,
 *   invokeFunction(name, accessToken, bodyJson): String.
 *   Each returns the raw response body and throws on HTTP or
 *   network failure. Only restGet, restPost and restUpsert are
 *   used here; restDelete exists for a future delete caller.
 * - object AuthManager: suspend fun validAccessToken(): String?,
 *   fun currentUserId(): String?, and
 *   var onDeleteAccountLocal: (() -> Unit)?.
 * - object AppStore: suspend fun getString(baseKey): String?,
 *   putString(baseKey, value), getLong(baseKey): Long?,
 *   putLong(baseKey, value). The get/put calls take BASE keys and
 *   apply the account namespace internally; never wrap a key in
 *   keyFor() before passing it in, or the key is namespaced twice.
 *
 * WIRING (coordinator):
 * - Call SyncService.initialize(applicationContext) once from
 *   Application.onCreate, before startForegroundSync().
 * - Call SyncService.startForegroundSync() once to observe
 *   foreground returns.
 * - Wire account deletion by assigning AuthManager's hook to run
 *   clearForAccountDeletion() in a coroutine, because the hook is
 *   a plain (() -> Unit)? while clearing is suspend:
 *   AuthManager.onDeleteAccountLocal = {
 *       applicationScope.launch { SyncService.clearForAccountDeletion() }
 *   }
 *   AuthManager is Worker E's file and is not edited here.
 * - Set lessonIdsProvider to the known lesson ids (catalogue ids,
 *   or the ProgressStore follow-up) so local completion and quiz
 *   reads can enumerate lessons. TrainingHubApp sets it from
 *   ContentRepository.knownLessonIdsSnapshot() at startup; until
 *   the first index load those reads see an empty set and only
 *   server-to-local import runs.
 *
 * FOLLOW-UPS (not in skeleton scope):
 * 1. ProgressStore wiring: replace the defensive AppStore key reads
 *    (lesson.completed.<id>, quiz.answers.<id>) with the real store
 *    once it exists, including a last-accessed-lesson key (iOS
 *    fills it from the server's latest completed_at when the device
 *    has none; no Android key for it exists yet).
 * 2. Confirm the stored JSON shape of quiz.answers.<id> with
 *    Worker D and replace LocalQuizAttempt if it differs. Attempts
 *    without a stored attempted_at are stamped at push time, which
 *    weakens the dedupe key on retry; the store should persist a
 *    stable attempted_at per attempt.
 * 3. Confirm the local preferences keys with Worker D. The keys
 *    used here mirror the iOS pf.prefs.* base keys.
 * 4. learning_activity (one row per day, higher of local and server
 *    wins), user_learning_paths (highest progress wins), and
 *    practice_attempts (owned by the PracticeStore follow-up, with
 *    a synced flag) plus path certificate backfill.
 * 5. Queued "delete" ops are kept queued and skipped: restDelete
 *    now exists in SupabaseClient, but no caller enqueues deletes
 *    and the delete payload shape (a PostgREST filter query) is
 *    undefined until a caller defines it. Bookmarks have no
 *    delete path on iOS either, so nothing enqueues deletes
 *    today. The first delete caller must define the payload
 *    shape and wire the drainPendingQueue branch to restDelete.
 * 6. Replace the HTTP-status failure heuristic (classifyFailure)
 *    with Worker E's typed error model once it exists.
 */
object SyncService {

    private const val TAG = "SyncService"

    /** Debounce quiet period after the last local change. */
    private const val DEBOUNCE_MS = 800L

    /** A foreground return syncs when the last success is older than this. */
    private const val STALE_THRESHOLD_MS = 15 * 60 * 1000L

    /** Max attempts per syncAll() trigger for retryable failures. */
    private const val MAX_ATTEMPTS = 3

    /** Max replay attempts per queued op before it is left queued. */
    private const val MAX_OP_RETRIES = 3

    /** Persisted last-successful-sync key (account-scoped via keyFor). */
    private const val LAST_SYNCED_AT_KEY = "sync.lastSyncedAt.v1"

    /** Local completion key prefix, matching the Worker E/D scheme. */
    private const val LESSON_COMPLETED_PREFIX = "lesson.completed."

    /** Local quiz answers key prefix, matching the Worker E/D scheme. */
    private const val QUIZ_ANSWERS_PREFIX = "quiz.answers."

    /** Local lesson bookmarks key: one JSON string list. */
    private const val BOOKMARKS_KEY = "bookmarks.lessons.v1"

    /** Local preferences keys, mirroring the iOS pf.prefs.* base keys. */
    private const val PREFS_EXPERIENCE_LEVEL_KEY = "pf.prefs.experienceLevel.v1"
    private const val PREFS_DOMAIN_INTERESTS_KEY = "pf.prefs.domainInterests.v1"
    private const val PREFS_INCLUDE_AUDIO_KEY = "pf.prefs.includeAudio.v1"
    private const val PREFS_HAS_SAVED_KEY = "pf.prefs.hasSaved.v1"

    /**
     * JSON for server rows. Unknown server columns are ignored, so a
     * new column does not break decoding; missing required fields
     * still fail decoding, which fails the sync. Null optionals are
     * omitted on encode, matching iOS encodeIfPresent behaviour.
     */
    internal val syncJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val debounceLock = Any()
    private var debounceJob: Job? = null
    private var syncRequestedWhileRunning = false
    private var foregroundObserverStarted = AtomicBoolean(false)
    private var queue: SyncPendingQueue? = null
    private var lastSyncedLoaded = false

    /**
     * Supplies the lesson ids whose local keys are read. Until the
     * coordinator wires this (catalogue ids or ProgressStore), it
     * returns an empty set and local completion/quiz reads are
     * empty; server-to-local import still runs.
     */
    var lessonIdsProvider: (() -> Set<String>)? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges.asStateFlow()

    // MARK: - Setup

    /**
     * One-time setup. Creates the durable pending queue for this app
     * install and loads the persisted last-sync time for the current
     * account. Safe to call more than once; later calls are no-ops
     * for the queue but reload the last-sync time (for account
     * changes, syncAfterSignIn() also reloads it).
     */
    fun initialize(appContext: Context) {
        if (queue == null) {
            queue = SyncPendingQueue(appContext.applicationContext)
        }
        scope.launch { reloadLastSyncedAt() }
    }

    /**
     * Observe foreground returns and sync when work is pending or
     * the last successful sync is stale. Defaults to the process
     * lifecycle owner, so the app returning to the foreground is the
     * trigger. Calling more than once registers only one observer.
     */
    fun startForegroundSync(lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()) {
        if (!foregroundObserverStarted.compareAndSet(false, true)) return
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { syncIfNeededOnForeground() }
            }
        })
    }

    // MARK: - Entry points

    /**
     * Called after a guest signs in or registers. Runs a full
     * refresh for the newly signed-in user: each table pulls server
     * state first, then pushes local changes, so guest progress made
     * on this device migrates into the new account and server state
     * from other devices lands here. Any waiting debounced sync is
     * cancelled; this run replaces it.
     */
    suspend fun syncAfterSignIn() {
        cancelDebounce()
        reloadLastSyncedAt()
        syncAll()
    }

    /**
     * Sync everything wired for the signed-in user: the pending
     * queue, progress, quiz attempts, bookmarks and preferences.
     * Safe to call any time; it is a no-op for guests and when
     * Supabase is not configured. If a run is already in flight, the
     * request is remembered and one follow-up sync is scheduled when
     * the run finishes, instead of running two syncs at once.
     */
    suspend fun syncAll(): SyncResult {
        if (!isSupabaseConfigured()) {
            return SyncResult(success = false, skipped = true)
        }
        val userId = AuthManager.currentUserId() ?: run {
            return SyncResult(success = false, skipped = true)
        }
        if (!syncMutex.tryLock()) {
            syncRequestedWhileRunning = true
            _hasPendingChanges.value = true
            return SyncResult(
                success = false,
                error = "Sync already running; a follow-up run was scheduled."
            )
        }
        try {
            _status.value = SyncStatus.Syncing
            val runStartedAt = System.currentTimeMillis()
            var attempt = 0
            var delayMs = 1000L
            var lastError: String? = null
            while (attempt < MAX_ATTEMPTS) {
                attempt += 1
                try {
                    ensureSameUser(userId)
                    val token = AuthManager.validAccessToken()
                        ?: throw SyncAuthException("No access token for the signed-in user.")
                    ensureSameUser(userId)
                    val tablesSynced = mutableListOf<String>()
                    drainPendingQueue(userId, token)
                    ensureSameUser(userId)
                    syncProgress(userId, token)
                    tablesSynced.add(SyncTables.USER_PROGRESS)
                    ensureSameUser(userId)
                    syncQuizAttempts(userId, token)
                    tablesSynced.add(SyncTables.QUIZ_ATTEMPTS)
                    ensureSameUser(userId)
                    syncBookmarks(userId, token)
                    tablesSynced.add(SyncTables.LESSON_BOOKMARKS)
                    ensureSameUser(userId)
                    syncPreferences(userId, token)
                    tablesSynced.add(SyncTables.USER_PREFERENCES)
                    ensureSameUser(userId)
                    // Full success. Anything recorded mid-run stays
                    // pending through the dirty flag and its own
                    // debounced sync, which runs as the follow-up.
                    val now = System.currentTimeMillis()
                    persistLastSyncedAt(now)
                    _lastSyncedAt.value = now
                    _hasPendingChanges.value = false
                    _status.value = SyncStatus.Succeeded(now)
                    return SyncResult(
                        success = true,
                        syncedAt = now,
                        tablesSynced = tablesSynced,
                        pendingRemaining = pendingCount()
                    )
                } catch (e: SyncAbortException) {
                    // The signed-in user changed mid-sync. Abandon
                    // quietly: pending state is kept, local data is
                    // untouched, and nothing is written for the
                    // wrong account. No error is surfaced.
                    Log.i(TAG, "Sync abandoned: the signed-in user changed mid-sync. Pending state is kept.")
                    _status.value = SyncStatus.Idle
                    return SyncResult(success = false, abandoned = true, pendingRemaining = pendingCount())
                } catch (e: Exception) {
                    // Local val: lastError is a nullable var and the
                    // compiler cannot smart-cast it in the branches below.
                    val failureMessage = e.message ?: e.javaClass.simpleName
                    lastError = failureMessage
                    Log.w(TAG, "Sync attempt $attempt of $MAX_ATTEMPTS failed: $failureMessage")
                    _hasPendingChanges.value = true
                    when (classifyFailure(e)) {
                        FailureCategory.AUTH, FailureCategory.TERMINAL -> {
                            _status.value = SyncStatus.Failed(failureMessage)
                            return SyncResult(
                                success = false,
                                error = failureMessage,
                                pendingRemaining = pendingCount()
                            )
                        }
                        FailureCategory.RETRYABLE -> {
                            if (attempt >= MAX_ATTEMPTS) {
                                _status.value = SyncStatus.Failed(failureMessage)
                                return SyncResult(
                                    success = false,
                                    error = failureMessage,
                                    pendingRemaining = pendingCount()
                                )
                            }
                            delay(delayMs)
                            delayMs *= 2
                            if (AuthManager.currentUserId() != userId) {
                                _status.value = SyncStatus.Idle
                                return SyncResult(success = false, abandoned = true, pendingRemaining = pendingCount())
                            }
                        }
                    }
                }
            }
            val reason = lastError ?: "Sync failed."
            _status.value = SyncStatus.Failed(reason)
            return SyncResult(success = false, error = reason, pendingRemaining = pendingCount())
        } finally {
            syncMutex.unlock()
            if (syncRequestedWhileRunning) {
                syncRequestedWhileRunning = false
                if (_hasPendingChanges.value) {
                    scheduleDebouncedSync()
                }
            }
        }
    }

    /**
     * Mark local progress as changed, so a sync picks it up soon.
     * Starts a debounced sync (800ms of quiet), coalescing bursts of
     * changes into one run. Safe to call from any thread and with no
     * async context. The durable record of the progress itself is
     * the local stores; this only schedules the sync run.
     */
    fun noteLocalChange() {
        _hasPendingChanges.value = true
        scheduleDebouncedSync()
    }

    /**
     * Drop all pending sync work for the current account: the durable
     * queue, the persisted last-sync time and any waiting debounced
     * sync. Needed by account deletion, so a deleted account leaves
     * no sync work behind. This drops only the "sync needed" record
     * and the queue; it never touches local progress, which the
     * deletion flow clears separately.
     *
     * Serialized on the sync mutex: when a sync run is in flight,
     * the clear waits for it to finish instead of racing it, so
     * the run cannot re-queue work after the clear or read a
     * half-cleared queue. The in-flight run also aborts on its
     * own once the session clears (ensureSameUser), so the wait
     * is bounded by one run. Never called from inside a sync run,
     * so the mutex cannot deadlock here.
     */
    suspend fun clearPendingOperations() {
        syncMutex.withLock {
            cancelDebounce()
            queue?.clear()
            persistLastSyncedAt(0L)
            _lastSyncedAt.value = null
            _hasPendingChanges.value = false
            _status.value = SyncStatus.Idle
        }
    }

    /**
     * Clear all sync state for account deletion. The coordinator
     * wires this into AuthManager.onDeleteAccountLocal (see the file
     * header for the wiring snippet). Identical to
     * clearPendingOperations(); kept as a separate named entry point
     * so the deletion wiring reads as deletion, not as a generic
     * clear.
     */
    suspend fun clearForAccountDeletion() {
        clearPendingOperations()
    }

    /**
     * Push one set of preferences now, called after the user saves on
     * the preferences screen. No-op for guests. Returns true when
     * the server confirmed the write, false when it did not: the
     * caller must not claim the preferences synced when this is
     * false. The local copy is already saved either way. A failed
     * push marks pending work so a later sync retries it.
     */
    suspend fun pushPreferences(preferences: UserPreferencesRow): Boolean {
        val userId = AuthManager.currentUserId() ?: return false
        if (!isSupabaseConfigured()) return false
        val token = try {
            AuthManager.validAccessToken()
        } catch (e: Exception) {
            null
        } ?: run {
            _hasPendingChanges.value = true
            return false
        }
        val row = preferences.copy(userId = userId, updatedAt = nowIso())
        return try {
            ensureSameUser(userId)
            SupabaseClient.restUpsert(
                SyncTables.USER_PREFERENCES,
                token,
                syncJson.encodeToString(listOf(row)),
                SyncTables.ON_CONFLICT.getValue(SyncTables.USER_PREFERENCES)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "pushPreferences failed: ${e.message}")
            _hasPendingChanges.value = true
            false
        }
    }

    // MARK: - Triggers and pending state

    private fun scheduleDebouncedSync() {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                syncAll()
            }
        }
    }

    private fun cancelDebounce() {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = null
        }
    }

    /**
     * Foreground trigger: sync when work is pending, when no sync
     * has ever succeeded, or when the last successful sync is stale.
     * A foreground return with a recent successful sync and no
     * pending work does nothing.
     */
    private suspend fun syncIfNeededOnForeground() {
        if (!isSupabaseConfigured()) return
        if (AuthManager.currentUserId() == null) return
        reloadLastSyncedAtIfNeeded()
        val lastSync = _lastSyncedAt.value
        val isStale = lastSync == null || System.currentTimeMillis() - lastSync > STALE_THRESHOLD_MS
        val pending = _hasPendingChanges.value || pendingCount() > 0
        if (!pending && !isStale) return
        if (syncMutex.isLocked) {
            syncRequestedWhileRunning = true
            return
        }
        cancelDebounce()
        syncAll()
    }

    private suspend fun pendingCount(): Int = try {
        queue?.peekAll()?.size ?: 0
    } catch (e: Exception) {
        0
    }

    private suspend fun reloadLastSyncedAt() {
        try {
            val stored: Long? = AppStore.getLong(LAST_SYNCED_AT_KEY)
            _lastSyncedAt.value = stored?.takeIf { it > 0L }
            lastSyncedLoaded = true
        } catch (e: Exception) {
            _lastSyncedAt.value = null
            lastSyncedLoaded = true
        }
    }

    private suspend fun reloadLastSyncedAtIfNeeded() {
        if (!lastSyncedLoaded) reloadLastSyncedAt()
    }

    private suspend fun persistLastSyncedAt(epochMillis: Long) {
        try {
            AppStore.putLong(LAST_SYNCED_AT_KEY, epochMillis)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist the last sync time.", e)
        }
    }

    private fun isSupabaseConfigured(): Boolean {
        return try {
            SupabaseClient.BASE_URL.isNotBlank() && SupabaseClient.PUBLISHABLE_KEY.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Account guard and failure classification

    /** Throw unless the given user is still the signed-in user. */
    private fun ensureSameUser(userId: String) {
        if (AuthManager.currentUserId() != userId) {
            throw SyncAbortException()
        }
    }

    private enum class FailureCategory { RETRYABLE, AUTH, TERMINAL }

    /**
     * Classify a failure. Decode failures are terminal: the payload
     * will not change by retrying immediately, and retrying must
     * never read the failure as empty data. Auth failures stop after
     * the fresh-token attempt. Everything else is treated as
     * retryable within this trigger. The HTTP status check is a
     * heuristic on the exception message until Worker E exposes a
     * typed error model; see follow-up 6 in the file header.
     */
    private fun classifyFailure(e: Exception): FailureCategory {
        if (e is SyncDecodeException || e is SerializationException) return FailureCategory.TERMINAL
        if (e is SyncAuthException) return FailureCategory.AUTH
        val message = e.message.orEmpty()
        if (message.contains("401") || message.contains("403") ||
            message.contains("Unauthorized", ignoreCase = true) ||
            message.contains("Forbidden", ignoreCase = true)
        ) {
            return FailureCategory.AUTH
        }
        return FailureCategory.RETRYABLE
    }

    /**
     * Decode one table's server rows. A decoding failure is thrown
     * with the table named: it must NEVER be treated as an empty
     * dataset, because an empty dataset would make the merge push
     * local state over server state it never saw, and would hide the
     * failure from every later retry.
     */
    private inline fun <reified T> decodeServerRows(body: String, table: String): List<T> {
        try {
            return syncJson.decodeFromString<List<T>>(body)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode server rows for table $table (${body.length} bytes). Treating this as a sync failure, not as an empty dataset.", e)
            throw SyncDecodeException(table, e)
        }
    }

    // MARK: - Pending queue drain

    /**
     * Replay queued ops, oldest first, before the table steps. An op
     * at the retry cap is left queued and skipped, so a poison op
     * stops blocking later runs after 3 attempts. A failed replay
     * increments the op's retry count, keeps it queued, and aborts
     * this run (the remaining ops stay queued for the next trigger).
     * "delete" ops are kept queued and skipped: no caller enqueues
     * deletes, and the delete payload shape is undefined until one
     * does (see follow-up 5 in the header; restDelete exists for
     * that caller). An "upsert" for a history table
     * with no conflict key (quiz_attempts, practice_attempts) is
     * replayed as a plain insert through restPost.
     */
    private suspend fun drainPendingQueue(userId: String, token: String) {
        val pendingQueue = queue ?: return
        val ops = pendingQueue.peekAll()
        for (op in ops) {
            ensureSameUser(userId)
            if (op.retryCount >= MAX_OP_RETRIES) continue
            when (op.action) {
                SyncPendingOp.ACTION_DELETE -> {
                    // No caller enqueues deletes, and the delete
                    // payload shape is undefined. Kept queued so a
                    // future delete is never silently lost; see
                    // follow-up 5 in the header.
                    continue
                }
                SyncPendingOp.ACTION_UPSERT -> {
                    try {
                        val onConflict = SyncTables.ON_CONFLICT[op.table]
                        if (onConflict != null) {
                            SupabaseClient.restUpsert(op.table, token, op.payloadJson, onConflict)
                        } else {
                            SupabaseClient.restPost(op.table, token, op.payloadJson)
                        }
                        pendingQueue.remove(op.id)
                    } catch (e: Exception) {
                        pendingQueue.incrementRetryCount(op.id)
                        throw e
                    }
                }
                else -> {
                    // Unknown action: never drop the op, never guess
                    // a replay. It stays queued for a later build.
                    Log.w(TAG, "Unknown pending op action '${op.action}' for table ${op.table}; leaving it queued.")
                    continue
                }
            }
        }
    }

    // MARK: - Progress

    /**
     * Pull server progress, merge with local progress, push only
     * what the server does not have yet. Completion is monotonic:
     * the merged set is the union, a lesson the server already has
     * keeps its own completed_at and updated_at because it is never
     * re-pushed, and local wins only for lessons the server has no
     * record of. Server lessons are imported locally first, so the
     * device reflects the merged set even if the push fails.
     */
    private suspend fun syncProgress(userId: String, token: String) {
        ensureSameUser(userId)
        val lessonIds = lessonIdsProvider?.invoke() ?: emptySet()
        val localCompleted = lessonIds.filter { isLessonCompletedLocally(it) }.toSet()

        val body = SupabaseClient.restGet(
            SyncTables.USER_PROGRESS,
            token,
            "select=user_id,lesson_id,completed,completed_at,updated_at&user_id=eq.${encode(userId)}"
        )
        val serverRows = decodeServerRows<ServerProgressRow>(body, SyncTables.USER_PROGRESS)
        val serverCompleted = serverRows.filter { it.completed }.map { it.lessonId }.toSet()

        for (lessonId in serverCompleted) {
            if (lessonId !in localCompleted) {
                markLessonCompletedLocally(lessonId)
            }
        }

        val localOnly = localCompleted - serverCompleted
        if (localOnly.isEmpty()) return
        ensureSameUser(userId)
        val now = nowIso()
        val rows = localOnly.map { lessonId ->
            ServerProgressRow(
                userId = userId,
                lessonId = lessonId,
                completed = true,
                completedAt = now,
                updatedAt = now
            )
        }
        SupabaseClient.restUpsert(
            SyncTables.USER_PROGRESS,
            token,
            syncJson.encodeToString(rows),
            SyncTables.ON_CONFLICT.getValue(SyncTables.USER_PROGRESS)
        )
    }

    // MARK: - Quiz attempts

    /**
     * Push the FULL local quiz history the server does not have yet.
     * Attempts are history and are never overwritten: a local attempt
     * is pushed when no server row matches its lesson, correct count,
     * total and timestamp. quiz_attempts has no unique key, so the
     * write is a plain insert through restPost and the read-first
     * dedupe above is what stops a retry from duplicating. A decode
     * failure throws before any push, so a local attempt is never
     * dropped because the server reply could not be read. When there
     * are no local attempts, the server is not read at all, matching
     * iOS; quiz history is push-only and is never imported locally.
     */
    private suspend fun syncQuizAttempts(userId: String, token: String) {
        ensureSameUser(userId)
        val lessonIds = lessonIdsProvider?.invoke() ?: emptySet()
        val localAttempts = lessonIds.flatMap { lessonId ->
            readLocalQuizAttempts(lessonId).map { attempt ->
                attempt.copy(lessonId = attempt.lessonId ?: lessonId)
            }
        }
        if (localAttempts.isEmpty()) return

        val body = SupabaseClient.restGet(
            SyncTables.QUIZ_ATTEMPTS,
            token,
            "select=*&user_id=eq.${encode(userId)}"
        )
        val serverAttempts = decodeServerRows<QuizAttemptRow>(body, SyncTables.QUIZ_ATTEMPTS)
        val serverKeys = serverAttempts.map { attempt ->
            dedupeKey(attempt.lessonId, attempt.correct, attempt.total, attempt.attemptedAt.orEmpty())
        }.toSet()

        val newRows = localAttempts.mapNotNull { attempt ->
            val lessonId = attempt.lessonId ?: return@mapNotNull null
            // Attempts without a stored timestamp are stamped at push
            // time; see follow-up 2 in the file header.
            val attemptedAt = attempt.attemptedAt ?: nowIso()
            val key = dedupeKey(lessonId, attempt.correct, attempt.total, attemptedAt)
            if (key in serverKeys) return@mapNotNull null
            QuizAttemptRow(
                userId = userId,
                lessonId = lessonId,
                correct = attempt.correct,
                total = attempt.total,
                score = attempt.correct,
                attemptedAt = attemptedAt
            )
        }
        if (newRows.isEmpty()) return
        ensureSameUser(userId)
        SupabaseClient.restPost(
            SyncTables.QUIZ_ATTEMPTS,
            token,
            syncJson.encodeToString(newRows)
        )
    }

    private fun dedupeKey(lessonId: String, correct: Int, total: Int, attemptedAt: String): String =
        "$lessonId|$correct|$total|$attemptedAt"

    // MARK: - Bookmarks

    /**
     * Sync lesson bookmarks: union of the device set and the server
     * rows. Server-only bookmarks are added on this device, and
     * local-only bookmarks are pushed. The upsert key
     * (user_id, lesson_id) stops duplicates. Situation bookmarks
     * have no server table and stay on the device only.
     */
    private suspend fun syncBookmarks(userId: String, token: String) {
        ensureSameUser(userId)
        val local = readLocalBookmarks().toSet()
        val body = SupabaseClient.restGet(
            SyncTables.LESSON_BOOKMARKS,
            token,
            "select=user_id,lesson_id&user_id=eq.${encode(userId)}"
        )
        val serverRows = decodeServerRows<LessonBookmarkRow>(body, SyncTables.LESSON_BOOKMARKS)
        val serverIds = serverRows.map { it.lessonId }.toSet()
        ensureSameUser(userId)
        val merged = local + serverIds
        if (merged != local) {
            writeLocalBookmarks(merged)
        }
        val localOnly = local - serverIds
        if (localOnly.isEmpty()) return
        ensureSameUser(userId)
        val rows = localOnly.map { LessonBookmarkRow(userId = userId, lessonId = it) }
        SupabaseClient.restUpsert(
            SyncTables.LESSON_BOOKMARKS,
            token,
            syncJson.encodeToString(rows),
            SyncTables.ON_CONFLICT.getValue(SyncTables.LESSON_BOOKMARKS)
        )
    }

    // MARK: - Preferences

    /**
     * Sync learning preferences. Local is authoritative once the user
     * has explicitly saved on this device: a stale server row never
     * overwrites it. The server row is only used to restore
     * preferences on a device that never saved them, and local is
     * only pushed to the server when it was explicitly saved and no
     * server row exists, so a default row is never created from
     * untouched defaults. A sync run never overwrites an existing
     * server row: only an explicit save pushes, through
     * pushPreferences(). This rule is deliberately not
     * last-write-wins by updated_at: the server updated_at is
     * decoded but never compared. The weekly goal is not merged
     * here: it lives only in the progress store, and no server
     * column is written for it.
     */
    private suspend fun syncPreferences(userId: String, token: String) {
        ensureSameUser(userId)
        val local = readLocalPreferences()
        val localSaved = hasSavedPreferencesLocally()
        val body = SupabaseClient.restGet(
            SyncTables.USER_PREFERENCES,
            token,
            "select=*&user_id=eq.${encode(userId)}"
        )
        val serverRows = decodeServerRows<UserPreferencesRow>(body, SyncTables.USER_PREFERENCES)
        val server = serverRows.firstOrNull()
        if (server != null) {
            if (localSaved) {
                // Local wins: the user saved on this device. The
                // server row is left alone; an explicit save pushes
                // through pushPreferences(), not through syncAll().
                return
            }
            // Never saved on this device: restore the account row.
            writeLocalPreferences(server.copy(userId = userId))
            return
        }
        if (!localSaved) {
            // No server row and nothing explicitly saved: do not
            // create a row from untouched defaults.
            return
        }
        ensureSameUser(userId)
        val row = local.copy(userId = userId, updatedAt = nowIso())
        SupabaseClient.restUpsert(
            SyncTables.USER_PREFERENCES,
            token,
            syncJson.encodeToString(listOf(row)),
            SyncTables.ON_CONFLICT.getValue(SyncTables.USER_PREFERENCES)
        )
    }

    // MARK: - Local data reads (defensive; absent means empty)

    private suspend fun isLessonCompletedLocally(lessonId: String): Boolean {
        val value = readAppString(LESSON_COMPLETED_PREFIX + lessonId) ?: return false
        return value == "true" || value == "1"
    }

    private suspend fun markLessonCompletedLocally(lessonId: String) {
        writeAppString(LESSON_COMPLETED_PREFIX + lessonId, "true")
    }

    /**
     * Defensive read of one lesson's stored quiz attempts. A missing
     * key reads as no attempts. A value that does not decode in the
     * provisional LocalQuizAttempt shape is skipped for that lesson
     * only: the stored shape is unconfirmed (see follow-up 2), so a
     * local shape mismatch must not fail the whole sync. This is
     * local bookkeeping, not server data: server decode failures
     * still throw in decodeServerRows.
     */
    private suspend fun readLocalQuizAttempts(lessonId: String): List<LocalQuizAttempt> {
        val raw = readAppString(QUIZ_ANSWERS_PREFIX + lessonId) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            syncJson.decodeFromString<List<LocalQuizAttempt>>(raw)
        } catch (e: Exception) {
            try {
                listOf(syncJson.decodeFromString<LocalQuizAttempt>(raw))
            } catch (e2: Exception) {
                Log.w(TAG, "Stored quiz data for lesson $lessonId did not decode; skipping it for this sync.")
                emptyList()
            }
        }
    }

    private suspend fun readLocalBookmarks(): List<String> {
        val raw = readAppString(BOOKMARKS_KEY) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            syncJson.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Stored bookmarks did not decode; treating them as empty for this sync.")
            emptyList()
        }
    }

    private suspend fun writeLocalBookmarks(lessonIds: Set<String>) {
        try {
            writeAppString(BOOKMARKS_KEY, syncJson.encodeToString(lessonIds.sorted()))
        } catch (e: Exception) {
            Log.w(TAG, "Could not write merged bookmarks locally.", e)
        }
    }

    private suspend fun readLocalPreferences(): UserPreferencesRow {
        val interestsRaw = readAppString(PREFS_DOMAIN_INTERESTS_KEY)
        val interests = if (interestsRaw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                syncJson.decodeFromString<List<String>>(interestsRaw)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val includeAudioRaw = readAppString(PREFS_INCLUDE_AUDIO_KEY)
        return UserPreferencesRow(
            userId = null,
            experienceLevel = readAppString(PREFS_EXPERIENCE_LEVEL_KEY),
            domainInterests = interests,
            includeAudio = includeAudioRaw?.toBooleanStrictOrNull() ?: true,
            updatedAt = null
        )
    }

    private suspend fun hasSavedPreferencesLocally(): Boolean {
        val value = readAppString(PREFS_HAS_SAVED_KEY) ?: return false
        return value == "true" || value == "1"
    }

    private suspend fun writeLocalPreferences(preferences: UserPreferencesRow) {
        try {
            if (preferences.experienceLevel != null) {
                writeAppString(PREFS_EXPERIENCE_LEVEL_KEY, preferences.experienceLevel)
            }
            writeAppString(
                PREFS_DOMAIN_INTERESTS_KEY,
                syncJson.encodeToString(preferences.domainInterests)
            )
            writeAppString(PREFS_INCLUDE_AUDIO_KEY, preferences.includeAudio.toString())
            writeAppString(PREFS_HAS_SAVED_KEY, "true")
        } catch (e: Exception) {
            Log.w(TAG, "Could not save restored preferences locally.", e)
        }
    }

    private suspend fun readAppString(baseKey: String): String? {
        return try {
            AppStore.getString(baseKey)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeAppString(baseKey: String, value: String) {
        AppStore.putString(baseKey, value)
    }

    // MARK: - Helpers

    private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

/** Thrown when the signed-in user changes mid-sync. The run is abandoned. */
internal class SyncAbortException : Exception()

/** Thrown when no usable access token is available. Stops the run without retry. */
internal class SyncAuthException(message: String) : Exception(message)

/**
 * Thrown when a table's server rows cannot be decoded. Terminal: the
 * sync fails and the failure is reported, never read as empty data.
 */
internal class SyncDecodeException(val table: String, cause: Throwable) :
    Exception("Could not decode server rows for table $table.", cause)
