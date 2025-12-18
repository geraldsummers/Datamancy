#!/usr/bin/env kotlin

/**
 * Datamancy Integration Test Runner
 *
 * Runs integration tests inside Docker network with access to all services.
 *
 * Usage:
 *   ./run-integration-tests.main.kts                    - Run all integration tests
 *   ./run-integration-tests.main.kts :control-panel:test --tests "*RealIntegrationTest"
 *   ./run-integration-tests.main.kts :data-fetcher:test --tests "*.StorageRealIntegrationTest"
 */

@file:Suppress("unused")

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

// ============================================================================
// Utilities
// ============================================================================

// ANSI color codes
private val ANSI_RESET = "\u001B[0m"
private val ANSI_RED = "\u001B[31m"
private val ANSI_GREEN = "\u001B[32m"
private val ANSI_BLUE = "\u001B[34m"
private val ANSI_CYAN = "\u001B[36m"

private fun info(msg: String) = println("${ANSI_CYAN}[INFO]${ANSI_RESET} $msg")
private fun success(msg: String) = println("${ANSI_GREEN}✓${ANSI_RESET} $msg")
private fun error(msg: String) = System.err.println("${ANSI_RED}✗${ANSI_RESET} $msg")
private fun header(msg: String) {
    println("${ANSI_BLUE}═══════════════════════════════════════════════════════${ANSI_RESET}")
    println("${ANSI_BLUE}  $msg${ANSI_RESET}")
    println("${ANSI_BLUE}═══════════════════════════════════════════════════════${ANSI_RESET}")
    println()
}

private fun run(
    vararg cmd: String,
    cwd: Path? = null,
    allowFail: Boolean = false,
    showOutput: Boolean = true
): Pair<Int, String> {
    val pb = ProcessBuilder(*cmd)
    if (cwd != null) pb.directory(cwd.toFile())
    pb.redirectErrorStream(true)
    val p = pb.start()
    p.outputStream.close()

    val outputBuilder = StringBuilder()
    p.inputStream.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            if (showOutput) println(line)
            outputBuilder.appendLine(line)
        }
    }

    val out = outputBuilder.toString()
    val code = p.waitFor()
    if (code != 0 && !allowFail) {
        if (!showOutput) System.err.println(out)
    }
    return Pair(code, out)
}

private fun projectRoot(): Path {
    val prop = System.getProperty("kotlin.script.file.path")
    return if (prop != null) {
        val scriptPath = Paths.get(prop).toAbsolutePath().normalize()
        scriptPath.parent ?: Paths.get("").toAbsolutePath().normalize()
    } else {
        Paths.get("").toAbsolutePath().normalize()
    }
}

// ============================================================================
// Integration Test Runner
// ============================================================================

fun checkServicesRunning(): Boolean {
    val (code, output) = run("docker", "compose", "ps", showOutput = false, allowFail = true)
    return code == 0 && output.contains("Up")
}

fun buildTestRunner() {
    info("Building test runner image...")
    run("docker", "compose", "--profile", "testing", "build", "integration-test-runner")
}

fun runTests(args: List<String>): Int {
    info("Running integration tests inside Docker network...")
    println()

    val cmd = mutableListOf(
        "docker", "compose", "--profile", "testing",
        "run", "--rm", "integration-test-runner"
    )

    if (args.isNotEmpty()) {
        // Pass arguments through to gradle
        cmd.add("./gradlew")
        cmd.addAll(args)
        cmd.add("--no-daemon")
        cmd.add("--stacktrace")
    }
    // else: use default CMD from Dockerfile

    val (exitCode, _) = run(*cmd.toTypedArray(), allowFail = true)
    return exitCode
}

fun showTestReports(projectRoot: Path) {
    println()
    println("Test reports available at:")

    val modules = listOf("control-panel", "data-fetcher", "unified-indexer", "search-service")
    modules.forEach { module ->
        val reportPath = projectRoot.resolve("src/$module/build/reports/tests/test/index.html")
        if (reportPath.toFile().exists()) {
            println("  - ${module.split("-").joinToString(" ") { it.capitalize() }}: file://$reportPath")
        }
    }
}

// ============================================================================
// Main
// ============================================================================

header("Datamancy Integration Test Runner")

// Check if services are running
if (!checkServicesRunning()) {
    error("Docker services are not running")
    println("Please start services first with: docker compose up -d")
    exitProcess(1)
}

val root = projectRoot()

// Build test runner image
buildTestRunner()

// Run integration tests
val testArgs = args.toList()
val exitCode = runTests(testArgs)

// Show results
println()
if (exitCode == 0) {
    success("All integration tests passed!")
} else {
    error("Some integration tests failed (exit code: $exitCode)")
    showTestReports(root)
}

exitProcess(exitCode)
