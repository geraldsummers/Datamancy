package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class DatabasesConfig(
    val postgres: PostgresConfig = PostgresConfig(),
    val mariadb: MariaDbConfig? = null,
    val clickhouse: ClickHouseConfig? = null,
    val qdrant: QdrantConfig? = null,
    val valkey: ValkeyConfig = ValkeyConfig()
)

@Serializable
data class PostgresConfig(
    val dbs: List<PostgresDbEntry> = emptyList(),
    val initAdminUserEnvKey: String = "POSTGRES_SUPERUSER_PASSWORD"
)

@Serializable
data class PostgresDbEntry(
    val name: String,
    val username: String,
    val passwordEnvKey: String,
    val schemas: List<String> = listOf("public")
)

// Placeholders for optional DBs (not fully implemented in MVP)
@Serializable data class MariaDbConfig(val placeholder: Boolean = false)
@Serializable data class ClickHouseConfig(val placeholder: Boolean = false)
@Serializable data class QdrantConfig(val placeholder: Boolean = false)
@Serializable data class ValkeyConfig(val placeholder: Boolean = false)
