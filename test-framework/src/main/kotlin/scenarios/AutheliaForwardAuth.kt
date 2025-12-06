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

            // Heuristic: if redirected to auth subdomain, attempt simple form fill if visible
            if (page.url().contains("auth.")) {
                tryFill(page, listOf("#username", "#username-textfield", "input[name='username']"), service.credentials?.username)
                tryFill(page, listOf("#password", "#password-textfield", "input[name='password']"), service.credentials?.password)
                tryClick(page, listOf("button[type='submit']", "#sign-in-button"))
            }

            // Wait for final state selector
            val finalSelector = service.finalWaitSelector
            if (finalSelector != null) {
                try {
                    page.waitForSelector(finalSelector, Page.WaitForSelectorOptions().setTimeout(30000.0))
                } catch (t: Throwable) {
                    // Continue anyway to capture what we got
                }
            } else {
                // Wait for any screenshot step selectors
                service.screenshots.firstOrNull()?.waitFor?.let { selector ->
                    try {
                        page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(30000.0))
                    } catch (t: Throwable) {
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
}
