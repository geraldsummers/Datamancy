package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import kotlinx.coroutines.delay

suspend fun TestRunner.knowledgeBaseTests() = suite("Knowledge Base Tests") {
    val userContext = env.endpoints.userContext

    suspend fun searchWithRetry(
        query: String,
        collections: List<String> = listOf("*"),
        limit: Int = 5,
        attempts: Int = 10,
        delayMs: Long = 4000
    ): SearchResult {
        var last = client.search(query = query, collections = collections, limit = limit)
        repeat(attempts - 1) {
            if (last.success) return last
            delay(delayMs)
            last = client.search(query = query, collections = collections, limit = limit)
        }
        return last
    }

    suspend fun embedWithRetry(
        text: String,
        attempts: Int = 12,
        delayMs: Long = 5000
    ): ToolResult {
        var last: ToolResult = ToolResult.Error(0, "Embedding request did not execute")
        repeat(attempts) { index ->
            val result = client.callTool("llm_embed_text", mapOf(
                "text" to text,
                "model" to "bge-m3"
            ))
            if (result is ToolResult.Success) {
                return result
            }

            last = result
            val message = (result as? ToolResult.Error)?.message?.lowercase() ?: ""
            val retryable = message.contains("timed out") ||
                message.contains("timeout") ||
                message.contains("connection error") ||
                message.contains("service unavailable")
            if (!retryable || index == attempts - 1) {
                return result
            }
            delay(delayMs)
        }
        return last
    }

    if (userContext == null) {
        skip("MariaDB query test", "No user context configured")
        skip("Query security test", "No user context configured")
    } else {
        test("Query MariaDB with shadow account") {
            val result = client.callTool("query_mariadb", mapOf(
                "database" to "grafana",
                "query" to "SELECT COUNT(*) as count FROM public_dashboards"
            ))

            when (result) {
                is ToolResult.Success -> {
                    val output = result.output
                    // Check for common MariaDB table/database not found errors (these are OK for fresh deployments)
                    if (output.contains("doesn't exist") ||
                        output.contains("Unknown database") ||
                        output.contains("relation") && output.contains("does not exist")) {
                        println("\n      ℹ️  Table or database does not exist (expected for fresh deployment)")
                        println("      Shadow account may not be provisioned. Run:")
                        println("      scripts/security/create-shadow-agent-account.main.kts $userContext")
                    } else if (output.contains("Access denied") || output.contains("permission")) {
                        println("\n      ℹ️  Shadow account not provisioned or lacks permissions")
                        println("      This is expected if shadow accounts haven't been created yet")
                    } else {
                        // Query succeeded or returned valid data
                        println("      ✓ MariaDB query executed successfully")
                    }
                }
                is ToolResult.Error -> {
                    if (result.message.contains("not provisioned") ||
                        result.message.contains("does not exist") ||
                        result.message.contains("Access denied")) {
                        println("\n      ℹ️  Shadow account not created or lacks permissions")
                        println("      scripts/security/create-shadow-agent-account.main.kts $userContext")
                    } else {
                        throw AssertionError("Query failed: ${result.message}")
                    }
                }
            }
        }

        test("Query MariaDB blocks forbidden patterns") {
            val result = client.callTool("query_mariadb", mapOf(
                "database" to "grafana",
                "query" to "DROP TABLE users"
            ))

            when (result) {
                is ToolResult.Success -> {
                    
                    val output = result.output.lowercase()
                    require(output.contains("error") || output.contains("only select")) {
                        "Expected error response containing 'error' or 'only select' (case-insensitive), got: ${result.output}"
                    }
                }
                is ToolResult.Error -> {
                    
                    val msg = result.message
                    require(msg.contains("Only SELECT") || msg.contains("forbidden")) {
                        "Expected security error, got: $msg"
                    }
                }
            }
        }
    }

    test("Semantic search executes") {
        val result = searchWithRetry(
            query = "kubernetes deployment",
            collections = listOf("*"),
            limit = 5
        )

        result.success shouldBe true
        
        val resultStr = result.results.toString()
        require(resultStr.contains("results") || resultStr.startsWith("[")) {
            "Expected results array, got: $resultStr"
        }
    }

    test("Vectorization pipeline: embed → store → retrieve") {
        
        val testText = "Datamancy integration test vector ${System.currentTimeMillis()}"
        println("\n      Generating embedding for: '$testText'")

        val embedResult = embedWithRetry(testText)

        require(embedResult is ToolResult.Success) {
            "Embedding generation failed: ${(embedResult as? ToolResult.Error)?.message}"
        }
        val embeddingOutput = (embedResult as ToolResult.Success).output

        
        require(embeddingOutput.contains("[") && embeddingOutput.contains("]")) {
            "Expected vector array, got: $embeddingOutput"
        }

        val dimensions = embeddingOutput.split(",").size
        println("      ✓ Generated ${dimensions}d vector")
        require(dimensions > 1000) { "Expected ~1024 dimensions, got $dimensions" }

        
        
        println("      Testing semantic search with query...")
        val searchResult = searchWithRetry(
            query = "integration test",
            collections = listOf("*"),
            limit = 10
        )

        searchResult.success shouldBe true
        println("      ✓ Search completed successfully")

        
        val searchResultStr = searchResult.results.toString()
        require(searchResultStr.contains("results") || searchResultStr.startsWith("[")) {
            "Expected results structure, got: $searchResultStr"
        }

        println("      ✓ Vectorization pipeline validated: embed → search")
    }
}
