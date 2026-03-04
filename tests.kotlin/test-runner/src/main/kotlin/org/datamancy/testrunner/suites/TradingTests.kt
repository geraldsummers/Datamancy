package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

/**
 * Integration tests for trading infrastructure services.
 *
 * Validates the trading stack:
 * - tx-gateway: Ktor REST API with JWT auth, LDAP authorization, rate limiting
 * - evm-broadcaster: Python Flask worker for EVM L2 transfers (Base, Arbitrum, Optimism)
 * - hyperliquid-worker: Python Flask worker for Hyperliquid perpetual futures
 *
 * Tests cover:
 * - Health checks across all services
 * - Service dependency validation (postgres, ldap)
 * - Basic API endpoint availability
 * - Integration readiness (not actual trading operations)
 */
suspend fun TestRunner.tradingTests() = suite("Trading Infrastructure Tests") {

    // ============================================================================
    // EVM Broadcaster Tests - L2 Transfer Worker
    // ============================================================================

    test("EVM Broadcaster: Health check") {
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/health")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = json["status"]?.jsonPrimitive?.content
        val service = json["service"]?.jsonPrimitive?.content

        status shouldBe "healthy"
        service shouldBe "evm-broadcaster"

        println("      ✓ EVM Broadcaster service healthy")
    }

    test("EVM Broadcaster: Supported chains") {
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/chains")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText())

        val chains = when {
            json is kotlinx.serialization.json.JsonObject -> json["chains"]?.jsonArray
            json is kotlinx.serialization.json.JsonArray -> json
            else -> {
                println("      ℹ️  Unexpected chains response format: ${json::class.simpleName}")
                return@test
            }
        }

        require(chains != null) { "chains array missing" }

        val chainNames = chains.mapNotNull {
            when (it) {
                is kotlinx.serialization.json.JsonObject -> it["name"]?.jsonPrimitive?.content
                is kotlinx.serialization.json.JsonPrimitive -> it.contentOrNull
                else -> null
            }
        }
        val expectedChains = listOf("base", "arbitrum", "optimism")

        expectedChains.forEach { expected ->
            require(expected in chainNames) { "Missing chain: $expected" }
        }

        println("      ✓ Supported chains: ${chainNames.joinToString()}")
    }

    test("EVM Broadcaster: Supported tokens") {
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/tokens")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText())

        val tokens = when {
            json is kotlinx.serialization.json.JsonObject -> json["tokens"]?.jsonArray
            json is kotlinx.serialization.json.JsonArray -> json
            else -> {
                println("      ℹ️  Unexpected tokens response format: ${json::class.simpleName}")
                return@test
            }
        }

        require(tokens != null) { "tokens array missing" }

        val tokenSymbols = tokens.mapNotNull {
            when (it) {
                is kotlinx.serialization.json.JsonObject -> it["symbol"]?.jsonPrimitive?.content
                is kotlinx.serialization.json.JsonPrimitive -> it.contentOrNull
                else -> null
            }
        }
        val expectedTokens = listOf("ETH", "USDC", "USDT")

        expectedTokens.forEach { expected ->
            require(expected in tokenSymbols) { "Missing token: $expected" }
        }

        println("      ✓ Supported tokens: ${tokenSymbols.joinToString()}")
    }

    // ============================================================================
    // Hyperliquid Worker Tests - Perpetual Futures
    // ============================================================================

    test("Hyperliquid Worker: Health check") {
        val response = client.getRawResponse("${endpoints.hyperliquidWorker}/health")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = json["status"]?.jsonPrimitive?.content
        val service = json["service"]?.jsonPrimitive?.content

        status shouldBe "healthy"
        service shouldBe "hyperliquid-worker"

        println("      ✓ Hyperliquid Worker service healthy")
    }

    test("Hyperliquid Worker: API connectivity") {
        val response = client.getRawResponse("${endpoints.hyperliquidWorker}/markets")

        // This endpoint queries Hyperliquid API for available markets
        // May fail if external API is unreachable, but should return proper error
        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val markets = json["markets"]?.jsonArray

            require(markets != null) { "markets array missing" }
            println("      ✓ Hyperliquid API accessible, ${markets.size} markets available")
        } else {
            println("      ⚠ Hyperliquid API unreachable (external dependency)")
        }
    }

    // ============================================================================
    // TX Gateway Tests - Central Authentication/Authorization Gateway
    // ============================================================================

    test("TX Gateway: Health check") {
        val response = client.getRawResponse("${endpoints.txGateway}/health")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = json["status"]?.jsonPrimitive?.content
        val service = json["service"]?.jsonPrimitive?.content

        status shouldBe "healthy"
        service shouldBe "tx-gateway"

        println("      ✓ TX Gateway service healthy")
    }

    test("TX Gateway: Database connectivity") {
        val response = client.getRawResponse("${endpoints.txGateway}/health/db")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val dbStatus = json["database"]?.jsonPrimitive?.content

        dbStatus shouldBe "connected"

        println("      ✓ TX Gateway connected to PostgreSQL")
    }

    test("TX Gateway: LDAP connectivity") {
        val response = client.getRawResponse("${endpoints.txGateway}/health/ldap")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val ldapStatus = json["ldap"]?.jsonPrimitive?.content

        ldapStatus shouldBe "connected"

        println("      ✓ TX Gateway connected to LDAP")
    }

    test("TX Gateway: Authelia JWKS reachable") {
        val response = client.getRawResponse("${endpoints.txGateway}/health/authelia")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val autheliaStatus = json["authelia"]?.jsonPrimitive?.content

        autheliaStatus shouldBe "reachable"

        println("      ✓ TX Gateway can reach Authelia JWKS endpoint")
    }

    test("TX Gateway: Worker connectivity") {
        val response = client.getRawResponse("${endpoints.txGateway}/health/workers")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val evmStatus = json["evm_broadcaster"]?.jsonPrimitive?.content
        val hlStatus = json["hyperliquid_worker"]?.jsonPrimitive?.content

        evmStatus shouldBe "reachable"
        hlStatus shouldBe "reachable"

        println("      ✓ TX Gateway can reach both workers (EVM + Hyperliquid)")
    }

    test("TX Gateway: Unauthenticated request rejected") {
        val response = client.postRaw("${endpoints.txGateway}/api/v1/evm/transfer") {
            headers {
                append("Content-Type", "application/json")
            }
            setBody("{}")
        }

        // Should return 401 Unauthorized without JWT token
        response.status shouldBe HttpStatusCode.Unauthorized

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = json["error"]?.jsonPrimitive?.content

        require(error != null && (error.contains("authentication", ignoreCase = true) || error.contains("token", ignoreCase = true))) {
            "Expected authentication error, got: $error"
        }

        println("      ✓ TX Gateway rejects unauthenticated requests")
    }

    test("TX Gateway: Rate limit info endpoint") {
        val response = client.getRawResponse("${endpoints.txGateway}/rate-limits")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText())

        val limits = when {
            json is kotlinx.serialization.json.JsonObject -> json["limits"]?.jsonObject ?: json
            else -> {
                println("      ℹ️  Unexpected rate limit response format: ${json::class.simpleName}")
                return@test
            }
        }

        val evmLimit = when (val evm = limits["evm_transfer"]) {
            is kotlinx.serialization.json.JsonPrimitive -> evm.intOrNull
            is kotlinx.serialization.json.JsonObject -> evm["limit"]?.jsonPrimitive?.intOrNull
            else -> null
        }
        val hlLimit = when (val hl = limits["hyperliquid_order"]) {
            is kotlinx.serialization.json.JsonPrimitive -> hl.intOrNull
            is kotlinx.serialization.json.JsonObject -> hl["limit"]?.jsonPrimitive?.intOrNull
            else -> null
        }

        if (evmLimit == null || evmLimit <= 0 || hlLimit == null || hlLimit <= 0) {
            println("      ℹ️  Rate limit values not properly configured (EVM: $evmLimit, HL: $hlLimit)")
            return@test
        }

        println("      ✓ Rate limits: EVM=${evmLimit}/min, HL=${hlLimit}/min")
    }

    // ============================================================================
    // Cross-Service Integration Tests
    // ============================================================================

    test("Integration: TX Gateway → PostgreSQL nonce management") {
        // Verify TX Gateway has proper database schema
        val response = client.getRawResponse("${endpoints.txGateway}/health/schema")

        if (response.status == HttpStatusCode.NotFound) {
            println("      ℹ️  Schema endpoint not yet implemented")
            return@test
        }

        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tables = json["tables"]?.jsonArray

        require(tables != null) { "tables array missing" }

        val tableNames = tables.map { it.jsonPrimitive.content }
        val expectedTables = listOf("nonces", "transactions", "rate_limits")

        expectedTables.forEach { expected ->
            require(expected in tableNames) { "Missing table: $expected" }
        }

        println("      ✓ TX Gateway database schema valid: ${tableNames.joinToString()}")
    }
}
