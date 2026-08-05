package com.openminis.app.knowledgebase

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Agent-facing tools for knowledge base retrieval (RAG).
 *
 * The agent gets three primitives:
 *  - kb_list       — enumerate available knowledge bases
 *  - kb_retrieve   — semantic/keyword search inside one KB, returns top chunks
 *  - kb_ingest     — add a document (raw text) to a KB
 *
 * This is the "from chat tool to personal AI" bridge: instead of only
 * recalling memory lines, the agent can now pull facts from a curated
 * document collection the user maintains.
 */
object KnowledgeBaseTools {

    fun kbListDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "kb_list",
        description = "List all knowledge bases with their document/chunk counts. A knowledge base is a curated document collection the user maintains (notes, code, papers, manuals). Use this first to discover available KBs, then kb_retrieve to search within one. Returns JSON: id, name, description, document_count, chunk_count.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'List knowledge bases', 'Enumerate available KBs'). Use the same language as the user."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    fun kbRetrieveDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "kb_retrieve",
        description = "Retrieve the most relevant passages (chunks) from a knowledge base for a query. " +
            "Use when the answer to the user's question likely lives in a document collection: codebases, notes, manuals, papers. " +
            "Returns up to topK chunks, each with document title, relevance score and content. " +
            "Pass kb_id from kb_list (omit kb_id or pass 'all' to search every knowledge base). " +
            "After retrieving, answer the user grounded ONLY in the retrieved content; cite the document title when you use it.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Search knowledge base for install instructions', 'Look up the backup format spec'). Use the same language as the user."),
            "query" to AgentToolParam("string", "The search query. Use keywords and natural language terms likely to appear in the target document (BM25 keyword search, not semantic)."),
            "kb_id" to AgentToolParam("string", "Knowledge base id to search. Omit or pass 'all' to search across all knowledge bases."),
            "top_k" to AgentToolParam("integer", "How many chunks to return (default 8, max 20)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "kb_id", "top_k"),
    )

    fun kbIngestDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "kb_ingest",
        description = "Add a document to a knowledge base so it becomes searchable via kb_retrieve. " +
            "Pass the full text content (read the file first with file_read or shell cat if it lives in the sandbox). " +
            "Content is chunked (800 chars, 200 overlap) and indexed locally with BM25 — no network, no API key needed. " +
            "Ingesting the same source_path again with identical content is a no-op (idempotent).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Add API reference to knowledge base', 'Index the meeting notes'). Use the same language as the user."),
            "kb_id" to AgentToolParam("string", "Knowledge base id to add the document to (from kb_list)."),
            "title" to AgentToolParam("string", "Document title, e.g. 'API Reference v2' or the file name."),
            "content" to AgentToolParam("string", "The full document text to ingest."),
            "source_path" to AgentToolParam("string", "Optional: the original file path (e.g. /var/minis/workspace/notes.md). Used for idempotent re-ingest and display."),
        ),
        required = listOf("tool_title", "kb_id", "title", "content"),
        propertyOrdering = listOf("tool_title", "kb_id", "title", "content", "source_path"),
    )

    fun kbCreateDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "kb_create",
        description = "Create a new (empty) knowledge base. " +
            "Use when the user asks to organize a new topic as searchable documents, " +
            "or when kb_list shows no suitable KB for the material at hand.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create notes knowledge base'). Use the same language as the user."),
            "name" to AgentToolParam("string", "Knowledge base name, e.g. 'Project docs' or 'Recipes'."),
            "description" to AgentToolParam("string", "Optional: what this KB contains, so future retrievals pick it correctly."),
        ),
        required = listOf("tool_title", "name"),
        propertyOrdering = listOf("tool_title", "name", "description"),
    )
}
