#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"
val url = "https://vaultwarden.$domain"

println("Testing Vaultwarden button click strategies\n")

Playwright.create().use { playwright ->
    val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(false))
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)
    )

    val page = context.newPage()
    page.navigate(url)

    println("Waiting for page load...")
    page.waitForTimeout(3000.0)
    println("Current URL: ${page.url()}")

    val selector = "button.vw-sso-login"

    // Strategy 1: Wait for selector to be attached
    println("\n1. Waiting for selector to be attached...")
    try {
        page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(5000.0).setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED))
        println("   ✓ Selector is attached")
    } catch (e: Exception) {
        println("   ✗ Failed: ${e.message}")
    }

    // Strategy 2: Check if button is in viewport
    println("\n2. Checking button position...")
    try {
        val box = page.locator(selector).first().boundingBox()
        println("   Button position: x=${box?.x}, y=${box?.y}, width=${box?.width}, height=${box?.height}")
    } catch (e: Exception) {
        println("   ✗ Failed: ${e.message}")
    }

    // Strategy 3: Try dispatchEvent
    println("\n3. Trying dispatchEvent click...")
    try {
        page.locator(selector).first().dispatchEvent("click")
        println("   ✓ dispatchEvent succeeded")
        page.waitForTimeout(2000.0)
        println("   After click URL: ${page.url()}")
    } catch (e: Exception) {
        println("   ✗ Failed: ${e.message}")
    }

    // Strategy 4: Try evaluate click
    println("\n4. Trying document.querySelector().click()...")
    try {
        page.evaluate("document.querySelector('$selector').click()")
        println("   ✓ JS click succeeded")
        page.waitForTimeout(2000.0)
        println("   After click URL: ${page.url()}")
    } catch (e: Exception) {
        println("   ✗ Failed: ${e.message}")
    }

    // Strategy 5: Get button text and use text selector
    println("\n5. Trying text-based selector...")
    try {
        page.locator("button:has-text('Use single sign-on')").click(Locator.ClickOptions().setTimeout(5000.0))
        println("   ✓ Text selector click succeeded")
        page.waitForTimeout(2000.0)
        println("   After click URL: ${page.url()}")
    } catch (e: Exception) {
        println("   ✗ Failed: ${e.message}")
    }

    println("\nFinal URL: ${page.url()}")

    if (page.url().contains("auth.")) {
        println("✓✓✓ SUCCESS - Redirected to Authelia!")
    } else {
        println("✗✗✗ FAILED - Still on Vaultwarden")
    }

    page.waitForTimeout(3000.0)
    browser.close()
}
