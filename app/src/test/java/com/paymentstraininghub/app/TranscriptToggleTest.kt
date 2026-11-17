// TranscriptToggleTest.kt
// Wave 3 "Show full text" toggle proofs. Real lessons carry
// exactly one paragraph, so the old paragraph-count collapse
// showed the whole text while the label still said "Show full
// text" and the toggle did nothing. The fix previews by
// character count. Pure tests pin the preview rules; compose
// tests prove one tap expands and one tap collapses, with the
// label matching the visible state each way.

package com.paymentstraininghub.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.paymentstraininghub.app.ui.lesson.TRANSCRIPT_PREVIEW_CHARS
import com.paymentstraininghub.app.ui.lesson.TranscriptSection
import com.paymentstraininghub.app.ui.lesson.transcriptCanExpand
import com.paymentstraininghub.app.ui.lesson.transcriptCollapsedPreview
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class TranscriptTogglePureTest {

    @Test
    fun singleLongParagraphCanExpand() {
        val paragraph = "A payment moves money. ".repeat(30)
        assertTrue(paragraph.length > TRANSCRIPT_PREVIEW_CHARS)
        assertTrue(transcriptCanExpand(listOf(paragraph)))
    }

    @Test
    fun singleShortParagraphCannotExpand() {
        assertFalse(transcriptCanExpand(listOf("Short text.")))
    }

    @Test
    fun multipleParagraphsCanExpand() {
        assertTrue(transcriptCanExpand(listOf("First paragraph.", "Second paragraph.")))
    }

    @Test
    fun previewTruncatesLongParagraphWithEllipsis() {
        val paragraph = "A payment moves money. ".repeat(30)
        val preview = transcriptCollapsedPreview(listOf(paragraph))
        assertTrue(preview.endsWith("..."))
        assertTrue(preview.length <= TRANSCRIPT_PREVIEW_CHARS + 3)
        assertTrue(preview.startsWith("A payment moves money."))
    }

    @Test
    fun previewOfShortParagraphHasNoEllipsis() {
        assertEquals("Short text.", transcriptCollapsedPreview(listOf("Short text.")))
    }

    @Test
    fun previewOfEmptyTranscriptIsEmpty() {
        assertEquals("", transcriptCollapsedPreview(emptyList()))
        assertFalse(transcriptCanExpand(emptyList()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class TranscriptToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleTapExpandsAndCollapsesWithMatchingLabel() {
        val detail = RenderFakeData.lessonDetail("ch1s1")
        assertEquals(1, detail.transcript.split("\n\n").size)
        assertTrue(detail.transcript.length > TRANSCRIPT_PREVIEW_CHARS)
        composeRule.setContent {
            PfTheme { TranscriptSection(detail = detail) }
        }
        composeRule.waitForIdle()
        // Collapsed: the preview shows, the tail text does not,
        // and the label says Show.
        composeRule.onNodeWithText("Show full text").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasText("Keep these three layers", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
        // One tap expands: full text plus the Hide label.
        composeRule.onNodeWithTag("transcript-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide full text").assertIsDisplayed()
        composeRule.onNodeWithText("Keep these three layers", substring = true)
            .assertIsDisplayed()
        // One tap collapses again: the label and the text agree.
        composeRule.onNodeWithTag("transcript-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show full text").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasText("Keep these three layers", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun shortTranscriptShowsNoToggle() {
        val detail = RenderFakeData.lessonDetail("ch1s1").copy(
            transcript = "Short text.",
            beats = emptyList()
        )
        composeRule.setContent {
            PfTheme { TranscriptSection(detail = detail) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Short text.").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasText("Show full text"))
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            composeRule.onAllNodes(hasText("Hide full text"))
                .fetchSemanticsNodes().isEmpty()
        )
    }
}
