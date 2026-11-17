package com.paymentstraininghub.app.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfTypography

/**
 * The guest to account migration choice, shown after a fresh sign in
 * when guest progress exists and the offer was never resolved for
 * this account. Accepting or declining both resolve the offer: it is
 * made at most once per account. Dismissing the dialog counts as
 * declining. Migration copies, it does not move: the guest copy is
 * left in place, so signing out still shows the same guest data.
 * Copy and styling mirror the iOS migration alert in WelcomeView,
 * EmailAuthView and the email signup view.
 */
@Composable
fun MigrationOfferDialog(
    guestHasProgress: Boolean,
    onMigrate: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = PfColors.card,
        titleContentColor = PfColors.navy,
        textContentColor = PfColors.secondaryText,
        title = {
            Text(
                text = "Move your guest progress to your account?",
                style = PfTypography.cardTitle,
                color = PfColors.navy
            )
        },
        text = {
            Text(
                text = if (guestHasProgress) {
                    "You learned as a guest on this device. Move that progress, " +
                        "notes and bookmarks to your account? Anything already in " +
                        "your account is kept, and nothing is overwritten."
                } else {
                    "You have no guest progress on this device. " +
                        "You can start fresh in your account."
                },
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        },
        confirmButton = {
            TextButton(onClick = onMigrate) {
                Text("Move my progress", color = PfColors.royal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Not now", color = PfColors.royal)
            }
        }
    )
}

/**
 * The once per account migration offer marker, mirroring the iOS
 * resolved marker stored in the account namespace. The flag base key
 * is "migration.offered.<userId>" and it is stored through AppStore,
 * so it lands in the current (account) namespace. Callers must check
 * and mark while the account namespace is current, right after sign
 * in. Never offered account to account: the source is always the
 * guest namespace.
 */
object MigrationTracker {

    private fun flagBaseKey(userId: String): String = "migration.offered.$userId"

    suspend fun shouldOffer(userId: String): Boolean {
        if (userId.isBlank()) return false
        return try {
            AppStore.getBoolean(flagBaseKey(userId)) != true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun markOffered(userId: String) {
        if (userId.isBlank()) return
        try {
            AppStore.putBoolean(flagBaseKey(userId), true)
        } catch (_: Exception) {
            // A missed marker only means the offer may show again
            // on a later sign in. It never blocks sign in.
        }
    }
}
