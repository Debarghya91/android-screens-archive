// Routes.kt
// Payments Training Hub Android - single source of truth for navigation routes.
// Route strings mirror the iOS tab order: Home, Learn, Search,
// Payment Cases, My Progress. No em dashes in any user-facing text.
//
// MERGED by the v2 coordinator: pristine routes plus the email
// signup and password reset routes (auth port), notifications
// (notifications port), and the investigation destinations
// (investigation port).

package com.paymentstraininghub.app.ui.navigation

object Routes {
    const val HOME = "home"
    const val LEARN = "learn"
    const val SEARCH = "search"
    const val CASES = "cases"
    const val PROGRESS = "progress"

    /**
     * Home sections, for anchor navigation into the merged Home.
     * The separate Learn page is gone: Learn links land on Home
     * scrolled to the matching section. Section keys are stable
     * strings carried in the `section` query argument of the
     * home route ("home?section=courses"). Unknown keys land at
     * the top of Home.
     */
    const val HOME_SECTION_CONTINUE = "continue"
    const val HOME_SECTION_STATUS = "status"
    const val HOME_SECTION_JOURNEYS = "journeys"
    const val HOME_SECTION_COURSES = "courses"
    const val HOME_SECTION_CATALOGUE = "catalogue"
    const val HOME_SECTION_CAPABILITIES = "capabilities"

    /** Home opened scrolled to one section. */
    fun homeSection(section: String): String = "home?section=$section"

    const val LESSON = "lesson/{lessonId}"
    fun lesson(id: String): String = "lesson/$id"

    const val SITUATION = "situation/{situationId}"
    fun situation(id: String): String = "situation/$id"

    /** The weekly-case detail: the same Situation detail reached
     * from the Weekly case screen, marked with the weekly free
     * exemption. Mirrors the iOS WeeklyCaseDetailHost, which
     * presents SituationDetailView with isWeeklyFreeCase. */
    const val SITUATION_WEEKLY = "situation/{situationId}/weekly"
    fun situationWeekly(id: String): String = "situation/$id/weekly"

    const val CHECKOUT = "checkout"
    const val CHECKOUT_PRODUCT = "checkout/{productId}"
    fun checkout(productId: String): String = "checkout/$productId"

    const val PROFILE = "profile"
    const val NOTIFICATIONS = "notifications"
    const val AUTH = "auth"
    const val EMAIL_SIGNUP = "signup"
    const val FORGOT_PASSWORD = "forgot-password"
    const val RESET_PASSWORD = "reset-password"
    const val SIMULATOR = "simulator"
    const val ISO = "iso"

    /** The ISO explorer opened directly on one message code, as
     * the investigation message evidence links to it. An unknown
     * code shows the explorer list, as on iOS. */
    const val ISO_MESSAGE = "iso/{messageId}"
    fun isoMessage(messageId: String): String = "iso/$messageId"

    const val LAB = "lab"
    const val STUDIO = "studio"
    const val WEEKLY_CASE = "weekly-case"
    const val CHALLENGE = "challenge"
    const val DAILY_CHALLENGE = "daily-challenge"
    const val HABIT = "habit"
    const val PRIVACY = "privacy"
    const val TERMS = "terms"

    /** Practice Mode: decide what to do next for real situations. */
    const val PRACTICE = "practice"

    /** Practice Mode opened on one situation, from its detail screen. */
    const val PRACTICE_SITUATION = "practice/{situationId}"
    fun practiceSituation(situationId: String): String = "practice/$situationId"

    // Learn destinations: guided paths, sessions, chapters, the
    // guides/labs/tests library, entry reading view, interview
    // bank, and the assessment entry points. Mirrors the iOS
    // CoursesView navigation destinations.
    const val PATH = "path/{pathId}"
    fun path(id: String): String = "path/$id"

    const val SESSION = "session/{sessionId}"
    fun session(id: Int): String = "session/$id"

    const val CHAPTER = "chapter/{chapterNumber}"
    fun chapter(number: Int): String = "chapter/$number"

    const val GUIDES = "guides"
    const val LABS = "labs"
    const val TESTS = "tests"

    const val TEST_QUIZ = "test-quiz/{entryId}"
    fun testQuiz(entryId: String): String = "test-quiz/$entryId"

    const val ENTRY = "entry/{entryId}"
    fun entry(entryId: String): String = "entry/$entryId"

    const val INTERVIEW = "interview"
    const val INTERVIEW_CATEGORY = "interview/{categoryId}"
    fun interviewCategory(categoryId: String): String = "interview/$categoryId"

    const val ASSESSMENT_PATH = "assessment-path/{pathId}"
    fun assessmentPath(pathId: String): String = "assessment-path/$pathId"

    const val ASSESSMENT_DIMENSION = "assessment-dimension/{dimension}"
    fun assessmentDimension(dimension: String): String = "assessment-dimension/$dimension"

    const val CERTIFICATES = "certificates"

    const val FINAL_ASSESSMENT = "final-assessment/{productId}"
    fun finalAssessment(productId: String): String = "final-assessment/$productId"

    const val COURSE_CERTIFICATE = "course-certificate/{productId}"
    fun courseCertificate(productId: String): String = "course-certificate/$productId"

    const val COURSE_CERTIFICATE_SAMPLE = "course-certificate-sample"

    const val PATH_CERTIFICATE = "path-certificate/{pathId}"
    fun pathCertificate(pathId: String): String = "path-certificate/$pathId"

    const val REVISION = "revision"

    /** Appearance settings: dark mode and larger text. */
    const val APPEARANCE = "appearance"

    /** Help and Support: FAQs and support contact. */
    const val HELP = "help"

    /** About Payments Training Hub, the operator, the curriculum. */
    const val ABOUT = "about"

    /** Diagnostics: crash reports, device info, soft security notices. Reached from Help and Support. */
    const val DIAGNOSTICS = "diagnostics"

    // Investigation Experience destinations. Detail destinations
    // only, reached from the existing entry points (Situation
    // detail, Lab list, Weekly case). No tab is added and no deep
    // link is added: these bases are deliberately NOT in
    // knownBases, matching iOS, which adds no deep link for the
    // experience.
    const val INVESTIGATION_SITUATION = "investigation-situation/{situationId}"
    fun investigationSituation(id: String): String = "investigation-situation/$id"

    const val INVESTIGATION_SITUATION_WEEKLY = "investigation-situation/{situationId}/weekly"
    fun investigationSituationWeekly(id: String): String = "investigation-situation/$id/weekly"

    const val INVESTIGATION_LAB = "investigation-lab/{labCaseId}"
    fun investigationLab(id: String): String = "investigation-lab/$id"

    const val INVESTIGATION_WEEKLY = "investigation-weekly"

    /** Base segments that may appear as the first part of a deep link route. */
    internal val knownBases: Set<String> = setOf(
        HOME, LEARN, SEARCH, CASES, PROGRESS,
        "lesson", "situation", "checkout",
        PROFILE, NOTIFICATIONS, AUTH, EMAIL_SIGNUP, FORGOT_PASSWORD, RESET_PASSWORD,
        SIMULATOR, ISO, LAB, STUDIO,
        WEEKLY_CASE, CHALLENGE, DAILY_CHALLENGE, HABIT, PRIVACY, TERMS,
        PRACTICE, APPEARANCE, HELP, ABOUT, DIAGNOSTICS,
        "path", "session", "chapter", GUIDES, LABS, TESTS,
        "test-quiz", "entry", INTERVIEW,
        "assessment-path", "assessment-dimension",
        CERTIFICATES, "final-assessment", "course-certificate",
        COURSE_CERTIFICATE_SAMPLE, "path-certificate", REVISION
    )

    /** True when [route] starts with a known base segment. */
    fun isKnownRoute(route: String): Boolean {
        val base = route.substringBefore("/").substringBefore("?")
        return base in knownBases
    }
}
