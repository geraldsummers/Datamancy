package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.datamancy.testrunner.framework.*

/**
 * Integration tests for trading infrastructure services.
 *
 * Validates the complete trading stack:
 * - tx-gateway: Ktor REST API with JWT auth, LDAP authorization, rate limiting
 * - evm-broadcaster: Python Flask worker for EVM L2 transfers (Base, Arbitrum, Optimism)
 * - hyperliquid-worker: Python Flask worker for Hyperliquid perpetual futures
 * - vault: HashiCorp Vault for credential storage
 * - web3signer: ConsenSys Web3Signer for remote transaction signing
 *
 * Tests cover:
 * - Health checks across all services
 * - Service dependency validation (vault, web3signer, postgres, ldap)
 * - Basic API endpoint availability
 * - Integration readiness (not actual trading operations)
 */
suspend fun TestRunner.tradingTests() = suite("Trading Infrastructure Tests") {

    // ============================================================================
    // Vault Tests - Key/Secret Storage
    // ============================================================================

    test("Vault: Health check") {
        val response = client.getRawResponse("${endpoints.vault}/v1/sys/health")

        // Vault returns 200 when initialized and unsealed (dev mode)
        // Returns 429, 472, 473, 501, or 503 for various unhealthy states
        require(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.TooManyRequests
        ) { "Vault health check failed with status: ${response.status}" }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val initialized = json["initialized"]?.jsonPrimitive?.booleanOrNull
        val sealed = json["sealed"]?.jsonPrimitive?.booleanOrNull

        initialized shouldBe true
        sealed shouldBe false

        println("      ✓ Vault initialized and unsealed")
    }

    test("Vault: Version info") {
        val response = client.getRawResponse("${endpoints.vault}/v1/sys/seal-status")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val version = json["version"]?.jsonPrimitive?.content
        val clusterName = json["cluster_name"]?.jsonPrimitive?.content

        require(version != null) { "Vault version missing" }
        println("      ✓ Vault version: $version, cluster: $clusterName")
    }

    // ============================================================================
    // Web3Signer Tests - Remote Transaction Signing
    // ============================================================================

    test("Web3Signer: Health check") {
        val response = client.getRawResponse("${endpoints.web3signer}/upcheck")
        response.status shouldBe HttpStatusCode.OK

        val body = response.bodyAsText()
        body shouldContain "OK"

        println("      ✓ Web3Signer service healthy")
    }

    test("Web3Signer: List public keys (empty on fresh install)") {
        val response = client.getRawResponse("${endpoints.web3signer}/api/v1/eth2/publicKeys")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        println("      ✓ Web3Signer has ${json.size} keys loaded")
    }

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

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val chains = json["chains"]?.jsonArray

        require(chains != null) { "chains array missing" }

        val chainNames = chains.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        val expectedChains = listOf("base", "arbitrum", "optimism")

        expectedChains.forEach { expected ->
            require(expected in chainNames) { "Missing chain: $expected" }
        }

        println("      ✓ Supported chains: ${chainNames.joinToString()}")
    }

    test("EVM Broadcaster: Supported tokens") {
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/tokens")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tokens = json["tokens"]?.jsonArray

        require(tokens != null) { "tokens array missing" }

        val tokenSymbols = tokens.map { it.jsonObject["symbol"]?.jsonPrimitive?.content }
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
        val response = client.getRawResponse("${endpoints.txGateway}/api/v1/evm/transfer")

        // Should return 401 Unauthorized without JWT token
        response.status shouldBe HttpStatusCode.Unauthorized

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = json["error"]?.jsonPrimitive?.content

        require(error != null && error.contains("authentication", ignoreCase = true)) {
            "Expected authentication error, got: $error"
        }

        println("      ✓ TX Gateway rejects unauthenticated requests")
    }

    test("TX Gateway: Rate limit info endpoint") {
        val response = client.getRawResponse("${endpoints.txGateway}/rate-limits")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val limits = json["limits"]?.jsonObject

        require(limits != null) { "rate limits missing" }

        val evmLimit = limits["evm_transfer"]?.jsonPrimitive?.intOrNull
        val hlLimit = limits["hyperliquid_order"]?.jsonPrimitive?.intOrNull

        require(evmLimit != null && evmLimit > 0) { "EVM rate limit missing or invalid" }
        require(hlLimit != null && hlLimit > 0) { "Hyperliquid rate limit missing or invalid" }

        println("      ✓ Rate limits: EVM=${evmLimit}/min, HL=${hlLimit}/min")
    }

    // ============================================================================
    // Cross-Service Integration Tests
    // ============================================================================

    test("Integration: Vault → EVM Broadcaster credential flow") {
        // Test that EVM Broadcaster can communicate with Vault
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/health/vault")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val vaultStatus = json["vault"]?.jsonPrimitive?.content

        vaultStatus shouldBe "connected"

        println("      ✓ EVM Broadcaster → Vault connectivity verified")
    }

    test("Integration: Vault → Hyperliquid Worker credential flow") {
        // Test that Hyperliquid Worker can communicate with Vault
        val response = client.getRawResponse("${endpoints.hyperliquidWorker}/health/vault")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val vaultStatus = json["vault"]?.jsonPrimitive?.content

        vaultStatus shouldBe "connected"

        println("      ✓ Hyperliquid Worker → Vault connectivity verified")
    }

    test("Integration: EVM Broadcaster → Web3Signer signing flow") {
        // Test that EVM Broadcaster can communicate with Web3Signer
        val response = client.getRawResponse("${endpoints.evmBroadcaster}/health/web3signer")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val signerStatus = json["web3signer"]?.jsonPrimitive?.content

        signerStatus shouldBe "reachable"

        println("      ✓ EVM Broadcaster → Web3Signer connectivity verified")
    }

    test("Integration: TX Gateway → PostgreSQL nonce management") {
        // Verify TX Gateway has proper database schema
        val response = client.getRawResponse("${endpoints.txGateway}/health/schema")
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
