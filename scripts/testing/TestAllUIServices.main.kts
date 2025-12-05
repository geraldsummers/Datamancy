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

// ---- Tunables ----

 val NAV_TIMEOUT_MS = 15_000.0
 val SHORT_NAV_TIMEOUT_MS = 8_000.0
 val ELEMENT_WAIT_MS = 2_000.0
 val ELEMENT_WAIT_SHORT_MS = 1_000.0

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
    val loginType: String = "direct",
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
        loginType = "authelia",
        usernameSelectors = listOf("input[name='user']", "input[name='username']"),
        passwordSelectors = listOf("input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Open WebUI",
        url = "https://open-webui.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[name='email']", "input[type='email']"),
        passwordSelectors = listOf("input[name='password']", "input[type='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Vaultwarden",
        url = "https://vaultwarden.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Planka",
        url = "https://planka.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Bookstack",
        url = "https://bookstack.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[name='username']"),
        passwordSelectors = listOf("input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Seafile",
        url = "https://seafile.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[name='login']"),
        passwordSelectors = listOf("input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "OnlyOffice",
        url = "https://onlyoffice.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[type='email']"),
        passwordSelectors = listOf("input[type='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "JupyterHub",
        url = "https://jupyterhub.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Homepage",
        url = "https://homepage.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "SOGo",
        url = "https://sogo.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Home Assistant",
        url = "https://homeassistant.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[name='username']"),
        passwordSelectors = listOf("input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Kopia",
        url = "https://kopia.$DOMAIN",
        loginRequired = false,  // HTTP Basic Auth is handled at the browser level
        loginType = "http-basic"
    ),
    // Mailu services: not using Authelia, skip automated login
    ServiceConfig(
        name = "Mailu Admin",
        url = "https://mail.$DOMAIN/admin",
        loginRequired = false,
        loginType = "mailu-internal"
    ),
    ServiceConfig(
        name = "Roundcube",
        url = "https://mail.$DOMAIN/webmail",
        loginRequired = false,
        loginType = "mailu-internal"
    ),
    ServiceConfig(
        name = "LDAP Account Manager",
        url = "https://lam.$DOMAIN",
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Dockge",
        url = "https://dockge.$DOMAIN",
        loginType = "authelia",
        usernameSelectors = listOf("input[name='username']"),
        passwordSelectors = listOf("input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Mastodon",
        url = "https://mastodon.$DOMAIN",
        loginRequired = false,
        loginType = "optional"
    )
)

// ---- Helpers ----

fun saveScreenshot(page: Page, serviceName: String, step: String, result: TestResult): String? {
    return try {
        val safeName = serviceName.replace(" ", "_").replace("/", "_")
        val filename = "${safeName}_${step}.png"
        val filepath = File(SCREENSHOT_DIR, filename).absolutePath

        runBlocking { delay(500) }

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

        // OAuth consent screen (if any)
        val currentUrl = page.url()
        if (currentUrl.contains("auth.project-saturn.com") &&
            page.content().contains("Consent Request")
        ) {
            println("  🔐 OAuth consent screen detected, clicking Accept")
            val (acceptButton, _) = findElement(
                page,
                listOf("button:has-text('ACCEPT')", "button:has-text('Accept')"),
                timeout = ELEMENT_WAIT_MS
            )
            if (acceptButton != null) {
                try {
                    page.waitForNavigation(
                        Page.WaitForNavigationOptions()
                            .setTimeout(NAV_TIMEOUT_MS)
                    ) {
                        acceptButton.click()
                    }
                } catch (_: Exception) {
                }
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

            if (serviceName in listOf("Open WebUI", "Planka", "SOGo", "Vaultwarden")) {
                try {
                    page.waitForTimeout(100.0)
                } catch (_: Exception) {
                }
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

        // Mailu / Roundcube Sign in link (informational)
        val signInLinkSelectors = listOf(
            "a[href='/sso/login']",
            "a:has-text('Sign in')"
        )
        val (signInLink, _) = findElement(
            page,
            signInLinkSelectors,
            timeout = ELEMENT_WAIT_SHORT_MS
        )
        if (signInLink != null && page.url().contains("mail.project-saturn.com")) {
            println("  🔑 Found Sign in link, clicking")
            try {
                page.waitForNavigation(
                    Page.WaitForNavigationOptions()
                        .setTimeout(SHORT_NAV_TIMEOUT_MS)
                ) {
                    signInLink.click()
                }
            } catch (_: Exception) {
            }
        }

        // SSO/OIDC button before Authelia redirect
        val ssoButtonSelectorsInitial = listOf(
            "button.vw-sso-login",
            ".vw-sso-login",
            "button:has-text('Log in with SSO')",
            "button:has-text('Use single sign-on')",
            "a:has-text('Log in with SSO')"
        )

        var ssoButtonInitial: Locator? = null
        var ssoSelInitial: String? = null

        val (visibleButton, visibleSel) = findElement(
            page,
            ssoButtonSelectorsInitial,
            timeout = ELEMENT_WAIT_SHORT_MS,
            requireVisible = true
        )
        if (visibleButton != null) {
            ssoButtonInitial = visibleButton
            ssoSelInitial = visibleSel
        } else {
            val (attachedButton, attachedSel) = findElement(
                page,
                ssoButtonSelectorsInitial,
                timeout = ELEMENT_WAIT_SHORT_MS,
                requireVisible = false
            )
            if (attachedButton != null) {
                ssoButtonInitial = attachedButton
                ssoSelInitial = attachedSel
                println("  ℹ️  Found SSO button (not visible), will try JS click")
            }
        }

        if (ssoButtonInitial != null && ssoSelInitial != null) {
            println("  🔑 Found SSO button on initial page, clicking: $ssoSelInitial")
            try {
                val isVisible = try {
                    ssoButtonInitial.isVisible()
                } catch (_: Exception) {
                    false
                }

                if (!isVisible) {
                    page.evaluate("document.querySelector('${ssoSelInitial}').click()")
                    try {
                        page.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.LOAD,
                            Page.WaitForLoadStateOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
                        )
                    } catch (_: Exception) {
                    }
                } else {
                    page.waitForNavigation(
                        Page.WaitForNavigationOptions()
                            .setTimeout(SHORT_NAV_TIMEOUT_MS)
                    ) {
                        ssoButtonInitial.click()
                    }
                }
            } catch (e: Exception) {
                println("  ⚠️  SSO button navigation failed or timed out: ${e.message}")
            }
        }

        // Authelia handling if expected
        var currentUrl = page.url()
        if ((currentUrl.contains("auth.project-saturn.com") || service.loginType == "authelia") &&
            service.loginType == "authelia"
        ) {
            if (!currentUrl.contains("auth.project-saturn.com")) {
                try {
                    page.waitForURL(
                        "**/auth.project-saturn.com/**",
                        Page.WaitForURLOptions().setTimeout(SHORT_NAV_TIMEOUT_MS)
                    )
                } catch (_: Exception) {
                    println("  ℹ️  Expected Authelia redirect but didn't happen, trying direct login")
                }
            }

            if (page.url().contains("auth.project-saturn.com")) {
                val success = handleAutheliaLogin(page, serviceName, result)
                if (success) {
                    // Some apps (e.g. Bookstack) show OIDC button after Authelia
                    val oidcButtonSelectors = listOf(
                        "button#oidc-login",
                        "button:has-text('Login with Authelia')",
                        "button:has-text('Use single sign-on')"
                    )

                    val (oidcButton, _) = findElement(
                        page,
                        oidcButtonSelectors,
                        timeout = ELEMENT_WAIT_SHORT_MS
                    )
                    if (oidcButton != null && page.url().contains("/login")) {
                        println("  🔑 Clicking OIDC/SSO button after Authelia")
                        try {
                            page.waitForNavigation(
                                Page.WaitForNavigationOptions()
                                    .setTimeout(NAV_TIMEOUT_MS)
                            ) {
                                oidcButton.click()
                            }
                        } catch (_: Exception) {
                        }
                    }

                    result.loginSuccessful = true
                    result.finalUrl = page.url()

                    waitForPostLoginContent(page, serviceName)

                    // Optional logged-in check
                    val contentLower = page.content().lowercase()
                    val loggedInIndicators = listOf(
                        "logout", "log out", "sign out",
                        "settings", "profile", "account",
                        "dashboard", "welcome"
                    )
                    val hasLoggedInIndicator = loggedInIndicators.any { contentLower.contains(it) }

                    if (hasLoggedInIndicator) {
                        println("  ✅ Detected logged-in state")
                    }

                    saveScreenshot(page, serviceName, "logged_in", result)
                }

                return result
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
