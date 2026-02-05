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
import java.security.cert.X509Certificate
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
    val runner = TestRunner(env, serviceClient, httpClient)

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
        }
        else -> {
            println("❌ Unknown suite: $suite")
            println("Available suites: foundation, llm, knowledge-base, data-pipeline, microservices,")
            println("                  search-service, infrastructure, databases, user-interface, communication,")
            println("                  collaboration, productivity, file-management, security, monitoring, backup,")
            println("                  authentication, enhanced-auth, authenticated-ops, utility, homeassistant,")
            println("                  stack-deployment, bookstack, cicd, labware, stack-replication,")
            println("                  agent-capability, agent-security, agent-llm-quality, all")
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
                             Values: foundation, llm, knowledge-base,
                                     data-pipeline, microservices, search-service,
                                     infrastructure, databases, user-interface,
                                     communication, collaboration, productivity,
                                     file-management, security, monitoring, backup,
                                     authentication, enhanced-auth, authenticated-ops, utility, homeassistant,
                                     stack-deployment, bookstack, cicd, labware,
                                     agent-capability, agent-security, agent-llm-quality, all

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
