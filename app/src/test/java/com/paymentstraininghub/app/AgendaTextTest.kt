// AgendaTextTest.kt
// Unit tests for the agenda display polish: standalone "ai"
// renders as "AI", and nothing else in the copy changes.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.theme.polishAgendaText
import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaTextTest {

    @Test
    fun standaloneAiBecomesAI() {
        assertEquals(
            "Explain AI in reconciliation matching.",
            polishAgendaText("Explain ai in reconciliation matching.")
        )
    }

    @Test
    fun otherWordsAreUntouched() {
        assertEquals(
            "A payment is an instruction to move money. Said plainly, it is a claim.",
            polishAgendaText("A payment is an instruction to move money. Said plainly, it is a claim.")
        )
        assertEquals("AI governance.", polishAgendaText("AI governance."))
        assertEquals("", polishAgendaText(""))
    }
}
