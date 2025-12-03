#!/usr/bin/env kotlin

/**
 * Pre-Deployment Test Suite
 *
 * Validates all critical fixes are working before lab deployment.
 * Run this on development machine before pushing to server.
 *
 * Usage:
 *   kotlin scripts/test-pre-deployment.main.kts
 */

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

val GREEN = "\u001B[32m"
val RED = "\u001B[31m"
val YELLOW = "\u001B[33m"
val RESET = "\u001B[0m"

var failCount = 0
var passCount = 0

fun test(name: String, check: () -> Boolean) {
    print("Testing: $name... ")
    try {
        if (check()) {
            println("${GREEN}PASS${RESET}")
            passCount++
        } else {
            println("${RED}FAIL${RESET}")
            failCount++
        }
    } catch (e: Exception) {
        println("${RED}ERROR: ${e.message}${RESET}")
        failCount++
    }
}

fun info(msg: String) = println("${YELLOW}[INFO]${RESET} $msg")

println("=".repeat(60))
println("Pre-Deployment Test Suite")
println("=".repeat(60))
println()

// Test 1: Runtime config dir structure
test("Runtime config dir exists or can be created") {
    val runtimeDir = Paths.get(System.getProperty("user.home"), ".config/datamancy")
    Files.createDirectories(runtimeDir)
    Files.isDirectory(runtimeDir)
}

// Test 2: .gitignore updated
test(".gitignore blocks bootstrap_ldap.ldif") {
    val gitignore = File(".gitignore")
    val content = gitignore.readText()
    content.contains("bootstrap_ldap.ldif") &&
    !content.contains("# bootstrap_ldap.ldif")  // Not commented
}

test(".gitignore blocks .env.runtime") {
    val gitignore = File(".gitignore")
    gitignore.readText().contains(".env.runtime")
}

// Test 3: Stack controller has validation functions
test("stack-controller has validateEnvFile function") {
    val controller = File("stack-controller.main.kts")
    controller.readText().contains("private fun validateEnvFile")
}

test("stack-controller has validateDomain function") {
    val controller = File("stack-controller.main.kts")
    controller.readText().contains("private fun validateDomain")
}

test("stack-controller has runtimeConfigDir function") {
    val controller = File("stack-controller.main.kts")
    controller.readText().contains("private fun runtimeConfigDir")
}

// Test 4: Docker compose has restricted docker-proxy
test("docker-proxy has IMAGES=0") {
    val compose = File("docker-compose.yml")
    val content = compose.readText()
    content.contains("- IMAGES=0")
}

test("docker-proxy has NETWORKS=0") {
    val compose = File("docker-compose.yml")
    val content = compose.readText()
    content.contains("- NETWORKS=0")
}

// Test 5: Scripts support --output flag
test("process-config-templates supports --output") {
    val script = File("scripts/core/process-config-templates.main.kts")
    script.readText().contains("--output=")
}

test("generate-ldap-bootstrap supports --output") {
    val script = File("scripts/security/generate-ldap-bootstrap.main.kts")
    script.readText().contains("--output=")
}

// Test 6: Template files exist
test("Config templates directory exists") {
    File("configs.templates").isDirectory
}

test("LDAP bootstrap template exists") {
    File("configs.templates/infrastructure/ldap/bootstrap_ldap.ldif.template").isFile
}

// Test 7: Stack controller executable
test("stack-controller.main.kts is executable or can be run") {
    val controller = File("stack-controller.main.kts")
    controller.canRead()
}

// Test 8: .env.example doesn't have bad patterns
test(".env.example properly documented") {
    val example = File(".env.example")
    val content = example.readText()
    // Should have CHANGE_ME markers
    content.contains("<CHANGE_ME>")
}

// Test 9: bootstrap_ldap.ldif not in git index
test("bootstrap_ldap.ldif not tracked by git") {
    val result = ProcessBuilder("git", "ls-files", "bootstrap_ldap.ldif")
        .redirectErrorStream(true)
        .start()
    val output = result.inputStream.readBytes().toString(Charsets.UTF_8)
    result.waitFor()
    output.trim().isEmpty()  // Should be empty (file not tracked)
}

// Summary
println()
println("=".repeat(60))
println("Test Results:")
println("  ${GREEN}PASSED:${RESET} $passCount")
println("  ${RED}FAILED:${RESET} $failCount")
println("=".repeat(60))

if (failCount > 0) {
    println()
    println("${RED}⚠ Some tests failed!${RESET}")
    println("Review failures above and fix before deployment.")
    exitProcess(1)
} else {
    println()
    println("${GREEN}✓ All tests passed!${RESET}")
    println("Ready for lab deployment.")
    println()
    info("Next steps:")
    info("  1. Commit changes: git add -A && git commit -m 'fix: critical pre-deployment fixes'")
    info("  2. Push: git push")
    info("  3. Deploy on lab server")
    exitProcess(0)
}
