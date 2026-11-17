package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.theme.PfParagraphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PfParagraphsTest {

    @Test
    fun emptyTextRendersNothing() {
        assertEquals(emptyList<String>(), PfParagraphs.split(""))
        assertEquals(emptyList<String>(), PfParagraphs.split("   \n\n  "))
    }

    @Test
    fun shortTextIsUnchanged() {
        val text = "One short sentence."
        assertEquals(listOf(text), PfParagraphs.split(text))
    }

    @Test
    fun lineBreaksAreParagraphBoundariesFirst() {
        val split = PfParagraphs.split("First paragraph.\nSecond paragraph.\n\nThird paragraph.")
        assertEquals(
            listOf("First paragraph.", "Second paragraph.", "Third paragraph."),
            split
        )
    }

    @Test
    fun windowsLineEndingsAreNormalised() {
        val split = PfParagraphs.split("First.\r\nSecond.")
        assertEquals(listOf("First.", "Second."), split)
    }

    @Test
    fun longBlockSplitsIntoSentenceGroupsOfAtMostThree() {
        val block = (1..7).joinToString(" ") { "Sentence number $it ends here." }
        val split = PfParagraphs.split(block)
        assertEquals(3, split.size)
        assertTrue(split[0].contains("Sentence number 3"))
        assertTrue(split[1].contains("Sentence number 6"))
        assertTrue(split[2].contains("Sentence number 7"))
        // No wording is lost or changed: rejoining reproduces the block.
        assertEquals(block, split.joinToString(" "))
    }

    @Test
    fun longSentencesSplitAtTheCharacterLimit() {
        val longSentence = "Word ".repeat(120).trim() + "."
        val block = "$longSentence $longSentence"
        val split = PfParagraphs.split(block)
        assertEquals(2, split.size)
        assertEquals(block, split.joinToString(" "))
    }

    @Test
    fun abbreviationsAndDecimalsDoNotSplitSentences() {
        val block = "Dr. Rao approved Rs. 2,500.50 for the e.g. test case. " +
            "The amount was 10.5 percent. U.S. banks use Fedwire."
        val split = PfParagraphs.split(block)
        // Three sentences, under the character limit: one paragraph.
        assertEquals(1, split.size)
        assertEquals(block, split[0])
    }

    @Test
    fun singleLongSentenceIsUnchanged() {
        val block = "Word ".repeat(130).trim() + "."
        assertEquals(listOf(block), PfParagraphs.split(block))
    }
}
