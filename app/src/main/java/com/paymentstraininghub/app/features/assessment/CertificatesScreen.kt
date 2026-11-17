// CertificatesScreen.kt
// All certificates: a sample preview, the course certificates
// earned by passing Final Assessments, and the path certificates
// earned by completing learning paths. Ported from iOS
// CertificatesListView in CertificateView.swift.
// No em dashes in any user-facing text.

package com.paymentstraininghub.app.features.assessment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.data.model.SearchEntry
import com.paymentstraininghub.app.features.habit.HabitStore
import com.paymentstraininghub.app.ui.navigation.Routes
import com.paymentstraininghub.app.ui.theme.PfBottomInset
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfIconTile
import com.paymentstraininghub.app.ui.theme.PfSectionHeader
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTypography
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificatesScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val ownedIds by BillingManager.ownedProductIds.collectAsState()
    val serverIds by BillingManager.serverEntitledProductIds.collectAsState()
    val gateState by BillingManager.premiumGateState.collectAsState()
    // Collected so owned state refreshes when the admin flag
    // lands on restore: the bare gate call below only
    // re-evaluates on recomposition.
    @Suppress("UNUSED_VARIABLE")
    val isAdmin by AuthManager.isAdmin.collectAsState()
    val certificates by FinalAssessmentStore.certificates.collectAsState()
    val attemptsMap by FinalAssessmentStore.attemptsUsed.collectAsState()

    var entries by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var completedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        FinalAssessmentStore.refresh()
        entries = runCatching { ContentRepository.loadSearchIndex().entries }
            .getOrDefault(emptyList())
        completedIds = HabitStore.loadCompletedLessonIds()
    }

    // Entitlement state changes recompose through the StateFlow
    // collects above; ownership is computed during composition.
    val certificatePaths = remember {
        LearningPaths.all.filter { it.certificateTitle != null }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Certificates", color = PfColors.navy) },
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
            verticalArrangement = Arrangement.spacedBy(PfSpacing.medium)
        ) {
            // MARK: - Sample certificate
            PfSectionHeader(title = "Sample certificate")
            Card(
                onClick = { onNavigate(Routes.COURSE_CERTIFICATE_SAMPLE) },
                colors = CardDefaults.cardColors(containerColor = PfColors.card),
                border = BorderStroke(1.dp, PfColors.border),
                shape = RoundedCornerShape(PfSpacing.cardRadius)
            ) {
                Box {
                    CourseCertificateCard(certificate = sampleCourseCertificate())
                    Surface(
                        shape = RoundedCornerShape(PfSpacing.small),
                        color = PfColors.gold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(PfSpacing.small)
                    ) {
                        Text(
                            "SAMPLE",
                            style = PfTypography.captionBold,
                            color = Color.White,
                            modifier = Modifier.padding(
                                horizontal = PfSpacing.small,
                                vertical = 4.dp
                            )
                        )
                    }
                }
            }
            Text(
                "This is what you are working towards. Pass a course Final Assessment, 20 questions, above 70 percent, with 3 attempts, to earn your own certificate.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )

            // MARK: - Course certificates (Final Assessment)
            PfSectionHeader(title = "Course certificates")
            Text(
                "Pass a course Final Assessment with more than 70 percent to earn its certificate. Each course allows 3 attempts. Only four courses also carry a Certifier digital credential: The Integrated Course, ISO 20022 and SWIFT Messaging, AI Product Owner and Product Manager for Payments, and Global Payment Rails.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            FinalAssessmentStore.assessableCourses.forEach { course ->
                val certificate = certificates[course.productId]
                val remaining = FinalAssessmentStore.attemptsRemaining(course.productId)
                val owned = BillingManager.isProductUnlocked(course.productId)
                when {
                    certificate != null -> {
                        PfCard(
                            onClick = { onNavigate(Routes.courseCertificate(course.productId)) }
                        ) {
                            CertificateRowContent(
                                icon = Icons.Filled.WorkspacePremium,
                                title = course.name,
                                subtitle = "Certificate earned - ${certificate.scoreText} (${Math.round(certificate.scorePercent).toInt()}%)",
                                subtitleColor = PfColors.success,
                                showChevron = true
                            )
                        }
                    }
                    owned && remaining > 0 -> {
                        PfCard(
                            onClick = { onNavigate(Routes.finalAssessment(course.productId)) }
                        ) {
                            CertificateRowContent(
                                icon = Icons.Filled.Assignment,
                                title = course.name,
                                subtitle = "Final Assessment - $remaining of ${FinalAssessmentStore.MAX_ATTEMPTS} attempts left",
                                subtitleColor = PfColors.royal,
                                showChevron = true
                            )
                        }
                    }
                    owned -> {
                        PfCard {
                            CertificateRowContent(
                                icon = Icons.Filled.Lock,
                                title = course.name,
                                subtitle = "Final Assessment locked - all ${FinalAssessmentStore.MAX_ATTEMPTS} attempts used",
                                subtitleColor = PfColors.secondaryText,
                                showChevron = false
                            )
                        }
                    }
                    else -> {
                        PfCard(
                            onClick = { onNavigate(Routes.checkout(course.productId)) }
                        ) {
                            CertificateRowContent(
                                icon = Icons.Filled.WorkspacePremium,
                                title = course.name,
                                subtitle = "Buy the course to unlock its Final Assessment",
                                subtitleColor = PfColors.secondaryText,
                                showChevron = true
                            )
                        }
                    }
                }
            }

            // MARK: - Path certificates
            Text(
                "Journey certificates are issued only when every lesson in a journey is complete. The Interview Preparation journey has no certificate.",
                style = MaterialTheme.typography.bodyMedium,
                color = PfColors.secondaryText
            )
            certificatePaths.forEach { path ->
                val (completed, total) = path.progress(entries, completedIds)
                val earned = path.isComplete(entries, completedIds)
                PfCard(
                    onClick = { onNavigate(Routes.pathCertificate(path.id)) }
                ) {
                    CertificateRowContent(
                        icon = Icons.Filled.WorkspacePremium,
                        title = path.certificateTitle ?: path.title,
                        // Progress rule: no 0-of-N counter. A path with
                        // no lessons done yet says so in words.
                        subtitle = when {
                            earned -> "Earned"
                            completed == 0 -> "Not started yet"
                            else -> "$completed of $total lessons complete"
                        },
                        subtitleColor = if (earned) PfColors.success else PfColors.royal,
                        showChevron = true
                    )
                }
            }
            PfBottomInset()
        }
    }
}

@Composable
private fun CertificateRowContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    showChevron: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PfSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PfIconTile(icon = icon, size = 44.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PfSpacing.xs)
        ) {
            Text(title, style = PfTypography.cardTitle, color = PfColors.navy)
            Text(subtitle, style = PfTypography.captionBold, color = subtitleColor)
        }
        if (showChevron) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = PfColors.secondaryText
            )
        }
    }
}
