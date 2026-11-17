// LabGatingTest.kt
// Tests for the Investigation Lab gate: which cases open without
// the Investigation Lab entitlement, and the pure chapter-unlock
// decision behind BillingManager.isChapterUnlocked. Realistic
// data shapes: an authored practice exercise with no source
// situation, cases grounded in the two real free samples
// (ABH-01, JEB-C01), and a case grounded in a non-free situation.
// The gate product is the Investigation Lab, matching iOS
// InvestigationGate and the Investigation Experience screen: a
// Practice Experiences entitlement must NOT open a lab case.
// Written-not-run: local Gradle cannot run here (JVM loopback
// TCP black-holed); CI is the compile and execution proof.
// No em dashes.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.billing.EntitlementRules
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.features.lab.ExceptionCase
import com.paymentstraininghub.app.features.lab.ExceptionLevel
import com.paymentstraininghub.app.features.lab.LabGating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabGatingTest {

    private fun labCase(id: String, sourceSituationId: String?): ExceptionCase =
        ExceptionCase(
            id = id,
            incidentType = "Duplicate pacs.008",
            title = "Case $id",
            level = ExceptionLevel.CORE,
            narrative = "Narrative for $id",
            evidence = emptyList(),
            steps = emptyList(),
            resolution = "Resolution for $id",
            messages = emptyList(),
            systems = emptyList(),
            sourceSituationID = sourceSituationId
        )

    @Test
    fun authoredExerciseIsOpenWithoutAnyUnlock() {
        val exercise = labCase("LAB-01", sourceSituationId = null)
        assertTrue(exercise.isPracticeExercise)
        assertFalse(exercise.requiresSituationsUnlock)
        assertTrue(LabGating.isCaseOpen(exercise, labUnlocked = false))
    }

    @Test
    fun freeSampleGroundedCasesAreOpenWithoutAnyUnlock() {
        listOf("ABH-01", "JEB-C01").forEach { sampleId ->
            val grounded = labCase("LAB-$sampleId", sourceSituationId = sampleId)
            assertTrue("grounded in $sampleId", grounded.isGroundedInFreeSample)
            assertFalse("grounded in $sampleId", grounded.requiresSituationsUnlock)
            assertTrue(
                "case grounded in $sampleId must be open",
                LabGating.isCaseOpen(grounded, labUnlocked = false)
            )
        }
    }

    @Test
    fun nonFreeGroundedCaseNeedsTheLabUnlock() {
        val grounded = labCase("LAB-ABH-02", sourceSituationId = "ABH-02")
        assertTrue(grounded.requiresSituationsUnlock)
        assertFalse(LabGating.isCaseOpen(grounded, labUnlocked = false))
        assertTrue(LabGating.isCaseOpen(grounded, labUnlocked = true))
    }

    @Test
    fun labGateProductIsTheInvestigationLab() {
        assertEquals(
            ProductCatalogue.investigationLabProductId,
            LabGating.LAB_PRODUCT_ID
        )
    }

    @Test
    fun chapterUnlockHeldOutChapterIsAlwaysOpen() {
        assertTrue(
            "ch37 is held out and must be open without any purchase",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = true,
                hasContainingPart = false,
                integratedOwned = false,
                containingPartOwned = false
            )
        )
    }

    @Test
    fun chapterUnlockAdminSeesEverything() {
        assertTrue(
            EntitlementRules.isChapterUnlocked(
                isAdmin = true,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = false,
                containingPartOwned = false
            )
        )
    }

    @Test
    fun chapterUnlockUnmappedChapterFailsClosed() {
        assertFalse(
            "a chapter in no part must stay locked for a non-admin",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = false,
                integratedOwned = true,
                containingPartOwned = false
            )
        )
    }

    @Test
    fun chapterUnlockIntegratedOrPartOpensAMappedChapter() {
        assertTrue(
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = true,
                containingPartOwned = false
            )
        )
        assertTrue(
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = false,
                containingPartOwned = true
            )
        )
        assertFalse(
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = false,
                containingPartOwned = false
            )
        )
    }
}
