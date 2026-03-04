package org.datamancy.testrunner.framework

import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Probabilistic test runner for performance and reliability testing under real-world conditions.
 *
 * ProbabilisticTestRunner handles flaky infrastructure gracefully by accepting that some operations
 * may fail intermittently due to network latency, external service unavailability, or resource
 * contention. Instead of binary pass/fail, tests define acceptable failure rates and performance
 * thresholds.
 *
 * ## Why Probabilistic Testing Matters
 * In distributed systems like Datamancy:
 * - **External Dependencies**: RSS feeds, CVE databases, Wikipedia may be temporarily unreachable
 * - **Network Latency**: Docker network, DNS resolution, and HTTP requests have variable latency
 * - **Resource Contention**: Multiple services competing for CPU, memory, and I/O
 * - **Eventual Consistency**: Vector databases, full-text indexes may lag behind writes
 *
 * Binary pass/fail tests would be too brittle. Probabilistic tests set realistic expectations.
 *
 * ## Test Types
 * 1. **Probabilistic Success/Failure** (`probabilisticTest`):
 *    - Run operation N times, accept up to X% failures
 *    - Use case: Validating external data source reliability
 *    - Example: "RSS feed fetch succeeds 80% of the time"
 *
 * 2. **Latency Testing** (`latencyTest`):
 *    - Measure latency distribution (median, p95, mean, stddev)
 *    - Use case: Validating search performance doesn't degrade
 *    - Example: "Hybrid search p95 latency < 5 seconds"
 *
 * 3. **Throughput Testing** (`throughputTest`):
 *    - Measure operations per second over time window
 *    - Use case: Validating agent tools can handle concurrent requests
 *    - Example: "Agent-tool-server handles 10+ requests/second"
 *
 * ## Integration with Broader Stack
 * Probabilistic tests validate:
 * - **Pipeline Reliability**: Can we fetch from RSS feeds consistently?
 * - **Search Performance**: Is hybrid search fast enough for interactive use?
 * - **Agent Tool Throughput**: Can tools handle concurrent LLM requests?
 * - **Database Performance**: Do queries complete within acceptable time limits?
 *
 * ## How This Handles Flaky Infrastructure
 * - **Acceptable Failure Rates**: Some failures are expected and tolerated
 * - **Statistical Analysis**: Median/p95 more robust than single measurements
 * - **Long-Running Tests**: Capture performance degradation over time
 * - **Realistic Expectations**: Tests reflect production behavior, not ideal conditions
 *
 * @property environment Test environment configuration
 * @property client ServiceClient for making HTTP requests
 * @property httpClient Raw Ktor HTTP client
 */
class ProbabilisticTestRunner(
    val environment: TestEnvironment,
    val client: ServiceClient,
    val httpClient: io.ktor.client.HttpClient
) {
    private val results = mutableListOf<ProbabilisticTestResultBase>()

    /**
     * Runs a probabilistic test that accepts a defined failure rate.
     *
     * This is critical for testing operations that interact with unreliable external systems
     * or depend on eventually-consistent infrastructure. The test runs multiple trials and
     * passes if the actual failure rate is within acceptable bounds.
     *
     * ## Use Cases
     * - **External Data Sources**: RSS feeds, CVE APIs may be temporarily unavailable
     * - **Network Operations**: Docker DNS, service discovery may have transient failures
     * - **Race Conditions**: Multi-service workflows may occasionally timeout
     * - **Resource Limits**: System under load may throttle some requests
     *
     * ## Example
     * ```kotlin
     * probabilisticTest(
     *     name = "RSS feed fetch reliability",
     *     trials = 20,
     *     acceptableFailureRate = 0.2  // Accept up to 20% failures
     * ) {
     *     val result = client.triggerFetch("rss")
     *     result.success  // Return true/false for each trial
     * }
     * ```
     *
     * @param name Test description
     * @param trials Number of times to run the operation
     * @param acceptableFailureRate Maximum acceptable failure rate (0.0-1.0)
     * @param block Test operation that returns Boolean (true = success, false = failure)
     * @return ProbabilisticTestResultSuccess with success/failure counts and pass/fail status
     */
    suspend fun probabilisticTest(
        name: String,
        trials: Int = 10,
        acceptableFailureRate: Double = 0.2,
        block: suspend ProbabilisticTestContext.() -> Boolean
    ): ProbabilisticTestResultSuccess {
        require(trials > 0) { "Trials must be positive" }
        require(acceptableFailureRate in 0.0..1.0) { "Acceptable failure rate must be between 0 and 1" }

        print("  [PROB] $name (n=$trials, max_fail=${(acceptableFailureRate * 100).toInt()}%) ... ")

        val context = ProbabilisticTestContext(client, environment)
        val outcomes = mutableListOf<TestOutcome>()
        var totalDuration = 0L

        repeat(trials) { trial ->
            var duration = 0L
            val success = try {
                var result = false
                duration = measureTimeMillis {
                    result = context.block()
                }
                result
            } catch (e: Exception) {
                false
            }
            outcomes.add(TestOutcome(trial + 1, success, duration))
            totalDuration += duration
        }

        val successCount = outcomes.count { it.success }
        val failureCount = trials - successCount
        val actualFailureRate = failureCount.toDouble() / trials
        val passed = actualFailureRate <= acceptableFailureRate

        val result = ProbabilisticTestResultSuccess(
            name = name,
            trials = trials,
            successCount = successCount,
            failureCount = failureCount,
            acceptableFailureRate = acceptableFailureRate,
            actualFailureRate = actualFailureRate,
            passed = passed,
            totalDurationMs = totalDuration,
            outcomes = outcomes
        )

        results.add(result)

        if (passed) {
            println("✓ OK ($successCount/$trials passed, ${(actualFailureRate * 100).toInt()}% fail rate, ${totalDuration}ms)")
        } else {
            println("✗ FAIL ($successCount/$trials passed, ${(actualFailureRate * 100).toInt()}% fail rate exceeds threshold)")
        }

        return result
    }

    /**
     * Measures latency distribution for operations to detect performance degradation.
     *
     * Latency tests validate that operations complete within acceptable time bounds under
     * realistic conditions. By measuring median and p95 (95th percentile) latencies, tests
     * can detect performance regressions while tolerating occasional slow requests.
     *
     * ## Why Latency Distribution Matters
     * - **Median**: Represents typical performance, resilient to outliers
     * - **P95**: Captures worst-case (but not extremely rare) latencies
     * - **Mean**: Overall average, useful for capacity planning
     * - **StdDev**: Measures consistency/variability of performance
     *
     * ## Use Cases
     * - **Search Performance**: "Hybrid search p95 < 5 seconds"
     * - **Agent Tools**: "Tool execution median < 2 seconds"
     * - **Pipeline Operations**: "Document embedding p95 < 10 seconds"
     * - **Database Queries**: "PostgreSQL full-text search p95 < 1 second"
     *
     * ## Example
     * ```kotlin
     * latencyTest(
     *     name = "Search service hybrid search latency",
     *     trials = 50,
     *     maxMedianLatency = 2000,  // 2 seconds
     *     maxP95Latency = 5000      // 5 seconds
     * ) {
     *     val start = System.currentTimeMillis()
     *     client.search("kubernetes documentation")
     *     System.currentTimeMillis() - start  // Return latency in ms
     * }
     * ```
     *
     * @param name Test description
     * @param trials Number of measurements to collect
     * @param maxMedianLatency Maximum acceptable median latency in milliseconds
     * @param maxP95Latency Maximum acceptable p95 latency in milliseconds
     * @param block Operation to measure (returns latency in milliseconds)
     * @return LatencyTestResult with min, max, mean, median, p95, stddev
     */
    suspend fun latencyTest(
        name: String,
        trials: Int = 50,
        maxMedianLatency: Long = 5000,
        maxP95Latency: Long = 10000,
        block: suspend ProbabilisticTestContext.() -> Long
    ): LatencyTestResult {
        require(trials > 0) { "Trials must be positive" }

        print("  [LATENCY] $name (n=$trials, median≤${maxMedianLatency}ms, p95≤${maxP95Latency}ms) ... ")

        val context = ProbabilisticTestContext(client, environment)
        val latencies = mutableListOf<Long>()

        repeat(trials) {
            try {
                val latency = context.block()
                latencies.add(latency)
            } catch (e: Exception) {
                
                latencies.add(Long.MAX_VALUE)
            }
        }

        val sortedLatencies = latencies.sorted()
        val median = sortedLatencies[trials / 2]
        val p95 = sortedLatencies[(trials * 0.95).toInt()]
        val mean = latencies.average().toLong()
        val min = latencies.minOrNull() ?: 0L
        val max = latencies.maxOrNull() ?: 0L
        val stdDev = sqrt(latencies.map { (it - mean).toDouble() * (it - mean) }.average()).toLong()

        val passed = median <= maxMedianLatency && p95 <= maxP95Latency

        val result = LatencyTestResult(
            name = name,
            trials = trials,
            minMs = min,
            maxMs = max,
            meanMs = mean,
            medianMs = median,
            p95Ms = p95,
            stdDevMs = stdDev,
            maxMedianLatency = maxMedianLatency,
            maxP95Latency = maxP95Latency,
            passed = passed
        )

        results.add(result)

        if (passed) {
            println("✓ OK (median=${median}ms, p95=${p95}ms, mean=${mean}ms±${stdDev}ms)")
        } else {
            println("✗ FAIL (median=${median}ms, p95=${p95}ms exceeds thresholds)")
        }

        return result
    }

    /**
     * Measures throughput (operations per second) over a time window.
     *
     * Throughput tests validate that services can handle sustained load and concurrent
     * requests. This is critical for services like agent-tool-server that may receive
     * bursts of concurrent LLM requests or pipeline components processing high volumes.
     *
     * ## Use Cases
     * - **Agent Tools**: "Handle 10+ tool calls per second"
     * - **Search Service**: "Process 5+ search queries per second"
     * - **Pipeline**: "Embed 2+ documents per second"
     * - **API Endpoints**: "Handle 20+ health check requests per second"
     *
     * ## Example
     * ```kotlin
     * throughputTest(
     *     name = "Agent-tool-server call-tool throughput",
     *     durationSeconds = 30,
     *     minOpsPerSecond = 10.0
     * ) {
     *     client.callTool("semantic_search", mapOf("query" to "test"))
     *     // Method returns void, just executes as many times as possible
     * }
     * ```
     *
     * @param name Test description
     * @param durationSeconds How long to run the test
     * @param minOpsPerSecond Minimum acceptable operations per second
     * @param block Operation to execute repeatedly (no return value)
     * @return ThroughputTestResult with ops/second, total operations, error rate
     */
    suspend fun throughputTest(
        name: String,
        durationSeconds: Int = 30,
        minOpsPerSecond: Double = 1.0,
        block: suspend ProbabilisticTestContext.() -> Unit
    ): ThroughputTestResult {
        require(durationSeconds > 0) { "Duration must be positive" }

        print("  [THROUGHPUT] $name (${durationSeconds}s, min=${minOpsPerSecond}ops/s) ... ")

        val context = ProbabilisticTestContext(client, environment)
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (durationSeconds * 1000)
        var operations = 0
        var errors = 0

        while (System.currentTimeMillis() < endTime) {
            try {
                context.block()
                operations++
            } catch (e: Exception) {
                errors++
            }
        }

        val actualDuration = (System.currentTimeMillis() - startTime) / 1000.0
        val opsPerSecond = operations / actualDuration
        val errorRate = errors.toDouble() / (operations + errors)
        val passed = opsPerSecond >= minOpsPerSecond

        val result = ThroughputTestResult(
            name = name,
            durationSeconds = actualDuration,
            totalOperations = operations,
            errors = errors,
            opsPerSecond = opsPerSecond,
            errorRate = errorRate,
            minOpsPerSecond = minOpsPerSecond,
            passed = passed
        )

        results.add(result)

        if (passed) {
            println("✓ OK (${String.format("%.2f", opsPerSecond)}ops/s, $operations ops, ${(errorRate * 100).toInt()}% errors)")
        } else {
            println("✗ FAIL (${String.format("%.2f", opsPerSecond)}ops/s below minimum)")
        }

        return result
    }

    fun summary(): ProbabilisticTestSummary {
        val passed = results.count { it.passed }
        val failed = results.size - passed

        return ProbabilisticTestSummary(
            total = results.size,
            passed = passed,
            failed = failed,
            results = results
        )
    }
}

class ProbabilisticTestContext(
    val client: ServiceClient,
    val environment: TestEnvironment
) {
    val endpoints = environment.endpoints

    infix fun Boolean.shouldBe(expected: Boolean) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    infix fun String.shouldContain(substring: String) {
        if (!this.contains(substring)) {
            throw AssertionError("Expected string to contain '$substring'")
        }
    }
}

data class TestOutcome(
    val trial: Int,
    val success: Boolean,
    val durationMs: Long
)

sealed class ProbabilisticTestResultBase {
    abstract val name: String
    abstract val passed: Boolean
}

data class ProbabilisticTestResultSuccess(
    override val name: String,
    val trials: Int,
    val successCount: Int,
    val failureCount: Int,
    val acceptableFailureRate: Double,
    val actualFailureRate: Double,
    override val passed: Boolean,
    val totalDurationMs: Long,
    val outcomes: List<TestOutcome>
) : ProbabilisticTestResultBase()

data class LatencyTestResult(
    override val name: String,
    val trials: Int,
    val minMs: Long,
    val maxMs: Long,
    val meanMs: Long,
    val medianMs: Long,
    val p95Ms: Long,
    val stdDevMs: Long,
    val maxMedianLatency: Long,
    val maxP95Latency: Long,
    override val passed: Boolean
) : ProbabilisticTestResultBase()

data class ThroughputTestResult(
    override val name: String,
    val durationSeconds: Double,
    val totalOperations: Int,
    val errors: Int,
    val opsPerSecond: Double,
    val errorRate: Double,
    val minOpsPerSecond: Double,
    override val passed: Boolean
) : ProbabilisticTestResultBase()

data class ProbabilisticTestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val results: List<ProbabilisticTestResultBase>
)
