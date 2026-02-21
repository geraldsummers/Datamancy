package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.datamancy.testrunner.framework.*
import kotlin.system.measureTimeMillis

/**
 * Tests for evaluating the stack LLM's understanding and manipulation of Docker stacks.
 *
 * These tests are separate from the integration-test-runner as they focus specifically on:
 * - Stack generation and validation
 * - Docker compose file understanding
 * - Service configuration knowledge
 * - Stack-related reasoning and problem-solving
 */
suspend fun TestRunner.stackLlmCapabilityTests() {
    val probRunner = ProbabilisticTestRunner(environment, client, httpClient)

    println("\n▶ Stack LLM Capability Tests")

    // ============================================================================
    // Basic Stack Understanding
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands Docker Compose service structure",
        trials = 15,
        acceptableFailureRate = 0.2
    ) {
        val prompt = """
            Given this Docker Compose service definition, what is the container name?

            services:
              test-service:
                image: nginx:latest
                container_name: my-nginx-container
                ports:
                  - "8080:80"
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert. Answer concisely."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        response.status == HttpStatusCode.OK &&
            response.bodyAsText().contains("my-nginx-container", ignoreCase = true)
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Identifies network configurations",
        trials = 15,
        acceptableFailureRate = 0.2
    ) {
        val prompt = """
            What networks is this service connected to?

            services:
              app:
                image: myapp:latest
                networks:
                  - frontend
                  - backend
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert. List the networks."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("frontend", ignoreCase = true) &&
            body.contains("backend", ignoreCase = true)
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Recognizes environment variable patterns",
        trials = 15,
        acceptableFailureRate = 0.2
    ) {
        val prompt = """
            What environment variables are set in this service?

            services:
              db:
                image: postgres:15
                environment:
                  POSTGRES_DB: mydb
                  POSTGRES_USER: admin
                  POSTGRES_PASSWORD: secret123
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("POSTGRES_DB", ignoreCase = true)
    }

    // ============================================================================
    // Stack Generation & Validation
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Generates valid service definitions",
        trials = 10,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Generate a minimal Docker Compose service definition for Redis.
            Include only: service name, image, and one port mapping.
            Output ONLY the YAML, no explanation.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker Compose expert. Output only YAML."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("services:", ignoreCase = true) &&
            body.contains("image:", ignoreCase = true) &&
            (body.contains("redis", ignoreCase = true) || body.contains("6379"))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Identifies configuration errors",
        trials = 15,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Is there an error in this Docker Compose service? Answer yes or no, then explain.

            services:
              web:
                image: nginx:latest
                ports:
                  - 8080:80
                  - 8080:443
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("yes", ignoreCase = true) ||
             body.contains("error", ignoreCase = true) ||
             body.contains("conflict", ignoreCase = true) ||
             body.contains("same port", ignoreCase = true))
    }

    // ============================================================================
    // Stack Reasoning & Problem Solving
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Recommends appropriate service dependencies",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            I have a web application service that needs a database.
            Should it use 'depends_on' in Docker Compose? Answer yes or no and why briefly.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert. Be concise."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("yes", ignoreCase = true) &&
            (body.contains("depends_on", ignoreCase = true) ||
             body.contains("order", ignoreCase = true) ||
             body.contains("start", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands volume persistence requirements",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Should a PostgreSQL database service in Docker use a volume?
            Answer yes or no, then explain in one sentence.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert. Be concise."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":80
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("yes", ignoreCase = true) &&
            (body.contains("persist", ignoreCase = true) ||
             body.contains("data", ignoreCase = true) ||
             body.contains("volume", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Suggests health check configurations",
        trials = 10,
        acceptableFailureRate = 0.4
    ) {
        val prompt = """
            Write a health check for a PostgreSQL service in Docker Compose.
            Output only the healthcheck YAML section.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker expert. Output YAML only."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("healthcheck", ignoreCase = true) &&
            (body.contains("pg_isready", ignoreCase = true) ||
             body.contains("test:", ignoreCase = true))
    }

    // ============================================================================
    // Datamancy Stack-Specific Knowledge
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands common service patterns",
        trials = 15,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            In a microservices stack, if a service needs to access LiteLLM,
            what URL format would it typically use? Answer with just the URL pattern.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker networking expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("http://", ignoreCase = true) ||
             body.contains("litellm", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Identifies security best practices",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Is it a good practice to hardcode passwords in Docker Compose files?
            Answer yes or no, and state the alternative in one sentence.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Docker security expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("no", ignoreCase = true) &&
            (body.contains("environment", ignoreCase = true) ||
             body.contains("secret", ignoreCase = true) ||
             body.contains("variable", ignoreCase = true))
    }

    // ============================================================================
    // JupyterHub Code Generation
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Generates valid Python code for data analysis",
        trials = 15,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Write a Python function to calculate the mean of a list of numbers.
            Output only the code, no explanation.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Python expert. Output code only."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("def", ignoreCase = true) &&
            (body.contains("sum", ignoreCase = true) || body.contains("len", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Generates Jupyter notebook compatible pandas code",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Write Python code to load a CSV file using pandas and display the first 5 rows.
            Output only the code.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a pandas expert. Output code only."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("pandas", ignoreCase = true) &&
            body.contains("read_csv", ignoreCase = true) &&
            body.contains("head", ignoreCase = true)
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Suggests appropriate Python libraries for tasks",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            What Python library should I use for making HTTP requests?
            Answer with just the library name.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Python expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":20
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("requests", ignoreCase = true) ||
             body.contains("httpx", ignoreCase = true) ||
             body.contains("urllib", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Generates matplotlib visualization code",
        trials = 10,
        acceptableFailureRate = 0.4
    ) {
        val prompt = """
            Write Python code to create a simple line plot with matplotlib.
            Output only the code.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a matplotlib expert. Output code only."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":150
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("matplotlib", ignoreCase = true) &&
            body.contains("plot", ignoreCase = true)
    }

    // ============================================================================
    // Qdrant Knowledge Base Reasoning
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands Qdrant vector database concepts",
        trials = 15,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            What is a collection in Qdrant?
            Answer in one sentence.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a Qdrant expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":80
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("vector", ignoreCase = true) ||
             body.contains("embedding", ignoreCase = true) ||
             body.contains("point", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Explains vector similarity search",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            How does cosine similarity work in vector search?
            Answer briefly in 1-2 sentences.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a vector database expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("cosine", ignoreCase = true) ||
             body.contains("angle", ignoreCase = true) ||
             body.contains("similarity", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Recommends appropriate embedding dimensions",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            What is a typical vector dimension size for text embeddings?
            Answer with a number or range.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are an ML expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("384", ignoreCase = true) ||
             body.contains("512", ignoreCase = true) ||
             body.contains("768", ignoreCase = true) ||
             body.contains("1024", ignoreCase = true) ||
             body.contains("dimension", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands hybrid search strategies",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            What is hybrid search in the context of vector databases?
            Answer in one sentence.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a search engine expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":80
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            ((body.contains("vector", ignoreCase = true) && body.contains("keyword", ignoreCase = true)) ||
             body.contains("BM25", ignoreCase = true) ||
             body.contains("semantic", ignoreCase = true) ||
             body.contains("combine", ignoreCase = true))
    }

    // ============================================================================
    // Search Service API Querying
    // ============================================================================

    probRunner.probabilisticTest(
        name = "Stack LLM: Constructs valid search API requests",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Write a JSON request body to search for "kubernetes" with limit 10.
            Output only the JSON.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a REST API expert. Output JSON only."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            body.contains("query", ignoreCase = true) &&
            (body.contains("kubernetes", ignoreCase = true) ||
             body.contains("limit", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Understands search result structure",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            What fields would you expect in a search result from a knowledge base API?
            List 3-5 common fields.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a search API expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.2,
                        "max_tokens":100
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        val hasExpectedFields = listOf("title", "url", "score", "snippet", "content", "link")
            .count { body.contains(it, ignoreCase = true) } >= 2

        response.status == HttpStatusCode.OK && hasExpectedFields
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Suggests appropriate search modes",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            Should I use vector search or keyword search for finding semantically similar documents?
            Answer with the search type.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a search expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":50
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("vector", ignoreCase = true) ||
             body.contains("semantic", ignoreCase = true) ||
             body.contains("embedding", ignoreCase = true))
    }

    probRunner.probabilisticTest(
        name = "Stack LLM: Interprets search relevance scores",
        trials = 12,
        acceptableFailureRate = 0.3
    ) {
        val prompt = """
            If a search result has a score of 0.95, is it highly relevant or not?
            Answer yes or no and explain briefly.
        """.trimIndent()

        val response = httpClient.post("${endpoints.agentToolServer}/call-tool") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "tool":"llm_chat_completion",
                    "input":{
                        "messages":[
                            {"role":"system","content":"You are a search ranking expert."},
                            {"role":"user","content":"$prompt"}
                        ],
                        "temperature":0.1,
                        "max_tokens":80
                    }
                }
            """.trimIndent())
        }

        val body = response.bodyAsText()
        response.status == HttpStatusCode.OK &&
            (body.contains("yes", ignoreCase = true) ||
             body.contains("highly", ignoreCase = true) ||
             body.contains("relevant", ignoreCase = true))
    }

    // ============================================================================
    // Performance Tests
    // ============================================================================

    probRunner.latencyTest(
        name = "Stack LLM: Stack query response latency",
        trials = 20,
        maxMedianLatency = 5000,   // 5s median
        maxP95Latency = 15000      // 15s p95
    ) {
        measureTimeMillis {
            httpClient.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[
                                {"role":"user","content":"What is Docker Compose?"}
                            ],
                            "max_tokens":50
                        }
                    }
                """.trimIndent())
            }
        }
    }

    probRunner.latencyTest(
        name = "Stack LLM: Complex stack reasoning latency",
        trials = 15,
        maxMedianLatency = 8000,   // 8s median
        maxP95Latency = 20000      // 20s p95
    ) {
        measureTimeMillis {
            httpClient.post("${endpoints.agentToolServer}/call-tool") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "tool":"llm_chat_completion",
                        "input":{
                            "messages":[
                                {"role":"user","content":"Explain the networking between services in a Docker Compose file."}
                            ],
                            "max_tokens":150
                        }
                    }
                """.trimIndent())
            }
        }
    }

    // ============================================================================
    // Summary
    // ============================================================================

    val summary = probRunner.summary()
    println("\n" + "=".repeat(80))
    println("STACK LLM CAPABILITY TEST SUMMARY")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed: ${summary.passed}")
    println("  ✗ Failed: ${summary.failed}")

    if (summary.failed > 0) {
        println("\n❌ Failed Tests:")
        summary.results.filter { !it.passed }.forEach { result ->
            when (result) {
                is ProbabilisticTestResultSuccess -> {
                    println("  • ${result.name}")
                    println("    Success rate: ${result.successCount}/${result.trials} (${(result.actualFailureRate * 100).toInt()}% failure)")
                }
                is LatencyTestResult -> {
                    println("  • ${result.name}")
                    println("    Median: ${result.medianMs}ms (max ${result.maxMedianLatency}ms), P95: ${result.p95Ms}ms (max ${result.maxP95Latency}ms)")
                }
                is ThroughputTestResult -> {
                    println("  • ${result.name}")
                    println("    Throughput: ${String.format("%.2f", result.opsPerSecond)}ops/s (min ${result.minOpsPerSecond}ops/s)")
                }
            }
        }
    }

    println("=".repeat(80))

    if (summary.failed > 0) {
        throw AssertionError("${summary.failed} stack LLM capability test(s) failed")
    }
}
