package org.datamancy.controlpanel.services

import org.datamancy.controlpanel.models.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

open class DatabaseService(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    private fun getConnection(): Connection {
        return DriverManager.getConnection(jdbcUrl, username, password)
    }

    open fun ensureSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // Create config table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS control_panel_config (
                        key VARCHAR(255) PRIMARY KEY,
                        value JSONB NOT NULL,
                        updated_at TIMESTAMP DEFAULT NOW(),
                        updated_by VARCHAR(255)
                    )
                """)

                // Create source status cache table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS source_status (
                        source_name VARCHAR(255) PRIMARY KEY,
                        enabled BOOLEAN DEFAULT true,
                        schedule_interval VARCHAR(50) DEFAULT '6h',
                        last_fetch_at TIMESTAMP,
                        last_fetch_status VARCHAR(50),
                        items_new INTEGER DEFAULT 0,
                        items_updated INTEGER DEFAULT 0,
                        items_failed INTEGER DEFAULT 0,
                        next_scheduled_at TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT NOW()
                    )
                """)

                // Create system events table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS system_events (
                        event_id SERIAL PRIMARY KEY,
                        event_type VARCHAR(100) NOT NULL,
                        service_name VARCHAR(100) NOT NULL,
                        message TEXT,
                        metadata JSONB,
                        occurred_at TIMESTAMP DEFAULT NOW()
                    )
                """)

                // Initialize default sources if not exist
                val sources = listOf("wiki", "rss", "market_data", "economic", "legal", "docs", "search", "torrents", "agent_functions")
                sources.forEach { source ->
                    stmt.execute("""
                        INSERT INTO source_status (source_name, enabled, schedule_interval)
                        VALUES ('$source', true, '6h')
                        ON CONFLICT (source_name) DO NOTHING
                    """)
                }
            }
        }
    }

    open fun getSourceConfigs(): List<SourceConfig> {
        val configs = mutableListOf<SourceConfig>()
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("""
                        SELECT
                            source_name,
                            enabled,
                            schedule_interval,
                            last_fetch_at,
                            items_new,
                            items_updated,
                            items_failed,
                            next_scheduled_at
                        FROM source_status
                        ORDER BY source_name
                    """)
                    while (rs.next()) {
                        configs.add(SourceConfig(
                            name = rs.getString("source_name"),
                            enabled = rs.getBoolean("enabled"),
                            scheduleInterval = rs.getString("schedule_interval") ?: "6h",
                            lastFetch = rs.getTimestamp("last_fetch_at")?.toInstant()?.toString(),
                            itemsNew = rs.getInt("items_new"),
                            itemsUpdated = rs.getInt("items_updated"),
                            itemsFailed = rs.getInt("items_failed"),
                            nextScheduled = rs.getTimestamp("next_scheduled_at")?.toInstant()?.toString()
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // If query fails, ensure schema exists and retry
            try {
                ensureSchema()
                return getSourceConfigs()
            } catch (retryException: Exception) {
                // Return empty list if still failing
                return emptyList()
            }
        }
        return configs
    }

    open fun updateSourceConfig(sourceName: String, update: SourceConfigUpdate): Boolean {
        getConnection().use { conn ->
            val updates = mutableListOf<String>()
            update.enabled?.let { updates.add("enabled = $it") }
            update.scheduleInterval?.let { updates.add("schedule_interval = '$it'") }
            updates.add("updated_at = NOW()")

            if (updates.isEmpty()) return false

            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    UPDATE source_status
                    SET ${updates.joinToString(", ")}
                    WHERE source_name = '$sourceName'
                """)
            }
        }
        return true
    }

    open fun getStorageStats(): StorageStats {
        val pgStats = getPostgresStats()
        return StorageStats(
            postgres = pgStats,
            clickhouse = DatabaseStats(0.0, emptyMap()), // TODO: Query ClickHouse
            qdrant = VectorStats(0.0, emptyMap()) // TODO: Query QDrant
        )
    }

    private fun getPostgresStats(): DatabaseStats {
        val tables = mutableMapOf<String, TableStats>()
        var totalSize = 0.0

        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT
                        schemaname,
                        tablename,
                        pg_total_relation_size(schemaname||'.'||tablename) as size_bytes,
                        n_live_tup as row_count
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'public'
                    ORDER BY size_bytes DESC
                    LIMIT 20
                """)
                while (rs.next()) {
                    val tableName = rs.getString("tablename")
                    val sizeBytes = rs.getLong("size_bytes")
                    val sizeGB = sizeBytes / (1024.0 * 1024.0 * 1024.0)
                    totalSize += sizeGB

                    tables[tableName] = TableStats(
                        rows = rs.getLong("row_count"),
                        sizeGB = sizeGB
                    )
                }
            }
        }

        return DatabaseStats(totalSize, tables)
    }

    open fun getRecentEvents(limit: Int = 100): List<SystemEvent> {
        val events = mutableListOf<SystemEvent>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT event_type, service_name, message, occurred_at
                FROM system_events
                ORDER BY occurred_at DESC
                LIMIT ?
            """).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    events.add(SystemEvent(
                        eventType = rs.getString("event_type"),
                        serviceName = rs.getString("service_name"),
                        message = rs.getString("message") ?: "",
                        occurredAt = rs.getTimestamp("occurred_at").toInstant().toString()
                    ))
                }
            }
        }
        return events
    }

    open fun logEvent(eventType: String, serviceName: String, message: String, metadata: Map<String, String> = emptyMap()) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO system_events (event_type, service_name, message, metadata)
                VALUES (?, ?, ?, ?::jsonb)
            """).use { stmt ->
                stmt.setString(1, eventType)
                stmt.setString(2, serviceName)
                stmt.setString(3, message)
                stmt.setString(4, if (metadata.isEmpty()) "{}" else metadata.entries.joinToString(",", "{", "}") { """"${it.key}":"${it.value}"""" })
                stmt.executeUpdate()
            }
        }
    }

    fun close() {
        // Connection pooling would go here in production
    }
}
