package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.datamancy.testrunner.framework.AuthResult
import org.datamancy.testrunner.framework.ServiceEndpoints
import org.datamancy.testrunner.framework.TestRunner
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stagedFixtureJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

private data class StagedTradingConfig(
    val mode: String,
    val runLiveProbes: Boolean,
    val targetHost: String,
    val txGatewayBaseUrl: String,
    val autheliaBaseUrl: String,
    val hyperliquidWorkerBaseUrl: String?,
    val recordResponses: Boolean,
    val recordDir: String
)

private data class ResolvedTradingAuth(
    val bearerToken: String,
    val source: String
)

private fun envValue(name: String): String? =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun stagedTradingConfig(endpoints: ServiceEndpoints): StagedTradingConfig {
    val mode = (envValue("TRADING_E2E_MODE") ?: "replay").lowercase(Locale.US)
    val runLive = mode == "live" || mode == "hybrid"

    val targetHost = envValue("TRADING_E2E_TARGET_HOST")
        ?: envValue("DOMAIN")
        ?: "latium.local"

    val txGatewayBaseUrl = envValue("TRADING_E2E_TX_GATEWAY_URL")
        ?: if (runLive) "https://tx-gateway.$targetHost" else endpoints.txGateway

    val autheliaBaseUrl = envValue("TRADING_E2E_AUTHELIA_URL")
        ?: if (runLive) "https://auth.$targetHost" else endpoints.authelia

    val hyperliquidWorkerBaseUrl = envValue("TRADING_E2E_HYPERLIQUID_WORKER_URL")
        ?: if (runLive) "https://hyperliquid-worker.$targetHost" else endpoints.hyperliquidWorker

    val recordResponses = envValue("TRADING_E2E_RECORD")
        ?.lowercase(Locale.US)
        ?.let { it == "1" || it == "true" || it == "yes" }
        ?: false

    val recordDir = envValue("TRADING_E2E_RECORD_DIR") ?: "/tmp/trading-e2e-fixtures"

    return StagedTradingConfig(
        mode = mode,
        runLiveProbes = runLive,
        targetHost = targetHost,
        txGatewayBaseUrl = txGatewayBaseUrl.trimEnd('/'),
        autheliaBaseUrl = autheliaBaseUrl.trimEnd('/'),
        hyperliquidWorkerBaseUrl = hyperliquidWorkerBaseUrl?.trimEnd('/'),
        recordResponses = recordResponses,
        recordDir = recordDir
    )
}

private fun loadStagedFixture(resourceName: String): JsonObject {
    val resourcePath = "fixtures/trading-staged/$resourceName"
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: throw IllegalStateException("Missing staged trading fixture on classpath: $resourcePath")

    val text = stream.bufferedReader().use { it.readText() }
    return stagedFixtureJson.parseToJsonElement(text).jsonObject
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.jsonObject ?: throw IllegalStateException("Fixture missing object field '$name'")

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalStateException("Fixture missing string field '$name'")

private fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalStateException("Fixture missing int field '$name'")

private fun parseDecimal(raw: String?): BigDecimal? = runCatching {
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
}.getOrNull()

private fun assertFixtureContractMatchesResponse(
    fixtureName: String,
    fixture: JsonObject,
    response: JsonObject
) {
    val expected = fixture.requiredObject("expected")
    val requiredKeys = expected["requiredKeys"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: emptyList()

    requiredKeys.forEach { key ->
        require(response.containsKey(key)) {
            "$fixtureName missing required response key '$key': ${response.keys.sorted()}"
        }
    }

    expected["executionMode"]?.jsonPrimitive?.contentOrNull?.let { expectedExecutionMode ->
        val actualExecutionMode = response["executionMode"]?.jsonPrimitive?.contentOrNull
        require(actualExecutionMode == expectedExecutionMode) {
            "$fixtureName expected executionMode=$expectedExecutionMode but got $actualExecutionMode"
        }
    }
}

private fun writeSnapshotIfEnabled(
    config: StagedTradingConfig,
    scenario: String,
    requestBody: JsonElement?,
    responseStatus: Int,
    responseBody: String
) {
    if (!config.recordResponses) return

    val recordRoot = File(config.recordDir)
    recordRoot.mkdirs()

    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(Instant.now())

    val safeScenario = scenario
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')

    val payload = buildJsonObject {
        put("recordedAt", Instant.now().toString())
        put("targetHost", config.targetHost)
        put("mode", config.mode)
        put("scenario", scenario)
        put("responseStatus", responseStatus)
        requestBody?.let { put("request", it) }
        put(
            "responseBody",
            runCatching { stagedFixtureJson.parseToJsonElement(responseBody) }
                .getOrElse { JsonPrimitive(responseBody) }
        )
    }

    val outputFile = File(recordRoot, "${timestamp}_${safeScenario}.json")
    outputFile.writeText(stagedFixtureJson.encodeToString(JsonObject.serializer(), payload))
}

private suspend fun TestRunner.isTradingTokenValid(
    token: String,
    txGatewayBaseUrl: String
): Boolean {
    val response = client.getRawResponse("$txGatewayBaseUrl/api/v1/user") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    return response.status == HttpStatusCode.OK
}

private suspend fun TestRunner.resolveTradingAuth(config: StagedTradingConfig): ResolvedTradingAuth? {
    envValue("TRADING_E2E_BEARER_TOKEN")?.let { bearer ->
        if (isTradingTokenValid(bearer, config.txGatewayBaseUrl)) {
            return ResolvedTradingAuth(bearerToken = bearer, source = "TRADING_E2E_BEARER_TOKEN")
        }
    }

    val clientId = envValue("TRADING_E2E_OIDC_CLIENT_ID")
        ?: envValue("MODEL_CONTEXT_OIDC_CLIENT_ID")
        ?: "test-runner"
    val clientSecret = envValue("TRADING_E2E_OIDC_CLIENT_SECRET")
        ?: envValue("TEST_RUNNER_OAUTH_SECRET")
        ?: envValue("MODEL_CONTEXT_OIDC_CLIENT_SECRET")
        ?: return null
    val redirectUri = envValue("TRADING_E2E_OIDC_REDIRECT_URI")
        ?: envValue("MODEL_CONTEXT_OIDC_REDIRECT_URI")
        ?: "http://test-runner/callback"
    val scope = envValue("TRADING_E2E_OIDC_SCOPE") ?: "openid profile email groups"

    val username = envValue("TRADING_E2E_USERNAME")
        ?: envValue("STACK_ADMIN_USER")
        ?: "sysadmin"
    val password = envValue("TRADING_E2E_PASSWORD")
        ?: envValue("STACK_ADMIN_PASSWORD")
        ?: envValue("LDAP_ADMIN_PASSWORD")
        ?: return null

    val login = auth.login(username, password)
    if (login !is AuthResult.Success) {
        return null
    }

    val minted = runCatching {
        val code = oidc.getAuthorizationCode(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope
        )
        val tokens = oidc.exchangeCodeForTokens(
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            redirectUri = redirectUri
        )
        tokens.accessToken ?: tokens.idToken
    }.getOrNull() ?: return null

    if (!isTradingTokenValid(minted, config.txGatewayBaseUrl)) {
        return null
    }

    return ResolvedTradingAuth(
        bearerToken = minted,
        source = "oidc:$clientId"
    )
}

private suspend fun TestRunner.selectPaperVenueWithQuote(
    txGatewayBaseUrl: String,
    bearerToken: String
): Pair<String, String>? {
    val userResponse = client.getRawResponse("$txGatewayBaseUrl/api/v1/user") {
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
    }
    if (userResponse.status != HttpStatusCode.OK) {
        return null
    }

    val userJson = runCatching { stagedFixtureJson.parseToJsonElement(userResponse.bodyAsText()).jsonObject }
        .getOrNull()
        ?: return null

    val allowed = userJson["allowedExchanges"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase(Locale.US) }
        ?.toSet()
        ?: emptySet()

    val paperExchanges = listOf("swyftx", "binance", "bybit", "coinbase", "dydx", "aster")
    val symbols = listOf("BTC", "BTCUSDT")

    for (exchange in paperExchanges) {
        if (allowed.isNotEmpty() && exchange !in allowed) continue

        for (symbol in symbols) {
            val quoteResponse = client.getRawResponse(
                "$txGatewayBaseUrl/api/v1/exchanges/$exchange/quote?symbol=$symbol"
            )
            if (quoteResponse.status == HttpStatusCode.OK) {
                return exchange to symbol
            }
        }
    }
    return null
}

suspend fun TestRunner.stagedTradingExecutionTests() = suite("Staged Trading Execution Tests") {
    val config = stagedTradingConfig(endpoints)
    println(
        "      ℹ️  staged-trading mode=${config.mode} target=${config.targetHost} txGateway=${config.txGatewayBaseUrl}"
    )

    val fullFillFixture = loadStagedFixture("paper_full_fill.json")
    val partialFillFixture = loadStagedFixture("paper_partial_fill.json")
    val degradedFixture = loadStagedFixture("worker_degraded.json")

    test("Stage 1: Replay fixture - paper full fill contract") {
        val expectedHttpStatus = fullFillFixture.requiredInt("expectedHttpStatus")
        expectedHttpStatus shouldBe 200

        val response = fullFillFixture.requiredObject("sampleResponse")
        assertFixtureContractMatchesResponse("paper_full_fill", fullFillFixture, response)

        response["status"]?.jsonPrimitive?.content shouldBe "FILLED"
        val requestedSize = parseDecimal(fullFillFixture.requiredObject("request").requiredString("size"))
        val filledSize = parseDecimal(response["filledSize"]?.jsonPrimitive?.contentOrNull)
        require(requestedSize != null && filledSize != null) { "Invalid fixture size fields for full-fill scenario" }
        require(filledSize.compareTo(requestedSize) == 0) {
            "Expected full fill size=$requestedSize but got $filledSize"
        }
    }

    test("Stage 2: Replay fixture - paper partial fill contract") {
        val expectedHttpStatus = partialFillFixture.requiredInt("expectedHttpStatus")
        expectedHttpStatus shouldBe 200

        val response = partialFillFixture.requiredObject("sampleResponse")
        assertFixtureContractMatchesResponse("paper_partial_fill", partialFillFixture, response)

        response["status"]?.jsonPrimitive?.content shouldBe "PARTIALLY_FILLED"
        val requestedSize = parseDecimal(partialFillFixture.requiredObject("request").requiredString("size"))
        val filledSize = parseDecimal(response["filledSize"]?.jsonPrimitive?.contentOrNull)
        require(requestedSize != null && filledSize != null) { "Invalid fixture size fields for partial-fill scenario" }
        require(filledSize > BigDecimal.ZERO && filledSize < requestedSize) {
            "Expected partial fill between 0 and $requestedSize but got $filledSize"
        }
    }

    test("Stage 3: Replay fixture - worker degradation contract") {
        val expectedHttpStatus = degradedFixture.requiredInt("expectedHttpStatus")
        require(expectedHttpStatus in setOf(500, 503)) {
            "Expected degraded fixture status in {500,503}, got $expectedHttpStatus"
        }

        val response = degradedFixture.requiredObject("sampleResponse")
        val error = response["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(error.contains("worker", ignoreCase = true) || error.contains("hyperliquid", ignoreCase = true)) {
            "Expected worker degradation error context, got: $error"
        }
    }

    if (!config.runLiveProbes) {
        skip(
            "Stage 4+: Live latium probes",
            "TRADING_E2E_MODE=${config.mode}; set TRADING_E2E_MODE=hybrid or live for live execution checks"
        )
        return@suite
    }

    test("Stage 4: Live latium tx-gateway health probe") {
        val response = client.getRawResponse("${config.txGatewayBaseUrl}/api/v1/health")
        response.status shouldBe HttpStatusCode.OK

        val body = response.bodyAsText()
        val json = stagedFixtureJson.parseToJsonElement(body).jsonObject
        json["status"]?.jsonPrimitive?.content shouldBe "healthy"
        writeSnapshotIfEnabled(config, "tx_gateway_health", requestBody = null, response.status.value, body)
    }

    test("Stage 4b: Live Authelia OIDC metadata probe") {
        val response = client.getRawResponse("${config.autheliaBaseUrl}/.well-known/openid-configuration")
        response.status shouldBe HttpStatusCode.OK

        val body = response.bodyAsText()
        val json = stagedFixtureJson.parseToJsonElement(body).jsonObject
        require(json.containsKey("issuer") && json.containsKey("token_endpoint")) {
            "Authelia discovery document missing required OIDC fields"
        }
        writeSnapshotIfEnabled(config, "authelia_oidc_discovery", requestBody = null, response.status.value, body)
    }

    test("Stage 5: Live quote mux contract probe") {
        val response = client.getRawResponse("${config.txGatewayBaseUrl}/api/v1/exchanges/best-quote?symbol=BTC&side=buy")
        require(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound) {
            "Unexpected best-quote status: ${response.status}"
        }

        val body = response.bodyAsText()
        val json = stagedFixtureJson.parseToJsonElement(body).jsonObject
        if (response.status == HttpStatusCode.OK) {
            val quote = json["quote"]?.jsonObject ?: error("Missing quote object")
            val bid = quote["bid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val ask = quote["ask"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            require(bid > 0.0 && ask > 0.0 && ask >= bid) {
                "Invalid best quote payload: bid=$bid ask=$ask"
            }
        } else {
            val error = json["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(error.contains("Quote unavailable")) {
                "Unexpected best-quote 404 payload: $body"
            }
        }

        writeSnapshotIfEnabled(config, "best_quote_probe", requestBody = null, response.status.value, body)
    }

    val resolvedAuth = resolveTradingAuth(config)
    if (resolvedAuth == null) {
        skip(
            "Stage 6: Live authenticated paper order contract",
            "No compatible tx-gateway bearer token found (set TRADING_E2E_BEARER_TOKEN or OIDC vars)"
        )
    } else {
        val selectedVenue = selectPaperVenueWithQuote(
            txGatewayBaseUrl = config.txGatewayBaseUrl,
            bearerToken = resolvedAuth.bearerToken
        )

        if (selectedVenue == null) {
            skip(
                "Stage 6: Live authenticated paper order contract",
                "No paper venue with fresh quote available for current account"
            )
        } else {
            test("Stage 6: Live authenticated paper order contract") {
                val (exchange, symbol) = selectedVenue
                val requestBody = buildJsonObject {
                    put("symbol", symbol)
                    put("side", "BUY")
                    put("type", "MARKET")
                    put("size", "0.05")
                    put("urgencyClass", "normal")
                }

                val response = client.postRaw("${config.txGatewayBaseUrl}/api/v1/exchanges/$exchange/order") {
                    header(HttpHeaders.Authorization, "Bearer ${resolvedAuth.bearerToken}")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                val json = stagedFixtureJson.parseToJsonElement(body).jsonObject

                assertFixtureContractMatchesResponse("live_paper_contract", fullFillFixture, json)

                val status = json["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(status in setOf("FILLED", "PARTIALLY_FILLED", "PENDING", "REJECTED")) {
                    "Unexpected paper order status '$status': $body"
                }

                val executionMode = json["executionMode"]?.jsonPrimitive?.contentOrNull
                executionMode shouldBe "paper"

                println(
                    "      ✓ Live paper order contract validated on $exchange/$symbol using ${resolvedAuth.source} (status=$status)"
                )

                writeSnapshotIfEnabled(
                    config = config,
                    scenario = "paper_order_${exchange}_${symbol}",
                    requestBody = requestBody,
                    responseStatus = response.status.value,
                    responseBody = body
                )
            }
        }
    }

    test("Stage 7: Live worker degradation signal surface") {
        val response = client.getRawResponse("${config.txGatewayBaseUrl}/health/workers")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        val json = stagedFixtureJson.parseToJsonElement(body).jsonObject

        val evm = json["evm_broadcaster"]?.jsonPrimitive?.contentOrNull
        val hyperliquid = json["hyperliquid_worker"]?.jsonPrimitive?.contentOrNull
        require(evm in setOf("reachable", "unreachable")) {
            "Unexpected evm worker status: $evm"
        }
        require(hyperliquid in setOf("reachable", "unreachable")) {
            "Unexpected hyperliquid worker status: $hyperliquid"
        }

        config.hyperliquidWorkerBaseUrl?.let { workerBaseUrl ->
            val workerHealth = client.getRawResponse("$workerBaseUrl/health")
            require(workerHealth.status == HttpStatusCode.OK) {
                "Direct hyperliquid-worker health probe failed: ${workerHealth.status}"
            }
        }

        writeSnapshotIfEnabled(config, "worker_health_probe", requestBody = null, response.status.value, body)
    }
}
