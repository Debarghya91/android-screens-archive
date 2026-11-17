// ForgotPasswordScreen.kt
// Payments Training Hub Android - "Forgot password?" screen. The
// learner enters an email address and a reset link is sent. The
// screen always shows the same generic success message whether or
// not an account exists for the address, so it never reveals
// whether an address has an account. Only validation and network
// failures show a different message, through the catalog. Mirrors
// the iOS auth-screen layout with Pf components: navy headings,
// royal primary action, gold used sparingly (not at all here).
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfField
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

/**
 * Request a password reset link. [onSignIn] returns to sign-in;
 * when null it falls back to [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit = {},
    onSignIn: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var linkSent by remember { mutableStateOf(false) }

    val goToSignIn: () -> Unit = { (onSignIn ?: onBack)() }

    fun submit() {
        errorMessage = null
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            errorMessage = "Enter a valid email address."
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.sendPasswordResetEmail(cleanEmail)
            busy = false
            result.fold(
                onSuccess = {
                    // The same message for every accepted request:
                    // it never says whether an account exists.
                    linkSent = true
                },
                onFailure = { failure ->
                    errorMessage = failure.message ?: SignupErrorCode.UNKNOWN_ERROR.message
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Forgot password?", color = PfColors.navy, style = PfTypography.cardTitle) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                navigationIconContentColor = PfColors.navy
            )
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PfColors.background)
                    .verticalScroll(rememberTopScrollState())
                    .padding(PfSpacing.xLarge)
            ) {
                Text(
                    text = "Reset your password",
                    style = PfTypography.screenTitle,
                    color = PfColors.navy
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                if (linkSent) {
                    Text(
                        text = "If an account exists for that email, a reset link is on its way.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    Text(
                        text = "Open the link in the email to set a new password. If the email does not arrive, check your spam folder.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    PfPrimaryButton(
                        title = "Back to sign in",
                        onClick = goToSignIn
                    )
                } else {
                    Text(
                        text = "Enter the email address for your account. We will send a link to set a new password.",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    PfField(
                        label = "Email address",
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "you@example.com",
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    if (email.isNotEmpty() && !email.trim().contains("@")) {
                        Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                        Text(
                            text = "Enter a valid email address.",
                            style = PfTypography.caption,
                            color = PfColors.error
                        )
                    }
                    errorMessage?.let { message ->
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        Text(
                            text = message,
                            style = PfTypography.body,
                            color = PfColors.error
                        )
                    }
                    Spacer(modifier = Modifier.height(PfSpacing.large))
                    PfPrimaryButton(
                        title = if (busy) "Sending..." else "Send reset link",
                        onClick = { submit() },
                        enabled = !busy
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    Text(
                        text = "Remembered your password?",
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    TextButton(onClick = goToSignIn, enabled = !busy) {
                        Text(
                            text = "Sign in",
                            style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PfColors.royal
                        )
                    }
                }
            }
        }
    }
}
