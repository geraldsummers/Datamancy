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
}
