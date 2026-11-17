// PremiumAccessGate.kt
// Payments Training Hub Android - the single enforcement point for
// premium access. A paid lesson, situation or feature unlocks only
// when BOTH hold: an entitlement (a live Play purchase or an
// active server entitlement, see BillingManager) AND this gate
// allows it. The gate combines four checks, all of which must
// pass in a release build:
//
//   1. Not rooted (RootCheck, lightweight on-device signals).
//   2. Installed from Google Play (TamperCheck installer check).
//   3. Signing certificate matches the Play App Signing hash
//      (TamperCheck signature check; skipped while the expected
//      hash is unconfigured, which the owner's Play Console step
//      supplies).
//   4. Play Integrity verdict verified server-side
//      (IntegrityCheck). A fresh cached pass (24 hours) avoids a
//      round trip on every launch; only passes are cached, and a
//      failed verdict clears the cache.
//
// Fail-closed behaviour: before the first refresh finishes the
// gate is Unknown and premium stays locked; a failed integrity
// verdict, a non-Play installer, a signature mismatch or a rooted
// device blocks premium outright. A transient integrity failure
// (no network, Play services missing) blocks premium unless a
// fresh cached pass exists. When the verify-integrity function is
// not deployed or the Play secrets are unset, the gate runs in
// DEGRADED mode: the other three checks still enforce, the state
// records integrityVerified = false, and full enforcement starts
// automatically once the server side is configured. Degraded mode
// is a deployment gap, never a device trust signal, and it is
// logged loudly.
//
// Debug builds skip every check: developers and testers install
// over adb, and Play Integrity tokens do not apply to debug
// builds. The qa build type (small sideload test builds signed
// with the debug key) skips them too via ENABLE_TEST_UNLOCK.
// The debug unlock set in BillingManager is likewise
// DEBUG/qa-only. Release builds never skip.
//
// HONEST LIMIT: these checks run in the app, and a modified build
// can remove or fake any of them. They stop casual mod-APK
// redistribution (re-signed copies, sideloaded copies, rooted
// devices, faked BillingClient responses). The real enforcement is
// server-side: entitlements come only from user_purchases rows the
// verify-play-purchase function wrote after Google confirmed the
// purchase, and the integrity verdict comes only from the
// verify-integrity function. Content URLs themselves are public
// today, so a determined attacker can fetch lesson content
// directly; authenticated content delivery is the follow-up that
// closes that hole, not this gate.

package com.paymentstraininghub.app.security

import android.content.Context
import android.util.Log
import com.paymentstraininghub.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

object PremiumAccessGate {

    private const val TAG = "PremiumAccessGate"

    /** Passed-verdict cache file: device-scoped, not account-namespaced. */
    private const val PREFS_NAME = "pf_integrity_verdict"
    private const val PASSED_AT_KEY = "passed_at_ms"

    /** A cached pass backs premium for 24 hours, then re-verification is required. */
    internal const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /** Why premium is blocked. Shown in logs; the UI shows a generic message. */
    enum class BlockReason {
        ROOTED,
        NON_PLAY_INSTALLER,
        SIGNATURE_MISMATCH,
        SIGNATURE_UNREADABLE,
        INTEGRITY_FAILED,
        INTEGRITY_UNAVAILABLE,
    }

    sealed interface GateState {
        /**
         * No refresh has finished yet: premium stays available.
         * Unknown unlocks, matching iOS (G32), so a slow or
         * failed integrity check never locks
         * legitimately-purchased premium. Only an explicit
         * Blocked verdict denies.
         */
        data object Unknown : GateState

        /**
         * Premium may unlock (subject to an entitlement).
         * [integrityVerified] is false in debug builds and in
         * degraded mode (the server side is not configured yet).
         */
        data class Allowed(val integrityVerified: Boolean) : GateState

        /** Premium must not unlock. */
        data class Blocked(val reason: BlockReason) : GateState
    }

    /** The integrity input to the decision, after the cache is consulted. */
    enum class IntegrityGateInput {
        VERIFIED_PASS,
        VERIFIED_FAIL,
        UNAVAILABLE,
        NOT_CONFIGURED,
    }

    private val _state = MutableStateFlow<GateState>(GateState.Unknown)
    val state: StateFlow<GateState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    /**
     * True unless the gate explicitly blocked premium (G32,
     * aligned to iOS unlock behaviour). Unknown unlocks: only a
     * Blocked verdict denies.
     */
    fun isAllowed(): Boolean = isAllowedState(_state.value)

    /** Pure form of [isAllowed], for tests and callers holding a state value. */
    fun isAllowedState(state: GateState): Boolean = state !is GateState.Blocked

    /**
     * Pure decision core: combines the four checks. Unit-tested;
     * [refresh] only gathers the inputs and applies this.
     */
    fun decide(
        debugBuild: Boolean,
        rooted: Boolean,
        installer: TamperCheck.InstallerVerdict,
        signature: TamperCheck.SignatureVerdict,
        integrity: IntegrityGateInput,
        cachedPassFresh: Boolean,
    ): GateState {
        if (debugBuild) return GateState.Allowed(integrityVerified = false)
        if (rooted) return GateState.Blocked(BlockReason.ROOTED)
        if (installer !is TamperCheck.InstallerVerdict.PlayStore) {
            return GateState.Blocked(BlockReason.NON_PLAY_INSTALLER)
        }
        when (signature) {
            TamperCheck.SignatureVerdict.Mismatch ->
                return GateState.Blocked(BlockReason.SIGNATURE_MISMATCH)
            TamperCheck.SignatureVerdict.Unreadable ->
                return GateState.Blocked(BlockReason.SIGNATURE_UNREADABLE)
            TamperCheck.SignatureVerdict.Match,
            TamperCheck.SignatureVerdict.Unconfigured -> Unit
        }
        return when (integrity) {
            IntegrityGateInput.VERIFIED_PASS -> GateState.Allowed(integrityVerified = true)
            IntegrityGateInput.VERIFIED_FAIL -> GateState.Blocked(BlockReason.INTEGRITY_FAILED)
            IntegrityGateInput.UNAVAILABLE -> {
                if (cachedPassFresh) {
                    GateState.Allowed(integrityVerified = true)
                } else {
                    GateState.Blocked(BlockReason.INTEGRITY_UNAVAILABLE)
                }
            }
            // Degraded mode: the server side is not configured
            // yet. The other checks still enforce; full
            // enforcement starts automatically once the function
            // and secrets exist.
            IntegrityGateInput.NOT_CONFIGURED -> GateState.Allowed(integrityVerified = false)
        }
    }

    /**
     * Runs every check and publishes the resulting state. Safe to
     * call on every launch and foreground return: a refresh
     * already running is skipped, and a fresh cached pass avoids
     * the integrity round trip. Never throws.
     */
    suspend fun refresh(context: Context) {
        if (!refreshMutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                try {
                    refreshInner(context.applicationContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Premium gate refresh failed: ${e.message}")
                }
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshInner(context: Context) {
        if (BuildConfig.DEBUG || BuildConfig.ENABLE_TEST_UNLOCK) {
            _state.value = GateState.Allowed(integrityVerified = false)
            return
        }
        val rooted = runCatching { RootCheck.isRooted(context) }.getOrDefault(false)
        val installer = runCatching { TamperCheck.installerVerdict(context) }
            .getOrDefault(TamperCheck.InstallerVerdict.Other(null))
        val signature = runCatching {
            TamperCheck.signatureVerdict(context, BuildConfig.EXPECTED_SIGNING_CERT_SHA256)
        }.getOrDefault(TamperCheck.SignatureVerdict.Unreadable)
        if (signature is TamperCheck.SignatureVerdict.Unconfigured) {
            Log.w(TAG, "Signature check skipped: EXPECTED_SIGNING_CERT_SHA256 is not configured.")
        }

        val cachedPassFresh = hasFreshCachedPass(context)
        val integrityInput = if (cachedPassFresh) {
            IntegrityGateInput.VERIFIED_PASS
        } else {
            when (val outcome = IntegrityCheck.requestAndVerify(context)) {
                is IntegrityCheck.IntegrityOutcome.Verified -> {
                    if (outcome.passed) {
                        recordCachedPass(context)
                        IntegrityGateInput.VERIFIED_PASS
                    } else {
                        clearCachedPass(context)
                        Log.w(TAG, "Integrity verdict failed: code=${outcome.code}")
                        IntegrityGateInput.VERIFIED_FAIL
                    }
                }
                is IntegrityCheck.IntegrityOutcome.Unavailable -> {
                    Log.w(TAG, "Integrity unavailable: ${outcome.reason}")
                    IntegrityGateInput.UNAVAILABLE
                }
                is IntegrityCheck.IntegrityOutcome.NotConfigured -> {
                    Log.w(TAG, "Integrity DEGRADED: verify-integrity is not deployed or Play secrets are unset.")
                    IntegrityGateInput.NOT_CONFIGURED
                }
            }
        }

        val next = decide(
            debugBuild = false,
            rooted = rooted,
            installer = installer,
            signature = signature,
            integrity = integrityInput,
            cachedPassFresh = cachedPassFresh,
        )
        if (next is GateState.Blocked) {
            Log.w(TAG, "Premium blocked: ${next.reason}")
        }
        _state.value = next
    }

    /** True when a pass was cached within the TTL and not in the future (clock games fail stale). */
    internal fun hasFreshCachedPass(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val passedAt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PASSED_AT_KEY, 0L)
        if (passedAt <= 0L || passedAt > nowMs) return false
        return nowMs - passedAt < CACHE_TTL_MS
    }

    private fun recordCachedPass(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PASSED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun clearCachedPass(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PASSED_AT_KEY)
            .apply()
    }
}
