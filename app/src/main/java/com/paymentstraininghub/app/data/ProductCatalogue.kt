package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.CourseProduct
import com.paymentstraininghub.app.data.model.ProductScope

// ProductCatalogue.kt
// The one authoritative product catalogue: the decided 16-product
// lineup (15 Nov 2026). The old 12-product chapter-bundle division
// is shelved and must never return: no old product id appears
// anywhere in this file.
//
// The lineup: the 10 book parts (live v1.1 map, section 3 of the
// product-structure proposal), BA/PO Studio, Payment Simulator,
// Investigation Lab, Practice Experiences, Interview Ready, and
// The Integrated Course. ISO 20022 Explorer is free for everyone
// and is not a product.
//
// Prices: priceInr is the SALE reference price and listPriceInr is
// the LIST reference price. Both are reference data only. Neither
// is EVER shown as a purchase price. The only price a buyer ever
// sees is the Play Billing product price once it has loaded, and
// Play products carry the sale price only. The list price is
// website display only and is never shown in this app. If the Play
// product has not loaded, the price is shown as unavailable and
// purchase is disabled. Play Console is the source of truth for
// prices. The single exception is DEBUG builds, where Checkout may
// show the reference sale price labelled honestly as the catalogue
// price with a Play-not-loaded note, so unlocking can be tested
// before Play products exist.
//
// Validity: every product gives 1 year of access (365 days) from
// the verified purchase timestamp. Each product also has a renewal
// subscription (product id plus ".renewal", a 1-year Play base
// plan) at half the SALE price, which keeps the product active
// while the subscription is active. Renewal prices are halves of
// the sale price, never of the list price.

/// The one authoritative catalogue of the buyable products.
///
/// Product IDs follow com.paymentstraininghub.app.<product-id> and must
/// match the Play Console products exactly. Renewal subscription
/// IDs are the purchase id plus ".renewal".
///
/// Included content per product:
///   The Integrated Course: all 10 parts (99 chapters, 874
///     lessons), Studio, Simulator, Investigation Lab, Practice
///     Experiences and Interview Ready. Everything.
///   Part 1 to Part 10: only their own chapters, in reading order.
///     Supplemental lessons follow their chapter's part, and
///     chapter tests belong to their chapters' parts.
///   BA/PO Studio: 156 BABOK teaching units plus 5 career guides.
///   Payment Simulator: all 10 simulators.
///   Investigation Lab: the full 616-item Investigation Experience.
///   Practice Experiences: 564 situations, Practice Mode, Daily
///     Challenge, 10 labs, 14 ISO exercises, 52 weekly cases.
///   Interview Ready: 500 interview questions plus 320 FAQ.
/// ch37 (How 10 Large Banks Do Payments) is in NO product and is
/// never sold, including by the Integrated Course.
///
/// Free samples: 2 free sample situations stay free without any
/// purchase (ABH-01 and JEB-C01). The first lesson of each chapter
/// is also free (settled D77), plus the first 2 lessons of each
/// part in reading order. Interview Ready keeps 5 free questions.
/// The weekly free case stays free. ISO 20022 Explorer is free.
///
/// Entitlement rules (enforced by the billing layer, stated here so
/// the offer and the enforcement cannot drift apart):
///   A part unlocks only its own chapters, while active.
///   A standalone product unlocks only its own experience, while
///     active.
///   The Integrated Course unlocks everything, while active.
///   A product is active when the user holds an active purchase
///     of it, an active renewal subscription for it, or an active
///     Integrated purchase or subscription. Expired entitlements
///     lock again.
///   A chapter in no part that is not held out is LOCKED
///     (fail closed): an unmapped chapter is a catalogue error
///     and must never be given away silently. ch37 is held out
///     on purpose: it is in no product, is never sold, and is
///     always reachable, matching iOS and the website.
object ProductCatalogue {

    /// All 16 buyable products, in catalogue order: the Integrated
    /// Course first, then the 10 parts, then the standalone
    /// products.
    val products: List<CourseProduct> = listOf(
        CourseProduct(
            id = "integrated",
            productId = "com.paymentstraininghub.app.integrated",
            name = "The Integrated Course",
            tagline = "Everything in the academy, in one course.",
            description = "The complete Payments Training Hub academy in one course: all 10 courses, 99 chapters and 874 lessons, plus BA/PO Studio, the Payment Simulator, the Investigation Lab, Practice Experiences and Interview Ready. Buy this and everything unlocks for 1 year.",
            audience = "For learners who want the full academy: every course and every practice experience, in one purchase.",
            priceInr = 2999,
            listPriceInr = 4499,
            renewalPriceInr = 1500,
            chapterIds = listOf(
                "ch1", "ch2", "ch3", "ch4", "ch64", "ch69", "ch70",
                "ch9", "ch10", "ch11", "ch12", "ch33", "ch56", "ch57", "ch59", "ch61", "ch62", "ch74", "ch91", "ch92", "ch93",
                "ch5", "ch6", "ch7", "ch8", "ch38", "ch54", "ch39", "ch50", "ch51", "ch52", "ch53", "ch58", "ch60", "ch67",
                "ch13", "ch14", "ch15", "ch16", "ch94", "ch49", "ch66", "ch75",
                "ch17", "ch18", "ch21", "ch71", "ch90", "ch19", "ch20", "ch40", "ch44", "ch76", "ch77", "ch88", "ch89",
                "ch72", "ch23", "ch24", "ch41", "ch86", "ch87", "ch95", "ch73", "ch78", "ch81", "ch99",
                "ch22", "ch31", "ch32", "ch34", "ch63",
                "ch25", "ch26", "ch30", "ch35", "ch36", "ch42", "ch43", "ch46", "ch47", "ch48", "ch65", "ch80", "ch68", "ch79",
                "ch28", "ch45", "ch82", "ch83", "ch84", "ch29", "ch96", "ch97", "ch98", "ch100",
                "ch55", "ch27", "ch85"
            ),
            includesSituations = true,
            scope = ProductScope.INTEGRATED
        ),
        CourseProduct(
            id = "part-1",
            productId = "com.paymentstraininghub.app.part-1",
            name = "Course 1: Foundations of Money and Payments",
            tagline = "What a payment is, and how the ecosystem fits together.",
            description = "Start here. Speak the language of payments and follow any payment from initiation to settlement: what a payment is, instruments and types, the ecosystem, the end-to-end lifecycle, cheques, what money is, and accounts and balances.",
            audience = "For complete beginners. No prior knowledge of payments is assumed.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch1", "ch2", "ch3", "ch4", "ch64", "ch69", "ch70"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-2",
            productId = "com.paymentstraininghub.app.part-2",
            name = "Course 2: How Payments Move",
            tagline = "India, US, Europe and UK rails, plus TARGET and TIPS.",
            description = "How money actually moves in each market. Read a scheme rulebook, follow a payment on any major rail, and compare rails to recommend the right one: UPI, Fedwire, SEPA, Faster Payments, TARGET services, TIPS, and instant systems worldwide.",
            audience = "For learners who know the fundamentals and need to follow payments on real rails and read scheme rulebooks.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch9", "ch10", "ch11", "ch12", "ch33", "ch56", "ch57", "ch59", "ch61", "ch62", "ch74", "ch91", "ch92", "ch93"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-3",
            productId = "com.paymentstraininghub.app.part-3",
            name = "Course 3: Messages: SWIFT MT, ISO 20022 and Standards",
            tagline = "MT and MX messages, field by field, with real examples.",
            description = "The messaging course. Read, write, map and validate SWIFT MT and ISO 20022 messages field by field, and run an MT to MX migration: SWIFT fundamentals, pacs.008, pain.001, the full ISO 20022 catalogue, and CBPR+ rejects and returns.",
            audience = "For learners who work with payment messages: reading, writing, mapping and validating MT and MX messages.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch5", "ch6", "ch7", "ch8", "ch38", "ch54", "ch39", "ch50", "ch51", "ch52", "ch53", "ch58", "ch60", "ch67"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-4",
            productId = "com.paymentstraininghub.app.part-4",
            name = "Course 4: Cross-Border Payments and Correspondent Banking",
            tagline = "How international payments really work, end to end.",
            description = "Trace a cross-border payment bank by bank. Correspondent banking and nostro vostro, serial versus cover, charges, FX and gpi tracking, cross-border exceptions and investigations, standby letters of credit, and trade finance.",
            audience = "For learners who handle international payments, correspondent banking and cross-border exceptions.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch13", "ch14", "ch15", "ch16", "ch94", "ch49", "ch66", "ch75"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-5",
            productId = "com.paymentstraininghub.app.part-5",
            name = "Course 5: Clearing, Settlement, Liquidity and Reconciliation",
            tagline = "Clearing is not settlement. Learn the difference properly.",
            description = "Run the settlement day: clear, net, settle, fund liquidity, reconcile the books, and clear breaks. Clearing and netting, settlement models, card clearing, ledgers, liquidity, settlement risk, reconciliation, operations, and cash management.",
            audience = "For learners who run or support the settlement day: clearing, settlement, liquidity and reconciliation.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch17", "ch18", "ch21", "ch71", "ch90", "ch19", "ch20", "ch40", "ch44", "ch76", "ch77", "ch88", "ch89"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-6",
            productId = "com.paymentstraininghub.app.part-6",
            name = "Course 6: Exceptions, Investigations, Returns and Claims",
            tagline = "Sanctions, AML, exceptions, returns and the daily control grind.",
            description = "Run an investigation to closure, clear a sanctions alert, handle a return or recall, and prove the control worked. Failure modes, sanctions screening, AML and fraud, exceptions and investigations, compliance, controls, claims, and AI governance.",
            audience = "For learners who handle exceptions, investigations, sanctions alerts, returns and controls.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch72", "ch23", "ch24", "ch41", "ch86", "ch87", "ch95", "ch73", "ch78", "ch81", "ch99"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-7",
            productId = "com.paymentstraininghub.app.part-7",
            name = "Course 7: Cards and Customer Payments",
            tagline = "Authorisation, wallets, POS, and chargebacks.",
            description = "Follow a card payment from tap to settlement. 3D Secure, tokenization and chargebacks, card networks, digital wallets and POS systems, recurring payments, and the card payments deep dive.",
            audience = "For learners who work with card payments, wallets, POS systems and customer payments.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch22", "ch31", "ch32", "ch34", "ch63"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-8",
            productId = "com.paymentstraininghub.app.part-8",
            name = "Course 8: Architecture, APIs, Hubs and Engineering",
            tagline = "Hubs, platforms, gateways and integration, the engineering view.",
            description = "Design a payment hub, specify an API, integrate a rail, and test and certify the result. Hub and gateway architecture, API and open banking, system design, testing and certification, security, real-time architecture, and integration patterns.",
            audience = "For engineers, architects and business analysts who design, specify and integrate payment systems.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch25", "ch26", "ch30", "ch35", "ch36", "ch42", "ch43", "ch46", "ch47", "ch48", "ch65", "ch80", "ch68", "ch79"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-9",
            productId = "com.paymentstraininghub.app.part-9",
            name = "Course 9: The Business Analyst at Work",
            tagline = "Do the BA job end to end, and deliver the capstone.",
            description = "Gather requirements, write the BRD and user stories, map messages, run workshops, use AI properly, and deliver the capstone. The BA practical workshop, product management, product strategy, scheme onboarding, BA practice depth, and AI for the payments BA and PO.",
            audience = "For business analysts and product owners doing payments work, including those new to business analysis.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch28", "ch45", "ch82", "ch83", "ch84", "ch29", "ch96", "ch97", "ch98", "ch100"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "part-10",
            productId = "com.paymentstraininghub.app.part-10",
            name = "Course 10: Emerging Payments and the Future",
            tagline = "CBDCs, stablecoins, embedded finance, and where payments goes next.",
            description = "Judge what is real in CBDCs, stablecoins, embedded finance and AI, and say what each means for your bank and your role. Payments in 2025 and beyond, CBDCs, stablecoins and AI, and embedded finance with ISO 20022 data analytics.",
            audience = "For learners who need to judge emerging payments: CBDCs, stablecoins, embedded finance and AI.",
            priceInr = 399,
            listPriceInr = 599,
            renewalPriceInr = 200,
            chapterIds = listOf("ch55", "ch27", "ch85"),
            includesSituations = false,
            scope = ProductScope.PART
        ),
        CourseProduct(
            id = "studio",
            productId = "com.paymentstraininghub.app.studio",
            name = "BA/PO Studio",
            tagline = "BABOK teaching for the payments BA and PO, plus career guides.",
            description = "156 teaching units built from the BABOK Guide: key concepts, tasks, techniques, competencies and perspectives, each teaching how to write the output with a worked example. Plus 5 career guides with step by step routes into business analysis, product ownership, product management, and payments specialism.",
            audience = "For business analysts and product owners building BABOK-based outputs and planning a career move.",
            priceInr = 799,
            listPriceInr = 1199,
            renewalPriceInr = 400,
            chapterIds = emptyList(),
            includesSituations = false,
            scope = ProductScope.STUDIO
        ),
        CourseProduct(
            id = "simulator",
            productId = "com.paymentstraininghub.app.simulator",
            name = "Payment Simulator",
            tagline = "Run a payment, a hub, an exception, or a message flow.",
            description = "All 10 simulators: Payment Lifecycle, Payment Hub Architecture, T2 and TIPS, Payment Accounting, Payment Exception Investigation, Payment State Machine, Scenario, API Playground, Cross-Border, and Message Explorer. Running a payment is doing, not reading.",
            audience = "For learners who learn by doing: run payments, hubs, exceptions and message flows.",
            priceInr = 499,
            listPriceInr = 749,
            renewalPriceInr = 250,
            chapterIds = emptyList(),
            includesSituations = false,
            scope = ProductScope.SIMULATOR
        ),
        CourseProduct(
            id = "investigation-lab",
            productId = "com.paymentstraininghub.app.investigation-lab",
            name = "Investigation Lab",
            tagline = "Trace a case, check the message evidence, decide.",
            description = "The full 616-item Investigation Experience. Trace a case, check message evidence, form a hypothesis, decide, and close the case with a decision note. The core doing experience for exceptions and investigations.",
            audience = "For learners who work exceptions and investigations and need to practise deciding with evidence.",
            priceInr = 499,
            listPriceInr = 749,
            renewalPriceInr = 250,
            chapterIds = emptyList(),
            includesSituations = false,
            scope = ProductScope.INVESTIGATION_LAB
        ),
        CourseProduct(
            id = "practice",
            productId = "com.paymentstraininghub.app.practice",
            name = "Practice Experiences",
            tagline = "Do the work: situations, practice, labs, exercises, weekly cases.",
            description = "564 Payment Situations sequenced from foundations to advanced, full Practice Mode, the full Daily Challenge, 10 labs, 14 ISO 20022 exercises, and 52 weekly cases. The weekly free case stays free for everyone.",
            audience = "For learners who want to practise: situations, practice mode, labs, exercises and weekly cases.",
            priceInr = 999,
            listPriceInr = 1499,
            renewalPriceInr = 500,
            chapterIds = emptyList(),
            includesSituations = true,
            scope = ProductScope.PRACTICE
        ),
        CourseProduct(
            id = "interview",
            productId = "com.paymentstraininghub.app.interview",
            name = "Interview Ready",
            tagline = "Walk into a payments interview ready to answer.",
            description = "500 interview questions across every domain, each with a model answer, plus the 320-item FAQ bank. For active job seekers preparing for a payments interview.",
            audience = "For active job seekers preparing for a payments interview.",
            priceInr = 1599,
            listPriceInr = 2399,
            renewalPriceInr = 800,
            chapterIds = emptyList(),
            includesSituations = false,
            scope = ProductScope.INTERVIEW
        )
    )

    /// All purchase product IDs, in catalogue order.
    val allProductIds: List<String> get() = products.map { it.productId }

    /// All renewal subscription product IDs, in catalogue order:
    /// each purchase id plus ".renewal".
    val allRenewalProductIds: List<String> get() = products.map { it.renewalProductId }

    /// Every known product id, purchases and renewals.
    val allKnownProductIds: Set<String> get() = (allProductIds + allRenewalProductIds).toSet()

    /// The Integrated Course product ID: while active, it unlocks
    /// every product.
    const val integratedProductId = "com.paymentstraininghub.app.integrated"

    /// The Practice Experiences product ID: situations, Practice
    /// Mode, Daily Challenge, labs, ISO exercises, weekly cases.
    const val practiceProductId = "com.paymentstraininghub.app.practice"

    /// The BA/PO Studio product ID.
    const val studioProductId = "com.paymentstraininghub.app.studio"

    /// The Payment Simulator product ID: all 10 simulators.
    const val simulatorProductId = "com.paymentstraininghub.app.simulator"

    /// The Investigation Lab product ID: the full 616-item
    /// Investigation Experience.
    const val investigationLabProductId = "com.paymentstraininghub.app.investigation-lab"

    /// The Interview Ready product ID: 500 questions plus 320 FAQ.
    const val interviewProductId = "com.paymentstraininghub.app.interview"

    /// The free sample situations, open without any purchase. This
    /// list mirrors ContentRepository.freeSampleSituationIds, which
    /// is the runtime source; keep the two in step.
    val freeSampleSituationIds: List<String> = listOf(
        "ABH-01", "JEB-C01"
    )

    /// The product for a Play product ID, null when unknown. A
    /// renewal subscription id resolves to its base product.
    fun productFor(productId: String): CourseProduct? {
        products.firstOrNull { it.productId == productId }?.let { return it }
        if (productId.endsWith(".renewal")) {
            val base = productId.removeSuffix(".renewal")
            return products.firstOrNull { it.productId == base }
        }
        return null
    }

    /// The part that contains this chapter, not counting the
    /// Integrated Course (which contains every chapter). Null when
    /// no part contains it; such a chapter maps to no product.
    /// ch37 maps to no part, on purpose.
    fun productForChapter(chapterId: String): CourseProduct? =
        products.firstOrNull { it.scope == ProductScope.PART && it.chapterIds.contains(chapterId) }

    /// The 10 book parts, in reading order.
    val parts: List<CourseProduct> get() = products.filter { it.scope == ProductScope.PART }
}
