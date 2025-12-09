package scenarios

import com.microsoft.playwright.BrowserContext
import core.ScreenshotCollector
import core.TestResult
import services.ServiceDefinition

interface TestScenario {
    fun execute(
        service: ServiceDefinition,
        context: BrowserContext,
        screenshots: ScreenshotCollector
    ): TestResult
}
