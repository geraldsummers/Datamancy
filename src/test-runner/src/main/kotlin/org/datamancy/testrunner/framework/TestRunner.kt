package org.datamancy.testrunner.framework

import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

class TestRunner(
    val environment: TestEnvironment,
    val client: ServiceClient,
    val httpClient: io.ktor.client.HttpClient
) {
    val env get() = environment
    val endpoints get() = environment.endpoints

    // Initialize LDAP helper if LDAP URL and admin password are available
    private val ldapHelper: LdapHelper? = environment.endpoints.ldap?.let { ldapUrl ->
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

    private val results = mutableListOf<TestResult>()

    suspend fun suite(name: String, block: suspend TestSuite.() -> Unit) {
        println("\n▶ $name")
        val suite = TestSuite(name, this)
        suite.block()
    }

    suspend fun test(name: String, block: suspend TestContext.() -> Unit): TestResult {
        print("  [TEST] $name ... ")
        var duration = 0L
        val result = try {
            duration = measureTimeMillis {
                val ctx = TestContext(client, auth)
                ctx.block()
            }
            TestResult.Success(name, duration).also {
                println("✓ OK (${duration}ms)")
            }
        } catch (e: AssertionError) {
            TestResult.Failure(name, e.message ?: "Assertion failed", duration).also {
                println("✗ FAIL (${duration}ms)")
                println("      ${e.message}")
            }
        } catch (e: Exception) {
            TestResult.Failure(name, e.message ?: "Unknown error", duration).also {
                println("✗ ERROR (${duration}ms)")
                println("      ${e.message}")
                if (e.stackTrace.isNotEmpty()) {
                    println("      at ${e.stackTrace[0]}")
                }
            }
        }
        results.add(result)
        return result
    }

    fun skip(name: String, reason: String) {
        results.add(TestResult.Skipped(name, reason))
        println("  [SKIP] $name - $reason")
    }

    fun summary(): TestSummary {
        val passed = results.filterIsInstance<TestResult.Success>()
        val failed = results.filterIsInstance<TestResult.Failure>()
        val skipped = results.filterIsInstance<TestResult.Skipped>()

        return TestSummary(
            total = results.size,
            passed = passed.size,
            failed = failed.size,
            skipped = skipped.size,
            duration = passed.sumOf { it.durationMs } + failed.sumOf { it.durationMs },
            failures = failed
        )
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

class TestContext(val client: ServiceClient, val auth: AuthHelper) {
    // Fluent assertions
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

    // Additional assertion helpers
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
