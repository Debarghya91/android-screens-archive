// CitationTest.kt
// Unit tests for SearchEntry parsing and citations. The supplemental
// trace is the regression test for the 14 Nov 2026 correction:
// lessonNumber parses from the LAST "s" in the id, so for
// "ch54s901-supp" the last "s" is in "-supp", no digits follow it,
// and lessonNumber is null. Citations must therefore keep the
// chapter and the real title with no misleading number, and must
// never render "Lesson 901".

package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.model.SearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CitationTest {

    private fun entry(id: String, title: String, type: String = "lesson"): SearchEntry {
        return SearchEntry(id = id, title = title, url = "", type = type, text = "")
    }

    @Test
    fun supplementalLessonNumberIsNull() {
        val supplemental = entry(
            "ch54s901-supp",
            "RMA: the relationship that lets banks send each other SWIFT messages"
        )
        assertNull(supplemental.lessonNumber)
        assertEquals(54, supplemental.chapterNumber)
    }

    @Test
    fun supplementalCitationKeepsChapterAndTitleWithNoNumber() {
        val supplemental = entry(
            "ch54s901-supp",
            "RMA: the relationship that lets banks send each other SWIFT messages"
        )
        assertEquals(
            "Chapter 27: RMA: the relationship that lets banks send each other SWIFT messages",
            supplemental.citation
        )
    }

    @Test
    fun regularLessonCitationUsesSequentialPosition() {
        assertEquals(
            "Chapter 27, Lesson 2: SWIFTNet: How Banks Connect",
            entry("ch54s2", "SWIFTNet: How Banks Connect").citation
        )
        assertEquals(
            "Chapter 27, Lesson 1: What SWIFT Is and What It Is Not",
            entry("ch54s1", "What SWIFT Is and What It Is Not").citation
        )
    }

    @Test
    fun nonLessonCitationUsesTypeAndTitle() {
        assertEquals(
            "Guide: camt.029 Explained",
            entry("guide-camt029", "camt.029 Explained", type = "guide").citation
        )
    }
}
