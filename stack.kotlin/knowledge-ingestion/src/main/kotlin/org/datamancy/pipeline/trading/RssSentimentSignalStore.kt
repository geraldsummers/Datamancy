package org.datamancy.pipeline.trading

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

private object RssSentimentSignalsTable : Table("rss_sentiment_signals") {
    val id = long("id").autoIncrement()
    val observedAt = timestamp("observed_at")
    val symbol = varchar("symbol", 64)
    val sourceName = varchar("source", 255)
    val articleTitle = text("article_title").default("")
    val articleUrl = text("article_url").default("")
    val sentimentScore = double("sentiment_score")
    val confidence = double("confidence").default(0.0)
    val sentimentLabel = varchar("sentiment_label", 32).default("neutral")
    val provider = varchar("provider", 255).nullable()
    val explanation = text("explanation").nullable()
    val modelName = varchar("model_name", 255)
    val metadata = text("metadata").default("{}")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_rss_sentiment_time", false, observedAt)
        index("idx_rss_sentiment_symbol_time", false, symbol, observedAt)
        index("idx_rss_sentiment_source_time", false, sourceName, observedAt)
        index("idx_rss_sentiment_label_time", false, sentimentLabel, observedAt)
        index("idx_rss_sentiment_dedupe", true, sourceName, symbol, articleUrl, articleTitle, observedAt)
    }
}

class RssSentimentSignalStore(
    jdbcUrl: String,
    user: String,
    password: String
) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val database = Database.connect(
        url = jdbcUrl,
        driver = resolveDriver(jdbcUrl),
        user = user,
        password = password
    )

    init {
        ensureTableExists()
    }

    fun persistBatch(signals: List<RssSentimentSignal>) {
        if (signals.isEmpty()) return

        try {
            transaction(database) {
                signals.forEach { signal ->
                    RssSentimentSignalsTable.insertIgnore { row ->
                        row[observedAt] = signal.observedAt
                        row[symbol] = signal.symbol
                        row[sourceName] = signal.source
                        row[articleTitle] = signal.articleTitle.orEmpty()
                        row[articleUrl] = signal.articleUrl.orEmpty()
                        row[sentimentScore] = signal.sentimentScore
                        row[confidence] = signal.confidence
                        row[sentimentLabel] = signal.sentimentLabel
                        row[provider] = signal.provider
                        row[explanation] = signal.explanation
                        row[modelName] = signal.modelName
                        row[metadata] = json.encodeToString(signal.metadata)
                    }
                }
            }

            logger.debug { "Persisted ${signals.size} RSS sentiment signals (duplicates ignored by DB constraints)" }
        } catch (e: SQLException) {
            logger.error(e) { "RSS sentiment persist failed; keeping ingestion alive: ${e.message}" }
        } catch (e: Exception) {
            val sqlException = findSqlException(e)
            if (sqlException != null) {
                logger.error(sqlException) { "RSS sentiment persist failed; keeping ingestion alive: ${sqlException.message}" }
            } else {
                logger.error(e) { "RSS sentiment persist failed; keeping ingestion alive: ${e.message}" }
            }
        }
    }

    private fun ensureTableExists() {
        try {
            transaction(database) {
                SchemaUtils.createMissingTablesAndColumns(RssSentimentSignalsTable)
            }
        } catch (e: Exception) {
            val sqlException = findSqlException(e)
            if (sqlException != null && isOwnershipPrivilegeError(sqlException)) {
                logger.warn { "Skipping RSS sentiment DDL due to limited DB privileges: ${sqlException.message}" }
            } else {
                throw e
            }
        }
    }

    override fun close() = Unit
}

private fun resolveDriver(jdbcUrl: String): String = when {
    jdbcUrl.startsWith("jdbc:postgresql") -> "org.postgresql.Driver"
    jdbcUrl.startsWith("jdbc:mariadb") -> "org.mariadb.jdbc.Driver"
    else -> error("unsupported JDBC URL for RSS sentiment store: $jdbcUrl")
}

private fun isOwnershipPrivilegeError(exception: SQLException): Boolean {
    return exception.sqlState == "42501" ||
        exception.message?.contains("must be owner of table", ignoreCase = true) == true
}

private fun findSqlException(error: Throwable?): SQLException? {
    var current = error
    while (current != null) {
        if (current is SQLException) {
            return current
        }
        current = current.cause
    }
    return null
}
