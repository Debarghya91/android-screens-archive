package com.paymentstraininghub.app.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.data.ThumbnailStore

// ThumbnailViews.kt
// Thumbnail rendering and the highlighted agenda block shared by
// the lesson, chapter, course and case views.
//
// Thumbnails are SVG files on the website. The app has no image
// loading library and none resolves offline, so the SVG subset
// the website ships is drawn natively on a Canvas (see
// ThumbSvg.kt): rects, circles, polylines and labels, scaled to
// the tile. When no thumbnail path exists, or the file cannot be
// loaded or parsed, the caller-supplied fallback renders instead:
// never a crash, never placeholder text claiming content exists.
//
// The agenda block is the highlighted why-take-it block: a gold
// tinted card with a gold bar and a bold label. Callers render it
// only when an agenda string exists.

/**
 * A thumbnail tile for a relative thumbnail path. [fallback]
 * renders while the file loads, when the path is null, and when
 * the file cannot be loaded or parsed. The tile keeps the
 * thumbnail aspect ratio: callers size it 16:9 (for example
 * 72x40 dp) and the artwork fills it.
 */
@Composable
fun ThumbnailTile(
    thumbnailPath: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var svg by remember(thumbnailPath) { mutableStateOf<ThumbSvg?>(null) }
    var failed by remember(thumbnailPath) { mutableStateOf(false) }
    LaunchedEffect(thumbnailPath) {
        svg = null
        failed = false
        val path = thumbnailPath
        if (!path.isNullOrBlank()) {
            val text = ThumbnailStore.svgFor(path, context.filesDir)
            val parsed = text?.let { parseThumbSvg(it) }
            if (parsed != null) {
                svg = parsed
            } else {
                failed = true
            }
        }
    }
    val parsed = svg
    if (thumbnailPath.isNullOrBlank() || failed || parsed == null) {
        Box(
            modifier = modifier.testTag("thumbnailFallback"),
            contentAlignment = Alignment.Center
        ) { fallback() }
    } else {
        Canvas(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .testTag("thumbnailArtwork")
        ) {
            drawThumbSvg(parsed)
        }
    }
}

/** Draw a parsed thumbnail, scaled to fill this canvas. */
private fun DrawScope.drawThumbSvg(thumb: ThumbSvg) {
    val scaleX = size.width / thumb.viewBoxWidth
    val scaleY = size.height / thumb.viewBoxHeight
    val scale = minOf(scaleX, scaleY)
    // Letterbox: centre the artwork when the tile aspect differs
    // from the viewBox, so no label is ever cropped.
    val offsetX = (size.width - thumb.viewBoxWidth * scale) / 2f
    val offsetY = (size.height - thumb.viewBoxHeight * scale) / 2f
    fun x(value: Float): Float = offsetX + value * scale
    fun y(value: Float): Float = offsetY + value * scale
    for (shape in thumb.shapes) {
        when (shape) {
            is ThumbShape.Rect -> {
                val color = shape.fill.toColor(shape.opacity) ?: continue
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x(shape.x), y(shape.y)),
                    size = Size(shape.width * scale, shape.height * scale),
                    cornerRadius = CornerRadius(shape.cornerRadius * scale, shape.cornerRadius * scale)
                )
            }
            is ThumbShape.Circle -> {
                shape.fill.toColor(shape.opacity)?.let { color ->
                    drawCircle(
                        color = color,
                        radius = shape.r * scale,
                        center = Offset(x(shape.cx), y(shape.cy))
                    )
                }
                val stroke = shape.stroke.toColor(shape.opacity)
                if (stroke != null && shape.strokeWidth > 0f) {
                    drawCircle(
                        color = stroke,
                        radius = shape.r * scale,
                        center = Offset(x(shape.cx), y(shape.cy)),
                        style = Stroke(width = shape.strokeWidth * scale)
                    )
                }
            }
            is ThumbShape.Path -> {
                val path = Path()
                var open = false
                for (command in shape.commands) {
                    when (command) {
                        is PathCommand.MoveTo -> {
                            path.moveTo(x(command.x), y(command.y))
                            open = true
                        }
                        is PathCommand.LineTo -> {
                            if (open) path.lineTo(x(command.x), y(command.y))
                            else path.moveTo(x(command.x), y(command.y))
                            open = true
                        }
                        is PathCommand.Close -> {
                            path.close()
                            open = false
                        }
                    }
                }
                shape.fill.toColor(shape.opacity)?.let { color ->
                    drawPath(path = path, color = color)
                }
                val stroke = shape.stroke.toColor(shape.opacity)
                if (stroke != null && shape.strokeWidth > 0f) {
                    drawPath(
                        path = path,
                        color = stroke,
                        style = Stroke(
                            width = shape.strokeWidth * scale,
                            cap = if (shape.roundCaps) StrokeCap.Round else StrokeCap.Butt
                        )
                    )
                }
            }
            is ThumbShape.Label -> {
                val color = shape.fill.toColor(shape.opacity) ?: continue
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        this.color = android.graphics.Color.argb(
                            (color.alpha * 255).toInt(),
                            (color.red * 255).toInt(),
                            (color.green * 255).toInt(),
                            (color.blue * 255).toInt()
                        )
                        textSize = shape.fontSize * scale
                        typeface = if (shape.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        isAntiAlias = true
                    }
                    drawText(shape.text, x(shape.x), y(shape.y), paint)
                }
            }
        }
    }
}

private fun ThumbPaint.toColor(opacity: Float): Color? {
    return when (this) {
        is ThumbPaint.None -> null
        is ThumbPaint.Color -> Color(red, green, blue, (opacity * 255).toInt())
    }
}

/**
 * The highlighted agenda block: a gold-tinted card with a gold
 * bar, a bold label naming what the agenda is for, and the agenda
 * text. Rendered only when an agenda string exists.
 */
/**
 * Display polish for agenda copy fetched from the website data:
 * a standalone lowercase "ai" is the AI initialism and renders
 * as "AI". The website data is not regenerated in this wave, so
 * the fix lives at the display layer; the stored text is
 * untouched, and only the exact standalone word is changed.
 */
fun polishAgendaText(agenda: String): String {
    return agenda.replace(Regex("\\bai\\b"), "AI")
}

@Composable
fun AgendaBlock(
    agenda: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(PfSpacing.smallRadius))
            .background(PfColors.goldSoft)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(PfColors.gold)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(PfSpacing.medium)
        ) {
            Text(
                label,
                style = PfTypography.captionBold,
                fontWeight = FontWeight.Bold,
                color = PfColors.goldTextDark
            )
            Text(
                polishAgendaText(agenda),
                style = PfTypography.body,
                color = PfColors.navy
            )
        }
    }
}
