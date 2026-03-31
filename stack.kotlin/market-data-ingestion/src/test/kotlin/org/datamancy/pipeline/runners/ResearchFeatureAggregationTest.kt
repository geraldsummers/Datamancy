package org.datamancy.pipeline.runners

import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchFeatureAggregationTest {
    @Test
    fun `chunk aggregation windows partitions range into fixed hour slices`() {
        val start = Instant.parse("2026-03-20T00:00:00Z")
        val end = Instant.parse("2026-03-21T06:00:00Z")

        val windows = chunkAggregationWindows(
            startInclusive = start,
            endExclusive = end,
            chunkHours = 12
        )

        assertEquals(3, windows.size)
        assertEquals(start, windows.first().startInclusive)
        assertEquals(Instant.parse("2026-03-20T12:00:00Z"), windows.first().endExclusive)
        assertEquals(Instant.parse("2026-03-21T00:00:00Z"), windows[1].endExclusive)
        assertEquals(end, windows.last().endExclusive)
    }

    @Test
    fun `chunk aggregation windows by minutes partitions range into minute slices`() {
        val windows = chunkAggregationWindowsByMinutes(
            startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-03-20T00:40:00Z"),
            chunkMinutes = 15
        )

        assertEquals(
            listOf(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:15:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:15:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:30:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:30:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:40:00Z")
                )
            ),
            windows
        )
    }

    @Test
    fun `recent aggregation prioritization processes newest windows first`() {
        val windows = chunkAggregationWindows(
            startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-03-20T18:00:00Z"),
            chunkHours = 6
        )

        val prioritized = prioritizeRecentAggregationWindows(windows)

        assertEquals(Instant.parse("2026-03-20T12:00:00Z"), prioritized.first().startInclusive)
        assertEquals(Instant.parse("2026-03-20T18:00:00Z"), prioritized.first().endExclusive)
        assertEquals(Instant.parse("2026-03-20T00:00:00Z"), prioritized.last().startInclusive)
    }

    @Test
    fun `refresh overlap clamps to minimum`() {
        assertEquals(MIN_RESEARCH_FEATURES_REFRESH_OVERLAP_MINUTES, resolveResearchFeaturesRefreshOverlapMinutes(0L))
    }

    @Test
    fun `background window timeout defaults below frontier timeout`() {
        assertEquals(30, resolveResearchFeaturesBackgroundWindowTimeoutSeconds(null, defaultSeconds = 30))
        assertEquals(8, resolveResearchFeaturesBackgroundWindowTimeoutSeconds(null, defaultSeconds = 8))
    }

    @Test
    fun `background window timeout clamps to minimum`() {
        assertEquals(
            MIN_RESEARCH_FEATURES_WINDOW_TIMEOUT_SECONDS,
            resolveResearchFeaturesBackgroundWindowTimeoutSeconds(1, defaultSeconds = 30)
        )
    }

    @Test
    fun `background phase budget is capped to half the refresh interval`() {
        assertEquals(
            30_000L,
            resolveResearchFeaturesBackgroundPhaseBudgetMs(
                explicitBudgetMs = 300_000L,
                refreshIntervalMs = 60_000L
            )
        )
        assertEquals(
            30_000L,
            resolveResearchFeaturesBackgroundPhaseBudgetMs(
                explicitBudgetMs = null,
                refreshIntervalMs = 60_000L
            )
        )
    }

    @Test
    fun `maintenance loop only waits the remaining interval after a long cycle`() {
        assertEquals(0L, nextMaintenanceDelayMs(refreshIntervalMs = 60_000L, cycleElapsedMs = 75_000L))
        assertEquals(15_000L, nextMaintenanceDelayMs(refreshIntervalMs = 60_000L, cycleElapsedMs = 45_000L))
    }

    @Test
    fun `historical catchup prioritizes newest missing windows near feature floor`() {
        val windows = planHistoricalCatchUpWindows(
            rawStartInclusive = Instant.parse("2026-03-10T00:00:00Z"),
            featureStartInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            now = Instant.parse("2026-03-25T00:00:00Z"),
            bootstrapHours = 336,
            refreshOverlapMinutes = 180,
            backfillChunkHours = 6,
            maxWindowsPerCycle = 3
        )

        assertEquals(3, windows.size)
        assertEquals(Instant.parse("2026-03-20T00:00:00Z"), windows[0].startInclusive)
        assertEquals(Instant.parse("2026-03-20T03:00:00Z"), windows[0].endExclusive)
        assertEquals(Instant.parse("2026-03-19T18:00:00Z"), windows[1].startInclusive)
        assertEquals(Instant.parse("2026-03-19T12:00:00Z"), windows[2].startInclusive)
    }

    @Test
    fun `historical catchup respects bootstrap floor when raw history is deeper`() {
        val windows = planHistoricalCatchUpWindows(
            rawStartInclusive = Instant.parse("2026-01-01T00:00:00Z"),
            featureStartInclusive = Instant.parse("2026-03-24T20:00:00Z"),
            now = Instant.parse("2026-03-25T00:00:00Z"),
            bootstrapHours = 24,
            refreshOverlapMinutes = 60,
            backfillChunkHours = 6,
            maxWindowsPerCycle = 4
        )

        assertEquals(4, windows.size)
        assertEquals(Instant.parse("2026-03-24T18:00:00Z"), windows.first().startInclusive)
        assertEquals(Instant.parse("2026-03-24T21:00:00Z"), windows.first().endExclusive)
        assertEquals(Instant.parse("2026-03-24T00:00:00Z"), windows.last().startInclusive)
    }

    @Test
    fun `historical catchup returns empty when feature history already reaches raw floor`() {
        val windows = planHistoricalCatchUpWindows(
            rawStartInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            featureStartInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            now = Instant.parse("2026-03-25T00:00:00Z"),
            bootstrapHours = 336,
            refreshOverlapMinutes = 180,
            backfillChunkHours = 6
        )

        assertEquals(0, windows.size)
    }

    @Test
    fun `finalized at projection pins timestamptz typing`() {
        assertEquals(
            "CASE WHEN c.bucket_time <= CAST(? AS TIMESTAMPTZ) THEN CAST(? AS TIMESTAMPTZ) ELSE NULL::TIMESTAMPTZ END",
            finalizedAtProjectionSql("c.bucket_time")
        )
    }

    @Test
    fun `startup refresh window caps bootstrap visibility query width`() {
        assertEquals(5L, startupRefreshWindowMinutes(180L))
        assertEquals(5L, startupRefreshWindowMinutes(15L))
        assertEquals(3L, startupRefreshWindowMinutes(3L))
    }

    @Test
    fun `bootstrap planning uses short chunks near the frontier`() {
        assertEquals(
            5L,
            bootstrapPlanningChunkMinutes(
                startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                endExclusive = Instant.parse("2026-03-20T00:28:00Z"),
                backfillChunkHours = 1L
            )
        )
        assertEquals(
            null,
            bootstrapPlanningChunkMinutes(
                startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                endExclusive = Instant.parse("2026-03-20T03:00:00Z"),
                backfillChunkHours = 1L
            )
        )
    }

    @Test
    fun `bootstrap start ignores startup refresh frontier when history before earliest feature is missing`() {
        assertEquals(
            Instant.parse("2026-01-14T16:01:00Z"),
            selectBootstrapStartInclusive(
                requestedStartInclusive = Instant.parse("2026-01-14T16:01:00Z"),
                earliestFeatureInclusive = Instant.parse("2026-03-31T00:30:00Z"),
                latestFeatureInclusive = Instant.parse("2026-03-31T00:35:00Z"),
                refreshOverlapMinutes = 180L
            )
        )
    }

    @Test
    fun `bootstrap start resumes near latest frontier once feature history reaches requested floor`() {
        assertEquals(
            Instant.parse("2026-03-24T17:00:00Z"),
            selectBootstrapStartInclusive(
                requestedStartInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                earliestFeatureInclusive = Instant.parse("2026-03-20T01:00:00Z"),
                latestFeatureInclusive = Instant.parse("2026-03-24T20:00:00Z"),
                refreshOverlapMinutes = 180L
            )
        )
    }

    @Test
    fun `recent gap repair lookback is at least six hours and capped by default window`() {
        assertEquals(6L, recentGapRepairHours(1L))
        assertEquals(12L, recentGapRepairHours(12L))
        assertEquals(48L, recentGapRepairHours(336L))
    }

    @Test
    fun `recent gap repair starts from five minute chunks`() {
        assertEquals(5L, recentGapRepairChunkMinutes(1L))
        assertEquals(5L, recentGapRepairChunkMinutes(6L))
    }

    @Test
    fun `recent gap repair windows per cycle clamps to minimum`() {
        assertEquals(1, resolveResearchFeaturesRecentGapRepairWindowsPerCycle(0))
        assertEquals(24, resolveResearchFeaturesRecentGapRepairWindowsPerCycle(24))
    }

    @Test
    fun `historical catchup runs once recent gap repair queue is clear`() {
        assertTrue(
            shouldRunHistoricalCatchupAfterRecentGapRepair(
                repairedRecentGaps = true,
                pendingRecentGapWindows = 0
            )
        )
        assertFalse(
            shouldRunHistoricalCatchupAfterRecentGapRepair(
                repairedRecentGaps = true,
                pendingRecentGapWindows = 3
            )
        )
        assertTrue(
            shouldRunHistoricalCatchupAfterRecentGapRepair(
                repairedRecentGaps = false,
                pendingRecentGapWindows = 5
            )
        )
    }

    @Test
    fun `best effort timeout fallback returns default on aggregation timeout`() {
        assertEquals(
            emptyList(),
            bestEffortOnAggregationTimeout(emptyList<String>()) {
                throw SQLTimeoutException("timed out")
            }
        )
        assertEquals(
            listOf("ok"),
            bestEffortOnAggregationTimeout(emptyList()) { listOf("ok") }
        )
    }

    @Test
    fun `best effort timeout fallback rethrows non timeout errors`() {
        val ex = kotlin.test.assertFailsWith<SQLException> {
            bestEffortOnAggregationTimeout(Unit) {
                throw SQLException("boom", "23505")
            }
        }
        assertEquals("23505", ex.sqlState)
    }

    @Test
    fun `aggregation window minutes measure inclusive range width in minutes`() {
        val window = AggregationWindow(
            startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-03-20T01:30:00Z")
        )

        assertEquals(90L, aggregationWindowMinutes(window))
    }

    @Test
    fun `single minute aggregation window detection only accepts one minute or less`() {
        assertTrue(
            isSingleMinuteAggregationWindow(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:01:00Z")
                )
            )
        )
        assertFalse(
            isSingleMinuteAggregationWindow(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:02:00Z")
                )
            )
        )
    }

    @Test
    fun `split aggregation window halves work newest-first`() {
        val split = splitAggregationWindow(
            window = AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                endExclusive = Instant.parse("2026-03-20T01:00:00Z")
            )
        )

        assertEquals(
            listOf(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:30:00Z"),
                    endExclusive = Instant.parse("2026-03-20T01:00:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:30:00Z")
                )
            ),
            split
        )
    }

    @Test
    fun `minimum-sized aggregation window does not subdivide`() {
        val window = AggregationWindow(
            startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-03-20T00:01:00Z")
        )

        assertFalse(canSubdivideAggregationWindow(window))
        assertEquals(listOf(window), splitAggregationWindow(window))
    }

    @Test
    fun `blocking phases expand multi minute windows into minute slices`() {
        val expanded = expandAggregationWindowsByMinute(
            windows = listOf(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:03:00Z")
                )
            ),
            shouldExpand = true
        )

        assertEquals(
            listOf(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:01:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:01:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:02:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T00:02:00Z"),
                    endExclusive = Instant.parse("2026-03-20T00:03:00Z")
                )
            ),
            expanded
        )
    }

    @Test
    fun `short non historical phases also expand into minute slices`() {
        assertTrue(
            shouldExpandAggregationWindowsByMinute(
                phase = "startup_refresh",
                windows = listOf(
                    AggregationWindow(
                        startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                        endExclusive = Instant.parse("2026-03-20T00:05:00Z")
                    )
                )
            )
        )
        assertFalse(
            shouldExpandAggregationWindowsByMinute(
                phase = "historical_catchup",
                windows = listOf(
                    AggregationWindow(
                        startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
                        endExclusive = Instant.parse("2026-03-20T00:05:00Z")
                    )
                )
            )
        )
    }

    @Test
    fun `merged aggregation window covers both current and finalized ranges`() {
        val merged = mergeAggregationWindows(
            primaryWindow = AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T00:05:00Z"),
                endExclusive = Instant.parse("2026-03-20T00:06:00Z")
            ),
            secondaryWindow = AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T00:01:00Z"),
                endExclusive = Instant.parse("2026-03-20T00:04:00Z")
            )
        )

        assertEquals(Instant.parse("2026-03-20T00:01:00Z"), merged.startInclusive)
        assertEquals(Instant.parse("2026-03-20T00:06:00Z"), merged.endExclusive)
    }

    @Test
    fun `aggregation timeout detection catches jdbc timeout and postgres cancel state`() {
        assertTrue(isAggregationQueryTimeout(SQLTimeoutException("timed out")))
        assertTrue(isAggregationQueryTimeout(SQLException("cancelled", "57014")))
        assertFalse(isAggregationQueryTimeout(SQLException("other", "23505")))
    }

    @Test
    fun `rolling recent gap repair windows walk backward through recent horizon then reset`() {
        val start = Instant.parse("2026-03-20T00:00:00Z")
        val end = Instant.parse("2026-03-20T12:00:00Z")

        val (first, firstCursor) = planRollingRecentGapRepairWindows(
            startInclusive = start,
            endExclusive = end,
            chunkMinutes = 60,
            maxWindowsPerCycle = 3
        )
        assertEquals(
            listOf(
                Instant.parse("2026-03-20T11:00:00Z"),
                Instant.parse("2026-03-20T10:00:00Z"),
                Instant.parse("2026-03-20T09:00:00Z")
            ),
            first.map { it.startInclusive }
        )
        assertEquals(Instant.parse("2026-03-20T09:00:00Z"), firstCursor)

        val (second, secondCursor) = planRollingRecentGapRepairWindows(
            startInclusive = start,
            endExclusive = end,
            chunkMinutes = 60,
            maxWindowsPerCycle = 3,
            cursorExclusive = firstCursor
        )
        assertEquals(
            listOf(
                Instant.parse("2026-03-20T08:00:00Z"),
                Instant.parse("2026-03-20T07:00:00Z"),
                Instant.parse("2026-03-20T06:00:00Z")
            ),
            second.map { it.startInclusive }
        )
        assertEquals(Instant.parse("2026-03-20T06:00:00Z"), secondCursor)

        val (finalSweep, finalCursor) = planRollingRecentGapRepairWindows(
            startInclusive = start,
            endExclusive = end,
            chunkMinutes = 60,
            maxWindowsPerCycle = 6,
            cursorExclusive = Instant.parse("2026-03-20T06:00:00Z")
        )
        assertEquals(
            listOf(
                Instant.parse("2026-03-20T05:00:00Z"),
                Instant.parse("2026-03-20T04:00:00Z"),
                Instant.parse("2026-03-20T03:00:00Z"),
                Instant.parse("2026-03-20T02:00:00Z"),
                Instant.parse("2026-03-20T01:00:00Z"),
                Instant.parse("2026-03-20T00:00:00Z")
            ),
            finalSweep.map { it.startInclusive }
        )
        assertEquals(end, finalCursor)
    }

    @Test
    fun `recent gap repair reuses pending split windows before planning new cursor work`() {
        val pending = listOf(
            AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T22:30:00Z"),
                endExclusive = Instant.parse("2026-03-20T23:00:00Z")
            ),
            AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T22:00:00Z"),
                endExclusive = Instant.parse("2026-03-20T22:30:00Z")
            )
        )

        val batch = selectRecentGapRepairBatch(
            startInclusive = Instant.parse("2026-03-20T00:00:00Z"),
            endExclusive = Instant.parse("2026-03-21T00:00:00Z"),
            chunkMinutes = 60,
            cursorExclusive = Instant.parse("2026-03-20T23:00:00Z"),
            pendingWindows = pending,
            pendingNextCursorExclusive = Instant.parse("2026-03-20T22:00:00Z")
        )

        assertTrue(batch.reusedPendingWindows)
        assertEquals(pending, batch.windows)
        assertEquals(Instant.parse("2026-03-20T22:00:00Z"), batch.nextCursorExclusive)
    }

    @Test
    fun `observed candle repair windows deduplicate and prioritize newest buckets`() {
        val windows = observedCandleRepairWindows(
            listOf(
                Instant.parse("2026-03-20T14:30:00Z"),
                Instant.parse("2026-03-20T14:31:00Z"),
                Instant.parse("2026-03-20T14:30:00Z"),
                Instant.parse("2026-03-20T14:29:00Z")
            )
        )

        assertEquals(
            listOf(
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T14:31:00Z"),
                    endExclusive = Instant.parse("2026-03-20T14:32:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T14:30:00Z"),
                    endExclusive = Instant.parse("2026-03-20T14:31:00Z")
                ),
                AggregationWindow(
                    startInclusive = Instant.parse("2026-03-20T14:29:00Z"),
                    endExclusive = Instant.parse("2026-03-20T14:30:00Z")
                )
            ),
            windows
        )
    }

    @Test
    fun `recent gap repair keeps pending windows when cycle pauses incomplete`() {
        val pending = listOf(
            AggregationWindow(
                startInclusive = Instant.parse("2026-03-20T22:30:00Z"),
                endExclusive = Instant.parse("2026-03-20T23:00:00Z")
            )
        )

        val state = advanceRecentGapRepairState(
            currentCursorExclusive = Instant.parse("2026-03-20T23:00:00Z"),
            plannedNextCursorExclusive = Instant.parse("2026-03-20T22:00:00Z"),
            result = WindowMaterializationResult(
                totalRows = 100,
                totalFinalizedRows = 80,
                completed = false,
                remainingWindows = pending
            )
        )

        assertEquals(Instant.parse("2026-03-20T23:00:00Z"), state.cursorExclusive)
        assertEquals(pending, state.pendingWindows)
        assertEquals(Instant.parse("2026-03-20T22:00:00Z"), state.pendingNextCursorExclusive)
    }

    @Test
    fun `recent gap repair advances cursor after pending work completes`() {
        val state = advanceRecentGapRepairState(
            currentCursorExclusive = Instant.parse("2026-03-20T23:00:00Z"),
            plannedNextCursorExclusive = Instant.parse("2026-03-20T22:00:00Z"),
            result = WindowMaterializationResult(
                totalRows = 100,
                totalFinalizedRows = 80,
                completed = true
            )
        )

        assertEquals(Instant.parse("2026-03-20T22:00:00Z"), state.cursorExclusive)
        assertTrue(state.pendingWindows.isEmpty())
        assertEquals(null, state.pendingNextCursorExclusive)
    }

    @Test
    fun `frontier recovery plans newest stale windows before older repairs`() {
        val windows = planFrontierRecoveryWindows(
            latestFinalizedTime = Instant.parse("2026-03-25T22:33:00Z"),
            now = Instant.parse("2026-03-26T00:24:00Z"),
            refreshOverlapMinutes = 5,
            finalizationLagMinutes = 3,
            maxWindowsPerCycle = 4
        )

        assertEquals(
            listOf(
                Instant.parse("2026-03-26T00:19:00Z"),
                Instant.parse("2026-03-26T00:14:00Z"),
                Instant.parse("2026-03-26T00:09:00Z"),
                Instant.parse("2026-03-26T00:04:00Z")
            ),
            windows.map { it.startInclusive }
        )
        assertEquals(Instant.parse("2026-03-26T00:21:00Z"), windows.first().endExclusive)
    }

    @Test
    fun `frontier recovery is idle when finalized frontier is already current enough`() {
        val windows = planFrontierRecoveryWindows(
            latestFinalizedTime = Instant.parse("2026-03-26T00:20:00Z"),
            now = Instant.parse("2026-03-26T00:24:00Z"),
            refreshOverlapMinutes = 5,
            finalizationLagMinutes = 3
        )

        assertTrue(windows.isEmpty())
    }

    @Test
    fun `frontier recovery only blocks background phases when debt spans multiple windows`() {
        val multiWindowDebt = planFrontierRecoveryWindows(
            latestFinalizedTime = Instant.parse("2026-03-25T22:33:00Z"),
            now = Instant.parse("2026-03-26T00:24:00Z"),
            refreshOverlapMinutes = 5,
            finalizationLagMinutes = 3,
            maxWindowsPerCycle = 4
        )
        val smallTailDebt = planFrontierRecoveryWindows(
            latestFinalizedTime = Instant.parse("2026-03-26T00:16:00Z"),
            now = Instant.parse("2026-03-26T00:24:00Z"),
            refreshOverlapMinutes = 5,
            finalizationLagMinutes = 3,
            maxWindowsPerCycle = 4
        )

        assertTrue(frontierRecoveryBlocksBackgroundPhases(multiWindowDebt))
        assertFalse(frontierRecoveryBlocksBackgroundPhases(smallTailDebt))
        assertFalse(frontierRecoveryBlocksBackgroundPhases(emptyList()))
    }
}
