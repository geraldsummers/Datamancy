package org.datamancy.config

/**
 * Single source of truth for all service port numbers in the Datamancy stack.
 *
 * These ports are used:
 * - In service Main.kt files (internal container ports)
 * - In test configuration (localhost forwarded ports)
 * - In Docker Compose port mappings
 * - In health check URLs
 *
 * Port Scheme:
 * - Internal ports: Standard service ports (8095-8098, 5432, 8123, etc.)
 * - Test ports: localhost:1XXXX (e.g., 18095 for data-fetcher)
 *
 * When adding a new service, add its ports here to maintain consistency.
 */
object ServicePorts {

    // ========================================================================
    // Datamancy Core Services
    // ========================================================================

    /** Data Fetcher - RSS/HTML/content scraping service */
    object DataFetcher {
        const val INTERNAL = 8095
        const val TEST_LOCALHOST = 18095
    }

    /** Unified Indexer - Document indexing pipeline */
    object DataTransformer {
        const val INTERNAL = 8096
        const val TEST_LOCALHOST = 18096
    }

    /** Control Panel - Administrative dashboard and proxy */
    object ControlPanel {
        const val INTERNAL = 8097
        const val TEST_LOCALHOST = 18097
    }

    /** Search Service - RAG search API */
    object SearchService {
        const val INTERNAL = 8098
        const val TEST_LOCALHOST = 18098
    }

    /** Agent Tool Server - AI agent tooling interface */
    object AgentToolServer {
        const val INTERNAL = 8081
        const val TEST_LOCALHOST = 18081
    }

    /** BookStack Writer - BookStack content writer service */
    object BookStackWriter {
        const val INTERNAL = 8099
        const val TEST_LOCALHOST = 18099
    }

    /** Vector Indexer - Vector embedding and Qdrant indexing service */
    object VectorIndexer {
        const val INTERNAL = 8100
        const val TEST_LOCALHOST = 18100
    }

    /** Embedding Service - Vector embedding generation */
    object EmbeddingService {
        const val INTERNAL = 8080
        const val TEST_LOCALHOST = 18080
    }

    // ========================================================================
    // Database Services
    // ========================================================================

    /** PostgreSQL - Primary relational database */
    object Postgres {
        const val INTERNAL = 5432
        const val TEST_LOCALHOST = 15432
    }

    /** MariaDB - Secondary relational database */
    object MariaDB {
        const val INTERNAL = 3306
        const val TEST_LOCALHOST = 13306
    }

    /** ClickHouse - Analytics database */
    object ClickHouse {
        const val HTTP = 8123
        const val NATIVE = 9000
        const val TEST_HTTP = 18123
        const val TEST_NATIVE = 19000
    }

    /** Qdrant - Vector database */
    object Qdrant {
        const val HTTP = 6333
        const val GRPC = 6334
        const val TEST_HTTP = 16333
        const val TEST_GRPC = 16334
    }

    // ========================================================================
    // Infrastructure Services
    // ========================================================================

    /** Caddy - Reverse proxy and web server */
    object Caddy {
        const val HTTP = 80
        const val HTTPS = 443
        const val ADMIN = 2019
    }

    /** Authelia - Authentication/SSO */
    object Authelia {
        const val INTERNAL = 9091
        const val TEST_LOCALHOST = 19091
    }

    /** LDAP - Directory service */
    object LDAP {
        const val INTERNAL = 389
        const val LDAPS = 636
    }

    /** Valkey (Redis) - In-memory cache */
    object Valkey {
        const val INTERNAL = 6379
    }

    /** Docker Proxy - Protected Docker API */
    object DockerProxy {
        const val INTERNAL = 2375
        const val TEST_LOCALHOST = 12375
    }

    // ========================================================================
    // Application Services
    // ========================================================================

    /** Grafana - Monitoring dashboards */
    object Grafana {
        const val INTERNAL = 3000
        const val TEST_LOCALHOST = 13001
    }

    /** Open WebUI - LLM web interface */
    object OpenWebUI {
        const val INTERNAL = 8080
        const val TEST_LOCALHOST = 18081
    }

    /** Vaultwarden - Password manager */
    object Vaultwarden {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10081
    }

    /** BookStack - Wiki/documentation */
    object BookStack {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10080
    }

    /** Planka - Kanban board */
    object Planka {
        const val INTERNAL = 1337
        const val TEST_LOCALHOST = 11337
    }

    /** Forgejo - Git hosting */
    object Forgejo {
        const val HTTP = 3000
        const val SSH = 222
        const val TEST_HTTP = 13000
        const val TEST_SSH = 10222
    }

    /** Homepage - Dashboard */
    object Homepage {
        const val INTERNAL = 3000
        const val TEST_LOCALHOST = 13002
    }

    /** JupyterHub - Notebook server */
    object JupyterHub {
        const val INTERNAL = 8000
        const val TEST_LOCALHOST = 18000
    }

    /** Home Assistant - Home automation */
    object HomeAssistant {
        const val INTERNAL = 8123
        const val TEST_LOCALHOST = 18124
    }

    /** qBittorrent - Torrent client */
    object QBittorrent {
        const val WEB = 8080
        const val TORRENT = 6881
        const val TEST_WEB = 18082
    }

    // ========================================================================
    // Communication Services
    // ========================================================================

    /** Matrix Synapse - Chat server */
    object Synapse {
        const val HTTP = 8008
        const val FEDERATION = 8448
        const val TEST_HTTP = 18008
    }

    /** Element - Matrix web client */
    object Element {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10082
    }

    /** Mastodon - Social network */
    object Mastodon {
        const val WEB = 3000
        const val STREAMING = 4000
        const val TEST_WEB = 13003
        const val TEST_STREAMING = 14000
    }

    /** Roundcube - Webmail */
    object Roundcube {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10083
    }

    /** Radicale - CalDAV/CardDAV */
    object Radicale {
        const val INTERNAL = 5232
        const val TEST_LOCALHOST = 15232
    }

    // ========================================================================
    // File Services
    // ========================================================================

    /** Seafile - File sync and share */
    object Seafile {
        const val INTERNAL = 8000
        const val TEST_LOCALHOST = 18001
    }

    /** OnlyOffice - Document editor */
    object OnlyOffice {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10084
    }

    // ========================================================================
    // AI/ML Services
    // ========================================================================

    /** vLLM - LLM inference engine */
    object VLLM {
        const val INTERNAL = 8000
        const val TEST_LOCALHOST = 18002
    }

    /** LiteLLM - LLM proxy */
    object LiteLLM {
        const val INTERNAL = 4000
        const val TEST_LOCALHOST = 14001
    }

    // ========================================================================
    // Management Services
    // ========================================================================

    /** LDAP Account Manager - User management */
    object LDAPAccountManager {
        const val INTERNAL = 80
        const val TEST_LOCALHOST = 10085
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    /**
     * Get all test localhost port mappings for stack-tests.
     * Returns map of service name to (localhost_port, internal_port) pairs.
     */
    fun getAllTestPortMappings(): Map<String, Pair<Int, Int>> = mapOf(
        "postgres" to (Postgres.TEST_LOCALHOST to Postgres.INTERNAL),
        "clickhouse" to (ClickHouse.TEST_HTTP to ClickHouse.HTTP),
        "qdrant" to (Qdrant.TEST_HTTP to Qdrant.HTTP),
        "control-panel" to (ControlPanel.TEST_LOCALHOST to ControlPanel.INTERNAL),
        "data-fetcher" to (DataFetcher.TEST_LOCALHOST to DataFetcher.INTERNAL),
        "data-transformer" to (DataTransformer.TEST_LOCALHOST to DataTransformer.INTERNAL),
        "search-service" to (SearchService.TEST_LOCALHOST to SearchService.INTERNAL),
        "embedding-service" to (EmbeddingService.TEST_LOCALHOST to EmbeddingService.INTERNAL),
        "authelia" to (Authelia.TEST_LOCALHOST to Authelia.INTERNAL),
        "docker-proxy" to (DockerProxy.TEST_LOCALHOST to DockerProxy.INTERNAL),
        "bookstack" to (BookStack.TEST_LOCALHOST to BookStack.INTERNAL),
        "forgejo" to (Forgejo.TEST_HTTP to Forgejo.HTTP),
        "grafana" to (Grafana.TEST_LOCALHOST to Grafana.INTERNAL),
        "homepage" to (Homepage.TEST_LOCALHOST to Homepage.INTERNAL),
        "jupyterhub" to (JupyterHub.TEST_LOCALHOST to JupyterHub.INTERNAL),
        "open-webui" to (OpenWebUI.TEST_LOCALHOST to OpenWebUI.INTERNAL),
        "planka" to (Planka.TEST_LOCALHOST to Planka.INTERNAL),
        "vaultwarden" to (Vaultwarden.TEST_LOCALHOST to Vaultwarden.INTERNAL),
        "element" to (Element.TEST_LOCALHOST to Element.INTERNAL),
        "synapse" to (Synapse.TEST_HTTP to Synapse.HTTP),
        "mastodon-web" to (Mastodon.TEST_WEB to Mastodon.WEB),
        "mastodon-streaming" to (Mastodon.TEST_STREAMING to Mastodon.STREAMING),
        "roundcube" to (Roundcube.TEST_LOCALHOST to Roundcube.INTERNAL),
        "seafile" to (Seafile.TEST_LOCALHOST to Seafile.INTERNAL),
        "onlyoffice" to (OnlyOffice.TEST_LOCALHOST to OnlyOffice.INTERNAL),
        "radicale" to (Radicale.TEST_LOCALHOST to Radicale.INTERNAL),
        "homeassistant" to (HomeAssistant.TEST_LOCALHOST to HomeAssistant.INTERNAL),
        "qbittorrent" to (QBittorrent.TEST_WEB to QBittorrent.WEB),
        "ldap-account-manager" to (LDAPAccountManager.TEST_LOCALHOST to LDAPAccountManager.INTERNAL),
        "litellm" to (LiteLLM.TEST_LOCALHOST to LiteLLM.INTERNAL),
        "vllm" to (VLLM.TEST_LOCALHOST to VLLM.INTERNAL)
    )
}
