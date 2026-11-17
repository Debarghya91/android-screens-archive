// SecurityMonitor.kt
// Payments Training Hub Android - soft device-trust reporting.
//
// Runs the existing root, installer and signature checks plus a
// debugger check once per launch, logs the outcome, and publishes
// it for the Diagnostics view. SOFT ONLY: nothing here blocks,
// locks out, or changes any behaviour. The premium gate
// (PremiumAccessGate) is the enforcement point and is untouched;
// this monitor exists so a suspicious device state is visible in
// the app and in logs instead of silently gating content, which
// matters while server verification is not deployed and a
// fail-closed mistake would brick legitimate users, including the
// owner's own phone.
//
// The gathering runs on a background scope and never throws. The
// notice lines are built by the pure [describe] function, which is
// unit-tested.

package com.paymentstraininghub.app.security

import android.content.Context
import android.os.Debug
import android.util.Log
import com.paymentstraininghub.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SecurityMonitor {

    private const val TAG = "SecurityMonitor"

    /** One refresh result: the raw findings plus human-readable lines. */
    data class SecurityFindings(
        val rooted: Boolean,
        val debuggerAttached: Boolean,
        val installerDescription: String,
        val signatureDescription: String,
        val notices: List<String>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _findings = MutableStateFlow<SecurityFindings?>(null)
    val findings: StateFlow<SecurityFindings?> = _findings.asStateFlow()

    /**
     * Run every check on a background thread and publish the
     * result. Safe to call on every launch; never throws and
     * never blocks the caller.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val result = runCatching { gather(appContext) }.getOrNull() ?: return@launch
            result.notices.forEach { Log.w(TAG, it) }
            _findings.value = result
        }
    }

    private fun gather(context: Context): SecurityFindings {
        val rooted = runCatching { RootCheck.isRooted(context) }.getOrDefault(false)
        val debuggerAttached = runCatching {
            Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        }.getOrDefault(false)
        val installer = runCatching { TamperCheck.installerVerdict(context) }
            .getOrDefault(TamperCheck.InstallerVerdict.Other(null))
        val signature = runCatching {
            TamperCheck.signatureVerdict(context, BuildConfig.EXPECTED_SIGNING_CERT_SHA256)
        }.getOrDefault(TamperCheck.SignatureVerdict.Unreadable)
        val installerDescription = when (installer) {
            TamperCheck.InstallerVerdict.PlayStore -> "Google Play"
            is TamperCheck.InstallerVerdict.Other ->
                installer.installerPackage ?: "unknown installer"
        }
        val signatureDescription = when (signature) {
            TamperCheck.SignatureVerdict.Match -> "matches Play App Signing"
            TamperCheck.SignatureVerdict.Mismatch -> "MISMATCH with Play App Signing"
            TamperCheck.SignatureVerdict.Unconfigured -> "not configured yet"
            TamperCheck.SignatureVerdict.Unreadable -> "could not be read"
        }
        return SecurityFindings(
            rooted = rooted,
            debuggerAttached = debuggerAttached,
            installerDescription = installerDescription,
            signatureDescription = signatureDescription,
            notices = describe(rooted, debuggerAttached, installer, signature)
        )
    }

    /**
     * The notice lines for one set of findings. Pure and
     * unit-tested. A clean device gets one reassuring line; each
     * suspicious signal gets its own line. These are notices, not
     * verdicts: the gate decides enforcement, never this text.
     */
    fun describe(
        rooted: Boolean,
        debuggerAttached: Boolean,
        installer: TamperCheck.InstallerVerdict,
        signature: TamperCheck.SignatureVerdict
    ): List<String> {
        val lines = mutableListOf<String>()
        if (rooted) {
            lines.add("Device shows root indicators.")
        }
        if (debuggerAttached) {
            lines.add("A debugger is attached to this app.")
        }
        if (installer !is TamperCheck.InstallerVerdict.PlayStore) {
            lines.add("Installed from ${installerDescription(installer)}, not Google Play.")
        }
        when (signature) {
            TamperCheck.SignatureVerdict.Mismatch ->
                lines.add("Signing certificate does not match Play App Signing.")
            TamperCheck.SignatureVerdict.Unreadable ->
                lines.add("Signing certificate could not be read.")
            TamperCheck.SignatureVerdict.Match,
            TamperCheck.SignatureVerdict.Unconfigured -> Unit
        }
        if (lines.isEmpty()) {
            lines.add("No device trust concerns found.")
        }
        return lines
    }

    private fun installerDescription(installer: TamperCheck.InstallerVerdict): String {
        return when (installer) {
            TamperCheck.InstallerVerdict.PlayStore -> "Google Play"
            is TamperCheck.InstallerVerdict.Other ->
                installer.installerPackage ?: "an unknown installer"
        }
    }
}
