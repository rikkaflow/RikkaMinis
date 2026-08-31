package com.openminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-cost-budget] Cost dimension of AgentExecutionBudget — mirrors the token
 * dimension's contract: null cap = allowed+unbooked, denial is side-effect
 * free, NaN/negative rejected, snapshot/remaining views consistent.
 */
class AgentExecutionBudgetCostTest {

    private fun budget(
        maxEstimatedCostUsd: Double? = null,
        startedAt: Long = 0L,
        deadline: Long = Long.MAX_VALUE / 2,
        clock: () -> Long = { 0L },
    ) = AgentExecutionBudget(
        startedAtMonotonicMs = startedAt,
        deadlineMonotonicMs = deadline,
        maxTurns = 100,
        maxProviderAttempts = 100,
        maxToolCalls = 100,
        maxShellCommands = 100,
        maxCompactionCalls = 100,
        maxConcurrentTools = 5,
        maxEstimatedTokens = null,
        maxEstimatedCostUsd = maxEstimatedCostUsd,
        monotonicClock = clock,
    )

    @Test
    fun `null cost cap allows and does not bookkeep`() {
        val b = budget(maxEstimatedCostUsd = null)
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.5))
        assertNull(b.snapshot().estimatedCostUsdUsed)
        assertNull(b.remaining().estimatedCostUsdRemaining)
    }

    @Test
    fun `cost consumes to cap then denies`() {
        val b = budget(maxEstimatedCostUsd = 1.0)
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.4))
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.4))
        assertEquals(0.8, b.snapshot().estimatedCostUsdUsed!!, 1e-12)
        // exactly to the cap → allowed
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.2))
        // one cent over → denied with the cost reason
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.COST_BUDGET_EXCEEDED),
            b.consumeEstimatedCostUsd(0.01),
        )
        // denial had no side effect
        assertEquals(1.0, b.snapshot().estimatedCostUsdUsed!!, 1e-12)
    }

    @Test
    fun `denied cost leaves state untouched`() {
        val b = budget(maxEstimatedCostUsd = 0.1)
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.COST_BUDGET_EXCEEDED),
            b.consumeEstimatedCostUsd(5.0),
        )
        assertEquals(0.0, b.snapshot().estimatedCostUsdUsed!!, 1e-12)
    }

    @Test
    fun `zero cost is legal and books zero`() {
        val b = budget(maxEstimatedCostUsd = 1.0)
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.0))
        assertEquals(0.0, b.snapshot().estimatedCostUsdUsed!!, 1e-12)
    }

    @Test
    fun `nan and negative costs throw`() {
        val b = budget(maxEstimatedCostUsd = 1.0)
        try { b.consumeEstimatedCostUsd(Double.NaN); fail("NaN must throw") } catch (_: IllegalArgumentException) {}
        try { b.consumeEstimatedCostUsd(-0.01); fail("negative must throw") } catch (_: IllegalArgumentException) {}
        // state untouched after the throws
        assertEquals(0.0, b.snapshot().estimatedCostUsdUsed!!, 1e-12)
    }

    @Test
    fun `remaining view reflects consumption`() {
        val b = budget(maxEstimatedCostUsd = 2.0)
        assertEquals(2.0, b.remaining().estimatedCostUsdRemaining!!, 1e-12)
        b.consumeEstimatedCostUsd(0.5)
        assertEquals(1.5, b.remaining().estimatedCostUsdRemaining!!, 1e-12)
    }

    @Test
    fun `constructor rejects negative cost cap`() {
        try {
            budget(maxEstimatedCostUsd = -1.0)
            fail("negative cap must throw")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `cost dimension independent of token dimension`() {
        // Token budget exhausted does not affect cost accounting and vice
        // versa — separate caps, separate reasons.
        val b = AgentExecutionBudget(
            startedAtMonotonicMs = 0L,
            deadlineMonotonicMs = Long.MAX_VALUE / 2,
            maxTurns = 100,
            maxProviderAttempts = 100,
            maxToolCalls = 100,
            maxShellCommands = 100,
            maxCompactionCalls = 100,
            maxConcurrentTools = 5,
            maxEstimatedTokens = 100L,
            maxEstimatedCostUsd = 1.0,
            monotonicClock = { 0L },
        )
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED), b.consumeEstimatedTokens(101L))
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedCostUsd(0.5))
        assertEquals(BudgetDecision.Denied(BudgetExhaustedReason.COST_BUDGET_EXCEEDED), b.consumeEstimatedCostUsd(0.6))
        assertEquals(BudgetDecision.Allowed, b.consumeEstimatedTokens(50L))
    }

    @Test
    fun `deadline expiry denies cost consumption too`() {
        var now = 0L
        val b = budget(maxEstimatedCostUsd = 1.0, clock = { now })
        now = 10_000L  // past deadline (deadline = MAX/2 is huge; use small deadline instead)
        // build with real small deadline:
        val b2 = AgentExecutionBudget(
            startedAtMonotonicMs = 0L,
            deadlineMonotonicMs = 100L,
            maxTurns = 100, maxProviderAttempts = 100, maxToolCalls = 100,
            maxShellCommands = 100, maxCompactionCalls = 100, maxConcurrentTools = 5,
            maxEstimatedTokens = null,
            maxEstimatedCostUsd = 1.0,
            monotonicClock = { now },
        )
        now = 200L
        assertEquals(
            BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED),
            b2.consumeEstimatedCostUsd(0.1),
        )
    }

    @Test
    fun `snapshot exposes cost used alongside tokens`() {
        val b = AgentExecutionBudget(
            startedAtMonotonicMs = 0L,
            deadlineMonotonicMs = Long.MAX_VALUE / 2,
            maxTurns = 100, maxProviderAttempts = 100, maxToolCalls = 100,
            maxShellCommands = 100, maxCompactionCalls = 100, maxConcurrentTools = 5,
            maxEstimatedTokens = 1000L,
            maxEstimatedCostUsd = 1.0,
            monotonicClock = { 0L },
        )
        b.consumeEstimatedTokens(200L)
        b.consumeEstimatedCostUsd(0.25)
        val snap = b.snapshot()
        assertEquals(200L, snap.estimatedTokensUsed)
        assertEquals(0.25, snap.estimatedCostUsdUsed!!, 1e-12)
    }
}
