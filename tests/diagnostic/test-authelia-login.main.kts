#!/usr/bin/env kotlin
@file:DependsOn("com.microsoft.playwright:playwright:1.56.0")

/**
 * Kotlin/Playwright diagnostic: Test Authelia login and take screenshots.
 *
 * Migrated from Python to use Java Playwright bindings.
 *
 * Hardcoded Authelia selectors:
 * - Username: #username-textfield
 * - Password: #password-textfield
 * - Submit:   #sign-in-button
 */
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import java.io.IO.println
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale

data class TestResult(
  val success: Boolean,
  val finalUrl: String? = null,
  val loginDetected: Boolean? = null,
  val screenshotPath: String? = null,
  val screenshotSizeKb: Double? = null,
  val elapsedSeconds: Double,
  val error: String? = null,
)

fun getenv(name: String, def: String? = null): String = System.getenv(name) ?: def ?: ""

fun nowSeconds(): Long = Instant.now().epochSecond

fun testAutheliaLogin(url: String, username: String, password: String, serviceName: String? = null): TestResult {
  println("🔐 Testing login for: $url")

  val start = System.nanoTime()

  Playwright.create().use { pw ->
    val browser = pw.firefox().launch(
      BrowserType.LaunchOptions()
        .setHeadless(true)
    )
    browser.use {
      val context = browser.newContext(
        Browser.NewContextOptions().setIgnoreHTTPSErrors(true)
      )
      context.use {
        val page = context.newPage()
        try {
          println("  → Navigating to $url")
          page.navigate(url, Page.NavigateOptions().setTimeout(30_000.0))
          page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(30_000.0))

          val usernameInput = page.querySelector("#username-textfield")
          val loginDetected = usernameInput != null

          if (loginDetected) {
            println("  ✓ Authelia login page detected")
            println("  → Filling username: $username")
            page.fill("#username-textfield", username)
            println("  → Filling password: ${"*".repeat(password.length)}")
            page.fill("#password-textfield", password)
            println("  → Clicking sign-in button")
            page.click("#sign-in-button")
            try {
              page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(30_000.0))
              println("  ✓ Login completed")
            } catch (te: com.microsoft.playwright.TimeoutError) {
              println("  ⚠ Timeout waiting for post-login navigation")
            }
          } else {
            println("  ✓ Already authenticated (no login page)")
          }

          val finalUrl = page.url()
          println("  → Final URL: $finalUrl")

          val screenshotPath = "/tmp/${serviceName ?: "test"}-login-${nowSeconds()}.png"
          page.screenshot(Page.ScreenshotOptions().setPath(Paths.get(screenshotPath)))
          val sizeKb = java.nio.file.Files.size(Paths.get(screenshotPath)).toDouble() / 1024.0

          val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
          println("  ✓ Screenshot saved: $screenshotPath (" + String.format(Locale.US, "%.1f", sizeKb) + "KB)")
          println("  ⏱ Total time: " + String.format(Locale.US, "%.2f", elapsed) + "s")

          return TestResult(
            success = true,
            finalUrl = finalUrl,
            loginDetected = loginDetected,
            screenshotPath = screenshotPath,
            screenshotSizeKb = sizeKb,
            elapsedSeconds = elapsed,
          )
        } catch (e: Exception) {
          val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
          println("  ✗ Error: ${e.message}")
          println("  ⏱ Failed after: " + String.format(Locale.US, "%.2f", elapsed) + "s")
          return TestResult(
            success = false,
            error = e.message,
            elapsedSeconds = elapsed,
          )
        }
      }
    }
  }
}

fun main() {
  // Default test set mirroring the Python script
  val testServices = listOf(
    "https://grafana.project-saturn.com" to "grafana",
    "https://planka.project-saturn.com" to "planka",
    "https://outline.project-saturn.com" to "outline",
  )

  val username = getenv("AUTHELIA_USERNAME", "admin")
  val password = getenv("AUTHELIA_PASSWORD", "password")

  println("\n" + "=".repeat(60))
  println("Authelia Login Test (Kotlin/Playwright)")
  println("Username: $username")
  println("" + "=".repeat(60) + "\n")

  val results = mutableListOf<Pair<String, TestResult>>()
  for ((url, service) in testServices) {
    val r = testAutheliaLogin(url, username, password, service)
    results += service to r
    println()
  }

  println("\n" + "=".repeat(60))
  println("Summary")
  println("" + "=".repeat(60))
  val successful = results.count { it.second.success }
  println("✅ Successful: $successful/${results.size}")
  for ((service, r) in results) {
    val status = if (r.success) "✅" else "✗"
    if (r.success) {
      println("  $status $service: ${r.finalUrl}")
    } else {
      println("  $status $service: ${r.error}")
    }
  }

  if (successful != results.size) {
    kotlin.system.exitProcess(1)
  }
}

main()
