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

                // Create legal ingestion tracking table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS legal_ingestion_status (
                        jurisdiction VARCHAR(50) PRIMARY KEY,
                        last_sync_at TIMESTAMP,
                        sync_status VARCHAR(50),
                        acts_total INTEGER DEFAULT 0,
                        acts_new INTEGER DEFAULT 0,
                        acts_updated INTEGER DEFAULT 0,
                        acts_repealed INTEGER DEFAULT 0,
                        sections_total INTEGER DEFAULT 0,
                        errors_count INTEGER DEFAULT 0,
                        last_error_message TEXT,
                        metadata JSONB,
                        updated_at TIMESTAMP DEFAULT NOW()
                    )
                """)

                // Create per-act tracking table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS legal_acts_tracking (
                        act_url VARCHAR(500) PRIMARY KEY,
                        jurisdiction VARCHAR(50) NOT NULL,
                        act_title TEXT NOT NULL,
                        year VARCHAR(10),
                        identifier VARCHAR(100),
                        status VARCHAR(50) DEFAULT 'active',
                        sections_count INTEGER DEFAULT 0,
                        last_checked_at TIMESTAMP,
                        last_modified_at TIMESTAMP,
                        content_hash VARCHAR(64),
                        fetch_status VARCHAR(50),
                        error_message TEXT,
                        metadata JSONB,
                        created_at TIMESTAMP DEFAULT NOW(),
                        updated_at TIMESTAMP DEFAULT NOW()
                    )
                """)

                // Index for jurisdiction queries
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_legal_acts_jurisdiction
                    ON legal_acts_tracking(jurisdiction, status)
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
                        relname as tablename,
                        pg_total_relation_size(schemaname||'.'||relname) as size_bytes,
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
        try {
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
        } catch (e: Exception) {
            // If query fails, ensure schema exists and retry
            try {
                ensureSchema()
                return getRecentEvents(limit)
            } catch (retryException: Exception) {
                // Return empty list if still failing
                return emptyList()
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

    open fun getLegalIngestionStatus(): LegalIngestionStatusResponse {
        val jurisdictions = mutableListOf<JurisdictionStatus>()
        var totalActs = 0
        var totalSections = 0
        var totalErrors = 0

        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("""
                        SELECT
                            jurisdiction,
                            last_sync_at,
                            sync_status,
                            acts_total,
                            acts_new,
                            acts_updated,
                            acts_repealed,
                            sections_total,
                            errors_count,
                            last_error_message,
                            updated_at
                        FROM legal_ingestion_status
                        ORDER BY jurisdiction
                    """)
                    while (rs.next()) {
                        val actsTotal = rs.getInt("acts_total")
                        val sectionsTotal = rs.getInt("sections_total")
                        val errorsCount = rs.getInt("errors_count")

                        totalActs += actsTotal
                        totalSections += sectionsTotal
                        totalErrors += errorsCount

                        jurisdictions.add(JurisdictionStatus(
                            jurisdiction = rs.getString("jurisdiction"),
                            lastSyncAt = rs.getTimestamp("last_sync_at")?.toInstant()?.toString(),
                            syncStatus = rs.getString("sync_status") ?: "pending",
                            actsTotal = actsTotal,
                            actsNew = rs.getInt("acts_new"),
                            actsUpdated = rs.getInt("acts_updated"),
                            actsRepealed = rs.getInt("acts_repealed"),
                            sectionsTotal = sectionsTotal,
                            errorsCount = errorsCount,
                            lastErrorMessage = rs.getString("last_error_message")
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // If table doesn't exist yet, ensure schema and return empty
            try {
                ensureSchema()
            } catch (schemaException: Exception) {
                // Ignore
            }
        }

        return LegalIngestionStatusResponse(
            totalActs = totalActs,
            totalSections = totalSections,
            totalErrors = totalErrors,
            jurisdictions = jurisdictions
        )
    }

    open fun updateLegalIngestionStatus(
        jurisdiction: String,
        syncStatus: String,
        actsTotal: Int,
        actsNew: Int,
        actsUpdated: Int,
        actsRepealed: Int,
        sectionsTotal: Int,
        errorsCount: Int,
        lastErrorMessage: String? = null
    ) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO legal_ingestion_status (
                    jurisdiction,
                    last_sync_at,
                    sync_status,
                    acts_total,
                    acts_new,
                    acts_updated,
                    acts_repealed,
                    sections_total,
                    errors_count,
                    last_error_message,
                    updated_at
                ) VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (jurisdiction) DO UPDATE SET
                    last_sync_at = NOW(),
                    sync_status = EXCLUDED.sync_status,
                    acts_total = EXCLUDED.acts_total,
                    acts_new = EXCLUDED.acts_new,
                    acts_updated = EXCLUDED.acts_updated,
                    acts_repealed = EXCLUDED.acts_repealed,
                    sections_total = EXCLUDED.sections_total,
                    errors_count = EXCLUDED.errors_count,
                    last_error_message = EXCLUDED.last_error_message,
                    updated_at = NOW()
            """).use { stmt ->
                stmt.setString(1, jurisdiction)
                stmt.setString(2, syncStatus)
                stmt.setInt(3, actsTotal)
                stmt.setInt(4, actsNew)
                stmt.setInt(5, actsUpdated)
                stmt.setInt(6, actsRepealed)
                stmt.setInt(7, sectionsTotal)
                stmt.setInt(8, errorsCount)
                stmt.setString(9, lastErrorMessage)
                stmt.executeUpdate()
            }
        }
    }

    open fun trackLegalAct(
        actUrl: String,
        jurisdiction: String,
        actTitle: String,
        year: String?,
        identifier: String?,
        status: String,
        sectionsCount: Int,
        contentHash: String?,
        fetchStatus: String,
        errorMessage: String? = null
    ) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO legal_acts_tracking (
                    act_url,
                    jurisdiction,
                    act_title,
                    year,
                    identifier,
                    status,
                    sections_count,
                    last_checked_at,
                    content_hash,
                    fetch_status,
                    error_message,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, NOW())
                ON CONFLICT (act_url) DO UPDATE SET
                    status = EXCLUDED.status,
                    sections_count = EXCLUDED.sections_count,
                    last_checked_at = NOW(),
                    content_hash = EXCLUDED.content_hash,
                    fetch_status = EXCLUDED.fetch_status,
                    error_message = EXCLUDED.error_message,
                    updated_at = NOW()
            """).use { stmt ->
                stmt.setString(1, actUrl)
                stmt.setString(2, jurisdiction)
                stmt.setString(3, actTitle)
                stmt.setString(4, year)
                stmt.setString(5, identifier)
                stmt.setString(6, status)
                stmt.setInt(7, sectionsCount)
                stmt.setString(8, contentHash)
                stmt.setString(9, fetchStatus)
                stmt.setString(10, errorMessage)
                stmt.executeUpdate()
            }
        }
    }

    fun close() {
        // Connection pooling would go here in production
    }
}
