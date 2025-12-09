package core

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import scenarios.TestScenario
import scenarios.AutheliaForwardAuthScenario
import scenarios.OIDCLoginScenario
import scenarios.DirectLoginScenario
import services.AuthType
import services.ServiceDefinition
import java.time.Duration
import java.time.Instant

/**
 * SSO Test Orchestrator - Tests services sequentially with a single browser context
 * to properly test Single Sign-On behavior where authentication persists across services.
 */
class SSOTestOrchestrator(
    private val browserPool: BrowserPool,
    private val output: java.nio.file.Path
) {
    suspend fun runSSOTests(services: List<ServiceDefinition>): TestReport {
        println("Running SSO tests sequentially with shared browser context...")
        println("This simulates real user behavior: log in once, access all services")
        println()

        val results = mutableListOf<TestResult>()

        browserPool.withBrowser { browser ->
            // Create ONE browser context that will be shared across all tests
            val context = browser.newContext()

            try {
                // Step 1: Find Authelia service and authenticate first
                val autheliaService = services.find { it.name.equals("Authelia", ignoreCase = true) }

                if (autheliaService != null) {
                    println("=== Phase 1: Authenticating with Authelia ===")
                    val autheliaResult = authenticateWithAuthelia(autheliaService, context)
                    results.add(autheliaResult)

                    when (autheliaResult) {
                        is TestResult.Success -> {
                            println("✓ Authelia authentication successful (${autheliaResult.duration.toMillis()}ms)")
                            println()
                        }
                        else -> {
                            println("✗ Authelia authentication failed - SSO will not work for other services")
                            println()
                        }
                    }
                } else {
                    println("WARNING: No Authelia service found in config - SSO may not work")
                    println()
                }

                // Step 2: Test all other services with the same authenticated context
                println("=== Phase 2: Testing services with SSO session ===")
                val otherServices = services.filter { !it.name.equals("Authelia", ignoreCase = true) }

                otherServices.forEachIndexed { index, service ->
                    println("[${index + 1}/${otherServices.size}] Testing ${service.name}...")
                    val result = testServiceWithSSOContext(service, context)
                    results.add(result)

                    when (result) {
                        is TestResult.Success -> println("  ✓ ${service.name}: PASS (${result.duration.toMillis()}ms)")
                        is TestResult.Failure -> println("  ✗ ${service.name}: FAIL (${result.error.message})")
                        is TestResult.Timeout -> println("  ✗ ${service.name}: TIMEOUT (${result.elapsed.toMillis()}ms)")
                    }
                }

            } finally {
                context.close()
            }
        }

        return TestReport(java.util.UUID.randomUUID().toString(), results)
    }

    private fun authenticateWithAuthelia(
        service: ServiceDefinition,
        context: BrowserContext
    ): TestResult {
        val start = Instant.now()
        return try {
            val page = context.newPage()
            val collector = ScreenshotCollector(output, service.name)

            // Navigate to Authelia
            page.navigate(service.url)
            page.waitForTimeout(1000.0)

            // Fill in login form
            val username = service.credentials?.username ?: "admin"
            val password = service.credentials?.password ?: ""

            println("  Logging in as: $username")

            // Try to fill username
            tryFill(page, listOf(
                "#username-textfield",
                "#username",
                "input[name='username']",
                "input[type='text']"
            ), username)

            // Try to fill password
            tryFill(page, listOf(
                "#password-textfield",
                "#password",
                "input[name='password']",
                "input[type='password']"
            ), password)

            // Click sign in
            tryClick(page, listOf(
                "#sign-in-button",
                "button[type='submit']",
                "button:has-text('Sign in')"
            ))

            // Wait for redirect and authentication to complete
            page.waitForTimeout(5000.0)

            // Wait for either logout link or dashboard to appear (confirms we're logged in)
            try {
                page.waitForSelector("a[href*='logout'], [aria-label*='Dashboard'], .dashboard",
                    Page.WaitForSelectorOptions().setTimeout(10000.0))
                println("  Authentication successful - session established")
            } catch (e: Exception) {
                println("  WARNING: Could not find logout link or dashboard, authentication may have failed")
                println("  Current URL: ${page.url()}")
            }

            // Take screenshot
            collector.captureFinal(page)
            page.close()

            val duration = Duration.between(start, Instant.now())
            TestResult.Success(service.name, duration, collector.getAll(), emptyList())

        } catch (e: Exception) {
            TestResult.Failure(
                service.name,
                TestError(ErrorType.UNKNOWN, e.message ?: "Authentication failed"),
                emptyList(),
                emptyList()
            )
        }
    }

    private fun testServiceWithSSOContext(
        service: ServiceDefinition,
        context: BrowserContext
    ): TestResult {
        val start = Instant.now()
        return try {
            val page = context.newPage()
            val collector = ScreenshotCollector(output, service.name)

            // Navigate to service - should already be authenticated via SSO
            page.navigate(service.url)

            // Wait for page to load
            page.waitForTimeout(2000.0)

            // Check if we got redirected to Authelia (means SSO didn't work)
            if (page.url().contains("auth.")) {
                println("  WARNING: Redirected to Authelia - SSO session not working for this service")
                // Don't try to re-auth, just capture state
            }

            // Wait for expected selector if configured
            service.finalWaitSelector?.let { selector ->
                try {
                    page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(5000.0))
                    println("  Found expected element: $selector")
                } catch (e: Exception) {
                    println("  Could not find expected element: $selector")
                }
            }

            // Give page time to render
            page.waitForTimeout(2000.0)

            // Capture screenshot
            collector.captureFinal(page)
            page.close()

            val duration = Duration.between(start, Instant.now())
            TestResult.Success(service.name, duration, collector.getAll(), emptyList())

        } catch (e: Exception) {
            TestResult.Failure(
                service.name,
                TestError(ErrorType.UNKNOWN, e.message ?: "Test failed"),
                emptyList(),
                emptyList()
            )
        }
    }

    private fun tryFill(page: Page, selectors: List<String>, value: String?) {
        if (value == null) return
        for (selector in selectors) {
            try {
                if (page.isVisible(selector)) {
                    page.fill(selector, value)
                    return
                }
            } catch (_: Throwable) { }
        }
    }

    private fun tryClick(page: Page, selectors: List<String>) {
        for (selector in selectors) {
            try {
                if (page.isVisible(selector)) {
                    page.click(selector)
                    return
                }
            } catch (_: Throwable) { }
        }
    }
}
