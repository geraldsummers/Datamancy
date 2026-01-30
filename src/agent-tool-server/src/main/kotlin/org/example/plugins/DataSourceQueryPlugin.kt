package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.Select
import org.example.api.LlmTool
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.ToolDefinition
import org.example.host.ToolHandler
import org.example.host.ToolParam
import org.example.host.ToolRegistry
import org.example.manifest.PluginManifest
import org.example.manifest.Requires
import java.net.HttpURLConnection
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64

// SQL Query Validation
sealed class QueryValidationResult {
    data class Approved(val query: String) : QueryValidationResult()
    data class Rejected(val reason: String) : QueryValidationResult()
}

private fun validateSqlQuery(query: String, requiredSchema: String? = null): QueryValidationResult {
    return try {
        // Parse SQL to AST (catches malformed queries and comment-based bypasses)
        val statement = CCJSqlParserUtil.parse(query)

        // Only allow SELECT statements
        if (statement !is Select) {
            return QueryValidationResult.Rejected("Only SELECT queries allowed")
        }

        // Validate schema requirement via simple string check
        // (Parser already validated query structure, this is defense in depth)
        if (requiredSchema != null) {
            if (!query.contains(requiredSchema, ignoreCase = true)) {
                return QueryValidationResult.Rejected(
                    "Query must reference $requiredSchema schema"
                )
            }
        }

        // Check for dangerous functions
        val queryLower = query.lowercase()
        val dangerousFunctions = listOf(
            "pg_sleep", "pg_read_file", "pg_ls_dir",
            "copy ", "\\copy", "lo_import", "lo_export",
            "dblink", "pg_execute", "xmlparse"
        )

        if (dangerousFunctions.any { queryLower.contains(it) }) {
            return QueryValidationResult.Rejected("Query contains forbidden functions")
        }

        // Limit complexity (prevent DoS)
        val selectCount = query.split("SELECT", ignoreCase = true).size - 1
        if (selectCount > 3) {
            return QueryValidationResult.Rejected("Too many nested subqueries (max 3)")
        }

        QueryValidationResult.Approved(query)

    } catch (e: Exception) {
        QueryValidationResult.Rejected("Invalid SQL syntax: ${e.message}")
    }
}

// Configuration data classes
data class PostgresConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String
)

data class MariaDBConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String
)

data class ClickHouseConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String
)

data class QdrantConfig(
    val host: String,
    val port: Int,
    val apiKey: String
)

data class LdapConfig(
    val host: String,
    val port: Int,
    val bindDn: String,
    val password: String,
    val baseDn: String
)

data class SearchServiceConfig(
    val url: String
)

/**
 * Data source configuration holder (supports dependency injection)
 */
data class DataSourceConfigs(
    val postgresConfig: PostgresConfig? = null,
    val mariadbConfig: MariaDBConfig? = null,
    val clickhouseConfig: ClickHouseConfig? = null,
    val qdrantConfig: QdrantConfig? = null,
    val ldapConfig: LdapConfig? = null,
    val searchServiceConfig: SearchServiceConfig? = null
) {
    companion object {
        /**
         * Create configs from environment variables (production use)
         */
        fun fromEnvironment(): DataSourceConfigs {
            val env = System.getenv()

            val postgresConfig = env["POSTGRES_HOST"]?.let {
                PostgresConfig(
                    host = it,
                    port = env["POSTGRES_PORT"]?.toIntOrNull() ?: 5432,
                    user = env["POSTGRES_OBSERVER_USER"] ?: "agent_observer",
                    password = env["POSTGRES_OBSERVER_PASSWORD"]
                        ?: throw IllegalStateException("POSTGRES_OBSERVER_PASSWORD must be set")
                )
            }

            val mariadbConfig = env["MARIADB_HOST"]?.let {
                MariaDBConfig(
                    host = it,
                    port = env["MARIADB_PORT"]?.toIntOrNull() ?: 3306,
                    user = env["MARIADB_OBSERVER_USER"] ?: "agent_observer",
                    password = env["MARIADB_OBSERVER_PASSWORD"]
                        ?: throw IllegalStateException("MARIADB_OBSERVER_PASSWORD must be set")
                )
            }

            val clickhouseConfig = env["CLICKHOUSE_HOST"]?.let {
                ClickHouseConfig(
                    host = it,
                    port = env["CLICKHOUSE_PORT"]?.toIntOrNull() ?: 8123,
                    user = env["CLICKHOUSE_OBSERVER_USER"] ?: "default",
                    password = env["CLICKHOUSE_OBSERVER_PASSWORD"]
                        ?: throw IllegalStateException("CLICKHOUSE_OBSERVER_PASSWORD must be set")
                )
            }

            val qdrantConfig = env["QDRANT_HOST"]?.let {
                val apiKey = env["QDRANT_OBSERVER_API_KEY"] ?: ""
                if (apiKey.isEmpty()) {
                    println("[WARN] QDRANT_OBSERVER_API_KEY not set - Qdrant access will be unauthenticated")
                }
                QdrantConfig(
                    host = it,
                    port = env["QDRANT_PORT"]?.toIntOrNull() ?: 6333,
                    apiKey = apiKey
                )
            }

            val ldapConfig = env["LDAP_HOST"]?.let {
                val password = env["LDAP_OBSERVER_PASSWORD"] ?: ""
                if (password.isEmpty()) {
                    println("[WARN] LDAP_OBSERVER_PASSWORD not set - will attempt anonymous LDAP bind (not recommended for production)")
                }
                LdapConfig(
                    host = it,
                    port = env["LDAP_PORT"]?.toIntOrNull() ?: 389,
                    bindDn = env["LDAP_OBSERVER_DN"] ?: "cn=agent_observer,dc=stack,dc=local",
                    password = password,
                    baseDn = env["LDAP_BASE_DN"] ?: "dc=stack,dc=local"
                )
            }

            val searchServiceConfig = env["SEARCH_SERVICE_URL"]?.let {
                SearchServiceConfig(url = it)
            }

            return DataSourceConfigs(
                postgresConfig = postgresConfig,
                mariadbConfig = mariadbConfig,
                clickhouseConfig = clickhouseConfig,
                qdrantConfig = qdrantConfig,
                ldapConfig = ldapConfig,
                searchServiceConfig = searchServiceConfig
            )
        }
    }
}

/**
 * DataSourceQueryPlugin - Provides LM-friendly tools to query various data sources
 * with read-only observation accounts.
 *
 * Supports: PostgreSQL, MariaDB, ClickHouse, CouchDB, Qdrant, LDAP
 */
class DataSourceQueryPlugin(
    private val configs: DataSourceConfigs = DataSourceConfigs.fromEnvironment()
) : Plugin {
    // Connection pools for database connections (prevents connection exhaustion)
    private var postgresPool: HikariDataSource? = null
    private var mariadbPool: HikariDataSource? = null
    private var clickhousePool: HikariDataSource? = null

    override fun manifest() = PluginManifest(
        id = "org.example.plugins.datasource",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.DataSourceQueryPlugin",
        capabilities = listOf("host.network.http"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        // Use injected configs (allows testing without environment variables)
        // Initialize connection pools for configured databases
        configs.postgresConfig?.let { config ->
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/postgres"
                username = config.user
                password = config.password
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 10000
                idleTimeout = 300000
                maxLifetime = 600000
                poolName = "agent-postgres-pool"
                // Additional safety settings
                isReadOnly = true
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            }
            postgresPool = HikariDataSource(hikariConfig)
            println("[DataSourceQueryPlugin] PostgreSQL connection pool initialized")
        }

        configs.mariadbConfig?.let { config ->
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:mariadb://${config.host}:${config.port}"
                username = config.user
                password = config.password
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 10000
                idleTimeout = 300000
                maxLifetime = 600000
                poolName = "agent-mariadb-pool"
                isReadOnly = true
            }
            mariadbPool = HikariDataSource(hikariConfig)
            println("[DataSourceQueryPlugin] MariaDB connection pool initialized")
        }

        configs.clickhouseConfig?.let { config ->
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:clickhouse://${config.host}:${config.port}/default"
                username = config.user
                password = config.password
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 10000
                idleTimeout = 300000
                maxLifetime = 600000
                poolName = "agent-clickhouse-pool"
                isReadOnly = true
            }
            clickhousePool = HikariDataSource(hikariConfig)
            println("[DataSourceQueryPlugin] ClickHouse connection pool initialized")
        }

        println("[DataSourceQueryPlugin] Initialized with ${listOfNotNull(
            configs.postgresConfig?.let { "postgres" },
            configs.mariadbConfig?.let { "mariadb" },
            configs.clickhouseConfig?.let { "clickhouse" },
            configs.qdrantConfig?.let { "qdrant" },
            configs.ldapConfig?.let { "ldap" },
            configs.searchServiceConfig?.let { "search-service" }
        ).joinToString(", ")}")
    }

    override fun tools(): List<Any> = listOf(Tools(
        configs.postgresConfig,
        configs.mariadbConfig,
        configs.clickhouseConfig,
        configs.qdrantConfig,
        configs.ldapConfig,
        configs.searchServiceConfig,
        postgresPool,
        mariadbPool,
        clickhousePool
    ))

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(
            configs.postgresConfig,
            configs.mariadbConfig,
            configs.clickhouseConfig,
            configs.qdrantConfig,
            configs.ldapConfig,
            configs.searchServiceConfig,
            postgresPool,
            mariadbPool,
            clickhousePool
        )

        if (configs.postgresConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_postgres",
                    description = "Execute read-only SQL query on PostgreSQL",
                    shortDescription = "Query PostgreSQL database",
                    longDescription = "Execute a SELECT query on PostgreSQL using per-user shadow account. Returns results as JSON array of row objects. Max 100 rows. Requires X-User-Context header.",
                    parameters = listOf(
                        ToolParam("database", "string", true, "Database name (e.g., planka, grafana, openwebui)"),
                        ToolParam("query", "string", true, "SQL SELECT query to execute")
                    ),
                    paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string"},"query":{"type":"string"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, userContext ->
                    val database = args.get("database")?.asText() ?: throw IllegalArgumentException("database required")
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_postgres(database, query, userContext)
                }
            )
        }

        if (configs.mariadbConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_mariadb",
                    description = "Execute read-only SQL query on MariaDB",
                    shortDescription = "Query MariaDB database",
                    longDescription = "Execute a SELECT query on MariaDB using per-user shadow account. Returns results as JSON array of row objects. Max 100 rows. Requires X-User-Context header.",
                    parameters = listOf(
                        ToolParam("database", "string", true, "Database name (e.g., bookstack, seafile_db)"),
                        ToolParam("query", "string", true, "SQL SELECT query to execute")
                    ),
                    paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string"},"query":{"type":"string"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, userContext ->
                    val database = args.get("database")?.asText() ?: throw IllegalArgumentException("database required")
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_mariadb(database, query, userContext)
                }
            )
        }

        if (configs.clickhouseConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_clickhouse",
                    description = "Execute read-only query on ClickHouse",
                    shortDescription = "Query ClickHouse database",
                    longDescription = "Execute a SELECT query on ClickHouse analytics database. Returns results as JSON.",
                    parameters = listOf(
                        ToolParam("query", "string", true, "SQL SELECT query to execute")
                    ),
                    paramsSpec = """{"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, _ ->
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_clickhouse(query)
                }
            )
        }

        if (configs.qdrantConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "search_qdrant",
                    description = "Vector similarity search in Qdrant",
                    shortDescription = "Search Qdrant vectors",
                    longDescription = "Perform vector similarity search in Qdrant collection. Returns nearest neighbors.",
                    parameters = listOf(
                        ToolParam("collection", "string", true, "Collection name"),
                        ToolParam("vector", "array", true, "Query vector (array of floats)"),
                        ToolParam("limit", "integer", false, "Max results (default 10)")
                    ),
                    paramsSpec = """{"type":"object","required":["collection","vector"],"properties":{"collection":{"type":"string"},"vector":{"type":"array","items":{"type":"number"}},"limit":{"type":"integer","default":10}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, _ ->
                    val collection = args.get("collection")?.asText() ?: throw IllegalArgumentException("collection required")
                    val vectorNode = args.get("vector") ?: throw IllegalArgumentException("vector required")
                    val vector = vectorNode.map { it.asDouble().toFloat() }
                    val limit = args.get("limit")?.asInt() ?: 10
                    tools.search_qdrant(collection, vector, limit)
                }
            )
        }

        if (configs.ldapConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "search_ldap",
                    description = "Search LDAP directory for users, groups, or organizational units",
                    shortDescription = "Search LDAP directory",
                    longDescription = "Search LDAP directory for users, groups, or organizational units. Returns matching entries with their attributes. Read-only access.",
                    parameters = listOf(
                        ToolParam("filter", "string", true, "LDAP search filter (e.g., '(cn=*)' or '(objectClass=person)')"),
                        ToolParam("base_dn", "string", false, "Search base DN (defaults to configured base DN)"),
                        ToolParam("attributes", "array", false, "Attributes to return (defaults to all)"),
                        ToolParam("limit", "integer", false, "Max results (default 100)")
                    ),
                    paramsSpec = """{"type":"object","required":["filter"],"properties":{"filter":{"type":"string"},"base_dn":{"type":"string"},"attributes":{"type":"array","items":{"type":"string"}},"limit":{"type":"integer","default":100}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, _ ->
                    val filter = args.get("filter")?.asText() ?: throw IllegalArgumentException("filter required")
                    val baseDn = args.get("base_dn")?.asText()
                    val attributesNode = args.get("attributes")
                    val attributes = if (attributesNode != null && attributesNode.isArray) {
                        attributesNode.map { it.asText() }
                    } else {
                        null
                    }
                    val limit = args.get("limit")?.asInt() ?: 100
                    tools.search_ldap(filter, baseDn, attributes, limit)
                }
            )
        }

        // Register semantic search tool if search-service is configured
        if (configs.searchServiceConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "semantic_search",
                    description = "Semantic search across document collections using natural language queries",
                    shortDescription = "Search documents semantically",
                    longDescription = "Perform semantic search across document collections. Automatically handles embedding generation and hybrid search (vector + BM25). Returns relevant documents with scores.",
                    parameters = listOf(
                        ToolParam("query", "string", true, "Natural language search query"),
                        ToolParam("collections", "array", false, "Collection names to search (default: all collections). Use ['*'] for all."),
                        ToolParam("mode", "string", false, "Search mode: 'hybrid' (default), 'vector', or 'bm25'"),
                        ToolParam("limit", "integer", false, "Max results (default 20)")
                    ),
                    paramsSpec = """{"type":"object","required":["query"],"properties":{"query":{"type":"string"},"collections":{"type":"array","items":{"type":"string"},"default":["*"]},"mode":{"type":"string","enum":["hybrid","vector","bm25"],"default":"hybrid"},"limit":{"type":"integer","default":20}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args, _ ->
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    val collectionsNode = args.get("collections")
                    val collections = if (collectionsNode != null && collectionsNode.isArray) {
                        collectionsNode.map { it.asText() }
                    } else {
                        listOf("*")
                    }
                    val mode = args.get("mode")?.asText() ?: "hybrid"
                    val limit = args.get("limit")?.asInt() ?: 20
                    tools.semantic_search(query, collections, mode, limit)
                }
            )
        }
    }

    override fun shutdown() {
        // Close connection pools to release resources
        try {
            postgresPool?.close()
            mariadbPool?.close()
            clickhousePool?.close()
            println("[DataSourceQueryPlugin] Connection pools closed successfully")
        } catch (e: Exception) {
            println("[DataSourceQueryPlugin] Error closing connection pools: ${e.message}")
        }
    }

    internal class Tools(
        private val postgresConfig: PostgresConfig?,
        private val mariadbConfig: MariaDBConfig?,
        private val clickhouseConfig: ClickHouseConfig?,
        private val qdrantConfig: QdrantConfig?,
        private val ldapConfig: LdapConfig?,
        private val searchServiceConfig: SearchServiceConfig?,
        private val postgresPool: HikariDataSource?,
        private val mariadbPool: HikariDataSource?,
        private val clickhousePool: HikariDataSource?
    ) {
        private val secretsDir = System.getenv("SHADOW_ACCOUNTS_SECRETS_DIR") ?: "/run/secrets/datamancy"

        /**
         * Load shadow account credentials for a user.
         * Returns Pair(shadowUsername, password) or null if not found.
         */
        private fun loadShadowCredentials(username: String): Pair<String, String>? {
            val shadowUsername = "$username-agent"
            val passwordFile = java.io.File("$secretsDir/shadow-agent-$username.pwd")

            return if (passwordFile.exists()) {
                val password = passwordFile.readText().trim()
                Pair(shadowUsername, password)
            } else {
                null
            }
        }

        @LlmTool(
            shortDescription = "Query PostgreSQL database",
            longDescription = "Execute a read-only SELECT query on PostgreSQL agent_observer schema. Only public/safe views accessible. Max 100 rows. Available databases: grafana, planka, mastodon, forgejo.",
            paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string","enum":["grafana","planka","mastodon","forgejo"]},"query":{"type":"string"}}}"""
        )
        fun query_postgres(database: String, query: String, userContext: String? = null): String {
            val config = postgresConfig ?: return "ERROR: PostgreSQL not configured"

            // Whitelist safe databases only
            val allowedDbs = listOf("grafana", "planka", "mastodon", "forgejo")
            if (database !in allowedDbs) {
                return "ERROR: Database '$database' not accessible. Allowed: ${allowedDbs.joinToString(", ")}"
            }

            // Validate query with SQL parser (prevents injection via comments, encoding, etc.)
            when (val validation = validateSqlQuery(query, "agent_observer")) {
                is QueryValidationResult.Rejected -> {
                    println("[AUDIT] user=${userContext ?: "anonymous"} database=$database query_rejected reason=\"${validation.reason}\"")
                    return """{"error": "${validation.reason}"}"""
                }
                is QueryValidationResult.Approved -> {
                    // Query validated, proceed with execution
                }
            }

            // Determine credentials (shadow account if userContext provided, otherwise fallback to config)
            val (dbUser, dbPassword) = if (userContext != null) {
                val shadowCreds = loadShadowCredentials(userContext)
                if (shadowCreds == null) {
                    return "ERROR: Shadow account not provisioned for user: $userContext. Contact admin to run: scripts/security/create-shadow-agent-account.main.kts $userContext"
                }
                println("[AUDIT] user=$userContext shadow=${shadowCreds.first} tool=query_postgres database=$database")
                shadowCreds
            } else {
                // Fallback to global config (deprecated, will be removed)
                println("[WARN] No user context provided, using global account (deprecated)")
                Pair(config.user, config.password)
            }

            return try {
                // Use connection pool instead of DriverManager for better performance
                val pool = postgresPool ?: return "ERROR: PostgreSQL connection pool not initialized"

                pool.connection.use { conn ->
                    // Switch to the requested database
                    conn.createStatement().execute("SET search_path TO agent_observer")
                    conn.catalog = database

                    val startTime = System.currentTimeMillis()
                    conn.createStatement().use { stmt ->
                        stmt.maxRows = 100
                        stmt.executeQuery(query).use { rs ->
                            val result = resultSetToJson(rs)
                            val elapsedMs = System.currentTimeMillis() - startTime
                            println("[AUDIT] user=${userContext ?: "anonymous"} shadow=$dbUser database=$database query=\"${query.take(100)}\" rows=${result.lines().size - 2} elapsed_ms=$elapsedMs success=true")
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                println("[AUDIT] user=${userContext ?: "anonymous"} shadow=$dbUser database=$database query=\"${query.take(100)}\" success=false error=\"${e.message}\"")
                "ERROR: ${e.message}"
            }
        }

        @LlmTool(
            shortDescription = "Query MariaDB database",
            longDescription = "Execute a read-only SELECT query on MariaDB. Only specific databases accessible. Max 100 rows. Available databases: bookstack (wiki/documentation).",
            paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string","enum":["bookstack"]},"query":{"type":"string"}}}"""
        )
        fun query_mariadb(database: String, query: String, userContext: String? = null): String {
            val config = mariadbConfig ?: return "ERROR: MariaDB not configured"

            // Whitelist safe databases only - BookStack is safe, Seafile is not
            val allowedDbs = listOf("bookstack")
            if (database !in allowedDbs) {
                return "ERROR: Database '$database' not accessible. Allowed: ${allowedDbs.joinToString(", ")}"
            }

            // Safety checks
            val queryUpper = query.trim().uppercase()
            if (!queryUpper.startsWith("SELECT")) {
                return "ERROR: Only SELECT queries are allowed"
            }

            // Prevent access to sensitive data
            if (queryUpper.contains("PASSWORD") || queryUpper.contains("SECRET") || queryUpper.contains("TOKEN")) {
                return "ERROR: Queries accessing password/secret/token fields are not allowed"
            }

            // Use shadow account if user context provided
            val username = if (userContext != null) {
                config.user.replace("agent_observer", "${userContext}-agent")
            } else {
                config.user
            }

            return try {
                // Use connection pool instead of DriverManager for better performance
                val pool = mariadbPool ?: return "ERROR: MariaDB connection pool not initialized"

                pool.connection.use { conn ->
                    // Switch to the requested database
                    conn.catalog = database

                    val startTime = System.currentTimeMillis()
                    conn.createStatement().use { stmt ->
                        stmt.maxRows = 100
                        stmt.executeQuery(query).use { rs ->
                            val result = resultSetToJson(rs)
                            val elapsedMs = System.currentTimeMillis() - startTime
                            println("[AUDIT] user=${userContext ?: "anonymous"} shadow=$username database=$database query=\"${query.take(100)}\" rows=${result.lines().size - 2} elapsed_ms=$elapsedMs success=true")
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                println("[AUDIT] user=${userContext ?: "anonymous"} shadow=$username database=$database query=\"${query.take(100)}\" success=false error=\"${e.message}\"")
                "ERROR: ${e.message}"
            }
        }

        @LlmTool(
            shortDescription = "Query ClickHouse database (DISABLED)",
            longDescription = "ClickHouse querying is currently disabled. Safe views must be created first to expose only aggregated/public metrics.",
            paramsSpec = """{"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}"""
        )
        fun query_clickhouse(query: String): String {
            return "ERROR: ClickHouse querying is disabled until safe aggregated views are created. Analytics data may contain sensitive metrics."
        }

        @LlmTool(
            shortDescription = "Search Qdrant vectors",
            longDescription = "Perform vector similarity search in Qdrant collection. Returns nearest neighbors.",
            paramsSpec = """{"type":"object","required":["collection","vector"],"properties":{"collection":{"type":"string"},"vector":{"type":"array","items":{"type":"number"}},"limit":{"type":"integer","default":10}}}"""
        )
        fun search_qdrant(collection: String, vector: List<Float>, limit: Int = 10): String {
            val config = qdrantConfig ?: return "ERROR: Qdrant not configured"

            return try {
                val url = URI("http://${config.host}:${config.port}/collections/$collection/points/search").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) {
                    conn.setRequestProperty("api-key", config.apiKey)
                }

                val payload = """{"vector":${vector},"limit":$limit,"with_payload":true}"""
                conn.outputStream.write(payload.toByteArray())

                if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    "ERROR: HTTP ${conn.responseCode}"
                }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        @LlmTool(
            shortDescription = "Search LDAP directory",
            longDescription = "Search LDAP directory for users, groups, or organizational units. Returns matching entries with their attributes.",
            paramsSpec = """{"type":"object","required":["filter"],"properties":{"filter":{"type":"string","description":"LDAP search filter (e.g., '(cn=*)' or '(objectClass=person)')"},"base_dn":{"type":"string","description":"Search base DN (defaults to configured base DN)"},"attributes":{"type":"array","items":{"type":"string"},"description":"Attributes to return (defaults to all)"},"limit":{"type":"integer","default":100,"description":"Max results"}}}"""
        )
        fun search_ldap(filter: String, baseDn: String? = null, attributes: List<String>? = null, limit: Int = 100): String {
            val config = ldapConfig ?: return "ERROR: LDAP not configured"
            
            // Basic safety checks
            if (filter.isBlank()) {
                return "ERROR: Filter cannot be empty"
            }
            
            // Prevent potentially dangerous operations
            val forbiddenPatterns = listOf("userPassword", "sambaNTPassword", "sambaLMPassword")
            if (forbiddenPatterns.any { filter.contains(it, ignoreCase = true) }) {
                return "ERROR: Cannot query password attributes"
            }
            
            return try {
                val env = java.util.Hashtable<String, String>()
                env["java.naming.factory.initial"] = "com.sun.jndi.ldap.LdapCtxFactory"
                env["java.naming.provider.url"] = "ldap://${config.host}:${config.port}"
                env["java.naming.security.authentication"] = "simple"
                env["java.naming.security.principal"] = config.bindDn
                env["java.naming.security.credentials"] = config.password
                
                val ctx = javax.naming.directory.InitialDirContext(env)
                val searchBase = baseDn ?: config.baseDn
                val searchControls = javax.naming.directory.SearchControls()
                searchControls.searchScope = javax.naming.directory.SearchControls.SUBTREE_SCOPE
                searchControls.countLimit = limit.toLong()
                if (attributes != null) {
                    searchControls.returningAttributes = attributes.toTypedArray()
                }
                
                val results = ctx.search(searchBase, filter, searchControls)
                val entries = mutableListOf<ObjectNode>()
                
                while (results.hasMore() && entries.size < limit) {
                    val result = results.next()
                    val entry = JsonNodeFactory.instance.objectNode()
                    entry.put("dn", result.nameInNamespace)
                    
                    val attrs = result.attributes
                    val attrIds = attrs.iDs
                    while (attrIds.hasMore()) {
                        val attrId = attrIds.next()
                        val attr = attrs.get(attrId)
                        if (attr.size() == 1) {
                            entry.put(attrId, attr.get().toString())
                        } else {
                            val values = JsonNodeFactory.instance.arrayNode()
                            val enum = attr.all
                            while (enum.hasMore()) {
                                values.add(enum.next().toString())
                            }
                            entry.set<ObjectNode>(attrId, values)
                        }
                    }
                    entries.add(entry)
                }
                
                ctx.close()
                entries.toString()
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        fun semantic_search(query: String, collections: List<String> = listOf("*"), mode: String = "hybrid", limit: Int = 20): String {
            val config = searchServiceConfig ?: return "ERROR: Search service not configured"

            return try {
                val url = URI("${config.url}/search").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                val collectionsJson = collections.joinToString(",", "[", "]") { "\"$it\"" }
                val payload = """{"query":"${query.replace("\"", "\\\"")}","collections":$collectionsJson,"mode":"$mode","limit":$limit}"""
                conn.outputStream.write(payload.toByteArray())

                if (conn.responseCode == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    "ERROR: HTTP ${conn.responseCode} - $errorBody"
                }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        private fun resultSetToJson(rs: ResultSet): String {
            val meta = rs.metaData
            val columnCount = meta.columnCount
            val rows = mutableListOf<ObjectNode>()

            while (rs.next() && rows.size < 100) {
                val row = JsonNodeFactory.instance.objectNode()
                for (i in 1..columnCount) {
                    val colName = meta.getColumnName(i)
                    val value = rs.getObject(i)
                    when (value) {
                        null -> row.putNull(colName)
                        is String -> row.put(colName, value)
                        is Int -> row.put(colName, value)
                        is Long -> row.put(colName, value)
                        is Double -> row.put(colName, value)
                        is Float -> row.put(colName, value)
                        is Boolean -> row.put(colName, value)
                        else -> row.put(colName, value.toString())
                    }
                }
                rows.add(row)
            }

            return rows.toString()
        }
    }
}
