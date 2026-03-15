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
import kotlinx.serialization.json.Json
import org.datamancy.txgateway.routes.evmRoutes
import org.datamancy.txgateway.routes.hyperliquidRoutes
import org.datamancy.txgateway.services.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")
private const val INIT_MAX_ATTEMPTS = 60
private const val INIT_DELAY_MS = 2000L

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
            call.respond(HttpStatusCode.OK, mapOf(
                "limits" to mapOf(
                    "evm_transfer" to mapOf("per_hour" to 100, "per_day" to 1000),
                    "hyperliquid_order" to mapOf("per_minute" to 20, "per_hour" to 200)
                )
            ))
        }

        get("/") {
            call.respondText("""
                {
                  "service": "tx-gateway",
                  "version": "1.0.0",
                  "endpoints": [
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
    }

    logger.info("tx-gateway started on port ${environment.config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
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
