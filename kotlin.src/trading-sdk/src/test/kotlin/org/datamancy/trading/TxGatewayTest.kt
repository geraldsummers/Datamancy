package org.datamancy.trading

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.datamancy.trading.models.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
}
