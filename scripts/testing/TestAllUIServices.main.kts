#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.40.0")
@file:DependsOn("com.google.code.gson:gson:2.10.1")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

import com.google.gson.GsonBuilder
import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitForSelectorState
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlin.collections.fill

// ---- Tunables ----

 val NAV_TIMEOUT_MS = 30_000.0
 val SHORT_NAV_TIMEOUT_MS = 15_000.0
 val ELEMENT_WAIT_MS = 5_000.0
 val ELEMENT_WAIT_SHORT_MS = 2_000.0

// ---- Shell helper ----

fun runCmd(vararg cmd: String): CommandResult {
    val process = ProcessBuilder(*cmd).start()
    val output = process.inputStream.bufferedReader().readText()
    val error = process.errorStream.bufferedReader().readText()
    process.waitFor()
    return CommandResult(output, error, process.exitValue())
}

data class CommandResult(val out: String, val err: String, val exitCode: Int)

// ---- Configuration ----

val SCRIPT_DIR: Path = runCmd(
    "bash",
    "-c",
    "cd \$(dirname \${BASH_SOURCE[0]:-\${0}}) && pwd"
).out.trim().let { Path.of(it) }

val DOMAIN = "project-saturn.com"
val ADMIN_USER = "admin"
val ADMIN_PASSWORD = "dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV"
val SCREENSHOT_DIR = SCRIPT_DIR.resolve("screenshots").toFile()

// ---- Data classes ----

data class ServiceConfig(
    val name: String,
    val url: String,
    val loginRequired: Boolean = true,
    val loginType: String = "direct", // "direct", "oidc", "forward-auth", "http-basic", "optional"
    val usernameSelectors: List<String> = emptyList(),
    val passwordSelectors: List<String> = emptyList(),
    val submitSelectors: List<String> = emptyList()
)

data class Screenshot(
    val step: String,
    val path: String,
    val filename: String
)

data class TestResult(
    val service: String,
    val url: String,
    val timestamp: String,
    var accessible: Boolean = false,
    var loginAttempted: Boolean = false,
    var loginSuccessful: Boolean = false,
    val loginType: String,
    var error: String? = null,
    val screenshots: MutableList<Screenshot> = mutableListOf(),
    var finalUrl: String? = null
)

data class TestSummary(
    val totalServices: Int,
    val accessible: Int,
    val loginAttempts: Int,
    val loginSuccessful: Int,
    val totalScreenshots: Int
)

data class TestReport(
    val timestamp: String,
    val summary: TestSummary,
    val results: List<TestResult>
)

// ---- Services ----

val SERVICES = listOf(
    ServiceConfig(
        name = "Authelia",
        url = "https://auth.$DOMAIN",
        loginType = "direct",
        usernameSelectors = listOf("#username-textfield", "input[name='username']"),
        passwordSelectors = listOf("#password-textfield", "input[name='password']"),
        submitSelectors = listOf("button[type='submit']", "#sign-in-button")
    ),
    ServiceConfig(
        name = "Grafana",
        url = "https://grafana.$DOMAIN",
        loginType = "oidc"
    ),
    ServiceConfig(
        name = "Open WebUI",
        url = "https://open-webui.$DOMAIN",
        loginType = "oidc"
    ),
    ServiceConfig(
        name = "Vaultwarden",
        url = "https://vaultwarden.$DOMAIN",
        loginType = "oidc"
    ),
    ServiceConfig(
        name = "Planka",
        url = "https://planka.$DOMAIN",
        loginType = "oidc"
    ),
    ServiceConfig(
        name = "Bookstack",
        url = "https://bookstack.$DOMAIN",
        loginType = "oidc"  // Currently uses OIDC, will be forward-auth after OBLITERATE
    ),
    ServiceConfig(
        name = "Seafile",
        url = "https://seafile.$DOMAIN",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "OnlyOffice",
        url = "https://onlyoffice.$DOMAIN",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "JupyterHub",
        url = "https://jupyterhub.$DOMAIN",
        loginType = "oidc-auto"  // Auto-redirects to Authelia, no button click needed
    ),
    ServiceConfig(
        name = "Homepage",
        url = "https://homepage.$DOMAIN",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "SOGo",
        url = "https://sogo.$DOMAIN/SOGo",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "Home Assistant",
        url = "https://homeassistant.$DOMAIN",
        loginType = "forward-auth"  // Uses Authelia forward auth, not OIDC
    ),
    ServiceConfig(
        name = "Kopia",
        url = "https://kopia.$DOMAIN",
        loginRequired = false,  // HTTP Basic Auth is handled at the browser level
        loginType = "http-basic"
    ),
    // Mailu services: protected by Authelia forward_auth
    ServiceConfig(
        name = "Mailu Admin",
        url = "https://mail.$DOMAIN/admin",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "LDAP Account Manager",
        url = "https://lam.$DOMAIN",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "Dockge",
        url = "https://dockge.$DOMAIN",
        loginType = "forward-auth"
    ),
    ServiceConfig(
        name = "Mastodon",
        url = "https://mastodon.$DOMAIN",
        loginRequired = false,
        loginType = "optional"
    ),
    ServiceConfig(
        name = "Forgejo",
        url = "https://forgejo.$DOMAIN/user/login",
        loginType = "oidc"  // Currently uses OIDC, will be forward-auth after OBLITERATE
    ),
    ServiceConfig(
        name = "qBittorrent",
        url = "https://qbittorrent.$DOMAIN",
        loginType = "forward-auth"
    )
)

// ---- Helpers ----

fun saveScreenshot(page: Page, serviceName: String, step: String, result: TestResult): String? {
    return try {
        val safeName = serviceName.replace(" ", "_").replace("/", "_")
        val filename = "${safeName}_${step}.png"
        val filepath = File(SCREENSHOT_DIR, filename).absolutePath

        runBlocking { delay(5000) }

        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(Paths.get(filepath))
                .setFullPage(true)
        )
        println("  📸 Screenshot: $filename")

        // HTML dump alongside screenshot
        try {
            val htmlFilename = "${safeName}_${step}.html"
            val htmlFilepath = File(SCREENSHOT_DIR, htmlFilename).absolutePath
            val htmlContent = page.content()
            File(htmlFilepath).writeText(htmlContent)
            println("  📄 HTML dump: $htmlFilename")
        } catch (e: Exception) {
            println("  ⚠️  HTML dump failed: ${e.message}")
        }

        result.screenshots.add(Screenshot(step, filepath, filename))
        filepath
    } catch (e: Exception) {
        println("  ⚠️  Screenshot failed: ${e.message}")
        null
    }
}

fun findElement(
    page: Page,
    selectors: List<String>,
    timeout: Double = ELEMENT_WAIT_MS,
    requireVisible: Boolean = true
): Pair<Locator?, String?> {
    for (selector in selectors) {
        try {
            val locator = page.locator(selector)
            val options = Locator.WaitForOptions()
                .setTimeout(timeout)
                .setState(
                    if (requireVisible) WaitForSelectorState.VISIBLE
                    else WaitForSelectorState.ATTACHED
                )
            locator.waitFor(options)

            if (locator.count() > 0) {
                return locator.first() to selector
            }
        } catch (_: Exception) {
            // Try next selector
        }
    }
    return null to null
}

/**
 * Light "post-login" settle:
 * - Try to detect typical logged-in/app-shell elements.
 * - Fallback to a very short wait if nothing obvious appears.
 */

// ---- Authelia ----

fun handleAutheliaLogin(page: Page, serviceName: String, result: TestResult): Boolean {
    println("  🔐 Handling Authelia login")

    return try {
        // Make sure we're actually on Authelia
        try {
            page.waitForURL(
                "**/auth.project-saturn.com/**",
                Page.WaitForURLOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
            )
        } catch (_: Exception) {
            if (!page.url().contains("auth.project-saturn.com")) {
                result.error = "Expected Authelia but URL is ${page.url()}"
                saveScreenshot(page, serviceName, "error_authelia_not_reached", result)
                return false
            }
        }

        val (usernameInput, _) = findElement(
            page,
            listOf("#username-textfield", "input[name='username']")
        )
        if (usernameInput == null) {
            result.error = "Authelia username field not found"
            saveScreenshot(page, serviceName, "error_authelia_no_username", result)
            return false
        }

        val (passwordInput, _) = findElement(
            page,
            listOf("#password-textfield", "input[name='password']")
        )
        if (passwordInput == null) {
            result.error = "Authelia password field not found"
            saveScreenshot(page, serviceName, "error_authelia_no_password", result)
            return false
        }

        println("  ✏️  Filling Authelia credentials")
        usernameInput.fill(ADMIN_USER)
        passwordInput.fill(ADMIN_PASSWORD)

        val (submitButton, _) = findElement(
            page,
            listOf("#sign-in-button", "button[type='submit']")
        )
        if (submitButton == null) {
            result.error = "Authelia submit button not found"
            saveScreenshot(page, serviceName, "error_authelia_no_submit", result)
            return false
        }

        println("  🖱️  Clicking Authelia sign in")

        // Wait for any loading/AJAX to complete
        try {
            page.waitForTimeout(2000.0)
        } catch (_: Exception) {
        }

        try {
            page.waitForNavigation(
                Page.WaitForNavigationOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
            ) {
                submitButton.click()
            }
        } catch (_: Exception) {
            // If nav didn't happen, we'll just inspect where we are
        }

        // Wait for redirect away from Authelia (either to consent screen or back to service)
        println("  ⏱️  Waiting for redirect away from Authelia...")
        try {
            // Wait up to 15 seconds for URL to change away from Authelia login
            page.waitForURL(
                "**/*",
                Page.WaitForURLOptions()
                    .setTimeout(15000.0)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD)
            )
        } catch (_: Exception) {
            println("  ⚠️  Timeout waiting for redirect")
        }

        // Give page time to fully load and render
        try {
            page.waitForTimeout(2000.0)
        } catch (_: Exception) {
        }

        // Check for OAuth consent screen
        val currentUrl = page.url()
        println("  🔍 Current URL after login: $currentUrl")

        if (currentUrl.contains("auth.project-saturn.com")) {
            val content = page.content()
            println("  🔍 Checking for consent screen...")

            if (content.contains("Consent Request") || content.contains("Accept") || content.contains("ACCEPT")) {
                println("  🔐 OAuth consent screen detected, clicking Accept")

                // Try multiple consent button selectors
                val consentButtonSelectors = listOf(
                    "button:has-text('ACCEPT')",
                    "button:has-text('Accept')",
                    "button:has-text('accept')",
                    "button[type='submit']:has-text('ACCEPT')",
                    "input[type='submit'][value='ACCEPT']",
                    "//button[contains(text(), 'ACCEPT')]",
                    "//button[contains(text(), 'Accept')]"
                )

                val (acceptButton, acceptSel) = findElement(
                    page,
                    consentButtonSelectors,
                    timeout = ELEMENT_WAIT_MS
                )

                if (acceptButton != null) {
                    println("  ✓ Found consent accept button: $acceptSel")
                    try {
                        page.waitForNavigation(
                            Page.WaitForNavigationOptions()
                                .setTimeout(NAV_TIMEOUT_MS)
                        ) {
                            acceptButton.click()
                            println("  🖱️  Clicked ACCEPT button")
                        }
                    } catch (e: Exception) {
                        println("  ⚠️  Navigation after clicking consent: ${e.message}")
                    }

                    // Wait again for redirect back to service
                    try {
                        page.waitForTimeout(5000.0)
                    } catch (_: Exception) {
                    }
                } else {
                    println("  ⚠️  Consent screen detected but ACCEPT button not found")
                    saveScreenshot(page, serviceName, "error_consent_button_not_found", result)
                }
            } else {
                println("  ℹ️  No consent screen detected in page content")
            }
        }

        val finalUrl = page.url()
        if (!finalUrl.contains("auth.project-saturn.com")) {
            println("  ✅ Redirected back to service: $finalUrl")
            try {
                page.waitForLoadState(
                    com.microsoft.playwright.options.LoadState.LOAD,
                    Page.WaitForLoadStateOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
                )
            } catch (_: Exception) {
            }

            // Extra wait for services that need time to settle after OAuth callback
            try {
                page.waitForTimeout(3000.0)
            } catch (_: Exception) {
            }

            return true
        } else {
            result.error = "Still on Authelia after login attempt"
            saveScreenshot(page, serviceName, "error_authelia_still_here", result)
            return false
        }
    } catch (e: Exception) {
        result.error = "Authelia login error: ${e.message}"
        saveScreenshot(page, serviceName, "error_authelia_exception", result)
        false
    }
}

// ---- Core test ----

fun testService(browser: Browser, service: ServiceConfig): TestResult {
    val serviceName = service.name
    val url = service.url

    println("\n${"=".repeat(80)}")
    println("Testing: $serviceName")
    println("URL: $url")
    println("=".repeat(80))

    val result = TestResult(
        service = serviceName,
        url = url,
        timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        loginType = service.loginType
    )

    var context: BrowserContext? = null

    try {
        val contextOptions = Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080)

        if (serviceName == "Kopia") {
            contextOptions.setHttpCredentials(ADMIN_USER, ADMIN_PASSWORD)
        }

        context = browser.newContext(contextOptions)
        val page = context.newPage()

        // Navigate
        println("  🌐 Navigating to $url")
        try {
            val response = page.navigate(
                url,
                Page.NavigateOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
            )
            result.accessible = response?.status()?.let { it < 400 } ?: false
            println("  📊 HTTP Status: ${response?.status() ?: "N/A"}")
        } catch (e: TimeoutError) {
            result.error = "Navigation timeout"
            println("  ❌ Navigation timeout")
            saveScreenshot(page, serviceName, "error_nav_timeout", result)
            return result
        } catch (e: Exception) {
            result.error = "Navigation error: ${e.message}"
            println("  ❌ Navigation error: ${e.message}")
            saveScreenshot(page, serviceName, "error_nav_exception", result)
            return result
        }

        // Light stabilisation (no NETWORKIDLE)
        try {
            page.waitForLoadState(
                com.microsoft.playwright.options.LoadState.LOAD,
                Page.WaitForLoadStateOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
            )
        } catch (_: Exception) {
        }

        // No-login services
        if (!service.loginRequired) {
            println("  ℹ️  No login required")
            result.loginSuccessful = true
            result.finalUrl = page.url()

            waitForPostLoginContent(page, serviceName)
            saveScreenshot(page, serviceName, "logged_in", result)

            return result
        }

        result.loginAttempted = true

        // Handle OIDC and forward-auth flows
        var currentUrl = page.url()

        when (service.loginType) {
            "oidc" -> {
                // OIDC services should have an SSO/OIDC button on their login page
                println("  🔐 OIDC flow: Looking for SSO button on service login page")

                // Vaultwarden: SSO button is already visible on the login page, no email entry needed
                // The "Continue" button is for password login, not SSO

                val oidcButtonSelectors = listOf(
                    "button#oidc-login",  // Bookstack
                    "button.vw-sso-login",  // Vaultwarden
                    "a.vw-sso-login",  // Vaultwarden (link variant)
                    "button:has-text('Login with Authelia')",  // Bookstack
                    "button:has-text('Use single sign-on')",  // Vaultwarden
                    "a:has-text('Use single sign-on')",  // Vaultwarden (link)
                    "button:has-text('Sign in with Authelia')",
                    "a:has-text('Sign in with Authelia')",
                    "button:has-text('Sign in with SSO')",
                    "a:has-text('Sign in with SSO')",
                    "button:has-text('SSO')",
                    "a:has-text('SSO')",
                    "button:has-text('Enterprise single sign-on')",  // Vaultwarden enterprise
                    "a:has-text('Enterprise single sign-on')",
                    "a.link-action:has-text('Authelia')",  // Forgejo
                    "a[href*='/user/oauth2/']",  // Forgejo OAuth link
                    "a[href*='oauth']",
                    "a[href*='sso']",  // Generic SSO links
                    "a[href*='oidc']",
                    "button[id*='oauth']",
                    "button[id*='oidc']",
                    "button[id*='sso']",
                    "button:has-text('Log in with SSO')",  // JupyterHub
                    "a:has-text('Log in with SSO')",  // JupyterHub
                    "button:has-text('OAuth 2.0')",  // Grafana
                    "a:has-text('OAuth 2.0')"
                )

                // Wait longer for page to stabilize and buttons to render
                try {
                    page.waitForTimeout(3000.0)
                } catch (_: Exception) {
                }

                println("  🔍 Searching for OIDC button with ${oidcButtonSelectors.size} selectors")

                // Try each selector manually with better error reporting
                var oidcButton: Locator? = null
                var oidcSel: String? = null

                for (selector in oidcButtonSelectors) {
                    try {
                        val loc = page.locator(selector)
                        val count = loc.count()
                        println("     Trying: $selector (found $count elements)")

                        if (count > 0) {
                            // Check if visible
                            val first = loc.first()
                            try {
                                if (first.isVisible()) {
                                    oidcButton = first
                                    oidcSel = selector
                                    println("     ✓ Found visible button: $selector")
                                    break
                                } else {
                                    println("     - Element exists but not visible, will try as fallback")
                                    // Store as fallback option for hidden buttons (like Vaultwarden)
                                    if (oidcButton == null) {
                                        oidcButton = first
                                        oidcSel = selector
                                    }
                                }
                            } catch (e: Exception) {
                                println("     - Visibility check failed: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        // Silently continue to next selector
                    }
                }

                if (oidcButton == null) {
                    println("  ⚠️  OIDC button not found after checking all selectors")
                    println("  📸 Taking screenshot for debugging...")
                    saveScreenshot(page, serviceName, "error_oidc_button_not_found", result)

                    // Debug: Print out all buttons and links on the page
                    try {
                        val allButtons = page.locator("button").all()
                        println("  🔍 Found ${allButtons.size} button elements on page")
                        allButtons.take(10).forEachIndexed { idx, btn ->
                            try {
                                val text = btn.textContent()?.trim()?.take(50) ?: ""
                                val classes = btn.getAttribute("class") ?: ""
                                val id = btn.getAttribute("id") ?: ""
                                println("     Button $idx: text='$text' class='$classes' id='$id'")
                            } catch (_: Exception) {}
                        }

                        val allLinks = page.locator("a").all()
                        println("  🔍 Found ${allLinks.size} link elements on page")
                        allLinks.take(10).forEachIndexed { idx, link ->
                            try {
                                val text = link.textContent()?.trim()?.take(50) ?: ""
                                val href = link.getAttribute("href") ?: ""
                                val classes = link.getAttribute("class") ?: ""
                                println("     Link $idx: text='$text' href='$href' class='$classes'")
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        println("  ⚠️  Error debugging page elements: ${e.message}")
                    }
                }

                if (oidcButton != null) {
                    val isVisible = try { oidcButton.isVisible() } catch (_: Exception) { false }
                    println("  🔑 Found OIDC button: $oidcSel (visible: $isVisible)")

                    // Wait for button to be fully visible and clickable
                    try {
                        page.waitForTimeout(1000.0)
                    } catch (_: Exception) {
                    }

                    try {
                        if (!isVisible) {
                            // For hidden buttons (like Vaultwarden), use JavaScript click directly
                            println("  🔄 Button not visible, using JavaScript click")
                            page.evaluate("document.querySelector('$oidcSel')?.click()")
                            page.waitForTimeout(3000.0)
                        } else {
                            // For visible buttons, use normal click with navigation wait
                            page.waitForNavigation(
                                Page.WaitForNavigationOptions()
                                    .setTimeout(NAV_TIMEOUT_MS)
                            ) {
                                oidcButton.click()
                            }

                            // Wait for redirect to Authelia
                            try {
                                page.waitForTimeout(2000.0)
                            } catch (_: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        println("  ⚠️  OIDC button click failed: ${e.message}")
                        // Try JavaScript click as fallback
                        println("  🔄 Attempting JavaScript click as fallback")
                        try {
                            page.evaluate("document.querySelector('$oidcSel')?.click()")
                            page.waitForTimeout(3000.0)
                        } catch (jsE: Exception) {
                            println("  ⚠️  JavaScript click also failed: ${jsE.message}")
                        }
                    }
                }

                // Now check if we're on Authelia
                if (page.url().contains("auth.project-saturn.com")) {
                    val success = handleAutheliaLogin(page, serviceName, result)
                    if (!success) {
                        return result
                    }
                    // After Authelia login, wait for OAuth callback
                    // This includes consent screen and redirect back to service
                    println("  🔄 Waiting for OAuth callback to complete")
                    try {
                        page.waitForURL(
                            "**//*",
                            Page.WaitForURLOptions().setTimeout(15000.0)
                        )
                        page.waitForTimeout(3000.0)
                    } catch (_: Exception) {
                    }

                    result.loginSuccessful = true
                    result.finalUrl = page.url()
                    waitForPostLoginContent(page, serviceName)
                    saveScreenshot(page, serviceName, "logged_in", result)
                    return result
                } else {
                    println("  ⚠️  Expected Authelia redirect but didn't happen")
                    result.error = "OIDC flow: expected Authelia redirect"
                    saveScreenshot(page, serviceName, "error_no_authelia_redirect", result)
                    return result
                }
            }

            "oidc-auto" -> {
                // OIDC services that automatically redirect to Auth elia (no button click needed)
                println("  🔐 OIDC auto-redirect flow: Service should automatically redirect to Authelia")

                // Wait for automatic redirect to Authelia
                try {
                    page.waitForURL(
                        "**/auth.project-saturn.com/**",
                        Page.WaitForURLOptions().setTimeout(10000.0)
                    )
                } catch (_: Exception) {
                    if (!page.url().contains("auth.project-saturn.com")) {
                        result.error = "Expected automatic redirect to Authelia but didn't happen"
                        saveScreenshot(page, serviceName, "error_no_authelia_redirect", result)
                        return result
                    }
                }

                // Handle Authelia login
                if (page.url().contains("auth.project-saturn.com")) {
                    val success = handleAutheliaLogin(page, serviceName, result)
                    if (!success) {
                        return result
                    }

                    // Wait for OAuth callback
                    println("  🔄 Waiting for OAuth callback to complete")
                    try {
                        page.waitForURL(
                            "**//*",
                            Page.WaitForURLOptions().setTimeout(15000.0)
                        )
                        page.waitForTimeout(3000.0)
                    } catch (_: Exception) {
                    }

                    result.loginSuccessful = true
                    result.finalUrl = page.url()
                    waitForPostLoginContent(page, serviceName)
                    saveScreenshot(page, serviceName, "logged_in", result)
                    return result
                } else {
                    result.error = "oidc-auto: expected Authelia redirect but didn't happen"
                    saveScreenshot(page, serviceName, "error_no_authelia_redirect", result)
                    return result
                }
            }

            "forward-auth" -> {
                // Forward-auth services should automatically redirect to Authelia
                println("  🔐 Forward-auth flow: Expecting automatic redirect to Authelia")

                if (!currentUrl.contains("auth.project-saturn.com")) {
                    try {
                        page.waitForURL(
                            "**/auth.project-saturn.com/**",
                            Page.WaitForURLOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
                        )
                    } catch (_: Exception) {
                        println("  ⚠️  No Authelia redirect detected")
                        result.error = "Forward-auth: expected Authelia redirect"
                        saveScreenshot(page, serviceName, "error_no_authelia_redirect", result)
                        return result
                    }
                }

                if (page.url().contains("auth.project-saturn.com")) {
                    val success = handleAutheliaLogin(page, serviceName, result)
                    if (success) {
                        result.loginSuccessful = true
                        result.finalUrl = page.url()

                        waitForPostLoginContent(page, serviceName)
                        saveScreenshot(page, serviceName, "logged_in", result)
                    }
                    return result
                }
            }
        }

        // Direct login
        println("  🔑 Attempting direct login")

        val (usernameInput, usernameSel) = findElement(
            page,
            service.usernameSelectors,
            timeout = ELEMENT_WAIT_MS
        )
        if (usernameInput == null) {
            result.error = "Username field not found"
            println("  ❌ Username field not found")
            saveScreenshot(page, serviceName, "error_no_username", result)
            return result
        }

        val (passwordInput, passwordSel) = findElement(
            page,
            service.passwordSelectors,
            timeout = ELEMENT_WAIT_MS
        )
        if (passwordInput == null) {
            result.error = "Password field not found"
            println("  ❌ Password field not found")
            saveScreenshot(page, serviceName, "error_no_password", result)
            return result
        }

        println("  ✏️  Filling credentials")
        println("     Username selector: $usernameSel")
        println("     Password selector: $passwordSel")

        usernameInput.fill(ADMIN_USER)
        passwordInput.fill(ADMIN_PASSWORD)

        val (submitButton, submitSel) = findElement(
            page,
            service.submitSelectors,
            timeout = ELEMENT_WAIT_MS
        )
        if (submitButton == null) {
            result.error = "Submit button not found"
            println("  ❌ Submit button not found")
            saveScreenshot(page, serviceName, "error_no_submit", result)
            return result
        }

        println("  🖱️  Clicking submit button: $submitSel")
        try {
            page.waitForNavigation(
                Page.WaitForNavigationOptions()
                    .setTimeout(NAV_TIMEOUT_MS)
            ) {
                submitButton.click()
            }
        } catch (_: Exception) {
            // If no nav, we'll just inspect URL
        }

        val finalUrl = page.url()
        result.finalUrl = finalUrl

        when {
            finalUrl != url &&
                    !finalUrl.contains("login", ignoreCase = true) &&
                    !finalUrl.contains("sign-in", ignoreCase = true) -> {
                result.loginSuccessful = true
                println("  ✅ Login successful (URL: $finalUrl)")

                waitForPostLoginContent(page, serviceName)
                saveScreenshot(page, serviceName, "logged_in", result)
            }

            finalUrl.contains("login", ignoreCase = true) ||
                    finalUrl.contains("sign-in", ignoreCase = true) -> {
                result.loginSuccessful = false
                result.error = "Still on login page after submit"
                println("  ⚠️  Still on login page")
                saveScreenshot(page, serviceName, "error_login_failed", result)
            }

            else -> {
                result.loginSuccessful = true
                println("  ✅ Login appears successful")

                waitForPostLoginContent(page, serviceName)
                saveScreenshot(page, serviceName, "logged_in", result)
            }
        }

    } catch (e: Exception) {
        result.error = "Unexpected error: ${e.message}"
        println("  ❌ Unexpected error: ${e.message}")
    } finally {
        try {
            context?.close()
        } catch (_: Exception) {
        }
    }

    return result
}

// ---- Per-service browser wrapper ----

fun testServiceWithOwnBrowser(service: ServiceConfig): TestResult {
    Playwright.create().use { playwright ->
        val browser = playwright.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(true)
                .setSlowMo(0.0)
        )
        return testService(browser, service)
    }
}

// ---- Reporting ----

fun generateReport(results: List<TestResult>) {
    println("\n${"=".repeat(80)}")
    println("DATAMANCY UI SERVICES - COMPREHENSIVE TEST REPORT")
    println("=".repeat(80))
    println(
        "Test Date: " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    )
    println("Total Services: ${results.size}")
    println("=".repeat(80))

    val accessible = results.count { it.accessible }
    val loginAttempted = results.count { it.loginAttempted }
    val loginSuccessful = results.count { it.loginSuccessful }
    val totalScreenshots = results.sumOf { it.screenshots.size }

    println("\n📊 Summary:")
    println("  • Accessible: $accessible/${results.size}")
    println("  • Login attempts: $loginAttempted")
    println("  • Successful logins: $loginSuccessful")
    println("  • Total screenshots: $totalScreenshots")

    println("\n${"-".repeat(80)}")
    println("Detailed Results:")
    println("-".repeat(80))

    for (result in results) {
        val icon = when {
            result.loginSuccessful -> "✅"
            result.accessible -> "⚠️"
            else -> "❌"
        }

        println("\n$icon ${result.service}")
        println("   URL: ${result.url}")
        println("   Accessible: ${result.accessible}")
        println("   Login Type: ${result.loginType}")
        println("   Login Attempted: ${result.loginAttempted}")
        println("   Login Successful: ${result.loginSuccessful}")
        result.finalUrl?.let { println("   Final URL: $it") }
        result.error?.let { println("   ⚠️  Error: $it") }
        println("   Screenshots (${result.screenshots.size}):")
        for (ss in result.screenshots) {
            println("      • ${ss.step}: ${ss.filename}")
        }
    }

    val reportPath = File(SCREENSHOT_DIR, "ui_test_report_kotlin.json")
    val gson = GsonBuilder().setPrettyPrinting().create()
    val report = TestReport(
        timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        summary = TestSummary(
            totalServices = results.size,
            accessible = accessible,
            loginAttempts = loginAttempted,
            loginSuccessful = loginSuccessful,
            totalScreenshots = totalScreenshots
        ),
        results = results
    )

    reportPath.writeText(gson.toJson(report))

    println("\n${"=".repeat(80)}")
    println("📄 JSON report: ${reportPath.absolutePath}")
    println("📁 Screenshots: ${SCREENSHOT_DIR.absolutePath}")
    println("=".repeat(80))
}

// ---- Entry point ----

fun main() = runBlocking {
    println("🚀 Starting Datamancy UI Services Comprehensive Test (Kotlin)")
    println("📂 Screenshot directory: ${SCREENSHOT_DIR.absolutePath}\n")

    SCREENSHOT_DIR.mkdirs()

    val results = SERVICES.map { service ->
        async(Dispatchers.IO) {
            testServiceWithOwnBrowser(service)
        }
    }.awaitAll()

    generateReport(results)

    println("\n✅ All tests completed!")
}

main()

/**
 * Light "post-login" settle:
 * - Try to detect typical logged-in/app-shell elements.
 * - Fallback to a very short wait if nothing obvious appears.
 */
fun waitForPostLoginContent(page: Page, serviceName: String) {
    val candidates = listOf(
        "text=Logout",
        "text=Log out",
        "text=Sign out",
        "a[href*='logout']",
        "button:has-text('Logout')",
        "button:has-text('Log out')",
        "nav",
        "main"
    )

    for (selector in candidates) {
        try {
            page.waitForSelector(
                selector,
                Page.WaitForSelectorOptions().setTimeout(1_500.0)
            )
            return
        } catch (_: Exception) {
            // Try next
        }
    }

    // If nothing matched, tiny fallback pause
    try {
        page.waitForTimeout(500.0)
    } catch (_: Exception) {
    }
}

/**
 * Retry helper.
 */
fun withRetries(times: Int = 3, pauseMs: Double = 200.0, block: () -> Boolean): Boolean {
    repeat(times) { attempt ->
        if (block()) return true
        try { Thread.sleep(pauseMs.toLong()) } catch (_: Exception) {}
    }
    return false
}

/**
 * Read current value of an input.
 */
fun getInputValue(locator: Locator): String? {
    return try {
        locator.inputValue()
    } catch (_: Exception) {
        try {
            locator.evaluate("el => el && 'value' in el ? el.value : null") as? String
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Robustly fill an input:
 * - Scrolls into view
 * - Focus/click
 * - Clears current value
 * - Types with small delay (to trigger listeners)
 * - Verifies value
 * - Falls back to JS set + input/change events if needed
 */
fun robustFillInput(page: Page, locator: Locator, value: String, label: String = "field"): Boolean {
    return withRetries(times = 3) {
        try {
            try { locator.scrollIntoViewIfNeeded() } catch (_: Exception) {}
            try { locator.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(ELEMENT_WAIT_MS)) } catch (_: Exception) {}

            // Focus/click
            try { locator.click(Locator.ClickOptions().setTimeout(1_500.0)) } catch (_: Exception) {}

            // Clear current value
            try { locator.fill("") } catch (_: Exception) {}
            try {
                locator.type(value, Locator.TypeOptions().setDelay(25.0))
            } catch (_: Exception) {
                try { locator.fill(value) } catch (_: Exception) {}
            }

            // Verify and fallback if needed
            val current = getInputValue(locator)
            if (current == value) return@withRetries true

            // Fallback via JS direct set + events
            val escaped = value.replace("'", "\\'")
            try {
                locator.evaluate(
                    "el => { " +
                            "el.value = '$escaped'; " +
                            "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                            "el.dispatchEvent(new Event('change', { bubbles: true })); " +
                            "}"
                )
            } catch (_: Exception) {
            }

            val after = getInputValue(locator)
            if (after == value) return@withRetries true

            false
        } catch (e: Exception) {
            println("  ⚠️  Failed to fill $label: ${e.message}")
            false
        }
    }.also { ok ->
        if (!ok) println("  ❌ Could not reliably fill $label")
    }
}

// ---- Authelia ----
