package org.datamancy.txgateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.datamancy.txgateway.routes.evmRoutes
import org.datamancy.txgateway.routes.hyperliquidRoutes
import org.datamancy.txgateway.routes.unifiedExchangeRoutes
import org.datamancy.txgateway.services.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")
private const val INIT_MAX_ATTEMPTS = 60
private const val INIT_DELAY_MS = 2000L
private const val DEFAULT_EVM_RATE_LIMIT_PER_MINUTE = 120
private const val DEFAULT_EVM_RATE_LIMIT_PER_HOUR = 1000
private const val DEFAULT_HL_RATE_LIMIT_PER_MINUTE = 240
private const val DEFAULT_HL_RATE_LIMIT_PER_HOUR = 3000

@Serializable
private data class RateLimitConfig(
    val limit: Int,
    val window: String,
    val per_minute: Int,
    val per_hour: Int
)

@Serializable
private data class RateLimitResponse(
    val limits: Map<String, RateLimitConfig>
)

@Serializable
private data class SchemaHealthResponse(
    val status: String,
    val service: String,
    val tables: List<String>,
    val raw_tables: List<String>,
    val aliases: List<String>
)

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080

    // Initialize services
    val authService = AuthService()
    val ldapService = LdapService()
    val workerClient = WorkerClient()
    val dbService = DatabaseService()

    initializeWithRetry("LDAP service", INIT_MAX_ATTEMPTS, INIT_DELAY_MS) {
        ldapService.init()
    }
    initializeWithRetry("Database service", INIT_MAX_ATTEMPTS, INIT_DELAY_MS) {
        dbService.init()
    }

    embeddedServer(Netty, port = port) {
        configureApp(authService, ldapService, workerClient, dbService)
    }.start(wait = true)
}

fun Application.configureApp(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "service" to "tx-gateway"))
        }

        get("/health/db") {
            val dbHealthy = try {
                dbService.healthCheck()
                true
            } catch (e: Exception) {
                false
            }
            call.respond(HttpStatusCode.OK, mapOf("database" to if (dbHealthy) "connected" else "disconnected"))
        }

        get("/health/ldap") {
            val ldapHealthy = try {
                ldapService.healthCheck()
                true
            } catch (e: Exception) {
                false
            }
            call.respond(HttpStatusCode.OK, mapOf("ldap" to if (ldapHealthy) "connected" else "disconnected"))
        }

        get("/health/authelia") {
            val autheliaHealthy = try {
                authService.healthCheck()
                true
            } catch (e: Exception) {
                false
            }
            call.respond(HttpStatusCode.OK, mapOf("authelia" to if (autheliaHealthy) "reachable" else "unreachable"))
        }

        get("/health/workers") {
            val (evmHealthy, hlHealthy) = try {
                workerClient.healthCheck()
            } catch (e: Exception) {
                Pair(false, false)
            }
            call.respond(HttpStatusCode.OK, mapOf(
                "evm_broadcaster" to if (evmHealthy) "reachable" else "unreachable",
                "hyperliquid_worker" to if (hlHealthy) "reachable" else "unreachable"
            ))
        }

        get("/rate-limits") {
            val evmPerMinute = parseIntEnv("TXG_EVM_RATE_LIMIT_PER_MINUTE", DEFAULT_EVM_RATE_LIMIT_PER_MINUTE)
            val evmPerHour = parseIntEnv("TXG_EVM_RATE_LIMIT_PER_HOUR", DEFAULT_EVM_RATE_LIMIT_PER_HOUR)
            val hlPerMinute = parseIntEnv("TXG_HL_RATE_LIMIT_PER_MINUTE", DEFAULT_HL_RATE_LIMIT_PER_MINUTE)
            val hlPerHour = parseIntEnv("TXG_HL_RATE_LIMIT_PER_HOUR", DEFAULT_HL_RATE_LIMIT_PER_HOUR)

            val payload = RateLimitResponse(
                limits = mapOf(
                    "evm_transfer" to RateLimitConfig(
                        limit = evmPerMinute,
                        window = "minute",
                        per_minute = evmPerMinute,
                        per_hour = evmPerHour
                    ),
                    "hyperliquid_order" to RateLimitConfig(
                        limit = hlPerMinute,
                        window = "minute",
                        per_minute = hlPerMinute,
                        per_hour = hlPerHour
                    )
                )
            )
            call.respondText(
                Json.encodeToString(RateLimitResponse.serializer(), payload),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/health/schema") {
            val overview = try {
                dbService.schemaOverview()
            } catch (e: Exception) {
                logger.warn("Failed to compute schema overview", e)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("status" to "error", "message" to (e.message ?: "schema check failed"))
                )
                return@get
            }

            val payload = SchemaHealthResponse(
                status = "ok",
                service = "tx-gateway",
                tables = overview.tables,
                raw_tables = overview.rawTables,
                aliases = overview.aliases
            )
            call.respondText(
                Json.encodeToString(SchemaHealthResponse.serializer(), payload),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        get("/") {
            call.respondText("""
                {
                  "service": "tx-gateway",
                  "version": "1.0.0",
                  "endpoints": [
                    "/api/v1/health",
                    "/api/v1/user",
                    "/api/v1/history",
                    "/api/v1/exchanges",
                    "/api/v1/exchanges/best-quote?symbol=BTC&side=buy",
                    "/api/v1/exchanges/{exchange}/quote?symbol=BTC",
                    "/api/v1/exchanges/{exchange}/order",
                    "/api/v1/hyperliquid/order",
                    "/api/v1/hyperliquid/cancel/{orderId}",
                    "/api/v1/hyperliquid/positions",
                    "/api/v1/hyperliquid/balance",
                    "/api/v1/evm/transfer",
                    "/api/v1/evm/addressbook/{user}"
                  ]
                }
            """.trimIndent(), ContentType.Application.Json)
        }

        hyperliquidRoutes(authService, ldapService, workerClient, dbService)
        evmRoutes(authService, ldapService, workerClient, dbService)
        unifiedExchangeRoutes(authService, ldapService, workerClient, dbService)
    }

    logger.info("tx-gateway started on port ${environment.config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
}

private fun parseIntEnv(name: String, default: Int): Int {
    val raw = System.getenv(name)?.trim().orEmpty()
    if (raw.isEmpty()) return default
    return raw.toIntOrNull()?.takeIf { it > 0 } ?: default
}

private fun initializeWithRetry(
    label: String,
    maxAttempts: Int,
    delayMs: Long,
    block: () -> Unit
) {
    var lastError: Exception? = null
    for (attempt in 1..maxAttempts) {
        try {
            if (attempt > 1) {
                logger.info("Retrying $label initialization ($attempt/$maxAttempts)")
            }
            block()
            return
        } catch (e: Exception) {
            lastError = e
            logger.warn("$label initialization failed ($attempt/$maxAttempts): ${e.message}")
            if (attempt < maxAttempts) {
                Thread.sleep(delayMs)
            }
        }
    }
    throw IllegalStateException("Failed to initialize $label after $maxAttempts attempts", lastError)
}
