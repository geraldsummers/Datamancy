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
import java.io.File
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    val env = parseEnvironment(args)
    val suite = parseSuite(args)
    val verbose = "--verbose" in args || "-v" in args

    if ("--help" in args || "-h" in args) {
        printUsage()
        exitProcess(0)
    }

    // Create results directory with timestamp
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    val resultsBaseDir = File("/app/test-results")
    if (!resultsBaseDir.exists()) {
        resultsBaseDir.mkdirs()
    }
    val resultsDir = File(resultsBaseDir, "$timestamp-$suite")
    resultsDir.mkdirs()

    println("""
        ╔═══════════════════════════════════════════════════════════════════════════╗
        ║  Datamancy Integration Test Runner (Kotlin ${KotlinVersion.CURRENT})                 ║
        ╚═══════════════════════════════════════════════════════════════════════════╝

        Environment: ${env.name}
        Suite: $suite
        Verbose: $verbose
        Results: ${resultsDir.absolutePath}

    """.trimIndent())

    val httpClient = createHttpClient(verbose)
    val serviceClient = ServiceClient(env.endpoints, httpClient)
    val runner = TestRunner(env, serviceClient, httpClient, resultsDir)

    val startTime = System.currentTimeMillis()
    try {
        runTestSuite(runner, suite)

        val summary = runner.summary()
        val duration = System.currentTimeMillis() - startTime

        // Print minimal telemetry to stdout
        printTelemetry(summary, duration, resultsDir)

        // Save detailed results to files
        saveResults(summary, resultsDir, env, suite, duration)

        if (summary.failed > 0) {
            exitProcess(1)
        }
    } catch (e: Exception) {
        println("\n❌ Fatal error during test execution:")
        println("   ${e.message}")
        if (verbose) {
            e.printStackTrace()
        }

        // Ensure results directory exists before writing error log
        if (!resultsDir.exists()) {
            resultsDir.mkdirs()
        }

        // Save error log with fallback
        try {
            File(resultsDir, "error.log").writeText("""
                Fatal Error: ${e.message}

                Stack Trace:
                ${e.stackTraceToString()}
            """.trimIndent())
        } catch (writeError: Exception) {
            System.err.println("Failed to write error log to ${resultsDir.absolutePath}/error.log: ${writeError.message}")
            System.err.println("Original error: ${e.message}")
            System.err.println(e.stackTraceToString())
        }

        exitProcess(2)
    } finally {
        httpClient.close()
    }
}

private fun createHttpClient(verbose: Boolean): HttpClient {
    
    val disableTlsValidation = System.getenv("DISABLE_TLS_VALIDATION")?.toBoolean() ?: false

    if (disableTlsValidation) {
        println("⚠️  WARNING: TLS certificate validation is DISABLED")
        println("   This should only be used in isolated test environments with self-signed certificates")
        println("   Set DISABLE_TLS_VALIDATION=false or unset to enable validation")
    }

    return HttpClient(CIO) {
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

        
        followRedirects = false

        engine {
            requestTimeout = 180_000  
            endpoint {
                connectTimeout = 15_000
                connectAttempts = 3
            }
            https {
                if (disableTlsValidation) {
                    
                    trustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                } else {
                    
                    
                }
            }
        }
    }
}

private suspend fun runTestSuite(runner: TestRunner, suite: String) {
    when (suite) {
        "foundation" -> runner.foundationTests()
        "llm" -> runner.llmTests()
        "knowledge-base" -> runner.knowledgeBaseTests()
        "data-pipeline" -> runner.dataPipelineTests()
        "microservices" -> runner.microserviceTests()
        "search-service" -> runner.searchServiceTests()
        
        "infrastructure" -> runner.infrastructureTests()
        "databases" -> runner.databaseTests()
        "user-interface" -> runner.userInterfaceTests()
        
        "communication" -> runner.communicationTests()
        "collaboration" -> runner.collaborationTests()
        "productivity" -> runner.productivityTests()
        "file-management" -> runner.fileManagementTests()
        "security" -> runner.securityTests()
        "monitoring" -> runner.monitoringTests()
        "backup" -> runner.backupTests()
        
        "authentication" -> runner.authenticationTests()
        
        "enhanced-auth" -> runner.enhancedAuthenticationTests()
        
        "authenticated-ops" -> runner.authenticatedOperationsTests()
        
        "utility" -> runner.utilityServicesTests()
        
        "homeassistant" -> runner.homeAssistantTests()
        
        "stack-deployment" -> runner.stackDeploymentTests()
        
        "bookstack" -> runner.bookStackIntegrationTests()
        
        "cicd" -> runner.cicdTests()
        "labware" -> runner.labwareTests()
        "stack-replication" -> runner.stackReplicationTests()
        
        "agent-capability" -> runner.agentCapabilityTests()
        "agent-security" -> runner.agentSecurityTests()
        "agent-llm-quality" -> runner.agentLlmQualityTests()
        "trading" -> runner.tradingTests()
        "all" -> {
            runner.foundationTests()
            runner.llmTests()
            runner.knowledgeBaseTests()
            runner.dataPipelineTests()
            runner.microserviceTests()
            runner.searchServiceTests()
            runner.infrastructureTests()
            runner.databaseTests()
            runner.userInterfaceTests()
            runner.communicationTests()
            runner.collaborationTests()
            runner.productivityTests()
            runner.fileManagementTests()
            runner.securityTests()
            runner.monitoringTests()
            runner.backupTests()
            runner.authenticationTests()
            runner.enhancedAuthenticationTests()
            runner.authenticatedOperationsTests()
            runner.utilityServicesTests()
            runner.homeAssistantTests()
            runner.cicdTests()
            runner.labwareTests()
            runner.stackReplicationTests()
            runner.tradingTests()
        }
        else -> {
            println("❌ Unknown suite: $suite")
            println("Available suites: foundation, llm, knowledge-base, data-pipeline, microservices,")
            println("                  search-service, infrastructure, databases, user-interface, communication,")
            println("                  collaboration, productivity, file-management, security, monitoring, backup,")
            println("                  authentication, enhanced-auth, authenticated-ops, utility, homeassistant,")
            println("                  stack-deployment, bookstack, cicd, labware, stack-replication,")
            println("                  agent-capability, agent-security, agent-llm-quality, trading, all")
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

private fun printTelemetry(summary: TestSummary, duration: Long, resultsDir: File) {
    println("\n" + "=".repeat(80))
    println("TEST RESULTS")
    println("=".repeat(80))
    println("Total Tests: ${summary.total}")
    println("  ✓ Passed:  ${summary.passed}")
    println("  ✗ Failed:  ${summary.failed}")
    println("  ⊘ Skipped: ${summary.skipped}")
    println("  Duration:  ${duration}ms (${duration / 1000.0}s)")
    println()
    println("Results saved to: ${resultsDir.absolutePath}")
    println("  - summary.txt     : Test summary")
    println("  - detailed.log    : Full test output")
    println("  - failures.log    : Failed test details (if any)")
    println("  - metadata.txt    : Run metadata")
    println("=".repeat(80))

    if (summary.failed > 0) {
        println("\n❌ Some tests failed! See failures.log for details.")
    } else {
        println("\n✅ All tests passed!")
    }
}

private fun saveResults(
    summary: TestSummary,
    resultsDir: File,
    env: TestEnvironment,
    suite: String,
    duration: Long
) {
    // Save summary
    File(resultsDir, "summary.txt").writeText("""
        ╔═══════════════════════════════════════════════════════════════════════════╗
        ║  TEST SUMMARY                                                             ║
        ╚═══════════════════════════════════════════════════════════════════════════╝

        Total Tests: ${summary.total}
          ✓ Passed:  ${summary.passed}
          ✗ Failed:  ${summary.failed}
          ⊘ Skipped: ${summary.skipped}
          Duration:  ${duration}ms (${duration / 1000.0}s)

        Status: ${if (summary.failed > 0) "FAILED ❌" else "PASSED ✅"}
    """.trimIndent())

    // Save detailed log
    val detailedLog = StringBuilder()
    detailedLog.appendLine("Datamancy Integration Test Runner - Detailed Results")
    detailedLog.appendLine("=" .repeat(80))
    detailedLog.appendLine()
    detailedLog.appendLine("Test Statistics:")
    detailedLog.appendLine("  Total:   ${summary.total}")
    detailedLog.appendLine("  Passed:  ${summary.passed}")
    detailedLog.appendLine("  Failed:  ${summary.failed}")
    detailedLog.appendLine("  Skipped: ${summary.skipped}")
    detailedLog.appendLine("  Duration: ${duration}ms (${duration / 1000.0}s)")
    detailedLog.appendLine()

    File(resultsDir, "detailed.log").writeText(detailedLog.toString())

    // Save failures if any
    if (summary.failures.isNotEmpty()) {
        val failuresLog = StringBuilder()
        failuresLog.appendLine("Failed Tests Report")
        failuresLog.appendLine("=" .repeat(80))
        failuresLog.appendLine()
        summary.failures.forEach {
            failuresLog.appendLine("Test: ${it.name}")
            failuresLog.appendLine("Duration: ${it.durationMs}ms")
            failuresLog.appendLine("Error:")
            failuresLog.appendLine("  ${it.error}")
            failuresLog.appendLine()
            failuresLog.appendLine("-".repeat(80))
            failuresLog.appendLine()
        }
        File(resultsDir, "failures.log").writeText(failuresLog.toString())
    }

    // Save metadata
    File(resultsDir, "metadata.txt").writeText("""
        Test Run Metadata
        ================

        Timestamp: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())}
        Environment: ${env.name}
        Suite: $suite
        Kotlin Version: ${KotlinVersion.CURRENT}

        Endpoints:
          Authelia: ${env.endpoints.authelia}
          LDAP: ${env.endpoints.ldap ?: "N/A"}
          LiteLLM: ${env.endpoints.liteLLM}
          PostgreSQL: ${env.endpoints.postgres}
          Qdrant: ${env.endpoints.qdrant}
          Pipeline: ${env.endpoints.pipeline}
          Search Service: ${env.endpoints.searchService}
          BookStack: ${env.endpoints.bookstack}

        Results Directory: ${resultsDir.absolutePath}
    """.trimIndent())
}

private fun printUsage() {
    println("""
    Datamancy Integration Test Runner

    Usage: test-runner [OPTIONS]

    Options:
      --env <environment>    Test environment (default: auto-detect)
                             Values: container, internal, localhost, local

      --suite <suite>        Test suite to run (default: all)
                             Values: foundation, llm, knowledge-base,
                                     data-pipeline, microservices, search-service,
                                     infrastructure, databases, user-interface,
                                     communication, collaboration, productivity,
                                     file-management, security, monitoring, backup,
                                     authentication, enhanced-auth, authenticated-ops, utility, homeassistant,
                                     stack-deployment, bookstack, cicd, labware,
                                     agent-capability, agent-security, agent-llm-quality, trading, all

      --verbose, -v          Enable verbose logging
      --help, -h             Show this help message

    Examples:
      test-runner                              # Auto-detect env, run all tests
      test-runner --env container --suite foundation
      test-runner --suite llm --verbose
      test-runner --env localhost --suite all

    Environment Detection:
      - Checks for /.dockerenv file (inside container)
      - Checks TEST_ENV environment variable
      - Falls back to localhost if neither found
    """.trimIndent())
}
