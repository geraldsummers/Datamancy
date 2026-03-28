package org.datamancy.marketdataarchiveimporter

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.datamancy.trading.alpha.ArchiveImportChannelResult
import org.datamancy.trading.alpha.ArchiveImportRunRequest
import org.datamancy.trading.alpha.ArchiveImportRunResponse
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val archiveLogger = KotlinLogging.logger {}
private val archiveDateFormatter: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

class ArchiveImportService(
    private val config: ArchiveImporterConfig = loadArchiveImporterConfig(),
    private val objectStore: ArchiveObjectStore = S3ArchiveObjectStore(config),
    private val persistence: ArchivePersistence = PostgresArchivePersistence(),
    private val parsers: ArchiveLineParsers = ArchiveLineParsers()
) {
    fun run(request: ArchiveImportRunRequest): ArchiveImportRunResponse {
        require(request.exchange == "hyperliquid_mainnet") {
            "archive import currently supports exchange=hyperliquid_mainnet only"
        }
        val startDate = LocalDate.parse(request.startDate)
        val endDate = LocalDate.parse(request.endDate)
        require(!endDate.isBefore(startDate)) { "endDate must be on or after startDate" }
        val requestedChannels = request.channels.map(String::trim).filter(String::isNotEmpty).distinct()
        require(requestedChannels.isNotEmpty()) { "channels must not be empty" }
        val requestedSymbols = request.symbols.map(String::trim).filter(String::isNotEmpty).toSet()
        val results = mutableListOf<ArchiveImportChannelResult>()
        val notes = mutableListOf<String>()

        if (requestedChannels.any { it == "trade" || it == "candle_1m" }) {
            val tradeImport = importTradesAndSynthesizedCandles(
                exchange = request.exchange,
                startDate = startDate,
                endDate = endDate,
                requestedSymbols = requestedSymbols
            )
            if ("trade" in requestedChannels) {
                results += tradeImport.tradeResult
            }
            if ("candle_1m" in requestedChannels) {
                results += tradeImport.candleResult
            }
            notes += tradeImport.notes
        }
        if (requestedChannels.any { it == "funding" || it == "open_interest" }) {
            val assetResult = importAssetContexts(
                exchange = request.exchange,
                startDate = startDate,
                endDate = endDate,
                requestedSymbols = requestedSymbols
            )
            if ("funding" in requestedChannels) results += assetResult.copy(channel = "funding")
            if ("open_interest" in requestedChannels) results += assetResult.copy(channel = "open_interest")
        }
        if ("orderbook_l2" in requestedChannels) {
            results += importOrderbooks(
                exchange = request.exchange,
                startDate = startDate,
                endDate = endDate,
                requestedSymbols = requestedSymbols
            )
        }
        if (results.isEmpty()) {
            notes += "No archive channels were imported for the request."
        }
        return ArchiveImportRunResponse(
            generatedAt = Instant.now(),
            exchange = request.exchange,
            startDate = request.startDate,
            endDate = request.endDate,
            requestedChannels = requestedChannels,
            requestedSymbols = request.symbols,
            results = results,
            notes = notes.distinct()
        )
    }

    private data class TradeImportOutcome(
        val tradeResult: ArchiveImportChannelResult,
        val candleResult: ArchiveImportChannelResult,
        val notes: List<String>
    )

    private fun importTradesAndSynthesizedCandles(
        exchange: String,
        startDate: LocalDate,
        endDate: LocalDate,
        requestedSymbols: Set<String>
    ): TradeImportOutcome {
        val refs = listTradeObjects(startDate, endDate)
        val tradeAccumulator = ChannelAccumulator("trade")
        val candleAccumulator = ChannelAccumulator("candle_1m")
        refs.forEach { ref ->
            val trades = readLines(ref)
                .flatMap(parsers::parseTradeLine)
                .filter { requestedSymbols.isEmpty() || it.symbol in requestedSymbols }
                .toList()
            if (trades.isEmpty()) {
                return@forEach
            }
            persistence.persistTrades(exchange, trades)
            tradeAccumulator.recordObject(trades.map { it.symbol }.toSet(), trades.minOf { it.time }, trades.maxOf { it.time }, trades.size.toLong())
            val synthStart = trades.minOf { it.time }.truncatedTo(ChronoUnit.MINUTES)
            val synthEnd = trades.maxOf { it.time }.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
            val synth = persistence.synthesizeMinuteCandlesFromTrades(
                exchange = exchange,
                startInclusive = synthStart,
                endExclusive = synthEnd,
                symbols = trades.map { it.symbol }.toSet()
            )
            candleAccumulator.recordSummary(synth)
            archiveLogger.info {
                "archive-import trade object=${ref.key} trades=${trades.size} synthCandles=${synth.rowCount} exchange=$exchange"
            }
        }
        val tradeResult = tradeAccumulator.result(
            note = if (refs.isEmpty()) "No node trade archive objects matched the requested date range." else null
        )
        val candleResult = candleAccumulator.result(
            note = if (refs.isEmpty()) {
                "No trade archives were available, so no candle_1m rows were synthesized."
            } else {
                "candle_1m rows were synthesized from imported archived trades."
            }
        ).copy(symbolsImported = tradeResult.symbolsImported)
        return TradeImportOutcome(
            tradeResult = tradeResult,
            candleResult = candleResult,
            notes = listOf(
                "trade and candle_1m archive recovery currently relies on archived node fill/trade data, not archived candle snapshots."
            )
        )
    }

    private fun importAssetContexts(
        exchange: String,
        startDate: LocalDate,
        endDate: LocalDate,
        requestedSymbols: Set<String>
    ): ArchiveImportChannelResult {
        val refs = listAssetContextObjects(startDate, endDate, requestedSymbols)
        val accumulator = ChannelAccumulator("asset_context")
        refs.forEach { ref ->
            val contexts = readLines(ref)
                .mapNotNull(parsers::parseAssetContextLine)
                .filter { requestedSymbols.isEmpty() || it.symbol in requestedSymbols }
                .toList()
            if (contexts.isEmpty()) return@forEach
            persistence.persistAssetContexts(exchange, contexts)
            accumulator.recordObject(
                symbols = contexts.map { it.symbol }.toSet(),
                earliestTime = contexts.minOf { it.time },
                latestTime = contexts.maxOf { it.time },
                rowCount = contexts.size.toLong()
            )
            archiveLogger.info {
                "archive-import asset-context object=${ref.key} rows=${contexts.size} exchange=$exchange"
            }
        }
        return accumulator.result(
            note = if (refs.isEmpty()) "No asset_ctx archive objects matched the requested date range." else null
        )
    }

    private fun importOrderbooks(
        exchange: String,
        startDate: LocalDate,
        endDate: LocalDate,
        requestedSymbols: Set<String>
    ): ArchiveImportChannelResult {
        val refs = listOrderbookObjects(startDate, endDate, requestedSymbols)
        val accumulator = ChannelAccumulator("orderbook_l2")
        refs.forEach { ref ->
            val orderbooks = readLines(ref)
                .mapNotNull(parsers::parseOrderbookLine)
                .filter { requestedSymbols.isEmpty() || it.symbol in requestedSymbols }
                .toList()
            if (orderbooks.isEmpty()) return@forEach
            persistence.persistOrderbooks(exchange, orderbooks)
            accumulator.recordObject(
                symbols = orderbooks.map { it.symbol }.toSet(),
                earliestTime = orderbooks.minOf { it.time },
                latestTime = orderbooks.maxOf { it.time },
                rowCount = orderbooks.size.toLong()
            )
            archiveLogger.info {
                "archive-import orderbook object=${ref.key} rows=${orderbooks.size} exchange=$exchange"
            }
        }
        return accumulator.result(
            note = if (refs.isEmpty()) "No archived l2Book objects matched the requested date range." else null
        )
    }

    private fun listTradeObjects(startDate: LocalDate, endDate: LocalDate): List<ArchiveObjectRef> {
        val refs = linkedMapOf<String, ArchiveObjectRef>()
        dates(startDate, endDate).forEach { date ->
            tradePrefixes(date).forEach { prefix ->
                objectStore.listObjects(config.hyperliquidNodeArchiveBucket, prefix).forEach { ref ->
                    if (ref.key.endsWith(".lz4")) {
                        refs["${ref.bucket}:${ref.key}"] = ref
                    }
                }
            }
        }
        return refs.values.sortedBy { it.key }
    }

    private fun listAssetContextObjects(
        startDate: LocalDate,
        endDate: LocalDate,
        requestedSymbols: Set<String>
    ): List<ArchiveObjectRef> {
        val refs = mutableListOf<ArchiveObjectRef>()
        dates(startDate, endDate).forEach { date ->
            objectStore.listObjects(config.hyperliquidArchiveBucket, "asset_ctxs/${date.format(archiveDateFormatter)}/")
                .filter { it.key.endsWith(".lz4") && isSymbolScopedObjectSelected(it.key, requestedSymbols) }
                .let(refs::addAll)
        }
        return refs.distinctBy { "${it.bucket}:${it.key}" }.sortedBy { it.key }
    }

    private fun listOrderbookObjects(
        startDate: LocalDate,
        endDate: LocalDate,
        requestedSymbols: Set<String>
    ): List<ArchiveObjectRef> {
        val refs = mutableListOf<ArchiveObjectRef>()
        dates(startDate, endDate).forEach { date ->
            objectStore.listObjects(config.hyperliquidArchiveBucket, "market_data/${date.format(archiveDateFormatter)}/")
                .filter { it.key.endsWith(".lz4") && "/l2Book/" in it.key && isSymbolScopedObjectSelected(it.key, requestedSymbols) }
                .let(refs::addAll)
        }
        return refs.distinctBy { "${it.bucket}:${it.key}" }.sortedBy { it.key }
    }

    private fun readLines(ref: ArchiveObjectRef): Sequence<String> {
        val input = objectStore.open(ref)
        val decoded = if (ref.key.endsWith(".lz4")) {
            FramedLZ4CompressorInputStream(input)
        } else {
            input
        }
        val reader = BufferedReader(InputStreamReader(decoded))
        return sequence {
            reader.use { buffered ->
                buffered.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        yield(line)
                    }
                }
            }
        }
    }

    private fun isSymbolScopedObjectSelected(key: String, requestedSymbols: Set<String>): Boolean {
        if (requestedSymbols.isEmpty()) return true
        val filename = key.substringAfterLast('/').removeSuffix(".lz4")
        return filename in requestedSymbols
    }

    private fun tradePrefixes(date: LocalDate): List<String> {
        val text = date.format(archiveDateFormatter)
        return listOf(
            "node_fills_by_block/hourly/$text/",
            "node_fills_by_block/$text/",
            "node_fills/hourly/$text/",
            "node_fills/$text/",
            "node_trades/hourly/$text/",
            "node_trades/$text/"
        )
    }

    private fun dates(startDate: LocalDate, endDate: LocalDate): Sequence<LocalDate> = sequence {
        var current = startDate
        while (!current.isAfter(endDate)) {
            yield(current)
            current = current.plusDays(1)
        }
    }

    private class ChannelAccumulator(
        private val channel: String
    ) {
        private var objectsProcessed = 0
        private var rowsImported = 0L
        private var earliestTime: Instant? = null
        private var latestTime: Instant? = null
        private val symbols = linkedSetOf<String>()

        fun recordObject(symbols: Set<String>, earliestTime: Instant, latestTime: Instant, rowCount: Long) {
            objectsProcessed += 1
            rowsImported += rowCount
            this.symbols += symbols
            this.earliestTime = minInstant(this.earliestTime, earliestTime)
            this.latestTime = maxInstant(this.latestTime, latestTime)
        }

        fun recordSummary(summary: ArchivePersistSummary) {
            if (summary.rowCount <= 0) return
            objectsProcessed += 1
            rowsImported += summary.rowCount
            this.earliestTime = minInstant(this.earliestTime, summary.earliestTime)
            this.latestTime = maxInstant(this.latestTime, summary.latestTime)
        }

        fun result(note: String? = null): ArchiveImportChannelResult = ArchiveImportChannelResult(
            channel = channel,
            objectsProcessed = objectsProcessed,
            rowsImported = rowsImported,
            earliestTime = earliestTime,
            latestTime = latestTime,
            symbolsImported = symbols.size,
            notes = listOfNotNull(note)
        )
    }
}

private fun minInstant(left: Instant?, right: Instant?): Instant? = when {
    left == null -> right
    right == null -> left
    else -> minOf(left, right)
}

private fun maxInstant(left: Instant?, right: Instant?): Instant? = when {
    left == null -> right
    right == null -> left
    else -> maxOf(left, right)
}
