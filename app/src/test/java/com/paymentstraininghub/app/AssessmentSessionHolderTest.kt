package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.assessment.AssessmentSessionHolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the retained assessment session holder:
 * one session per key, a fresh session on key change, and the
 * result-recorded flag that stops a rotation on the results
 * screen from recording the same attempt twice. Constructing a
 * session touches no dispatcher, so no Robolectric is needed.
 */
class AssessmentSessionHolderTest {

    @Test
    fun sameKeyReturnsSameSession() {
        val first = AssessmentSessionHolder.sessionFor("holder-test-a")
        val second = AssessmentSessionHolder.sessionFor("holder-test-a")
        assertSame(first, second)
    }

    @Test
    fun differentKeyReturnsFreshSessionAndClearsRecordedFlag() {
        AssessmentSessionHolder.sessionFor("holder-test-b")
        AssessmentSessionHolder.markResultRecorded("holder-test-b")
        assertTrue(AssessmentSessionHolder.isResultRecorded("holder-test-b"))
        val other = AssessmentSessionHolder.sessionFor("holder-test-c")
        assertNotSame(
            AssessmentSessionHolder.sessionFor("holder-test-b"),
            other
        )
        assertFalse(AssessmentSessionHolder.isResultRecorded("holder-test-c"))
        assertFalse(AssessmentSessionHolder.isResultRecorded("holder-test-b"))
    }

    @Test
    fun recordedFlagRoundTripsAndClears() {
        val key = "holder-test-d"
        AssessmentSessionHolder.sessionFor(key)
        assertFalse(AssessmentSessionHolder.isResultRecorded(key))
        AssessmentSessionHolder.markResultRecorded(key)
        assertTrue(AssessmentSessionHolder.isResultRecorded(key))
        AssessmentSessionHolder.clearResultRecorded(key)
        assertFalse(AssessmentSessionHolder.isResultRecorded(key))
    }

    @Test
    fun recordedFlagIgnoresOtherKeys() {
        AssessmentSessionHolder.sessionFor("holder-test-e")
        AssessmentSessionHolder.markResultRecorded("holder-test-other")
        assertFalse(AssessmentSessionHolder.isResultRecorded("holder-test-e"))
    }
}
