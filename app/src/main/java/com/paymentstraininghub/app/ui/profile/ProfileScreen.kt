// ProfileScreen.kt
// Payments Training Hub Android - account screen, mirroring iOS
// ProfileView: guests see "Guest" with a Sign in button; signed-in
// users see their email, the email verification line, the case ID,
// the referral code and credit balance, and the account rows.
// Email confirmation verifies the email address. Referral discount
// redemption is website-only; the app shows and shares the code
// only. Delete Account goes through the delete-account edge
// function and clears local state only after the server confirms.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.CaseIdStore
import com.paymentstraininghub.app.auth.ReferralStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val authState by AuthManager.authState.collectAsState()
    val emailVerified by AuthManager.emailVerified.collectAsState()
    val creditBalance by ReferralStore.creditBalance.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showSignOutDialog by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var signOutError by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleteInfo by remember { mutableStateOf<String?>(null) }
    var resending by remember { mutableStateOf(false) }
    var resendInfo by remember { mutableStateOf<String?>(null) }
    var resendError by remember { mutableStateOf<String?>(null) }
    var caseId by remember { mutableStateOf<String?>(null) }
    var shareFeedback by remember { mutableStateOf<String?>(null) }

    val versionName = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // Reload the account-scoped referral state and the case ID
    // whenever the signed-in identity changes, and refresh the email
    // verification status for the signed-in account.
    LaunchedEffect(authState) {
        try {
            ReferralStore.reloadForCurrentAccount()
        } catch (_: Exception) {
        }
        val signedIn = authState as? AuthManager.AuthState.SignedIn
        if (signedIn != null) {
            caseId = try {
                CaseIdStore.ensureCaseId(signedIn.userId)
            } catch (_: Exception) {
                null
            }
            AuthManager.refreshVerificationStatus()
        } else {
            caseId = null
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            containerColor = PfColors.card,
            titleContentColor = PfColors.navy,
            textContentColor = PfColors.secondaryText,
            title = { Text("Sign out?", style = PfTypography.cardTitle, color = PfColors.navy) },
            text = {
                Text(
                    "Your progress is kept under your account. Sign in again any time to pick up where you left off.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        signingOut = true
                        signOutError = false
                        scope.launch {
                            try {
                                AuthManager.signOut()
                            } catch (e: Exception) {
                                signOutError = true
                            } finally {
                                signingOut = false
                            }
                        }
                    }
                ) { Text("Sign out", color = PfColors.royal) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel", color = PfColors.royal)
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteDialog = false },
            containerColor = PfColors.card,
            titleContentColor = PfColors.navy,
            textContentColor = PfColors.secondaryText,
            title = {
                Text("Delete your account?", style = PfTypography.cardTitle, color = PfColors.navy)
            },
            text = {
                Text(
                    text = "This removes:\n" +
                        "Your profile, including your name, designation, organisation and experience details.\n" +
                        "Your lesson progress, including completed lessons, quiz attempts, test results, practice attempts and streaks.\n" +
                        "Your email verification status.\n" +
                        "Your referral records, including your referral code, referral uses and referral credits.\n" +
                        "Your case ID record.\n" +
                        "\nThis cannot be undone. Once deleted, your account and the data above cannot be restored.\n" +
                        "\nIf you bought courses through Google Play, those purchase records belong to Google Play and are not affected.",
                    style = PfTypography.body,
                    color = PfColors.secondaryText
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteError = null
                        deleteInfo = null
                        scope.launch {
                            val result = AuthManager.deleteAccount()
                            deleting = false
                            showDeleteDialog = false
                            result.fold(
                                onSuccess = {
                                    deleteInfo = "Your account has been deleted."
                                },
                                onFailure = { failure ->
                                    deleteError = failure.message
                                        ?: "Your account could not be deleted. Nothing was changed. Please try again."
                                }
                            )
                        }
                    }
                ) {
                    Text(
                        if (deleting) "Deleting..." else "Delete my account",
                        color = PfColors.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { showDeleteDialog = false }
                ) { Text("Cancel", color = PfColors.royal) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Profile", color = PfColors.navy, style = PfTypography.cardTitle) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PfColors.background)
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xLarge)
        ) {
            when (val state = authState) {
                is AuthManager.AuthState.Guest -> {
                    PfCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            InitialsCircle(initials = "G")
                            Text(
                                "Guest",
                                style = PfTypography.screenTitle,
                                color = PfColors.navy
                            )
                            Text(
                                "You are learning as a guest. Your progress stays on this device.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText,
                                textAlign = TextAlign.Center
                            )
                            PfPrimaryButton(
                                title = "Sign in",
                                onClick = { onNavigate(Routes.AUTH) }
                            )
                            Text(
                                "Signing in syncs your progress across devices and keeps it safe if you change phones.",
                                style = PfTypography.body,
                                color = PfColors.secondaryText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                is AuthManager.AuthState.SignedIn -> {
                    PfCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            InitialsCircle(
                                initials = state.email.firstOrNull()
                                    ?.uppercaseChar()?.toString() ?: "?"
                            )
                            Text(
                                state.email,
                                style = PfTypography.screenTitle,
                                color = PfColors.navy,
                                textAlign = TextAlign.Center
                            )
                            when (emailVerified) {
                                true -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Verified,
                                            contentDescription = null,
                                            tint = PfColors.success,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.size(PfSpacing.xSmall))
                                        Text(
                                            "Email verified",
                                            style = PfTypography.caption,
                                            color = PfColors.success
                                        )
                                    }
                                }
                                false -> {
                                    Text(
                                        "Email not verified yet",
                                        style = PfTypography.caption,
                                        color = PfColors.secondaryText
                                    )
                                    TextButton(
                                        onClick = {
                                            resendInfo = null
                                            resendError = null
                                            resending = true
                                            scope.launch {
                                                val result = AuthManager.resendVerificationEmail()
                                                resending = false
                                                result.fold(
                                                    onSuccess = {
                                                        resendInfo = "Verification email sent to ${state.email}. Open the link in it to confirm your email."
                                                    },
                                                    onFailure = { failure ->
                                                        resendError = failure.message
                                                            ?: "Something went wrong. Try again, and contact support if it keeps happening."
                                                    }
                                                )
                                            }
                                        },
                                        enabled = !resending,
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text(
                                            "Resend verification email",
                                            style = PfTypography.captionBold,
                                            color = PfColors.royal
                                        )
                                    }
                                }
                                null -> {
                                    // Verification status unknown
                                    // (no refresh has run yet): show
                                    // no line rather than a wrong one.
                                }
                            }
                            resendInfo?.let { message ->
                                Text(
                                    message,
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText,
                                    textAlign = TextAlign.Center
                                )
                            }
                            resendError?.let { message ->
                                Text(
                                    message,
                                    style = PfTypography.body,
                                    color = PfColors.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                            caseId?.let { id ->
                                Text(
                                    "Your case ID is $id. Keep it for future reference.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                        PfSectionHeader(title = "Referrals")
                        PfCard {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Your referral code",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        ReferralStore.referralCode() ?: "Not available",
                                        style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = PfColors.navy
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Referral credits",
                                        style = PfTypography.body,
                                        color = PfColors.secondaryText,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "$creditBalance",
                                        style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                        color = PfColors.navy
                                    )
                                }
                                Text(
                                    "Referral credits are confirmed on the website.",
                                    style = PfTypography.caption,
                                    color = PfColors.secondaryText
                                )
                                val code = ReferralStore.referralCode()
                                if (!code.isNullOrEmpty()) {
                                    TextButton(
                                        onClick = {
                                            // The tap must always do
                                            // something visible: the
                                            // share sheet, or the
                                            // clipboard fallback with
                                            // a message saying so.
                                            val shared = shareReferralCode(context)
                                            shareFeedback = if (shared) {
                                                null
                                            } else {
                                                "Sharing is not available right now. Your referral text was copied instead."
                                            }
                                        },
                                        modifier = Modifier.testTag("share-referral-code-button")
                                    ) {
                                        Text(
                                            "Share your code",
                                            style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                            color = PfColors.royal
                                        )
                                    }
                                    shareFeedback?.let { feedback ->
                                        Text(
                                            feedback,
                                            style = PfTypography.caption,
                                            color = PfColors.secondaryText
                                        )
                                    }
                                }
                                Text(
                                    "Referral discounts can only be redeemed on the website. In-app purchases use Google Play billing, which cannot apply an external discount.",
                                    style = PfTypography.body,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                    }
                }
            }

            ProfileRow(
                title = "My purchases",
                subtitle = "Courses you own, and courses to buy",
                onClick = { onNavigate(Routes.CHECKOUT) }
            )
            ProfileRow(
                title = "Habit reminders",
                subtitle = "Reminders and the question of the day",
                onClick = { onNavigate(Routes.HABIT) }
            )
            ProfileRow(
                title = "Notifications",
                subtitle = "Choose which notifications you receive",
                onClick = { onNavigate(Routes.NOTIFICATIONS) }
            )
            ProfileRow(
                title = "Privacy Policy",
                subtitle = "How your data is used",
                onClick = { onNavigate(Routes.PRIVACY) }
            )
            ProfileRow(
                title = "Terms of Use",
                subtitle = "The terms for using Payments Training Hub",
                onClick = { onNavigate(Routes.TERMS) }
            )

            if (authState is AuthManager.AuthState.SignedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)) {
                    if (signingOut) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PfColors.royal)
                        }
                    } else {
                        PfSecondaryButton(
                            title = "Sign Out",
                            onClick = { showSignOutDialog = true }
                        )
                    }
                    if (signOutError) {
                        Text(
                            "Sign out did not complete. Please try again.",
                            style = PfTypography.body,
                            color = PfColors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Delete Account",
                            style = PfTypography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = PfColors.error
                        )
                    }
                    deleteError?.let { message ->
                        Text(
                            message,
                            style = PfTypography.body,
                            color = PfColors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    deleteInfo?.let { message ->
                        Text(
                            message,
                            style = PfTypography.body,
                            color = PfColors.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Text(
                "Payments Training Hub for Android, version $versionName",
                style = PfTypography.body,
                color = PfColors.secondaryText
            )
        }
    }
}

/** The initials circle used where no profile photo exists: an 84dp circle with a gold ring, mirroring the iOS avatar treatment. */
@Composable
private fun InitialsCircle(initials: String) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(PfColors.navySoft)
            .border(BorderStroke(2.dp, PfColors.gold), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = PfTypography.sectionTitle.copy(fontWeight = FontWeight.Bold),
            color = PfColors.navy
        )
    }
}

@Composable
private fun ProfileRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(PfSpacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(1.dp, PfColors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PfSpacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
                Text(subtitle, style = PfTypography.body, color = PfColors.secondaryText)
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = PfColors.secondaryText
            )
        }
    }
}

/**
 * The Android share-sheet intent for referral text: a chooser
 * around an ACTION_SEND text intent carrying the subject and the
 * text. The NEW_TASK flag covers a non-Activity context, which
 * would otherwise throw on startActivity. Pure, so the intent
 * shape is pinned by unit tests.
 */
internal fun buildReferralShareIntent(text: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "My Payments Training Hub referral code")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return Intent.createChooser(send, "Share your code").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Share the user's own referral code through the Android share
 * sheet. True when the sheet opened. When the sheet cannot open
 * (no share target, or a context that refuses the start), the
 * text is copied to the clipboard instead and false is returned,
 * so the caller can say so: the old code swallowed the failure
 * silently, which is exactly the dead tap the owner reported.
 */
private fun shareReferralCode(context: Context): Boolean {
    val text = ReferralStore.shareText()
    if (text.isEmpty()) return false
    return try {
        context.startActivity(buildReferralShareIntent(text))
        true
    } catch (_: Exception) {
        copyTextToClipboard(context, text)
        false
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Referral code", text))
    } catch (_: Exception) {
        // The clipboard is a best-effort fallback; the code stays
        // visible on screen either way.
    }
}
