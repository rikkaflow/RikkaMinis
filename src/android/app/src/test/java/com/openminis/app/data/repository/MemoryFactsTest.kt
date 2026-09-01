package com.openminis.app.data.repository

import com.openminis.app.data.model.MemoryFact
import com.openminis.app.tools.MemoryTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [feat/memory-facts] JVM tests for the structured-facts layer:
 * MemoryRepository.appendFacts/loadFacts/searchFacts/formatFactsForPrompt +
 * MemoryTools.executeMemoryWrite facts parsing + AgentToolDefinition
 * array-type schema output.
 */
class MemoryFactsTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "facts-${UUID.randomUUID().toString().take(8)}")
    private val memoryDir = File(tempDir, "minis-global/memory")

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun repo(): MemoryRepository = MemoryRepository(memoryDir)

    // [fix/test-date-flake] The default fixture date must track the RUNTIME
    // "today": MemoryRepository.appendFacts dedups facts whose createdDatePrefix
    // equals today, and MemoryTools.executeMemoryWrite writes today's daily log
    // (yyyy-MM-dd.md). A hard-coded 2026-08-31 passed on 08-31 CI and turned red
    // the next day — a time-bomb test, not a real regression (caught by the
    // 2026-09-01 CI run on an unrelated branch). All other hard-coded dates in
    // this file are pure historical fixtures compared only against themselves
    // (round-trip equality), so they are left alone.
    private val todayDate: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun fact(
        subject: String,
        predicate: String,
        `object`: String,
        confidence: Double = 0.8,
        createdAt: String = "${todayDate}T12:00:00",
    ) = MemoryFact(subject, predicate, `object`, confidence, "$todayDate.md", "device-A", createdAt)

    // ── append / load round-trip ─────────────────────────────────────────

    @Test
    fun appendFacts_roundTrip_preservesFieldsAndSnakeCaseKeys() {
        val r = repo()
        val n = r.appendFacts(listOf(fact("user", "prefers", "dark theme", 0.9)))
        assertEquals(1, n)

        val lines = File(memoryDir, "facts.jsonl").readLines()
        assertEquals(1, lines.size)
        val obj = JSONObject(lines[0])
        // snake_case keys on disk
        assertEquals("device-A", obj.getString("device_id"))
        assertEquals("${todayDate}T12:00:00", obj.getString("created_at"))
        assertEquals(0.9, obj.getDouble("confidence"), 1e-9)

        val loaded = r.loadFacts()
        assertEquals(1, loaded.size)
        assertEquals("user", loaded[0].subject)
        assertEquals("prefers", loaded[0].predicate)
        assertEquals("dark theme", loaded[0].`object`)
        assertEquals(0.9, loaded[0].confidence, 1e-9)
        assertEquals("device-A", loaded[0].deviceId)
        assertEquals("${todayDate}T12:00:00", loaded[0].createdAt)
        assertEquals("$todayDate.md", loaded[0].source)
    }

    @Test
    fun loadFacts_skipsMalformedLines() {
        val r = repo()
        r.appendFacts(listOf(fact("user", "prefers", "dark theme")))
        val file = File(memoryDir, "facts.jsonl")
        // Corrupt the middle line + append a truncated JSON line.
        val lines = file.readLines().toMutableList()
        lines.add("not json at all")
        lines.add("{\"subject\": \"truncated\",")
        file.writeText(lines.joinToString("\n") + "\n")

        val loaded = r.loadFacts()
        assertEquals(1, loaded.size)
        assertEquals("user", loaded[0].subject)
    }

    @Test
    fun loadFacts_respectsLimit() {
        val r = repo()
        r.appendFacts(
            (1..5).map { i -> fact("user$i", "has", "property$i", createdAt = "2026-08-3${i}T00:00:00") }
        )
        assertEquals(2, r.loadFacts(2).size)
        assertEquals(5, r.loadFacts(200).size)
    }

    @Test
    fun loadFacts_missingFile_returnsEmpty() {
        assertEquals(0, repo().loadFacts().size)
    }

    // ── search ───────────────────────────────────────────────────────────

    @Test
    fun searchFacts_keywordMultiHit_andZeroHit() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("user", "prefers", "dark theme", 0.9, "2026-08-30T10:00:00"),
                fact("user", "prefers", "light theme", 0.7, "2026-08-30T11:00:00"),
                fact("project", "uses", "kotlin", 0.9, "2026-08-30T12:00:00"),
            )
        )
        val hit = r.searchFacts(listOf("theme"))
        assertEquals(2, hit.size)
        // OR semantics: "user" matches both user facts, "light" matches only
        // one — so 2 results surface, with the fact matching BOTH tokens
        // ("light theme") ranked above the one matching only "user".
        val hit2 = r.searchFacts(listOf("user", "light"))
        assertEquals(2, hit2.size)
        assertEquals("light theme", hit2[0].`object`)
        val miss = r.searchFacts(listOf("nonexistent"))
        assertTrue(miss.isEmpty())
    }

    @Test
    fun searchFacts_caseInsensitive() {
        val r = repo()
        r.appendFacts(listOf(fact("User", "Prefers", "Dark Theme")))
        val hit = r.searchFacts(listOf("dark"))
        assertEquals(1, hit.size)
    }

    // [fix/facts-query-punct-filter] jieba query mode emits standalone
    // punctuation tokens (，。？！…). Under OR semantics a lone "，" matches
    // any fact whose searchable text contains a comma — which nearly every
    // Chinese fact does — so punctuation must never count as a keyword.
    @Test
    fun searchFacts_purePunctuationTokensAreDropped() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("user", "prefers", "中文交流，代码保留英文", 0.95, "2026-08-31T00:00:00"),
                fact("dev", "discipline", "kotlin 编译", 0.9, "2026-08-31T00:00:00"),
            )
        )
        // Only punctuation tokens → no keywords left → falls back to pure
        // recency ordering (same as empty-keyword behavior; all facts
        // surface, ranked by date). The punctuation itself never matches.
        val punctOnly = r.searchFacts(listOf("，", "。", "？"))
        assertEquals(2, punctOnly.size)
        // Mixed: punctuation is dropped, real token "kotlin" still matches.
        val hit = r.searchFacts(listOf("，", "kotlin", "。"))
        assertEquals(1, hit.size)
        assertEquals("dev", hit[0].subject)
        // Token containing letters alongside punctuation survives filtering
        // and still matches (substring contains, punctuation ignored).
        val hit2 = r.searchFacts(listOf("kotlin", "？"))
        assertEquals(1, hit2.size)
        assertEquals("dev", hit2[0].subject)
    }

    @Test
    fun searchFacts_emptyKeywords_returnsTopByRecency() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("old", "fact", "one", 0.8, "2026-08-01T00:00:00"),
                fact("new", "fact", "two", 0.8, "2026-08-31T00:00:00"),
            )
        )
        val top = r.searchFacts(emptyList(), 15)
        assertEquals(2, top.size)
        // Recency decay: today's fact ranks above the 30-day-old one.
        assertEquals("new", top[0].subject)
    }

    // ── recency-decay ordering ───────────────────────────────────────────

    // ── [feat/facts-query-relevance] query-relevance ranking ─────────────

    @Test
    fun searchFacts_relevantFactBeatsIrrelevantRecent() {
        val r = repo()
        r.appendFacts(
            listOf(
                // Irrelevant but recent: highest recency, must NOT win.
                fact("user", "prefers", "dark theme", 0.9, "2026-08-31T00:00:00"),
                // Relevant but older: keyword hit must surface it first.
                fact("dev", "discipline", "kotlin 单测 用 kotlinc", 0.9, "2026-08-25T00:00:00"),
            )
        )
        val hit = r.searchFacts(listOf("kotlin"))
        assertEquals(1, hit.size)
        assertEquals("dev", hit[0].subject)
    }

    @Test
    fun searchFacts_relevantRecentBeatsRelevantOld() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("dev", "discipline", "kotlin 单测 用 kotlinc", 0.9, "2026-08-20T00:00:00"),
                fact("dev", "discipline", "kotlin 编译 陷阱", 0.9, "2026-08-30T00:00:00"),
            )
        )
        val hit = r.searchFacts(listOf("kotlin"))
        assertEquals(2, hit.size)
        // Both relevant; recency is the tiebreaker — newer first.
        assertEquals("kotlin 编译 陷阱", hit[0].`object`)
    }

    @Test
    fun searchFacts_noKeywords_stillPureRecency() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("old", "fact", "one", 0.8, "2026-08-01T00:00:00"),
                fact("new", "fact", "two", 0.8, "2026-08-31T00:00:00"),
            )
        )
        val top = r.searchFacts(emptyList(), 15)
        // Zero-regression contract: empty keywords = legacy recency order.
        assertEquals("new", top[0].subject)
        assertEquals("old", top[1].subject)
    }

    @Test
    fun rankFactForQuery_partialHitBeatsNoHit() {
        val f = fact("user", "prefers", "dark theme", 0.9, "2026-08-31T00:00:00")
        // OR semantics: a fact matching ONE token ("dark") must surface even
        // when the query has other non-matching tokens — the old AND logic
        // returned 0 here and silently emptied injection.
        val score = MemoryRepository.rankFactForQuery(f, listOf("dark", "kotlin"), 1.0)
        assertTrue("partial hit must score > 0, got $score", score > 0.0)
    }

    @Test
    fun rankFactForQuery_higherHitRatioRanksHigher() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("a", "p", "dark theme", 0.9, "2026-08-31T00:00:00"),
                fact("b", "p", "dark kotlin", 0.9, "2026-08-31T00:00:00"),
            )
        )
        // "dark" + "kotlin" — the fact matching BOTH must outrank the one
        // matching only "dark".
        val hit = r.searchFacts(listOf("dark", "kotlin"))
        assertEquals(2, hit.size)
        assertEquals("dark kotlin", hit[0].`object`)
    }

    @Test
    fun searchFacts_filtersStopwords() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("user", "prefers", "dark theme", 0.9, "2026-08-31T00:00:00"),
                fact("user", "prefers", "有立场 敢反驳", 0.9, "2026-08-31T00:00:00"),
            )
        )
        // "的" is a stopword — it must not act as a keyword that matches both
        // facts. A query of pure stopwords degrades to recency (both returned,
        // newest first), but a mixed query must rank the real match first.
        val hit = r.searchFacts(listOf("theme", "的"))
        assertEquals(1, hit.size)
        assertEquals("dark theme", hit[0].`object`)
    }

    @Test
    fun searchFacts_pureStopwords_degradesToRecency() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("old", "fact", "one", 0.8, "2026-08-01T00:00:00"),
                fact("new", "fact", "two", 0.8, "2026-08-31T00:00:00"),
            )
        )
        // All tokens are stopwords → empty effective keyword set → recency.
        val hit = r.searchFacts(listOf("的", "了", "the"))
        assertEquals(2, hit.size)
        assertEquals("new", hit[0].subject)
    }

    @Test
    fun rankFactForQuery_scoresHigherConfidenceFirst() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("dev", "discipline", "kotlin 单测", 0.5, "2026-08-30T00:00:00"),
                fact("dev", "discipline", "kotlin 编译", 0.95, "2026-08-30T00:00:00"),
            )
        )
        // Same recency (same day), both relevant — higher confidence wins.
        val hit = r.searchFacts(listOf("kotlin"))
        assertEquals("kotlin 编译", hit[0].`object`)
    }

    @Test
    fun rankFactForQuery_emptyTokens_returnsRecency() {
        val f = fact("user", "prefers", "dark theme", 0.9, "2026-08-31T00:00:00")
        // Pure-function contract: no tokens → score == recency (1.0 for today).
        assertEquals(1.0, MemoryRepository.rankFactForQuery(f, emptyList(), 1.0), 1e-9)
    }

    @Test
    fun rankFactForQuery_irrelevantToken_returnsZero() {
        val f = fact("user", "prefers", "dark theme", 0.9, "2026-08-31T00:00:00")
        assertEquals(0.0, MemoryRepository.rankFactForQuery(f, listOf("kotlin"), 1.0), 1e-9)
    }

    @Test
    fun searchFacts_recentBeatsOld_onSameKeyword() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("user", "prefers", "theme X", 0.8, "2026-07-15T00:00:00"),
                fact("user", "prefers", "theme Y", 0.8, "2026-08-30T00:00:00"),
            )
        )
        val hit = r.searchFacts(listOf("theme"))
        assertEquals(2, hit.size)
        assertEquals("theme Y", hit[0].`object`)
        assertEquals("theme X", hit[1].`object`)
    }

    @Test
    fun searchFacts_unparseableDate_getsNoPenalty() {
        val r = repo()
        r.appendFacts(
            listOf(
                fact("a", "b", "dated", 0.8, "2026-08-01T00:00:00"),
                fact("c", "d", "undated", 0.8, ""),
            )
        )
        val hit = r.searchFacts(listOf("d"))
        // Both facts match on predicate "d" — the undated one must not be
        // pushed out by the decay ordering (it gets weight 1.0).
        assertEquals(2, hit.size)
    }

    // ── dedup ────────────────────────────────────────────────────────────

    @Test
    fun appendFacts_sameDayTripleDedup() {
        val r = repo()
        val f = fact("user", "prefers", "dark theme", 0.9)
        assertEquals(1, r.appendFacts(listOf(f)))
        // Same triple, same day → skipped.
        assertEquals(0, r.appendFacts(listOf(f)))
        // Same triple, different confidence, same day → still skipped (triple key).
        assertEquals(0, r.appendFacts(listOf(f.copy(confidence = 0.5))))
        assertEquals(1, r.loadFacts().size)
    }

    @Test
    fun appendFacts_crossDayDuplicateCoexists() {
        val r = repo()
        assertEquals(1, r.appendFacts(listOf(fact("user", "prefers", "dark", 0.8, "2026-08-01T00:00:00"))))
        assertEquals(1, r.appendFacts(listOf(fact("user", "prefers", "dark", 0.9, "2026-08-31T00:00:00"))))
        assertEquals(2, r.loadFacts().size)
        // Newer declaration ranks first on search.
        val hit = r.searchFacts(listOf("dark"))
        assertEquals("2026-08-31T00:00:00", hit[0].createdAt)
    }

    // ── executeMemoryWrite integration ───────────────────────────────────

    @Test
    fun executeMemoryWrite_withFacts_appendsAndReports() {
        val r = repo()
        val factsArr = JSONArray().apply {
            put(JSONObject().apply {
                put("subject", "user")
                put("predicate", "prefers")
                put("object", "dark theme")
                put("confidence", 0.9)
            })
        }
        val input = JSONObject().apply {
            put("tool_title", "memory_write")
            put("content", "## note\nuser prefers dark theme")
            put("facts", factsArr)
        }.toString()

        val result = MemoryTools.executeMemoryWrite(input, r)
        assertTrue(result.success)
        assertTrue("expected '(+1 facts)' in: ${result.output}", result.output.contains("(+1 facts)"))
        assertEquals(1, r.loadFacts().size)
        assertEquals("user", r.loadFacts()[0].subject)
        assertEquals(0.9, r.loadFacts()[0].confidence, 1e-9)
        // [fix] source/created_at must be populated (non-empty) so the fact
        // earns a recency-decay weight and participates in same-day dedup.
        assertTrue("source must be a dated .md file", r.loadFacts()[0].source.endsWith(".md"))
        assertTrue("created_at must be a non-empty timestamp", r.loadFacts()[0].createdAt.isNotEmpty())
        assertEquals(r.loadFacts()[0].createdAt.substring(0, 10), r.loadFacts()[0].source.removeSuffix(".md"))
    }

    @Test
    fun executeMemoryWrite_malformedFacts_degradesToPlainWrite() {
        val r = repo()
        val input = JSONObject().apply {
            put("tool_title", "memory_write")
            put("content", "## note\nplain entry")
            // facts as a string → must not fail the write
            put("facts", "not-an-array")
        }.toString()

        val result = MemoryTools.executeMemoryWrite(input, r)
        assertTrue("write should succeed: ${result.output}", result.success)
        assertFalse("no (+N facts) suffix expected: ${result.output}", result.output.contains("(+"))
        assertEquals(0, r.loadFacts().size)
        // Daily log still written
        val daily = File(memoryDir, "$todayDate.md")
        assertTrue(daily.exists())
    }

    @Test
    fun executeMemoryWrite_noFacts_unchangedOutput() {
        val r = repo()
        val input = JSONObject().apply {
            put("tool_title", "memory_write")
            put("content", "## note\nplain")
        }.toString()
        val result = MemoryTools.executeMemoryWrite(input, r)
        assertTrue(result.success)
        assertFalse(result.output.contains("(+"))
    }

    // ── negative self-check: hostile facts.jsonl ─────────────────────────

    @Test
    fun hostileFile_neverThrows() {
        val r = repo()
        val file = File(memoryDir, "facts.jsonl")
        file.writeText(
            "{bad json\n" +
                "{\"subject\":\"x\"}\n" +
                "{\"created_at\":\"2099-01-01T00:00:00\",\"subject\":\"future\",\"predicate\":\"p\",\"object\":\"o\"}\n" +
                "{\"subject\":\"\",\"predicate\":\"\",\"object\":\"\"}\n" +
                "{\"subject\":\"ok\",\"predicate\":\"p\",\"object\":\"o\",\"confidence\":\"NaN\"}\n"
        )
        // loadFacts must not throw; malformed lines skipped; empty-triple skipped;
        // NaN confidence clamped; future date parses without penalty.
        val loaded = r.loadFacts()
        assertTrue(loaded.isNotEmpty())
        for (f in loaded) {
            assertTrue(f.confidence in 0.0..1.0)
        }
        // searchFacts must not throw either, including on the future-dated line.
        val hit = r.searchFacts(listOf("future"))
        assertTrue(hit.isNotEmpty())
        val any = r.searchFacts(emptyList())
        assertTrue(any.isNotEmpty())
        // blank-only triple must never be returned
        assertTrue(loaded.none { it.subject.isEmpty() && it.predicate.isEmpty() && it.`object`.isEmpty() })
    }

    // ── AgentToolDefinition array-type schema (provider contract) ────────

    @Test
    fun agentToolDefinition_arrayType_schemaShape() {
        // Mirror of AgentTools.memoryWriteDefinition()'s facts param — the
        // full AgentTools.kt can't compile in the JVM sandbox (Android deps),
        // so we assert the provider-facing JSON shape here; AgentTools.kt
        // compilation is covered by the full CI build.
        val factsParam = com.openminis.app.data.model.AgentToolParam(
            type = "array",
            description = "Optional array of structured facts. Each element: {\"subject\": string, \"predicate\": string, \"object\": string, \"confidence\": number 0-1}",
        )
        val def = com.openminis.app.data.model.AgentToolDefinition(
            name = "memory_write",
            description = "d",
            parameters = mapOf(
                "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "t"),
                "content" to com.openminis.app.data.model.AgentToolParam("string", "c"),
                "facts" to factsParam,
            ),
            required = listOf("tool_title", "content"),
            propertyOrdering = listOf("tool_title", "content", "facts"),
        )

        val anthropic = def.toAnthropicJson().getJSONObject("input_schema")
        val props = anthropic.getJSONObject("properties")
        assertEquals("array", props.getJSONObject("facts").getString("type"))
        assertFalse(props.getJSONObject("facts").has("items"))
        // facts not required
        val required = anthropic.getJSONArray("required")
        assertEquals(2, required.length())
        assertFalse(required.toString().contains("facts"))

        val openai = def.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
        assertEquals("array", openai.getJSONObject("properties").getJSONObject("facts").getString("type"))

        val gemini = def.toGeminiJson().getJSONObject("parameters")
        assertEquals("ARRAY", gemini.getJSONObject("properties").getJSONObject("facts").getString("type"))
    }
}
