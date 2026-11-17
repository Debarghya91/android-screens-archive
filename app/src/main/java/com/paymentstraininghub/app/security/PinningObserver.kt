// PinningObserver.kt
// Payments Training Hub Android - certificate pinning in REPORT-ONLY
// mode.
//
// This interceptor observes the TLS handshake of every OkHttp call,
// computes the SHA-256 public-key pins of the presented chain with
// OkHttp's own CertificatePinner.pin(), and logs what it saw. It
// NEVER fails a request: a mismatch is logged and the call proceeds.
//
// Why report-only: enforcing pins that cannot be verified from the
// build environment would risk bricking the app for every user the
// moment a host rotates a certificate (Cloudflare and Supabase both
// rotate; leaf pins go stale in weeks). The pins for the four hosts
// could not be fetched and verified from this environment, so the
// configured pin set is intentionally empty: the observer records
// the real pins from live runs (see the Diagnostics view), and
// enforcement can be switched on only once pins are collected from
// real devices and a rotation process exists. Backup pins are part
// of that future step: at least two pins per host (leaf plus
// intermediate), so one rotation never locks the app out.
//
// Attach with addNetworkInterceptor: only a network interceptor
// sees the TLS handshake. Streaming through ExoPlayer uses
// HttpURLConnection, not OkHttp, so it is not observed here; that
// is an honest gap, not a silent one.
//
// The pin comparison is the pure [evaluate] function, unit-tested
// with fake pin strings; the certificate-to-pin conversion is
// OkHttp library code.

package com.paymentstraininghub.app.security

import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.Response
import java.security.cert.Certificate

object PinningObserver : Interceptor {

    private const val TAG = "PinningObserver"

    /** Hosts whose pins are watched. Pins are collected, never enforced. */
    val WATCHED_HOSTS: Set<String> = setOf(
        "eofsotgclhwsdwvbbtib.supabase.co",
        "www.paymentstraininghub.com",
        "paymentforge-audio.pages.dev",
        "paymentforge-audio-backup.pages.dev"
    )

    /**
     * Configured pins per host. EMPTY BY DESIGN: no pin could be
     * verified from the build environment, so nothing is enforced.
     * Fill this only with pins collected from live device runs,
     * with at least two pins per host, and only together with a
     * rotation process. See the file header.
     */
    val CONFIGURED_PINS: Map<String, Set<String>> = emptyMap()

    /** What one observation means. */
    enum class PinEvaluation {
        /** No pins are configured for this host: observation only. */
        UNCONFIGURED,

        /** At least one presented pin matches the configured set. */
        MATCH,

        /** No presented pin matches the configured set. Logged only. */
        MISMATCH
    }

    /** The last observed pins per host, for the Diagnostics view. */
    private val observedPins: MutableMap<String, List<String>> = mutableMapOf()
    private val loggedHosts: MutableSet<String> = mutableSetOf()

    /** A snapshot of the pins observed so far this run. */
    @Synchronized
    fun observationsSnapshot(): Map<String, List<String>> = observedPins.toMap()

    /**
     * Pure comparison: the observed pins against the configured
     * pins for one host. Unit-tested.
     */
    fun evaluate(host: String, observed: List<String>, configured: Map<String, Set<String>>): PinEvaluation {
        val expected = configured[host] ?: return PinEvaluation.UNCONFIGURED
        if (expected.isEmpty()) return PinEvaluation.UNCONFIGURED
        return if (observed.any { it in expected }) PinEvaluation.MATCH else PinEvaluation.MISMATCH
    }

    /** The SHA-256 public-key pin for one certificate, in OkHttp's "sha256/..." form. */
    fun pinFor(certificate: Certificate): String = CertificatePinner.pin(certificate)

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val response = chain.proceed(chain.request())
        if (host in WATCHED_HOSTS) {
            runCatching { observe(host, chain) }
        }
        return response
    }

    private fun observe(host: String, chain: Interceptor.Chain) {
        val handshake = chain.connection()?.handshake() ?: return
        val pins = handshake.peerCertificates.map { pinFor(it) }
        val firstSight: Boolean
        synchronized(this) {
            observedPins[host] = pins
            firstSight = loggedHosts.add(host)
        }
        when (evaluate(host, pins, CONFIGURED_PINS)) {
            PinEvaluation.MISMATCH -> {
                // Report-only: log loudly, never fail the request.
                Log.w(TAG, "TLS pin MISMATCH for $host (report-only, call allowed): $pins")
            }
            PinEvaluation.MATCH -> {
                if (firstSight) Log.i(TAG, "TLS pins match for $host")
            }
            PinEvaluation.UNCONFIGURED -> {
                if (firstSight) Log.i(TAG, "TLS pins observed for $host (report-only): $pins")
            }
        }
    }
}
