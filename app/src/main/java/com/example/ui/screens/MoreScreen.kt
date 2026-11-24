package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.core.security.BabySettings
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import com.example.util.PermissionSnapshot

private data class GlobalSearchDestination(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val onOpen: () -> Unit
)

@Composable
fun MoreScreen(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    emergencyContactsCount: Int,
    actionLogsCount: Int,
    memoriesCount: Int,
    onOpenSettings: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRoutinesTab: (Int) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenMemory: () -> Unit,
    onExportActionLogsShare: () -> Unit,
    onExportActionLogsToUri: (Uri) -> Unit,
    onReopenOnboarding: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    var searchQuery by remember { mutableStateOf("") }
    var showAboutDialog by remember { mutableStateOf(false) }

    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportActionLogsToUri(uri)
    }

    val permTotal = 9
    val permGranted = remember(permissions) {
        listOf(
            permissions.recordAudio,
            permissions.postNotifications,
            permissions.accessibility,
            permissions.notificationListener,
            permissions.overlayBubble,
            permissions.batteryOptimizationIgnored,
            permissions.callPhone && permissions.readContacts && permissions.sendSms,
            permissions.writeSettings,
            permissions.dndAccess
        ).count { it }
    }

    val allSearchIndex = remember(settings, emergencyContactsCount, permGranted) {
        listOf(
            GlobalSearchDestination("API Keys (Groq & Gemini)", "Encrypted keystore setup & live test", "api key groq gemini paste") {
                onOpenSettings("keys")
            },
            GlobalSearchDestination("Appearance & Theme", "Dark, Light, AMOLED black, Dynamic Color, sound cues", "theme dark light amoled dynamic color sound") {
                onOpenSettings("appearance")
            },
            GlobalSearchDestination("AI Brain Routing & Models", "Auto, Groq, Gemini, Llama, Whisper, fallback", "ai brain model routing llama whisper fallback") {
                onOpenSettings("ai")
            },
            GlobalSearchDestination("Wake Word & Voice", "\"Hey Baby\", \"Baby\", sensitivity, TTS, floating bubble", "wake word sensitivity voice tts bubble language") {
                onOpenSettings("voice")
            },
            GlobalSearchDestination("Auto-Reply Modes", "Busy, Driving, Sleeping, templates, schedule, contact rules", "auto reply whatsapp sms busy driving sleeping schedule") {
                onOpenSettings("auto_reply")
            },
            GlobalSearchDestination("Notification Summary Schedule", "Morning/Evening digest & OTP masking", "notification summary otp banking schedule") {
                onOpenSettings("notifications")
            },
            GlobalSearchDestination("Safety & Voice PIN", "Biometric check, Voice PIN, Allowed Apps Only", "safety voice pin biometric allowed apps security") {
                onOpenSettings("safety")
            },
            GlobalSearchDestination("Emergency SOS", "$emergencyContactsCount contacts • Power shortcut & auto-call", "emergency sos help location power shortcut") {
                onOpenSettings("emergency")
            },
            GlobalSearchDestination("Danger Zone", "Wipe memory, clear logs, clear API keys, reset setup", "danger wipe delete reset clear keys") {
                onOpenSettings("danger")
            },
            GlobalSearchDestination("Permissions Checklist", "$permGranted/$permTotal granted • Mic, Accessibility, Battery, Overlay", "permissions accessibility battery overlay dnd") {
                onOpenPermissions()
            },
            GlobalSearchDestination("Daily Routines", "Good morning, Good night & schedules", "routines schedule morning night") {
                onOpenRoutinesTab(0)
            },
            GlobalSearchDestination("Custom Commands & Macros", "Movie mode & recorded screen macros", "commands macros record replay") {
                onOpenRoutinesTab(1)
            },
            GlobalSearchDestination("Reminders & Places", "Alarms & Home/Work geofencing", "reminders alarm location geofence") {
                onOpenReminders()
            },
            GlobalSearchDestination("Memory", "$memoriesCount saved nicknames & facts", "memory yaad rakhna nickname") {
                onOpenMemory()
            }
        )
    }

    val searchMatches = remember(searchQuery, allSearchIndex) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else allSearchIndex.filter {
            it.title.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.more_title).withDevanagariFallback(),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.more_subtitle).withDevanagariFallback(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_all_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(spacing.chipRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("more_global_search_field")
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("more_list"),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (searchQuery.isNotBlank()) {
                    if (searchMatches.isEmpty()) {
                        item {
                            Text(
                                text = "Koi matching feature ya setting nahi mili.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(spacing.sm)
                            )
                        }
                    } else {
                        items(searchMatches, key = { it.title }) { match ->
                            MoreRowCard(
                                icon = Icons.Default.Search,
                                title = match.title,
                                subtitle = match.subtitle,
                                badge = "Open",
                                badgePositive = true,
                                testTag = "search_match_${match.title}",
                                onClick = match.onOpen
                            )
                        }
                    }
                } else {
                    item {
                        MoreRowCard(
                            icon = Icons.Default.AutoAwesome,
                            title = "Daily Routines",
                            subtitle = "Good morning, Good night & scheduled routines",
                            badge = "Routines",
                            badgePositive = true,
                            testTag = "more_open_routines",
                            onClick = { onOpenRoutinesTab(0) }
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Bolt,
                            title = "Custom Commands",
                            subtitle = "One-phrase shortcuts like Movie mode",
                            badge = "Commands",
                            badgePositive = true,
                            testTag = "more_open_commands",
                            onClick = { onOpenRoutinesTab(1) }
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.PlayCircleOutline,
                            title = "Macros",
                            subtitle = "Record & replay multi-step screen actions",
                            badge = "Macros",
                            badgePositive = true,
                            testTag = "more_open_macros",
                            onClick = { onOpenRoutinesTab(2) }
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Alarm,
                            title = "Reminders & Places",
                            subtitle = "Exact time alarms & Home/Work geofences",
                            badge = "Reminders",
                            badgePositive = true,
                            testTag = "more_open_reminders",
                            onClick = onOpenReminders
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Memory,
                            title = "Memory",
                            subtitle = "Saved nicknames, contacts & personal facts",
                            badge = "$memoriesCount saved",
                            badgePositive = memoriesCount > 0,
                            testTag = "more_open_memory",
                            onClick = onOpenMemory
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Settings,
                            title = "Settings",
                            subtitle = "API keys, Theme, AI routing, Wake word & Auto-reply",
                            badge = if (settings.hasAnyApiKey) "Ready" else "Add Key",
                            badgePositive = settings.hasAnyApiKey,
                            testTag = "more_open_settings",
                            onClick = { onOpenSettings("") }
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Security,
                            title = "Permissions Checklist",
                            subtitle = "Mic, Accessibility, Battery survival & Overlay setup",
                            badge = "$permGranted/$permTotal",
                            badgePositive = permGranted >= 6,
                            testTag = "more_open_permissions",
                            onClick = onOpenPermissions
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Shield,
                            title = "Safety & Voice PIN",
                            subtitle = "Confirm sensitive actions, biometric lock & Allowed Apps",
                            badge = if (settings.confirmSensitiveActions) "Protected" else "Relaxed",
                            badgePositive = settings.confirmSensitiveActions,
                            testTag = "more_open_safety",
                            onClick = { onOpenSettings("safety") }
                        )
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Emergency,
                            title = "Emergency SOS",
                            subtitle = "$emergencyContactsCount contact(s) • 5s cancellable alert + GPS",
                            badge = if (settings.emergencyModeEnabled) "ON" else "OFF",
                            badgePositive = settings.emergencyModeEnabled,
                            testTag = "more_open_emergency",
                            onClick = { onOpenSettings("emergency") }
                        )
                    }
                    item {
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Backup & Export", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "$actionLogsCount action logs & $memoriesCount memories stored locally",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                    OutlinedButton(
                                        onClick = { saveTxtLauncher.launch("baby_action_log.txt") },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Save .txt")
                                    }
                                    OutlinedButton(
                                        onClick = onExportActionLogsShare,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Share Log")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        MoreRowCard(
                            icon = Icons.Default.Info,
                            title = "About Baby",
                            subtitle = "v${BuildConfig.VERSION_NAME} • On-device Room + Keystore • Setup Wizard",
                            badge = "Info",
                            badgePositive = true,
                            testTag = "more_open_about",
                            onClick = { showAboutDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Baby Assistant") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        "Dual-brain Android AI assistant powered by your own Groq & Gemini API keys. Zero login, Room local database, and Keystore-encrypted credentials.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Safety: Protected banking/UPI screens & password fields are automatically skipped during automation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ext.success
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAboutDialog = false
                    onReopenOnboarding()
                }) {
                    Text("Run Setup Wizard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun MoreRowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    badgePositive: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(spacing.cardRadius))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                shape = CircleShape,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                color = if (badgePositive) ext.successContainer else ext.warningContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (badgePositive) ext.onSuccessContainer else ext.onWarningContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true, name = "More Hub Dark")
@Composable
private fun MoreScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        MoreScreen(
            settings = BabySettings(groqApiKey = "gsk_demo"),
            permissions = PermissionSnapshot(
                recordAudio = true, postNotifications = true, accessibility = true,
                notificationListener = true, overlayBubble = false, batteryOptimizationIgnored = true,
                callPhone = true, sendSms = true, readContacts = true, writeSettings = false, dndAccess = true
            ),
            emergencyContactsCount = 2,
            actionLogsCount = 14,
            memoriesCount = 6,
            onOpenSettings = {},
            onOpenPermissions = {},
            onOpenRoutinesTab = {},
            onOpenReminders = {},
            onOpenMemory = {},
            onExportActionLogsShare = {},
            onExportActionLogsToUri = {},
            onReopenOnboarding = {}
        )
    }
}
