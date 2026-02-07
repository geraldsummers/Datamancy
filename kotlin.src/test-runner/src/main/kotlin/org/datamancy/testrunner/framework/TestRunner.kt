package org.datamancy.testrunner.framework

import kotlinx.coroutines.*
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Core test execution engine and DSL for integration testing.
 *
 * TestRunner provides a structured DSL for writing integration tests that validate
 * cross-service interactions in the Datamancy stack. It orchestrates test execution,
 * manages authentication helpers, and collects results for reporting.
 *
 * ## Test Structure
 * Tests are organized hierarchically:
 * - **Suite**: Named group of related tests (e.g., "Authentication Tests")
 * - **Test**: Individual test case with setup, execution, and assertions
 * - **Context**: Test execution environment with helper methods and assertions
 *
 * ## Cross-Service Integration Testing
 * TestRunner enables tests that span multiple services:
 * - **Authentication Flows**: LDAP → Authelia → OIDC → Services
 * - **Data Pipelines**: Pipeline → PostgreSQL → Qdrant → Search-Service → BookStack
 * - **Agent Tools**: Agent-Tool-Server → All Services (LLM-driven workflows)
 * - **SSO Validation**: Single Authelia session accessing multiple applications
 *
 * ## Helper Orchestration
 * TestRunner pre-configures helpers for common operations:
 * - `ldapHelper`: Create/delete ephemeral users in OpenLDAP
 * - `auth`: Authenticate with Authelia, manage sessions
 * - `oidc`: Perform OAuth 2.0 flows, validate tokens
 * - `tokens`: Acquire service-specific API tokens
 * - `client`: Make HTTP requests to services
 *
 * ## Why This DSL Exists
 * - **Readability**: Tests read like specifications, not HTTP boilerplate
 * - **Isolation**: Each test gets fresh context, preventing state leaks
 * - **Error Handling**: Exceptions converted to test failures with clear messages
 * - **Timing**: Automatic duration tracking for performance analysis
 *
 * @property environment Detected test environment (Container or Localhost)
 * @property client ServiceClient for HTTP requests to Datamancy services
 * @property httpClient Raw Ktor HTTP client for lower-level operations
 */
class TestRunner(
    val environment: TestEnvironment,
    val client: ServiceClient,
    val httpClient: io.ktor.client.HttpClient,
    val resultsDir: File? = null
) {
    val env get() = environment
    val endpoints get() = environment.endpoints
    private val testOutputLog = StringBuilder()

    /**
     * LDAP helper for ephemeral user creation and management.
     *
     * Only initialized if LDAP endpoint is configured and admin password is available.
     * Tests use this to create isolated users for authentication flow validation.
     * Null if LDAP is not accessible (e.g., testing only HTTP services).
     */
    val ldapHelper: LdapHelper? = environment.endpoints.ldap?.let { ldapUrl ->
        if (environment.ldapAdminPassword.isNotEmpty()) {
            LdapHelper(
                ldapUrl = ldapUrl,
                adminPassword = environment.ldapAdminPassword
            )
        } else {
            null
        }
    }

    val auth = AuthHelper(environment.endpoints.authelia, httpClient, ldapHelper)
    val oidc = OIDCHelper(environment.endpoints.authelia, httpClient, auth)
    val tokens = TokenManager(httpClient, environment.endpoints)

    private val results = mutableListOf<TestResult>()

    suspend fun suite(name: String, block: suspend TestSuite.() -> Unit) {
        val message = "\n▶ $name"
        println(message)
        log(message)
        val suite = TestSuite(name, this)
        suite.block()
    }

    suspend fun test(name: String, block: suspend TestContext.() -> Unit): TestResult {
        print("  [TEST] $name ... ")
        log("  [TEST] $name ... ")
        var duration = 0L
        val result = try {
            duration = measureTimeMillis {
                val ctx = TestContext(client, auth, oidc, tokens)
                ctx.block()
            }
            TestResult.Success(name, duration).also {
                val message = "✓ OK (${duration}ms)"
                println(message)
                log(message)
            }
        } catch (e: AssertionError) {
            TestResult.Failure(name, e.message ?: "Assertion failed", duration).also {
                val message1 = "✗ FAIL (${duration}ms)"
                val message2 = "      ${e.message}"
                println(message1)
                println(message2)
                log(message1)
                log(message2)
            }
        } catch (e: Exception) {
            TestResult.Failure(name, e.message ?: "Unknown error", duration).also {
                val message1 = "✗ ERROR (${duration}ms)"
                val message2 = "      ${e.message}"
                println(message1)
                println(message2)
                log(message1)
                log(message2)
                if (e.stackTrace.isNotEmpty()) {
                    val message3 = "      at ${e.stackTrace[0]}"
                    println(message3)
                    log(message3)
                }
            }
        }
        results.add(result)
        return result
    }

    fun skip(name: String, reason: String) {
        results.add(TestResult.Skipped(name, reason))
        val message = "  [SKIP] $name - $reason"
        println(message)
        log(message)
    }

    fun summary(): TestSummary {
        val passed = results.filterIsInstance<TestResult.Success>()
        val failed = results.filterIsInstance<TestResult.Failure>()
        val skipped = results.filterIsInstance<TestResult.Skipped>()

        // Save detailed log if results directory exists
        resultsDir?.let {
            File(it, "detailed.log").writeText(testOutputLog.toString())
        }

        return TestSummary(
            total = results.size,
            passed = passed.size,
            failed = failed.size,
            skipped = skipped.size,
            duration = passed.sumOf { it.durationMs } + failed.sumOf { it.durationMs },
            failures = failed
        )
    }

    private fun log(message: String) {
        testOutputLog.appendLine(message)
    }
}

class TestSuite(val name: String, private val runner: TestRunner) {
    suspend fun test(name: String, block: suspend TestContext.() -> Unit) {
        runner.test(name, block)
    }

    fun skip(name: String, reason: String) {
        runner.skip(name, reason)
    }
}

class TestContext(
    val client: ServiceClient,
    val auth: AuthHelper,
    val oidc: OIDCHelper,
    val tokens: TokenManager
) {
    
    infix fun String.shouldContain(substring: String) {
        if (!this.contains(substring)) {
            throw AssertionError("Expected string to contain '$substring', but got: ${this.take(200)}")
        }
    }

    infix fun String.shouldNotContain(substring: String) {
        if (this.contains(substring)) {
            throw AssertionError("Expected string NOT to contain '$substring', but it was found")
        }
    }

    infix fun Boolean.shouldBe(expected: Boolean) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    infix fun Int.shouldBeGreaterThan(threshold: Int) {
        if (this <= threshold) {
            throw AssertionError("Expected $this to be greater than $threshold")
        }
    }

    infix fun Int.shouldBe(expected: Int) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    infix fun <T> T.shouldBe(expected: T) {
        if (this != expected) {
            throw AssertionError("Expected $expected but got $this")
        }
    }

    fun <T> T.shouldNotBeNull(): T {
        if (this == null) {
            throw AssertionError("Expected non-null value but got null")
        }
        return this
    }

    fun require(condition: Boolean, message: String = "Requirement failed") {
        if (!condition) {
            throw AssertionError(message)
        }
    }

    
    infix fun Int.shouldBeOneOf(values: List<Int>) {
        if (this !in values) {
            throw AssertionError("Expected $this to be one of $values")
        }
    }
}

sealed class TestResult {
    data class Success(val name: String, val durationMs: Long) : TestResult()
    data class Failure(val name: String, val error: String, val durationMs: Long) : TestResult()
    data class Skipped(val name: String, val reason: String) : TestResult()
}

data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val duration: Long,
    val failures: List<TestResult.Failure>
)
