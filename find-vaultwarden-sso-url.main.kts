#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"
val url = "https://vaultwarden.$domain"

println("Looking for Vaultwarden SSO redirect URL in HTML\n")

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

    // Get the button's onclick or href
    try {
        val href = page.evaluate("""
            const btn = document.querySelector('button.vw-sso-login');
            if (btn) {
                // Check for Angular click handler or data attributes
                const attrs = {};
                for (let attr of btn.attributes) {
                    attrs[attr.name] = attr.value;
                }
                return {
                    tag: btn.tagName,
                    onclick: btn.onclick,
                    attributes: attrs,
                    innerHTML: btn.innerHTML.substring(0, 200)
                };
            }
            return null;
        """)
        println("Button info:")
        println(href)
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }

    // Try to find SSO endpoint in JavaScript or network requests
    println("\n\nChecking for SSO endpoints in page source...")
    val content = page.content()
    val ssoMatches = Regex("""(identity/connect|oauth|sso)[^\s"'<>]+""").findAll(content)
    ssoMatches.take(10).forEach {
        println("  Found: ${it.value}")
    }

    // Try to intercept network request when clicking
    println("\n\nAttempting to intercept network requests...")
    page.route("**/*") { route ->
        val url = route.request().url()
        if (url.contains("identity") || url.contains("oauth") || url.contains("sso")) {
            println("  Network request: $url")
        }
        route.resume()
    }

    try {
        page.locator("button.vw-sso-login").first().dispatchEvent("click")
        page.waitForTimeout(2000.0)
        println("After click URL: ${page.url()}")
    } catch (e: Exception) {
        println("Click failed: ${e.message}")
    }

    browser.close()
}
