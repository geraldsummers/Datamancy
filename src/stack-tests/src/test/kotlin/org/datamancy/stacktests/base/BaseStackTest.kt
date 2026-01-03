package org.datamancy.stacktests.base

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for all stack integration tests.
 * Provides common HTTP client and service URL configuration.
 */
abstract class BaseStackTest {

    protected lateinit var client: HttpClient

    // Service URLs - localhost ports exposed by docker-compose.test-ports.yml
    protected val localhostPorts = ServicePorts()

    @BeforeEach
    open fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = true
                })
            }
            expectSuccess = false
            engine {
                requestTimeout = 90_000
                endpoint {
                    connectTimeout = 30_000
                    socketTimeout = 30_000
                    keepAliveTime = 5000
                    connectAttempts = 3
                }
                maxConnectionsCount = 1000
                pipelining = false
            }
        }
    }

    @AfterEach
    open fun teardown() {
        client.close()
    }

    /**
     * Helper to get configuration value from system properties or environment variables.
     * Checks system properties first (set by Gradle), then falls back to environment variables.
     */
    protected fun getConfig(key: String, default: String = ""): String {
        return System.getProperty(key) ?: System.getenv(key) ?: default
    }

    /**
     * Service port mappings for localhost testing.
     */
    data class ServicePorts(
        // Infrastructure
        val caddy: Int = 80,
        val ldap: Int = 10389,
        val valkey: Int = 6379,
        val authelia: Int = 19091,

        // Databases
        val postgres: Int = 15432,
        val mariadb: Int = 13306,
        val clickhouse: Int = 18123,
        val qdrant: Int = 16333,

        // Mail
        val mailserverSmtp: Int = 587,
        val mailserverImap: Int = 993,
        val roundcube: Int = 10083,

        // Applications
        val grafana: Int = 13001,
        val openWebui: Int = 18081,
        val vaultwarden: Int = 10081,
        val planka: Int = 11337,
        val bookstack: Int = 10080,
        val seafile: Int = 18001,
        val onlyoffice: Int = 10084,
        val synapse: Int = 18008,
        val element: Int = 10082,
        val mastodonWeb: Int = 13003,
        val mastodonStreaming: Int = 14000,
        val homepage: Int = 13002,
        val jupyterhub: Int = 18000,
        val homeassistant: Int = 18124,
        val forgejo: Int = 13000,
        val qbittorrent: Int = 18082,
        val lamAccountManager: Int = 10085,

        // AI/ML Stack
        val vllm: Int = 18002,
        val litellm: Int = 14001,
        val embeddingService: Int = 18080,

        // Data Pipeline
        val dataFetcher: Int = 18095,
        val unifiedIndexer: Int = 18096,
        val searchService: Int = 18098,
        val controlPanel: Int = 18097,

        // Other
        val dockerProxy: Int = 12375,
        val radicale: Int = 15232,
        val kopia: Int = 10087
    ) {
        fun postgresUrl() = "jdbc:postgresql://localhost:$postgres"
        fun mariadbUrl() = "jdbc:mysql://localhost:$mariadb"
        fun clickhouseUrl() = "http://localhost:$clickhouse"
        fun qdrantUrl() = "http://localhost:$qdrant"
        fun valkeyUrl() = "redis://localhost:$valkey"

        fun httpUrl(port: Int) = "http://localhost:$port"
        fun httpsUrl(port: Int) = "https://localhost:$port"
    }
}
