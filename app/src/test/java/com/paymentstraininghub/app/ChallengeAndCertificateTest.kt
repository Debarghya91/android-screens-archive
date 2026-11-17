package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.schemeForSituation
import com.paymentstraininghub.app.features.assessment.CourseCertificate
import com.paymentstraininghub.app.features.assessment.TestEntryInfo
import com.paymentstraininghub.app.features.assessment.TestPageLoader
import com.paymentstraininghub.app.features.challenge.DailyChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyChallengeTest {

    private fun situation(
        id: String,
        takeaway: String,
        free: Boolean = false
    ): Situation = Situation(
        id = id,
        title = "Situation $id",
        stage = 1,
        stageName = "Stage One",
        level = "Foundation",
        explain = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.",
        takeaway = takeaway,
        isFreeSample = free,
        isStageAuthoritative = true
    )

    private fun pool(size: Int): List<Situation> =
        (0 until size).map { i ->
            situation(
                id = "S-$i",
                takeaway = "Takeaway number $i states the key lesson here in full.",
                free = i < 2
            )
        }

    @Test
    fun entitledPoolIsUnchanged() {
        val situations = pool(10)
        assertEquals(
            situations,
            DailyChallenge.eligiblePool(situations, true, "S-5")
        )
    }

    @Test
    fun gatedPoolKeepsOnlyFreeSamplesAndWeeklyCase() {
        val situations = pool(10)
        val gated = DailyChallenge.eligiblePool(situations, false, "S-5")
        assertEquals(listOf("S-0", "S-1", "S-5"), gated.map { it.id })
    }

    @Test
    fun gatedPoolWithoutWeeklyCaseKeepsOnlyFreeSamples() {
        val situations = pool(10)
        val gated = DailyChallenge.eligiblePool(situations, false, null)
        assertEquals(listOf("S-0", "S-1"), gated.map { it.id })
    }

    @Test
    fun pickIsDeterministicByDay() {
        val situations = pool(10)
        val first = DailyChallenge.todaysSituation(situations, 20350)
        val second = DailyChallenge.todaysSituation(situations, 20350)
        assertEquals(first, second)
        assertEquals(situations[20350 % 10], first)
    }

    @Test
    fun optionsAreFourWithExactlyOneCorrect() {
        val situations = pool(10)
        val today = DailyChallenge.todaysSituation(situations, 20350)!!
        val options = DailyChallenge.options(situations, today, 20350)
        assertEquals(4, options.size)
        assertEquals(1, options.count { it.isCorrect })
        assertEquals(4, options.map { it.text }.toSet().size)
    }

    @Test
    fun optionsAreDeterministicByDay() {
        val situations = pool(10)
        val today = DailyChallenge.todaysSituation(situations, 20350)!!
        assertEquals(
            DailyChallenge.options(situations, today, 20350),
            DailyChallenge.options(situations, today, 20350)
        )
    }

    @Test
    fun firstSentenceRules() {
        assertEquals(
            "See the situation takeaway.",
            DailyChallenge.firstSentence("  ")
        )
        assertEquals(
            "A long enough first sentence states the lesson.",
            DailyChallenge.firstSentence(
                "A long enough first sentence states the lesson. A second sentence follows here."
            )
        )
        // A short first sentence falls through to the whole text.
        assertEquals(
            "Short. Second sentence here.",
            DailyChallenge.firstSentence("Short. Second sentence here.")
        )
    }

    @Test
    fun scenarioTextIsTheFirstTwoParagraphs() {
        val text = DailyChallenge.scenarioText(situation("S-1", "Takeaway."))
        assertEquals("First paragraph.\n\nSecond paragraph.", text)
    }

    @Test
    fun dayKeyFormat() {
        assertEquals("2026-11-13", DailyChallenge.dayKey(LocalDate.of(2026, 11, 13)))
    }
}

class SchemeDetectionTest {

    private fun situation(title: String): Situation = Situation(
        id = "S-1",
        title = title,
        stage = 1,
        stageName = "Stage One",
        level = "Foundation",
        explain = "",
        takeaway = "",
        isFreeSample = false,
        isStageAuthoritative = true
    )

    @Test
    fun schemeMatchesAreWordBoundedAndOrdered() {
        assertEquals("UPI", schemeForSituation(situation("A UPI payment failed")))
        assertEquals("SWIFT", schemeForSituation(situation("The swift message arrived late")))
        assertEquals("Cards", schemeForSituation(situation("A card payment was declined")))
        assertEquals("Direct Debit", schemeForSituation(situation("Direct debit mandate missing")))
        assertNull(situation("A payment failed").let { schemeForSituation(it) })
        // "upis" is not the word UPI.
        assertNull(schemeForSituation(situation("The upis report arrived")))
    }
}

class TestEntryInfoTest {

    private fun entry(title: String, text: String): SearchEntry =
        SearchEntry(id = "t1", title = title, url = "test", type = "test", text = text)

    @Test
    fun parsesCountAndDomainLabel() {
        val info = TestEntryInfo.from(
            entry(
                "SWIFT Messaging Test",
                "SWIFT Messaging Test ISO 20022 and SWIFT Messaging 18 questions covering pacs and pain."
            )
        )
        assertEquals(18, info.questionCount)
        assertEquals("ISO 20022 and SWIFT", info.domainLabel)
        assertEquals(18, info.estimatedMinutes)
    }

    @Test
    fun missingCountIsNull() {
        val info = TestEntryInfo.from(entry("General Test", "General Test A broad review."))
        assertNull(info.questionCount)
        assertNull(info.domainLabel)
    }

    @Test
    fun extractTestDataJsonFindsThePayload() {
        val html = "<html><script>var TEST_DATA = {\"a\": 1, \"b\": \"x} y\"};\nvar OTHER = 2;</script></html>"
        assertEquals("{\"a\": 1, \"b\": \"x} y\"}", TestPageLoader.extractTestDataJson(html))
        assertNull(TestPageLoader.extractTestDataJson("<html>no payload</html>"))
        assertNull(TestPageLoader.extractTestDataJson("var TEST_DATA = {\"unclosed\": true;"))
    }
}

class CourseCertificateTest {

    @Test
    fun subjectNamesMapOnlyTheFourCertificateCourses() {
        assertEquals(
            "Digital Payments and Transaction Banking",
            CourseCertificate.certificateSubjectName("com.paymentstraininghub.app.integrated", "The Integrated Course")
        )
        assertEquals(
            "ISO 20022 and SWIFT Messaging",
            CourseCertificate.certificateSubjectName("com.paymentstraininghub.app.part-3", "Part 3: Messages: SWIFT MT, ISO 20022 and Standards")
        )
        assertEquals(
            "AI Product Ownership for Payments",
            CourseCertificate.certificateSubjectName("com.paymentstraininghub.app.part-9", "Part 9: The Business Analyst at Work")
        )
        assertEquals(
            "Global Payment Rails",
            CourseCertificate.certificateSubjectName("com.paymentstraininghub.app.part-2", "Part 2: How Payments Move")
        )
        assertEquals(
            "Part 5: Clearing, Settlement, Liquidity and Reconciliation",
            CourseCertificate.certificateSubjectName("com.paymentstraininghub.app.part-5", "Part 5: Clearing, Settlement, Liquidity and Reconciliation")
        )
    }

    @Test
    fun scoreTextAndPercent() {
        val cert = CourseCertificate(
            id = "PF-FULL-ABCDEFGH",
            courseId = "com.paymentstraininghub.app.integrated",
            courseName = "Digital Payments and Transaction Banking",
            learnerName = "Guest Learner",
            issuedAtMillis = 0L,
            correctCount = 18,
            totalQuestions = 20
        )
        assertEquals("18/20", cert.scoreText)
        assertEquals(90.0, cert.scorePercent, 0.001)
        assertEquals(
            "https://www.paymentstraininghub.com/verify?id=PF-FULL-ABCDEFGH",
            cert.verificationUrl
        )
        assertTrue(cert.shareText.contains("Certificate ID: PF-FULL-ABCDEFGH"))
    }

    @Test
    fun passMarkIsFifteenOfTwenty() {
        assertEquals(20, com.paymentstraininghub.app.features.assessment.FinalAssessmentStore.QUESTION_COUNT)
        assertEquals(15, com.paymentstraininghub.app.features.assessment.FinalAssessmentStore.PASS_CORRECT_COUNT)
        assertEquals(3, com.paymentstraininghub.app.features.assessment.FinalAssessmentStore.MAX_ATTEMPTS)
        assertTrue(
            com.paymentstraininghub.app.features.assessment.FinalAssessmentStore.assessableCourses
                .none { it.chapterIds.isEmpty() }
        )
        assertEquals(
            11,
            com.paymentstraininghub.app.features.assessment.FinalAssessmentStore.assessableCourses.size
        )
    }
}
