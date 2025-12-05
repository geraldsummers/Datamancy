#!/usr/bin/env kotlin

@file:DependsOn("com.microsoft.playwright:playwright:1.40.0")
@file:DependsOn("com.google.code.gson:gson:2.10.1")

import com.google.gson.GsonBuilder
import com.microsoft.playwright.*
import com.microsoft.playwright.options.WaitForSelectorState
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Comprehensive UI testing with Playwright - Login automation for all services
 */

fun runCmd(vararg cmd: String): CommandResult {
    val process = ProcessBuilder(*cmd).start()
    val output = process.inputStream.bufferedReader().readText()
    val error = process.errorStream.bufferedReader().readText()
    process.waitFor()
    return CommandResult(output, error, process.exitValue())
}

data class CommandResult(val out: String, val err: String, val exitCode: Int)

// Configuration
val SCRIPT_DIR = runCmd("bash", "-c", "cd \$(dirname \${BASH_SOURCE[0]:-\${0}}) && pwd").out.trim().let { Path.of(it) }
val DOMAIN = "project-saturn.com"
val ADMIN_USER = "admin"
val ADMIN_PASSWORD = "dKnoXMO7y-MJR6YHl22NQtFmsf3GR2tV"
val SCREENSHOT_DIR = SCRIPT_DIR.resolve("screenshots").toFile()

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

// UI Services to test
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
        loginType = "authelia"
    ),
    ServiceConfig(
        name = "Mailu Admin",
        url = "https://mail.$DOMAIN/admin",
        loginType = "direct",
        usernameSelectors = listOf("input[name='email']"),
        passwordSelectors = listOf("input[name='pw']", "input[name='password']"),
        submitSelectors = listOf("button[type='submit']")
    ),
    ServiceConfig(
        name = "Roundcube",
        url = "https://mail.$DOMAIN/webmail",
        loginType = "direct",
        usernameSelectors = listOf("input[name='_user']", "#rcmloginuser"),
        passwordSelectors = listOf("input[name='_pass']", "#rcmloginpwd"),
        submitSelectors = listOf("button[type='submit']", "#rcmloginsubmit")
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

fun saveScreenshot(page: Page, serviceName: String, step: String, result: TestResult): String? {
    return try {
        val safeName = serviceName.replace(" ", "_").replace("/", "_")
        val filename = "${safeName}_${step}.png"
        val filepath = File(SCREENSHOT_DIR, filename).absolutePath

        page.screenshot(Page.ScreenshotOptions().setPath(Paths.get(filepath)).setFullPage(true))
        println("  📸 Screenshot: $filename")

        // Save HTML/DOM dump alongside screenshot
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

fun findElement(page: Page, selectors: List<String>, timeout: Double = 5000.0): Pair<Locator?, String?> {
    for (selector in selectors) {
        try {
            val locator = page.locator(selector)
            locator.waitFor(Locator.WaitForOptions().setTimeout(timeout).setState(WaitForSelectorState.VISIBLE))
            if (locator.count() > 0) {
                return Pair(locator.first(), selector)
            }
        } catch (e: Exception) {
            continue
        }
    }
    return Pair(null, null)
}

fun handleAutheliaLogin(page: Page, serviceName: String, result: TestResult): Boolean {
    println("  🔐 Detected Authelia login redirect")

    return try {
        // Wait for Authelia login page
        page.waitForURL("**/auth.project-saturn.com/**", Page.WaitForURLOptions().setTimeout(10000.0))

        // Find username field
        val (usernameInput, usernameSel) = findElement(page, listOf("#username-textfield", "input[name='username']"))
        if (usernameInput == null) {
            result.error = "Authelia username field not found"
            saveScreenshot(page, serviceName, "error_authelia", result)
            return false
        }

        // Find password field
        val (passwordInput, passwordSel) = findElement(page, listOf("#password-textfield", "input[name='password']"))
        if (passwordInput == null) {
            result.error = "Authelia password field not found"
            saveScreenshot(page, serviceName, "error_authelia", result)
            return false
        }

        println("  ✏️  Filling Authelia credentials")
        usernameInput.fill(ADMIN_USER)
        passwordInput.fill(ADMIN_PASSWORD)

        // Find and click sign in button
        val (submitButton, submitSel) = findElement(page, listOf("#sign-in-button", "button[type='submit']"))
        if (submitButton != null) {
            println("  🖱️  Clicking Authelia sign in")

            // Wait for navigation to complete - use response/navigation waiters
            val navigationPromise = page.waitForNavigation(Page.WaitForNavigationOptions()
                .setTimeout(15000.0)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE)) {
                submitButton.click()
            }

            // Check for OAuth consent screen
            val currentUrl = page.url()
            if (currentUrl.contains("auth.project-saturn.com") && page.content().contains("Consent Request")) {
                println("  🔐 OAuth consent screen detected, clicking Accept")
                val (acceptButton, _) = findElement(page, listOf("button:has-text('ACCEPT')", "button:has-text('Accept')"), 5000.0)
                if (acceptButton != null) {
                    page.waitForNavigation(Page.WaitForNavigationOptions()
                        .setTimeout(15000.0)
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE)) {
                        acceptButton.click()
                    }
                }
            }

            // Check if we're back at the service
            val finalUrl = page.url()
            if (!finalUrl.contains("auth.project-saturn.com")) {
                println("  ✅ Redirected back to service: $finalUrl")
                // Wait a bit more for the service to fully load
                try {
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        Page.WaitForLoadStateOptions().setTimeout(10000.0))
                } catch (e: Exception) {
                    // Continue
                }

                // Extra wait for SPA applications to initialize
                if (serviceName in listOf("Open WebUI", "Planka", "SOGo", "Vaultwarden")) {
                    println("  ⏱️  Extra wait for SPA initialization...")
                    try {
                        page.waitForTimeout(5000.0)
                    } catch (e: Exception) {
                        // Continue
                    }
                }

                return true
            } else {
                result.error = "Still on Authelia after login attempt"
                saveScreenshot(page, serviceName, "error_authelia", result)
                return false
            }
        } else {
            result.error = "Authelia submit button not found"
            saveScreenshot(page, serviceName, "error_authelia", result)
            return false
        }
    } catch (e: Exception) {
        result.error = "Authelia login error: ${e.message}"
        saveScreenshot(page, serviceName, "error_authelia", result)
        false
    }
}

fun testService(browser: Browser, service: ServiceConfig, resultsList: MutableList<TestResult>): TestResult {
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
        // Create browser context
        context = browser.newContext(Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setViewportSize(1920, 1080))
        val page = context.newPage()

        // Navigate to service
        println("  🌐 Navigating to $url")
        try {
            val response = page.navigate(url, Page.NavigateOptions()
                .setTimeout(30000.0)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED))
            result.accessible = response?.status()?.let { it < 400 } ?: false
            println("  📊 HTTP Status: ${response?.status() ?: "N/A"}")
        } catch (e: TimeoutError) {
            result.error = "Navigation timeout"
            println("  ❌ Navigation timeout")
            context?.close()
            resultsList.add(result)
            return result
        } catch (e: Exception) {
            result.error = "Navigation error: ${e.message}"
            println("  ❌ Navigation error: ${e.message}")
            context?.close()
            resultsList.add(result)
            return result
        }

        // Wait for page to stabilize
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                Page.WaitForLoadStateOptions().setTimeout(5000.0))
        } catch (e: Exception) {
            // Continue even if networkidle times out
        }

        // Check if we need to login
        if (!service.loginRequired) {
            println("  ℹ️  No login required")
            result.loginSuccessful = true
            result.finalUrl = page.url()
            saveScreenshot(page, serviceName, "logged_in", result)
            context?.close()
            resultsList.add(result)
            return result
        }

        result.loginAttempted = true

        // Check for SSO button on services that support it
        val ssoButtonSelectors = listOf(
            "button:has-text('Log in with SSO')",
            "button:has-text('Use single sign-on')",
            "a:has-text('Log in with SSO')",
            "a:has-text('Use single sign-on')"
        )

        val (ssoButton, ssoSel) = findElement(page, ssoButtonSelectors, 3000.0)
        if (ssoButton != null) {
            println("  🔑 Found SSO button, clicking: $ssoSel")
            try {
                page.waitForNavigation(Page.WaitForNavigationOptions()
                    .setTimeout(10000.0)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE)) {
                    ssoButton.click()
                }
            } catch (e: Exception) {
                // Continue even if navigation times out
            }
        }

        // Check if redirected to Authelia (or if service expects it)
        val currentUrl = page.url()
        if ((currentUrl.contains("auth.project-saturn.com") || service.loginType == "authelia") && service.loginType == "authelia") {
            // If not yet on Authelia, wait a bit longer for redirect
            if (!currentUrl.contains("auth.project-saturn.com")) {
                try {
                    page.waitForURL("**/auth.project-saturn.com/**", Page.WaitForURLOptions().setTimeout(5000.0))
                } catch (e: Exception) {
                    // Didn't redirect to Authelia, treat as direct login
                    println("  ℹ️  Expected Authelia redirect but didn't happen, trying direct login")
                }
            }

            // Check again after waiting
            if (page.url().contains("auth.project-saturn.com")) {
                val success = handleAutheliaLogin(page, serviceName, result)
                if (success) {
                    result.loginSuccessful = true
                    result.finalUrl = page.url()

                    // Wait for content to actually load (not just login screens)
                    println("  ⏱️  Waiting for application content to load...")
                    try {
                        page.waitForTimeout(5000.0)
                        // Try to detect if we're still on a login/setup screen
                        val pageContent = page.content().lowercase()
                        if (pageContent.contains("log in") || pageContent.contains("login") ||
                            pageContent.contains("sign in") || pageContent.contains("create your")) {
                            println("  ⚠️  Still appears to be on login/setup screen")
                        }
                    } catch (e: Exception) {
                        // Continue
                    }

                    saveScreenshot(page, serviceName, "logged_in", result)
                }
                context?.close()
                resultsList.add(result)
                return result
            }
        }

        // Direct login (not Authelia)
        println("  🔑 Attempting direct login")

        // Find login form elements
        val (usernameInput, usernameSel) = findElement(page, service.usernameSelectors)
        if (usernameInput == null) {
            result.error = "Username field not found"
            println("  ❌ Username field not found")
            saveScreenshot(page, serviceName, "error_no_username", result)
            context?.close()
            resultsList.add(result)
            return result
        }

        val (passwordInput, passwordSel) = findElement(page, service.passwordSelectors)
        if (passwordInput == null) {
            result.error = "Password field not found"
            println("  ❌ Password field not found")
            saveScreenshot(page, serviceName, "error_no_password", result)
            context?.close()
            resultsList.add(result)
            return result
        }

        println("  ✏️  Filling credentials")
        println("     Username selector: $usernameSel")
        println("     Password selector: $passwordSel")

        usernameInput.fill(ADMIN_USER)
        passwordInput.fill(ADMIN_PASSWORD)

        // Find and click submit
        val (submitButton, submitSel) = findElement(page, service.submitSelectors)
        if (submitButton != null) {
            println("  🖱️  Clicking submit button: $submitSel")

            // Wait for navigation to complete
            try {
                page.waitForNavigation(Page.WaitForNavigationOptions()
                    .setTimeout(15000.0)
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE)) {
                    submitButton.click()
                }
            } catch (e: Exception) {
                // Continue even if navigation times out
            }

            // Check if login was successful
            val finalUrl = page.url()
            result.finalUrl = finalUrl

            // Heuristics to determine success
            when {
                finalUrl != url && !finalUrl.contains("login", ignoreCase = true) && !finalUrl.contains("sign-in", ignoreCase = true) -> {
                    result.loginSuccessful = true
                    println("  ✅ Login successful (URL: $finalUrl)")
                    saveScreenshot(page, serviceName, "logged_in", result)
                }
                finalUrl.contains("login", ignoreCase = true) || finalUrl.contains("sign-in", ignoreCase = true) -> {
                    result.loginSuccessful = false
                    result.error = "Still on login page after submit"
                    println("  ⚠️  Still on login page")
                    saveScreenshot(page, serviceName, "error_login_failed", result)
                }
                else -> {
                    // Assume success if no obvious error
                    result.loginSuccessful = true
                    println("  ✅ Login appears successful")
                    saveScreenshot(page, serviceName, "logged_in", result)
                }
            }
        } else {
            result.error = "Submit button not found"
            println("  ❌ Submit button not found")
            saveScreenshot(page, serviceName, "error_no_submit", result)
        }

        context?.close()

    } catch (e: Exception) {
        result.error = "Unexpected error: ${e.message}"
        println("  ❌ Unexpected error: ${e.message}")
        try {
            context?.close()
        } catch (ignored: Exception) {
        }
    }

    resultsList.add(result)
    return result
}

fun generateReport(results: List<TestResult>) {
    println("\n${"=".repeat(80)}")
    println("DATAMANCY UI SERVICES - COMPREHENSIVE TEST REPORT")
    println("=".repeat(80))
    println("Test Date: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
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

    // Save JSON report
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

fun main() {
    println("🚀 Starting Datamancy UI Services Comprehensive Test (Kotlin)")
    println("📂 Screenshot directory: ${SCREENSHOT_DIR.absolutePath}\n")

    // Ensure screenshot directory exists
    SCREENSHOT_DIR.mkdirs()

    val results = mutableListOf<TestResult>()

    Playwright.create().use { playwright ->
        println("🌐 Launching Chromium browser...")
        val browser = playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )

        for (service in SERVICES) {
            testService(browser, service, results)
        }

        browser.close()
    }

    generateReport(results)

    println("\n✅ All tests completed!")
}

main()
