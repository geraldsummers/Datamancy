#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.49.0")

import com.microsoft.playwright.*

val domain = System.getenv("DOMAIN") ?: "project-saturn.com"

val services = listOf(
    "vaultwarden" to "https://vaultwarden.$domain",
    "planka" to "https://planka.$domain",
    "jupyterhub" to "https://jupyterhub.$domain",
    "mastodon" to "https://mastodon.$domain"
)

println("Inspecting OIDC login buttons...\n")

Playwright.create().use { playwright ->
    val browser = playwright.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(true)
    )

    val context = browser.newContext(
        Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)
    )

    for ((name, url) in services) {
        println("=" .repeat(60))
        println("Service: $name")
        println("URL: $url")
        println("=" .repeat(60))

        try {
            val page = context.newPage()
            page.navigate(url)
            page.waitForTimeout(3000.0)

            println("Current URL: ${page.url()}")
            println()

            // Look for common button/link patterns
            val selectors = listOf(
                "button" to "all buttons",
                "a[href*='oauth']" to "OAuth links",
                "a[href*='oidc']" to "OIDC links",
                "a[href*='auth']" to "Auth links",
                "button:has-text('Sign')" to "Sign buttons",
                "a:has-text('Sign')" to "Sign links",
                "button[class*='sso']" to "SSO class buttons",
                "button[class*='login']" to "Login class buttons",
                "a.service-login" to "Service login links"
            )

            for ((selector, description) in selectors) {
                try {
                    val elements = page.locator(selector)
                    val count = elements.count()
                    if (count > 0) {
                        println("Found $count $description:")
                        for (i in 0 until minOf(count, 5)) {
                            try {
                                val elem = elements.nth(i)
                                val text = elem.textContent()?.take(100) ?: ""
                                val href = try { elem.getAttribute("href") } catch (e: Exception) { null }
                                val classes = try { elem.getAttribute("class") } catch (e: Exception) { null }

                                print("  - ")
                                if (text.isNotBlank()) print("text='$text' ")
                                if (href != null) print("href='$href' ")
                                if (classes != null) print("class='$classes' ")
                                println()
                            } catch (e: Exception) {
                                // Skip this element
                            }
                        }
                        println()
                    }
                } catch (e: Exception) {
                    // Selector failed, skip it
                }
            }

            // Get a snippet of the body HTML
            try {
                val bodyHTML = page.locator("body").innerHTML()
                println("Body HTML snippet (first 1000 chars):")
                println(bodyHTML.take(1000))
                println("...")
            } catch (e: Exception) {
                println("Could not get body HTML: ${e.message}")
            }

            page.close()

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
        }

        println("\n")
    }

    browser.close()
}

println("Inspection complete!")
