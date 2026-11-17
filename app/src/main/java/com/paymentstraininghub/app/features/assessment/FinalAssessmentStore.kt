// FinalAssessmentStore.kt
// Final Assessment attempts and course certificates, ported from
// iOS FinalAssessment.swift. A Final Assessment is 20 questions
// drawn ONLY from the real lesson quiz questions of one course
// bundle's chapters. The pass mark is strictly above 70 percent,
// so 15 or more correct out of 20. Each course allows at most 3
// attempts, tracked per course; when the attempts are exhausted
// without a pass the assessment locks and the learner must
// contact support. Passing issues a Certificate of Completion
// for that course, stored persistently so it survives restarts.
//
// This is one of two certificate systems, kept separate on
// purpose. CourseCertificate here is earned by passing a Final
// Assessment, stored as JSON, written to the course_certificates
// table, and issued in Certifier for the four certificate
// courses. PFCertificate (PathCertificates.kt) is earned by
// completing every lesson of a learning path, derived on read,
// never written with an exam score. Do not merge them.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.CourseProduct
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/// A Certificate of Completion earned by passing a course Final
/// Assessment. Stored as JSON, keyed by course.
data class CourseCertificate(
    val id: String,
    /// The Play product ID of the course, for example
    /// com.paymentstraininghub.app.part-1.
    val courseId: String,
    val courseName: String,
    val learnerName: String,
    val issuedAtMillis: Long,
    val correctCount: Int,
    val totalQuestions: Int
) {
    val scorePercent: Double
        get() = if (totalQuestions <= 0) 0.0
        else 100.0 * correctCount.toDouble() / totalQuestions.toDouble()

    val scoreText: String get() = "$correctCount/$totalQuestions"

    /// The full certificate title, for sharing and for Certifier.
    val certificateTitle: String get() = "Certificate in $courseName"

    val verificationUrl: String
        get() = "https://www.paymentstraininghub.com/verify?id=$id"

    val shareText: String
        get() = listOf(
            "Payments Training Hub Certificate of Completion",
            courseName,
            "Issued to $learnerName",
            "Score: $scoreText (${Math.round(scorePercent).toInt()}%)",
            "Certificate ID: $id",
            "Verify at $verificationUrl",
            "Certificate of Completion. This certifies completion of the named course. It is not an accredited qualification."
        ).joinToString("\n")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("courseId", courseId)
        .put("courseName", courseName)
        .put("learnerName", learnerName)
        .put("issuedAtMillis", issuedAtMillis)
        .put("correctCount", correctCount)
        .put("totalQuestions", totalQuestions)

    companion object {
        fun fromJson(json: JSONObject): CourseCertificate = CourseCertificate(
            id = json.getString("id"),
            courseId = json.getString("courseId"),
            courseName = json.getString("courseName"),
            learnerName = json.getString("learnerName"),
            issuedAtMillis = json.getLong("issuedAtMillis"),
            correctCount = json.getInt("correctCount"),
            totalQuestions = json.getInt("totalQuestions")
        )

        /// The logical, industry-trendy subject name shown on the
        /// certificate, instead of the raw course name. Only the
        /// four certificate courses have a mapped name; anything
        /// else falls back to the product name. The mappings follow
        /// the new parts: Integrated keeps the flagship subject,
        /// Part 3 is the messaging part, Part 9 is the BA/AI part,
        /// Part 2 is the rails part.
        fun certificateSubjectName(forProductId: String, fallback: String): String =
            when (forProductId) {
                "com.paymentstraininghub.app.integrated" -> "Digital Payments and Transaction Banking"
                "com.paymentstraininghub.app.part-3" -> "ISO 20022 and SWIFT Messaging"
                "com.paymentstraininghub.app.part-9" -> "AI Product Ownership for Payments"
                "com.paymentstraininghub.app.part-2" -> "Global Payment Rails"
                else -> fallback
            }
    }
}

/// Tracks Final Assessment attempts and issued course
/// certificates, persisted through AppStore (namespaced per
/// account, exactly as iOS AccountScopedStorage) so they survive
/// restarts. This store is the single source of truth for
/// attempts and certificates.
object FinalAssessmentStore {
    /// Questions in one Final Assessment.
    const val QUESTION_COUNT = 20

    /// Pass is strictly above 70 percent of 20, so 15 or more
    /// correct. 14 correct is exactly 70 percent and does not pass.
    const val PASS_CORRECT_COUNT = 15
    const val MAX_ATTEMPTS = 3

    /// Courses a Final Assessment can be taken for: every product
    /// with chapters (the 10 parts and the Integrated Course).
    /// Standalone products have no chapters and no assessment.
    val assessableCourses: List<CourseProduct>
        get() = ProductCatalogue.products.filter { it.chapterIds.isNotEmpty() }

    private const val CERTIFICATES_KEY = "pf.finalAssessment.certificates.v1"
    private const val ATTEMPTS_KEY = "pf.finalAssessment.attempts.v1"

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _certificates = MutableStateFlow<Map<String, CourseCertificate>>(emptyMap())
    val certificates: StateFlow<Map<String, CourseCertificate>> = _certificates.asStateFlow()

    private val _attemptsUsed = MutableStateFlow<Map<String, Int>>(emptyMap())
    val attemptsUsed: StateFlow<Map<String, Int>> = _attemptsUsed.asStateFlow()

    suspend fun refresh() {
        runCatching {
            AppStore.getString(CERTIFICATES_KEY)?.let { raw ->
                val root = JSONObject(raw)
                val restored = mutableMapOf<String, CourseCertificate>()
                for (key in root.keys()) {
                    runCatching {
                        restored[key] = CourseCertificate.fromJson(root.getJSONObject(key))
                    }
                }
                _certificates.value = restored
            }
        }
        runCatching {
            AppStore.getString(ATTEMPTS_KEY)?.let { raw ->
                val root = JSONObject(raw)
                val restored = mutableMapOf<String, Int>()
                for (key in root.keys()) {
                    runCatching { restored[key] = root.getInt(key) }
                }
                _attemptsUsed.value = restored
            }
        }
    }

    private suspend fun persist() {
        runCatching {
            val root = JSONObject()
            _certificates.value.forEach { (key, cert) -> root.put(key, cert.toJson()) }
            AppStore.putString(CERTIFICATES_KEY, root.toString())
        }
        runCatching {
            val root = JSONObject()
            _attemptsUsed.value.forEach { (key, count) -> root.put(key, count) }
            AppStore.putString(ATTEMPTS_KEY, root.toString())
        }
    }

    fun certificateFor(courseId: String): CourseCertificate? =
        _certificates.value[courseId]

    fun attemptsUsedCount(courseId: String): Int =
        _attemptsUsed.value[courseId] ?: 0

    fun attemptsRemaining(courseId: String): Int =
        maxOf(0, MAX_ATTEMPTS - attemptsUsedCount(courseId))

    fun hasPassed(courseId: String): Boolean =
        _certificates.value[courseId] != null

    /// True when the learner may start a new attempt: no
    /// certificate yet and at least one attempt left.
    fun canAttempt(courseId: String): Boolean =
        _certificates.value[courseId] == null && attemptsRemaining(courseId) > 0

    /// True when all 3 attempts were used without a pass.
    fun isLocked(courseId: String): Boolean =
        _certificates.value[courseId] == null && attemptsRemaining(courseId) == 0

    fun allCertificates(): List<CourseCertificate> =
        _certificates.value.values.sortedByDescending { it.issuedAtMillis }

    /// Record one completed attempt. Increments the attempt count
    /// and persists it. When the score passes, issues and stores
    /// the certificate and returns it; otherwise returns nil. A
    /// second call after a pass never issues a second certificate.
    suspend fun recordAttempt(
        course: CourseProduct,
        correct: Int,
        total: Int,
        learnerName: String
    ): CourseCertificate? {
        val used = attemptsUsedCount(course.productId) + 1
        _attemptsUsed.value = _attemptsUsed.value + (course.productId to used)
        var issued: CourseCertificate? = null
        if (correct >= PASS_CORRECT_COUNT && _certificates.value[course.productId] == null) {
            val cert = CourseCertificate(
                id = "PF-${course.id.uppercase()}-${UUID.randomUUID().toString().take(8).uppercase()}",
                courseId = course.productId,
                courseName = CourseCertificate.certificateSubjectName(
                    course.productId, course.name
                ),
                learnerName = learnerName,
                issuedAtMillis = System.currentTimeMillis(),
                correctCount = correct,
                totalQuestions = total
            )
            _certificates.value = _certificates.value + (course.productId to cert)
            issued = cert
        }
        persist()
        issued?.let { cert ->
            // Save a copy to Supabase so the certificate can be
            // verified on the website. Fire and forget: the local
            // certificate is the record of the pass, and a failed
            // save must never block it or confuse the learner.
            serverScope.launch { saveCertificateToServer(cert) }
        }
        return issued
    }

    /// Insert one issued certificate into the course_certificates
    /// table so the website verification page can check it. Silent
    /// on failure: the local certificate still counts. Guests have
    /// no account, so their certificates stay local only.
    private suspend fun saveCertificateToServer(certificate: CourseCertificate) {
        val userId = AuthManager.currentUserId() ?: return
        val token = runCatching { AuthManager.validAccessToken() }.getOrNull() ?: return
        val row = JSONObject()
            .put("id", certificate.id)
            .put("user_id", userId)
            .put("course_id", certificate.courseId)
            .put("course_name", certificate.courseName)
            .put("learner_name", certificate.learnerName)
            .put("issued_at", Instant.ofEpochMilli(certificate.issuedAtMillis).toString())
            .put("correct_count", certificate.correctCount)
            .put("total_questions", certificate.totalQuestions)
            .put("score_percent", Math.round(certificate.scorePercent).toInt())
        runCatching {
            SupabaseClient.restPost(
                table = "course_certificates",
                accessToken = token,
                bodyJson = JSONArray().put(row).toString()
            )
        }
    }
}
