package org.datamancy.testrunner

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.datamancy.testrunner.framework.*
import org.datamancy.testrunner.suites.*
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val env = parseEnvironment(args)
    val suite = parseSuite(args)
    val verbose = "--verbose" in args || "-v" in args

    if ("--help" in args || "-h" in args) {
        printUsage()
        exitProcess(0)
    }

    println("""
        ╔═══════════════════════════════════════════════════════════════════════════╗
        ║  Datamancy Integration Test Runner (Kotlin ${KotlinVersion.CURRENT})                 ║
        ╚═══════════════════════════════════════════════════════════════════════════╝

        Environment: ${env.name}
        Suite: $suite
        Verbose: $verbose

    """.trimIndent())

    val httpClient = createHttpClient(verbose)
    val serviceClient = ServiceClient(env.endpoints, httpClient)
    val runner = TestRunner(env, serviceClient)

    try {
        runTestSuite(runner, suite)

        val summary = runner.summary()
        printSummary(summary)

        if (summary.failed > 0) {
            exitProcess(1)
        }
    } catch (e: Exception) {
        println("\n❌ Fatal error during test execution:")
        println("   ${e.message}")
        if (verbose) {
            e.printStackTrace()
        }
        exitProcess(2)
    } finally {
        httpClient.close()
    }
}

private fun createHttpClient(verbose: Boolean) = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = verbose
        })
    }

    if (verbose) {
        install(Logging) {
            level = LogLevel.INFO
            logger = Logger.DEFAULT
        }
    }

    engine {
        requestTimeout = 180_000  // 3 minutes for Docker operations (image pulls, SSH setup, container creation)
        endpoint {
            connectTimeout = 15_000
            connectAttempts = 3
        }
    }
}

private suspend fun runTestSuite(runner: TestRunner, suite: String) {
    when (suite) {
        "foundation" -> runner.foundationTests()
        "docker" -> runner.dockerTests()
        "llm" -> runner.llmTests()
        "knowledge-base" -> runner.knowledgeBaseTests()
        "data-pipeline" -> runner.dataPipelineTests()
        "microservices" -> runner.microserviceTests()
        "search-service" -> runner.searchServiceTests()
        "e2e" -> runner.e2eTests()
        "all" -> {
            runner.foundationTests()
            runner.dockerTests()
            runner.llmTests()
            runner.knowledgeBaseTests()
            runner.dataPipelineTests()
            runner.microserviceTests()
            runner.searchServiceTests()
            runner.e2eTests()
        }
        else -> {
            println("❌ Unknown suite: $suite")
            println("Available suites: foundation, docker, llm, knowledge-base, data-pipeline, microservices, search-service, e2e, all")
            exitProcess(1)
        }
    }
}

private fun parseEnvironment(args: Array<String>): TestEnvironment {
    return when {
        "--env" in args -> {
            val envName = args[args.indexOf("--env") + 1]
            when (envName) {
                "container", "internal" -> TestEnvironment.Container
                "localhost", "local" -> TestEnvironment.Localhost
                else -> {
                    println("❌ Unknown environment: $envName")
                    println("Available environments: container, internal, localhost, local")
                    exitProcess(1)
                }
            }
        }
        else -> TestEnvironment.detect()
    }
}

private fun parseSuite(args: Array<String>): String {
    return args.find { it.startsWith("--suite=") }?.substringAfter("=")
        ?: args.getOrNull(args.indexOfFirst { it == "--suite" } + 1)
        ?: "all"
}

private fun printSummary(summary: TestSummary) {
    println("\n" + "=".repeat(80))
    println("TEST SUMMARY")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed:  ${summary.passed}")
    println("  ✗ Failed:  ${summary.failed}")
    println("  ⊘ Skipped: ${summary.skipped}")
    println("  Duration:  ${summary.duration}ms (${summary.duration / 1000.0}s)")

    if (summary.failures.isNotEmpty()) {
        println("\n❌ Failed Tests:")
        summary.failures.forEach {
            println("  • ${it.name}")
            println("    ${it.error}")
        }
    }

    println("=".repeat(80))

    if (summary.failed > 0) {
        println("\n❌ Some tests failed!")
    } else {
        println("\n✅ All tests passed!")
    }
}

private fun printUsage() {
    println("""
    Datamancy Integration Test Runner

    Usage: test-runner [OPTIONS]

    Options:
      --env <environment>    Test environment (default: auto-detect)
                             Values: container, internal, localhost, local

      --suite <suite>        Test suite to run (default: all)
                             Values: foundation, docker, llm, knowledge-base,
                                     data-pipeline, microservices, search-service,
                                     e2e, all

      --verbose, -v          Enable verbose logging
      --help, -h             Show this help message

    Examples:
      test-runner                              # Auto-detect env, run all tests
      test-runner --env container --suite foundation
      test-runner --suite docker --verbose
      test-runner --env localhost --suite all

    Environment Detection:
      - Checks for /.dockerenv file (inside container)
      - Checks TEST_ENV environment variable
      - Falls back to localhost if neither found
    """.trimIndent())
}
