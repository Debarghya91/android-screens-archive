// SwipeHintGif.kt
// The looping swipe-next hint for the introduction: three royal
// chevrons sliding left, on a transparent background, in a 600ms
// loop. The GIF is generated (see the onboarding notes) and
// rendered through ImageDecoder on API 28 and above. Older
// devices, and any decode failure, fall back to static chevrons
// drawn in Compose, so the hint can never crash the introduction
// or the render harness. No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.onboarding

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.paymentstraininghub.app.R
import com.paymentstraininghub.app.ui.theme.PfColors

/** The content description shared by the animated hint and its static fallback. */
const val SWIPE_HINT_DESCRIPTION = "Swipe left for the next page"

@Composable
fun SwipeHintGif(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            null
        } else {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.resources, R.drawable.swipe_next_hint)
                )
            }.getOrNull()
        }
    }
    if (drawable != null) {
        AndroidView(
            factory = { imageContext ->
                ImageView(imageContext).apply {
                    contentDescription = SWIPE_HINT_DESCRIPTION
                    setImageDrawable(drawable)
                }
            },
            update = { view ->
                (view.drawable as? Animatable)?.start()
            },
            onRelease = { view ->
                (view.drawable as? Animatable)?.stop()
            },
            modifier = modifier
                .size(width = 72.dp, height = 24.dp)
                .semantics { contentDescription = SWIPE_HINT_DESCRIPTION }
        )
    } else {
        Canvas(
            modifier = modifier
                .size(width = 72.dp, height = 24.dp)
                .semantics { contentDescription = SWIPE_HINT_DESCRIPTION }
        ) {
            // Two static chevrons, the same royal and the same
            // shape as the GIF, for devices without ImageDecoder
            // and for any environment where decoding fails.
            val chevron: (Float) -> Path = { left ->
                Path().apply {
                    val top = 4.dp.toPx()
                    val middle = size.height / 2f
                    val bottom = size.height - 4.dp.toPx()
                    val tip = left
                    val tail = left + 14.dp.toPx()
                    moveTo(tail, top)
                    lineTo(tip, middle)
                    lineTo(tail, bottom)
                }
            }
            val stroke = Stroke(width = 3.dp.toPx())
            drawPath(chevron(8.dp.toPx()), PfColors.royal, style = stroke)
            drawPath(chevron(34.dp.toPx()), PfColors.royal, style = stroke)
        }
    }
}
