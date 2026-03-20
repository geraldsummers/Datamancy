package org.datamancy.trading

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.datamancy.trading.models.*
import org.datamancy.trading.strategy.ModeRoutedOrderRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TxGatewayTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var gateway: TxGateway

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        gateway = TxGateway.create(baseUrl, "test-token")
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `test hyperliquid market order success`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "12345",
                "symbol": "ETH-PERP",
                "side": "BUY",
                "type": "MARKET",
                "size": "1.0",
                "price": null,
                "status": "FILLED",
                "filledSize": "1.0",
                "fillPrice": "2450.50",
                "timestamp": "2026-02-07T00:00:00Z"
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.hyperliquid.market(
            symbol = "ETH-PERP",
            side = Side.BUY,
            size = BigDecimal.ONE
        )

        println("Result: $result")
        assertTrue(result is ApiResult.Success)
        val order = (result as ApiResult.Success).data
        assertEquals("12345", order.orderId)
        assertEquals("ETH-PERP", order.symbol)
        assertEquals(Side.BUY, order.side)
        assertEquals(OrderStatus.FILLED, order.status)
    }

    @Test
    fun `test hyperliquid limit order success`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "67890",
                "symbol": "BTC-PERP",
                "side": "SELL",
                "type": "LIMIT",
                "size": "0.5",
                "price": "50000.00",
                "status": "PENDING",
                "filledSize": "0.0",
                "fillPrice": null,
                "timestamp": "2026-02-07T00:00:00Z"
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.hyperliquid.limit(
            symbol = "BTC-PERP",
            side = Side.SELL,
            size = BigDecimal("0.5"),
            price = BigDecimal("50000")
        )

        assertTrue(result is ApiResult.Success)
        val order = (result as ApiResult.Success).data
        assertEquals("67890", order.orderId)
        assertEquals(OrderType.LIMIT, order.type)
        assertEquals(OrderStatus.PENDING, order.status)
    }

    @Test
    fun `test evm transfer success`() = runBlocking {
        val mockResponse = """
            {
                "txHash": "0xabc123",
                "from": "0xUser1",
                "to": "0xUser2",
                "toUser": "alice",
                "amount": "1000.0",
                "token": "USDC",
                "chain": "BASE",
                "status": "SUBMITTED",
                "timestamp": "2026-02-07T00:00:00Z",
                "confirmations": 0,
                "gasUsed": null
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.evm.transfer(
            toUser = "alice",
            amount = BigDecimal("1000"),
            token = Token.USDC,
            chain = Chain.BASE
        )

        assertTrue(result is ApiResult.Success)
        val transfer = (result as ApiResult.Success).data
        assertEquals("0xabc123", transfer.txHash)
        assertEquals("alice", transfer.toUser)
        assertEquals(Token.USDC, transfer.token)
        assertEquals(Chain.BASE, transfer.chain)
    }

    @Test
    fun `test error handling`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error": "Insufficient permissions"}""")
        )

        val result = gateway.hyperliquid.market(
            symbol = "ETH-PERP",
            side = Side.BUY,
            size = BigDecimal.ONE
        )

        assertTrue(result is ApiResult.Error)
        val error = result as ApiResult.Error
        assertEquals(403, error.code)
    }

    @Test
    fun `test chain enum has correct chain IDs`() {
        assertEquals(8453L, Chain.BASE.chainId)
        assertEquals(42161L, Chain.ARBITRUM.chainId)
        assertEquals(10L, Chain.OPTIMISM.chainId)
        assertEquals(1L, Chain.ETHEREUM.chainId)
    }

    @Test
    fun `test unified exchange order forwards execution controls`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-123",
                "status": "PENDING",
                "filledSize": "0",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "LIMIT"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.LIMIT,
                size = BigDecimal("0.5"),
                price = BigDecimal("72000"),
                urgencyClass = "high",
                feeTier = "vip",
                maxSlippageBps = BigDecimal("5.5"),
                cancelAfterMs = 750
            )
        )

        assertTrue(result is ApiResult.Success)
        val requestBody = mockServer.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains(""""urgencyClass":"high""""))
        assertTrue(requestBody.contains(""""feeTier":"vip""""))
        assertTrue(requestBody.contains(""""maxSlippageBps":5.5"""))
        assertTrue(requestBody.contains(""""cancelAfterMs":750"""))
    }

    @Test
    fun `test unified exchange order rejects impossible fill payload`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-124",
                "status": "FILLED",
                "filledSize": "-1",
                "fillPrice": "73100",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid filledSize"))
    }

    @Test
    fun `test unified exchange order rejects missing fill price for non-zero fill`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-125",
                "status": "PARTIALLY_FILLED",
                "filledSize": "0.1",
                "fillPrice": null,
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "LIMIT"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.LIMIT,
                size = BigDecimal("0.5"),
                price = BigDecimal("72000")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Missing fillPrice"))
    }

    @Test
    fun `test unified exchange order rejects filled status without fills`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-126",
                "status": "FILLED",
                "filledSize": "0",
                "fillPrice": null,
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid filledSize for FILLED status"))
    }

    @Test
    fun `test unified exchange order rejects filled status with partial fill size`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-127",
                "status": "FILLED",
                "filledSize": "0.2",
                "fillPrice": "73100",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid filledSize for FILLED status"))
    }

    @Test
    fun `test unified exchange order rejects partial status with fully filled size`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-128",
                "status": "PARTIALLY_FILLED",
                "filledSize": "0.5",
                "fillPrice": "73100",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid filledSize for PARTIALLY_FILLED status"))
    }

    @Test
    fun `test unified exchange order rejects pending status with non-zero fills`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-129",
                "status": "PENDING",
                "filledSize": "0.1",
                "fillPrice": "73100",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid status/filledSize combination"))
    }

    @Test
    fun `test unified exchange order rejects fill price without fills`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-130",
                "status": "REJECTED",
                "filledSize": "0",
                "fillPrice": "73100",
                "exchange": "binance",
                "symbol": "BTC",
                "side": "BUY",
                "type": "MARKET"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.placeOrder(
            UnifiedOrderRequest(
                exchange = ExchangeId.BINANCE,
                symbol = "BTC",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("0.5")
            )
        )

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("fillPrice provided without fills"))
    }

    @Test
    fun `test unified quote request URL-encodes symbol`() = runBlocking {
        val mockResponse = """
            {
                "exchange": "binance",
                "symbol": "BTC/USD",
                "bid": 73000.0,
                "ask": 73010.0,
                "last": 73005.0,
                "timestamp": "2026-02-07T00:00:00Z",
                "source": "market_data:trade"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.quote(ExchangeId.BINANCE, "BTC/USD")
        assertTrue(result is ApiResult.Success)

        val requestPath = mockServer.takeRequest().path ?: ""
        assertTrue(
            requestPath.contains("/api/v1/exchanges/binance/quote?symbol=BTC%2FUSD"),
            requestPath
        )
    }

    @Test
    fun `test unified quote rejects crossed orderbook snapshot`() = runBlocking {
        val mockResponse = """
            {
                "exchange": "binance",
                "symbol": "BTC/USD",
                "bid": 73010.0,
                "ask": 73000.0,
                "last": 73005.0,
                "timestamp": "2026-02-07T00:00:00Z",
                "source": "market_data:trade"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.quote(ExchangeId.BINANCE, "BTC/USD")
        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.contains("Invalid quote payload"))
    }

    @Test
    fun `test best quote via gateway URL-encodes symbol`() = runBlocking {
        val freshTimestamp = Instant.now().minusSeconds(5).toString()
        val mockResponse = """
            {
                "requestedSymbol": "BTC/USD",
                "normalizedSymbol": "BTC/USD",
                "side": "buy",
                "selectedExchange": "binance",
                "quote": {
                    "exchange": "binance",
                    "symbol": "BTC/USD",
                    "bid": 73000.0,
                    "ask": 73010.0,
                    "last": 73005.0,
                    "timestamp": "$freshTimestamp",
                    "source": "market_data:trade"
                },
                "comparedExchanges": ["binance"]
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.exchanges.bestQuoteViaGateway(
            symbol = "BTC/USD",
            side = Side.BUY,
            exchanges = listOf(ExchangeId.BINANCE)
        )
        assertTrue(result is ApiResult.Success)

        val requestPath = mockServer.takeRequest().path ?: ""
        assertTrue(
            requestPath.contains("/api/v1/exchanges/best-quote?symbol=BTC%2FUSD&side=buy&exchanges=binance"),
            requestPath
        )
    }

    @Test
    fun `test best quote via gateway falls back when gateway returns unexpected exchange`() = runBlocking {
        val freshTimestamp = Instant.now().minusSeconds(5).toString()
        val gatewayResponse = """
            {
                "requestedSymbol": "BTC/USD",
                "normalizedSymbol": "BTC/USD",
                "side": "buy",
                "selectedExchange": "hyperliquid",
                "quote": {
                    "exchange": "hyperliquid",
                    "symbol": "BTC/USD",
                    "bid": 73000.0,
                    "ask": 73010.0,
                    "last": 73005.0,
                    "timestamp": "$freshTimestamp",
                    "source": "market_data:trade"
                },
                "comparedExchanges": ["hyperliquid"]
            }
        """.trimIndent()
        val directQuoteResponse = """
            {
                "exchange": "binance",
                "symbol": "BTC/USD",
                "bid": 73100.0,
                "ask": 73110.0,
                "last": 73105.0,
                "timestamp": "$freshTimestamp",
                "source": "market_data:trade"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(gatewayResponse).setResponseCode(200))
        mockServer.enqueue(MockResponse().setBody(directQuoteResponse).setResponseCode(200))

        val result = gateway.exchanges.bestQuoteViaGateway(
            symbol = "BTC/USD",
            side = Side.BUY,
            exchanges = listOf(ExchangeId.BINANCE)
        )
        assertTrue(result is ApiResult.Success)
        assertEquals(ExchangeId.BINANCE, (result as ApiResult.Success).data.exchange)

        val firstPath = mockServer.takeRequest().path ?: ""
        val secondPath = mockServer.takeRequest().path ?: ""
        assertTrue(firstPath.contains("/api/v1/exchanges/best-quote"), firstPath)
        assertTrue(secondPath.contains("/api/v1/exchanges/binance/quote"), secondPath)
    }

    @Test
    fun `test best quote via gateway falls back when gateway quote is stale`() = runBlocking {
        val staleTimestamp = Instant.now().minusSeconds(7200).toString()
        val freshTimestamp = Instant.now().minusSeconds(5).toString()
        val staleGatewayResponse = """
            {
                "requestedSymbol": "BTC/USD",
                "normalizedSymbol": "BTC/USD",
                "side": "buy",
                "selectedExchange": "binance",
                "quote": {
                    "exchange": "binance",
                    "symbol": "BTC/USD",
                    "bid": 73000.0,
                    "ask": 73010.0,
                    "last": 73005.0,
                    "timestamp": "$staleTimestamp",
                    "source": "market_data:trade"
                },
                "comparedExchanges": ["binance"]
            }
        """.trimIndent()
        val freshDirectQuoteResponse = """
            {
                "exchange": "binance",
                "symbol": "BTC/USD",
                "bid": 73100.0,
                "ask": 73110.0,
                "last": 73105.0,
                "timestamp": "$freshTimestamp",
                "source": "market_data:trade"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(staleGatewayResponse).setResponseCode(200))
        mockServer.enqueue(MockResponse().setBody(freshDirectQuoteResponse).setResponseCode(200))

        val result = gateway.exchanges.bestQuoteViaGateway(
            symbol = "BTC/USD",
            side = Side.BUY,
            exchanges = listOf(ExchangeId.BINANCE)
        )
        assertTrue(result is ApiResult.Success)
        assertEquals(ExchangeId.BINANCE, (result as ApiResult.Success).data.exchange)
        assertEquals(0, BigDecimal("73110").compareTo(result.data.ask))

        val firstPath = mockServer.takeRequest().path ?: ""
        val secondPath = mockServer.takeRequest().path ?: ""
        assertTrue(firstPath.contains("/api/v1/exchanges/best-quote"), firstPath)
        assertTrue(secondPath.contains("/api/v1/exchanges/binance/quote"), secondPath)
    }

    @Test
    fun `test token contract addresses`() {
        assertEquals(
            "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
            Token.USDC.contractAddress(Chain.BASE)
        )
        assertEquals(null, Token.ETH.contractAddress(Chain.BASE))
    }

    @Test
    fun `test position computed properties`() {
        val position = Position(
            symbol = "ETH-PERP",
            size = BigDecimal("2.0"),
            entryPrice = BigDecimal("2000"),
            markPrice = BigDecimal("2500"),
            leverage = BigDecimal("5")
        )

        assertEquals(0, BigDecimal("5000").compareTo(position.notionalValue))
        assertEquals(0, BigDecimal("1000").compareTo(position.unrealizedPnl))
        // PnL% = unrealizedPnl / notionalValue * 100 = 1000/5000*100 = 20%
        // (This is PnL as percentage of current position value, not ROI)
        assertEquals(0, BigDecimal("20.0").compareTo(position.pnlPercent))
    }

    @Test
    fun `mode router backtest path stays local`() = runBlocking {
        val result = gateway.modeRouter.submit(
            ModeRoutedOrderRequest(
                mode = TradingMode.BACKTEST,
                strategyName = "wf-ema",
                exchange = "hyperliquid",
                symbol = "BTC-PERP",
                side = Side.BUY,
                type = OrderType.MARKET,
                size = BigDecimal("1.2"),
                price = BigDecimal("72000")
            )
        )

        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals(TradingMode.BACKTEST, data.mode)
        assertEquals(OrderStatus.FILLED, data.status)
        assertEquals(0, BigDecimal("1.2").compareTo(data.filledSize))
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `mode router forward paper remaps hyperliquid to paper venue`() = runBlocking {
        val mockResponse = """
            {
                "orderId": "paper-200",
                "status": "PENDING",
                "filledSize": "0",
                "exchange": "binance",
                "symbol": "BTC-PERP",
                "side": "BUY",
                "type": "LIMIT"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setBody(mockResponse).setResponseCode(200))

        val result = gateway.modeRouter.submit(
            ModeRoutedOrderRequest(
                mode = TradingMode.FORWARD_PAPER,
                strategyName = "wf-ema",
                exchange = "hyperliquid",
                symbol = "BTC-PERP",
                side = Side.BUY,
                type = OrderType.LIMIT,
                size = BigDecimal("0.4"),
                price = BigDecimal("69000")
            )
        )

        assertTrue(result is ApiResult.Success)
        val requestPath = mockServer.takeRequest().path ?: ""
        assertTrue(requestPath.contains("/api/v1/exchanges/binance/order"), requestPath)
    }
}
