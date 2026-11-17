// AuthScreen.kt
// Payments Training Hub Android - sign-in screen. It opens when
// the learner taps a Buy action while signed out, or chooses to
// sign in from the Profile screen. Content stays browsable
// without a session, so this screen never blocks the app: after
// sign-in the learner returns to the purchase, or to the screen
// they came from. The layout
// is a centred column: the wordmark with the open-book icon, one
// card holding a single form, and the create-account row below
// the card. The password form shows first; the emailed-code form
// appears only behind a tap, and only one form is ever visible.
// "Create account" opens the email signup screen (through
// [onCreateAccount] when the navigator supplies it), and
// "Forgot password?" opens the reset flow. "Continue with Google"
// opens the Supabase OAuth flow in the browser and returns
// through the auth-callback deep link; the screen consumes the
// callback code exactly once and exchanges it for a session.
// Form state survives rotation: email, password, code, the
// visible form and the code-sent flag are saved, so a rotation
// never wipes what the learner typed and never re-sends a code.
//
// The guest-to-account migration offer and the registration
// welcome popup used to live here; both moved to the navigation
// graph, which renders them above the app after sign-in, because
// the gate unmounts this screen the moment the auth state flips.
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.SignInGuidanceError
import com.paymentstraininghub.app.auth.cappedAuthCodeInput
import com.paymentstraininghub.app.auth.cappedAuthEmailInput
import com.paymentstraininghub.app.auth.cappedAuthPasswordInput
import com.paymentstraininghub.app.auth.isValidAuthEmail
import com.paymentstraininghub.app.auth.isValidEmailCode
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfField
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which single form the card shows: password sign-in, or the emailed code. */
private enum class AuthForm { PASSWORD, CODE }

private val authFormSaver: Saver<AuthForm, String> = Saver(
    save = { it.name },
    restore = { AuthForm.valueOf(it) }
)

/** An OAuth callback older than this is stale and is ignored, so an old code never fires on a later sign-in. */
private const val OAUTH_CALLBACK_MAX_AGE_MS = 10 * 60 * 1000L

@Composable
fun AuthScreen(
    onSignedIn: () -> Unit = {},
    onCreateAccount: (() -> Unit)? = null,
    onForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by AuthManager.authState.collectAsState()
    val pendingOAuthCode by DeepLinkRouter.pendingOAuthCode.collectAsState()
    val pendingOAuthError by DeepLinkRouter.pendingOAuthError.collectAsState()

    var form by rememberSaveable(stateSaver = authFormSaver) { mutableStateOf(AuthForm.PASSWORD) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var guidanceText by remember { mutableStateOf<String?>(null) }
    var showGuidanceActions by remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    var resendCooldownSeconds by remember { mutableIntStateOf(0) }
    var completingGoogle by remember { mutableStateOf(false) }

    fun clearMessages() {
        errorText = null
        guidanceText = null
        showGuidanceActions = false
        infoText = null
    }

    fun afterSignIn() {
        // The migration offer and the registration welcome popup
        // are rendered by the navigation graph, above the app.
        onSignedIn()
    }

    // Consume an OAuth callback exactly once. The router state is
    // cleared before the exchange starts, so a recomposition or a
    // rotation can never exchange the same code twice. A stale
    // callback is dropped silently: the code is single-use and
    // short-lived, so an old one can never succeed.
    LaunchedEffect(pendingOAuthCode, pendingOAuthError) {
        if (pendingOAuthCode == null && pendingOAuthError == null) return@LaunchedEffect
        if (DeepLinkRouter.pendingOAuthAgeMs(System.currentTimeMillis()) > OAUTH_CALLBACK_MAX_AGE_MS) {
            DeepLinkRouter.clearOAuthCallback()
            return@LaunchedEffect
        }
        val codeFromLink = pendingOAuthCode
        val errorFromLink = pendingOAuthError
        DeepLinkRouter.clearOAuthCallback()
        if (completingGoogle) return@LaunchedEffect
        if (errorFromLink != null || codeFromLink.isNullOrBlank()) {
            clearMessages()
            errorText = "Google sign-in did not complete. Try again, or sign in with email."
            return@LaunchedEffect
        }
        clearMessages()
        completingGoogle = true
        infoText = "Completing Google sign-in..."
        val result = AuthManager.completeGoogleSignIn(codeFromLink)
        completingGoogle = false
        result.fold(
            onSuccess = { afterSignIn() },
            onFailure = { failure ->
                errorText = failure.message ?: "Something went wrong. Please try again."
                infoText = null
            }
        )
    }

    fun openCreateAccount() {
        // The navigator must supply the email signup destination;
        // there is no inline signup fallback on this screen.
        onCreateAccount?.invoke()
    }

    fun submitEmail() {
        // Double-fire guard (Tester 2 E11): a second tap before
        // recomposition replaces the button with the spinner must
        // not start a second sign-in.
        if (busy) return
        clearMessages()
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) {
            errorText = "Enter your email address."
            return
        }
        if (!isValidAuthEmail(cleanEmail.lowercase())) {
            errorText = "Enter a valid email address."
            return
        }
        if (password.isEmpty()) {
            errorText = "Enter your password."
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.signInEmail(email, password)
            busy = false
            result.fold(
                onSuccess = { afterSignIn() },
                onFailure = { failure ->
                    errorText = failure.message ?: "Something went wrong. Please try again."
                    val guided = failure as? SignInGuidanceError
                    guidanceText = guided?.guidance
                    showGuidanceActions = guided != null
                }
            )
        }
    }

    fun startResendCooldown() {
        // One countdown at a time: a second start while one is
        // running would double-decrement and end it early.
        if (resendCooldownSeconds > 0) return
        scope.launch {
            resendCooldownSeconds = 30
            while (resendCooldownSeconds > 0) {
                delay(1000L)
                resendCooldownSeconds--
            }
        }
    }

    fun sendCode() {
        if (busy) return
        clearMessages()
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) {
            errorText = "Enter your email address."
            return
        }
        if (!isValidAuthEmail(cleanEmail.lowercase())) {
            errorText = "Enter a valid email address."
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.sendEmailCode(email)
            busy = false
            result.fold(
                onSuccess = {
                    codeSent = true
                    infoText = "We emailed an 8-digit code to $cleanEmail. It expires after one hour."
                    startResendCooldown()
                },
                onFailure = { failure ->
                    errorText = failure.message ?: "Something went wrong. Please try again."
                }
            )
        }
    }

    fun submitCode() {
        if (busy) return
        clearMessages()
        val cleanEmail = email.trim()
        if (!isValidAuthEmail(cleanEmail.lowercase())) {
            errorText = "Enter a valid email address."
            return
        }
        if (!isValidEmailCode(code)) {
            errorText = "Enter the 8-digit code from the email."
            return
        }
        busy = true
        scope.launch {
            val result = AuthManager.verifyEmailCode(email, code)
            busy = false
            result.fold(
                onSuccess = { afterSignIn() },
                onFailure = { failure ->
                    errorText = failure.message ?: "Something went wrong. Please try again."
                }
            )
        }
    }

    fun resendConfirmation() {
        if (resending || busy || resendCooldownSeconds > 0) return
        clearMessages()
        val cleanEmail = email.trim()
        if (!isValidAuthEmail(cleanEmail.lowercase())) {
            errorText = "Enter a valid email address."
            return
        }
        resending = true
        scope.launch {
            val result = AuthManager.resendConfirmationEmail(email)
            resending = false
            result.fold(
                onSuccess = {
                    infoText = "A new confirmation email is on its way to $cleanEmail. " +
                        "Open the link in it to verify your email address."
                    startResendCooldown()
                },
                onFailure = { failure ->
                    errorText = failure.message ?: "Something went wrong. Please try again."
                }
            )
        }
    }

    fun startGoogleSignIn() {
        if (busy || completingGoogle) return
        clearMessages()
        val url = AuthManager.beginGoogleSignIn()
        infoText = "Opening Google sign-in in your browser. Return here after signing in."
        openUrlInBrowser(context, url)
    }

    fun switchToCodeForm() {
        clearMessages()
        code = ""
        codeSent = false
        form = AuthForm.CODE
    }

    fun switchToPasswordForm() {
        clearMessages()
        form = AuthForm.PASSWORD
    }

    val signedInState = authState as? AuthManager.AuthState.SignedIn
    if (signedInState != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PfColors.background)
                    .padding(PfSpacing.xLarge),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "You are signed in as ${signedInState.email}",
                    style = PfTypography.body,
                    color = PfColors.navy,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(PfSpacing.large))
                PfPrimaryButton(title = "Continue", onClick = onSignedIn)
                Spacer(modifier = Modifier.height(PfSpacing.small))
                TextButton(
                    onClick = {
                        scope.launch { AuthManager.signOut() }
                    }
                ) {
                    Text("Sign out", color = PfColors.royal)
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PfColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.xLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(PfSpacing.xxLarge))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(PfSpacing.cornerRadius))
                    .background(PfColors.royal.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MenuBook,
                    contentDescription = "Open book",
                    tint = PfColors.royal,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            Text(
                text = "Payments Training Hub",
                style = PfTypography.sectionTitle.copy(fontWeight = FontWeight.Bold),
                color = PfColors.navy,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(PfSpacing.xLarge))
            PfCard {
                when (form) {
                    AuthForm.PASSWORD -> {
                        PfField(
                            label = "Email address",
                            value = email,
                            onValueChange = { email = cappedAuthEmailInput(it) },
                            placeholder = "you@example.com",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.testTag("auth-email-field")
                        )
                        if (email.isNotEmpty() && !isValidAuthEmail(email.trim().lowercase())) {
                            Text(
                                text = "Enter a valid email address.",
                                style = PfTypography.caption,
                                color = PfColors.error
                            )
                        }
                        PfField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = cappedAuthPasswordInput(it) },
                            placeholder = "Your password",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.testTag("auth-password-field")
                        )
                        errorText?.let { message ->
                            Text(
                                text = message,
                                color = PfColors.error,
                                style = PfTypography.body,
                                modifier = Modifier.testTag("auth-error-text")
                            )
                        }
                        guidanceText?.let { guidance ->
                            Text(
                                text = guidance,
                                color = PfColors.secondaryText,
                                style = PfTypography.body,
                                modifier = Modifier.testTag("auth-guidance-text")
                            )
                        }
                        if (showGuidanceActions) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                    PfSpacing.small
                                )
                            ) {
                                TextButton(
                                    onClick = { switchToCodeForm() },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("auth-guidance-code-button")
                                ) {
                                    Text(
                                        text = "Use an emailed code",
                                        color = PfColors.royal,
                                        style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                TextButton(
                                    onClick = { resendConfirmation() },
                                    enabled = !busy && !resending && resendCooldownSeconds <= 0,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("auth-guidance-resend-button")
                                ) {
                                    Text(
                                        text = if (resendCooldownSeconds > 0) {
                                            "Resend in $resendCooldownSeconds seconds"
                                        } else {
                                            "Resend confirmation email"
                                        },
                                        color = PfColors.royal,
                                        style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        infoText?.let { info ->
                            Text(
                                text = info,
                                color = PfColors.secondaryText,
                                style = PfTypography.body
                            )
                        }
                        if (busy) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = PfColors.royal)
                            }
                        } else {
                            PfPrimaryButton(
                                title = "Sign in",
                                onClick = { submitEmail() },
                                modifier = Modifier.testTag("auth-sign-in-button")
                            )
                        }
                        onForgotPassword?.let { goToForgotPassword ->
                            TextButton(
                                onClick = goToForgotPassword,
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth-forgot-password-button")
                            ) {
                                Text(
                                    text = "Forgot password?",
                                    color = PfColors.royal,
                                    style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                        OrDivider()
                        PfSecondaryButton(
                            title = "Use an emailed code instead",
                            onClick = { switchToCodeForm() },
                            enabled = !busy,
                            modifier = Modifier.testTag("auth-email-code-button")
                        )
                        PfSecondaryButton(
                            title = "Continue with Google",
                            onClick = { startGoogleSignIn() },
                            enabled = !busy && !completingGoogle,
                            modifier = Modifier.testTag("auth-google-button")
                        )
                        if (completingGoogle) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = PfColors.royal)
                            }
                        }
                    }
                    AuthForm.CODE -> {
                        PfField(
                            label = "Email address",
                            value = email,
                            onValueChange = {
                                email = cappedAuthEmailInput(it)
                                // A changed address invalidates the
                                // code already sent: the learner
                                // requests a fresh one for it.
                                if (codeSent) {
                                    codeSent = false
                                    code = ""
                                    infoText = null
                                }
                            },
                            placeholder = "you@example.com",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.testTag("auth-email-field")
                        )
                        if (!codeSent) {
                            Text(
                                text = "We will email you an 8-digit code. Enter it below to sign in. " +
                                    "The code expires after one hour.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText
                            )
                        }
                        if (codeSent) {
                            infoText?.let { info ->
                                Text(
                                    text = info,
                                    color = PfColors.secondaryText,
                                    style = PfTypography.body
                                )
                            }
                            PfField(
                                label = "8-digit code",
                                value = code,
                                onValueChange = { code = cappedAuthCodeInput(it) },
                                placeholder = "12345678",
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.testTag("auth-code-field")
                            )
                        }
                        errorText?.let { message ->
                            Text(
                                text = message,
                                color = PfColors.error,
                                style = PfTypography.body,
                                modifier = Modifier.testTag("auth-error-text")
                            )
                        }
                        if (busy) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = PfColors.royal)
                            }
                        } else if (!codeSent) {
                            PfPrimaryButton(
                                title = "Email me a code",
                                onClick = { sendCode() },
                                modifier = Modifier.testTag("auth-send-code-button")
                            )
                        } else {
                            PfPrimaryButton(
                                title = "Verify code and sign in",
                                onClick = { submitCode() },
                                modifier = Modifier.testTag("auth-verify-code-button")
                            )
                            TextButton(
                                onClick = { sendCode() },
                                enabled = resendCooldownSeconds <= 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth-resend-code-button")
                            ) {
                                Text(
                                    text = if (resendCooldownSeconds > 0) {
                                        "Resend code in $resendCooldownSeconds seconds"
                                    } else {
                                        "Resend code"
                                    },
                                    color = PfColors.royal,
                                    style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                        TextButton(
                            onClick = { switchToPasswordForm() },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth-back-to-password-button")
                        ) {
                            Text(
                                text = "Back to password sign-in",
                                color = PfColors.royal,
                                style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Text(
                            text = "By signing in with a code, you agree to the Terms and Privacy Policy.",
                            style = PfTypography.caption,
                            color = PfColors.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            TextButton(
                onClick = { openCreateAccount() },
                enabled = !busy,
                modifier = Modifier.testTag("auth-create-account-button")
            ) {
                Text(
                    text = "No account yet? Create one",
                    color = PfColors.royal,
                    style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(modifier = Modifier.height(PfSpacing.large))
            Text(
                text = "What happens to guest progress: it stays on this device " +
                    "under your guest profile. After you sign in, you can copy " +
                    "it into your account. Copying never overwrites progress " +
                    "your account already has, and signing out never deletes it.",
                style = PfTypography.caption,
                color = PfColors.secondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(PfSpacing.large))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Terms",
                    style = PfTypography.caption,
                    color = PfColors.royal,
                    modifier = Modifier.clickable {
                        openUrlInBrowser(context, AuthLinks.TERMS_URL)
                    }
                )
                Spacer(modifier = Modifier.size(PfSpacing.small))
                Text(
                    text = "and",
                    style = PfTypography.caption,
                    color = PfColors.secondaryText
                )
                Spacer(modifier = Modifier.size(PfSpacing.small))
                Text(
                    text = "Privacy Policy",
                    style = PfTypography.caption,
                    color = PfColors.royal,
                    modifier = Modifier.clickable {
                        openUrlInBrowser(context, AuthLinks.PRIVACY_URL)
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(PfSpacing.xLarge))
        }
    }
}

/** The "or" divider between the password form and the emailed-code alternative. */
@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(PfColors.border)
        )
        Text(
            text = "or",
            style = PfTypography.caption,
            color = PfColors.secondaryText,
            modifier = Modifier.padding(horizontal = PfSpacing.medium)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(PfColors.border)
        )
    }
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
