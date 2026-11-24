package com.example.ui.components

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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabySettings
import com.example.core.security.SecureKeyStore
import com.example.ui.state.BabyUiPreferences
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.viewmodel.BabyHeaderStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickStatusSheet(
    headerStatus: BabyHeaderStatus,
    settings: BabySettings,
    uiPrefs: BabyUiPreferences,
    onDismiss: () -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onAutoReplyToggle: (Boolean) -> Unit,
    onAutoReplyModeChange: (AutoReplyMode) -> Unit,
    onDataSaverToggle: (Boolean) -> Unit,
    onTtsToggle: (Boolean) -> Unit,
    onSoundCuesToggle: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs)
                .padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.quick_status_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Current state: ${headerStatusLabel(headerStatus)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                TextButton(onClick = {
                    onDismiss()
                    onOpenPermissions()
                }) {
                    Text("Permissions")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            QuickToggleRow(
                icon = Icons.Default.Mic,
                title = "Wake Word (\"Hey Baby\" / \"Baby\")",
                subtitle = if (settings.wakeWordEnabled) "Background mic ready" else "Paused — tap Mic manually",
                checked = settings.wakeWordEnabled,
                onCheckedChange = onWakeWordToggle,
                testTag = "quick_status_wake_switch"
            )

            QuickToggleRow(
                icon = Icons.Default.MarkChatRead,
                title = "Auto-Reply (${settings.autoReplyMode.label})",
                subtitle = "WhatsApp, SMS & missed calls auto-reply",
                checked = settings.autoReplyEnabled,
                onCheckedChange = { enabled ->
                    onAutoReplyToggle(enabled)
                    if (!enabled) onAutoReplyModeChange(AutoReplyMode.OFF)
                    else if (settings.autoReplyMode == AutoReplyMode.OFF) onAutoReplyModeChange(AutoReplyMode.BUSY)
                },
                testTag = "quick_status_autoreply_switch"
            )

            if (settings.autoReplyEnabled) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs)
                ) {
                    listOf(AutoReplyMode.BUSY, AutoReplyMode.DRIVING, AutoReplyMode.SLEEPING).forEach { mode ->
                        FilterChip(
                            selected = settings.autoReplyMode == mode,
                            onClick = { onAutoReplyModeChange(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }

            QuickToggleRow(
                icon = Icons.Default.DataSaverOn,
                title = "Data Saver Mode",
                subtitle = "Fewer API calls; skip screenshots & camera uploads",
                checked = settings.dataSaverEnabled,
                onCheckedChange = onDataSaverToggle,
                testTag = "quick_status_datasaver_switch"
            )

            QuickToggleRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Speak Replies Aloud (TTS)",
                subtitle = "Voice answers in ${settings.preferredLanguage.displayName}",
                checked = settings.ttsRepliesEnabled,
                onCheckedChange = onTtsToggle,
                testTag = "quick_status_tts_switch"
            )

            QuickToggleRow(
                icon = Icons.Default.MusicNote,
                title = "Listening Sound Cues",
                subtitle = "Subtle start & stop beep on voice capture",
                checked = uiPrefs.listeningSoundCuesEnabled,
                onCheckedChange = onSoundCuesToggle,
                testTag = "quick_status_sound_cues_switch"
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(spacing.chipRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        text = "Brain: ${settings.preferredBrain.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun QuickToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

fun headerStatusLabel(status: BabyHeaderStatus): String = when (status) {
    BabyHeaderStatus.LISTENING -> "Listening"
    BabyHeaderStatus.THINKING -> "Thinking"
    BabyHeaderStatus.SPEAKING -> "Speaking"
    BabyHeaderStatus.LOCKED_QUEUE_PENDING -> "Locked-queue pending"
    BabyHeaderStatus.RATE_LIMITED -> "Rate limited"
    BabyHeaderStatus.OFFLINE -> "Offline"
    BabyHeaderStatus.NO_API_KEY -> "No API key"
    BabyHeaderStatus.PAUSED -> "Paused"
}
