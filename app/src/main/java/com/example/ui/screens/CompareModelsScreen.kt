package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.state.CompareBenchmarkUiState
import com.example.ui.state.CompareCommandCase
import com.example.ui.state.CompareCommandResult
import com.example.ui.state.CompareModelSummary
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareModelsScreen(
    state: CompareBenchmarkUiState,
    onRunBenchmark: () -> Unit,
    onBack: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Mercury 3", "Gemini 3.8 Flash", "Groq Fallback")

    val filteredResults = remember(state.results, selectedFilter) {
        if (selectedFilter == "All") state.results
        else state.results.filter { it.modelLabel == selectedFilter }
    }

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
                            .testTag("compare_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compare AI Models", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "30 Hinglish commands • Pass/Fail, Latency & Tool Accuracy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(spacing.md),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(state.statusText, style = MaterialTheme.typography.titleSmall)
                            if (state.isRunning) {
                                val pct = if (state.progressTotal > 0) {
                                    state.progressCurrent.toFloat() / state.progressTotal
                                } else 0f
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Button(
                                onClick = onRunBenchmark,
                                enabled = !state.isRunning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("run_compare_benchmark_btn")
                            ) {
                                if (state.isRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Running (${state.progressCurrent}/${state.progressTotal})...")
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Run 30 Hinglish Commands")
                                }
                            }
                        }
                    }
                }

                if (state.summaries.isNotEmpty()) {
                    items(state.summaries, key = { it.modelLabel }) { summary ->
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${summary.modelLabel} (${summary.modelId})", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (summary.configured) "${summary.passedCount}/${summary.totalCount} Pass" else "No Key",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (summary.configured && summary.passedCount >= 20) ext.success else MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Text(
                                    text = "Avg Latency: ${summary.avgLatencyMs} ms • Tool Accuracy: ${summary.toolAccuracyPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            filters.forEach { f ->
                                FilterChip(
                                    selected = selectedFilter == f,
                                    onClick = { selectedFilter = f },
                                    label = { Text(f) }
                                )
                            }
                        }
                    }
                }

                items(filteredResults, key = { "${it.modelLabel}_${it.case.id}" }) { row ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(spacing.chipRadius),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (row.passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = if (row.passed) "Pass" else "Fail",
                                tint = if (row.passed) ext.success else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${row.case.id} [${row.modelLabel}] ${row.case.prompt}",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Expected: ${row.case.expectedTool ?: "Baby reply"} • Got: ${row.actualToolOrReply} • ${row.latencyMs}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
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
private fun CompareModelsScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        CompareModelsScreen(
            state = CompareBenchmarkUiState(
                isRunning = false,
                statusText = "Benchmark complete",
                summaries = listOf(
                    CompareModelSummary("Mercury 3", "mercury-3", true, 28, 30, 410, 93),
                    CompareModelSummary("Gemini 3.8 Flash", "gemini-3.8-flash", true, 27, 30, 620, 90)
                ),
                results = listOf(
                    CompareCommandResult(
                        case = CompareCommandCase(1, "Flashlight on karo", "toggle_flashlight", "Device"),
                        modelLabel = "Mercury 3",
                        passed = true,
                        toolAccurate = true,
                        latencyMs = 380,
                        actualToolOrReply = "toggle_flashlight"
                    )
                )
            ),
            onRunBenchmark = {},
            onBack = {}
        )
    }
}
