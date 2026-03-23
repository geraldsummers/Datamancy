package org.datamancy.txgateway.routes

import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.datamancy.txgateway.models.OrderRequest
import org.datamancy.txgateway.services.AuthService
import org.datamancy.txgateway.services.CredentialResolver
import org.datamancy.txgateway.services.DatabaseService
import org.datamancy.txgateway.services.LdapService
import org.datamancy.txgateway.services.LatestQuote
import org.datamancy.txgateway.services.RiskPolicyRecord
import org.datamancy.txgateway.services.TradingAccountAudit
import org.datamancy.txgateway.services.TradingPermissionCatalog
import org.datamancy.txgateway.services.RiskDecision
import org.datamancy.txgateway.services.RiskEngineService
import org.datamancy.txgateway.services.RiskAccountStatePatch
import org.datamancy.txgateway.services.StrategyExecutionBaseline
import org.datamancy.txgateway.services.TradingTelemetryMetrics
import org.datamancy.txgateway.services.WorkerClient
import org.datamancy.txgateway.services.parseBooleanFlag
import org.datamancy.txgateway.services.resolveHyperliquidQuoteExchangeCandidates
import org.datamancy.txgateway.services.resolveHyperliquidQuoteExchange
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("UnifiedExchangeRoutes")
private val dynamicJson = Gson()

private val knownExchanges = TradingPermissionCatalog.knownExchanges
private val paperOrderExchanges = TradingPermissionCatalog.paperOrderExchanges
private val marketDataIngressExchanges = TradingPermissionCatalog.marketDataIngressExchanges
private val nativeOrderExchanges = TradingPermissionCatalog.nativeOrderExchanges
private val liveOrderExchanges = TradingPermissionCatalog.liveOrderExchanges
private val defaultBestQuoteExchanges = TradingPermissionCatalog.bestQuoteDefaultExchanges.toList()
private val mainnetReservedGroups = TradingPermissionCatalog.mainnetReservedGroups
private val tradingAdminGroups = TradingPermissionCatalog.tradingAdminGroups
private val maxQuoteAgeMs: Long = System.getenv("TX_GATEWAY_MAX_QUOTE_AGE_MS")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.toLongOrNull()
    ?.coerceAtLeast(1L)
    ?: 300_000L
private val symbolPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$")
private val maxOrderSize = BigDecimal("1000000000")
private val maxOrderPrice = BigDecimal("1000000000")
private const val PAPER_EXECUTION_STRATEGY = "tx_gateway_paper_execution"
private const val LIVE_EXECUTION_STRATEGY = "tx_gateway_live_execution"
private const val FORWARD_PAPER_EXECUTION_MODE = "forward_paper"
private val liveExecutionModes = setOf("testnet_live", "mainnet_live")
private val hyperliquidDefaultLiveExecutionMode = if (
    parseBooleanFlag(
        raw = System.getenv("HYPERLIQUID_MAINNET"),
        defaultValue = false
    )
) {
    "mainnet_live"
} else {
    "testnet_live"
}

@Serializable
private data class UserResponse(
    val username: String,
    val email: String,
    val groups: List<String>,
    val allowedChains: List<String>,
    val allowedExchanges: List<String>,
    val allowedTradingModes: List<String>,
    val maxTxPerHour: Int,
    val maxTxValueUSD: Int,
    val evmAddress: String?
)

@Serializable
private data class HistoryDetails(
    val request: String,
    val response: String? = null,
    val error: String? = null
)

@Serializable
private data class HistoryItem(
    val id: String,
    val timestamp: String,
    val type: String,
    val description: String,
    val status: String,
    val details: HistoryDetails
)

@Serializable
private data class ExchangeCapabilities(
    val paperOrder: Boolean,
    val liveOrder: Boolean,
    val nativeOrderAdapter: Boolean,
    val marketDataIngress: Boolean,
    val bestQuoteDefault: Boolean
)

@Serializable
private data class ExchangeDescriptor(
    val name: String,
    val apiName: String,
    val implementationStatus: String,
    val liveOrder: Boolean,
    val capabilities: ExchangeCapabilities,
    val supportedExecutionModes: List<String>,
    val defaultExecutionMode: String,
    val notes: String
)

@Serializable
private data class ExchangeCatalogResponse(
    val exchanges: List<ExchangeDescriptor>
)

@Serializable
private data class ExchangeMarketDescriptor(
    val symbol: String,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
private data class ExchangeMarketsResponse(
    val exchange: String,
    val count: Int,
    val markets: List<ExchangeMarketDescriptor>
)

@Serializable
private data class ErrorResponse(
    val error: String
)

@Serializable
private data class RequiredGroupsErrorResponse(
    val error: String,
    val requiredGroups: List<String>
)

@Serializable
private data class TradingAccountHomogeneitySummaryResponse(
    val totalAccounts: Int,
    val tradingAccounts: Int,
    val accountsWithFindings: Int,
    val accountsWithUnsupportedPermissions: Int,
    val accountsWithMainnetLive: Int,
    val accountsMissingTradingObjectClass: Int
)

@Serializable
private data class TradingAccountHomogeneityResponse(
    val requestedBy: String,
    val requestedAt: String,
    val mainnetReservedGroups: List<String>,
    val canonicalAllowedChains: List<String>,
    val canonicalAllowedExchanges: List<String>,
    val canonicalAllowedTradingModes: List<String>,
    val summary: TradingAccountHomogeneitySummaryResponse,
    val accounts: List<TradingAccountAudit>
)

@Serializable
private data class WalletLinkedRiskPolicyResponse(
    val id: String,
    val username: String,
    val walletAddress: String? = null,
    val version: Int,
    val status: String,
    val createdBy: String,
    val createdAt: String,
    val activatedAt: String? = null,
    val activatedByWallet: String? = null,
    val isBootstrap: Boolean
)

@Serializable
private data class UserWalletLinkResponse(
    val requestedBy: String,
    val requestedAt: String,
    val linkedWallets: List<String>,
    val draftPolicyCount: Int,
    val activePolicy: WalletLinkedRiskPolicyResponse? = null,
    val ldapProfile: TradingAccountAudit,
    val findings: List<String>
)

@Serializable
private data class TradingAccountWalletLinkSummaryResponse(
    val activeWalletLinks: Int,
    val uniqueWallets: Int,
    val walletsLinkedToMultipleUsers: Int,
    val walletLinksDifferingFromLdap: Int,
    val walletLinksMissingLdapEvmAddress: Int
)

@Serializable
private data class TradingAccountWalletLinkEntryResponse(
    val username: String,
    val email: String,
    val ldapEvmAddress: String? = null,
    val walletAddress: String,
    val groups: List<String>,
    val allowedExchanges: List<String>,
    val allowedTradingModes: List<String>,
    val policyId: String,
    val policyVersion: Int,
    val activatedAt: String? = null,
    val findings: List<String>
)

@Serializable
private data class TradingAccountWalletLinksResponse(
    val requestedBy: String,
    val requestedAt: String,
    val summary: TradingAccountWalletLinkSummaryResponse,
    val links: List<TradingAccountWalletLinkEntryResponse>
)

@Serializable
private data class QuoteResponse(
    val exchange: String,
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val last: Double,
    val timestamp: String,
    val source: String
)

@Serializable
private data class BestQuoteResponse(
    val requestedSymbol: String,
    val normalizedSymbol: String,
    val side: String,
    val selectedExchange: String,
    val quote: QuoteResponse,
    val comparedExchanges: List<String>
)

@Serializable
private data class PaperQuoteSnapshot(
    val bid: Double,
    val ask: Double,
    val last: Double,
    val timestamp: String,
    val source: String
)

@Serializable
private data class PaperOrderResponse(
    val orderId: String,
    val status: String,
    val requestedSize: String,
    val filledSize: String,
    val remainingSize: String,
    val fillRatio: Double,
    val fillPrice: String? = null,
    val exchange: String,
    val symbol: String,
    val side: String,
    val type: String,
    val simulated: Boolean,
    val executionMode: String,
    val rejectionReason: String? = null,
    val quoteAgeMs: Long,
    val quote: PaperQuoteSnapshot,
    val policy: PaperExecutionPolicy,
    val costs: PaperCostBreakdown,
    val telemetry: PaperExecutionTelemetry,
    val simulation: PaperExecutionSimulation,
    val receivedAt: String
)

@Serializable
private data class PaperExecutionPolicy(
    val venue: String,
    val orderType: String,
    val urgencyClass: String,
    val cancelCadenceMs: Long,
    val placementSchedule: List<PaperPlacementSlice>
)

@Serializable
private data class PaperPlacementSlice(
    val delayMs: Long,
    val price: String,
    val sizePercent: Int
)

@Serializable
private data class PaperCostBreakdown(
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val appliedFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double
)

@Serializable
private data class PaperExecutionTelemetry(
    val decisionLatencyMs: Long,
    val submitToAckMs: Long,
    val submitToFirstFillMs: Long? = null,
    val submitToFinalFillMs: Long? = null,
    val cancelReplaceLatencyMs: Long,
    val p50RoundTripMs: Long,
    val p95RoundTripMs: Long,
    val p99RoundTripMs: Long,
    val jitterMs: Long
)

@Serializable
private data class PaperExecutionSimulation(
    val queuePositionEstimate: Int,
    val expectedFillProbability: Double,
    val projectedPartialFills: List<PaperFillSlice>,
    val failToFillReason: String? = null,
    val regimeBucket: String,
    val liquidityBucket: String
)

@Serializable
private data class PaperFillSlice(
    val delayMs: Long,
    val size: String,
    val price: String
)

@Serializable
private data class RiskRejectionResponse(
    val error: String,
    val risk: RiskRejectionDetails
)

@Serializable
private data class RiskRejectionDetails(
    val allowed: Boolean,
    val tier: String,
    val action: String,
    val reason: String,
    val policyId: String? = null,
    val policyVersion: Int? = null,
    val suggestedMaxOrderNotionalUsd: String? = null,
    val unwindSliceSeconds: Int? = null,
    val unwindMaxSlippageBps: Double? = null,
    val metrics: RiskRejectionMetrics,
    val sentiment: RiskRejectionSentiment? = null
)

@Serializable
private data class RiskRejectionMetrics(
    val currentExposureUsd: String,
    val projectedExposureUsd: String,
    val accountEquityUsd: String,
    val highWaterMarkUsd: String,
    val dailyLossUsd: String,
    val leverage: Double,
    val exposureUtilization: Double,
    val drawdownPct: Double,
    val drawdownUtilization: Double,
    val dailyLossUtilization: Double,
    val leverageUtilization: Double
)

@Serializable
private data class RiskRejectionSentiment(
    val symbol: String,
    val sentimentScore: Double,
    val confidence: Double,
    val observedAt: String,
    val modelName: String? = null,
    val source: String? = null,
    val label: String? = null
)

private enum class UrgencyClass {
    LOW,
    NORMAL,
    HIGH
}

private enum class FeeTier {
    RETAIL,
    PRO,
    VIP
}

fun Route.unifiedExchangeRoutes(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService,
    tradingTelemetryMetrics: TradingTelemetryMetrics? = null,
    requiredHyperliquidQuoteExchange: String? = resolveHyperliquidQuoteExchange(
        explicitExchange = System.getenv("HYPERLIQUID_QUOTE_EXCHANGE"),
        mainnetFlag = System.getenv("HYPERLIQUID_MAINNET")
    ),
    credentialResolver: CredentialResolver = CredentialResolver(ldapService = ldapService)
) {
    val riskEngine = RiskEngineService(dbService)
    route("/api/v1") {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf("status" to "healthy", "service" to "tx-gateway")
            )
        }

        get("/user") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val userInfo = ldapService.getUserInfo(username)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@get
                }

            call.respond(
                HttpStatusCode.OK,
                UserResponse(
                    username = userInfo.username,
                    email = userInfo.email,
                    groups = userInfo.groups,
                    allowedChains = userInfo.allowedChains,
                    allowedExchanges = userInfo.allowedExchanges,
                    allowedTradingModes = userInfo.allowedTradingModes,
                    maxTxPerHour = userInfo.maxTxPerHour,
                    maxTxValueUSD = userInfo.maxTxValueUSD,
                    evmAddress = userInfo.evmAddress
                )
            )
        }

        get("/user/trading-profile") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val audit = ldapService.getTradingAccountAudit(username)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@get
                }

            call.respond(HttpStatusCode.OK, audit)
        }

        get("/user/wallet-link") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val audit = ldapService.getTradingAccountAudit(username)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@get
                }

            val policies = dbService.listRiskPolicies(username = username, includeBootstrap = false)
            val activePolicy = policies.firstOrNull { it.status.equals("active", ignoreCase = true) }
            val linkedWallets = policies.mapNotNull { record ->
                record.walletAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }.distinct().sorted()
            val findings = buildUserWalletLinkFindings(audit, activePolicy, linkedWallets)

            call.respond(
                HttpStatusCode.OK,
                UserWalletLinkResponse(
                    requestedBy = username,
                    requestedAt = Instant.now().toString(),
                    linkedWallets = linkedWallets,
                    draftPolicyCount = policies.count { it.status.equals("draft", ignoreCase = true) },
                    activePolicy = activePolicy?.toWalletLinkedRiskPolicyResponse(),
                    ldapProfile = audit,
                    findings = findings
                )
            )
        }

        route("/accounts/trading") {
            get("/homogeneity") {
                val caller = extractAuthorizedTradingAdmin(call, authService, ldapService) ?: return@get
                val audits = ldapService.listTradingAccountAudits()
                val summary = TradingAccountHomogeneitySummaryResponse(
                    totalAccounts = audits.size,
                    tradingAccounts = audits.count { it.hasTradingProfile },
                    accountsWithFindings = audits.count { it.findings.isNotEmpty() },
                    accountsWithUnsupportedPermissions = audits.count { audit ->
                        audit.findings.any { finding -> "unsupported values" in finding }
                    },
                    accountsWithMainnetLive = audits.count { "mainnet_live" in it.allowedTradingModes },
                    accountsMissingTradingObjectClass = audits.count { it.hasTradingProfile && !it.hasTradingObjectClass }
                )

                call.respond(
                    HttpStatusCode.OK,
                    TradingAccountHomogeneityResponse(
                        requestedBy = caller.username,
                        requestedAt = Instant.now().toString(),
                        mainnetReservedGroups = mainnetReservedGroups.toList().sorted(),
                        canonicalAllowedChains = TradingPermissionCatalog.defaultAllowedChains,
                        canonicalAllowedExchanges = TradingPermissionCatalog.defaultAllowedExchanges,
                        canonicalAllowedTradingModes = TradingPermissionCatalog.defaultAllowedTradingModes,
                        summary = summary,
                        accounts = audits
                    )
                )
            }

            get("/wallet-links") {
                val caller = extractAuthorizedTradingAdmin(call, authService, ldapService) ?: return@get
                val audits = ldapService.listTradingAccountAudits()
                    .associateBy { it.username.lowercase() }
                val linkedPolicies = dbService.listActiveWalletLinkedRiskPolicies()
                val walletOwners = linkedPolicies.groupBy { it.walletAddress!!.trim().lowercase() }
                    .mapValues { (_, policies) -> policies.map { it.username }.distinct().sorted() }

                val links = linkedPolicies.map { policy ->
                    val audit = audits[policy.username.lowercase()]
                    val walletAddress = policy.walletAddress!!.trim().lowercase()
                    val duplicateOwners = walletOwners[walletAddress].orEmpty()
                        .filterNot { it.equals(policy.username, ignoreCase = true) }
                    val findings = buildAdminWalletLinkFindings(audit, walletAddress, duplicateOwners)

                    TradingAccountWalletLinkEntryResponse(
                        username = policy.username,
                        email = audit?.email ?: "${policy.username}@datamancy.net",
                        ldapEvmAddress = audit?.evmAddress,
                        walletAddress = walletAddress,
                        groups = audit?.groups ?: emptyList(),
                        allowedExchanges = audit?.allowedExchanges ?: emptyList(),
                        allowedTradingModes = audit?.allowedTradingModes ?: emptyList(),
                        policyId = policy.id.toString(),
                        policyVersion = policy.version,
                        activatedAt = policy.activatedAt?.toString(),
                        findings = findings
                    )
                }.sortedBy { it.username }

                val summary = TradingAccountWalletLinkSummaryResponse(
                    activeWalletLinks = links.size,
                    uniqueWallets = walletOwners.size,
                    walletsLinkedToMultipleUsers = walletOwners.count { (_, owners) -> owners.size > 1 },
                    walletLinksDifferingFromLdap = links.count { link ->
                        link.findings.any { it == "active wallet link differs from LDAP evmAddress" }
                    },
                    walletLinksMissingLdapEvmAddress = links.count { link ->
                        link.findings.any { it == "LDAP trading account has no evmAddress" }
                    }
                )

                call.respond(
                    HttpStatusCode.OK,
                    TradingAccountWalletLinksResponse(
                        requestedBy = caller.username,
                        requestedAt = Instant.now().toString(),
                        summary = summary,
                        links = links
                    )
                )
            }
        }

        get("/history") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            val records = dbService.getTransactionHistory(username = username, days = days)

            val mapped = records.map { record ->
                HistoryItem(
                    id = record.id.toString(),
                    timestamp = record.timestamp.toString(),
                    type = record.txType,
                    description = "${record.txType}:${record.status}",
                    status = record.status,
                    details = HistoryDetails(
                        request = record.request,
                        response = record.response,
                        error = record.errorMessage
                    )
                )
            }
            call.respond(HttpStatusCode.OK, mapped)
        }

        route("/exchanges") {
            get {
                val payload = knownExchanges.map { exchange ->
                    ExchangeDescriptor(
                        name = exchange,
                        apiName = exchange,
                        implementationStatus = TradingPermissionCatalog.implementationStatus(exchange),
                        liveOrder = liveOrderExchanges.contains(exchange),
                        capabilities = ExchangeCapabilities(
                            paperOrder = paperOrderExchanges.contains(exchange),
                            liveOrder = liveOrderExchanges.contains(exchange),
                            nativeOrderAdapter = nativeOrderExchanges.contains(exchange),
                            marketDataIngress = marketDataIngressExchanges.contains(exchange),
                            bestQuoteDefault = exchange in defaultBestQuoteExchanges
                        ),
                        supportedExecutionModes = supportedExecutionModes(exchange),
                        defaultExecutionMode = defaultExecutionModeForExchange(exchange),
                        notes = TradingPermissionCatalog.implementationNotes(exchange)
                    )
                }
                call.respond(HttpStatusCode.OK, ExchangeCatalogResponse(exchanges = payload))
            }

            get("/best-quote") {
                val symbol = call.request.queryParameters["symbol"]?.trim()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                        return@get
                    }
                if (symbol.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                    return@get
                }

                val sideRaw = call.request.queryParameters["side"]?.trim()?.lowercase().orEmpty()
                val side = when (sideRaw) {
                    "", "buy" -> "buy"
                    "sell" -> "sell"
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid side: $sideRaw"))
                        return@get
                    }
                }

                val requestedExchanges = call.request.queryParameters["exchanges"]
                    ?.split(",")
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.ifEmpty { null }

                val exchangesToScan = requestedExchanges ?: defaultBestQuoteExchanges
                val unsupported = exchangesToScan.filterNot { it in knownExchanges }
                if (unsupported.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Unsupported exchange(s): ${unsupported.joinToString(", ")}")
                    )
                    return@get
                }

                val requestedExecutionMode = call.request.queryParameters["executionMode"]
                val exchangeExecutionModes = mutableMapOf<String, String>()
                for (exchange in exchangesToScan) {
                    val resolvedExecutionMode = resolveExecutionMode(exchange, requestedExecutionMode)
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Invalid executionMode '$requestedExecutionMode' for $exchange")
                            )
                            return@get
                        }
                    exchangeExecutionModes[exchange] = resolvedExecutionMode
                }

                val candidateQuotes = exchangesToScan.mapNotNull { exchange ->
                    val quote = resolveQuoteWithPaperFallback(
                        dbService = dbService,
                        exchange = exchange,
                        symbol = symbol,
                        executionMode = exchangeExecutionModes.getValue(exchange)
                    ) ?: return@mapNotNull null

                    if (!quote.isValidSnapshot()) {
                        logger.warn(
                            "Ignoring invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                            quote.exchange,
                            quote.symbol,
                            quote.bid,
                            quote.ask,
                            quote.last
                        )
                        return@mapNotNull null
                    }

                    quote
                }

                if (candidateQuotes.isEmpty()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Quote unavailable",
                            "symbol" to symbol,
                            "side" to side,
                            "exchangesScanned" to exchangesToScan.joinToString(",")
                        )
                    )
                    return@get
                }

                val freshQuotes = candidateQuotes.filter { it.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs) }
                val servingStaleQuotes = freshQuotes.isEmpty()
                val quotes = if (servingStaleQuotes) {
                    candidateQuotes.map { it.withStaleSourceTag() }
                } else {
                    freshQuotes
                }

                if (servingStaleQuotes) {
                    logger.warn(
                        "Serving stale best-quote snapshot for symbol={} side={} exchanges={} maxQuoteAgeMs={}",
                        symbol,
                        side,
                        exchangesToScan.joinToString(","),
                        maxQuoteAgeMs
                    )
                }

                val best = if (side == "buy") {
                    quotes.minByOrNull { it.ask }
                } else {
                    quotes.maxByOrNull { it.bid }
                } ?: quotes.first()

                val quotePayload = QuoteResponse(
                    exchange = best.exchange,
                    symbol = best.symbol,
                    bid = best.bid,
                    ask = best.ask,
                    last = best.last,
                    timestamp = best.timestamp.toString(),
                    source = best.source
                )
                call.respond(
                    HttpStatusCode.OK,
                    BestQuoteResponse(
                        requestedSymbol = symbol,
                        normalizedSymbol = best.symbol,
                        side = side,
                        selectedExchange = best.exchange,
                        quote = quotePayload,
                        comparedExchanges = quotes.map { it.exchange }.distinct()
                    )
                )
            }

            get("/{exchange}/quote") {
                val exchange = call.parameters["exchange"]?.lowercase()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing exchange"))
                        return@get
                    }
                if (exchange !in knownExchanges) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unsupported exchange: $exchange")
                    )
                    return@get
                }

                val symbol = call.request.queryParameters["symbol"]?.trim()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                        return@get
                    }
                if (symbol.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                    return@get
                }

                val requestedExecutionMode = call.request.queryParameters["executionMode"]
                val executionMode = resolveExecutionMode(exchange, requestedExecutionMode)
                    ?: run {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid executionMode: $requestedExecutionMode")
                        )
                        return@get
                    }

                val quote = resolveQuoteWithPaperFallback(
                    dbService = dbService,
                    exchange = exchange,
                    symbol = symbol,
                    executionMode = executionMode
                )
                if (quote == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Quote unavailable",
                            "exchange" to exchange,
                            "symbol" to symbol
                        )
                    )
                    return@get
                }
                if (!quote.isValidSnapshot()) {
                    logger.warn(
                        "Rejecting invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                        quote.exchange,
                        quote.symbol,
                        quote.bid,
                        quote.ask,
                        quote.last
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Invalid quote snapshot",
                            "exchange" to exchange,
                            "symbol" to symbol
                        )
                    )
                    return@get
                }

                if (!quote.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)) {
                    logger.warn(
                        "Rejecting stale quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                        quote.exchange,
                        quote.symbol,
                        quote.quoteAgeMs(),
                        maxQuoteAgeMs
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Stale quote snapshot",
                            "exchange" to exchange,
                            "symbol" to symbol,
                            "quoteAgeMs" to quote.quoteAgeMs().toString(),
                            "maxQuoteAgeMs" to maxQuoteAgeMs.toString()
                        )
                    )
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    QuoteResponse(
                        exchange = exchange,
                        symbol = quote.symbol,
                        bid = quote.bid,
                        ask = quote.ask,
                        last = quote.last,
                        timestamp = quote.timestamp.toString(),
                        source = quote.source
                    )
                )
            }

            get("/{exchange}/markets") {
                val exchange = call.parameters["exchange"]?.lowercase()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing exchange"))
                        return@get
                    }
                if (exchange !in knownExchanges) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unsupported exchange: $exchange")
                    )
                    return@get
                }
                if (exchange !in marketDataIngressExchanges) {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        ErrorResponse("Market catalog unavailable for exchange: $exchange")
                    )
                    return@get
                }

                val markets = runCatching {
                    when (exchange) {
                        "hyperliquid" -> workerClient.getHyperliquidMarkets()
                        else -> emptyList()
                    }
                }.getOrElse { ex ->
                    logger.warn("Failed to fetch market catalog for exchange={}", exchange, ex)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse("Failed to fetch market catalog for exchange: $exchange")
                    )
                    return@get
                }

                val normalized = markets.mapNotNull(::normalizeExchangeMarketDescriptor)
                    .sortedBy { it.symbol }

                call.respond(
                    HttpStatusCode.OK,
                    ExchangeMarketsResponse(
                        exchange = exchange,
                        count = normalized.size,
                        markets = normalized
                    )
                )
            }

            post("/{exchange}/order") {
                val exchange = call.parameters["exchange"]?.lowercase()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing exchange"))
                        return@post
                    }
                if (exchange !in knownExchanges) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unsupported exchange: $exchange")
                    )
                    return@post
                }

                val username = extractAuthenticatedUsername(call, authService) ?: return@post
                val userInfo = ldapService.getUserInfo(username)
                    ?: run {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "User not found"))
                        return@post
                    }

                val allowedExchanges = userInfo.allowedExchanges.map { it.lowercase() }.toSet()
                if (exchange !in allowedExchanges) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Exchange not allowed: $exchange")
                    )
                    return@post
                }

                if (!dbService.checkRateLimit(username, userInfo.maxTxPerHour)) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                    return@post
                }

                val orderRequest = call.receive<OrderRequest>()
                val orderValidationError = validateOrderRequest(orderRequest)
                if (orderValidationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to orderValidationError))
                    return@post
                }
                val executionControlError = validateExecutionControls(orderRequest)
                if (executionControlError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to executionControlError))
                    return@post
                }
                val executionMode = resolveExecutionMode(
                    exchange = exchange,
                    requestedExecutionMode = orderRequest.executionMode
                ) ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid executionMode: ${orderRequest.executionMode}")
                    )
                    return@post
                }
                val allowedTradingModes = userInfo.allowedTradingModes
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (executionMode !in allowedTradingModes) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Execution mode not allowed: $executionMode")
                    )
                    return@post
                }
                val callerGroups = userInfo.groups
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (executionMode == "mainnet_live" && callerGroups.intersect(mainnetReservedGroups).isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        RequiredGroupsErrorResponse(
                            error = "Execution mode '$executionMode' requires reserved trading role",
                            requiredGroups = mainnetReservedGroups.toList().sorted()
                        )
                    )
                    return@post
                }
                val requestLog = mapOf(
                    "exchange" to exchange,
                    "executionMode" to executionMode,
                    "symbol" to orderRequest.symbol,
                    "side" to orderRequest.side,
                    "type" to orderRequest.type,
                    "size" to orderRequest.size,
                    "price" to orderRequest.price,
                    "urgencyClass" to orderRequest.urgencyClass,
                    "feeTier" to orderRequest.feeTier,
                    "maxSlippageBps" to orderRequest.maxSlippageBps,
                    "cancelAfterMs" to orderRequest.cancelAfterMs
                )
                val maxTxValueUsd = BigDecimal.valueOf(userInfo.maxTxValueUSD.toLong())

                if (isPaperExecutionMode(executionMode)) {
                    val quote = resolveQuoteWithPaperFallback(
                        dbService = dbService,
                        exchange = exchange,
                        symbol = orderRequest.symbol,
                        executionMode = executionMode
                    )
                    if (quote == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf(
                                "error" to "Quote unavailable",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }
                    if (!quote.isValidSnapshot()) {
                        logger.warn(
                            "Rejecting paper order due to invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                            quote.exchange,
                            quote.symbol,
                            quote.bid,
                            quote.ask,
                            quote.last
                        )
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "error" to "Invalid quote snapshot",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }
                    if (!quote.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)) {
                        logger.warn(
                            "Rejecting paper order due to stale quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                            quote.exchange,
                            quote.symbol,
                            quote.quoteAgeMs(),
                            maxQuoteAgeMs
                        )
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "error" to "Stale quote snapshot",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }

                    val estimatedNotionalUsd = estimateOrderNotionalUsd(orderRequest, quote)
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Unable to estimate order value for risk checks")
                            )
                            return@post
                        }
                    if (estimatedNotionalUsd > maxTxValueUsd) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf(
                                "error" to "Order value exceeds maxTxValueUSD",
                                "estimatedNotionalUsd" to estimatedNotionalUsd.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "maxTxValueUSD" to userInfo.maxTxValueUSD.toString()
                            )
                        )
                        return@post
                    }

                    val riskDecision = riskEngine.evaluateOrder(
                        username = username,
                        symbol = orderRequest.symbol,
                        orderNotionalUsd = estimatedNotionalUsd,
                        reduceOnly = orderRequest.reduceOnly
                    )
                    if (!riskDecision.allowed) {
                        call.respond(HttpStatusCode.Conflict, riskDecision.toRejectionPayload())
                        return@post
                    }

                    val executionPreview = runCatching {
                        buildPaperOrderResponse(
                            exchange = exchange,
                            orderRequest = orderRequest,
                            quote = quote,
                            executionMode = executionMode
                        )
                    }.getOrElse { error ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid order payload")))
                        return@post
                    }

                    if (executionPreview.status == "REJECTED") {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to (executionPreview.rejectionReason ?: "Order rejected by execution safeguards"),
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }

                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
                        response = dynamicJson.toJson(executionPreview),
                        status = "success"
                    )
                    executionPreview.estimatedExecutedNotionalUsd()?.let { executedNotionalUsd ->
                        if (executedNotionalUsd > BigDecimal.ZERO) {
                            riskEngine.recordAcceptedOrder(
                                username = username,
                                notionalUsd = executedNotionalUsd,
                                reduceOnly = orderRequest.reduceOnly
                            )
                        }
                    }

                    runCatching {
                        dbService.logPaperExecutionAnalytics(
                            strategyName = PAPER_EXECUTION_STRATEGY,
                            exchange = exchange,
                            symbol = executionPreview.symbol,
                            side = executionPreview.side.lowercase(),
                            decisionLatencyMs = executionPreview.telemetry.decisionLatencyMs.toDouble(),
                            submitToAckMs = executionPreview.telemetry.submitToAckMs.toDouble(),
                            submitToFillMs = (
                                executionPreview.telemetry.submitToFinalFillMs
                                    ?: executionPreview.telemetry.submitToFirstFillMs
                                )?.toDouble(),
                            p50RoundTripMs = executionPreview.telemetry.p50RoundTripMs.toDouble(),
                            p95RoundTripMs = executionPreview.telemetry.p95RoundTripMs.toDouble(),
                            p99RoundTripMs = executionPreview.telemetry.p99RoundTripMs.toDouble(),
                            jitterMs = executionPreview.telemetry.jitterMs.toDouble(),
                            feeBps = executionPreview.costs.appliedFeeBps,
                            feeTier = executionPreview.costs.feeTier,
                            feeTierAdjustmentBps = executionPreview.costs.feeTierAdjustmentBps,
                            makerFeeBps = executionPreview.costs.makerFeeBps,
                            takerFeeBps = executionPreview.costs.takerFeeBps,
                            spreadCostBps = executionPreview.costs.spreadCostBps,
                            slippageBps = executionPreview.costs.slippageBps,
                            impactBps = executionPreview.costs.impactBps,
                            adverseSelectionBps = executionPreview.costs.adverseSelectionBps,
                            fundingDriftBps = executionPreview.costs.fundingDriftBps,
                            basisDriftBps = executionPreview.costs.basisDriftBps,
                            totalCostBps = executionPreview.costs.totalCostBps,
                            edgeAfterCostBps = -executionPreview.costs.totalCostBps,
                            estimatedFeeUsd = executionPreview.costs.estimatedFeeUsd,
                            estimatedCostUsd = executionPreview.costs.estimatedCostUsd,
                            metadataJson = buildPaperAnalyticsMetadataJson(
                                username = username,
                                orderRequest = orderRequest,
                                result = executionPreview
                            )
                        )
                    }.onFailure { analyticsError ->
                        logger.warn(
                            "Failed to persist paper execution analytics for user={} exchange={} symbol={}: {}",
                            username,
                            exchange,
                            orderRequest.symbol,
                            analyticsError.message
                        )
                    }
                    runCatching {
                        recordExecutionDrift(
                            dbService = dbService,
                            tradingTelemetryMetrics = tradingTelemetryMetrics,
                            strategyName = PAPER_EXECUTION_STRATEGY,
                            username = username,
                            exchange = exchange,
                            executionMode = executionMode,
                            orderRequest = orderRequest,
                            result = executionPreview
                        )
                    }.onFailure { driftError ->
                        logger.warn(
                            "Failed to persist paper drift telemetry for user={} exchange={} symbol={}: {}",
                            username,
                            exchange,
                            orderRequest.symbol,
                            driftError.message
                        )
                    }

                    call.respond(HttpStatusCode.OK, executionPreview)
                    return@post
                }

                if (exchange != "hyperliquid") {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf(
                            "error" to "Execution mode '$executionMode' is not supported for $exchange",
                            "exchange" to exchange,
                            "supportedExecutionModes" to supportedExecutionModes(exchange)
                        )
                    )
                    return@post
                }
                val liveModeCompatibilityError = validateLiveExecutionMode(exchange, executionMode)
                if (liveModeCompatibilityError != null) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to liveModeCompatibilityError))
                    return@post
                }

                val allowedQuoteExchanges = resolveAllowedQuoteExchanges(
                    exchange = exchange,
                    executionMode = executionMode,
                    legacyHyperliquidQuoteExchange = requiredHyperliquidQuoteExchange
                )
                val rawRiskQuote = dbService.fetchLatestQuote(
                    exchange = exchange,
                    symbol = orderRequest.symbol,
                    executionMode = executionMode
                )
                val quoteForRisk = rawRiskQuote?.takeIf {
                    it.isValidSnapshot() && it.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)
                }
                if (rawRiskQuote != null && quoteForRisk == null) {
                    if (!rawRiskQuote.isValidSnapshot()) {
                        logger.warn(
                            "Ignoring invalid live quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                            rawRiskQuote.exchange,
                            rawRiskQuote.symbol,
                            rawRiskQuote.bid,
                            rawRiskQuote.ask,
                            rawRiskQuote.last
                        )
                    } else {
                        logger.warn(
                            "Ignoring stale live quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                            rawRiskQuote.exchange,
                            rawRiskQuote.symbol,
                            rawRiskQuote.quoteAgeMs(),
                            maxQuoteAgeMs
                        )
                    }
                }
                if (quoteForRisk == null) {
                    logger.warn(
                        "Rejecting live order due to missing fresh quote snapshot for exchange={} symbol={} hasRawQuote={}",
                        exchange,
                        orderRequest.symbol,
                        rawRiskQuote != null
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Fresh quote snapshot required for live order risk checks",
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol
                        )
                    )
                    return@post
                }
                if (!quoteForRisk.isOrderbookBacked()) {
                    logger.warn(
                        "Rejecting live order due to non-orderbook quote source for exchange={} symbol={} source={}",
                        exchange,
                        orderRequest.symbol,
                        quoteForRisk.source
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Orderbook-backed quote required for live order risk checks",
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol,
                            "quoteSource" to quoteForRisk.source
                        )
                    )
                    return@post
                }
                if (
                    exchange == "hyperliquid" &&
                    allowedQuoteExchanges.isNotEmpty() &&
                    !quoteSourceMatchesAnyResolvedExchange(
                        source = quoteForRisk.source,
                        expectedExchanges = allowedQuoteExchanges
                    )
                ) {
                    logger.warn(
                        "Rejecting live order due to quote exchange mismatch for exchange={} symbol={} source={} expectedExchanges={}",
                        exchange,
                        orderRequest.symbol,
                        quoteForRisk.source,
                        allowedQuoteExchanges.joinToString(",")
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Quote exchange mismatch for live order risk checks",
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol,
                            "quoteSource" to quoteForRisk.source,
                            "expectedQuoteExchanges" to allowedQuoteExchanges.joinToString(",")
                        )
                    )
                    return@post
                }
                val estimatedNotionalUsd = estimateOrderNotionalUsd(orderRequest, quoteForRisk)
                    ?: run {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Unable to estimate order value for risk checks")
                        )
                        return@post
                    }
                if (estimatedNotionalUsd > maxTxValueUsd) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "Order value exceeds maxTxValueUSD",
                            "estimatedNotionalUsd" to estimatedNotionalUsd.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "maxTxValueUSD" to userInfo.maxTxValueUSD.toString()
                        )
                    )
                    return@post
                }

                val riskDecision = riskEngine.evaluateOrder(
                    username = username,
                    symbol = orderRequest.symbol,
                    orderNotionalUsd = estimatedNotionalUsd,
                    reduceOnly = orderRequest.reduceOnly
                )
                if (!riskDecision.allowed) {
                    call.respond(HttpStatusCode.Conflict, riskDecision.toRejectionPayload())
                    return@post
                }

                val liveExecutionPreview = quoteForRisk?.let { quote ->
                    runCatching {
                        buildPaperOrderResponse(
                            exchange = exchange,
                            orderRequest = orderRequest,
                            quote = quote,
                            executionMode = executionMode
                        )
                    }.getOrNull()
                }
                if (liveExecutionPreview?.status == "REJECTED") {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (liveExecutionPreview.rejectionReason ?: "Order rejected by execution safeguards"),
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol
                        )
                    )
                    return@post
                }

                val hyperliquidKey = credentialResolver.resolveHyperliquidCredential(
                    username = username,
                    providedCredential = call.request.headers["X-Credential-hyperliquid"],
                    executionMode = executionMode
                )
                if (hyperliquidKey == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                    return@post
                }

                try {
                    val payload = mutableMapOf<String, Any>(
                        "username" to username,
                        "symbol" to orderRequest.symbol,
                        "side" to orderRequest.side,
                        "type" to orderRequest.type,
                        "size" to orderRequest.size,
                        "price" to (orderRequest.price ?: ""),
                        "reduceOnly" to orderRequest.reduceOnly,
                        "postOnly" to orderRequest.postOnly,
                        "hyperliquidKey" to hyperliquidKey
                    )
                    orderRequest.urgencyClass?.let { payload["urgencyClass"] = it }
                    orderRequest.feeTier?.let { payload["feeTier"] = it }
                    orderRequest.maxSlippageBps?.let { payload["maxSlippageBps"] = it }
                    orderRequest.cancelAfterMs?.let { payload["cancelAfterMs"] = it }
                    val result = workerClient.submitHyperliquidOrder(payload)
                    val responsePayload = linkedMapOf<String, Any?>().apply {
                        putAll(result)
                        put("executionMode", executionMode)
                        put("simulated", false)
                    }

                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
                        response = responsePayload.toString(),
                        status = "success"
                    )

                    val reservedNotionalUsd = result.acceptedExposureReservationUsd(
                        estimatedNotionalUsd = estimatedNotionalUsd,
                        requestedSizeRaw = orderRequest.size
                    )
                    if (reservedNotionalUsd != null && reservedNotionalUsd > BigDecimal.ZERO) {
                        riskEngine.recordAcceptedOrder(
                            username = username,
                            notionalUsd = reservedNotionalUsd,
                            reduceOnly = orderRequest.reduceOnly
                        )
                    }
                    liveExecutionPreview?.let { preview ->
                        runCatching {
                            dbService.logPaperExecutionAnalytics(
                                strategyName = LIVE_EXECUTION_STRATEGY,
                                exchange = exchange,
                                symbol = preview.symbol,
                                side = preview.side.lowercase(),
                                decisionLatencyMs = preview.telemetry.decisionLatencyMs.toDouble(),
                                submitToAckMs = preview.telemetry.submitToAckMs.toDouble(),
                                submitToFillMs = (
                                    preview.telemetry.submitToFinalFillMs
                                        ?: preview.telemetry.submitToFirstFillMs
                                    )?.toDouble(),
                                p50RoundTripMs = preview.telemetry.p50RoundTripMs.toDouble(),
                                p95RoundTripMs = preview.telemetry.p95RoundTripMs.toDouble(),
                                p99RoundTripMs = preview.telemetry.p99RoundTripMs.toDouble(),
                                jitterMs = preview.telemetry.jitterMs.toDouble(),
                                feeBps = preview.costs.appliedFeeBps,
                                feeTier = preview.costs.feeTier,
                                feeTierAdjustmentBps = preview.costs.feeTierAdjustmentBps,
                                makerFeeBps = preview.costs.makerFeeBps,
                                takerFeeBps = preview.costs.takerFeeBps,
                                spreadCostBps = preview.costs.spreadCostBps,
                                slippageBps = preview.costs.slippageBps,
                                impactBps = preview.costs.impactBps,
                                adverseSelectionBps = preview.costs.adverseSelectionBps,
                                fundingDriftBps = preview.costs.fundingDriftBps,
                                basisDriftBps = preview.costs.basisDriftBps,
                                totalCostBps = preview.costs.totalCostBps,
                                edgeAfterCostBps = -preview.costs.totalCostBps,
                                estimatedFeeUsd = preview.costs.estimatedFeeUsd,
                                estimatedCostUsd = preview.costs.estimatedCostUsd,
                                metadataJson = buildLiveAnalyticsMetadataJson(
                                    username = username,
                                    orderRequest = orderRequest,
                                    result = result,
                                    preview = preview,
                                    executionMode = executionMode
                                )
                            )
                        }.onFailure { analyticsError ->
                            logger.warn(
                                "Failed to persist live execution analytics for user={} exchange={} symbol={}: {}",
                                username,
                                exchange,
                                orderRequest.symbol,
                                analyticsError.message
                            )
                        }
                        runCatching {
                            recordExecutionDrift(
                                dbService = dbService,
                                tradingTelemetryMetrics = tradingTelemetryMetrics,
                                strategyName = LIVE_EXECUTION_STRATEGY,
                                username = username,
                                exchange = exchange,
                                executionMode = executionMode,
                                orderRequest = orderRequest,
                                result = preview
                            )
                        }.onFailure { driftError ->
                            logger.warn(
                                "Failed to persist live drift telemetry for user={} exchange={} symbol={}: {}",
                                username,
                                exchange,
                                orderRequest.symbol,
                                driftError.message
                            )
                        }
                    }
                    runCatching {
                        reconcileHyperliquidRiskState(
                            dbService = dbService,
                            workerClient = workerClient,
                            username = username,
                            hyperliquidKey = hyperliquidKey
                        )
                    }.onFailure { syncError ->
                        logger.warn(
                            "Live risk reconciliation failed for user={} exchange={} symbol={}: {}",
                            username,
                            exchange,
                            orderRequest.symbol,
                            syncError.message
                        )
                    }

                    // Worker payload is dynamic JSON with nested heterogeneous objects.
                    // Serialize manually to avoid Kotlinx map polymorphism failures.
                    call.respondText(
                        text = dynamicJson.toJson(responsePayload),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    logger.error("Unified order failed for exchange=$exchange user=$username", e)
                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
                        response = null,
                        status = "error",
                        errorMessage = e.message ?: "unified order failed"
                    )
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "unified order failed"))
                    )
                }
            }
        }
    }
}

private fun buildPaperOrderResponse(
    exchange: String,
    orderRequest: OrderRequest,
    quote: LatestQuote,
    executionMode: String = FORWARD_PAPER_EXECUTION_MODE
): PaperOrderResponse {
    val now = Instant.now()
    val side = orderRequest.side.trim().uppercase()
    require(side == "BUY" || side == "SELL") { "Unsupported side: ${orderRequest.side}" }

    val type = orderRequest.type.trim().uppercase()
    require(type == "MARKET" || type == "LIMIT") { "Unsupported order type: ${orderRequest.type}" }

    val size = orderRequest.size.trim().toBigDecimalOrNull()
        ?: throw IllegalArgumentException("Invalid size: ${orderRequest.size}")
    require(size > BigDecimal.ZERO) { "Order size must be > 0" }

    val limitPrice = orderRequest.price?.trim()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    if (type == "LIMIT" && (limitPrice == null || limitPrice <= BigDecimal.ZERO)) {
        throw IllegalArgumentException("Limit orders require a positive price")
    }

    val quoteBid = quote.bid.toBigDecimal()
    val quoteAsk = quote.ask.toBigDecimal()
    val quoteMid = quoteBid.add(quoteAsk).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP)
    val spread = quoteAsk.subtract(quoteBid).max(BigDecimal.ZERO)
    val spreadBps = toBps(spread, quoteMid)

    val urgency = parseUrgencyClass(orderRequest.urgencyClass)
    val feeTier = parseFeeTier(orderRequest.feeTier)
    val marketFillPrice = if (side == "BUY") quoteAsk else quoteBid
    val targetPrice = limitPrice ?: marketFillPrice
    val canFillLimit = when (side) {
        "BUY" -> targetPrice >= quoteAsk
        else -> targetPrice <= quoteBid
    }
    val postOnlyWouldTakeLiquidity = type == "LIMIT" && orderRequest.postOnly && canFillLimit

    val notional = size.multiply(marketFillPrice)
    val sizeImpactBps = when {
        notional <= BigDecimal("25000") -> BigDecimal("0.5")
        notional <= BigDecimal("100000") -> BigDecimal("3.0")
        else -> BigDecimal("8.0")
    }

    val baseSlippageBps = when {
        notional <= BigDecimal("25000") -> BigDecimal("1.5")
        notional <= BigDecimal("100000") -> BigDecimal("6.0")
        else -> BigDecimal("15.0")
    }

    val urgencySlippageAdj = when (urgency) {
        UrgencyClass.LOW -> BigDecimal("-0.5")
        UrgencyClass.NORMAL -> BigDecimal.ZERO
        UrgencyClass.HIGH -> BigDecimal("1.5")
    }

    val spreadAdj = spreadBps.multiply(
        if (type == "MARKET") BigDecimal("0.25") else BigDecimal("0.10")
    )
    val estimatedSlippageBps = if (type == "LIMIT" && !canFillLimit) {
        BigDecimal.ZERO
    } else {
        (baseSlippageBps + sizeImpactBps + urgencySlippageAdj + spreadAdj).max(BigDecimal.ZERO)
    }
    val regimeBucket = classifyRegimeBucket(spreadBps, estimatedSlippageBps)
    val liquidityBucket = classifyLiquidityBucket(spreadBps, notional)

    val maxSlippageBps = orderRequest.maxSlippageBps?.let { BigDecimal.valueOf(it) }
    val rejectionReason = when {
        postOnlyWouldTakeLiquidity ->
            "Post-only limit order would cross the spread and remove liquidity"
        maxSlippageBps != null && estimatedSlippageBps > maxSlippageBps ->
            "Estimated slippage ${estimatedSlippageBps.setScale(2, RoundingMode.HALF_UP)}bps exceeds limit ${maxSlippageBps.setScale(2, RoundingMode.HALF_UP)}bps"
        else -> null
    }

    var fillRatio = when {
        type == "LIMIT" && !canFillLimit -> BigDecimal.ZERO
        notional <= BigDecimal("25000") -> BigDecimal.ONE
        notional <= BigDecimal("100000") -> BigDecimal("0.70")
        else -> BigDecimal("0.35")
    }
    fillRatio += when (urgency) {
        UrgencyClass.LOW -> BigDecimal("-0.15")
        UrgencyClass.NORMAL -> BigDecimal.ZERO
        UrgencyClass.HIGH -> BigDecimal("0.15")
    }
    if (type == "LIMIT" && canFillLimit) {
        fillRatio += BigDecimal("0.10")
    }
    fillRatio = fillRatio.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

    if (rejectionReason != null) {
        fillRatio = BigDecimal.ZERO
    }

    val filledSize = size.multiply(fillRatio).setScale(8, RoundingMode.DOWN)
    val remainingSize = size.subtract(filledSize).max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN)

    val isFilled = fillRatio >= BigDecimal("0.9999")
    val fillPrice = when {
        fillRatio <= BigDecimal.ZERO -> null
        type == "MARKET" -> applySlippage(side, marketFillPrice, estimatedSlippageBps)
        side == "BUY" -> applySlippage(side, quoteAsk, estimatedSlippageBps).min(targetPrice)
        else -> applySlippage(side, quoteBid, estimatedSlippageBps).max(targetPrice)
    }

    val feeProfile = paperFeeProfile(exchange, feeTier)
    val appliedFeeBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        type == "LIMIT" && !canFillLimit -> feeProfile.makerFeeBps
        type == "LIMIT" && orderRequest.postOnly && !canFillLimit -> feeProfile.makerFeeBps
        else -> feeProfile.takerFeeBps
    }

    val spreadCostBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        type == "MARKET" -> spreadBps.divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
        type == "LIMIT" && canFillLimit -> spreadBps.divide(BigDecimal("3"), 8, RoundingMode.HALF_UP)
        else -> spreadBps.divide(BigDecimal("10"), 8, RoundingMode.HALF_UP)
    }
    val adverseSelectionBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        urgency == UrgencyClass.HIGH -> BigDecimal("1.5")
        type == "MARKET" -> BigDecimal("0.8")
        else -> BigDecimal("0.3")
    }
    val fundingDriftBps = if (orderRequest.symbol.contains("PERP", ignoreCase = true)) {
        when (regimeBucket) {
            "high_volatility", "event_jump" -> BigDecimal("1.2")
            "liquidity_stress" -> BigDecimal("0.8")
            else -> BigDecimal("0.35")
        }
    } else {
        BigDecimal.ZERO
    }
    val basisDriftBps = if (orderRequest.symbol.contains("PERP", ignoreCase = true)) {
        when (urgency) {
            UrgencyClass.LOW -> BigDecimal("0.20")
            UrgencyClass.NORMAL -> BigDecimal("0.45")
            UrgencyClass.HIGH -> BigDecimal("0.80")
        }
    } else {
        BigDecimal.ZERO
    }

    val totalCostBps =
        appliedFeeBps + spreadCostBps + estimatedSlippageBps + sizeImpactBps + adverseSelectionBps + fundingDriftBps + basisDriftBps
    val executionNotional = filledSize.multiply(fillPrice ?: marketFillPrice)
    val estimatedFeeUsd = executionNotional.multiply(appliedFeeBps).divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)
    val estimatedCostUsd = executionNotional.multiply(totalCostBps).divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)

    val cancelCadenceMs = orderRequest.cancelAfterMs?.coerceAtLeast(100L) ?: when (urgency) {
        UrgencyClass.LOW -> 2_000L
        UrgencyClass.NORMAL -> 1_000L
        UrgencyClass.HIGH -> 400L
    }
    val tierLatencyAdj = when {
        notional <= BigDecimal("25000") -> 0L
        notional <= BigDecimal("100000") -> 20L
        else -> 45L
    }
    val decisionLatencyMs = 8L + tierLatencyAdj / 10L + when (urgency) {
        UrgencyClass.LOW -> 3L
        UrgencyClass.NORMAL -> 2L
        UrgencyClass.HIGH -> 1L
    }
    val submitToAckMs = when (urgency) {
        UrgencyClass.LOW -> 140L
        UrgencyClass.NORMAL -> 90L
        UrgencyClass.HIGH -> 55L
    } + tierLatencyAdj
    val submitToFirstFillMs = if (fillRatio > BigDecimal.ZERO) submitToAckMs + 15L else null
    val submitToFinalFillMs = when {
        fillRatio <= BigDecimal.ZERO -> null
        isFilled -> submitToFirstFillMs?.plus(30L + tierLatencyAdj)
        else -> submitToFirstFillMs?.plus(450L + tierLatencyAdj * 2L)
    }
    val jitterMs = 6L + tierLatencyAdj / 5L
    val p50RoundTripMs = submitToAckMs + jitterMs / 2L
    val p95RoundTripMs = submitToAckMs * 2L + jitterMs
    val p99RoundTripMs = submitToAckMs * 3L + jitterMs * 2L
    val queuePositionEstimate = estimateQueuePosition(type, urgency, canFillLimit, liquidityBucket)
    val expectedFillProbability = fillRatio.setScale(6, RoundingMode.HALF_UP).toDouble()
    val projectedPartialFills = buildProjectedFills(
        filledSize = filledSize,
        fillPrice = fillPrice ?: marketFillPrice,
        submitToFirstFillMs = submitToFirstFillMs,
        submitToFinalFillMs = submitToFinalFillMs
    )
    val failToFillReason = when {
        rejectionReason != null -> rejectionReason
        fillRatio <= BigDecimal.ZERO && type == "LIMIT" -> "Queue priority too deep for immediate fill at requested limit price"
        fillRatio <= BigDecimal.ZERO -> "No executable liquidity available at current controls"
        else -> null
    }

    val placementSchedule = buildPlacementSchedule(
        side = side,
        type = type,
        urgency = urgency,
        targetPrice = targetPrice,
        spread = spread,
        marketFillPrice = marketFillPrice
    )

    val status = when {
        rejectionReason != null -> "REJECTED"
        fillRatio <= BigDecimal.ZERO -> "PENDING"
        isFilled -> "FILLED"
        else -> "PARTIALLY_FILLED"
    }

    return PaperOrderResponse(
        orderId = "paper-$exchange-${UUID.randomUUID().toString().take(12)}",
        status = status,
        requestedSize = size.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(),
        filledSize = filledSize.stripTrailingZeros().toPlainString(),
        remainingSize = remainingSize.stripTrailingZeros().toPlainString(),
        fillRatio = fillRatio.setScale(6, RoundingMode.HALF_UP).toDouble(),
        fillPrice = fillPrice?.setScale(8, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString(),
        exchange = exchange,
        symbol = orderRequest.symbol,
        side = side,
        type = type,
        simulated = true,
        executionMode = executionMode,
        rejectionReason = rejectionReason,
        quoteAgeMs = Duration.between(quote.timestamp, now).toMillis().coerceAtLeast(0L),
        quote = PaperQuoteSnapshot(
            bid = quote.bid,
            ask = quote.ask,
            last = quote.last,
            timestamp = quote.timestamp.toString(),
            source = quote.source
        ),
        policy = PaperExecutionPolicy(
            venue = exchange,
            orderType = type,
            urgencyClass = urgency.name.lowercase(),
            cancelCadenceMs = cancelCadenceMs,
            placementSchedule = placementSchedule
        ),
        costs = PaperCostBreakdown(
            feeTier = feeProfile.tier.name.lowercase(),
            feeTierAdjustmentBps = feeProfile.tierAdjustmentBps.toDouble(),
            makerFeeBps = feeProfile.makerFeeBps.toDouble(),
            takerFeeBps = feeProfile.takerFeeBps.toDouble(),
            appliedFeeBps = appliedFeeBps.toDouble(),
            spreadCostBps = spreadCostBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            slippageBps = estimatedSlippageBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            impactBps = sizeImpactBps.toDouble(),
            adverseSelectionBps = adverseSelectionBps.toDouble(),
            fundingDriftBps = fundingDriftBps.toDouble(),
            basisDriftBps = basisDriftBps.toDouble(),
            totalCostBps = totalCostBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            estimatedFeeUsd = estimatedFeeUsd.setScale(6, RoundingMode.HALF_UP).toDouble(),
            estimatedCostUsd = estimatedCostUsd.setScale(6, RoundingMode.HALF_UP).toDouble()
        ),
        telemetry = PaperExecutionTelemetry(
            decisionLatencyMs = decisionLatencyMs,
            submitToAckMs = submitToAckMs,
            submitToFirstFillMs = submitToFirstFillMs,
            submitToFinalFillMs = submitToFinalFillMs,
            cancelReplaceLatencyMs = cancelCadenceMs,
            p50RoundTripMs = p50RoundTripMs,
            p95RoundTripMs = p95RoundTripMs,
            p99RoundTripMs = p99RoundTripMs,
            jitterMs = jitterMs
        ),
        simulation = PaperExecutionSimulation(
            queuePositionEstimate = queuePositionEstimate,
            expectedFillProbability = expectedFillProbability,
            projectedPartialFills = projectedPartialFills,
            failToFillReason = failToFillReason,
            regimeBucket = regimeBucket,
            liquidityBucket = liquidityBucket
        ),
        receivedAt = now.toString()
    )
}

private data class PaperFeeProfile(
    val makerFeeBps: BigDecimal,
    val takerFeeBps: BigDecimal,
    val tier: FeeTier,
    val tierAdjustmentBps: BigDecimal
)

private fun estimateOrderNotionalUsd(
    orderRequest: OrderRequest,
    quote: LatestQuote?
): BigDecimal? {
    val side = orderRequest.side.trim().uppercase()
    if (side != "BUY" && side != "SELL") return null

    val type = orderRequest.type.trim().uppercase()
    if (type != "MARKET" && type != "LIMIT") return null

    val size = orderRequest.size.trim().toBigDecimalOrNull() ?: return null
    if (size <= BigDecimal.ZERO) return null

    val explicitPrice = orderRequest.price
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.toBigDecimalOrNull()

    val bookPrice = quote?.let {
        if (side == "BUY") it.ask.toBigDecimal() else it.bid.toBigDecimal()
    }

    val executionPrice = when (type) {
        "MARKET" -> bookPrice
        else -> explicitPrice
    } ?: return null

    if (executionPrice <= BigDecimal.ZERO) return null
    return size.abs().multiply(executionPrice)
}

private fun paperFeeProfile(exchange: String, tier: FeeTier): PaperFeeProfile {
    val base = when (exchange.lowercase()) {
        "binance" -> BigDecimal("1.0") to BigDecimal("5.0")
        "bybit" -> BigDecimal("1.5") to BigDecimal("5.5")
        "coinbase" -> BigDecimal("3.5") to BigDecimal("6.0")
        "dydx" -> BigDecimal("2.0") to BigDecimal("5.0")
        "swyftx" -> BigDecimal("4.0") to BigDecimal("7.0")
        "aster" -> BigDecimal("2.0") to BigDecimal("5.0")
        else -> BigDecimal("2.0") to BigDecimal("5.0")
    }
    val tierAdjustment = when (tier) {
        FeeTier.RETAIL -> BigDecimal.ZERO
        FeeTier.PRO -> BigDecimal("-0.5")
        FeeTier.VIP -> BigDecimal("-1.5")
    }
    return PaperFeeProfile(
        makerFeeBps = (base.first + tierAdjustment).max(BigDecimal("-2.0")),
        takerFeeBps = (base.second + tierAdjustment).max(BigDecimal("0.4")),
        tier = tier,
        tierAdjustmentBps = tierAdjustment
    )
}

private fun parseUrgencyClass(raw: String?): UrgencyClass = when (raw?.trim()?.lowercase()) {
    "low" -> UrgencyClass.LOW
    "high" -> UrgencyClass.HIGH
    else -> UrgencyClass.NORMAL
}

private fun parseFeeTier(raw: String?): FeeTier = when (raw?.trim()?.lowercase()) {
    "pro" -> FeeTier.PRO
    "vip" -> FeeTier.VIP
    else -> FeeTier.RETAIL
}

private fun supportedExecutionModes(exchange: String): List<String> {
    return TradingPermissionCatalog.supportedExecutionModes(exchange)
}

private fun defaultExecutionModeForExchange(exchange: String): String {
    return TradingPermissionCatalog.defaultExecutionModeForExchange(exchange)
}

private fun resolveExecutionMode(exchange: String, requestedExecutionMode: String?): String? {
    val normalized = requestedExecutionMode
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return defaultExecutionModeForExchange(exchange)
    return normalized.takeIf { it in supportedExecutionModes(exchange) }
}

private fun isPaperExecutionMode(executionMode: String): Boolean =
    executionMode == FORWARD_PAPER_EXECUTION_MODE

private fun validateLiveExecutionMode(exchange: String, executionMode: String): String? {
    if (exchange.lowercase() != "hyperliquid") {
        return "Execution mode '$executionMode' is not supported for $exchange"
    }
    if (executionMode !in liveExecutionModes) {
        return "Execution mode '$executionMode' is not a live mode"
    }

    val environmentMode = hyperliquidDefaultLiveExecutionMode
    return if (environmentMode != executionMode) {
        "Hyperliquid worker is configured for $environmentMode, cannot execute $executionMode"
    } else {
        null
    }
}

private fun validateOrderRequest(orderRequest: OrderRequest): String? {
    if (orderRequest.symbol != orderRequest.symbol.trim()) {
        return "symbol must not contain leading or trailing whitespace"
    }
    val symbol = orderRequest.symbol.trim()
    if (!symbolPattern.matches(symbol)) {
        return "Invalid symbol: ${orderRequest.symbol}"
    }

    val side = orderRequest.side.trim().uppercase()
    if (side != "BUY" && side != "SELL") {
        return "Invalid side: ${orderRequest.side}"
    }

    val type = orderRequest.type.trim().uppercase()
    if (type != "MARKET" && type != "LIMIT") {
        return "Invalid type: ${orderRequest.type}"
    }

    val size = orderRequest.size.trim().toBigDecimalOrNull()
        ?: return "Invalid size: ${orderRequest.size}"
    if (size <= BigDecimal.ZERO) {
        return "size must be > 0"
    }
    if (size > maxOrderSize) {
        return "size exceeds safety limit"
    }

    val parsedPrice = orderRequest.price
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.toBigDecimalOrNull()
    if (orderRequest.price != null && parsedPrice == null) {
        return "Invalid price: ${orderRequest.price}"
    }
    if (parsedPrice != null && parsedPrice <= BigDecimal.ZERO) {
        return "price must be > 0"
    }
    if (parsedPrice != null && parsedPrice > maxOrderPrice) {
        return "price exceeds safety limit"
    }
    if (type == "LIMIT" && parsedPrice == null) {
        return "Limit orders require a positive price"
    }
    if (type == "MARKET" && parsedPrice != null) {
        return "Market orders must not include price"
    }

    return null
}

private fun validateExecutionControls(orderRequest: OrderRequest): String? {
    val type = orderRequest.type.trim().uppercase()
    if (orderRequest.postOnly && type != "LIMIT") {
        return "postOnly is only valid for LIMIT orders"
    }

    val urgencyClass = orderRequest.urgencyClass?.trim()?.lowercase()
    if (!urgencyClass.isNullOrBlank() && urgencyClass !in setOf("low", "normal", "high")) {
        return "Invalid urgencyClass: ${orderRequest.urgencyClass}"
    }

    val feeTier = orderRequest.feeTier?.trim()?.lowercase()
    if (!feeTier.isNullOrBlank() && feeTier !in setOf("retail", "pro", "vip")) {
        return "Invalid feeTier: ${orderRequest.feeTier}"
    }

    val maxSlippageBps = orderRequest.maxSlippageBps
    if (maxSlippageBps != null) {
        if (!maxSlippageBps.isFinite()) {
            return "maxSlippageBps must be finite"
        }
        if (maxSlippageBps < 0.0) {
            return "maxSlippageBps must be >= 0"
        }
        if (maxSlippageBps > 500.0) {
            return "maxSlippageBps is unreasonably high"
        }
    }

    val cancelAfterMs = orderRequest.cancelAfterMs
    if (cancelAfterMs != null) {
        if (cancelAfterMs < 100L) {
            return "cancelAfterMs must be >= 100"
        }
        if (cancelAfterMs > 600_000L) {
            return "cancelAfterMs must be <= 600000"
        }
    }

    return null
}

private fun applySlippage(side: String, referencePrice: BigDecimal, slippageBps: BigDecimal): BigDecimal {
    val slipFactor = slippageBps.divide(BigDecimal("10000"), 18, RoundingMode.HALF_UP)
    return if (side == "BUY") {
        referencePrice.multiply(BigDecimal.ONE + slipFactor)
    } else {
        referencePrice.multiply(BigDecimal.ONE - slipFactor)
    }
}

private fun buildPlacementSchedule(
    side: String,
    type: String,
    urgency: UrgencyClass,
    targetPrice: BigDecimal,
    spread: BigDecimal,
    marketFillPrice: BigDecimal
): List<PaperPlacementSlice> {
    val schedule = when (type) {
        "MARKET" -> listOf(
            PaperPlacementSlice(
                delayMs = 0,
                price = marketFillPrice.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                sizePercent = 100
            )
        )

        else -> when (urgency) {
            UrgencyClass.LOW -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.10"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 40
                ),
                PaperPlacementSlice(
                    delayMs = 1_000,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.25"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                ),
                PaperPlacementSlice(
                    delayMs = 2_000,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.50"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                )
            )

            UrgencyClass.NORMAL -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.20"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 60
                ),
                PaperPlacementSlice(
                    delayMs = 600,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.40"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 40
                )
            )

            UrgencyClass.HIGH -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.30"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 70
                ),
                PaperPlacementSlice(
                    delayMs = 250,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.60"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                )
            )
        }
    }
    return schedule
}

private fun estimateQueuePosition(
    orderType: String,
    urgency: UrgencyClass,
    canFillLimit: Boolean,
    liquidityBucket: String
): Int {
    if (orderType == "MARKET" || canFillLimit) return 1
    val base = when (liquidityBucket) {
        "liquidity_stress" -> 28
        "thin" -> 18
        else -> 10
    }
    val urgencyAdj = when (urgency) {
        UrgencyClass.HIGH -> -4
        UrgencyClass.NORMAL -> 0
        UrgencyClass.LOW -> 5
    }
    return (base + urgencyAdj).coerceAtLeast(1)
}

private fun buildProjectedFills(
    filledSize: BigDecimal,
    fillPrice: BigDecimal,
    submitToFirstFillMs: Long?,
    submitToFinalFillMs: Long?
): List<PaperFillSlice> {
    if (filledSize <= BigDecimal.ZERO) return emptyList()
    val firstDelay = submitToFirstFillMs ?: 0L
    val finalDelay = submitToFinalFillMs ?: firstDelay
    val duration = (finalDelay - firstDelay).coerceAtLeast(0L)
    val ratios = when {
        duration <= 80L -> listOf(BigDecimal.ONE)
        duration <= 400L -> listOf(BigDecimal("0.6"), BigDecimal("0.4"))
        else -> listOf(BigDecimal("0.5"), BigDecimal("0.3"), BigDecimal("0.2"))
    }

    val slices = mutableListOf<PaperFillSlice>()
    var remaining = filledSize
    ratios.forEachIndexed { index, ratio ->
        val sliceSize = if (index == ratios.lastIndex) {
            remaining
        } else {
            filledSize.multiply(ratio).setScale(8, RoundingMode.DOWN)
        }
        remaining = (remaining - sliceSize).max(BigDecimal.ZERO)
        val offset = if (ratios.size == 1) {
            0L
        } else {
            (duration * index.toLong()) / (ratios.lastIndex.toLong().coerceAtLeast(1L))
        }
        slices += PaperFillSlice(
            delayMs = firstDelay + offset,
            size = sliceSize.stripTrailingZeros().toPlainString(),
            price = fillPrice.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        )
    }
    return slices
}

private fun classifyRegimeBucket(spreadBps: BigDecimal, slippageBps: BigDecimal): String = when {
    spreadBps >= BigDecimal("20") || slippageBps >= BigDecimal("25") -> "liquidity_stress"
    spreadBps >= BigDecimal("8") || slippageBps >= BigDecimal("12") -> "high_volatility"
    spreadBps >= BigDecimal("4") || slippageBps >= BigDecimal("6") -> "event_jump"
    else -> "low_volatility"
}

private fun classifyLiquidityBucket(spreadBps: BigDecimal, notional: BigDecimal): String = when {
    spreadBps >= BigDecimal("20") -> "liquidity_stress"
    spreadBps >= BigDecimal("10") || notional > BigDecimal("250000") -> "thin"
    spreadBps >= BigDecimal("4") || notional > BigDecimal("100000") -> "medium"
    else -> "deep"
}

private fun signedOffset(side: String, value: BigDecimal): BigDecimal {
    return if (side == "BUY") value.negate() else value
}

private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal {
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

private fun resolveQuoteWithPaperFallback(
    dbService: DatabaseService,
    exchange: String,
    symbol: String,
    executionMode: String
): LatestQuote? {
    return dbService.fetchLatestQuote(exchange = exchange, symbol = symbol, executionMode = executionMode)
}

private fun resolveAllowedQuoteExchanges(
    exchange: String,
    executionMode: String,
    legacyHyperliquidQuoteExchange: String?
): List<String> {
    if (exchange.lowercase() != "hyperliquid") return emptyList()
    return resolveHyperliquidQuoteExchangeCandidates(
        requestedExecutionMode = executionMode,
        legacyQuoteExchange = legacyHyperliquidQuoteExchange,
        forwardPaperExchange = System.getenv("HYPERLIQUID_FORWARD_PAPER_QUOTE_EXCHANGE"),
        testnetExchange = System.getenv("HYPERLIQUID_TESTNET_QUOTE_EXCHANGE"),
        mainnetExchange = System.getenv("HYPERLIQUID_MAINNET_QUOTE_EXCHANGE"),
        mainnetFlag = System.getenv("HYPERLIQUID_MAINNET"),
        allowCanonicalFallback = parseBooleanFlag(
            raw = System.getenv("HYPERLIQUID_QUOTE_EXCHANGE_ALLOW_CANONICAL_FALLBACK"),
            defaultValue = true
        )
    )
}

private fun LatestQuote.withStaleSourceTag(): LatestQuote {
    return if (source.startsWith("stale:")) this else copy(source = "stale:$source")
}

internal fun quoteSourceMatchesResolvedExchange(source: String, expectedExchange: String): Boolean {
    val expected = expectedExchange.trim().lowercase()
    if (expected.isBlank()) return true
    val normalizedSource = source.trim().lowercase()
    if (!normalizedSource.contains("resolved_exchange=")) {
        return expected == "hyperliquid"
    }
    return normalizedSource.contains("resolved_exchange=$expected")
}

internal fun quoteSourceMatchesAnyResolvedExchange(source: String, expectedExchanges: Collection<String>): Boolean {
    if (expectedExchanges.isEmpty()) return true
    return expectedExchanges.any { expected ->
        quoteSourceMatchesResolvedExchange(source = source, expectedExchange = expected)
    }
}

private fun toBps(numerator: BigDecimal, denominator: BigDecimal): BigDecimal {
    if (denominator <= BigDecimal.ZERO) return BigDecimal.ZERO
    return numerator
        .multiply(BigDecimal("10000"))
        .divide(denominator, 8, RoundingMode.HALF_UP)
}

private fun Any?.toBigDecimalOrNull(): BigDecimal? = when (this) {
    null -> null
    is BigDecimal -> this
    is Number -> runCatching { BigDecimal(this.toString()) }.getOrNull()
    is String -> runCatching {
        this.trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
    }.getOrNull()
    else -> null
}

private fun LatestQuote.isValidSnapshot(): Boolean {
    if (!bid.isFinite() || !ask.isFinite() || !last.isFinite()) return false
    if (bid <= 0.0 || ask <= 0.0 || last <= 0.0) return false
    return ask >= bid
}

private fun LatestQuote.quoteAgeMs(referenceTime: Instant = Instant.now()): Long {
    return Duration.between(timestamp, referenceTime).toMillis()
}

private fun LatestQuote.isFreshSnapshot(
    referenceTime: Instant = Instant.now(),
    maxQuoteAgeMs: Long
): Boolean {
    val ageMs = quoteAgeMs(referenceTime)
    if (ageMs < -5_000L) return false
    return ageMs <= maxQuoteAgeMs
}

private fun LatestQuote.isOrderbookBacked(): Boolean {
    return source.lowercase().startsWith("orderbook_data")
}

private fun Map<String, Any>.executedNotionalUsd(
    estimatedNotionalUsd: BigDecimal,
    requestedSizeRaw: String
): BigDecimal? {
    val explicitNotional = this["executedNotionalUsd"].toBigDecimalOrNull()
    if (explicitNotional != null && explicitNotional > BigDecimal.ZERO) {
        return explicitNotional
    }

    val filledSize = this["filledSize"].toBigDecimalOrNull()
    val fillPrice = this["fillPrice"].toBigDecimalOrNull()
    if (filledSize != null && fillPrice != null && filledSize > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
        return filledSize.multiply(fillPrice).setScale(8, RoundingMode.HALF_UP)
    }

    val status = this["status"]?.toString()?.trim()?.uppercase().orEmpty()
    val requestedSize = requestedSizeRaw.toBigDecimalOrNull()
    if (filledSize != null && requestedSize != null && requestedSize > BigDecimal.ZERO && filledSize > BigDecimal.ZERO) {
        val fillRatio = filledSize
            .divide(requestedSize, 18, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        return estimatedNotionalUsd.multiply(fillRatio).setScale(8, RoundingMode.HALF_UP)
    }
    return if (status == "FILLED") estimatedNotionalUsd else null
}

private fun Map<String, Any>.acceptedExposureReservationUsd(
    estimatedNotionalUsd: BigDecimal,
    requestedSizeRaw: String
): BigDecimal? {
    executedNotionalUsd(
        estimatedNotionalUsd = estimatedNotionalUsd,
        requestedSizeRaw = requestedSizeRaw
    )?.let { return it }

    val status = this["status"]?.toString()?.trim()?.uppercase()
    val hasOrderId = this["orderId"]?.toString()?.trim()?.isNotEmpty() == true
    if (status in setOf("REJECTED", "FAILED", "CANCELLED", "ERROR")) {
        return null
    }
    if (status == null && !hasOrderId) {
        return null
    }
    return estimatedNotionalUsd.takeIf { it > BigDecimal.ZERO }
}

private fun reconcileHyperliquidRiskState(
    dbService: DatabaseService,
    workerClient: WorkerClient,
    username: String,
    hyperliquidKey: String
) {
    val balance = workerClient.getHyperliquidBalance(username, hyperliquidKey)
    val positions = workerClient.getHyperliquidPositions(username, hyperliquidKey)

    val accountEquity = balance["accountValue"].toBigDecimalOrNull()
        ?: balance["totalRawUsd"].toBigDecimalOrNull()
    var openExposureUsd = BigDecimal.ZERO
    var unrealizedPnlUsd = BigDecimal.ZERO

    positions.forEach { position ->
        val size = position["size"].toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
        val entryPrice = position["entryPrice"].toBigDecimalOrNull()
        val marginUsed = position["marginUsed"].toBigDecimalOrNull()
        val leverage = position["leverage"].toBigDecimalOrNull()
        val notional = when {
            size <= BigDecimal.ZERO -> BigDecimal.ZERO
            entryPrice != null && entryPrice > BigDecimal.ZERO -> size.multiply(entryPrice)
            marginUsed != null &&
                marginUsed > BigDecimal.ZERO &&
                leverage != null &&
                leverage > BigDecimal.ZERO -> marginUsed.multiply(leverage)
            marginUsed != null && marginUsed > BigDecimal.ZERO -> marginUsed
            else -> BigDecimal.ZERO
        }
        openExposureUsd = openExposureUsd.add(notional)
        unrealizedPnlUsd = unrealizedPnlUsd.add(position["unrealizedPnl"].toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }

    val patch = RiskAccountStatePatch(
        accountEquityUsd = accountEquity,
        unrealizedPnlUsd = unrealizedPnlUsd,
        openExposureUsd = openExposureUsd
    )
    dbService.upsertRiskAccountState(username = username, patch = patch)
}

private fun PaperOrderResponse.estimatedExecutedNotionalUsd(): BigDecimal? {
    val fillPx = fillPrice?.toBigDecimalOrNull() ?: return null
    val filled = filledSize.toBigDecimalOrNull() ?: return null
    if (fillPx <= BigDecimal.ZERO || filled <= BigDecimal.ZERO) return null
    return fillPx.multiply(filled)
}

private fun recordExecutionDrift(
    dbService: DatabaseService,
    tradingTelemetryMetrics: TradingTelemetryMetrics?,
    strategyName: String,
    username: String,
    exchange: String,
    executionMode: String,
    orderRequest: OrderRequest,
    result: PaperOrderResponse
) {
    val submitToFillMs = (
        result.telemetry.submitToFinalFillMs
            ?: result.telemetry.submitToFirstFillMs
            ?: result.telemetry.submitToAckMs
        ).toDouble()
    val baseline = dbService.fetchStrategyExecutionBaseline(
        strategyName = strategyName,
        symbol = result.symbol
    )
    val liveEdgeBps = -result.costs.totalCostBps
    val slippageDriftBps = baseline?.avgSlippageBps?.let { result.costs.slippageBps - it }
    val fillQualityDeltaBps = baseline?.avgFillRatio?.let { (it - result.fillRatio) * 10_000.0 }
    val latencyDriftMs = baseline?.avgSubmitToFillMs?.let { submitToFillMs - it }
    val driftScore = computeDriftScore(
        slippageDriftBps = slippageDriftBps,
        fillQualityDeltaBps = fillQualityDeltaBps,
        latencyDriftMs = latencyDriftMs,
        backtestEdgeBps = baseline?.backtestEdgeBps,
        liveEdgeBps = liveEdgeBps
    )
    tradingTelemetryMetrics?.recordDrift(
        strategyName = strategyName,
        exchange = exchange,
        executionMode = executionMode,
        slippageDriftBps = slippageDriftBps,
        fillQualityDecayBps = fillQualityDeltaBps,
        latencyDriftMs = latencyDriftMs,
        driftScore = driftScore,
        submitToFillMs = submitToFillMs,
        totalCostBps = result.costs.totalCostBps,
        impactBps = result.costs.impactBps,
        adverseSelectionBps = result.costs.adverseSelectionBps,
        fundingDriftBps = result.costs.fundingDriftBps,
        basisDriftBps = result.costs.basisDriftBps,
        feeTierAdjustmentBps = result.costs.feeTierAdjustmentBps,
        edgeAfterCostBps = liveEdgeBps
    )
    dbService.logLiveBacktestDrift(
        strategyName = strategyName,
        symbol = result.symbol,
        liveEdgeBps = liveEdgeBps,
        backtestEdgeBps = baseline?.backtestEdgeBps,
        fillQualityDeltaBps = fillQualityDeltaBps,
        slippageDriftBps = slippageDriftBps,
        latencyDriftMs = latencyDriftMs,
        driftScore = driftScore,
        metadataJson = buildDriftAnalyticsMetadataJson(
            username = username,
            exchange = exchange,
            executionMode = executionMode,
            orderRequest = orderRequest,
            result = result,
            baseline = baseline,
            submitToFillMs = submitToFillMs
        )
    )
}

private fun computeDriftScore(
    slippageDriftBps: Double?,
    fillQualityDeltaBps: Double?,
    latencyDriftMs: Double?,
    backtestEdgeBps: Double?,
    liveEdgeBps: Double
): Double? {
    val penalties = mutableListOf<Double>()
    slippageDriftBps?.takeIf { it > 0.0 }?.let { penalties += it }
    fillQualityDeltaBps?.takeIf { it > 0.0 }?.let { penalties += it }
    latencyDriftMs?.takeIf { it > 0.0 }?.let { penalties += it / 10.0 }
    backtestEdgeBps?.let {
        val edgeDecay = it - liveEdgeBps
        if (edgeDecay > 0.0) {
            penalties += edgeDecay
        }
    }
    if (penalties.isEmpty()) return null
    return penalties.sum()
}

private fun buildDriftAnalyticsMetadataJson(
    username: String,
    exchange: String,
    executionMode: String,
    orderRequest: OrderRequest,
    result: PaperOrderResponse,
    baseline: StrategyExecutionBaseline?,
    submitToFillMs: Double
): String {
    return """
        {
          "source":"tx-gateway",
          "username":"${jsonEscape(username)}",
          "exchange":"${jsonEscape(exchange)}",
          "executionMode":"${jsonEscape(executionMode)}",
          "status":"${jsonEscape(result.status)}",
          "orderType":"${jsonEscape(result.type)}",
          "urgencyClass":"${jsonEscape(result.policy.urgencyClass)}",
          "fillRatio":${result.fillRatio},
          "submitToFillMs":${jsonNumberOrNull(submitToFillMs)},
          "liveTotalCostBps":${jsonNumberOrNull(result.costs.totalCostBps)},
          "baselineAvgSlippageBps":${jsonNumberOrNull(baseline?.avgSlippageBps)},
          "baselineAvgSubmitToFillMs":${jsonNumberOrNull(baseline?.avgSubmitToFillMs)},
          "baselineAvgFillRatio":${jsonNumberOrNull(baseline?.avgFillRatio)},
          "baselineBacktestEdgeBps":${jsonNumberOrNull(baseline?.backtestEdgeBps)},
          "reduceOnly":${orderRequest.reduceOnly}
        }
    """.trimIndent()
}

private fun buildPaperAnalyticsMetadataJson(
    username: String,
    orderRequest: OrderRequest,
    result: PaperOrderResponse
): String {
    return """
        {
          "source":"tx-gateway",
          "username":"${jsonEscape(username)}",
          "status":"${jsonEscape(result.status)}",
          "executionMode":"${jsonEscape(result.executionMode)}",
          "orderType":"${jsonEscape(result.type)}",
          "urgencyClass":"${jsonEscape(result.policy.urgencyClass)}",
          "simulated":${result.simulated},
          "fillRatio":${result.fillRatio},
          "quoteAgeMs":${result.quoteAgeMs},
          "reduceOnly":${orderRequest.reduceOnly}
        }
    """.trimIndent()
}

private fun jsonEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun jsonNumberOrNull(value: Double?): String {
    if (value == null || !value.isFinite()) return "null"
    return value.toString()
}

private fun buildLiveAnalyticsMetadataJson(
    username: String,
    orderRequest: OrderRequest,
    result: Map<String, Any>,
    preview: PaperOrderResponse,
    executionMode: String
): String {
    val resultStatus = result["status"]?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
    val resultOrderId = result["orderId"]?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
    val resultFilledSize = result["filledSize"]?.toString()?.takeIf { it.isNotBlank() } ?: preview.filledSize
    return """
        {
          "source":"tx-gateway",
          "username":"${jsonEscape(username)}",
          "status":"${jsonEscape(resultStatus)}",
          "orderId":"${jsonEscape(resultOrderId)}",
          "exchange":"${jsonEscape(preview.exchange)}",
          "executionMode":"${jsonEscape(executionMode)}",
          "simulated":false,
          "previewOnlyCosts":true,
          "orderType":"${jsonEscape(preview.type)}",
          "urgencyClass":"${jsonEscape(preview.policy.urgencyClass)}",
          "fillRatio":${preview.fillRatio},
          "filledSize":"${jsonEscape(resultFilledSize)}",
          "quoteAgeMs":${preview.quoteAgeMs},
          "reduceOnly":${orderRequest.reduceOnly}
        }
    """.trimIndent()
}

private fun RiskDecision.toRejectionPayload(): RiskRejectionResponse {
    return RiskRejectionResponse(
        error = "Order blocked by risk engine",
        risk = RiskRejectionDetails(
            allowed = allowed,
            tier = tier.name.lowercase(),
            action = action.name.lowercase(),
            reason = reason,
            policyId = policyId,
            policyVersion = policyVersion,
            suggestedMaxOrderNotionalUsd = suggestedMaxOrderNotionalUsd?.toPlainString(),
            unwindSliceSeconds = unwindSliceSeconds,
            unwindMaxSlippageBps = unwindMaxSlippageBps,
            metrics = RiskRejectionMetrics(
                currentExposureUsd = metrics.currentExposureUsd.toPlainString(),
                projectedExposureUsd = metrics.projectedExposureUsd.toPlainString(),
                accountEquityUsd = metrics.accountEquityUsd.toPlainString(),
                highWaterMarkUsd = metrics.highWaterMarkUsd.toPlainString(),
                dailyLossUsd = metrics.dailyLossUsd.toPlainString(),
                leverage = metrics.leverage,
                exposureUtilization = metrics.exposureUtilization,
                drawdownPct = metrics.drawdownPct,
                drawdownUtilization = metrics.drawdownUtilization,
                dailyLossUtilization = metrics.dailyLossUtilization,
                leverageUtilization = metrics.leverageUtilization
            ),
            sentiment = sentiment?.let {
                RiskRejectionSentiment(
                    symbol = it.symbol,
                    sentimentScore = it.sentimentScore,
                    confidence = it.confidence,
                    observedAt = it.observedAt.toString(),
                    modelName = it.modelName,
                    source = it.source,
                    label = it.sentimentLabel
                )
            }
        )
    )
}

private fun RiskPolicyRecord.toWalletLinkedRiskPolicyResponse(): WalletLinkedRiskPolicyResponse {
    return WalletLinkedRiskPolicyResponse(
        id = id.toString(),
        username = username,
        walletAddress = walletAddress,
        version = version,
        status = status,
        createdBy = createdBy,
        createdAt = createdAt.toString(),
        activatedAt = activatedAt?.toString(),
        activatedByWallet = activatedByWallet,
        isBootstrap = isBootstrap
    )
}

private fun buildUserWalletLinkFindings(
    audit: TradingAccountAudit,
    activePolicy: RiskPolicyRecord?,
    linkedWallets: List<String>
): List<String> {
    val findings = audit.findings.toMutableList()
    if (activePolicy == null) {
        findings += "no active risk policy"
    } else if (activePolicy.walletAddress.isNullOrBlank()) {
        findings += "active risk policy has no wallet link"
    }

    if (linkedWallets.isEmpty()) {
        findings += "no wallet linked to risk policy history"
    }
    if (linkedWallets.size > 1) {
        findings += "multiple wallets linked across policy history"
    }

    val activeWallet = activePolicy?.walletAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val ldapWallet = audit.evmAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (activeWallet != null && ldapWallet != null && activeWallet != ldapWallet) {
        findings += "active wallet link differs from LDAP evmAddress"
    }
    if (activeWallet != null && ldapWallet == null) {
        findings += "LDAP trading account has no evmAddress"
    }

    return findings.distinct()
}

private fun buildAdminWalletLinkFindings(
    audit: TradingAccountAudit?,
    walletAddress: String,
    duplicateOwners: List<String>
): List<String> {
    val findings = mutableListOf<String>()
    if (audit == null) {
        findings += "active wallet link has no matching LDAP trading audit"
    } else {
        findings += audit.findings
        val ldapWallet = audit.evmAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (ldapWallet == null) {
            findings += "LDAP trading account has no evmAddress"
        } else if (walletAddress != ldapWallet) {
            findings += "active wallet link differs from LDAP evmAddress"
        }
    }
    if (duplicateOwners.isNotEmpty()) {
        findings += "wallet linked to multiple users: ${duplicateOwners.joinToString(",")}"
    }
    return findings.distinct()
}

private fun normalizeExchangeMarketDescriptor(raw: Map<String, Any?>): ExchangeMarketDescriptor? {
    val symbol = raw["symbol"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val attributes = raw.entries
        .asSequence()
        .filter { (key, value) -> key != "symbol" && value != null }
        .map { (key, value) -> key to formatMarketAttributeValue(value) }
        .filter { (_, value) -> value.isNotEmpty() }
        .associate { it }
    return ExchangeMarketDescriptor(symbol = symbol, attributes = attributes)
}

private fun formatMarketAttributeValue(value: Any?): String =
    when (value) {
        null -> ""
        is String -> value.trim()
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> dynamicJson.toJson(value)
    }

private data class AuthorizedTradingUser(
    val username: String,
    val userInfo: org.datamancy.txgateway.models.UserInfo
)

private suspend fun extractAuthorizedTradingAdmin(
    call: RoutingCall,
    authService: AuthService,
    ldapService: LdapService
): AuthorizedTradingUser? {
    val username = extractAuthenticatedUsername(call, authService) ?: return null
    val userInfo = ldapService.getUserInfo(username)
        ?: run {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("User not found"))
            return null
        }
    val groups = userInfo.groups
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    if (groups.intersect(tradingAdminGroups).isEmpty()) {
        call.respond(
            HttpStatusCode.Forbidden,
            RequiredGroupsErrorResponse(
                error = "Trading admin role required",
                requiredGroups = tradingAdminGroups.toList().sorted()
            )
        )
        return null
    }
    return AuthorizedTradingUser(username = username, userInfo = userInfo)
}

private suspend fun extractAuthenticatedUsername(
    call: RoutingCall,
    authService: AuthService
): String? {
    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing token"))
        return null
    }

    val jwt = authService.validateToken(token)
    if (jwt == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return null
    }

    val username = authService.extractUsername(jwt)
    if (username == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token claims"))
        return null
    }

    return username
}
