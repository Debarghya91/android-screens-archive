package com.paymentstraininghub.app.data

// ContentManifest.kt
// Payments Training Hub Android - Single source of truth for catalogue counts.
// Ported from the iOS ContentManifest.swift.

/// Verified live numbers (15 Nov 2026): 100 chapters, 881
/// narrated lessons, 564 situations, 320 FAQ, 10 simulators,
/// exactly 500 interview questions, 45 guides, 15 tests, 10
/// labs, 15 domains, 72 supplemental lessons. The lesson total follows the live search
/// index; the 881-lesson content round is live, and this
/// number tracks it.
///
/// This object is the single source of truth for counts. No file
/// should hardcode these numbers: refer to ContentManifest instead,
/// so a catalogue change updates every screen from one place. Counts
/// that come from live loaded data (the search index, the bundled
/// interview bank) still come from that data; ContentManifest is
/// used for display copy and for fallbacks while live data loads.
object ContentManifest {
    const val courseCount = 10
    const val chapterCount = 100
    const val lessonCount = 881
    const val situationCount = 564
    const val interviewQuestionCount = 500
    const val faqCount = 320
    const val simulatorCount = 10
    const val guideCount = 45
    const val testCount = 15
    const val labCount = 10
    const val domainCount = 15
    const val supplementalLessonCount = 72
}
