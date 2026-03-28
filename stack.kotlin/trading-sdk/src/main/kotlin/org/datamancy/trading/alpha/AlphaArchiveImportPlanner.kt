package org.datamancy.trading.alpha

import java.time.LocalDate
import java.time.YearMonth

class AlphaArchiveImportPlanner {
    fun plan(request: ArchiveImportPlanRequest): ArchiveImportPlan {
        val start = LocalDate.parse(request.startDate)
        val end = LocalDate.parse(request.endDate)
        require(!end.isBefore(start)) { "endDate must be on or after startDate" }
        val windows = mutableListOf<ArchiveImportWindow>()
        var cursor = YearMonth.from(start)
        val endMonth = YearMonth.from(end)
        while (!cursor.isAfter(endMonth)) {
            val monthText = cursor.toString()
            val includesExecution = request.channels.any { it == "orderbook_l2" || it == "trade" }
            windows += ArchiveImportWindow(
                month = monthText,
                exchange = request.exchange,
                channels = request.channels,
                source = if (includesExecution) "hyperliquid-archive + hl-mainnet-node-data" else "hyperliquid-archive",
                notes = buildList {
                    add("Monthly partition sized for bounded reingestion and replay.")
                    if ("orderbook_l2" in request.channels) add("Orderbook history quality depends on archive availability; treat execution backfill separately from signal backfill.")
                    if ("open_interest" in request.channels || "funding" in request.channels) add("Carry-side exchange state is included so cross-sectional signal research can use OI and funding as first-class inputs.")
                }
            )
            cursor = cursor.plusMonths(1)
        }
        return ArchiveImportPlan(
            exchange = request.exchange,
            startDate = request.startDate,
            endDate = request.endDate,
            windows = windows,
            notes = listOf(
                "Archive imports should backfill price action broadly, then layer execution and orderbook archives only where fill-model validation needs them.",
                "This planner is intentionally monthly because Hyperliquid archive drops are monthly and bounded replay is easier to operate that way."
            )
        )
    }
}
