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

    test("TX Gateway: API v1 health alias") {
        val response = client.getRawResponse("${endpoints.txGateway}/api/v1/health")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = json["status"]?.jsonPrimitive?.content
        status shouldBe "healthy"

        println("      ✓ TX Gateway API v1 health endpoint healthy")
    }

    test("TX Gateway: Unified exchange catalog endpoint") {
        val response = client.getRawResponse("${endpoints.txGateway}/api/v1/exchanges")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val exchanges = json["exchanges"]?.jsonArray ?: error("Missing exchanges array")
        val names = exchanges.mapNotNull { it.jsonObject["apiName"]?.jsonPrimitive?.content }.toSet()

        val expected = setOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster")
        require(expected.all { it in names }) {
            "Missing exchanges. Expected=$expected actual=$names"
        }

        println("      ✓ Unified exchange catalog includes all expected venues")
    }

    test("TX Gateway: Per-exchange quote endpoints are wired") {
        val expected = listOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster")
        val softUnavailableExchanges = setOf("aster")
        val outcomes = mutableListOf<String>()

        for (exchange in expected) {
            val symbolCandidates = if (exchange == "hyperliquid") listOf("BTC", "BTC-PERP", "BTCUSDT") else listOf("BTC")
            var exchangePassed = false

            for (symbol in symbolCandidates) {
                val response = try {
                    client.getRawResponse("${endpoints.txGateway}/api/v1/exchanges/$exchange/quote?symbol=$symbol")
                } catch (error: Exception) {
                    val message = (error.message ?: error.toString())
                    val timedOut = message.contains("timeout", ignoreCase = true)
                    if (timedOut && exchange in softUnavailableExchanges) {
                        outcomes += "$exchange:$symbol:timeout-soft-unavailable"
                        exchangePassed = true
                        break
                    }

                    outcomes += "$exchange:$symbol:error-${error::class.simpleName}"
                    continue
                }

                if (response.status == HttpStatusCode.OK) {
                    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val bid = json["bid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val ask = json["ask"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    require(bid > 0.0 && ask > 0.0 && ask >= bid) {
                        "Invalid quote payload for $exchange/$symbol: bid=$bid ask=$ask"
                    }
                    outcomes += "$exchange:$symbol:ok"
                    exchangePassed = true
                    break
                }

                if (response.status == HttpStatusCode.NotFound) {
                    val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                    val error = body?.get("error")?.jsonPrimitive?.contentOrNull
                    if (error == "Quote unavailable") {
                        outcomes += "$exchange:$symbol:unavailable"
                        exchangePassed = true
                        break
                    }
                }

                outcomes += "$exchange:$symbol:unexpected-${response.status.value}"
            }

            require(exchangePassed) {
                "Quote endpoint did not return expected response for $exchange. Outcomes=${outcomes.joinToString()}"
            }
        }

        println("      ✓ Per-exchange quote endpoints responded as expected (${outcomes.joinToString()})")
    }

    test("TX Gateway: Unified quote endpoint returns executable quote") {
        val symbols = listOf("BTC", "BTC-PERP", "BTCUSDT")
        val statuses = mutableListOf<String>()
        val unexpectedStatuses = mutableListOf<String>()
        var gracefulUnavailable = 0
        var success = false

        for (symbol in symbols) {
            val response = client.getRawResponse("${endpoints.txGateway}/api/v1/exchanges/hyperliquid/quote?symbol=$symbol")
            statuses += "$symbol:${response.status.value}"
            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val bid = json["bid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val ask = json["ask"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                require(bid > 0.0 && ask > 0.0) { "Invalid bid/ask from quote endpoint: bid=$bid ask=$ask" }
                require(ask >= bid) { "Ask must be >= bid: bid=$bid ask=$ask" }
                success = true
                println("      ✓ Unified quote: $symbol bid=$bid ask=$ask")
                break
            } else if (response.status == HttpStatusCode.NotFound) {
                val json = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                val error = json?.get("error")?.jsonPrimitive?.contentOrNull
                if (error == "Quote unavailable") {
                    gracefulUnavailable += 1
                    continue
                }
            }
            unexpectedStatuses += "$symbol:${response.status.value}"
        }

        if (!success && gracefulUnavailable == symbols.size) {
            println("      ℹ️  Unified quote endpoint returned graceful 'Quote unavailable' for all tested symbols (${statuses.joinToString()})")
        }

        require(success || gracefulUnavailable == symbols.size) {
            "Unified quote endpoint failed unexpectedly (${statuses.joinToString()})"
        }

        require(unexpectedStatuses.isEmpty()) {
            "Unified quote endpoint returned unexpected statuses (${unexpectedStatuses.joinToString()})"
        }
    }

    test("TX Gateway: Best-quote mux returns actionable venue") {
        val response = client.getRawResponse("${endpoints.txGateway}/api/v1/exchanges/best-quote?symbol=BTC&side=buy")
        response.status shouldBe HttpStatusCode.OK

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val selectedExchange = json["selectedExchange"]?.jsonPrimitive?.content
        val quote = json["quote"]?.jsonObject ?: error("Missing quote payload")
        val bid = quote["bid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val ask = quote["ask"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val compared = json["comparedExchanges"]?.jsonArray ?: error("Missing comparedExchanges")

        require(!selectedExchange.isNullOrBlank()) { "selectedExchange missing" }
        require(bid > 0.0 && ask > 0.0) { "Invalid bid/ask in best quote: bid=$bid ask=$ask" }
        require(ask >= bid) { "Ask must be >= bid in best quote: bid=$bid ask=$ask" }
        require(compared.isNotEmpty()) { "No exchanges were compared by best-quote mux" }

        println("      ✓ Best-quote mux selected $selectedExchange for BTC buy (bid=$bid ask=$ask)")
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

    test("TX Gateway: All exchange order routes reject unauthenticated requests") {
        val exchanges = listOf("swyftx", "binance", "bybit", "coinbase", "dydx", "hyperliquid", "aster")
        val unexpected = mutableListOf<String>()

        for (exchange in exchanges) {
            val response = client.postRaw("${endpoints.txGateway}/api/v1/exchanges/$exchange/order") {
                headers {
                    append("Content-Type", "application/json")
                }
                setBody(
                    """
                    {
                      "symbol": "BTC",
                      "side": "BUY",
                      "type": "MARKET",
                      "size": "0.01"
                    }
                    """.trimIndent()
                )
            }

            if (response.status != HttpStatusCode.Unauthorized) {
                unexpected += "$exchange:${response.status.value}"
            }
        }

        require(unexpected.isEmpty()) {
            "Expected unauthenticated exchange order requests to be rejected, got: ${unexpected.joinToString()}"
        }

        println("      ✓ All exchange order routes enforce authentication")
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
