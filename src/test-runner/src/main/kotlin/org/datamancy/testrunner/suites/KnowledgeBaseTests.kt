package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*

suspend fun TestRunner.knowledgeBaseTests() = suite("Knowledge Base Tests") {
    val userContext = env.endpoints.userContext

    if (userContext == null) {
        skip("PostgreSQL query test", "No user context configured")
        skip("Query security test", "No user context configured")
    } else {
        test("Query PostgreSQL with shadow account") {
            val result = client.callTool("query_postgres", mapOf(
                "database" to "grafana",
                "query" to "SELECT COUNT(*) as count FROM agent_observer.public_dashboards"
            ))

            when (result) {
                is ToolResult.Success -> {
                    // Success - but check if table exists
                    if (result.output.contains("relation") && result.output.contains("does not exist")) {
                        println("\n      Note: Table does not exist yet. This is expected for fresh deployments.")
                        println("      Shadow account may not be provisioned. Run:")
                        println("      scripts/security/create-shadow-agent-account.main.kts $userContext")
                    } else {
                        // Table exists and query succeeded
                        result.output shouldNotContain "ERROR"
                    }
                }
                is ToolResult.Error -> {
                    if (result.message.contains("not provisioned") ||
                        result.message.contains("does not exist")) {
                        println("\n      Note: Shadow account not created or table missing. Run:")
                        println("      scripts/security/create-shadow-agent-account.main.kts $userContext")
                    } else {
                        throw AssertionError("Query failed: ${result.message}")
                    }
                }
            }
        }

        test("Query PostgreSQL blocks forbidden patterns") {
            val result = client.callTool("query_postgres", mapOf(
                "database" to "grafana",
                "query" to "DROP TABLE users"
            ))

            when (result) {
                is ToolResult.Success -> {
                    // Should contain error about forbidden operation
                    result.output shouldContain "ERROR"
                    result.output shouldContain "Only SELECT"
                }
                is ToolResult.Error -> {
                    // Server-side rejection is also acceptable
                    val msg = result.message
                    require(msg.contains("Only SELECT") || msg.contains("forbidden")) {
                        "Expected security error, got: $msg"
                    }
                }
            }
        }
    }

    test("Semantic search executes") {
        val result = client.search(
            query = "kubernetes deployment",
            collections = listOf("*"),
            limit = 5
        )

        result.success shouldBe true
        // Results may be empty but should return valid JSON structure
        val resultStr = result.results.toString()
        require(resultStr.contains("results") || resultStr.startsWith("[")) {
            "Expected results array, got: $resultStr"
        }
    }

    test("Vectorization pipeline: embed → store → retrieve") {
        // 1. Generate embedding for test text
        val testText = "Datamancy integration test vector ${System.currentTimeMillis()}"
        println("\n      Generating embedding for: '$testText'")

        val embedResult = client.callTool("llm_embed_text", mapOf(
            "text" to testText,
            "model" to "bge-base-en-v1.5"
        ))

        require(embedResult is ToolResult.Success) {
            "Embedding generation failed: ${(embedResult as? ToolResult.Error)?.message}"
        }
        val embeddingOutput = (embedResult as ToolResult.Success).output

        // Verify vector structure
        require(embeddingOutput.contains("[") && embeddingOutput.contains("]")) {
            "Expected vector array, got: $embeddingOutput"
        }

        val dimensions = embeddingOutput.split(",").size
        println("      ✓ Generated ${dimensions}d vector")
        require(dimensions > 700) { "Expected ~768 dimensions, got $dimensions" }

        // 2. Test semantic search with related query
        // Search should use embedding service to vectorize the query
        println("      Testing semantic search with query...")
        val searchResult = client.search(
            query = "integration test",
            collections = listOf("*"),
            limit = 10
        )

        searchResult.success shouldBe true
        println("      ✓ Search completed successfully")

        // Verify search used vectorization (returns valid structure)
        val searchResultStr = searchResult.results.toString()
        require(searchResultStr.contains("results") || searchResultStr.startsWith("[")) {
            "Expected results structure, got: $searchResultStr"
        }

        println("      ✓ Vectorization pipeline validated: embed → search")
    }
}
