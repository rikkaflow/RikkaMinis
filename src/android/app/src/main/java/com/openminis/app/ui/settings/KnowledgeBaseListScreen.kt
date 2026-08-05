package com.openminis.app.ui.settings

import com.openminis.app.knowledgebase.KnowledgeBaseEntity
import com.openminis.app.knowledgebase.KnowledgeBaseRepository

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch

/**
 * Settings-level knowledge base management. Lists all KBs with
 * document/chunk counts, allows creating and deleting KBs, and
 * navigates into a KB's document list.
 *
 * [RAG v1] — this is the human-facing side of the RAG feature;
 * the agent-facing side is kb_list/kb_retrieve/kb_ingest/kb_create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseListScreen(
    knowledgeBaseRepository: KnowledgeBaseRepository,
    onBack: () -> Unit,
    onOpenKb: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val kbs by knowledgeBaseRepository.getAllKnowledgeBases().collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Bases") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create knowledge base")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (kbs.isEmpty()) {
                item {
                    Text(
                        text = "No knowledge bases yet. Tap + to create one, or ask the agent to kb_create / kb_ingest documents.\n\n" +
                            "A knowledge base lets the agent search a curated document collection (notes, code, manuals) with kb_retrieve.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(kbs, key = { it.id }) { kb ->
                KnowledgeBaseRow(
                    kb = kb,
                    onClick = { onOpenKb(kb.id) },
                    onDelete = { deleteTarget = kb },
                )
                HorizontalDivider()
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Knowledge Base") },
            text = {
                Column {
                    DialogTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = "Name",
                        singleLine = true,
                    )
                    Spacer(Modifier.size(8.dp))
                    DialogTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it },
                        label = "Description (optional)",
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            knowledgeBaseRepository.createKnowledgeBase(newName, newDescription)
                        }
                        showCreateDialog = false
                        newName = ""
                        newDescription = ""
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Knowledge Base") },
            text = { Text("Delete \"${target.name}\" and all ${target.documentCount} document(s) inside? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { knowledgeBaseRepository.deleteKnowledgeBase(target.id) }
                        deleteTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun KnowledgeBaseRow(
    kb: KnowledgeBaseEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = kb.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (kb.description.isNotBlank()) {
                Text(
                    text = kb.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${kb.documentCount} documents · ${kb.chunkCount} chunks · ${if (kb.embeddingProvider == "bm25") "BM25 (offline)" else kb.embeddingProvider}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
