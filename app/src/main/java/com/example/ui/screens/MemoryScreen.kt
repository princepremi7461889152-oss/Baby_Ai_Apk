package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.data.local.MemoryEntity
import com.example.ui.components.BabyEmptyStateCard
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    memories: List<MemoryEntity>,
    onBack: () -> Unit,
    onSaveMemory: (String, String, String) -> Unit,
    onUpdateMemory: (MemoryEntity) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onWipeAll: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }

    val categories = remember { listOf("ALL", "NICKNAME", "PREFERENCE", "FACT", "APP_HABIT") }

    val filteredMemories = remember(memories, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        memories.filter { mem ->
            val catOk = selectedCategory == "ALL" || mem.category.equals(selectedCategory, ignoreCase = true)
            val qOk = q.isEmpty() ||
                mem.memoryKey.lowercase().contains(q) ||
                mem.memoryValue.lowercase().contains(q) ||
                mem.category.lowercase().contains(q)
            catOk && qOk
        }
    }

    fun deleteWithUndo(mem: MemoryEntity) {
        onDeleteMemory(mem.id)
        scope.launch {
            val res = snackbarHostState.showSnackbar(
                message = "Deleted \"${mem.memoryKey}\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (res == SnackbarResult.ActionPerformed) {
                onSaveMemory(mem.memoryKey, mem.memoryValue, mem.category)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
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
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.sm, vertical = spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("memory_back_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Baby Memory", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "On-device nicknames, habits & \"yaad rakhna\" (${memories.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { showAddDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("add_memory_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add memory")
                            }
                            if (memories.isNotEmpty()) {
                                IconButton(
                                    onClick = { showWipeConfirm = true },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("wipe_all_memory_btn")
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = "Sab bhool jao",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search nicknames, facts, preferences...") },
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
                                .padding(horizontal = spacing.xs)
                                .testTag("memory_search_input")
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categories) { cat ->
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = { selectedCategory = cat },
                                    label = { Text(if (cat == "ALL") "All" else cat.replace('_', ' ')) },
                                    modifier = Modifier.testTag("memory_filter_$cat")
                                )
                            }
                        }
                    }
                }

                if (filteredMemories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(spacing.md),
                        contentAlignment = Alignment.Center
                    ) {
                        BabyEmptyStateCard(
                            icon = Icons.Default.Psychology,
                            title = if (memories.isEmpty()) "Abhi koi memory saved nahi hai" else "Koi matching memory nahi mili",
                            subtitle = if (memories.isEmpty()) {
                                "Bolo \"yaad rakhna Mummy ka number 98...\" ya '+' se add karein."
                            } else {
                                "Search ya category filter change karke dekho."
                            },
                            ctaLabel = "Add Memory",
                            onCtaClick = { showAddDialog = true }
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredMemories, key = { it.id }) { mem ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            deleteWithUndo(mem)
                                            true
                                        }
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            editingMemory = mem
                                            false
                                        }
                                        SwipeToDismissBoxValue.Settled -> false
                                    }
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val dir = dismissState.dismissDirection
                                    val isDelete = dir == SwipeToDismissBoxValue.EndToStart
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(spacing.cardRadius))
                                            .background(
                                                if (isDelete) MaterialTheme.colorScheme.errorContainer
                                                else MaterialTheme.colorScheme.secondaryContainer
                                            )
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isDelete) Icons.Default.DeleteOutline else Icons.Default.Edit,
                                                contentDescription = if (isDelete) "Delete memory" else "Edit memory",
                                                tint = if (isDelete) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (isDelete) "Delete" else "Edit",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isDelete) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            ) {
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("memory_card_${mem.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(spacing.md),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                            shape = CircleShape,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Psychology,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = mem.category.replace('_', ' '),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Text(
                                                    mem.memoryKey.withDevanagariFallback(),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                mem.memoryValue.withDevanagariFallback(),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        IconButton(
                                            onClick = { editingMemory = mem },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit memory")
                                        }
                                        IconButton(
                                            onClick = { deleteWithUndo(mem) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete memory")
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

    if (showAddDialog) {
        MemoryEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { k, v, c ->
                onSaveMemory(k, v, c)
                showAddDialog = false
            }
        )
    }

    editingMemory?.let { current ->
        MemoryEditDialog(
            initial = current,
            onDismiss = { editingMemory = null },
            onConfirm = { k, v, c ->
                onUpdateMemory(current.copy(memoryKey = k, memoryValue = v, category = c))
                editingMemory = null
            }
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Sab bhool jao?") },
            text = { Text("Kya aap pakka Baby ki sabhi ${memories.size} saved memories delete karna chahte hain?") },
            confirmButton = {
                Button(
                    onClick = {
                        showWipeConfirm = false
                        onWipeAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete All") }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditDialog(
    initial: MemoryEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var key by remember { mutableStateOf(initial?.memoryKey ?: "") }
    var value by remember { mutableStateOf(initial?.memoryValue ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "NICKNAME") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Save Memory" else "Edit Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NICKNAME", "PREFERENCE", "APP_HABIT", "FACT").forEach { cat ->
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat) })
                    }
                }
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key (e.g. Mummy)") })
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value / Note") })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (value.isNotBlank()) {
                        onConfirm(key.ifBlank { value.take(20) }, value, category)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true)
@Composable
private fun MemoryScreenPreviewDark() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        MemoryScreen(
            memories = listOf(
                MemoryEntity(id = 1L, category = "NICKNAME", memoryKey = "Mummy", memoryValue = "Sunita Sharma (+91 98111 22334)"),
                MemoryEntity(id = 2L, category = "PREFERENCE", memoryKey = "Coffee", memoryValue = "Less sugar, black coffee in morning")
            ),
            onBack = {},
            onSaveMemory = { _, _, _ -> },
            onUpdateMemory = {},
            onDeleteMemory = {},
            onWipeAll = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MemoryScreenEmptyLightPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        MemoryScreen(
            memories = emptyList(),
            onBack = {},
            onSaveMemory = { _, _, _ -> },
            onUpdateMemory = {},
            onDeleteMemory = {},
            onWipeAll = {}
        )
    }
}
