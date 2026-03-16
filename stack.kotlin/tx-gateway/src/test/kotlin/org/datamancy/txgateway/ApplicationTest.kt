package org.datamancy.txgateway

import com.auth0.jwt.interfaces.DecodedJWT
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

    @Test
    fun testBestQuoteEndpointSelectsBySide() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)

            every { dbService.fetchLatestQuote("hyperliquid", "BTC") } returns LatestQuote(
                exchange = "hyperliquid",
                symbol = "BTC",
                bid = 72990.0,
                ask = 73000.0,
                last = 72995.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73010.0,
                ask = 73020.0,
                last = 73015.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val buyResponse = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=buy&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.OK, buyResponse.status)
        val buyBody = buyResponse.bodyAsText()
        assertTrue(buyBody.contains("\"selectedExchange\": \"hyperliquid\""))
        assertTrue(buyBody.contains("\"side\": \"buy\""))

        val sellResponse = client.get("/api/v1/exchanges/best-quote?symbol=BTC&side=sell&exchanges=hyperliquid,binance")
        assertEquals(HttpStatusCode.OK, sellResponse.status)
        val sellBody = sellResponse.bodyAsText()
        assertTrue(sellBody.contains("\"selectedExchange\": \"binance\""))
        assertTrue(sellBody.contains("\"side\": \"sell\""))
    }

    @Test
    fun testPaperOrderEndpointForNonLiveExchange() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("binance", "hyperliquid"),
                maxTxPerHour = 100,
                maxTxValueUSD = 10000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("binance", "BTC") } returns LatestQuote(
                exchange = "binance",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/binance/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"MARKET","size":"0.25"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"simulated\"\\s*:\\s*true").containsMatchIn(body), body)
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"paper\"").containsMatchIn(body), body)
        assertTrue(Regex("\"status\"\\s*:\\s*\"FILLED\"").containsMatchIn(body), body)
    }

    @Test
    fun testPaperLimitOrderCanRemainPending() = testApplication {
        application {
            val authService = mockk<AuthService>(relaxed = true)
            val ldapService = mockk<LdapService>(relaxed = true)
            val workerClient = mockk<WorkerClient>(relaxed = true)
            val dbService = mockk<DatabaseService>(relaxed = true)
            val jwt = mockk<DecodedJWT>(relaxed = true)

            every { authService.validateToken("token") } returns jwt
            every { authService.extractUsername(jwt) } returns "trader1"
            every { ldapService.getUserInfo("trader1") } returns org.datamancy.txgateway.models.UserInfo(
                username = "trader1",
                email = "trader1@datamancy.net",
                groups = listOf("traders"),
                evmAddress = null,
                allowedChains = listOf("base"),
                allowedExchanges = listOf("coinbase"),
                maxTxPerHour = 100,
                maxTxValueUSD = 10000
            )
            every { dbService.checkRateLimit("trader1", 100) } returns true
            every { dbService.fetchLatestQuote("coinbase", "BTC") } returns LatestQuote(
                exchange = "coinbase",
                symbol = "BTC",
                bid = 73000.0,
                ask = 73010.0,
                last = 73005.0,
                timestamp = java.time.Instant.parse("2026-03-16T00:00:00Z"),
                source = "market_data:trade"
            )

            configureApp(authService, ldapService, workerClient, dbService)
        }

        val response = client.post("/api/v1/exchanges/coinbase/order") {
            header(HttpHeaders.Authorization, "Bearer token")
            contentType(ContentType.Application.Json)
            setBody("""{"symbol":"BTC","side":"BUY","type":"LIMIT","size":"0.25","price":"72000"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(Regex("\"status\"\\s*:\\s*\"PENDING\"").containsMatchIn(body), body)
        assertTrue(Regex("\"filledSize\"\\s*:\\s*\"0\"").containsMatchIn(body), body)
        assertTrue(Regex("\"executionMode\"\\s*:\\s*\"paper\"").containsMatchIn(body), body)
    }
}
