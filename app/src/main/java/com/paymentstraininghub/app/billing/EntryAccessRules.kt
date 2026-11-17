// EntryAccessRules.kt
// Payments Training Hub Android - the pure entry-gating rules
// for search-index entries (guides, labs, tests, FAQs), ported
// from iOS EntryAccessRules in ContentModels.swift so the two
// platforms cannot drift apart. No Android types, no I/O: the
// caller feeds entitlement booleans and the entry's position
// among entries of its type in catalogue order, and this object
// answers. Screens apply the answer by showing the paywall view
// (title, what to expect, buy button) instead of the entry text.

package com.paymentstraininghub.app.billing

import com.paymentstraininghub.app.data.ProductCatalogue
import com.paymentstraininghub.app.data.model.SearchEntry

object EntryAccessRules {

    /**
     * The chapters each standalone test draws its questions
     * from, taken from the live test pages: every question in a
     * test page's TEST_DATA carries its chapter. Copied verbatim
     * from iOS EntryAccessRules.testChapters so the two
     * platforms cannot drift apart. A test is unlocked only
     * when every chapter it draws on is unlocked, so owning one
     * part never exposes another part's questions. The map must
     * be extended when a test page gains questions from a
     * chapter it did not draw on before.
     */
    val testChapters: Map<String, List<String>> = mapOf(
        "t-apis" to listOf("ch26", "ch30", "ch38", "ch47", "ch65"),
        "t-architecture" to listOf("ch25", "ch35", "ch36", "ch37", "ch48"),
        "t-cards" to listOf("ch21", "ch22", "ch31", "ch32", "ch34", "ch63"),
        "t-clearing" to listOf("ch17", "ch18", "ch19", "ch20"),
        "t-compliance" to listOf("ch23", "ch24", "ch40", "ch41", "ch43", "ch44", "ch68"),
        "t-crossborder" to listOf("ch13", "ch14", "ch15", "ch16", "ch49", "ch51", "ch53", "ch58"),
        "t-emerging" to listOf("ch27", "ch34", "ch46", "ch55", "ch59"),
        "t-foundations" to listOf("ch1", "ch2", "ch3", "ch4", "ch64"),
        "t-messaging" to listOf("ch5", "ch6", "ch7", "ch8", "ch39", "ch50", "ch52", "ch60", "ch67"),
        "t-mock-1" to listOf("ch1", "ch3", "ch4", "ch6", "ch7", "ch8", "ch9", "ch10", "ch11", "ch39", "ch50", "ch56", "ch57", "ch62"),
        "t-mock-2" to listOf("ch14", "ch17", "ch18", "ch20", "ch21", "ch22", "ch23", "ch24", "ch40", "ch51", "ch58"),
        "t-mock-3" to listOf("ch2", "ch8", "ch11", "ch16", "ch19", "ch24", "ch25", "ch26", "ch27", "ch28", "ch31", "ch36", "ch42", "ch44", "ch65"),
        "t-operations" to listOf("ch40", "ch41", "ch42", "ch43", "ch44", "ch45"),
        "t-professional" to listOf("ch28", "ch29", "ch33", "ch42", "ch45", "ch47"),
        "t-rails" to listOf("ch9", "ch10", "ch11", "ch12", "ch36", "ch56", "ch57", "ch59", "ch62")
    )

    /**
     * True when this test's questions must stay locked: any
     * chapter it draws on is locked, or the test has no entry
     * in [testChapters] (fail closed for unknown tests).
     */
    fun isTestLocked(testId: String, isChapterUnlocked: (String) -> Boolean): Boolean {
        val chapters = testChapters[testId] ?: return true
        return chapters.any { !isChapterUnlocked(it) }
    }

    /**
     * The single product whose purchase unlocks this test: the
     * one part all its chapters belong to, or the Integrated
     * Course when its chapters span parts (or the test is
     * unknown), because only the Integrated Course unlocks
     * every chapter. Held-out chapters (ch37) belong to no part
     * and never force the Integrated fallback on their own.
     */
    fun testUnlockingProductId(testId: String): String {
        val chapters = testChapters[testId] ?: return ProductCatalogue.integratedProductId
        val parts = chapters.mapNotNull { ProductCatalogue.productForChapter(it)?.productId }.toSet()
        if (parts.size == 1) return parts.first()
        return ProductCatalogue.integratedProductId
    }

    /**
     * True when this entry's full text is locked.
     *
     * - FAQ: locked unless Interview Ready is unlocked, except
     *   the first [FreeSampleRules.FAQ_FREE_COUNT] in catalogue
     *   order, which are free samples. An entry that is not in
     *   the loaded list (null index) fails closed.
     * - Lab: locked unless Practice Experiences is unlocked,
     *   except the first [FreeSampleRules.LAB_FREE_COUNT] in
     *   catalogue order. A null index fails closed.
     * - Test: locked unless every chapter it draws on is
     *   unlocked (see [isTestLocked]). The caller passes false
     *   for [isChapterUnlocked] when [isTestLocked] says the
     *   test is locked, so the test branch here needs no
     *   chapter of its own. An unknown test fails closed.
     * - Lesson, situation, guide: never locked at entry level.
     *   Lessons and situations gate at their detail screens;
     *   guides never lock.
     */
    fun isLocked(
        entry: SearchEntry,
        indexInType: Int?,
        isPracticeUnlocked: Boolean,
        isInterviewUnlocked: Boolean,
        isChapterUnlocked: Boolean
    ): Boolean {
        return when {
            entry.isFaq -> {
                if (isInterviewUnlocked) return false
                val index = indexInType ?: return true
                index >= FreeSampleRules.FAQ_FREE_COUNT
            }
            entry.isLab -> {
                if (isPracticeUnlocked) return false
                val index = indexInType ?: return true
                index >= FreeSampleRules.LAB_FREE_COUNT
            }
            entry.isTest -> !isChapterUnlocked
            else -> false
        }
    }

    /**
     * The product id that unlocks this entry, for lock copy and
     * the checkout route. Null for entry types that never lock.
     * For a test, the product comes from
     * [testUnlockingProductId]: its single part, or the
     * Integrated Course when its chapters span parts or the
     * test is unknown.
     */
    fun unlockingProductId(entry: SearchEntry): String? {
        return when {
            entry.isFaq -> ProductCatalogue.interviewProductId
            entry.isLab -> ProductCatalogue.practiceProductId
            entry.isTest -> testUnlockingProductId(entry.id)
            else -> null
        }
    }
}
