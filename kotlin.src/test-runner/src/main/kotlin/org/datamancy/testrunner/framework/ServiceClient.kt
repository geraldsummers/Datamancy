package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Type-safe service client with automatic error handling
 */
class ServiceClient(
    private val endpoints: ServiceEndpoints,
    private val client: HttpClient
) {
    suspend fun healthCheck(service: String): HealthStatus {
        val url = when (service) {
            "agent-tool-server" -> "${endpoints.agentToolServer}/healthz"
            "pipeline" -> "${endpoints.pipeline}/health"
            "search-service" -> "${endpoints.searchService}/health"
            // Legacy service names (deprecated - services merged into pipeline)
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

    suspend fun triggerFetch(source: String): FetchResult {
        return try {
            val response = client.post("${endpoints.dataFetcher}/trigger/$source")
            FetchResult(response.status == HttpStatusCode.OK, response.bodyAsText())
        } catch (e: Exception) {
            FetchResult(false, e.message ?: "Unknown error")
        }
    }

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

    suspend fun getRawResponse(url: String): HttpResponse {
        return client.get(url) {
            // Automatically add BookStack auth headers if URL is BookStack and credentials available
            if (url.contains("/api/") && endpoints.bookstackTokenId != null && endpoints.bookstackTokenSecret != null) {
                header("Authorization", "Token ${endpoints.bookstackTokenId}:${endpoints.bookstackTokenSecret}")
            }
        }
    }

    suspend fun getRawResponse(url: String, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        return client.get(url) {
            // Automatically add BookStack auth headers if URL is BookStack and credentials available
            if (url.contains("/api/") && endpoints.bookstackTokenId != null && endpoints.bookstackTokenSecret != null) {
                header("Authorization", "Token ${endpoints.bookstackTokenId}:${endpoints.bookstackTokenSecret}")
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

// Extension to convert Map to JsonObject
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
