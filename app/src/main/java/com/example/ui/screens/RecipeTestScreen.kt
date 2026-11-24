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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.data.local.SearchRecipeEntity
import com.example.features.searchplay.SearchPlayEngine
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeTestScreen(
    recipes: List<SearchRecipeEntity>,
    lastRunOutput: String?,
    onBack: () -> Unit,
    onSaveRecipeJson: (appKey: String, rawJson: String) -> Unit,
    onResetRecipe: (appKey: String) -> Unit,
    onRunSearchPlayCommand: (String) -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    var testPhrase by remember { mutableStateOf("YouTube par Arijit Singh ka gaana bajao") }
    var editingRecipe by remember { mutableStateOf<SearchRecipeEntity?>(null) }
    var jsonDraft by remember { mutableStateOf("") }
    var jsonError by remember { mutableStateOf<String?>(null) }

    val parsedIntent = remember(testPhrase) {
        SearchPlayEngine.extractSearchPlayIntent(testPhrase)
    }

    val samplePhrases = listOf(
        "YouTube par Arijit Singh ka gaana bajao",
        "Spotify pe Kesariya chalao",
        "Amazon par wireless earbuds dhoondo",
        "Maps par India Gate ka route dikhao"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search & Play Recipes") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("recipe_test_back_btn")
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
                item {
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = ext.warningContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = ext.onWarningContainer)
                            Text(
                                text = "Honest Note: Third-party apps ke updates ke saath unke Accessibility buttons aur labels badal sakte hain. Yahan aap kisi bhi app ki JSON recipe inspect ya tweak kar sakte hain. Banking/UPI apps safety ke liye blocked hain.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.onWarningContainer
                            )
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
                            Text("Test Intent Parser & Live Run", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = testPhrase,
                                onValueChange = { testPhrase = it },
                                label = { Text("Hinglish Search-and-Play Phrase") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recipe_test_phrase_input")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                samplePhrases.forEach { phrase ->
                                    AssistChip(
                                        onClick = { testPhrase = phrase },
                                        label = { Text(phrase, maxLines = 1) }
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (parsedIntent != null) {
                                        "Matched -> app=${parsedIntent.appKey} | query=\"${parsedIntent.query}\" | autoPlay=${parsedIntent.autoPlay}"
                                    } else {
                                        "Local regex ne parse nahi kiya (LLM search_in_app tool fallback karega)."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (parsedIntent != null) ext.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            Button(
                                onClick = { onRunSearchPlayCommand(testPhrase) },
                                enabled = testPhrase.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("recipe_live_run_btn")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Run Search-and-Play Now")
                            }

                            if (!lastRunOutput.isNullOrBlank()) {
                                Text(
                                    text = "Last Output: $lastRunOutput",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "10 Bundled App Recipes (JSON Editable)",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(recipes, key = { it.appKey }) { recipe ->
                    val parsedSpec = remember(recipe.recipeJson) {
                        SearchPlayEngine.parseRecipeJson(recipe.recipeJson).getOrNull()
                    }
                    Card(
                        shape = RoundedCornerShape(spacing.cardRadius),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(spacing.md),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(recipe.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        recipe.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { onResetRecipe(recipe.appKey) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Reset ${recipe.displayName}")
                                    }
                                    IconButton(
                                        onClick = {
                                            editingRecipe = recipe
                                            jsonDraft = recipe.recipeJson
                                            jsonError = null
                                        },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit ${recipe.displayName} JSON")
                                    }
                                }
                            }

                            if (parsedSpec != null) {
                                Text(
                                    text = "Mode: ${parsedSpec.launchMode.name} • Skip: ${parsedSpec.skipLabels.joinToString(", ").ifBlank { "none" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingRecipe?.let { target ->
        AlertDialog(
            onDismissRequest = { editingRecipe = null },
            title = { Text("Edit Recipe: ${target.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = jsonDraft,
                        onValueChange = {
                            jsonDraft = it
                            jsonError = SearchPlayEngine.parseRecipeJson(it).exceptionOrNull()?.message
                        },
                        label = { Text("Recipe JSON") },
                        minLines = 8,
                        maxLines = 14,
                        isError = jsonError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (jsonError != null) {
                        Text(
                            text = "Invalid JSON: $jsonError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveRecipeJson(target.appKey, jsonDraft)
                        editingRecipe = null
                    },
                    enabled = jsonError == null
                ) {
                    Text("Save JSON")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingRecipe = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeTestScreenPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        RecipeTestScreen(
            recipes = SearchPlayEngine.DEFAULT_RECIPES.map {
                SearchRecipeEntity(it.appKey, it.displayName, it.packageName, it.toJsonString())
            },
            lastRunOutput = null,
            onBack = {},
            onSaveRecipeJson = { _, _ -> },
            onResetRecipe = {},
            onRunSearchPlayCommand = {}
        )
    }
}
