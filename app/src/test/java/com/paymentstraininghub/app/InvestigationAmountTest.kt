package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.investigation.InvestigationEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Amount-extraction tests for the Investigation Experience header:
 * a scale word after the number (million, billion, thousand,
 * crore, lakh) is kept as written, mirroring website fix 5.
 * No numeric conversion happens anywhere: the header carries
 * the amount as stated, as a display string.
 */
class InvestigationAmountTest {

    @Test
    fun scaleWordMillionKept() {
        val header = InvestigationEngine.paymentHeader(
            "EUR 40 million weekly settlement fails",
            "The settlement file never arrived."
        )
        assertEquals("EUR 40 million", header.amountText)
        assertEquals("EUR", header.currencyText)
    }

    @Test
    fun scaleWordBillionWithDecimalKept() {
        val header = InvestigationEngine.paymentHeader(
            "USD 2.5 billion payment stuck in clearing",
            "The clearing batch is held."
        )
        assertEquals("USD 2.5 billion", header.amountText)
        assertEquals("USD", header.currencyText)
    }

    @Test
    fun scaleWordLakhWithRsKept() {
        val header = InvestigationEngine.paymentHeader(
            "Rs 50 lakh transfer delayed",
            "The transfer is still queued."
        )
        assertEquals("Rs 50 lakh", header.amountText)
        assertEquals("INR", header.currencyText)
    }

    @Test
    fun amountWithoutScaleWordUnchanged() {
        val header = InvestigationEngine.paymentHeader(
            "GBP 12,500 fee dispute",
            "The fee was charged twice."
        )
        assertEquals("GBP 12,500", header.amountText)
        assertEquals("GBP", header.currencyText)
    }

    @Test
    fun scaleWordCroreKept() {
        val header = InvestigationEngine.paymentHeader(
            "INR 7 crore payout held for review",
            "The payout awaits approval."
        )
        assertEquals("INR 7 crore", header.amountText)
        assertEquals("INR", header.currencyText)
    }

    @Test
    fun scaleWordThousandWithRupeeSignKept() {
        val header = InvestigationEngine.paymentHeader(
            "₹ 25 thousand refund missing",
            "The refund never reached the account."
        )
        assertEquals("₹ 25 thousand", header.amountText)
        assertEquals("INR", header.currencyText)
    }

    @Test
    fun pluralScaleWordKeptAsWritten() {
        val header = InvestigationEngine.paymentHeader(
            "EUR 3 millions held in suspense",
            "The suspense account grows daily."
        )
        assertEquals("EUR 3 millions", header.amountText)
        assertEquals("EUR", header.currencyText)
    }

    @Test
    fun amountInExplainUsedWhenTitleHasNone() {
        val header = InvestigationEngine.paymentHeader(
            "Settlement file never arrived",
            "The file carried EUR 40 million in weekly settlements."
        )
        assertEquals("EUR 40 million", header.amountText)
        assertEquals("EUR", header.currencyText)
    }

    @Test
    fun noAmountStated() {
        val header = InvestigationEngine.paymentHeader(
            "Duplicate file received",
            "The same file arrived twice."
        )
        assertEquals("Amount not stated in the case", header.amountText)
    }
}
