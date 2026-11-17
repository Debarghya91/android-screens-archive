package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.AgendaEntry
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.Agendas
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.FullSituationItem
import com.paymentstraininghub.app.data.ThumbnailStore
import com.paymentstraininghub.app.data.model.LessonBeat
import com.paymentstraininghub.app.data.model.LessonDetail
import com.paymentstraininghub.app.data.model.QuizQuestion
import com.paymentstraininghub.app.data.model.SearchCounts
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.data.model.SearchIndex
import com.paymentstraininghub.app.data.model.Situation
import com.paymentstraininghub.app.data.model.SituationDiagram
import com.paymentstraininghub.app.data.model.SituationDiagramPart
import com.paymentstraininghub.app.data.model.SituationFlow
import com.paymentstraininghub.app.data.model.SituationFlowStep
import com.paymentstraininghub.app.data.model.SituationStacked
import com.paymentstraininghub.app.data.model.SituationStackedSegment
import com.paymentstraininghub.app.data.model.SituationVisual
import kotlinx.serialization.json.JsonPrimitive

/**
 * Realistic fake content for the Robolectric render harness. The
 * situations include the two free samples ABH-01 and JEB-C01 plus
 * one situation per learning stage, so the Payment Cases list
 * renders both the free samples section and every stage group.
 * No em dashes in any string here.
 */
internal object RenderFakeData {

    private const val ABH01_EXPLAIN =
        "A corporate client sent a pacs.008 payment of EUR 40,000 to a beneficiary in Germany. " +
            "The payment left the sending bank on time, but the beneficiary bank returned it two days later " +
            "with a pacs.004 return, reason code AC04, closed account number. The client had changed banks " +
            "and had not told the sender. The operations team had to decide whether to repair and resend " +
            "the payment, contact the client for new account details, or hold the funds while the " +
            "investigation ran. Each option carried a different cost and a different risk to the client " +
            "relationship. The team checked the original payment instruction, confirmed the account " +
            "number matched the instruction on file, and then called the client to confirm the new " +
            "account before resending. The resend settled the same day."

    private const val ABH01_TAKEAWAY =
        "Always confirm changed beneficiary details with the client before resending a returned payment."

    private const val JEB01_EXPLAIN =
        "A pacs.002 status report arrived with status RJCT for a batch of twelve salary payments " +
            "totalling GBP 85,000. The rejection reason was a format error in one payment, but the " +
            "whole batch had been rejected by the clearing system. The payroll team needed the " +
            "payments to reach staff accounts by Friday. The business analyst traced the error to a " +
            "single invalid character in a remittance field, split the batch, repaired the one " +
            "payment, and resubmitted the batch in two parts. Eleven payments settled on Thursday " +
            "and the repaired payment settled on Friday morning."

    private const val JEB01_TAKEAWAY =
        "One bad payment can reject a whole batch. Split, repair, and resubmit rather than resending the batch unchanged."

    private fun explainFor(id: String, stage: Int): String =
        "Situation $id is a stage $stage case from the payments operations floor. " +
            "A payment of USD 12,500 was sent with a pacs.008 message and reached the intermediary " +
            "bank, where it stopped because a compliance check needed more information about the " +
            "purpose of the payment. The sending bank received a pacs.002 status report asking for " +
            "the missing information. The analyst gathered the purpose code and the supporting " +
            "documents, sent the information through the case management channel, and the payment " +
            "was released the next business day. The client was kept informed at each step, and " +
            "the case notes recorded what was sent and when, so the next analyst could follow " +
            "the same path without asking the client twice."

    val situations: List<Situation> = listOf(
        Situation(
            id = "ABH-01",
            title = "Returned payment: closed beneficiary account",
            stage = 1,
            stageName = "Payment mechanics",
            level = "foundation",
            explain = ABH01_EXPLAIN,
            takeaway = ABH01_TAKEAWAY,
            isFreeSample = true,
            isStageAuthoritative = true,
            visual = SituationVisual(
                type = "flow",
                flow = SituationFlow(
                    stepSeconds = null,
                    steps = listOf(
                        SituationFlowStep(kind = "step", title = "Payment sent", note = "pacs.008 for EUR 40,000"),
                        SituationFlowStep(kind = "decision", title = "Account still open?", note = "Beneficiary bank checks the account"),
                        SituationFlowStep(kind = "step", title = "Return received", note = "pacs.004 with reason AC04"),
                        SituationFlowStep(kind = "step", title = "Client confirms new account", note = "Call the client before resending"),
                        SituationFlowStep(kind = "step", title = "Payment resent", note = "Settles the same day")
                    )
                )
            )
        ),
        Situation(
            id = "JEB-C01",
            title = "Batch rejection: one bad payment stops payroll",
            stage = 2,
            stageName = "Processing decisions",
            level = "core",
            explain = JEB01_EXPLAIN,
            takeaway = JEB01_TAKEAWAY,
            isFreeSample = true,
            isStageAuthoritative = true,
            visual = null
        ),
        Situation(
            id = "ABH-02",
            title = "Missing confirmation after a SWIFT gpi payment",
            stage = 1,
            stageName = "Payment mechanics",
            level = "foundation",
            explain = explainFor("ABH-02", 1),
            takeaway = "Chase the missing confirmation through the tracker before telling the client the payment is lost.",
            isFreeSample = false,
            isStageAuthoritative = true,
            visual = null
        ),
        Situation(
            id = "INS-001",
            title = "Sanctions hold on a cross-border payment",
            stage = 3,
            stageName = "Operational exceptions",
            level = "advanced",
            explain = explainFor("INS-001", 3),
            takeaway = "A sanctions hold is not a rejection. Gather the evidence and follow the screening queue.",
            isFreeSample = false,
            isStageAuthoritative = true,
            visual = SituationVisual(
                type = "diagram",
                diagram = SituationDiagram(
                    centre = "Sanctions screening",
                    parts = listOf(
                        SituationDiagramPart(label = "Name match", note = "The beneficiary name matched a list entry"),
                        SituationDiagramPart(label = "Evidence pack", note = "Purpose, invoice, and relationship proof"),
                        SituationDiagramPart(label = "Release decision", note = "Screening team releases or escalates")
                    )
                )
            )
        ),
        Situation(
            id = "SCN-01",
            title = "Duplicate file uploads settle twice",
            stage = 4,
            stageName = "Cross-system consequences",
            level = "core",
            explain = explainFor("SCN-01", 4),
            takeaway = "Deduplicate on the file reference before the second upload reaches the ledger.",
            isFreeSample = false,
            isStageAuthoritative = true,
            visual = SituationVisual(
                type = "stacked",
                stacked = SituationStacked(
                    segments = listOf(
                        SituationStackedSegment(label = "First upload", note = "Settled", value = 50.0),
                        SituationStackedSegment(label = "Duplicate upload", note = "Reversed", value = 50.0)
                    ),
                    unit = "payments"
                )
            )
        ),
        Situation(
            id = "PAI-C001",
            title = "Liquidity shortfall at the cut-off",
            stage = 5,
            stageName = "Complex banking scenarios",
            level = "advanced",
            explain = explainFor("PAI-C001", 5),
            takeaway = "Queue low-priority payments and protect the time-critical ones when liquidity runs short.",
            isFreeSample = false,
            isStageAuthoritative = true,
            visual = null
        ),
        Situation(
            id = "RUN3-001",
            title = "Senior judgement: recall a payment after settlement",
            stage = 6,
            stageName = "Senior SME judgement",
            level = "advanced",
            explain = explainFor("RUN3-001", 6),
            takeaway = "A recall after settlement needs the beneficiary bank to agree. Start with the facts, not the demand.",
            isFreeSample = false,
            isStageAuthoritative = true,
            visual = null
        )
    )

    val fullItems: List<FullSituationItem> = situations.map { situation ->
        FullSituationItem(
            id = situation.id,
            title = situation.title,
            level = situation.level,
            explain = situation.explain,
            takeaway = situation.takeaway,
            visual = situation.visual,
            stage = JsonPrimitive(situation.stage),
            stageName = situation.stageName,
            domain = "Cross-Border Payments",
            messages = listOf("pacs.008", "pacs.002"),
            failureCategory = "Beneficiary account problem",
            resolution = "Confirm the details with the client and resend."
        )
    }

    val weeklyCases: List<Situation> = listOf(situations[1], situations[2])

    private fun lessonEntry(id: String, title: String, chapterName: String, part: Int, domain: String, body: String): SearchEntry =
        SearchEntry(
            id = id,
            title = title,
            url = "/lessons/$id.html",
            type = "lesson",
            text = "$title $chapterName Part $part: $domain $body"
        )

    val entries: List<SearchEntry> = listOf(
        lessonEntry(
            "ch1s1", "What a payment is", "Payment Foundations", 1, "Foundations",
            "A payment moves money from one account to another. This lesson explains the parties, the accounts, and the messages that carry the instruction."
        ),
        lessonEntry(
            "ch1s2", "Parties to a payment", "Payment Foundations", 1, "Foundations",
            "Every payment has a debtor, a creditor, and the banks that move the money between them. This lesson names each party and its job."
        ),
        lessonEntry(
            "ch1s3", "Accounts and ledgers", "Payment Foundations", 1, "Foundations",
            "Banks record payments in ledgers. This lesson shows how a debit on one account becomes a credit on another."
        ),
        lessonEntry(
            "ch2s1", "How clearing works", "Clearing and Settlement", 2, "Clearing and Settlement",
            "Clearing is how banks exchange payment instructions and work out who owes whom. This lesson walks through a clearing cycle."
        ),
        lessonEntry(
            "ch2s2", "Settlement and finality", "Clearing and Settlement", 2, "Clearing and Settlement",
            "Settlement is the final movement of funds. This lesson explains when a payment becomes final and cannot be undone."
        ),
        SearchEntry(
            id = "guide-pacs002",
            title = "Guide: pacs.002 Explained",
            url = "/guides/pacs002.html",
            type = "guide",
            text = "Guide: pacs.002 Explained Payment status reports tell the sender whether a payment was accepted or rejected, and why."
        ),
        SearchEntry(
            id = "guide-camt029",
            title = "Guide: camt.029 Explained",
            url = "/guides/camt029.html",
            type = "guide",
            text = "Guide: camt.029 Explained Resolution of investigation messages carry the answer to a payment investigation."
        ),
        SearchEntry(
            id = "lab-1",
            title = "Lab: trace a missing confirmation",
            url = "/labs/lab-1.html",
            type = "lab",
            text = "Lab: trace a missing confirmation Follow the evidence trail to find why a confirmation never arrived."
        ),
        SearchEntry(
            id = "lab-2",
            title = "Lab: repair a rejected batch",
            url = "/labs/lab-2.html",
            type = "lab",
            text = "Lab: repair a rejected batch Find the one bad payment in a rejected batch and repair it."
        ),
        SearchEntry(
            id = "test-foundations",
            title = "Test: Payment Foundations",
            url = "/tests/test-foundations.html",
            type = "test",
            text = "Test: Payment Foundations Ten questions on the foundations of payments, with a pass mark of 70 percent."
        ),
        SearchEntry(
            id = "test-iso20022",
            title = "Test: ISO 20022 Messages",
            url = "/tests/test-iso20022.html",
            type = "test",
            text = "Test: ISO 20022 Messages Ten questions on pacs.008, pacs.002, and camt messages."
        ),
        SearchEntry(
            id = "faq-1",
            title = "FAQ: What is a pacs.008?",
            url = "/faq/faq-1.html",
            type = "faq",
            text = "FAQ: What is a pacs.008? A pacs.008 is the ISO 20022 customer credit transfer message."
        ),
        SearchEntry(
            id = "faq-2",
            title = "FAQ: How long does a cross-border payment take?",
            url = "/faq/faq-2.html",
            type = "faq",
            text = "FAQ: How long does a cross-border payment take? Most cross-border payments settle within one business day."
        )
    ) + situations.map { situation ->
        SearchEntry(
            id = situation.id,
            title = situation.title,
            url = "/situations/${situation.id}.html",
            type = "situation",
            text = "${situation.title} ${situation.explain}"
        )
    }

    val searchIndex: SearchIndex = SearchIndex(
        counts = SearchCounts(
            lesson = entries.count { it.isLesson },
            guide = entries.count { it.isGuide },
            situation = entries.count { it.isSituation },
            lab = entries.count { it.isLab },
            test = entries.count { it.isTest },
            faq = entries.count { it.isFaq },
            total = entries.size
        ),
        entries = entries
    )

    /** A full lesson detail for any lesson id, with quiz questions and timed beats. */
    fun lessonDetail(id: String): LessonDetail {
        val entry = entries.firstOrNull { it.id == id }
        val title = entry?.title ?: "Lesson $id"
        return LessonDetail(
            id = id,
            title = title,
            chapterTitle = entry?.chapterName ?: "Payment Foundations",
            audioFileName = "$id.mp3",
            transcript = "$title. " +
                "This lesson explains the idea in plain words, then shows how it works at a real bank. " +
                "First, the core idea: a payment is an instruction to move money, and every instruction " +
                "needs a sender, a receiver, and a path between them. " +
                "Second, the working detail: the messages, the checks, and the cut-off times that decide " +
                "whether the payment settles today. " +
                "Third, the analyst view: what to check first when something goes wrong, and which " +
                "evidence proves what happened. Keep these three layers in mind as you work through " +
                "the examples in the rest of this lesson.",
            takeaways = listOf(
                "A payment is an instruction to move money between accounts.",
                "Messages, checks, and cut-off times decide when it settles.",
                "Check the evidence trail first when something goes wrong."
            ),
            doThisAtWork = listOf(
                "Trace one real payment through its messages.",
                "Note the cut-off time for your main payment rail."
            ),
            sources = listOf("SWIFT gpi rulebook", "ISO 20022 message definitions"),
            quiz = listOf(
                QuizQuestion(
                    id = "$id-q1",
                    question = "What is the first thing to check when a payment goes missing?",
                    options = listOf(
                        "The evidence trail of messages",
                        "The client relationship manager",
                        "The year-end report",
                        "The branch opening hours"
                    ),
                    correctOptionIndex = 0,
                    explanation = "The message trail shows where the payment stopped."
                ),
                QuizQuestion(
                    id = "$id-q2",
                    question = "Which message carries a customer credit transfer?",
                    options = listOf("pacs.002", "pacs.008", "camt.029", "pain.001"),
                    correctOptionIndex = 1,
                    explanation = "pacs.008 is the customer credit transfer message."
                )
            ),
            nextLessonId = "ch1s2",
            beats = listOf(
                LessonBeat(id = "$id-b1", startTime = 0.0, endTime = 90.0, title = "The core idea", note = "What the lesson teaches."),
                LessonBeat(id = "$id-b2", startTime = 90.0, endTime = 200.0, title = "How it works", note = "Messages, checks, and cut-offs."),
                LessonBeat(id = "$id-b3", startTime = 200.0, endTime = 300.0, title = "The analyst view", note = "What to check first.")
            ),
            diagramSvg = null,
            durationSec = 300.0,
            nextLessonTitle = "Parties to a payment",
            quizIntro = "Check what you learned in this lesson."
        )
    }

    /** Install every fake override so screens compose without network access. */
    fun install() {
        ContentRepository.testSearchIndexOverride = searchIndex
        ContentRepository.testSituationsOverride = situations
        ContentRepository.testWeeklyCasesOverride = weeklyCases
        ContentRepository.testFullItemsOverride = fullItems
        ContentRepository.testLessonDetailProvider = { id -> lessonDetail(id) }
    }

    /** The real shipped ch1s1 thumbnail, copied verbatim (see ThumbSvgTest). */
    val sampleSvg: String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180" role="img"><title>ch1s1</title><rect width="320" height="180" fill="#0D1C38"/><rect x="0" y="0" width="320" height="180" fill="#173F8C" opacity="0.35"/><circle cx="210" cy="60" r="18" fill="#93C5FD"/><circle cx="272" cy="110" r="18" fill="#FBBF24"/><path d="M224 72 L258 98" stroke="#FBBF24" stroke-width="5" stroke-linecap="round"/><path d="M258 98 l-14 -2 l6 13 z" fill="#FBBF24"/><circle cx="232" cy="146" r="8" fill="#93C5FD" opacity="0.7"/><rect x="18" y="118" width="46" height="6" rx="3" fill="#FBBF24"/><text x="18" y="102" font-family="Arial, Helvetica, sans-serif" font-size="34" font-weight="bold" fill="#FFFFFF">1.1</text><text x="18" y="152" font-family="Arial, Helvetica, sans-serif" font-size="17" fill="#E2E8F0">Lesson</text></svg>"""

    /** Agendas for every fake id, so thumbnail artwork renders under test. */
    val testAgendas: Agendas = Agendas(
        lessons = entries.filter { it.isLesson }.associate { entry ->
            entry.id to AgendaEntry(
                agenda = "Why take ${entry.title}.",
                thumbnail = "assets/thumbs/${entry.id}.svg"
            )
        },
        chapters = listOf("ch1", "ch2").associate { id ->
            id to AgendaEntry(
                agenda = "Why take this chapter.",
                thumbnail = "assets/thumbs/$id.svg"
            )
        },
        courses = (listOf("integrated") + (1..10).map { "part-$it" }).associate { id ->
            id to AgendaEntry(
                agenda = "Why take this course. You will be able to: follow a payment end to end.",
                thumbnail = "assets/thumbs/course-$id.svg"
            )
        },
        cases = situations.associate { situation ->
            situation.id to AgendaEntry(
                agenda = "Why work this case.",
                thumbnail = "assets/thumbs/case-${situation.id}.svg"
            )
        }
    )

    /**
     * Install agendas plus a thumbnail fetcher that returns the
     * real shipped SVG for every path, so tests prove artwork
     * renders instead of only the fallback tile.
     */
    fun installAgendas() {
        AgendaStore.setTestAgendas(testAgendas)
        ThumbnailStore.testFetcher = { sampleSvg }
    }

    /** Clear the agenda and thumbnail test state. */
    fun resetStores() {
        AgendaStore.resetForTest()
        ThumbnailStore.resetForTest()
    }

    /** 616 situations shaped like production: 2 free samples + 614 staged cases. */
    fun fullSizeSituations(): List<Situation> {
        val list = mutableListOf<Situation>()
        list.add(situations[0])
        list.add(situations[1])
        for (i in 3..616) {
            val stage = (i % 6) + 1
            list.add(
                Situation(
                    id = "CASE-%03d".format(i),
                    title = "Payment case number $i about a delayed settlement",
                    stage = stage,
                    stageName = "Stage $stage",
                    level = listOf("foundation", "core", "advanced")[i % 3],
                    explain = "A payment of USD 1,000 was delayed at stage $stage. The analyst traced the messages, found the hold, and released the payment.",
                    takeaway = "Trace the messages first for case $i.",
                    isFreeSample = false,
                    isStageAuthoritative = true,
                    visual = null
                )
            )
        }
        return list
    }

    /** 881 lesson entries shaped like production, plus the small fixed entries. */
    fun fullSizeEntries(): List<SearchEntry> {
        val lessons = mutableListOf<SearchEntry>()
        var made = 0
        for (chapter in 1..100) {
            for (section in 1..9) {
                if (made >= 881) break
                val id = "ch${chapter}s$section"
                lessons.add(
                    SearchEntry(
                        id = id,
                        title = "Payment lesson $id: how money moves",
                        url = "/lessons/$id.html",
                        type = "lesson",
                        text = "Payment lesson $id how money moves through banks, messages, clearing and settlement."
                    )
                )
                made++
            }
        }
        return lessons + entries.filter { !it.isLesson } + fullSizeSituations().map { situation ->
            SearchEntry(
                id = situation.id,
                title = situation.title,
                url = "/situations/${situation.id}.html",
                type = "situation",
                text = "${situation.title} ${situation.explain}"
            )
        }
    }

    /** Install the full-size shapes (881 lessons, 616 cases) for pagination tests. */
    fun installFullSize() {
        val fullEntries = fullSizeEntries()
        ContentRepository.testSearchIndexOverride = SearchIndex(
            counts = SearchCounts(
                lesson = fullEntries.count { it.isLesson },
                guide = fullEntries.count { it.isGuide },
                situation = fullEntries.count { it.isSituation },
                lab = fullEntries.count { it.isLab },
                test = fullEntries.count { it.isTest },
                faq = fullEntries.count { it.isFaq },
                total = fullEntries.size
            ),
            entries = fullEntries
        )
        ContentRepository.testSituationsOverride = fullSizeSituations()
        ContentRepository.testWeeklyCasesOverride = weeklyCases
        ContentRepository.testFullItemsOverride = fullItems
        ContentRepository.testLessonDetailProvider = { id -> lessonDetail(id) }
    }

    /** Install empty content, so the crash sweep can prove screens survive no data. */
    fun installEmpty() {
        ContentRepository.testSearchIndexOverride = SearchIndex(
            counts = SearchCounts(0, 0, 0, 0, 0, 0, 0),
            entries = emptyList()
        )
        ContentRepository.testSituationsOverride = emptyList()
        ContentRepository.testWeeklyCasesOverride = emptyList()
        ContentRepository.testFullItemsOverride = emptyList()
        ContentRepository.testLessonDetailProvider = { id -> lessonDetail(id) }
    }
}
