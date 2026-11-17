// SetNewPasswordScreen.kt
// Payments Training Hub Android - set a new password from a reset
// link. The recovery token arrives in the link fragment and is read
// once from DeepLinkRouter, then cleared so it is never reused. When
// no token is present (the screen was opened directly, or the link
// was already used), the expired-link state shows instead of the
// form. A failed update with an expired or invalid token offers a
// new link. On success the learner signs in with the new password.
// No iOS reset screen exists yet, so this layout follows the iOS
// auth-screen design language (navy headings, royal primary action)
// without an exact-match reference to verify against.
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfField
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

private enum class ResetPasswordState {
    FORM,
    LINK_EXPIRED,
    DONE
}

/**
 * Set a new password with the recovery token from a reset link.
 * [onBackToSignIn] returns to sign-in after a successful change.
 * [onRequestNewLink] opens the forgot-password screen when the link
 * has expired; when null it falls back to [onBackToSignIn].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetNewPasswordScreen(
    onBackToSignIn: () -> Unit = {},
    onRequestNewLink: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    // Read the token once, at first composition, and clear it at
    // once so a later visit to this screen can never reuse it.
    val recoveryToken = remember { DeepLinkRouter.pendingRecoveryToken.value }
    LaunchedEffect(Unit) {
        DeepLinkRouter.clearRecoveryToken()
    }

    var state by remember {
        mutableStateOf(
            if (recoveryToken.isNullOrBlank()) {
                ResetPasswordState.LINK_EXPIRED
            } else {
                ResetPasswordState.FORM
            }
        )
    }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The Save button stays tappable and submit reports what is
    // missing, with live hints under the fields: a disabled button
    // with no message left learners stuck with no explanation.
    val goToNewLink: () -> Unit = { (onRequestNewLink ?: onBackToSignIn)() }

    fun submit() {
        errorMessage = null
        val token = recoveryToken
        if (token.isNullOrBlank()) {
            state = ResetPasswordState.LINK_EXPIRED
            return
        }
        if (password.length < 8) {
            errorMessage = SignupErrorCode.WEAK_PASSWORD.message
            return
        }
        if (password != confirmPassword) {
            errorMessage = SignupErrorCode.PASSWORD_MISMATCH.message
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.updatePasswordWithRecoveryToken(token, password)
            busy = false
            result.fold(
                onSuccess = {
                    state = ResetPasswordState.DONE
                },
                onFailure = { failure ->
                    val message = failure.message ?: SignupErrorCode.UNKNOWN_ERROR.message
                    if (message == SignupErrorCode.RESET_LINK_EXPIRED.message) {
                        state = ResetPasswordState.LINK_EXPIRED
                    } else {
                        errorMessage = message
                    }
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Set a new password", color = PfColors.navy, style = PfTypography.cardTitle) },
            navigationIcon = {
                IconButton(onClick = onBackToSignIn) {
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
                when (state) {
                    ResetPasswordState.FORM -> {
                        Text(
                            text = "Choose a new password",
                            style = PfTypography.screenTitle,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            text = "Your new password must be at least 8 characters. You will sign in with it afterwards.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                        PfField(
                            label = "New password",
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "At least 8 characters",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        if (password.isNotEmpty() && password.length < 8) {
                            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                            Text(
                                text = SignupErrorCode.WEAK_PASSWORD.message,
                                style = PfTypography.caption,
                                color = PfColors.error
                            )
                        }
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfField(
                            label = "Confirm new password",
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            placeholder = "Repeat your new password",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                            Text(
                                text = SignupErrorCode.PASSWORD_MISMATCH.message,
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
                            title = if (busy) "Saving..." else "Save new password",
                            onClick = { submit() },
                            enabled = !busy
                        )
                    }
                    ResetPasswordState.LINK_EXPIRED -> {
                        Text(
                            text = "This link is no longer valid",
                            style = PfTypography.screenTitle,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            text = SignupErrorCode.RESET_LINK_EXPIRED.message,
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                        PfPrimaryButton(
                            title = "Request a new link",
                            onClick = goToNewLink
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfSecondaryButton(
                            title = "Back to sign in",
                            onClick = onBackToSignIn
                        )
                    }
                    ResetPasswordState.DONE -> {
                        Text(
                            text = "Password changed",
                            style = PfTypography.screenTitle,
                            color = PfColors.navy
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            text = "Your password has been changed. Sign in with your new password.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                        PfPrimaryButton(
                            title = "Sign in",
                            onClick = onBackToSignIn
                        )
                    }
                }

                if (state == ResetPasswordState.FORM) {
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    TextButton(onClick = onBackToSignIn, enabled = !busy) {
                        Text(
                            text = "Back to sign in",
                            style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PfColors.royal
                        )
                    }
                }
            }
        }
    }
}
