package com.paymentstraininghub.app.billing

import com.paymentstraininghub.app.data.ProductCatalogue

/*
 * EntitlementRules: the pure, testable core of the entitlement
 * model. No Android types, no Play types, no I/O: BillingManager
 * feeds it timestamps and id sets, and it answers. The same rules
 * apply on every surface:
 *
 * VALIDITY: every product gives 1 year of access (365 days) from
 * the verified purchase timestamp. A one-time purchase expires
 * 365 days after its Play purchase time. A renewal subscription
 * keeps its product active while Play reports the subscription
 * as active; its expiry is the subscription expiry time, which
 * only the store and the server know, so Play-side subscription
 * presence carries no app-side expiry (null) and the server read
 * carries the server expiry when it has one.
 *
 * ENTITLEMENT: a product is active when the user holds an ACTIVE
 * purchase of that product OR an ACTIVE renewal subscription for
 * that product OR an ACTIVE Integrated purchase or subscription.
 * Expired entitlements lock again. Renewal ids always resolve to
 * their base product before any check. Unknown ids never unlock.
 *
 * FREE SAMPLES live in FreeSampleRules below: 2 per product, with
 * Interview Ready keeping 5 free questions, on top of the D77
 * first-lesson-of-chapter browsing rule.
 */

object EntitlementRules {

    /** 1 year of access, for every product. No other term exists. */
    const val VALIDITY_DAYS: Int = 365

    /** 365 days in milliseconds. */
    const val VALIDITY_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

    /**
     * The expiry instant for a one-time purchase: the verified
     * purchase timestamp plus the validity term. A missing or
     * non-positive purchase timestamp expires in the past (fail
     * closed): an unknown purchase time never grants a year.
     */
    fun expiryForPurchase(purchaseTimeMillis: Long, validityDays: Int = VALIDITY_DAYS): Long {
        if (purchaseTimeMillis <= 0L) return 0L
        return purchaseTimeMillis + validityDays * 24L * 60L * 60L * 1000L
    }

    /**
     * True when an entitlement with this expiry is active now. A
     * null expiry means the store or server owns the state (an
     * active subscription reported by Play, or a legacy server
     * row with no expiry): active. Any known expiry must be in
     * the future; expiry at exactly now is expired.
     */
    fun isActive(expiresAtMillis: Long?, nowMillis: Long): Boolean {
        if (expiresAtMillis == null) return true
        return expiresAtMillis > nowMillis
    }

    /** True when this id is a renewal subscription id. */
    fun isRenewalProductId(productId: String): Boolean =
        productId in ProductCatalogue.allRenewalProductIds.toSet()

    /**
     * The base purchase id for any known id: a renewal id resolves
     * to its product, a purchase id resolves to itself. Unknown
     * ids pass through unchanged so callers can reject them.
     */
    fun baseProductIdFor(productId: String): String {
        if (isRenewalProductId(productId)) return productId.removeSuffix(".renewal")
        return productId
    }

    /**
     * The active base product ids from the Play and server
     * entitlement maps plus the debug unlock set, at [nowMillis].
     * Each map holds raw product ids (purchase or renewal) to
     * expiry instants, null when the store or server owns the
     * state. Renewal ids resolve to their base product, unknown
     * ids are dropped, and a product is active when ANY of its
     * records is active. Debug ids are always active.
     */
    fun resolveActiveProducts(
        playExpiries: Map<String, Long?>,
        serverExpiries: Map<String, Long?>,
        debugIds: Set<String>,
        nowMillis: Long
    ): Set<String> {
        val known = ProductCatalogue.allProductIds.toSet()
        val active = mutableSetOf<String>()
        for ((productId, expiresAt) in playExpiries.entries + serverExpiries.entries) {
            val base = baseProductIdFor(productId)
            if (base !in known) continue
            if (isActive(expiresAt, nowMillis)) active.add(base)
        }
        for (debugId in debugIds) {
            val base = baseProductIdFor(debugId)
            if (base in known) active.add(base)
        }
        return active
    }

    /**
     * True when an admin session unlocks this product: the admin
     * flag is set and the id names a known catalogue product (a
     * renewal id resolves to its base product first). Unknown ids
     * never unlock, even for an admin: no screen gates on an
     * unknown id, and the admin bypass must not invent products.
     * The admin flag itself is read from the session token (see
     * AdminClaims); this function only applies it.
     */
    fun isAdminUnlocked(isAdmin: Boolean, productId: String): Boolean {
        if (!isAdmin) return false
        return baseProductIdFor(productId) in ProductCatalogue.allProductIds.toSet()
    }

    private val lessonIdRegex = Regex("^ch(\\d+)s(\\d+)$")
    private val chapterIdRegex = Regex("^ch(\\d+)$")

    /**
     * True when this lesson is free without any purchase: it is
     * the first lesson of its chapter (D77 browsing), or it is
     * the second lesson of its part's first chapter (the 2-sample
     * sequential rule: the first 2 lessons of a part, in reading
     * order, are free). [firstChapterIdOfPart] is the first
     * chapter id of the lesson's part in reading order, or null
     * when the chapter maps to no part (ch37: only its first
     * lesson is free). Supplemental ids never match.
     */
    fun isFreeSampleLesson(
        lessonId: String,
        chapterId: String,
        firstChapterIdOfPart: String?
    ): Boolean {
        val lessonMatch = lessonIdRegex.matchEntire(lessonId) ?: return false
        val lessonNumber = lessonMatch.groupValues[2].toIntOrNull() ?: return false
        val chapterNumber = chapterIdRegex.matchEntire(chapterId)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        if (lessonMatch.groupValues[1].toIntOrNull() != chapterNumber) return false
        if (lessonNumber == 1) return true
        if (lessonNumber == 2 && firstChapterIdOfPart != null && chapterId == firstChapterIdOfPart) return true
        return false
    }
}

/**
 * The free-sample counts and selectors, shared by every gated
 * surface so the sample rule cannot drift between screens.
 * Samples are the first items in catalogue order: the first 2
 * lessons of a part, the first 2 Studio units, the first 2
 * career guides, the first 2 simulator stages, the 2 free
 * situations, and the first 5 interview questions.
 */
object FreeSampleRules {

    /** Free samples per product or section, with a buy prompt after the second. */
    const val SAMPLES_PER_PRODUCT: Int = 2

    /** Free interview questions. Interview Ready keeps 5. */
    const val INTERVIEW_FREE_SAMPLES: Int = 5

    /** The first [count] ids of an ordered list: its free samples. */
    fun sampleIds(orderedIds: List<String>, count: Int = SAMPLES_PER_PRODUCT): List<String> =
        orderedIds.take(count)

    /** True when this id is among the first [count] of the ordered list. */
    fun isFreeSampleId(id: String, orderedIds: List<String>, count: Int = SAMPLES_PER_PRODUCT): Boolean =
        orderedIds.take(count).contains(id)

    /** The free interview question ids: the first 5 in bank order. */
    fun freeInterviewQuestionIds(orderedQuestionIds: List<String>): List<String> =
        orderedQuestionIds.take(INTERVIEW_FREE_SAMPLES)
}
