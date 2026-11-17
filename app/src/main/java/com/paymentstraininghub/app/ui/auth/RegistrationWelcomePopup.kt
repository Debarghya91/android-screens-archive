// RegistrationWelcomePopup.kt
// Payments Training Hub Android - the congratulations popup shown
// once, on the first sign-in right after registration completes
// (email and password set, terms accepted, signed in). It stays
// on screen until the learner dismisses it deliberately, with
// the Continue button or a tap anywhere: an earlier 3 second
// auto-dismiss flashed the "finish your profile" step away before
// it could be read. Mirrors iOS RegistrationWelcomePopup.
// AuthManager owns the once-only rule: the popup is only ever
// presented when the pf.welcomeShown.v1 flag for the account is
// unset, and the flag is written when the popup is first shown,
// so it can never appear on a later login, even if the app is
// killed while it is showing. Content: congratulations and
// welcome to Payments Training Hub, the case ID for future
// reference, and the next step (finish profile when the profile
// is incomplete, otherwise browse courses).
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography

/**
 * The registration welcome popup. Place it as an overlay on top
 * of the presenting screen: it fills the screen with a dimmed
 * scrim and a centred card. It never dismisses on its own:
 * [onDismiss] is called exactly once, when the learner presses
 * Continue or taps anywhere.
 *
 * @param caseID the signup case ID, in PF-XXXXXX form.
 * @param profileIncomplete true when the profile is incomplete, so the next step points at finishing the profile instead of browsing courses.
 */
@Composable
fun RegistrationWelcomePopup(
    caseID: String,
    profileIncomplete: Boolean,
    onDismiss: () -> Unit
) {
    var dismissed by remember { mutableStateOf(false) }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        onDismiss()
    }

    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = { dismiss() }
            )
            .testTag("welcome-popup"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(PfSpacing.xLarge)
                .widthIn(max = 340.dp)
                .shadow(20.dp, RoundedCornerShape(PfSpacing.cardRadius))
                .background(PfColors.card, RoundedCornerShape(PfSpacing.cardRadius))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() }
                )
                .padding(PfSpacing.xLarge),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = null,
                    tint = PfColors.success,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.size(PfSpacing.small))
                Text(
                    text = "Congratulations!",
                    style = PfTypography.sectionTitle.copy(fontWeight = FontWeight.Bold),
                    color = PfColors.navy
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            Text(
                text = "Welcome to Payments Training Hub.",
                style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            Text(
                text = "Your case ID is $caseID. Keep it for future reference.",
                style = PfTypography.body,
                color = PfColors.navy
            )
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            Text(
                text = if (profileIncomplete) {
                    "Next step: finish your profile."
                } else {
                    "Next step: browse courses."
                },
                style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                color = PfColors.royal
            )
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            PfPrimaryButton(
                title = "Continue",
                onClick = { dismiss() },
                modifier = Modifier.testTag("welcome-continue-button")
            )
        }
    }
}
