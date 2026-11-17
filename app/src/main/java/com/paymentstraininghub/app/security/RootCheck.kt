// RootCheck.kt
// Payments Training Hub Android - lightweight root detection for the
// premium access gate. A rooted device can subvert on-device checks
// (hooked BillingClient responses, patched signature checks), so
// premium content is refused on devices that show root indicators.
// This is a lightweight check, not a root-beer-grade detector: the
// authoritative device verdict is the Play Integrity response
// (MEETS_DEVICE_INTEGRITY), verified server-side. The checks here
// use only public APIs: well-known su binary paths, known
// root-management packages, and the test-keys build tag. No shell
// execution and no hidden-API reflection.
//
// The decision core (looksRooted) is pure and unit-tested; the
// Android part only gathers the inputs.

package com.paymentstraininghub.app.security

import android.content.Context
import android.os.Build
import java.io.File

object RootCheck {

    /** Well-known su binary locations. */
    internal val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/sbin/.magisk",
        "/data/adb/magisk",
    )

    /** Known root-management packages. */
    internal val ROOT_PACKAGES = setOf(
        "com.topjohnwu.magisk",
        "com.koushikdutta.superuser",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.kingroot.kinguser",
        "com.kingoroot.kinguser",
    )

    /**
     * Pure decision core: rooted when any su path exists, any
     * root-management package is installed, or the build carries
     * test keys (an engineering build, which runs as root).
     */
    fun looksRooted(
        suPathsPresent: Collection<String>,
        installedRootPackages: Collection<String>,
        buildTags: String?,
    ): Boolean {
        if (suPathsPresent.isNotEmpty()) return true
        if (installedRootPackages.isNotEmpty()) return true
        if (buildTags != null && buildTags.contains("test-keys")) return true
        return false
    }

    /** Gathers the inputs on this device and applies [looksRooted]. */
    fun isRooted(context: Context): Boolean {
        val suPresent = SU_PATHS.filter { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
        val packagesPresent = ROOT_PACKAGES.filter { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }
        return looksRooted(suPresent, packagesPresent, Build.TAGS)
    }
}
