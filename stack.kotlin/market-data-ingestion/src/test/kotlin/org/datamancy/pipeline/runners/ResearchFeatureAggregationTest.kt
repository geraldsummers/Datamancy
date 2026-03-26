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
    fun `recent gap repair lookback is at least six hours and capped by default window`() {
        assertEquals(6L, recentGapRepairHours(1L))
        assertEquals(12L, recentGapRepairHours(12L))
        assertEquals(48L, recentGapRepairHours(336L))
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
            chunkHours = 1,
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
            chunkHours = 1,
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
            chunkHours = 1,
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
