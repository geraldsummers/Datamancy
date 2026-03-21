package org.datamancy.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
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
import org.datamancy.testrunner.framework.AuthHelper
import org.datamancy.testrunner.framework.OIDCHelper
import org.datamancy.testrunner.framework.ServiceEndpoints
import org.datamancy.testrunner.framework.TestRunner
import java.io.File
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stagedFixtureJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

internal data class StagedTradingConfig(
    val mode: String,
    val runLiveProbes: Boolean,
    val targetHost: String,
    val txGatewayBaseUrl: String,
    val autheliaBaseUrl: String,
    val hyperliquidWorkerBaseUrl: String?,
    val strictHyperliquidCredentialChecks: Boolean,
    val prepareRiskStateBeforeOrders: Boolean,
    val recordResponses: Boolean,
    val recordDir: String
)

private data class ResolvedTradingAuth(
    val bearerToken: String,
    val source: String
)

private data class CapturedHttpResponse(
    val status: HttpStatusCode,
    val body: String,
    val json: JsonObject?
)

private data class AckRetryResult(
    val response: CapturedHttpResponse,
    val ackAttempted: Boolean,
    val retryExecuted: Boolean
)

internal fun envValue(
    name: String,
    env: Map<String, String> = System.getenv()
): String? =
    env[name]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun envFlag(
    name: String,
    defaultValue: Boolean,
    env: Map<String, String> = System.getenv()
): Boolean =
    envValue(name, env)
        ?.lowercase(Locale.US)
        ?.let { it == "1" || it == "true" || it == "yes" || it == "on" }
        ?: defaultValue

internal fun csvEnvValues(
    name: String,
    env: Map<String, String> = System.getenv()
): Set<String> =
    envValue(name, env)
        ?.split(',', ';', '|', ' ')
        ?.map { it.trim().lowercase(Locale.US) }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

internal fun stagedTradingConfig(
    endpoints: ServiceEndpoints,
    env: Map<String, String> = System.getenv()
): StagedTradingConfig {
    val mode = (envValue("TRADING_E2E_MODE", env) ?: "replay").lowercase(Locale.US)
    val runLive = mode == "live" || mode == "hybrid"
    val liveExternalUrls = envFlag("TRADING_E2E_LIVE_EXTERNAL_URLS", defaultValue = false, env = env)

    val targetHost = envValue("TRADING_E2E_TARGET_HOST", env)
        ?: envValue("DOMAIN", env)
        ?: "latium.local"

    val txGatewayBaseUrl = envValue("TRADING_E2E_TX_GATEWAY_URL", env)
        ?: when {
            !runLive -> endpoints.txGateway
            liveExternalUrls -> "https://tx-gateway.$targetHost"
            else -> endpoints.txGateway
        }

    val autheliaBaseUrl = envValue("TRADING_E2E_AUTHELIA_URL", env)
        ?: when {
            !runLive -> endpoints.authelia
            liveExternalUrls -> "https://auth.$targetHost"
            else -> endpoints.authelia
        }

    val hyperliquidWorkerBaseUrl = envValue("TRADING_E2E_HYPERLIQUID_WORKER_URL", env)
        ?: when {
            !runLive -> endpoints.hyperliquidWorker
            liveExternalUrls -> "https://hyperliquid-worker.$targetHost"
            else -> endpoints.hyperliquidWorker
        }

    val strictHyperliquidCredentialChecks = envFlag(
        "TRADING_E2E_HYPERLIQUID_STRICT_CREDENTIALS",
        defaultValue = runLive,
        env = env
    )

    val prepareRiskStateBeforeOrders = envFlag(
        "TRADING_E2E_PREP_RISK_STATE",
        defaultValue = runLive,
        env = env
    )

    val recordResponses = envValue("TRADING_E2E_RECORD", env)
        ?.lowercase(Locale.US)
        ?.let { it == "1" || it == "true" || it == "yes" }
        ?: false

    val recordDir = envValue("TRADING_E2E_RECORD_DIR", env) ?: "/tmp/trading-e2e-fixtures"

    return StagedTradingConfig(
        mode = mode,
        runLiveProbes = runLive,
        targetHost = targetHost,
        txGatewayBaseUrl = txGatewayBaseUrl.trimEnd('/'),
        autheliaBaseUrl = autheliaBaseUrl.trimEnd('/'),
        hyperliquidWorkerBaseUrl = hyperliquidWorkerBaseUrl?.trimEnd('/'),
        strictHyperliquidCredentialChecks = strictHyperliquidCredentialChecks,
        prepareRiskStateBeforeOrders = prepareRiskStateBeforeOrders,
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
    val response = httpClient.get("$txGatewayBaseUrl/api/v1/user") {
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

    val directMinted = mintTradingTokenViaDirectOidc(
        autheliaBaseUrl = config.autheliaBaseUrl,
        username = username,
        password = password,
        clientId = clientId,
        clientSecret = clientSecret,
        redirectUri = redirectUri,
        scope = scope
    )
    if (directMinted != null && isTradingTokenValid(directMinted, config.txGatewayBaseUrl)) {
        return ResolvedTradingAuth(
            bearerToken = directMinted,
            source = "oidc-direct:$clientId"
        )
    }

    val liveAuth = AuthHelper(config.autheliaBaseUrl, httpClient)
    val liveOidc = OIDCHelper(config.autheliaBaseUrl, httpClient, liveAuth)
    val login = liveAuth.login(username, password)
    if (login !is AuthResult.Success) {
        return null
    }

    val minted = runCatching {
        val code = liveOidc.getAuthorizationCode(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope
        )
        val tokens = liveOidc.exchangeCodeForTokens(
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            redirectUri = redirectUri
        )
        val accessToken = tokens.accessToken?.takeIf { isTradingTokenValid(it, config.txGatewayBaseUrl) }
        accessToken ?: tokens.idToken
    }.getOrNull() ?: return null

    if (!isTradingTokenValid(minted, config.txGatewayBaseUrl)) {
        return null
    }

    return ResolvedTradingAuth(
        bearerToken = minted,
        source = "oidc:$clientId"
    )
}

private suspend fun TestRunner.mintTradingTokenViaDirectOidc(
    autheliaBaseUrl: String,
    username: String,
    password: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    scope: String
): String? {
    val loginResponse = httpClient.post("$autheliaBaseUrl/api/firstfactor") {
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("keepMeLoggedIn", false)
            }.toString()
        )
    }
    if (loginResponse.status != HttpStatusCode.OK) {
        return null
    }

    val authorizationUrl = "$autheliaBaseUrl/api/oidc/authorization?${buildString {
        append("client_id=$clientId")
        append("&redirect_uri=${redirectUri.encodeURLParameter()}")
        append("&response_type=code")
        append("&scope=${scope.encodeURLParameter()}")
        append("&state=staged-direct")
    }}"
    val authorizationResponse = httpClient.get(authorizationUrl)
    if (authorizationResponse.status != HttpStatusCode.Found && authorizationResponse.status != HttpStatusCode.SeeOther) {
        return null
    }

    val location = authorizationResponse.headers["Location"] ?: return null
    val code = extractQueryParam(location, "code") ?: return null

    val tokenResponse = httpClient.post("$autheliaBaseUrl/api/oidc/token") {
        contentType(ContentType.Application.FormUrlEncoded)
        basicAuth(clientId, clientSecret)
        setBody(
            "grant_type=authorization_code" +
                "&code=${code.encodeURLParameter()}" +
                "&redirect_uri=${redirectUri.encodeURLParameter()}"
        )
    }
    if (tokenResponse.status != HttpStatusCode.OK) {
        return null
    }

    val body = tokenResponse.bodyAsText()
    val tokenJson = runCatching { stagedFixtureJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    return tokenJson["access_token"]?.jsonPrimitive?.contentOrNull
        ?: tokenJson["id_token"]?.jsonPrimitive?.contentOrNull
}

private fun extractQueryParam(location: String, key: String): String? {
    val match = Regex("""(?:\?|&)${Regex.escape(key)}=([^&]+)""").find(location) ?: return null
    return URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8)
}

private suspend fun TestRunner.selectPaperVenueWithQuote(
    txGatewayBaseUrl: String,
    bearerToken: String
): Pair<String, String>? {
    val userResponse = httpClient.get("$txGatewayBaseUrl/api/v1/user") {
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
            val quoteResponse = httpClient.get(
                "$txGatewayBaseUrl/api/v1/exchanges/$exchange/quote?symbol=$symbol"
            )
            if (quoteResponse.status == HttpStatusCode.OK) {
                return exchange to symbol
            }
        }
    }
    return null
}

private fun parseJsonObjectOrNull(raw: String): JsonObject? =
    runCatching { stagedFixtureJson.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun compactForLog(text: String, maxLength: Int = 220): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= maxLength) normalized else "${normalized.take(maxLength)}..."
}

private fun extractErrorContext(json: JsonObject?, rawBody: String): String {
    val directError = json?.get("error")?.jsonPrimitive?.contentOrNull
    val riskReason = (json?.get("risk") as? JsonObject)
        ?.get("reason")
        ?.jsonPrimitive
        ?.contentOrNull
    return listOfNotNull(directError, riskReason)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" | ")
        .ifBlank { rawBody.trim() }
}

private fun containsHyperliquidKeyFailureSignal(errorText: String): Boolean {
    val normalized = errorText.lowercase(Locale.US)
    val markers = listOf(
        "user or api wallet",
        "does not exist",
        "invalid signature",
        "invalid key",
        "missing hyperliquid credentials",
        "missing private key",
        "unable to derive address",
        "hyperliquidkey is empty"
    )
    return markers.any { normalized.contains(it) }
}

internal fun shouldFailOnHyperliquidCredentialError(
    strictCredentialChecks: Boolean,
    errorText: String
): Boolean = strictCredentialChecks && containsHyperliquidKeyFailureSignal(errorText)

private fun isManualAckRiskConflict(response: CapturedHttpResponse): Boolean {
    if (response.status != HttpStatusCode.Conflict) return false
    val context = extractErrorContext(response.json, response.body).lowercase(Locale.US)
    val markers = listOf(
        "manual acknowledgement required",
        "manual acknowledgment required",
        "kill switch engaged",
        "kill-switch",
        "hard risk kill-switch triggered"
    )
    return context.contains("risk") && markers.any { marker -> context.contains(marker) }
}

private suspend fun captureResponse(response: HttpResponse): CapturedHttpResponse {
    val body = response.bodyAsText()
    return CapturedHttpResponse(
        status = response.status,
        body = body,
        json = parseJsonObjectOrNull(body)
    )
}

private suspend fun TestRunner.ackRiskKillSwitch(
    config: StagedTradingConfig,
    bearerToken: String,
    stageName: String
): Boolean {
    val ackNote = "staged auto-ack for $stageName at ${Instant.now()}"
    val ackResponse = httpClient.post("${config.txGatewayBaseUrl}/api/v1/risk/kill-switch/ack") {
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("note", ackNote)
            }.toString()
        )
    }

    val capturedAck = captureResponse(ackResponse)
    return when (capturedAck.status) {
        HttpStatusCode.OK -> {
            println("      ✓ $stageName auto-acknowledged engaged risk kill-switch")
            true
        }
        HttpStatusCode.Conflict -> {
            println("      ℹ️  $stageName kill-switch ACK skipped (not currently engaged)")
            false
        }
        else -> error(
            "$stageName failed risk kill-switch ACK call (status=${capturedAck.status}, body=${capturedAck.body})"
        )
    }
}

private suspend fun TestRunner.prepareRiskStateForLiveOrders(
    config: StagedTradingConfig,
    bearerToken: String
) {
    val stageName = "Risk preflight"
    val riskStatePatch = buildJsonObject {
        put("accountEquityUsd", "100000")
        put("highWaterMarkUsd", "100000")
        put("realizedPnlUsd", "0")
        put("unrealizedPnlUsd", "0")
        put("dailyRealizedPnlUsd", "0")
        put("dailyUnrealizedPnlUsd", "0")
        put("openExposureUsd", "0")
    }

    val stateResponse = captureResponse(
        httpClient.put("${config.txGatewayBaseUrl}/api/v1/risk/state") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.Application.Json)
            setBody(riskStatePatch.toString())
        }
    )
    if (stateResponse.status == HttpStatusCode.OK) {
        println("      ✓ $stageName patched account state to nominal test values")
    } else {
        val errorContext = extractErrorContext(stateResponse.json, stateResponse.body)
        println(
            "      ℹ️  $stageName could not patch account state (status=${stateResponse.status.value}, detail=${compactForLog(errorContext)})"
        )
        return
    }

    val killSwitchState = captureResponse(
        httpClient.get("${config.txGatewayBaseUrl}/api/v1/risk/kill-switch") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
        }
    )
    if (killSwitchState.status == HttpStatusCode.OK) {
        val engaged = killSwitchState.json
            ?.get("engaged")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false
        if (engaged) {
            ackRiskKillSwitch(
                config = config,
                bearerToken = bearerToken,
                stageName = stageName
            )
        }
    }

    val previewResponse = captureResponse(
        httpClient.post("${config.txGatewayBaseUrl}/api/v1/risk/evaluate/order-preview") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("symbol", "BTC")
                    put("notionalUsd", "4000")
                    put("reduceOnly", false)
                }.toString()
            )
        }
    )
    if (previewResponse.status != HttpStatusCode.OK) {
        val errorContext = extractErrorContext(previewResponse.json, previewResponse.body)
        println(
            "      ℹ️  $stageName order-preview unavailable (status=${previewResponse.status.value}, detail=${compactForLog(errorContext)})"
        )
        return
    }

    val previewAllowed = previewResponse.json
        ?.get("allowed")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()
    val previewReason = previewResponse.json
        ?.get("reason")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: "no reason provided"

    if (previewAllowed == true) {
        println("      ✓ $stageName indicates live orders are currently allowed")
    } else {
        println("      ℹ️  $stageName indicates orders may still be blocked ($previewReason)")
    }
}

private suspend fun TestRunner.executeOrderWithAckRetryOnConflict(
    config: StagedTradingConfig,
    bearerToken: String,
    stageName: String,
    submitOrder: suspend () -> HttpResponse
): AckRetryResult {
    val firstAttempt = captureResponse(submitOrder())
    if (!isManualAckRiskConflict(firstAttempt)) {
        return AckRetryResult(response = firstAttempt, ackAttempted = false, retryExecuted = false)
    }

    val errorContext = extractErrorContext(firstAttempt.json, firstAttempt.body)
    println(
        "      ℹ️  $stageName received risk 409 requiring manual acknowledgement; attempting auto-ack (detail=$errorContext)"
    )
    val acked = ackRiskKillSwitch(
        config = config,
        bearerToken = bearerToken,
        stageName = stageName
    )
    if (!acked) {
        return AckRetryResult(response = firstAttempt, ackAttempted = true, retryExecuted = false)
    }

    val retryAttempt = captureResponse(submitOrder())
    return AckRetryResult(response = retryAttempt, ackAttempted = true, retryExecuted = true)
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
    val hyperliquidTestnetKey = envValue("TRADING_E2E_HYPERLIQUID_KEY")
        ?: envValue("HYPERLIQUID_TESTNET_KEY")
    if (resolvedAuth == null) {
        skip(
            "Stage 5b: Live trading account homogeneity probe",
            "No compatible tx-gateway bearer token found (set TRADING_E2E_BEARER_TOKEN or OIDC vars)"
        )
        skip(
            "Stage 6: Live authenticated paper order contract",
            "No compatible tx-gateway bearer token found (set TRADING_E2E_BEARER_TOKEN or OIDC vars)"
        )
        skip(
            "Stage 6b: Live authenticated hyperliquid testnet order contract",
            "No compatible tx-gateway bearer token found (set TRADING_E2E_BEARER_TOKEN or OIDC vars)"
        )
    } else {
        if (config.prepareRiskStateBeforeOrders) {
            prepareRiskStateForLiveOrders(
                config = config,
                bearerToken = resolvedAuth.bearerToken
            )
        }

        test("Stage 5b: Live trading account homogeneity probe") {
            val response = httpClient.get("${config.txGatewayBaseUrl}/api/v1/accounts/trading/homogeneity") {
                header(HttpHeaders.Authorization, "Bearer ${resolvedAuth.bearerToken}")
            }
            response.status shouldBe HttpStatusCode.OK

            val body = response.bodyAsText()
            val json = stagedFixtureJson.parseToJsonElement(body).jsonObject
            val accounts = json["accounts"]?.jsonArray ?: error("Homogeneity response missing accounts array")
            val expectedTradingUsers = buildSet {
                envValue("STACK_ADMIN_USER")?.lowercase(Locale.US)?.let { add(it) }
                addAll(csvEnvValues("LDAP_MANAGED_TRADING_USERS"))
            }

            require(expectedTradingUsers.isNotEmpty()) {
                "Expected at least one managed trading user to validate"
            }

            val accountsByUser = accounts.mapNotNull { element ->
                val account = element.jsonObject
                val username = account["username"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.US)
                username?.let { it to account }
            }.toMap()

            expectedTradingUsers.forEach { username ->
                val account = accountsByUser[username]
                    ?: error("Managed trading user '$username' missing from homogeneity audit")
                val hasTradingProfile = account["hasTradingProfile"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                val hasTradingObjectClass = account["hasTradingObjectClass"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                val findings = account["findings"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()

                require(hasTradingProfile == true) {
                    "Managed trading user '$username' is missing a trading profile: $account"
                }
                require(hasTradingObjectClass == true) {
                    "Managed trading user '$username' is missing tradingAccount objectClass: $account"
                }
                require(findings.isEmpty()) {
                    "Managed trading user '$username' has homogeneity findings: ${findings.joinToString(" | ")}"
                }
            }

            writeSnapshotIfEnabled(
                config = config,
                scenario = "trading_account_homogeneity",
                requestBody = null,
                responseStatus = response.status.value,
                responseBody = body
            )
        }

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
                    put("executionMode", "forward_paper")
                    put("urgencyClass", "normal")
                }

                val orderResult = executeOrderWithAckRetryOnConflict(
                    config = config,
                    bearerToken = resolvedAuth.bearerToken,
                    stageName = "Stage 6 paper order"
                ) {
                    httpClient.post("${config.txGatewayBaseUrl}/api/v1/exchanges/$exchange/order") {
                        header(HttpHeaders.Authorization, "Bearer ${resolvedAuth.bearerToken}")
                        contentType(ContentType.Application.Json)
                        setBody(requestBody.toString())
                    }
                }

                val body = orderResult.response.body
                val json = orderResult.response.json
                val retrySuffix = if (orderResult.retryExecuted) " after auto-ack retry" else ""

                when (orderResult.response.status) {
                    HttpStatusCode.OK -> {
                        require(json != null) { "Expected JSON body for successful paper response: $body" }
                        assertFixtureContractMatchesResponse("live_paper_contract", fullFillFixture, json)

                        val status = json["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        require(status in setOf("FILLED", "PARTIALLY_FILLED", "PENDING", "REJECTED")) {
                            "Unexpected paper order status '$status': $body"
                        }

                        val executionMode = json["executionMode"]?.jsonPrimitive?.contentOrNull
                        executionMode shouldBe "forward_paper"

                        println(
                            "      ✓ Live paper order contract validated on $exchange/$symbol using ${resolvedAuth.source} (status=$status$retrySuffix)"
                        )
                    }
                    HttpStatusCode.Conflict -> {
                        val errorContext = extractErrorContext(json, body)
                        require(errorContext.contains("risk", ignoreCase = true)) {
                            "Unexpected conflict response for paper order: $body"
                        }
                        println(
                            "      ✓ Live paper order was safely blocked by risk controls on $exchange/$symbol using ${resolvedAuth.source} (status=409, detail=$errorContext$retrySuffix)"
                        )
                    }
                    else -> error("Unexpected paper order status=${orderResult.response.status} body=$body")
                }

                writeSnapshotIfEnabled(
                    config = config,
                    scenario = "paper_order_${exchange}_${symbol}",
                    requestBody = requestBody,
                    responseStatus = orderResult.response.status.value,
                    responseBody = body
                )
            }
        }

        if (hyperliquidTestnetKey == null) {
            skip(
                "Stage 6b: Live authenticated hyperliquid testnet order contract",
                "Set TRADING_E2E_HYPERLIQUID_KEY (or HYPERLIQUID_TESTNET_KEY) to run signed testnet order checks"
            )
        } else {
            test("Stage 6b: Live authenticated hyperliquid testnet order contract") {
                config.hyperliquidWorkerBaseUrl?.let { workerBaseUrl ->
                    val workerHealth = client.getRawResponse("$workerBaseUrl/health")
                    require(workerHealth.status == HttpStatusCode.OK) {
                        "hyperliquid-worker health probe failed: ${workerHealth.status}"
                    }
                    val workerJson = parseJsonObjectOrNull(workerHealth.bodyAsText())
                        ?: error("hyperliquid-worker health payload is not JSON")
                    val isMainnet = workerJson["mainnet"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    require(isMainnet == false) {
                        "hyperliquid-worker is not in testnet mode (mainnet=$isMainnet)"
                    }
                }

                val requestBody = buildJsonObject {
                    put("symbol", "BTC")
                    put("side", "BUY")
                    put("type", "MARKET")
                    put("size", "0.001")
                    put("executionMode", "testnet_live")
                    put("urgencyClass", "normal")
                    put("maxSlippageBps", 35.0)
                }

                val orderResult = executeOrderWithAckRetryOnConflict(
                    config = config,
                    bearerToken = resolvedAuth.bearerToken,
                    stageName = "Stage 6b hyperliquid testnet order"
                ) {
                    httpClient.post("${config.txGatewayBaseUrl}/api/v1/exchanges/hyperliquid/order") {
                        header(HttpHeaders.Authorization, "Bearer ${resolvedAuth.bearerToken}")
                        header("X-Credential-hyperliquid", hyperliquidTestnetKey)
                        contentType(ContentType.Application.Json)
                        setBody(requestBody.toString())
                    }
                }

                val body = orderResult.response.body
                val json = orderResult.response.json
                val retrySuffix = if (orderResult.retryExecuted) " after auto-ack retry" else ""

                require(orderResult.response.status !in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                    "Hyperliquid testnet order failed auth/authorization: status=${orderResult.response.status} body=$body"
                }

                when (orderResult.response.status) {
                    HttpStatusCode.OK -> {
                        require(json != null) { "Expected JSON body for success response: $body" }
                        val status = json["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        require(status.isNotBlank()) { "Missing status in success payload: $body" }
                        val executionMode = json["executionMode"]?.jsonPrimitive?.contentOrNull
                        if (executionMode != null) {
                            executionMode shouldBe "testnet_live"
                        }
                        println(
                            "      ✓ Hyperliquid testnet order path reached exchange worker using ${resolvedAuth.source} (status=$status$retrySuffix)"
                        )
                    }
                    HttpStatusCode.BadRequest,
                    HttpStatusCode.Conflict,
                    HttpStatusCode.ServiceUnavailable,
                    HttpStatusCode.InternalServerError -> {
                        val error = extractErrorContext(json, body)
                        require(error.isNotBlank()) {
                            "Expected explanatory error payload for status=${orderResult.response.status}, got: $body"
                        }
                        val credentialRejected = containsHyperliquidKeyFailureSignal(error)
                        if (shouldFailOnHyperliquidCredentialError(config.strictHyperliquidCredentialChecks, error)) {
                            error("Hyperliquid testnet key/wallet was rejected by exchange path: $error")
                        }
                        require(!error.contains("Serializing collections of different element types", ignoreCase = true)) {
                            "tx-gateway response serialization failure encountered: $error"
                        }
                        if (credentialRejected) {
                            println(
                                "      ~ Hyperliquid credentials were rejected by exchange path; strict mode was disabled for this run (set TRADING_E2E_HYPERLIQUID_STRICT_CREDENTIALS=true to fail, or leave it unset in live/hybrid mode to fail by default)."
                            )
                        } else {
                            println(
                                "      ✓ Hyperliquid testnet order path reached worker and returned controlled rejection (status=${orderResult.response.status.value}, error=$error$retrySuffix)"
                            )
                        }
                    }
                    else -> error("Unexpected hyperliquid testnet order status=${orderResult.response.status} body=$body")
                }

                writeSnapshotIfEnabled(
                    config = config,
                    scenario = "hyperliquid_testnet_live_order",
                    requestBody = requestBody,
                    responseStatus = orderResult.response.status.value,
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
