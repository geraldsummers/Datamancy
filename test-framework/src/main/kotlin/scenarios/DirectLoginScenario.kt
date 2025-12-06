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

            // Try common username field selectors
            tryFill(page, listOf(
                "#username",
                "#username-textfield",
                "input[name='username']",
                "input[type='text']",
                "input[type='email']"
            ), service.credentials?.username)

            // Try common password field selectors
            tryFill(page, listOf(
                "#password",
                "#password-textfield",
                "input[name='password']",
                "input[type='password']"
            ), service.credentials?.password)

            // Try common submit button selectors
            tryClick(page, listOf(
                "button[type='submit']",
                "#sign-in-button",
                "button:has-text('Sign in')",
                "button:has-text('Log in')",
                "button:has-text('Login')"
            ))

            // Wait for final state selector
            val finalSelector = service.finalWaitSelector
            if (finalSelector != null) {
                try {
                    page.waitForSelector(finalSelector, Page.WaitForSelectorOptions().setTimeout(30000.0))
                } catch (t: Throwable) {
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
}
