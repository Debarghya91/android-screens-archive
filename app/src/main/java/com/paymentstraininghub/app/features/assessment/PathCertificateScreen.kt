// PathCertificateScreen.kt
// Learning-path Certificate of Completion, ported from iOS
// CertificateView.swift. A certificate is issued ONLY when every
// required lesson of its path is complete. The overall score
// comes from CompetencyStore and is shown only when an
// assessment has actually been taken, otherwise a dash. The
// verification URL is the address where the certificate can be
// checked. The learner name is the signed-in account's email
// when signed in, else "Guest Learner": Android collects no
// display name, and that is the iOS fallback too.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.data.CompetencyStore
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfLoadingView
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfProgressBar
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathCertificateScreen(
    pathId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val path = remember(pathId) { LearningPaths.all.firstOrNull { it.id == pathId } }
    val authState by AuthManager.authState.collectAsState()
    // The certificate names the signed-in account (G10); guests
    // keep the "Guest Learner" fallback.
    val learnerName = (authState as? AuthManager.AuthState.SignedIn)
        ?.email?.takeIf { it.isNotBlank() } ?: "Guest Learner"

    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var certificate by remember { mutableStateOf<PFCertificate?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(pathId, authState) {
        val loadedEntries = runCatching {
            ContentRepository.loadSearchIndex().entries
        }.getOrDefault(emptyList())
        entries = loadedEntries
        completedIds = HabitStore.loadCompletedLessonIds()
        CompetencyStore.refresh()
        val target = LearningPaths.all.firstOrNull { it.id == pathId }
        certificate = target?.let {
            PFCertificateStore.certificateFor(
                path = it,
                entries = loadedEntries,
                completedLessonIds = completedIds,
                learnerName = learnerName
            )
        }
        loaded = true
    }

    // Backfill the server verification copy once per appearance.
    // Silent on failure; the local certificate is unaffected.
    LaunchedEffect(certificate) {
        certificate?.let { PFCertificateStore.syncCertificateToServer(it) }
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

    fun openVerificationUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
                path == null -> {
                    Text(
                        "That learning journey was not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PfColors.secondaryText
                    )
                }
                !loaded -> {
                    PfLoadingView(message = "Loading the certificate...")
                }
                certificate != null -> {
                    val cert = certificate!!
                    PfCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.lg)
                        ) {
                            Icon(
                                Icons.Filled.WorkspacePremium,
                                contentDescription = null,
                                tint = PfColors.gold,
                                modifier = Modifier.size(52.dp)
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
                                cert.learnerName,
                                style = PfTypography.sectionTitle.copy(fontWeight = FontWeight.Bold),
                                color = PfColors.navy,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "has completed the learning journey",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Text(
                                cert.pathTitle,
                                style = PfTypography.sectionTitle,
                                color = PfColors.royal,
                                textAlign = TextAlign.Center
                            )
                            Divider(color = PfColors.border)
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
                                        formatCertificateDate(cert.issuedAtMillis),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.navy
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)
                                ) {
                                    Text(
                                        "Overall score",
                                        style = PfTypography.captionBold,
                                        color = PfColors.secondaryText
                                    )
                                    Text(
                                        cert.overallScore?.let { "${Math.round(it).toInt()}%" } ?: "-",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PfColors.navy
                                    )
                                }
                            }
                            Text(
                                "Certificate ID: ${cert.id}",
                                style = PfTypography.caption,
                                color = PfColors.secondaryText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.small)
                        ) {
                            Text(
                                "Verification",
                                style = PfTypography.cardTitle,
                                color = PfColors.navy
                            )
                            Text(
                                "Check this certificate at:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            Text(
                                cert.verificationUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.royal,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable {
                                    openVerificationUrl(cert.verificationUrl)
                                }
                            )
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
                            Text(
                                "Certificate of Completion. This certifies completion of the named learning journey. It is not an accredited qualification.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.navy
                            )
                        }
                    }
                    PfPrimaryButton(
                        title = "Share certificate",
                        onClick = { shareText(cert.shareText) }
                    )
                }
                else -> {
                    val (completed, total) = path.progress(entries, completedIds)
                    PfCard {
                        Column(
                            modifier = Modifier,
                            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
                        ) {
                            Icon(
                                Icons.Filled.WorkspacePremium,
                                contentDescription = null,
                                tint = PfColors.gold,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "Certificate not earned yet",
                                style = PfTypography.sectionTitle,
                                color = PfColors.navy
                            )
                            Text(
                                "Complete all $total lessons in ${path.title} to earn the ${path.certificateTitle ?: "journey"} certificate. You have completed $completed so far.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PfColors.secondaryText
                            )
                            if (total > 0) {
                                PfProgressBar(
                                    progress = completed.toFloat() / total.toFloat()
                                )
                            }
                            PfPrimaryButton(
                                title = "Open the journey",
                                onClick = { onNavigate(Routes.path(path.id)) }
                            )
                        }
                    }
                }
            }
            PfBottomInset()
        }
    }
}
