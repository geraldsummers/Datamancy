#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"
val url = "https://jupyterhub.$domain"

println("Testing JupyterHub redirect behavior\n")

Playwright.create().use { playwright ->
    val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)
    )

    val page = context.newPage()
    println("Navigating to: $url")
    page.navigate(url)

    println("Initial URL: ${page.url()}")
    page.waitForTimeout(1000.0)
    println("After 1s: ${page.url()}")
    page.waitForTimeout(1000.0)
    println("After 2s: ${page.url()}")
    page.waitForTimeout(1000.0)
    println("After 3s: ${page.url()}")

    if (page.url().contains("auth.")) {
        println("\n✓ JupyterHub auto-redirected to Authelia!")
        println("No button click needed - it's configured for auto-redirect")
    } else {
        println("\n✗ Still on JupyterHub, looking for buttons...")
        val bodySnippet = page.content().take(1000)
        println(bodySnippet)
    }

    browser.close()
}
