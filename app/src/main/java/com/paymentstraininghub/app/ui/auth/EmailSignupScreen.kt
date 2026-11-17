// EmailSignupScreen.kt
// Payments Training Hub Android - email signup: email and password,
// terms acceptance, then sign-in. Email confirmation verifies the
// email address; the learner opens the confirmation link sent to
// their address, or enters the 8-digit code from the email on the
// confirmation step, and then signs in. No phone number is
// collected. A signup for an address that is already registered
// shows the already-registered step, never a second "account
// created" message: GoTrue keeps the FIRST password on a re-signup,
// so the password just entered was not saved, and the screen says
// so plainly. Mirrors the iOS email signup screen layout with Pf
// components. There is no referral code entry here: referral
// discount redemption is website-only, because Google Play billing
// cannot take an external discount. The registration welcome popup
// shows once, right after the registration completes and the
// learner signs in; AuthManager owns that once-only flag. Sign-in
// is the mandatory first step: this screen appears from the sign-in
// screen when the learner asks to create an account, and the gate
// shows nothing else until a session exists. Form state survives
// rotation: the entered email, passwords, terms choice, code and
// step are saved, so a rotation never wipes the form and never
// re-sends a confirmation email.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.PasswordSignupResult
import com.paymentstraininghub.app.auth.SignInGuidanceError
import com.paymentstraininghub.app.auth.SignupError
import com.paymentstraininghub.app.auth.SignupErrorCode
import com.paymentstraininghub.app.auth.SupabaseClient
import com.paymentstraininghub.app.auth.SupabaseException
import com.paymentstraininghub.app.auth.cappedAuthCodeInput
import com.paymentstraininghub.app.auth.cappedAuthEmailInput
import com.paymentstraininghub.app.auth.cappedAuthPasswordInput
import com.paymentstraininghub.app.auth.isValidAuthEmail
import com.paymentstraininghub.app.auth.isValidEmailCode
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfField
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class EmailSignupStep {
    CREDENTIALS,
    EMAIL_CONFIRMATION,
    ALREADY_REGISTERED
}

private val emailSignupStepSaver: Saver<EmailSignupStep, String> = Saver(
    save = { it.name },
    restore = { EmailSignupStep.valueOf(it) }
)

/**
 * Email account creation. Calls [onSignedIn] once sign-in
 * completes. The registration welcome popup and the
 * guest-to-account migration offer are rendered by the
 * navigation graph, above the app, after sign-in: the gate
 * unmounts this screen the moment the auth state flips, so this
 * screen never waits for them. [onSignInInstead] is the
 * "Sign in instead" destination; when null it falls back to [onBack].
 * [onForgotPassword] is the reset-password destination from the
 * already-registered step; when null the forgot-password action
 * is hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSignupScreen(
    onSignedIn: () -> Unit = {},
    onBack: () -> Unit = {},
    onSignInInstead: (() -> Unit)? = null,
    onForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by AuthManager.authState.collectAsState()

    var step by rememberSaveable(stateSaver = emailSignupStepSaver) {
        mutableStateOf(EmailSignupStep.CREDENTIALS)
    }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var termsAccepted by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var guidanceMessage by remember { mutableStateOf<String?>(null) }
    var lastErrorCode by remember { mutableStateOf<SignupErrorCode?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var pendingCompletion by remember { mutableStateOf(false) }
    var completionChecked by remember { mutableStateOf(false) }
    var alreadyRegisteredConfirmed by remember { mutableStateOf(false) }

    // Validation is shown, never silently enforced by a disabled
    // button: the Create button stays tappable and createAccount
    // reports exactly what is missing, and live hints under each
    // field explain the rule as the learner types. A disabled
    // button with no message left learners stuck with no
    // explanation (a 7 character password greyed the button with
    // nothing on screen saying why).
    val signedIn = authState is AuthManager.AuthState.SignedIn

    // The completion guard resets when sign-in drops: an auth
    // flap after completion was checked would otherwise strand
    // the screen, because the completion effect below only fires
    // while the guard is false and sign-in is back.
    LaunchedEffect(signedIn) {
        if (!signedIn) completionChecked = false
    }

    // Report completion once sign-in lands. The welcome popup
    // and the migration offer are rendered by the navigation
    // graph, above the app, so this screen does not wait for
    // them: the gate unmounts this screen the moment the auth
    // state flips.
    LaunchedEffect(pendingCompletion, signedIn, completionChecked) {
        if (pendingCompletion && signedIn && !completionChecked) {
            completionChecked = true
            onSignedIn()
        }
    }

    fun startResendCooldown() {
        if (cooldownSeconds > 0) return
        scope.launch {
            cooldownSeconds = 30
            while (cooldownSeconds > 0) {
                delay(1000L)
                cooldownSeconds--
            }
        }
    }

    fun createAccount() {
        // Double-fire guard (Tester 2 E11): a second tap before
        // recomposition disables the button must not start a
        // second account creation.
        if (busy) return
        errorMessage = null
        guidanceMessage = null
        lastErrorCode = null
        val cleanEmail = email.trim()
        if (!isValidAuthEmail(cleanEmail.lowercase())) {
            errorMessage = "Enter a valid email address."
            return
        }
        val passwordError = signupPasswordError(password, confirmPassword)
        if (passwordError != null) {
            lastErrorCode = passwordError
            errorMessage = passwordError.message
            return
        }
        if (!termsAccepted) {
            lastErrorCode = SignupErrorCode.TERMS_NOT_ACCEPTED
            errorMessage = SignupErrorCode.TERMS_NOT_ACCEPTED.message
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.signUpWithPassword(
                email = email,
                password = password,
                termsAccepted = termsAccepted
            )
            busy = false
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        PasswordSignupResult.SignedIn -> {
                            infoMessage = "Signed in."
                            pendingCompletion = true
                        }
                        PasswordSignupResult.NeedsEmailConfirmation -> {
                            infoMessage = null
                            step = EmailSignupStep.EMAIL_CONFIRMATION
                        }
                        is PasswordSignupResult.AlreadyRegistered -> {
                            infoMessage = null
                            alreadyRegisteredConfirmed = outcome.emailConfirmed
                            step = EmailSignupStep.ALREADY_REGISTERED
                        }
                    }
                },
                onFailure = { failure ->
                    val message = failure.message ?: SignupErrorCode.UNKNOWN_ERROR.message
                    errorMessage = message
                    lastErrorCode = when (message) {
                        SignupErrorCode.EMAIL_ALREADY_REGISTERED.message ->
                            SignupErrorCode.EMAIL_ALREADY_REGISTERED
                        SignupErrorCode.TERMS_NOT_ACCEPTED.message ->
                            SignupErrorCode.TERMS_NOT_ACCEPTED
                        SignupErrorCode.WEAK_PASSWORD.message ->
                            SignupErrorCode.WEAK_PASSWORD
                        else -> null
                    }
                }
            )
        }
    }

    fun resendConfirmationEmail() {
        if (resending || busy || verifying || cooldownSeconds > 0) return
        errorMessage = null
        guidanceMessage = null
        lastErrorCode = null
        infoMessage = null
        resending = true
        scope.launch {
            try {
                // The learner is not signed in yet, so the resend
                // goes through SupabaseClient directly with the
                // address entered on this screen.
                SupabaseClient.resendVerificationEmail(email.trim())
                infoMessage = "A new confirmation email is on its way to ${email.trim()}. Open the link in it to verify your email address, or enter the 8-digit code from it below."
                startResendCooldown()
            } catch (e: IOException) {
                lastErrorCode = SignupErrorCode.NETWORK_ERROR
                errorMessage = SignupErrorCode.NETWORK_ERROR.message
            } catch (e: SupabaseException) {
                val catalogError = e.serverMessage?.let { SignupError.fromGoTrueMessage(it) }
                lastErrorCode = catalogError?.code
                errorMessage = catalogError?.displayMessage ?: SignupErrorCode.UNKNOWN_ERROR.message
            } catch (_: Exception) {
                lastErrorCode = SignupErrorCode.UNKNOWN_ERROR
                errorMessage = SignupErrorCode.UNKNOWN_ERROR.message
            } finally {
                resending = false
            }
        }
    }

    fun verifyCode() {
        if (verifying || busy) return
        errorMessage = null
        guidanceMessage = null
        lastErrorCode = null
        if (!isValidEmailCode(code)) {
            errorMessage = "Enter the 8-digit code from the email."
            return
        }
        verifying = true
        scope.launch {
            val result = AuthManager.verifyEmailCode(email, code)
            verifying = false
            result.fold(
                onSuccess = {
                    // A verified code signs straight in: the
                    // learner never returns to a "verify first"
                    // state after a successful verification.
                    infoMessage = "Signed in."
                    pendingCompletion = true
                },
                onFailure = { failure ->
                    errorMessage = failure.message ?: SignupErrorCode.UNKNOWN_ERROR.message
                }
            )
        }
    }

    fun signInAfterConfirmation() {
        if (busy || verifying) return
        errorMessage = null
        guidanceMessage = null
        lastErrorCode = null
        busy = true
        scope.launch {
            val result = AuthManager.signInEmail(email, password)
            busy = false
            result.fold(
                onSuccess = {
                    infoMessage = "Signed in."
                    pendingCompletion = true
                },
                onFailure = { failure ->
                    errorMessage = failure.message ?: SignupErrorCode.UNKNOWN_ERROR.message
                    // The guiding text names the unconfirmed and
                    // no-password cases: a bare "does not match"
                    // here stranded learners whose account was
                    // never confirmed, or who signed up twice and
                    // typed the second password.
                    guidanceMessage = (failure as? SignInGuidanceError)?.guidance
                }
            )
        }
    }

    val goToSignIn: () -> Unit = { (onSignInInstead ?: onBack)() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Create account", color = PfColors.navy, style = PfTypography.cardTitle) },
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
                    text = "Create your account",
                    style = PfTypography.screenTitle,
                    color = PfColors.navy
                )
                Spacer(modifier = Modifier.height(PfSpacing.small))
                Text(
                    text = "Set your email and password. We will send a confirmation email to verify your address.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
                Spacer(modifier = Modifier.height(PfSpacing.xLarge))

                when (step) {
                    EmailSignupStep.CREDENTIALS -> {
                        PfField(
                            label = "Email address",
                            value = email,
                            onValueChange = { email = cappedAuthEmailInput(it) },
                            placeholder = "you@example.com",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.testTag("signup-email-field")
                        )
                        if (email.isNotEmpty() && !isValidAuthEmail(email.trim().lowercase())) {
                            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                            Text(
                                text = "Enter a valid email address.",
                                style = PfTypography.caption,
                                color = PfColors.error
                            )
                        }
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = cappedAuthPasswordInput(it) },
                            placeholder = "At least 8 characters",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.testTag("signup-password-field")
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
                            label = "Confirm password",
                            value = confirmPassword,
                            onValueChange = { confirmPassword = cappedAuthPasswordInput(it) },
                            placeholder = "Repeat your password",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.testTag("signup-confirm-password-field")
                        )
                        if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                            Spacer(modifier = Modifier.height(PfSpacing.xSmall))
                            Text(
                                text = SignupErrorCode.PASSWORD_MISMATCH.message,
                                style = PfTypography.caption,
                                color = PfColors.error
                            )
                        }
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = termsAccepted,
                                onCheckedChange = { termsAccepted = it },
                                enabled = !busy,
                                modifier = Modifier.testTag("signup-terms-checkbox"),
                                colors = CheckboxDefaults.colors(
                                    checkedColor = PfColors.royal,
                                    uncheckedColor = PfColors.secondaryText,
                                    checkmarkColor = PfColors.card
                                )
                            )
                            Row(
                                modifier = Modifier.padding(top = PfSpacing.medium),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "I accept the ",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                                Text(
                                    text = "Terms",
                                    style = PfTypography.body.copy(
                                        color = PfColors.royal,
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    modifier = Modifier.clickable {
                                        openUrlInBrowser(context, AuthLinks.TERMS_URL)
                                    }
                                )
                                Text(
                                    text = " and ",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                                Text(
                                    text = "Privacy Policy",
                                    style = PfTypography.body.copy(
                                        color = PfColors.royal,
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    modifier = Modifier.clickable {
                                        openUrlInBrowser(context, AuthLinks.PRIVACY_URL)
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfPrimaryButton(
                            title = if (busy) "Creating..." else "Create account and sign in",
                            onClick = { createAccount() },
                            enabled = !busy,
                            modifier = Modifier.testTag("signup-create-button")
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        Text(
                            text = "Have a referral code? Referral discounts are applied on the website. In-app purchases use Google Play billing, which cannot apply an external discount.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            text = "One account per email address. Your email is verified by a confirmation email.",
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                    }
                    EmailSignupStep.EMAIL_CONFIRMATION -> {
                        EmailConfirmationStep(
                            email = email.trim(),
                            code = code,
                            onCodeChange = { code = cappedAuthCodeInput(it) },
                            verifying = verifying,
                            busy = busy,
                            resending = resending,
                            cooldownSeconds = cooldownSeconds,
                            onVerifyCode = { verifyCode() },
                            onSignInWithPassword = { signInAfterConfirmation() },
                            onResend = { resendConfirmationEmail() }
                        )
                    }
                    EmailSignupStep.ALREADY_REGISTERED -> {
                        Text(
                            text = SignupErrorCode.EMAIL_ALREADY_REGISTERED.message,
                            style = PfTypography.body,
                            color = PfColors.navy,
                            modifier = Modifier.testTag("signup-already-registered")
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        Text(
                            text = if (alreadyRegisteredConfirmed) {
                                "Your original password still applies. The password you just entered was not saved. Sign in with your original password, or reset it if you forgot it."
                            } else {
                                "This email is registered but not confirmed yet. The password you just entered was not saved. Resend the confirmation email, then sign in with your original password after confirming."
                            },
                            style = PfTypography.body,
                            color = PfColors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(PfSpacing.medium))
                        PfPrimaryButton(
                            title = "Sign in",
                            onClick = goToSignIn,
                            modifier = Modifier.testTag("signup-already-sign-in-button")
                        )
                        if (!alreadyRegisteredConfirmed) {
                            Spacer(modifier = Modifier.height(PfSpacing.medium))
                            PfSecondaryButton(
                                title = if (resending) {
                                    "Sending..."
                                } else if (cooldownSeconds > 0) {
                                    "Resend in $cooldownSeconds seconds"
                                } else {
                                    "Resend confirmation email"
                                },
                                onClick = { resendConfirmationEmail() },
                                enabled = !resending && !busy && cooldownSeconds <= 0,
                                modifier = Modifier.testTag("signup-already-resend-button")
                            )
                        }
                        onForgotPassword?.let { goToForgotPassword ->
                            Spacer(modifier = Modifier.height(PfSpacing.small))
                            TextButton(
                                onClick = goToForgotPassword,
                                enabled = !busy,
                                modifier = Modifier.testTag("signup-already-forgot-button")
                            ) {
                                Text(
                                    text = "Forgot password?",
                                    style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = PfColors.royal
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(PfSpacing.small))
                        TextButton(
                            onClick = {
                                errorMessage = null
                                guidanceMessage = null
                                lastErrorCode = null
                                infoMessage = null
                                step = EmailSignupStep.CREDENTIALS
                            },
                            enabled = !busy
                        ) {
                            Text(
                                text = "Use a different email",
                                style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                color = PfColors.royal
                            )
                        }
                    }
                }

                infoMessage?.let { message ->
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    Text(
                        text = message,
                        style = PfTypography.body,
                        color = PfColors.secondaryText
                    )
                }
                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                    Text(
                        text = message,
                        style = PfTypography.body,
                        color = PfColors.error,
                        modifier = Modifier.testTag("signup-error-text")
                    )
                }
                guidanceMessage?.let { message ->
                    Spacer(modifier = Modifier.height(PfSpacing.small))
                    Text(
                        text = message,
                        style = PfTypography.body,
                        color = PfColors.secondaryText,
                        modifier = Modifier.testTag("signup-guidance-text")
                    )
                }
                if (lastErrorCode == SignupErrorCode.EMAIL_ALREADY_REGISTERED) {
                    Spacer(modifier = Modifier.height(PfSpacing.medium))
                    PfSecondaryButton(title = "Sign in instead", onClick = goToSignIn)
                }

                Spacer(modifier = Modifier.height(PfSpacing.xLarge))
                Text(
                    text = "Already have an account?",
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

/**
 * The "Check your email" step: the emailed link stays an option,
 * and the 8-digit code from the same email can be entered here
 * instead. A verified code signs straight in. Extracted so the
 * step is harness-tested on its own: reaching it through the
 * screen needs a successful signup network call.
 */
@Composable
internal fun EmailConfirmationStep(
    email: String,
    code: String,
    onCodeChange: (String) -> Unit,
    verifying: Boolean,
    busy: Boolean,
    resending: Boolean,
    cooldownSeconds: Int,
    onVerifyCode: () -> Unit,
    onSignInWithPassword: () -> Unit,
    onResend: () -> Unit
) {
    Text(
        text = "Check your email",
        style = PfTypography.captionBold,
        color = PfColors.secondaryText
    )
    Spacer(modifier = Modifier.height(PfSpacing.small))
    Text(
        text = "Your account is created. Open the confirmation link sent to $email to verify your email address, or enter the 8-digit code from that email below.",
        style = PfTypography.body,
        color = PfColors.secondaryText
    )
    Spacer(modifier = Modifier.height(PfSpacing.medium))
    PfField(
        label = "8-digit code",
        value = code,
        onValueChange = onCodeChange,
        placeholder = "12345678",
        enabled = !busy && !verifying,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.testTag("signup-code-field")
    )
    Spacer(modifier = Modifier.height(PfSpacing.medium))
    PfPrimaryButton(
        title = if (verifying) "Verifying..." else "Verify code and sign in",
        onClick = onVerifyCode,
        enabled = !verifying && !busy,
        modifier = Modifier.testTag("signup-verify-code-button")
    )
    Spacer(modifier = Modifier.height(PfSpacing.medium))
    PfSecondaryButton(
        title = if (busy) "Signing in..." else "Sign in with password",
        onClick = onSignInWithPassword,
        enabled = !busy && !resending && !verifying,
        modifier = Modifier.testTag("signup-sign-in-button")
    )
    Spacer(modifier = Modifier.height(PfSpacing.medium))
    PfSecondaryButton(
        title = if (resending) {
            "Sending..."
        } else if (cooldownSeconds > 0) {
            "Resend in $cooldownSeconds seconds"
        } else {
            "Resend confirmation email"
        },
        onClick = onResend,
        enabled = !resending && !busy && !verifying && cooldownSeconds <= 0,
        modifier = Modifier.testTag("signup-resend-button")
    )
    Spacer(modifier = Modifier.height(PfSpacing.small))
    Text(
        text = "If the email does not arrive, check your spam folder.",
        style = PfTypography.body,
        color = PfColors.secondaryText
    )
}

private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        // No browser available. The link stays visible on screen.
    }
}

/**
 * The password validation for account creation, in the order the
 * screen reports it: a password shorter than 8 characters is weak,
 * and anything else that differs from the confirmation, including
 * an empty confirmation, is a mismatch. Passwords are compared
 * exactly as typed, never trimmed: a leading or trailing space is
 * part of the password, and trimming it here would create an
 * account whose password the learner cannot reproduce. Null means
 * the pair is acceptable. Pure, so the trimming, empty-value and
 * mismatch behaviour is pinned by unit tests.
 */
internal fun signupPasswordError(password: String, confirmPassword: String): SignupErrorCode? {
    if (password.length < 8) return SignupErrorCode.WEAK_PASSWORD
    if (password != confirmPassword) return SignupErrorCode.PASSWORD_MISMATCH
    return null
}
