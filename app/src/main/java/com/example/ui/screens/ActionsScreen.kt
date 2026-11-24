package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabySettings
import com.example.core.security.SecureKeyStore
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback

private data class ActionFeatureItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val statusBadge: String,
    val prefKey: String?,
    val onOpen: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    settings: BabySettings,
    routinesCount: Int,
    commandsCount: Int,
    macrosCount: Int,
    activeRemindersCount: Int,
    memoriesCount: Int,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onToggleFeature: (String, Boolean) -> Unit,
    onAutoReplyToggle: (Boolean) -> Unit,
    onOpenRoutinesTab: (Int) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettingsSection: (String) -> Unit,
    onRunNotifSummaryNow: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    var searchQuery by remember { mutableStateOf("") }

    val features = remember(
        settings, routinesCount, commandsCount, macrosCount, activeRemindersCount, memoriesCount
    ) {
        listOf(
            ActionFeatureItem(
                id = "routines",
                title = "Routines",
                subtitle = "$routinesCount saved • Morning, night & schedules",
                icon = Icons.Default.AutoAwesome,
                enabled = settings.routinesEnabled,
                statusBadge = if (settings.routinesEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_ROUTINES_ENABLED,
                onOpen = { onOpenRoutinesTab(0) }
            ),
            ActionFeatureItem(
                id = "commands",
                title = "Commands",
                subtitle = "$commandsCount custom multi-action phrases",
                icon = Icons.Default.Bolt,
                enabled = settings.customCommandsEnabled,
                statusBadge = if (settings.customCommandsEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_CUSTOM_COMMANDS_ENABLED,
                onOpen = { onOpenRoutinesTab(1) }
            ),
            ActionFeatureItem(
                id = "macros",
                title = "Macros",
                subtitle = "$macrosCount recorded UI tap & input replays",
                icon = Icons.Default.FiberManualRecord,
                enabled = settings.macrosEnabled,
                statusBadge = if (settings.macrosEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_MACROS_ENABLED,
                onOpen = { onOpenRoutinesTab(2) }
            ),
            ActionFeatureItem(
                id = "reminders",
                title = "Reminders",
                subtitle = "$activeRemindersCount active alarms & geofences",
                icon = Icons.Default.Alarm,
                enabled = settings.remindersEnabled,
                statusBadge = if (settings.remindersEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_REMINDERS_ENABLED,
                onOpen = onOpenReminders
            ),
            ActionFeatureItem(
                id = "memory",
                title = "Memory",
                subtitle = "$memoriesCount saved nicknames, facts & habits",
                icon = Icons.Default.Psychology,
                enabled = settings.memoryEnabled,
                statusBadge = if (settings.memoryEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_MEMORY_ENABLED,
                onOpen = onOpenMemory
            ),
            ActionFeatureItem(
                id = "autoreply",
                title = "Auto-reply",
                subtitle = if (settings.autoReplyEnabled && settings.autoReplyMode != AutoReplyMode.OFF) {
                    "Mode: ${settings.autoReplyMode.label}"
                } else {
                    "WhatsApp, SMS & missed calls"
                },
                icon = Icons.Default.MarkChatRead,
                enabled = settings.autoReplyEnabled,
                statusBadge = if (settings.autoReplyEnabled) settings.autoReplyMode.label.uppercase() else "OFF",
                prefKey = SecureKeyStore.KEY_AUTO_REPLY_ENABLED,
                onOpen = { onOpenSettingsSection("auto_reply") }
            ),
            ActionFeatureItem(
                id = "notif_summary",
                title = "Notification Summary",
                subtitle = "Hinglish digest grouped by people & promos",
                icon = Icons.Default.NotificationsActive,
                enabled = settings.notificationSummaryEnabled,
                statusBadge = if (settings.notificationSummaryEnabled) "ON" else "OFF",
                prefKey = SecureKeyStore.KEY_NOTIFICATION_SUMMARY_ENABLED,
                onOpen = { onOpenSettingsSection("notifications") }
            )
        )
    }

    val filtered = remember(features, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) features
        else features.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
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
                        text = stringResource(R.string.actions_title).withDevanagariFallback(),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.actions_subtitle).withDevanagariFallback(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search routines, macros, memory…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(spacing.chipRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("actions_search_field")
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = if (maxWidth >= 600.dp) 3 else 2
                    val rows = filtered.chunked(columns)

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        items(rows.size, key = { idx -> rows[idx].joinToString { it.id } }) { rowIndex ->
                            val rowItems = rows[rowIndex]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                rowItems.forEach { item ->
                                    ActionHubCard(
                                        item = item,
                                        onToggle = { enabled ->
                                            if (item.id == "autoreply") {
                                                onAutoReplyToggle(enabled)
                                            } else if (item.prefKey != null) {
                                                onToggleFeature(item.prefKey, enabled)
                                            }
                                        },
                                        extraFooter = if (item.id == "notif_summary" && settings.notificationSummaryEnabled) {
                                            {
                                                TextButton(
                                                    onClick = onRunNotifSummaryNow,
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                                ) {
                                                    Text("Summarize Now")
                                                }
                                            }
                                        } else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(columns - rowItems.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionHubCard(
    item: ActionFeatureItem,
    onToggle: (Boolean) -> Unit,
    extraFooter: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .heightIn(min = 164.dp)
            .clip(RoundedCornerShape(spacing.cardRadius))
            .clickable { item.onOpen() }
            .testTag("action_card_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (item.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("action_toggle_${item.id}")
                )
            }

            Surface(
                color = if (item.enabled) ext.successContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = item.statusBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.enabled) ext.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                extraFooter?.invoke() ?: Spacer(Modifier.width(1.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open ${item.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Actions Hub Dark")
@Composable
private fun ActionsScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        ActionsScreen(
            settings = BabySettings(autoReplyEnabled = true, autoReplyMode = AutoReplyMode.BUSY),
            routinesCount = 4,
            commandsCount = 2,
            macrosCount = 1,
            activeRemindersCount = 3,
            memoriesCount = 5,
            onToggleFeature = { _, _ -> },
            onAutoReplyToggle = {},
            onOpenRoutinesTab = {},
            onOpenReminders = {},
            onOpenMemory = {},
            onOpenSettingsSection = {},
            onRunNotifSummaryNow = {}
        )
    }
}
