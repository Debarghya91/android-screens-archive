package com.paymentstraininghub.app

import com.paymentstraininghub.app.ui.theme.PathCommand
import com.paymentstraininghub.app.ui.theme.ThumbPaint
import com.paymentstraininghub.app.ui.theme.ThumbShape
import com.paymentstraininghub.app.ui.theme.parsePathData
import com.paymentstraininghub.app.ui.theme.parseThumbSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbSvgTest {

    // The real shipped ch1s1 thumbnail, copied verbatim: navy
    // background, overlay, circles, arrow path, gold bar, labels.
    private val ch1s1 = """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180" role="img"><title>ch1s1</title><rect width="320" height="180" fill="#0D1C38"/><rect x="0" y="0" width="320" height="180" fill="#173F8C" opacity="0.35"/><circle cx="210" cy="60" r="18" fill="#93C5FD"/><circle cx="272" cy="110" r="18" fill="#FBBF24"/><path d="M224 72 L258 98" stroke="#FBBF24" stroke-width="5" stroke-linecap="round"/><path d="M258 98 l-14 -2 l6 13 z" fill="#FBBF24"/><circle cx="232" cy="146" r="8" fill="#93C5FD" opacity="0.7"/><rect x="18" y="118" width="46" height="6" rx="3" fill="#FBBF24"/><text x="18" y="102" font-family="Arial, Helvetica, sans-serif" font-size="34" font-weight="bold" fill="#FFFFFF">1.1</text><text x="18" y="152" font-family="Arial, Helvetica, sans-serif" font-size="17" fill="#E2E8F0">Lesson</text></svg>"""

    @Test
    fun parsesShippedThumbnail() {
        val parsed = parseThumbSvg(ch1s1)!!
        assertEquals(320f, parsed.viewBoxWidth, 0.001f)
        assertEquals(180f, parsed.viewBoxHeight, 0.001f)
        assertEquals(10, parsed.shapes.size)
        val background = parsed.shapes[0] as ThumbShape.Rect
        assertEquals(ThumbPaint.Color(0x0D, 0x1C, 0x38), background.fill)
        assertEquals(320f, background.width, 0.001f)
    }

    @Test
    fun parsesLabelsWithWeightAndFill() {
        val parsed = parseThumbSvg(ch1s1)!!
        val labels = parsed.shapes.filterIsInstance<ThumbShape.Label>()
        assertEquals(2, labels.size)
        assertEquals("1.1", labels[0].text)
        assertEquals(34f, labels[0].fontSize, 0.001f)
        assertTrue(labels[0].bold)
        assertEquals(ThumbPaint.Color(0xFF, 0xFF, 0xFF), labels[0].fill)
        assertEquals("Lesson", labels[1].text)
    }

    @Test
    fun parsesOpacityAndStroke() {
        val parsed = parseThumbSvg(ch1s1)!!
        val overlay = parsed.shapes[1] as ThumbShape.Rect
        assertEquals(0.35f, overlay.opacity, 0.001f)
        val arrow = parsed.shapes[4] as ThumbShape.Path
        assertEquals(ThumbPaint.Color(0xFB, 0xBF, 0x24), arrow.stroke)
        assertEquals(5f, arrow.strokeWidth, 0.001f)
        assertTrue(arrow.roundCaps)
    }

    @Test
    fun relativePathCommandsResolveToAbsolute() {
        val commands = parsePathData("M258 98 l-14 -2 l6 13 z")
        assertEquals(
            listOf(
                PathCommand.MoveTo(258f, 98f),
                PathCommand.LineTo(244f, 96f),
                PathCommand.LineTo(250f, 109f),
                PathCommand.Close
            ),
            commands
        )
    }

    @Test
    fun multipleMovesStayMoves() {
        val commands = parsePathData("M10 10 L20 20 M30 30 L40 40")
        assertEquals(
            listOf(
                PathCommand.MoveTo(10f, 10f),
                PathCommand.LineTo(20f, 20f),
                PathCommand.MoveTo(30f, 30f),
                PathCommand.LineTo(40f, 40f)
            ),
            commands
        )
    }

    @Test
    fun implicitLinePairsAfterMoveAreLines() {
        val commands = parsePathData("M10 10 20 20 30 30")
        assertEquals(
            listOf(
                PathCommand.MoveTo(10f, 10f),
                PathCommand.LineTo(20f, 20f),
                PathCommand.LineTo(30f, 30f)
            ),
            commands
        )
    }

    @Test
    fun nonSvgOrEmptyYieldsNull() {
        assertNull(parseThumbSvg(""))
        assertNull(parseThumbSvg("not svg at all"))
        assertNull(parseThumbSvg("<svg viewBox=\"0 0 320 180\"></svg>"))
        assertNull(parseThumbSvg("<svg width=\"320\" height=\"180\"><title>x</title></svg>"))
    }

    @Test
    fun malformedElementsAreSkippedNotFatal() {
        val parsed = parseThumbSvg(
            "<svg viewBox=\"0 0 320 180\">" +
                "<rect width=\"oops\" height=\"10\"/>" +
                "<rect width=\"320\" height=\"180\" fill=\"#0D1C38\"/>" +
                "<ellipse cx=\"10\" cy=\"10\" rx=\"5\"/>" +
                "</svg>"
        )!!
        assertEquals(1, parsed.shapes.size)
    }

    @Test
    fun shortHexAndNonePaints() {
        val parsed = parseThumbSvg(
            "<svg viewBox=\"0 0 10 10\">" +
                "<rect width=\"10\" height=\"10\" fill=\"#F80\"/>" +
                "<circle cx=\"5\" cy=\"5\" r=\"2\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1\"/>" +
                "</svg>"
        )!!
        val rect = parsed.shapes[0] as ThumbShape.Rect
        assertEquals(ThumbPaint.Color(0xFF, 0x88, 0x00), rect.fill)
        val circle = parsed.shapes[1] as ThumbShape.Circle
        assertEquals(ThumbPaint.None, circle.fill)
        assertEquals(ThumbPaint.Color(0xFF, 0xFF, 0xFF), circle.stroke)
    }
}
