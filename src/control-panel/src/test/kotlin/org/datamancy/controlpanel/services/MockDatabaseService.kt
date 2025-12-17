package org.datamancy.controlpanel.services

import org.datamancy.controlpanel.models.*

class MockDatabaseService : DatabaseService("jdbc:h2:mem:test", "sa", "") {

    private val mockSources = mutableListOf(
        SourceConfig("wiki", true, "6h", null, 0, 0, 0, null),
        SourceConfig("rss", true, "1h", null, 0, 0, 0, null),
        SourceConfig("market_data", true, "30m", null, 0, 0, 0, null)
    )

    private val mockEvents = mutableListOf<SystemEvent>()

    override fun ensureSchema() {
        // No-op for mock
    }

    override fun getSourceConfigs(): List<SourceConfig> {
        return mockSources
    }

    override fun updateSourceConfig(sourceName: String, update: SourceConfigUpdate): Boolean {
        val index = mockSources.indexOfFirst { it.name == sourceName }
        if (index == -1) return false

        val current = mockSources[index]
        mockSources[index] = current.copy(
            enabled = update.enabled ?: current.enabled,
            scheduleInterval = update.scheduleInterval ?: current.scheduleInterval
        )
        return true
    }

    override fun getStorageStats(): StorageStats {
        return StorageStats(
            postgres = DatabaseStats(
                sizeGB = 5.5,
                tables = mapOf(
                    "fetch_metadata" to TableStats(1000, 1.2),
                    "source_status" to TableStats(10, 0.01)
                )
            ),
            clickhouse = DatabaseStats(
                sizeGB = 10.0,
                tables = mapOf("market_data" to TableStats(50000, 8.5))
            ),
            qdrant = VectorStats(
                sizeGB = 20.0,
                collections = mapOf("docs" to CollectionStats(10000, 15.0))
            )
        )
    }

    override fun getRecentEvents(limit: Int): List<SystemEvent> {
        return mockEvents.takeLast(limit)
    }

    override fun logEvent(eventType: String, serviceName: String, message: String, metadata: Map<String, String>) {
        mockEvents.add(SystemEvent(
            eventType = eventType,
            serviceName = serviceName,
            message = message,
            metadata = metadata,
            occurredAt = java.time.Instant.now().toString()
        ))
    }
}
