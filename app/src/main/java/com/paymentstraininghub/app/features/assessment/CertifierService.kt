// CertifierService.kt
// Certifier digital credentials, ported from iOS
// CertifierService.swift. After a learner passes a course Final
// Assessment, the certificate view asks the
// issue-certifier-credential edge function to issue a Certifier
// credential for that certificate. The edge function returns the
// Certifier credential reference, the Payments Training Hub
// verification link, the QR code data and a LinkedIn
// add-to-profile URL. The first call issues the credential;
// later calls return the stored record, so one certificate costs
// one Certifier credit. Guests have no server certificate row,
// so they never reach this service.

package com.paymentstraininghub.app.features.assessment

import com.paymentstraininghub.app.auth.SupabaseClient
import org.json.JSONObject

/// The issue-certifier-credential edge function reply, decoded.
data class CertifierCredential(
    val ok: Boolean,
    val alreadyIssued: Boolean,
    val certificateId: String,
    val course: String,
    val certifierCredentialId: String?,
    val certifierPublicId: String?,
    val certifierUrl: String?,
    val verificationUrl: String,
    val linkedinUrl: String?,
    val qrData: String?
) {
    companion object {
        fun fromJson(json: JSONObject): CertifierCredential = CertifierCredential(
            ok = json.optBoolean("ok", false),
            alreadyIssued = json.optBoolean("already_issued", false),
            certificateId = json.optString("certificate_id", ""),
            course = json.optString("course", ""),
            certifierCredentialId = json.optString("certifier_credential_id").takeIf { it.isNotEmpty() },
            certifierPublicId = json.optString("certifier_public_id").takeIf { it.isNotEmpty() },
            certifierUrl = json.optString("certifier_url").takeIf { it.isNotEmpty() },
            verificationUrl = json.optString("verification_url", ""),
            linkedinUrl = json.optString("linkedin_url").takeIf { it.isNotEmpty() },
            qrData = json.optString("qr_data").takeIf { it.isNotEmpty() }
        )
    }
}

/// Asks the edge function to issue (or return) the Certifier
/// credential for one earned certificate.
object CertifierService {

    /// The four certificate courses, by product ID. Any other
    /// course has no Certifier template and is never sent. The
    /// slug VALUES are the server-side template names and are
    /// unchanged, so the server contract is untouched; only the
    /// product-id keys follow the new catalogue (Integrated,
    /// Part 3, Part 9, Part 2). The certificate rule itself is
    /// still open (proposal question 13); this mapping keeps the
    /// existing four templates reachable under the new ids.
    fun courseSlug(forProductId: String): String? = when (forProductId) {
        "com.paymentstraininghub.app.integrated" -> "full"
        "com.paymentstraininghub.app.part-3" -> "messaging"
        "com.paymentstraininghub.app.part-9" -> "ba-practice"
        "com.paymentstraininghub.app.part-2" -> "rails"
        else -> null
    }

    /// One in-memory result per certificate id, so opening the
    /// same certificate twice in one session does not call the
    /// server twice. The server is still the record of what was
    /// issued.
    private val cache = mutableMapOf<String, CertifierCredential>()

    /// Issue the Certifier credential for this certificate, or
    /// return the stored one when it was already issued. Returns
    /// null when the learner is not signed in, the course has no
    /// Certifier template, the certificate is not on the server
    /// yet, or the server call fails. A null result never blocks
    /// the certificate itself: the /verify page still works.
    suspend fun requestCredential(
        certificate: CourseCertificate,
        accessToken: String?,
        recipientEmail: String?
    ): CertifierCredential? {
        cache[certificate.id]?.let { return it }
        val course = courseSlug(certificate.courseId) ?: return null
        val token = accessToken ?: return null
        val email = recipientEmail?.takeIf { it.isNotEmpty() } ?: return null
        val body = JSONObject()
            .put("certificate_id", certificate.id)
            .put("course", course)
            .put("recipient_name", certificate.learnerName)
            .put("recipient_email", email)
            .toString()
        return runCatching {
            val response = SupabaseClient.invokeFunction(
                name = "issue-certifier-credential",
                accessToken = token,
                bodyJson = body
            )
            val decoded = CertifierCredential.fromJson(JSONObject(response))
            if (!decoded.ok) return null
            cache[certificate.id] = decoded
            decoded
        }.getOrNull()
    }
}
