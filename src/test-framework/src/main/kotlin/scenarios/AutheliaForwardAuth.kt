package scenarios

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import core.CheckpointResult
import core.ErrorType
import core.ScreenshotCollector
import core.TestError
import core.TestResult
import services.ServiceDefinition
import java.time.Duration
import java.time.Instant

class AutheliaForwardAuthScenario : TestScenario {
    override fun execute(
        service: ServiceDefinition,
        context: BrowserContext,
        screenshots: ScreenshotCollector
    ): TestResult {
        val start = Instant.now()
        return try {
            val page = context.newPage()
            page.navigate(service.url)

            // Wait for page to load and check if we're on auth page
            page.waitForTimeout(1000.0)

            // Check for OIDC/OAuth "Sign in with Authelia" button first (like Grafana)
            val oauthButtonSelectors = listOf(
                "a:has-text('Sign in with Authelia')",
                "button:has-text('Sign in with Authelia')",
                "a:has-text('Login with Authelia')",
                "[data-testid='oauth-button']"
            )

            var clickedOAuthButton = false
            for (selector in oauthButtonSelectors) {
                try {
                    if (page.isVisible(selector)) {
                        println("[${service.name}] Found OAuth/OIDC button, clicking: $selector")
                        page.click(selector)
                        clickedOAuthButton = true
                        page.waitForTimeout(2000.0)
                        break
                    }
                } catch (e: Throwable) {
                    // Selector not found, try next
                }
            }

            // Heuristic: if redirected to auth subdomain OR clicked OAuth button, attempt simple form fill if visible
            if (page.url().contains("auth.") || clickedOAuthButton) {
                println("[${service.name}] Detected Authelia redirect or OAuth flow, filling credentials")

                // Wait for form to be visible
                tryWaitForSelector(page, listOf("#username-textfield", "#username", "input[name='username']"), 3000.0)

                tryFill(page, listOf("#username-textfield", "#username", "input[name='username']"), service.credentials?.username)
                tryFill(page, listOf("#password-textfield", "#password", "input[name='password']"), service.credentials?.password)
                tryClick(page, listOf("#sign-in-button", "button[type='submit']", "button:has-text('Sign in')"))

                println("[${service.name}] Submitted auth form, waiting for redirect back")
                // Wait for redirect back to service
                page.waitForTimeout(3000.0)
            }

            // Wait for final state selector
            val finalSelector = service.finalWaitSelector
            if (finalSelector != null) {
                try {
                    println("[${service.name}] Looking for finalWaitSelector: $finalSelector")
                    println("[${service.name}] Current URL: ${page.url()}")
                    page.waitForSelector(finalSelector, Page.WaitForSelectorOptions().setTimeout(5000.0))
                    println("[${service.name}] Found finalWaitSelector")
                } catch (t: Throwable) {
                    println("[${service.name}] Warning: finalWaitSelector '$finalSelector' not found: ${t.message}")
                    // Continue anyway to capture what we got
                }
            } else {
                // Wait for any screenshot step selectors
                service.screenshots.firstOrNull()?.waitFor?.let { selector ->
                    try {
                        println("[${service.name}] Looking for screenshot waitFor: $selector")
                        page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(5000.0))
                        println("[${service.name}] Found screenshot waitFor selector")
                    } catch (t: Throwable) {
                        println("[${service.name}] Warning: screenshot waitFor '$selector' not found: ${t.message}")
                        // Continue anyway
                    }
                }
            }

            // Give page time to fully render
            page.waitForTimeout(2000.0)

            // Capture final state
            screenshots.captureFinal(page)

            val dur = Duration.between(start, Instant.now())
            TestResult.Success(service.name, dur, screenshots.getAll(), emptyList())
        } catch (t: Throwable) {
            TestResult.Failure(
                service.name,
                TestError(ErrorType.UNKNOWN, t.message ?: "Unknown"),
                screenshots.getAll(),
                emptyList()
            )
        }
    }

    private fun tryFill(page: Page, selectors: List<String>, value: String?) {
        if (value == null) return
        for (s in selectors) {
            try {
                if (page.isVisible(s)) {
                    page.fill(s, value)
                    return
                }
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun tryClick(page: Page, selectors: List<String>) {
        for (s in selectors) {
            try {
                if (page.isVisible(s)) {
                    page.click(s)
                    return
                }
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun tryWaitForSelector(page: Page, selectors: List<String>, timeout: Double) {
        for (s in selectors) {
            try {
                page.waitForSelector(s, Page.WaitForSelectorOptions().setTimeout(timeout))
                return
            } catch (_: Throwable) { /* ignore */ }
        }
    }
}
