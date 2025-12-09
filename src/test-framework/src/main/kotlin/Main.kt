import core.BrowserPool
import core.TestOrchestrator
import core.SSOTestOrchestrator
import core.TestResult
import kotlinx.coroutines.runBlocking
import reporting.HtmlReporter
import reporting.JsonReporter
import services.ServiceRegistry
import java.io.File
import java.nio.file.Paths

fun main(args: Array<String>) {
    val configPath = args.getOrNull(0) ?: "services.yaml"
    val outputDir = args.getOrNull(1) ?: "screenshots"
    val mode = args.getOrNull(2) ?: "sso"  // "sso" or "parallel"
    val serviceName = args.getOrNull(3)  // Optional: test only this service

    println("Starting UI test suite")
    println("Config: $configPath")
    println("Output: $outputDir")
    println("Mode: $mode (use 'parallel' for old behavior, 'sso' for sequential SSO testing)")
    if (serviceName != null) {
        println("Single service mode: $serviceName")
    }
    println()

    val outputPath = Paths.get(outputDir)
    outputPath.toFile().mkdirs()

    // Clean up old screenshots and artifacts
    println("Cleaning up old test artifacts...")
    var deletedCount = 0
    outputPath.toFile().listFiles()?.forEach { file ->
        if (file.isFile && (file.extension == "png" || file.extension == "html" || file.extension == "json")) {
            file.delete()
            deletedCount++
        }
    }
    println("  Deleted $deletedCount old artifacts")
    println()

    // Load service definitions
    val allServices = File(configPath).inputStream().use { input ->
        ServiceRegistry.fromYaml(input).all()
    }

    // Filter to single service if specified
    val services = if (serviceName != null) {
        allServices.filter { it.name.equals(serviceName, ignoreCase = true) }.also {
            if (it.isEmpty()) {
                println("ERROR: Service '$serviceName' not found in config")
                println("Available services:")
                allServices.forEach { svc -> println("  - ${svc.name}") }
                kotlin.system.exitProcess(1)
            }
        }
    } else {
        allServices
    }

    println("Loaded ${services.size} services to test:")
    services.forEach { println("  - ${it.name} (${it.url})") }
    println()

    // Run tests
    val report = runBlocking {
        val pool = BrowserPool(5)  // Pool size doesn't matter for SSO mode

        if (mode == "sso") {
            val orchestrator = SSOTestOrchestrator(pool, outputPath)
            orchestrator.runSSOTests(services)
        } else {
            // Old parallel mode
            val concurrency = 5
            val orchestrator = TestOrchestrator(pool, outputPath, concurrency)
            orchestrator.runTests(services)
        }
    }

    // Generate reports
    println("\n=== Test Results ===")
    val passed = report.results.count { it is TestResult.Success }
    val failed = report.results.size - passed

    report.results.forEach { result ->
        when (result) {
            is TestResult.Success -> {
                println("✓ PASS ${result.service}: ${result.duration.toMillis()}ms")
            }
            is TestResult.Failure -> {
                println("✗ FAIL ${result.service}: ${result.error.message}")
            }
            is TestResult.Timeout -> {
                println("✗ TIMEOUT ${result.service}: ${result.elapsed.toMillis()}ms")
            }
        }
    }

    println("\nSummary: $passed passed, $failed failed")

    // Write JSON report
    val jsonPath = outputPath.resolve("test-report.json")
    JsonReporter().generate(report.results, jsonPath)
    println("JSON report: $jsonPath")

    // Write HTML report
    val htmlPath = outputPath.resolve("test-report.html")
    HtmlReporter().generate(report.results, htmlPath)
    println("HTML report: $htmlPath")

    // Exit explicitly to terminate Playwright threads
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    } else {
        kotlin.system.exitProcess(0)
    }
}
