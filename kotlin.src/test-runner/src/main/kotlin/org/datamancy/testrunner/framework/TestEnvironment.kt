package org.datamancy.testrunner.framework

import java.io.File

/**
 * Centralized service endpoint configuration for the entire Datamancy stack.
 *
 * This data class provides URLs and connection details for all services in the stack,
 * enabling tests to discover and interact with the correct service endpoints based on
 * the runtime environment (Docker container network vs. localhost port-mapped).
 *
 * ## Cross-Service Integration Testing
 * The endpoints represent the full authentication cascade and data flow:
 * - **LDAP → Authelia → OIDC → Services**: Authentication flows validated end-to-end
 * - **Pipeline → PostgreSQL → Qdrant → Search-Service**: Document ingestion and retrieval
 * - **Agent-Tool-Server → All Services**: LLM tool execution across the stack
 *
 * ## Design Rationale
 * - **Environment Agnostic**: Works inside Docker (`service:port`) or via localhost (`localhost:mapped-port`)
 * - **Service Discovery**: Tests don't hardcode URLs; they adapt to deployment topology
 * - **Integration Scope**: Includes every service that tests might interact with, from core
 *   infrastructure (PostgreSQL, LDAP) to end-user applications (Grafana, BookStack, Mastodon)
 *
 * @property agentToolServer Agent-Tool-Server endpoint for LLM tool execution
 * @property dataFetcher Data fetcher service for triggering pipeline ingestion
 * @property searchService Hybrid search service (vector + BM25) endpoint
 * @property pipeline Pipeline monitoring and control endpoint
 * @property liteLLM LiteLLM proxy for LLM routing and function calling
 * @property bookstack BookStack knowledge base API endpoint
 * @property postgres PostgreSQL connection config (shared by Pipeline, Search-Service, tests)
 * @property mariadb MariaDB connection config (used by BookStack)
 * @property qdrant Qdrant vector database HTTP endpoint
 * @property valkey Valkey (Redis-compatible) cache endpoint
 * @property ldap OpenLDAP server endpoint for user management
 * @property userContext Default user context header for agent-tool-server requests
 * @property apiKey Optional API key for agent-tool-server authorization
 * @property bookstackTokenId BookStack API token ID for authenticated requests
 * @property bookstackTokenSecret BookStack API token secret
 * @property caddy Caddy reverse proxy endpoint (forward-auth enforcement)
 * @property authelia Authelia SSO and OIDC provider endpoint
 * @property openWebUI Open-WebUI chat interface endpoint
 * @property jupyterhub JupyterHub notebook server endpoint
 * @property mailserver Mail server SMTP endpoint
 * @property synapse Synapse Matrix homeserver endpoint
 * @property element Element Matrix web client endpoint
 * @property mastodon Mastodon social network web endpoint
 * @property mastodonStreaming Mastodon streaming API endpoint
 * @property roundcube Roundcube webmail client endpoint
 * @property forgejo Forgejo Git hosting endpoint
 * @property planka Planka project management endpoint
 * @property seafile Seafile file sync and share endpoint
 * @property onlyoffice OnlyOffice document server endpoint
 * @property vaultwarden Vaultwarden password manager endpoint
 * @property prometheus Prometheus metrics collection endpoint
 * @property grafana Grafana monitoring dashboard endpoint
 * @property kopia Kopia backup server endpoint
 * @property homepage Homepage dashboard endpoint
 * @property radicale Radicale CalDAV/CardDAV server endpoint
 * @property ntfy Ntfy notification service endpoint
 * @property qbittorrent qBittorrent torrent client endpoint
 * @property homeassistant Home Assistant home automation endpoint
 */
data class ServiceEndpoints(
    val agentToolServer: String,
    val dataFetcher: String,
    val searchService: String,
    val pipeline: String,  
    val liteLLM: String,
    val bookstack: String,
    val postgres: DatabaseConfig,
    val mariadb: DatabaseConfig? = null,
    val qdrant: String,
    val valkey: String? = null,
    val ldap: String? = null,
    val userContext: String? = null,
    val apiKey: String? = null,
    
    val bookstackTokenId: String? = null,
    val bookstackTokenSecret: String? = null,
    
    val caddy: String,
    val authelia: String,
    
    val openWebUI: String,
    val jupyterhub: String,
    
    val mailserver: String,
    val synapse: String,
    val element: String,
    
    val mastodon: String,
    val mastodonStreaming: String,
    val roundcube: String,
    
    val forgejo: String,
    val planka: String,
    
    val seafile: String,
    val onlyoffice: String,
    
    val vaultwarden: String,
    
    val prometheus: String,
    val grafana: String,
    
    val kopia: String,
    
    val homepage: String? = null,
    val radicale: String? = null,
    val ntfy: String? = null,
    val qbittorrent: String? = null,
    
    val homeassistant: String? = null
) {
    companion object {
        /**
         * Creates service endpoints from environment variables (Docker container network mode).
         *
         * This factory method reads service URLs from environment variables with fallbacks to
         * internal Docker network hostnames (e.g., `http://agent-tool-server:8081`). This is
         * the default configuration when tests run inside the Docker stack.
         *
         * Tests running in containers can directly access services via Docker's DNS resolution,
         * avoiding the need for port mapping to the host machine.
         *
         * @return ServiceEndpoints configured for Docker container network access
         */
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
                password = env("POSTGRES_PASSWORD") ?: env("POSTGRES_DATAMANCY_PASSWORD") ?: ""
            ),
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
            
            bookstackTokenId = env("BOOKSTACK_API_TOKEN_ID"),
            bookstackTokenSecret = env("BOOKSTACK_API_TOKEN_SECRET"),
            
            caddy = env("CADDY_URL") ?: "http://caddy:80",
            
            authelia = env("AUTHELIA_URL") ?: "http://authelia:9091",
            
            openWebUI = env("OPEN_WEBUI_URL") ?: "http://open-webui:8080",
            jupyterhub = env("JUPYTERHUB_URL") ?: "http://jupyterhub:8000",
            
            mailserver = env("MAILSERVER_URL") ?: "mailserver:25",
            synapse = env("SYNAPSE_URL") ?: "http://synapse:8008",
            element = env("ELEMENT_URL") ?: "http://element:80",
            
            mastodon = env("MASTODON_URL") ?: "http://mastodon-web:3000",
            mastodonStreaming = env("MASTODON_STREAMING_URL") ?: "http://mastodon-streaming:4000",
            roundcube = env("ROUNDCUBE_URL") ?: "http://roundcube:80",
            
            forgejo = env("FORGEJO_URL") ?: "http://forgejo:3000",
            planka = env("PLANKA_URL") ?: "http://planka:1337",
            
            seafile = env("SEAFILE_URL") ?: "http://seafile:80",
            onlyoffice = env("ONLYOFFICE_URL") ?: "http://onlyoffice:80",
            
            vaultwarden = env("VAULTWARDEN_URL") ?: "http://vaultwarden:80",
            
            prometheus = env("PROMETHEUS_URL") ?: "http://prometheus:9090",
            grafana = env("GRAFANA_URL") ?: "http://grafana:3000",
            
            kopia = env("KOPIA_URL") ?: "http://kopia:51515",
            
            homepage = env("HOMEPAGE_URL") ?: "http://homepage:3000",
            radicale = env("RADICALE_URL") ?: "http://radicale:5232",
            ntfy = env("NTFY_URL") ?: "http://ntfy:80",
            qbittorrent = env("QBITTORRENT_URL") ?: "http://qbittorrent:8080",
            
            homeassistant = env("HOMEASSISTANT_URL") ?: "http://homeassistant:8123"
        )

        /**
         * Creates service endpoints for localhost access (developer machine mode).
         *
         * This factory method returns endpoints that connect to services via localhost
         * port mappings (e.g., `http://localhost:18091`). This is used when tests run
         * outside the Docker stack, typically during local development.
         *
         * Port mappings are defined in docker-compose.yml and allow external access to
         * containerized services for debugging and development workflows.
         *
         * @return ServiceEndpoints configured for localhost port-mapped access
         */
        fun forLocalhost(): ServiceEndpoints = ServiceEndpoints(
            agentToolServer = "http://localhost:18091",
            dataFetcher = "http://localhost:18095",
            searchService = "http://localhost:18098",
            pipeline = "http://localhost:18080",
            liteLLM = "http://localhost:14001",
            bookstack = "http://localhost:10080",
            postgres = DatabaseConfig("localhost", 15432, "datamancy", "datamancer", ""),
            mariadb = DatabaseConfig("localhost", 13306, "bookstack", "bookstack", ""),
            qdrant = "http://localhost:16333",
            valkey = "localhost:16379",
            ldap = "ldap://localhost:10389",
            userContext = "test-agent-user",
            
            bookstackTokenId = env("BOOKSTACK_API_TOKEN_ID"),
            bookstackTokenSecret = env("BOOKSTACK_API_TOKEN_SECRET"),
            
            caddy = "http://localhost:80",
            authelia = "http://localhost:9091",
            
            openWebUI = "http://localhost:8080",
            jupyterhub = "http://localhost:8000",
            
            mailserver = "localhost:25",
            synapse = "http://localhost:8008",
            element = "http://localhost:8009",
            
            mastodon = "http://localhost:3000",
            mastodonStreaming = "http://localhost:4000",
            roundcube = "http://localhost:8010",
            
            forgejo = "http://localhost:3001",
            planka = "http://localhost:1337",
            
            seafile = "http://localhost:8011",
            onlyoffice = "http://localhost:8012",
            
            vaultwarden = "http://localhost:8013",
            
            prometheus = "http://localhost:9090",
            grafana = "http://localhost:3002",
            
            kopia = "http://localhost:51515",
            
            homepage = "http://localhost:3003",
            radicale = "http://localhost:5232",
            ntfy = "http://localhost:8081",
            qbittorrent = "http://localhost:8082",
            
            homeassistant = "http://localhost:8123"
        )
    }
}

/**
 * Database connection configuration for PostgreSQL and MariaDB.
 *
 * Used by tests to establish JDBC connections for validating database state,
 * querying document staging tables, and verifying cross-service data consistency.
 *
 * The same PostgreSQL database is shared by:
 * - **Pipeline**: Writes documents to `document_staging` table
 * - **Search-Service**: Queries `document_staging` for full-text search
 * - **Tests**: Validates document processing states and counts
 *
 * @property host Database server hostname (e.g., "postgres" or "localhost")
 * @property port Database server port (5432 for PostgreSQL, 3306 for MariaDB)
 * @property database Database name to connect to
 * @property user Database username for authentication
 * @property password Database password for authentication
 * @property jdbcUrl Auto-generated JDBC connection string based on port
 */
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
            else -> "jdbc:postgresql://$host:$port/$database"
        }
}

/**
 * Runtime environment detection and configuration for test execution.
 *
 * TestEnvironment is the entry point for all integration tests, automatically detecting
 * whether tests are running inside Docker containers or on a localhost developer machine.
 * This abstraction enables the same test code to run in CI/CD pipelines and local development.
 *
 * ## Environment Detection
 * The framework detects the environment by:
 * 1. Checking for `/.dockerenv` file (Docker container indicator)
 * 2. Reading `TEST_ENV` environment variable
 * 3. Defaulting to localhost mode for developer convenience
 *
 * ## Authentication Cascade Configuration
 * Each environment provides credentials for the full authentication stack:
 * - **LDAP admin password**: For creating ephemeral test users
 * - **Stack admin password**: For Authelia first-factor authentication
 * - **OAuth secrets**: For OIDC flows to Open-WebUI, Grafana, Mastodon, Forgejo, BookStack
 *
 * ## Why Environment Abstraction Matters
 * - **CI/CD**: Tests run inside Docker network without port mapping overhead
 * - **Local Development**: Tests run on host machine with port-mapped service access
 * - **Test Isolation**: Each environment has isolated credentials and endpoints
 * - **No Hardcoding**: Service URLs adapt to deployment topology automatically
 *
 * @property name Environment name ("container" or "localhost")
 * @property endpoints Service endpoint configuration for this environment
 * @property adminPassword Authelia admin user password for authentication tests
 * @property ldapAdminPassword OpenLDAP admin password for user lifecycle management
 * @property domain Domain name for the stack (e.g., "datamancy.local" or "localhost")
 * @property isDevMode Whether this is a development environment (enables relaxed validation)
 * @property openwebuiOAuthSecret OAuth client secret for Open-WebUI OIDC integration
 * @property grafanaOAuthSecret OAuth client secret for Grafana OIDC integration
 * @property mastodonOAuthSecret OAuth client secret for Mastodon OIDC integration
 * @property forgejoOAuthSecret OAuth client secret for Forgejo OIDC integration
 * @property bookstackOAuthSecret OAuth client secret for BookStack OIDC integration
 */
sealed interface TestEnvironment {
    val name: String
    val endpoints: ServiceEndpoints
    val adminPassword: String
    val ldapAdminPassword: String
    val domain: String
    val isDevMode: Boolean get() = false

    
    val openwebuiOAuthSecret: String
    val grafanaOAuthSecret: String
    val mastodonOAuthSecret: String
    val forgejoOAuthSecret: String
    val bookstackOAuthSecret: String

    /**
     * Container environment: Tests run inside Docker network with direct service access.
     *
     * Used by CI/CD pipelines and when tests are executed as a containerized workload
     * within the Datamancy stack. Services are accessed via internal Docker DNS names.
     */
    data object Container : TestEnvironment {
        override val name = "container"
        override val endpoints = ServiceEndpoints.fromEnvironment()
        override val adminPassword = env("STACK_ADMIN_PASSWORD") ?: ""
        override val ldapAdminPassword = env("LDAP_ADMIN_PASSWORD") ?: ""
        override val domain = env("DOMAIN") ?: "datamancy.local"
        override val openwebuiOAuthSecret = env("OPENWEBUI_OAUTH_SECRET") ?: ""
        override val grafanaOAuthSecret = env("GRAFANA_OAUTH_SECRET") ?: ""
        override val mastodonOAuthSecret = env("MASTODON_OIDC_SECRET") ?: ""
        override val forgejoOAuthSecret = env("FORGEJO_OAUTH_SECRET") ?: ""
        override val bookstackOAuthSecret = env("BOOKSTACK_OAUTH_SECRET") ?: ""
    }

    /**
     * Localhost environment: Tests run on developer machine with port-mapped access.
     *
     * Used during local development when tests execute outside Docker but need to
     * interact with containerized services via localhost port mappings. Enables
     * debugging with IDE breakpoints and faster iteration cycles.
     */
    data object Localhost : TestEnvironment {
        override val name = "localhost"
        override val endpoints = ServiceEndpoints.forLocalhost()
        override val adminPassword = env("STACK_ADMIN_PASSWORD") ?: "admin"
        override val ldapAdminPassword = env("LDAP_ADMIN_PASSWORD") ?: "admin"
        override val domain = env("DOMAIN") ?: "localhost"
        override val isDevMode = true
        override val openwebuiOAuthSecret = env("OPENWEBUI_OAUTH_SECRET") ?: "test-secret"
        override val grafanaOAuthSecret = env("GRAFANA_OAUTH_SECRET") ?: "test-secret"
        override val mastodonOAuthSecret = env("MASTODON_OIDC_SECRET") ?: "test-secret"
        override val forgejoOAuthSecret = env("FORGEJO_OAUTH_SECRET") ?: "test-secret"
        override val bookstackOAuthSecret = env("BOOKSTACK_OAUTH_SECRET") ?: "test-secret"
    }

    companion object {
        /**
         * Automatically detects and returns the appropriate test environment.
         *
         * Detection logic:
         * 1. If `/.dockerenv` exists → Container environment
         * 2. If `TEST_ENV=container` → Container environment
         * 3. Otherwise → Localhost environment (developer default)
         *
         * This allows tests to adapt seamlessly to their execution context without
         * requiring manual configuration or command-line flags.
         *
         * @return The detected TestEnvironment instance (Container or Localhost)
         */
        fun detect(): TestEnvironment = when {
            File("/.dockerenv").exists() -> Container
            env("TEST_ENV") == "container" -> Container
            else -> Localhost
        }
    }
}

private fun env(key: String): String? = System.getenv(key)
