// PinningObserverTest.kt
// Unit tests for the pure pin comparison. The interceptor itself
// needs a live TLS handshake and is verified by observation on
// device; the certificate-to-pin conversion is OkHttp library
// code (CertificatePinner.pin).

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.PinningObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinningObserverTest {

    private val host = "www.paymentstraininghub.com"
    private val pinA = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val pinB = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    @Test
    fun emptyConfigurationIsUnconfigured() {
        assertEquals(
            PinningObserver.PinEvaluation.UNCONFIGURED,
            PinningObserver.evaluate(host, listOf(pinA), emptyMap())
        )
    }

    @Test
    fun unlistedHostIsUnconfigured() {
        val configured = mapOf(host to setOf(pinA))
        assertEquals(
            PinningObserver.PinEvaluation.UNCONFIGURED,
            PinningObserver.evaluate("other.example.com", listOf(pinA), configured)
        )
    }

    @Test
    fun matchingPinMatches() {
        val configured = mapOf(host to setOf(pinA, pinB))
        assertEquals(
            PinningObserver.PinEvaluation.MATCH,
            PinningObserver.evaluate(host, listOf(pinB), configured)
        )
    }

    @Test
    fun noMatchingPinMismatches() {
        val configured = mapOf(host to setOf(pinA))
        assertEquals(
            PinningObserver.PinEvaluation.MISMATCH,
            PinningObserver.evaluate(host, listOf(pinB), configured)
        )
    }

    @Test
    fun emptyObservedListMismatchesWhenConfigured() {
        val configured = mapOf(host to setOf(pinA))
        assertEquals(
            PinningObserver.PinEvaluation.MISMATCH,
            PinningObserver.evaluate(host, emptyList(), configured)
        )
    }

    @Test
    fun shippedConfigurationIsEmpty() {
        // Report-only by design: no pin ships until pins are
        // collected from live device runs. If this fails, pins
        // were added and the enforcing story must be reviewed.
        assertTrue(PinningObserver.CONFIGURED_PINS.isEmpty())
    }

    @Test
    fun watchedHostsCoverAllFirstPartyHosts() {
        assertEquals(
            setOf(
                "eofsotgclhwsdwvbbtib.supabase.co",
                "www.paymentstraininghub.com",
                "paymentforge-audio.pages.dev",
                "paymentforge-audio-backup.pages.dev"
            ),
            PinningObserver.WATCHED_HOSTS
        )
    }
}
