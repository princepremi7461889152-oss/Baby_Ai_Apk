package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.core.telemetry.ExecutionPathType
import com.example.core.telemetry.LatencyRecord
import com.example.data.local.AppAliasEntity
import com.example.domain.tools.AppLaunchAttemptLog
import com.example.domain.tools.AppOpenStatus
import com.example.domain.tools.IndexedApp
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LatencyDebugScreen(
    records: List<LatencyRecord>,
    aliases: List<AppAliasEntity>,
    installedApps: List<IndexedApp>,
    launchAttempts: List<AppLaunchAttemptLog>,
    onBack: () -> Unit,
    onClearLatency: () -> Unit,
    onRefreshApps: () -> Unit,
    onSaveAlias: (alias: String, packageName: String, appLabel: String) -> Unit,
    onDeleteAlias: (alias: String) -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }

    var aliasInput by remember { mutableStateOf("") }
    var targetAppInput by remember { mutableStateOf("") }
    var targetPkgInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Debug & App Aliases") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("latency_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTab == 0 && records.isNotEmpty()) {
                        IconButton(onClick = onClearLatency, modifier = Modifier.testTag("latency_clear_btn")) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear latency log")
                        }
                    } else if (selectedTab == 1) {
                        IconButton(onClick = onRefreshApps, modifier = Modifier.testTag("refresh_apps_btn")) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh app index")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = spacing.maxContentWidth)
            ) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Latency (${records.size})") },
                        icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("App Aliases (${aliases.size})") },
                        icon = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                if (selectedTab == 0) {
                    val fastAvg = records.filter { it.pathType == ExecutionPathType.FAST_PATH }
                        .map { it.totalMs }.average().takeIf { !it.isNaN() }?.toLong()
                    val aiAvgFirst = records.filter { it.pathType != ExecutionPathType.FAST_PATH && it.firstSpokenMs > 0 }
                        .map { it.firstSpokenMs }.average().takeIf { !it.isNaN() }?.toLong()

                    LazyColumn(
                        contentPadding = PaddingValues(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(spacing.cardRadius),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Speed Targets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Simple Fast Path: < 500 ms (no AI call)\nFirst spoken word of AI reply: < 2000 ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            "Avg Fast Path: ${fastAvg?.let { "${it} ms" } ?: "--"}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Avg AI First Word: ${aiAvgFirst?.let { "${it} ms" } ?: "--"}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }

                        if (records.isEmpty()) {
                            item {
                                Text(
                                    "Abhi koi command latency record nahi hai. Chat ya voice se ek command chalao (e.g. 'Flashlight on karo').",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(spacing.sm)
                                )
                            }
                        } else {
                            items(records, key = { it.id }) { rec ->
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(spacing.md),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                rec.commandPreview,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                color = if (rec.metTarget) ext.successContainer else ext.warningContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "${rec.totalMs} ms",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (rec.metTarget) ext.onSuccessContainer else ext.onWarningContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            "${rec.pathType.label} • Status: ${rec.status}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "STT: ${rec.sttMs}ms | Match/LLM: ${rec.intentOrLlmMs}ms | Tool: ${rec.toolMs}ms | TTS: ${rec.ttsMs}ms | FirstWord: ${rec.firstSpokenMs}ms",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(spacing.cardRadius),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Add / Edit App Alias (${installedApps.size} installed apps indexed)",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    OutlinedTextField(
                                        value = aliasInput,
                                        onValueChange = { aliasInput = it },
                                        label = { Text("Spoken Hinglish alias (e.g. yutub, gpay)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("alias_name_field")
                                    )
                                    OutlinedTextField(
                                        value = targetAppInput,
                                        onValueChange = { targetAppInput = it },
                                        label = { Text("Target App Label (e.g. YouTube)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("alias_app_label_field")
                                    )
                                    OutlinedTextField(
                                        value = targetPkgInput,
                                        onValueChange = { targetPkgInput = it },
                                        label = { Text("Package Name optional (e.g. com.google.android.youtube)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            if (aliasInput.isNotBlank() && (targetAppInput.isNotBlank() || targetPkgInput.isNotBlank())) {
                                                onSaveAlias(aliasInput, targetPkgInput, targetAppInput)
                                                aliasInput = ""
                                                targetAppInput = ""
                                                targetPkgInput = ""
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("save_alias_btn")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Save App Alias")
                                    }
                                }
                            }
                        }

                        if (launchAttempts.isNotEmpty()) {
                            item {
                                Text("Recent App Open Attempts", style = MaterialTheme.typography.titleSmall)
                            }
                            items(launchAttempts.take(8)) { attempt ->
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(spacing.sm)) {
                                        Text(
                                            "\"${attempt.query}\" -> ${attempt.status.name}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (attempt.status == AppOpenStatus.OPENED) ext.success else MaterialTheme.colorScheme.primary
                                        )
                                        if (attempt.resolvedPackage.isNotBlank()) {
                                            Text(attempt.resolvedPackage, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(attempt.detail, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        item {
                            Text("Saved Aliases", style = MaterialTheme.typography.titleSmall)
                        }

                        items(aliases, key = { it.alias }) { entry ->
                            Card(
                                shape = RoundedCornerShape(spacing.cardRadius),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${entry.alias} -> ${entry.appLabel}",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            "${entry.packageName} • ${if (entry.isUserEdited) "Custom" else "Default"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(onClick = { onDeleteAlias(entry.alias) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LatencyDebugScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        LatencyDebugScreen(
            records = listOf(
                LatencyRecord(
                    id = 1L,
                    commandPreview = "Flashlight on karo",
                    pathType = ExecutionPathType.FAST_PATH,
                    sttMs = 0L,
                    intentOrLlmMs = 14L,
                    toolMs = 0L,
                    ttsMs = 18L,
                    firstSpokenMs = 14L,
                    totalMs = 35L
                ),
                LatencyRecord(
                    id = 2L,
                    commandPreview = "Aaj Delhi ka mausam kaisa hai?",
                    pathType = ExecutionPathType.GEMINI,
                    sttMs = 420L,
                    intentOrLlmMs = 950L,
                    toolMs = 0L,
                    ttsMs = 120L,
                    firstSpokenMs = 1380L,
                    totalMs = 1520L
                )
            ),
            aliases = listOf(
                AppAliasEntity("yutub", "com.google.android.youtube", "YouTube", isUserEdited = false)
            ),
            installedApps = emptyList(),
            launchAttempts = emptyList(),
            onBack = {},
            onClearLatency = {},
            onRefreshApps = {},
            onSaveAlias = { _, _, _ -> },
            onDeleteAlias = {}
        )
    }
}
