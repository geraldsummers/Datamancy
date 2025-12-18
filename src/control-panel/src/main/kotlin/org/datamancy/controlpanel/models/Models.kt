package org.datamancy.controlpanel.models

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class SourceConfig(
    val name: String,
    val enabled: Boolean,
    val scheduleInterval: String,
    val lastFetch: String?,
    val itemsNew: Int,
    val itemsUpdated: Int,
    val itemsFailed: Int,
    val nextScheduled: String?
)

@Serializable
data class SourceConfigUpdate(
    val enabled: Boolean? = null,
    val scheduleInterval: String? = null
)

@Serializable
data class IndexingJob(
    val jobId: String,
    val collectionName: String,
    val status: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val totalPages: Int,
    val indexedPages: Int,
    val failedPages: Int,
    val currentPageId: Int?,
    val errorMessage: String?
)

@Serializable
data class StorageStats(
    val postgres: DatabaseStats,
    val clickhouse: DatabaseStats,
    val qdrant: VectorStats
)

@Serializable
data class DatabaseStats(
    val sizeGB: Double,
    val tables: Map<String, TableStats>
)

@Serializable
data class TableStats(
    val rows: Long,
    val sizeGB: Double
)

@Serializable
data class VectorStats(
    val sizeGB: Double,
    val collections: Map<String, CollectionStats>
)

@Serializable
data class CollectionStats(
    val vectors: Long,
    val sizeGB: Double
)

@Serializable
data class LogEntry(
    val timestamp: String,
    val level: String,
    val service: String,
    val message: String
)

@Serializable
data class SystemEvent(
    val eventType: String,
    val serviceName: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
    val occurredAt: String
)

@Serializable
data class ScheduleInfo(
    val source: String,
    val interval: String,
    val nextScheduled: String?,
    val enabled: Boolean
)

@Serializable
data class ServiceInfo(
    val name: String,
    val hasLogs: Boolean
)
