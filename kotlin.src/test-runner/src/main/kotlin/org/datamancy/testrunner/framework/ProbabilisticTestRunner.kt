package org.datamancy.testrunner.framework

import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Non-deterministic test runner for probabilistic testing of agent capabilities
 *
 * Unlike traditional deterministic tests, probabilistic tests acknowledge that:
 * - LLM outputs are inherently non-deterministic
 * - Agent tool selection may vary across runs
 * - Some tasks have acceptable failure rates
 * - Performance metrics follow statistical distributions
 */
class ProbabilisticTestRunner(
    val environment: TestEnvironment,
    val client: ServiceClient,
    val httpClient: io.ktor.client.HttpClient
) {
    private val results = mutableListOf<ProbabilisticTestResultBase>()

    /**
     * Run a probabilistic test that may pass or fail with some probability
     * @param name Test name
     * @param trials Number of times to run the test
     * @param acceptableFailureRate Maximum failure rate (0.0-1.0) to consider test passing
     * @param block Test logic that returns true on success, false on failure
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
                duration = measureTimeMillis {
                    context.block()
                }
                true
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
     * Run a latency test that measures response time distribution
     * @param name Test name
     * @param trials Number of trials
     * @param maxMedianLatency Maximum acceptable median latency in ms
     * @param maxP95Latency Maximum acceptable 95th percentile latency in ms
     * @param block Test logic that returns response time in ms
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
                // Record failed attempts as max timeout
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

        if (passed) {
            println("✓ OK (median=${median}ms, p95=${p95}ms, mean=${mean}ms±${stdDev}ms)")
        } else {
            println("✗ FAIL (median=${median}ms, p95=${p95}ms exceeds thresholds)")
        }

        return result
    }

    /**
     * Run a throughput test that measures operations per second
     * @param name Test name
     * @param durationSeconds How long to run the test
     * @param minOpsPerSecond Minimum acceptable operations per second
     * @param block Test logic that performs one operation
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
