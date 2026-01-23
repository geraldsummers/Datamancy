package org.datamancy.testrunner.framework

import java.io.File

data class ServiceEndpoints(
    val agentToolServer: String,
    val dataFetcher: String,
    val searchService: String,
    val pipeline: String,  // New pipeline service
    val liteLLM: String,
    val bookstack: String,
    val postgres: DatabaseConfig,
    val clickhouse: String,  // Changed to String for HTTP API
    val mariadb: DatabaseConfig? = null,
    val qdrant: String,
    val valkey: String? = null,
    val ldap: String? = null,
    val userContext: String? = null,
    val apiKey: String? = null
) {
    companion object {
        fun fromEnvironment(): ServiceEndpoints = ServiceEndpoints(
            agentToolServer = env("AGENT_TOOL_SERVER_URL") ?: "http://agent-tool-server:8081",
            dataFetcher = env("DATA_FETCHER_URL") ?: "http://data-fetcher:8095",
            searchService = env("SEARCH_SERVICE_URL") ?: "http://search-service:8098",
            pipeline = env("PIPELINE_URL") ?: "http://pipeline:8090",
            liteLLM = env("LITELLM_URL") ?: "http://litellm:4000",
            bookstack = env("BOOKSTACK_URL") ?: "http://bookstack:80",
            postgres = DatabaseConfig(
                host = env("POSTGRES_HOST") ?: "postgres",
                port = env("POSTGRES_PORT")?.toInt() ?: 5432,
                database = env("POSTGRES_DB") ?: "datamancy",
                user = env("POSTGRES_USER") ?: "datamancer",
                password = env("STACK_ADMIN_PASSWORD") ?: ""
            ),
            clickhouse = env("CLICKHOUSE_URL") ?: "http://clickhouse:8123",
            mariadb = DatabaseConfig(
                host = env("MARIADB_HOST") ?: "mariadb",
                port = env("MARIADB_PORT")?.toInt() ?: 3306,
                database = "bookstack",
                user = env("MARIADB_USER") ?: "bookstack",
                password = env("MARIADB_PASSWORD") ?: ""
            ),
            qdrant = env("QDRANT_URL") ?: "http://qdrant:6333",
            valkey = env("VALKEY_URL") ?: "valkey:6379",
            ldap = env("LDAP_URL") ?: "ldap://ldap:389",
            userContext = env("TEST_USER_CONTEXT") ?: "test-agent-user"
        )

        fun forLocalhost(): ServiceEndpoints = ServiceEndpoints(
            agentToolServer = "http://localhost:18091",
            dataFetcher = "http://localhost:18095",
            searchService = "http://localhost:18098",
            pipeline = "http://localhost:18080",
            liteLLM = "http://localhost:14001",
            bookstack = "http://localhost:10080",
            postgres = DatabaseConfig("localhost", 15432, "datamancy", "datamancer", ""),
            clickhouse = "http://localhost:18123",
            mariadb = DatabaseConfig("localhost", 13306, "bookstack", "bookstack", ""),
            qdrant = "http://localhost:16333",
            valkey = "localhost:16379",
            ldap = "ldap://localhost:10389",
            userContext = "test-agent-user"
        )
    }
}

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String
) {
    val jdbcUrl: String
        get() = when {
            port == 5432 -> "jdbc:postgresql://$host:$port/$database"
            port == 3306 -> "jdbc:mariadb://$host:$port/$database"
            else -> "jdbc:clickhouse://$host:$port/$database"
        }
}

sealed interface TestEnvironment {
    val name: String
    val endpoints: ServiceEndpoints

    data object Container : TestEnvironment {
        override val name = "container"
        override val endpoints = ServiceEndpoints.fromEnvironment()
    }

    data object Localhost : TestEnvironment {
        override val name = "localhost"
        override val endpoints = ServiceEndpoints.forLocalhost()
    }

    companion object {
        fun detect(): TestEnvironment = when {
            File("/.dockerenv").exists() -> Container
            env("TEST_ENV") == "container" -> Container
            else -> Localhost
        }
    }
}

private fun env(key: String): String? = System.getenv(key)
