package org.datamancy.txgateway.services

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

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
    private val password: String = System.getenv("POSTGRES_PASSWORD")?.also {
        require(it.isNotBlank()) { "POSTGRES_PASSWORD environment variable is empty" }
    } ?: error("POSTGRES_PASSWORD environment variable not set")
) {
    private val logger = LoggerFactory.getLogger(DatabaseService::class.java)
    private lateinit var dataSource: HikariDataSource

    fun init() {
        logger.info("Initializing database connection to $host:$port/$database as user $user (password: ${password.length} chars)")
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            username = user
            this.password = password
            // Explicitly add password to dataSourceProperties as workaround for JDBC driver issue
            addDataSourceProperty("password", password)
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
        }

        dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        // Create tables
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TxAuditLog,
                EvmNonces,
                EvmPendingTxs,
                RateLimitWindows
            )
        }

        logger.info("Database initialized successfully")
    }

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
            val existing = EvmNonces.select {
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
            val count = RateLimitWindows.select {
                (RateLimitWindows.username eq username) and
                        (RateLimitWindows.windowStart greater oneHourAgo)
            }.sumOf { it[RateLimitWindows.txCount] }

            if (count >= maxTxPerHour) {
                false
            } else {
                // Increment counter
                val windowStart = Instant.ofEpochSecond(now.epochSecond / 3600 * 3600)
                val existing = RateLimitWindows.select {
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

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}
