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
    val apiKey: String? = null,
    // Infrastructure
    val caddy: String,
    val authelia: String,
    // User Interfaces
    val openWebUI: String,
    val jupyterhub: String,
    // Communication
    val mailserver: String,
    val synapse: String,
    val element: String,
    // Collaboration
    val mastodon: String,
    val mastodonStreaming: String,
    val roundcube: String,
    // Productivity
    val forgejo: String,
    val planka: String,
    // File Management
    val seafile: String,
    val onlyoffice: String,
    // Security
    val vaultwarden: String,
    // Monitoring
    val prometheus: String,
    val grafana: String,
    // Backup
    val kopia: String
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
                password = env("POSTGRES_PASSWORD") ?: env("DATAMANCY_SERVICE_PASSWORD") ?: ""
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
            userContext = env("TEST_USER_CONTEXT") ?: "test-agent-user",
            // Infrastructure
            caddy = env("CADDY_URL") ?: "http://caddy:80",
            authelia = env("AUTHELIA_URL") ?: "http://authelia:9091",
            // User Interfaces
            openWebUI = env("OPEN_WEBUI_URL") ?: "http://open-webui:8080",
            jupyterhub = env("JUPYTERHUB_URL") ?: "http://jupyterhub:8000",
            // Communication
            mailserver = env("MAILSERVER_URL") ?: "mailserver:25",
            synapse = env("SYNAPSE_URL") ?: "http://synapse:8008",
            element = env("ELEMENT_URL") ?: "http://element:80",
            // Collaboration
            mastodon = env("MASTODON_URL") ?: "http://mastodon-web:3000",
            mastodonStreaming = env("MASTODON_STREAMING_URL") ?: "http://mastodon-streaming:4000",
            roundcube = env("ROUNDCUBE_URL") ?: "http://roundcube:80",
            // Productivity
            forgejo = env("FORGEJO_URL") ?: "http://forgejo:3000",
            planka = env("PLANKA_URL") ?: "http://planka:1337",
            // File Management
            seafile = env("SEAFILE_URL") ?: "http://seafile:80",
            onlyoffice = env("ONLYOFFICE_URL") ?: "http://onlyoffice:80",
            // Security
            vaultwarden = env("VAULTWARDEN_URL") ?: "http://vaultwarden:80",
            // Monitoring
            prometheus = env("PROMETHEUS_URL") ?: "http://prometheus:9090",
            grafana = env("GRAFANA_URL") ?: "http://grafana:3000",
            // Backup
            kopia = env("KOPIA_URL") ?: "http://kopia:51515"
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
            userContext = "test-agent-user",
            // Infrastructure
            caddy = "http://localhost:80",
            authelia = "http://localhost:9091",
            // User Interfaces
            openWebUI = "http://localhost:8080",
            jupyterhub = "http://localhost:8000",
            // Communication
            mailserver = "localhost:25",
            synapse = "http://localhost:8008",
            element = "http://localhost:8009",
            // Collaboration
            mastodon = "http://localhost:3000",
            mastodonStreaming = "http://localhost:4000",
            roundcube = "http://localhost:8010",
            // Productivity
            forgejo = "http://localhost:3001",
            planka = "http://localhost:1337",
            // File Management
            seafile = "http://localhost:8011",
            onlyoffice = "http://localhost:8012",
            // Security
            vaultwarden = "http://localhost:8013",
            // Monitoring
            prometheus = "http://localhost:9090",
            grafana = "http://localhost:3002",
            // Backup
            kopia = "http://localhost:51515"
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
    val adminPassword: String
    val ldapAdminPassword: String

    data object Container : TestEnvironment {
        override val name = "container"
        override val endpoints = ServiceEndpoints.fromEnvironment()
        override val adminPassword = env("STACK_ADMIN_PASSWORD") ?: ""
        override val ldapAdminPassword = env("LDAP_ADMIN_PASSWORD") ?: ""
    }

    data object Localhost : TestEnvironment {
        override val name = "localhost"
        override val endpoints = ServiceEndpoints.forLocalhost()
        override val adminPassword = env("STACK_ADMIN_PASSWORD") ?: "admin"
        override val ldapAdminPassword = env("LDAP_ADMIN_PASSWORD") ?: "admin"
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
