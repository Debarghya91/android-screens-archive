// EntryAccessRulesTest.kt
// Tests for the search-index entry gating rules (FAQ, lab, test,
// guide, lesson, situation), the test-to-chapters map, the pure
// chapter-unlock decision, and the test-quiz routing decision
// (a locked test tap lands on the paywall, an unlocked test tap
// opens the quiz), all ported from iOS so the platforms cannot
// drift. Realistic data shapes: FAQ and lab
// entries in catalogue order with the free-sample boundary on
// both sides, a single-part test, a multi-part test, and an
// unknown test id (fail closed), and an entry missing from the
// loaded list (null index, fail closed). Written-not-run: local
// Gradle cannot run here (JVM loopback TCP black-holed); CI is
// the compile and execution proof. No em dashes.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.billing.EntitlementRules
import com.paymentstraininghub.app.billing.EntryAccessRules
import com.paymentstraininghub.app.billing.FreeSampleRules
import com.paymentstraininghub.app.billing.TestQuizDestination
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryAccessRulesTest {

    private fun entry(id: String, type: String): SearchEntry =
        SearchEntry(id = id, title = "Title for $id", url = "entry/$id", type = type, text = "Body text for $id")

    @Test
    fun faqFirstFiveFreeRestLockedWithoutInterview() {
        val faq = entry("faq-1", "faq")
        for (index in 0 until FreeSampleRules.FAQ_FREE_COUNT) {
            assertFalse(
                "FAQ at index $index must be free",
                EntryAccessRules.isLocked(faq, index, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
            )
        }
        assertTrue(
            "FAQ at index 5 must lock without Interview Ready",
            EntryAccessRules.isLocked(faq, 5, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
        assertTrue(
            "FAQ at index 319 must lock without Interview Ready",
            EntryAccessRules.isLocked(faq, 319, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
    }

    @Test
    fun faqUnlocksWithInterviewAtAnyIndex() {
        val faq = entry("faq-99", "faq")
        assertFalse(
            EntryAccessRules.isLocked(faq, 200, isPracticeUnlocked = false, isInterviewUnlocked = true, isChapterUnlocked = false)
        )
        assertFalse(
            "Interview unlock beats a missing index",
            EntryAccessRules.isLocked(faq, null, isPracticeUnlocked = false, isInterviewUnlocked = true, isChapterUnlocked = false)
        )
    }

    @Test
    fun faqMissingFromListFailsClosed() {
        val faq = entry("faq-unknown", "faq")
        assertTrue(
            EntryAccessRules.isLocked(faq, null, isPracticeUnlocked = true, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
    }

    @Test
    fun faqUnlockingProductIsInterview() {
        assertEquals(
            ProductCatalogue.interviewProductId,
            EntryAccessRules.unlockingProductId(entry("faq-1", "faq"))
        )
    }

    @Test
    fun labFirstTwoFreeRestLockedWithoutPractice() {
        val lab = entry("lab-1", "lab")
        assertFalse(
            EntryAccessRules.isLocked(lab, 0, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
        assertFalse(
            EntryAccessRules.isLocked(lab, 1, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
        assertTrue(
            EntryAccessRules.isLocked(lab, 2, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
        assertFalse(
            EntryAccessRules.isLocked(lab, 9, isPracticeUnlocked = true, isInterviewUnlocked = false, isChapterUnlocked = false)
        )
    }

    @Test
    fun labMissingFromListFailsClosed() {
        assertTrue(
            EntryAccessRules.isLocked(entry("lab-x", "lab"), null, isPracticeUnlocked = false, isInterviewUnlocked = true, isChapterUnlocked = false)
        )
    }

    @Test
    fun labUnlockingProductIsPractice() {
        assertEquals(
            ProductCatalogue.practiceProductId,
            EntryAccessRules.unlockingProductId(entry("lab-1", "lab"))
        )
    }

    @Test
    fun testLocksOnChapterUnlockOnly() {
        val test = entry("ch10-test", "test")
        assertTrue(
            EntryAccessRules.isLocked(test, null, isPracticeUnlocked = true, isInterviewUnlocked = true, isChapterUnlocked = false)
        )
        assertFalse(
            EntryAccessRules.isLocked(test, null, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = true)
        )
    }

    @Test
    fun testMapCoversFifteenTests() {
        assertEquals(15, EntryAccessRules.testChapters.size)
    }

    @Test
    fun testLocksWhenAnyChapterLocked() {
        assertFalse(
            "All chapters unlocked opens the test",
            EntryAccessRules.isTestLocked("t-clearing") { true }
        )
        assertTrue(
            "One locked chapter locks the test",
            EntryAccessRules.isTestLocked("t-clearing") { it != "ch18" }
        )
        assertTrue(
            "Only one chapter unlocked still locks the test",
            EntryAccessRules.isTestLocked("t-foundations") { it == "ch1" }
        )
    }

    @Test
    fun testUnknownIdFailsClosed() {
        assertTrue(
            "Unknown test locks even when every chapter reports unlocked",
            EntryAccessRules.isTestLocked("t-unknown") { true }
        )
        assertEquals(
            ProductCatalogue.integratedProductId,
            EntryAccessRules.testUnlockingProductId("t-unknown")
        )
        assertEquals(
            ProductCatalogue.integratedProductId,
            EntryAccessRules.unlockingProductId(entry("t-unknown", "test"))
        )
    }

    @Test
    fun testUnlockingProductIsSinglePart() {
        assertEquals(
            ProductCatalogue.productForChapter("ch1")?.productId,
            EntryAccessRules.testUnlockingProductId("t-foundations")
        )
        assertEquals(
            ProductCatalogue.productForChapter("ch17")?.productId,
            EntryAccessRules.testUnlockingProductId("t-clearing")
        )
        assertEquals(
            ProductCatalogue.productForChapter("ch5")?.productId,
            EntryAccessRules.testUnlockingProductId("t-messaging")
        )
        assertEquals(
            "Held-out ch37 never forces the Integrated fallback on its own",
            ProductCatalogue.productForChapter("ch25")?.productId,
            EntryAccessRules.testUnlockingProductId("t-architecture")
        )
        assertEquals(
            ProductCatalogue.productForChapter("ch1")?.productId,
            EntryAccessRules.unlockingProductId(entry("t-foundations", "test"))
        )
    }

    @Test
    fun testUnlockingProductIsIntegratedWhenChaptersSpanParts() {
        assertEquals(
            ProductCatalogue.integratedProductId,
            EntryAccessRules.testUnlockingProductId("t-mock-1")
        )
        assertEquals(
            "t-rails spans part-2 and part-8",
            ProductCatalogue.integratedProductId,
            EntryAccessRules.testUnlockingProductId("t-rails")
        )
        assertEquals(
            ProductCatalogue.integratedProductId,
            EntryAccessRules.testUnlockingProductId("t-apis")
        )
    }

    @Test
    fun testQuizDestinationOpensQuizWhenEveryChapterUnlocked() {
        assertEquals(
            "All chapters unlocked opens the quiz",
            TestQuizDestination.Quiz,
            EntryAccessRules.testQuizDestination("t-clearing") { true }
        )
        assertEquals(
            "A multi-part test opens the quiz when every chapter is unlocked",
            TestQuizDestination.Quiz,
            EntryAccessRules.testQuizDestination("t-mock-1") { true }
        )
    }

    @Test
    fun testQuizDestinationShowsPaywallWhenAnyChapterLocked() {
        val destination = EntryAccessRules.testQuizDestination("t-clearing") { it != "ch18" }
        assertTrue(
            "One locked chapter routes the tap to the paywall, never to the quiz",
            destination is TestQuizDestination.Paywall
        )
        assertEquals(
            "The paywall offers the single part that unlocks the test",
            ProductCatalogue.productForChapter("ch17")?.productId,
            (destination as TestQuizDestination.Paywall).productId
        )
    }

    @Test
    fun testQuizDestinationShowsIntegratedPaywallForMultiPartTest() {
        assertEquals(
            "A locked multi-part test routes to the Integrated paywall",
            TestQuizDestination.Paywall(ProductCatalogue.integratedProductId),
            EntryAccessRules.testQuizDestination("t-mock-1") { it != "ch1" }
        )
    }

    @Test
    fun testQuizDestinationFailsClosedForUnknownTest() {
        assertEquals(
            "An unknown test routes to the Integrated paywall even when every chapter reports unlocked",
            TestQuizDestination.Paywall(ProductCatalogue.integratedProductId),
            EntryAccessRules.testQuizDestination("t-unknown") { true }
        )
    }

    @Test
    fun lessonSituationGuideNeverLockAtEntryLevel() {
        listOf(
            entry("ch1s3", "lesson"),
            entry("ABH-02", "situation"),
            entry("guide-1", "guide")
        ).forEach { item ->
            assertFalse(
                "${item.type} must never lock at entry level",
                EntryAccessRules.isLocked(item, null, isPracticeUnlocked = false, isInterviewUnlocked = false, isChapterUnlocked = false)
            )
            assertNull(EntryAccessRules.unlockingProductId(item))
        }
    }

    @Test
    fun chapterUnlockHeldOutAlwaysOpen() {
        assertTrue(
            "Held-out chapter opens for a guest with no purchases",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = true,
                hasContainingPart = false,
                integratedOwned = false,
                containingPartOwned = false
            )
        )
        assertTrue(
            "Held-out chapter opens for an Integrated owner too",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = true,
                hasContainingPart = false,
                integratedOwned = true,
                containingPartOwned = false
            )
        )
    }

    @Test
    fun chapterUnlockUnmappedFailsClosed() {
        assertFalse(
            "Unmapped, non-held-out chapter locks even for an Integrated owner",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = false,
                integratedOwned = true,
                containingPartOwned = false
            )
        )
        assertTrue(
            "Admin still sees an unmapped chapter",
            EntitlementRules.isChapterUnlocked(
                isAdmin = true,
                isHeldOutChapter = false,
                hasContainingPart = false,
                integratedOwned = false,
                containingPartOwned = false
            )
        )
    }

    @Test
    fun chapterUnlockIntegratedAndPart() {
        assertTrue(
            "Integrated unlocks a mapped chapter without the part",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = true,
                containingPartOwned = false
            )
        )
        assertTrue(
            "Owning the part unlocks its chapter",
            EntitlementRules.isChapterUnlocked(
                isAdmin = false,
                isHeldOutChapter = false,
                hasContainingPart = true,
                integratedOwned = false,
                containingPartOwned = true
            )
        )
        assertFalse(
            "Owning neither locks a mapped chapter",
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
