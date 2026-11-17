// TamperCheck.kt
// Payments Training Hub Android - installer and signing-signature
// verification for the premium access gate. Distribution is
// Google Play only: a release build that was not installed from
// Play, or whose signing certificate differs from the Play App
// Signing certificate, must not run premium features. A modified
// build (re-signed mod APK, sideloaded copy) fails one or both
// checks.
//
// The expected certificate SHA-256 comes from the
// EXPECTED_SIGNING_CERT_SHA256 BuildConfig field, fed by the
// paymentstraininghub.signingCertSha256 Gradle property. It is the App
// Signing certificate SHA-256 from Play Console (Release, Setup,
// App signing), the certificate Google re-signs the app with, NOT
// the upload key. While the field is empty the signature check is
// skipped and reported as UNCONFIGURED: the mechanism ships, the
// credential is the owner's Play Console step. The installer check
// needs no credential and always runs in release builds.
//
// The SHA-256 helper and the verdict comparisons are pure and
// unit-tested; the Android part only reads the package info.

package com.paymentstraininghub.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest

object TamperCheck {

    const val PLAY_INSTALLER_PACKAGE = "com.android.vending"

    /** Where this install came from. */
    sealed interface InstallerVerdict {
        /** Installed by Google Play. */
        data object PlayStore : InstallerVerdict

        /** Installed by something else (adb, sideload, another store). */
        data class Other(val installerPackage: String?) : InstallerVerdict
    }

    /** The signing-certificate comparison outcome. */
    sealed interface SignatureVerdict {
        /** The certificate matches the expected Play App Signing hash. */
        data object Match : SignatureVerdict

        /** The certificate differs from the expected hash: re-signed. */
        data object Mismatch : SignatureVerdict

        /** No expected hash is configured yet; the check is skipped. */
        data object Unconfigured : SignatureVerdict

        /** The certificate could not be read at all. */
        data object Unreadable : SignatureVerdict
    }

    /**
     * SHA-256 of [bytes] as an uppercase hex string with no
     * separators, matching the form Play Console shows (colons
     * removed) and the form the Gradle property holds.
     */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        // Mask to 0xFF: without it, bytes >= 0x80 format as
        // 8-digit sign-extended hex and the hash is wrong.
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** Normalises a certificate hash for comparison: uppercased, with colons, spaces and dashes removed. */
    fun normaliseHash(hash: String): String {
        return hash.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
    }

    /** Pure comparison: the observed hash against the expected hash. */
    fun compareSignature(observedSha256Hex: String?, expectedSha256Hex: String): SignatureVerdict {
        if (expectedSha256Hex.isBlank()) return SignatureVerdict.Unconfigured
        if (observedSha256Hex.isNullOrBlank()) return SignatureVerdict.Unreadable
        return if (normaliseHash(observedSha256Hex) == normaliseHash(expectedSha256Hex)) {
            SignatureVerdict.Match
        } else {
            SignatureVerdict.Mismatch
        }
    }

    /** Pure installer classification from the installer package name. */
    fun classifyInstaller(installerPackage: String?): InstallerVerdict {
        return if (installerPackage == PLAY_INSTALLER_PACKAGE) {
            InstallerVerdict.PlayStore
        } else {
            InstallerVerdict.Other(installerPackage)
        }
    }

    /** The installer verdict for this install, from the platform source info. */
    fun installerVerdict(context: Context): InstallerVerdict {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(context.packageName)
                info.installingPackageName ?: info.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return classifyInstaller(installer)
    }

    /** The SHA-256 of the first signing certificate, or null when it cannot be read. */
    fun signingCertSha256Hex(context: Context): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingCertSha256Hex28(context)
            } else {
                signingCertSha256HexLegacy(context)
            }
        }.getOrNull()
    }

    /** API 28+: signingInfo is the only source of the signing certificates. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun signingCertSha256Hex28(context: Context): String? {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return sha256Hex(signer.toByteArray())
    }

    /** API 26-27: the deprecated signatures array is the only source. */
    private fun signingCertSha256HexLegacy(context: Context): String? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES,
        )
        @Suppress("DEPRECATION")
        val signer = info.signatures?.firstOrNull() ?: return null
        return sha256Hex(signer.toByteArray())
    }

    /** The signature verdict for this install against the expected hash. */
    fun signatureVerdict(context: Context, expectedSha256Hex: String): SignatureVerdict {
        if (expectedSha256Hex.isBlank()) return SignatureVerdict.Unconfigured
        return compareSignature(signingCertSha256Hex(context), expectedSha256Hex)
    }
}
