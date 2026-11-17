// PremiumAccessGateTest.kt
// Unit tests for the pure gate decision matrix. The Android
// gathering (PackageManager, SharedPreferences, Play Integrity)
// is not unit-testable on the JVM.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.PremiumAccessGate
import com.paymentstraininghub.app.security.PremiumAccessGate.BlockReason
import com.paymentstraininghub.app.security.PremiumAccessGate.GateState
import com.paymentstraininghub.app.security.PremiumAccessGate.IntegrityGateInput
import com.paymentstraininghub.app.security.TamperCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumAccessGateTest {

    private val play = TamperCheck.InstallerVerdict.PlayStore
    private val other = TamperCheck.InstallerVerdict.Other("com.example.other")
    private val match = TamperCheck.SignatureVerdict.Match
    private val unconfigured = TamperCheck.SignatureVerdict.Unconfigured

    private fun decide(
        debugBuild: Boolean = false,
        rooted: Boolean = false,
        installer: TamperCheck.InstallerVerdict = play,
        signature: TamperCheck.SignatureVerdict = match,
        integrity: IntegrityGateInput = IntegrityGateInput.VERIFIED_PASS,
        cachedPassFresh: Boolean = false,
    ): GateState = PremiumAccessGate.decide(
        debugBuild = debugBuild,
        rooted = rooted,
        installer = installer,
        signature = signature,
        integrity = integrity,
        cachedPassFresh = cachedPassFresh,
    )

    @Test
    fun debugBuildAlwaysAllowedWithoutIntegrity() {
        assertEquals(
            GateState.Allowed(integrityVerified = false),
            decide(debugBuild = true, rooted = true, installer = other),
        )
    }

    @Test
    fun cleanReleaseWithVerifiedPassIsAllowedAndVerified() {
        assertEquals(
            GateState.Allowed(integrityVerified = true),
            decide(),
        )
    }

    @Test
    fun rootedIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.ROOTED),
            decide(rooted = true),
        )
    }

    @Test
    fun nonPlayInstallerIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.NON_PLAY_INSTALLER),
            decide(installer = other),
        )
        assertEquals(
            GateState.Blocked(BlockReason.NON_PLAY_INSTALLER),
            decide(installer = TamperCheck.InstallerVerdict.Other(null)),
        )
    }

    @Test
    fun signatureMismatchIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.SIGNATURE_MISMATCH),
            decide(signature = TamperCheck.SignatureVerdict.Mismatch),
        )
    }

    @Test
    fun unreadableSignatureIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.SIGNATURE_UNREADABLE),
            decide(signature = TamperCheck.SignatureVerdict.Unreadable),
        )
    }

    @Test
    fun unconfiguredSignatureProceedsToIntegrity() {
        assertEquals(
            GateState.Allowed(integrityVerified = true),
            decide(signature = unconfigured),
        )
    }

    @Test
    fun failedIntegrityVerdictIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.INTEGRITY_FAILED),
            decide(integrity = IntegrityGateInput.VERIFIED_FAIL),
        )
    }

    @Test
    fun unavailableIntegrityWithoutCacheIsBlocked() {
        assertEquals(
            GateState.Blocked(BlockReason.INTEGRITY_UNAVAILABLE),
            decide(integrity = IntegrityGateInput.UNAVAILABLE, cachedPassFresh = false),
        )
    }

    @Test
    fun unavailableIntegrityWithFreshCacheIsAllowed() {
        assertEquals(
            GateState.Allowed(integrityVerified = true),
            decide(integrity = IntegrityGateInput.UNAVAILABLE, cachedPassFresh = true),
        )
    }

    @Test
    fun notConfiguredIntegrityIsDegradedAllowed() {
        assertEquals(
            GateState.Allowed(integrityVerified = false),
            decide(integrity = IntegrityGateInput.NOT_CONFIGURED),
        )
    }

    @Test
    fun degradedModeStillBlocksRootAndTamper() {
        assertEquals(
            GateState.Blocked(BlockReason.ROOTED),
            decide(rooted = true, integrity = IntegrityGateInput.NOT_CONFIGURED),
        )
        assertEquals(
            GateState.Blocked(BlockReason.NON_PLAY_INSTALLER),
            decide(installer = other, integrity = IntegrityGateInput.NOT_CONFIGURED),
        )
        assertEquals(
            GateState.Blocked(BlockReason.SIGNATURE_MISMATCH),
            decide(
                signature = TamperCheck.SignatureVerdict.Mismatch,
                integrity = IntegrityGateInput.NOT_CONFIGURED,
            ),
        )
    }

    @Test
    fun checksApplyInOrderRootBeforeInstallerBeforeSignatureBeforeIntegrity() {
        // Root wins over everything.
        assertEquals(
            GateState.Blocked(BlockReason.ROOTED),
            decide(
                rooted = true,
                installer = other,
                signature = TamperCheck.SignatureVerdict.Mismatch,
                integrity = IntegrityGateInput.VERIFIED_FAIL,
            ),
        )
        // Installer wins over signature and integrity.
        assertEquals(
            GateState.Blocked(BlockReason.NON_PLAY_INSTALLER),
            decide(
                installer = other,
                signature = TamperCheck.SignatureVerdict.Mismatch,
                integrity = IntegrityGateInput.VERIFIED_FAIL,
            ),
        )
        // Signature wins over integrity.
        assertEquals(
            GateState.Blocked(BlockReason.SIGNATURE_MISMATCH),
            decide(
                signature = TamperCheck.SignatureVerdict.Mismatch,
                integrity = IntegrityGateInput.VERIFIED_FAIL,
            ),
        )
    }

    @Test
    fun unknownStateUnlocksLikeIos() {
        // G32: only an explicit Blocked verdict denies. Unknown
        // (no refresh finished yet) unlocks, so a slow or failed
        // integrity check never locks purchased premium.
        assertEquals(true, PremiumAccessGate.isAllowedState(GateState.Unknown))
        assertEquals(
            true,
            PremiumAccessGate.isAllowedState(GateState.Allowed(integrityVerified = false))
        )
        assertEquals(
            true,
            PremiumAccessGate.isAllowedState(GateState.Allowed(integrityVerified = true))
        )
        assertEquals(
            false,
            PremiumAccessGate.isAllowedState(GateState.Blocked(BlockReason.ROOTED))
        )
        assertEquals(
            false,
            PremiumAccessGate.isAllowedState(
                GateState.Blocked(BlockReason.INTEGRITY_UNAVAILABLE)
            )
        )
    }
}
