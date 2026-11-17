// TamperCheckTest.kt
// Unit tests for the pure parts of TamperCheck: SHA-256 hex,
// hash normalisation, signature comparison and installer
// classification. The PackageManager reads are not unit-testable
// on the JVM.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.TamperCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperCheckTest {

    @Test
    fun sha256HexEmptyString() {
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            TamperCheck.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun sha256HexAbc() {
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            TamperCheck.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun normaliseHashStripsSeparatorsAndUppercases() {
        assertEquals(
            "AABBCCDD",
            TamperCheck.normaliseHash("aa:bb-cc dd"),
        )
    }

    @Test
    fun compareSignatureMatchesCaseAndColonInsensitively() {
        assertEquals(
            TamperCheck.SignatureVerdict.Match,
            TamperCheck.compareSignature("aa:bb:cc", "AABBCC"),
        )
    }

    @Test
    fun compareSignatureMismatch() {
        assertEquals(
            TamperCheck.SignatureVerdict.Mismatch,
            TamperCheck.compareSignature("AABBCC", "DDEEFF"),
        )
    }

    @Test
    fun compareSignatureUnconfiguredWhenExpectedBlank() {
        assertEquals(
            TamperCheck.SignatureVerdict.Unconfigured,
            TamperCheck.compareSignature("AABBCC", ""),
        )
        assertEquals(
            TamperCheck.SignatureVerdict.Unconfigured,
            TamperCheck.compareSignature(null, "   "),
        )
    }

    @Test
    fun compareSignatureUnreadableWhenObservedNull() {
        assertEquals(
            TamperCheck.SignatureVerdict.Unreadable,
            TamperCheck.compareSignature(null, "AABBCC"),
        )
    }

    @Test
    fun classifyInstallerPlayStore() {
        assertEquals(
            TamperCheck.InstallerVerdict.PlayStore,
            TamperCheck.classifyInstaller("com.android.vending"),
        )
    }

    @Test
    fun classifyInstallerOtherAndNull() {
        assertTrue(
            TamperCheck.classifyInstaller("com.example.other")
                is TamperCheck.InstallerVerdict.Other,
        )
        assertTrue(
            TamperCheck.classifyInstaller(null)
                is TamperCheck.InstallerVerdict.Other,
        )
    }
}
