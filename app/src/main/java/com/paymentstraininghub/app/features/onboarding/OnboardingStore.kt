// OnboardingStore.kt
// Keeps the onboarding profile for the current account namespace
// and the install-level completed flag, ported from
// OnboardingStore in OnboardingProfile.swift. The profile is
// stored per account namespace through AppStore; the completed
// flag is install level (TourStore), with no account namespace,
// so signing in, signing out, or a crash and relaunch never
// shows onboarding again. The flag is written the moment
// onboarding completes or is skipped, before the profile write,
// so completion survives a crash. Reload never touches the flag.

package com.paymentstraininghub.app.features.onboarding

import android.content.Context
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.features.tour.TourStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object OnboardingStore {
    const val PROFILE_BASE_KEY = "pf.onboarding.profile.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _profile = MutableStateFlow<OnboardingProfile?>(null)
    val profile: StateFlow<OnboardingProfile?> = _profile.asStateFlow()

    init {
        scope.launch { load() }
    }

    /**
     * Save a completed profile and mark onboarding complete on
     * this install. The flag is written first, before the profile
     * is stored. A diagnostic score outside 0 to 1 is clamped
     * into range; no other value is rewritten.
     */
    fun saveProfile(context: Context, profile: OnboardingProfile) {
        TourStore.markOnboardingCompleted(context)
        val stored = profile.copy(
            diagnosticScore = profile.diagnosticScore?.coerceIn(0.0, 1.0)
        )
        _profile.value = stored
        scope.launch { persist(stored) }
    }

    /**
     * Record that onboarding was skipped: no profile is kept, and
     * onboarding is marked complete on this install so the flow
     * is not shown again, on any account. The weekly goal is
     * untouched: a skip must not change it.
     */
    fun markSkipped(context: Context) {
        TourStore.markOnboardingCompleted(context)
        _profile.value = null
        scope.launch {
            runCatching { AppStore.remove(PROFILE_BASE_KEY) }
        }
    }

    /** Reload for the current account namespace. Never touches the completed flag. */
    fun reloadForCurrentAccount() {
        _profile.value = null
        scope.launch { load() }
    }

    private suspend fun load() {
        val raw = runCatching { AppStore.getString(PROFILE_BASE_KEY) }.getOrNull()
            ?: return
        if (raw.isBlank()) return
        val parsed = runCatching {
            val item = JSONObject(raw)
            val role = LearnerRole.fromRawValue(item.getString("role")) ?: return@runCatching null
            val level = LearnerKnowledgeLevel.fromRawValue(item.getString("knowledgeLevel"))
                ?: return@runCatching null
            val objective = LearningObjective.fromRawValue(item.getString("objective"))
                ?: return@runCatching null
            val intensity = LearningIntensity.fromRawValue(item.getString("intensity"))
                ?: return@runCatching null
            OnboardingProfile(
                role = role,
                knowledgeLevel = level,
                objective = objective,
                intensity = intensity,
                weeklyTarget = item.getInt("weeklyTarget"),
                diagnosticScore = if (item.isNull("diagnosticScore")) null
                else item.getDouble("diagnosticScore"),
                diagnosticTakenAtMillis = if (item.isNull("diagnosticTakenAtMillis")) null
                else item.getLong("diagnosticTakenAtMillis"),
                completedAtMillis = item.getLong("completedAtMillis"),
                wasSkipped = item.getBoolean("wasSkipped")
            )
        }.getOrNull()
        _profile.value = parsed
    }

    private suspend fun persist(profile: OnboardingProfile) {
        val item = JSONObject().apply {
            put("role", profile.role.rawValue)
            put("knowledgeLevel", profile.knowledgeLevel.rawValue)
            put("objective", profile.objective.rawValue)
            put("intensity", profile.intensity.rawValue)
            put("weeklyTarget", profile.weeklyTarget)
            if (profile.diagnosticScore == null) put("diagnosticScore", JSONObject.NULL)
            else put("diagnosticScore", profile.diagnosticScore)
            if (profile.diagnosticTakenAtMillis == null) {
                put("diagnosticTakenAtMillis", JSONObject.NULL)
            } else {
                put("diagnosticTakenAtMillis", profile.diagnosticTakenAtMillis)
            }
            put("completedAtMillis", profile.completedAtMillis)
            put("wasSkipped", profile.wasSkipped)
        }
        runCatching { AppStore.putString(PROFILE_BASE_KEY, item.toString()) }
    }
}
