package core

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.BrowserContext

class BrowserPool(private val concurrency: Int = 5) {
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions()
            .setHeadless(true)
            .setArgs(listOf("--disable-dev-shm-usage", "--no-sandbox"))
    )

    fun <T> withContext(block: (BrowserContext) -> T): T {
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(1920, 1080)
                .setIgnoreHTTPSErrors(true)
        )
        return try {
            block(context)
        } finally {
            context.close()
        }
    }

    fun <T> withBrowser(block: (Browser) -> T): T {
        return block(browser)
    }
}
