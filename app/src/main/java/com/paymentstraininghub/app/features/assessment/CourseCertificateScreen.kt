// CourseCertificateScreen.kt
// Course Certificate of Completion, ported from iOS
// CourseCertificateView.swift. Shown when a learner passes a
// course Final Assessment (more than 70 percent, at least 15
// out of 20). The card is premium: royal blue and gold on white,
// Payments Training Hub branding, a gold certificate border.
// It can be shared as text, verified at the verification URL,
// and, for the four certificate courses, issued as a Certifier
// digital credential with an Add to LinkedIn link.
//
// Android divergences from iOS, stated plainly: the certificate
// cannot be rendered to a shareable image or PDF here (no
// render-to-image exporter exists on Android), and the QR code
// is not rendered (no QR encoder dependency); the Certifier
// credential opens as a link instead. The achievement share
// card has no Android equivalent and is not shown. The sample
// preview names "Sample Learner": Android collects no display
// name, and that is the iOS fallback.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPill
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSecondaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/// A preview of what a learner earns, built on the fly from
/// sample data and never stored, so it can never be mistaken
/// for a real earned certificate.
fun sampleCourseCertificate(): CourseCertificate = CourseCertificate(
    id = "PF-SAMPLE-000000",
    courseId = "com.paymentstraininghub.app.integrated",
    courseName = "The Integrated Course",
    learnerName = "Sample Learner",
    issuedAtMillis = System.currentTimeMillis(),
    correctCount = 18,
    totalQuestions = 20
)

fun formatCertificateDate(issuedAtMillis: Long): String =
    Instant.ofEpochMilli(issuedAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy"))

/// The premium certificate card for one earned course certificate.
@Composable
fun CourseCertificateCard(certificate: CourseCertificate, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PfSpacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = PfColors.card),
        border = BorderStroke(2.dp, PfColors.gold)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(PfSpacing.cardRadius - 5.dp),
                color = PfColors.card,
                border = BorderStroke(1.dp, PfColors.gold.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PfSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                ) {
                    Icon(
                        Icons.Filled.WorkspacePremium,
                        contentDescription = null,
                        tint = PfColors.gold,
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        "PAYMENTS TRAINING HUB",
                        style = PfTypography.captionBold.copy(letterSpacing = 3.sp),
                        color = PfColors.royal,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Certificate of Completion",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PfColors.navy,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "This certifies that",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    Text(
                        certificate.learnerName,
                        style = PfTypography.sectionTitle.copy(fontWeight = FontWeight.Bold),
                        color = PfColors.royal,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "has successfully completed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                    Text(
                        certificate.courseName,
                        style = PfTypography.sectionTitle,
                        color = PfColors.navy,
                        textAlign = TextAlign.Center
                    )
                    Divider(color = PfColors.gold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)) {
                            Text(
                                "Issued",
                                style = PfTypography.captionBold,
                                color = PfColors.secondaryText
                            )
                            Text(
                                formatCertificateDate(certificate.issuedAtMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)
                        ) {
                            Text(
                                "Score",
                                style = PfTypography.captionBold,
                                color = PfColors.secondaryText
                            )
                            Text(
                                "${certificate.scoreText} (${Math.round(certificate.scorePercent).toInt()}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                        }
                    }
                    Text(
                        "Issued by Payments Training Hub",
                        style = PfTypography.captionBold,
                        color = PfColors.navy
                    )
                    Text(
                        "Certificate ID: ${certificate.id}",
                        style = PfTypography.caption,
                        color = PfColors.secondaryText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCertificateScreen(
    productId: String,
    isSample: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val certificates by FinalAssessmentStore.certificates.collectAsState()
    val authState by AuthManager.authState.collectAsState()

    var storeLoaded by remember { mutableStateOf(false) }
    var certifierCredential by remember { mutableStateOf<CertifierCredential?>(null) }

    val certificate: CourseCertificate? = if (isSample) {
        remember { sampleCourseCertificate() }
    } else {
        certificates[productId]
    }

    LaunchedEffect(productId, isSample) {
        if (!isSample) {
            FinalAssessmentStore.refresh()
            storeLoaded = true
        }
    }

    LaunchedEffect(certificate, isSample) {
        val cert = certificate ?: return@LaunchedEffect
        if (isSample) return@LaunchedEffect
        val token = runCatching { AuthManager.validAccessToken() }.getOrNull()
        val email = (authState as? AuthManager.AuthState.SignedIn)?.email
        certifierCredential = CertifierService.requestCredential(
            certificate = cert,
            accessToken = token,
            recipientEmail = email
        )
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share certificate"))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Certificate", color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PfColors.navy)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PfColors.card)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberTopScrollState())
                .padding(PfSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
        ) {
            when {
                certificate == null && !storeLoaded && !isSample -> {
                    Text(
                        "Loading the certificate...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
                certificate == null -> {
                    Text(
                        "This certificate has not been earned yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
                else -> {
                    val cert = certificate
                    if (isSample) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(PfSpacing.cornerRadius),
                            color = PfColors.gold
                        ) {
                            Text(
                                "SAMPLE CERTIFICATE - NOT EARNED",
                                style = PfTypography.captionBold,
                                color = androidx.compose.ui.graphics.Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = PfSpacing.small)
                            )
                        }
                    }
                    Box {
                        CourseCertificateCard(certificate = cert)
                        if (isSample) {
                            Text(
                                "SAMPLE",
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Bold,
                                color = PfColors.gold.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .rotate(-28f)
                            )
                        }
                    }
                    if (isSample) {
                        Text(
                            "This is a sample to show what you earn. Pass a course Final Assessment, 20 questions, above 70 percent, with 3 attempts, to earn your own certificate.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PfColors.secondaryText
                        )
                    }
                    if (!isSample) {
                        PfCard {
                            Column(
                                modifier = Modifier,
                                verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(PfSpacing.small),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Verification",
                                        style = PfTypography.cardTitle,
                                        color = PfColors.navy
                                    )
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        if (certifierCredential != null) {
                                            PfPill(
                                                text = "Verified",
                                                background = PfColors.gold,
                                                foreground = androidx.compose.ui.graphics.Color.White
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Check this certificate at:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.secondaryText
                                )
                                TextButton(onClick = { openUrl(cert.verificationUrl) }) {
                                    Text(
                                        cert.verificationUrl,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.royal
                                    )
                                }
                                val signedIn = authState is AuthManager.AuthState.SignedIn
                                Text(
                                    if (!signedIn) {
                                        "This certificate is stored on this device. The website verification copy is only written for signed-in learners."
                                    } else {
                                        "Anyone can check this certificate at the address above."
                                    },
                                    style = PfTypography.caption,
                                    color = PfColors.secondaryText
                                )
                                if (certifierCredential != null) {
                                    Text(
                                        "Also issued as a digital credential in Certifier. The wallet link was emailed to you.",
                                        style = PfTypography.caption,
                                        color = PfColors.secondaryText
                                    )
                                    certifierCredential?.certifierUrl?.let { url ->
                                        TextButton(onClick = { openUrl(url) }) {
                                            Text(
                                                "Open the Certifier credential",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = PfColors.royal
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Certificate of Completion. This certifies completion of the named course. It is not an accredited qualification.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PfColors.navy
                                )
                            }
                        }
                        certifierCredential?.linkedinUrl?.let { linkedinUrl ->
                            PfPrimaryButton(
                                title = "Add to LinkedIn",
                                onClick = { openUrl(linkedinUrl) }
                            )
                        }
                        PfSecondaryButton(
                            title = "Share certificate details as text",
                            onClick = { shareText(cert.shareText) }
                        )
                    }
                }
            }
            PfBottomInset()
        }
    }
}
