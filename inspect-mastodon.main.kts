#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"
val url = "https://mastodon.$domain/auth/sign_in"

println("Inspecting Mastodon login page: $url\n")

Playwright.create().use { playwright ->
    val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)
    )

    val page = context.newPage()
    page.navigate(url)
    page.waitForTimeout(3000.0)

    println("Current URL: ${page.url()}\n")

    // Get all buttons and links
    val allButtons = page.locator("button, a.button, a[role='button']")
    println("Found ${allButtons.count()} buttons/links:")
    for (i in 0 until allButtons.count()) {
        val elem = allButtons.nth(i)
        val text = elem.textContent()?.trim() ?: ""
        val href = try { elem.getAttribute("href") } catch (e: Exception) { null }
        val classes = try { elem.getAttribute("class") } catch (e: Exception) { null }

        print("  ${i+1}. ")
        if (text.isNotBlank()) print("'$text' ")
        if (href != null) print("href='$href' ")
        if (classes != null) print("class='$classes'")
        println()
    }

    println("\n\nFull HTML:")
    println(page.content())

    browser.close()
}
