package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
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

/**
 * DataSourceQueryPlugin - Provides LM-friendly tools to query various data sources
 * with read-only observation accounts.
 *
 * Supports: PostgreSQL, MariaDB, ClickHouse, CouchDB, Qdrant, LDAP
 */
class DataSourceQueryPlugin : Plugin {
    internal data class PostgresConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String
    )

    internal data class MariaDBConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String
    )

    internal data class ClickHouseConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String
    )

    internal data class CouchDBConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String
    )

    internal data class QdrantConfig(
        val host: String,
        val port: Int,
        val apiKey: String
    )

    internal data class LdapConfig(
        val host: String,
        val port: Int,
        val bindDn: String,
        val password: String,
        val baseDn: String
    )

    internal data class SearchServiceConfig(
        val url: String
    )

    private var postgresConfig: PostgresConfig? = null
    private var mariadbConfig: MariaDBConfig? = null
    private var clickhouseConfig: ClickHouseConfig? = null
    private var couchdbConfig: CouchDBConfig? = null
    private var qdrantConfig: QdrantConfig? = null
    private var ldapConfig: LdapConfig? = null
    private var searchServiceConfig: SearchServiceConfig? = null

    override fun manifest() = PluginManifest(
        id = "org.example.plugins.datasource",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.DataSourceQueryPlugin",
        capabilities = listOf("host.network.http"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        // Load configurations from environment
        val env = System.getenv()

        postgresConfig = env["POSTGRES_HOST"]?.let {
            PostgresConfig(
                host = it,
                port = env["POSTGRES_PORT"]?.toIntOrNull() ?: 5432,
                user = env["POSTGRES_OBSERVER_USER"] ?: "agent_observer",
                password = env["POSTGRES_OBSERVER_PASSWORD"] ?: ""
            )
        }

        mariadbConfig = env["MARIADB_HOST"]?.let {
            MariaDBConfig(
                host = it,
                port = env["MARIADB_PORT"]?.toIntOrNull() ?: 3306,
                user = env["MARIADB_OBSERVER_USER"] ?: "agent_observer",
                password = env["MARIADB_OBSERVER_PASSWORD"] ?: ""
            )
        }

        clickhouseConfig = env["CLICKHOUSE_HOST"]?.let {
            ClickHouseConfig(
                host = it,
                port = env["CLICKHOUSE_PORT"]?.toIntOrNull() ?: 8123,
                user = env["CLICKHOUSE_OBSERVER_USER"] ?: "default",
                password = env["CLICKHOUSE_OBSERVER_PASSWORD"] ?: ""
            )
        }

        couchdbConfig = env["COUCHDB_HOST"]?.let {
            CouchDBConfig(
                host = it,
                port = env["COUCHDB_PORT"]?.toIntOrNull() ?: 5984,
                user = env["COUCHDB_OBSERVER_USER"] ?: "agent_observer",
                password = env["COUCHDB_OBSERVER_PASSWORD"] ?: ""
            )
        }

        qdrantConfig = env["QDRANT_HOST"]?.let {
            QdrantConfig(
                host = it,
                port = env["QDRANT_PORT"]?.toIntOrNull() ?: 6333,
                apiKey = env["QDRANT_OBSERVER_API_KEY"] ?: ""
            )
        }

        ldapConfig = env["LDAP_HOST"]?.let {
            LdapConfig(
                host = it,
                port = env["LDAP_PORT"]?.toIntOrNull() ?: 389,
                bindDn = env["LDAP_OBSERVER_DN"] ?: "cn=agent_observer,dc=stack,dc=local",
                password = env["LDAP_OBSERVER_PASSWORD"] ?: "",
                baseDn = env["LDAP_BASE_DN"] ?: "dc=stack,dc=local"
            )
        }

        searchServiceConfig = env["SEARCH_SERVICE_URL"]?.let {
            SearchServiceConfig(url = it)
        }

        println("[DataSourceQueryPlugin] Initialized with ${listOfNotNull(
            postgresConfig?.let { "postgres" },
            mariadbConfig?.let { "mariadb" },
            clickhouseConfig?.let { "clickhouse" },
            couchdbConfig?.let { "couchdb" },
            qdrantConfig?.let { "qdrant" },
            ldapConfig?.let { "ldap" },
            searchServiceConfig?.let { "search-service" }
        ).joinToString(", ")}")
    }

    override fun tools(): List<Any> = listOf(Tools(
        postgresConfig,
        mariadbConfig,
        clickhouseConfig,
        couchdbConfig,
        qdrantConfig,
        ldapConfig,
        searchServiceConfig
    ))

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(
            postgresConfig,
            mariadbConfig,
            clickhouseConfig,
            couchdbConfig,
            qdrantConfig,
            ldapConfig,
            searchServiceConfig
        )

        if (postgresConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_postgres",
                    description = "Execute read-only SQL query on PostgreSQL",
                    shortDescription = "Query PostgreSQL database",
                    longDescription = "Execute a SELECT query on PostgreSQL. Returns results as JSON array of row objects. Max 100 rows.",
                    parameters = listOf(
                        ToolParam("database", "string", true, "Database name (e.g., planka, grafana, openwebui)"),
                        ToolParam("query", "string", true, "SQL SELECT query to execute")
                    ),
                    paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string"},"query":{"type":"string"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args ->
                    val database = args.get("database")?.asText() ?: throw IllegalArgumentException("database required")
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_postgres(database, query)
                }
            )
        }

        if (mariadbConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_mariadb",
                    description = "Execute read-only SQL query on MariaDB",
                    shortDescription = "Query MariaDB database",
                    longDescription = "Execute a SELECT query on MariaDB. Returns results as JSON array of row objects. Max 100 rows.",
                    parameters = listOf(
                        ToolParam("database", "string", true, "Database name (e.g., bookstack, seafile_db)"),
                        ToolParam("query", "string", true, "SQL SELECT query to execute")
                    ),
                    paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string"},"query":{"type":"string"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args ->
                    val database = args.get("database")?.asText() ?: throw IllegalArgumentException("database required")
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_mariadb(database, query)
                }
            )
        }

        if (clickhouseConfig != null) {
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
                ToolHandler { args ->
                    val query = args.get("query")?.asText() ?: throw IllegalArgumentException("query required")
                    tools.query_clickhouse(query)
                }
            )
        }

        if (couchdbConfig != null) {
            registry.register(
                ToolDefinition(
                    name = "query_couchdb",
                    description = "Query CouchDB document database",
                    shortDescription = "Query CouchDB",
                    longDescription = "Query CouchDB using Mango query syntax. Returns matching documents.",
                    parameters = listOf(
                        ToolParam("database", "string", true, "Database name"),
                        ToolParam("selector", "object", true, "Mango query selector")
                    ),
                    paramsSpec = """{"type":"object","required":["database","selector"],"properties":{"database":{"type":"string"},"selector":{"type":"object"}}}""",
                    pluginId = pluginId
                ),
                ToolHandler { args ->
                    val database = args.get("database")?.asText() ?: throw IllegalArgumentException("database required")
                    val selector = args.get("selector") as? ObjectNode ?: throw IllegalArgumentException("selector required")
                    tools.query_couchdb(database, selector)
                }
            )
        }

        if (qdrantConfig != null) {
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
                ToolHandler { args ->
                    val collection = args.get("collection")?.asText() ?: throw IllegalArgumentException("collection required")
                    val vectorNode = args.get("vector") ?: throw IllegalArgumentException("vector required")
                    val vector = vectorNode.map { it.asDouble().toFloat() }
                    val limit = args.get("limit")?.asInt() ?: 10
                    tools.search_qdrant(collection, vector, limit)
                }
            )
        }

        if (ldapConfig != null) {
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
                ToolHandler { args ->
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
        if (searchServiceConfig != null) {
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
                ToolHandler { args ->
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
        // No cleanup needed
    }

    internal class Tools(
        private val postgresConfig: PostgresConfig?,
        private val mariadbConfig: MariaDBConfig?,
        private val clickhouseConfig: ClickHouseConfig?,
        private val couchdbConfig: CouchDBConfig?,
        private val qdrantConfig: QdrantConfig?,
        private val ldapConfig: LdapConfig?,
        private val searchServiceConfig: SearchServiceConfig?
    ) {

        @LlmTool(
            shortDescription = "Query PostgreSQL database",
            longDescription = "Execute a read-only SELECT query on PostgreSQL agent_observer schema. Only public/safe views accessible. Max 100 rows. Available databases: grafana, planka, mastodon, forgejo.",
            paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string","enum":["grafana","planka","mastodon","forgejo"]},"query":{"type":"string"}}}"""
        )
        fun query_postgres(database: String, query: String): String {
            val config = postgresConfig ?: return "ERROR: PostgreSQL not configured"

            // Whitelist safe databases only
            val allowedDbs = listOf("grafana", "planka", "mastodon", "forgejo")
            if (database !in allowedDbs) {
                return "ERROR: Database '$database' not accessible. Allowed: ${allowedDbs.joinToString(", ")}"
            }

            // Safety checks
            val queryUpper = query.trim().uppercase()
            if (!queryUpper.startsWith("SELECT")) {
                return "ERROR: Only SELECT queries are allowed"
            }

            // Must query from agent_observer schema only
            if (!query.contains("agent_observer.", ignoreCase = true)) {
                return "ERROR: Queries must use agent_observer schema (e.g., SELECT * FROM agent_observer.public_dashboards)"
            }

            // Forbidden patterns
            val forbidden = listOf("information_schema", "pg_catalog", "public.", "DROP", "INSERT", "UPDATE", "DELETE", "ALTER", "CREATE")
            if (forbidden.any { queryUpper.contains(it) }) {
                return "ERROR: Query contains forbidden patterns"
            }

            val url = "jdbc:postgresql://${config.host}:${config.port}/$database"

            return try {
                DriverManager.getConnection(url, config.user, config.password).use { conn ->
                    // Set search path to agent_observer schema only for extra safety
                    conn.createStatement().execute("SET search_path TO agent_observer")
                    conn.createStatement().use { stmt ->
                        stmt.maxRows = 100
                        stmt.executeQuery(query).use { rs ->
                            resultSetToJson(rs)
                        }
                    }
                }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }

        @LlmTool(
            shortDescription = "Query MariaDB database (DISABLED)",
            longDescription = "MariaDB querying is currently disabled. Safe views must be created first to expose only public data.",
            paramsSpec = """{"type":"object","required":["database","query"],"properties":{"database":{"type":"string"},"query":{"type":"string"}}}"""
        )
        fun query_mariadb(database: String, query: String): String {
            return "ERROR: MariaDB querying is disabled until safe public views are created. BookStack and Seafile contain user content that must not be exposed directly."
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
            shortDescription = "Query CouchDB (DISABLED)",
            longDescription = "CouchDB querying is currently disabled. Safe views must be created first to expose only public documents.",
            paramsSpec = """{"type":"object","required":["database","selector"],"properties":{"database":{"type":"string"},"selector":{"type":"object"}}}"""
        )
        fun query_couchdb(database: String, selector: ObjectNode): String {
            return "ERROR: CouchDB querying is disabled until safe views are created. Document databases may contain private user data."
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
