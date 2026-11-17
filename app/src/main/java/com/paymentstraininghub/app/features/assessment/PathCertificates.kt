// PathCertificates.kt
// Learning-path certificates, ported from iOS LearningPath.swift
// (PFCertificate and PFCertificateStore). A path certificate is
// issued ONLY when every required lesson of its path is complete.
// The overall score comes from CompetencyStore and is shown only
// when an assessment has actually been taken. The issued date is
// stored the first time a completed path is viewed, and never
// changes after that. The certificate id is stable per path.
//
// This is the second certificate system, kept separate from
// CourseCertificate (FinalAssessmentStore.kt) on purpose: path
// certificates are derived on read, never written to Supabase
// with an exam score, and carry an optional competency average
// instead. Do not merge them.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.data.CompetencyStore
import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.model.SearchEntry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class PFCertificate(
    val id: String,
    val pathId: String,
    val pathTitle: String,
    val learnerName: String,
    val issuedAtMillis: Long,
    /// Overall competency score at issue time, nil when no
    /// assessment had been taken yet. Never a fabricated score.
    val overallScore: Double?
) {
    val verificationUrl: String
        get() = "https://www.paymentstraininghub.com/certificate/$id"

    val shareText: String
        get() {
            val lines = mutableListOf(
                "Payments Training Hub Certificate of Completion",
                pathTitle,
                "Issued to $learnerName",
                "Certificate ID: $id",
                "Verify at $verificationUrl"
            )
            overallScore?.let { lines.add("Overall score: ${Math.round(it).toInt()}%") }
            lines.add(
                "Certificate of Completion. This certifies completion of the named learning journey. " +
                    "It is not an accredited qualification."
            )
            return lines.joinToString("\n")
        }
}

/// Issues and re-reads path certificates. No in-memory cache:
//  issued dates, ids and the synced set are read from AppStore
/// on each access, so an account switch can never serve another
/// learner's certificates.
object PFCertificateStore {

    private fun issuedAtKey(pathId: String): String =
        "pf.certificateIssuedAt.$pathId.v1"

    private fun certificateIdKey(pathId: String): String =
        "pf.certificateID.$pathId.v1"

    /// The certificate for a path, or null when the path is not
    /// complete yet or has no certificate. Never issues for an
    /// incomplete path.
    suspend fun certificateFor(
        path: LearningPath,
        entries: List<SearchEntry>,
        completedLessonIds: Set<String>,
        learnerName: String
    ): PFCertificate? {
        val title = path.certificateTitle ?: return null
        if (!path.isComplete(entries, completedLessonIds)) return null
        val issuedAt = AppStore.getLong(issuedAtKey(path.id))?.takeIf { it > 0 }
            ?: System.currentTimeMillis().also { AppStore.putLong(issuedAtKey(path.id), it) }
        return PFCertificate(
            id = stableCertificateId(path.id),
            pathId = path.id,
            pathTitle = title,
            learnerName = learnerName,
            issuedAtMillis = issuedAt,
            overallScore = if (CompetencyStore.hasAssessments) CompetencyStore.overallScore else null
        )
    }

    /// A stable certificate id per path, so the same certificate
    /// keeps the same id across views: it is generated once and
    /// stored.
    suspend fun stableCertificateId(pathId: String): String {
        AppStore.getString(certificateIdKey(pathId))?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = "PF-${pathId.uppercase()}-${UUID.randomUUID().toString().take(8).uppercase()}"
        AppStore.putString(certificateIdKey(pathId), fresh)
        return fresh
    }

    // MARK: - Server verification copy

    /// Key for the set of path certificate ids already written to
    /// the course_certificates table. An id is added only after a
    /// successful insert, never after a failure, so a failed write
    /// is retried on the next trigger.
    private const val SYNCED_IDS_KEY = "pf.pathCertificatesSynced.v1"

    private val syncMutex = Mutex()
    private val inFlightIds = mutableSetOf<String>()

    private suspend fun syncedIds(): Set<String> {
        val raw = runCatching { AppStore.getString(SYNCED_IDS_KEY) }.getOrNull() ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun markSynced(id: String) {
        val next = syncedIds() + id
        val array = JSONArray()
        next.sorted().forEach { array.put(it) }
        runCatching { AppStore.putString(SYNCED_IDS_KEY, array.toString()) }
    }

    /// Insert one path certificate into the course_certificates
    /// table so the website verification page can check it.
    /// Signed-in users only, fire and forget, silent on every
    /// failure. The local certificate always still counts. A
    /// certificate whose learner name is the "Guest Learner"
    /// fallback is never written: learner_name must be a real
    /// signed-in name. The path id and the certificate title are
    /// the row's course id and course name: paths have no product
    /// id, and nothing else may be invented. Path certificates
    /// have no exam, so the score columns are omitted entirely:
    /// no exam score is ever invented.
    suspend fun syncCertificateToServer(certificate: PFCertificate) {
        val shouldRun = syncMutex.withLock {
            if (certificate.id in inFlightIds) return@withLock false
            inFlightIds.add(certificate.id)
            true
        }
        if (!shouldRun) return
        try {
            if (certificate.id in syncedIds()) return
            val trimmedName = certificate.learnerName.trim()
            if (trimmedName.isEmpty() || trimmedName == "Guest Learner") return
            val userId = AuthManager.currentUserId() ?: return
            val token = runCatching { AuthManager.validAccessToken() }.getOrNull() ?: return
            val row = JSONObject()
                .put("id", certificate.id)
                .put("user_id", userId)
                .put("course_id", certificate.pathId)
                .put("course_name", certificate.pathTitle)
                .put("learner_name", certificate.learnerName)
                .put("issued_at", Instant.ofEpochMilli(certificate.issuedAtMillis).toString())
                .put("certificate_kind", "path")
            certificate.overallScore?.let { row.put("competency_average", it) }
            runCatching {
                SupabaseClient.restPost(
                    table = "course_certificates",
                    accessToken = token,
                    bodyJson = JSONArray().put(row).toString()
                )
            }.onSuccess { markSynced(certificate.id) }
        } finally {
            syncMutex.withLock { inFlightIds.remove(certificate.id) }
        }
    }
}
