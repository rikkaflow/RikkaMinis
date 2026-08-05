package com.openminis.app.ui.settings

import com.openminis.app.knowledgebase.DocumentEntity
import com.openminis.app.knowledgebase.KnowledgeBaseEntity
import com.openminis.app.knowledgebase.KnowledgeBaseRepository

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch

/**
 * KB detail: lists documents inside a knowledge base, shows a live
 * retrieval preview (test your queries), and allows document deletion.
 * Documents can also be added by the agent via kb_ingest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseDetailScreen(
    kbId: String,
    knowledgeBaseRepository: KnowledgeBaseRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var kb by remember { mutableStateOf<KnowledgeBaseEntity?>(null) }
    val documents by knowledgeBaseRepository.getDocumentsByKb(kbId).collectAsState(initial = emptyList())
    var deleteTarget by remember { mutableStateOf<DocumentEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<KnowledgeBaseRepository.RetrievedChunk>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(kbId) {
        kb = knowledgeBaseRepository.getKnowledgeBase(kbId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kb?.name ?: "Knowledge Base") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            item {
                Text(
                    text = kb?.description?.ifBlank { "No description" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = "${kb?.documentCount ?: 0} documents · ${kb?.chunkCount ?: 0} chunks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Retrieval test box
            item {
                Text(
                    text = "Test retrieval (BM25, offline)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Query, e.g. 'how does backup work'") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = query.isNotBlank(),
                        onClick = {
                            scope.launch {
                                results = knowledgeBaseRepository.retrieve(kbId, query, topK = 5)
                                searched = true
                            }
                        },
                    ) { Text("Search") }
                }
            }

            if (searched) {
                if (results.isEmpty()) {
                    item {
                        Text(
                            text = "No matches for \"$query\". Try different keywords.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "Top ${results.size} results:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(results, key = { it.chunk.id }) { r ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(
                                text = "%.3f".format(r.score) + " — " + (r.document?.title ?: "?"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = r.chunk.content.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.size(8.dp)) }
            }

            item {
                Text(
                    text = "Documents",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (documents.isEmpty()) {
                item {
                    Text(
                        text = "No documents yet. Ask the agent to kb_ingest a document (e.g. 'save this note to my knowledge base'), or add via the API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(documents, key = { it.id }) { doc ->
                DocumentRow(doc = doc, onDelete = { deleteTarget = doc })
                HorizontalDivider()
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Document") },
            text = { Text("Delete \"${target.title}\" and its ${target.chunkCount} chunk(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { knowledgeBaseRepository.deleteDocument(target.id) }
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
private fun DocumentRow(
    doc: DocumentEntity,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = doc.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${doc.chunkCount} chunks")
                    if (doc.sourcePath.isNotBlank()) append(" · ${doc.sourcePath}")
                    append(" · ${doc.mimeType}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
