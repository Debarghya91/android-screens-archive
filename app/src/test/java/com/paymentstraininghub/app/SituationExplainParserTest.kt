package com.paymentstraininghub.app

import com.paymentstraininghub.app.data.parseSituationExplain
import com.paymentstraininghub.app.ui.cases.formatStackedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationExplainParserTest {

    private val sevenPart = listOf(
        "A corporate client sends a salary payment for 500 employees.",
        "Here is what should happen:",
        "→ The payment is validated against the payroll file.\n→ The bank releases the funds on the value date.",
        "The requirement said: release the payment as soon as the file arrives.",
        "Why?",
        "The file arrival was treated as approval.",
        "The real control is a separate approval step.",
        "BA Takeaway",
        "Always separate file receipt from payment approval.",
        "Keep these controls separate:\n→ Require dual approval.\n→ Reconcile the file before release.",
        "The real requirement is: release only after approval and reconciliation.",
        "Have you seen a payment released on file arrival alone?"
    ).joinToString("\n\n")

    @Test
    fun sevenPartExplainParsesEverySection() {
        val parsed = parseSituationExplain(sevenPart)
        assertTrue(parsed.isParsed)
        assertEquals(
            listOf("A corporate client sends a salary payment for 500 employees."),
            parsed.opening
        )
        assertEquals(
            listOf(
                "The payment is validated against the payroll file.",
                "The bank releases the funds on the value date."
            ),
            parsed.flowSteps
        )
        assertEquals(
            listOf("The requirement said: release the payment as soon as the file arrives."),
            parsed.flawedRequirement
        )
        assertEquals(
            listOf("The file arrival was treated as approval."),
            parsed.rootCause
        )
        assertEquals("The real control is a separate approval step.", parsed.insight)
        assertEquals(
            listOf("Always separate file receipt from payment approval."),
            parsed.baBody
        )
        assertEquals(
            listOf("Require dual approval.", "Reconcile the file before release."),
            parsed.controls
        )
        assertEquals(
            listOf("The real requirement is: release only after approval and reconciliation."),
            parsed.realRequirement
        )
        assertEquals(
            "Have you seen a payment released on file arrival alone?",
            parsed.closingQuestion
        )
    }

    @Test
    fun unstructuredTextDoesNotParse() {
        val parsed = parseSituationExplain("Just a wall of text with no structure.")
        assertFalse(parsed.isParsed)
        assertNull(parsed.closingQuestion)
        assertTrue(parsed.baBody.isEmpty())
        assertTrue(parsed.controls.isEmpty())
    }

    @Test
    fun rootCauseWithoutWhyComesFromFlawedBlock() {
        val explain = listOf(
            "A payment went to the wrong beneficiary.",
            "The requirement said: use the name on the invoice.",
            "The team never checked the account number.",
            "The check was the missing control.",
            "BA Takeaway",
            "Check the account number first."
        ).joinToString("\n\n")
        val parsed = parseSituationExplain(explain)
        assertTrue(parsed.isParsed)
        assertEquals(
            listOf("The requirement said: use the name on the invoice."),
            parsed.flawedRequirement
        )
        assertEquals(
            listOf("The team never checked the account number."),
            parsed.rootCause
        )
        assertEquals("The check was the missing control.", parsed.insight)
        assertEquals(listOf("Check the account number first."), parsed.baBody)
    }

    @Test
    fun emptyExplainParsesToEmpty() {
        val parsed = parseSituationExplain("")
        assertFalse(parsed.isParsed)
        assertTrue(parsed.opening.isEmpty())
    }

    @Test
    fun stackedValueFormatsWholeAndDecimal() {
        assertEquals("100 cases", formatStackedValue(100.0, "cases"))
        assertEquals("12.5", formatStackedValue(12.5, null))
        assertEquals("0 USD", formatStackedValue(0.0, "USD"))
    }
}
