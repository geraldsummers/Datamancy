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

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080

    // Initialize services
    val authService = AuthService()
    val ldapService = LdapService()
    val workerClient = WorkerClient()
    val dbService = DatabaseService()

    try {
        ldapService.init()
        dbService.init()
    } catch (e: Exception) {
        logger.error("Failed to initialize services", e)
        throw e
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

        get("/") {
            call.respond(HttpStatusCode.OK, mapOf(
                "service" to "tx-gateway",
                "version" to "1.0.0",
                "endpoints" to listOf(
                    "/api/v1/hyperliquid/order",
                    "/api/v1/hyperliquid/cancel/{orderId}",
                    "/api/v1/hyperliquid/positions",
                    "/api/v1/hyperliquid/balance",
                    "/api/v1/evm/transfer",
                    "/api/v1/evm/addressbook/{user}"
                )
            ))
        }

        hyperliquidRoutes(authService, ldapService, workerClient, dbService)
        evmRoutes(authService, ldapService, workerClient, dbService)
    }

    logger.info("tx-gateway started on port ${environment.config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"}")
}
