package org.datamancy.txgateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.every
import io.ktor.server.testing.*
import io.mockk.mockk
import org.datamancy.txgateway.services.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun testHealthEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("healthy"))
    }

    @Test
    fun testRootEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("tx-gateway"))
        assertTrue(body.contains("endpoints"))
    }

    @Test
    fun testApiV1HealthEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("healthy"))
    }

    @Test
    fun testUnifiedExchangesEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("hyperliquid"))
        assertTrue(body.contains("binance"))
        assertTrue(body.contains("swyftx"))
    }

    @Test
    fun testUnifiedQuoteEndpoint() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "orderbook_data"
            )
            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.get("/api/v1/exchanges/hyperliquid/quote?symbol=BTC")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"bid\""))
        assertTrue(body.contains("73000"))
    }
}
