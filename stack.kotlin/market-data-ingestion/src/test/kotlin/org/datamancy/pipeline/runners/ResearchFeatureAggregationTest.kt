package org.datamancy.pipeline.runners

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
