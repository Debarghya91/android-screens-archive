package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.SupplementalFlowJson
import com.paymentstraininghub.app.data.SupplementalFlowStepJson
import com.paymentstraininghub.app.data.SupplementalLessons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementalLessonsTest {

    private fun flows(kind: String?, vararg steps: SupplementalFlowStepJson) =
        mapOf("ch1s901-supp" to SupplementalFlowJson(kind = kind, steps = steps.toList()))

    @Test
    fun beatsAreUntimedAndInOrder() {
        val beats = SupplementalLessons.beatsFor(
            "ch1s901-supp",
            flows(
                "flow",
                SupplementalFlowStepJson(title = "First step", note = "First note"),
                SupplementalFlowStepJson(title = "Second step", note = null)
            )
        )
        assertEquals(2, beats.size)
        assertEquals("ch1s901-supp-beat-0", beats[0].id)
        assertEquals("First step", beats[0].title)
        assertEquals("First note", beats[0].note)
        assertEquals("Second step", beats[1].title)
        assertEquals("", beats[1].note)
        for (beat in beats) {
            assertEquals(0.0, beat.startTime, 0.0)
            assertEquals(0.0, beat.endTime, 0.0)
        }
    }

    @Test
    fun emptyTitleStepsAreSkipped() {
        val beats = SupplementalLessons.beatsFor(
            "ch1s901-supp",
            flows(
                "flow",
                SupplementalFlowStepJson(title = "  ", note = "No title"),
                SupplementalFlowStepJson(title = "Kept", note = null)
            )
        )
        assertEquals(1, beats.size)
        assertEquals("Kept", beats[0].title)
    }

    @Test
    fun missingDescriptorYieldsNoBeats() {
        assertTrue(
            SupplementalLessons.beatsFor("ch1s901-supp", emptyMap()).isEmpty()
        )
        assertTrue(
            SupplementalLessons.beatsFor(
                "ch1s901-supp",
                mapOf("ch1s901-supp" to SupplementalFlowJson(kind = "flow", steps = null))
            ).isEmpty()
        )
    }

    @Test
    fun arrowsOnlyForFlowKind() {
        assertTrue(
            SupplementalLessons.showsFlowArrows(
                "ch1s901-supp",
                flows("flow", SupplementalFlowStepJson(title = "Step"))
            )
        )
        assertFalse(
            SupplementalLessons.showsFlowArrows(
                "ch1s901-supp",
                flows("diagram", SupplementalFlowStepJson(title = "Step"))
            )
        )
        assertFalse(
            SupplementalLessons.showsFlowArrows(
                "ch1s901-supp",
                flows("something-else", SupplementalFlowStepJson(title = "Step"))
            )
        )
        assertFalse(
            SupplementalLessons.showsFlowArrows("ch1s901-supp", emptyMap())
        )
    }

    @Test
    fun isSupplementalMatchesIdSuffix() {
        val entry = com.paymentstraininghub.app.data.model.SearchEntry(
            id = "ch1s901-supp",
            title = "Book payments",
            url = "",
            type = "lesson",
            text = "Body"
        )
        assertTrue(entry.isSupplemental)
        assertFalse(entry.copy(id = "ch1s1").isSupplemental)
    }
}
