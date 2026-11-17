// AppearanceScreen.kt
// Appearance settings, matching the iOS AppearanceSettingsView:
// a Display section with Dark mode and Larger text toggles, and a
// Colour section naming the royal blue and gold theme. Dark mode
// changes the Material colour scheme (system surfaces, dialogs,
// switches); the pf-painted cards and lesson pages stay light, so
// text stays readable, exactly as on iOS. Larger text scales the
// font size across the app.

package com.paymentstraininghub.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val darkMode by AppearanceStore.darkMode.collectAsState()
    val largerText by AppearanceStore.largerText.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Appearance", color = PfColors.navy) },
            actions = {
                TextButton(onClick = onBack) {
                    Text("Done", color = PfColors.royal)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                actionIconContentColor = PfColors.navy
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            Text(
                "Display",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column {
                    AppearanceToggleRow(
                        title = "Dark mode",
                        checked = darkMode,
                        onCheckedChange = {
                            AppearanceStore.setDarkMode(context, it)
                        }
                    )
                    AppearanceToggleRow(
                        title = "Larger text",
                        checked = largerText,
                        onCheckedChange = {
                            AppearanceStore.setLargerText(context, it)
                        }
                    )
                }
            }
            Text(
                "Dark mode changes the system appearance. Cards and lesson pages stay light so text stays readable. Larger text increases the text size across the app. It takes effect when a screen is next opened.",
                style = PfTextStyles.caption,
                color = PfColors.secondaryText
            )
            Text(
                "Colour",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Theme",
                        style = PfTextStyles.body,
                        fontWeight = FontWeight.SemiBold,
                        color = PfColors.navy
                    )
                    Text(
                        "Royal blue and gold",
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                }
            }
            Text(
                "Payments Training Hub uses royal blue as the primary colour, with gold for the main call to action.",
                style = PfTextStyles.caption,
                color = PfColors.secondaryText
            )
        }
    }
}

@Composable
private fun AppearanceToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PfSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = PfTextStyles.body,
            fontWeight = FontWeight.SemiBold,
            color = PfColors.navy
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
