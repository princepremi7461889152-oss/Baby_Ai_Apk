package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabyPersonality
import com.example.core.security.BabySettings
import com.example.core.security.SecureKeyStore
import com.example.data.local.AllowedAppEntity
import com.example.data.local.AutoReplyContactRuleEntity
import com.example.data.local.EmergencyContactEntity
import com.example.ui.theme.BabyThemeTokens
import com.example.util.PermissionSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAutoReplySection(
    settings: BabySettings,
    rules: List<AutoReplyContactRuleEntity>,
    onFeatureToggle: (String, Boolean) -> Unit,
    onModeChange: (AutoReplyMode) -> Unit,
    onSaveTemplates: (String, String, String, String) -> Unit,
    onScheduleChange: (Boolean, Int, Int, Int, Int) -> Unit,
    onUpsertRule: (String, String) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var busyMsg by remember(settings.autoReplyBusyMsg) { mutableStateOf(settings.autoReplyBusyMsg) }
    var drivingMsg by remember(settings.autoReplyDrivingMsg) { mutableStateOf(settings.autoReplyDrivingMsg) }
    var sleepingMsg by remember(settings.autoReplySleepingMsg) { mutableStateOf(settings.autoReplySleepingMsg) }
    var customMsg by remember(settings.autoReplyCustomMsg) { mutableStateOf(settings.autoReplyCustomMsg) }
    var startTimeInput by remember(settings.autoReplyStartHour, settings.autoReplyStartMinute) {
        mutableStateOf("%02d:%02d".format(settings.autoReplyStartHour, settings.autoReplyStartMinute))
    }
    var endTimeInput by remember(settings.autoReplyEndHour, settings.autoReplyEndMinute) {
        mutableStateOf("%02d:%02d".format(settings.autoReplyEndHour, settings.autoReplyEndMinute))
    }
    var contactName by remember { mutableStateOf("") }
    var ruleType by remember { mutableStateOf("ALLOW") }

    val summaryText = if (settings.autoReplyEnabled) {
        "${settings.autoReplyMode.label} • ${rules.size} contact rules"
    } else {
        "Off"
    }

    ExtrasSectionCard(
        icon = Icons.Default.MarkChatRead,
        title = "Auto-Reply (WhatsApp / SMS / Calls)",
        summary = summaryText
    ) {
        ExtrasSwitchRow(
            title = "Enable Auto-Reply",
            subtitle = "Rate limit: 1 reply/contact/hour. Logged in Action Log & Quick Settings.",
            checked = settings.autoReplyEnabled,
            onCheckedChange = { enabled ->
                onFeatureToggle(SecureKeyStore.KEY_AUTO_REPLY_ENABLED, enabled)
                if (!enabled) onModeChange(AutoReplyMode.OFF)
                else if (settings.autoReplyMode == AutoReplyMode.OFF) onModeChange(AutoReplyMode.BUSY)
            },
            testTag = "auto_reply_switch"
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoReplyMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.autoReplyMode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExtrasSwitchRow(
            title = "Ignore Group Chats",
            subtitle = "Group conversations par auto-reply na bheje (default ON).",
            checked = settings.autoReplyIgnoreGroups,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_AUTO_REPLY_IGNORE_GROUPS, it) },
            testTag = "auto_reply_ignore_groups_switch"
        )
        ExtrasSwitchRow(
            title = "Smart Hinglish Reply (Groq)",
            subtitle = "Sender ke message ke hisaab se personalized reply bheje.",
            checked = settings.autoReplyAiEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_AUTO_REPLY_AI, it) }
        )
        ExtrasSwitchRow(
            title = "Scheduled Active Hours",
            subtitle = "Sirf chosen start-end time window ke andar auto-reply kare.",
            checked = settings.autoReplyScheduleEnabled,
            onCheckedChange = { enabled ->
                val s = parseHhMm(startTimeInput, settings.autoReplyStartHour, settings.autoReplyStartMinute)
                val e = parseHhMm(endTimeInput, settings.autoReplyEndHour, settings.autoReplyEndMinute)
                onScheduleChange(enabled, s.first, s.second, e.first, e.second)
            },
            testTag = "auto_reply_schedule_switch"
        )
        if (settings.autoReplyScheduleEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startTimeInput,
                    onValueChange = { startTimeInput = it },
                    label = { Text("Start (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endTimeInput,
                    onValueChange = { endTimeInput = it },
                    label = { Text("End (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val s = parseHhMm(startTimeInput, settings.autoReplyStartHour, settings.autoReplyStartMinute)
                        val e = parseHhMm(endTimeInput, settings.autoReplyEndHour, settings.autoReplyEndMinute)
                        onScheduleChange(true, s.first, s.second, e.first, e.second)
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) { Text("Set") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedTextField(value = busyMsg, onValueChange = { busyMsg = it }, label = { Text("Busy Message") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = drivingMsg, onValueChange = { drivingMsg = it }, label = { Text("Driving Message") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sleepingMsg, onValueChange = { sleepingMsg = it }, label = { Text("Sleeping Message") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = customMsg, onValueChange = { customMsg = it }, label = { Text("Custom Mode Message") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { onSaveTemplates(busyMsg, drivingMsg, sleepingMsg, customMsg) },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("Save Auto-Reply Messages")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Per-Contact Allow / Never Reply List", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = contactName,
                onValueChange = { contactName = it },
                label = { Text("Contact Name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = ruleType == "ALLOW",
                onClick = { ruleType = if (ruleType == "ALLOW") "NEVER" else "ALLOW" },
                label = { Text(ruleType) }
            )
            IconButton(
                onClick = {
                    if (contactName.isNotBlank()) {
                        onUpsertRule(contactName, ruleType)
                        contactName = ""
                    }
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) { Icon(Icons.Default.Add, contentDescription = "Add contact rule") }
        }
        rules.forEach { rule ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${rule.displayName} (${rule.ruleType})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onDeleteRule(rule.contactKey) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete contact rule")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAssistantFeaturesSection(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    onFeatureToggle: (String, Boolean) -> Unit,
    onRetentionDaysChange: (Int) -> Unit,
    onPersonalityChange: (BabyPersonality) -> Unit,
    onOpenMemory: () -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefreshPermissions() }
    val locPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefreshPermissions() }
    val calPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefreshPermissions() }

    val retentionLabel = if (settings.actionLogRetentionDays < 0) "Forever" else "${settings.actionLogRetentionDays}d"
    ExtrasSectionCard(
        icon = Icons.Default.Tune,
        title = "Assistant Modules, Personality & Data",
        summary = "${settings.personality.label} • Logs: $retentionLabel"
    ) {
        Button(
            onClick = onOpenMemory,
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("open_memory_manager_btn")
        ) {
            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Manage Memories (yaad rakhna / Sab bhool jao)")
        }

        Text("Personality Tone", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BabyPersonality.entries.forEach { p ->
                FilterChip(
                    selected = settings.personality == p,
                    onClick = { onPersonalityChange(p) },
                    label = { Text(p.label) },
                    modifier = Modifier.testTag("personality_${p.name.lowercase()}")
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExtrasSwitchRow(
            title = "Data Saver Mode",
            subtitle = "Fewer API calls; NO screenshots or camera images sent to Gemini.",
            checked = settings.dataSaverEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_DATA_SAVER, it) },
            testTag = "data_saver_switch"
        )
        ExtrasSwitchRow(
            title = "Offline Basic Commands",
            subtitle = "Internet bina: flashlight, volume, brightness, alarm, DND, open app, call contact.",
            checked = settings.offlineCommandsEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_OFFLINE_COMMANDS, it) },
            testTag = "offline_commands_switch"
        )
        ExtrasSwitchRow(
            title = "Daily Routines",
            subtitle = "Good morning, Good night, Leaving/Reached home & custom schedules.",
            checked = settings.routinesEnabled,
            onCheckedChange = {
                onFeatureToggle(SecureKeyStore.KEY_ROUTINES_ENABLED, it)
                if (it && !permissions.readCalendar) {
                    calPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            }
        )
        ExtrasSwitchRow(
            title = "Memory (\"yaad rakhna ...\")",
            subtitle = "Save nicknames, preferences & app habits locally in Room.",
            checked = settings.memoryEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_MEMORY_ENABLED, it) }
        )
        ExtrasSwitchRow(
            title = "Action Audit Log",
            subtitle = "Record every tool action for searchable history & .txt export.",
            checked = settings.actionLogEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_ACTION_LOG_ENABLED, it) }
        )
        Text("Auto-Delete Action Logs After", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", -1 to "Forever").forEach { (days, label) ->
                FilterChip(
                    selected = settings.actionLogRetentionDays == days,
                    onClick = { onRetentionDaysChange(days) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("retention_$days")
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExtrasSwitchRow(
            title = "Notification Summary (\"Notifications batao\")",
            subtitle = "People messages first, promos last via Groq.",
            checked = settings.notificationSummaryEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_NOTIFICATION_SUMMARY_ENABLED, it) },
            testTag = "notification_summary_switch"
        )
        ExtrasSwitchRow(
            title = "Screen Help (Translate / Summarize Screen)",
            subtitle = "\"Ye kya hai?\", \"isko translate karo\", \"isko summarize karo\".",
            checked = settings.screenHelpEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_SCREEN_HELP, it) },
            testTag = "screen_help_switch"
        )
        ExtrasSwitchRow(
            title = "Clipboard AI (Floating Bubble Clip)",
            subtitle = "Summarize, translate or draft reply on demand.",
            checked = settings.clipboardAiEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_CLIPBOARD_AI, it) },
            testTag = "clipboard_ai_switch"
        )
        ExtrasSwitchRow(
            title = "Camera + Vision (\"Ye kya hai?\")",
            subtitle = "Opens camera only when toggled ON and asked.",
            checked = settings.cameraVisionEnabled,
            onCheckedChange = { enabled ->
                onFeatureToggle(SecureKeyStore.KEY_CAMERA_VISION, enabled)
                if (enabled && !permissions.camera) {
                    camPermLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            testTag = "camera_vision_switch"
        )
        ExtrasSwitchRow(
            title = "Custom Commands (Multi-Action Phrases)",
            subtitle = "\"Movie mode\" -> DND on, low brightness, volume 40%, open Netflix.",
            checked = settings.customCommandsEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_CUSTOM_COMMANDS_ENABLED, it) },
            testTag = "custom_commands_switch"
        )
        ExtrasSwitchRow(
            title = "Macro Recording (Accessibility)",
            subtitle = "Record screen taps, scrolls & inputs with element IDs and replay.",
            checked = settings.macrosEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_MACROS_ENABLED, it) },
            testTag = "macros_enabled_switch"
        )
        ExtrasSwitchRow(
            title = "Smart Reminders (Alarms)",
            subtitle = "Natural-language time reminders via Groq + AlarmManager.",
            checked = settings.remindersEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_REMINDERS_ENABLED, it) },
            testTag = "reminders_enabled_switch"
        )
        ExtrasSwitchRow(
            title = "Location Reminders (Geofencing)",
            subtitle = "\"Ghar pahunchu to bolna\" — requests location only when enabled.",
            checked = settings.locationRemindersEnabled,
            onCheckedChange = { enabled ->
                onFeatureToggle(SecureKeyStore.KEY_LOCATION_REMINDERS, enabled)
                if (enabled && !permissions.fineLocation) {
                    locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            testTag = "location_reminders_switch"
        )
    }
}

@Composable
fun SettingsScheduledSummariesSection(
    settings: BabySettings,
    onFeatureToggle: (String, Boolean) -> Unit
) {
    val summaryState = buildString {
        append(if (settings.scheduledSummaryMorningEnabled) "Morning ON" else "Morning OFF")
        append(" • ")
        append(if (settings.scheduledSummaryEveningEnabled) "Evening ON" else "Evening OFF")
    }
    ExtrasSectionCard(
        icon = Icons.Default.NotificationsActive,
        title = "Notification Summary Schedule & Privacy",
        summary = summaryState
    ) {
        ExtrasSwitchRow(
            title = "Allow Reading OTPs & Banking Alerts",
            subtitle = "Default OFF: OTPs & bank/UPI amounts are masked and never spoken aloud.",
            checked = settings.readSensitiveNotificationsEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_READ_SENSITIVE_NOTIFS, it) },
            testTag = "read_sensitive_notifs_switch"
        )
        ExtrasSwitchRow(
            title = "Morning Summary (8:30 AM)",
            subtitle = "WorkManager daily Hinglish summary grouped by app.",
            checked = settings.scheduledSummaryMorningEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_SUMMARY_MORNING, it) }
        )
        ExtrasSwitchRow(
            title = "Evening Summary (8:00 PM)",
            subtitle = "Evening catch-up notification summary.",
            checked = settings.scheduledSummaryEveningEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_SUMMARY_EVENING, it) }
        )
        ExtrasSwitchRow(
            title = "Speak Scheduled Summaries Aloud",
            subtitle = "Default OFF (shows notification only); enable to also speak aloud.",
            checked = settings.scheduledSummarySpeakEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_SUMMARY_SPEAK, it) }
        )
    }
}

@Composable
fun SettingsSafetyAndSosSection(
    settings: BabySettings,
    allowedApps: List<AllowedAppEntity>,
    emergencyContacts: List<EmergencyContactEntity>,
    onFeatureToggle: (String, Boolean) -> Unit,
    onSetVoicePin: (String) -> Unit,
    onSaveEmergencyMessage: (String) -> Unit = {},
    onAddAllowedApp: (String) -> Unit,
    onToggleAllowedApp: (AllowedAppEntity, Boolean) -> Unit,
    onRemoveAllowedApp: (String) -> Unit,
    onAddEmergencyContact: (String, String) -> Unit,
    onDeleteEmergencyContact: (Long) -> Unit,
    onTriggerSos: () -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var appInput by remember { mutableStateOf("") }
    var sosName by remember { mutableStateOf("") }
    var sosPhone by remember { mutableStateOf("") }
    var sosCustomMsg by remember(settings.emergencyCustomMessage) { mutableStateOf(settings.emergencyCustomMessage) }

    val sosPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions refreshed on resume */ }

    val safetySummary = buildString {
        append(if (settings.hasVoicePin) "Voice PIN set" else "No PIN")
        append(" • ")
        append("${emergencyContacts.size} SOS contacts")
    }

    ExtrasSectionCard(
        icon = Icons.Default.Shield,
        title = "Safety, Allowed Apps & Emergency SOS",
        summary = safetySummary
    ) {
        ExtrasSwitchRow(
            title = "Biometric Check on Sensitive Actions",
            subtitle = "Fingerprint/device unlock prompt before payments, delete, uninstall, security changes, or SMS/WhatsApp.",
            checked = settings.biometricSafetyEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_BIOMETRIC_SAFETY, it) },
            testTag = "biometric_safety_switch"
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pinInput,
                onValueChange = { pinInput = it },
                label = {
                    Text(
                        if (settings.hasVoicePin) "Voice PIN Active (Keystore Hashed)"
                        else "Set Voice/Confirm PIN"
                    )
                },
                placeholder = { Text(if (settings.hasVoicePin) "Enter new PIN to change" else "e.g. 4321") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("voice_pin_input")
            )
            OutlinedButton(
                onClick = {
                    if (pinInput.isNotBlank()) {
                        onSetVoicePin(pinInput)
                        pinInput = ""
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("save_voice_pin_btn")
            ) {
                Text("Save PIN")
            }
            if (settings.hasVoicePin) {
                TextButton(
                    onClick = { onSetVoicePin("") },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Clear")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExtrasSwitchRow(
            title = "Allowed Apps Only Mode",
            subtitle = "Baby sirf niche approved apps hi open/control kar sakti hai.",
            checked = settings.allowedAppsOnlyEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_ALLOWED_APPS_ONLY, it) },
            testTag = "allowed_apps_switch"
        )
        Text(
            "Blocked screens (Banking/UPI, Password Managers, System Security) are always protected automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = appInput,
                onValueChange = { appInput = it },
                label = { Text("App Name / Package (e.g. WhatsApp)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (appInput.isNotBlank()) {
                        onAddAllowedApp(appInput)
                        appInput = ""
                    }
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) { Icon(Icons.Default.Add, contentDescription = "Add allowed app") }
        }
        allowedApps.forEach { app ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.appLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = app.isAllowed, onCheckedChange = { onToggleAllowedApp(app, it) })
                IconButton(
                    onClick = { onRemoveAllowedApp(app.packageName) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove allowed app")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExtrasSwitchRow(
            title = "Emergency Mode (\"Baby help\")",
            subtitle = "5-second cancellable countdown before sending SMS + live location.",
            checked = settings.emergencyModeEnabled,
            onCheckedChange = { enabled ->
                onFeatureToggle(SecureKeyStore.KEY_EMERGENCY_ENABLED, enabled)
                if (enabled) {
                    sosPermLauncher.launch(
                        arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                }
            },
            testTag = "emergency_mode_switch"
        )
        ExtrasSwitchRow(
            title = "Power-Button Multi-Press SOS Shortcut",
            subtitle = "4 rapid power-button screen toggles within 3.5s starts SOS countdown.",
            checked = settings.emergencyPowerShortcutEnabled,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_EMERGENCY_POWER_SHORTCUT, it) },
            testTag = "emergency_power_shortcut_switch"
        )
        ExtrasSwitchRow(
            title = "Auto-Call First Emergency Contact",
            subtitle = "SOS SMS ke saath first contact ko call bhi lagaye.",
            checked = settings.emergencyAutoCall,
            onCheckedChange = { onFeatureToggle(SecureKeyStore.KEY_EMERGENCY_AUTO_CALL, it) },
            testTag = "emergency_auto_call_switch"
        )
        OutlinedTextField(
            value = sosCustomMsg,
            onValueChange = { sosCustomMsg = it },
            label = { Text("Custom SOS Message (sent with GPS link)") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_custom_msg_input")
        )
        OutlinedButton(
            onClick = { onSaveEmergencyMessage(sosCustomMsg) },
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text("Save SOS Message")
        }

        Text(
            "Emergency Contacts (${emergencyContacts.size}/5 max)",
            style = MaterialTheme.typography.titleSmall
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = sosName, onValueChange = { sosName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = sosPhone, onValueChange = { sosPhone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(
                enabled = emergencyContacts.size < 5,
                onClick = {
                    if (sosName.isNotBlank() && sosPhone.isNotBlank()) {
                        onAddEmergencyContact(sosName, sosPhone)
                        sosName = ""
                        sosPhone = ""
                    }
                },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) { Icon(Icons.Default.Add, contentDescription = "Add emergency contact") }
        }
        emergencyContacts.forEach { c ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.name} • ${c.phoneNumber}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onDeleteEmergencyContact(c.id) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete emergency contact")
                }
            }
        }
        Button(
            onClick = onTriggerSos,
            enabled = settings.emergencyModeEnabled && emergencyContacts.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("test_emergency_sos_btn")
        ) {
            Icon(Icons.Default.Emergency, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Test \"Baby help\" SOS (5s Countdown)")
        }
    }
}

@Composable
private fun ExtrasSectionCard(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    content: @Composable () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ExtrasSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

private fun parseHhMm(input: String, defaultHour: Int, defaultMinute: Int): Pair<Int, Int> {
    val parts = input.trim().split(":")
    if (parts.size != 2) return defaultHour to defaultMinute
    val h = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: defaultHour
    val m = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: defaultMinute
    return h to m
}
