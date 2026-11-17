package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.Chapter

// ProgressVisibility.kt
// The progress rule (owner order, 15 Nov 2026): a fresh user must
// never see "0 of 953" or any 0-of-N counter. Progress UI appears
// only after meaningful progress, which means the first chapter
// completed: every lesson of at least one chapter finished.
// Before that, screens show a starter state, not a zero counter.
// Pure, so the rule is pinned by unit tests.

object ProgressVisibility {

    /**
     * True when at least one chapter is fully complete: the
     * chapter has lessons, and every one of its lesson ids is in
     * [completedIds]. Chapters with no lessons never count.
     */
    fun hasCompletedChapter(
        completedIds: Set<String>,
        chapters: List<Chapter>
    ): Boolean {
        return chapters.any { chapter ->
            chapter.lessonIds.isNotEmpty() &&
                chapter.lessonIds.all { it in completedIds }
        }
    }
}
