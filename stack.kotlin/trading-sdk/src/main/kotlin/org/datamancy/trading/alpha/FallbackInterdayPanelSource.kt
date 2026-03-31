package org.datamancy.trading.alpha

import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.math.ceil

private const val DEFAULT_MIN_PANEL_HISTORY_COVERAGE_RATIO = 0.80
private val fallbackPanelLogger = LoggerFactory.getLogger(FallbackInterdayPanelSource::class.java)

class FallbackInterdayPanelSource(
    private val primary: InterdayPanelSource,
    private val fallback: InterdayPanelSource,
    private val minCoverageRatio: Double = DEFAULT_MIN_PANEL_HISTORY_COVERAGE_RATIO
) : InterdayPanelSource {
    override suspend fun load(request: InterdayPanelRequest): InterdayPanel {
        val primaryResult = runCatching { primary.load(request) }
        val primaryPanel = primaryResult.getOrNull()
        if (primaryPanel != null && panelHasSufficientHistory(primaryPanel, request, minCoverageRatio)) {
            return primaryPanel
        }

        val expectedBars = expectedPanelBars(request)
        if (primaryPanel != null) {
            fallbackPanelLogger.warn(
                "Primary interday panel source returned sparse history exchange={} signalBarMinutes={} bars={} expectedBars={} start={} end={}; falling back",
                request.exchange,
                request.signalBarMinutes,
                primaryPanel.timeline.size,
                expectedBars,
                request.startTime,
                request.endTime
            )
        } else {
            fallbackPanelLogger.warn(
                "Primary interday panel source failed exchange={} signalBarMinutes={} start={} end={}; falling back",
                request.exchange,
                request.signalBarMinutes,
                request.startTime,
                request.endTime,
                primaryResult.exceptionOrNull()
            )
        }
        return fallback.load(request)
    }
}

internal fun panelHasSufficientHistory(
    panel: InterdayPanel,
    request: InterdayPanelRequest,
    minCoverageRatio: Double = DEFAULT_MIN_PANEL_HISTORY_COVERAGE_RATIO
): Boolean {
    val expectedBars = expectedPanelBars(request)
    if (expectedBars <= 0) return panel.timeline.isNotEmpty()
    if (panel.timeline.isEmpty()) return false
    val coverageRatio = panel.timeline.size.toDouble() / expectedBars.toDouble()
    return coverageRatio >= minCoverageRatio.coerceIn(0.0, 1.0)
}

internal fun expectedPanelBars(request: InterdayPanelRequest): Int {
    val barMinutes = request.signalBarMinutes.coerceAtLeast(1)
    val durationMinutes = Duration.between(request.startTime, request.endTime).toMinutes().coerceAtLeast(0L)
    if (durationMinutes <= 0L) return 1
    return ceil(durationMinutes.toDouble() / barMinutes.toDouble()).toInt().coerceAtLeast(1)
}
