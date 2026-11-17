// LearnShared.kt
// Shared rows and helpers for the Learn detail screens: the
// lesson row with lock/sample states. Lock rules match iOS: a lesson
// is open when it is the free sample (first lesson of its
// chapter) or its chapter is unlocked. A locked lesson tap always
// opens the lesson route, where the lesson screen shows the
// paywall view (what to expect plus the buy button); list rows
// never jump straight to checkout. No em dashes in any
// user-facing text.

package com.paymentstraininghub.app.ui.learn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.ThumbnailTile

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun LearnLessonRow(
    lessonId: String,
    title: String,
    isSample: Boolean,
    unlocked: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    thumbnailPath: String? = null
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = PfColors.background),
        border = BorderStroke(1.dp, PfColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PfSpacing.xSmall)
    ) {
        Row(
            modifier = Modifier.padding(PfSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThumbnailTile(
                thumbnailPath = thumbnailPath,
                modifier = Modifier.size(width = 56.dp, height = 32.dp),
                fallback = {
                    Icon(
                        if (unlocked) Icons.Filled.PlayArrow else Icons.Filled.Lock,
                        contentDescription = if (unlocked) "Open lesson" else "Locked lesson",
                        tint = if (unlocked) PfColors.royal else PfColors.secondaryText
                    )
                }
            )
            Column(modifier = Modifier.weight(1f).padding(start = PfSpacing.small)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = PfColors.navy)
                // The subtitle is a display label ("Lesson 3",
                // "Chapter 26, Lesson 1"), never the raw lesson
                // id. lessonId stays a parameter for call-site
                // compatibility; it is never rendered.
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PfColors.secondaryText)
                }
            }
            if (isSample) {
                Surface(
                    color = PfColors.goldSoft,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "Free sample",
                        style = MaterialTheme.typography.labelSmall,
                        color = PfColors.goldText,
                        modifier = Modifier.padding(
                            horizontal = PfSpacing.small,
                            vertical = PfSpacing.xSmall
                        )
                    )
                }
            }
        }
    }
}

/** Whether a lesson opens: free sample, or its chapter is unlocked. */
internal fun isLessonOpen(lessonId: String, chapterId: String, isSample: Boolean): Boolean {
    return isSample || BillingManager.isLessonUnlocked(lessonId, chapterId)
}
