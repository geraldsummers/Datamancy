package org.datamancy.pipeline.trading

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp

private val logger = KotlinLogging.logger {}

class RssSentimentSignalStore(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String
) : AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val isPostgres = jdbcUrl.startsWith("jdbc:postgresql")

    init {
        ensureTableExists()
    }

    fun persistBatch(signals: List<RssSentimentSignal>) {
        if (signals.isEmpty()) return

        try {
            withConnection { connection ->
                connection.autoCommit = false
                val sql = if (isPostgres) {
                    """
                    INSERT INTO rss_sentiment_signals (
                        observed_at, symbol, source, article_title, article_url,
                        sentiment_score, confidence, model_name, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                } else {
                    """
                    INSERT INTO rss_sentiment_signals (
                        observed_at, symbol, source, article_title, article_url,
                        sentiment_score, confidence, model_name, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                }

                connection.prepareStatement(sql).use { statement ->
                    signals.forEach { signal ->
                        statement.setTimestamp(1, Timestamp.from(signal.observedAt))
                        statement.setString(2, signal.symbol)
                        statement.setString(3, signal.source)
                        statement.setString(4, signal.articleTitle)
                        statement.setString(5, signal.articleUrl)
                        statement.setDouble(6, signal.sentimentScore)
                        statement.setDouble(7, signal.confidence)
                        statement.setString(8, signal.modelName)
                        statement.setString(9, json.encodeToString(signal.metadata))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            }

            logger.debug { "Persisted ${signals.size} RSS sentiment signals (duplicates ignored by DB constraints)" }
        } catch (e: SQLException) {
            logger.error(e) { "RSS sentiment persist failed; keeping ingestion alive: ${e.message}" }
        }
    }

    private fun ensureTableExists() {
        withConnection { connection ->
            val ddl = if (isPostgres) {
                listOf(
                    """
                    CREATE TABLE IF NOT EXISTS rss_sentiment_signals (
                        id BIGSERIAL PRIMARY KEY,
                        observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        symbol TEXT NOT NULL,
                        source TEXT NOT NULL,
                        article_title TEXT,
                        article_url TEXT,
                        sentiment_score DOUBLE PRECISION NOT NULL,
                        confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                        model_name TEXT,
                        metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_rss_sentiment_time ON rss_sentiment_signals (observed_at DESC)",
                    "CREATE INDEX IF NOT EXISTS idx_rss_sentiment_symbol_time ON rss_sentiment_signals (symbol, observed_at DESC)",
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_rss_sentiment_dedupe
                    ON rss_sentiment_signals (
                        source,
                        symbol,
                        COALESCE(article_url, ''),
                        COALESCE(article_title, ''),
                        observed_at
                    )
                    """.trimIndent()
                )
            } else {
                listOf(
                    """
                    CREATE TABLE IF NOT EXISTS rss_sentiment_signals (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        observed_at TIMESTAMP NOT NULL,
                        symbol VARCHAR(32) NOT NULL,
                        source VARCHAR(255) NOT NULL,
                        article_title CLOB,
                        article_url CLOB,
                        sentiment_score DOUBLE NOT NULL,
                        confidence DOUBLE NOT NULL DEFAULT 0.0,
                        model_name VARCHAR(255),
                        metadata CLOB NOT NULL
                    )
                    """.trimIndent()
                )
            }

            connection.createStatement().use { statement ->
                ddl.forEach { sql ->
                    try {
                        statement.execute(sql)
                    } catch (e: SQLException) {
                        if (e.sqlState == "42501" || e.message?.contains("must be owner of table", ignoreCase = true) == true) {
                            logger.warn { "Skipping RSS sentiment DDL due to limited DB privileges: ${e.message}" }
                        } else {
                            throw e
                        }
                    }
                }
            }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            return block(connection)
        }
    }

    override fun close() = Unit
}
