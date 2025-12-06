#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"
val url = "https://vaultwarden.$domain"

println("Debugging Vaultwarden button visibility\n")

Playwright.create().use { playwright ->
    val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)
    )

    val page = context.newPage()
    page.navigate(url)

    for (delay in listOf(1000, 2000, 3000, 5000)) {
        page.waitForTimeout(delay.toDouble())
        println("After ${delay}ms:")
        println("  Current URL: ${page.url()}")

        val selector = "button.vw-sso-login"
        try {
            val element = page.locator(selector).first()
            println("  Element exists: ${element.count() > 0}")
            println("  Element visible: ${element.isVisible()}")
            println("  Element enabled: ${element.isEnabled()}")

            if (element.isVisible()) {
                println("  ✓ Button is visible! Trying to click...")
                element.scrollIntoViewIfNeeded()
                element.click()
                println("  ✓ Clicked successfully!")
                page.waitForTimeout(2000.0)
                println("  After click URL: ${page.url()}")
                break
            } else {
                println("  ✗ Button not yet visible")
            }
        } catch (e: Exception) {
            println("  ERROR: ${e.message}")
        }
        println()
    }

    browser.close()
}
