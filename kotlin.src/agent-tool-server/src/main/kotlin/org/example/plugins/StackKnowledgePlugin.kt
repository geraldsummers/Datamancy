package org.example.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.api.Plugin
import org.example.api.PluginContext
import org.example.host.*
import org.example.manifest.PluginManifest
import org.example.manifest.Requires

/**
 * Stack Knowledge Plugin
 *
 * Provides context retrieval from the Datamancy stack knowledge base stored in Qdrant.
 * This enables the LLM to access documentation, examples, and best practices.
 */
class StackKnowledgePlugin : Plugin {
    private lateinit var httpClient: HttpClient
    private val qdrantUrl = System.getenv("QDRANT_URL") ?: "http://qdrant:6333"
    private val searchServiceUrl = System.getenv("SEARCH_SERVICE_URL") ?: "http://search-service:8098"
    private val collectionName = "stack_knowledge"

    companion object {
        init {
            PluginFactories.register("org.example.plugins.StackKnowledgePlugin") {
                StackKnowledgePlugin()
            }
        }
    }

    @Serializable
    data class SearchRequest(
        val query: String,
        val mode: String = "hybrid",
        val collections: List<String> = listOf("stack_knowledge"),
        val limit: Int = 5,
        val min_score: Double = 0.0
    )

    @Serializable
    data class SearchResult(
        val id: String,
        val score: Double,
        val source: String,
        val title: String? = null,
        val content: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable
    data class SearchResponse(
        val query: String,
        val mode: String,
        val results: List<SearchResult>,
        val total: Int,
        val took_ms: Int? = null
    )

    override fun manifest() = PluginManifest(
        id = "org.datamancy.plugins.stack-knowledge",
        version = "1.0.0",
        apiVersion = "1.0.0",
        implementation = "org.example.plugins.StackKnowledgePlugin",
        capabilities = listOf("knowledge-retrieval", "rag-context"),
        requires = Requires(host = ">=1.0.0", api = ">=1.0.0")
    )

    override fun init(context: PluginContext) {
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
    }

    override fun tools(): List<Any> = listOf(Tools(httpClient, searchServiceUrl, collectionName))

    override fun registerTools(registry: ToolRegistry) {
        val pluginId = manifest().id
        val tools = Tools(httpClient, searchServiceUrl, collectionName)

        // retrieve_stack_context tool
        registry.register(
            ToolDefinition(
                name = "retrieve_stack_context",
                description = "Retrieve relevant context from Datamancy stack knowledge base",
                shortDescription = "Search stack knowledge base for relevant information",
                longDescription = """
                    Retrieves relevant documentation, examples, and best practices from the Datamancy
                    stack knowledge base. Use this when you need information about:
                    - Docker Compose configurations
                    - Datamancy stack architecture
                    - Python/JupyterHub patterns
                    - Qdrant vector database operations
                    - Search Service API usage
                    - Troubleshooting common issues
                    - Code templates and examples

                    Returns formatted context snippets that can be used to answer user questions.
                """.trimIndent(),
                parameters = listOf(
                    ToolParam("query", "string", true, "Search query describing the information needed"),
                    ToolParam("limit", "integer", false, "Maximum number of results (default: 5, max: 20)"),
                    ToolParam("mode", "string", false, "Search mode: 'vector' (semantic), 'bm25' (keyword), or 'hybrid' (default: hybrid)")
                ),
                paramsSpec = """
                    {
                      "type": "object",
                      "required": ["query"],
                      "properties": {
                        "query": {
                          "type": "string",
                          "description": "Search query for relevant stack knowledge"
                        },
                        "limit": {
                          "type": "integer",
                          "description": "Maximum results to return (1-20)",
                          "minimum": 1,
                          "maximum": 20,
                          "default": 5
                        },
                        "mode": {
                          "type": "string",
                          "description": "Search mode",
                          "enum": ["vector", "bm25", "hybrid"],
                          "default": "hybrid"
                        }
                      }
                    }
                """.trimIndent(),
                pluginId = pluginId
            ),
            ToolHandler { args, _ ->
                val query = args.get("query")?.asText()
                    ?: throw IllegalArgumentException("query is required")
                val limit = args.get("limit")?.asInt() ?: 5
                val mode = args.get("mode")?.asText() ?: "hybrid"

                runBlocking {
                    tools.retrieve_stack_context(query, limit.coerceIn(1, 20), mode)
                }
            }
        )

        // get_stack_knowledge_categories tool
        registry.register(
            ToolDefinition(
                name = "get_stack_knowledge_categories",
                description = "List available knowledge categories in the stack knowledge base",
                shortDescription = "List knowledge categories",
                longDescription = """
                    Returns a list of available knowledge categories in the Datamancy stack
                    knowledge base, helping you understand what information is available.
                """.trimIndent(),
                parameters = emptyList(),
                paramsSpec = """{"type":"object","properties":{}}""",
                pluginId = pluginId
            ),
            ToolHandler { _, _ ->
                tools.get_stack_knowledge_categories()
            }
        )
    }

    class Tools(
        private val httpClient: HttpClient,
        private val searchServiceUrl: String,
        private val collectionName: String
    ) {
        /**
         * Retrieve relevant context from the stack knowledge base
         */
        suspend fun retrieve_stack_context(query: String, limit: Int = 5, mode: String = "hybrid"): String {
            return try {
                // Use Search Service for retrieval
                val response = httpClient.post("$searchServiceUrl/search") {
                    contentType(ContentType.Application.Json)
                    setBody(SearchRequest(
                        query = query,
                        mode = mode,
                        collections = listOf(collectionName),
                        limit = limit,
                        min_score = 0.5  // Only return relevant results
                    ))
                }

                if (response.status != HttpStatusCode.OK) {
                    return "Error: Failed to retrieve stack knowledge (${response.status})"
                }

                val searchResponse: SearchResponse = response.body()

                if (searchResponse.results.isEmpty()) {
                    return """
                        No relevant knowledge found for query: "$query"

                        Available categories:
                        - Docker Compose fundamentals
                        - Datamancy stack architecture
                        - Python & JupyterHub patterns
                        - Qdrant vector database
                        - Search Service API
                        - Troubleshooting guide
                        - Code templates

                        Try rephrasing your query or using more specific terms.
                    """.trimIndent()
                }

                // Format results as context
                buildString {
                    appendLine("=".repeat(80))
                    appendLine("STACK KNOWLEDGE CONTEXT")
                    appendLine("Query: $query")
                    appendLine("Found ${searchResponse.results.size} relevant document(s)")
                    appendLine("=".repeat(80))
                    appendLine()

                    searchResponse.results.forEachIndexed { index, result ->
                        appendLine("[${index + 1}] ${result.title ?: "Untitled"} (relevance: ${String.format("%.2f", result.score)})")
                        appendLine("─".repeat(80))

                        val content = result.content ?: result.metadata["content"] ?: ""
                        // Limit content length to avoid token overflow
                        val maxLength = 2000
                        if (content.length > maxLength) {
                            appendLine(content.take(maxLength))
                            appendLine("\n[... content truncated ...]")
                        } else {
                            appendLine(content)
                        }

                        appendLine()
                        appendLine()
                    }

                    appendLine("=".repeat(80))
                    appendLine("END OF CONTEXT")
                    appendLine("=".repeat(80))
                }
            } catch (e: Exception) {
                "Error retrieving stack knowledge: ${e.message}\n\n" +
                "This may indicate:\n" +
                "- Stack knowledge base not initialized\n" +
                "- Search Service unavailable\n" +
                "- Network connectivity issues\n\n" +
                "Run: /configs/stack-knowledge/bootstrap_stack_knowledge.main.kts to initialize"
            }
        }

        /**
         * Get available knowledge categories
         */
        fun get_stack_knowledge_categories(): String {
            return """
                Available Stack Knowledge Categories:

                1. Docker Compose Fundamentals (01-docker-compose-fundamentals.md)
                   - Service structure, networks, volumes
                   - Environment variables, dependencies
                   - Port mappings, restart policies, health checks
                   - Common patterns and best practices

                2. Datamancy Stack Architecture (02-datamancy-stack-architecture.md)
                   - Network topology and service organization
                   - Infrastructure/Data/AI/Pipeline/Knowledge layers
                   - Service communication patterns
                   - Authentication flow and security

                3. Python & JupyterHub Patterns (03-python-jupyterhub-patterns.md)
                   - Data analysis with pandas, numpy, matplotlib
                   - HTTP requests (requests, httpx)
                   - JupyterHub-specific patterns
                   - Common workflows and library recommendations

                4. Qdrant Vector Database (04-qdrant-vector-database.md)
                   - Core concepts (vectors, collections, points)
                   - Distance metrics (cosine, euclidean)
                   - CRUD operations and search strategies
                   - Best practices and troubleshooting

                5. Search Service API (05-search-service-api.md)
                   - Endpoint documentation
                   - Search modes (vector, BM25, hybrid)
                   - Collection-specific searches
                   - Query patterns and score interpretation

                6. Troubleshooting Guide (06-troubleshooting-guide.md)
                   - Service startup and configuration issues
                   - Network and database problems
                   - LLM/AI service debugging
                   - Emergency procedures

                7. Code Templates (07-code-templates.md)
                   - Docker Compose service templates
                   - Python scripts (data analysis, API, database)
                   - Qdrant integration examples
                   - Bash scripts for common tasks

                Use retrieve_stack_context(query) to search for specific information within these categories.
            """.trimIndent()
        }
    }
}
