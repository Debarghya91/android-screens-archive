// Analytics consent dialog. Opt-in, default off. Granting consent starts
// recording; declining or withdrawing stops recording and deletes buffered
// events. No event leaves this device in this build. No em dashes.
package com.paymentstraininghub.app.features.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun AnalyticsConsentDialog(
    isConsented: Boolean,
    hasDecided: Boolean,
    onGrant: () -> Unit,
    onDecline: () -> Unit,
    onWithdraw: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analytics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Analytics is optional and off by default. If you turn it on, the app " +
                        "records anonymous learning events on this device only, such as lessons " +
                        "started and completed, quiz scores, and challenge results."
                )
                Text(
                    "No event leaves this device in this build. No name, email, account " +
                        "identifier, question text, answer text, search words, payment detail, " +
                        "or device identifier is ever recorded."
                )
                Text(
                    if (isConsented) {
                        "Analytics is currently on. You can withdraw consent at any time; " +
                            "withdrawing deletes the buffered events."
                    } else if (hasDecided) {
                        "Analytics is currently off."
                    } else {
                        "You have not decided yet. Analytics stays off until you turn it on."
                    }
                )
            }
        },
        confirmButton = {
            if (isConsented) {
                TextButton(onClick = onWithdraw) { Text("Withdraw consent") }
            } else {
                TextButton(onClick = onGrant) { Text("Turn analytics on") }
            }
        },
        dismissButton = {
            if (isConsented) {
                TextButton(onClick = onDismiss) { Text("Keep it on") }
            } else {
                TextButton(onClick = onDecline) { Text("Keep it off") }
            }
        }
    )
}
