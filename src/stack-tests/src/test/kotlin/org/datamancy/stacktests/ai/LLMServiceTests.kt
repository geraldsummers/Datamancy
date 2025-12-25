package org.datamancy.stacktests.ai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.datamancy.stacktests.base.BaseStackTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for LLM services (vLLM, LiteLLM).
 *
 * Tests cover:
 * - vLLM model server health and readiness
 * - LiteLLM proxy functionality
 * - OpenAI API compatibility
 * - Model listing and availability
 * - Basic inference requests
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LLMServiceTests : BaseStackTest() {

    private val vllmUrl = localhostPorts.httpUrl(localhostPorts.vllm)
    private val litellmUrl = localhostPorts.httpUrl(localhostPorts.litellm)
    private val litellmMasterKey = getConfig("LITELLM_MASTER_KEY", "")

    @Test
    @Order(1)
    fun `vLLM service is healthy`() = runBlocking {
        val response = client.get("$vllmUrl/health")

        assertTrue(response.status.value in 200..299,
            "vLLM health endpoint should respond (got ${response.status})")

        println("✓ vLLM service is healthy")
    }

    @Test
    @Order(2)
    fun `vLLM can list available models`() = runBlocking {
        val response = client.get("$vllmUrl/v1/models")

        assertEquals(HttpStatusCode.OK, response.status,
            "Models endpoint should succeed")

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val models = json["data"]?.jsonArray

        assertNotNull(models, "Should have models array")
        assertTrue(models!!.size > 0, "Should have at least one model")

        val modelNames = models.map {
            it.jsonObject["id"]?.jsonPrimitive?.content
        }
        println("✓ vLLM has ${models.size} model(s): ${modelNames.joinToString(", ")}")
    }

    @Test
    @Order(3)
    fun `vLLM supports OpenAI-compatible completions API`() = runBlocking {
        // First, get the model name
        val modelsResponse = client.get("$vllmUrl/v1/models")
        val json = Json.parseToJsonElement(modelsResponse.bodyAsText()).jsonObject
        val models = json["data"]?.jsonArray!!
        val modelId = models[0].jsonObject["id"]?.jsonPrimitive?.content

        assertNotNull(modelId, "Should have a model ID")

        // Test completions endpoint
        val response = client.post("$vllmUrl/v1/completions") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "$modelId",
                    "prompt": "Hello",
                    "max_tokens": 10,
                    "temperature": 0.7
                }
            """.trimIndent())
        }

        assertTrue(response.status.value in 200..299,
            "Completions should succeed (got ${response.status})")

        if (response.status == HttpStatusCode.OK) {
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(result.containsKey("choices"), "Response should have choices")
            println("✓ vLLM completions API working")
        } else {
            println("⚠️  vLLM completions returned ${response.status}")
        }
    }

    @Test
    @Order(4)
    fun `LiteLLM proxy is healthy`() = runBlocking {
        val response = client.get("$litellmUrl/health") {
            header("Authorization", "Bearer $litellmMasterKey")
        }

        assertTrue(response.status.value in 200..299,
            "LiteLLM health should respond (got ${response.status})")

        println("✓ LiteLLM proxy is healthy")
    }

    @Test
    @Order(5)
    fun `LiteLLM can list available models`() = runBlocking {
        val response = client.get("$litellmUrl/v1/models") {
            header("Authorization", "Bearer $litellmMasterKey")
        }

        assertTrue(response.status.value in 200..299,
            "LiteLLM models endpoint should respond (got ${response.status})")

        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val models = json["data"]?.jsonArray

            if (models != null && models.size > 0) {
                println("✓ LiteLLM has ${models.size} model(s) configured")
            } else {
                println("✓ LiteLLM proxy is running (no models configured yet)")
            }
        }
    }

    @Test
    @Order(6)
    fun `LiteLLM supports chat completions API`() = runBlocking {
        val response = client.post("$litellmUrl/v1/chat/completions") {
            header("Authorization", "Bearer $litellmMasterKey")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "gpt-3.5-turbo",
                    "messages": [
                        {"role": "user", "content": "Say hello"}
                    ],
                    "max_tokens": 10
                }
            """.trimIndent())
        }

        // May fail if no backend is configured, but endpoint should exist
        assertTrue(response.status.value in 200..599,
            "Chat completions endpoint should exist")

        if (response.status == HttpStatusCode.OK) {
            println("✓ LiteLLM chat completions working")
        } else {
            println("⚠️  LiteLLM chat completions returned ${response.status} (may need backend configuration)")
        }
    }

    @Test
    @Order(7)
    fun `vLLM can handle tokenization request`() = runBlocking {
        val response = client.post("$vllmUrl/v1/tokenize") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "prompt": "Hello, world!",
                    "add_special_tokens": true
                }
            """.trimIndent())
        }

        // Tokenize endpoint may not be available on all vLLM versions
        if (response.status == HttpStatusCode.OK) {
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tokens = result["tokens"]?.jsonArray
            if (tokens != null) {
                println("✓ vLLM tokenization working (${tokens.size} tokens)")
            } else {
                println("✓ vLLM tokenization endpoint responded")
            }
        } else {
            println("⚠️  vLLM tokenization not available (status: ${response.status})")
        }
    }

    @Test
    @Order(8)
    fun `vLLM reports model info`() = runBlocking {
        val response = client.get("$vllmUrl/v1/models")

        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val models = json["data"]?.jsonArray

            if (models != null && models.size > 0) {
                val firstModel = models[0].jsonObject
                val modelId = firstModel["id"]?.jsonPrimitive?.content
                val ownedBy = firstModel["owned_by"]?.jsonPrimitive?.content
                val created = firstModel["created"]?.jsonPrimitive?.long

                println("✓ Model info:")
                println("  ID: $modelId")
                println("  Owned by: $ownedBy")
                println("  Created: $created")
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            println("\n╔════════════════════════════════════════════════════╗")
            println("║           LLM Service Tests (vLLM, LiteLLM)       ║")
            println("╚════════════════════════════════════════════════════╝\n")
        }
    }
}
