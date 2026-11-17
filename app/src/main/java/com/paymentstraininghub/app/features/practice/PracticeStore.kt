// PracticeStore.kt
// Practice attempts, ported from PracticeStore.swift: stored
// locally first (account-namespaced, guests local only), then
// mirrored to the practice_attempts table when the learner is
// signed in. A failed mirror leaves synced false so the attempt
// is retried later. Follows the InvestigationStore pattern
// (AppStore, StateFlow, org.json).

package com.paymentstraininghub.app.features.practice

import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class PracticeAttempt(
    val id: String = UUID.randomUUID().toString(),
    val situationId: String,
    val userId: String?,
    val chosenOptionIndex: Int,
    val isCorrect: Boolean,
    val timestampMillis: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

object PracticeStore {
    private const val ATTEMPTS_KEY = "pf.practiceAttempts.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _attempts = MutableStateFlow<List<PracticeAttempt>>(emptyList())

    /** All attempts on this device, newest last. */
    val attempts: StateFlow<List<PracticeAttempt>> = _attempts.asStateFlow()

    val totalAttempts: Int get() = _attempts.value.size

    val correctCount: Int get() = _attempts.value.count { it.isCorrect }

    /**
     * Recorded accuracy, 0 to 100. Null when no attempt has been
     * recorded yet; callers must not present 0 as a real score.
     */
    val accuracy: Double?
        get() {
            val all = _attempts.value
            if (all.isEmpty()) return null
            return 100.0 * correctCount.toDouble() / all.size.toDouble()
        }

    /** True when at least one attempt is still waiting to be mirrored. */
    val hasUnsyncedAttempts: Boolean get() = _attempts.value.any { !it.synced }

    init {
        scope.launch { load() }
    }

    /**
     * Reload for the current account namespace, clearing the
     * previous account's attempts first. Guest attempts reach an
     * account only through the guest-to-account migration offer;
     * after migration copies them into the account namespace,
     * this reload loads them (still tagged with no user id) and
     * syncPendingAttempts adopts them on the next sync.
     */
    fun reloadForCurrentAccount() {
        _attempts.value = emptyList()
        scope.launch { load() }
    }

    /** Attempts for one situation, in the order they were made. */
    fun attemptsFor(situationId: String): List<PracticeAttempt> {
        return _attempts.value.filter { it.situationId == situationId }
    }

    /** The most recent attempt for one situation, if any. */
    fun lastAttemptFor(situationId: String): PracticeAttempt? {
        return attemptsFor(situationId).lastOrNull()
    }

    /**
     * Record one decision. The attempt is saved locally first,
     * then mirrored in the background when the learner is signed
     * in. Returns the recorded attempt.
     */
    fun recordAttempt(
        situationId: String,
        chosenOptionIndex: Int,
        isCorrect: Boolean
    ): PracticeAttempt {
        val attempt = PracticeAttempt(
            situationId = situationId,
            userId = AuthManager.currentUserId(),
            chosenOptionIndex = chosenOptionIndex,
            isCorrect = isCorrect
        )
        _attempts.value = _attempts.value + attempt
        scope.launch { save() }
        // Practice attempts sync through SyncService, so a new
        // attempt flags a sync.
        SyncService.noteLocalChange()
        scope.launch { mirror(attempt) }
        return attempt
    }

    /**
     * Mirror every still-unsynced attempt for the signed-in user.
     * Guest attempts (userId null) are adopted into the signed-in
     * account first, so practice done as a guest is not lost on
     * sign-in. Already-synced attempts are never re-adopted.
     * Safe to call any time; it is a no-op for guests.
     */
    fun syncPendingAttempts() {
        scope.launch {
            val userId = AuthManager.currentUserId() ?: return@launch
            val token = runCatching { AuthManager.validAccessToken() }.getOrNull()
                ?: return@launch
            val adopted = _attempts.value.map { attempt ->
                if (!attempt.synced && attempt.userId == null) {
                    attempt.copy(userId = userId)
                } else {
                    attempt
                }
            }
            if (adopted != _attempts.value) {
                _attempts.value = adopted
                save()
            }
            for (attempt in adopted) {
                if (!attempt.synced && attempt.userId != null) {
                    mirror(attempt, token)
                }
            }
        }
    }

    /**
     * Mirror one attempt to the practice_attempts table. No-op
     * for guests. A failure leaves synced false so the attempt
     * is retried later.
     */
    private suspend fun mirror(attempt: PracticeAttempt, token: String? = null) {
        val userId = attempt.userId ?: return
        val accessToken = token
            ?: runCatching { AuthManager.validAccessToken() }.getOrNull()
            ?: return
        val row = JSONObject().apply {
            put("user_id", userId)
            put("situation_id", attempt.situationId)
            put("chosen_option_index", attempt.chosenOptionIndex)
            put("is_correct", attempt.isCorrect)
            put("attempted_at", Instant.ofEpochMilli(attempt.timestampMillis).toString())
        }
        val body = JSONArray().apply { put(row) }.toString()
        val ok = runCatching {
            SupabaseClient.restPost("practice_attempts", accessToken, body)
        }.isSuccess
        if (ok) {
            markSynced(attempt.id)
        }
    }

    private suspend fun markSynced(id: String) {
        val updated = _attempts.value.map { attempt ->
            if (attempt.id == id) attempt.copy(synced = true) else attempt
        }
        if (updated != _attempts.value) {
            _attempts.value = updated
            save()
        }
    }

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(ATTEMPTS_KEY) }.getOrNull()
            ?: return
        if (raw.isBlank()) return
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PracticeAttempt(
                    id = item.getString("id"),
                    situationId = item.getString("situationId"),
                    userId = if (item.isNull("userId")) null else item.getString("userId"),
                    chosenOptionIndex = item.getInt("chosenOptionIndex"),
                    isCorrect = item.getBoolean("isCorrect"),
                    timestampMillis = item.getLong("timestampMillis"),
                    synced = item.getBoolean("synced")
                )
            }
        }.getOrElse { emptyList() }
        _attempts.value = parsed
    }

    private suspend fun save() {
        val array = JSONArray()
        for (attempt in _attempts.value) {
            array.put(
                JSONObject().apply {
                    put("id", attempt.id)
                    put("situationId", attempt.situationId)
                    if (attempt.userId == null) put("userId", JSONObject.NULL)
                    else put("userId", attempt.userId)
                    put("chosenOptionIndex", attempt.chosenOptionIndex)
                    put("isCorrect", attempt.isCorrect)
                    put("timestampMillis", attempt.timestampMillis)
                    put("synced", attempt.synced)
                }
            )
        }
        runCatching { AppStore.putString(ATTEMPTS_KEY, array.toString()) }
    }
}
