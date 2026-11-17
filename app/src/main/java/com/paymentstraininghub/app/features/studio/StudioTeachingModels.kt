// StudioTeachingModels.kt
// Teaching-lesson and career-guide models for the Studio rework,
// ported from StudioExercises.swift (StudioLesson, StudioLessonKind,
// StudioLevel, StudioKnowledgeArea, StudioCareerGuide). The
// content is the converted iOS data, bundled as JSON assets and
// parsed here; kind, level and knowledge area stay raw strings
// in the serialized form so one unexpected value can never empty
// the whole Studio, with typed computed accessors on top.

package com.paymentstraininghub.app.features.studio

import kotlinx.serialization.Serializable

@Serializable
data class StudioOutputSection(
    val title: String = "",
    val guidance: String = ""
)

@Serializable
data class StudioPracticeScenario(
    val id: String = "",
    val title: String = "",
    val takeaway: String = "",
    val story: List<String> = emptyList()
)

@Serializable
data class StudioPracticeQuestion(
    val id: String = "",
    val question: String = "",
    val answer: List<String> = emptyList()
)

@Serializable
data class StudioLesson(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val kind: String = "",
    val level: String = "",
    val knowledgeArea: String? = null,
    val babokReference: String? = null,
    val explanation: List<String> = emptyList(),
    val outputName: String = "",
    val outputStructure: List<StudioOutputSection> = emptyList(),
    val howToWrite: List<String> = emptyList(),
    val workedExampleTitle: String = "",
    val workedExample: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val practiceScenarios: List<StudioPracticeScenario> = emptyList(),
    val practiceQuestions: List<StudioPracticeQuestion> = emptyList(),
    val linkedExerciseIDs: List<String> = emptyList(),
    val suggestedMinimumWords: Int = 0,
    val suggestedMaximumWords: Int = 0
) {
    val kindEnum: StudioLessonKind get() = StudioLessonKind.fromRaw(kind)
    val levelEnum: StudioLevel get() = StudioLevel.fromRaw(level)
    val knowledgeAreaEnum: StudioKnowledgeArea? get() =
        knowledgeArea?.let { StudioKnowledgeArea.fromRaw(it) }

    val wordCountGuidance: String
        get() = "Suggested $suggestedMinimumWords to $suggestedMaximumWords words."

    val hasPractice: Boolean
        get() = practiceScenarios.isNotEmpty() ||
            practiceQuestions.isNotEmpty() ||
            linkedExerciseIDs.isNotEmpty()
}

enum class StudioLessonKind(
    val rawValue: String,
    val displayName: String,
    val sortOrder: Int
) {
    CONCEPT("concept", "Concept", 0),
    TASK("task", "Task", 1),
    TECHNIQUE("technique", "Technique", 2),
    COMPETENCY("competency", "Competency", 3),
    PERSPECTIVE("perspective", "Perspective", 4),
    ORIENTATION("orientation", "Orientation", 5);

    companion object {
        fun fromRaw(raw: String): StudioLessonKind =
            entries.firstOrNull { it.rawValue == raw } ?: CONCEPT
    }
}

enum class StudioLevel(
    val rawValue: String,
    val value: Int,
    val displayName: String,
    val shortName: String,
    val blurb: String
) {
    FOUNDATION(
        "foundation",
        1,
        "Level 1: Foundations",
        "Level 1",
        "Start here. The key concepts behind all analysis, then planning and elicitation, the core techniques for gathering and recording information, working with people, and the agile way of working."
    ),
    PRACTITIONER(
        "practitioner",
        2,
        "Level 2: Practitioner",
        "Level 2",
        "The working craft. Managing requirements through their life, specifying and modelling them, the modelling and decision techniques, and the technology and process perspectives."
    ),
    ADVANCED(
        "advanced",
        3,
        "Level 3: Advanced",
        "Level 3",
        "Judgement and strategy. Strategy analysis, solution evaluation, the analytical and commercial techniques, professional judgement, and the enterprise perspectives."
    );

    companion object {
        fun fromRaw(raw: String): StudioLevel =
            entries.firstOrNull { it.rawValue == raw } ?: FOUNDATION
    }
}

enum class StudioKnowledgeArea(val rawValue: String, val displayName: String) {
    PLANNING("planning", "Business Analysis Planning and Monitoring"),
    ELICITATION("elicitation", "Elicitation and Collaboration"),
    LIFECYCLE("lifecycle", "Requirements Life Cycle Management"),
    STRATEGY("strategy", "Strategy Analysis"),
    ANALYSIS_DESIGN("analysisDesign", "Requirements Analysis and Design Definition"),
    SOLUTION_EVALUATION("solutionEvaluation", "Solution Evaluation");

    val introduction: List<String>
        get() = when (this) {
            PLANNING -> listOf(
                "This section teaches how to plan business analysis work, engage stakeholders, govern decisions, manage analysis information, and improve how analysis is performed over time.",
                "Planning is the work that makes the rest of the analysis possible. It is revisited whenever scope, risk, or timing changes, because a plan written once and never revised is a record of what was believed at the start, not a guide to the work ahead."
            )
            ELICITATION -> listOf(
                "This section teaches how to prepare for, run, and confirm elicitation, and how to share business analysis information and work well with stakeholders.",
                "Elicitation is the drawing out of information from stakeholders and other sources, and collaboration is the work that keeps stakeholders engaged while it happens. The two belong together: information gathered without collaboration is rarely trusted, and collaboration without information is only conversation."
            )
            LIFECYCLE -> listOf(
                "This section teaches how to trace, maintain, prioritize, change and approve requirements across their life, so the delivered work stays linked to real business needs.",
                "Requirements live longer than the meetings that created them. This area keeps them accurate, ordered, and linked, from the first draft to the last change, so the delivered work stays connected to the needs it serves."
            )
            STRATEGY -> listOf(
                "This section teaches how a business analyst understands the present position, shapes a realistic future position, weighs risks, and chooses a practical way to get from one to the other.",
                "Strategy analysis looks before it leaps. It builds an honest picture of the present, shapes a realistic picture of the future, weighs the risks, and chooses a practical route between the two, before any solution is designed in detail."
            )
            ANALYSIS_DESIGN -> listOf(
                "This section teaches how to specify and model requirements, check them, confirm they meet the need, structure them, compare design options, and recommend the solution with the strongest value.",
                "This area turns needs into requirements that a delivery team can build and test, checks those requirements, confirms they serve the need, organises them, and compares the design options that could meet them. It is where analysis becomes specific enough to build from."
            )
            SOLUTION_EVALUATION -> listOf(
                "This section teaches how to check whether a live solution delivers value, find its limits, and recommend the next best action.",
                "A solution that is live is not necessarily a solution that delivers value. This area checks whether the value arrived, finds the limits of the solution and of the organisation around it, and recommends the next best action on evidence."
            )
        }

    companion object {
        fun fromRaw(raw: String): StudioKnowledgeArea? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
data class StudioGuideStep(
    val title: String = "",
    val action: String = "",
    val timeframe: String = ""
)

@Serializable
data class StudioGuideSection(
    val heading: String = "",
    val paragraphs: List<String> = emptyList(),
    val steps: List<StudioGuideStep> = emptyList(),
    val keyPoints: List<String> = emptyList()
)

@Serializable
data class StudioGuideCertification(
    val name: String = "",
    val body: String = "",
    val note: String = ""
)

@Serializable
data class StudioCareerGuide(
    val id: String = "",
    val title: String = "",
    val level: String = "",
    val description: List<String> = emptyList(),
    val audience: String = "",
    val timeframe: String = "",
    val sections: List<StudioGuideSection> = emptyList(),
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val certifications: List<StudioGuideCertification> = emptyList(),
    val portfolio: List<String> = emptyList(),
    val mistakes: List<String> = emptyList()
) {
    val levelEnum: StudioLevel get() = StudioLevel.fromRaw(level)

    companion object {
        const val CERTIFICATION_NOTE = "Career guidance written originally for Payments Training Hub. Certification names are used to identify the certifications only. This material is not affiliated with, sponsored, or endorsed by IIBA, Scrum.org, or PMI."
    }
}

/**
 * The Studio disclaimer. Adapted from the iOS text, with the
 * authorship line reading "original Payments Training Hub
 * authorship", because user-facing Android text must never
 * name the old codename.
 */
object StudioDisclaimer {
    const val TEXT = "This Studio teaches business analysis using the structure and names of the BABOK Guide v3 knowledge areas, tasks, techniques, competencies, and perspectives. All explanations, examples, and practice material are original Payments Training Hub authorship. This material is not affiliated with, sponsored, or endorsed by IIBA. BABOK is a registered trademark of the International Institute of Business Analysis."
}
