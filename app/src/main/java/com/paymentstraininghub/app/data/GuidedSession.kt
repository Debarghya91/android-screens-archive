// GuidedSession.kt
// The 8-session guided path, ported from ContentService.swift
// guidedSessions: titles, descriptions and chapter ids are copied
// exactly. Chapters resolve from the loaded search index; lessons
// run in session chapter order, deduped by id, as in
// PFChapterHelper.lessons(forSession:). No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.SearchEntry

data class GuidedSession(
    val id: Int,
    val title: String,
    val description: String,
    val chapterIds: List<String>
) {
    /** Lessons for this session, in session chapter order, deduped by id. */
    fun lessons(entries: List<SearchEntry>): List<SearchEntry> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SearchEntry>()
        for (chapterId in chapterIds) {
            val number = ContentRepository.chapterNumberFromId(chapterId + "s1") ?: continue
            for (lesson in entries) {
                if (!lesson.isLesson) continue
                if (ContentRepository.chapterNumberFromId(lesson.id) != number) continue
                if (seen.add(lesson.id)) result.add(lesson)
            }
        }
        return result
    }
}

object GuidedSessions {
    val all: List<GuidedSession> = listOf(
        GuidedSession(
            id = 1,
            title = "What a payment is",
            description = "Foundations, parties, accounts and the payment lifecycle.",
            chapterIds = listOf("ch69", "ch1", "ch2", "ch64", "ch3", "ch70", "ch4", "ch71", "ch90")
        ),
        GuidedSession(
            id = 2,
            title = "How money moves",
            description = "Rails, clearing and settlement, correspondent banking.",
            chapterIds = listOf("ch13", "ch17", "ch18", "ch72", "ch19", "ch20", "ch46", "ch74", "ch94", "ch77", "ch88", "ch89", "ch27")
        ),
        GuidedSession(
            id = 3,
            title = "SWIFT and messages",
            description = "MT and MX messages, field-level reading, UETR tracking.",
            chapterIds = listOf("ch54", "ch5", "ch14", "ch6", "ch53", "ch50", "ch52", "ch75")
        ),
        GuidedSession(
            id = 4,
            title = "ISO 20022 in depth",
            description = "pain, pacs and camt messages, migration and mapping.",
            chapterIds = listOf("ch8", "ch7", "ch60", "ch76", "ch39", "ch67", "ch73", "ch55")
        ),
        GuidedSession(
            id = 5,
            title = "Schemes and rails",
            description = "SEPA, Faster Payments, CHAPS, BACS, Fedwire and CHIPS.",
            chapterIds = listOf("ch9", "ch33", "ch10", "ch92", "ch11", "ch93", "ch61", "ch62", "ch56", "ch57", "ch59", "ch12", "ch91", "ch30", "ch34", "ch83")
        ),
        GuidedSession(
            id = 6,
            title = "Exceptions, risk and compliance",
            description = "Returns, recalls, investigations, risk and AML.",
            chapterIds = listOf("ch41", "ch16", "ch58", "ch40", "ch23", "ch95", "ch24", "ch81", "ch86", "ch65", "ch87")
        ),
        GuidedSession(
            id = 7,
            title = "Cards",
            description = "Card payments, authorisation, clearing and chargebacks.",
            chapterIds = listOf("ch21", "ch22", "ch31", "ch63", "ch78")
        ),
        GuidedSession(
            id = 8,
            title = "Practice and interview",
            description = "Scenarios and interview questions to practise what you have learned.",
            chapterIds = listOf("ch15", "ch28", "ch38", "ch42", "ch44", "ch84", "ch29")
        )
    )

    fun byId(id: Int): GuidedSession? = all.firstOrNull { it.id == id }
}
