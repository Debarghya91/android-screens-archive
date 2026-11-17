package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.agendaThumbnailUrl
import com.paymentstraininghub.app.data.parseAgendas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaStoreTest {

    private val sampleJson = """
        {
          "lessons": {
            "ch1s1": {"agenda": "Why take this lesson.", "thumbnail": "assets/thumbs/ch1s1.svg"},
            "ch1s2": {"agenda": "Second lesson agenda.", "thumbnail": "assets/thumbs/ch1s2.svg"}
          },
          "chapters": {
            "ch1": {"agenda": "Why take this chapter.", "thumbnail": "assets/thumbs/ch1.svg"}
          },
          "courses": {
            "part-1": {"agenda": "Why take this course.", "thumbnail": "assets/thumbs/course-part-1.svg"},
            "integrated": {"agenda": "The full course agenda.", "thumbnail": "assets/thumbs/course-integrated.svg"}
          },
          "cases": {
            "ABH-01": {"agenda": "Why work this case.", "thumbnail": "assets/thumbs/case-ABH-01.svg"}
          }
        }
    """.trimIndent()

    @Test
    fun lessonsJoinByLessonId() {
        val agendas = parseAgendas(sampleJson)
        assertEquals("Why take this lesson.", agendas.lesson("ch1s1")?.agenda)
        assertEquals("assets/thumbs/ch1s1.svg", agendas.lesson("ch1s1")?.thumbnail)
        assertNull(agendas.lesson("ch9s9"))
    }

    @Test
    fun chaptersJoinByChapterId() {
        val agendas = parseAgendas(sampleJson)
        assertEquals("Why take this chapter.", agendas.chapter("ch1")?.agenda)
        assertNull(agendas.chapter("ch2"))
    }

    @Test
    fun coursesJoinByBundleId() {
        val agendas = parseAgendas(sampleJson)
        assertEquals("Why take this course.", agendas.course("part-1")?.agenda)
        assertEquals("The full course agenda.", agendas.course("integrated")?.agenda)
        assertNull(agendas.course("part-2"))
    }

    @Test
    fun casesJoinByCaseId() {
        val agendas = parseAgendas(sampleJson)
        assertEquals("Why work this case.", agendas.case("ABH-01")?.agenda)
        assertEquals("assets/thumbs/case-ABH-01.svg", agendas.case("ABH-01")?.thumbnail)
        assertNull(agendas.case("ABH-02"))
    }

    @Test
    fun malformedJsonYieldsEmptyAgendas() {
        assertTrue(parseAgendas("not json").isEmpty)
        assertTrue(parseAgendas("").isEmpty)
        assertTrue(parseAgendas("{}").isEmpty)
    }

    @Test
    fun blankAgendasAreDropped() {
        val parsed = parseAgendas("""{"lessons": {"ch1s1": {"agenda": "  ", "thumbnail": "assets/thumbs/ch1s1.svg"}}}""")
        assertNull(parsed.lesson("ch1s1"))
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val parsed = parseAgendas("""{"lessons": {"ch1s1": {"agenda": "A.", "thumbnail": null, "extra": 1}}, "future": {"x": 1}}""")
        assertEquals("A.", parsed.lesson("ch1s1")?.agenda)
        assertNull(parsed.lesson("ch1s1")?.thumbnail)
    }

    @Test
    fun thumbnailUrlPrependsWebsiteBase() {
        assertEquals(
            "https://www.paymentstraininghub.com/assets/thumbs/ch1s1.svg",
            agendaThumbnailUrl("assets/thumbs/ch1s1.svg")
        )
        assertEquals(
            "https://www.paymentstraininghub.com/assets/thumbs/ch1s1.svg",
            agendaThumbnailUrl("/assets/thumbs/ch1s1.svg")
        )
        val absolute = "https://example.com/thumb.svg"
        assertEquals(absolute, agendaThumbnailUrl(absolute))
    }

    @Test
    fun agendasUrlPointsAtTheLiveFile() {
        assertEquals(
            "https://www.paymentstraininghub.com/assets/agendas.json",
            AgendaStore.AGENDAS_URL
        )
    }
}
