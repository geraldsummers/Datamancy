package org.datamancy.pipeline.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.datamancy.pipeline.runners.HyperliquidUniverseResolver
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

private data class ProbePlan(
    val label: String,
    val symbols: List<String>,
    val subscribeToTrades: Boolean,
    val subscribeToCandles: Boolean,
    val subscribeToOrderbook: Boolean,
    val subscribeToAssetCtx: Boolean
)

private data class ProbeResult(
    val label: String,
    val symbols: Int,
    val events: Int,
    val status: String,
    val detail: String? = null
)

private fun chunkBySizes(symbols: List<String>, sizes: List<Int>): List<List<String>> {
    var offset = 0
    return sizes.map { size ->
        val end = (offset + size).coerceAtMost(symbols.size)
        val chunk = symbols.subList(offset, end)
        offset = end
        chunk
    }.filter { it.isNotEmpty() }
}

private fun loadSymbols(path: Path): List<String> {
    if (!path.exists()) error("symbol file not found: $path")
    return Files.readAllLines(path)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
}

private suspend fun loadSymbolsFromResolver(): List<String> {
    val config = loadMarketDataServiceConfig()
    val resolver = HyperliquidUniverseResolver(
        infoUrl = config.infoUrl,
        marketCatalogUrl = config.marketCatalogUrl
    )
    return try {
        resolver.resolve(config.universeSettings).symbols
    } finally {
        runCatching { resolver.close() }
    }
}

private fun buildProbePlans(symbols: List<String>): List<ProbePlan> {
    val execChunks = chunkBySizes(symbols, listOf(52, 52, 52, 51))
    val candleChunks = chunkBySizes(symbols, listOf(35, 35, 35, 35, 35, 32))
    return buildList {
        addAll(
            execChunks.mapIndexed { index, chunk ->
                ProbePlan(
                    label = "exec_${index + 1}",
                    symbols = chunk,
                    subscribeToTrades = true,
                    subscribeToCandles = false,
                    subscribeToOrderbook = true,
                    subscribeToAssetCtx = true
                )
            }
        )
        addAll(
            candleChunks.mapIndexed { index, chunk ->
                ProbePlan(
                    label = "candle_${index + 1}",
                    symbols = chunk,
                    subscribeToTrades = false,
                    subscribeToCandles = true,
                    subscribeToOrderbook = false,
                    subscribeToAssetCtx = false
                )
            }
        )
    }
}

fun main(args: Array<String>) = runBlocking {
    val useResolver = args.getOrNull(0) == "--resolver"
    val dumpSymbols = args.contains("--dump-symbols")
    val symbolFile = if (useResolver) {
        null
    } else {
        args.getOrNull(0)?.let { Path.of(it) } ?: Path.of("/tmp/hl_symbols.txt")
    }
    val observeArgIndex = if (useResolver) 1 else 1
    val publishArgIndex = if (useResolver) 2 else 2
    val observeMs = args.getOrNull(observeArgIndex)?.toLongOrNull() ?: 20_000L
    val publishMode = args.getOrNull(publishArgIndex)?.equals("publish", ignoreCase = true) == true
    val symbols = if (useResolver) loadSymbolsFromResolver() else loadSymbols(symbolFile!!)
    if (dumpSymbols) {
        symbols.forEachIndexed { index, symbol ->
            println("${index + 1},$symbol")
        }
        return@runBlocking
    }
    val plans = buildProbePlans(symbols)
    val transport = if (publishMode) {
        NatsJetStreamRawMarketDataTransport(
            config = RawEventTransportConfig(
                url = System.getenv("RAW_EVENT_BUS_URL") ?: "nats://localhost:4222",
                stream = "PROBE_MARKET_DATA_RAW",
                ingestSubjectPrefix = "probe.raw.market.ingest",
                persistSubjectPrefix = "probe.raw.market.persist",
                dlqSubject = "probe.raw.market.dlq",
                maxAgeHours = 1L,
                fetchBatch = 128,
                fetchExpiresMs = 5_000L,
                maxAckPending = 2_048L
            ),
            connectionName = "hyperliquid-source-probe"
        )
    } else {
        null
    }

    println(
        "symbols=${symbols.size} plans=${plans.size} observeMs=$observeMs " +
            "symbolSource=${if (useResolver) "resolver" else symbolFile} publishMode=$publishMode"
    )

    try {
        val results = plans.mapIndexed { index, plan ->
            async {
                if (index > 0) {
                    delay((index * 250L).coerceAtMost(5_000L))
                }
                val source = HyperliquidSource(
                    symbols = plan.symbols,
                    subscribeToTrades = plan.subscribeToTrades,
                    subscribeToCandles = plan.subscribeToCandles,
                    candleIntervals = listOf("5m", "4h"),
                    subscribeToOrderbook = plan.subscribeToOrderbook,
                    subscribeToAssetCtx = plan.subscribeToAssetCtx,
                    url = "wss://api.hyperliquid.xyz/ws",
                    receiveIdleTimeoutMs = 120_000L
                )
                var events = 0
                try {
                    withTimeout(observeMs) {
                        source.fetch().collect { data: HyperliquidMarketData ->
                            events += 1
                            if (transport != null) {
                                val envelope = RawMarketDataEnvelope.from(
                                    exchangeId = "hyperliquid_mainnet",
                                    source = plan.label,
                                    marketData = data
                                )
                                transport.publish(
                                    subject = transportConfigForProbe().ingestSubject(
                                        exchangeId = "hyperliquid_mainnet",
                                        channel = envelope.channel,
                                        lane = envelope.lane
                                    ),
                                    envelope = envelope
                                )
                            }
                        }
                    }
                    ProbeResult(plan.label, plan.symbols.size, events, "completed")
                } catch (_: TimeoutCancellationException) {
                    ProbeResult(plan.label, plan.symbols.size, events, "timeout_ok")
                } catch (t: Throwable) {
                    ProbeResult(
                        label = plan.label,
                        symbols = plan.symbols.size,
                        events = events,
                        status = "failed",
                        detail = "${t::class.simpleName}: ${t.message}"
                    )
                } finally {
                    withContext(Dispatchers.IO) {
                        runCatching { source.close() }
                    }
                }
            }
        }.awaitAll()

        results.forEach { result ->
            println(
                "${result.label} symbols=${result.symbols} events=${result.events} " +
                    "status=${result.status} detail=${result.detail}"
            )
        }
    } finally {
        withContext(Dispatchers.IO) {
            runCatching { transport?.close() }
        }
    }
}

private fun transportConfigForProbe(): RawEventTransportConfig =
    RawEventTransportConfig(
        url = System.getenv("RAW_EVENT_BUS_URL") ?: "nats://localhost:4222",
        stream = "PROBE_MARKET_DATA_RAW",
        ingestSubjectPrefix = "probe.raw.market.ingest",
        persistSubjectPrefix = "probe.raw.market.persist",
        dlqSubject = "probe.raw.market.dlq",
        maxAgeHours = 1L,
        fetchBatch = 128,
        fetchExpiresMs = 5_000L,
        maxAckPending = 2_048L
    )
