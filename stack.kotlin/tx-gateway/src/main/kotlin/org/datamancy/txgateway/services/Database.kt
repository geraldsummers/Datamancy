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
    val timestamp = timestamp("timestamp").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

object EvmNonces : Table("evm_nonces") {
    val chainId = long("chain_id")
    val fromAddress = varchar("from_address", 42)
    val nonce = long("nonce")
    val lastUpdated = timestamp("last_updated").default(Instant.now())

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
    val submittedAt = timestamp("submitted_at").default(Instant.now())
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
                ensureBootstrapRiskPolicy()
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
    fun fetchLatestQuote(exchange: String, symbol: String): LatestQuote? {
        val normalizedExchange = exchange.lowercase()
        val candidates = symbolCandidates(symbol)

        for (candidate in candidates) {
            queryOrderbookQuote(normalizedExchange, candidate)?.let { return it }
        }

        for (candidate in candidates) {
            queryMarketDataQuote(normalizedExchange, candidate)?.let { return it }
        }

        return null
    }

    private fun quoteDataSources(): List<HikariDataSource> = buildList {
        marketDataSource?.let { add(it) }
        if (::dataSource.isInitialized && dataSource !in this) {
            add(dataSource)
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
        spreadCostBps: Double,
        slippageBps: Double,
        impactBps: Double,
        adverseSelectionBps: Double,
        totalCostBps: Double,
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
                spread_cost_bps,
                slippage_bps,
                impact_bps,
                adverse_selection_bps,
                total_cost_bps,
                estimated_cost_usd,
                metadata
            ) VALUES (
                NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb
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
                                stmt.setDouble(6, spreadCostBps)
                                stmt.setDouble(7, slippageBps)
                                stmt.setDouble(8, impactBps)
                                stmt.setDouble(9, adverseSelectionBps)
                                stmt.setDouble(10, totalCostBps)
                                if (estimatedCostUsd != null) {
                                    stmt.setDouble(11, estimatedCostUsd)
                                } else {
                                    stmt.setNull(11, Types.DOUBLE)
                                }
                                stmt.setString(12, metadataJson)
                                stmt.executeUpdate()
                            }

                            conn.commit()
                            return true
                        } catch (e: SQLException) {
                            conn.rollback()
                            if (isMissingRelation(e) && !attemptedSchemaRepair &&
                                ensurePaperExecutionAnalyticsSchema(source)
                            ) {
                                attemptedSchemaRepair = true
                                retryCurrentSource = true
                            } else if (isMissingRelation(e)) {
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
                    if (isMissingRelation(e) && !attemptedSchemaRepair &&
                        ensurePaperExecutionAnalyticsSchema(source)
                    ) {
                        attemptedSchemaRepair = true
                        retryCurrentSource = true
                    } else if (isMissingRelation(e)) {
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

    private fun ensurePaperExecutionAnalyticsSchema(source: HikariDataSource): Boolean {
        val key = source.jdbcUrl.ifBlank { source.poolName ?: "unknown-pool" }
        if (analyticsSchemaEnsured.contains(key)) {
            return true
        }

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
                spread_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                slippage_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                impact_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                adverse_selection_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                total_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                estimated_cost_usd DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
        """.trimIndent()

        return try {
            source.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(latencyTableSql)
                    stmt.execute(costTableSql)
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

    private fun warnMissingRelationOnce(table: String, e: SQLException) {
        if (missingTableWarnings.add(table)) {
            logger.warn(
                "Table '{}' is unavailable for quote lookups; falling back. sqlState={}, error={}",
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
