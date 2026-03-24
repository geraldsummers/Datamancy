package org.datamancy.txgateway.services

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SchemaOverview(
    val tables: List<String>,
    val rawTables: List<String>,
    val aliases: List<String>
)

data class LatestQuote(
    val exchange: String,
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val last: Double,
    val timestamp: Instant,
    val source: String
)

data class TxHistoryRecord(
    val id: Long,
    val username: String,
    val txType: String,
    val status: String,
    val request: String,
    val response: String?,
    val errorMessage: String?,
    val timestamp: Instant
)

data class StrategyExecutionBaseline(
    val avgSlippageBps: Double? = null,
    val avgSubmitToFillMs: Double? = null,
    val avgFillRatio: Double? = null,
    val avgTotalCostBps: Double? = null,
    val backtestEdgeBps: Double? = null
)

data class RiskPolicyRecord(
    val id: UUID,
    val username: String,
    val walletAddress: String?,
    val version: Int,
    val status: String,
    val policyJson: String,
    val createdBy: String,
    val createdAt: Instant,
    val activatedAt: Instant?,
    val activatedByWallet: String?,
    val activationSignature: String?,
    val activationNonce: String?,
    val activationMessage: String?,
    val isBootstrap: Boolean
)

data class RiskActivationChallengeRecord(
    val id: Long,
    val policyId: UUID,
    val username: String,
    val walletAddress: String,
    val nonce: String,
    val challengeMessage: String,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val createdAt: Instant
)

data class RiskAccountStateRecord(
    val username: String,
    val accountEquityUsd: BigDecimal,
    val highWaterMarkUsd: BigDecimal,
    val realizedPnlUsd: BigDecimal,
    val unrealizedPnlUsd: BigDecimal,
    val dailyRealizedPnlUsd: BigDecimal,
    val dailyUnrealizedPnlUsd: BigDecimal,
    val openExposureUsd: BigDecimal,
    val sentimentScore: Double?,
    val sentimentConfidence: Double?,
    val riskTier: String,
    val tierReason: String?,
    val updatedAt: Instant
)

data class RiskAccountStatePatch(
    val accountEquityUsd: BigDecimal? = null,
    val realizedPnlUsd: BigDecimal? = null,
    val unrealizedPnlUsd: BigDecimal? = null,
    val dailyRealizedPnlUsd: BigDecimal? = null,
    val dailyUnrealizedPnlUsd: BigDecimal? = null,
    val openExposureUsd: BigDecimal? = null,
    val highWaterMarkUsd: BigDecimal? = null
)

data class RiskKillSwitchStateRecord(
    val username: String,
    val engaged: Boolean,
    val reason: String?,
    val engagedAt: Instant?,
    val engagedBy: String?,
    val manualAckRequired: Boolean,
    val acknowledgedAt: Instant?,
    val acknowledgedBy: String?,
    val ackNote: String?,
    val updatedAt: Instant
)

data class SentimentSnapshot(
    val symbol: String,
    val sentimentScore: Double,
    val confidence: Double,
    val observedAt: Instant,
    val modelName: String?,
    val source: String?,
    val sentimentLabel: String?
)

object TxAuditLog : Table("tx_audit_log") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 64)
    val txType = varchar("tx_type", 32) // "hyperliquid_order", "evm_transfer"
    val request = text("request") // JSON
    val response = text("response").nullable()
    val status = varchar("status", 32) // "success", "error"
    val errorMessage = text("error_message").nullable()
    val timestamp = timestamp("timestamp").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

object EvmNonces : Table("evm_nonces") {
    val chainId = long("chain_id")
    val fromAddress = varchar("from_address", 42)
    val nonce = long("nonce")
    val lastUpdated = timestamp("last_updated").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(chainId, fromAddress)
}

object EvmPendingTxs : Table("evm_pending_txs") {
    val id = long("id").autoIncrement()
    val userId = varchar("user_id", 64)
    val chainId = long("chain_id")
    val nonce = long("nonce")
    val fromAddress = varchar("from_address", 42)
    val txHash = varchar("tx_hash", 66)
    val status = varchar("status", 32) // "submitted", "confirmed", "replaced", "failed"
    val originalGasPrice = varchar("original_gas_price", 64)
    val currentGasPrice = varchar("current_gas_price", 64)
    val replacementCount = integer("replacement_count").default(0)
    val submittedAt = timestamp("submitted_at").clientDefault { Instant.now() }
    val confirmedAt = timestamp("confirmed_at").nullable()
    val replacedByTxHash = varchar("replaced_by_tx_hash", 66).nullable()

    override val primaryKey = PrimaryKey(id)
}

object RateLimitWindows : Table("rate_limit_windows") {
    val username = varchar("username", 64)
    val windowStart = timestamp("window_start")
    val txCount = integer("tx_count").default(0)

    override val primaryKey = PrimaryKey(username, windowStart)
}

class DatabaseService(
    private val host: String = System.getenv("POSTGRES_HOST") ?: "postgres",
    private val port: Int = System.getenv("POSTGRES_PORT")?.toInt() ?: 5432,
    private val database: String = System.getenv("POSTGRES_DB") ?: "txgateway",
    private val user: String = System.getenv("POSTGRES_USER") ?: "txgateway",
    private val dbPassword: String = System.getenv("POSTGRES_PASSWORD")?.also {
        require(it.isNotBlank()) { "POSTGRES_PASSWORD environment variable is empty" }
    } ?: error("POSTGRES_PASSWORD environment variable not set")
) {
    private val marketDataHost: String = System.getenv("POSTGRES_MARKET_DATA_HOST") ?: host
    private val marketDataPort: Int = System.getenv("POSTGRES_MARKET_DATA_PORT")?.toIntOrNull() ?: port
    private val marketDataDatabase: String = System.getenv("POSTGRES_MARKET_DATA_DB") ?: "datamancy"
    private val marketDataUser: String =
        System.getenv("POSTGRES_MARKET_DATA_USER")
            ?: System.getenv("POSTGRES_PIPELINE_USER")
            ?: user
    private val marketDataPassword: String =
        System.getenv("POSTGRES_MARKET_DATA_PASSWORD")
            ?: System.getenv("POSTGRES_PIPELINE_PASSWORD")
            ?: dbPassword

    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    private lateinit var dataSource: HikariDataSource
    private var marketDataSource: HikariDataSource? = null
    private val missingTableWarnings = ConcurrentHashMap.newKeySet<String>()
    private val analyticsSchemaEnsured = ConcurrentHashMap.newKeySet<String>()
    private val hyperliquidMainnetFlag = System.getenv("HYPERLIQUID_MAINNET")
    private val legacyHyperliquidQuoteExchange = resolveHyperliquidQuoteExchange(
        explicitExchange = System.getenv("HYPERLIQUID_QUOTE_EXCHANGE"),
        mainnetFlag = hyperliquidMainnetFlag
    )
    private val hyperliquidForwardPaperQuoteExchange = resolveHyperliquidQuoteExchangeForExecutionMode(
        requestedExecutionMode = "forward_paper",
        legacyQuoteExchange = legacyHyperliquidQuoteExchange,
        forwardPaperExchange = System.getenv("HYPERLIQUID_FORWARD_PAPER_QUOTE_EXCHANGE"),
        testnetExchange = System.getenv("HYPERLIQUID_TESTNET_QUOTE_EXCHANGE"),
        mainnetExchange = System.getenv("HYPERLIQUID_MAINNET_QUOTE_EXCHANGE"),
        mainnetFlag = hyperliquidMainnetFlag
    )
    private val hyperliquidTestnetQuoteExchange = resolveHyperliquidQuoteExchangeForExecutionMode(
        requestedExecutionMode = "testnet_live",
        legacyQuoteExchange = legacyHyperliquidQuoteExchange,
        forwardPaperExchange = System.getenv("HYPERLIQUID_FORWARD_PAPER_QUOTE_EXCHANGE"),
        testnetExchange = System.getenv("HYPERLIQUID_TESTNET_QUOTE_EXCHANGE"),
        mainnetExchange = System.getenv("HYPERLIQUID_MAINNET_QUOTE_EXCHANGE"),
        mainnetFlag = hyperliquidMainnetFlag
    )
    private val hyperliquidMainnetQuoteExchange = resolveHyperliquidQuoteExchangeForExecutionMode(
        requestedExecutionMode = "mainnet_live",
        legacyQuoteExchange = legacyHyperliquidQuoteExchange,
        forwardPaperExchange = System.getenv("HYPERLIQUID_FORWARD_PAPER_QUOTE_EXCHANGE"),
        testnetExchange = System.getenv("HYPERLIQUID_TESTNET_QUOTE_EXCHANGE"),
        mainnetExchange = System.getenv("HYPERLIQUID_MAINNET_QUOTE_EXCHANGE"),
        mainnetFlag = hyperliquidMainnetFlag
    )
    private val allowCanonicalHyperliquidFallback = parseBooleanFlag(
        raw = System.getenv("HYPERLIQUID_QUOTE_EXCHANGE_ALLOW_CANONICAL_FALLBACK"),
        defaultValue = true
    )

    fun init(maxAttempts: Int = 60, delayMs: Long = 2000) {
        logger.info("Initializing database connection to $host:$port/$database as user $user (password: ${dbPassword.length} chars)")
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            var candidateDataSource: HikariDataSource? = null
            try {
                candidateDataSource = createDataSource(
                    host = host,
                    port = port,
                    database = database,
                    username = user,
                    password = dbPassword,
                    maximumPoolSize = 10,
                    minimumIdle = 2,
                    poolName = "tx-gateway-primary"
                )
                Database.connect(candidateDataSource)

                // Create tables
                transaction {
                    SchemaUtils.createMissingTablesAndColumns(
                        TxAuditLog,
                        EvmNonces,
                        EvmPendingTxs,
                        RateLimitWindows
                    )
                }

                dataSource = candidateDataSource
                initializeQuoteDataSource()
                ensureRiskTables()
                normalizeDisengagedRiskKillSwitchRows()
                ensureBootstrapRiskPolicy()
                ensureStrategyAnalyticsSchemas()
                logger.info("Database initialized successfully")
                return
            } catch (e: Exception) {
                lastError = e
                candidateDataSource?.close()
                logger.warn("Database init failed ($attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    Thread.sleep(delayMs)
                }
            }
        }

        throw IllegalStateException("Failed to initialize database after $maxAttempts attempts", lastError)
    }

    private fun createDataSource(
        host: String,
        port: Int,
        database: String,
        username: String,
        password: String,
        maximumPoolSize: Int,
        minimumIdle: Int,
        poolName: String
    ): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            this.username = username
            this.password = password
            this.maximumPoolSize = maximumPoolSize
            this.minimumIdle = minimumIdle
            connectionTimeout = 30000
            this.poolName = poolName
        }
        return HikariDataSource(config)
    }

    private fun initializeQuoteDataSource() {
        marketDataSource?.close()
        marketDataSource = null

        val reusePrimary =
            marketDataHost == host &&
                marketDataPort == port &&
                marketDataDatabase == database &&
                marketDataUser == user &&
                marketDataPassword == dbPassword

        if (reusePrimary) {
            logger.info("Using primary tx-gateway datasource for quote queries")
            return
        }

        try {
            val quoteDs = createDataSource(
                host = marketDataHost,
                port = marketDataPort,
                database = marketDataDatabase,
                username = marketDataUser,
                password = marketDataPassword,
                maximumPoolSize = 4,
                minimumIdle = 1,
                poolName = "tx-gateway-marketdata"
            )
            quoteDs.connection.use { conn ->
                conn.prepareStatement("SELECT 1").use { stmt ->
                    stmt.executeQuery().use { rs -> rs.next() }
                }
            }
            marketDataSource = quoteDs
            logger.info(
                "Market-data quote datasource initialized for {}:{} / {} as {}",
                marketDataHost,
                marketDataPort,
                marketDataDatabase,
                marketDataUser
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to initialize market-data datasource for quote queries; falling back to primary DB: {}",
                e.message
            )
            marketDataSource?.close()
            marketDataSource = null
        }
    }

    private fun ensureRiskTables() {
        val ddl = listOf(
            """
            CREATE TABLE IF NOT EXISTS risk_policy_versions (
                id UUID PRIMARY KEY,
                username TEXT NOT NULL,
                wallet_address TEXT,
                version INTEGER NOT NULL,
                status TEXT NOT NULL,
                policy_json JSONB NOT NULL,
                created_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                activated_at TIMESTAMPTZ,
                activated_by_wallet TEXT,
                activation_signature TEXT,
                activation_nonce TEXT,
                activation_message TEXT,
                is_bootstrap BOOLEAN NOT NULL DEFAULT FALSE
            )
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_risk_policy_versions_user_version
                ON risk_policy_versions (username, version)
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_risk_policy_versions_user_active
                ON risk_policy_versions (username)
                WHERE status = 'active'
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS risk_policy_activation_nonces (
                id BIGSERIAL PRIMARY KEY,
                policy_id UUID NOT NULL,
                username TEXT NOT NULL,
                wallet_address TEXT NOT NULL,
                nonce TEXT NOT NULL,
                challenge_message TEXT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                consumed_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_risk_activation_nonce_unique
                ON risk_policy_activation_nonces (policy_id, wallet_address, nonce)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS risk_account_state (
                username TEXT PRIMARY KEY,
                account_equity_usd NUMERIC(20, 8) NOT NULL DEFAULT 100000,
                high_water_mark_usd NUMERIC(20, 8) NOT NULL DEFAULT 100000,
                realized_pnl_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
                unrealized_pnl_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
                daily_realized_pnl_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
                daily_unrealized_pnl_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
                open_exposure_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
                sentiment_score DOUBLE PRECISION,
                sentiment_confidence DOUBLE PRECISION,
                risk_tier TEXT NOT NULL DEFAULT 'normal',
                tier_reason TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS risk_kill_switch_state (
                username TEXT PRIMARY KEY,
                engaged BOOLEAN NOT NULL DEFAULT FALSE,
                reason TEXT,
                engaged_at TIMESTAMPTZ,
                engaged_by TEXT,
                manual_ack_required BOOLEAN NOT NULL DEFAULT TRUE,
                acknowledged_at TIMESTAMPTZ,
                acknowledged_by TEXT,
                ack_note TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent()
        )

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                ddl.forEach(stmt::execute)
            }
        }
    }

    private fun normalizeDisengagedRiskKillSwitchRows() {
        val sql = """
            UPDATE risk_kill_switch_state
            SET
                reason = NULL,
                engaged_at = NULL,
                engaged_by = NULL,
                manual_ack_required = FALSE,
                updated_at = NOW()
            WHERE engaged = FALSE
              AND (
                reason IS NOT NULL
                OR engaged_at IS NOT NULL
                OR engaged_by IS NOT NULL
                OR manual_ack_required <> FALSE
              )
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                val normalizedRows = stmt.executeUpdate()
                if (normalizedRows > 0) {
                    logger.info("Normalized {} disengaged risk kill-switch rows", normalizedRows)
                }
            }
        }
    }

    private fun ensureBootstrapRiskPolicy() {
        val existingSql = """
            SELECT id
            FROM risk_policy_versions
            WHERE username = '*' AND status = 'active'
            LIMIT 1
        """.trimIndent()
        val insertSql = """
            INSERT INTO risk_policy_versions (
                id, username, wallet_address, version, status, policy_json, created_by, is_bootstrap
            ) VALUES (?, '*', NULL, 1, 'active', ?::jsonb, 'system-bootstrap', TRUE)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(existingSql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return
                }
            }
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, defaultBootstrapRiskPolicyJson())
                stmt.executeUpdate()
            }
            logger.info("Created bootstrap risk policy for global scope")
        }
    }

    private fun defaultBootstrapRiskPolicyJson(): String = """
        {
          "maxExposureUsd": 25000.0,
          "maxLeverage": 2.5,
          "maxDrawdownPct": 8.0,
          "maxDailyLossUsd": 2500.0,
          "approachTrigger": 0.8,
          "unwindTrigger": 1.0,
          "hardKillTrigger": 1.15,
          "unwindSliceSeconds": 45,
          "unwindMaxSlippageBps": 35.0,
          "manualAckRequired": true,
          "requireSentimentSignal": false,
          "sentimentLookbackMinutes": 180,
          "sentimentEscalationScore": -0.6,
          "sentimentEscalationConfidence": 0.65
        }
    """.trimIndent()

    fun logTransaction(
        username: String,
        txType: String,
        request: String,
        response: String?,
        status: String,
        errorMessage: String? = null
    ) {
        transaction {
            TxAuditLog.insert {
                it[TxAuditLog.username] = username
                it[TxAuditLog.txType] = txType
                it[TxAuditLog.request] = request
                it[TxAuditLog.response] = response
                it[TxAuditLog.status] = status
                it[TxAuditLog.errorMessage] = errorMessage
            }
        }
    }

    fun allocateNonce(chainId: Long, fromAddress: String): Long {
        return transaction {
            val existing = EvmNonces.selectAll().where {
                (EvmNonces.chainId eq chainId) and (EvmNonces.fromAddress eq fromAddress)
            }.singleOrNull()

            if (existing != null) {
                val currentNonce = existing[EvmNonces.nonce]
                val newNonce = currentNonce + 1
                EvmNonces.update({
                    (EvmNonces.chainId eq chainId) and (EvmNonces.fromAddress eq fromAddress)
                }) {
                    it[nonce] = newNonce
                    it[lastUpdated] = Instant.now()
                }
                newNonce
            } else {
                EvmNonces.insert {
                    it[EvmNonces.chainId] = chainId
                    it[EvmNonces.fromAddress] = fromAddress
                    it[nonce] = 0L
                }
                0L
            }
        }
    }

    fun checkRateLimit(username: String, maxTxPerHour: Int): Boolean {
        val now = Instant.now()
        val oneHourAgo = now.minusSeconds(3600)

        return transaction {
            // Count transactions in the last hour
            val count = RateLimitWindows.selectAll().where {
                (RateLimitWindows.username eq username) and
                        (RateLimitWindows.windowStart greater oneHourAgo)
            }.sumOf { it[RateLimitWindows.txCount] }

            if (count >= maxTxPerHour) {
                false
            } else {
                // Increment counter
                val windowStart = Instant.ofEpochSecond(now.epochSecond / 3600 * 3600)
                val existing = RateLimitWindows.selectAll().where {
                    (RateLimitWindows.username eq username) and
                            (RateLimitWindows.windowStart eq windowStart)
                }.singleOrNull()

                if (existing != null) {
                    RateLimitWindows.update({
                        (RateLimitWindows.username eq username) and
                                (RateLimitWindows.windowStart eq windowStart)
                    }) {
                        it[txCount] = existing[txCount] + 1
                    }
                } else {
                    RateLimitWindows.insert {
                        it[RateLimitWindows.username] = username
                        it[RateLimitWindows.windowStart] = windowStart
                        it[txCount] = 1
                    }
                }
                true
            }
        }
    }

    fun healthCheck() {
        transaction {
            exec("SELECT 1") {}
        }
    }

    /**
     * Fetch the latest quote for an exchange/symbol pair.
     *
     * Resolution order:
     * 1. Latest orderbook snapshot (best bid/ask + mid)
     * 2. Latest candle close (synthetic tight spread)
     * 3. Latest trade (synthetic tight spread)
     */
    fun fetchLatestQuote(exchange: String, symbol: String): LatestQuote? =
        fetchLatestQuote(exchange = exchange, symbol = symbol, executionMode = null)

    fun fetchLatestQuote(exchange: String, symbol: String, executionMode: String?): LatestQuote? {
        val normalizedExchange = exchange.lowercase()
        val candidates = symbolCandidates(symbol)
        val exchangeCandidates = quoteExchangeCandidates(normalizedExchange, executionMode)

        val orderbookQuotes = buildList {
            exchangeCandidates.forEach { exchangeCandidate ->
                candidates.forEach { candidate ->
                    queryOrderbookQuote(exchangeCandidate, candidate)?.let { quote ->
                        add(
                            normalizeResolvedQuote(
                                quote = quote,
                                requestedExchange = normalizedExchange,
                                resolvedExchange = exchangeCandidate
                            )
                        )
                    }
                }
            }
        }
        freshestQuote(orderbookQuotes)?.let { return it }

        val marketDataQuotes = buildList {
            exchangeCandidates.forEach { exchangeCandidate ->
                candidates.forEach { candidate ->
                    queryMarketDataQuote(exchangeCandidate, candidate)?.let { quote ->
                        add(
                            normalizeResolvedQuote(
                                quote = quote,
                                requestedExchange = normalizedExchange,
                                resolvedExchange = exchangeCandidate
                            )
                        )
                    }
                }
            }
        }
        return freshestQuote(marketDataQuotes)
    }

    private fun quoteExchangeCandidates(exchange: String, executionMode: String?): List<String> {
        if (exchange != "hyperliquid") return listOf(exchange)
        return resolveHyperliquidQuoteExchangeCandidates(
            requestedExecutionMode = executionMode,
            legacyQuoteExchange = legacyHyperliquidQuoteExchange,
            forwardPaperExchange = hyperliquidForwardPaperQuoteExchange,
            testnetExchange = hyperliquidTestnetQuoteExchange,
            mainnetExchange = hyperliquidMainnetQuoteExchange,
            mainnetFlag = hyperliquidMainnetFlag,
            allowCanonicalFallback = allowCanonicalHyperliquidFallback
        )
    }

    private fun normalizeResolvedQuote(
        quote: LatestQuote,
        requestedExchange: String,
        resolvedExchange: String
    ): LatestQuote {
        if (resolvedExchange == requestedExchange) return quote
        val resolvedTag = "resolved_exchange=${resolvedExchange.lowercase()}"
        val taggedSource = if (quote.source.contains(resolvedTag)) quote.source else "${quote.source}:$resolvedTag"
        return quote.copy(
            exchange = requestedExchange,
            source = taggedSource
        )
    }

    private fun freshestQuote(quotes: List<LatestQuote>): LatestQuote? =
        quotes.maxByOrNull { it.timestamp }

    private fun quoteDataSources(): List<HikariDataSource> {
        marketDataSource?.let { return listOf(it) }
        return if (::dataSource.isInitialized) listOf(dataSource) else emptyList()
    }

    private fun analyticsDataSources(): List<HikariDataSource> = buildList {
        marketDataSource?.let { add(it) }
        if (::dataSource.isInitialized && dataSource !in this) {
            add(dataSource)
        }
    }

    private fun ensureStrategyAnalyticsSchemas() {
        analyticsDataSources().forEach { source ->
            if (!ensureStrategyAnalyticsSchema(source)) {
                logger.warn(
                    "Unable to ensure strategy analytics schema on datasource {}",
                    source.jdbcUrl.ifBlank { source.poolName ?: "unknown-pool" }
                )
            }
        }
    }

    fun getTransactionHistory(
        username: String,
        days: Int = 7,
        limit: Int = 200
    ): List<TxHistoryRecord> {
        val effectiveDays = days.coerceIn(1, 365)
        val effectiveLimit = limit.coerceIn(1, 1000)
        val sql = """
            SELECT id, username, tx_type, status, request, response, error_message, timestamp
            FROM tx_audit_log
            WHERE username = ?
              AND timestamp >= NOW() - (?::text || ' days')::interval
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        val records = mutableListOf<TxHistoryRecord>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, username)
                stmt.setInt(2, effectiveDays)
                stmt.setInt(3, effectiveLimit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        records += TxHistoryRecord(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            txType = rs.getString("tx_type"),
                            status = rs.getString("status"),
                            request = rs.getString("request") ?: "{}",
                            response = rs.getString("response"),
                            errorMessage = rs.getString("error_message"),
                            timestamp = rs.getTimestamp("timestamp").toInstant()
                        )
                    }
                }
            }
        }
        return records
    }

    fun logPaperExecutionAnalytics(
        strategyName: String,
        exchange: String,
        symbol: String,
        side: String,
        decisionLatencyMs: Double,
        submitToAckMs: Double,
        submitToFillMs: Double?,
        p50RoundTripMs: Double?,
        p95RoundTripMs: Double?,
        p99RoundTripMs: Double?,
        jitterMs: Double?,
        feeBps: Double,
        feeTier: String?,
        feeTierAdjustmentBps: Double?,
        makerFeeBps: Double?,
        takerFeeBps: Double?,
        spreadCostBps: Double,
        slippageBps: Double,
        impactBps: Double,
        adverseSelectionBps: Double,
        fundingDriftBps: Double?,
        basisDriftBps: Double?,
        totalCostBps: Double,
        edgeAfterCostBps: Double?,
        estimatedFeeUsd: Double?,
        estimatedCostUsd: Double?,
        metadataJson: String = "{}"
    ): Boolean {
        val latencySql = """
            INSERT INTO strategy_latency_metrics (
                observed_at,
                strategy_name,
                exchange,
                symbol,
                decision_latency_ms,
                submit_to_ack_ms,
                submit_to_fill_ms,
                p50_roundtrip_ms,
                p95_roundtrip_ms,
                p99_roundtrip_ms,
                jitter_ms,
                metadata
            ) VALUES (
                NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb
            )
        """.trimIndent()
        val costSql = """
            INSERT INTO strategy_execution_costs (
                observed_at,
                strategy_name,
                exchange,
                symbol,
                side,
                fee_bps,
                fee_tier,
                fee_tier_adjustment_bps,
                maker_fee_bps,
                taker_fee_bps,
                spread_cost_bps,
                slippage_bps,
                impact_bps,
                adverse_selection_bps,
                funding_drift_bps,
                basis_drift_bps,
                total_cost_bps,
                edge_after_cost_bps,
                estimated_fee_usd,
                estimated_cost_usd,
                metadata
            ) VALUES (
                NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb
            )
        """.trimIndent()

        for (source in quoteDataSources()) {
            var tryNextSource = false
            var attemptedSchemaRepair = false
            var retryCurrentSource: Boolean
            do {
                retryCurrentSource = false
                try {
                    source.connection.use { conn ->
                        conn.autoCommit = false
                        try {
                            conn.prepareStatement(latencySql).use { stmt ->
                                stmt.setString(1, strategyName)
                                stmt.setString(2, exchange)
                                stmt.setString(3, symbol)
                                stmt.setDouble(4, decisionLatencyMs)
                                stmt.setDouble(5, submitToAckMs)
                                if (submitToFillMs != null) {
                                    stmt.setDouble(6, submitToFillMs)
                                } else {
                                    stmt.setNull(6, Types.DOUBLE)
                                }
                                if (p50RoundTripMs != null) {
                                    stmt.setDouble(7, p50RoundTripMs)
                                } else {
                                    stmt.setNull(7, Types.DOUBLE)
                                }
                                if (p95RoundTripMs != null) {
                                    stmt.setDouble(8, p95RoundTripMs)
                                } else {
                                    stmt.setNull(8, Types.DOUBLE)
                                }
                                if (p99RoundTripMs != null) {
                                    stmt.setDouble(9, p99RoundTripMs)
                                } else {
                                    stmt.setNull(9, Types.DOUBLE)
                                }
                                if (jitterMs != null) {
                                    stmt.setDouble(10, jitterMs)
                                } else {
                                    stmt.setNull(10, Types.DOUBLE)
                                }
                                stmt.setString(11, metadataJson)
                                stmt.executeUpdate()
                            }

                            conn.prepareStatement(costSql).use { stmt ->
                                stmt.setString(1, strategyName)
                                stmt.setString(2, exchange)
                                stmt.setString(3, symbol)
                                stmt.setString(4, side)
                                stmt.setDouble(5, feeBps)
                                stmt.setString(6, feeTier?.trim()?.ifEmpty { "retail" } ?: "retail")
                                setNullableDouble(stmt, 7, feeTierAdjustmentBps)
                                setNullableDouble(stmt, 8, makerFeeBps)
                                setNullableDouble(stmt, 9, takerFeeBps)
                                stmt.setDouble(10, spreadCostBps)
                                stmt.setDouble(11, slippageBps)
                                stmt.setDouble(12, impactBps)
                                stmt.setDouble(13, adverseSelectionBps)
                                setNullableDouble(stmt, 14, fundingDriftBps)
                                setNullableDouble(stmt, 15, basisDriftBps)
                                stmt.setDouble(16, totalCostBps)
                                setNullableDouble(stmt, 17, edgeAfterCostBps)
                                setNullableDouble(stmt, 18, estimatedFeeUsd)
                                if (estimatedCostUsd != null) {
                                    stmt.setDouble(19, estimatedCostUsd)
                                } else {
                                    stmt.setNull(19, Types.DOUBLE)
                                }
                                stmt.setString(20, metadataJson)
                                stmt.executeUpdate()
                            }

                            conn.commit()
                            return true
                        } catch (e: SQLException) {
                            conn.rollback()
                            if ((isMissingRelation(e) || isMissingColumn(e)) && !attemptedSchemaRepair &&
                                ensureStrategyAnalyticsSchema(source)
                            ) {
                                attemptedSchemaRepair = true
                                retryCurrentSource = true
                            } else if (isMissingRelation(e) || isMissingColumn(e)) {
                                warnMissingRelationOnce("strategy_latency_metrics", e)
                                warnMissingRelationOnce("strategy_execution_costs", e)
                                tryNextSource = true
                            } else {
                                logger.warn(
                                    "Failed to insert paper execution analytics for {}/{} strategy={}: {}",
                                    exchange,
                                    symbol,
                                    strategyName,
                                    e.message
                                )
                            }
                        } finally {
                            conn.autoCommit = true
                        }
                    }
                } catch (e: SQLException) {
                    if ((isMissingRelation(e) || isMissingColumn(e)) && !attemptedSchemaRepair &&
                        ensureStrategyAnalyticsSchema(source)
                    ) {
                        attemptedSchemaRepair = true
                        retryCurrentSource = true
                    } else if (isMissingRelation(e) || isMissingColumn(e)) {
                        warnMissingRelationOnce("strategy_latency_metrics", e)
                        warnMissingRelationOnce("strategy_execution_costs", e)
                        tryNextSource = true
                    } else {
                        logger.warn(
                            "Unable to persist paper execution analytics for {}/{} strategy={}: {}",
                            exchange,
                            symbol,
                            strategyName,
                            e.message
                        )
                    }
                }
            } while (retryCurrentSource)

            if (tryNextSource) continue
        }

        return false
    }

    fun logLiveBacktestDrift(
        strategyName: String,
        symbol: String,
        liveEdgeBps: Double?,
        backtestEdgeBps: Double?,
        fillQualityDeltaBps: Double?,
        slippageDriftBps: Double?,
        latencyDriftMs: Double?,
        driftScore: Double?,
        metadataJson: String = "{}"
    ): Boolean {
        val driftSql = """
            INSERT INTO strategy_live_backtest_drift (
                observed_at,
                strategy_name,
                symbol,
                live_edge_bps,
                backtest_edge_bps,
                fill_quality_delta_bps,
                slippage_drift_bps,
                latency_drift_ms,
                drift_score,
                metadata
            ) VALUES (
                NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb
            )
        """.trimIndent()

        for (source in quoteDataSources()) {
            var tryNextSource = false
            var attemptedSchemaRepair = false
            var retryCurrentSource: Boolean
            do {
                retryCurrentSource = false
                try {
                    source.connection.use { conn ->
                        conn.prepareStatement(driftSql).use { stmt ->
                            stmt.setString(1, strategyName)
                            stmt.setString(2, symbol)
                            setNullableDouble(stmt, 3, liveEdgeBps)
                            setNullableDouble(stmt, 4, backtestEdgeBps)
                            setNullableDouble(stmt, 5, fillQualityDeltaBps)
                            setNullableDouble(stmt, 6, slippageDriftBps)
                            setNullableDouble(stmt, 7, latencyDriftMs)
                            setNullableDouble(stmt, 8, driftScore)
                            stmt.setString(9, metadataJson)
                            stmt.executeUpdate()
                            return true
                        }
                    }
                } catch (e: SQLException) {
                    if (isMissingRelation(e) && !attemptedSchemaRepair &&
                        ensureStrategyAnalyticsSchema(source)
                    ) {
                        attemptedSchemaRepair = true
                        retryCurrentSource = true
                    } else if (isMissingRelation(e)) {
                        warnMissingRelationOnce("strategy_live_backtest_drift", e)
                        tryNextSource = true
                    } else {
                        logger.warn(
                            "Unable to persist strategy live-backtest drift for strategy={} symbol={}: {}",
                            strategyName,
                            symbol,
                            e.message
                        )
                    }
                }
            } while (retryCurrentSource)

            if (tryNextSource) continue
        }

        return false
    }

    fun fetchStrategyExecutionBaseline(
        strategyName: String,
        symbol: String,
        lookbackHours: Int = 24 * 14
    ): StrategyExecutionBaseline? {
        val candidates = symbolCandidates(symbol)
        if (candidates.isEmpty()) return null
        val effectiveLookbackHours = lookbackHours.coerceAtLeast(1)

        val metricsSql = """
            SELECT
                AVG(c.slippage_bps) AS avg_slippage_bps,
                AVG(COALESCE(l.submit_to_fill_ms, l.submit_to_ack_ms)) AS avg_submit_to_fill_ms,
                AVG(
                    CASE
                        WHEN c.metadata ? 'fillRatio'
                            THEN NULLIF(c.metadata ->> 'fillRatio', '')::DOUBLE PRECISION
                        ELSE NULL
                    END
                ) AS avg_fill_ratio,
                AVG(c.total_cost_bps) AS avg_total_cost_bps
            FROM strategy_execution_costs c
            JOIN strategy_latency_metrics l
              ON l.strategy_name = c.strategy_name
             AND l.exchange = c.exchange
             AND l.symbol = c.symbol
             AND l.observed_at = c.observed_at
            WHERE c.strategy_name = ?
              AND c.symbol = ANY(?)
              AND c.observed_at >= NOW() - (?::text || ' hours')::interval
        """.trimIndent()

        val backtestSql = """
            SELECT net_return_pct, trades
            FROM strategy_backtest_runs
            WHERE strategy_name = ?
              AND symbol = ANY(?)
            ORDER BY run_at DESC
            LIMIT 1
        """.trimIndent()

        for (source in quoteDataSources()) {
            var attemptedSchemaRepair = false
            var retryCurrentSource: Boolean
            do {
                retryCurrentSource = false
                try {
                    source.connection.use { conn ->
                        val symbolArray = conn.createArrayOf("text", candidates.toTypedArray())
                        try {
                            val metricsBaseline = conn.prepareStatement(metricsSql).use { stmt ->
                                stmt.setString(1, strategyName)
                                stmt.setArray(2, symbolArray)
                                stmt.setInt(3, effectiveLookbackHours)
                                stmt.executeQuery().use { rs ->
                                    if (!rs.next()) {
                                        StrategyExecutionBaseline()
                                    } else {
                                        StrategyExecutionBaseline(
                                            avgSlippageBps = rs.getDoubleOrNull("avg_slippage_bps"),
                                            avgSubmitToFillMs = rs.getDoubleOrNull("avg_submit_to_fill_ms"),
                                            avgFillRatio = rs.getDoubleOrNull("avg_fill_ratio"),
                                            avgTotalCostBps = rs.getDoubleOrNull("avg_total_cost_bps")
                                        )
                                    }
                                }
                            }

                            val backtestEdgeBps = conn.prepareStatement(backtestSql).use { stmt ->
                                stmt.setString(1, strategyName)
                                stmt.setArray(2, symbolArray)
                                stmt.executeQuery().use { rs ->
                                    if (!rs.next()) {
                                        null
                                    } else {
                                        val netReturnPct = (rs.getObject("net_return_pct") as? Number)?.toDouble()
                                        val trades = (rs.getObject("trades") as? Number)?.toInt() ?: 0
                                        netReturnPct?.let {
                                            if (trades > 0) {
                                                (it * 100.0) / trades
                                            } else {
                                                it * 100.0
                                            }
                                        }
                                    }
                                }
                            }

                            val combined = metricsBaseline.copy(backtestEdgeBps = backtestEdgeBps)
                            if (combined.hasAnyValue()) {
                                return combined
                            }
                        } finally {
                            runCatching { symbolArray.free() }
                        }
                    }
                } catch (e: SQLException) {
                    if (isMissingRelation(e) && !attemptedSchemaRepair &&
                        ensureStrategyAnalyticsSchema(source)
                    ) {
                        attemptedSchemaRepair = true
                        retryCurrentSource = true
                    } else if (isMissingRelation(e)) {
                        warnMissingRelationOnce("strategy_execution_costs", e)
                        warnMissingRelationOnce("strategy_latency_metrics", e)
                        warnMissingRelationOnce("strategy_backtest_runs", e)
                    } else {
                        logger.warn(
                            "Unable to query strategy execution baseline for strategy={} symbol={}: {}",
                            strategyName,
                            symbol,
                            e.message
                        )
                    }
                }
            } while (retryCurrentSource)
        }

        return null
    }

    fun createRiskPolicyVersion(
        username: String,
        policyJson: String,
        createdBy: String,
        walletAddress: String? = null
    ): RiskPolicyRecord {
        val policyId = UUID.randomUUID()
        val normalizedUser = username.trim().lowercase()
        val normalizedWallet = walletAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val nextVersionSql = """
            SELECT COALESCE(MAX(version), 0) + 1 AS next_version
            FROM risk_policy_versions
            WHERE username = ?
        """.trimIndent()
        val insertSql = """
            INSERT INTO risk_policy_versions (
                id, username, wallet_address, version, status, policy_json, created_by, is_bootstrap
            ) VALUES (?, ?, ?, ?, 'draft', ?::jsonb, ?, FALSE)
        """.trimIndent()

        dataSource.connection.use { conn ->
            val nextVersion = conn.prepareStatement(nextVersionSql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("next_version") else 1
                }
            }
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, policyId)
                stmt.setString(2, normalizedUser)
                stmt.setString(3, normalizedWallet)
                stmt.setInt(4, nextVersion)
                stmt.setString(5, policyJson)
                stmt.setString(6, createdBy)
                stmt.executeUpdate()
            }
        }

        return getRiskPolicyById(normalizedUser, policyId)
            ?: error("Failed to read created risk policy $policyId")
    }

    fun listRiskPolicies(username: String, includeBootstrap: Boolean = true): List<RiskPolicyRecord> {
        val normalizedUser = username.trim().lowercase()
        val sql = if (includeBootstrap) {
            """
            SELECT *
            FROM risk_policy_versions
            WHERE username = ? OR username = '*'
            ORDER BY created_at DESC, version DESC
            """.trimIndent()
        } else {
            """
            SELECT *
            FROM risk_policy_versions
            WHERE username = ?
            ORDER BY created_at DESC, version DESC
            """.trimIndent()
        }

        val policies = mutableListOf<RiskPolicyRecord>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        policies += rs.toRiskPolicyRecord()
                    }
                }
            }
        }
        return policies
    }

    fun listActiveWalletLinkedRiskPolicies(): List<RiskPolicyRecord> {
        val sql = """
            SELECT *
            FROM risk_policy_versions
            WHERE status = 'active'
              AND username <> '*'
              AND wallet_address IS NOT NULL
              AND BTRIM(wallet_address) <> ''
            ORDER BY username ASC, activated_at DESC NULLS LAST, created_at DESC
        """.trimIndent()

        val policies = mutableListOf<RiskPolicyRecord>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        policies += rs.toRiskPolicyRecord()
                    }
                }
            }
        }
        return policies
    }

    fun getRiskPolicyById(username: String, policyId: UUID): RiskPolicyRecord? {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            SELECT *
            FROM risk_policy_versions
            WHERE id = ?
              AND (username = ? OR username = '*')
            LIMIT 1
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, policyId)
                stmt.setString(2, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRiskPolicyRecord()
                }
            }
        }
    }

    fun getActiveRiskPolicyForUser(username: String): RiskPolicyRecord? {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            SELECT *
            FROM risk_policy_versions
            WHERE username = ?
              AND status = 'active'
            LIMIT 1
        """.trimIndent()
        val globalSql = """
            SELECT *
            FROM risk_policy_versions
            WHERE username = '*'
              AND status = 'active'
            LIMIT 1
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.toRiskPolicyRecord()
                    }
                }
            }
            conn.prepareStatement(globalSql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.toRiskPolicyRecord()
                    }
                }
            }
        }
        return null
    }

    fun createRiskActivationChallenge(
        username: String,
        policyId: UUID,
        walletAddress: String,
        nonce: String,
        challengeMessage: String,
        expiresAt: Instant
    ): RiskActivationChallengeRecord {
        val normalizedUser = username.trim().lowercase()
        val normalizedWallet = walletAddress.trim().lowercase()
        val sql = """
            INSERT INTO risk_policy_activation_nonces (
                policy_id, username, wallet_address, nonce, challenge_message, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id, policy_id, username, wallet_address, nonce, challenge_message, expires_at, consumed_at, created_at
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, policyId)
                stmt.setString(2, normalizedUser)
                stmt.setString(3, normalizedWallet)
                stmt.setString(4, nonce)
                stmt.setString(5, challengeMessage)
                stmt.setTimestamp(6, Timestamp.from(expiresAt))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) error("Failed to create risk activation challenge")
                    return rs.toRiskActivationChallengeRecord()
                }
            }
        }
    }

    fun consumeRiskActivationChallenge(
        username: String,
        policyId: UUID,
        walletAddress: String,
        nonce: String,
        now: Instant = Instant.now()
    ): RiskActivationChallengeRecord? {
        val normalizedUser = username.trim().lowercase()
        val normalizedWallet = walletAddress.trim().lowercase()
        val sql = """
            UPDATE risk_policy_activation_nonces
            SET consumed_at = NOW()
            WHERE id = (
                SELECT id
                FROM risk_policy_activation_nonces
                WHERE policy_id = ?
                  AND username = ?
                  AND wallet_address = ?
                  AND nonce = ?
                  AND consumed_at IS NULL
                  AND expires_at >= ?
                ORDER BY created_at DESC
                LIMIT 1
            )
            RETURNING id, policy_id, username, wallet_address, nonce, challenge_message, expires_at, consumed_at, created_at
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, policyId)
                stmt.setString(2, normalizedUser)
                stmt.setString(3, normalizedWallet)
                stmt.setString(4, nonce)
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRiskActivationChallengeRecord()
                }
            }
        }
    }

    fun activateRiskPolicy(
        username: String,
        policyId: UUID,
        walletAddress: String,
        signature: String,
        nonce: String,
        message: String
    ): RiskPolicyRecord? {
        val normalizedUser = username.trim().lowercase()
        val normalizedWallet = walletAddress.trim().lowercase()
        val deactivateSql = """
            UPDATE risk_policy_versions
            SET status = 'superseded'
            WHERE username = ?
              AND status = 'active'
        """.trimIndent()
        val activateSql = """
            UPDATE risk_policy_versions
            SET
                status = 'active',
                wallet_address = ?,
                activated_at = NOW(),
                activated_by_wallet = ?,
                activation_signature = ?,
                activation_nonce = ?,
                activation_message = ?
            WHERE id = ?
              AND username = ?
            RETURNING *
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(deactivateSql).use { stmt ->
                    stmt.setString(1, normalizedUser)
                    stmt.executeUpdate()
                }

                val activated = conn.prepareStatement(activateSql).use { stmt ->
                    stmt.setString(1, normalizedWallet)
                    stmt.setString(2, normalizedWallet)
                    stmt.setString(3, signature)
                    stmt.setString(4, nonce)
                    stmt.setString(5, message)
                    stmt.setObject(6, policyId)
                    stmt.setString(7, normalizedUser)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.toRiskPolicyRecord() else null
                    }
                }
                conn.commit()
                return activated
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun getRiskAccountState(username: String): RiskAccountStateRecord? {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            SELECT *
            FROM risk_account_state
            WHERE username = ?
            LIMIT 1
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRiskAccountStateRecord()
                }
            }
        }
    }

    fun getOrCreateRiskAccountState(username: String): RiskAccountStateRecord {
        getRiskAccountState(username)?.let { return it }
        val normalizedUser = username.trim().lowercase()
        val insertSql = """
            INSERT INTO risk_account_state (username)
            VALUES (?)
            ON CONFLICT (username) DO NOTHING
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeUpdate()
            }
        }
        return getRiskAccountState(normalizedUser)
            ?: error("Failed to create risk account state for $normalizedUser")
    }

    fun upsertRiskAccountState(username: String, patch: RiskAccountStatePatch): RiskAccountStateRecord {
        val normalizedUser = username.trim().lowercase()
        val existing = getOrCreateRiskAccountState(normalizedUser)

        val accountEquity = patch.accountEquityUsd ?: existing.accountEquityUsd
        val realizedPnl = patch.realizedPnlUsd ?: existing.realizedPnlUsd
        val unrealizedPnl = patch.unrealizedPnlUsd ?: existing.unrealizedPnlUsd
        val dailyRealized = patch.dailyRealizedPnlUsd ?: existing.dailyRealizedPnlUsd
        val dailyUnrealized = patch.dailyUnrealizedPnlUsd ?: existing.dailyUnrealizedPnlUsd
        val openExposure = (patch.openExposureUsd ?: existing.openExposureUsd).max(BigDecimal.ZERO)
        val candidateHighWater = patch.highWaterMarkUsd ?: existing.highWaterMarkUsd
        val highWater = maxOf(candidateHighWater, accountEquity, existing.highWaterMarkUsd)

        val sql = """
            INSERT INTO risk_account_state (
                username,
                account_equity_usd,
                high_water_mark_usd,
                realized_pnl_usd,
                unrealized_pnl_usd,
                daily_realized_pnl_usd,
                daily_unrealized_pnl_usd,
                open_exposure_usd,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (username) DO UPDATE SET
                account_equity_usd = EXCLUDED.account_equity_usd,
                high_water_mark_usd = EXCLUDED.high_water_mark_usd,
                realized_pnl_usd = EXCLUDED.realized_pnl_usd,
                unrealized_pnl_usd = EXCLUDED.unrealized_pnl_usd,
                daily_realized_pnl_usd = EXCLUDED.daily_realized_pnl_usd,
                daily_unrealized_pnl_usd = EXCLUDED.daily_unrealized_pnl_usd,
                open_exposure_usd = EXCLUDED.open_exposure_usd,
                updated_at = NOW()
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.setBigDecimal(2, accountEquity)
                stmt.setBigDecimal(3, highWater)
                stmt.setBigDecimal(4, realizedPnl)
                stmt.setBigDecimal(5, unrealizedPnl)
                stmt.setBigDecimal(6, dailyRealized)
                stmt.setBigDecimal(7, dailyUnrealized)
                stmt.setBigDecimal(8, openExposure)
                stmt.executeUpdate()
            }
        }

        return getOrCreateRiskAccountState(normalizedUser)
    }

    fun adjustRiskOpenExposure(username: String, deltaExposureUsd: BigDecimal): RiskAccountStateRecord {
        val normalizedUser = username.trim().lowercase()
        getOrCreateRiskAccountState(normalizedUser)
        val sql = """
            UPDATE risk_account_state
            SET
                open_exposure_usd = GREATEST(0, open_exposure_usd + ?),
                updated_at = NOW()
            WHERE username = ?
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setBigDecimal(1, deltaExposureUsd)
                stmt.setString(2, normalizedUser)
                stmt.executeUpdate()
            }
        }
        return getOrCreateRiskAccountState(normalizedUser)
    }

    fun updateRiskTierSnapshot(
        username: String,
        riskTier: String,
        tierReason: String?,
        sentiment: SentimentSnapshot?
    ): RiskAccountStateRecord {
        val normalizedUser = username.trim().lowercase()
        getOrCreateRiskAccountState(normalizedUser)
        val sql = """
            UPDATE risk_account_state
            SET
                risk_tier = ?,
                tier_reason = ?,
                sentiment_score = ?,
                sentiment_confidence = ?,
                updated_at = NOW()
            WHERE username = ?
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, riskTier)
                stmt.setString(2, tierReason)
                if (sentiment != null) {
                    stmt.setDouble(3, sentiment.sentimentScore)
                    stmt.setDouble(4, sentiment.confidence)
                } else {
                    stmt.setNull(3, java.sql.Types.DOUBLE)
                    stmt.setNull(4, java.sql.Types.DOUBLE)
                }
                stmt.setString(5, normalizedUser)
                stmt.executeUpdate()
            }
        }
        return getOrCreateRiskAccountState(normalizedUser)
    }

    fun getRiskKillSwitchState(username: String): RiskKillSwitchStateRecord? {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            SELECT *
            FROM risk_kill_switch_state
            WHERE username = ?
            LIMIT 1
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRiskKillSwitchStateRecord()
                }
            }
        }
    }

    fun engageRiskKillSwitch(
        username: String,
        reason: String,
        engagedBy: String,
        manualAckRequired: Boolean = true
    ): RiskKillSwitchStateRecord {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            INSERT INTO risk_kill_switch_state (
                username,
                engaged,
                reason,
                engaged_at,
                engaged_by,
                manual_ack_required,
                acknowledged_at,
                acknowledged_by,
                ack_note,
                updated_at
            ) VALUES (?, TRUE, ?, NOW(), ?, ?, NULL, NULL, NULL, NOW())
            ON CONFLICT (username) DO UPDATE SET
                engaged = TRUE,
                reason = EXCLUDED.reason,
                engaged_at = NOW(),
                engaged_by = EXCLUDED.engaged_by,
                manual_ack_required = EXCLUDED.manual_ack_required,
                acknowledged_at = NULL,
                acknowledged_by = NULL,
                ack_note = NULL,
                updated_at = NOW()
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, normalizedUser)
                stmt.setString(2, reason)
                stmt.setString(3, engagedBy)
                stmt.setBoolean(4, manualAckRequired)
                stmt.executeUpdate()
            }
        }
        return getRiskKillSwitchState(normalizedUser)
            ?: error("Failed to engage kill switch for $normalizedUser")
    }

    fun acknowledgeRiskKillSwitch(
        username: String,
        acknowledgedBy: String,
        note: String?
    ): RiskKillSwitchStateRecord? {
        val normalizedUser = username.trim().lowercase()
        val sql = """
            UPDATE risk_kill_switch_state
            SET
                engaged = FALSE,
                reason = NULL,
                engaged_at = NULL,
                engaged_by = NULL,
                manual_ack_required = FALSE,
                acknowledged_at = NOW(),
                acknowledged_by = ?,
                ack_note = ?,
                updated_at = NOW()
            WHERE username = ?
              AND engaged = TRUE
            RETURNING *
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, acknowledgedBy)
                stmt.setString(2, note)
                stmt.setString(3, normalizedUser)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRiskKillSwitchStateRecord()
                }
            }
        }
    }

    fun fetchLatestSentiment(symbol: String, lookbackMinutes: Int = 180): SentimentSnapshot? {
        val candidates = (symbolCandidates(symbol) + "CRYPTO_GLOBAL").distinct()
        if (candidates.isEmpty()) return null

        val placeholders = candidates.joinToString(",") { "?" }
        val sqlWithLabel = """
            SELECT
                symbol,
                sentiment_score,
                confidence,
                observed_at,
                model_name,
                source,
                sentiment_label
            FROM rss_sentiment_signals
            WHERE symbol IN ($placeholders)
              AND observed_at >= NOW() - (?::text || ' minutes')::interval
            ORDER BY observed_at DESC
            LIMIT 1
        """.trimIndent()
        val sqlLegacy = """
            SELECT
                symbol,
                sentiment_score,
                confidence,
                observed_at,
                model_name,
                source
            FROM rss_sentiment_signals
            WHERE symbol IN ($placeholders)
              AND observed_at >= NOW() - (?::text || ' minutes')::interval
            ORDER BY observed_at DESC
            LIMIT 1
        """.trimIndent()

        var best: SentimentSnapshot? = null
        for (quoteSource in quoteDataSources()) {
            val fromSource = runSentimentLookupQuery(
                dataSource = quoteSource,
                sql = sqlWithLabel,
                sqlLegacy = sqlLegacy,
                candidates = candidates,
                lookbackMinutes = lookbackMinutes
            ) ?: continue
            if (best == null || fromSource.observedAt.isAfter(best!!.observedAt)) {
                best = fromSource
            }
        }
        return best
    }

    private fun runSentimentLookupQuery(
        dataSource: HikariDataSource,
        sql: String,
        sqlLegacy: String,
        candidates: List<String>,
        lookbackMinutes: Int
    ): SentimentSnapshot? {
        fun execute(query: String, includeLabel: Boolean): SentimentSnapshot? {
            dataSource.connection.use { conn ->
                conn.prepareStatement(query).use { stmt ->
                    var index = 1
                    candidates.forEach { candidate ->
                        stmt.setString(index++, candidate)
                    }
                    stmt.setInt(index, lookbackMinutes.coerceAtLeast(1))
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        return SentimentSnapshot(
                            symbol = rs.getString("symbol"),
                            sentimentScore = rs.getDouble("sentiment_score"),
                            confidence = rs.getDouble("confidence"),
                            observedAt = rs.getTimestamp("observed_at")?.toInstant() ?: Instant.now(),
                            modelName = rs.getString("model_name"),
                            source = rs.getString("source"),
                            sentimentLabel = if (includeLabel) rs.getString("sentiment_label") else null
                        )
                    }
                }
            }
        }

        return try {
            execute(sql, includeLabel = true)
        } catch (e: SQLException) {
            if (isMissingColumn(e)) {
                execute(sqlLegacy, includeLabel = false)
            } else if (isMissingRelation(e)) {
                warnMissingRelationOnce("rss_sentiment_signals", e)
                null
            } else {
                logger.warn("Failed querying rss_sentiment_signals: {}", e.message)
                null
            }
        }
    }

    private fun queryOrderbookQuote(exchange: String, symbol: String): LatestQuote? {
        val canonicalSql = """
            SELECT symbol, best_bid AS bid, best_ask AS ask, mid_price, time
            FROM orderbook_data
            WHERE exchange = ?
              AND symbol = ?
              AND best_bid IS NOT NULL
              AND best_ask IS NOT NULL
            ORDER BY time DESC
            LIMIT 1
        """.trimIndent()
        val topOfBookSql = """
            SELECT symbol, bid_price AS bid, ask_price AS ask, ((bid_price + ask_price) / 2.0) AS mid_price, time
            FROM orderbook_data
            WHERE exchange = ?
              AND symbol = ?
              AND bid_price IS NOT NULL
              AND ask_price IS NOT NULL
            ORDER BY time DESC
            LIMIT 1
        """.trimIndent()

        return runOrderbookQuoteQuery(
            exchange = exchange,
            symbol = symbol,
            sql = canonicalSql,
            sourceLabel = "orderbook_data:canonical"
        ) ?: runOrderbookQuoteQuery(
            exchange = exchange,
            symbol = symbol,
            sql = topOfBookSql,
            sourceLabel = "orderbook_data:top_of_book_legacy"
        )
    }

    private fun runOrderbookQuoteQuery(
        exchange: String,
        symbol: String,
        sql: String,
        sourceLabel: String
    ): LatestQuote? {
        for (quoteSource in quoteDataSources()) {
            try {
                quoteSource.connection.use { conn ->
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, exchange)
                        stmt.setString(2, symbol)
                        stmt.executeQuery().use { rs ->
                            if (!rs.next()) return@use
                            val bid = rs.getBigDecimal("bid")?.toDouble() ?: return@use
                            val ask = rs.getBigDecimal("ask")?.toDouble() ?: return@use
                            if (bid <= 0.0 || ask <= 0.0) return@use
                            val mid = rs.getBigDecimal("mid_price")?.toDouble()
                                ?: ((bid + ask) / 2.0)
                            val ts = rs.getTimestamp("time")?.toInstant() ?: Instant.now()
                            return LatestQuote(
                                exchange = exchange,
                                symbol = rs.getString("symbol"),
                                bid = bid,
                                ask = ask,
                                last = mid,
                                timestamp = ts,
                                source = sourceLabel
                            )
                        }
                    }
                }
            } catch (e: SQLException) {
                if (isMissingRelation(e) || isMissingColumn(e)) {
                    continue
                }
                logger.warn("Failed querying orderbook_data for {}/{}: {}", exchange, symbol, e.message)
            }
        }
        return null
    }

    private fun queryMarketDataQuote(exchange: String, symbol: String): LatestQuote? {
        val candleSql = """
            SELECT symbol, close AS px, time
            FROM market_data
            WHERE exchange = ?
              AND symbol = ?
              AND data_type = 'candle_1m'
            ORDER BY time DESC
            LIMIT 1
        """.trimIndent()

        val tradeSql = """
            SELECT symbol, price AS px, time
            FROM market_data
            WHERE exchange = ?
              AND symbol = ?
              AND data_type = 'trade'
            ORDER BY time DESC
            LIMIT 1
        """.trimIndent()

        fun fromSql(sql: String): LatestQuote? {
            for (quoteSource in quoteDataSources()) {
                try {
                    quoteSource.connection.use { conn ->
                        conn.prepareStatement(sql).use { stmt ->
                            stmt.setString(1, exchange)
                            stmt.setString(2, symbol)
                            stmt.executeQuery().use { rs ->
                                if (!rs.next()) return@use
                                val last = rs.getBigDecimal("px")?.toDouble() ?: return@use
                                if (last <= 0.0) return@use
                                val syntheticHalfSpread = last * 0.0002 // 2 bps proxy
                                val bid = (last - syntheticHalfSpread).coerceAtLeast(0.0)
                                val ask = last + syntheticHalfSpread
                                val ts = rs.getTimestamp("time")?.toInstant() ?: Instant.now()
                                return LatestQuote(
                                    exchange = exchange,
                                    symbol = rs.getString("symbol"),
                                    bid = bid,
                                    ask = ask,
                                    last = last,
                                    timestamp = ts,
                                    source = if (sql == candleSql) "market_data:candle_1m" else "market_data:trade"
                                )
                            }
                        }
                    }
                } catch (e: SQLException) {
                    if (isMissingRelation(e)) {
                        warnMissingRelationOnce("market_data", e)
                        continue
                    }
                    logger.warn("Failed querying market_data for {}/{}: {}", exchange, symbol, e.message)
                }
            }
            return null
        }

        return fromSql(candleSql) ?: fromSql(tradeSql)
    }

    private fun isMissingRelation(e: SQLException): Boolean {
        return e.sqlState == "42P01" || (e.message?.contains("does not exist", ignoreCase = true) == true)
    }

    private fun ensureStrategyAnalyticsSchema(source: HikariDataSource): Boolean {
        val key = source.jdbcUrl.ifBlank { source.poolName ?: "unknown-pool" }
        if (analyticsSchemaEnsured.contains(key)) {
            return true
        }

        val backtestTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_backtest_runs (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                start_time TIMESTAMPTZ NOT NULL,
                end_time TIMESTAMPTZ NOT NULL,
                trades INTEGER NOT NULL DEFAULT 0,
                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                notes TEXT,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val latencyTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_latency_metrics (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                decision_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_ack_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_fill_ms DOUBLE PRECISION,
                p50_roundtrip_ms DOUBLE PRECISION,
                p95_roundtrip_ms DOUBLE PRECISION,
                p99_roundtrip_ms DOUBLE PRECISION,
                jitter_ms DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val costTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_execution_costs (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                fee_tier TEXT NOT NULL DEFAULT 'retail',
                fee_tier_adjustment_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                maker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                taker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                spread_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                slippage_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                impact_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                adverse_selection_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                funding_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                basis_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                total_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                edge_after_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                estimated_fee_usd DOUBLE PRECISION,
                estimated_cost_usd DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val costColumnsRepairSql = listOf(
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS fee_tier TEXT NOT NULL DEFAULT 'retail'",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS fee_tier_adjustment_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS maker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS taker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS funding_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS basis_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS edge_after_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_execution_costs ADD COLUMN IF NOT EXISTS estimated_fee_usd DOUBLE PRECISION"
        )
        val walkForwardTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_walkforward_runs (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                train_start TIMESTAMPTZ NOT NULL,
                train_end TIMESTAMPTZ NOT NULL,
                test_start TIMESTAMPTZ NOT NULL,
                test_end TIMESTAMPTZ NOT NULL,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val sensitivityTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_sensitivity_sweeps (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                parameter_name TEXT NOT NULL,
                parameter_value TEXT NOT NULL,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val driftTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_live_backtest_drift (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                live_edge_bps DOUBLE PRECISION,
                backtest_edge_bps DOUBLE PRECISION,
                fill_quality_delta_bps DOUBLE PRECISION,
                slippage_drift_bps DOUBLE PRECISION,
                latency_drift_ms DOUBLE PRECISION,
                drift_score DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val universeProfilesTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_universe_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'research',
                timeframe TEXT NOT NULL,
                candidate_symbols INTEGER NOT NULL DEFAULT 0,
                selected_symbols INTEGER NOT NULL DEFAULT 0,
                benchmark_symbols INTEGER NOT NULL DEFAULT 0,
                candidate_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_tradable_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_observed_ratio DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_depth_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_avg_volume_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_observed_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                candidate_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                selected_tradable_execution_share DOUBLE PRECISION NOT NULL DEFAULT 0,
                deep_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                core_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                tradable_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                fragile_liquidity_symbols INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val portfolioProfilesTableSql = """
            CREATE TABLE IF NOT EXISTS strategy_portfolio_profiles (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                strategy_kind TEXT NOT NULL,
                stage TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_concurrent_positions INTEGER NOT NULL DEFAULT 0,
                max_concurrent_longs INTEGER NOT NULL DEFAULT 0,
                max_concurrent_shorts INTEGER NOT NULL DEFAULT 0,
                avg_concurrent_positions DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_longs DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_concurrent_shorts DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_gross_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_net_exposure_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0,
                avg_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                max_capacity_utilization DOUBLE PRECISION NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0,
                candidate_entries INTEGER NOT NULL DEFAULT 0,
                accepted_entries INTEGER NOT NULL DEFAULT 0,
                rejected_open_symbol INTEGER NOT NULL DEFAULT 0,
                rejected_gross_limit INTEGER NOT NULL DEFAULT 0,
                rejected_long_limit INTEGER NOT NULL DEFAULT 0,
                rejected_short_limit INTEGER NOT NULL DEFAULT 0,
                rejected_net_limit INTEGER NOT NULL DEFAULT 0,
                rejected_beta_limit INTEGER NOT NULL DEFAULT 0,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        val indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS idx_strategy_backtest_runs_run_at ON strategy_backtest_runs(run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_backtest_runs_symbol_time ON strategy_backtest_runs(symbol, timeframe, run_at DESC)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_runs_dedupe ON strategy_backtest_runs(strategy_name, symbol, timeframe, start_time, end_time)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_latency_metrics_time ON strategy_latency_metrics(observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_latency_metrics_strategy_time ON strategy_latency_metrics(strategy_name, observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time ON strategy_execution_costs(observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time ON strategy_execution_costs(strategy_name, observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_runs_time ON strategy_walkforward_runs(run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_walkforward_runs_strategy_time ON strategy_walkforward_runs(strategy_name, run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_sweeps_time ON strategy_sensitivity_sweeps(run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_sensitivity_sweeps_strategy_time ON strategy_sensitivity_sweeps(strategy_name, run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_live_backtest_drift_time ON strategy_live_backtest_drift(observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_live_backtest_drift_strategy_time ON strategy_live_backtest_drift(strategy_name, observed_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_run_at ON strategy_universe_profiles(run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_strategy_time ON strategy_universe_profiles(strategy_name, run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_universe_profiles_stage_time ON strategy_universe_profiles(stage, run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_run_at ON strategy_portfolio_profiles(run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_strategy_time ON strategy_portfolio_profiles(strategy_name, run_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_strategy_portfolio_profiles_stage_time ON strategy_portfolio_profiles(stage, run_at DESC)"
        )
        val profileColumnsRepairSql = listOf(
            "ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS candidate_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_universe_profiles ADD COLUMN IF NOT EXISTS selected_median_spread_bps DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_positions INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_longs INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_concurrent_shorts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_net_exposure_fraction DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_btc DOUBLE PRECISION NOT NULL DEFAULT 0",
            "ALTER TABLE strategy_portfolio_profiles ADD COLUMN IF NOT EXISTS policy_max_abs_beta_eth DOUBLE PRECISION NOT NULL DEFAULT 0"
        )

        return try {
            source.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(backtestTableSql)
                    stmt.execute(latencyTableSql)
                    stmt.execute(costTableSql)
                    costColumnsRepairSql.forEach(stmt::execute)
                    stmt.execute(walkForwardTableSql)
                    stmt.execute(sensitivityTableSql)
                    stmt.execute(driftTableSql)
                    stmt.execute(universeProfilesTableSql)
                    stmt.execute(portfolioProfilesTableSql)
                    profileColumnsRepairSql.forEach(stmt::execute)
                    indexSql.forEach(stmt::execute)
                }
            }
            analyticsSchemaEnsured.add(key)
            logger.info("Ensured strategy analytics schema on datasource {}", key)
            true
        } catch (e: SQLException) {
            logger.warn("Failed ensuring strategy analytics schema on datasource {}: {}", key, e.message)
            false
        }
    }

    private fun isMissingColumn(e: SQLException): Boolean {
        return e.sqlState == "42703" || (e.message?.contains("column", ignoreCase = true) == true &&
            e.message?.contains("does not exist", ignoreCase = true) == true)
    }

    private fun setNullableDouble(stmt: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value == null) {
            stmt.setNull(index, Types.DOUBLE)
        } else {
            stmt.setDouble(index, value)
        }
    }

    private fun StrategyExecutionBaseline.hasAnyValue(): Boolean {
        return avgSlippageBps != null ||
            avgSubmitToFillMs != null ||
            avgFillRatio != null ||
            avgTotalCostBps != null ||
            backtestEdgeBps != null
    }

    private fun warnMissingRelationOnce(table: String, e: SQLException) {
        if (missingTableWarnings.add(table)) {
            logger.warn(
                "Table '{}' is unavailable; falling back. sqlState={}, error={}",
                table,
                e.sqlState,
                e.message
            )
        }
    }

    private fun symbolCandidates(symbol: String): List<String> {
        val raw = symbol.trim().uppercase()
        if (raw.isBlank()) return emptyList()

        val base = raw.substringBefore("-").substringBefore("/")
        val stripPerp = raw.removeSuffix("-PERP")
        val stripQuoteSuffixes = listOf("USDT", "USD", "PERP")
            .fold(stripPerp) { acc, suffix ->
                if (acc.endsWith(suffix) && acc.length > suffix.length) {
                    acc.removeSuffix(suffix)
                } else {
                    acc
                }
            }

        return listOf(
            raw,
            stripPerp,
            base,
            stripQuoteSuffixes
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Returns a schema overview used by health and integration checks.
     * Includes compatibility aliases expected by the test-suite.
     */
    fun schemaOverview(): SchemaOverview = transaction {
        val tableNames = mutableListOf<String>()
        val query = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            ORDER BY table_name
        """.trimIndent()

        exec(query) { rs ->
            while (rs.next()) {
                tableNames += rs.getString("table_name")
            }
        }

        val compatibilityAliases = buildSet {
            if ("evm_nonces" in tableNames) add("nonces")
            if ("tx_audit_log" in tableNames || "evm_pending_txs" in tableNames) add("transactions")
            if ("rate_limit_windows" in tableNames) add("rate_limits")
        }

        SchemaOverview(
            tables = (tableNames + compatibilityAliases).distinct().sorted(),
            rawTables = tableNames.sorted(),
            aliases = compatibilityAliases.sorted()
        )
    }

    private fun java.sql.ResultSet.toRiskPolicyRecord(): RiskPolicyRecord = RiskPolicyRecord(
        id = getObject("id", UUID::class.java),
        username = getString("username"),
        walletAddress = getString("wallet_address"),
        version = getInt("version"),
        status = getString("status"),
        policyJson = getString("policy_json"),
        createdBy = getString("created_by"),
        createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.now(),
        activatedAt = getTimestamp("activated_at")?.toInstant(),
        activatedByWallet = getString("activated_by_wallet"),
        activationSignature = getString("activation_signature"),
        activationNonce = getString("activation_nonce"),
        activationMessage = getString("activation_message"),
        isBootstrap = getBoolean("is_bootstrap")
    )

    private fun java.sql.ResultSet.toRiskActivationChallengeRecord(): RiskActivationChallengeRecord =
        RiskActivationChallengeRecord(
            id = getLong("id"),
            policyId = getObject("policy_id", UUID::class.java),
            username = getString("username"),
            walletAddress = getString("wallet_address"),
            nonce = getString("nonce"),
            challengeMessage = getString("challenge_message"),
            expiresAt = getTimestamp("expires_at")?.toInstant() ?: Instant.now(),
            consumedAt = getTimestamp("consumed_at")?.toInstant(),
            createdAt = getTimestamp("created_at")?.toInstant() ?: Instant.now()
        )

    private fun java.sql.ResultSet.toRiskAccountStateRecord(): RiskAccountStateRecord =
        RiskAccountStateRecord(
            username = getString("username"),
            accountEquityUsd = getBigDecimal("account_equity_usd") ?: BigDecimal.ZERO,
            highWaterMarkUsd = getBigDecimal("high_water_mark_usd") ?: BigDecimal.ZERO,
            realizedPnlUsd = getBigDecimal("realized_pnl_usd") ?: BigDecimal.ZERO,
            unrealizedPnlUsd = getBigDecimal("unrealized_pnl_usd") ?: BigDecimal.ZERO,
            dailyRealizedPnlUsd = getBigDecimal("daily_realized_pnl_usd") ?: BigDecimal.ZERO,
            dailyUnrealizedPnlUsd = getBigDecimal("daily_unrealized_pnl_usd") ?: BigDecimal.ZERO,
            openExposureUsd = getBigDecimal("open_exposure_usd") ?: BigDecimal.ZERO,
            sentimentScore = getDoubleOrNull("sentiment_score"),
            sentimentConfidence = getDoubleOrNull("sentiment_confidence"),
            riskTier = getString("risk_tier"),
            tierReason = getString("tier_reason"),
            updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.now()
        )

    private fun java.sql.ResultSet.toRiskKillSwitchStateRecord(): RiskKillSwitchStateRecord =
        RiskKillSwitchStateRecord(
            username = getString("username"),
            engaged = getBoolean("engaged"),
            reason = getString("reason"),
            engagedAt = getTimestamp("engaged_at")?.toInstant(),
            engagedBy = getString("engaged_by"),
            manualAckRequired = getBoolean("manual_ack_required"),
            acknowledgedAt = getTimestamp("acknowledged_at")?.toInstant(),
            acknowledgedBy = getString("acknowledged_by"),
            ackNote = getString("ack_note"),
            updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.now()
        )

    private fun java.sql.ResultSet.getDoubleOrNull(column: String): Double? {
        val value = getDouble(column)
        return if (wasNull()) null else value
    }

    fun close() {
        marketDataSource?.close()
        marketDataSource = null
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}

internal fun resolveHyperliquidQuoteExchange(
    explicitExchange: String?,
    mainnetFlag: String?
): String? {
    val explicit = explicitExchange
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
    if (explicit != null) return explicit

    val normalizedMainnetFlag = mainnetFlag
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val mainnet = parseBooleanFlag(normalizedMainnetFlag, defaultValue = false)
    return if (mainnet) "hyperliquid_mainnet" else "hyperliquid_testnet"
}

internal fun resolveHyperliquidQuoteExchangeForExecutionMode(
    requestedExecutionMode: String?,
    legacyQuoteExchange: String?,
    forwardPaperExchange: String?,
    testnetExchange: String?,
    mainnetExchange: String?,
    mainnetFlag: String?
): String? {
    fun normalize(raw: String?): String? {
        return raw
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    val normalizedMode = normalize(requestedExecutionMode)
    val legacy = normalize(legacyQuoteExchange)
    return when (normalizedMode) {
        "forward_paper" -> normalize(forwardPaperExchange) ?: "hyperliquid_mainnet"
        "testnet_live" -> normalize(testnetExchange) ?: "hyperliquid_testnet"
        "mainnet_live" -> normalize(mainnetExchange) ?: "hyperliquid_mainnet"
        else -> legacy ?: resolveHyperliquidQuoteExchange(explicitExchange = null, mainnetFlag = mainnetFlag)
    }
}

internal fun resolveHyperliquidQuoteExchangeCandidates(
    requestedExecutionMode: String?,
    legacyQuoteExchange: String?,
    forwardPaperExchange: String?,
    testnetExchange: String?,
    mainnetExchange: String?,
    mainnetFlag: String?,
    allowCanonicalFallback: Boolean
): List<String> {
    fun normalize(raw: String?): String? {
        return raw
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    fun addIfPresent(target: MutableList<String>, raw: String?) {
        normalize(raw)?.let(target::add)
    }

    val normalizedMode = normalize(requestedExecutionMode)
    val legacy = normalize(legacyQuoteExchange)
    val candidates = mutableListOf<String>()

    when (normalizedMode) {
        "forward_paper" -> {
            addIfPresent(candidates, forwardPaperExchange)
            addIfPresent(candidates, mainnetExchange)
        }
        "testnet_live" -> {
            addIfPresent(candidates, testnetExchange)
            addIfPresent(candidates, mainnetExchange)
            addIfPresent(candidates, forwardPaperExchange)
        }
        "mainnet_live" -> {
            addIfPresent(candidates, mainnetExchange)
            addIfPresent(candidates, forwardPaperExchange)
        }
        else -> addIfPresent(
            candidates,
            legacy ?: resolveHyperliquidQuoteExchange(explicitExchange = null, mainnetFlag = mainnetFlag)
        )
    }

    if (allowCanonicalFallback || candidates.isEmpty()) {
        candidates += "hyperliquid"
    }

    return candidates.distinct()
}

internal fun parseBooleanFlag(raw: String?, defaultValue: Boolean): Boolean {
    val normalized = raw?.trim()?.lowercase() ?: return defaultValue
    return when (normalized) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}
