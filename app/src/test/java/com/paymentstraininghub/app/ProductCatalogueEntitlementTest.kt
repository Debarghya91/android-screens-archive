// ProductCatalogueEntitlementTest.kt
// Tests for the decided 16-product lineup: catalogue ids, prices,
// validity, chapter coverage, free samples, entitlement expiry and
// renewal resolution, and the referral/promo rules. The decided
// figures are pinned deliberately: a price or scope change is an
// owner decision, and the test author updates the pins with it.
// No em dashes.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.ReferralPromoRules
import com.paymentstraininghub.app.billing.EntitlementRules
import com.paymentstraininghub.app.billing.FreeSampleRules
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.ReadingOrder
import com.paymentstraininghub.app.data.model.ProductScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductCatalogueTest {

    private val expectedIds = listOf(
        "com.paymentstraininghub.app.integrated",
        "com.paymentstraininghub.app.part-1",
        "com.paymentstraininghub.app.part-2",
        "com.paymentstraininghub.app.part-3",
        "com.paymentstraininghub.app.part-4",
        "com.paymentstraininghub.app.part-5",
        "com.paymentstraininghub.app.part-6",
        "com.paymentstraininghub.app.part-7",
        "com.paymentstraininghub.app.part-8",
        "com.paymentstraininghub.app.part-9",
        "com.paymentstraininghub.app.part-10",
        "com.paymentstraininghub.app.studio",
        "com.paymentstraininghub.app.simulator",
        "com.paymentstraininghub.app.investigation-lab",
        "com.paymentstraininghub.app.practice",
        "com.paymentstraininghub.app.interview"
    )

    // Sale, list, renewal, in rupees. List is at most 50 percent
    // above sale; renewal is half the sale price, rounded half up.
    private val expectedPrices = mapOf(
        "com.paymentstraininghub.app.integrated" to Triple(2999, 4499, 1500),
        "com.paymentstraininghub.app.part-1" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-2" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-3" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-4" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-5" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-6" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-7" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-8" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-9" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.part-10" to Triple(399, 599, 200),
        "com.paymentstraininghub.app.studio" to Triple(799, 1199, 400),
        "com.paymentstraininghub.app.simulator" to Triple(499, 749, 250),
        "com.paymentstraininghub.app.investigation-lab" to Triple(499, 749, 250),
        "com.paymentstraininghub.app.practice" to Triple(999, 1499, 500),
        "com.paymentstraininghub.app.interview" to Triple(1599, 2399, 800)
    )

    @Test
    fun catalogueHasExactlySixteenProductsWithDecidedIds() {
        assertEquals(expectedIds, ProductCatalogue.products.map { it.productId })
        assertEquals(expectedIds, ProductCatalogue.allProductIds)
        assertEquals(16, ProductCatalogue.products.map { it.productId }.toSet().size)
    }

    @Test
    fun pricesMatchDecidedSaleListAndRenewalTable() {
        for (product in ProductCatalogue.products) {
            val expected = expectedPrices.getValue(product.productId)
            assertEquals("sale ${product.productId}", expected.first, product.priceInr)
            assertEquals("list ${product.productId}", expected.second, product.listPriceInr)
            assertEquals("renewal ${product.productId}", expected.third, product.renewalPriceInr)
            // List at most 50 percent above sale, allowing the
            // 1-rupee .99 rounding in the decided figures.
            assertTrue(
                "list exceeds sale by more than 50 percent: ${product.productId}",
                product.listPriceInr <= product.priceInr * 3 / 2 + 1
            )
            // Renewal is half the sale price, rounded half up.
            assertEquals(
                "renewal is half the sale price: ${product.productId}",
                (product.priceInr + 1) / 2,
                product.renewalPriceInr
            )
        }
    }

    @Test
    fun everyProductGivesOneYearOfAccess() {
        assertEquals(365, EntitlementRules.VALIDITY_DAYS)
        for (product in ProductCatalogue.products) {
            assertEquals(
                "validity ${product.productId}",
                365,
                product.validityDays
            )
        }
    }

    @Test
    fun renewalIdsAreBasePlusRenewalSuffix() {
        for (product in ProductCatalogue.products) {
            assertEquals(
                "${product.productId}.renewal",
                product.renewalProductId
            )
            assertEquals(
                product,
                ProductCatalogue.productFor(product.renewalProductId)
            )
        }
        assertEquals(16, ProductCatalogue.allRenewalProductIds.size)
        assertEquals(32, ProductCatalogue.allKnownProductIds.size)
        assertEquals(
            expectedIds.map { "$it.renewal" }.toSet(),
            ProductCatalogue.allRenewalProductIds.toSet()
        )
    }

    @Test
    fun noShelvedTwelveProductIdRemains() {
        val shelved = listOf(
            "full", "situations", "foundations", "messaging", "rails",
            "crossborder", "clearing", "cards", "compliance-ops",
            "architecture-apis", "ba-practice", "emerging"
        )
        for (product in ProductCatalogue.products) {
            val suffix = product.productId.removePrefix("com.paymentstraininghub.app.")
            assertFalse("shelved id in catalogue: ${product.productId}", suffix in shelved)
            assertFalse("shelved id in renewal id", product.renewalProductId.removePrefix("com.paymentstraininghub.app.") in shelved)
        }
    }

    @Test
    fun scopesMatchDecidedLineup() {
        val byId = ProductCatalogue.products.associateBy { it.productId }
        assertEquals(ProductScope.INTEGRATED, byId.getValue("com.paymentstraininghub.app.integrated").scope)
        for (part in 1..10) {
            assertEquals(
                ProductScope.PART,
                byId.getValue("com.paymentstraininghub.app.part-$part").scope
            )
        }
        assertEquals(ProductScope.STUDIO, byId.getValue("com.paymentstraininghub.app.studio").scope)
        assertEquals(ProductScope.SIMULATOR, byId.getValue("com.paymentstraininghub.app.simulator").scope)
        assertEquals(ProductScope.INVESTIGATION_LAB, byId.getValue("com.paymentstraininghub.app.investigation-lab").scope)
        assertEquals(ProductScope.PRACTICE, byId.getValue("com.paymentstraininghub.app.practice").scope)
        assertEquals(ProductScope.INTERVIEW, byId.getValue("com.paymentstraininghub.app.interview").scope)
        assertEquals(10, ProductCatalogue.parts.size)
    }

    @Test
    fun freeSampleSituationsAreExactlyAbh01AndJebC01() {
        assertEquals(
            listOf("ABH-01", "JEB-C01"),
            ProductCatalogue.freeSampleSituationIds
        )
        assertEquals(
            listOf("ABH-01", "JEB-C01"),
            ContentRepository.freeSampleSituationIds
        )
    }
}

class ChapterCoverageTest {

    // Per-part chapter lists, exactly per the product structure
    // proposal section 3 (live v1.1 map).
    private val expectedParts = mapOf(
        1 to listOf("ch1", "ch2", "ch3", "ch4", "ch64", "ch69", "ch70"),
        2 to listOf("ch9", "ch10", "ch11", "ch12", "ch33", "ch56", "ch57", "ch59", "ch61", "ch62", "ch74", "ch91", "ch92", "ch93"),
        3 to listOf("ch5", "ch6", "ch7", "ch8", "ch38", "ch54", "ch39", "ch50", "ch51", "ch52", "ch53", "ch58", "ch60", "ch67"),
        4 to listOf("ch13", "ch14", "ch15", "ch16", "ch94", "ch49", "ch66", "ch75"),
        5 to listOf("ch17", "ch18", "ch21", "ch71", "ch90", "ch19", "ch20", "ch40", "ch44", "ch76", "ch77", "ch88", "ch89"),
        6 to listOf("ch72", "ch23", "ch24", "ch41", "ch86", "ch87", "ch95", "ch73", "ch78", "ch81", "ch99"),
        7 to listOf("ch22", "ch31", "ch32", "ch34", "ch63"),
        8 to listOf("ch25", "ch26", "ch30", "ch35", "ch36", "ch42", "ch43", "ch46", "ch47", "ch48", "ch65", "ch80", "ch68", "ch79"),
        9 to listOf("ch28", "ch45", "ch82", "ch83", "ch84", "ch29", "ch96", "ch97", "ch98", "ch100"),
        10 to listOf("ch55", "ch27", "ch85")
    )

    private val expectedLessonCounts = mapOf(
        1 to 49, 2 to 137, 3 to 149, 4 to 71, 5 to 110,
        6 to 98, 7 to 37, 8 to 118, 9 to 79, 10 to 26
    )

    @Test
    fun partChapterSetsMatchProposalExactly() {
        for ((partNumber, chapters) in expectedParts) {
            val product = ProductCatalogue.productFor("com.paymentstraininghub.app.part-$partNumber")
                ?: throw AssertionError("missing part $partNumber")
            assertEquals(
                "part $partNumber chapters",
                chapters.toSet(),
                product.chapterIds.toSet()
            )
            assertEquals(
                "part $partNumber has no duplicate chapters",
                chapters.size,
                product.chapterIds.toSet().size
            )
        }
    }

    @Test
    fun partsCoverExactlyTheNinetyNineBookChapters() {
        val covered = ProductCatalogue.parts.flatMap { it.chapterIds }
        assertEquals(99, covered.size)
        assertEquals(99, covered.toSet().size)
        assertEquals(ReadingOrder.orderedChapterIds.toSet(), covered.toSet())
    }

    @Test
    fun cataloguePartsAgreeWithReadingOrderParts() {
        for ((partNumber, chapters) in expectedParts) {
            for (chapterId in chapters) {
                assertEquals(
                    "ReadingOrder part for $chapterId",
                    partNumber,
                    ReadingOrder.partFor(chapterId)?.first
                )
                assertEquals(
                    "productForChapter $chapterId",
                    "com.paymentstraininghub.app.part-$partNumber",
                    ProductCatalogue.productForChapter(chapterId)?.productId
                )
            }
        }
    }

    @Test
    fun ch37IsHeldOutAndInNoProductIncludingIntegrated() {
        assertTrue("ch37 held out", "ch37" in ReadingOrder.heldOutIds)
        assertFalse("ch37 not in book order", "ch37" in ReadingOrder.orderedChapterIds)
        assertNull(ProductCatalogue.productForChapter("ch37"))
        for (product in ProductCatalogue.products) {
            assertFalse(
                "ch37 must never be sold: found in ${product.productId}",
                "ch37" in product.chapterIds
            )
        }
    }

    @Test
    fun integratedCoversAllNinetyNineBookChapters() {
        val integrated = ProductCatalogue.productFor(ProductCatalogue.integratedProductId)
            ?: throw AssertionError("missing integrated product")
        assertEquals(ReadingOrder.orderedChapterIds.toSet(), integrated.chapterIds.toSet())
    }

    @Serializable
    private data class AssetLesson(
        @SerialName("id") val id: String
    )

    @Serializable
    private data class AssetChapter(
        @SerialName("id") val id: String,
        @SerialName("lessons") val lessons: List<AssetLesson> = emptyList()
    )

    @Serializable
    private data class AssetPart(
        @SerialName("part") val part: Int,
        @SerialName("chapters") val chapters: List<AssetChapter> = emptyList()
    )

    @Serializable
    private data class AssetMap(
        @SerialName("parts") val parts: List<AssetPart> = emptyList()
    )

    @Test
    fun perPartLessonCountsMatchProposalTotal874() {
        val candidates = listOf(
            File("src/main/assets/reading-order.json"),
            File("app/src/main/assets/reading-order.json")
        )
        val asset = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("reading-order.json asset not found under the test working directory")
        val map = Json { ignoreUnknownKeys = true }
            .decodeFromString<AssetMap>(asset.readText())
        val countsByChapter = map.parts
            .flatMap { it.chapters }
            .associate { it.id to it.lessons.size }
        assertEquals(99, countsByChapter.size)
        var total = 0
        for ((partNumber, chapters) in expectedParts) {
            val sum = chapters.sumOf { countsByChapter.getValue(it) }
            assertEquals("part $partNumber lessons", expectedLessonCounts.getValue(partNumber), sum)
            total += sum
        }
        assertEquals(874, total)
    }
}

class EntitlementRulesTest {

    private val now = 1_700_000_000_000L

    @Test
    fun expiryIsPurchasePlus365Days() {
        assertEquals(
            1_000_000L + EntitlementRules.VALIDITY_MILLIS,
            EntitlementRules.expiryForPurchase(1_000_000L)
        )
        assertEquals(
            365L * 24L * 60L * 60L * 1000L,
            EntitlementRules.VALIDITY_MILLIS
        )
    }

    @Test
    fun missingOrNonPositivePurchaseTimeExpiresInThePast() {
        assertEquals(0L, EntitlementRules.expiryForPurchase(0L))
        assertEquals(0L, EntitlementRules.expiryForPurchase(-5L))
        assertFalse(EntitlementRules.isActive(EntitlementRules.expiryForPurchase(0L), now))
    }

    @Test
    fun isActiveRules() {
        assertTrue(EntitlementRules.isActive(null, now))
        assertTrue(EntitlementRules.isActive(now + 1L, now))
        assertFalse(EntitlementRules.isActive(now, now))
        assertFalse(EntitlementRules.isActive(now - 1L, now))
    }

    @Test
    fun renewalIdsResolveToBase() {
        assertTrue(EntitlementRules.isRenewalProductId("com.paymentstraininghub.app.part-1.renewal"))
        assertTrue(EntitlementRules.isRenewalProductId("com.paymentstraininghub.app.integrated.renewal"))
        assertFalse(EntitlementRules.isRenewalProductId("com.paymentstraininghub.app.part-1"))
        assertFalse(EntitlementRules.isRenewalProductId("com.paymentstraininghub.app.unknown.renewal"))
        assertEquals(
            "com.paymentstraininghub.app.part-1",
            EntitlementRules.baseProductIdFor("com.paymentstraininghub.app.part-1.renewal")
        )
        assertEquals(
            "com.paymentstraininghub.app.part-1",
            EntitlementRules.baseProductIdFor("com.paymentstraininghub.app.part-1")
        )
    }

    @Test
    fun resolveActiveProductsHonoursExpiryAndDropsUnknown() {
        val active = EntitlementRules.resolveActiveProducts(
            playExpiries = mapOf(
                "com.paymentstraininghub.app.part-1" to now + 1000L,
                "com.paymentstraininghub.app.part-2" to now - 1000L,
                "com.paymentstraininghub.app.unknown" to now + 1000L
            ),
            serverExpiries = mapOf(
                "com.paymentstraininghub.app.part-3.renewal" to null,
                "com.paymentstraininghub.app.part-4" to now - 1L
            ),
            debugIds = setOf("com.paymentstraininghub.app.part-5"),
            nowMillis = now
        )
        assertEquals(
            setOf(
                "com.paymentstraininghub.app.part-1",
                "com.paymentstraininghub.app.part-3",
                "com.paymentstraininghub.app.part-5"
            ),
            active
        )
    }

    @Test
    fun activeRenewalWinsOverExpiredPurchase() {
        val active = EntitlementRules.resolveActiveProducts(
            playExpiries = mapOf("com.paymentstraininghub.app.part-1" to now - 1000L),
            serverExpiries = mapOf("com.paymentstraininghub.app.part-1.renewal" to now + 1000L),
            debugIds = emptySet(),
            nowMillis = now
        )
        assertEquals(setOf("com.paymentstraininghub.app.part-1"), active)
    }

    @Test
    fun emptyEntitlementsResolveToNothing() {
        assertTrue(
            EntitlementRules.resolveActiveProducts(
                emptyMap(), emptyMap(), emptySet(), now
            ).isEmpty()
        )
    }

    @Test
    fun firstLessonOfEveryChapterIsFree() {
        assertTrue(EntitlementRules.isFreeSampleLesson("ch9s1", "ch9", "ch9"))
        assertTrue(EntitlementRules.isFreeSampleLesson("ch9s1", "ch9", "ch10"))
        assertTrue(EntitlementRules.isFreeSampleLesson("ch37s1", "ch37", null))
    }

    @Test
    fun secondLessonFreeOnlyInPartFirstChapter() {
        assertTrue(EntitlementRules.isFreeSampleLesson("ch9s2", "ch9", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch10s2", "ch10", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch37s2", "ch37", null))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch9s3", "ch9", "ch9"))
    }

    @Test
    fun supplementalAndMismatchedIdsAreNeverFree() {
        assertFalse(EntitlementRules.isFreeSampleLesson("ch9s901", "ch9", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch9-supp", "ch9", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch10s1", "ch9", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("guide-1", "ch9", "ch9"))
        assertFalse(EntitlementRules.isFreeSampleLesson("ch9s1", "guide-x", "ch9"))
    }
}

class FreeSampleRulesTest {

    @Test
    fun sampleCountsAreDecidedValues() {
        assertEquals(2, FreeSampleRules.SAMPLES_PER_PRODUCT)
        assertEquals(5, FreeSampleRules.INTERVIEW_FREE_SAMPLES)
    }

    @Test
    fun sampleIdsTakeFirstItemsInOrder() {
        assertEquals(listOf("a", "b"), FreeSampleRules.sampleIds(listOf("a", "b", "c", "d")))
        assertEquals(listOf("a"), FreeSampleRules.sampleIds(listOf("a")))
        assertEquals(emptyList<String>(), FreeSampleRules.sampleIds(emptyList()))
        assertTrue(FreeSampleRules.isFreeSampleId("b", listOf("a", "b", "c")))
        assertFalse(FreeSampleRules.isFreeSampleId("c", listOf("a", "b", "c")))
    }

    @Test
    fun freeInterviewQuestionsAreFirstFive() {
        val ids = (1..10).map { "q$it" }
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), FreeSampleRules.freeInterviewQuestionIds(ids))
        assertEquals(listOf("q1", "q2"), FreeSampleRules.freeInterviewQuestionIds(listOf("q1", "q2")))
    }
}

class ReferralPromoRulesTest {

    @Test
    fun referralCreditIsOneHundredToReferrerOnlyOnPurchase() {
        assertEquals(100, ReferralPromoRules.REFERRAL_CREDIT_INR)
        assertEquals(999, ReferralPromoRules.REFERRAL_MINIMUM_PRICE_INR)
        assertTrue(ReferralPromoRules.REFERRAL_GOES_TO_REFERRER_ONLY)
        assertTrue(ReferralPromoRules.REFERRAL_REQUIRES_COMPLETED_PURCHASE)
    }

    @Test
    fun referralEligibleExactlyPracticeInterviewIntegrated() {
        val eligible = ProductCatalogue.products
            .map { it.productId }
            .filter { ReferralPromoRules.isReferralEligible(it) }
        assertEquals(
            setOf(
                "com.paymentstraininghub.app.practice",
                "com.paymentstraininghub.app.interview",
                "com.paymentstraininghub.app.integrated"
            ),
            eligible.toSet()
        )
        assertFalse(ReferralPromoRules.isReferralEligible("com.paymentstraininghub.app.part-1"))
        assertFalse(ReferralPromoRules.isReferralEligible("com.paymentstraininghub.app.studio"))
        assertFalse(ReferralPromoRules.isReferralEligible("com.paymentstraininghub.app.unknown"))
    }

    @Test
    fun promoExcludesIntegratedAndNeverStacks() {
        assertEquals("FIRSTLAUNCH25", ReferralPromoRules.PROMO_CODE)
        assertEquals(25, ReferralPromoRules.PROMO_DISCOUNT_PERCENT)
        assertFalse(ReferralPromoRules.PROMO_STACKS_WITH_REFERRAL)
        assertFalse(ReferralPromoRules.isPromoEligible("com.paymentstraininghub.app.integrated"))
        for (product in ProductCatalogue.products) {
            if (product.productId != ProductCatalogue.integratedProductId) {
                assertTrue(
                    "promo eligible: ${product.productId}",
                    ReferralPromoRules.isPromoEligible(product.productId)
                )
            }
        }
        assertFalse(ReferralPromoRules.isPromoEligible("com.paymentstraininghub.app.unknown"))
    }
}
