package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.TestRunner
import java.io.File

/**
 * Playwright E2E Tests
 *
 * Orchestrates running Playwright browser automation tests for:
 * - Forward auth services (Caddy → Authelia)
 * - OIDC services (OAuth2/OIDC flows)
 * - UI validation across all web services
 */
suspend fun TestRunner.playwrightE2ETests() {
    suite("Playwright E2E Tests") {
        test("Run Playwright E2E Test Suite") {
            val playwrightDir = File("/app/playwright-tests")

            if (!playwrightDir.exists()) {
                throw Exception("Playwright tests directory not found: ${playwrightDir.absolutePath}")
            }

            // Set environment variables for Playwright tests
            val ldapUrl = this@playwrightE2ETests.env.endpoints.ldap ?: "ldap://openldap:389"
            val autheliaUrl = this@playwrightE2ETests.env.endpoints.authelia.replace("https://", "http://").replace(":9091", "")
            // Use Caddy internal proxy to avoid NAT hairpin issues (external domain routes through Cloudflare)
            val baseUrl = System.getenv("BASE_URL") ?: "http://caddy"

            val processBuilder = ProcessBuilder(
                "npm", "run", "test:e2e",
                "--", "--reporter=json"
            ).apply {
                directory(playwrightDir)
                environment().apply {
                    put("LDAP_URL", ldapUrl)
                    put("LDAP_ADMIN_DN", "cn=admin,dc=datamancy,dc=net")
                    put("LDAP_ADMIN_PASSWORD", System.getenv("LDAP_ADMIN_PASSWORD") ?: "admin")
                    put("AUTHELIA_URL", autheliaUrl)
                    put("BASE_URL", baseUrl)
                }
                redirectErrorStream(true)
            }

            println("Starting Playwright E2E tests...")
            println("  Playwright dir: ${playwrightDir.absolutePath}")
            println("  LDAP URL: $ldapUrl")
            println("  Authelia URL: $autheliaUrl")
            println("  Base URL: $baseUrl")

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            // Log output
            println("Playwright output:\n$output")

            // Copy Playwright reports to test-results
            val resultsDir = this@playwrightE2ETests.resultsDir
            if (resultsDir != null) {
                val playwrightReportDir = File(playwrightDir, "playwright-report")
                val playwrightResultsDir = File(playwrightDir, "test-results")
                val targetDir = File(resultsDir, "playwright")
                targetDir.mkdirs()

                if (playwrightReportDir.exists()) {
                    playwrightReportDir.copyRecursively(File(targetDir, "report"), overwrite = true)
                    println("Copied Playwright HTML report to ${targetDir.absolutePath}/report")
                }

                if (playwrightResultsDir.exists()) {
                    playwrightResultsDir.copyRecursively(File(targetDir, "test-results"), overwrite = true)
                    println("Copied Playwright test results to ${targetDir.absolutePath}/test-results")
                }
            }

            if (exitCode != 0) {
                throw Exception("Playwright E2E tests failed with exit code $exitCode. See output above.")
            }

            println("✓ Playwright E2E tests passed")
        }
    }
}
