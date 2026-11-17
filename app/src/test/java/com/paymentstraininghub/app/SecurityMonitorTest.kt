// SecurityMonitorTest.kt
// Unit tests for the pure notice-line builder. On-device
// gathering (root files, packages, debugger state) is not
// unit-testable on the JVM.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.SecurityMonitor
import com.paymentstraininghub.app.security.TamperCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityMonitorTest {

    @Test
    fun cleanDeviceGetsOneReassuringLine() {
        val lines = SecurityMonitor.describe(
            rooted = false,
            debuggerAttached = false,
            installer = TamperCheck.InstallerVerdict.PlayStore,
            signature = TamperCheck.SignatureVerdict.Match
        )
        assertEquals(listOf("No device trust concerns found."), lines)
    }

    @Test
    fun unconfiguredSignatureIsNotSuspicious() {
        val lines = SecurityMonitor.describe(
            rooted = false,
            debuggerAttached = false,
            installer = TamperCheck.InstallerVerdict.PlayStore,
            signature = TamperCheck.SignatureVerdict.Unconfigured
        )
        assertEquals(listOf("No device trust concerns found."), lines)
    }

    @Test
    fun rootedDeviceIsNoted() {
        val lines = SecurityMonitor.describe(
            rooted = true,
            debuggerAttached = false,
            installer = TamperCheck.InstallerVerdict.PlayStore,
            signature = TamperCheck.SignatureVerdict.Match
        )
        assertTrue(lines.any { it.contains("root") })
    }

    @Test
    fun debuggerAttachedIsNoted() {
        val lines = SecurityMonitor.describe(
            rooted = false,
            debuggerAttached = true,
            installer = TamperCheck.InstallerVerdict.PlayStore,
            signature = TamperCheck.SignatureVerdict.Match
        )
        assertTrue(lines.any { it.contains("debugger") })
    }

    @Test
    fun sideloadedInstallIsNotedWithInstaller() {
        val lines = SecurityMonitor.describe(
            rooted = false,
            debuggerAttached = false,
            installer = TamperCheck.InstallerVerdict.Other("com.example.sideload"),
            signature = TamperCheck.SignatureVerdict.Match
        )
        assertTrue(lines.any { it.contains("com.example.sideload") && it.contains("not Google Play") })
    }

    @Test
    fun signatureMismatchIsNoted() {
        val lines = SecurityMonitor.describe(
            rooted = false,
            debuggerAttached = false,
            installer = TamperCheck.InstallerVerdict.PlayStore,
            signature = TamperCheck.SignatureVerdict.Mismatch
        )
        assertTrue(lines.any { it.contains("does not match") })
    }

    @Test
    fun multipleFindingsProduceMultipleLines() {
        val lines = SecurityMonitor.describe(
            rooted = true,
            debuggerAttached = true,
            installer = TamperCheck.InstallerVerdict.Other(null),
            signature = TamperCheck.SignatureVerdict.Unreadable
        )
        assertEquals(4, lines.size)
    }
}
