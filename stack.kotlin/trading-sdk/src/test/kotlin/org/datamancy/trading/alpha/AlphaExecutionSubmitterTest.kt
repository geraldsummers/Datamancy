package org.datamancy.trading.alpha

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlphaExecutionSubmitterTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var submitter: AlphaExecutionSubmitter

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        submitter = AlphaExecutionSubmitter {
            mockServer.url("/").toString().removeSuffix("/")
        }
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `submit quantizes Hyperliquid size using market metadata`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "exchange": "hyperliquid",
                      "count": 1,
                      "markets": [
                        {
                          "symbol": "FET",
                          "attributes": {
                            "szDecimals": "0",
                            "maxLeverage": "50"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "orderId": "order-123",
                      "status": "FILLED",
                      "filledSize": "459",
                      "fillPrice": "0.24384"
                    }
                    """.trimIndent()
                )
        )

        val result = submitter.submit(
            request = AlphaExecutionSubmitRequest(
                exchange = "hyperliquid",
                symbol = "FET",
                direction = AlphaDirection.LONG,
                size = 459.031786,
                mode = AlphaRunMode.TESTNET_LIVE
            ),
            bearerToken = "test-token",
            credentials = mapOf("hyperliquid" to "test-key")
        )

        assertTrue(result.accepted)
        assertTrue(result.notes.any { it.contains("Quantized FET size from 459.031786 to 459") }, result.notes.joinToString(" | "))

        val marketRequest = mockServer.takeRequest()
        assertEquals("/api/v1/exchanges/hyperliquid/markets", marketRequest.path)

        val orderRequest = mockServer.takeRequest()
        assertEquals("/api/v1/exchanges/hyperliquid/order", orderRequest.path)
        val body = orderRequest.body.readUtf8()
        assertTrue(body.contains("\"size\":\"459\""), body)
        assertTrue(body.contains("\"executionMode\":\"testnet_live\""), body)
    }

    @Test
    fun `submit fails loudly when venue precision rounds size to zero`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "exchange": "hyperliquid",
                      "count": 1,
                      "markets": [
                        {
                          "symbol": "TAO",
                          "attributes": {
                            "szDecimals": "3"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                submitter.submit(
                    request = AlphaExecutionSubmitRequest(
                        exchange = "hyperliquid",
                        symbol = "TAO",
                        direction = AlphaDirection.LONG,
                        size = 0.0004,
                        mode = AlphaRunMode.TESTNET_LIVE
                    ),
                    bearerToken = "test-token",
                    credentials = mapOf("hyperliquid" to "test-key")
                )
            }
        }

        assertTrue(error.message!!.contains("rounds to zero"), error.message)
    }
}
