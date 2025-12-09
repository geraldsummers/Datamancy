package scenarios

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import core.ScreenshotCollector
import core.TestError
import core.ErrorType
import core.TestResult
import services.ServiceDefinition
import java.time.Duration
import java.time.Instant

class DirectLoginScenario : TestScenario {
    override fun execute(
        service: ServiceDefinition,
        context: BrowserContext,
        screenshots: ScreenshotCollector
    ): TestResult {
        val start = Instant.now()
        return try {
            val page = context.newPage()
            page.navigate(service.url)

            // Wait for page to load
            page.waitForTimeout(1000.0)

            println("[${service.name}] Attempting direct login at: ${page.url()}")

            // Wait for login form to be visible
            tryWaitForSelector(page, listOf("#username-textfield", "#username", "input[name='username']", "input[type='text']"), 3000.0)

            // Try common username field selectors
            tryFill(page, listOf(
                "#username-textfield",
                "#username",
                "input[name='username']",
                "input[type='text']",
                "input[type='email']"
            ), service.credentials?.username)

            // Try common password field selectors
            tryFill(page, listOf(
                "#password-textfield",
                "#password",
                "input[name='password']",
                "input[type='password']"
            ), service.credentials?.password)

            // Try common submit button selectors
            tryClick(page, listOf(
                "#sign-in-button",
                "button[type='submit']",
                "button:has-text('Sign in')",
                "button:has-text('Log in')",
                "button:has-text('Login')"
            ))

            println("[${service.name}] Submitted login form, waiting for redirect")
            // Wait for page to redirect after login
            page.waitForTimeout(3000.0)

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
