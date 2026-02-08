package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * HTTP client abstraction for interacting with Datamancy stack services.
 *
 * ServiceClient provides high-level methods for testing cross-service integration,
 * encapsulating the complexity of HTTP requests, authentication headers, and response
 * parsing. It validates that services can communicate correctly and that the data flow
 * between components works end-to-end.
 *
 * ## Cross-Service Integration Patterns
 * - **Agent-Tool-Server**: Tests LLM tool execution (`callTool`) to validate agent capabilities
 * - **Data Pipeline**: Triggers ingestion (`triggerFetch`) to test document flow
 * - **Search-Service**: Performs hybrid search (`search`) to validate vector + BM25 fusion
 * - **BookStack**: Queries knowledge base API to verify pipeline publishing
 * - **MariaDB**: Executes database queries via agent-tool-server to test tool chain
 *
 * ## Why This Abstraction Exists
 * - **Test Clarity**: Tests focus on behavior, not HTTP mechanics
 * - **Authentication Handling**: Automatically includes user-context headers and API keys
 * - **Error Translation**: Converts HTTP failures into test-friendly result objects
 * - **Integration Testing**: Validates real HTTP communication, not mocked interfaces
 *
 * @property endpoints Service endpoint configuration (URLs and database connections)
 * @property client Ktor HTTP client instance for making requests
 */
class ServiceClient(
    private val endpoints: ServiceEndpoints,
    private val client: HttpClient
) {
    /**
     * Performs health check for core Datamancy services.
     *
     * Validates that services are reachable and responding to HTTP requests. This is
     * the foundation for all integration tests - if health checks fail, the stack is
     * not ready for testing.
     *
     * @param service Service name ("agent-tool-server", "pipeline", "search-service", "data-fetcher")
     * @return HealthStatus indicating whether the service is healthy and its HTTP status code
     */
    suspend fun healthCheck(service: String): HealthStatus {
        val url = when (service) {
            "agent-tool-server" -> "${endpoints.agentToolServer}/healthz"
            "pipeline" -> "${endpoints.pipeline}/health"
            "search-service" -> "${endpoints.searchService}/health"
            
            "data-fetcher" -> "${endpoints.dataFetcher}/health"
            else -> throw IllegalArgumentException("Unknown service: $service")
        }

        return try {
            val response = client.get(url)
            HealthStatus(service, response.status == HttpStatusCode.OK, response.status.value)
        } catch (e: Exception) {
            HealthStatus(service, false, -1, e.message)
        }
    }

    /**
     * Calls an agent tool via agent-tool-server to test LLM-agent integration.
     *
     * This method validates the full agent tool execution chain:
     * 1. HTTP request to agent-tool-server `/call-tool` endpoint
     * 2. Plugin routing and capability enforcement
     * 3. Tool execution (e.g., querying PostgreSQL, searching Qdrant, executing Docker commands)
     * 4. Response formatting for LLM consumption
     *
     * Tests use this to verify that:
     * - Tools execute correctly and return expected results
     * - User-context headers enable proper isolation
     * - Error handling works (invalid args, missing permissions, service failures)
     *
     * @param name Tool name (e.g., "semantic_search", "query_postgres", "docker_ps")
     * @param args Tool arguments as a map (converted to JSON)
     * @return ToolResult.Success with output string, or ToolResult.Error with details
     */
    suspend fun callTool(name: String, args: Map<String, Any>): ToolResult {
        return try {
            val response = client.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                endpoints.userContext?.let { header("X-User-Context", it) }
                endpoints.apiKey?.let { bearerAuth(it) }
                setBody(ToolCallRequest(name, args.toJsonObject()))
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.body<ToolCallResponse>()
                ToolResult.Success(body.result.toString())
            } else {
                ToolResult.Error(response.status.value, response.bodyAsText())
            }
        } catch (e: Exception) {
            ToolResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Triggers data ingestion for a specific source in the pipeline.
     *
     * Tests use this to initiate document fetching and validate the pipeline's ability to:
     * - Connect to external data sources (RSS, CVE feeds, Wikipedia, etc.)
     * - Transform and chunk content
     * - Stage documents in PostgreSQL
     * - Trigger downstream processing (embedding, indexing, publishing)
     *
     * This tests the first stage of the data flow: Source → Pipeline → PostgreSQL staging.
     *
     * @param source Source name (e.g., "rss", "cve", "wikipedia")
     * @return FetchResult indicating success and any error messages
     */
    suspend fun triggerFetch(source: String): FetchResult {
        return try {
            val response = client.post("${endpoints.dataFetcher}/trigger/$source")
            FetchResult(response.status == HttpStatusCode.OK, response.bodyAsText())
        } catch (e: Exception) {
            FetchResult(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Performs a dry-run fetch to validate source connectivity without persisting data.
     *
     * Used to test that the pipeline can reach external data sources and parse their
     * responses without actually ingesting documents. This is useful for validating
     * configuration changes or testing against unreliable external APIs.
     *
     * @param source Source name to dry-run
     * @return DryRunFetchResult with validation status and preview data
     */
    suspend fun dryRunFetch(source: String): DryRunFetchResult {
        return try {
            val response = client.get("${endpoints.dataFetcher}/dry-run/$source")
            val body = response.bodyAsText()
            val success = response.status == HttpStatusCode.OK
            DryRunFetchResult(success, body)
        } catch (e: Exception) {
            DryRunFetchResult(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Performs hybrid search via the search-service to validate RAG capabilities.
     *
     * This tests the complete search pipeline:
     * 1. Query is sent to search-service
     * 2. Search-service calls embedding-service to vectorize the query
     * 3. Parallel searches: Qdrant (vector similarity) + PostgreSQL (full-text BM25)
     * 4. Results merged using Reciprocal Rank Fusion (RRF)
     * 5. Deduplicated and ranked results returned
     *
     * Tests validate that:
     * - Vector search finds semantically similar documents
     * - Full-text search finds exact keyword matches
     * - Hybrid fusion provides better results than either approach alone
     * - Collection filtering works correctly
     * - Results include the documents that pipeline indexed
     *
     * @param query Natural language search query
     * @param collections Qdrant collections to search (default: all)
     * @param limit Maximum number of results to return
     * @return SearchResult with success flag and JSON results array
     */
    suspend fun search(query: String, collections: List<String> = listOf("*"), limit: Int = 5): SearchResult {
        return try {
            val response = client.post("${endpoints.searchService}/search") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("query", query)
                    put("collections", JsonArray(collections.map { JsonPrimitive(it) }))
                    put("mode", "hybrid")
                    put("limit", limit)
                })
            }

            SearchResult(
                success = response.status == HttpStatusCode.OK,
                results = Json.parseToJsonElement(response.bodyAsText())
            )
        } catch (e: Exception) {
            SearchResult(false, JsonPrimitive(e.message ?: "Unknown error"))
        }
    }

    /**
     * Performs a raw HTTP GET request with optional BookStack authentication.
     *
     * Provides low-level HTTP access for tests that need to interact with services
     * beyond the high-level abstractions. Automatically injects BookStack API tokens
     * when accessing BookStack endpoints.
     *
     * @param url Full URL to request
     * @return Raw HTTP response for custom parsing
     */
    suspend fun getRawResponse(url: String): HttpResponse {
        return client.get(url) {
            // Add BookStack authentication
            if (url.contains("/api/") && endpoints.bookstackTokenId != null && endpoints.bookstackTokenSecret != null) {
                header("Authorization", "Token ${endpoints.bookstackTokenId}:${endpoints.bookstackTokenSecret}")
            }
            // Add Qdrant authentication
            if (url.contains(endpoints.qdrant)) {
                if (endpoints.qdrantApiKey != null) {
                    header("api-key", endpoints.qdrantApiKey)
                } else {
                    println("      ⚠️  WARNING: Qdrant URL detected but qdrantApiKey is null!")
                }
            }
        }
    }

    suspend fun getRawResponse(url: String, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        return client.get(url) {
            // Add BookStack authentication
            if (url.contains("/api/") && endpoints.bookstackTokenId != null && endpoints.bookstackTokenSecret != null) {
                header("Authorization", "Token ${endpoints.bookstackTokenId}:${endpoints.bookstackTokenSecret}")
            }
            // Add Qdrant authentication
            if (url.contains(endpoints.qdrant)) {
                if (endpoints.qdrantApiKey != null) {
                    header("api-key", endpoints.qdrantApiKey)
                } else {
                    println("      ⚠️  WARNING: Qdrant URL detected but qdrantApiKey is null!")
                }
            }
            block()
        }
    }

    suspend fun postRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.post(url, block)
    }

    suspend fun putRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.put(url, block)
    }

    suspend fun deleteRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.delete(url, block)
    }

    suspend fun requestRaw(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        return client.request(url, block)
    }

    /**
     * Executes a SQL query against MariaDB via agent-tool-server's query_mariadb tool.
     *
     * This tests the agent tool chain for database access:
     * 1. Test sends query to agent-tool-server via /call-tool
     * 2. Agent-tool-server routes to DataSourceQueryPlugin
     * 3. Plugin establishes JDBC connection to MariaDB
     * 4. Query executes with validation (SELECT only, no dangerous functions)
     * 5. Results formatted as text table and returned
     *
     * Tests validate:
     * - Agent tools can query BookStack's MariaDB database
     * - SQL injection prevention works
     * - Query results are accurate and complete
     * - Error handling for invalid queries
     *
     * @param query SQL query string (must be SELECT-only)
     * @return MariaDbResult with success flag and data or error message
     */
    suspend fun queryMariaDB(query: String): MariaDbResult {
        return try {
            val response = client.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                endpoints.userContext?.let { header("X-User-Context", it) }
                setBody(buildJsonObject {
                    put("name", "query_mariadb")
                    put("args", buildJsonObject {
                        put("database", "bookstack")
                        put("query", query)
                    })
                })
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.body<ToolCallResponse>()
                val resultText = body.result.toString().removeSurrounding("\"").replace("\\n", "\n")
                MariaDbResult(success = true, data = resultText)
            } else {
                MariaDbResult(success = false, error = response.bodyAsText())
            }
        } catch (e: Exception) {
            MariaDbResult(success = false, error = e.message ?: "Unknown error")
        }
    }
}

@Serializable
data class ToolCallRequest(val name: String, val args: JsonObject)

@Serializable
data class ToolCallResponse(val result: JsonElement, val elapsedMs: Long? = null)

data class HealthStatus(val service: String, val healthy: Boolean, val statusCode: Int, val error: String? = null)

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val statusCode: Int, val message: String) : ToolResult()
}

data class FetchResult(val success: Boolean, val message: String)
data class DryRunFetchResult(val success: Boolean, val message: String)
data class IndexResult(val success: Boolean, val message: String)
data class SearchResult(val success: Boolean, val results: JsonElement)
data class MariaDbResult(val success: Boolean, val data: String? = null, val error: String? = null)


private fun Map<String, Any>.toJsonObject(): JsonObject {
    return JsonObject(this.mapValues { (_, v) ->
        when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { item ->
                when (item) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (item as Map<String, Any>).toJsonObject()
                    }
                    is String -> JsonPrimitive(item)
                    is Number -> JsonPrimitive(item)
                    is Boolean -> JsonPrimitive(item)
                    else -> JsonPrimitive(item.toString())
                }
            })
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (v as Map<String, Any>).toJsonObject()
            }
            else -> JsonPrimitive(v.toString())
        }
    })
}
