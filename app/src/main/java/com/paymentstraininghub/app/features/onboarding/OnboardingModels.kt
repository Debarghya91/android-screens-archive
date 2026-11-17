// OnboardingModels.kt
// Optional first-time onboarding models, ported from
// OnboardingProfile.swift: the learner's role, knowledge level,
// objective and pace, the completed profile, and the route
// recommendation. These models are currently unused: the live
// introduction is a fixed four-page flow shown on first launch,
// whether or not a session exists, and skipping it is recorded
// so it is not shown again on this install.
//
// The knowledge level here is self described payments knowledge.
// It is NOT years of experience, and onboarding never writes any
// experience setting.

package com.paymentstraininghub.app.features.onboarding

import com.paymentstraininghub.app.data.LearningPath
import com.paymentstraininghub.app.data.LearningPaths

/** What the learner does, in their own terms. Raw values are stored in the persisted profile, so never rename one. */
enum class LearnerRole(val rawValue: String, val displayTitle: String, val displayDescription: String) {
    NEW_TO_PAYMENTS(
        "new-to-payments",
        "New to payments",
        "You are starting out and want the foundations first."
    ),
    BUSINESS_ANALYST(
        "business-analyst",
        "Business analyst",
        "You gather requirements and shape how payments work."
    ),
    TECHNICAL_ANALYST(
        "technical-analyst",
        "Technical analyst",
        "You work with payment systems, data and integrations."
    ),
    PRODUCT_OWNER(
        "product-owner",
        "Product owner",
        "You own a payments product and decide what gets built."
    ),
    TESTER(
        "tester",
        "Tester",
        "You test payment flows and check they behave as expected."
    ),
    CROSS_BORDER_SPECIALIST(
        "cross-border-specialist",
        "Cross-border specialist",
        "You work on international payments and investigations."
    ),
    SME(
        "sme",
        "Subject matter expert",
        "You already know payments deeply and want depth in new areas."
    ),
    SOLUTION_ARCHITECT(
        "solution-architect",
        "Solution architect",
        "You design payment systems and their architecture."
    );

    companion object {
        fun fromRawValue(raw: String): LearnerRole? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/** Self described payments knowledge. Never written into any experience setting. */
enum class LearnerKnowledgeLevel(val rawValue: String, val displayTitle: String) {
    BEGINNER("beginner", "Beginner"),
    INTERMEDIATE("intermediate", "Intermediate"),
    ADVANCED("advanced", "Advanced");

    companion object {
        fun fromRawValue(raw: String): LearnerKnowledgeLevel? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/** The learner's primary reason for learning. Raw values are stored, so never rename one. */
enum class LearningObjective(val rawValue: String, val displayTitle: String) {
    BUILD_FOUNDATIONS("build-foundations", "Build foundations"),
    PREPARE_FOR_INTERVIEWS("prepare-for-interviews", "Prepare for interviews"),
    DO_THE_JOB_BETTER("do-the-job-better", "Do the job better"),
    SPECIALISE("specialise", "Specialise in one area"),
    LEAD_AND_DESIGN("lead-and-design", "Lead and design payments work");

    companion object {
        fun fromRawValue(raw: String): LearningObjective? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How much the learner plans to study each week. The weekly
 * lesson target is the intensity's target: onboarding writes it
 * into the shared weekly goal as well, so Home and the drawer
 * card agree.
 */
enum class LearningIntensity(
    val rawValue: String,
    val displayTitle: String,
    val weeklyLessonTarget: Int
) {
    LIGHT("light", "Light", 2),
    STEADY("steady", "Steady", 5),
    INTENSIVE("intensive", "Intensive", 7);

    companion object {
        fun fromRawValue(raw: String): LearningIntensity? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The completed onboarding profile. diagnosticScore is a fraction
 * from 0 to 1, null when the diagnostic was skipped.
 * diagnosticTakenAtMillis is set only when a diagnostic score was
 * recorded. wasSkipped is false for a completed profile; a skipped
 * onboarding stores no profile at all.
 */
data class OnboardingProfile(
    val role: LearnerRole,
    val knowledgeLevel: LearnerKnowledgeLevel,
    val objective: LearningObjective,
    val intensity: LearningIntensity,
    val weeklyTarget: Int,
    val diagnosticScore: Double?,
    val diagnosticTakenAtMillis: Long?,
    val completedAtMillis: Long,
    val wasSkipped: Boolean
)

object OnboardingRecommendation {
    fun pathFor(
        role: LearnerRole,
        objective: LearningObjective,
        knowledgeLevel: LearnerKnowledgeLevel
    ): LearningPath {
        val pathId: String
        if (knowledgeLevel == LearnerKnowledgeLevel.BEGINNER) {
            pathId = "fundamentals"
        } else {
            pathId = when (objective) {
                LearningObjective.BUILD_FOUNDATIONS -> "fundamentals"
                LearningObjective.PREPARE_FOR_INTERVIEWS -> "interview-preparation"
                LearningObjective.DO_THE_JOB_BETTER,
                LearningObjective.SPECIALISE,
                LearningObjective.LEAD_AND_DESIGN -> when (role) {
                    LearnerRole.BUSINESS_ANALYST,
                    LearnerRole.PRODUCT_OWNER -> "business-analyst"
                    LearnerRole.NEW_TO_PAYMENTS -> "fundamentals"
                    LearnerRole.TECHNICAL_ANALYST,
                    LearnerRole.TESTER,
                    LearnerRole.CROSS_BORDER_SPECIALIST,
                    LearnerRole.SME,
                    LearnerRole.SOLUTION_ARCHITECT -> "operations-reconciliation"
                }
            }
        }
        return LearningPaths.all.firstOrNull { it.id == pathId }
            ?: LearningPaths.all.first()
    }
}
