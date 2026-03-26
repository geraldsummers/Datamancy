package org.datamancy.pipeline.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.pipeline.runners.CandleHistoricalBackfillCandidate
import org.datamancy.pipeline.runners.FeatureStateStore
import org.datamancy.pipeline.runners.HYPERLIQUID_MAINNET_WS_URL
import org.datamancy.pipeline.runners.HYPERLIQUID_TESTNET_WS_URL
import org.datamancy.pipeline.runners.HyperliquidBackfillDeferredException
import org.datamancy.pipeline.runners.HyperliquidCandleBackfillClient
import org.datamancy.pipeline.runners.HyperliquidContinuityWatchdog
import org.datamancy.pipeline.runners.HyperliquidUniverseMode
import org.datamancy.pipeline.runners.HyperliquidUniverseResolver
import org.datamancy.pipeline.runners.HyperliquidUniverseSettings
import org.datamancy.pipeline.runners.HyperliquidUniverseSnapshot
import org.datamancy.pipeline.runners.RawCandleCoverageState
import org.datamancy.pipeline.runners.RawCandleRecoveryAction
import org.datamancy.pipeline.runners.RawCandleRecoveryPlannerState
import org.datamancy.pipeline.runners.RawSyncStateStore
import org.datamancy.pipeline.runners.ResearchFeatureAggregator
import org.datamancy.pipeline.runners.candleAllowedLagMs
import org.datamancy.pipeline.runners.candleIntervalToMillis
import org.datamancy.pipeline.runners.candleTradeRelevanceWindowMs
import org.datamancy.pipeline.runners.determineCandleRepairPermits
import org.datamancy.pipeline.runners.planCandleBackfillWindowsForRange
import org.datamancy.pipeline.runners.planRawCandleRecoveryAction
import org.datamancy.pipeline.runners.prioritizeHistoricalBackfillCandidates
import org.datamancy.pipeline.runners.reconnectBackoffDelayMs
import org.datamancy.pipeline.runners.resolveHyperliquidBackfillLookbackHours
import org.datamancy.pipeline.runners.resolveHyperliquidBackfillMaxBars
import org.datamancy.pipeline.runners.resolveHyperliquidBackfillOverlapBars
import org.datamancy.pipeline.runners.resolveHyperliquidCandleStaleMultiplier
import org.datamancy.pipeline.runners.resolveHyperliquidChannelActivityTimeoutMs
import org.datamancy.pipeline.runners.resolveHyperliquidExchangeId
import org.datamancy.pipeline.runners.resolveHyperliquidFreshnessCheckIntervalMs
import org.datamancy.pipeline.runners.resolveHyperliquidIdleTimeoutMs
import org.datamancy.pipeline.runners.resolveHyperliquidInfoUrl
import org.datamancy.pipeline.runners.resolveHyperliquidUniverseRefreshIntervalMs
import org.datamancy.pipeline.runners.resolveResearchFeaturesBackfillChunkHours
import org.datamancy.pipeline.runners.resolveResearchFeaturesBootstrapHours
import org.datamancy.pipeline.runners.resolveResearchFeaturesRefreshIntervalMs
import org.datamancy.pipeline.runners.resolveResearchFeaturesRefreshOverlapMinutes
import org.datamancy.pipeline.runners.restartContinuityIssueThreshold
import org.datamancy.pipeline.runners.shouldRestartForContinuityIssues
import org.datamancy.pipeline.sinks.IngestionStats
import org.datamancy.pipeline.sinks.MarketDataSink
import org.datamancy.pipeline.sources.HyperliquidAssetContext
import org.datamancy.pipeline.sources.HyperliquidCandle
import org.datamancy.pipeline.sources.HyperliquidMarketData
import org.datamancy.pipeline.sources.HyperliquidOrderbook
import org.datamancy.pipeline.sources.HyperliquidOrderbookLevel
import org.datamancy.pipeline.sources.HyperliquidSource
import org.datamancy.pipeline.sources.HyperliquidTrade
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.RequirementLevel
import org.datamancy.trading.policy.UniverseSelectionMode
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Array as SqlArray
import java.sql.Connection as JdbcConnection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private val splitLogger = KotlinLogging.logger {}
private const val DEFAULT_RAW_EVENT_BUS_URL = "nats://nats:4222"
private const val DEFAULT_RAW_EVENT_STREAM = "MARKET_DATA_RAW"
private const val DEFAULT_RAW_EVENT_INGEST_SUBJECT_PREFIX = "raw.market.ingest"
private const val DEFAULT_RAW_EVENT_PERSIST_SUBJECT_PREFIX = "raw.market.persist"
private const val DEFAULT_RAW_EVENT_DLQ_SUBJECT = "raw.market.dlq"
private const val DEFAULT_RAW_EVENT_MAX_AGE_HOURS = 168L
private const val DEFAULT_RAW_EVENT_FETCH_BATCH = 128
private const val DEFAULT_RAW_EVENT_FETCH_EXPIRES_MS = 5_000L
private const val DEFAULT_RAW_EVENT_MAX_ACK_PENDING = 2_048L
private const val DEFAULT_STATE_REFRESH_INTERVAL_MS = 300_000L
private const val DEFAULT_STATE_REFRESH_LOOKBACK_HOURS = 48L
private const val DEFAULT_SYNC_STATS_INTERVAL_MS = 60_000L
private const val DEFAULT_PERSIST_STATS_INTERVAL_MS = 60_000L
private const val DEFAULT_DB_WAIT_ATTEMPTS = 90
private const val DEFAULT_DB_WAIT_DELAY_MS = 2_000L
private const val DEFAULT_HYPERLIQUID_INITIAL_RECENT_REPAIR_BATCH_STREAMS = 24

internal data class RawEventTransportConfig(
    val url: String,
    val stream: String,
    val ingestSubjectPrefix: String,
    val persistSubjectPrefix: String,
    val dlqSubject: String,
    val maxAgeHours: Long,
    val fetchBatch: Int,
    val fetchExpiresMs: Long,
    val maxAckPending: Long
) {
    fun ingestSubject(exchangeId: String, channel: String, lane: RawEventLane): String =
        "${ingestSubjectPrefix}.${lane.subjectToken}.${sanitizeSubjectToken(exchangeId)}.${sanitizeSubjectToken(channel)}"

    fun persistWildcard(lane: RawEventLane): String = "${persistSubjectPrefix}.${lane.subjectToken}.>"

    fun persistSubject(exchangeId: String, channel: String, lane: RawEventLane): String =
        "${persistSubjectPrefix}.${lane.subjectToken}.${sanitizeSubjectToken(exchangeId)}.${sanitizeSubjectToken(channel)}"
}

internal data class StateUpdaterConfig(
    val refreshIntervalMs: Long,
    val refreshLookbackHours: Long
)

internal data class MarketDataServiceConfig(
    val postgresHost: String,
    val postgresPort: Int,
    val postgresDb: String,
    val postgresUser: String,
    val postgresPassword: String,
    val batchSize: Int,
    val flushIntervalSeconds: Long,
    val exchangeId: String,
    val mainnet: Boolean,
    val wsUrl: String,
    val infoUrl: String,
    val idleTimeoutMs: Long,
    val freshnessCheckIntervalMs: Long,
    val channelActivityTimeoutMs: Long,
    val candleStaleMultiplier: Double,
    val backfillLookbackHours: Long,
    val backfillMaxBars: Int,
    val backfillOverlapBars: Int,
    val researchFeaturesEnabled: Boolean,
    val researchFeaturesBootstrapHours: Long,
    val researchFeaturesRefreshIntervalMs: Long,
    val researchFeaturesRefreshOverlapMinutes: Long,
    val researchFeaturesBackfillChunkHours: Long,
    val researchFeaturesFinalizationLagMinutes: Long,
    val universeSettings: HyperliquidUniverseSettings,
    val enableOrderbook: Boolean,
    val splitCandlesFromExecution: Boolean,
    val symbolsPerConnection: Int,
    val candleSymbolsPerConnection: Int,
    val executionSymbolsPerConnection: Int,
    val candleIntervals: List<String>,
    val rawEventTransport: RawEventTransportConfig,
    val stateUpdater: StateUpdaterConfig
)

internal fun loadMarketDataServiceConfig(): MarketDataServiceConfig {
    val tradingPolicy = ActiveTradingPolicy.current()
    val hyperliquidPolicy = tradingPolicy.venue("hyperliquid")
    val universeMode = when (hyperliquidPolicy.universe.selectionMode) {
        UniverseSelectionMode.STATIC -> HyperliquidUniverseMode.STATIC
        UniverseSelectionMode.EXCHANGE_CATALOG -> HyperliquidUniverseMode.CATALOG
    }

    return MarketDataServiceConfig(
        postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres",
        postgresPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432,
        postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy",
        postgresUser = System.getenv("POSTGRES_USER") ?: "pipeline",
        postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: "",
        batchSize = System.getenv("MARKET_DATA_BATCH_SIZE")?.toIntOrNull() ?: 250,
        flushIntervalSeconds = System.getenv("MARKET_DATA_FLUSH_SECONDS")?.toLongOrNull() ?: 10L,
        exchangeId = hyperliquidPolicy.exchangeId,
        mainnet = hyperliquidPolicy.mainnet,
        wsUrl = resolveHyperliquidWsUrl(
            explicitUrl = hyperliquidPolicy.websocketUrl,
            mainnet = hyperliquidPolicy.mainnet
        ),
        infoUrl = resolveHyperliquidInfoUrl(
            explicitUrl = hyperliquidPolicy.infoUrl,
            mainnet = hyperliquidPolicy.mainnet
        ),
        idleTimeoutMs = resolveHyperliquidIdleTimeoutMs(hyperliquidPolicy.rawSync.idleTimeoutMs),
        freshnessCheckIntervalMs = resolveHyperliquidFreshnessCheckIntervalMs(
            hyperliquidPolicy.rawSync.freshnessCheckIntervalMs
        ),
        channelActivityTimeoutMs = resolveHyperliquidChannelActivityTimeoutMs(
            hyperliquidPolicy.rawSync.channelActivityTimeoutMs
        ),
        candleStaleMultiplier = resolveHyperliquidCandleStaleMultiplier(
            hyperliquidPolicy.rawSync.candleStaleMultiplier
        ),
        backfillLookbackHours = resolveHyperliquidBackfillLookbackHours(
            hyperliquidPolicy.rawSync.backfillLookbackHours
        ),
        backfillMaxBars = resolveHyperliquidBackfillMaxBars(hyperliquidPolicy.rawSync.backfillMaxBars),
        backfillOverlapBars = resolveHyperliquidBackfillOverlapBars(
            hyperliquidPolicy.rawSync.backfillOverlapBars
        ),
        researchFeaturesEnabled = hyperliquidPolicy.features.enabled,
        researchFeaturesBootstrapHours = resolveResearchFeaturesBootstrapHours(
            hyperliquidPolicy.features.bootstrapHours
        ),
        researchFeaturesRefreshIntervalMs = resolveResearchFeaturesRefreshIntervalMs(
            hyperliquidPolicy.features.refreshIntervalMs
        ),
        researchFeaturesRefreshOverlapMinutes = resolveResearchFeaturesRefreshOverlapMinutes(
            hyperliquidPolicy.features.refreshOverlapMinutes
        ),
        researchFeaturesBackfillChunkHours = resolveResearchFeaturesBackfillChunkHours(
            hyperliquidPolicy.features.backfillChunkHours
        ),
        researchFeaturesFinalizationLagMinutes = hyperliquidPolicy.features.finalizationLagMinutes,
        universeSettings = HyperliquidUniverseSettings(
            mode = universeMode,
            staticSymbols = hyperliquidPolicy.universe.staticSymbols,
            includeSymbols = hyperliquidPolicy.universe.includeSymbols.toSet(),
            excludeSymbols = hyperliquidPolicy.universe.excludeSymbols.toSet(),
            includeDelisted = hyperliquidPolicy.universe.includeDelisted,
            refreshIntervalMs = resolveHyperliquidUniverseRefreshIntervalMs(
                hyperliquidPolicy.universe.refreshIntervalMs
            )
        ),
        enableOrderbook = hyperliquidPolicy.rawSync.channels["orderbook_l2"] != RequirementLevel.DISABLED,
        splitCandlesFromExecution = hyperliquidPolicy.rawSync.splitCandlesFromExecution,
        symbolsPerConnection = hyperliquidPolicy.universe.symbolsPerConnection.coerceAtLeast(1),
        candleSymbolsPerConnection = hyperliquidPolicy.rawSync.candleSymbolsPerConnection.coerceAtLeast(1),
        executionSymbolsPerConnection = hyperliquidPolicy.rawSync.executionSymbolsPerConnection.coerceAtLeast(1),
        candleIntervals = listOf("1m"),
        rawEventTransport = RawEventTransportConfig(
            url = System.getenv("RAW_EVENT_BUS_URL") ?: DEFAULT_RAW_EVENT_BUS_URL,
            stream = System.getenv("RAW_EVENT_STREAM") ?: DEFAULT_RAW_EVENT_STREAM,
            ingestSubjectPrefix = System.getenv("RAW_EVENT_INGEST_SUBJECT_PREFIX")
                ?: DEFAULT_RAW_EVENT_INGEST_SUBJECT_PREFIX,
            persistSubjectPrefix = System.getenv("RAW_EVENT_PERSIST_SUBJECT_PREFIX")
                ?: DEFAULT_RAW_EVENT_PERSIST_SUBJECT_PREFIX,
            dlqSubject = System.getenv("RAW_EVENT_DLQ_SUBJECT") ?: DEFAULT_RAW_EVENT_DLQ_SUBJECT,
            maxAgeHours = System.getenv("RAW_EVENT_MAX_AGE_HOURS")?.toLongOrNull()
                ?: DEFAULT_RAW_EVENT_MAX_AGE_HOURS,
            fetchBatch = System.getenv("RAW_EVENT_FETCH_BATCH")?.toIntOrNull()
                ?: DEFAULT_RAW_EVENT_FETCH_BATCH,
            fetchExpiresMs = System.getenv("RAW_EVENT_FETCH_EXPIRES_MS")?.toLongOrNull()
                ?: DEFAULT_RAW_EVENT_FETCH_EXPIRES_MS,
            maxAckPending = System.getenv("RAW_EVENT_MAX_ACK_PENDING")?.toLongOrNull()
                ?: DEFAULT_RAW_EVENT_MAX_ACK_PENDING
        ),
        stateUpdater = StateUpdaterConfig(
            refreshIntervalMs = System.getenv("MARKET_DATA_STATE_REFRESH_INTERVAL_MS")?.toLongOrNull()
                ?: DEFAULT_STATE_REFRESH_INTERVAL_MS,
            refreshLookbackHours = System.getenv("MARKET_DATA_STATE_REFRESH_LOOKBACK_HOURS")?.toLongOrNull()
                ?: DEFAULT_STATE_REFRESH_LOOKBACK_HOURS
        )
    )
}

internal fun createMarketDataDataSource(config: MarketDataServiceConfig): PGSimpleDataSource {
    return PGSimpleDataSource().apply {
        serverNames = arrayOf(config.postgresHost)
        portNumbers = intArrayOf(config.postgresPort)
        databaseName = config.postgresDb
        user = config.postgresUser
        password = config.postgresPassword
    }
}

internal suspend fun waitForDataSource(
    dataSource: DataSource,
    label: String,
    maxAttempts: Int = DEFAULT_DB_WAIT_ATTEMPTS,
    delayMs: Long = DEFAULT_DB_WAIT_DELAY_MS
): Boolean {
    repeat(maxAttempts) { attemptIndex ->
        val healthy = runCatching {
            withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1").use { result ->
                            result.next()
                        }
                    }
                }
            }
        }.isSuccess
        if (healthy) {
            if (attemptIndex > 0) {
                splitLogger.info { "$label became reachable on attempt ${attemptIndex + 1}/$maxAttempts" }
            }
            return true
        }
        splitLogger.warn { "$label not ready (${attemptIndex + 1}/$maxAttempts), retrying in ${delayMs}ms" }
        delay(delayMs)
    }
    return false
}

@Serializable
internal enum class RawEventLane {
    @SerialName("live")
    LIVE,

    @SerialName("replay")
    REPLAY;

    val subjectToken: String
        get() = name.lowercase()
}

@Serializable
internal data class RawMarketDataEnvelope(
    val eventId: String,
    val exchange: String,
    val symbol: String,
    val channel: String,
    val lane: RawEventLane = RawEventLane.LIVE,
    val source: String,
    val eventTime: String,
    val publishedAt: String,
    val trades: List<TradePayload> = emptyList(),
    val candle: CandlePayload? = null,
    val orderbook: OrderbookPayload? = null,
    val assetContext: AssetContextPayload? = null
) {
    fun toMarketData(): HyperliquidMarketData {
        return when {
            trades.isNotEmpty() -> HyperliquidMarketData.Trades(
                trades = trades.map {
                    HyperliquidTrade(
                        time = Instant.parse(it.time),
                        symbol = it.symbol,
                        price = it.price,
                        size = it.size,
                        side = it.side,
                        tradeId = it.tradeId
                    )
                }
            )

            candle != null -> HyperliquidMarketData.Candle(
                HyperliquidCandle(
                    time = Instant.parse(candle.time),
                    symbol = candle.symbol,
                    interval = candle.interval,
                    open = candle.open,
                    high = candle.high,
                    low = candle.low,
                    close = candle.close,
                    volume = candle.volume,
                    numTrades = candle.numTrades
                )
            )

            orderbook != null -> HyperliquidMarketData.Orderbook(
                HyperliquidOrderbook(
                    time = Instant.parse(orderbook.time),
                    symbol = orderbook.symbol,
                    bids = orderbook.bids.map { HyperliquidOrderbookLevel(it.price, it.size) },
                    asks = orderbook.asks.map { HyperliquidOrderbookLevel(it.price, it.size) }
                )
            )

            assetContext != null -> HyperliquidMarketData.AssetContext(
                HyperliquidAssetContext(
                    time = Instant.parse(assetContext.time),
                    symbol = assetContext.symbol,
                    fundingRate = assetContext.fundingRate,
                    openInterest = assetContext.openInterest,
                    markPrice = assetContext.markPrice,
                    oraclePrice = assetContext.oraclePrice,
                    midPrice = assetContext.midPrice,
                    dayNotionalVolume = assetContext.dayNotionalVolume,
                    previousDayPrice = assetContext.previousDayPrice
                )
            )

            else -> error("Raw market data envelope $eventId carried no payload")
        }
    }

    companion object {
        fun from(
            exchangeId: String,
            source: String,
            marketData: HyperliquidMarketData,
            lane: RawEventLane = RawEventLane.LIVE,
            publishedAt: Instant = Instant.now()
        ): RawMarketDataEnvelope {
            return when (marketData) {
                is HyperliquidMarketData.Trades -> {
                    val first = marketData.trades.firstOrNull()
                        ?: error("Trade batch cannot be empty")
                    RawMarketDataEnvelope(
                        eventId = UUID.randomUUID().toString(),
                        exchange = exchangeId,
                        symbol = first.symbol,
                        channel = "trade",
                        lane = lane,
                        source = source,
                        eventTime = marketData.trades.maxOf { it.time }.toString(),
                        publishedAt = publishedAt.toString(),
                        trades = marketData.trades.map {
                            TradePayload(
                                time = it.time.toString(),
                                symbol = it.symbol,
                                price = it.price,
                                size = it.size,
                                side = it.side,
                                tradeId = it.tradeId
                            )
                        }
                    )
                }

                is HyperliquidMarketData.Candle -> RawMarketDataEnvelope(
                    eventId = UUID.randomUUID().toString(),
                    exchange = exchangeId,
                    symbol = marketData.candle.symbol,
                    channel = "candle_${marketData.candle.interval}",
                    lane = lane,
                    source = source,
                    eventTime = marketData.candle.time.toString(),
                    publishedAt = publishedAt.toString(),
                    candle = CandlePayload(
                        time = marketData.candle.time.toString(),
                        symbol = marketData.candle.symbol,
                        interval = marketData.candle.interval,
                        open = marketData.candle.open,
                        high = marketData.candle.high,
                        low = marketData.candle.low,
                        close = marketData.candle.close,
                        volume = marketData.candle.volume,
                        numTrades = marketData.candle.numTrades
                    )
                )

                is HyperliquidMarketData.Orderbook -> RawMarketDataEnvelope(
                    eventId = UUID.randomUUID().toString(),
                    exchange = exchangeId,
                    symbol = marketData.orderbook.symbol,
                    channel = "orderbook_l2",
                    lane = lane,
                    source = source,
                    eventTime = marketData.orderbook.time.toString(),
                    publishedAt = publishedAt.toString(),
                    orderbook = OrderbookPayload(
                        time = marketData.orderbook.time.toString(),
                        symbol = marketData.orderbook.symbol,
                        bids = marketData.orderbook.bids.map { OrderbookLevelPayload(it.price, it.size) },
                        asks = marketData.orderbook.asks.map { OrderbookLevelPayload(it.price, it.size) }
                    )
                )

                is HyperliquidMarketData.AssetContext -> RawMarketDataEnvelope(
                    eventId = UUID.randomUUID().toString(),
                    exchange = exchangeId,
                    symbol = marketData.assetContext.symbol,
                    channel = "asset_context",
                    lane = lane,
                    source = source,
                    eventTime = marketData.assetContext.time.toString(),
                    publishedAt = publishedAt.toString(),
                    assetContext = AssetContextPayload(
                        time = marketData.assetContext.time.toString(),
                        symbol = marketData.assetContext.symbol,
                        fundingRate = marketData.assetContext.fundingRate,
                        openInterest = marketData.assetContext.openInterest,
                        markPrice = marketData.assetContext.markPrice,
                        oraclePrice = marketData.assetContext.oraclePrice,
                        midPrice = marketData.assetContext.midPrice,
                        dayNotionalVolume = marketData.assetContext.dayNotionalVolume,
                        previousDayPrice = marketData.assetContext.previousDayPrice
                    )
                )
            }
        }
    }
}

@Serializable
internal data class TradePayload(
    val time: String,
    val symbol: String,
    val price: Double,
    val size: Double,
    val side: String,
    val tradeId: String? = null
)

@Serializable
internal data class CandlePayload(
    val time: String,
    val symbol: String,
    val interval: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val numTrades: Int
)

@Serializable
internal data class OrderbookPayload(
    val time: String,
    val symbol: String,
    val bids: List<OrderbookLevelPayload>,
    val asks: List<OrderbookLevelPayload>
)

@Serializable
internal data class OrderbookLevelPayload(
    val price: Double,
    val size: Double
)

@Serializable
internal data class AssetContextPayload(
    val time: String,
    val symbol: String,
    val fundingRate: Double,
    val openInterest: Double,
    val markPrice: Double? = null,
    val oraclePrice: Double? = null,
    val midPrice: Double? = null,
    val dayNotionalVolume: Double? = null,
    val previousDayPrice: Double? = null
)

internal interface RawMarketDataTransport : AutoCloseable {
    suspend fun publish(subject: String, envelope: RawMarketDataEnvelope)
    suspend fun consume(
        subjectFilter: String,
        durableName: String,
        handler: suspend (RawMarketDataEnvelope) -> Unit
    )
}

internal class NatsJetStreamRawMarketDataTransport(
    private val config: RawEventTransportConfig,
    connectionName: String
) : RawMarketDataTransport {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection: Connection = Nats.connect(
        Options.builder()
            .server(config.url)
            .connectionName(connectionName)
            .build()
    )
    private val jetStreamManagement: JetStreamManagement = connection.jetStreamManagement()
    private val jetStream: JetStream = connection.jetStream()

    init {
        ensureStream()
    }

    override suspend fun publish(subject: String, envelope: RawMarketDataEnvelope) {
        withContext(Dispatchers.IO) {
            jetStream.publish(subject, json.encodeToString(RawMarketDataEnvelope.serializer(), envelope).toByteArray())
        }
    }

    override suspend fun consume(
        subjectFilter: String,
        durableName: String,
        handler: suspend (RawMarketDataEnvelope) -> Unit
    ) {
        val consumerConfiguration = ConsumerConfiguration.builder()
            .durable(durableName)
            .deliverPolicy(DeliverPolicy.All)
            .ackPolicy(AckPolicy.Explicit)
            .filterSubject(subjectFilter)
            .ackWait(Duration.ofMinutes(2))
            .maxAckPending(config.maxAckPending)
            .build()
        runCatching {
            jetStreamManagement.addOrUpdateConsumer(config.stream, consumerConfiguration)
        }.onFailure { ex ->
            logger.warn(ex) { "Failed to add or update consumer $durableName for $subjectFilter" }
        }

        val subscription = jetStream.subscribe(
            subjectFilter,
            PullSubscribeOptions.builder()
                .stream(config.stream)
                .durable(durableName)
                .configuration(consumerConfiguration)
                .build()
        )
        try {
            while (currentCoroutineContext().isActive) {
                val messages = withContext(Dispatchers.IO) {
                    subscription.fetch(config.fetchBatch, Duration.ofMillis(config.fetchExpiresMs))
                }
                for (message in messages) {
                    currentCoroutineContext().ensureActive()
                    if (message.data == null || message.data.isEmpty()) {
                        continue
                    }
                    val payload = json.decodeFromString(
                        RawMarketDataEnvelope.serializer(),
                        message.data.decodeToString()
                    )
                    try {
                        handler(payload)
                        withContext(Dispatchers.IO) {
                            message.ack()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ex: Exception) {
                        logger.error(ex) {
                            "Failed to process raw market data event ${payload.eventId}; acknowledging to avoid replay storms"
                        }
                        withContext(Dispatchers.IO) {
                            message.ack()
                        }
                    }
                }
            }
        } finally {
            runCatching { subscription.unsubscribe() }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    private fun ensureStream() {
        val streamConfiguration = StreamConfiguration.builder()
            .name(config.stream)
            .subjects(
                listOf(
                    "${config.ingestSubjectPrefix}.>",
                    "${config.persistSubjectPrefix}.>",
                    config.dlqSubject
                )
            )
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .discardPolicy(DiscardPolicy.Old)
            .maxAge(Duration.ofHours(config.maxAgeHours))
            .build()
        runCatching {
            jetStreamManagement.addStream(streamConfiguration)
        }.recoverCatching {
            jetStreamManagement.updateStream(streamConfiguration)
        }.getOrElse { ex ->
            error("Failed to provision JetStream stream ${config.stream}: ${ex.message}")
        }
    }
}

internal data class HyperliquidSourcePlan(
    val family: String,
    val shardIndex: Int,
    val shardCount: Int,
    val symbols: List<String>,
    val subscribeToTrades: Boolean,
    val subscribeToCandles: Boolean,
    val subscribeToOrderbook: Boolean,
    val subscribeToAssetCtx: Boolean
) {
    val label: String = "$family shard=${shardIndex + 1}/$shardCount"
}

internal fun buildHyperliquidSourcePlans(
    symbols: List<String>,
    splitCandlesFromExecution: Boolean,
    symbolsPerConnection: Int,
    candleSymbolsPerConnection: Int,
    executionSymbolsPerConnection: Int,
    enableOrderbook: Boolean
): List<HyperliquidSourcePlan> {
    val normalized = symbols.distinct()
    if (normalized.isEmpty()) return emptyList()
    if (!splitCandlesFromExecution) {
        val combinedShards = normalized.chunked(symbolsPerConnection.coerceAtLeast(1))
        return combinedShards.mapIndexed { shardIndex, shardSymbols ->
            HyperliquidSourcePlan(
                family = "combined",
                shardIndex = shardIndex,
                shardCount = combinedShards.size,
                symbols = shardSymbols,
                subscribeToTrades = true,
                subscribeToCandles = true,
                subscribeToOrderbook = enableOrderbook,
                subscribeToAssetCtx = true
            )
        }
    }

    val candleShards = normalized.chunked(candleSymbolsPerConnection.coerceAtLeast(1))
    val executionShards = normalized.chunked(executionSymbolsPerConnection.coerceAtLeast(1))
    return buildList {
        addAll(
            candleShards.mapIndexed { shardIndex, shardSymbols ->
                HyperliquidSourcePlan(
                    family = "candle",
                    shardIndex = shardIndex,
                    shardCount = candleShards.size,
                    symbols = shardSymbols,
                    subscribeToTrades = false,
                    subscribeToCandles = true,
                    subscribeToOrderbook = false,
                    subscribeToAssetCtx = false
                )
            }
        )
        addAll(
            executionShards.mapIndexed { shardIndex, shardSymbols ->
                HyperliquidSourcePlan(
                    family = "execution",
                    shardIndex = shardIndex,
                    shardCount = executionShards.size,
                    symbols = shardSymbols,
                    subscribeToTrades = true,
                    subscribeToCandles = false,
                    subscribeToOrderbook = enableOrderbook,
                    subscribeToAssetCtx = true
                )
            }
        )
    }
}

private fun createSource(plan: HyperliquidSourcePlan, config: MarketDataServiceConfig): HyperliquidSource {
    return HyperliquidSource(
        symbols = plan.symbols,
        subscribeToTrades = plan.subscribeToTrades,
        subscribeToCandles = plan.subscribeToCandles,
        candleIntervals = config.candleIntervals,
        subscribeToOrderbook = plan.subscribeToOrderbook,
        subscribeToAssetCtx = plan.subscribeToAssetCtx,
        url = config.wsUrl,
        receiveIdleTimeoutMs = config.idleTimeoutMs
    )
}

private data class SyncRunnerDependencies(
    val config: MarketDataServiceConfig,
    val transport: RawMarketDataTransport,
    val universeResolver: HyperliquidUniverseResolver
)

private fun buildSyncRunnerDependencies(): SyncRunnerDependencies {
    val config = loadMarketDataServiceConfig()
    return SyncRunnerDependencies(
        config = config,
        transport = NatsJetStreamRawMarketDataTransport(
            config = config.rawEventTransport,
            connectionName = "market-data-sync"
        ),
        universeResolver = HyperliquidUniverseResolver(config.infoUrl)
    )
}

private data class PersistRunnerDependencies(
    val config: MarketDataServiceConfig,
    val dataSource: PGSimpleDataSource,
    val transport: RawMarketDataTransport,
    val sink: MarketDataSink
)

private fun buildPersistRunnerDependencies(): PersistRunnerDependencies {
    val config = loadMarketDataServiceConfig()
    val dataSource = createMarketDataDataSource(config)
    return PersistRunnerDependencies(
        config = config,
        dataSource = dataSource,
        transport = NatsJetStreamRawMarketDataTransport(
            config = config.rawEventTransport,
            connectionName = "market-data-persist"
        ),
        sink = MarketDataSink(dataSource, config.batchSize, config.exchangeId)
    )
}

private data class FeatureMaterializerDependencies(
    val config: MarketDataServiceConfig,
    val dataSource: PGSimpleDataSource,
    val featureStateStore: FeatureStateStore,
    val aggregator: ResearchFeatureAggregator
)

private fun buildFeatureMaterializerDependencies(): FeatureMaterializerDependencies {
    val config = loadMarketDataServiceConfig()
    val dataSource = createMarketDataDataSource(config)
    val featureStateStore = FeatureStateStore(dataSource, config.exchangeId, 1)
    return FeatureMaterializerDependencies(
        config = config,
        dataSource = dataSource,
        featureStateStore = featureStateStore,
        aggregator = ResearchFeatureAggregator(
            dataSource = dataSource,
            exchangeId = config.exchangeId,
            enabled = config.researchFeaturesEnabled,
            bootstrapHours = config.researchFeaturesBootstrapHours,
            refreshIntervalMs = config.researchFeaturesRefreshIntervalMs,
            refreshOverlapMinutes = config.researchFeaturesRefreshOverlapMinutes,
            backfillChunkHours = config.researchFeaturesBackfillChunkHours,
            finalizationLagMinutes = config.researchFeaturesFinalizationLagMinutes,
            featureStateStore = featureStateStore
        )
    )
}

private data class StateUpdaterDependencies(
    val config: MarketDataServiceConfig,
    val dataSource: PGSimpleDataSource,
    val rawSyncStateStore: RawSyncStateStore,
    val featureStateStore: FeatureStateStore
)

private fun buildStateUpdaterDependencies(): StateUpdaterDependencies {
    val config = loadMarketDataServiceConfig()
    val dataSource = createMarketDataDataSource(config)
    return StateUpdaterDependencies(
        config = config,
        dataSource = dataSource,
        rawSyncStateStore = RawSyncStateStore(dataSource, config.exchangeId),
        featureStateStore = FeatureStateStore(dataSource, config.exchangeId, 1)
    )
}

private data class RepairRunnerDependencies(
    val config: MarketDataServiceConfig,
    val dataSource: PGSimpleDataSource,
    val transport: RawMarketDataTransport,
    val candleBackfillClient: HyperliquidCandleBackfillClient,
    val universeResolver: HyperliquidUniverseResolver
)

private fun buildRepairRunnerDependencies(): RepairRunnerDependencies {
    val config = loadMarketDataServiceConfig()
    return RepairRunnerDependencies(
        config = config,
        dataSource = createMarketDataDataSource(config),
        transport = NatsJetStreamRawMarketDataTransport(
            config = config.rawEventTransport,
            connectionName = "market-data-repair"
        ),
        candleBackfillClient = HyperliquidCandleBackfillClient(
            infoUrl = config.infoUrl,
            lookbackHours = config.backfillLookbackHours,
            maxBarsPerRequest = config.backfillMaxBars,
            overlapBars = config.backfillOverlapBars
        ),
        universeResolver = HyperliquidUniverseResolver(config.infoUrl)
    )
}

class MarketDataSyncRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val transport: RawMarketDataTransport,
    private val universeResolver: HyperliquidUniverseResolver
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeSourcesLock = Any()
    @Volatile
    private var activeSources: List<HyperliquidSource> = emptyList()
    @Volatile
    private var activeUniverse: HyperliquidUniverseSnapshot? = null
    private var ingestionJob: Job? = null
    private var statsJob: Job? = null
    private val publishedMessages = AtomicInteger(0)

    constructor() : this(buildSyncRunnerDependencies())

    private constructor(deps: SyncRunnerDependencies) : this(
        config = deps.config,
        transport = deps.transport,
        universeResolver = deps.universeResolver
    )

    fun start() {
        logger.info {
            "Starting market-data-sync exchange=${config.exchangeId} ws=${config.wsUrl} stream=${config.rawEventTransport.stream}"
        }
        ingestionJob = scope.launch { runIngestionLoop() }
        statsJob = scope.launch {
            while (isActive) {
                delay(DEFAULT_SYNC_STATS_INTERVAL_MS)
                logger.info { "market-data-sync published=${publishedMessages.get()} universe=${activeUniverse?.symbols?.size ?: 0}" }
            }
        }
    }

    fun stop() {
        runBlocking {
            statsJob?.cancelAndJoin()
            ingestionJob?.cancelAndJoin()
            closeActiveSources()
            runCatching { universeResolver.close() }
            runCatching { transport.close() }
            scope.cancel()
        }
    }

    private suspend fun runIngestionLoop() {
        var reconnectAttempt = 0
        while (scope.isActive) {
            val messagesInSession = AtomicInteger(0)
            val sessionProducedData = AtomicBoolean(false)
            var sessionRestartReason: String? = null
            try {
                val universe = resolveUniverseSnapshot(activeUniverse?.symbols.orEmpty())
                activeUniverse = universe
                val sourcePlans = buildHyperliquidSourcePlans(
                    symbols = universe.symbols,
                    splitCandlesFromExecution = config.splitCandlesFromExecution,
                    symbolsPerConnection = config.symbolsPerConnection,
                    candleSymbolsPerConnection = config.candleSymbolsPerConnection,
                    executionSymbolsPerConnection = config.executionSymbolsPerConnection,
                    enableOrderbook = config.enableOrderbook
                )
                val continuityWatchdog = HyperliquidContinuityWatchdog(
                    symbols = universe.symbols,
                    candleIntervals = config.candleIntervals,
                    activityTimeoutMs = config.channelActivityTimeoutMs,
                    candleStaleMultiplier = config.candleStaleMultiplier
                )
                logger.info {
                    "market-data-sync connecting symbols=${universe.symbols.size} shards=${sourcePlans.size} source=${universe.source}"
                }
                supervisorScope {
                    val sessionRestart = CompletableDeferred<String>()
                    val refreshJob = launch {
                        refreshUniverseDuringSession(universe) { refreshed ->
                            activeUniverse = refreshed
                            completeSessionRestart(sessionRestart, "universe_refresh")
                        }
                    }
                    val shardJobs = sourcePlans.map { sourcePlan ->
                        launch {
                            runShardLoop(sourcePlan, sessionRestart) { data ->
                                messagesInSession.incrementAndGet()
                                publishedMessages.incrementAndGet()
                                sessionProducedData.set(true)
                                continuityWatchdog.record(data)
                                publishMarketData(sourcePlan.label, data)
                            }
                        }
                    }
                    val completionWatcher = launch {
                        shardJobs.joinAll()
                        completeSessionRestart(sessionRestart, "all_shards_stopped")
                    }
                    val continuityMonitor = launch {
                        while (isActive && !sessionRestart.isCompleted) {
                            delay(config.freshnessCheckIntervalMs)
                            val issues = continuityWatchdog.staleCandleStreams()
                            if (issues.isEmpty()) continue
                            val shouldRestart = shouldRestartForContinuityIssues(
                                issueCount = issues.size,
                                trackedSymbols = continuityWatchdog.trackedSymbolCount
                            )
                            if (!shouldRestart) continue
                            val sample = issues.take(5).joinToString("; ") { it.reason }
                            logger.error {
                                "market-data-sync continuity restart issues=${issues.size} threshold=${restartContinuityIssueThreshold(continuityWatchdog.trackedSymbolCount)} sample=$sample"
                            }
                            completeSessionRestart(sessionRestart, "continuity_failure")
                            return@launch
                        }
                    }
                    try {
                        sessionRestartReason = sessionRestart.await()
                    } finally {
                        shardJobs.forEach { it.cancelAndJoin() }
                        refreshJob.cancelAndJoin()
                        completionWatcher.cancelAndJoin()
                        continuityMonitor.cancelAndJoin()
                    }
                }
                if (sessionProducedData.get() && reconnectAttempt > 0) {
                    reconnectAttempt = 0
                }
                reconnectAttempt += if (sessionRestartReason == "universe_refresh") 0 else 1
                val reconnectDelayMs = if (sessionRestartReason == "universe_refresh") {
                    0L
                } else {
                    reconnectBackoffDelayMs(reconnectAttempt, 60_000L)
                }
                logger.warn {
                    "market-data-sync restarting reason=${sessionRestartReason ?: "unknown"} messages=${messagesInSession.get()} delayMs=$reconnectDelayMs"
                }
                if (reconnectDelayMs > 0) {
                    delay(reconnectDelayMs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                logger.error(ex) { "market-data-sync session failed: ${ex.message}" }
                reconnectAttempt += 1
                delay(reconnectBackoffDelayMs(reconnectAttempt, 60_000L))
            } finally {
                closeActiveSources()
            }
        }
    }

    private suspend fun publishMarketData(sourceLabel: String, data: HyperliquidMarketData) {
        val envelope = RawMarketDataEnvelope.from(
            exchangeId = config.exchangeId,
            source = sourceLabel,
            marketData = data
        )
        transport.publish(
            subject = config.rawEventTransport.ingestSubject(
                exchangeId = config.exchangeId,
                channel = envelope.channel,
                lane = envelope.lane
            ),
            envelope = envelope
        )
    }

    private suspend fun resolveUniverseSnapshot(previous: List<String>): HyperliquidUniverseSnapshot {
        return runCatching { universeResolver.resolve(config.universeSettings) }
            .recoverCatching { ex ->
                if (previous.isEmpty()) throw ex
                logger.warn(ex) { "Universe refresh failed; keeping previous subscription set of ${previous.size} symbols" }
                HyperliquidUniverseSnapshot(symbols = previous, source = "previous")
            }
            .getOrThrow()
    }

    private suspend fun refreshUniverseDuringSession(
        currentUniverse: HyperliquidUniverseSnapshot,
        onUniverseChanged: (HyperliquidUniverseSnapshot) -> Unit
    ) {
        if (config.universeSettings.mode != HyperliquidUniverseMode.CATALOG) return
        while (currentCoroutineContext().isActive) {
            delay(config.universeSettings.refreshIntervalMs)
            val refreshedResult = runCatching { universeResolver.resolve(config.universeSettings) }
            if (refreshedResult.isFailure) {
                val failure = refreshedResult.exceptionOrNull()
                logger.warn(failure) { "Universe refresh during active session failed: ${failure?.message}" }
                continue
            }
            val refreshed = refreshedResult.getOrThrow()
            if (refreshed.symbols != currentUniverse.symbols) {
                onUniverseChanged(refreshed)
                return
            }
        }
    }

    private suspend fun runShardLoop(
        sourcePlan: HyperliquidSourcePlan,
        sessionRestart: CompletableDeferred<String>,
        onData: suspend (HyperliquidMarketData) -> Unit
    ) {
        var reconnectAttempt = 0
        while (currentCoroutineContext().isActive && !sessionRestart.isCompleted) {
            if (reconnectAttempt == 0 && sourcePlan.shardIndex > 0) {
                delay((sourcePlan.shardIndex * 250L).coerceAtMost(5_000L))
            }
            val source = createSource(sourcePlan, config)
            registerActiveSource(source)
            try {
                source.fetch()
                    .onEach {
                        if (reconnectAttempt > 0) {
                            reconnectAttempt = 0
                            logger.info { "${sourcePlan.label} recovered" }
                        }
                        onData(it)
                    }
                    .catch { ex ->
                        if (sessionRestart.isCompleted || !currentCoroutineContext().isActive) throw ex
                        logger.error(ex) { "${sourcePlan.label} failed: ${ex.message}" }
                        throw ex
                    }
                    .collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (sessionRestart.isCompleted || !currentCoroutineContext().isActive) return
            } finally {
                unregisterActiveSource(source)
                runCatching { source.close() }
            }
            if (!currentCoroutineContext().isActive || sessionRestart.isCompleted) return
            reconnectAttempt += 1
            delay(reconnectBackoffDelayMs(reconnectAttempt, 30_000L))
        }
    }

    private fun registerActiveSource(source: HyperliquidSource) {
        synchronized(activeSourcesLock) {
            activeSources = activeSources + source
        }
    }

    private fun unregisterActiveSource(source: HyperliquidSource) {
        synchronized(activeSourcesLock) {
            activeSources = activeSources.filterNot { it === source }
        }
    }

    private fun closeActiveSources() {
        val sources = synchronized(activeSourcesLock) {
            activeSources.also { activeSources = emptyList() }
        }
        sources.forEach { source ->
            runCatching { source.close() }
        }
    }

    private fun completeSessionRestart(sessionRestart: CompletableDeferred<String>, reason: String) {
        if (!sessionRestart.isCompleted) {
            sessionRestart.complete(reason)
        }
    }
}

class MarketDataPersistRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val dataSource: PGSimpleDataSource,
    private val transport: RawMarketDataTransport,
    private val sink: MarketDataSink
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var liveConsumeJob: Job? = null
    private var replayConsumeJob: Job? = null
    private var flushJob: Job? = null
    private var statsJob: Job? = null

    constructor() : this(buildPersistRunnerDependencies())

    private constructor(deps: PersistRunnerDependencies) : this(
        config = deps.config,
        dataSource = deps.dataSource,
        transport = deps.transport,
        sink = deps.sink
    )

    fun start() {
        runBlocking {
            if (!waitForDataSource(dataSource, "TimescaleDB")) {
                error("TimescaleDB did not become ready for market-data-persist")
            }
        }
        liveConsumeJob = scope.launch {
            transport.consume(
                subjectFilter = config.rawEventTransport.persistWildcard(RawEventLane.LIVE),
                durableName = "market-data-persist-live-${sanitizeSubjectToken(config.exchangeId)}"
            ) { envelope ->
                sink.write(envelope.toMarketData())
            }
        }
        replayConsumeJob = scope.launch {
            transport.consume(
                subjectFilter = config.rawEventTransport.persistWildcard(RawEventLane.REPLAY),
                durableName = "market-data-persist-replay-${sanitizeSubjectToken(config.exchangeId)}"
            ) { envelope ->
                sink.write(envelope.toMarketData())
            }
        }
        flushJob = scope.launch {
            while (isActive) {
                delay(config.flushIntervalSeconds * 1_000L)
                sink.flush()
            }
        }
        statsJob = scope.launch {
            while (isActive) {
                delay(DEFAULT_PERSIST_STATS_INTERVAL_MS)
                logStats(sink.getStats())
            }
        }
    }

    fun stop() {
        runBlocking {
            statsJob?.cancelAndJoin()
            flushJob?.cancelAndJoin()
            replayConsumeJob?.cancelAndJoin()
            liveConsumeJob?.cancelAndJoin()
            runCatching { sink.flush() }
            runCatching { transport.close() }
            scope.cancel()
        }
    }

    private fun logStats(stats: IngestionStats) {
        logger.info {
            "market-data-persist trades=${stats.tradesIngested} candles=${stats.candlesIngested} orderbooks=${stats.orderbooksIngested} funding=${stats.fundingRowsIngested} openInterest=${stats.openInterestRowsIngested} pending=${stats.totalPending}"
        }
    }
}

class FeatureMaterializerRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val dataSource: PGSimpleDataSource,
    private val featureStateStore: FeatureStateStore,
    private val aggregator: ResearchFeatureAggregator
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null

    constructor() : this(buildFeatureMaterializerDependencies())

    private constructor(deps: FeatureMaterializerDependencies) : this(
        config = deps.config,
        dataSource = deps.dataSource,
        featureStateStore = deps.featureStateStore,
        aggregator = deps.aggregator
    )

    fun start() {
        runBlocking {
            if (!waitForDataSource(dataSource, "TimescaleDB")) {
                error("TimescaleDB did not become ready for feature-materializer")
            }
        }
        logger.info { "Starting feature-materializer exchange=${config.exchangeId}" }
        runJob = scope.launch {
            aggregator.runLoop()
        }
    }

    fun stop() {
        runBlocking {
            runJob?.cancelAndJoin()
            scope.cancel()
        }
    }
}

class MarketDataStateUpdaterRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val dataSource: PGSimpleDataSource,
    private val rawSyncStateStore: RawSyncStateStore,
    private val featureStateStore: FeatureStateStore
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null

    constructor() : this(buildStateUpdaterDependencies())

    private constructor(deps: StateUpdaterDependencies) : this(
        config = deps.config,
        dataSource = deps.dataSource,
        rawSyncStateStore = deps.rawSyncStateStore,
        featureStateStore = deps.featureStateStore
    )

    fun start() {
        runBlocking {
            if (!waitForDataSource(dataSource, "TimescaleDB")) {
                error("TimescaleDB did not become ready for market-data-state-updater")
            }
        }
        runJob = scope.launch {
            reconcileStateOnce(fullFeatureBackfill = true)
            while (isActive) {
                delay(config.stateUpdater.refreshIntervalMs)
                reconcileStateOnce(fullFeatureBackfill = false)
            }
        }
    }

    fun stop() {
        runBlocking {
            runJob?.cancelAndJoin()
            scope.cancel()
        }
    }

    private suspend fun reconcileStateOnce(fullFeatureBackfill: Boolean) {
        if (!rawSyncStateStore.hasPersistedState()) {
            logger.info { "Hydrating raw_sync_state from canonical raw tables" }
            rawSyncStateStore.backfillAll()
        }
        if (fullFeatureBackfill) {
            logger.info { "Hydrating feature state tables from research_features_1m" }
            featureStateStore.backfillAll()
            return
        }
        val endExclusive = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val startInclusive = endExclusive.minus(config.stateUpdater.refreshLookbackHours, ChronoUnit.HOURS)
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    featureStateStore.refresh(connection, startInclusive, endExclusive)
                    connection.commit()
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                }
            }
        }
    }
}

class MarketDataRepairRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val dataSource: PGSimpleDataSource,
    private val transport: RawMarketDataTransport,
    private val candleBackfillClient: HyperliquidCandleBackfillClient,
    private val universeResolver: HyperliquidUniverseResolver
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null
    private var plannerState = RawCandleRecoveryPlannerState(initialRecentRepairPending = true)
    private var currentUniverseSymbols: List<String> = emptyList()
    private var initialRepairSymbols: List<String> = emptyList()

    constructor() : this(buildRepairRunnerDependencies())

    private constructor(deps: RepairRunnerDependencies) : this(
        config = deps.config,
        dataSource = deps.dataSource,
        transport = deps.transport,
        candleBackfillClient = deps.candleBackfillClient,
        universeResolver = deps.universeResolver
    )

    fun start() {
        runBlocking {
            if (!waitForDataSource(dataSource, "TimescaleDB")) {
                error("TimescaleDB did not become ready for market-data-repair")
            }
        }
        runJob = scope.launch { runRepairLoop() }
    }

    fun stop() {
        runBlocking {
            runJob?.cancelAndJoin()
            runCatching { candleBackfillClient.close() }
            runCatching { universeResolver.close() }
            runCatching { transport.close() }
            scope.cancel()
        }
    }

    private suspend fun runRepairLoop() {
        val repairIntervalMs = config.freshnessCheckIntervalMs.coerceAtLeast(30_000L)
        while (currentCoroutineContext().isActive) {
            try {
                refreshUniverseSymbols()
                if (currentUniverseSymbols.isEmpty()) {
                    delay(repairIntervalMs)
                    continue
                }
                val initialStreams = if (plannerState.initialRecentRepairPending) {
                    initialRepairSymbols
                        .drop(plannerState.initialRecentRepairCursor)
                        .take(DEFAULT_HYPERLIQUID_INITIAL_RECENT_REPAIR_BATCH_STREAMS)
                        .flatMap { symbol -> config.candleIntervals.map { interval -> symbol to interval } }
                } else {
                    emptyList()
                }
                val targetedStreams = loadPersistedCandleRepairStreams(currentUniverseSymbols)
                val historicalCandidates = if (
                    plannerState.initialRecentRepairPending || targetedStreams.isNotEmpty()
                ) {
                    emptyList()
                } else {
                    loadHistoricalCandleBackfillCandidates(currentUniverseSymbols)
                }
                when (
                    val action = planRawCandleRecoveryAction(
                        now = Instant.now(),
                        state = plannerState,
                        initialStreams = initialStreams,
                        targetedStreams = targetedStreams,
                        historicalCandidates = historicalCandidates
                    )
                ) {
                    is RawCandleRecoveryAction.InitialRecentRepair -> {
                        val nextCursor = plannerState.initialRecentRepairCursor + action.streams.size
                        val initialRepairComplete = nextCursor >= initialRepairSymbols.size
                        repairCandleStreams(
                            streams = action.streams,
                            label = "initial_recent_candle_repair",
                            lookbackHours = 2L.coerceAtMost(config.backfillLookbackHours)
                        )
                        plannerState = plannerState.copy(
                            initialRecentRepairPending = !initialRepairComplete,
                            initialRecentRepairCursor = nextCursor,
                            initialRecentRepairCompletedAt = if (initialRepairComplete) Instant.now() else null
                        )
                    }
                    is RawCandleRecoveryAction.TargetedRecentRepair -> {
                        repairCandleStreams(
                            streams = action.streams,
                            label = "targeted_recent_candle_repair",
                            lookbackHours = 1L.coerceAtMost(config.backfillLookbackHours)
                        )
                    }
                    is RawCandleRecoveryAction.HistoricalBackfill -> {
                        runHistoricalCandleBackfill(action.candidate)
                    }
                    is RawCandleRecoveryAction.Idle -> delay(repairIntervalMs)
                }
            } catch (deferred: HyperliquidBackfillDeferredException) {
                logger.warn {
                    "market-data-repair deferred ${deferred.symbol}/${deferred.interval} " +
                        "retryAfterMs=${deferred.retryAfterMs} reason=${deferred.message}"
                }
                delay(repairIntervalMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                logger.error(ex) { "market-data-repair cycle failed: ${ex.message}" }
                delay(repairIntervalMs)
            }
        }
    }

    private suspend fun refreshUniverseSymbols() {
        val snapshot = runCatching { universeResolver.resolve(config.universeSettings) }
            .recoverCatching { ex ->
                if (currentUniverseSymbols.isEmpty()) throw ex
                logger.warn(ex) { "Universe refresh failed; keeping ${currentUniverseSymbols.size} repair symbols" }
                HyperliquidUniverseSnapshot(currentUniverseSymbols, "previous")
            }
            .getOrThrow()
        if (snapshot.symbols != currentUniverseSymbols) {
            currentUniverseSymbols = snapshot.symbols
            initialRepairSymbols = prioritizeRecentRepairSymbols(currentUniverseSymbols)
            plannerState = if (initialRepairSymbols.isEmpty()) {
                RawCandleRecoveryPlannerState(
                    initialRecentRepairPending = false,
                    initialRecentRepairCompletedAt = Instant.now()
                )
            } else {
                RawCandleRecoveryPlannerState(initialRecentRepairPending = true)
            }
            logger.info { "market-data-repair loaded ${currentUniverseSymbols.size} symbols source=${snapshot.source}" }
        }
    }

    private suspend fun prioritizeRecentRepairSymbols(sessionSymbols: List<String>): List<String> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return@withContext emptyList()
        val sql = """
            WITH raw_ranked AS (
                SELECT
                    symbol,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'candle_1m') AS candle_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'trade') AS trade_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'orderbook_l2') AS orderbook_latest_raw_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'funding') AS funding_latest_raw_time
                FROM raw_sync_state
                WHERE exchange = ?
                  AND symbol = ANY (?)
                GROUP BY symbol
            )
            SELECT symbol
            FROM raw_ranked
            ORDER BY
                candle_latest_raw_time ASC NULLS FIRST,
                GREATEST(
                    COALESCE(trade_latest_raw_time, '-infinity'::timestamptz),
                    COALESCE(orderbook_latest_raw_time, '-infinity'::timestamptz),
                    COALESCE(funding_latest_raw_time, '-infinity'::timestamptz)
                ) DESC NULLS LAST,
                symbol ASC
        """.trimIndent()
        val ordered = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, config.exchangeId)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(2, sqlArray)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("symbol"))
                    }
                }
            }
        }
        val prioritized = linkedSetOf<String>()
        ordered.forEach(prioritized::add)
        normalized.forEach(prioritized::add)
        prioritized.toList()
    }

    private suspend fun loadPersistedCandleRepairStreams(
        sessionSymbols: List<String>,
        limit: Int = 48
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return@withContext emptyList()
        val now = Instant.now()
        val candleIntervalMs = candleIntervalToMillis("1m")
        val recentActivityCutoff = now.minusMillis(
            candleTradeRelevanceWindowMs(
                activityTimeoutMs = config.channelActivityTimeoutMs,
                intervalMs = candleIntervalMs,
                candleStaleMultiplier = config.candleStaleMultiplier
            )
        )
        val staleCandleCutoff = now.minusMillis(
            candleAllowedLagMs(
                activityTimeoutMs = config.channelActivityTimeoutMs,
                intervalMs = candleIntervalMs,
                candleStaleMultiplier = config.candleStaleMultiplier
            )
        )
        val sql = """
            WITH channel_state AS (
                SELECT
                    symbol,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'trade') AS latest_trade_time,
                    MAX(latest_raw_time) FILTER (WHERE channel = 'candle_1m') AS latest_candle_time
                FROM raw_sync_state
                WHERE exchange = ?
                  AND symbol = ANY (?)
                GROUP BY symbol
            )
            SELECT symbol
            FROM channel_state
            WHERE latest_trade_time IS NOT NULL
              AND latest_trade_time >= ?
              AND (
                    latest_candle_time IS NULL OR
                    latest_candle_time < date_trunc('minute', latest_trade_time) OR
                    latest_candle_time < ?
                )
            ORDER BY
                latest_candle_time ASC NULLS FIRST,
                latest_trade_time DESC,
                symbol ASC
            LIMIT ?
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, config.exchangeId)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(2, sqlArray)
                statement.setTimestamp(3, Timestamp.from(recentActivityCutoff))
                statement.setTimestamp(4, Timestamp.from(staleCandleCutoff))
                statement.setInt(5, limit.coerceAtLeast(1))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString("symbol") to "1m")
                    }
                }
            }
        }
    }

    private suspend fun loadHistoricalCandleBackfillCandidates(
        sessionSymbols: List<String>,
        limit: Int = 1,
        now: Instant = Instant.now()
    ): List<CandleHistoricalBackfillCandidate> = withContext(Dispatchers.IO) {
        val normalized = sessionSymbols.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return@withContext emptyList()
        val sql = """
            SELECT symbol, earliest_raw_time, latest_raw_time
            FROM raw_sync_state
            WHERE exchange = ?
              AND channel = 'candle_1m'
              AND symbol = ANY (?)
        """.trimIndent()
        val persistedStates = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, config.exchangeId)
                val sqlArray: SqlArray = connection.createArrayOf("text", normalized.toTypedArray())
                statement.setArray(2, sqlArray)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RawCandleCoverageState(
                                    symbol = rs.getString("symbol"),
                                    earliestRawTime = rs.getTimestamp("earliest_raw_time")?.toInstant(),
                                    latestRawTime = rs.getTimestamp("latest_raw_time")?.toInstant()
                                )
                            )
                        }
                    }
                }
            }
        }
        val stateBySymbol = persistedStates.associateBy { it.symbol }
        prioritizeHistoricalBackfillCandidates(
            interval = "1m",
            now = now,
            lookbackHours = config.backfillLookbackHours,
            coverageStates = normalized.map { symbol ->
                stateBySymbol[symbol] ?: RawCandleCoverageState(symbol, null, null)
            },
            maxCandidates = limit.coerceAtLeast(1)
        )
    }

    private suspend fun repairCandleStreams(
        streams: List<Pair<String, String>>,
        label: String,
        lookbackHours: Long
    ) {
        val distinctStreams = streams.distinct()
        val permits = determineCandleRepairPermits(
            streamCount = distinctStreams.size,
            markInitialRepairComplete = plannerState.initialRecentRepairPending
        )
        val semaphore = Semaphore(permits)
        logger.info {
            "$label streams=${distinctStreams.size} lookbackHours=$lookbackHours permits=$permits"
        }
        val results = supervisorScope {
            distinctStreams.map { (symbol, interval) ->
                async {
                    semaphore.withPermit {
                        try {
                            val candles = candleBackfillClient.fetchRecentCandles(
                                symbol = symbol,
                                interval = interval,
                                now = Instant.now(),
                                lookbackHoursOverride = lookbackHours
                            )
                            candles.forEach { candle ->
                                publishBackfilledCandle(label, candle)
                            }
                            RepairStreamResult(publishedCandles = candles.size, deferred = false)
                        } catch (deferred: HyperliquidBackfillDeferredException) {
                            logger.warn {
                                "$label deferred stream $symbol/$interval retryAfterMs=${deferred.retryAfterMs} " +
                                    "reason=${deferred.message}"
                            }
                            RepairStreamResult(publishedCandles = 0, deferred = true)
                        }
                    }
                }
            }.awaitAll()
        }
        logger.info {
            "$label complete streams=${distinctStreams.size} lookbackHours=$lookbackHours " +
                "publishedCandles=${results.sumOf { it.publishedCandles }} deferredStreams=${results.count { it.deferred }}"
        }
    }

    private suspend fun runHistoricalCandleBackfill(candidate: CandleHistoricalBackfillCandidate) {
        val windows = planCandleBackfillWindowsForRange(
            symbol = candidate.symbol,
            interval = candidate.interval,
            startTime = candidate.range.startTime,
            endTime = candidate.range.endTime,
            maxBars = config.backfillMaxBars,
            overlapBars = config.backfillOverlapBars
        )
        for (window in windows) {
            currentCoroutineContext().ensureActive()
            val candles = try {
                candleBackfillClient.fetchWindowCandles(window)
            } catch (deferred: HyperliquidBackfillDeferredException) {
                logger.warn {
                    "historical_candle_backfill deferred ${candidate.symbol}/${candidate.interval} " +
                        "window=${window.startTime}..${window.endTime} retryAfterMs=${deferred.retryAfterMs} " +
                        "reason=${deferred.message}"
                }
                return
            }
            candles.forEach { candle ->
                publishBackfilledCandle("historical_candle_backfill", candle)
            }
        }
    }

    private suspend fun publishBackfilledCandle(sourceLabel: String, candle: HyperliquidCandle) {
        val envelope = RawMarketDataEnvelope.from(
            exchangeId = config.exchangeId,
            source = sourceLabel,
            marketData = HyperliquidMarketData.Candle(candle),
            lane = RawEventLane.REPLAY
        )
        transport.publish(
            subject = config.rawEventTransport.ingestSubject(
                exchangeId = config.exchangeId,
                channel = envelope.channel,
                lane = envelope.lane
            ),
            envelope = envelope
        )
    }
}

internal fun sanitizeSubjectToken(value: String): String =
    value.trim().lowercase().replace('.', '_').replace(' ', '_')

private fun resolveHyperliquidWsUrl(explicitUrl: String?, mainnet: Boolean): String {
    val url = explicitUrl?.trim()
    if (!url.isNullOrEmpty()) return url
    return if (mainnet) HYPERLIQUID_MAINNET_WS_URL else HYPERLIQUID_TESTNET_WS_URL
}

private fun IngestionStats.totalPending(): Int =
    pendingTrades + pendingCandles + pendingOrderbooks + pendingAssetContexts

private data class RepairStreamResult(
    val publishedCandles: Int,
    val deferred: Boolean
)

private val IngestionStats.totalPending: Int
    get() = pendingTrades + pendingCandles + pendingOrderbooks + pendingAssetContexts
