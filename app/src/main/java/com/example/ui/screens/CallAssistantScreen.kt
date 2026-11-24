package com.example.ui.screens

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.core.security.BabySettings
import com.example.core.security.CallAssistantMode
import com.example.core.security.SecureKeyStore
import com.example.core.security.UnknownCallerPolicy
import com.example.data.local.CallScreeningLogEntity
import com.example.features.calls.ScamMatcher
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CallAssistantScreen(
    settings: BabySettings,
    logs: List<CallScreeningLogEntity>,
    isCallScreeningRoleHeld: Boolean,
    onBack: () -> Unit,
    onModeChange: (CallAssistantMode) -> Unit,
    onPolicyChange: (UnknownCallerPolicy) -> Unit,
    onTogglePref: (String, Boolean) -> Unit,
    onSaveReplyTemplate: (String) -> Unit,
    onClearLogs: () -> Unit,
    onRefreshRole: () -> Unit = {}
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val context = LocalContext.current

    var templateInput by remember(settings.callAssistantReplyTemplate) {
        mutableStateOf(settings.callAssistantReplyTemplate)
    }
    var spamSampleInput by remember {
        mutableStateOf("Aapka KYC update pending hai, bank account block ho jayega, abhi OTP batayein")
    }
    val scamPreview = remember(spamSampleInput) { ScamMatcher.analyze(spamSampleInput) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onRefreshRole() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("call_assistant_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Call Assistant & Spam Shield", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (settings.callAssistantEnabled) {
                                "${settings.callAssistantMode.label} • ${settings.unknownCallerPolicy.label}"
                            } else "Currently OFF",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (logs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear call logs")
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCallScreeningRoleHeld) {
                                ext.successContainer.copy(alpha = 0.45f)
                            } else {
                                ext.warningContainer.copy(alpha = 0.7f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isCallScreeningRoleHeld) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = if (isCallScreeningRoleHeld) ext.success else ext.warning
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isCallScreeningRoleHeld) {
                                        "Baby Default Call Screener Active"
                                    } else {
                                        "Default Call Screening Role Needed"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Text(
                                text = "Android par background call screening (Silence / Reject / Spam shield) chalane ke liye Baby ko Call Screening App set karein.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!isCallScreeningRoleHeld && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Button(
                                    onClick = {
                                        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                                            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                        }
                                    },
                                    modifier = Modifier.testTag("request_call_screening_role_btn")
                                ) {
                                    Text("Set Baby as Call Screener")
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Call Mode & Overrides", style = MaterialTheme.typography.titleMedium)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CallAssistantMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = settings.callAssistantMode == mode,
                                        onClick = { onModeChange(mode) },
                                        label = { Text(mode.label) },
                                        modifier = Modifier.testTag("call_mode_${mode.name.lowercase()}")
                                    )
                                }
                            }
                            Text(
                                text = "Safety Overrides (Always Active): Emergency/VIP contacts aur 3 minute mein 2+ baar call karne wale repeat callers hamesha loud ring karenge.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider()
                            Text("Unknown Caller Policy", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UnknownCallerPolicy.entries.forEach { pol ->
                                    FilterChip(
                                        selected = settings.unknownCallerPolicy == pol,
                                        onClick = { onPolicyChange(pol) },
                                        label = { Text(pol.label) },
                                        modifier = Modifier.testTag("call_policy_${pol.name.lowercase()}")
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = templateInput,
                                onValueChange = { templateInput = it },
                                label = { Text("Level 1 Auto-Reject SMS Template (1/hr limit)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(onClick = { onSaveReplyTemplate(templateInput) }) {
                                Text("Save Call Reply Template")
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(8.dp))
                                Text("Level 2 AI Call Handler (Best-Effort Opt-In)", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "Honest OS Note: Standard Android par apps seedha cellular audio stream mein TTS nahi bhej sakti (speakerphone mic/speaker best-effort use hota hai, 60s hard auto-end cap ke saath). Caller ki baatein kabhi tool execution mein nahi jaati.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = settings.callAssistantCountryWarningAck,
                                    onCheckedChange = {
                                        onTogglePref(SecureKeyStore.KEY_CALL_COUNTRY_WARNING_ACK, it)
                                    }
                                )
                                Text(
                                    text = "Main samajhta/samajhti hoon ki AI screening aur call recording laws mere desh/region ke mutabik apply hote hain, aur caller ko AI disclosure sunaya jayega.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Enable Best-Effort Level 2 AI Answer", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Requires acknowledgment checkbox above",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.callAssistantLevel2OptIn,
                                    enabled = settings.callAssistantCountryWarningAck,
                                    onCheckedChange = {
                                        onTogglePref(SecureKeyStore.KEY_CALL_ASSISTANT_LEVEL2, it)
                                    }
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Save Redacted Call Transcript", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Default OFF — sirf short summary save hoti hai",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.callAssistantSaveAudio,
                                    onCheckedChange = {
                                        onTogglePref(SecureKeyStore.KEY_CALL_ASSISTANT_SAVE_AUDIO, it)
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Scam / Spam Signal Tester", style = MaterialTheme.typography.titleMedium)
                            }
                            OutlinedTextField(
                                value = spamSampleInput,
                                onValueChange = { spamSampleInput = it },
                                label = { Text("Sample caller speech / label") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = scamPreview.summary.withDevanagariFallback(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (scamPreview.isSuspicious) MaterialTheme.colorScheme.error else ext.success
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "Recent Screening Log (${logs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = "Abhi tak koi call screen record nahi hua hai.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(logs, key = { it.id }) { log ->
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        text = "${log.callerName.ifBlank { log.callerNumber }} (${log.callerNumber})",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = formatCallTime(log.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${log.decision} • ${log.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (log.replySent.isNotBlank()) {
                                    Text(log.replySent, style = MaterialTheme.typography.bodySmall)
                                }
                                if (log.transcript.isNotBlank()) {
                                    Text(
                                        log.transcript,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCallTime(ts: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ts))

@Preview(showBackground = true, name = "Call Assistant Dark")
@Composable
private fun CallAssistantPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        CallAssistantScreen(
            settings = BabySettings(
                callAssistantEnabled = true,
                callAssistantMode = CallAssistantMode.SMART,
                unknownCallerPolicy = UnknownCallerPolicy.SILENCE
            ),
            logs = listOf(
                CallScreeningLogEntity(
                    id = 1,
                    callerNumber = "+919876543210",
                    callerName = "Unknown KYC",
                    mode = "SMART",
                    decision = "SILENCE",
                    reason = "Spam/Scam signal: KYC / OTP threat"
                )
            ),
            isCallScreeningRoleHeld = false,
            onBack = {},
            onModeChange = {},
            onPolicyChange = {},
            onTogglePref = { _, _ -> },
            onSaveReplyTemplate = {},
            onClearLogs = {}
        )
    }
}
