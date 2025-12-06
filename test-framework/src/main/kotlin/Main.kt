import core.BrowserPool
import core.TestOrchestrator
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
    val concurrency = args.getOrNull(2)?.toIntOrNull() ?: 5

    println("Starting UI test suite")
    println("Config: $configPath")
    println("Output: $outputDir")
    println("Concurrency: $concurrency")
    println()

    val outputPath = Paths.get(outputDir)
    outputPath.toFile().mkdirs()

    // Load service definitions
    val services = File(configPath).inputStream().use { input ->
        ServiceRegistry.fromYaml(input).all()
    }

    println("Loaded ${services.size} services to test:")
    services.forEach { println("  - ${it.name} (${it.url})") }
    println()

    // Run tests
    val report = runBlocking {
        val pool = BrowserPool(concurrency)
        val orchestrator = TestOrchestrator(pool, outputPath, concurrency)
        orchestrator.runTests(services)
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

    // Exit with failure code if any tests failed
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
