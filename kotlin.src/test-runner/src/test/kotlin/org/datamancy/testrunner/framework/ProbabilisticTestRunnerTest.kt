package org.datamancy.testrunner.framework

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class ProbabilisticTestRunnerTest {

    private lateinit var mockHttpClient: HttpClient
    private lateinit var mockServiceClient: ServiceClient
    private lateinit var mockEnvironment: TestEnvironment
    private lateinit var probRunner: ProbabilisticTestRunner

    @BeforeEach
    fun setup() {
        mockEnvironment = TestEnvironment.Localhost

        mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("""{"status":"ok"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }

        mockServiceClient = ServiceClient(mockEnvironment.endpoints, mockHttpClient)
        probRunner = ProbabilisticTestRunner(mockEnvironment, mockServiceClient, mockHttpClient)
    }

    @AfterEach
    fun teardown() {
        mockHttpClient.close()
    }

    @Nested
    @DisplayName("ProbabilisticTest")
    inner class ProbabilisticTestTests {

        @Test
        fun `should pass when success rate is within acceptable failure rate`() = runBlocking {
            val result = probRunner.probabilisticTest(
                name = "Test with acceptable failures",
                trials = 10,
                acceptableFailureRate = 0.3  
            ) {
                
                (0..9).random() >= 2
            }

            assertTrue(result.passed)
            assertTrue(result.actualFailureRate <= 0.3)
        }

        @Test
        fun `should fail when success rate exceeds acceptable failure rate`() = runBlocking {
            var count = 0
            val result = probRunner.probabilisticTest(
                name = "Test with too many failures",
                trials = 10,
                acceptableFailureRate = 0.1  
            ) {
                
                (count++ % 2) == 0
            }

            assertFalse(result.passed)
            assertTrue(result.actualFailureRate > 0.1)
        }

        @Test
        fun `should pass with 100 percent success rate`() = runBlocking {
            val result = probRunner.probabilisticTest(
                name = "Test that always succeeds",
                trials = 20,
                acceptableFailureRate = 0.0
            ) {
                true
            }

            assertTrue(result.passed)
            assertEquals(0.0, result.actualFailureRate)
            assertEquals(20, result.successCount)
        }

        @Test
        fun `should track outcomes for all trials`() = runBlocking {
            val trials = 15
            val result = probRunner.probabilisticTest(
                name = "Outcome tracking test",
                trials = trials,
                acceptableFailureRate = 0.5
            ) {
                true
            }

            assertEquals(trials, result.outcomes.size)
            result.outcomes.forEachIndexed { index, outcome ->
                assertEquals(index + 1, outcome.trial)
                assertTrue(outcome.success)
            }
        }

        @Test
        fun `should reject invalid parameters`() {
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    probRunner.probabilisticTest(
                        name = "Invalid trials",
                        trials = 0,
                        acceptableFailureRate = 0.1
                    ) { true }
                }
            }

            assertThrows<IllegalArgumentException> {
                runBlocking {
                    probRunner.probabilisticTest(
                        name = "Invalid failure rate",
                        trials = 10,
                        acceptableFailureRate = 1.5
                    ) { true }
                }
            }
        }
    }

    @Nested
    @DisplayName("LatencyTest")
    inner class LatencyTestTests {

        @Test
        fun `should pass when latencies are within thresholds`() = runBlocking {
            val result = probRunner.latencyTest(
                name = "Fast operation test",
                trials = 20,
                maxMedianLatency = 100,
                maxP95Latency = 200
            ) {
                
                50L
            }

            assertTrue(result.passed)
            assertTrue(result.medianMs <= 100)
            assertTrue(result.p95Ms <= 200)
        }

        @Test
        fun `should fail when median exceeds threshold`() = runBlocking {
            val result = probRunner.latencyTest(
                name = "Slow median test",
                trials = 20,
                maxMedianLatency = 50,
                maxP95Latency = 200
            ) {
                
                100L
            }

            assertFalse(result.passed)
            assertTrue(result.medianMs > 50)
        }

        @Test
        fun `should fail when p95 exceeds threshold`() = runBlocking {
            val result = probRunner.latencyTest(
                name = "High p95 test",
                trials = 20,
                maxMedianLatency = 1000,
                maxP95Latency = 100
            ) {
                
                if ((0..19).random() > 17) 500L else 50L
            }

            assertFalse(result.passed)
            assertTrue(result.p95Ms > 100)
        }

        @Test
        fun `should calculate statistics correctly`() = runBlocking {
            val latencies = listOf(10L, 20L, 30L, 40L, 50L)
            var index = 0
            val result = probRunner.latencyTest(
                name = "Stats calculation test",
                trials = latencies.size,
                maxMedianLatency = 1000,
                maxP95Latency = 1000
            ) {
                latencies[index++ % latencies.size]
            }

            assertTrue(result.passed)
            assertTrue(result.minMs >= 10L)
            assertTrue(result.maxMs <= 50L)
            assertTrue(result.meanMs in 10L..50L)
            assertTrue(result.medianMs in 10L..50L)
        }

        @Test
        fun `should handle failed operations gracefully`() = runBlocking {
            val result = probRunner.latencyTest(
                name = "Failed operation test",
                trials = 10,
                maxMedianLatency = 100,
                maxP95Latency = 200
            ) {
                throw RuntimeException("Operation failed")
            }

            assertFalse(result.passed)
            
            assertEquals(Long.MAX_VALUE, result.maxMs)
        }
    }

    @Nested
    @DisplayName("ThroughputTest")
    inner class ThroughputTestTests {

        @Test
        fun `should pass when throughput meets minimum`() = runBlocking {
            val result = probRunner.throughputTest(
                name = "High throughput test",
                durationSeconds = 1,
                minOpsPerSecond = 5.0
            ) {
                
                
            }

            assertTrue(result.passed)
            assertTrue(result.opsPerSecond >= 5.0)
        }

        @Test
        fun `should fail when throughput is below minimum`() = runBlocking {
            val result = probRunner.throughputTest(
                name = "Low throughput test",
                durationSeconds = 1,
                minOpsPerSecond = 1000.0  
            ) {
                Thread.sleep(10)  
            }

            assertFalse(result.passed)
            assertTrue(result.opsPerSecond < 1000.0)
        }

        @Test
        fun `should track total operations and errors`() = runBlocking {
            var callCount = 0
            val result = probRunner.throughputTest(
                name = "Operation tracking test",
                durationSeconds = 1,
                minOpsPerSecond = 1.0
            ) {
                callCount++
                if (callCount % 3 == 0) throw RuntimeException("Simulated error")
            }

            assertTrue(result.totalOperations > 0)
            assertTrue(result.errors > 0)
            assertTrue(result.errorRate > 0.0)
            assertTrue(result.errorRate < 1.0)  
        }

        @Test
        fun `should calculate ops per second correctly`() = runBlocking {
            val result = probRunner.throughputTest(
                name = "Ops calculation test",
                durationSeconds = 2,
                minOpsPerSecond = 1.0
            ) {
                Thread.sleep(100)  
            }

            assertTrue(result.passed)
            assertTrue(result.durationSeconds >= 2.0)
            assertTrue(result.durationSeconds < 2.5)  
        }

        @Test
        fun `should reject invalid duration`() {
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    probRunner.throughputTest(
                        name = "Invalid duration",
                        durationSeconds = 0,
                        minOpsPerSecond = 1.0
                    ) {}
                }
            }
        }
    }

    @Nested
    @DisplayName("Summary")
    inner class SummaryTests {

        @Test
        fun `should aggregate results correctly`() = runBlocking {
            
            probRunner.probabilisticTest("pass1", 5, 0.5) { true }
            probRunner.probabilisticTest("pass2", 5, 0.5) { true }

            
            var count = 0
            probRunner.probabilisticTest("fail1", 5, 0.0) { (count++ % 2) == 0 }

            val summary = probRunner.summary()

            assertEquals(3, summary.total)
            assertEquals(2, summary.passed)
            assertEquals(1, summary.failed)
        }

        @Test
        fun `should include all test types in summary`() = runBlocking {
            probRunner.probabilisticTest("prob", 5, 0.5) { true }
            probRunner.latencyTest("lat", 5, 1000, 2000) { 100L }
            probRunner.throughputTest("thru", 1, 1.0) { Thread.sleep(1) }

            val summary = probRunner.summary()

            assertEquals(3, summary.total)
            assertEquals(3, summary.results.size)
            assertTrue(summary.results.all { it.passed })
        }
    }

    @Nested
    @DisplayName("ProbabilisticTestContext")
    inner class ProbabilisticTestContextTests {

        @Test
        fun `shouldBe assertion works correctly`() {
            val context = ProbabilisticTestContext(mockServiceClient, mockEnvironment)

            assertDoesNotThrow {
                context.run { true shouldBe true }
            }

            assertThrows<AssertionError> {
                context.run { true shouldBe false }
            }
        }

        @Test
        fun `shouldContain assertion works correctly`() {
            val context = ProbabilisticTestContext(mockServiceClient, mockEnvironment)

            assertDoesNotThrow {
                context.run { "hello world" shouldContain "world" }
            }

            assertThrows<AssertionError> {
                context.run { "hello world" shouldContain "goodbye" }
            }
        }
    }
}
