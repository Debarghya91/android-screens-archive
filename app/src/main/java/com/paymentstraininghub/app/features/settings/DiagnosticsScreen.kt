// DiagnosticsScreen.kt
// Diagnostics: crash reports, device info, and soft security
// notices. Reached from Help and Support. The owner shares one
// crash report file from here and support gets the real trace,
// with no cable and no account needed. Reports stay in app-private
// storage and are never uploaded; sharing sends the report text
// only when the owner taps Share.
//
// The security section shows the soft findings from
// SecurityMonitor (root, debugger, installer, signature). They
// are notices only: nothing on this screen blocks or changes
// anything. The TLS section shows the certificate pins observed
// on this device, which is how real pins are collected before
// any future enforcing mode.

package com.paymentstraininghub.app.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.ThumbnailStore
import com.paymentstraininghub.app.security.CrashCapture
import com.paymentstraininghub.app.security.EventLog
import com.paymentstraininghub.app.security.PinningObserver
import com.paymentstraininghub.app.security.SecurityMonitor
import com.paymentstraininghub.app.ui.cases.PointerDiagnostics
import com.paymentstraininghub.app.ui.theme.PfCard
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.rememberTopScrollState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf<List<CrashCapture.CrashReportInfo>>(emptyList()) }
    var deviceInfo by remember { mutableStateOf("") }
    var deviceInfoCopied by remember { mutableStateOf(false) }
    var copiedReport by remember { mutableStateOf<String?>(null) }
    var previousDeathUnreported by remember { mutableStateOf(false) }
    var observedPins by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var eventLogCopied by remember { mutableStateOf(false) }
    val findings by SecurityMonitor.findings.collectAsState()
    val agendaStatus by AgendaStore.loadStatus.collectAsState()
    val agendaCount by AgendaStore.agendas.collectAsState()
    val failedThumbnails by ThumbnailStore.failedPaths.collectAsState()
    val eventEntries by EventLog.entriesFlow.collectAsState()
    val pointerGestures by PointerDiagnostics.entriesFlow.collectAsState()

    LaunchedEffect(Unit) {
        SecurityMonitor.refresh(context)
        withContext(Dispatchers.IO) {
            reports = CrashCapture.listReports(context)
            deviceInfo = CrashCapture.deviceInfo(context)
            previousDeathUnreported = CrashCapture.previousDeathUnreported
            observedPins = PinningObserver.observationsSnapshot()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Diagnostics", color = PfColors.navy) },
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
                "Device",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    Text(
                        deviceInfo.ifBlank { "Loading device info." },
                        style = PfTextStyles.body,
                        color = PfColors.navy
                    )
                    OutlinedButton(
                        onClick = {
                            copyText(context, "Device info", deviceInfo)
                            deviceInfoCopied = true
                        }
                    ) {
                        Text(if (deviceInfoCopied) "Copied" else "Copy device info")
                    }
                }
            }
            Text(
                "Course content",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    Text(
                        if (agendaCount.isEmpty) {
                            "Agendas not loaded yet."
                        } else {
                            "Agendas loaded: ${agendaCount.lessons.size} lessons, " +
                                "${agendaCount.chapters.size} chapters, " +
                                "${agendaCount.courses.size} courses, " +
                                "${agendaCount.cases.size} cases."
                        },
                        style = PfTextStyles.body,
                        color = PfColors.navy
                    )
                    agendaStatus.lastError?.let { error ->
                        Text(
                            "Last agendas fetch failed: $error",
                            style = PfTextStyles.body,
                            color = PfColors.navy
                        )
                    }
                    Text(
                        if (failedThumbnails.isEmpty()) {
                            "No thumbnail fetches have failed."
                        } else {
                            "${failedThumbnails.size} thumbnail fetches failed " +
                                "(each retries on its own after 60 seconds). " +
                                "First failures: ${failedThumbnails.sorted().take(3).joinToString(", ")}."
                        },
                        style = PfTextStyles.body,
                        color = PfColors.navy
                    )
                }
            }
            if (previousDeathUnreported) {
                Text(
                    "Last run",
                    style = PfTextStyles.captionBold,
                    color = PfColors.secondaryText
                )
                PfCard {
                    Text(
                        "The app closed last time without a crash report. That usually means the phone ran out of memory and closed the app, or a native playback fault. If this keeps happening, tell support which lesson was playing.",
                        style = PfTextStyles.body,
                        color = PfColors.navy
                    )
                }
            }
            Text(
                "Crash reports",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            if (reports.isEmpty()) {
                PfCard {
                    Text(
                        "No crash reports. If the app closes unexpectedly, a report appears here and can be shared with support.",
                        style = PfTextStyles.body,
                        color = PfColors.secondaryText
                    )
                }
            } else {
                reports.forEach { report ->
                    PfCard {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                            Text(
                                formatReportDate(report.writtenAtMs),
                                style = PfTextStyles.body,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.navy
                            )
                            Text(
                                report.headline,
                                style = PfTextStyles.callout,
                                color = PfColors.secondaryText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                                OutlinedButton(
                                    onClick = {
                                        val text = CrashCapture.readReport(context, report.fileName)
                                        if (!text.isNullOrBlank()) {
                                            copyText(context, "Crash report", text)
                                            copiedReport = report.fileName
                                        }
                                    }
                                ) {
                                    Text(if (copiedReport == report.fileName) "Copied" else "Copy")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val text = CrashCapture.readReport(context, report.fileName)
                                        if (!text.isNullOrBlank()) {
                                            shareText(context, "Crash report", text)
                                        }
                                    }
                                ) {
                                    Text("Share")
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        CrashCapture.clearReports(context)
                        reports = emptyList()
                        copiedReport = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear all reports", color = PfColors.royal)
                }
            }
            Text(
                "Event log",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    if (eventEntries.isEmpty()) {
                        Text(
                            "No audio or navigation events recorded yet in this run.",
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    } else {
                        Text(
                            eventEntries.takeLast(40).asReversed()
                                .joinToString("\n") { it.displayLine() },
                            style = PfTextStyles.callout,
                            color = PfColors.navy
                        )
                        OutlinedButton(
                            onClick = {
                                copyText(
                                    context,
                                    "Event log",
                                    EventLog.formattedLines().joinToString("\n")
                                )
                                eventLogCopied = true
                            }
                        ) {
                            Text(if (eventLogCopied) "Copied" else "Copy event log")
                        }
                    }
                }
            }
            Text(
                "Pointer diagnostics",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    if (pointerGestures.isEmpty()) {
                        Text(
                            "No gestures recorded on the Payment Cases screen yet in this run.",
                            style = PfTextStyles.body,
                            color = PfColors.secondaryText
                        )
                    } else {
                        pointerGestures.takeLast(10).asReversed().forEach { gesture ->
                            Text(
                                gesture.displayLine(),
                                style = PfTextStyles.callout,
                                color = PfColors.navy
                            )
                        }
                    }
                }
            }
            Text(
                "Device trust",
                style = PfTextStyles.captionBold,
                color = PfColors.secondaryText
            )
            PfCard {
                val lines = findings?.notices ?: listOf("Checking device trust.")
                Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                    lines.forEach { line ->
                        Text(
                            line,
                            style = PfTextStyles.body,
                            color = PfColors.navy
                        )
                    }
                    findings?.let { found ->
                        Text(
                            "Installer: ${found.installerDescription}. Signing: ${found.signatureDescription}.",
                            style = PfTextStyles.callout,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
            if (observedPins.isNotEmpty()) {
                Text(
                    "TLS pins observed",
                    style = PfTextStyles.captionBold,
                    color = PfColors.secondaryText
                )
                PfCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpacing.small)) {
                        observedPins.forEach { (host, pins) ->
                            Text(
                                host,
                                style = PfTextStyles.body,
                                fontWeight = FontWeight.SemiBold,
                                color = PfColors.navy
                            )
                            pins.forEach { pin ->
                                Text(
                                    pin,
                                    style = PfTextStyles.callout,
                                    color = PfColors.secondaryText
                                )
                            }
                        }
                        Text(
                            "Pins are observed only and never enforced. They help collect real pins for a future update.",
                            style = PfTextStyles.caption,
                            color = PfColors.secondaryText
                        )
                    }
                }
            }
        }
    }
}

private fun formatReportDate(writtenAtMs: Long): String {
    return SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(writtenAtMs))
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, title: String, text: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: Exception) {
        // No share target available. The Copy button still works.
    }
}
