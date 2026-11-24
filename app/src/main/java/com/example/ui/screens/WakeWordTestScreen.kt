package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.core.security.BabySettings
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.voice.MicPipelineState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordTestScreen(
    settings: BabySettings,
    micState: MicPipelineState,
    rmsLevel: Float,
    wakeScore: Float,
    spotterName: String,
    watchdogRestarts: Int,
    watchdogLogs: List<String>,
    onBack: () -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onBatterySaverToggle: (Boolean) -> Unit,
    onTestWakeTrigger: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val animatedRms by animateFloatAsState(
        targetValue = (rmsLevel / 3_500f).coerceIn(0f, 1f),
        label = "rms_meter"
    )
    val animatedScore by animateFloatAsState(
        targetValue = wakeScore.coerceIn(0f, 1f),
        label = "wake_score"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wake Word & Mic Lab") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("wake_lab_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = spacing.maxContentWidth),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (micState == MicPipelineState.MIC_BUSY) {
                    item {
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(spacing.md),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "MIC_BUSY: Koi aur app microphone use kar rahi hai ya AudioRecord init fail hua. Watchdog backoff (1s -> 2s -> 4s ... max 30s) chal raha hai.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
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
                        Column(
                            modifier = Modifier.padding(spacing.md),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Single 16 kHz Mic Pipeline", style = MaterialTheme.typography.titleMedium)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (micState) {
                                        MicPipelineState.LISTENING -> ext.successContainer
                                        MicPipelineState.CAPTURING -> MaterialTheme.colorScheme.primaryContainer
                                        MicPipelineState.MIC_BUSY -> MaterialTheme.colorScheme.errorContainer
                                        MicPipelineState.PAUSED_FOR_TTS -> ext.warningContainer
                                        MicPipelineState.STOPPED -> MaterialTheme.colorScheme.surface
                                    }
                                ) {
                                    Text(
                                        text = micState.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Engine: $spotterName • 1s circular pre-roll + 300ms post-TTS ignore gate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Live RMS Level", style = MaterialTheme.typography.labelMedium)
                                    Text("${rmsLevel.roundToInt()}", style = MaterialTheme.typography.labelMedium)
                                }
                                LinearProgressIndicator(
                                    progress = { animatedRms },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Wake Confidence", style = MaterialTheme.typography.labelMedium)
                                    Text("${(wakeScore * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                                }
                                LinearProgressIndicator(
                                    progress = { animatedScore },
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onTestWakeTrigger,
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("wake_test_trigger_btn")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Test Wake Trigger")
                                }
                                OutlinedButton(
                                    onClick = { onToggleWakeWord(!settings.wakeWordEnabled) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("wake_pipeline_toggle_btn")
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (settings.wakeWordEnabled) "Stop" else "Start")
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
                        Column(
                            modifier = Modifier.padding(spacing.md),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Sensitivity", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${(settings.wakeWordSensitivity * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.wakeWordSensitivity,
                                onValueChange = onSensitivityChange,
                                valueRange = 0.1f..1.0f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column {
                                        Text("Battery-Saver Duty Cycle", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "Quiet rooms mein frame polling ko halka slow karta hai battery bachane ke liye.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = settings.wakeWordBatterySaver,
                                    onCheckedChange = onBatterySaverToggle,
                                    modifier = Modifier.testTag("wake_battery_saver_switch")
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Honest Android Mic & Battery Notes", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "1. Android 12+ par jab wake mic active hoga, status bar mein green microphone indicator dot dikhega (OS security rule).\n" +
                                        "2. Default spotter speech energy + band cadence gate hai; exact offline phrase spotter ke liye KeywordSpotter interface ko Porcupine/Vosk se swap kiya ja sakta hai.\n" +
                                        "3. Continuous 16 kHz mic ~2–4% extra battery per hour use kar sakta hai; zaroorat na ho toh Quick Settings tile se OFF karein.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Watchdog Log", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Restarts: $watchdogRestarts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (watchdogLogs.isEmpty()) {
                    item {
                        Text(
                            "Abhi koi watchdog event log nahi hai.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(watchdogLogs.asReversed()) { line ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WakeWordTestScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        WakeWordTestScreen(
            settings = BabySettings(wakeWordEnabled = true, wakeWordSensitivity = 0.7f, wakeWordBatterySaver = true),
            micState = MicPipelineState.LISTENING,
            rmsLevel = 920f,
            wakeScore = 0.54f,
            spotterName = "Acoustic Speech-Band KWS (Replaceable)",
            watchdogRestarts = 1,
            watchdogLogs = listOf("[10:14:22] 16kHz AudioRecord active"),
            onBack = {},
            onToggleWakeWord = {},
            onSensitivityChange = {},
            onBatterySaverToggle = {},
            onTestWakeTrigger = {}
        )
    }
}
