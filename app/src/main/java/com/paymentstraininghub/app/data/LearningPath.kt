// LearningPath.kt
// Guided learning paths, ported from LearningPath.swift: six
// paths, each a set of required chapters, practised situations
// and a capstone. Progress comes from real completed-lesson
// data passed in by the caller; nothing here is enforced, and
// any lesson can be opened at any time.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.SearchEntry

enum class CompetencyDimension(val label: String) {
    ARCHITECTURE("Payment architecture"),
    LIFECYCLE("Transaction lifecycle"),
    BUSINESS_ANALYSIS("Business analysis"),
    EXCEPTION_HANDLING("Exception handling"),
    RECONCILIATION("Reconciliation and settlement");

    /** Chapters whose lesson quizzes feed this dimension's assessment. */
    val chapterNumbers: List<Int>
        get() = when (this) {
            ARCHITECTURE -> listOf(25, 30, 35, 36, 48, 79)
            LIFECYCLE -> listOf(4, 17, 18, 72)
            BUSINESS_ANALYSIS -> listOf(28, 37, 45, 84)
            EXCEPTION_HANDLING -> listOf(16, 41, 58, 73)
            RECONCILIATION -> listOf(40, 44, 71, 88, 89)
        }
}

data class LearningPath(
    val id: String,
    val title: String,
    val description: String,
    val level: String,
    val systemImage: String,
    val objectives: List<String>,
    /** Chapter numbers whose lessons are all required for this path. */
    val chapterNumbers: List<Int>,
    /** Real situation ids practised along the path. */
    val situationIds: List<String>,
    /** The capstone: one real situation, worked end to end. */
    val capstoneSituationId: String,
    val assessmentDimension: CompetencyDimension,
    /** The certificate title earned on completion, null when this path has no certificate. */
    val certificateTitle: String?,
    /** Honest plain-language entry expectations. A level description only: nothing is enforced. */
    val entryExpectations: String,
    /** What completing this path really requires. Never claims an exam or assessment is required. */
    val completionCriteria: String
) {
    /** Required lessons, in catalogue order: every lesson of every chapter in this path. */
    fun lessons(entries: List<SearchEntry>): List<SearchEntry> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SearchEntry>()
        for (number in chapterNumbers) {
            for (lesson in entries) {
                if (!lesson.isLesson) continue
                if (ContentRepository.chapterNumberFromId(lesson.id) != number) continue
                if (seen.add(lesson.id)) result.add(lesson)
            }
        }
        return result
    }

    /** Completed required lessons over total required lessons. */
    fun progress(entries: List<SearchEntry>, completedLessonIds: Set<String>): Pair<Int, Int> {
        val required = lessons(entries)
        val completed = required.count { it.id in completedLessonIds }
        return completed to required.size
    }

    /** True only when every required lesson is complete. */
    fun isComplete(entries: List<SearchEntry>, completedLessonIds: Set<String>): Boolean {
        val (completed, total) = progress(entries, completedLessonIds)
        return total > 0 && completed == total
    }

    /** The first required lesson not yet complete, in path order. */
    fun firstIncompleteLesson(
        entries: List<SearchEntry>,
        completedLessonIds: Set<String>
    ): SearchEntry? {
        return lessons(entries).firstOrNull { it.id !in completedLessonIds }
    }

    companion object {
        /**
         * Whole weeks to finish a path at a weekly lesson target,
         * rounded up. Null when either input is not positive. No
         * minutes are invented: the count is required lessons only.
         */
        fun estimatedWeeks(totalLessons: Int, weeklyTarget: Int): Int? {
            if (totalLessons <= 0 || weeklyTarget <= 0) return null
            return (totalLessons + weeklyTarget - 1) / weeklyTarget
        }
    }
}

object LearningPaths {
    val all: List<LearningPath> = listOf(
        LearningPath(
            id = "fundamentals",
            title = "Payments Fundamentals",
            description = "What a payment is, the instruments used to make one, the parties involved, and the lifecycle from instruction to settlement.",
            level = "Foundation",
            systemImage = "book.fill",
            objectives = listOf(
            "Explain what a payment is and how it differs from a transfer and from settlement.",
            "Name the payment instruments and the parties in a payment.",
            "Trace a payment end to end through its lifecycle.",
            "Read a payment situation and spot where the lifecycle broke down."
        ),
            chapterNumbers = listOf(1, 2, 3, 4),
            situationIds = listOf(
            "ABH-01",
            "ABH-02",
            "INS-001",
            "SCN-15"
        ),
            capstoneSituationId = "ABH-10",
            assessmentDimension = CompetencyDimension.ARCHITECTURE,
            certificateTitle = "Payments Fundamentals",
            entryExpectations = "No prior knowledge of payments is assumed. This foundation journey starts with what a payment is and builds from there, so it is the right starting point for a complete beginner.",
            completionCriteria = "Complete every required lesson in this journey to earn the Payments Fundamentals certificate. No exam or assessment is required; the assessment checkpoint is optional practice."
        ),
        LearningPath(
            id = "business-analyst",
            title = "Payments Business Analyst",
            description = "The BA craft in payments: workshops, requirements, data models, product thinking and a capstone project drawn from real bank practice.",
            level = "Intermediate",
            systemImage = "doc.text.fill",
            objectives = listOf(
            "Run a payments BA workshop and capture requirements that hold up.",
            "Model payment data and map it across systems.",
            "Write product requirements a delivery team can build from.",
            "Present a payments case the way a senior BA would."
        ),
            chapterNumbers = listOf(28, 37, 45, 84, 29),
            situationIds = listOf(
            "ABH-37",
            "INS-084",
            "INS-085",
            "INS-096"
        ),
            capstoneSituationId = "ABH-18",
            assessmentDimension = CompetencyDimension.BUSINESS_ANALYSIS,
            certificateTitle = "Payments Business Analyst Foundations",
            entryExpectations = "An intermediate journey. It helps to already be comfortable with payments fundamentals, such as what a payment is and how one moves. No prior business analysis experience is assumed. Nothing here is enforced and any lesson can be opened at any time.",
            completionCriteria = "Complete every required lesson in this journey to earn the Payments Business Analyst Foundations certificate. No exam or assessment is required; the assessment checkpoint is optional practice."
        ),
        LearningPath(
            id = "upi-specialist",
            title = "UPI Specialist",
            description = "India's instant payments in depth: UPI, IMPS, NEFT and RTGS, the Indian payments landscape, and real-time payments architecture.",
            level = "Intermediate",
            systemImage = "bolt.fill",
            objectives = listOf(
            "Explain how a UPI payment moves from payer app to payee bank.",
            "Compare UPI, IMPS, NEFT and RTGS and pick the right rail.",
            "Describe the Indian payments landscape: NACH, BBPS and FEMA.",
            "Diagnose a failed or delayed instant payment."
        ),
            chapterNumbers = listOf(9, 33, 46),
            situationIds = listOf(
            "INS-105",
            "INS-166",
            "INS-067",
            "INS-033"
        ),
            capstoneSituationId = "SCN-36",
            assessmentDimension = CompetencyDimension.LIFECYCLE,
            certificateTitle = "UPI Professional Foundations",
            entryExpectations = "An intermediate journey. It helps to already be comfortable with payments fundamentals. No prior knowledge of Indian payment systems is assumed. Nothing here is enforced and any lesson can be opened at any time.",
            completionCriteria = "Complete every required lesson in this journey to earn the UPI Professional Foundations certificate. No exam or assessment is required; the assessment checkpoint is optional practice."
        ),
        LearningPath(
            id = "cards-digital",
            title = "Cards and Digital Banking",
            description = "Card payments end to end: authorisation, clearing and settlement, networks, wallets, POS, 3D Secure, tokenization and chargebacks.",
            level = "Intermediate",
            systemImage = "creditcard.fill",
            objectives = listOf(
            "Trace a card payment from authorisation through clearing to settlement.",
            "Explain how Visa and Mastercard networks move a transaction.",
            "Describe 3D Secure, tokenization and how wallets and POS work.",
            "Handle a chargeback and explain who bears the loss."
        ),
            chapterNumbers = listOf(21, 22, 31, 32, 63),
            situationIds = listOf(
            "ABH-04",
            "INS-001",
            "INS-005",
            "RUN5-001"
        ),
            capstoneSituationId = "RUN5-003",
            assessmentDimension = CompetencyDimension.LIFECYCLE,
            certificateTitle = "Card Payments Fundamentals",
            entryExpectations = "An intermediate journey. It helps to already be comfortable with payments fundamentals. No prior knowledge of card payments is assumed. Nothing here is enforced and any lesson can be opened at any time.",
            completionCriteria = "Complete every required lesson in this journey to earn the Card Payments Fundamentals certificate. No exam or assessment is required; the assessment checkpoint is optional practice."
        ),
        LearningPath(
            id = "operations-reconciliation",
            title = "Payment Operations and Reconciliation",
            description = "The operations floor: reconciliation, exceptions and investigations, rejects and returns, and the architecture that keeps payments resilient.",
            level = "Advanced",
            systemImage = "gearshape.fill",
            objectives = listOf(
            "Reconcile a payment book and explain every break.",
            "Work an exception from first alert to resolution.",
            "Handle rejects and returns under CBPR+.",
            "Describe the controls that keep payment operations resilient."
        ),
            chapterNumbers = listOf(40, 41, 44, 58, 68),
            situationIds = listOf(
            "INS-161",
            "INS-109",
            "INS-162",
            "PAI-C001"
        ),
            capstoneSituationId = "SCN-19",
            assessmentDimension = CompetencyDimension.RECONCILIATION,
            certificateTitle = "Payment Operations and Reconciliation",
            entryExpectations = "An advanced journey. It helps to already be comfortable with payments fundamentals and with how payments move and fail. Nothing here is enforced and any lesson can be opened at any time.",
            completionCriteria = "Complete every required lesson in this journey to earn the Payment Operations and Reconciliation certificate. No exam or assessment is required; the assessment checkpoint is optional practice."
        ),
        LearningPath(
            id = "interview-preparation",
            title = "Payments Interview Preparation",
            description = "Interview-ready payments: how large banks run payments, cross-border investigations, claims and case management, and a capstone case to present.",
            level = "Advanced",
            systemImage = "person.fill.questionmark",
            objectives = listOf(
            "Explain how large banks organise payments operations.",
            "Work a cross-border investigation from first alert to close.",
            "Answer payments interview questions with real cases, not definitions.",
            "Walk through a capstone project end to end."
        ),
            chapterNumbers = listOf(37, 75, 73, 29),
            situationIds = listOf(
            "INS-096",
            "INS-085",
            "INS-084",
            "ABH-36"
        ),
            capstoneSituationId = "ABH-37",
            assessmentDimension = CompetencyDimension.EXCEPTION_HANDLING,
            certificateTitle = null,
            entryExpectations = "An advanced journey for learners preparing to talk about payments work in interviews. It helps to have covered payments fundamentals first. Nothing here is enforced and any lesson can be opened at any time.",
            completionCriteria = "Complete every required lesson in this journey. This journey has no certificate, and no exam or assessment is required; the assessment checkpoint is optional practice."
        ),
    )
}
