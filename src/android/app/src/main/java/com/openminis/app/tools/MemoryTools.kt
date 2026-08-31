package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tool definitions and execution for memory_write and memory_get.
 * Schema matches iOS AgentToolDefinition exactly.
 */
object MemoryTools {

    // -- Tool Definitions (Anthropic format) --

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user.")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_write")
            put("description", "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. Each entry is prepended with a timestamp. Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                    put("content")
                })
            })
        }
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.")
                put("enum", JSONArray().apply {
                    put("daily")
                    put("all")
                })
            })
            put("keywords", JSONObject().apply {
                put("type", "string")
                put("description", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_get")
            put("description", "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                })
            })
        }
    }

    // -- OpenAI Function Calling format --

    fun memoryWriteOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_write")
                put("description", memoryWriteToolDefinition().getString("description"))
                put("parameters", memoryWriteToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    fun memoryGetOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_get")
                put("description", memoryGetToolDefinition().getString("description"))
                put("parameters", memoryGetToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else {
                val result = repository.writeMemory(content)
                val success = result.startsWith("Memory saved")
                // [feat/memory-facts] Optional structured facts. Missing /
                // empty / malformed → silently degrade to plain-text write;
                // a bad facts payload must NEVER fail the whole memory_write.
                val appended = try {
                    val facts = parseFactsArg(obj)
                    if (facts.isEmpty()) 0 else repository.appendFacts(facts)
                } catch (_: Exception) {
                    0
                }
                val output = if (appended > 0) "$result (+$appended facts)" else result
                ToolResult(output, success, toolTitle)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    /**
     * Parse the optional "facts" argument of a memory_write call. Lenient:
     * non-array, malformed elements, or invalid field shapes are skipped, not
     * fatal. Returns the facts that parsed cleanly (may be empty).
     */
    fun parseFactsArg(obj: JSONObject): List<com.openminis.app.data.model.MemoryFact> {
        if (!obj.has("facts")) return emptyList()
        val raw = obj.opt("facts")
        if (raw !is JSONArray) return emptyList()
        val out = mutableListOf<com.openminis.app.data.model.MemoryFact>()
        // [fix] 之前 source/createdAt 写死为空 → fact 永远拿不到时间衰减权重
        // （recency decay 依赖 created_at）。现在落盘当天日期（source 文件名）
        // 与 ISO 时间戳，让衰减排序与同日去重真正生效。
        val now = Date()
        val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".md"
        val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now)
        for (i in 0 until raw.length()) {
            val el = raw.optJSONObject(i) ?: continue
            val subject = el.optString("subject", "").trim()
            val predicate = el.optString("predicate", "").trim()
            val `object` = el.optString("object", "").trim()
            if (subject.isEmpty() && predicate.isEmpty() && `object`.isEmpty()) continue
            var confidence = el.optDouble("confidence", 0.8)
            if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) confidence = 0.8
            out.add(
                com.openminis.app.data.model.MemoryFact(
                    subject = subject,
                    predicate = predicate,
                    `object` = `object`,
                    confidence = confidence,
                    source = source,
                    deviceId = "unknown",
                    createdAt = createdAt,
                )
            )
        }
        return out
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = repository.getMemory(keywords, scope)
            ToolResult(result, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
