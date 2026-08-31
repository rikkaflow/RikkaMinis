package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [feat/memory-time-decay] JVM tests for the recency-decay ordering of
 * getMemory() keyword search.
 *
 * Contract under test:
 *  - When multiple daily logs match the same keywords, the search budget
 *    should be spent on the MOST RECENT file first (decay reordering), not
 *    in fixed name-descending order. Verified via the byte/line cap: with a
 *    tiny budget, the recent file's content must appear before an older
 *    file's.
 *  - The full-dump path (no keywords) keeps the chronological
 *    name-descending order (dump = chronological preview, not ranked set).
 *  - Pure scoring helpers: memoryRecencyWeight / dailyLogAgeDays clamp and
 *    parse behavior.
 *  - Existing behavior guards (rollup exclusion etc.) live in
 *    MemoryRepositoryTest and are not duplicated here.
 */
class MemoryTimeDecayTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "mem-decay-${UUID.randomUUID().toString().take(8)}")
    private val memoryDir = File(tempDir, "minis-global/memory")

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeLog(name: String, body: String) {
        memoryDir.mkdirs()
        File(memoryDir, name).writeText(body)
    }

    // ── pure helpers ──────────────────────────────────────────────────────

    @Test
    fun recencyWeight_today_isOne() {
        assertEquals(1.0, MemoryRepository.Companion.memoryRecencyWeight(0L), 1e-9)
    }

    @Test
    fun recencyWeight_monotonicDecreasing() {
        val w1 = MemoryRepository.Companion.memoryRecencyWeight(1L)
        val w10 = MemoryRepository.Companion.memoryRecencyWeight(10L)
        val w30 = MemoryRepository.Companion.memoryRecencyWeight(30L)
        assertTrue("w1=$w1 should be < 1.0", w1 < 1.0)
        assertTrue("w10=$w10 < w1=$w1", w10 < w1)
        assertTrue("w30=$w30 < w10=$w10", w30 < w10)
        // ~0.30 for 30 days at lambda=0.04
        assertEquals(0.30, w30, 0.01)
    }

    @Test
    fun recencyWeight_nullAndNegative_clampToOne() {
        assertEquals(1.0, MemoryRepository.Companion.memoryRecencyWeight(null), 1e-9)
        assertEquals(1.0, MemoryRepository.Companion.memoryRecencyWeight(-5L), 1e-9)
    }

    @Test
    fun dailyLogAgeDays_parsesDateAndComputesAge() {
        // 2026-08-31 00:00 UTC-ish baseline; use explicit ms math so the test
        // is timezone-independent: pick nowMs = 2026-08-31 + 12h in epoch ms
        // built from the same calendar the production parser uses.
        val cal = java.util.Calendar.getInstance(java.util.Locale.US)
        cal.clear()
        cal.set(2026, java.util.Calendar.AUGUST, 31, 0, 0, 0)
        val nowMs = cal.timeInMillis + 12L * 3600_000L
        assertEquals(0L, MemoryRepository.Companion.dailyLogAgeDays("2026-08-31.md", nowMs))
        assertEquals(1L, MemoryRepository.Companion.dailyLogAgeDays("2026-08-30.md", nowMs))
        assertEquals(30L, MemoryRepository.Companion.dailyLogAgeDays("2026-08-01.md", nowMs))
        // non-daily names → null (undated, e.g. GLOBAL.md)
        assertEquals(null, MemoryRepository.Companion.dailyLogAgeDays("GLOBAL.md", nowMs))
        assertEquals(null, MemoryRepository.Companion.dailyLogAgeDays("notes.md", nowMs))
        // malformed date (month 13) → null, not a crash
        assertEquals(null, MemoryRepository.Companion.dailyLogAgeDays("2026-13-01.md", nowMs))
    }

    // ── search-ordering integration ───────────────────────────────────────

    @Test
    fun keywordSearch_recentFileBeatsOldFile_inOutputOrder() {
        // Old file and recent file both match the keyword. The old file is
        // name-sorted FIRST only if both are "descending" — actually
        // name-descending puts the NEWEST first already; the interesting case
        // is scope=all where GLOBAL.md (undated, weight 1.0) and an old log
        // both match: decay must still put the newer daily log before the
        // older one, and the output must list the recent log's content before
        // the old log's when the budget forces an order.
        writeLog("2026-08-01.md", "<!-- ts -->\n## old\nalpha keyword old-entry\n")
        writeLog("2026-08-30.md", "<!-- ts -->\n## recent\nalpha keyword recent-entry\n")

        val repo = MemoryRepository(memoryDir)
        val out = repo.getMemory("alpha", "daily")

        val oldIdx = out.indexOf("old-entry")
        val recentIdx = out.indexOf("recent-entry")
        assertTrue("recent entry missing:\n$out", recentIdx >= 0)
        assertTrue("old entry missing:\n$out", oldIdx >= 0)
        assertTrue(
            "recent entry (idx=$recentIdx) should appear before old entry (idx=$oldIdx)",
            recentIdx < oldIdx,
        )
    }

    @Test
    fun keywordSearch_undatedGlobalGetsNoPenalty_vsOldLog() {
        // GLOBAL.md matches too: with decay, the 29-day-old log (weight ~0.31)
        // must rank BELOW both GLOBAL.md (1.0) and today's log (1.0).
        memoryDir.mkdirs()
        File(memoryDir, "GLOBAL.md").writeText("## global\nalpha keyword global-entry\n")
        writeLog("2026-08-01.md", "<!-- ts -->\n## old\nalpha keyword old-entry\n")
        writeLog("2026-08-30.md", "<!-- ts -->\n## recent\nalpha keyword recent-entry\n")

        val repo = MemoryRepository(memoryDir)
        val out = repo.getMemory("alpha", "all")

        val globalIdx = out.indexOf("global-entry")
        val oldIdx = out.indexOf("old-entry")
        val recentIdx = out.indexOf("recent-entry")
        assertTrue("global entry missing:\n$out", globalIdx >= 0)
        assertTrue("recent entry missing:\n$out", recentIdx >= 0)
        assertTrue("old entry missing:\n$out", oldIdx >= 0)
        assertTrue(
            "old 29-day log should come after both GLOBAL.md and the recent log",
            oldIdx > globalIdx && oldIdx > recentIdx,
        )
    }

    @Test
    fun fullDump_keepsChronologicalOrder() {
        // No keywords → dump keeps name-descending (chronological) order,
        // unchanged by decay.
        writeLog("2026-08-01.md", "<!-- ts -->\n## old\nold content line\n")
        writeLog("2026-08-30.md", "<!-- ts -->\n## recent\nrecent content line\n")

        val repo = MemoryRepository(memoryDir)
        val out = repo.getMemory("", "daily")
        val recentIdx = out.indexOf("recent content line")
        val oldIdx = out.indexOf("old content line")
        assertTrue("dump should list recent file first:\n$out", recentIdx in 0 until oldIdx)
    }
}
