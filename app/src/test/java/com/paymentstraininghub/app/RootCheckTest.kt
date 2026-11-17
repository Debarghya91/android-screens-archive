// RootCheckTest.kt
// Unit tests for the pure root-decision core. The on-device
// gathering (file existence, installed packages) is not
// unit-testable on the JVM.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.security.RootCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCheckTest {

    @Test
    fun cleanDeviceIsNotRooted() {
        assertFalse(
            RootCheck.looksRooted(emptyList(), emptyList(), "release-keys"),
        )
    }

    @Test
    fun suPathMeansRooted() {
        assertTrue(
            RootCheck.looksRooted(listOf("/system/xbin/su"), emptyList(), "release-keys"),
        )
    }

    @Test
    fun rootPackageMeansRooted() {
        assertTrue(
            RootCheck.looksRooted(emptyList(), listOf("com.topjohnwu.magisk"), "release-keys"),
        )
    }

    @Test
    fun testKeysMeansRooted() {
        assertTrue(
            RootCheck.looksRooted(emptyList(), emptyList(), "test-keys"),
        )
    }

    @Test
    fun nullBuildTagsWithoutOtherSignalsIsNotRooted() {
        assertFalse(
            RootCheck.looksRooted(emptyList(), emptyList(), null),
        )
    }
}
